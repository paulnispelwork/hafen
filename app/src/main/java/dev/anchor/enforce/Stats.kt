package dev.anchor.enforce

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of phone use. Times in milliseconds. */
@Serializable
data class DayStats(
    val day: String,
    val total: Long = 0,
    val apps: Map<String, Long> = emptyMap(),
    val hours: List<Long> = List(24) { 0L },
    val unlocks: Int = 0,
    val attempts: Int = 0,
    val nudges: Int = 0,
    val nudgesHeeded: Int = 0,
)

/**
 * Usage history. Android keeps detailed usage events for only about a week, so every day
 * Hafen computes is archived in its own file and kept for good.
 */
object Stats {
    private val json = Json { ignoreUnknownKeys = true }
    private const val RECOMPUTE_DAYS = 9L

    /** Called from the regular enforcement round: archive a few times a day, even if Stats is never opened. */
    fun archiveIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("archivedAt", 0) < 6 * 3_600_000L) return
        runCatching { history(ctx, 1) }
        prefs.edit().putLong("archivedAt", now).apply()
    }

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "stats.json")

    private fun load(ctx: Context): MutableMap<String, DayStats> =
        runCatching { json.decodeFromString<List<DayStats>>(file(ctx).readText()) }.getOrDefault(emptyList())
            .associateBy { it.day }.toMutableMap()

    /** The last [days] days (oldest first), today included. Recomputes the recent ones from Android's events. */
    @Synchronized
    fun history(ctx: Context, days: Int): List<DayStats> {
        val archive = load(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        if (hasAccess(ctx)) {
            fresh(ctx, today.minusDays(RECOMPUTE_DAYS - 1), zone).forEach { (d, s) ->
                // keep an archived day if Android has already forgotten most of it
                val old = archive[d]
                if (old == null || s.total >= old.total || d == today.toString()) archive[d] = s
            }
        }
        // our own counters (unlocks, blocked attempts, nudges) are the truth for those numbers
        for (i in 0 until RECOMPUTE_DAYS) {
            val d = today.minusDays(i)
            var base = archive[d.toString()] ?: DayStats(d.toString())
            if (base.total == 0L) Usage.dayApps(d).takeIf { it.isNotEmpty() }?.let { own ->
                base = base.copy(total = own.values.sum(), apps = own) // no Android history: what the watcher measured
            }
            archive[d.toString()] = base.copy(
                unlocks = maxOf(base.unlocks, Usage.count("unlock", d)),
                attempts = maxOf(base.attempts, Usage.count("attempt", d)),
                nudges = maxOf(base.nudges, Usage.count("nudge", d)),
                nudgesHeeded = maxOf(base.nudgesHeeded, Usage.count("nudgeHeeded", d)),
            )
        }
        runCatching { file(ctx).writeText(json.encodeToString(archive.values.sortedBy { it.day })) }
        return (days - 1 downTo 0).map { i -> today.minusDays(i.toLong()).toString().let { archive[it] ?: DayStats(it) } }
    }

    /** Foreground time per app, per hour, and unlocks, from Android's usage events. */
    private fun fresh(ctx: Context, from: LocalDate, zone: ZoneId): Map<String, DayStats> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        // count real apps only: no system screens, no launchers (home is not "using the phone")
        val homes = ctx.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
        ).map { it.activityInfo.packageName }.toSet()
        val real = Apps.launchable(ctx).map { it.pkg }.toSet() - homes - ctx.packageName
        val skip = setOf(ctx.packageName, "com.android.systemui", "android")

        val apps = HashMap<String, HashMap<String, Long>>()   // day -> pkg -> ms
        val hours = HashMap<String, LongArray>()               // day -> 24 buckets (screen time, overlaps counted once)
        val totals = HashMap<String, Long>()
        val unlocks = HashMap<String, Int>()
        val open = HashMap<String, Pair<String, Long>>()        // pkg/class -> (pkg, since)

        /** Splits [a, b) into hour pieces. */
        fun pieces(a: Long, b: Long, f: (day: String, hour: Int, ms: Long) -> Unit) {
            var t = a
            while (t < b) {
                val dt = Instant.ofEpochMilli(t).atZone(zone)
                val e = minOf(b, dt.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli())
                f(dt.toLocalDate().toString(), dt.hour, e - t)
                t = e
            }
        }
        fun add(pkg: String, a: Long, b: Long) {
            if (b > a) pieces(a, b) { d, _, ms -> apps.getOrPut(d) { HashMap() }.merge(pkg, ms, Long::plus) }
        }
        // screen time = time at least one real app was in front (split screen counts once)
        var inFront = 0; var since = 0L
        fun up(t: Long) { if (inFront++ == 0) since = t }
        fun down(t: Long) {
            if (inFront > 0 && --inFront == 0 && t > since) pieces(since, t) { d, h, ms -> totals.merge(d, ms, Long::plus); hours.getOrPut(d) { LongArray(24) }[h] += ms }
        }
        fun closeAll(at: Long) { open.values.forEach { (p, s) -> add(p, s, at); down(at) }; open.clear(); inFront = 0 }

        val ev = UsageEvents.Event()
        val events = usm.queryEvents(start, now)
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val key = ev.packageName + "/" + ev.className
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (ev.packageName !in skip && ev.packageName in real && key !in open) {
                    open[key] = ev.packageName to ev.timeStamp; up(ev.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(key)?.let { (p, s) -> add(p, s, ev.timeStamp); down(ev.timeStamp) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> closeAll(ev.timeStamp)
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    val d = Instant.ofEpochMilli(ev.timeStamp).atZone(zone).toLocalDate().toString()
                    unlocks.merge(d, 1, Int::plus)
                }
            }
        }
        closeAll(now) // whatever is open right now counts until now

        return (apps.keys + unlocks.keys).associateWith { d ->
            val a = apps[d].orEmpty()
            DayStats(d, totals[d] ?: 0L, a, (hours[d] ?: LongArray(24)).toList(), unlocks[d] ?: 0)
        }
    }
}

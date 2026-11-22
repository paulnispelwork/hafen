package dev.anchor.enforce

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Per-day usage seconds, opens, and gate sessions. Lives only on the phone. */
object Usage {
    private lateinit var prefs: SharedPreferences
    private lateinit var logFile: File

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)
        logFile = File(ctx.applicationContext.filesDir, "reasons.log")
    }

    private fun day() = LocalDate.now().toString()

    fun millis(pkg: String) = prefs.getLong("ms:${day()}:$pkg", 0)
    fun seconds(pkg: String) = millis(pkg) / 1000
    fun addMillis(pkg: String, ms: Long) {
        if (ms <= 0) return
        val k = "ms:${day()}:$pkg"
        prefs.edit().putLong(k, prefs.getLong(k, 0) + ms).apply()
    }

    fun opens(pkg: String) = prefs.getInt("op:${day()}:$pkg", 0)
    fun addOpen(pkg: String) {
        val k = "op:${day()}:$pkg"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    /** Daily counters: "unlock", "attempt" (blocked app/site), "nudge", "nudgeHeeded". */
    fun count(name: String, day: LocalDate = LocalDate.now()) = prefs.getInt("ct:$name:$day", 0)
    fun bump(name: String) {
        val k = "ct:$name:${day()}"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    /** "Open anyway" on the evening nudge: no nudge for this app for a while. */
    fun nudgePass(pkg: String, minutes: Int) = prefs.edit().putLong("npass:$pkg", System.currentTimeMillis() + minutes * 60_000L).apply()
    fun hasNudgePass(pkg: String) = System.currentTimeMillis() < prefs.getLong("npass:$pkg", 0)

    fun sessionUntil(pkg: String) = prefs.getLong("session:$pkg", 0)
    fun hasSession(pkg: String) = System.currentTimeMillis() < sessionUntil(pkg)
    fun startSession(pkg: String, minutes: Int) {
        prefs.edit().putLong("session:$pkg", System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    /** Hafen's own per-app time for a day (kept a week): the fallback when Android's history isn't allowed. */
    fun dayApps(day: LocalDate): Map<String, Long> {
        val prefix = "ms:$day:"
        return prefs.all.filterKeys { it.startsWith(prefix) }.map { it.key.removePrefix(prefix) to (it.value as Long) }.toMap()
    }

    /** Today's usage per package, biggest first. */
    fun today(): List<Pair<String, Long>> {
        val prefix = "ms:${day()}:"
        return prefs.all.filterKeys { it.startsWith(prefix) }
            .map { it.key.removePrefix(prefix) to (it.value as Long) }
            .sortedByDescending { it.second }
    }

    fun logReason(pkg: String, reason: String) {
        if (reason.isBlank()) return
        runCatching { logFile.appendText("${LocalDateTime.now()}\t$pkg\t${reason.replace('\n', ' ')}\n") }
    }

    fun reasons(limit: Int = 30): List<String> =
        runCatching { logFile.readLines().takeLast(limit).reversed() }.getOrDefault(emptyList())

    /** Drop usage keys older than a week. */
    fun prune() {
        val cutoff = LocalDate.now().minusDays(7).toString()
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("ms:") || it.startsWith("op:") }
            .filter { it.split(":").getOrNull(1)?.let { d -> d < cutoff } == true }
            .forEach { e.remove(it) }
        val longCutoff = LocalDate.now().minusDays(400).toString() // counters are tiny: keep a year+
        prefs.all.keys.filter { it.startsWith("ct:") && it.substringAfterLast(':') < longCutoff }.forEach { e.remove(it) }
        e.apply()
    }
}

package dev.anchor.enforce

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.anchor.policy.AppRule
import dev.anchor.policy.Mode
import dev.anchor.policy.Policy
import dev.anchor.policy.Schedule
import java.time.LocalDateTime

object Rules {
    /** Never suspended, never gated: calls, SMS, keyboard, system, Anchor itself. */
    fun essentials(ctx: Context): Set<String> = essentialsMemo.get(ctx)
    private val essentialsMemo = dev.anchor.policy.Memo(60_000) { computeEssentials(it) }

    private fun computeEssentials(ctx: Context): Set<String> {
        val s = mutableSetOf(
            ctx.packageName, "android", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
            "com.android.contacts", "com.google.android.contacts",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            // alarms must always ring
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
        )
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(s::add) }
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx)?.let(s::add) }
        runCatching { ctx.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { s.add(it.packageName) } }
        // the current home screen, or the phone has no way to navigate
        runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" }?.let(s::add)
        }
        return s
    }

    /** pkg -> reason, for every package under a running block. Recomputed by the Enforcer. */
    @Volatile var committed: Map<String, String> = emptyMap()

    /** Refreshed by the Enforcer: bedtime only switches off normal apps, never system screens. */
    @Volatile var hard: Set<String> = emptySet()
    @Volatile var launchable: Set<String> = emptySet()

    /** True while something is committed: then Anchor guards its own off-switches. */
    fun protecting(p: Policy, now: Long = dev.anchor.policy.TrustedTime.now()) =
        !p.released && (p.armed || p.lockedUntil > now || dev.anchor.policy.Targets.active(p, now).isNotEmpty())

    fun inBedtime(p: Policy, now: LocalDateTime = dev.anchor.policy.TrustedTime.localNow()) =
        p.bedtimeWindows.isNotEmpty() && Schedule.contains(p.bedtimeWindows, now)

    /** Why [pkg] is unavailable right now, or null if it may be used. */
    fun blockReason(p: Policy, pkg: String, now: LocalDateTime = dev.anchor.policy.TrustedTime.localNow()): String? {
        committed[pkg]?.let { return it }
        if (inBedtime(p, now) && pkg in launchable && pkg !in hard && pkg !in p.bedtimeAllowed && pkg !in p.essentials) return "bedtime"
        val r = p.apps[pkg] ?: return null
        return when (r.mode) {
            Mode.ALLOW -> null
            Mode.BLOCK -> "blocked"
            Mode.LIMIT -> when {
                r.windows.isNotEmpty() && !Schedule.contains(r.windows, now) -> "outside allowed hours"
                r.dailyBudgetMin > 0 && Usage.seconds(pkg) >= r.dailyBudgetMin * 60L -> "today's ${r.dailyBudgetMin} min are used up"
                r.maxOpensPerDay > 0 && Usage.opens(pkg) >= r.maxOpensPerDay && !Usage.hasSession(pkg) -> "no opens left today"
                else -> null
            }
        }
    }

    fun hasGate(r: AppRule?) = r != null && r.mode == Mode.LIMIT &&
        (r.waitSeconds > 0 || r.requireReason || r.maxOpensPerDay > 0)

    fun needsGate(p: Policy, pkg: String) = hasGate(p.apps[pkg]) && !Usage.hasSession(pkg)

    fun summary(r: AppRule?): String = when {
        r == null || r.mode == Mode.ALLOW -> "allowed"
        r.mode == Mode.BLOCK -> "BLOCKED"
        else -> buildList {
            if (r.windows.isNotEmpty()) add(r.windows.joinToString(", ") { Schedule.describe(it) })
            if (r.dailyBudgetMin > 0) add("${r.dailyBudgetMin}m/day")
            if (r.maxOpensPerDay > 0) add("${r.maxOpensPerDay} opens")
            if (r.waitSeconds > 0) add("${r.waitSeconds}s wait")
            if (r.requireReason) add("reason")
            if (hasGate(r)) add("${r.sessionMinutes}m sessions")
        }.joinToString(" · ").ifEmpty { "limit (no limits set)" }
    }
}

object Apps {
    data class Info(val pkg: String, val label: String)

    fun launchable(ctx: Context): List<Info> = launchableMemo.get(ctx)
    private val launchableMemo = dev.anchor.policy.Memo(60_000) { computeLaunchable(it) }

    private fun computeLaunchable(ctx: Context): List<Info> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .map { Info(it.activityInfo.packageName, it.loadLabel(pm).toString().trim().ifEmpty { it.activityInfo.packageName }) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}

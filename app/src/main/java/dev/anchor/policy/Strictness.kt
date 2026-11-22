package dev.anchor.policy

/**
 * Decides whether a proposed policy is looser than the current one in ANY dimension.
 * Looser changes go through the cooldown; changes that are equal or stricter apply at once.
 */
object Strictness {

    private fun widens(newAllowed: BooleanArray, oldAllowed: BooleanArray) =
        newAllowed.indices.any { newAllowed[it] && !oldAllowed[it] }

    /** a limit of 0 means "none" */
    private fun limitLooser(old: Int, new: Int) = old > 0 && (new == 0 || new > old)

    fun appLooser(o: AppRule?, n: AppRule?): Boolean {
        val old = o ?: AppRule(mode = Mode.ALLOW)
        val new = n ?: AppRule(mode = Mode.ALLOW)
        if (new.mode < old.mode) return true
        if (new.mode == Mode.BLOCK || old.mode == Mode.ALLOW) return false
        // both LIMIT
        if (widens(Schedule.coverage(new.windows, true), Schedule.coverage(old.windows, true))) return true
        if (limitLooser(old.dailyBudgetMin, new.dailyBudgetMin)) return true
        if (limitLooser(old.maxOpensPerDay, new.maxOpensPerDay)) return true
        if (new.waitSeconds < old.waitSeconds) return true
        if (old.requireReason && !new.requireReason) return true
        if (new.sessionMinutes > old.sessionMinutes) return true
        return false
    }

    private fun norm(d: String) = d.trim().lowercase().removePrefix("*.").removeSuffix(".")

    /** Active blocks that [n] would shorten or drop. These are refused outright, not queued. */
    fun brokenCommitments(o: Policy, n: Policy, now: Long = TrustedTime.now()): List<Commitment> =
        Targets.active(o, now).filter { oc ->
            val oldCover = Schedule.coverage(oc.windows, true)
            n.commitments.none { nc ->
                nc.target == oc.target && nc.until >= oc.until &&
                    !widens(oldCover, Schedule.coverage(nc.windows, true)) // every old blocked minute still blocked
            }
        }

    /** Human-readable list of what loosens; empty = not looser. */
    fun loosenings(o: Policy, n: Policy): List<String> {
        val r = mutableListOf<String>()
        if (o.armed && !n.armed) r += "disarm lock-in"
        (o.apps.keys + n.apps.keys).filter { appLooser(o.apps[it], n.apps[it]) }.let { if (it.isNotEmpty()) r += "app rules: ${it.joinToString()}" }
        if (!o.essentials.containsAll(n.essentials)) r += "more essential apps"
        if (o.blockNewApps && !n.blockNewApps) r += "stop blocking new apps"
        // bedtime: a minute covered before must still be covered
        if (widens(Schedule.coverage(o.bedtimeWindows, false), Schedule.coverage(n.bedtimeWindows, false))) r += "shorter bedtime"
        if (!o.bedtimeAllowed.containsAll(n.bedtimeAllowed)) r += "more apps allowed at bedtime"
        if (o.dnsFilterEnabled && !n.dnsFilterEnabled) r += "DNS filter off"
        if (o.vpnLockdown && !n.vpnLockdown) r += "VPN lockdown off"
        if (!n.blockedDomains.map(::norm).containsAll(o.blockedDomains.map(::norm))) r += "unblock websites"
        if (o.chromeHardening && !n.chromeHardening) r += "Chrome hardening off"
        if (o.blockShortForm && !n.blockShortForm) r += "allow Reels/Shorts"
        if (!n.shortFormViewIds.containsAll(o.shortFormViewIds)) r += "fewer Reels/Shorts detectors"
        if (o.launcherEnabled && !n.launcherEnabled) r += "launcher off"
        if (o.notifyFilter && !n.notifyFilter) r += "notification filter off"
        if (o.nudgeEnabled && !n.nudgeEnabled) r += "evening nudge off"
        if (widens(Schedule.coverage(n.inboxWindows, true), Schedule.coverage(o.inboxWindows, true))) r += "more time to read held notifications"
        if (o.nudgeEnabled && widens(Schedule.coverage(o.nudgeWindows, false), Schedule.coverage(n.nudgeWindows, false))) r += "shorter evening nudge"
        if (o.nudgeEnabled && n.nudgePassMinutes > o.nudgePassMinutes) r += "longer pass after the nudge"
        if (o.nudgeEnabled && n.nudgeApp != null && n.nudgeApp != o.nudgeApp) r += "new app exempt from the nudge"
        // favorites are just the home screen: adding one is never a loosening (user decision), even though they skip the nudge
        if (n.notifyFilter && o.notifyFilter && !o.notifyAllowed.containsAll(n.notifyAllowed)) r += "more apps may notify"
        if ((o.allAppsDelaySec < 0 && n.allAppsDelaySec >= 0) ||
            (o.allAppsDelaySec >= 0 && n.allAppsDelaySec in 0 until o.allAppsDelaySec)) r += "easier 'all apps'"
        if (o.grayscale && !n.grayscale) r += "grayscale off"
        if (widens(Schedule.coverage(o.grayscaleWindows, false), Schedule.coverage(n.grayscaleWindows, false))) r += "less grayscale time"
        if (!n.grayscaleApps.containsAll(o.grayscaleApps)) r += "fewer grayscale apps"
        if (o.lockedUntil > TrustedTime.now() && n.lockedUntil < o.lockedUntil) r += "shorter settings lock"
        o.restrictions.filter { it.value && n.restrictions[it.key] != true }.keys.let { if (it.isNotEmpty()) r += "lift restrictions: ${it.joinToString()}" }
        if (o.forceAutoTime && !n.forceAutoTime) r += "manual clock allowed"
        if (n.cooldownHours < o.cooldownHours) r += "shorter cooldown"
        if (n.partnerCode != o.partnerCode) r += if (o.partnerCode == null) "add partner code" else "change/remove partner code"
        if (!o.releaseAllowed && n.releaseAllowed) r += "allow removing Hafen"
        return r
    }

    fun isLooser(o: Policy, n: Policy) = loosenings(o, n).isNotEmpty()
}

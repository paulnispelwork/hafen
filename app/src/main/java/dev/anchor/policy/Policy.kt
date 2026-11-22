package dev.anchor.policy

import kotlinx.serialization.Serializable

/** ALLOW < LIMIT < BLOCK. Order matters: it is used to decide whether a change loosens a rule. */
@Serializable
enum class Mode { ALLOW, LIMIT, BLOCK }

@Serializable
data class TimeWindow(
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7), // ISO weekday, 1 = Monday
    val start: Int = 18 * 60,                        // minutes after midnight
    val end: Int = 20 * 60,                          // end <= start wraps past midnight
)

@Serializable
data class AppRule(
    val mode: Mode = Mode.LIMIT,
    /** LIMIT only: app is usable only inside these windows. Empty = any time. */
    val windows: List<TimeWindow> = emptyList(),
    val dailyBudgetMin: Int = 0,   // 0 = no budget
    val maxOpensPerDay: Int = 0,   // 0 = unlimited
    val waitSeconds: Int = 0,      // forced wait on the gate screen
    val requireReason: Boolean = false,
    val sessionMinutes: Int = 10,  // after passing the gate, how long until it asks again
)

/** A time-boxed block. While active it can only be extended, never shortened or removed. */
@Serializable
data class Commitment(
    val target: String,
    val until: Long,
    val createdAt: Long = 0,
    /** Empty = blocked all the time. Otherwise a daily rhythm: blocked only inside these windows. */
    val windows: List<TimeWindow> = emptyList(),
)

@Serializable
data class Policy(
    /** "no X until …" blocks; see Targets for what a target key covers. */
    val commitments: List<Commitment> = emptyList(),
    /** Apps you always need. Never hit by bedtime, game detection or the new-app block. */
    val essentials: List<String> = emptyList(),

    /** While false, every change applies immediately (setup mode). */
    val armed: Boolean = false,

    // apps
    val apps: Map<String, AppRule> = emptyMap(),
    val blockNewApps: Boolean = false,

    // bedtime: inside these windows every app except bedtimeAllowed (and essentials) is suspended
    val bedtimeWindows: List<TimeWindow> = emptyList(),
    val bedtimeAllowed: List<String> = emptyList(),

    // web
    val dnsFilterEnabled: Boolean = false, // VPN-based; off by default: only one VPN app can run at a time
    val vpnLockdown: Boolean = false,
    val blockedDomains: List<String> = DEFAULT_DOMAINS,
    val chromeHardening: Boolean = true,
    val blockShortForm: Boolean = true,
    val shortFormViewIds: List<String> = DEFAULT_SHORT_FORM_IDS,

    // home screen
    val launcherEnabled: Boolean = false,
    val launcherApps: List<String> = emptyList(),
    val allAppsDelaySec: Int = 0, // -1 = no "all apps" list at all; >0 = optional pause (old setting)

    // evening nudge: opening any non-essential app inside these times first suggests a book
    val nudgeEnabled: Boolean = false,
    val nudgeWindows: List<TimeWindow> = listOf(TimeWindow(days = (1..7).toSet(), start = 17 * 60, end = 0)),
    val nudgeText: String = "Read a book instead.",
    val nudgeApp: String? = null, // e.g. Audible: offered as the better option
    val nudgePassMinutes: Int = 10,

    // notification filter: only these apps (plus essentials, calls, SMS) may notify; blocked apps never may
    val notifyFilter: Boolean = false,
    val notifyAllowed: List<String> = emptyList(),
    /** Held notifications can only be read inside these times (max 3 a day). Empty = any time. */
    val inboxWindows: List<TimeWindow> = listOf(
        TimeWindow(days = (1..7).toSet(), start = 12 * 60, end = 13 * 60),
        TimeWindow(days = (1..7).toSet(), start = 20 * 60, end = 21 * 60),
    ),
    val grayscale: Boolean = false,                       // always gray
    val grayscaleWindows: List<TimeWindow> = emptyList(), // gray during these times
    val grayscaleApps: List<String> = emptyList(),        // gray while these apps are in front

    // system
    val restrictions: Map<String, Boolean> = DEFAULT_RESTRICTIONS,
    val forceAutoTime: Boolean = true,
    val blockedMessage: String = "Not now. You decided this with a clear head.",

    // lock-in
    val cooldownHours: Int = 24,
    /** Until this time nothing may be loosened at all (no cooldown, no partner code). */
    val lockedUntil: Long = 0,
    val partnerCode: String? = null, // "salt:sha256hex"
    val releaseAllowed: Boolean = false,
    /** Set by "Remove Hafen": nothing is enforced any more, ready to uninstall. */
    val released: Boolean = false,
)

val DEFAULT_DOMAINS = listOf(
    "tiktok.com", "tiktokv.com", "tiktokcdn.com", "musical.ly", "9gag.com",
    // DNS-over-HTTPS endpoints, so browsers can't route around the filter
    "dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "doh.opendns.com",
    "dns.quad9.net", "dns.nextdns.io", "doh.dns.sb", "dns.adguard-dns.com",
)

val DEFAULT_SHORT_FORM_IDS = listOf(
    "com.google.android.youtube:id/reel_recycler",
    "com.google.android.youtube:id/reel_player_page_container",
    "com.google.android.youtube:id/reel_watch_player",
    "com.instagram.android:id/clips_viewer_view_pager",
    "com.instagram.android:id/clips_viewer_container",
    "com.facebook.katana:id/reels_viewer_container",
    "com.snapchat.android:id/spotlight_container",
)

data class RestrictionInfo(val key: String, val title: String, val detail: String, val danger: Boolean = false)

val RESTRICTIONS = listOf(
    RestrictionInfo("no_factory_reset", "No factory reset", "Blocks reset from Settings (the escape hatch to wipe Hafen)."),
    RestrictionInfo("no_safe_boot", "No safe mode", "Safe mode would boot without Hafen."),
    RestrictionInfo("no_config_date_time", "No clock changes", "Stops moving the clock to skip schedules or cooldowns."),
    RestrictionInfo("no_config_vpn", "No VPN changes", "Stops switching off the DNS filter or adding another VPN."),
    RestrictionInfo("no_config_private_dns", "No Private DNS changes", "Stops bypassing the DNS filter."),
    RestrictionInfo("no_control_apps", "No force-stop / clear data", "Apps can't be force-stopped, disabled or have their data cleared."),
    RestrictionInfo("no_add_user", "No extra users", "A second user would have no rules."),
    RestrictionInfo("no_add_managed_profile", "No work profile", "A work profile would have no rules."),
    RestrictionInfo("no_add_clone_profile", "No app clones", "Cloned apps (dual apps) would have no rules."),
    RestrictionInfo("no_uninstall_apps", "No uninstalling apps", "Mostly useful to keep blocked apps from being re-installed fresh."),
    RestrictionInfo("no_install_unknown_sources", "No sideloading", "Blocks APKs from outside the Play Store."),
    RestrictionInfo("no_install_apps", "No installing apps at all", "Not even from the Play Store. Very strict."),
    RestrictionInfo("no_modify_accounts", "No account changes", "Can't add or remove accounts."),
    RestrictionInfo("no_debugging_features", "No USB debugging", "Locks out adb — including the Mac. Only once Hafen is final.", danger = true),
)

val DEFAULT_RESTRICTIONS: Map<String, Boolean> = RESTRICTIONS.associate { r ->
    r.key to (r.key in setOf(
        "no_factory_reset", "no_safe_boot", "no_config_date_time",
        "no_control_apps", "no_add_user", "no_add_managed_profile",
        "no_add_clone_profile",
    ))
}

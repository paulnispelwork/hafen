package dev.anchor.enforce

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import dev.anchor.policy.AppRule
import dev.anchor.policy.Mode
import dev.anchor.policy.Policy
import dev.anchor.policy.PolicyStore
import dev.anchor.policy.RESTRICTIONS
import dev.anchor.policy.Targets
import dev.anchor.service.DnsVpnService
import dev.anchor.service.WatchService
import dev.anchor.ui.LauncherActivity
import java.time.LocalDateTime
import java.util.concurrent.Executors

object Enforcer {
    private const val TAG = "Hafen"
    private val exec = Executors.newSingleThreadExecutor()
    private var lastHeavy: Policy? = null

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun dpm(ctx: Context): DevicePolicyManager = ctx.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    /** Plain device admin (works with accounts on the phone): Android refuses to uninstall Anchor while it's active. */
    fun notifyFilterConnected(ctx: Context) =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private var lastLauncher: Boolean? = null
    private fun applyLauncherComponent(ctx: Context, p: Policy) {
        if (lastLauncher == p.launcherEnabled) return
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, LauncherActivity::class.java),
                if (p.launcherEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            lastLauncher = p.launcherEnabled
        }
    }

    /** Switch Hafen's home screen component on or off right now (the setup guide needs it before asking Android). */
    fun setLauncherComponent(ctx: Context, on: Boolean) {
        lastLauncher = null
        applyLauncherComponent(ctx, PolicyStore.policy.value.copy(launcherEnabled = on))
    }

    fun isAdmin(ctx: Context) = runCatching { dpm(ctx).isAdminActive(admin(ctx)) }.getOrDefault(false)
    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun watcherComponent(ctx: Context) = "${ctx.packageName}/${WatchService::class.java.name}"
    fun watcherEnabled(ctx: Context) =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(watcherComponent(ctx), true) } == true

    fun enforceAsync(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { runCatching { enforce(app) }.onFailure { Log.e(TAG, "enforce failed", it) } }
    }

    @Synchronized
    fun enforce(ctx: Context) {
        PolicyStore.init(ctx); Usage.init(ctx)
        PolicyStore.applyDue()
        PolicyStore.pruneExpired()
        val p0 = PolicyStore.policy.value
        if (p0.released) { standDown(ctx); return }
        trackNewApps(ctx)
        val p = PolicyStore.policy.value
        Rules.hard = Rules.essentials(ctx)
        Rules.launchable = Apps.launchable(ctx).map { it.pkg }.toSet()
        Rules.committed = computeCommitted(ctx, p)
        Alarms.scheduleNextEdge(ctx, p)
        Stats.archiveIfDue(ctx)

        applySecureSettings(ctx, p)
        applyLauncherComponent(ctx, p) // own component: works without Device Owner too
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)

        applySuspensions(ctx, dpm, admin, p)

        // retried every round until every step went through
        if (p != lastHeavy && applyHeavy(ctx, dpm, admin, p)) lastHeavy = p
        applyChrome(dpm, admin, p)
    }

    private val untilFmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())

    /** Every package under a running block, plus other browsers while any website is blocked. */
    fun computeCommitted(ctx: Context, p: Policy): Map<String, String> {
        val m = linkedMapOf<String, String>()
        val active = Targets.active(p)
        val nowT = dev.anchor.policy.TrustedTime.localNow()
        for (c in active.sortedByDescending { it.until }) {
            if (c.windows.isNotEmpty() && !dev.anchor.policy.Schedule.contains(c.windows, nowT)) continue
            val t = Targets.resolve(c.target, ctx)
            val reason = if (c.windows.isEmpty()) "no ${t.label} until ${untilFmt.format(java.util.Date(c.until))}"
                else "no ${t.label} right now (${c.windows.joinToString { dev.anchor.policy.Schedule.describe(it) }})"
            // game *detection* never touches your essentials; an app you blocked by name still is blocked
            val pkgs = if (c.target == "games") t.packages - p.essentials.toSet() else t.packages
            pkgs.forEach { m.putIfAbsent(it, reason) }
        }
        if (Targets.needsBrowserLock(p)) {
            Targets.otherBrowsers(ctx).forEach { m.putIfAbsent(it, "other browsers are off while websites are blocked") }
        }
        return m - Rules.essentials(ctx)
    }

    /** New launchable apps get BLOCKED until you decide otherwise. */
    private fun trackNewApps(ctx: Context) {
        val prefs = ctx.getSharedPreferences("enforcer", Context.MODE_PRIVATE)
        val installed = Apps.launchable(ctx).map { it.pkg }.toSet()
        val known = prefs.getStringSet("known", null)
        val merged = installed + (known ?: emptySet())
        if (merged != known) prefs.edit().putStringSet("known", merged).apply()
        if (known == null) return // first run: everything present now counts as known
        val fresh = installed - known - Rules.essentials(ctx) - PolicyStore.policy.value.essentials.toSet()
        if (fresh.isEmpty() || !PolicyStore.policy.value.blockNewApps) return
        Log.i(TAG, "auto-blocking new apps: $fresh")
        PolicyStore.tighten { p ->
            p.copy(apps = p.apps + fresh.filter { it !in p.apps }.associateWith { AppRule(mode = Mode.BLOCK) })
        }
    }

    private fun applySuspensions(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, p: Policy) {
        val prefs = ctx.getSharedPreferences("enforcer", Context.MODE_PRIVATE)
        val essentials = Rules.essentials(ctx)
        val now = dev.anchor.policy.TrustedTime.localNow()
        val candidates = Apps.launchable(ctx).map { it.pkg }.toSet() + p.apps.keys + Rules.committed.keys
        val want = candidates.filter { it !in essentials && Rules.blockReason(p, it, now) != null }.toSet()
        val prev = prefs.getStringSet("suspended", emptySet())!!

        val off = prev - want
        // Ask Android for the real state: a reinstalled app comes back unsuspended. Not installed = skip.
        val pm = ctx.packageManager
        val on = want.filter { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess && // not installed: nothing to suspend (and no log spam)
                runCatching { !dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
        }.toSet()
        // Packages that failed to come back stay on the list and are retried next round (unless uninstalled).
        val stuck = if (off.isEmpty()) emptySet() else
            runCatching { dpm.setPackagesSuspended(admin, off.toTypedArray(), false).toSet() }.getOrDefault(off)
                .filter { runCatching { ctx.packageManager.getApplicationInfo(it, 0) }.isSuccess }.toSet()
        if (on.isNotEmpty()) {
            val failed = runCatching { dpm.setPackagesSuspended(admin, on.toTypedArray(), true) }.getOrNull()
            if (!failed.isNullOrEmpty()) Log.w(TAG, "could not suspend: ${failed.joinToString()}")
        }
        prefs.edit().putStringSet("suspended", want + stuck).apply()
    }

    /** Returns true only if every step succeeded. */
    private fun applyHeavy(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, p: Policy): Boolean {
        val pkg = ctx.packageName
        var ok = true
        fun step(name: String, f: () -> Unit) = runCatching(f).onFailure { ok = false; Log.w(TAG, "$name: ${it.message}") }

        step("uninstall-block") { dpm.setUninstallBlocked(admin, pkg, true) }
        step("support msg") { dpm.setShortSupportMessage(admin, p.blockedMessage) }

        // user restrictions (only touch the ones that differ)
        val active = runCatching { dpm.getUserRestrictions(admin) }.getOrElse { ok = false; Bundle() }
        for (r in RESTRICTIONS) step("restriction ${r.key}") {
            val want = p.restrictions[r.key] == true
            if (want && !active.getBoolean(r.key)) dpm.addUserRestriction(admin, r.key)
            if (!want && active.getBoolean(r.key)) dpm.clearUserRestriction(admin, r.key)
        }

        if (p.forceAutoTime) step("auto time") {
            if (Build.VERSION.SDK_INT >= 30) dpm.setAutoTimeEnabled(admin, true)
            else @Suppress("DEPRECATION") dpm.setAutoTimeRequired(admin, true)
        }

        // home screen
        step("launcher") {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, LauncherActivity::class.java),
                if (p.launcherEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (p.launcherEnabled) {
                val f = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, f, ComponentName(ctx, LauncherActivity::class.java))
            } else dpm.clearPackagePersistentPreferredActivities(admin, pkg)
        }

        // DNS filter as always-on VPN
        step("vpn") {
            if (p.dnsFilterEnabled) {
                dpm.setAlwaysOnVpnPackage(admin, pkg, false) // lockdown would cut all other traffic: the tunnel only carries DNS
                runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java)) }
            } else {
                // only switch off our own always-on VPN, never another VPN app's
                if (dpm.getAlwaysOnVpnPackage(admin) == pkg) dpm.setAlwaysOnVpnPackage(admin, null, false)
                runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
            }
        }
        if (p.dnsFilterEnabled) step("private dns") { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        return ok
    }

    private var lastChrome: Pair<Set<String>, Boolean>? = null

    /** Chrome managed policies: blocked sites, no incognito, no DNS-over-HTTPS. Re-applied when the set changes. */
    private fun applyChrome(dpm: DevicePolicyManager, admin: ComponentName, p: Policy) {
        val domains = Targets.activeDomains(p)
        val key = domains to p.chromeHardening
        if (key == lastChrome) return
        runCatching {
            val b = Bundle()
            if (domains.isNotEmpty()) b.putStringArray("URLBlocklist", domains.toTypedArray())
            if (p.chromeHardening || domains.isNotEmpty()) {
                b.putInt("IncognitoModeAvailability", 1)
                b.putString("DnsOverHttpsMode", "off")
                b.putBoolean("ForceGoogleSafeSearch", true)
            }
            dpm.setApplicationRestrictions(admin, "com.android.chrome", b)
            lastChrome = key
        }.onFailure { Log.w(TAG, "chrome: ${it.message}") }
    }

    /** App currently in front, reported by the watcher. */
    @Volatile var foreground: String? = null

    fun grayNow(p: Policy, now: LocalDateTime = dev.anchor.policy.TrustedTime.localNow()) =
        p.grayscale ||
            (p.grayscaleWindows.isNotEmpty() && dev.anchor.policy.Schedule.contains(p.grayscaleWindows, now)) ||
            foreground in p.grayscaleApps

    /** Fast path for the watcher: flip grayscale when the app in front changes. */
    fun updateGray(ctx: Context) {
        if (canWriteSecure(ctx)) setGray(ctx, grayNow(PolicyStore.policy.value))
    }

    /**
     * Turns grayscale on when the policy says so. Turns it off only if Anchor switched it on,
     * so grayscale you enable yourself (quick settings) is left alone.
     */
    private val grayLock = Any() // own lock: app switches must not wait for a full enforce round

    private fun setGray(ctx: Context, want: Boolean) = synchronized(grayLock) { runCatching {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("enforcer", Context.MODE_PRIVATE)
        // "on" means grayscale specifically; colour correction (another daltonizer mode) doesn't count
        val on = Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) == 1 &&
            Settings.Secure.getInt(cr, "accessibility_display_daltonizer", 0) == 0
        // No record yet (fresh install, update from a build that lost it, restore): if grayscale is on and the
        // policy uses grayscale at all, it's ours. Otherwise a reinstall at 21:00 leaves it on forever.
        val byUs = if (prefs.contains("grayByAnchor")) prefs.getBoolean("grayByAnchor", false)
            else (on && PolicyStore.policy.value.let { it.grayscale || it.grayscaleWindows.isNotEmpty() || it.grayscaleApps.isNotEmpty() })
                .also { prefs.edit().putBoolean("grayByAnchor", it).apply() }
        when {
            want && !on -> {
                // remember colour correction someone had on, to give it back afterwards
                val wasEnabled = Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) == 1
                prefs.edit().putInt("prevDaltonizer", if (wasEnabled) Settings.Secure.getInt(cr, "accessibility_display_daltonizer", -1) else -1).apply()
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0) // 0 = monochrome
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", 1)
                prefs.edit().putBoolean("grayByAnchor", true).apply()
            }
            !want && on && byUs -> {
                val prev = prefs.getInt("prevDaltonizer", -1)
                if (prev > 0) Settings.Secure.putInt(cr, "accessibility_display_daltonizer", prev) // colour correction back on
                else Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", 0)
                prefs.edit().putBoolean("grayByAnchor", false).apply()
            }
            !on && byUs -> prefs.edit().putBoolean("grayByAnchor", false).apply()
        }
    } }

    /** After release: hand everything back and stop. */
    private fun standDown(ctx: Context) {
        Rules.committed = emptyMap()
        if (!canWriteSecure(ctx)) return
        setGray(ctx, false)
        runCatching {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val next = cur.split(':').filter { it.isNotBlank() && !it.equals(watcherComponent(ctx), true) }.joinToString(":")
            if (next != cur) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        }
    }

    /** Needs WRITE_SECURE_SETTINGS (granted once over adb). */
    private fun applySecureSettings(ctx: Context, p: Policy) {
        if (!canWriteSecure(ctx)) return
        val cr = ctx.contentResolver
        setGray(ctx, grayNow(p))
        runCatching {
            if (!watcherEnabled(ctx)) {
                val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val next = listOf(cur, watcherComponent(ctx)).filter { it.isNotBlank() }.joinToString(":")
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
    }

    /** Undo everything and give up device owner. Only reachable when policy.releaseAllowed is active. */
    fun release(ctx: Context) {
        val cur = PolicyStore.policy.value
        if (!cur.releaseAllowed) return // allowing it goes through the waiting period like any loosening
        if (Targets.active(cur).isNotEmpty() || cur.lockedUntil > dev.anchor.policy.TrustedTime.now()) return // blocks and locks run to the end
        val dpm = dpm(ctx); val admin = admin(ctx); val pkg = ctx.packageName
        // From now on nothing is enforced: no grayscale, no watcher, no filter, no guards.
        PolicyStore.tighten { dev.anchor.policy.Policy(released = true, blockShortForm = false, chromeHardening = false, restrictions = emptyMap(), forceAutoTime = false) }
        standDown(ctx)
        if (!isOwner(ctx)) {
            runCatching { dpm.removeActiveAdmin(admin) }
            return
        }
        val prefs = ctx.getSharedPreferences("enforcer", Context.MODE_PRIVATE)
        runCatching { dpm.setPackagesSuspended(admin, prefs.getStringSet("suspended", emptySet())!!.toTypedArray(), false) }
        for (r in RESTRICTIONS) runCatching { dpm.clearUserRestriction(admin, r.key) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, pkg) }
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx, LauncherActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        runCatching { if (dpm.getAlwaysOnVpnPackage(admin) == pkg) dpm.setAlwaysOnVpnPackage(admin, null, false) }
        runCatching { dpm.setApplicationRestrictions(admin, "com.android.chrome", Bundle()) }
        runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
        runCatching { dpm.setShortSupportMessage(admin, null) }
        if (canWriteSecure(ctx)) setGray(ctx, false)
        prefs.edit().clear().apply()
        @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(pkg)
        lastHeavy = null
    }
}

package dev.anchor.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Rules
import dev.anchor.enforce.Usage
import dev.anchor.policy.PolicyStore
import dev.anchor.policy.Targets
import android.view.accessibility.AccessibilityNodeInfo
import dev.anchor.ui.GateActivity

/**
 * Knows which app is in front. Uses that to
 *  - count time per app (for budgets),
 *  - put the gate screen (wait / reason / opens) in front of limited apps,
 *  - leave Reels / Shorts the moment they appear,
 *  - throw you out of an app as soon as its budget or window runs out.
 */
class WatchService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var since = 0L
    private var lastShortCheck = 0L
    private var ignore = emptySet<String>()
    private var keyboards = emptySet<String>()
    private var lastGuard = 0L
    private var ticks = 0
    private var lastWindows = 0L
    private var lastUrl = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> switchTo(null)
                Intent.ACTION_USER_PRESENT -> {
                    Usage.bump("unlock")
                    rootInActiveWindow?.packageName?.toString()?.let(::onForeground)
                }
                Intent.ACTION_PACKAGE_ADDED -> { dev.anchor.policy.Memo.clearAll(); Enforcer.enforceAsync(this@WatchService) }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            flush()
            // events can be missed (going home to our own launcher, unlocking): re-read who is in front
            if (!PolicyStore.policy.value.released) rootInActiveWindow?.packageName?.toString()?.let(::onForeground)
            current?.let { pkg ->
                val p = PolicyStore.policy.value
                if (pkg !in ignore) {
                    if (Rules.blockReason(p, pkg) != null) kickOut(pkg, Rules.blockReason(p, pkg)!!)
                    else if (Rules.needsGate(p, pkg)) showGate(pkg) // session ran out
                }
            }
            // the full round (rules, suspensions, alarms) once a minute; block checks above run every 20 s
            if (++ticks % 3 == 0) Enforcer.enforceAsync(this@WatchService)
            h.postDelayed(this, 20_000)
        }
    }

    override fun onServiceConnected() {
        PolicyStore.init(this); Usage.init(this)
        ignore = Rules.essentials(this) - setOf("com.android.settings")
        keyboards = runCatching {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        val flag = ContextCompat.RECEIVER_EXPORTED // system broadcasts
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, flag)
        ContextCompat.registerReceiver(this, screen,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }, flag)
        Usage.prune()
        h.post(tick)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        flush()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (PolicyStore.policy.value.released) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // an app came to the front without a window-state event (e.g. reopened from the background)
            val now = SystemClock.elapsedRealtime()
            if (now - lastWindows > 250) { lastWindows = now; rootInActiveWindow?.packageName?.toString()?.let(::onForeground) }
            return
        }
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in GUARDED) guardSelf(pkg, force = true)
                onForeground(pkg)
                checkShortForm(pkg, force = true)
                checkUrl(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg in GUARDED) guardSelf(pkg, force = false)
                checkShortForm(pkg, force = false)
                checkUrl(pkg, force = false)
            }
        }
    }

    private fun onForeground(pkg: String) {
        // The keyboard popping up is not "switching apps": keep counting time and grayscale for the app below it.
        if (pkg == "com.android.systemui" || pkg in keyboards) return
        // Always check blocks, even for the "same" app: going home to Anchor's own launcher
        // doesn't always produce an event, so `current` can be stale.
        val changed = pkg != current
        if (changed) switchTo(pkg)
        if (pkg in ignore) return
        val p = PolicyStore.policy.value
        val reason = Rules.blockReason(p, pkg)
        when {
            reason != null -> kickOut(pkg, reason) // suspension normally catches this first
            changed && Rules.needsGate(p, pkg) -> showGate(pkg)
            changed && shouldNudge(p, pkg) -> showNudge(pkg)
        }
    }

    private fun switchTo(pkg: String?) {
        flush()
        current = pkg
        if (pkg != null && pkg != "com.android.systemui") {
            Enforcer.foreground = pkg
            Enforcer.updateGray(this)
        }
        since = SystemClock.elapsedRealtime()
    }

    private fun flush() {
        val now = SystemClock.elapsedRealtime()
        current?.let { if (it != packageName) Usage.addMillis(it, now - since) }
        since = now
    }

    private fun showGate(pkg: String) {
        startActivity(Intent(this, GateActivity::class.java)
            .putExtra(GateActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun shouldNudge(p: dev.anchor.policy.Policy, pkg: String) =
        p.nudgeEnabled && Rules.launchable.contains(pkg) && pkg !in p.essentials && pkg != p.nudgeApp &&
            dev.anchor.policy.Schedule.contains(p.nudgeWindows, dev.anchor.policy.TrustedTime.localNow()) && !Usage.hasNudgePass(pkg)

    private fun showNudge(pkg: String) {
        Usage.bump("nudge")
        startActivity(Intent(this, dev.anchor.ui.NudgeActivity::class.java)
            .putExtra(dev.anchor.ui.NudgeActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun kickOut(pkg: String, reason: String) {
        Usage.bump("attempt")
        performGlobalAction(GLOBAL_ACTION_HOME)
        switchTo(null) // we're home now; don't keep counting time for the blocked app
        Toast.makeText(this, "Hafen: $reason", Toast.LENGTH_SHORT).show()
        Enforcer.enforceAsync(this)
    }

    companion object {
        /** Where Anchor itself could be switched off or removed. */
        private val GUARDED = setOf(
            "com.android.settings", "com.google.android.settings",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller", // default home app
        )
        /** Screens that mention Anchor and one of these are off-switches (English + German UI). */
        private val OFF_SWITCHES = listOf(
            "uninstall", "force stop", "clear storage", "clear cache", "storage & cache", "deactivate",
            "device admin", "deinstallieren", "beenden erzwingen", "speicher", "cache leeren", "deaktivieren",
            "geräteadministrator", "notification access", "benachrichtigungszugriff",
        )
        /** Only while Anchor is your home screen: don't let it be swapped for another launcher. */
        private val HOME_SWITCHES = listOf("home app", "home screen", "launcher", "startbildschirm")
        private const val CHROME = "com.android.chrome"

        fun hostOf(s: String): String? = s.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').trimEnd('.').removePrefix("www.")
            .takeIf { it.contains('.') && ' ' !in it }
    }

    /**
     * Without Device Owner, Android can't stop you from uninstalling Anchor or switching off its watcher.
     * So while something is committed, the watcher leaves those screens the moment they show up.
     */
    private fun guardSelf(pkg: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastGuard < 300) return
        lastGuard = now
        if (!Rules.protecting(PolicyStore.policy.value)) return
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collect(root, texts, 0)
        val label = applicationInfo.loadLabel(packageManager).toString()
        if (texts.none { it.trim().equals(label, ignoreCase = true) }) return
        val all = texts.joinToString("\n").lowercase()
        val p = PolicyStore.policy.value
        val named = listOf("use ${label.lowercase()}", "${label.lowercase()} verwenden")
        if (OFF_SWITCHES.none { it in all } && named.none { it in all } && !(p.launcherEnabled && HOME_SWITCHES.any { it in all })) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 150)
        Toast.makeText(this, "Hafen is protected while blocks or locks run.", Toast.LENGTH_SHORT).show()
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 800) return
        n.text?.let { out += it.toString() }
        n.contentDescription?.let { out += it.toString() }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    /** Website blocking without Device Owner: read Chrome's address bar and leave blocked sites. */
    private fun checkUrl(pkg: String, force: Boolean) {
        if (pkg != CHROME) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUrl < 500) return
        lastUrl = now
        val root = rootInActiveWindow ?: return
        val bar = root.findAccessibilityNodeInfosByViewId("$CHROME:id/url_bar").firstOrNull() ?: return
        if (bar.isFocused) return // still typing
        val host = hostOf(bar.text?.toString() ?: return) ?: return
        val hit = Targets.activeDomains(PolicyStore.policy.value).firstOrNull { host == it || host.endsWith(".$it") } ?: return
        Usage.bump("attempt")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Hafen: $hit is blocked.", Toast.LENGTH_SHORT).show()
    }

    private fun checkShortForm(pkg: String, force: Boolean) {
        val p = PolicyStore.policy.value
        if (!p.blockShortForm) return
        val ids = p.shortFormViewIds.filter { it.startsWith("$pkg:") }
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastShortCheck < 400) return
        lastShortCheck = now
        val root = rootInActiveWindow ?: return
        for (id in ids) {
            val hit = root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
            if (hit) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Toast.makeText(this, "No short videos.", Toast.LENGTH_SHORT).show()
                return
            }
        }
    }
}

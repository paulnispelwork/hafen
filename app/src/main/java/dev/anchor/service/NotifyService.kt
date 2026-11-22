package dev.anchor.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.anchor.enforce.Held
import dev.anchor.enforce.Inbox
import dev.anchor.enforce.Rules
import dev.anchor.policy.PolicyStore

/**
 * Notification filter. Blocked apps never get through. With the filter on, only allowed apps,
 * essentials, calls and SMS do. Everything else is held in the Inbox, read on the home screen.
 */
class NotifyService : NotificationListenerService() {
    private var hard = emptySet<String>()
    private var browsers = emptySet<String>()

    override fun onListenerConnected() {
        PolicyStore.init(this); Inbox.init(this)
        hard = Rules.essentials(this)
        browsers = runCatching { dev.anchor.policy.Targets.otherBrowsers(this) + "com.android.chrome" }.getOrDefault(setOf("com.android.chrome"))
        // older versions kept blocked apps' messages: drop them now
        Inbox.purge { it.blocked || (it.pkg == "com.android.chrome" && mentionsBlockedSite(listOf(it.title, it.text))) }
        runCatching { activeNotifications?.forEach(::handle) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { handle(sbn) }

    private fun handle(sbn: StatusBarNotification) = runCatching {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in hard) return@runCatching
        val n = sbn.notification
        // never touch calls, alarms, music controls, navigation or anything that can't be dismissed
        if (sbn.isOngoing || n.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR) != 0) return@runCatching
        if (n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_TRANSPORT)) return@runCatching

        val p = PolicyStore.policy.value
        if (p.released) return@runCatching
        val e0 = n.extras
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { e0.getCharSequence(it)?.toString() }
        // a blocked app, or a website push (e.g. x.com via Chrome) from a blocked site: gone, not held
        // a website push names its site; only trust that for browsers (a chat message may just contain a link)
        if (Rules.blockReason(p, pkg) != null || (pkg in browsers && mentionsBlockedSite(texts))) {
            cancelNotification(sbn.key)
            return@runCatching
        }
        val filtered = p.notifyFilter && pkg !in p.notifyAllowed && pkg !in p.essentials
        if (!filtered) return@runCatching

        cancelNotification(sbn.key)
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@runCatching // the summary carries no message
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return@runCatching
        Inbox.add(Held(sbn.key, pkg, title.take(120), text.take(400), sbn.postTime, false))
    }.onFailure { Log.w("Hafen", "notify filter: ${it.message}") }

    /** "x.com", "https://x.com/…", "twitter.com" in a notification's text → from a blocked site. */
    private fun mentionsBlockedSite(texts: List<String>): Boolean {
        val doms = dev.anchor.policy.Targets.activeDomains(PolicyStore.policy.value)
        if (doms.isEmpty()) return false
        val words = texts.flatMap { it.lowercase().split(' ', '\n', '\t', '·', '•', '(', ')', ',') }
            .mapNotNull { dev.anchor.service.WatchService.hostOf(it) }
        return words.any { h -> doms.any { d -> h == d || h.endsWith(".$d") } }
    }
}

package dev.anchor.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/** What a block covers: app packages (installed now or later) and websites. */
data class Target(val key: String, val label: String, val packages: List<String>, val domains: List<String>)

object Targets {
    val BUILTIN = listOf(
        Target("instagram", "Instagram", listOf("com.instagram.android", "com.instagram.lite", "com.instagram.barcelona"),
            listOf("instagram.com", "cdninstagram.com", "ig.me", "threads.net", "threads.com")),
        Target("youtube", "YouTube", listOf("com.google.android.youtube", "com.google.android.apps.youtube.kids", "app.revanced.android.youtube", "app.rvx.android.youtube"),
            listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com")),
        Target("x", "X", listOf("com.twitter.android", "com.twitter.android.lite"),
            listOf("x.com", "twitter.com", "twimg.com")),
        Target("linkedin", "LinkedIn", listOf("com.linkedin.android", "com.linkedin.android.lite"),
            listOf("linkedin.com", "licdn.com", "lnkd.in")),
        Target("games", "Games", listOf("org.lichess.mobileV2", "com.lulo.scrabble.classicwords", "com.google.android.play.games",
            "com.valvesoftware.android.steam.community", "com.chess"),
            listOf("lichess.org", "chess.com", "poki.com", "crazygames.com", "steampowered.com", "steamcommunity.com")),
        Target("snapchat", "Snapchat", listOf("com.snapchat.android"), listOf("snapchat.com")),
        Target("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com")),
        Target("twitch", "Twitch", listOf("tv.twitch.android.app"), listOf("twitch.tv")),
        Target("reddit", "Reddit", listOf("com.reddit.frontpage"), listOf("reddit.com", "redd.it", "redditmedia.com")),
        Target("streaming", "Netflix & Prime Video", listOf("com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient"), listOf("netflix.com", "primevideo.com")),
        Target("facebook", "Facebook", listOf("com.facebook.katana", "com.facebook.lite"), listOf("facebook.com", "fb.com", "fbcdn.net")),
    )

    fun appKey(pkg: String) = "app:$pkg"
    fun siteKey(domain: String) = "site:${normDomain(domain)}"

    fun normDomain(d: String) = d.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('/').removePrefix("*.").removeSuffix(".")

    /** Without a context, game detection and app labels are skipped. */
    fun resolve(key: String, ctx: Context? = null): Target {
        BUILTIN.firstOrNull { it.key == key }?.let { t ->
            if (t.key == "games" && ctx != null) return t.copy(packages = (t.packages + gamePackages(ctx)).distinct())
            return t
        }
        if (key.startsWith("app:")) {
            val pkg = key.removePrefix("app:")
            val label = ctx?.let { c -> runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
            return Target(key, label ?: pkg, listOf(pkg), emptyList())
        }
        if (key.startsWith("site:")) {
            val d = key.removePrefix("site:")
            return Target(key, d, emptyList(), listOf(d))
        }
        return Target(key, key, emptyList(), emptyList())
    }

    fun gamePackages(ctx: Context): Set<String> = gamesMemo.get(ctx)
    private val gamesMemo = Memo(10 * 60_000) { computeGames(it) }
    private fun computeGames(ctx: Context): Set<String> =
        ctx.packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
            .filter { it.category == ApplicationInfo.CATEGORY_GAME }
            .map { it.packageName }.toSet()

    /** Every installed browser except Chrome (which Anchor controls through managed policy). */
    fun otherBrowsers(ctx: Context): Set<String> = browsersMemo.get(ctx)
    private val browsersMemo = Memo(10 * 60_000) { computeBrowsers(it) }
    private fun computeBrowsers(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        return ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.chrome" && it != ctx.packageName }
            .toSet()
    }

    fun active(p: Policy, now: Long = TrustedTime.now()) = p.commitments.filter { it.until > now }

    /** Websites blocked right now: the manual list plus every active block's sites. */
    fun activeDomains(p: Policy, now: Long = TrustedTime.now()): Set<String> =
        (p.blockedDomains.map(::normDomain) + blockDomains(p, now)).filter { it.isNotEmpty() }.toSet()

    /** Sites of blocks that are biting right now (a rhythm block only inside its windows). */
    fun blockDomains(p: Policy, now: Long = TrustedTime.now()): Set<String> {
        val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault())
        return active(p, now).filter { it.windows.isEmpty() || Schedule.contains(it.windows, t) }
            .flatMap { resolve(it.target).domains }.map(::normDomain).filter { it.isNotEmpty() }.toSet()
    }

    /** Other browsers go off only for sites you chose, not for the built-in DNS-over-HTTPS list. */
    fun needsBrowserLock(p: Policy, now: Long = TrustedTime.now()) =
        blockDomains(p, now).isNotEmpty() || (p.blockedDomains.map(::normDomain) - DEFAULT_DOMAINS.toSet()).isNotEmpty()
}

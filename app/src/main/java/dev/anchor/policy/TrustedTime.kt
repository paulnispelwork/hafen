package dev.anchor.policy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The time blocks are measured against. Moving the phone's clock doesn't move it:
 *  1. Android's network time (Android 13+), which ignores manual clock changes, if available;
 *  2. otherwise the last trusted reading plus the time since boot (which also can't be changed).
 * A clock that runs more than 2 minutes off from that is ignored until it agrees again.
 */
object TrustedTime {
    private var prefs: SharedPreferences? = null
    private var bootCount = -1
    private var anchorWall = 0L
    private var anchorElapsed = -1L
    private const val TOLERANCE = 2 * 60_000L

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("clock", Context.MODE_PRIVATE)
        bootCount = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val p = prefs!!
        if (p.getInt("boot", -2) == bootCount) {
            anchorWall = p.getLong("wall", 0); anchorElapsed = p.getLong("elapsed", -1)
        }
    }

    private fun network(): Long? =
        if (Build.VERSION.SDK_INT >= 33) runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull() else null

    @Synchronized
    fun now(): Long = runCatching { trusted() }.getOrElse { System.currentTimeMillis() }

    private fun trusted(): Long {
        val wall = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        network()?.let { n -> anchor(n, el); return n }
        if (anchorElapsed >= 0 && el >= anchorElapsed) {
            val expected = anchorWall + (el - anchorElapsed)
            if (kotlin.math.abs(wall - expected) > TOLERANCE) return expected // clock was moved: don't believe it
        }
        anchor(wall, el)
        return wall
    }

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now()), ZoneId.systemDefault())

    /** True while the phone's clock disagrees with trusted time (shown in Status). */
    fun clockTampered(): Boolean = kotlin.math.abs(System.currentTimeMillis() - now()) > TOLERANCE

    private fun anchor(wall: Long, el: Long) {
        val first = anchorElapsed < 0
        anchorWall = wall; anchorElapsed = el
        // persist now and then (and on the first reading of a boot), not on every call
        if (first || el - (prefs?.getLong("elapsed", 0) ?: 0) > 5 * 60_000L) {
            prefs?.edit()?.putLong("wall", wall)?.putLong("elapsed", el)?.putInt("boot", bootCount)?.apply()
        }
    }
}

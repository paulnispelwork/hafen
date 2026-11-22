package dev.anchor.enforce

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.anchor.policy.PolicyStore

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Enforcer.enforceAsync(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PolicyStore.init(context)
        Alarms.scheduleRepeating(context)
        PolicyStore.pending.value?.let { Alarms.scheduleAt(context, it.applyAt) }
        Enforcer.enforceAsync(context)
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            runCatching { Enforcer.enforce(context.applicationContext) }
            done.finish()
        }.start()
    }
}

object Alarms {
    private fun pi(ctx: Context, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, TickReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Fallback heartbeat in case the accessibility watcher is not running. */
    fun scheduleRepeating(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi(ctx, 1),
        )
    }

    fun scheduleAt(ctx: Context, at: Long, code: Int = 2) {
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1_000, pi(ctx, code))
    }

    /** So a rhythm, bedtime or block end bites on time even without the watcher. */
    fun scheduleNextEdge(ctx: Context, p: dev.anchor.policy.Policy) {
        val now = dev.anchor.policy.TrustedTime.now()
        val nowT = dev.anchor.policy.TrustedTime.localNow()
        val zone = java.time.ZoneId.systemDefault()
        val active = dev.anchor.policy.Targets.active(p, now)
        val edges = buildList {
            active.forEach { add(it.until) }
            if (p.lockedUntil > now) add(p.lockedUntil)
            (active.map { it.windows } + listOf(p.bedtimeWindows, p.grayscaleWindows) + p.apps.values.map { it.windows })
                .forEach { w -> dev.anchor.policy.Schedule.nextChange(w, nowT)?.let { add(it.atZone(zone).toInstant().toEpochMilli()) } }
        }
        edges.filter { it > now }.minOrNull()?.let { scheduleAt(ctx, it, code = 3) }
    }
}

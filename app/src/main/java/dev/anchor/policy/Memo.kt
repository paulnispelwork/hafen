package dev.anchor.policy

import android.content.Context
import android.os.SystemClock

/** Caches an expensive lookup (app lists, essentials) for [ttlMs]; cleared when apps change. */
class Memo<T : Any>(private val ttlMs: Long, private val f: (Context) -> T) {
    private var at = 0L
    private var v: T? = null
    init { synchronized(all) { all += this } }

    @Synchronized
    fun get(ctx: Context): T {
        val now = SystemClock.elapsedRealtime()
        v?.let { if (now - at < ttlMs) return it }
        return f(ctx).also { v = it; at = now }
    }

    @Synchronized
    fun clear() { v = null }

    companion object {
        private val all = mutableListOf<Memo<*>>()
        /** An app was installed or removed. */
        fun clearAll() = synchronized(all) { all.forEach { it.clear() } }
    }
}

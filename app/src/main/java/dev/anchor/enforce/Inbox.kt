package dev.anchor.enforce

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A notification Anchor held back. Stays on the phone, in Anchor's private storage. */
@Serializable
data class Held(val key: String, val pkg: String, val title: String, val text: String, val at: Long, val blocked: Boolean)

/** Notifications the filter held back, readable later from the home screen. */
object Inbox {
    val items = MutableStateFlow<List<Held>>(emptyList())
    private var file: File? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "inbox.json")
        items.value = runCatching { json.decodeFromString<List<Held>>(file!!.readText()) }.getOrDefault(emptyList())
    }

    /** Same key = updated notification (e.g. a chat with a new message): replace, don't pile up. */
    @Synchronized
    fun add(h: Held) = save((items.value.filter { it.key != h.key } + h).takeLast(300))

    @Synchronized
    fun removeApp(pkg: String) = save(items.value.filter { it.pkg != pkg })

    @Synchronized
    fun clear() = save(emptyList())

    @Synchronized
    fun purge(drop: (Held) -> Boolean) { if (items.value.any(drop)) save(items.value.filterNot(drop)) }

    private fun save(l: List<Held>) {
        items.value = l
        runCatching { file?.writeText(json.encodeToString(l)) }
    }
}

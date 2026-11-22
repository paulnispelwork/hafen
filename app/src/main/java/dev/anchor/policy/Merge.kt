package dev.anchor.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Three-way merges of policies, so edits and background changes don't overwrite each other. */
object Merge {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun obj(p: Policy) = json.encodeToJsonElement(p).jsonObject
    private fun back(o: Map<String, kotlinx.serialization.json.JsonElement>) = json.decodeFromJsonElement(Policy.serializer(), JsonObject(o))

    /**
     * Your unsaved [draft] was started from [base]; meanwhile the saved policy became [current].
     * Fields you didn't touch take the current value; fields you changed keep yours.
     * Blocks always come from [current] (they are added and extended directly, never through the draft).
     * App rules are merged per app, so an app auto-blocked in the background isn't lost.
     */
    fun rebase(draft: Policy, base: Policy, current: Policy): Policy {
        val d = obj(draft); val b = obj(base); val c = obj(current)
        val out = HashMap<String, kotlinx.serialization.json.JsonElement>()
        for (k in c.keys + d.keys) out[k] = if (d[k] == b[k]) c[k] ?: d[k]!! else d[k] ?: c[k]!!
        val apps = HashMap(current.apps)
        (draft.apps.keys + base.apps.keys).forEach { k ->
            if (draft.apps[k] != base.apps[k]) { val v = draft.apps[k]; if (v == null) apps.remove(k) else apps[k] = v }
        }
        return back(out).copy(commitments = current.commitments, apps = apps)
    }

    /**
     * A change [n] was applied on top of [o] while a loosening [pending] waits.
     * Keep the waiting change, but let the newest change win wherever it touched something.
     */
    fun carryPending(pending: Policy, o: Policy, n: Policy): Policy {
        val p = obj(pending); val a = obj(o); val b = obj(n)
        val out = HashMap<String, kotlinx.serialization.json.JsonElement>()
        for (k in p.keys + b.keys) out[k] = if (b[k] != a[k]) b[k] ?: p[k]!! else p[k] ?: b[k]!!
        return back(out).copy(commitments = n.commitments)
    }
}

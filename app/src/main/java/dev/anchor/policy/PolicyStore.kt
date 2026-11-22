package dev.anchor.policy

import android.content.Context
import dev.anchor.enforce.Alarms
import dev.anchor.enforce.Enforcer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class Pending(val policy: Policy, val applyAt: Long, val createdAt: Long, val reasons: List<String>)

object PolicyStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private lateinit var dir: File

    val policy = MutableStateFlow(Policy())
    val pending = MutableStateFlow<Pending?>(null)

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.applicationContext.filesDir
        TrustedTime.init(ctx)
        policy.value = read<Policy>("policy.json") ?: read<Policy>("policy.json.bak") ?: run {
            // Exists but unreadable: keep a copy for forensics instead of silently starting from nothing.
            File(dir, "policy.json").takeIf { it.exists() }?.copyTo(File(dir, "policy.json.broken"), overwrite = true)
            Policy()
        }
        pending.value = read<Pending>("pending.json")
    }

    private inline fun <reified T> read(name: String): T? =
        runCatching { json.decodeFromString<T>(File(dir, name).readText()) }.getOrNull()

    private inline fun <reified T> write(name: String, v: T?) {
        val f = File(dir, name)
        if (v == null) { f.delete(); return }
        val tmp = File(dir, "$name.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(v).toByteArray())
            out.fd.sync() // survive a power cut mid-write
        }
        if (f.exists()) f.copyTo(File(dir, "$name.bak"), overwrite = true)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    private fun setPolicy(p: Policy) { write("policy.json", p); policy.value = p }
    private fun setPending(p: Pending?) { write("pending.json", p); pending.value = p }

    sealed interface Outcome {
        data object Applied : Outcome
        data class Queued(val at: Long, val reasons: List<String>) : Outcome
        data class Refused(val broken: List<Commitment>) : Outcome
        data class Locked(val until: Long, val reasons: List<String>) : Outcome
    }

    /** A queued policy was written before newer blocks existed: carry every still-running block over. */
    private fun keepBlocks(base: Policy, cur: Policy): Policy {
        val now = TrustedTime.now()
        val all = (base.commitments + Targets.active(cur, now)).filter { it.until > now }
        return base.copy(commitments = all.groupBy { it.target to it.windows }.map { (_, cs) ->
            // keep the latest end, but the earliest start, so "time served" survives an extension
            cs.maxBy { it.until }.copy(createdAt = cs.filter { it.createdAt > 0 }.minOfOrNull { it.createdAt } ?: 0)
        })
    }

    /** Add blocks (or extend existing ones). Always a tightening, so always immediate. */
    @Synchronized
    fun addBlocks(ctx: Context, targets: Collection<String>, until: Long, windows: List<TimeWindow> = emptyList()): Outcome {
        val o = policy.value
        val now = TrustedTime.now()
        val added = targets.map { Commitment(it, until, now, windows) }
        return propose(ctx, keepBlocks(o.copy(commitments = o.commitments + added), o))
    }

    /** Stricter or equal → now. Looser → after the cooldown. A stricter change cancels a queued loosening. */
    @Synchronized
    fun propose(ctx: Context, n: Policy): Outcome {
        val o = policy.value
        val broken = Strictness.brokenCommitments(o, n)
        if (broken.isNotEmpty()) return Outcome.Refused(broken)
        val now0 = TrustedTime.now()
        // The partner code can end blocks, so while blocks or a lock run nobody can set a new one
        // (armed or not). Removing it is fine: that only takes the emergency exit away.
        val running = Targets.active(o, now0)
        if (n.partnerCode != null && n.partnerCode != o.partnerCode && (running.isNotEmpty() || o.lockedUntil > now0)) {
            return Outcome.Locked(maxOf(o.lockedUntil, running.maxOfOrNull { it.until } ?: 0), listOf("a new partner code can't be set while blocks or a settings lock run"))
        }
        if (o.lockedUntil > now0) {
            val loose = Strictness.loosenings(o, n)
            if (loose.isNotEmpty()) return Outcome.Locked(o.lockedUntil, loose)
        }
        val reasons = if (o.armed) Strictness.loosenings(o, n) else emptyList()
        if (reasons.isEmpty()) {
            setPolicy(n)
            // a waiting loosening stays; the newest change wins wherever both touched the same thing
            pending.value?.let { pd ->
                val carried = Merge.carryPending(pd.policy, o, n)
                setPending(if (Strictness.isLooser(n, carried)) pd.copy(policy = carried, reasons = Strictness.loosenings(n, carried)) else null)
            }
            Enforcer.enforceAsync(ctx)
            return Outcome.Applied
        }
        val now = TrustedTime.now()
        val at = now + o.cooldownHours * 3_600_000L
        setPending(Pending(n, at, now, reasons))
        Alarms.scheduleAt(ctx, at)
        return Outcome.Queued(at, reasons)
    }

    @Synchronized
    fun applyDue(): Boolean {
        val p = pending.value ?: return false
        val now = TrustedTime.now()
        if (now < p.applyAt) return false
        // never loosen through the queue while settings are locked
        if (policy.value.lockedUntil > now && Strictness.isLooser(policy.value, p.policy)) return false
        setPolicy(keepBlocks(p.policy, policy.value)); setPending(null)
        return true
    }

    sealed interface CodeResult {
        data object Ok : CodeResult
        data class Wrong(val triesLeft: Int) : CodeResult
        data class TooMany(val until: Long) : CodeResult
    }

    private const val MAX_TRIES = 5

    /** Checks the partner code with a lockout: 5 wrong tries, then 1h, 2h, 4h… */
    private fun tryCode(ctx: Context, code: String): CodeResult {
        val prefs = ctx.getSharedPreferences("partner", Context.MODE_PRIVATE)
        val now = TrustedTime.now()
        val blockedUntil = prefs.getLong("blockedUntil", 0)
        if (now < blockedUntil) return CodeResult.TooMany(blockedUntil)
        if (checkCode(policy.value.partnerCode, code)) {
            prefs.edit().putInt("fails", 0).putInt("lockouts", 0).apply()
            return CodeResult.Ok
        }
        val fails = prefs.getInt("fails", 0) + 1
        if (fails < MAX_TRIES) { prefs.edit().putInt("fails", fails).apply(); return CodeResult.Wrong(MAX_TRIES - fails) }
        val lockouts = prefs.getInt("lockouts", 0)
        val until = now + (3_600_000L shl lockouts.coerceAtMost(6))
        prefs.edit().putInt("fails", 0).putInt("lockouts", lockouts + 1).putLong("blockedUntil", until).apply()
        return CodeResult.TooMany(until)
    }

    /**
     * Partner override: ends one running block and/or the settings lock early.
     * The only way to shorten either; it needs the code someone else holds.
     */
    @Synchronized
    fun overrideWithCode(ctx: Context, code: String, block: Commitment? = null, unlockSettings: Boolean = false): CodeResult {
        val r = tryCode(ctx, code)
        if (r != CodeResult.Ok) return r
        fun strip(p: Policy) = p.copy(
            commitments = if (block == null) p.commitments else p.commitments.filterNot { it.target == block.target && it.windows == block.windows },
            lockedUntil = if (unlockSettings) 0 else p.lockedUntil,
        )
        setPolicy(strip(policy.value))
        pending.value?.let { setPending(it.copy(policy = strip(it.policy))) } // or the queue would bring it back
        dev.anchor.enforce.Usage.logReason("hafen", buildList {
            block?.let { add("partner code ended block: ${Targets.resolve(it.target, ctx).label}") }
            if (unlockSettings) add("partner code lifted the settings lock")
        }.joinToString("; "))
        Enforcer.enforceAsync(ctx)
        return r
    }

    @Synchronized
    fun applyWithCode(ctx: Context, code: String): CodeResult {
        val p = pending.value ?: return CodeResult.Wrong(0)
        val r = tryCode(ctx, code)
        if (r != CodeResult.Ok) return r
        if (policy.value.lockedUntil > TrustedTime.now() && Strictness.isLooser(policy.value, p.policy)) return CodeResult.TooMany(policy.value.lockedUntil)
        setPolicy(keepBlocks(p.policy, policy.value)); setPending(null)
        Enforcer.enforceAsync(ctx)
        return CodeResult.Ok
    }

    @Synchronized
    fun cancelPending() = setPending(null)

    /** Drop blocks that have run out. Returns true if anything changed. */
    @Synchronized
    fun pruneExpired(): Boolean {
        val now = TrustedTime.now()
        val p = policy.value
        if (p.commitments.all { it.until > now }) return false
        setPolicy(p.copy(commitments = p.commitments.filter { it.until > now }))
        return true
    }

    /** Internal tightening (e.g. auto-blocking a new app). Applied to the queued policy too. */
    @Synchronized
    fun tighten(f: (Policy) -> Policy) {
        setPolicy(f(policy.value))
        pending.value?.let { setPending(it.copy(policy = f(it.policy))) }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun hashCode(code: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return "$salt:${sha(salt + code.trim())}"
    }

    fun checkCode(stored: String?, code: String): Boolean {
        val (salt, hash) = stored?.split(":")?.takeIf { it.size == 2 } ?: return false
        return sha(salt + code.trim()) == hash
    }
}

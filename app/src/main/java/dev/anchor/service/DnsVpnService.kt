package dev.anchor.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.anchor.policy.PolicyStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Local DNS filter. Only the fake DNS server address is routed into the tunnel, so normal traffic
 * is untouched. Blocked names get NXDOMAIN; everything else is forwarded to a public resolver.
 */
class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "dev.anchor.STOP_VPN"
        private const val TUN_ADDR = "10.111.222.2"
        private const val DNS_ADDR = "10.111.222.1"
        private val UPSTREAM = listOf("1.1.1.1", "9.9.9.9")
        @Volatile var running = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); stopSelf(); return START_NOT_STICKY }
        if (!running) start()
        return START_STICKY
    }

    override fun onRevoke() { stop(); super.onRevoke() }
    override fun onDestroy() { stop(); super.onDestroy() }

    private fun start() {
        PolicyStore.init(this)
        val t = runCatching {
            Builder().setSession("Hafen DNS filter")
                .addAddress(TUN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setBlocking(true)
                .establish()
        }.getOrNull() ?: return
        tun = t
        running = true
        val out = FileOutputStream(t.fileDescriptor)
        thread(name = "hafen-dns") {
            val input = FileInputStream(t.fileDescriptor)
            val buf = ByteArray(32767)
            while (running) {
                val n = try { input.read(buf) } catch (e: IOException) { break }
                if (n <= 0) continue
                val pkt = buf.copyOf(n)
                pool.execute { runCatching { handle(pkt, out) } }
            }
        }
    }

    private fun stop() {
        running = false
        runCatching { tun?.close() }
        tun = null
    }

    // ---------- packet handling (IPv4 / UDP / DNS) ----------

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }

    private fun handle(pkt: ByteArray, out: FileOutputStream) {
        if (pkt.size < 28 || (pkt[0].toInt() shr 4) != 4) return
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17) return // UDP only
        val total = minOf(u16(pkt, 2), pkt.size)
        if (u16(pkt, ihl + 2) != 53) return
        val dns = pkt.copyOfRange(ihl + 8, total)
        if (dns.size < 12) return
        val (name, qEnd) = question(dns) ?: return
        val reply = if (Blocklist.blocked(name)) {
            Log.i("AnchorDNS", "blocked $name")
            nxdomain(dns, qEnd)
        } else forward(dns) ?: return
        val resp = wrap(pkt, ihl, reply)
        synchronized(out) { out.write(resp) }
    }

    /** Returns (lowercase name, offset after QTYPE/QCLASS). */
    private fun question(d: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        if (i + 4 > d.size) return null
        return sb.toString().lowercase() to i + 4
    }

    private fun nxdomain(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOf(qEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte() // QR=1, keep RD
        r[3] = 0x83.toByte()                                // RA=1, RCODE=3 (NXDOMAIN)
        put16(r, 4, 1); put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }

    private fun forward(q: ByteArray): ByteArray? {
        for (host in UPSTREAM) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    return buf.copyOf(p.length)
                }
            } catch (_: IOException) { }
        }
        return null
    }

    private fun wrap(req: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val r = ByteArray(total)
        r[0] = 0x45; put16(r, 2, total); put16(r, 6, 0x4000); r[8] = 64; r[9] = 17
        System.arraycopy(req, 16, r, 12, 4) // src = original dst
        System.arraycopy(req, 12, r, 16, 4) // dst = original src
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(r, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        put16(r, 10, sum.inv() and 0xFFFF)
        put16(r, 20, u16(req, ihl + 2)) // src port = 53
        put16(r, 22, u16(req, ihl))     // dst port = client's
        put16(r, 24, 8 + payload.size)
        put16(r, 26, 0)                 // UDP checksum optional on IPv4
        System.arraycopy(payload, 0, r, 28, payload.size)
        return r
    }
}

object Blocklist {
    private var source: Any? = null
    private var set: Set<String> = emptySet()

    fun blocked(name: String): Boolean {
        val p = PolicyStore.policy.value
        if (!p.dnsFilterEnabled) return false
        val minute = System.currentTimeMillis() / 60_000
        if (source != p to minute) {
            source = p to minute
            set = dev.anchor.policy.Targets.activeDomains(p)
        }
        var n = name.removeSuffix(".")
        while (true) {
            if (n in set) return true
            val dot = n.indexOf('.')
            if (dot < 0) return false
            n = n.substring(dot + 1)
        }
    }
}

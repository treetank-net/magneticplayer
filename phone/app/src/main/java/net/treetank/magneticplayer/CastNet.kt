package net.treetank.magneticplayer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Chromecasts on the network seen from the phone, without the agent and without the Cast SDK: mDNS discovery (NsdManager) and our own client
 * for the Cast v2 protocol (the same as in pychromecast). Lets Remote see and control what plays on any
 * Chromecast, whoever started it (the web page, another phone, YouTube), also away from home. Description: docs/devices.md.
 */
object CastNet {
    private const val TAG = "MpCastNet"
    private const val SERVICE = "_googlecast._tcp."

    data class Device(val id: String, val name: String, val model: String, val host: String, val port: Int)

    /** State of one Chromecast: app, volume, and media when something is playing. */
    data class Status(
        val device: Device, val app: String?, val volume: Double, val muted: Boolean, val fixedVolume: Boolean,
        val title: String?, val state: String?, val pos: Double, val dur: Double, val at: Long = System.currentTimeMillis(),
        /** the app is the idle screen (Backdrop, ambient mode), not something someone opened */
        val idleScreen: Boolean = false,
    ) {
        val active get() = state == "PLAYING" || state == "PAUSED" || state == "BUFFERING"
        /** an open app (e.g. Spotify after listening) that plays nothing: Remote lets you close it */
        val openIdle get() = !active && !idleScreen && !app.isNullOrBlank()
    }

    /** id (as in TXT `id`, 32 hex chars) → device; Compose state so Remote refreshes the chips */
    val devices = mutableStateMapOf<String, Device>()
    /** when each Chromecast last answered discovery: [devices] keeps the ones of other networks too ([Home]) */
    private val seenAt = ConcurrentHashMap<String, Long>()
    fun seenSince(t: Long): Set<String> = seenAt.filterValues { it >= t }.keys
    /** last state of each device (null = not queried yet or not responding) */
    val status = mutableStateMapOf<String, Status>()

    private val clients = ConcurrentHashMap<String, Client>()
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    /** for messages in the phone's language (set in [discover]) */
    @Volatile private var app: Context? = null

    /** Looks for Chromecasts until you call the returned function (Remote on screen). */
    fun discover(ctx: Context): () -> Unit {
        app = ctx.applicationContext
        val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.offer(info); resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        runCatching { m.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Log.w(TAG, "discover", it) }
        return { runCatching { m.stopServiceDiscovery(l) }; if (listener === l) listener = null }
    }

    /** NsdManager resolves one service at a time (old API), hence the queue. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        synchronized(this) { if (resolving) return; resolving = true }
        val info = resolveQueue.poll() ?: run { resolving = false; return }
        m.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { resolving = false; resolveNext() }
            override fun onServiceResolved(si: NsdServiceInfo) {
                fun txt(k: String) = si.attributes[k]?.let { String(it, Charsets.UTF_8) }
                val id = txt("id")
                val host = si.host?.hostAddress
                if (id != null && host != null && txt("md") != "Google Cast Group") {
                    devices[id] = Device(id, txt("fn") ?: si.serviceName, txt("md") ?: "", host, si.port)
                    seenAt[id] = System.currentTimeMillis()
                }
                resolving = false; resolveNext()
            }
        })
    }

    private fun client(d: Device): Client {
        val c = clients[d.id]
        if (c != null && c.alive && c.host == d.host) return c
        c?.close()
        return Client(d.host, d.port).also { clients[d.id] = it }
    }

    /** Reads the state of all known Chromecasts (IO thread). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (d in devices.values.toList()) {
            val s = runCatching { readStatus(d) }.onFailure { Log.d(TAG, "${d.name}: ${it.message}") }.getOrNull()
            if (s != null) status[d.id] = s else status.remove(d.id)
        }
    }

    /** Chromecasts where something is playing now. */
    fun playing(): List<Status> = status.values.filter { it.active }.sortedBy { it.device.name }

    /** Chromecasts with an open app that plays nothing (Spotify left over after listening). */
    fun openIdle(): List<Status> = status.values.filter { it.openIdle }.sortedBy { it.device.name }

    suspend fun statusOf(id: String): Status? = withContext(Dispatchers.IO) {
        val d = devices[id] ?: return@withContext null
        runCatching { readStatus(d) }.getOrNull()?.also { status[id] = it }
    }

    private fun readStatus(d: Device): Status {
        val c = client(d)
        val r = c.request(Client.NS_RECEIVER, "receiver-0", JSONObject().put("type", "GET_STATUS"))
        val st = r.getJSONObject("status")
        val vol = st.optJSONObject("volume") ?: JSONObject()
        val app = st.optJSONArray("applications")?.optJSONObject(0)
        val base = Status(d, app?.optString("displayName"), vol.optDouble("level", 0.0), vol.optBoolean("muted"),
            vol.optString("controlType") == "fixed", null, null, 0.0, 0.0,
            idleScreen = app?.optBoolean("isIdleScreen") == true || app?.optString("displayName") == "Backdrop")
        // sessionId also for apps without media (e.g. Spotify on Google TV), so "Stop" can close it
        c.appSession = app?.optString("sessionId")?.takeIf { it.isNotBlank() }
        if (app == null || !hasMedia(app)) return base
        val tid = app.getString("transportId")
        val m = c.request(Client.NS_MEDIA, tid, JSONObject().put("type", "GET_STATUS"))
        val ms = m.optJSONArray("status")?.optJSONObject(0) ?: return base
        c.mediaSession = ms.optInt("mediaSessionId")
        c.transport = tid
        val media = ms.optJSONObject("media")
        return base.copy(
            title = media?.optJSONObject("metadata")?.optString("title")?.takeIf { it.isNotBlank() }
                ?: media?.optString("contentId")?.substringAfterLast('/')?.substringBefore('?'),
            state = ms.optString("playerState"), pos = ms.optDouble("currentTime", 0.0),
            dur = media?.optDouble("duration", 0.0)?.takeIf { !it.isNaN() } ?: 0.0)
    }

    private fun hasMedia(app: JSONObject): Boolean {
        val ns = app.optJSONArray("namespaces") ?: return false
        return (0 until ns.length()).any { ns.optJSONObject(it)?.optString("name") == Client.NS_MEDIA }
    }

    /** Command for the media on device `id`: PLAY, PAUSE, STOP, SEEK (with `currentTime`). */
    suspend fun media(id: String, type: String, extra: JSONObject.() -> Unit = {}) = withContext(Dispatchers.IO) {
        val d = devices[id] ?: return@withContext
        val c = client(d)
        if (c.transport == null) readStatus(d)
        val tid = c.transport ?: return@withContext
        c.request(Client.NS_MEDIA, tid, JSONObject().put("type", type).put("mediaSessionId", c.mediaSession).apply(extra))
    }

    suspend fun setVolume(id: String, level: Double? = null, muted: Boolean? = null) = withContext(Dispatchers.IO) {
        val d = devices[id] ?: return@withContext
        val v = JSONObject().apply { if (level != null) put("level", level.coerceIn(0.0, 1.0)); if (muted != null) put("muted", muted) }
        client(d).request(Client.NS_RECEIVER, "receiver-0", JSONObject().put("type", "SET_VOLUME").put("volume", v))
    }

    /** Closes the app on the Chromecast (also someone else's: that is how "Stop" works). */
    suspend fun stopApp(id: String) = withContext(Dispatchers.IO) {
        val d = devices[id] ?: return@withContext
        val c = client(d)
        readStatus(d)  // fresh sessionId: the app may have changed
        val sid = c.appSession ?: return@withContext
        c.request(Client.NS_RECEIVER, "receiver-0", JSONObject().put("type", "STOP").put("sessionId", sid))
        status.remove(id)
    }

    /**
     * One Cast v2 connection: TLS on :8009, frames = 4 bytes of length + protobuf CastMessage (hand-encoded).
     * The reader thread answers PING and dispatches replies by `requestId`. Closes itself after a minute of no use.
     */
    class Client(val host: String, port: Int) {
        companion object {
            const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
            const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
            const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
            const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
            private const val SRC = "sender-mp"
            private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })
        }

        private val sock: SSLSocket
        private val out: OutputStream
        private val ids = AtomicInteger(1)
        private val waiting = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
        private val connected = ConcurrentHashMap.newKeySet<String>()
        @Volatile var alive = true; private set
        @Volatile private var lastUse = System.currentTimeMillis()
        @Volatile var transport: String? = null
        @Volatile var mediaSession: Int = 0
        @Volatile var appSession: String? = null

        init {
            // the Chromecast has a certificate signed by Google for the device, not for the address: we only check that it is TLS on the LAN
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            sock = ssl.socketFactory.createSocket() as SSLSocket
            sock.connect(java.net.InetSocketAddress(host, port), 3000)
            sock.soTimeout = 0
            sock.startHandshake()
            out = sock.outputStream
            Thread({ readLoop() }, "castnet-read-$host").apply { isDaemon = true }.start()
            Thread({ heartbeat() }, "castnet-ping-$host").apply { isDaemon = true }.start()
            ensureConnected("receiver-0")
        }

        private fun ensureConnected(dest: String) {
            if (connected.add(dest)) send(NS_CONN, dest, JSONObject().put("type", "CONNECT").toString())
        }

        fun request(ns: String, dest: String, body: JSONObject, timeoutMs: Long = 4000): JSONObject {
            lastUse = System.currentTimeMillis()
            ensureConnected(dest)
            val id = ids.getAndIncrement()
            val q = LinkedBlockingQueue<JSONObject>(1)
            waiting[id] = q
            try {
                send(ns, dest, body.put("requestId", id).toString())
                return q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw java.io.IOException(app?.getString(R.string.main_cast_no_answer) ?: "Chromecast didn't respond")
            } finally {
                waiting.remove(id)
            }
        }

        @Synchronized
        private fun send(ns: String, dest: String, payload: String) {
            val b = ByteArrayOutputStream()
            fun varint(v0: Int) { var v = v0; while (v and 0x7f.inv() != 0) { b.write((v and 0x7f) or 0x80); v = v ushr 7 }; b.write(v) }
            fun str(tag: Int, s: String) { val x = s.toByteArray(Charsets.UTF_8); b.write(tag); varint(x.size); b.write(x) }
            b.write(0x08); varint(0)          // protocol_version = CASTV2_1_0
            str(0x12, SRC); str(0x1a, dest); str(0x22, ns)
            b.write(0x28); varint(0)          // payload_type = STRING
            str(0x32, payload)
            val msg = b.toByteArray()
            try {
                out.write(byteArrayOf((msg.size ushr 24).toByte(), (msg.size ushr 16).toByte(), (msg.size ushr 8).toByte(), msg.size.toByte()))
                out.write(msg); out.flush()
            } catch (e: Exception) {
                close(); throw e
            }
        }

        private fun readLoop() {
            try {
                val inp = DataInputStream(sock.inputStream)
                while (alive) {
                    val len = inp.readInt()
                    if (len !in 1..1_000_000) throw java.io.IOException("bad frame")
                    val buf = ByteArray(len); inp.readFully(buf)
                    val (ns, payload) = parse(buf)
                    val j = runCatching { JSONObject(payload ?: continue) }.getOrNull() ?: continue
                    when {
                        ns == NS_HEARTBEAT && j.optString("type") == "PING" -> send(NS_HEARTBEAT, "receiver-0", """{"type":"PONG"}""")
                        ns == NS_CONN && j.optString("type") == "CLOSE" -> { transport = null; connected.removeIf { it != "receiver-0" } }
                        else -> j.optInt("requestId", 0).takeIf { it != 0 }?.let { waiting[it]?.offer(j) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        /** Reads fields 4 (namespace) and 6 (payload_utf8) from CastMessage; the rest is skipped. */
        private fun parse(b: ByteArray): Pair<String?, String?> {
            var i = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 } }
            var ns: String? = null; var payload: String? = null
            while (i < b.size) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val n = varint().toInt()
                        val s = String(b, i, n, Charsets.UTF_8); i += n
                        when (key ushr 3) { 4 -> ns = s; 6 -> payload = s }
                    }
                    5 -> i += 4
                    1 -> i += 8
                    else -> return ns to payload
                }
            }
            return ns to payload
        }

        private fun heartbeat() {
            while (alive) {
                Thread.sleep(5000)
                if (System.currentTimeMillis() - lastUse > 60_000) { close(); return }
                runCatching { send(NS_HEARTBEAT, "receiver-0", """{"type":"PING"}""") }
            }
        }

        fun close() {
            if (!alive) return
            alive = false
            runCatching { sock.close() }
            waiting.values.forEach { it.offer(JSONObject()) }
        }
    }
}

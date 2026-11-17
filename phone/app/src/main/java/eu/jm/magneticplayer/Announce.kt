package eu.jm.magneticplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Rozgłaszanie „co gra” między pilotami (telefony z apką i agenci): UDP multicast 239.255.77.77:47700, TTL 1, JSON
 * `{"mp":1,"type":"playing"|"stopped"|"who","from":{kind,id,name},"target":{kind,id,name,ctl},"title","state","pos","dur","ts"}`.
 * Nadawca powtarza co ~3 s, słuchacz zapomina po 10 s ciszy; „who” prosi o natychmiastową odpowiedź.
 * Bez sekretów: `ctl` to adresy sterowania DLNA/Chromecasta, które w LAN i tak nie wymagają logowania.
 * Opis: docs/plan-piloty.md, docs/urzadzenia.md.
 */
object Announce {
    private const val TAG = "MpAnnounce"
    const val GROUP = "239.255.77.77"
    const val PORT = 47700
    private const val TTL_MS = 10_000L

    /** Co gra u innych (klucz: rodzaj:id celu). */
    data class Entry(
        val kind: String, val targetId: String, val targetName: String, val ctl: JSONObject,
        val title: String, val state: String, val pos: Double, val dur: Double,
        val fromId: String, val fromName: String, val at: Long = System.currentTimeMillis(),
    ) {
        val key get() = "$kind:$targetId"
    }

    val others = mutableStateMapOf<String, Entry>()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "announce-send").apply { isDaemon = true } }
    @Volatile private var listeners = 0
    @Volatile private var sock: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    /** odpowiedź na „who”: co gra u nas (ustawia RemotePlayer) */
    @Volatile var mine: List<JSONObject> = emptyList()

    private fun selfId(ctx: Context): String {
        val p = ctx.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)
        return p.getString("announce_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("announce_id", it).apply() }
    }

    private fun from(ctx: Context) = JSONObject().put("kind", "phone").put("id", selfId(ctx))
        .put("name", listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" "))

    /** Wiadomość „gra” dla celu; `ctl` = jak sterować bez nas. */
    fun playing(ctx: Context, kind: String, id: String, name: String, ctl: JSONObject, title: String, state: String,
                pos: Double, dur: Double): JSONObject =
        JSONObject().also { app = ctx.applicationContext }.put("mp", 1).put("type", "playing").put("from", from(ctx))
            .put("target", JSONObject().put("kind", kind).put("id", id).put("name", name).put("ctl", ctl))
            .put("title", title).put("state", state).put("pos", pos).put("dur", dur).put("ts", System.currentTimeMillis() / 1000)

    @Volatile private var app: Context? = null

    fun stopped(kind: String, id: String) {
        val ctx = app ?: return
        mine = mine.filterNot { it.optJSONObject("target")?.optString("id") == id }
        send(JSONObject().put("mp", 1).put("type", "stopped").put("from", from(ctx))
            .put("target", JSONObject().put("kind", kind).put("id", id)))
    }

    fun send(msg: JSONObject) {
        val data = msg.toString().toByteArray(Charsets.UTF_8)
        io.execute {
            runCatching {
                val s = sock ?: MulticastSocket().apply { timeToLive = 1; lanIf()?.let { networkInterface = it } }
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(GROUP), PORT))
                if (s !== sock) s.close()
                Log.d(TAG, "send ${msg.optString("type")} przez ${s.networkInterface?.name}")
            }.onFailure { Log.w(TAG, "send: $it") }
        }
    }

    /** Słucha, dopóki nie wywołasz zwróconej funkcji (Pilot na ekranie, usługa sesji). Liczy wywołujących. */
    @Synchronized
    fun listen(ctx: Context): () -> Unit {
        val app = ctx.applicationContext
        if (listeners++ == 0) start(app)
        var done = false
        return {
            synchronized(this) {
                if (!done) { done = true; if (--listeners == 0) stop() }
            }
        }
    }

    private fun start(ctx: Context) {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("magneticplayer-announce").apply { setReferenceCounted(false); runCatching { acquire() } }
        val me = selfId(ctx)
        Thread({
            try {
                val s = MulticastSocket(null).apply {
                    reuseAddress = true; bind(InetSocketAddress(PORT)); timeToLive = 1; soTimeout = 2000
                }
                val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
                val ifs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                for (i in ifs) runCatching { s.joinGroup(group, i) }
                // bez tego Android wysyła multicast domyślną trasą (np. dane komórkowe), a nie przez Wi-Fi
                lanIf()?.let { runCatching { s.networkInterface = it } }
                sock = s
                send(JSONObject().put("mp", 1).put("type", "who").put("from", from(ctx)))
                val buf = ByteArray(8192)
                while (sock === s) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { expire(); continue }
                    val j = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    if (j.optInt("mp") != 1 || j.optJSONObject("from")?.optString("id") == me) continue
                    handle(j)
                    expire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "listen: ${e.message}")
            }
        }, "announce-listen").apply { isDaemon = true }.start()
    }

    /** Interfejs sieci lokalnej (Wi-Fi/Ethernet z prywatnym IPv4). */
    private fun lanIf(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { i ->
            i.isUp && !i.isLoopback && i.supportsMulticast() &&
                i.inetAddresses.toList().any { it is java.net.Inet4Address && it.isSiteLocalAddress }
        }.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }.firstOrNull()
    }.getOrNull()

    private fun handle(j: JSONObject) {
        val f = j.optJSONObject("from") ?: return
        val t = j.optJSONObject("target")
        when (j.optString("type")) {
            "who" -> mine.forEach { send(it) }
            "stopped" -> t?.let { others.remove(it.optString("kind") + ":" + it.optString("id")) }
            "playing" -> if (t != null) {
                val e = Entry(t.optString("kind"), t.optString("id"), t.optString("name"), t.optJSONObject("ctl") ?: JSONObject(),
                    j.optString("title"), j.optString("state"), j.optDouble("pos", 0.0), j.optDouble("dur", 0.0),
                    f.optString("id"), f.optString("name"))
                others[e.key] = e
            }
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        others.values.filter { now - it.at > TTL_MS }.forEach { others.remove(it.key) }
    }

    private fun stop() {
        val s = sock
        sock = null
        runCatching { s?.close() }
        runCatching { lock?.release() }
        lock = null
    }

    /** Telewizor DLNA, na który film puścił ktoś inny (rozgłoszony z `ctl`). */
    fun foreignDlna(): Entry? = others.values.filter { it.kind == "dlna" && it.ctl.has("avt") }.maxByOrNull { it.at }
}

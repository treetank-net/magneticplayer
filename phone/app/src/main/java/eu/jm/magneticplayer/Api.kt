package eu.jm.magneticplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder

class ApiException(msg: String, val code: Int = 0) : Exception(msg)

class Settings(ctx: Context) {
    val ctx: Context = ctx.applicationContext
    private val p = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    // adres, token i MAC serwerów: Servers (dawne pola host/port/token/mac czyta tylko migracja)
    var sequential: Boolean
        get() = p.getBoolean("sequential", true)
        set(v) = p.edit().putBoolean("sequential", v).apply()
    /** Pilot nie wygasza ekranu, gdy coś gra */
    var pilotKeepScreen: Boolean
        get() = p.getBoolean("pilot_keep_screen", true)
        set(v) = p.edit().putBoolean("pilot_keep_screen", v).apply()
    /** domyślna akcja po pobraniu: none | stop | suspend */
    var afterDefault: String
        get() = p.getString("after", "none")!!
        set(v) = p.edit().putString("after", v).apply()
    var subLanguages: String
        get() = p.getString("sub_langs", "en,pl")!!
        set(v) = p.edit().putString("sub_langs", v.replace(" ", "").lowercase()).apply()

    /** ile minut filmu apka pobiera do przodu na dysk telefonu (MediaCache) */
    var bufferMinutes: Int
        get() = p.getInt("buffer_min", 5)
        set(v) = p.edit().putInt("buffer_min", v).apply()

    /** nazwa ostatnio używanego telewizora (Chromecast albo DLNA), do „ostatnio” w wyborze ekranu */
    var lastTv: String?
        get() = p.getString("last_tv", null)
        set(v) = p.edit().putString("last_tv", v).apply()

    /** pytać o przesłanie filmów pobranych przeglądarką na serwer (Uploads.kt) */
    var uploadAsk: Boolean
        get() = p.getBoolean("upload_ask", false)
        set(v) = p.edit().putBoolean("upload_ask", v).apply()
    /** serwer docelowy przesyłania (klucz z Servers); null = serwer plików z features.upload */
    var uploadServer: String?
        get() = p.getString("upload_server", null)
        set(v) = p.edit().putString("upload_server", v).apply()


    fun position(key: String): Long = p.getLong("pos:$key", 0)
    fun setPosition(key: String, ms: Long) = p.edit().putLong("pos:$key", ms).apply()

    // bufor na dysku: ile filmu obejrzano (0–1) i kiedy ostatnio oglądano, do sprzątania w MediaCache.sweep
    fun cacheWatched(key: String): Float = p.getFloat("cw:$key", 0f)
    fun cacheUsed(key: String): Long = p.getLong("cu:$key", 0)
    fun touchCache(key: String, watched: Float) =
        p.edit().putFloat("cw:$key", watched).putLong("cu:$key", System.currentTimeMillis()).apply()
    fun forgetCache(key: String) = p.edit().remove("cw:$key").remove("cu:$key").apply()

}

data class Torrent(
    val id: String, val name: String, val progress: Double, val state: String,
    val downRate: Long, val upRate: Long, val eta: Long, val size: Long,
    val sequential: Boolean, val peers: Int,
    /** dodany z apki */
    val mine: Boolean, val after: String,
)

data class Job(
    val id: String, val name: String, val added: Double, val after: String, val afterDone: Boolean,
    val magnet: String?, val inClient: Boolean, val progress: Double?,
)

data class LibRoot(val index: Int, val name: String, val path: String, val exists: Boolean)
data class LibFile(val name: String, val size: Long, val media: String?, val subs: List<String>)
data class LibDir(val root: Int, val path: String, val dirs: List<String>, val files: List<LibFile>)
/** Postęp wyszukiwania napisów na komputerze (każde źródło osobno). */
data class SubsProvider(val name: String, val state: String, val count: Int?, val error: String?, val secs: Double?)
data class SubsFound(val query: String, val title: String, val year: Int?, val results: List<SubResult>, val hidden: Int)
data class SubsProgress(
    val phase: String, val round: Int, val query: String?, val matched: Int, val total: Int,
    val providers: List<SubsProvider>, val elapsed: Double, val done: Boolean, val error: String?, val found: SubsFound?,
)

data class SubResult(
    val id: String, val provider: String, val lang: String, val release: String, val score: Int,
    val hashMatch: Boolean, val hi: Boolean,
) {
    val key get() = "$provider:$id"
}
data class SubFile(val name: String, val lang: String, val ext: String)

val AFTER_LABELS = linkedMapOf(
    "none" to "nic (seeduje dalej)",
    "stop" to "zatrzymaj torrent",
    "suspend" to "uśpij komputer",
)

/**
 * Wskazanie pliku na serwerze `srv` (klucz z [Servers]): z torrenta, z biblioteki albo „to, co gra teraz w mpv”.
 * [key] (bufor na dysku, pozycja oglądania) ma prefiks serwera, poza serwerem [LEGACY] (dawne klucze zostają).
 */
sealed class MediaRef {
    abstract val title: String
    abstract val srv: String
    /** wskazanie dla API serwera (bez `srv`) */
    abstract fun json(o: JSONObject = JSONObject()): JSONObject
    abstract fun query(): String
    protected abstract fun localKey(): String
    fun key() = Servers.prefixOf(srv) + localKey()

    data class InTorrent(val torrentId: String, val index: Int, override val title: String, override val srv: String = LEGACY) : MediaRef() {
        override fun json(o: JSONObject) = o.put("torrent_id", torrentId).put("file_index", index)
        override fun query() = "torrent_id=$torrentId&file_index=$index"
        override fun localKey() = "$torrentId/$index"
    }

    data class InLibrary(val root: Int, val path: String, override val srv: String = LEGACY) : MediaRef() {
        override val title get() = path.substringAfterLast('/')
        override fun json(o: JSONObject) = o.put("root", root).put("path", path)
        override fun query() = "root=$root&path=${Uri.encode(path)}"
        override fun localKey() = "lib$root/$path"
    }

    data class Playing(override val title: String, override val srv: String = LEGACY) : MediaRef() {
        override fun json(o: JSONObject) = o.put("playing", true)
        override fun query() = "playing=1"
        override fun localKey() = "playing"
    }

    fun encode(): String = json().put("title", title).put("srv", srv).toString()

    companion object {
        fun decode(s: String): MediaRef {
            val o = JSONObject(s)
            val srv = o.optString("srv").ifEmpty { LEGACY }
            return when {
                o.has("torrent_id") -> InTorrent(o.getString("torrent_id"), o.getInt("file_index"), o.optString("title"), srv)
                o.has("root") -> InLibrary(o.getInt("root"), o.getString("path"), srv)
                else -> Playing(o.optString("title"), srv)
            }
        }
    }
}

/** Film z innego serwera dla ekranu: URL i napisy z biletem (POST /api/media/ticket). */
data class Ticket(val url: String, val subs: List<String>, val title: String, val subInfo: List<SubSrc> = emptyList(),
                  /** "video/mp4", gdy bilet wskazuje wersję „dla Chromecasta” */
                  val contentType: String? = null)

data class TFile(
    val index: Int, val path: String, val size: Long, val progress: Double, val media: String?,
    /** bajty pobrane ciągiem od początku pliku — tyle da się obejrzeć */
    val ready: Long, val readySec: Double?, val duration: Double?,
) {
    val name get() = path.substringAfterLast('/')
}

object Wol {
    private fun packet(mac: String): ByteArray {
        val b = mac.split(':', '-').map { it.toInt(16).toByte() }
        require(b.size == 6) { "zły MAC: $mac" }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { b[it % 6] }
    }

    /** Magic packet na broadcast ogólny i podsieci /24 serwera, porty 9 i 7. */
    fun send(mac: String, host: String) {
        val data = packet(mac)
        val targets = mutableListOf("255.255.255.255")
        Regex("""^(\d+\.\d+\.\d+)\.\d+$""").find(host)?.let { targets += it.groupValues[1] + ".255" }
        DatagramSocket().use { s ->
            s.broadcast = true
            for (t in targets) for (port in listOf(9, 7)) {
                runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), port)) }
            }
        }
    }
}

/** API jednego serwera. `srv` to migawka z [Servers]; zmiany (MAC, info) zapisuje z powrotem. */
class Api(val s: Settings, val srv: Server) {
    val base get() = "http://${srv.host}:${srv.port}"
    val configured get() = srv.host.isNotBlank() && srv.token.isNotBlank()
    private val ctx get() = s.ctx

    companion object {
        fun active(ctx: Context) = Api(Settings(ctx), Servers.active(ctx))
        fun of(ctx: Context, key: String?): Api? = Servers.get(ctx, key)?.let { Api(Settings(ctx), it) }
        /** Serwer, z którego pochodzi film. */
        fun forRef(ctx: Context, ref: MediaRef): Api =
            of(ctx, ref.srv) ?: throw ApiException("Nie ma już serwera, z którego był ten film")
    }

    private fun call(method: String, path: String, body: JSONObject? = null, timeout: Int = 8000): String {
        if (!configured) throw ApiException("Dodaj serwer (Ustawienia → Dodaj serwer)")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("Authorization", "Bearer ${srv.token}")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code")
                throw ApiException(msg, code)
            }
            return text
        } catch (e: IOException) {
            throw ApiException("${srv.name.ifEmpty { "Serwer" }} nie odpowiada (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }

    fun get(path: String): String = call("GET", path)

    /**
     * Czy serwer odpowiada. Bez tokenu (/api/ping), a gdy pod adresem jest inny serwer (inne id), to tak jakby
     * nie odpowiadał: wtedy [wrongServer] = true i nie wysyłamy mu tokenu.
     */
    fun ping(timeout: Int = 1500): Boolean {
        if (srv.host.isBlank()) return false
        val c = runCatching { URL("$base/api/ping").openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val id = j.optString("id")
            wrongServer = srv.uid != null && id.isNotEmpty() && id != srv.uid
            j.optBoolean("ok") && !wrongServer
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    @Volatile var wrongServer = false

    fun infoRaw(): JSONObject = JSONObject(call("GET", "/api/info"))

    /** Info o serwerze; zapisuje możliwości, id i MAC do Wake-on-LAN. */
    fun info(): JSONObject {
        val j = infoRaw()
        val mac = j.optString("mac").takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "00:00:00:00:00:00" }
        val uid = j.optString("id").takeIf { it.isNotEmpty() }
        val name = j.optString("name").ifEmpty { j.optString("hostname") }
        // nazwa domyślna (adres, np. po migracji starego serwera) ustępuje nazwie z agenta; własnej nie ruszamy
        // ten telefon już kiedyś obudził serwer, a agent jeszcze o tym nie wie: dosyłamy
        val agentKnows = j.optJSONObject("power")?.optJSONObject("wake")?.optString("confidence") == "yes"
        if (!agentKnows && Servers.get(ctx, srv.key)?.wakeOk == true && j.optInt("api") >= 2) {
            runCatching { call("POST", "/api/wake/verified", JSONObject(), 4000) }
        }
        Servers.update(ctx, srv.key) {
            it.copy(info = j, mac = mac ?: it.mac, uid = it.uid ?: uid,
                name = if ((it.name.isBlank() || it.name == it.host) && name.isNotEmpty()) name else it.name)
        }
        return j
    }

    fun pairCode(): JSONObject = JSONObject(call("POST", "/api/pair/code", JSONObject()))
    /** Kod dołączenia nowego komputera: `{code, expires_in, url, command, command_full}` (mp_install.py). */
    fun installCode(): JSONObject = JSONObject(call("POST", "/api/install/code", JSONObject()))
    /** `{state: waiting|downloading|joined, server: {host, port, id, name, pair_code} | null, expires_in}` */
    fun installStatus(code: String): JSONObject = JSONObject(call("GET", "/api/install/status?code=$code", null, 4000))
    fun devices(): JSONArray = JSONArray(call("GET", "/api/devices"))
    fun revoke(id: String) { call("POST", "/api/devices/revoke", JSONObject().put("id", id)) }
    fun wakeOther(mac: String, host: String) { call("POST", "/api/wake", JSONObject().put("mac", mac).put("ip", host), 4000) }

    fun ticket(ref: MediaRef, variant: String? = null): Ticket {
        val o = JSONObject(call("POST", "/api/media/ticket", ref.json().apply { if (variant != null) put("variant", variant) }))
        val a = o.optJSONArray("subs") ?: JSONArray()
        val info = List(a.length()) { i ->
            a.getJSONObject(i).let { x ->
                val u = x.getString("url")
                // starszy agent nie podaje „vtt”, ale /sub umie &vtt=1
                SubSrc(x.optString("name"), x.optString("lang"), u, x.optString("vtt").ifEmpty { "$u&vtt=1" })
            }
        }
        return Ticket(o.getString("url"), info.map { it.url }, o.optString("title"), info,
            o.optString("content_type").takeIf { !o.isNull("content_type") && it.isNotEmpty() })
    }

    fun torrents(): List<Torrent> {
        val a = JSONArray(call("GET", "/api/torrents"))
        return List(a.length()) { i ->
            val t = a.getJSONObject(i)
            Torrent(
                t.getString("id"), t.getString("name"), t.optDouble("progress", 0.0), t.optString("state"),
                t.optLong("down_rate"), t.optLong("up_rate"), t.optLong("eta"), t.optLong("size"),
                t.optBoolean("sequential"), t.optInt("peers"),
                t.optBoolean("mine"), t.optString("after", "none"),
            )
        }
    }

    fun files(id: String): List<TFile> {
        val a = JSONArray(call("GET", "/api/torrents/$id/files"))
        return List(a.length()) { i ->
            val f = a.getJSONObject(i)
            TFile(f.getInt("index"), f.getString("path"), f.optLong("size"), f.optDouble("progress"),
                f.optString("media").takeIf { it.isNotEmpty() && it != "null" },
                f.optLong("ready"),
                f.optDouble("ready_sec").takeIf { !it.isNaN() },
                f.optDouble("duration").takeIf { !it.isNaN() })
        }
    }

    fun addMagnet(uri: String, sequential: Boolean, after: String = s.afterDefault): String? =
        JSONObject(call("POST", "/api/torrents",
            JSONObject().put("magnet", uri).put("sequential", sequential).put("after", after), 20000))
            .optString("id").takeIf { it.isNotEmpty() && it != "null" }

    fun setAfter(id: String, after: String) {
        call("POST", "/api/torrents/$id/after", JSONObject().put("after", after))
    }

    fun jobs(): List<Job> {
        val a = JSONArray(call("GET", "/api/jobs"))
        return List(a.length()) { i ->
            val j = a.getJSONObject(i)
            Job(j.getString("id"), j.optString("name"), j.optDouble("added", 0.0), j.optString("after", "none"),
                j.optBoolean("after_done"), j.optString("magnet").takeIf { it.startsWith("magnet:") },
                j.optBoolean("in_client"), j.optDouble("progress").takeIf { !it.isNaN() })
        }
    }

    // ---- biblioteka
    private fun roots(a: JSONArray) = List(a.length()) { i ->
        val r = a.getJSONObject(i)
        LibRoot(r.getInt("index"), r.getString("name"), r.getString("path"), r.optBoolean("exists"))
    }

    fun libraryRoots(): List<LibRoot> = roots(JSONArray(call("GET", "/api/library")))
    fun libraryAdd(path: String): List<LibRoot> =
        roots(JSONArray(call("POST", "/api/library", JSONObject().put("op", "add").put("path", path))))
    fun libraryRemove(index: Int): List<LibRoot> =
        roots(JSONArray(call("POST", "/api/library", JSONObject().put("op", "remove").put("index", index))))

    fun browse(root: Int, path: String, all: Boolean = false): LibDir {
        val o = JSONObject(call("GET", "/api/library/browse?root=$root&path=${Uri.encode(path)}&all=${if (all) 1 else 0}"))
        val d = o.getJSONArray("dirs")
        val f = o.getJSONArray("files")
        return LibDir(root, o.optString("path"), List(d.length()) { d.getString(it) }, List(f.length()) { i ->
            val x = f.getJSONObject(i)
            val subs = x.optJSONArray("subs") ?: JSONArray()
            LibFile(x.getString("name"), x.optLong("size"), x.optString("media").takeIf { it.isNotEmpty() && it != "null" },
                List(subs.length()) { subs.getString(it) })
        })
    }

    /** Katalogi komputera do wyboru nowego katalogu biblioteki; path "" = punkty startowe. */
    fun fsDirs(path: String): Pair<String?, List<String>> {
        val o = JSONObject(call("GET", "/api/fs?path=${Uri.encode(path)}"))
        val d = o.getJSONArray("dirs")
        return o.optString("parent").takeIf { !o.isNull("parent") } to List(d.length()) { d.getString(it) }
    }

    // ---- napisy
    fun subsList(ref: MediaRef): List<SubFile> {
        val a = JSONArray(call("GET", "/api/subs/list?${ref.query()}"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            SubFile(o.getString("name"), o.optString("lang"), o.optString("ext"))
        }
    }

    fun subUrl(ref: MediaRef, name: String) = "$base/sub?${ref.query()}&name=${Uri.encode(name)}&token=${Uri.encode(srv.token)}"

    private fun subsBody(ref: MediaRef, title: String?, year: Int?) = ref.json().put("languages", s.subLanguages).apply {
        if (!title.isNullOrBlank()) put("title", title.trim())
        if (year != null) put("year", year)
    }

    /** Startuje wyszukiwanie w tle; postęp przez [subsJob]. */
    fun subsSearchStart(ref: MediaRef, title: String? = null, year: Int? = null): String =
        JSONObject(call("POST", "/api/subs/search", subsBody(ref, title, year).put("async", true))).getString("job")

    /** „Wystarczy”: agent przestaje czekać na wolne źródła i oddaje to, co już ma. */
    fun subsStop(id: String) {
        call("POST", "/api/subs/job/stop", JSONObject().put("id", id))
    }

    fun subsJob(id: String): SubsProgress {
        val o = JSONObject(call("GET", "/api/subs/job?id=$id"))
        val pr = o.optJSONObject("providers") ?: JSONObject()
        val providers = pr.keys().asSequence().map { k ->
            val p = pr.getJSONObject(k)
            SubsProvider(k, p.optString("state"), if (p.has("count")) p.optInt("count") else null,
                p.optString("error").takeIf { it.isNotEmpty() }, if (p.has("secs")) p.optDouble("secs") else null)
        }.toList()
        val found = o.optJSONObject("result")?.let { r ->
            val a = r.getJSONArray("results")
            SubsFound(r.optString("query"), r.optString("title"), r.optInt("year").takeIf { it > 0 }, List(a.length()) { i ->
                val x = a.getJSONObject(i)
                SubResult(x.getString("id"), x.optString("provider"), x.optString("lang"), x.optString("release"),
                    x.optInt("score"), x.optBoolean("hash_match"), x.optBoolean("hi"))
            }, r.optInt("hidden"))
        }
        return SubsProgress(o.optString("phase"), o.optInt("round", 1), o.optString("query").takeIf { it.isNotEmpty() && it != "null" },
            o.optInt("matched"), o.optInt("total"), providers, o.optDouble("elapsed"), o.optBoolean("done"),
            o.optString("error").takeIf { it.isNotEmpty() }, found)
    }

    fun subsDownload(ref: MediaRef, r: SubResult, title: String? = null, year: Int? = null): String =
        JSONObject(call("POST", "/api/subs/download",
            subsBody(ref, title, year).put("id", r.id).put("provider", r.provider), 90000))
            .optString("name")

    fun openSubsConfig(): JSONObject = JSONObject(call("GET", "/api/config/opensubtitles"))
    fun setOpenSubsConfig(apiKey: String?, user: String?, password: String?) {
        val o = JSONObject()
        if (apiKey != null) o.put("api_key", apiKey)
        if (user != null) o.put("username", user)
        if (!password.isNullOrEmpty()) o.put("password", password)
        call("POST", "/api/config/opensubtitles", o)
    }

    fun action(id: String, action: String) {
        call("POST", "/api/torrents/$id/action", JSONObject().put("action", action))
    }

    /** Format pliku i ocena dla Chromecasta, wersja „dla Chromecasta” i jej przygotowywanie (agent 1.2+). */
    fun mediaProbe(ref: MediaRef): JSONObject = JSONObject(call("GET", "/api/media/probe?${ref.query()}", timeout = 30000))
    fun mediaConvert(ref: MediaRef, op: String? = null): JSONObject =
        JSONObject(call("POST", "/api/media/convert", ref.json().apply { if (op != null) put(op, true) }))

    /** Chromecast widziany przez agenta (dowolny nadawca): `{active, device, id, app, title, state, pos, …, all: [...]}`. */
    fun castStatus(): JSONObject = JSONObject(call("GET", "/api/cast/status", timeout = 3000))

    fun castCmd(cmd: String, value: Any? = null, device: String? = null) {
        val b = JSONObject().put("cmd", cmd)
        if (value != null) b.put("value", value)
        if (device != null) b.put("device", device)
        call("POST", "/api/cast/cmd", b, 6000)
    }

    fun playerStatus(): JSONObject = JSONObject(call("GET", "/api/player/status", timeout = 3000))

    fun playerCmd(cmd: String, value: Any? = null) {
        val b = JSONObject().put("cmd", cmd)
        if (value != null) b.put("value", value)
        call("POST", "/api/player/cmd", b, 4000)
    }

    fun playerOpen(ref: MediaRef, startSec: Double? = null) {
        val b = ref.json()
        if (startSec != null) b.put("start", startSec)
        call("POST", "/api/player/open", b, 20000)
    }

    /** Film z innego serwera (URL z biletem). */
    fun playerOpenUrl(t: Ticket, startSec: Double? = null) {
        val b = JSONObject().put("url", t.url).put("subs", JSONArray(t.subs)).put("title", t.title)
        if (startSec != null && startSec > 0) b.put("start", startSec)
        call("POST", "/api/player/open", b, 25000)
    }

    fun suspendPc() { call("POST", "/api/system/suspend") }

    // ---- przesyłanie filmów z telefonu na serwer (Uploads.kt; agent z features.upload)
    /** `GET /api/upload/<id>` → kod HTTP i JSON (`received`, `done`); błędy HTTP nie rzucają, bo 404/409/507 niosą stan. */
    fun uploadStatus(id: String): Pair<Int, JSONObject> = uploadRequest("GET", "/api/upload/$id", 0, null, null)

    /**
     * `PUT /api/upload/<id>`: [length] bajtów z [input] (strumieniowo, bez ładowania do pamięci); [length] = 0 to samo
     * sprawdzenie (offset, wolne miejsce). [onProgress] dostaje liczbę wysłanych bajtów i może przerwać wyjątkiem.
     */
    fun uploadPut(id: String, name: String, size: Long, offset: Long, input: java.io.InputStream?, length: Long,
                  onProgress: ((Long) -> Unit)? = null): Pair<Int, JSONObject> =
        uploadRequest("PUT", "/api/upload/$id?name=${Uri.encode(name)}&size=$size&offset=$offset", length, input, onProgress)

    private fun uploadRequest(method: String, path: String, length: Long, input: java.io.InputStream?,
                              onProgress: ((Long) -> Unit)?): Pair<Int, JSONObject> {
        if (!configured) throw ApiException("Dodaj serwer (Ustawienia → Dodaj serwer)")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 90_000
            c.setRequestProperty("Authorization", "Bearer ${srv.token}")
            if (method == "PUT") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(length)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    val buf = ByteArray(256 shl 10)
                    var sent = 0L
                    while (input != null && sent < length) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt())
                        if (n < 0) throw IOException("plik na telefonie jest krótszy, niż był")
                        out.write(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent)
                    }
                }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        } catch (e: IOException) {
            throw ApiException("${srv.name.ifEmpty { "Serwer" }} nie odpowiada (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }
    fun screenOn() { call("POST", "/api/system/screen_on") }

    fun mediaUrl(ref: MediaRef): String = when (ref) {
        is MediaRef.InTorrent -> "$base/media/${ref.torrentId}/${ref.index}/${Uri.encode(ref.title)}?token=${Uri.encode(srv.token)}"
        is MediaRef.InLibrary -> "$base/lib/${ref.root}/${ref.path.split('/').joinToString("/") { Uri.encode(it) }}?token=${Uri.encode(srv.token)}"
        is MediaRef.Playing -> throw ApiException("nie da się strumieniować „tego, co gra”")
    }

    /**
     * Jeśli serwer śpi: Wake-on-LAN i czekanie, aż agent odpowie (do 2 min). Pakiet idzie z telefonu, a po kilku
     * sekundach także przez inne działające serwery (`wake_others`), bo z telefonu nie przejdzie do innej podsieci.
     * Udane budzenie zapamiętujemy (`wakeOk`), wtedy „Uśpij” nie ostrzega.
     */
    suspend fun ensureAwake(onStatus: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (ping()) return@withContext
        if (wrongServer) throw ApiException("Pod adresem ${srv.host} odpowiada inny serwer. Sparuj ${srv.name} ponownie.")
        val mac = Servers.get(ctx, srv.key)?.mac.orEmpty().ifBlank { srv.mac }
        if (mac.isBlank()) throw ApiException(
            "${srv.name} nie odpowiada, a nie znam jego MAC. Połącz się raz, gdy działa (Ustawienia → serwer → Testuj)."
        )
        val name = srv.name.ifEmpty { "serwer" }
        val start = System.currentTimeMillis()
        var lastWol = 0L
        var via: String? = null
        while (System.currentTimeMillis() - start < 120_000) {
            val now = System.currentTimeMillis()
            if (now - lastWol > 15_000) {
                runCatching { Wol.send(mac, srv.host) }
                lastWol = now
            }
            if (via == null && now - start > 6_000) {
                // pakiet z telefonu nie zadziałał (może inna podsieć): prosimy działający serwer
                via = Servers.all(ctx).filter { it.key != srv.key && it.canWakeOthers }.firstNotNullOfOrNull { o ->
                    val a = Api(s, o)
                    if (a.ping(1000) && runCatching { a.wakeOther(mac, srv.host) }.isSuccess) o.name else null
                } ?: ""
            }
            onStatus(if (!via.isNullOrEmpty()) "Budzę $name przez $via… ${(now - start) / 1000} s" else "Budzę $name… ${(now - start) / 1000} s")
            if (ping(1500)) {
                Servers.update(ctx, srv.key) { it.copy(wakeOk = true) }
                // agent zapamięta to dla wszystkich urządzeń (starszy agent nie zna endpointu: trudno)
                runCatching { call("POST", "/api/wake/verified", JSONObject(), 4000) }
                return@withContext
            }
            delay(1500)
        }
        throw ApiException("$name nie wstał w 2 minuty. Sprawdź Wake-on-LAN w BIOS-ie i kabel sieciowy.")
    }

    /** Dodanie magneta z ponawianiem — po wybudzeniu deluged/deluge-web potrzebują chwili. */
    suspend fun addMagnetRetrying(uri: String, sequential: Boolean, after: String, onStatus: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            var last: ApiException? = null
            repeat(12) { attempt ->
                try {
                    return@withContext addMagnet(uri, sequential, after)
                } catch (e: ApiException) {
                    if (e.code != 502 && e.code != 0) throw e
                    last = e
                    onStatus("Czekam na Deluge… (${attempt + 1})")
                    delay(3000)
                }
            }
            throw last ?: ApiException("Nie udało się dodać")
        }
}

fun magnetName(magnet: String): String? =
    magnet.substringAfter('?', "").split('&').firstOrNull { it.startsWith("dn=") }
        ?.let { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }

fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(java.util.Locale.ROOT, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun humanTime(sec: Long): String = when {
    sec <= 0 -> ""
    sec < 60 -> "${sec} s"
    sec < 3600 -> "${sec / 60} min"
    sec < 86400 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "${sec / 86400} d"
}

fun clock(sec: Double?): String {
    if (sec == null || sec.isNaN()) return "--:--"
    val s = sec.toLong()
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

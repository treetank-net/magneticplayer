package eu.jm.magneticplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Lokalny klucz serwera sprzed obsługi wielu serwerów: jego filmy mają klucze bez prefiksu (bufor, pozycje). */
const val LEGACY = "legacy"

/**
 * Sparowany serwer. `key` to lokalny identyfikator: [LEGACY] dla serwera z czasów jednego komputera, a dla
 * kolejnych id serwera z /api/info. `uid` to id zgłoszone przez serwer (sprawdzane przy połączeniu, żeby pod
 * starym adresem nie wysłać tokenu innemu serwerowi). `info` to ostatnia odpowiedź /api/info (możliwości).
 */
data class Server(
    val key: String,
    val uid: String?,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val mac: String = "",
    /** budzenie przez sieć już raz zadziałało */
    val wakeOk: Boolean = false,
    val info: JSONObject? = null,
) {
    private val features get() = info?.optJSONObject("features")
    private val power get() = info?.optJSONObject("power")

    /** Bez /api/info w nowym formacie (stary agent) zakładamy wszystko, jak dawniej na jmpc. */
    val knowsFeatures get() = features != null
    val hasTorrent get() = features?.let { !it.isNull("torrent") && it.optString("torrent").isNotEmpty() } ?: true
    val hasLibrary get() = features?.optBoolean("library", true) ?: true
    /** Umie zainstalować agenta na kolejnym komputerze (`/api/install/code`, agent 1.3+). */
    val canInstall get() = features?.optBoolean("install", false) ?: false
    val hasFiles get() = hasTorrent || hasLibrary
    val hasPlayer get() = features?.optBoolean("player", true) ?: true
    val canUnlock get() = features?.optBoolean("unlock", true) ?: true
    val canWakeOthers get() = features?.optBoolean("wake_others", false) ?: false
    val canSuspend get() = power?.optBoolean("suspend", true) ?: true
    val canPoweroff get() = power?.optBoolean("poweroff", true) ?: true
    /** koder do wersji „dla Chromecasta” (np. „NVIDIA NVENC”, „procesor (x264)”), null = brak ffmpeg albo stary agent */
    val transcode: String? get() = features?.takeIf { !it.isNull("transcode") }?.optString("transcode")?.ifEmpty { null }
    val transcodeHw get() = features?.optBoolean("transcode_hw") ?: false

    /** yes (sprawdzone przez apkę) / maybe / no */
    val wakeConfidence: String get() = when {
        wakeOk -> "yes"
        power == null -> if (info?.optBoolean("wireless") == true) "no" else "maybe"
        else -> power!!.optJSONObject("wake")?.optString("confidence", "maybe") ?: "maybe"
    }
    val wakeReason: String get() = power?.optJSONObject("wake")?.optString("reason").orEmpty().ifEmpty {
        if (info?.optBoolean("wireless") == true) "komputer jest na Wi-Fi" else "nie wiadomo, czy da się go obudzić"
    }

    val roles: String get() = listOfNotNull(if (hasFiles) "pliki" else null, if (hasPlayer) "ekran" else null)
        .joinToString(" + ").ifEmpty { "nic" }

    fun json(): JSONObject = JSONObject().put("key", key).put("uid", uid ?: JSONObject.NULL).put("name", name)
        .put("host", host).put("port", port).put("token", token).put("mac", mac).put("wake_ok", wakeOk)
        .put("info", info ?: JSONObject.NULL)

    companion object {
        fun from(o: JSONObject) = Server(
            o.getString("key"), o.optString("uid").takeIf { !o.isNull("uid") && it.isNotEmpty() }, o.optString("name"),
            o.optString("host"), o.optInt("port", 8765), o.optString("token"), o.optString("mac"),
            o.optBoolean("wake_ok"), o.optJSONObject("info"),
        )

        /** Gdy nie ma żadnego serwera: Api rzuca „Dodaj serwer”. */
        val NONE = Server("", null, "", "", 8765, "")
    }
}

/** Lista serwerów w SharedPreferences (JSON). Wybór źródła filmów i ekranu (Pilot) osobno. */
object Servers {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Server> {
        val p = prefs(ctx)
        val raw = p.getString("servers", null)
        if (raw == null) {
            // migracja z jednego komputera: dotychczasowy host/token staje się serwerem LEGACY (klucze bez prefiksu)
            val host = p.getString("host", "").orEmpty()
            val token = p.getString("token", "").orEmpty()
            val list = if (host.isNotBlank() && token.isNotBlank())
                listOf(Server(LEGACY, null, host, host, p.getInt("port", 8765), token, p.getString("mac", "").orEmpty()))
            else emptyList()
            save(ctx, list)
            if (list.isNotEmpty()) p.edit().putString("active_server", LEGACY).apply()
            return list
        }
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { Server.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<Server>) {
        prefs(ctx).edit().putString("servers", JSONArray(list.map { it.json() }).toString()).apply()
    }

    fun get(ctx: Context, key: String?): Server? = all(ctx).firstOrNull { it.key == key }

    @Synchronized
    fun put(ctx: Context, s: Server) {
        val list = all(ctx)
        save(ctx, if (list.any { it.key == s.key }) list.map { if (it.key == s.key) s else it } else list + s)
    }

    @Synchronized
    fun update(ctx: Context, key: String, f: (Server) -> Server) {
        get(ctx, key)?.let { put(ctx, f(it)) }
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        save(ctx, all(ctx).filter { it.key != key })
        if (activeKey(ctx) == key) setActive(ctx, all(ctx).firstOrNull()?.key)
    }

    fun activeKey(ctx: Context): String? = prefs(ctx).getString("active_server", null)
        ?.takeIf { k -> all(ctx).any { it.key == k } } ?: all(ctx).firstOrNull()?.key

    fun setActive(ctx: Context, key: String?) = prefs(ctx).edit().putString("active_server", key).apply()

    fun active(ctx: Context): Server = get(ctx, activeKey(ctx))
        ?: all(ctx).let { l -> l.firstOrNull { it.hasFiles } ?: l.firstOrNull() } ?: Server.NONE

    /** Serwery, na których da się odtwarzać (mpv). */
    fun screens(ctx: Context) = all(ctx).filter { it.hasPlayer }

    /** Ekran Pilota: ostatnio wybrany, a bez wyboru wybrany serwer plików (jeśli ma ekran) albo pierwszy ekran. */
    fun screenKey(ctx: Context): String? {
        val screens = screens(ctx)
        prefs(ctx).getString("screen_server", null)?.takeIf { k -> screens.any { it.key == k } }?.let { return it }
        return screens.firstOrNull { it.key == activeKey(ctx) }?.key ?: screens.firstOrNull()?.key
    }

    fun setScreen(ctx: Context, key: String) = prefs(ctx).edit().putString("screen_server", key).apply()

    /** Domyślny serwer dla magnet linków („zapamiętaj” w oknie wyboru). */
    fun magnetKey(ctx: Context): String? = prefs(ctx).getString("magnet_server", null)
    fun setMagnetKey(ctx: Context, key: String?) = prefs(ctx).edit().putString("magnet_server", key).apply()

    /** Ostatnio otwarty na ekranie film (pozycję zapisuje Pilot, bo przy URL z biletem mpv jej nie zapamięta). */
    fun setScreenRef(ctx: Context, screen: String, ref: MediaRef?) =
        prefs(ctx).edit().putString("screen_ref:$screen", ref?.encode()).apply()
    fun screenRef(ctx: Context, screen: String): MediaRef? =
        prefs(ctx).getString("screen_ref:$screen", null)?.let { runCatching { MediaRef.decode(it) }.getOrNull() }

    fun prefixOf(key: String) = if (key == LEGACY || key.isEmpty()) "" else "$key:"

    /** Nazwa tego telefonu na liście urządzeń serwera. */
    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").ifBlank { "Android" }
}

/** Zaproszenie do serwera: adres + kod parowania (albo stary token). */
data class PairTarget(val host: String, val port: Int, val code: String?, val token: String?, val mac: String?)

object Pairing {
    /**
     * magneticplayer://pair?host=…&port=…&code=… (jeden serwer), …?s=host:port:code&s=… (kilka naraz)
     * albo stary format z `token=`.
     */
    fun parse(uri: Uri): List<PairTarget> {
        if (uri.scheme != "magneticplayer" || uri.host != "pair") return emptyList()
        val multi = uri.getQueryParameters("s").mapNotNull { v ->
            val parts = v.split(':')
            if (parts.size == 3) PairTarget(parts[0], parts[1].toIntOrNull() ?: 8765, parts[2], null, null) else null
        }
        if (multi.isNotEmpty()) return multi
        val host = uri.getQueryParameter("host") ?: return emptyList()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8765
        val code = uri.getQueryParameter("code")
        val token = uri.getQueryParameter("token")
        if (code == null && token == null) return emptyList()
        return listOf(PairTarget(host, port, code, token, uri.getQueryParameter("mac")))
    }

    fun link(targets: List<Triple<String, Int, String>>): String =
        if (targets.size == 1) Uri.Builder().scheme("magneticplayer").authority("pair")
            .appendQueryParameter("host", targets[0].first).appendQueryParameter("port", targets[0].second.toString())
            .appendQueryParameter("code", targets[0].third).build().toString()
        else Uri.Builder().scheme("magneticplayer").authority("pair").apply {
            targets.forEach { (h, p, c) -> appendQueryParameter("s", "$h:$p:$c") }
        }.build().toString()

    fun looksLikeCode(v: String) = Regex("^[A-Za-z0-9]{3}-?[A-Za-z0-9]{3}$").matches(v.trim())

    /**
     * Paruje z serwerem (kod → token, albo stary token), pobiera /api/info i zapisuje serwer. Ten sam serwer
     * (po id) jest aktualizowany, nie dublowany, więc zachowuje klucze filmów. Zwraca zapisany serwer.
     */
    fun pair(ctx: Context, t: PairTarget): Server {
        val token = t.token ?: run {
            val res = post("http://${t.host}:${t.port}/api/pair",
                JSONObject().put("code", t.code).put("name", Servers.deviceName()))
            res.getString("token")
        }
        val probe = Server("", null, t.host, t.host, t.port, token, t.mac.orEmpty())
        val info = Api(Settings(ctx), probe).infoRaw()
        val uid = info.optString("id").takeIf { it.isNotEmpty() }
        val existing = Servers.all(ctx).firstOrNull { uid != null && it.uid == uid }
            ?: Servers.all(ctx).firstOrNull { it.uid == null && it.host == t.host && it.port == t.port }
        val key = existing?.key ?: uid ?: ("s" + System.currentTimeMillis().toString(36))
        val mac = info.optString("mac").takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "00:00:00:00:00:00" }
        val s = Server(key, uid, existing?.name?.takeIf { it != existing.host } ?: info.optString("name").ifEmpty { t.host },
            t.host, t.port, token, mac ?: existing?.mac ?: t.mac.orEmpty(), existing?.wakeOk ?: false, info)
        Servers.put(ctx, s)
        if (Servers.all(ctx).size == 1 || Servers.activeKey(ctx) == null) Servers.setActive(ctx, s.key)
        return s
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw ApiException(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code"), code)
            return JSONObject(text)
        } catch (e: java.io.IOException) {
            throw ApiException("Serwer ${URL(url).host} nie odpowiada (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }
}

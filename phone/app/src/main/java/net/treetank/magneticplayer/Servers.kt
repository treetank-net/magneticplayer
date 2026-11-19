package net.treetank.magneticplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Local server key from before multi-server support: its videos have keys without a prefix (buffer, positions). */
const val LEGACY = "legacy"

/**
 * A paired server. `key` is a local identifier: [LEGACY] for the server from the single-computer days, and for
 * later ones the server id from /api/info. `uid` is the id reported by the server (checked on connecting, so that
 * a token is not sent to a different server at the old address). `info` is the last /api/info response (capabilities).
 */
data class Server(
    val key: String,
    val uid: String?,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val mac: String = "",
    /** Wake-on-LAN has already worked once */
    val wakeOk: Boolean = false,
    val info: JSONObject? = null,
) {
    private val features get() = info?.optJSONObject("features")
    private val power get() = info?.optJSONObject("power")

    /** Without /api/info in the new format (old agent) we assume everything, as before on jmpc. */
    val knowsFeatures get() = features != null
    val hasTorrent get() = features?.let { !it.isNull("torrent") && it.optString("torrent").isNotEmpty() } ?: true
    val hasLibrary get() = features?.optBoolean("library", true) ?: true
    /** Tags, visibility and exclusions of library directories (agent 1.4+). */
    val libraryTags get() = features?.optBoolean("library_tags", false) ?: false
    /** Agent 1.5+: recognizes a magnet and downloads straight into a library directory (`/api/library/guess`). */
    /** Agent 1.5+: library search (`/api/library/search`). */
    val librarySearch get() = features?.optBoolean("library_search", false) ?: false
    val libraryGuess get() = features?.optBoolean("library_guess", false) ?: false
    /** Agent 1.6+: disks, plans and background file tasks (moving, shrinking, tidy-up, duplicates, trash). */
    val libraryTasks get() = features?.optBoolean("library_tasks", false) ?: false
    /** Storage profiles the agent knows, in display order (empty on an old agent). */
    val libraryProfiles: List<String> get() = features?.optJSONArray("library_profiles")
        ?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
    /** Agent 1.6+: covers for folders and videos (`/api/library/cover`). */
    val libraryCovers get() = features?.optBoolean("library_covers", false) ?: false
    /** Agent 1.10+: a series folder's episodes in order, extras apart (`/api/library/episodes`). */
    val libraryEpisodes get() = features?.optBoolean("library_episodes", false) ?: false
    /** intro and end credits of series episodes (agent 1.11+, GET /api/library/segments) */
    val segments get() = features?.optBoolean("segments", false) ?: false
    /**
     * Agent 1.8+: the library as films (`/api/catalog`, person, what next), the shared wishlist, search engines in
     * the settings and the history of files sent from phones (`/api/uploads`).
     */
    val hasCatalog get() = features?.optBoolean("catalog", false) ?: false
    /** Agent 1.8.2+: a plan that re-encodes old picture formats across the library. */
    val hasModernize get() = features?.optBoolean("library_modernize", false) ?: false
    /** agent 1.9+: subtitles fitted to the film's speech (after a download, and the "Subtitles" task in Disks) */
    val hasSubsFit get() = features?.optBoolean("subs_fit", false) ?: false
    val hasSubsVersions get() = features?.optBoolean("subs_versions", false) ?: false
    /** agent 1.12.1+: POST /api/subs/shift (subtitles too early / too late, saved in the file) */
    val hasSubsShift get() = features?.optBoolean("subs_shift", false) ?: false
    /** agent 1.12.1+: POST /api/subs/download with "async" → a job with "download" / "fit" phases */
    val hasSubsDownloadJob get() = features?.optBoolean("subs_download_job", false) ?: false
    /** /api/player/open takes `sub_lang` (agent 1.12.1+) */
    val hasPlayerSubLang get() = features?.optBoolean("player_sub_lang", false) ?: false
    /** POST /api/tasks/<id>/remove (agent 1.12.1+) */
    val hasTaskRemove get() = features?.optBoolean("task_remove", false) ?: false
    /** How many search engines the user set on the server (`/api/config/search`); 0 = no "Search" button. */
    val searchEngines get() = features?.optInt("search_engines", 0) ?: 0
    /** Can install the agent on another computer (`/api/install/code`, agent 1.3+). */
    val canInstall get() = features?.optBoolean("install", false) ?: false
    val canAccessRequests get() = features?.optBoolean("access_requests", false) ?: false
    /** This phone is a guest here (agent 1.3+): no inviting, torrents, settings or power control. */
    val isGuest get() = info?.optString("role") == "guest"
    val hasFiles get() = hasTorrent || hasLibrary
    val hasPlayer get() = features?.optBoolean("player", true) ?: true
    val canUnlock get() = features?.optBoolean("unlock", true) ?: true
    val canWakeOthers get() = features?.optBoolean("wake_others", false) ?: false
    val canSuspend get() = power?.optBoolean("suspend", true) ?: true
    /** Where the server gets the app for phones (agent 1.3.1+): "auto" (GitHub releases), "manual" (publish.sh), null. */
    val appUpdates: String? get() = features?.optString("app_updates")?.ifEmpty { null }
    /** The agent's own updates from GitHub (agent 1.6+): "auto", "manual", null = not supported (old agent, Windows). */
    val agentUpdates: String? get() = features?.optString("agent_updates")?.ifEmpty { null }
    /** Shrinking encoder setting (agent 1.7+): "auto", "cpu", "gpu", null = old agent (no choice). */
    val shrinkEncoder: String? get() = features?.optString("shrink_encoder")?.ifEmpty { null }
    /** What shrinking does while someone watches (agent 1.7+): "continue", "pause", null = old agent. */
    val shrinkWhileWatching: String? get() = features?.optString("shrink_while_watching")?.ifEmpty { null }
    /** A newer agent version found in the latest release, or null. */
    val agentAvailable: String? get() = features?.optString("agent_available")?.ifEmpty { null }
    val canPoweroff get() = power?.optBoolean("poweroff", true) ?: true
    /** encoder for the "for Chromecast" version (e.g. "NVIDIA NVENC", "CPU (x264)"), null = no ffmpeg or an old agent */
    val transcode: String? get() = features?.takeIf { !it.isNull("transcode") }?.optString("transcode")?.ifEmpty { null }
    val transcodeHw get() = features?.optBoolean("transcode_hw") ?: false

    /** yes (checked by the app) / maybe / no */
    val wakeConfidence: String get() = when {
        wakeOk -> "yes"
        power == null -> if (info?.optBoolean("wireless") == true) "no" else "maybe"
        else -> power!!.optJSONObject("wake")?.optString("confidence", "maybe") ?: "maybe"
    }
    /** Reason from the server (its text, untranslated), and without it our description. */
    fun wakeReason(ctx: Context): String = power?.optJSONObject("wake")?.optString("reason").orEmpty().ifEmpty {
        ctx.getString(if (info?.optBoolean("wireless") == true) R.string.srv_reason_wifi else R.string.srv_reason_unknown)
    }

    /** "files + screen" in the phone's language. */
    fun rolesText(ctx: Context): String = listOfNotNull(
        if (hasFiles) ctx.getString(R.string.srv_role_files) else null,
        if (hasPlayer) ctx.getString(R.string.srv_role_screen) else null,
    ).joinToString(" + ").ifEmpty { ctx.getString(R.string.srv_role_nothing) }

    fun json(): JSONObject = JSONObject().put("key", key).put("uid", uid ?: JSONObject.NULL).put("name", name)
        .put("host", host).put("port", port).put("token", token).put("mac", mac).put("wake_ok", wakeOk)
        .put("info", info ?: JSONObject.NULL)

    companion object {
        fun from(o: JSONObject) = Server(
            o.getString("key"), o.optString("uid").takeIf { !o.isNull("uid") && it.isNotEmpty() }, o.optString("name"),
            o.optString("host"), o.optInt("port", 8765), o.optString("token"), o.optString("mac"),
            o.optBoolean("wake_ok"), o.optJSONObject("info"),
        )

        /** When there is no server: Api throws "Add a server". */
        val NONE = Server("", null, "", "", 8765, "")
    }
}

/** List of servers in SharedPreferences (JSON). The choice of video source and of screen (Remote) is separate. */
object Servers {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Server> {
        val p = prefs(ctx)
        val raw = p.getString("servers", null)
        if (raw == null) {
            // migration from a single computer: the existing host/token becomes the LEGACY server (keys without a prefix)
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

    /** Servers where playback is possible (mpv). */
    fun screens(ctx: Context) = all(ctx).filter { it.hasPlayer }

    /** Remote screen: the last chosen one, and without a choice the selected file server (if it has a screen) or the first screen. */
    fun screenKey(ctx: Context): String? {
        val screens = screens(ctx)
        prefs(ctx).getString("screen_server", null)?.takeIf { k -> screens.any { it.key == k } }?.let { return it }
        return screens.firstOrNull { it.key == activeKey(ctx) }?.key ?: screens.firstOrNull()?.key
    }

    fun setScreen(ctx: Context, key: String) = prefs(ctx).edit().putString("screen_server", key).apply()

    /** Default server for magnet links ("remember" in the picker window). */
    fun magnetKey(ctx: Context): String? = prefs(ctx).getString("magnet_server", null)
    fun setMagnetKey(ctx: Context, key: String?) = prefs(ctx).edit().putString("magnet_server", key).apply()

    /** Video last opened on a screen (Remote saves the position, because with a ticket URL mpv will not remember it). */
    fun setScreenRef(ctx: Context, screen: String, ref: MediaRef?) =
        prefs(ctx).edit().putString("screen_ref:$screen", ref?.encode()).apply()
    fun screenRef(ctx: Context, screen: String): MediaRef? =
        prefs(ctx).getString("screen_ref:$screen", null)?.let { runCatching { MediaRef.decode(it) }.getOrNull() }

    fun prefixOf(key: String) = if (key == LEGACY || key.isEmpty()) "" else "$key:"

    /** Name of this phone in the server's device list. */
    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").ifBlank { "Android" }
}

/** Invitation to a server: address + pairing code (or an old token). */
data class PairTarget(val host: String, val port: Int, val code: String?, val token: String?, val mac: String?)

object Pairing {
    /**
     * magneticplayer://pair?host=…&port=…&code=… (one server), …?s=host:port:code&s=… (several at once)
     * or the old format with `token=`.
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
     * Pairs with a server (code → token, or an old token), fetches /api/info and saves the server. The same server
     * (by id) is updated, not duplicated, so it keeps the video keys. Returns the saved server.
     */
    fun pair(ctx: Context, t: PairTarget): Server {
        val token = t.token ?: run {
            val res = post(ctx, "http://${t.host}:${t.port}/api/pair",
                JSONObject().put("code", t.code).put("name", Servers.deviceName()))
            res.getString("token")
        }
        val probe = Server("", null, t.host, t.host, t.port, token, t.mac.orEmpty())
        val info = Api(Settings(ctx), probe).infoRaw()
        val uid = info.optString("id").takeIf { it.isNotEmpty() }
        // the same server by id; otherwise an entry with the same address and port (old token, or an agent installed from scratch
        // with a new id: only one agent listens on a given host:port, so the old entry is dead and we do not duplicate it)
        val existing = Servers.all(ctx).firstOrNull { uid != null && it.uid == uid }
            ?: Servers.all(ctx).firstOrNull { it.host == t.host && it.port == t.port }
        val key = existing?.key ?: uid ?: ("s" + System.currentTimeMillis().toString(36))
        val mac = info.optString("mac").takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "00:00:00:00:00:00" }
        val s = Server(key, uid, existing?.name?.takeIf { it != existing.host } ?: info.optString("name").ifEmpty { t.host },
            t.host, t.port, token, mac ?: existing?.mac ?: t.mac.orEmpty(), existing?.wakeOk ?: false, info)
        Servers.put(ctx, s)
        // other entries under the same address are dead copies (e.g. after a reinstall from before this fix)
        Servers.all(ctx).filter { it.key != key && it.host == t.host && it.port == t.port }.forEach { Servers.remove(ctx, it.key) }
        if (Servers.all(ctx).size == 1 || Servers.activeKey(ctx) == null) Servers.setActive(ctx, s.key)
        return s
    }

    private fun post(ctx: Context, url: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json"); c.acceptPhoneLanguage()
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw ApiException(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code"), code)
            return JSONObject(text)
        } catch (e: java.io.IOException) {
            throw ApiException(ctx.getString(R.string.srv_host_not_responding, URL(url).host, e.javaClass.simpleName))
        } finally {
            c.disconnect()
        }
    }
}

package net.treetank.magneticplayer

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

/** `detail`: agent 1.18.8+, the exception behind a plain "something went wrong" (shown folded). */
class ApiException(msg: String, val code: Int = 0, val detail: String? = null) : Exception(msg)

class Settings(ctx: Context) {
    val ctx: Context = ctx.applicationContext
    private val p = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    // server address, token and MAC: Servers (the old host/port/token/mac fields are read only by the migration)
    var sequential: Boolean
        get() = p.getBoolean("sequential", true)
        set(v) = p.edit().putBoolean("sequential", v).apply()
    /** Remote does not dim the screen while something is playing */
    var pilotKeepScreen: Boolean
        get() = p.getBoolean("pilot_keep_screen", true)
        set(v) = p.edit().putBoolean("pilot_keep_screen", v).apply()
    /** ask before closing someone else's app on a Chromecast (Spotify, YouTube…) */
    var confirmForeignStop: Boolean
        get() = p.getBoolean("confirm_foreign_stop", true)
        set(v) = p.edit().putBoolean("confirm_foreign_stop", v).apply()
    /** default action after a download: none | stop | suspend */
    var afterDefault: String
        get() = p.getString("after", "none")!!
        set(v) = p.edit().putString("after", v).apply()
    var subLanguages: String
        get() = p.getString("sub_langs", "en,pl")!!
        set(v) = p.edit().putString("sub_langs", v.replace(" ", "").lowercase()).apply()

    /** The subtitles last picked in the player: a language code, "off", or null (never): the next video starts with it,
     * ahead of the order in [subLanguages]. */
    var subChoice: String?
        get() = p.getString("sub_choice", null)
        set(v) = p.edit().putString("sub_choice", v).apply()

    /** The audio track picked last ([AudioPref.encode]): the next video starts with the matching track. */
    var audioChoice: String?
        get() = p.getString("audio_choice", null)
        set(v) = p.edit().putString("audio_choice", v).apply()
    /** The audio picked in a folder (a series): it wins over [audioChoice] there. */
    fun folderAudio(folder: String): String? = p.getString("audio:$folder", null)
    fun setFolderAudio(folder: String, v: String) = p.edit().putString("audio:$folder", v).apply()

    /** The folder (a season) where [subChoice] was last picked: missing subtitles there are downloaded without asking;
     * elsewhere the player only offers to. */
    var subChoiceFolder: String?
        get() = p.getString("sub_choice_folder", null)
        set(v) = p.edit().putString("sub_choice_folder", v).apply()

    /** A shift by hand (s) of subtitles from `provider` in a folder (a season): the next episode's subtitles from the
     * same provider get it too. */
    fun folderSubShift(folder: String): Pair<String, Double>? =
        p.getString("subshift:$folder", null)?.split('|')?.takeIf { it.size == 2 }?.let { (pr, s) -> s.toDoubleOrNull()?.let { pr to it } }
    fun setFolderSubShift(folder: String, provider: String, seconds: Double) =
        p.edit().putString("subshift:$folder", "$provider|$seconds").apply()

    /** covers kept on the phone: CoverStore.NONE / TINY / FULL */
    var coverStore: String
        get() = p.getString("cover_store", CoverStore.NONE) ?: CoverStore.NONE
        set(v) = p.edit().putString("cover_store", v).apply()

    /** how many minutes of video the app downloads ahead onto the phone's disk (MediaCache) */
    var bufferMinutes: Int
        get() = p.getInt("buffer_min", 5)
        set(v) = p.edit().putInt("buffer_min", v).apply()

    /** name of the last used TV (Chromecast or DLNA), for "recent" in the screen picker */
    var lastTv: String?
        get() = p.getString("last_tv", null)
        set(v) = p.edit().putString("last_tv", v).apply()

    /** ask about uploading videos downloaded by the browser to the server (Uploads.kt) */
    var uploadAsk: Boolean
        get() = p.getBoolean("upload_ask", false)
        set(v) = p.edit().putBoolean("upload_ask", v).apply()
    /** target server for uploads (key from Servers); null = the file server with features.upload */
    var uploadServer: String?
        get() = p.getString("upload_server", null)
        set(v) = p.edit().putString("upload_server", v).apply()


    fun position(key: String): Long = p.getLong("pos:${follow(key)}", 0)
    /** When the position was last saved (epoch ms, 0 = never). */
    fun positionAt(key: String): Long = p.getLong("pt:${follow(key)}", 0)
    /** When the video was last watched to the end (epoch ms, 0 = never): series continue after it. */
    fun watchedAt(key: String): Long = p.getLong("wd:${follow(key)}", 0)

    /**
     * A library file re-encoded on the server ("Old formats", shrinking) keeps its name but may change its extension
     * (.avi → .mkv). When nothing is saved under [key], what this phone saved under the same name with another
     * extension (position, its time, "watched") moves over. The disk buffer stays with the old key: it holds the old file.
     */
    private fun follow(key: String): String {
        if (p.contains("pos:$key") || p.contains("wd:$key")) return key
        val stem = stemOf(key) ?: return key
        val old = p.all.keys.asSequence().filter { it.startsWith("pos:") || it.startsWith("wd:") }
            .map { it.substringAfter(':') }.firstOrNull { it != key && stemOf(it) == stem } ?: return key
        val e = p.edit()
        for (pre in arrayOf("pos:", "pt:", "wd:")) {
            if (p.contains(pre + old)) e.putLong(pre + key, p.getLong(pre + old, 0)).remove(pre + old)
        }
        e.apply()
        return key
    }

    companion object {
        /** The key or path without the file's extension, null when the name has none. */
        fun stemOf(key: String): String? {
            val name = key.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) null else key.dropLast(name.length - dot)
        }
    }
    /** Videos watched to the end: key → when (epoch ms). */
    fun watchedAll(): Map<String, Long> = p.all.mapNotNull { (k, v) ->
        if (k.startsWith("wd:") && v is Long && v > 0) k.removePrefix("wd:") to v else null
    }.toMap()
    fun markWatched(key: String) = p.edit().putLong("wd:$key", System.currentTimeMillis()).apply()
    /** Also stamps when it was saved ("pt:"), so "Continue watching" in the catalog can order videos by recency. */
    fun setPosition(key: String, ms: Long) = p.edit().putLong("pos:$key", ms).putLong("pt:$key", System.currentTimeMillis()).apply()
    /** The same for [ref]; a file played from a torrent also keeps its name ("pn:"), so the catalog can find the title
     *  it is in the library (also once the torrent is moved there or removed) for "Continue watching" and suggestions. */
    fun setPosition(ref: MediaRef, ms: Long) {
        setPosition(ref.key(), ms)
        if (ref is MediaRef.InTorrent && ref.title.isNotBlank()) p.edit().putString("pn:${ref.key()}", ref.title).apply()
    }
    /** File names of positions saved from torrents: key → name. */
    fun torrentNames(): Map<String, String> = p.all.mapNotNull { (k, v) ->
        if (k.startsWith("pn:") && v is String) k.removePrefix("pn:") to v else null
    }.toMap()

    /**
     * Saved positions > 0: video key → (position ms, when it was last saved or watched, epoch ms; 0 = unknown).
     * Positions saved before the "pt:" stamp existed fall back to the disk buffer's last use ("cu:").
     */
    fun positions(): Map<String, Pair<Long, Long>> = p.all.mapNotNull { (k, v) ->
        if (!k.startsWith("pos:") || v !is Long || v <= 0) return@mapNotNull null
        val key = k.removePrefix("pos:")
        key to (v to p.getLong("pt:$key", p.getLong("cu:$key", 0)))
    }.toMap()

    // disk buffer: how much of the video was watched (0–1) and when last watched, for cleanup in MediaCache.sweep
    fun cacheWatched(key: String): Float = p.getFloat("cw:$key", 0f)
    fun cacheUsed(key: String): Long = p.getLong("cu:$key", 0)
    fun touchCache(key: String, watched: Float) =
        p.edit().putFloat("cw:$key", watched).putLong("cu:$key", System.currentTimeMillis()).apply()
    fun forgetCache(key: String) = p.edit().remove("cw:$key").remove("cu:$key").apply()

    /**
     * A file or folder of server [srv] moved on the server (load balancing, freeing a disk): what this phone saved
     * under its old place (positions, "watched", buffer use, the folder's audio and subtitle shift) follows it.
     * Downloaded copies keep their old key: they still play from "On the phone".
     */
    fun applyMove(srv: String, fromRoot: Int, from: String, toRoot: Int, to: String) {
        val oldKey = Servers.prefixOf(srv) + "lib$fromRoot/$from"
        val newKey = Servers.prefixOf(srv) + "lib$toRoot/$to"
        val oldDir = "$srv:$fromRoot:$from"
        val newDir = "$srv:$toRoot:$to"
        val e = p.edit()
        var n = 0
        for ((k, v) in p.all) {
            val pre = k.substringBefore(':', "") + ":"
            val rest = k.substringAfter(':', "")
            val moved = when (pre) {
                "pos:", "pt:", "wd:", "cw:", "cu:" -> when {
                    rest == oldKey -> newKey
                    rest.startsWith("$oldKey/") -> newKey + rest.removePrefix(oldKey)
                    else -> null
                }
                "audio:", "subshift:" -> when {
                    rest == oldDir -> newDir
                    rest.startsWith("$oldDir/") -> newDir + rest.removePrefix(oldDir)
                    else -> null
                }
                else -> null
            } ?: continue
            if (p.contains(pre + moved)) continue  // something saved at the new place already wins
            when (v) {
                is Long -> e.putLong(pre + moved, v)
                is Float -> e.putFloat(pre + moved, v)
                is String -> e.putString(pre + moved, v)
                else -> continue
            }
            e.remove(k)
            n++
        }
        if (n > 0) e.apply()
    }

    /** Up to when (server epoch s) moves of server [srv] were applied. */
    fun movesSeen(srv: String): Double = p.getString("moves_s:$srv", null)?.toDoubleOrNull() ?: 0.0
    fun setMovesSeen(srv: String, at: Double) = p.edit().putString("moves_s:$srv", at.toString()).apply()

}

/** One video of a series folder: [path] relative to the library folder, [season]/[episode] from the name (null = none). */
/** Where an episode's intro is and where its end credits start (ms); null = not found. */
data class Segments(val pending: Boolean, val introStart: Long?, val introEnd: Long?, val creditsAt: Long?)

data class Episode(val path: String, val name: String, val season: Int?, val episode: Int?, val size: Long) {
    /** "S1 E3" (or "E3", or "") */
    val label get() = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")
    /** the file name without the extension, dots and underscores as spaces */
    val shortName get() = name.substringBeforeLast('.').replace('_', ' ').replace(Regex("(?<=\\w)\\.(?=\\w)"), " ")
}

data class Episodes(val episodes: List<Episode>, val extras: List<Episode>)

data class Torrent(
    val id: String, val name: String, val progress: Double, val state: String,
    val downRate: Long, val upRate: Long, val eta: Long, val size: Long,
    val sequential: Boolean, val peers: Int,
    /** added from the app */
    val mine: Boolean, val after: String,
)

data class Job(
    val id: String, val name: String, val added: Double, val after: String, val afterDone: Boolean,
    val magnet: String?, val inClient: Boolean, val progress: Double?,
)

/** Library directory. `visible`: "all", "owners" or a list of device ids (`devices`); `open`: whether this phone can open it. */
/** Directory a torrent can be downloaded into (`sub`: subdirectory, e.g. an existing series). */
data class DlTarget(val root: Int, val name: String, val sub: String, val free: Long, val tags: List<String>, val existing: Boolean)

/** Magnet recognition from the agent: what it is and where it would fit (`pick`: index into `targets` or null). */
data class DlGuess(val tag: String?, val title: String?, val year: Int?, val confidence: String, val raw: JSONObject,
                   val targets: List<DlTarget>, val pick: Int?)

data class LibRoot(
    val index: Int, val name: String, val path: String, val exists: Boolean,
    val tags: List<String> = emptyList(), val visible: String = "all", val devices: List<String> = emptyList(),
    val exclude: List<String> = emptyList(), val open: Boolean = true,
    /** stable folder id (agent 1.4+), the folder this one is copied to (agent 1.6+) and the copying state */
    val id: String = "", val replicateTo: String? = null, val replica: Replica? = null,
)
/** Replication of a library folder to another one: files waiting, copied, last run (epoch s), last error. */
data class Replica(val pending: Int, val copied: Int, val last: Long?, val error: String?)

/** A library folder on a disk (`/api/library/disks`); `profile`: storage profile, `profiles`: per subfolder. */
data class DiskRoot(val root: Int, val id: String, val name: String, val tags: List<String>, val size: Long, val files: Int,
                    val profile: String, val profiles: Map<String, String>)
data class Disk(val id: String, val name: String, val mount: String, val total: Long, val free: Long, val used: Long,
                val roots: List<DiskRoot>,
                /** agent 1.8+: the system disk, and where "Add folder" starts browsing (disks without library folders are listed too) */
                val system: Boolean = false, val home: String = "")
data class TrashDay(val root: Int, val rootName: String, val day: String, val size: Long, val files: Int)
data class Disks(val disks: List<Disk>, val trash: List<TrashDay>, val trashDays: Int)
/** A plan (preview of a file task): `result` is the agent's JSON (kind-specific fields, see docs/api.md). */
/** `step`: what the planner is looking at now (agent 1.15.3+), shown under the preview's progress bar. */
data class LibPlan(val id: String, val kind: String, val state: String, val progress: Int, val error: String?, val result: JSONObject?,
                   val step: String? = null)
data class TaskItem(val name: String, val path: String, val size: Long, val state: String, val error: String?, val saved: Long,
                    /** agent 1.9+: outcome of a subtitle item ("pl: fitted (−0.7 s)…"), localized */
                    val note: String? = null)
/** A background file task on the agent (`/api/tasks`); times are epoch seconds. */
data class LibTask(
    val id: String, val kind: String, val title: String, val state: String, val created: Double, val finished: Double?,
    val total: Long, val doneBytes: Long, val pct: Int, val current: String?, val saved: Long, val errors: Int,
    val paused: Boolean, val items: List<TaskItem>,
    /** agent 1.7+: encoder label of a shrink task and a status note ("Slower: someone is watching…"), both localized */
    val encoder: String? = null, val note: String? = null,
    /** agent 1.9+, subtitle tasks: files fitted to their film, files that don't fit it */
    val fixed: Int = 0, val unfit: Int = 0,
    /** item counts; with `?brief=1` (agent 1.14.1+) [items] is empty and only these say how many there are */
    val itemCount: Int = items.size, val itemsDone: Int = items.count { it.state == "done" },
    /** agent 1.14.2+: item errors grouped by reason, most frequent first (reason to count) */
    val errorGroups: List<Pair<String, Int>> = emptyList(),
    /** agent 1.16+: the scope the task was made for ("Anime · 0+ and 7+"), localized; null = the whole computer */
    val scopeLabel: String? = null,
)
/** An encoder for shrinking (agent 1.7+); label and note come localized from the agent. */
data class ShrinkEncoder(val id: String, val label: String, val note: String, val bframes: Boolean)
/**
 * Shrinking settings (`/api/config/shrink`, agent 1.7+): `encoder` auto|cpu|gpu, `auto` = what auto resolves to,
 * `whileWatching` continue|pause, `detecting` = the agent is still checking which encoders work.
 */
data class ShrinkConfig(val encoder: String, val auto: String, val whileWatching: String, val detecting: Boolean,
                        val encoders: List<ShrinkEncoder>) {
    fun label(id: String) = encoders.firstOrNull { it.id == id }?.label
}
/** Search hit: a folder or a media file somewhere in a library folder. */
data class LibHit(val root: Int, val rootName: String, val path: String, val dir: Boolean, val size: Long, val media: String?)
data class LibFile(val name: String, val size: Long, val media: String?, val subs: List<String>)
data class LibDir(val root: Int, val path: String, val dirs: List<String>, val files: List<LibFile>)
/** Subtitle search progress on the computer (each source separately). */
data class SubsProvider(val name: String, val state: String, val count: Int?, val error: String?, val secs: Double?)
data class SubsFound(val query: String, val title: String, val year: Int?, val results: List<SubResult>, val hidden: Int)
data class SubsProgress(
    val phase: String, val round: Int, val query: String?, val matched: Int, val total: Int,
    val providers: List<SubsProvider>, val elapsed: Double, val done: Boolean, val error: String?, val found: SubsFound?,
)

data class SubResult(
    val id: String, val provider: String, val lang: String, val release: String, val score: Int,
    val hashMatch: Boolean, val hi: Boolean,
    // agent 1.17.3+, from the provider's own listing (OpenSubtitles): 0 / false / null when unknown
    val downloads: Int = 0, val machine: Boolean = false, val trusted: Boolean = false,
    val fps: Double? = null, val fpsOff: Boolean = false,
) {
    val key get() = "$provider:$id"
}

/** Best first, as the agent sorts (mp_subs.rank_key): not machine-translated, hash match, the film's frame rate, score,
 *  trusted uploader, downloads. */
val SUB_RANK: Comparator<SubResult> = compareBy<SubResult> { it.machine }.thenBy { !it.hashMatch }.thenBy { it.fpsOff }
    .thenByDescending { it.score }.thenBy { !it.trusted }.thenByDescending { it.downloads }
/** `fit` (agent 1.9+): good / unsure / bad = how these subtitles fit this copy of the film (speech detection), null = not checked */
data class SubFile(
    val name: String, val lang: String, val ext: String, val fit: String? = null,
    /** agent 1.12+: true = next to the video, the version of its language in use; false = another version (Subtitles/…) */
    val default: Boolean = true,
    /** agent 1.12+: where the agent downloaded it from */
    val source: SubSource? = null,
    /** agent 1.12.1+: seconds the file was moved by hand (the sync buttons) */
    val manual: Double = 0.0,
    /** agent 1.18+: when the file was last written on the server (epoch s, 0 = not said); a downloaded copy compares it */
    val mtime: Long = 0,
    /** agent 1.18.8+: "fits" only because it lined up as it was (frame rate corrected at most) */
    val weak: Boolean = false,
    /** agent 1.18.8+: why the fit is "pending": "incomplete" = the film is still downloading, fitted when complete */
    val fitReason: String? = null,
) {
    /** "pl" of "pl", "pl-PL", "" when unknown */
    val langKey get() = lang.takeWhile { it.isLetter() }.lowercase()
}
data class SubSource(val provider: String, val id: String, val release: String) {
    val key get() = "$provider:$id"
}
/** A downloaded subtitle file and, with agent 1.9+, how it fits the film (`fit` as in [SubFile]; `changed` = shifted to fit). */
data class SubsDownloaded(
    val name: String, val fit: String?, val changed: Boolean, val provider: String = "",
    /** agent 1.18.8+: as in [SubFile] */
    val weak: Boolean = false, val fitReason: String? = null,
    /** why it couldn't be fitted (`fit` = "error"), the agent's words */
    val fitError: String? = null,
)

/** Labels of after-download actions in the phone's language (resources media_after_*). */
fun afterLabels(ctx: Context): Map<String, String> = linkedMapOf(
    "none" to ctx.getString(R.string.media_after_none),
    "stop" to ctx.getString(R.string.media_after_stop),
    "suspend" to ctx.getString(R.string.media_after_suspend),
)

/** Phone language for the agent: it picks the language of error messages accordingly. */
fun HttpURLConnection.acceptPhoneLanguage() =
    setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag())

/**
 * Pointer to a file on server `srv` (key from [Servers]): from a torrent, from the library, or "what is playing now in mpv".
 * [key] (disk buffer, watch position) has the server prefix, except for server [LEGACY] (old keys stay).
 */
sealed class MediaRef {
    abstract val title: String
    abstract val srv: String
    /** pointer for the server's API (without `srv`) */
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

/** Video from another server for a screen: URL and subtitles with a ticket (POST /api/media/ticket). */
data class Ticket(val url: String, val subs: List<String>, val title: String, val subInfo: List<SubSrc> = emptyList(),
                  /** "video/mp4" when the ticket points to the "for Chromecast" version */
                  val contentType: String? = null)

data class TFile(
    val index: Int, val path: String, val size: Long, val progress: Double, val media: String?,
    /** bytes downloaded contiguously from the start of the file: that much can be watched */
    val ready: Long, val readySec: Double?, val duration: Double?,
) {
    val name get() = path.substringAfterLast('/')
}

object Wol {
    private fun packet(mac: String): ByteArray {
        val b = mac.split(':', '-').map { it.toInt(16).toByte() }
        require(b.size == 6) { "bad MAC: $mac" }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { b[it % 6] }
    }

    /** Magic packet to the general broadcast and the server's /24 subnet, ports 9 and 7. */
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

/**
 * API of one server. `srv` is a snapshot from [Servers]; changes (MAC, info) are written back, and [info] also refreshes
 * the snapshot, so screens holding this object see the agent's current capabilities.
 */
class Api(val s: Settings, srv: Server) {
    @Volatile var srv: Server = srv
        private set
    /** The agent's version from the last answered [ping] (null before one): a change means fresh capabilities ([info]). */
    @Volatile var pingVersion: String? = null
        private set
    val base get() = "http://${srv.host}:${srv.port}"
    val configured get() = srv.host.isNotBlank() && srv.token.isNotBlank()
    private val ctx get() = s.ctx

    companion object {
        fun active(ctx: Context) = Api(Settings(ctx), Servers.active(ctx))
        fun of(ctx: Context, key: String?): Api? = Servers.get(ctx, key)?.let { Api(Settings(ctx), it) }
        /** The server the video comes from. */
        fun forRef(ctx: Context, ref: MediaRef): Api =
            of(ctx, ref.srv) ?: throw ApiException(ctx.getString(R.string.media_err_server_gone_for_video))
    }

    private fun call(method: String, path: String, body: JSONObject? = null, timeout: Int = 8000): String {
        if (!configured) throw ApiException(ctx.getString(R.string.media_err_add_server))
        // not on this network (Home.kt): fail at once instead of every screen's loop waiting on a connection that can't
        // come (on mobile data each try sat in SYN_SENT for seconds, a few every few seconds)
        if (Home.away(srv.key)) throw ApiException(ctx.getString(R.string.srv_away_title, srv.name))
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("Authorization", "Bearer ${srv.token}")
            c.acceptPhoneLanguage()
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) {
                val j = runCatching { JSONObject(text) }.getOrNull()
                val msg = j?.optStr("error") ?: "HTTP $code"
                throw ApiException(msg, code, j?.optStr("detail"))
            }
            return text
        } catch (e: IOException) {
            throw ApiException(ctx.getString(R.string.media_err_no_response,
                srv.name.ifEmpty { ctx.getString(R.string.media_server_cap) }, e.javaClass.simpleName))
        } finally {
            c.disconnect()
        }
    }

    fun get(path: String): String = call("GET", path)
    /** Any API call (used by the catalog, wishlist and uploads in Catalog.kt); errors as [ApiException]. */
    fun request(method: String, path: String, body: JSONObject? = null, timeout: Int = 8000): String = call(method, path, body, timeout)

    /**
     * Whether the server responds. Without a token (/api/ping), and when another server (a different id) is at the address, it is as if it
     * did not respond: then [wrongServer] = true and we do not send it the token.
     * [timeout] applies to the connection (a sleeping computer does not answer it at all); we wait longer for the response, because
     * a running agent can be a few seconds late when an old USB disk spins up.
     */
    fun ping(timeout: Int = 1500, readTimeout: Int = 5000, force: Boolean = false): Boolean {
        if (srv.host.isBlank()) return false
        if (!force && Home.away(srv.key)) return false  // [force]: the checks that decide it (Home, the header, Wake)
        val c = runCatching { URL("$base/api/ping").openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            c.connectTimeout = timeout; c.readTimeout = maxOf(timeout, readTimeout)
            c.acceptPhoneLanguage()
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val id = j.optString("id")
            wrongServer = srv.uid != null && id.isNotEmpty() && id != srv.uid
            if (!wrongServer) j.optString("version").takeIf { it.isNotEmpty() }?.let { pingVersion = it }
            if (!wrongServer) pingTasks = j.optDouble("tasks").takeIf { !it.isNaN() }
            j.optBoolean("ok") && !wrongServer
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    @Volatile var wrongServer = false
    /** agent 1.14.1+: when its latest library task finished (from the last ping); null from an older agent */
    @Volatile var pingTasks: Double? = null

    fun infoRaw(): JSONObject = JSONObject(call("GET", "/api/info"))

    /** Server info; stores capabilities, id and MAC for Wake-on-LAN. */
    fun info(): JSONObject {
        val j = infoRaw()
        val mac = j.optString("mac").takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "00:00:00:00:00:00" }
        val uid = j.optString("id").takeIf { it.isNotEmpty() }
        val name = j.optString("name").ifEmpty { j.optString("hostname") }
        // a default name (an address, e.g. after migrating an old server) yields to the name from the agent; we leave a custom name alone
        // this phone already woke the server once and the agent does not know yet: send it along
        val agentKnows = j.optJSONObject("power")?.optJSONObject("wake")?.optString("confidence") == "yes"
        if (!agentKnows && Servers.get(ctx, srv.key)?.wakeOk == true && j.optInt("api") >= 2) {
            runCatching { call("POST", "/api/wake/verified", JSONObject(), 4000) }
        }
        Servers.update(ctx, srv.key) {
            it.copy(info = j, mac = mac ?: it.mac, uid = it.uid ?: uid,
                name = if ((it.name.isBlank() || it.name == it.host) && name.isNotEmpty()) name else it.name)
        }
        Servers.get(ctx, srv.key)?.let { srv = it }
        return j
    }

    /** Pairing code; guest: `role=guest`, `days` = access validity (0 = unlimited), the code itself lives for a day. */
    fun pairCode(guestDays: Int? = null): JSONObject = JSONObject(call("POST", "/api/pair/code",
        if (guestDays == null) JSONObject() else JSONObject().put("role", "guest").put("days", guestDays)))
    /** Code for joining a new computer: `{code, expires_in, url, command, command_full}` (mp_install.py). */
    fun installCode(): JSONObject = JSONObject(call("POST", "/api/install/code", JSONObject()))
    /** `{state: waiting|downloading|joined, server: {host, port, id, name, pair_code} | null, expires_in}` */
    fun installStatus(code: String): JSONObject = JSONObject(call("GET", "/api/install/status?code=$code", null, 4000))
    /** Pending access requests from the home network: `[{id, name, ip, age, expires_in}]` (mp_access.py). */
    /** Library items moved after [since] (epoch s): (from root, from path, to root, to path) and the server's "now". */
    fun libraryMoves(since: Double): Pair<List<List<Any>>, Double> {
        val o = JSONObject(call("GET", "/api/library/moves?since=$since", null, 6000))
        val a = o.getJSONArray("moves")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            listOf(it.getInt("from_root"), it.getString("from"), it.getInt("to_root"), it.getString("to"))
        } to o.getDouble("now")
    }
    fun accessRequests(): JSONArray = JSONObject(call("GET", "/api/access/requests", null, 4000)).getJSONArray("requests")
    fun accessDecide(id: String, approve: Boolean, role: String = "guest", days: Int = 0) {
        call("POST", "/api/access/requests/$id", JSONObject().put("action", if (approve) "approve" else "deny")
            .put("role", role).put("days", days))
    }
    fun devices(): JSONArray = JSONArray(call("GET", "/api/devices"))
    fun setAppUpdates(auto: Boolean) { call("POST", "/api/config/app_updates", JSONObject().put("mode", if (auto) "auto" else "manual")) }
    fun setAgentUpdates(auto: Boolean) { call("POST", "/api/config/agent_updates", JSONObject().put("mode", if (auto) "auto" else "manual")) }
    /** Shrinking settings (agent 1.7+). */
    fun shrinkConfig(): ShrinkConfig = shrinkCfg(JSONObject(call("GET", "/api/config/shrink")))
    /** Changes the shrinking encoder (auto|cpu|gpu) and/or what happens while someone watches (continue|pause); owner only. */
    fun setShrinkConfig(encoder: String? = null, whileWatching: String? = null): ShrinkConfig =
        shrinkCfg(JSONObject(call("POST", "/api/config/shrink", JSONObject().apply {
            if (encoder != null) put("encoder", encoder)
            if (whileWatching != null) put("while_watching", whileWatching)
        })))
    private fun shrinkCfg(o: JSONObject): ShrinkConfig {
        val a = o.optJSONArray("encoders") ?: JSONArray()
        return ShrinkConfig(o.optString("encoder", "auto"), o.optString("auto", "cpu"), o.optString("while_watching", "continue"),
            o.optBoolean("detecting"), List(a.length()) { i ->
                val x = a.getJSONObject(i)
                ShrinkEncoder(x.optString("id"), x.optString("label").ifEmpty { x.optString("id") }, x.optString("note"), x.optBoolean("bframes"))
            })
    }
    fun updateAgent() { call("POST", "/api/agent/update", JSONObject()) }
    fun revoke(id: String) { call("POST", "/api/devices/revoke", JSONObject().put("id", id)) }
    fun wakeOther(mac: String, host: String) { call("POST", "/api/wake", JSONObject().put("mac", mac).put("ip", host), 4000) }

    fun ticket(ref: MediaRef, variant: String? = null): Ticket {
        val o = JSONObject(call("POST", "/api/media/ticket", ref.json().apply { if (variant != null) put("variant", variant) }))
        val a = o.optJSONArray("subs") ?: JSONArray()
        val info = List(a.length()) { i ->
            a.getJSONObject(i).let { x ->
                val u = x.getString("url")
                // an older agent does not provide "vtt", but /sub supports &vtt=1
                SubSrc(x.optString("name"), x.optString("lang"), u, x.optString("vtt").ifEmpty { "$u&vtt=1" },
                    x.optBoolean("default", '/' !in x.optString("name")))
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

    fun addMagnet(uri: String, sequential: Boolean, after: String = s.afterDefault,
                  dest: DlTarget? = null, guess: JSONObject? = null): String? {
        val b = JSONObject().put("magnet", uri).put("sequential", sequential).put("after", after)
        if (dest != null) b.put("root", dest.root).put("sub", dest.sub).put("guess", guess)
        putSubLangs(b)
        return JSONObject(call("POST", "/api/torrents", b, 20000))
            .optString("id").takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Agent 1.21.3+: the downloader's subtitle languages (the last picked one first, then the settings' order, up to
     *  3), prepared by the server's automatic subtitles when the download finishes. */
    private fun putSubLangs(b: JSONObject) {
        if (!srv.hasSubsWant) return
        val langs = (listOfNotNull(s.subChoice?.takeIf { it != "off" }) + s.subLanguages.split(','))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (langs.isNotEmpty()) b.put("sub_langs", JSONArray(langs))
    }

    /** Agent 1.19+: an Internet Archive item by its .torrent address (the agent fetches it, keeps only the film file
     *  and names it "[title] ([year])"). Slower than a magnet: the agent downloads the .torrent first. */
    fun addTorrentUrl(pick: ArchivePick, sequential: Boolean, after: String = s.afterDefault,
                      dest: DlTarget? = null, guess: JSONObject? = null): String? {
        val b = JSONObject().put("torrent_url", pick.torrentUrl).put("title", pick.title)
            .put("year", pick.year ?: JSONObject.NULL).put("sequential", sequential).put("after", after)
        if (dest != null) b.put("root", dest.root).put("sub", dest.sub).put("guess", guess)
        putSubLangs(b)
        return JSONObject(call("POST", "/api/torrents", b, 60000))
            .optString("id").takeIf { it.isNotEmpty() && it != "null" }
    }

    fun librarySearch(q: String): List<LibHit> {
        val a = JSONArray(call("GET", "/api/library/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"), timeout = 30000))
        return List(a.length()) { i ->
            val h = a.getJSONObject(i)
            LibHit(h.getInt("root"), h.optString("root_name"), h.getString("path"), h.optBoolean("dir"), h.optLong("size"),
                h.optString("media").takeIf { it.isNotEmpty() && it != "null" })
        }
    }

    /** Agent 1.5+: recognition (IMDb index, Wikidata for uncertain ones) and a directory suggestion. */
    /** Recognition of a magnet from its name, or of a torrent already in the client ([torrent], once it has metadata). */
    fun guess(magnet: String?, torrent: String? = null, name: String? = null): DlGuess {
        val body = when {
            torrent != null -> JSONObject().put("torrent", torrent)
            magnet != null -> JSONObject().put("magnet", magnet)
            else -> JSONObject().put("name", name.orEmpty())  // an Internet Archive result: "Title (Year)"
        }
        val j = JSONObject(call("POST", "/api/library/guess", body, 30000))
        val g = j.optJSONObject("guess") ?: JSONObject()
        val a = j.optJSONArray("targets") ?: JSONArray()
        val targets = List(a.length()) { i ->
            val t = a.getJSONObject(i)
            val tags = t.optJSONArray("tags")
            DlTarget(t.getInt("root"), t.optString("name"), t.optString("sub"), t.optLong("free"),
                List(tags?.length() ?: 0) { k -> tags!!.getString(k) }, t.optBoolean("existing"))
        }
        return DlGuess(g.optString("tag").takeIf { it.isNotEmpty() && it != "null" },
            g.optString("title").takeIf { it.isNotEmpty() && it != "null" },
            g.optInt("year", 0).takeIf { it > 0 }, g.optString("confidence", "low"), g, targets,
            if (j.isNull("pick")) null else j.optInt("pick"))
    }

    /** Directory change after adding (the client moves the files); `learn`: the agent remembers the choice for this title. */
    fun moveTorrent(id: String, dest: DlTarget, learn: Boolean) {
        call("POST", "/api/torrents/$id/move",
            JSONObject().put("root", dest.root).put("sub", dest.sub).put("learn", learn), 20000)
    }

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
        fun strs(x: JSONArray?) = x?.let { j -> List(j.length()) { j.getString(it) } } ?: emptyList()
        val vis = r.opt("visible")
        val rep = r.optJSONObject("replica")
        LibRoot(r.getInt("index"), r.getString("name"), r.getString("path"), r.optBoolean("exists"),
            strs(r.optJSONArray("tags")), if (vis is JSONArray) "devices" else (vis as? String ?: "all"),
            strs(vis as? JSONArray), strs(r.optJSONArray("exclude")), r.optBoolean("open", true),
            r.optString("id"), r.optStr("replicate_to"),
            rep?.let { Replica(it.optInt("pending"), it.optInt("copied"), it.optLong("last").takeIf { v -> v > 0 && !it.isNull("last") },
                it.optStr("error")) })
    }

    /** `manage`: also directories hidden from this phone (household member, for changing settings). */
    fun libraryRoots(manage: Boolean = false): List<LibRoot> =
        roots(JSONArray(call("GET", "/api/library" + if (manage) "?manage=1" else "")))
    /** `replicate`: when not null, also sets the replication target (its `second`: folder id, null = none; agent 1.6+). */
    fun libraryUpdate(index: Int, name: String, tags: List<String>, visible: String, devices: List<String>, exclude: List<String>,
                      replicate: Pair<Boolean, String?>? = null): List<LibRoot> =
        roots(JSONArray(call("POST", "/api/library", JSONObject().put("op", "update").put("index", index).put("name", name)
            .put("tags", JSONArray(tags)).put("visible", if (visible == "devices") JSONArray(devices) else visible)
            .put("exclude", JSONArray(exclude))
            .apply { if (replicate != null) put("replicate_to", replicate.second ?: JSONObject.NULL) })))
    fun libraryAdd(path: String): List<LibRoot> =
        roots(JSONArray(call("POST", "/api/library", JSONObject().put("op", "add").put("path", path))))
    fun libraryRemove(index: Int): List<LibRoot> =
        roots(JSONArray(call("POST", "/api/library", JSONObject().put("op", "remove").put("index", index))))

    /** Intro and end credits of an episode (agent 1.11+, `Server.segments`); `pending` while the agent still listens. */
    fun segments(root: Int, path: String): Segments {
        val o = JSONObject(call("GET", "/api/library/segments?root=$root&path=${Uri.encode(path)}"))
        val i = o.optJSONArray("intro")
        return Segments(o.optString("state") == "pending",
            i?.let { (it.optDouble(0) * 1000).toLong() }, i?.let { (it.optDouble(1) * 1000).toLong() },
            if (o.isNull("credits") || !o.has("credits")) null else (o.optDouble("credits") * 1000).toLong())
    }

    /** A series folder's videos in watching order and its extras (agent 1.10+, `Server.libraryEpisodes`). */
    fun episodes(root: Int, path: String): Episodes {
        // kept on the phone (files/episodes/<server>/<hash>.json): a series' list opens without the server, next to
        // the catalog kept the same way (CatalogCache)
        val f = java.io.File(ctx.filesDir, "episodes/${srv.key.ifEmpty { "local" }}/${"$root/$path".hashCode().toUInt()}.json")
        val raw = try {
            call("GET", "/api/library/episodes?root=$root&path=${Uri.encode(path)}").also { r ->
                runCatching { f.parentFile?.mkdirs(); java.io.File(f.path + ".tmp").apply { writeText(r) }.renameTo(f) }
            }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
        val o = JSONObject(raw)
        fun list(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { i ->
            val x = a.getJSONObject(i)
            Episode(x.getString("path"), x.optString("name"), x.optInt("season", -1).takeIf { !x.isNull("season") && it >= 0 },
                x.optInt("episode", -1).takeIf { !x.isNull("episode") && it >= 0 }, x.optLong("size"))
        }
        return Episodes(list(o.optJSONArray("episodes")), list(o.optJSONArray("extras")))
    }

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

    // ---- disks, plans and file tasks (agent 1.6+, household members)
    fun disks(): Disks {
        val o = JSONObject(call("GET", "/api/library/disks", timeout = 30000))
        val d = o.optJSONArray("disks") ?: JSONArray()
        val t = o.optJSONArray("trash") ?: JSONArray()
        return Disks(List(d.length()) { i ->
            val x = d.getJSONObject(i)
            val rs = x.optJSONArray("roots") ?: JSONArray()
            Disk(x.optString("id"), x.optString("name"), x.optString("mount"), x.optLong("total"), x.optLong("free"),
                x.optLong("used"), List(rs.length()) { k ->
                    val r = rs.getJSONObject(k)
                    val tags = r.optJSONArray("tags") ?: JSONArray()
                    val pr = r.optJSONObject("profiles") ?: JSONObject()
                    DiskRoot(r.getInt("root"), r.optString("id"), r.optString("name"), List(tags.length()) { tags.getString(it) },
                        r.optLong("size"), r.optInt("files"), r.optString("profile", "original"),
                        pr.keys().asSequence().associateWith { pr.optString(it) })
                }, x.optBoolean("system"), x.optString("home").takeIf { !x.isNull("home") }.orEmpty())
        }, List(t.length()) { i ->
            val x = t.getJSONObject(i)
            TrashDay(x.optInt("root"), x.optString("root_name"), x.optString("day"), x.optLong("size"), x.optInt("files"))
        }, o.optInt("trash_days", 14))
    }

    private fun plan(o: JSONObject) = LibPlan(o.getString("id"), o.optString("kind"), o.optString("state"), o.optInt("progress"),
        o.optStr("error"), o.optJSONObject("result"), o.optStr("step"))

    /** Starts a plan (a preview: nothing changes on disk); body as in docs/api.md (`kind`: free, shrink, tidy, dupes, trash, move). */
    fun planStart(body: JSONObject): LibPlan = plan(JSONObject(call("POST", "/api/library/plan", body, 20000)))
    fun planGet(id: String): LibPlan = plan(JSONObject(call("GET", "/api/library/plan/$id")))

    /** Runs the picked items of a plan as a background task. */
    fun taskStart(planId: String, pick: List<Int>): LibTask =
        task(JSONObject(call("POST", "/api/tasks", JSONObject().put("plan", planId).put("pick", JSONArray(pick)), 20000)))
            .also { TaskWatch.watch(s.ctx, srv.key, it.id) }  // started here: this phone follows it
    /** [brief]: without the items, only their counts (when the agent can, `Server.hasTasksBrief`). */
    fun tasks(brief: Boolean = false): List<LibTask> {
        val a = JSONArray(call("GET", if (brief && srv.hasTasksBrief) "/api/tasks?brief=1" else "/api/tasks"))
        return List(a.length()) { task(a.getJSONObject(it)) }
    }
    fun taskGet(id: String): LibTask = task(JSONObject(call("GET", "/api/tasks/$id")))
    fun taskCancel(id: String) { call("POST", "/api/tasks/$id/cancel", JSONObject()) }
    /** A finished or cancelled task off the list (agent 1.12.1+, `Server.hasTaskRemove`). */
    fun taskRemove(id: String) { call("POST", "/api/tasks/$id/remove", JSONObject()) }

    private fun task(o: JSONObject): LibTask {
        val arr = o.optJSONArray("items") ?: JSONArray()
        return LibTask(o.optString("id"), o.optString("kind"), o.optString("title"), o.optString("state"),
            o.optDouble("created", 0.0), o.optDouble("finished").takeIf { !it.isNaN() && it > 0 },
            o.optLong("total"), o.optLong("done_bytes"), o.optInt("pct"), o.optStr("current"), o.optLong("saved"),
            o.optInt("errors"), o.optBoolean("paused"), List(arr.length()) { i ->
                val x = arr.getJSONObject(i)
                TaskItem(x.optString("name").ifEmpty { x.optString("path").substringAfterLast('/') }, x.optString("path"),
                    x.optLong("size"), x.optString("state"), x.optStr("error"), x.optLong("saved"), x.optStr("note"))
            }, o.optStr("encoder"), o.optStr("note"), o.optInt("fixed"), o.optInt("unfit"),
            o.optInt("item_count", arr.length()), o.optInt("items_done", -1).takeIf { it >= 0 }
                ?: (0 until arr.length()).count { arr.getJSONObject(it).optString("state") == "done" },
            o.optJSONArray("error_groups")?.let { g -> List(g.length()) { g.getJSONObject(it).let { e -> e.optString("error") to e.optInt("count") } } }
                ?: emptyList(), o.optStr("scope_label"))
    }

    /** Saves a storage profile for a folder (`path` "") or a subfolder. */
    fun setProfile(root: Int, path: String, profile: String) {
        call("POST", "/api/library/profile", JSONObject().put("root", root).put("path", path).put("profile", profile))
    }
    /** Empties the trash; returns the bytes freed. */
    fun trashEmpty(): Long = JSONObject(call("POST", "/api/library/trash/empty", JSONObject(), 60000)).optLong("freed")

    /** Cover of a folder or a video (agent 1.6+, also for guests), null when there is none. */
    /** A cover's bytes and whether it is a frame the agent took from the video (agent 1.10+ says so; older: false). */
    class CoverBytes(val bytes: ByteArray, val frame: Boolean)

    fun cover(root: Int, path: String, wide: Boolean = false): CoverBytes? {
        if (!configured) return null
        if (Home.away(srv.key)) throw IOException("not on this network")  // tried again once it is (LibraryTasks.loadRetrying)
        val c = URL("$base/api/library/cover?root=$root&path=${Uri.encode(path)}" + (if (wide) "&w=720" else ""))
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer ${srv.token}")
            val code = c.responseCode
            if (code == 404) return null
            if (code >= 400) throw ApiException("HTTP $code", code)
            return CoverBytes(c.inputStream.use { it.readBytes() }, c.getHeaderField("X-Cover-Kind") == "frame")
        } catch (e: IOException) {
            throw ApiException(e.javaClass.simpleName)
        } finally {
            c.disconnect()
        }
    }

    /** Computer directories for choosing a new library directory; path "" = starting points. */
    fun fsDirs(path: String): Pair<String?, List<String>> {
        val o = JSONObject(call("GET", "/api/fs?path=${Uri.encode(path)}"))
        val d = o.getJSONArray("dirs")
        return o.optString("parent").takeIf { !o.isNull("parent") } to List(d.length()) { d.getString(it) }
    }

    // ---- subtitles
    /** `want` (agent 1.21.3+): the player's language; the server prepares fitted subtitles in it when missing. */
    fun subsList(ref: MediaRef, want: String? = null): List<SubFile> {
        val w = want?.takeIf { it.isNotEmpty() && it != "off" && srv.hasSubsWant }?.let { "&want=${Uri.encode(it)}" } ?: ""
        val a = JSONArray(call("GET", "/api/subs/list?${ref.query()}$w"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            SubFile(o.getString("name"), o.optString("lang"), o.optString("ext"), o.optJSONObject("fit")?.optStr("verdict"),
                o.optBoolean("default", '/' !in o.getString("name")),
                o.optJSONObject("source")?.let { SubSource(it.optString("provider"), it.optString("id"), it.optString("release")) },
                o.optJSONObject("fit")?.optDouble("manual", 0.0)?.takeIf { !it.isNaN() } ?: 0.0, o.optLong("mtime"),
                o.optJSONObject("fit")?.optBoolean("weak") ?: false, o.optJSONObject("fit")?.optStr("reason"))
        }
    }

    /** Agent 1.12+: this version becomes its language's default (the current one moves to Subtitles/); the new name. */
    fun subsDefault(ref: MediaRef, name: String): String =
        JSONObject(call("POST", "/api/subs/default", ref.json().put("name", name))).getString("name")

    /** Agent 1.12.1+: moves the subtitle file by `by` seconds (+ later); the total shift by hand. */
    fun subsShift(ref: MediaRef, name: String, by: Double): Double =
        JSONObject(call("POST", "/api/subs/shift", ref.json().put("name", name).put("by", by))).optDouble("manual", 0.0)

    fun subUrl(ref: MediaRef, name: String) = "$base/sub?${ref.query()}&name=${Uri.encode(name)}&token=${Uri.encode(srv.token)}"

    private fun subsBody(ref: MediaRef, title: String?, year: Int?, langs: String? = null) = ref.json().put("languages", langs ?: s.subLanguages).apply {
        if (!title.isNullOrBlank()) put("title", title.trim())
        if (year != null) put("year", year)
    }

    /** Starts a background search; progress via [subsJob]. */
    fun subsSearchStart(ref: MediaRef, title: String? = null, year: Int? = null, langs: String? = null): String =
        JSONObject(call("POST", "/api/subs/search", subsBody(ref, title, year, langs).put("async", true))).getString("job")

    /** "Enough": the agent stops waiting for slow sources and returns what it already has. */
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
                    x.optInt("score"), x.optBoolean("hash_match"), x.optBoolean("hi"), x.optInt("downloads"),
                    x.optBoolean("machine"), x.optBoolean("trusted"), x.optDouble("fps").takeIf { !it.isNaN() && it > 0 },
                    x.optBoolean("fps_off"))
            }, r.optInt("hidden"))
        }
        return SubsProgress(o.optString("phase"), o.optInt("round", 1), o.optString("query").takeIf { it.isNotEmpty() && it != "null" },
            o.optInt("matched"), o.optInt("total"), providers, o.optDouble("elapsed"), o.optBoolean("done"),
            o.optString("error").takeIf { it.isNotEmpty() }, found)
    }

    /** Downloads subtitles; agent 1.9+ then fits them to the film's speech while we wait (~30 s the first time). */
    fun subsDownload(ref: MediaRef, r: SubResult, title: String? = null, year: Int? = null, langs: String? = null,
                     onPhase: (String) -> Unit = {}): SubsDownloaded {
        val fit = srv.hasSubsFit
        val body = subsBody(ref, title, year, langs).put("id", r.id).put("provider", r.provider).apply { if (fit) put("fit", "wait") }
        val o = if (srv.hasSubsDownloadJob) {
            // agent 1.12.1+: a job, so the app can say "downloading" and then "fitting" (that can take a minute)
            val id = JSONObject(call("POST", "/api/subs/download", body.put("async", true))).getString("job")
            var res: JSONObject? = null
            val until = System.currentTimeMillis() + 300_000
            while (res == null) {
                val j = JSONObject(call("GET", "/api/subs/job?id=$id"))
                j.optStr("error")?.let { throw ApiException(it, 500, j.optStr("detail")) }
                if (j.optBoolean("done")) res = j.optJSONObject("result") ?: throw ApiException("no result")
                else {
                    onPhase(j.optString("phase"))
                    if (System.currentTimeMillis() > until) throw ApiException("timeout")
                    Thread.sleep(700)
                }
            }
            res
        } else JSONObject(call("POST", "/api/subs/download", body, if (fit) 240000 else 90000))
        val f = o.optJSONObject("fit")
        return SubsDownloaded(o.optString("name"), f?.optStr("verdict"), f?.optBoolean("changed") ?: false, o.optString("provider"),
            f?.optBoolean("weak") ?: false, f?.optStr("reason"), f?.optStr("error"))
    }

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

    /** File format and rating for Chromecast, the "for Chromecast" version and its preparation (agent 1.2+). */
    fun mediaProbe(ref: MediaRef): JSONObject = JSONObject(call("GET", "/api/media/probe?${ref.query()}" +
        // the remembered audio choice: the answer says which track a version would carry and whether the prepared one
        // has it (agent 1.18+, `audio_want`, `variant.audio_match`)
        (audioPref(ref)?.takeIf { srv.hasConvertAudioMatch }?.let { "&audio=" + Uri.encode(it) } ?: ""), timeout = 30000))

    /** The audio picked for this folder (a series) or in general ([AudioPref.encode]), as the players use it. */
    fun audioPref(ref: MediaRef): String? = (ref as? MediaRef.InLibrary)?.let { s.folderAudio(folderKey(it)) } ?: s.audioChoice

    /** A library video's recognised name ("Series · S2 E3", "Film (1968)"), null when unknown or the agent is older
     * (agent 1.18+, `features.media_title`). */
    fun mediaTitle(ref: MediaRef): String? {
        if (!srv.hasMediaTitle) return null
        val o = JSONObject(call("GET", "/api/media/title?${ref.query()}", timeout = 4000))
        return if (o.isNull("title")) null else o.optString("title").ifBlank { null }
    }
    fun mediaConvert(ref: MediaRef, op: String? = null): JSONObject =
        JSONObject(call("POST", "/api/media/convert", ref.json().apply {
            if (op != null) put(op, true)
            // the version for a TV carries the audio this phone picked last (the same folder's choice first): a
            // Chromecast plays one audio track of a file, it can't switch like the phone's player or mpv
            else if (srv.hasConvertAudio) audioPref(ref)?.let { put("audio", it) }
        }))

    /** Chromecast seen by the agent (any sender): `{active, device, id, app, title, state, pos, …, all: [...]}`. */
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

    /** `subLang` (agent 1.12.1+, `features.player_sub_lang`): the last subtitle choice, a language or "off".
     * `audio` (agent 1.13.3+, `features.player_audio`): the remembered audio choice ([AudioPref.encode]). */
    fun playerOpen(ref: MediaRef, startSec: Double? = null, subLang: String? = null, audio: String? = null) {
        val b = ref.json()
        if (startSec != null) b.put("start", startSec)
        if (subLang != null && srv.hasPlayerSubLang) b.put("sub_lang", subLang)
        if (audio != null && srv.hasPlayerAudio) b.put("audio", audio)
        call("POST", "/api/player/open", b, 20000)
    }

    /** Video from another server (URL with a ticket). */
    fun playerOpenUrl(t: Ticket, startSec: Double? = null, subLang: String? = null, audio: String? = null) {
        val b = JSONObject().put("url", t.url).put("subs", JSONArray(t.subs)).put("title", t.title)
        if (subLang != null && srv.hasPlayerSubLang) b.put("sub_lang", subLang)
        if (audio != null && srv.hasPlayerAudio) b.put("audio", audio)
        if (startSec != null && startSec > 0) b.put("start", startSec)
        call("POST", "/api/player/open", b, 25000)
    }

    fun suspendPc() { call("POST", "/api/system/suspend") }

    // ---- uploading videos from the phone to the server (Uploads.kt; agent with features.upload)
    /** `GET /api/upload/<id>` → HTTP code and JSON (`received`, `done`); HTTP errors do not throw, because 404/409/507 carry state. */
    fun uploadStatus(id: String): Pair<Int, JSONObject> = uploadRequest("GET", "/api/upload/$id", 0, null, null)

    /**
     * `PUT /api/upload/<id>`: [length] bytes from [input] (streamed, without loading into memory); [length] = 0 is just
     * a check (offset, free space). [onProgress] receives the number of bytes sent and may abort by throwing.
     */
    fun uploadPut(id: String, name: String, size: Long, offset: Long, input: java.io.InputStream?, length: Long,
                  onProgress: ((Long) -> Unit)? = null): Pair<Int, JSONObject> =
        uploadRequest("PUT", "/api/upload/$id?name=${Uri.encode(name)}&size=$size&offset=$offset", length, input, onProgress)

    private fun uploadRequest(method: String, path: String, length: Long, input: java.io.InputStream?,
                              onProgress: ((Long) -> Unit)?): Pair<Int, JSONObject> {
        if (!configured) throw ApiException(ctx.getString(R.string.media_err_add_server))
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 90_000
            c.setRequestProperty("Authorization", "Bearer ${srv.token}")
            c.acceptPhoneLanguage()
            if (method == "PUT") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(length)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    val buf = ByteArray(256 shl 10)
                    var sent = 0L
                    while (input != null && sent < length) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt())
                        if (n < 0) throw IOException("file on the phone is shorter than before")
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
            throw ApiException(ctx.getString(R.string.media_err_no_response,
                srv.name.ifEmpty { ctx.getString(R.string.media_server_cap) }, e.javaClass.simpleName))
        } finally {
            c.disconnect()
        }
    }
    fun screenOn() { call("POST", "/api/system/screen_on") }

    fun mediaUrl(ref: MediaRef): String = when (ref) {
        is MediaRef.InTorrent -> "$base/media/${ref.torrentId}/${ref.index}/${Uri.encode(ref.title)}?token=${Uri.encode(srv.token)}"
        is MediaRef.InLibrary -> "$base/lib/${ref.root}/${ref.path.split('/').joinToString("/") { Uri.encode(it) }}?token=${Uri.encode(srv.token)}"
        is MediaRef.Playing -> throw ApiException(ctx.getString(R.string.media_err_cannot_stream_playing))
    }

    /**
     * If the server sleeps: Wake-on-LAN and waiting until the agent answers (up to 2 min). The packet goes from the phone, and after a few
     * seconds also through other running servers (`wake_others`), because it will not cross to another subnet from the phone.
     * A successful wake is remembered (`wakeOk`), so "Suspend" does not warn.
     */
    suspend fun ensureAwake(onStatus: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (ping(force = true)) { Home.mark(srv.key, Home.Here.HERE); return@withContext }
        if (wrongServer) throw ApiException(ctx.getString(R.string.media_err_wrong_server, srv.host, srv.name))
        val mac = Servers.get(ctx, srv.key)?.mac.orEmpty().ifBlank { srv.mac }
        if (mac.isBlank()) throw ApiException(
            ctx.getString(R.string.media_err_no_mac, srv.name)
        )
        val name = srv.name.ifEmpty { ctx.getString(R.string.media_server_lower) }
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
                // packet from the phone did not work (maybe another subnet): ask a running server
                via = Servers.all(ctx).filter { it.key != srv.key && it.canWakeOthers }.firstNotNullOfOrNull { o ->
                    val a = Api(s, o)
                    if (a.ping(1000) && runCatching { a.wakeOther(mac, srv.host) }.isSuccess) o.name else null
                } ?: ""
            }
            val secs = ((now - start) / 1000).toInt()
            onStatus(if (!via.isNullOrEmpty()) ctx.getString(R.string.media_waking_via, name, via, secs)
                else ctx.getString(R.string.media_waking, name, secs))
            if (ping(1500, force = true)) {
                Home.mark(srv.key, Home.Here.HERE)  // before the call below, which an "away" server refuses
                Servers.update(ctx, srv.key) { it.copy(wakeOk = true) }
                // the agent remembers this for all devices (an older agent does not know the endpoint: too bad)
                runCatching { call("POST", "/api/wake/verified", JSONObject(), 4000) }
                return@withContext
            }
            delay(1500)
        }
        throw ApiException(ctx.getString(R.string.media_err_wake_timeout, name))
    }

    /** Adding a magnet with retries: after waking, deluged/deluge-web need a moment. */
    suspend fun addMagnetRetrying(uri: String, sequential: Boolean, after: String, dest: DlTarget? = null,
                                  guess: JSONObject? = null, onStatus: (String) -> Unit): String? =
        retrying502(12, onStatus) { addMagnet(uri, sequential, after, dest, guess) }

    /** The same for an Internet Archive item; fewer tries, as a 502 may also be archive.org not answering. */
    suspend fun addTorrentUrlRetrying(pick: ArchivePick, sequential: Boolean, after: String, dest: DlTarget? = null,
                                      guess: JSONObject? = null, onStatus: (String) -> Unit): String? =
        retrying502(4, onStatus) { addTorrentUrl(pick, sequential, after, dest, guess) }

    private suspend fun retrying502(tries: Int, onStatus: (String) -> Unit, add: () -> String?): String? =
        withContext(Dispatchers.IO) {
            var last: ApiException? = null
            repeat(tries) { attempt ->
                try {
                    return@withContext add()
                } catch (e: ApiException) {
                    if (e.code != 502 && e.code != 0) throw e
                    last = e
                    onStatus(ctx.getString(R.string.media_waiting_deluge, attempt + 1))
                    delay(3000)
                }
            }
            throw last ?: ApiException(ctx.getString(R.string.media_err_add_failed))
        }
}

/** String field that is absent, JSON null or empty → null. */
fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

fun magnetName(magnet: String): String? =
    magnet.substringAfter('?', "").split('&').firstOrNull { it.startsWith("dn=") }
        ?.let { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }

fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(java.util.Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
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

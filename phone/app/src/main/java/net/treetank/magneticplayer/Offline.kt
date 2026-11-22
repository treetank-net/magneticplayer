package net.treetank.magneticplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** When to delete a downloaded video: after watching, N days after the download, or only manually. */
val RETENTION = listOf(
    "watched" to R.string.media_ret_watched,
    "days:2" to R.string.media_ret_2d,
    "days:7" to R.string.media_ret_7d,
    "days:30" to R.string.media_ret_30d,
    "manual" to R.string.media_ret_manual,
)

fun retentionLabel(ctx: Context, policy: String) = RETENTION.firstOrNull { it.first == policy }?.let { ctx.getString(it.second) } ?: policy

/** Subtitles saved on the phone together with a downloaded video. `default`: its language's version in use. */
data class LocalSub(
    val name: String, val lang: String, val ext: String, val file: String, val default: Boolean = true,
    /** the server file's [SubFile.mtime] when it was copied (0 = not known): a newer one there = refresh the copy */
    val mtime: Long = 0,
) {
    fun toSubFile() = SubFile(name, lang, ext, default = default)
}

/**
 * A video that has something on the phone's disk (MediaCache). `pinned` = downloaded with the "Download to phone" button: it does not disappear
 * through LRU and is deleted per `policy`. Without `pinned` it is an ordinary watching buffer (deleted after watching or after 48 h).
 */
data class OfflineItem(
    val key: String,
    val ref: MediaRef,
    val title: String,
    val size: Long = 0,
    val durationMs: Long = 0,
    val pinned: Boolean = false,
    val policy: String = "watched",
    /** download should continue (DownloadService), false = paused or interrupted */
    val want: Boolean = false,
    val complete: Boolean = false,
    /** reason the download was interrupted, to show in the list */
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
    val subs: List<LocalSub> = emptyList(),
    /** a series episode: intro (ms from, to) and where the credits start (ms), from the agent; for a TV fed by the phone */
    val intro: Pair<Long, Long>? = null,
    val creditsAt: Long? = null,
    /** the series folder's episodes in watching order (paths like [MediaRef.InLibrary.path]); null = not known */
    val episodes: List<String>? = null,
    /** the server file's Last-Modified (ms) the copy was taken from; 0 = unknown (older copies, or the file was still
     *  downloading on the server). A different one later = the copy is stale and gets downloaded again. */
    val stamp: Long = 0,
) {
    fun json(): JSONObject = JSONObject().put("key", key).put("ref", ref.encode()).put("title", title)
        .put("size", size).put("dur", durationMs).put("pinned", pinned).put("policy", policy).put("want", want)
        .put("complete", complete).put("error", error ?: JSONObject.NULL).put("created", created)
        .put("subs", JSONArray(subs.map {
            JSONObject().put("name", it.name).put("lang", it.lang).put("ext", it.ext).put("file", it.file).put("default", it.default)
                .apply { if (it.mtime > 0) put("mtime", it.mtime) }
        }))
        .apply {
            intro?.let { put("intro", JSONArray().put(it.first).put(it.second)) }
            creditsAt?.let { put("credits", it) }
            episodes?.let { put("episodes", JSONArray(it)) }
            if (stamp > 0) put("stamp", stamp)
        }

    companion object {
        fun from(o: JSONObject): OfflineItem {
            val a = o.optJSONArray("subs") ?: JSONArray()
            return OfflineItem(
                o.getString("key"), MediaRef.decode(o.getString("ref")), o.optString("title"),
                o.optLong("size"), o.optLong("dur"), o.optBoolean("pinned"), o.optString("policy", "watched"),
                o.optBoolean("want"), o.optBoolean("complete"), o.optString("error").takeIf { !o.isNull("error") && it.isNotEmpty() },
                o.optLong("created", System.currentTimeMillis()),
                List(a.length()) { i ->
                    a.getJSONObject(i).let { LocalSub(it.getString("name"), it.optString("lang"), it.optString("ext"), it.getString("file"),
                        it.optBoolean("default", true), it.optLong("mtime")) }
                },
                o.optJSONArray("intro")?.takeIf { it.length() == 2 }?.let { it.getLong(0) to it.getLong(1) },
                o.optLong("credits", -1).takeIf { it > 0 },
                o.optJSONArray("episodes")?.let { e -> List(e.length()) { e.getString(it) } },
                o.optLong("stamp"),
            )
        }
    }
}

/** List of videos on the phone (SharedPreferences "offline", one JSON per video). */
object OfflineStore {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("offline", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): OfflineItem? =
        prefs(ctx).getString("item:$key", null)?.let { runCatching { OfflineItem.from(JSONObject(it)) }.getOrNull() }

    fun all(ctx: Context): List<OfflineItem> = prefs(ctx).all.entries
        .filter { it.key.startsWith("item:") }
        .mapNotNull { e -> runCatching { OfflineItem.from(JSONObject(e.value as String)) }.getOrNull() }
        .sortedByDescending { it.created }

    @Synchronized
    fun put(ctx: Context, item: OfflineItem) {
        prefs(ctx).edit().putString("item:${item.key}", item.json().toString()).apply()
    }

    @Synchronized
    fun update(ctx: Context, key: String, f: (OfflineItem) -> OfflineItem) {
        get(ctx, key)?.let { put(ctx, f(it)) }
    }

    /** Entry for a video opened in the player (watching buffer), if there is none yet. */
    @Synchronized
    fun ensure(ctx: Context, ref: MediaRef) {
        if (get(ctx, ref.key()) == null) put(ctx, OfflineItem(ref.key(), ref, ref.title))
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        subsDir(ctx, key).deleteRecursively()
        prefs(ctx).edit().remove("item:$key").apply()
    }

    fun isPinned(ctx: Context, key: String) = get(ctx, key)?.pinned == true

    /** Subtitles of a downloaded video: filesDir/offline/<sha1 of key>/ (the system cache will not clean them up). */
    fun subsDir(ctx: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(ctx.applicationContext.filesDir, "offline/$h")
    }
}

/**
 * What a downloaded video needs from the agent besides its bytes, so the phone can play it (or feed a TV) without the
 * computer: the subtitles as they are now (fitted, shifted, the version in use), intro and credits, the series' episodes.
 * Saved when the download starts, again when it completes, and after the subtitles change (a download, a shift).
 */
object OfflineSync {
    /** Blocking: copies the current state from the agent into the item. Subtitles: filesDir/offline/<hash>/. */
    fun save(ctx: Context, api: Api, key: String) {
        val item = OfflineStore.get(ctx, key) ?: return
        val dir = OfflineStore.subsDir(ctx, key)
        val list = api.subsList(item.ref)
        val saved = list.mapNotNull { s ->
            val h = MessageDigest.getInstance("SHA-1").digest(s.name.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val f = File(dir, "$h.${s.ext}")
            val tmp = File(dir, "$h.${s.ext}.part")
            runCatching { download(api.subUrl(item.ref, s.name), tmp); tmp.renameTo(f); LocalSub(s.name, s.lang, s.ext, f.path, s.default, s.mtime) }
                .getOrElse { tmp.delete(); null }
        }
        val keep = saved.map { File(it.file).name }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        var intro = item.intro
        var credits = item.creditsAt
        var episodes = item.episodes
        (item.ref as? MediaRef.InLibrary)?.let { r ->
            if (api.srv.segments) runCatching { api.segments(r.root, r.path) }.getOrNull()?.takeIf { !it.pending }?.let { s ->
                intro = if (s.introStart != null && s.introEnd != null && s.introEnd > s.introStart) s.introStart to s.introEnd else null
                credits = s.creditsAt
            }
            if (api.srv.libraryEpisodes) runCatching { api.episodes(r.root, r.path.substringBeforeLast('/', "")) }.getOrNull()
                ?.let { e -> episodes = e.episodes.map { it.path } }
        }
        OfflineStore.update(ctx, key) { it.copy(subs = saved, intro = intro, creditsAt = credits, episodes = episodes) }
    }

    private val verified = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Blocking, at a sync point (the catalog of `api`'s server refreshed): downloaded copies whose file changed on the
     * server since (a torrent that finished after the copy was taken, a re-encode, fixed sound) are stale: their bytes go,
     * and a download asked for by the user starts again. Copies made before stamps were kept count as stale when the file
     * is newer than the download (that is how a copy of a torrent still downloading looked: zeros where pieces were
     * missing, "whole" on the phone). At most every 10 minutes per server.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun verify(ctx: Context, api: Api) {
        val now = System.currentTimeMillis()
        if (now - (verified[api.srv.key] ?: 0) < 10 * 60_000) return
        verified[api.srv.key] = now
        val cache = MediaCache.get(ctx)
        for (item in OfflineStore.all(ctx)) {
            if (item.want && !item.complete) continue
            if (runCatching { Api.forRef(ctx, item.ref).srv.key }.getOrNull() != api.srv.key) continue
            val (stamp, busy) = runCatching { fileStamp(api.mediaUrl(item.ref)) }.getOrNull() ?: continue
            if (busy || stamp <= 0) continue
            val stale = if (item.stamp > 0) stamp != item.stamp else stamp > item.created
            if (!stale) {
                if (item.stamp == 0L && item.complete) OfflineStore.update(ctx, item.key) { it.copy(stamp = stamp) }
                continue
            }
            if (runCatching { cache.getCachedBytes(item.key, 0, item.size.coerceAtLeast(1)) }.getOrDefault(0) <= 0) continue
            runCatching { cache.removeResource(item.key) }
            OfflineStore.update(ctx, item.key) { it.copy(complete = false, stamp = 0, error = null) }
            // a whole copy is downloaded again; a paused one waits for the user. Starting the service from the
            // background can be refused: the item stays wanted and the next download run takes it.
            if (item.pinned && item.complete) runCatching { DownloadService.start(ctx, item.ref) }
        }
    }

    private val subsChecked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Blocking, at a sync point (after [WatchSync], so a subtitle choice made offline has reached the server): a
     * downloaded copy whose subtitles differ from the server's (another version in use, a new download, a shift or a
     * fit, by [SubFile.mtime] with agent 1.18+) gets the server's. One subtitle list per downloaded copy, at most every
     * 10 minutes per server; a copy with a choice still waiting to be sent keeps its own.
     */
    fun refreshSubs(ctx: Context, api: Api) {
        val now = System.currentTimeMillis()
        if (now - (subsChecked[api.srv.key] ?: 0) < 10 * 60_000) return
        subsChecked[api.srv.key] = now
        for (item in OfflineStore.all(ctx)) {
            if (!item.pinned || WatchSync.pickPending(ctx, item.key)) continue
            if (runCatching { Api.forRef(ctx, item.ref).srv.key }.getOrNull() != api.srv.key) continue
            val there = runCatching { api.subsList(item.ref) }.getOrNull() ?: continue
            fun sig(name: String, default: Boolean, mtime: Long) = Triple(name, default, if (there.any { it.mtime > 0 }) mtime else 0L)
            val mine = item.subs.filter { File(it.file).exists() }.map { sig(it.name, it.default, it.mtime) }.toSet()
            if (there.map { sig(it.name, it.default, it.mtime) }.toSet() != mine) runCatching { save(ctx, api, item.key) }
        }
    }

    /** After the subtitles of `ref` changed: a downloaded copy gets them too (in the background, when the agent answers). */
    fun refreshLater(ctx: Context, ref: MediaRef) {
        val app = ctx.applicationContext
        if (OfflineStore.get(app, ref.key())?.pinned != true) return
        Thread { runCatching { Api.forRef(app, ref).let { save(app, it, ref.key()) } } }.start()
    }
}

/** HEAD with Range from `from` on the agent's stream: (file size, X-Ready-End, i.e. how far the computer has downloaded). */
fun headProbe(url: String, from: Long): Pair<Long, Long> {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "HEAD"
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("Range", "bytes=$from-")
        c.acceptPhoneLanguage()
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        val size = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: throw IOException("missing size")
        val ready = c.getHeaderField("X-Ready-End")?.toLongOrNull() ?: size
        return size to ready
    } finally {
        c.disconnect()
    }
}

/** HEAD on the agent's stream: the file's Last-Modified (ms, 0 = the agent doesn't say) and whether it is part of a
 *  torrent still downloading (X-Downloading, agent 1.15.2+), when it will still change. */
fun fileStamp(url: String): Pair<Long, Boolean> {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "HEAD"
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        return c.lastModified to (c.getHeaderField("X-Downloading") == "1")
    } finally {
        c.disconnect()
    }
}

/** Downloads a file from a URL to `f` (subtitles). */
fun download(url: String, f: File) {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 8_000; c.readTimeout = 15_000
        c.acceptPhoneLanguage()
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
        f.parentFile?.mkdirs()
        c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
    } finally {
        c.disconnect()
    }
}

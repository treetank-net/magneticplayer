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
data class LocalSub(val name: String, val lang: String, val ext: String, val file: String, val default: Boolean = true) {
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
) {
    fun json(): JSONObject = JSONObject().put("key", key).put("ref", ref.encode()).put("title", title)
        .put("size", size).put("dur", durationMs).put("pinned", pinned).put("policy", policy).put("want", want)
        .put("complete", complete).put("error", error ?: JSONObject.NULL).put("created", created)
        .put("subs", JSONArray(subs.map {
            JSONObject().put("name", it.name).put("lang", it.lang).put("ext", it.ext).put("file", it.file).put("default", it.default)
        }))
        .apply {
            intro?.let { put("intro", JSONArray().put(it.first).put(it.second)) }
            creditsAt?.let { put("credits", it) }
            episodes?.let { put("episodes", JSONArray(it)) }
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
                        it.optBoolean("default", true)) }
                },
                o.optJSONArray("intro")?.takeIf { it.length() == 2 }?.let { it.getLong(0) to it.getLong(1) },
                o.optLong("credits", -1).takeIf { it > 0 },
                o.optJSONArray("episodes")?.let { e -> List(e.length()) { e.getString(it) } },
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
            runCatching { download(api.subUrl(item.ref, s.name), tmp); tmp.renameTo(f); LocalSub(s.name, s.lang, s.ext, f.path, s.default) }
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

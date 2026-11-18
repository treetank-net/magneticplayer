package net.treetank.magneticplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Kiedy kasować pobrany film: po obejrzeniu, po N dniach od pobrania albo tylko ręcznie. */
val RETENTION = listOf(
    "watched" to R.string.media_ret_watched,
    "days:2" to R.string.media_ret_2d,
    "days:7" to R.string.media_ret_7d,
    "days:30" to R.string.media_ret_30d,
    "manual" to R.string.media_ret_manual,
)

fun retentionLabel(ctx: Context, policy: String) = RETENTION.firstOrNull { it.first == policy }?.let { ctx.getString(it.second) } ?: policy

/** Napisy zapisane na telefonie razem z pobranym filmem. */
data class LocalSub(val name: String, val lang: String, val ext: String, val file: String) {
    fun toSubFile() = SubFile(name, lang, ext)
}

/**
 * Film, który ma coś na dysku telefonu (MediaCache). `pinned` = pobrany przyciskiem „Pobierz na telefon”: nie znika
 * przez LRU, a kasuje się wg `policy`. Bez `pinned` to zwykły bufor z oglądania (kasowany po obejrzeniu albo 48 h).
 */
data class OfflineItem(
    val key: String,
    val ref: MediaRef,
    val title: String,
    val size: Long = 0,
    val durationMs: Long = 0,
    val pinned: Boolean = false,
    val policy: String = "watched",
    /** pobieranie ma trwać (DownloadService), false = wstrzymane albo przerwane */
    val want: Boolean = false,
    val complete: Boolean = false,
    /** powód przerwania pobierania, do pokazania na liście */
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
    val subs: List<LocalSub> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("key", key).put("ref", ref.encode()).put("title", title)
        .put("size", size).put("dur", durationMs).put("pinned", pinned).put("policy", policy).put("want", want)
        .put("complete", complete).put("error", error ?: JSONObject.NULL).put("created", created)
        .put("subs", JSONArray(subs.map {
            JSONObject().put("name", it.name).put("lang", it.lang).put("ext", it.ext).put("file", it.file)
        }))

    companion object {
        fun from(o: JSONObject): OfflineItem {
            val a = o.optJSONArray("subs") ?: JSONArray()
            return OfflineItem(
                o.getString("key"), MediaRef.decode(o.getString("ref")), o.optString("title"),
                o.optLong("size"), o.optLong("dur"), o.optBoolean("pinned"), o.optString("policy", "watched"),
                o.optBoolean("want"), o.optBoolean("complete"), o.optString("error").takeIf { !o.isNull("error") && it.isNotEmpty() },
                o.optLong("created", System.currentTimeMillis()),
                List(a.length()) { i ->
                    a.getJSONObject(i).let { LocalSub(it.getString("name"), it.optString("lang"), it.optString("ext"), it.getString("file")) }
                },
            )
        }
    }
}

/** Lista filmów na telefonie (SharedPreferences "offline", jeden JSON na film). */
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

    /** Wpis dla filmu otwartego w odtwarzaczu (bufor z oglądania), jeśli jeszcze go nie ma. */
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

    /** Napisy pobranego filmu: filesDir/offline/<sha1 klucza>/ (cache systemu ich nie sprzątnie). */
    fun subsDir(ctx: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(ctx.applicationContext.filesDir, "offline/$h")
    }
}

/** HEAD z Range od `from` na strumień z agenta: (rozmiar pliku, X-Ready-End, czyli do kiedy komputer ma pobrane). */
fun headProbe(url: String, from: Long): Pair<Long, Long> {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "HEAD"
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("Range", "bytes=$from-")
        c.acceptPhoneLanguage()
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        val size = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: throw IOException("brak rozmiaru")
        val ready = c.getHeaderField("X-Ready-End")?.toLongOrNull() ?: size
        return size to ready
    } finally {
        c.disconnect()
    }
}

/** Pobiera plik z URL do `f` (napisy). */
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

package net.treetank.magneticplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Covers kept on the phone's disk, by the owner's choice in Settings (`Settings.coverStore`):
 * - `none`: nothing on disk; covers come from the server while it answers (memory only), drawn ones otherwise;
 * - `tiny`: a tiny picture of every title (about 30 px wide, well under 1 KB, as messengers send before the photo),
 *   shown blurred when the server is out of reach and while the real cover loads; the whole library takes a few
 *   hundred KB;
 * - `full`: every cover at card size (the agent's 360 px picture, tens of KB each).
 * Covers seen while browsing are kept as they come; the rest are fetched in the background after a catalog refresh.
 * Files: `files/covers/<server>/<sha1 of root|path>.jpg`, `.f.jpg` for a frame from the video (no title on it).
 */
object CoverStore {
    const val NONE = "none"
    const val TINY = "tiny"
    const val FULL = "full"
    private const val TINY_W = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var mode: String? = null

    fun mode(ctx: Context): String = mode ?: Settings(ctx).coverStore.also { mode = it }

    fun setMode(ctx: Context, m: String) {
        val old = mode(ctx)
        Settings(ctx).coverStore = m
        mode = m
        if (m == old) return
        // a smaller choice drops what doesn't belong to it; a bigger one fills at the next catalog refresh
        scope.launch {
            when (m) {
                NONE -> dir(ctx).deleteRecursively()
                TINY -> dir(ctx).walkTopDown().filter { it.isFile }.forEach { f ->
                    if (f.length() > 4096) runCatching { shrink(f.readBytes())?.let { b -> f.writeBytes(b) } ?: f.delete() }
                }
            }
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers")

    private fun name(root: Int, path: String): String =
        MessageDigest.getInstance("SHA-1").digest("$root|$path".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun srvDir(ctx: Context, srv: String) = File(dir(ctx), srv.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun files(ctx: Context, srv: String, root: Int, path: String): Pair<File, File> {
        val n = name(root, path)
        val d = srvDir(ctx, srv)
        return File(d, "$n.jpg") to File(d, "$n.f.jpg")
    }

    /** A kept cover: (bitmap, is a frame, is tiny) or null. Blocking (disk). */
    fun read(ctx: Context, srv: String, root: Int, path: String): Triple<Bitmap, Boolean, Boolean>? {
        if (mode(ctx) == NONE) return null
        val (pic, frame) = files(ctx, srv, root, path)
        val f = if (pic.exists()) pic else if (frame.exists()) frame else return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        return Triple(bmp, f == frame, bmp.width <= TINY_W * 2)
    }

    /** Kept already (in the chosen size), or known to have no cover. */
    private fun has(ctx: Context, srv: String, root: Int, path: String, full: Boolean): Boolean {
        val (a, b) = files(ctx, srv, root, path)
        if (File(a.path.removeSuffix(".jpg") + ".none").exists()) return true
        val f = if (a.exists()) a else if (b.exists()) b else return false
        return !full || f.length() > 4096  // "full" after "tiny": the tiny ones are fetched again
    }

    /** Keeps a cover that came from the server, as the chosen mode says. */
    fun keep(ctx: Context, srv: String, root: Int, path: String, bytes: ByteArray, frame: Boolean) {
        val m = mode(ctx)
        if (m == NONE) return
        val data = if (m == TINY) shrink(bytes) ?: return else bytes
        val (pic, fr) = files(ctx, srv, root, path)
        val f = if (frame) fr else pic
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(data)
            tmp.renameTo(f)
            (if (frame) pic else fr).delete()
        }
    }

    /** About [TINY_W] px wide, low JPEG quality: the shape and colours of the cover, nothing more. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val h = (src.height * TINY_W / src.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, TINY_W, h, true)
        return java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 40, it) }.toByteArray()
    }

    /** Bytes on disk and number of covers kept, for Settings. */
    fun usage(ctx: Context): Pair<Long, Int> {
        val fs = dir(ctx).walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") }.toList()  // .none files are empty
        return fs.sumOf { it.length() } to fs.size
    }

    /**
     * Fetches the covers this phone doesn't keep yet for the catalog of [api]'s server, one at a time in the
     * background (the agent takes a frame from the video for a title without a picture: its disks are spared by
     * going slowly). Stops at the first error (the server went away) and starts again after the next refresh.
     */
    fun sync(ctx: Context, api: Api, cat: CatalogData) {
        val app = ctx.applicationContext
        if (mode(app) == NONE || !api.srv.libraryCovers) return
        val key = api.srv.key
        if (!syncing.add(key)) return
        scope.launch {
            try {
                for (x in cat.items) {
                    if (mode(app) == NONE) break
                    val (root, path) = x.art()
                    if (has(app, key, root, path, mode(app) == FULL)) continue
                    val got = try {
                        api.cover(root, path)
                    } catch (e: ApiException) {
                        break
                    }
                    if (got != null) keep(app, key, root, path, got.bytes, got.frame)
                    else runCatching {  // no picture and no frame: not asked again (until the setting changes)
                        val (a, _) = files(app, key, root, path)
                        a.parentFile?.mkdirs()
                        File(a.path.removeSuffix(".jpg") + ".none").createNewFile()
                    }
                    delay(150)
                }
            } finally {
                syncing.remove(key)
            }
        }
    }
}

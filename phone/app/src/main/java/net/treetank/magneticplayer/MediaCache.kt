package net.treetank.magneticplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * Videos on the phone's disk (noBackupFilesDir/media; formerly cacheDir/media, which Android clears when space runs low). Only prefetch in PlayerActivity and DownloadService write to it,
 * and only bytes the agent reported as downloaded (X-Ready-End), so zeros from an undownloaded torrent do not end up here.
 *
 * Cleanup ([sweep], [onWatched]):
 * - watching buffer: a watched video (≥80% or to the end) is dropped at once, an unwatched one after 48 h at app start;
 * - downloaded with the button (OfflineItem.pinned): per the chosen policy (after watching / after N days / manual).
 * Above [maxBytes] the least recently used chunks are evicted, but never from downloaded videos.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaCache {
    private const val BUFFER_MAX_AGE_MS = 48 * 3600_000L
    const val WATCHED = 0.8

    /**
     * Limit: 60% of free space (counting what the buffer already occupies), from 1 GB to 64 GB. Downloaded videos count towards it,
     * but [PinnedAwareLruEvictor] does not touch them. The evictor computes it once, on first use in the process.
     */
    fun maxBytes(ctx: Context): Long {
        val dir = dir(ctx)
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ((dir.parentFile!!.usableSpace + used) * 0.6).toLong().coerceIn(1L shl 30, 64L shl 30)
    }

    @Volatile private var cache: SimpleCache? = null

    /**
     * Buffer directory. Not cacheDir: the system deletes app files there that exceed the cache limit (here up to 60% of the disk),
     * so "downloaded" videos disappeared. The old directory is moved once (same filesystem, so a rename).
     */
    private fun dir(ctx: Context): File {
        val app = ctx.applicationContext
        val dir = File(app.noBackupFilesDir, "media")
        val old = File(app.cacheDir, "media")
        if (!dir.exists() && old.exists()) old.renameTo(dir)
        return dir
    }

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: ctx.applicationContext.let { app ->
            SimpleCache(dir(app),
                PinnedAwareLruEvictor(maxBytes(app)) { key -> OfflineStore.isPinned(app, key) },
                StandaloneDatabaseProvider(app))
        }.also { cache = it }
    }

    /** Stores the file length so the offline player knows where the end is. */
    fun setLength(ctx: Context, key: String, size: Long) {
        val c = get(ctx)
        if (androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key)) == size) return
        val m = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(m, size)
        runCatching { c.applyContentMetadataMutations(key, m) }
    }

    /** Removes a video from the phone together with its list entry and subtitles. Off the UI thread. */
    fun remove(ctx: Context, key: String) {
        DownloadService.cancel(key)
        runCatching { get(ctx).removeResource(key) }
        Settings(ctx).forgetCache(key)
        OfflineStore.remove(ctx, key)
    }

    /** Watched video (end or exit after ≥80%): we delete it unless it was downloaded with a policy other than "after watching". */
    fun onWatched(ctx: Context, key: String) {
        val item = OfflineStore.get(ctx, key)
        if (item == null || !item.pinned || item.policy == "watched") remove(ctx, key)
    }

    /** Cleans up per the rules in the class description, except `keep` (currently playing). Off the UI thread. */
    fun sweep(ctx: Context, keep: String? = null) {
        val s = Settings(ctx)
        val now = System.currentTimeMillis()
        val keys = runCatching { get(ctx).keys.toSet() }.getOrDefault(emptySet()) + OfflineStore.all(ctx).map { it.key }
        for (key in keys) {
            if (key == keep) continue
            val item = OfflineStore.get(ctx, key)
            val watched = s.cacheWatched(key) >= WATCHED
            val expired = if (item?.pinned == true) when {
                item.policy == "watched" -> watched
                item.policy.startsWith("days:") ->
                    now - item.created > (item.policy.removePrefix("days:").toLongOrNull() ?: 2) * 24 * 3600_000L
                else -> false
            } else {
                // watching buffer; an entry with nothing on disk is cleaned up too
                watched || now - s.cacheUsed(key) > BUFFER_MAX_AGE_MS ||
                    runCatching { get(ctx).getCachedBytes(key, 0, androidx.media3.common.C.LENGTH_UNSET.toLong()) }.getOrDefault(0) == 0L
            }
            if (expired) remove(ctx, key)
        }
    }
}

/** Like LeastRecentlyUsedCacheEvictor from media3, but skips chunks of downloaded videos (`pinned`). */
@androidx.annotation.OptIn(UnstableApi::class)
class PinnedAwareLruEvictor(private val maxBytes: Long, private val pinned: (String) -> Boolean) : CacheEvictor {
    private val lru = TreeSet<CacheSpan> { a, b ->
        when {
            a.lastTouchTimestamp != b.lastTouchTimestamp -> a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
            else -> a.compareTo(b)
        }
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        lru.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        lru.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, required: Long) {
        if (currentSize + required <= maxBytes) return
        var size = currentSize
        val victims = mutableListOf<CacheSpan>()
        for (s in lru) {
            if (size + required <= maxBytes) break
            if (pinned(s.key)) continue
            victims += s
            size -= s.length
        }
        victims.forEach { cache.removeSpan(it) }
    }
}

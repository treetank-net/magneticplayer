package eu.jm.magneticplayer

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
 * Filmy na dysku telefonu (cacheDir/media). Zapisują do niego tylko prefetch w PlayerActivity i DownloadService,
 * i to wyłącznie bajty, które agent zgłosił jako pobrane (X-Ready-End), więc nie trafią tu zera z niepobranego torrenta.
 *
 * Sprzątanie ([sweep], [onWatched]):
 * - bufor z oglądania: film obejrzany (≥80% albo do końca) wylatuje od razu, nieoglądany od 48 h przy starcie apki;
 * - pobrane przyciskiem (OfflineItem.pinned): wg wybranej zasady (po obejrzeniu / po N dniach / ręcznie).
 * Ponad [maxBytes] wyrzucane są najdawniej używane kawałki, ale nigdy z pobranych filmów.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaCache {
    private const val BUFFER_MAX_AGE_MS = 48 * 3600_000L
    const val WATCHED = 0.8

    /**
     * Limit: 60% wolnego miejsca (licząc to, co bufor już zajmuje), od 1 GB do 64 GB. Pobrane filmy liczą się do niego,
     * ale [PinnedAwareLruEvictor] ich nie rusza. Evictor liczy go raz, przy pierwszym użyciu w procesie.
     */
    fun maxBytes(ctx: Context): Long {
        val dir = File(ctx.applicationContext.cacheDir, "media")
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ((dir.parentFile!!.usableSpace + used) * 0.6).toLong().coerceIn(1L shl 30, 64L shl 30)
    }

    @Volatile private var cache: SimpleCache? = null

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: ctx.applicationContext.let { app ->
            SimpleCache(File(app.cacheDir, "media"),
                PinnedAwareLruEvictor(maxBytes(app)) { key -> OfflineStore.isPinned(app, key) },
                StandaloneDatabaseProvider(app))
        }.also { cache = it }
    }

    /** Zapisuje długość pliku, żeby odtwarzacz bez sieci wiedział, gdzie jest koniec. */
    fun setLength(ctx: Context, key: String, size: Long) {
        val c = get(ctx)
        if (androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key)) == size) return
        val m = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(m, size)
        runCatching { c.applyContentMetadataMutations(key, m) }
    }

    /** Usuwa film z telefonu razem z wpisem na liście i napisami. Poza wątkiem UI. */
    fun remove(ctx: Context, key: String) {
        DownloadService.cancel(key)
        runCatching { get(ctx).removeResource(key) }
        Settings(ctx).forgetCache(key)
        OfflineStore.remove(ctx, key)
    }

    /** Film obejrzany (koniec albo wyjście po ≥80%): kasujemy, chyba że pobrany z inną zasadą niż „po obejrzeniu”. */
    fun onWatched(ctx: Context, key: String) {
        val item = OfflineStore.get(ctx, key)
        if (item == null || !item.pinned || item.policy == "watched") remove(ctx, key)
    }

    /** Sprząta wg zasad z opisu klasy, poza `keep` (właśnie odtwarzany). Poza wątkiem UI. */
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
                // bufor z oglądania; wpis bez niczego na dysku też sprzątamy
                watched || now - s.cacheUsed(key) > BUFFER_MAX_AGE_MS ||
                    runCatching { get(ctx).getCachedBytes(key, 0, androidx.media3.common.C.LENGTH_UNSET.toLong()) }.getOrDefault(0) == 0L
            }
            if (expired) remove(ctx, key)
        }
    }
}

/** Jak LeastRecentlyUsedCacheEvictor z media3, tylko pomija kawałki pobranych filmów (`pinned`). */
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

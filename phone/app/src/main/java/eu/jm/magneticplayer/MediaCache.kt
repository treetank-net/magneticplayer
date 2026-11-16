package eu.jm.magneticplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Bufor filmów na dysku telefonu (cacheDir/media). Zapisuje do niego tylko prefetch w PlayerActivity, i to wyłącznie
 * bajty, które agent zgłosił jako pobrane (X-Ready-End), więc nie trafią tu zera z niepobranego torrenta.
 *
 * Sprzątanie: film obejrzany do końca od razu wylatuje, wyjście z odtwarzacza po ≥80% też. Gdy apkę ubito w trakcie,
 * [sweep] przy następnym starcie usuwa obejrzane ≥80% i wszystko, czego nie oglądano od [MAX_AGE_MS]. Ponad [MAX_BYTES]
 * LRU wyrzuca najdawniej używane.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaCache {
    private const val MAX_BYTES = 4L shl 30
    private const val MAX_AGE_MS = 48 * 3600_000L
    const val WATCHED = 0.8

    @Volatile private var cache: SimpleCache? = null

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: ctx.applicationContext.let { app ->
            SimpleCache(File(app.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(app))
        }.also { cache = it }
    }

    /** Wywoływać poza wątkiem UI. */
    fun remove(ctx: Context, key: String) {
        runCatching { get(ctx).removeResource(key) }
        Settings(ctx).forgetCache(key)
    }

    /** Usuwa obejrzane (≥80%) i dawno nieużywane filmy, poza `keep` (właśnie odtwarzany). Poza wątkiem UI. */
    fun sweep(ctx: Context, keep: String? = null) {
        val s = Settings(ctx)
        val now = System.currentTimeMillis()
        for (key in runCatching { get(ctx).keys.toList() }.getOrDefault(emptyList())) {
            if (key == keep) continue
            if (s.cacheWatched(key) >= WATCHED || now - s.cacheUsed(key) > MAX_AGE_MS) remove(ctx, key)
        }
    }
}

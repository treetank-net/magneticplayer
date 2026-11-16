package eu.jm.magneticplayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nakładka na pasek przewijania ExoPlayera (ViewOverlay, rysuje się nad nim i znika razem z nim): niebieskawy odcinek
 * od bieżącej pozycji do końca ciągłego zapasu na dysku. Szary od ExoPlayera to dalej bufor w pamięci (~50 s).
 * Geometria jak w DefaultTimeBar w stylu ExoStyledControls.TimeBar (media3-ui 1.8): pasek 2 dp przy dolnej krawędzi
 * obszaru 48 dp, z boków margines na kulkę (14 dp / 2).
 */
class DiskAheadOverlay(private val bar: android.view.View) : android.graphics.drawable.Drawable() {
    var positionMs = 0L
    var aheadEndMs = 0L
    var durationMs = 0L
    private val dp = bar.resources.displayMetrics.density
    private val paint = android.graphics.Paint().apply { color = 0xCC4FC3F7.toInt() }

    override fun draw(canvas: android.graphics.Canvas) {
        if (durationMs <= 0 || aheadEndMs <= positionMs) return
        val pad = 7 * dp
        val left = bar.paddingLeft + pad
        val width = bar.width - bar.paddingRight - pad - left
        if (width <= 0) return
        val h = 2 * dp
        val bottom = bar.height - bar.paddingBottom - pad + h / 2
        val x1 = left + width * positionMs.toFloat() / durationMs
        val x2 = left + width * minOf(aheadEndMs, durationMs).toFloat() / durationMs
        canvas.drawRect(x1, bottom - h, x2, bottom, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Drawable API")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

const val VLC_PACKAGE = "org.videolan.vlc"

/** Otwiera strumień w VLC (jeśli jest), inaczej wybór aplikacji. Napisy przekazuje przez extra VLC. */
fun openInVlc(ctx: Context, url: String, title: String, subUrl: String? = null) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
        .putExtra("title", title)
    if (subUrl != null) i.putExtra("subtitles_location", subUrl)
    val vlc = Intent(i).setPackage(VLC_PACKAGE)
    try {
        ctx.startActivity(vlc)
    } catch (_: Exception) {
        runCatching { ctx.startActivity(Intent.createChooser(i, "Otwórz w…")) }
            .onFailure { Toast.makeText(ctx, "Brak aplikacji do wideo — zainstaluj VLC", Toast.LENGTH_LONG).show() }
    }
}

/** Odtwarzacz w telefonie: strumień HTTP z komputera (ExoPlayer, przewijanie przez Range, napisy obok filmu). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {
    companion object {
        fun intent(ctx: Context, ref: MediaRef) =
            Intent(ctx, PlayerActivity::class.java).putExtra("ref", ref.encode())
    }

    private var player: ExoPlayer? = null
    private lateinit var settings: Settings
    private lateinit var api: Api
    private lateinit var ref: MediaRef
    private lateinit var url: String
    private var subs: List<SubFile> = emptyList()
    private var prefetchJob: Job? = null
    private var fileSize = 0L  // z prefetch (HEAD), do przeliczania bajtów na czas w nakładce
    private var diskOverlay: DiskAheadOverlay? = null
    private val key get() = ref.key()
    // agent czeka do 20 s na kolejne kawałki torrenta, więc timeout odczytu musi być dłuższy
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(30_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        api = Api(settings)
        ref = intent.getStringExtra("ref")?.let { MediaRef.decode(it) } ?: return finish()
        url = try { api.mediaUrl(ref) } catch (e: ApiException) { return finish() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("Na komputer") { handOffToPc() })
            addView(smallButton("VLC") {
                player?.pause()
                openInVlc(this@PlayerActivity, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
            })
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> buttons.visibility = v })
        view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
            val o = DiskAheadOverlay(bar)
            diskOverlay = o
            bar.overlay.add(o)
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> o.setBounds(0, 0, v.width, v.height) }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(16, 16, 16, 16) })
        })

        // najpierw dekodery telefonu, FFmpeg gdy telefon nie umie (AC3/E-AC3/DTS…)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // w pamięci zostaje domyślne ~50 s; dalszy zapas jest na dysku (prefetch). Odtwarzacz czyta z bufora na dysku,
        // a czego tam nie ma, bierze z sieci, ale sam do niego nie zapisuje.
        val source = CacheDataSource.Factory().setCache(MediaCache.get(this))
            // DefaultDataSource: http z komputera, ale też file:// dla napisów pobranych lokalnie (localSubs)
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // torrent się dociąga: agent zrywa po 20 s bez nowych kawałków, a my ponawiamy zamiast pokazać błąd
        val sources = DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(60))
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build()
        player = p
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefetchJob?.cancel()
                    val k = key
                    Thread { MediaCache.remove(this@PlayerActivity, k) }.start()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity,
                    "Nie da się odtworzyć: ${error.errorCodeName}. Spróbuj VLC albo poczekaj, aż się więcej pobierze.",
                    Toast.LENGTH_LONG).show()
            }
        })
        // preferowany język napisów = kolejność z ustawień
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*settings.subLanguages.split(',').filter { it.isNotBlank() }.toTypedArray())
            .build()
        val start = savedInstanceState?.getLong("pos") ?: settings.position(ref.key())

        lifecycleScope.launch {
            subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
            val local = withContext(Dispatchers.IO) { localSubs(subs) }
            val subConfigs = subs.mapNotNull { s ->
                val mime = when (s.ext) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> null
                } ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(local[s.name] ?: Uri.parse(api.subUrl(ref, s.name)))
                    .setMimeType(mime)
                    .setLanguage(s.lang.takeWhile { it.isLetter() }.ifEmpty { null })
                    .setLabel(s.name)
                    .setSelectionFlags(if (s == subs.first()) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            p.setMediaItem(MediaItem.Builder().setUri(url).setCustomCacheKey(key)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).build())
                .setSubtitleConfigurations(subConfigs)
                .build())
            p.seekTo(start)
            p.prepare()
            p.playWhenReady = true
            prefetchJob = prefetch(p)
            showDiskAhead(p)
        }
        settings.touchCache(key, settings.cacheWatched(key))
        Thread { MediaCache.sweep(this, keep = key) }.start()
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        alpha = 0.85f
        setOnClickListener { onClick() }
    }

    private fun handOffToPc() {
        val p = player ?: return
        val pos = p.currentPosition
        p.pause()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.playerOpen(ref, pos / 1000.0) }
                Toast.makeText(this@PlayerActivity, "Gra na komputerze", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@PlayerActivity, MainActivity::class.java).putExtra("tab", TAB_REMOTE)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            } catch (e: ApiException) {
                Toast.makeText(this@PlayerActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Dociąga na dysk do [Settings.bufferMinutes] minut od bieżącej pozycji, ale tylko do X-Ready-End (bajty, które
     * komputer już ma). Pozycję w bajtach liczy z czasu i średniego bitrate pliku, z zapasem 30 s wstecz.
     */
    private fun prefetch(p: ExoPlayer): Job = lifecycleScope.launch(Dispatchers.IO) {
        val cache = MediaCache.get(this@PlayerActivity)
        val writer = CacheDataSource(cache, http.createDataSource())
        while (isActive) {
            val (pos, dur) = withContext(Dispatchers.Main) { p.currentPosition to p.duration }
            if (dur <= 0) { delay(2_000); continue }
            val worked = runCatching {
                val size = probe(0).first
                fileSize = size
                val perMs = size.toDouble() / dur
                val from = ((pos - 30_000).coerceAtLeast(0) * perMs).toLong().coerceIn(0, size - 1)
                val readyEnd = probe(from).second
                val target = minOf(readyEnd, size, from + (settings.bufferMinutes * 60_000L * perMs).toLong())
                var at = from
                while (at < target) {
                    val cached = cache.getCachedLength(key, at, target - at)
                    if (cached > 0) { at += cached; continue }
                    val len = minOf(-cached, 8L shl 20, target - at)
                    CacheWriter(writer, DataSpec.Builder().setUri(url).setKey(key).setPosition(at).setLength(len).build(),
                        null, null).cache()
                    return@runCatching true  // po każdym kawałku od nowa: widz mógł przewinąć
                }
                false
            }.getOrElse { false }
            if (!worked) delay(3_000)
        }
    }

    /**
     * Napisy pobrane na telefon przy otwarciu filmu. ExoPlayer (media3 1.8, lazy loading napisów) czyta plik napisów
     * od nowa przy przewijaniu i przełączaniu ścieżek. Z sieci, bez Wi-Fi, takie ładowanie wisi na ponowieniach
     * i blokuje dociąganie filmu z bufora na dysku (CompositeSequenceableLoader czeka na najbardziej opóźnione źródło),
     * więc obraz się tnie. Lokalny plik czyta się zawsze. Gdy pobranie się nie uda, zostaje URL z komputera.
     */
    private fun localSubs(list: List<SubFile>): Map<String, Uri> {
        val dir = java.io.File(cacheDir, "subs")
        dir.deleteRecursively()  // tylko napisy bieżącego filmu
        dir.mkdirs()
        val out = mutableMapOf<String, Uri>()
        list.forEachIndexed { i, s ->
            runCatching {
                val f = java.io.File(dir, "$i.${s.ext}")
                val c = java.net.URL(api.subUrl(ref, s.name)).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 8_000; c.readTimeout = 15_000
                    if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                } finally {
                    c.disconnect()
                }
                out[s.name] = Uri.fromFile(f)
            }
        }
        return out
    }

    /** Co sekundę: ile ciągłego zapasu leży na dysku od bieżącej pozycji (przeliczone średnim bitrate na czas). */
    private fun showDiskAhead(p: ExoPlayer) = lifecycleScope.launch {
        val cache = MediaCache.get(this@PlayerActivity)
        while (isActive) {
            val o = diskOverlay
            val dur = p.duration
            val size = fileSize
            if (o != null && dur > 0 && size > 0) {
                val pos = p.currentPosition
                val from = (pos.toDouble() / dur * size).toLong().coerceIn(0, size - 1)
                val cached = withContext(Dispatchers.IO) { runCatching { cache.getCachedLength(key, from, size - from) }.getOrDefault(0) }
                o.positionMs = pos
                o.durationMs = dur
                o.aheadEndMs = if (cached > 0) pos + (cached.toDouble() / size * dur).toLong() else 0
                o.invalidateSelf()
            }
            delay(1_000)
        }
    }

    /** HEAD z Range od `from`: (rozmiar pliku, X-Ready-End). */
    private fun probe(from: Long): Pair<Long, Long> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.setRequestProperty("Range", "bytes=$from-")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val size = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: c.getHeaderField("Content-Length")!!.toLong()
            val ready = c.getHeaderField("X-Ready-End")?.toLongOrNull() ?: size
            return size to ready
        } finally {
            c.disconnect()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            val pos = it.currentPosition
            val dur = it.duration
            // pod koniec filmu zaczynamy następnym razem od początku
            if (it.playbackState != Player.STATE_IDLE) {
                settings.setPosition(ref.key(), if (dur > 0 && pos > dur - 60_000) 0 else pos)
                // ekran zablokowany: nic nie kasujemy; wyjście z odtwarzacza po ≥80%: kasujemy od razu
                val watched = if (dur > 0) pos.toFloat() / dur else 0f
                settings.touchCache(key, watched)
                if (isFinishing && watched >= MediaCache.WATCHED) {
                    prefetchJob?.cancel()
                    val k = key
                    Thread { MediaCache.remove(this, k) }.start()
                }
            }
            it.pause()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

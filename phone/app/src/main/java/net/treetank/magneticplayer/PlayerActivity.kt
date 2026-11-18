package net.treetank.magneticplayer

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
        runCatching { ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.media_open_with))) }
            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.media_no_video_app), Toast.LENGTH_LONG).show() }
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
    // koniec ciągłego zapasu na dysku telefonu (ms filmu); suwak rysuje go jako bufor (ForwardingPlayer niżej)
    @Volatile private var aheadEndMs = 0L
    private var info: android.widget.TextView? = null
    private val key get() = ref.key()
    // agent czeka do 20 s na kolejne kawałki torrenta, więc timeout odczytu musi być dłuższy
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(30_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        ref = intent.getStringExtra("ref")?.let { MediaRef.decode(it) } ?: return finish()
        // serwer mógł zniknąć z apki: pobrany film i tak gra z dysku telefonu (klucz bufora się nie zmienia)
        api = Api.of(this, ref.srv) ?: Api(settings, Server.NONE.copy(key = ref.srv))
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
        val dp = resources.displayMetrics.density
        // u góry: tytuł i ile da się obejrzeć bez sieci; widoczne razem z kontrolkami
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(android.widget.TextView(this).apply {
            text = ref.title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        info = android.widget.TextView(this).apply {
            setTextColor(0xFF81D4FA.toInt())
            textSize = 12f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        texts.addView(info)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val buttons = top.apply {
            addView(smallButton(getString(R.string.media_to_screen)) { handOffToPc() })
            addView(smallButton("VLC") {
                player?.pause()
                openInVlc(this@PlayerActivity, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
            })
        }
        // napisy nad kontrolkami, gdy są widoczne (inaczej chowają się pod suwakiem)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            buttons.visibility = v
            view.subtitleView?.setBottomPaddingFraction(if (v == android.view.View.VISIBLE) 0.24f
                else androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        })
        // jasny pas „bufor” na suwaku = to, co jest na dysku telefonu (albo w pamięci, co dalej)
        (view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.DefaultTimeBar)
            ?.setBufferedColor(0xCC4FC3F7.toInt())

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                // gradient pod tekstem, żeby był czytelny na jasnym obrazie
            })
            buttons.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0)
            )
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
        view.player = object : androidx.media3.common.ForwardingPlayer(p) {
            private fun ahead(real: Long) = maxOf(real, minOf(aheadEndMs, p.duration.takeIf { it > 0 } ?: 0))
            override fun getBufferedPosition() = ahead(super.getBufferedPosition())
            override fun getContentBufferedPosition() = ahead(super.getContentBufferedPosition())
        }
        p.addListener(object : Player.Listener {
            var retries = 0

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED) {
                    prefetchJob?.cancel()
                    val k = key
                    Thread { MediaCache.onWatched(this@PlayerActivity, k) }.start()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // błąd sieci (restart agenta przy aktualizacji, chwilowy brak Wi-Fi): łączymy się ponownie przez
                // ~minutę i gramy dalej od tego samego miejsca, zamiast zatrzymać film
                if (error.errorCode in 2000..2999 && retries < 15) {
                    if (retries++ == 0) Toast.makeText(this@PlayerActivity, R.string.media_player_reconnecting, Toast.LENGTH_SHORT).show()
                    val pos = p.currentPosition
                    lifecycleScope.launch {
                        delay(minOf(1000L * retries, 5000L))
                        p.seekTo(pos)
                        p.prepare()
                    }
                    return
                }
                Toast.makeText(this@PlayerActivity,
                    getString(R.string.media_player_error, error.errorCodeName),
                    Toast.LENGTH_LONG).show()
            }
        })
        // preferowany język napisów = kolejność z ustawień
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*settings.subLanguages.split(',').filter { it.isNotBlank() }.toTypedArray())
            .build()
        val start = savedInstanceState?.getLong("pos") ?: settings.position(ref.key())
        // pobrany (albo buforowany) film: rozmiar znany bez sieci, dla nakładki zapasu na pasku
        val offline = OfflineStore.get(this, key)
        if (offline != null && offline.size > 0) fileSize = offline.size

        lifecycleScope.launch {
            // pobrany film: napisy zapisane razem z nim (działa bez sieci); inaczej z komputera, kopiowane lokalnie
            val saved = offline?.subs.orEmpty().filter { java.io.File(it.file).exists() }
            val local: Map<String, Uri>
            if (saved.isNotEmpty()) {
                subs = saved.map { it.toSubFile() }
                local = saved.associate { it.name to Uri.fromFile(java.io.File(it.file)) }
            } else {
                subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
                local = withContext(Dispatchers.IO) { localSubs(subs) }
            }
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
        Thread { OfflineStore.ensure(this, ref); MediaCache.sweep(this, keep = key) }.start()
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
        val dp = resources.displayMetrics.density
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        minWidth = 0; minimumWidth = 0
        setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(0x55FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(-2, (36 * dp).toInt()).apply { marginStart = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    // Chromecasty w sieci, wykrywane, póki odtwarzacz jest na wierzchu
    private var casts: List<CastTarget> = emptyList()
    private var stopDiscovery: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        stopDiscovery = Cast.discover(this) { casts = it }
        Thread { Dlna.discover() }.start()  // do listy „Na ekran” (Dlna.found)
    }

    /** „Na ekran”: jeden ekran (bez Chromecastów) od razu, inaczej wybór (ostatnio używany oznaczony). */
    private fun handOffToPc() {
        val screens = Servers.screens(this)
        val cs = casts
        val tvs = Dlna.found.values.toList()
        if (screens.size == 1 && cs.isEmpty() && tvs.isEmpty()) return handOffTo(screens[0], null)
        if (screens.isEmpty() && cs.isEmpty() && tvs.isEmpty()) {
            Toast.makeText(this, getString(R.string.media_no_screens), Toast.LENGTH_LONG).show()
            return
        }
        val last = Servers.screenKey(this)
        val labels = screens.map { if (it.key == last) getString(R.string.media_screen_last, it.name) else it.name } +
            cs.map { "${it.name} (Chromecast)" } + tvs.map { "${it.name} (DLNA)" }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.media_which_screen))
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < screens.size -> handOffTo(screens[i], null)
                    i < screens.size + cs.size -> handOffTo(null, cs[i - screens.size])
                    else -> handOffTo(null, null, tvs[i - screens.size - cs.size])
                }
            }
            .show()
    }

    private fun handOffTo(screen: Server?, cast: CastTarget?, tv: DlnaTarget? = null) {
        val p = player ?: return
        val pos = p.currentPosition
        val name = screen?.name ?: cast?.name ?: tv?.name ?: return
        p.pause()
        Toast.makeText(this, getString(R.string.media_opening_on, name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                if (cast != null) Screens.cast(this@PlayerActivity, ref, cast, pos / 1000.0) { }
                else if (tv != null) Screens.dlna(this@PlayerActivity, ref, tv, pos / 1000.0) { }
                else Screens.play(this@PlayerActivity, ref, screen!!.key, pos / 1000.0) { }
                Toast.makeText(this@PlayerActivity, getString(R.string.media_playing_on, name), Toast.LENGTH_SHORT).show()
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
                val size = headProbe(url, 0).first
                fileSize = size
                OfflineStore.update(this@PlayerActivity, key) { it.copy(size = size) }
                MediaCache.setLength(this@PlayerActivity, key, size)
                val perMs = size.toDouble() / dur
                val from = ((pos - 30_000).coerceAtLeast(0) * perMs).toLong().coerceIn(0, size - 1)
                val readyEnd = headProbe(url, from).second
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
            val f = java.io.File(dir, "$i.${s.ext}")
            runCatching { download(api.subUrl(ref, s.name), f); out[s.name] = Uri.fromFile(f) }
        }
        return out
    }

    /** Co sekundę: ile ciągłego zapasu leży na dysku od bieżącej pozycji (przeliczone średnim bitrate na czas). */
    /** Co sekundę: zasięg ciągłego zapasu na dysku od bieżącej pozycji → suwak (bufor) i napis u góry. */
    private fun showDiskAhead(p: ExoPlayer) = lifecycleScope.launch {
        val cache = MediaCache.get(this@PlayerActivity)
        while (isActive) {
            val dur = p.duration
            val size = fileSize.takeIf { it > 0 } ?: OfflineStore.get(this@PlayerActivity, key)?.size ?: 0L
            if (dur > 0 && size > 0) {
                val pos = p.currentPosition
                val from = (pos.toDouble() / dur * size).toLong().coerceIn(0, size - 1)
                val cached = withContext(Dispatchers.IO) { runCatching { cache.getCachedLength(key, from, size - from) }.getOrDefault(0) }
                val end = if (cached > 0) minOf(dur, pos + (cached.toDouble() / size * dur).toLong()) else 0
                aheadEndMs = end
                val whole = withContext(Dispatchers.IO) { runCatching { cache.getCachedBytes(key, 0, size) }.getOrDefault(0) } >= size
                info?.text = when {
                    whole -> getString(R.string.media_player_whole)
                    end >= dur - 1_000 -> getString(R.string.media_player_to_end)
                    end - pos > 60_000 -> getString(R.string.media_player_until, clock(end / 1000.0), humanTime((end - pos) / 1000))
                    else -> ""
                }
            }
            delay(1_000)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        stopDiscovery?.invoke()
        stopDiscovery = null
        player?.let {
            val pos = it.currentPosition
            val dur = it.duration
            // pod koniec filmu zaczynamy następnym razem od początku
            if (it.playbackState != Player.STATE_IDLE) {
                settings.setPosition(ref.key(), if (dur > 0 && pos > dur - 60_000) 0 else pos)
                // ekran zablokowany: nic nie kasujemy; wyjście z odtwarzacza po ≥80%: kasujemy od razu
                val watched = if (dur > 0) pos.toFloat() / dur else 0f
                settings.touchCache(key, watched)
                if (dur > 0) OfflineStore.update(this, key) { o -> o.copy(durationMs = dur) }
                if (isFinishing && watched >= MediaCache.WATCHED) {
                    prefetchJob?.cancel()
                    val k = key
                    Thread { MediaCache.onWatched(this, k) }.start()
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

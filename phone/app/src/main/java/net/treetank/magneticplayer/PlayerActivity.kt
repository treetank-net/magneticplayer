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
import androidx.media3.common.Tracks
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

/** Opens the stream in VLC (if present), otherwise an app chooser. Passes subtitles via a VLC extra. */
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

/** In-phone player: HTTP stream from the computer (ExoPlayer, seeking via Range, subtitles next to the video). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {
    companion object {
        /** [imdb]: a film from the catalog; after it is watched to the end the app offers "What next".
         *  [next]: the following episodes of a series (same library folder): "Next episode" and a countdown at the end. */
        fun intent(ctx: Context, ref: MediaRef, imdb: String? = null, next: List<Episode> = emptyList(), series: Boolean = next.isNotEmpty()) =
            Intent(ctx, PlayerActivity::class.java).putExtra("ref", ref.encode()).apply {
                if (imdb != null) putExtra("imdb", imdb)
                if (series) putExtra("series", true)
                if (next.isNotEmpty()) putExtra("next", org.json.JSONArray(next.map { e ->
                    org.json.JSONObject().put("path", e.path).put("name", e.name).put("season", e.season ?: -1).put("episode", e.episode ?: -1)
                }).toString())
            }

        private fun nextOf(i: Intent): List<Episode> {
            val a = runCatching { org.json.JSONArray(i.getStringExtra("next") ?: return emptyList()) }.getOrNull() ?: return emptyList()
            return List(a.length()) { k ->
                val o = a.getJSONObject(k)
                Episode(o.getString("path"), o.optString("name"), o.optInt("season", -1).takeIf { it >= 0 },
                    o.optInt("episode", -1).takeIf { it >= 0 }, 0)
            }
        }
    }

    private var player: ExoPlayer? = null
    /** the following episodes when a series is played from the catalog */
    private var nextEps: List<Episode> = emptyList()
    /** "Next episode" card, bottom right: shown in the last [NEXT_SHOW_MS] (or from the credits), independent of the controls */
    private var nextCard: LinearLayout? = null
    private var nextLine: android.widget.TextView? = null
    private var nextCancel: Button? = null
    private var nextLeft = -1  // seconds of the countdown, -1: not counting
    private var nextCancelled = false
    /** where the credits start (ms), when known; the countdown starts there instead of at the very end */
    private var creditsAtMs = -1L
    /** the intro (ms from, to) when the agent found one: "Skip intro" while it plays */
    private var intro: Pair<Long, Long>? = null
    private var skipIntro: android.view.View? = null
    private val series get() = intent.getBooleanExtra("series", false)
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: FrameLayout? = null
    private lateinit var settings: Settings
    private lateinit var api: Api
    private lateinit var ref: MediaRef
    private lateinit var url: String
    private var subs: List<SubFile> = emptyList()
    private var prefetchJob: Job? = null
    private var fileSize = 0L  // from prefetch (HEAD), for converting bytes to time in the overlay
    // end of the contiguous reserve on the phone's disk (video ms); the slider draws it as a buffer (ForwardingPlayer below)
    @Volatile private var aheadEndMs = 0L
    private var info: android.widget.TextView? = null
    private val key get() = ref.key()
    // the agent waits up to 20 s for further torrent chunks, so the read timeout must be longer
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(30_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        ref = intent.getStringExtra("ref")?.let { MediaRef.decode(it) } ?: return finish()
        nextEps = if (ref is MediaRef.InLibrary) nextOf(intent) else emptyList()
        // the server may have vanished from the app: a downloaded video plays from the phone's disk anyway (the buffer key does not change)
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
        playerView = view
        val dp = resources.displayMetrics.density
        // top: title and how much can be watched without a network; visible together with the controls
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
            if (nextEps.isNotEmpty()) addView(smallButton(getString(R.string.media_next_episode)) { playNext() })
            if (Api.of(this@PlayerActivity, ref.srv) != null)  // searching needs the computer
                addView(smallButton(getString(R.string.media_psubs_button)) { pickSubtitles() })
            addView(smallButton(getString(R.string.media_to_screen)) { handOffToPc() })
            addView(smallButton("VLC") {
                player?.pause()
                openInVlc(this@PlayerActivity, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
            })
        }
        // subtitles above the controls when they are visible (otherwise they hide under the slider)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            buttons.visibility = v
            view.subtitleView?.setBottomPaddingFraction(if (v == android.view.View.VISIBLE) 0.24f
                else androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        })
        // light "buffer" strip on the slider = what is on the phone's disk (or in memory, further on)
        (view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.DefaultTimeBar)
            ?.setBufferedColor(0xCC4FC3F7.toInt())

        setContentView(FrameLayout(this).apply {
            root = this
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                // gradient under the text so that it is legible on a bright picture
            })
            buttons.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0)
            )
        })

        // phone decoders first, FFmpeg when the phone cannot (AC3/E-AC3/DTS…)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // memory keeps the default ~50 s; the further reserve is on disk (prefetch). The player reads from the disk buffer,
        // and what is not there, it takes from the network, but does not write to it itself.
        val source = CacheDataSource.Factory().setCache(MediaCache.get(this))
            // DefaultDataSource: http from the computer, but also file:// for locally downloaded subtitles (localSubs)
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // the torrent is still being pulled in: the agent drops the connection after 20 s without new chunks, and we retry instead of showing an error
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
                    settings.markWatched(ref.key())
                    // a series: the card's countdown (nextTick); a catalog film: "What next" instead of a black screen
                    if (nextEps.isEmpty() && offerNext()) finish()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // a picture the phone cannot decode (old DivX 3, WMV…): ExoPlayer plays the sound alone and may then hang on
                // seeking, so we say so and offer the computer's version
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { it.isSupported } && !noVideoHandled) {
                    noVideoHandled = true
                    val f = video.first().getTrackFormat(0)
                    onNoVideo(p, f.codecs ?: f.sampleMimeType?.substringAfter('/'))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // network error (agent restart during an update, a brief loss of Wi-Fi): we reconnect over
                // ~a minute and keep playing from the same place instead of stopping the video
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
                // any other error (a decoder that chokes, e.g. DivX 3 that the AVI extractor passes off as MPEG-4,
                // ends as "stuck buffering"): the computer's version may still play, so we offer it once
                if (!noVideoHandled && !altSource && Api.of(this@PlayerActivity, ref.srv) != null) {
                    noVideoHandled = true
                    onNoVideo(p, null, error.errorCodeName)
                    return
                }
                Toast.makeText(this@PlayerActivity,
                    getString(R.string.media_player_error, error.errorCodeName),
                    Toast.LENGTH_LONG).show()
            }
        })
        // preferred subtitle language = order from settings
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*settings.subLanguages.split(',').filter { it.isNotBlank() }.toTypedArray())
            .build()
        val start = savedInstanceState?.getLong("pos") ?: settings.position(ref.key())
        // downloaded (or buffered) video: size known without a network, for the reserve overlay on the bar
        val offline = OfflineStore.get(this, key)
        if (offline != null && offline.size > 0) fileSize = offline.size

        lifecycleScope.launch {
            // downloaded video: subtitles saved with it (works offline); otherwise from the computer, copied locally
            val saved = offline?.subs.orEmpty().filter { java.io.File(it.file).exists() }
            val local: Map<String, Uri>
            if (saved.isNotEmpty()) {
                subs = saved.map { it.toSubFile() }
                local = saved.associate { it.name to Uri.fromFile(java.io.File(it.file)) }
            } else {
                subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
                local = withContext(Dispatchers.IO) { localSubs(subs) }
            }
            localSubUris = local
            p.setMediaItem(buildItem())
            p.seekTo(start)
            p.prepare()
            p.playWhenReady = true
            prefetchJob = prefetch(p)
            showDiskAhead(p)
        }
        loadSegments()
        settings.touchCache(key, settings.cacheWatched(key))
        Thread { OfflineStore.ensure(this, ref); MediaCache.sweep(this, keep = key) }.start()
    }

    private var noVideoHandled = false
    private var altSource = false
    private var playerView: PlayerView? = null

    /** The phone has no decoder for the picture: play the computer's version (made for Chromecasts, H.264 MP4) instead. */
    private fun onNoVideo(p: ExoPlayer, trackCodec: String?, errorName: String? = null) {
        val server = Api.of(this, ref.srv)
        lifecycleScope.launch {
            val probe = server?.let { withContext(Dispatchers.IO) { runCatching { it.mediaProbe(ref) }.getOrNull() } }
            if (isFinishing) return@launch
            if (probe?.optJSONObject("variant") != null) return@launch switchToVariant(p, server)
            val canPrepare = probe?.optBoolean("ffmpeg") == true
            val codec = (probe?.optJSONObject("file")?.optJSONObject("video")?.optString("codec")?.ifEmpty { null }
                ?: trackCodec ?: errorName ?: "?").uppercase()
            if (errorName != null && !canPrepare) {  // nothing better to offer than the usual error
                Toast.makeText(this@PlayerActivity, getString(R.string.media_player_error, errorName), Toast.LENGTH_LONG).show()
                return@launch
            }
            val b = android.app.AlertDialog.Builder(this@PlayerActivity)
                .setTitle(R.string.media_player_no_video_title)
                .setMessage(getString(when {
                    errorName != null -> R.string.media_player_cant_play_prepare
                    canPrepare -> R.string.media_player_no_video_prepare
                    else -> R.string.media_player_no_video
                }, codec))
                .setNeutralButton(R.string.media_player_sound_only, null)
                .setNegativeButton("VLC") { _, _ ->
                    p.pause()
                    openInVlc(this@PlayerActivity, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
                }
            if (canPrepare) b.setPositiveButton(R.string.media_player_prepare) { _, _ -> prepareVariant(p, server!!) }
            b.show()
        }
    }

    /** Asks the computer for the version and waits for it, showing progress in the top line; then switches to it. */
    private fun prepareVariant(p: ExoPlayer, server: Api) = lifecycleScope.launch {
        p.pause()
        altSource = true
        try {
            withContext(Dispatchers.IO) { server.mediaConvert(ref) }
            while (isActive) {
                val pr = withContext(Dispatchers.IO) { runCatching { server.mediaProbe(ref) }.getOrNull() }
                if (pr?.optJSONObject("variant") != null) return@launch switchToVariant(p, server)
                val j = pr?.optJSONObject("job")
                if (j?.optString("state") == "error") throw ApiException(j.optString("error"))
                info?.text = getString(R.string.media_player_preparing, (j?.optDouble("pct", 0.0) ?: 0.0).toInt())
                playerView?.showController()
                delay(3_000)
            }
        } catch (e: ApiException) {
            altSource = false
            Toast.makeText(this@PlayerActivity, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun switchToVariant(p: ExoPlayer, server: Api) {
        val t = withContext(Dispatchers.IO) { runCatching { server.ticket(ref, "cast") }.getOrNull() } ?: return
        if (isFinishing) return
        prefetchJob?.cancel()  // the disk reserve is for the original file
        altSource = true
        val item = p.currentMediaItem ?: return
        val pos = p.currentPosition
        p.setMediaItem(item.buildUpon().setUri(t.url).setCustomCacheKey("$key#cast").build(), pos)
        p.prepare()
        p.playWhenReady = true
        info?.text = getString(R.string.media_player_variant)
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

    // Chromecasts on the network, discovered while the player is in front
    private var casts: List<CastTarget> = emptyList()
    private var stopDiscovery: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        stopDiscovery = Cast.discover(this) { casts = it }
        Thread { Dlna.discover() }.start()  // for the "To screen" list (Dlna.found)
        if (nextEps.isNotEmpty() || series) { ui.removeCallbacks(nextTick); ui.post(nextTick) }
    }

    /** "To screen": a single screen (no Chromecasts) immediately, otherwise a picker (last used marked). */
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
     * Pulls to disk up to [Settings.bufferMinutes] minutes from the current position, but only up to X-Ready-End (bytes the
     * computer already has). Computes the byte position from the time and the file's average bitrate, with a 30 s margin backwards.
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
                    return@runCatching true  // after each chunk anew: the viewer may have seeked
                }
                false
            }.getOrElse { false }
            if (!worked) delay(3_000)
        }
    }

    /**
     * Subtitles downloaded to the phone when the video is opened. ExoPlayer (media3 1.8, lazy subtitle loading) reads the subtitle file
     * again when seeking and switching tracks. From the network, without Wi-Fi, such loading hangs on retries
     * and blocks pulling the video from the disk buffer (CompositeSequenceableLoader waits for the most delayed source),
     * so the picture stutters. A local file is always readable. If the download fails, the URL from the computer remains.
     */
    private fun localSubs(list: List<SubFile>): Map<String, Uri> {
        val dir = java.io.File(cacheDir, "subs")
        dir.deleteRecursively()  // only the current video's subtitles
        dir.mkdirs()
        val out = mutableMapOf<String, Uri>()
        list.forEachIndexed { i, s ->
            val f = java.io.File(dir, "$i.${s.ext}")
            runCatching { download(api.subUrl(ref, s.name), f); out[s.name] = Uri.fromFile(f) }
        }
        return out
    }

    /** Every second: how much contiguous reserve is on disk from the current position (converted to time with the average bitrate). */
    /** Every second: extent of the contiguous reserve on disk from the current position → slider (buffer) and caption at the top. */
    private fun showDiskAhead(p: ExoPlayer) = lifecycleScope.launch {
        val cache = MediaCache.get(this@PlayerActivity)
        while (isActive) {
            if (altSource) { aheadEndMs = 0; delay(1_000); continue }  // preparing or playing the computer's version: its own line
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
        ui.removeCallbacks(nextTick)  // the next episode doesn't start behind a locked screen or another app
        nextLeft = -1
        stopDiscovery?.invoke()
        stopDiscovery = null
        player?.let {
            val pos = it.currentPosition
            val dur = it.duration
            // at the end of the video we start from the beginning next time
            if (it.playbackState != Player.STATE_IDLE) {
                settings.setPosition(ref.key(), if (dur > 0 && pos > dur - 60_000) 0 else pos)
                if (dur > 0 && pos > dur - 60_000) settings.markWatched(ref.key())  // the credits count as watched
                // screen locked: we delete nothing; exiting the player at ≥80%: we delete at once
                val watched = if (dur > 0) pos.toFloat() / dur else 0f
                settings.touchCache(key, watched)
                if (dur > 0) OfflineStore.update(this, key) { o -> o.copy(durationMs = dur) }
                if (isFinishing && watched >= MediaCache.WATCHED) {
                    prefetchJob?.cancel()
                    val k = key
                    Thread { MediaCache.onWatched(this, k) }.start()
                }
                // left during the credits (the same "near the end" rule as the position above)
                if (isFinishing && dur > 0 && pos > dur - 60_000) offerNext()
            }
            it.pause()
        }
    }

    private var nextOffered = false

    /** The next episode in a new player (its own buffer, subtitles and position), the rest of the list after it. */
    private fun playNext() {
        val r = ref as? MediaRef.InLibrary ?: return
        val n = nextEps.firstOrNull() ?: return
        ui.removeCallbacks(nextTick)
        player?.pause()
        startActivity(PlayerActivity.intent(this, MediaRef.InLibrary(r.root, n.path, r.srv), null, nextEps.drop(1), series = true))
        finish()
    }

    private val NEXT_SHOW_MS = 90_000L
    private val NEXT_COUNT_S = 10

    /**
     * Every second while a series plays: the position decides, not only STATE_ENDED (a stream may stay "buffering" at
     * its last second, and seeking around must not lose the card). Near the end the card shows; the countdown runs from
     * the credits when known, otherwise from the end of the file, and stands while the video is paused. Seeking back out
     * of the end hides the card and resets "Cancel".
     */
    private val nextTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 1000)
            val p = player ?: return
            val dur = p.duration
            if (dur <= 0 || dur == C.TIME_UNSET) return
            val pos = p.currentPosition
            val ended = p.playbackState == Player.STATE_ENDED || pos >= dur - 1_500
            val iv = intro
            if (iv != null && pos >= iv.first && pos < iv.second - 1_000 && !ended) showSkipIntro(iv.second) else hideSkipIntro()
            if (nextEps.isEmpty()) return
            val credits = creditsAtMs.takeIf { it in 1 until dur }
            if (!ended && pos < minOf(credits ?: dur, dur - NEXT_SHOW_MS)) {
                hideNextCard(); nextCancelled = false; nextLeft = -1
                return
            }
            showNextCard()
            val counting = !nextCancelled && (ended || (credits != null && pos >= credits))
            if (!counting) {
                nextLeft = -1
                nextLine?.text = getString(R.string.media_next_episode)
                nextCancel?.visibility = android.view.View.GONE
                return
            }
            if (nextLeft < 0) nextLeft = NEXT_COUNT_S
            else if (ended || p.isPlaying) nextLeft--
            if (nextLeft <= 0) return playNext()
            nextLine?.text = getString(R.string.media_next_in, nextLeft)
            nextCancel?.visibility = android.view.View.VISIBLE
        }
    }

    private var localSubUris: Map<String, Uri> = emptyMap()

    /**
     * The video with its subtitle files. Default per language: the file that fits this copy of the film best (agent
     * 1.9+), never one that doesn't fit; [prefer] (a file just fetched from the player) alone when given.
     */
    private fun buildItem(prefer: String? = null): MediaItem {
        val rank = { s: SubFile -> when (s.fit) { "good" -> 0; null, "unsure" -> 1; else -> 2 } }
        val defaults = subs.firstOrNull { it.name == prefer }?.let { setOf(it) } ?: subs.groupBy { it.lang.takeWhile { c -> c.isLetter() } }.values
            .mapNotNull { g -> g.minByOrNull(rank)?.takeIf { it.fit != "bad" } }.toSet()
            .ifEmpty { setOfNotNull(subs.firstOrNull()) }
        val badMark = getString(R.string.media_subs_bad_mark)
        val subConfigs = subs.mapNotNull { s ->
            val mime = when (s.ext) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> null
            } ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(localSubUris[s.name] ?: Uri.parse(api.subUrl(ref, s.name)))
                .setMimeType(mime)
                .setLanguage(s.lang.takeWhile { it.isLetter() }.ifEmpty { null })
                .setLabel(s.name + when (s.fit) { "good" -> " ✓"; "bad" -> badMark; else -> "" })
                .setSelectionFlags(if (s in defaults) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder().setUri(url).setCustomCacheKey(key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).build())
            .setSubtitleConfigurations(subConfigs)
            .build()
    }

    /** Languages offered by "Subtitles": the user's own (settings) first, then common ones. */
    private fun subLanguageChoices(): List<String> =
        (settings.subLanguages.split(',') + listOf("pl", "en", "de", "fr", "es", "it", "pt", "nl", "cs", "sk", "uk", "ru",
            "sv", "no", "da", "fi", "hu", "ro", "hr", "tr", "el", "he", "ar", "ja", "ko", "zh"))
            .map { it.trim().lowercase() }.filter { it.length in 2..3 }.distinct()

    /** "Subtitles" in the player: a language, then the best fitting file found on the internet, turned on in place. */
    private fun pickSubtitles() {
        val codes = subLanguageChoices()
        val ui = resources.configuration.locales[0]
        val names = codes.map { c -> java.util.Locale(c).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) } }
        android.app.AlertDialog.Builder(this).setTitle(R.string.media_psubs_pick_lang)
            .setItems(names.toTypedArray()) { _, i -> fetchSubtitles(codes[i], names[i]) }
            .show()
    }

    /**
     * Searches (agent: OpenSubtitles & co.), then downloads the candidates best first until one fits this copy of the
     * film (agent 1.9+ fits it to the speech; at most 3 tries), and reloads the video with it at the same position.
     */
    private fun fetchSubtitles(lang: String, langName: String) {
        val p = player ?: return
        val status = android.widget.TextView(this).apply {
            val dp = resources.displayMetrics.density
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            text = getString(R.string.media_psubs_searching, langName)
        }
        var job: String? = null
        val dlg = android.app.AlertDialog.Builder(this).setTitle(R.string.media_psubs_title).setView(status)
            .setNegativeButton(R.string.media_next_cancel, null).show()
        val work = lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { api.subsSearchStart(ref, langs = lang) }
                job = id
                var found: SubsFound? = null
                for (n in 0 until 120) {  // up to ~2 minutes; BSPlayer can think for 45 s
                    val pr = withContext(Dispatchers.IO) { api.subsJob(id) }
                    if (pr.error != null) throw ApiException(pr.error)
                    if (pr.done) { found = pr.found; break }
                    delay(1_000)
                }
                val results = found?.results.orEmpty()
                    .sortedWith(compareByDescending<SubResult> { it.hashMatch }.thenByDescending { it.score })
                if (results.isEmpty()) {
                    status.text = getString(R.string.media_psubs_none, langName)
                    return@launch
                }
                var got: SubsDownloaded? = null
                for ((k, r) in results.take(3).withIndex()) {
                    status.text = getString(R.string.media_psubs_trying, k + 1, minOf(3, results.size), r.release.ifEmpty { r.provider })
                    val d = withContext(Dispatchers.IO) { runCatching { api.subsDownload(ref, r, langs = lang) }.getOrNull() } ?: continue
                    got = d
                    if (d.fit != "bad") break
                }
                val d = got ?: run { status.text = getString(R.string.media_psubs_none, langName); return@launch }
                subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(subs) }
                localSubUris = withContext(Dispatchers.IO) { localSubs(subs) }
                val pos = p.currentPosition
                val playing = p.playWhenReady
                // the new file on, even when subtitles were switched off or another language was picked by hand
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setPreferredTextLanguage(lang)
                    .build()
                p.setMediaItem(buildItem(prefer = d.name), pos)
                p.prepare()
                p.playWhenReady = playing
                dlg.dismiss()
                Toast.makeText(this@PlayerActivity, getString(when (d.fit) {
                    "bad" -> R.string.media_psubs_on_bad; "good" -> R.string.media_psubs_on_good; else -> R.string.media_psubs_on
                }, d.name), Toast.LENGTH_LONG).show()
            } catch (e: ApiException) {
                status.text = e.message
            }
        }
        dlg.setOnDismissListener {
            if (work.isActive) {
                work.cancel()
                job?.let { j -> Thread { runCatching { api.subsStop(j) } }.start() }
            }
        }
    }

    /**
     * Intro and credits of a series episode from the agent (agent 1.11+). The agent compares the sound with the
     * neighbouring episodes on the first request (5–10 s), so "pending" is asked again a few times.
     */
    private fun loadSegments() {
        val r = ref as? MediaRef.InLibrary ?: return
        if (!series || !api.srv.segments) return
        lifecycleScope.launch {
            repeat(8) {
                val s = withContext(Dispatchers.IO) { runCatching { api.segments(r.root, r.path) }.getOrNull() } ?: return@launch
                if (!s.pending) {
                    if (s.introStart != null && s.introEnd != null && s.introEnd > s.introStart) intro = s.introStart to s.introEnd
                    creditsAtMs = s.creditsAt ?: -1L
                    return@launch
                }
                delay(15_000)
            }
        }
    }

    private fun showSkipIntro(to: Long) {
        if (skipIntro != null) return
        val dp = resources.displayMetrics.density
        val b = smallButton(getString(R.string.media_skip_intro)) {
            player?.seekTo(to)
            hideSkipIntro()
        }.apply {
            textSize = 16f
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 24 * dp; setColor(0xE0202124.toInt()); setStroke((1 * dp).toInt(), 0x66FFFFFF) }
        }
        skipIntro = b
        root?.addView(b, FrameLayout.LayoutParams(-2, (48 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (16 * dp).toInt(), (96 * dp).toInt())
        })
    }

    private fun hideSkipIntro() {
        skipIntro?.let { root?.removeView(it) }
        skipIntro = null
    }

    private fun hideNextCard() {
        nextCard?.let { root?.removeView(it) }
        nextCard = null; nextLine = null; nextCancel = null
    }

    /** "Next episode (in N s)", the episode's name, Play now / Cancel; above the controls' slider. */
    private fun showNextCard() {
        if (nextCard != null) return
        val n = nextEps.firstOrNull() ?: return
        val dp = resources.displayMetrics.density
        val label = listOf(n.label, n.shortName).filter { it.isNotEmpty() }.joinToString(" · ")
        val line = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        val name = android.widget.TextView(this).apply {
            text = label; setTextColor(0xFFB0BEC5.toInt()); textSize = 13f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val cancel = smallButton(getString(R.string.media_next_cancel)) {
            nextCancelled = true; nextLeft = -1
            nextLine?.text = getString(R.string.media_next_episode)
            nextCancel?.visibility = android.view.View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xE0202124.toInt()) }
            isClickable = true  // taps on the card don't reach the player (they would toggle the controls)
            addView(line); addView(name)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
                addView(smallButton(getString(R.string.media_next_now)) { playNext() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = 0
                })
                addView(cancel)
            })
        }
        nextCard = box; nextLine = line; nextCancel = cancel
        line.text = getString(R.string.media_next_episode)
        root?.addView(box, FrameLayout.LayoutParams((300 * dp).toInt(), -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (16 * dp).toInt(), (96 * dp).toInt())
        })
    }

    /**
     * After a film from the catalog (agent 1.8+) has been watched: the Library opens "What next" for it. The IMDb id comes
     * from the catalog screen that started the player, or from the cached catalog by the file (also when played from Folders).
     * Series and files without an id get nothing. Returns whether it was opened.
     */
    private fun offerNext(): Boolean {
        if (nextOffered) return false
        val imdb = intent.getStringExtra("imdb") ?: (ref as? MediaRef.InLibrary)?.let { r ->
            CatalogCache.itemFor(r.srv, r.root, r.path)?.takeIf { it.kind == "movie" }?.imdb
        } ?: return false
        if (Servers.get(this, ref.srv)?.hasCatalog != true) return false
        nextOffered = true
        startActivity(Intent(this, MainActivity::class.java).putExtra("next_imdb", imdb).putExtra("next_srv", ref.srv)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return true
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}

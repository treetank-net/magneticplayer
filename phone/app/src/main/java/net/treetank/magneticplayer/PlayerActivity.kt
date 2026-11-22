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
import kotlinx.coroutines.flow.first
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
        /** the timing bar saves the shift after this long without a tap */
        private const val SYNC_IDLE_MS = 8_000L
        private const val OFFER_MS = 12_000L
        /** the end of the file kept with every buffer (the container's index lives there) */
        private const val TAIL_BYTES = 4L shl 20

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
    /** The recognised name of a library video once known (libraryTitle), else the file name is shown. */
    private var shownTitle: String? = null
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
            setShowSubtitleButton(false)  // ours in its place (subtitleButton): ExoPlayer's is disabled when a video has none
            setShowNextButton(nextEps.isNotEmpty())  // ⏭ = the next episode (ForwardingPlayer below)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        playerView = view
        installSubtitleButton(view)
        pauseWhileInSettings(view)
        // our actions as icons in ExoPlayer's bottom bar, before CC (the top bar keeps only the title and the reserve)
        barButton(view, R.drawable.ic_player_screen, R.string.media_to_screen) { handOffToPc() }
        barButton(view, R.drawable.ic_player_open_in, R.string.media_open_vlc) {
            player?.pause()
            openInVlc(this, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
        }
        // the audio sheet next to CC (ExoPlayer's gear has a bare track list without remembering)
        audioButton = barButton(view, R.drawable.ic_player_audio, R.string.audio_title) { audioMenu() }
        audioButton?.visibility = android.view.View.GONE  // shown when the video has two or more audio tracks
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
            // the recognised name ("Series · S2 E3", "Film (1968)") as on the Remote, from the phone's catalog copy
            val tv = this
            lifecycleScope.launch {
                val t = withContext(Dispatchers.IO) { runCatching { libraryTitle(this@PlayerActivity, ref) }.getOrNull() }
                if (t != null) { tv.text = t; shownTitle = t }
            }
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
            // a spinner from the tap until the first picture and while it buffers: opening a file (from the phone's disk
            // too) can take a few seconds of black screen
            loading = android.widget.ProgressBar(this@PlayerActivity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            addView(loading, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.CENTER))
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
            // a series: ExoPlayer's ⏭ opens the next episode (each episode is its own player)
            private val next get() = nextEps.isNotEmpty()
            override fun isCommandAvailable(command: Int) =
                (next && command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) || super.isCommandAvailable(command)
            override fun getAvailableCommands(): Player.Commands = if (!next) super.getAvailableCommands()
                else super.getAvailableCommands().buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            override fun hasNextMediaItem() = next || super.hasNextMediaItem()
            override fun seekToNext() { if (next) playNext() else super.seekToNext() }
            override fun seekToNextMediaItem() { if (next) playNext() else super.seekToNextMediaItem() }
        }
        p.addListener(object : Player.Listener {
            var retries = 0

            override fun onPlaybackStateChanged(state: Int) {
                loading?.visibility = if (state == Player.STATE_BUFFERING) android.view.View.VISIBLE else android.view.View.GONE
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_READY && resumeSync) { resumeSync = false; p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT) }
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
                updateSubtitleIcon(tracks)
                if (!tracks.isEmpty) ensureChosenSubs(tracks)
                if (!tracks.isEmpty) applyAudioChoice(tracks)
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
                loading?.visibility = android.view.View.GONE
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
                // the sound's decoder gave up (damaged MP3 frames of an old AVI, copied as they were): it's the sound,
                // not the picture; the picture can go on without it, VLC skips such frames
                val ex = error as? androidx.media3.exoplayer.ExoPlaybackException
                if (ex?.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER && androidx.media3.common.MimeTypes.isAudio(ex.rendererFormat?.sampleMimeType) && !audioOff) {
                    onAudioFailed(p, ex.rendererFormat?.sampleMimeType?.substringAfter('/')?.let { if (it == "mpeg") "mp3" else it })
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
        // preferred subtitle language: the one picked last in the player ("off" = none), then the order from settings
        val choice = settings.subChoice
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*(listOfNotNull(choice?.takeIf { it != "off" }) + settings.subLanguages.split(','))
                .map { it.trim() }.filter { it.isNotBlank() }.distinct().toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice == "off")
            .build()
        val start = savedInstanceState?.getLong("pos") ?: settings.position(ref.key())
        // downloaded (or buffered) video: size known without a network, for the reserve overlay on the bar
        val offline = OfflineStore.get(this, key)
        if (offline != null && offline.size > 0) fileSize = offline.size

        lifecycleScope.launch {
            // nothing of it on the phone and the server out of reach: say so at once instead of a player stuck at 00:00
            // that keeps reconnecting for a minute (an episode list or a card offers it offline too)
            val onPhone = offline?.complete == true || withContext(Dispatchers.IO) {
                runCatching { MediaCache.get(this@PlayerActivity).getCachedBytes(key, 0, 1L shl 20) }.getOrDefault(0L) } > 0
            // downloaded video: subtitles saved with it (works offline); otherwise from the computer, copied locally
            val saved = offline?.subs.orEmpty().filter { java.io.File(it.file).exists() }
            val reachable = if (onPhone && saved.isNotEmpty()) true else withContext(Dispatchers.IO) { api.ping(2500) }
            if (isFinishing) return@launch
            if (!onPhone && !reachable) {
                loading?.visibility = android.view.View.GONE
                android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle(R.string.media_player_not_on_phone_title)
                    .setMessage(getString(R.string.media_player_not_on_phone, api.srv.name.ifBlank { getString(R.string.media_player_the_server) }))
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { finish() }
                    .show()
                return@launch
            }
            val local: Map<String, Uri>
            if (saved.isNotEmpty()) {
                subs = saved.map { it.toSubFile() }
                local = saved.associate { it.name to Uri.fromFile(java.io.File(it.file)) }
            } else if (!reachable) {  // a buffered video offline: no waiting for the server's subtitle list
                subs = emptyList()
                local = emptyMap()
            } else {
                subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
                local = withContext(Dispatchers.IO) { localSubs(subs) }
            }
            localSubUris = local
            p.setMediaItem(buildItem())
            // resume from the keyframe at or before the saved position: an exact seek decodes from that keyframe up to the
            // position first, and with a long GOP (x265) the sound started while the picture stood still until it caught
            // up. The owner's seeks later stay exact (resumeSync is switched back on the first READY).
            if (start > 0) { p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC); resumeSync = true }
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
    private var audioOff = false

    /** The audio decoder failed: say so (not "can't show the picture") and offer the picture without sound, or VLC. */
    private fun onAudioFailed(p: ExoPlayer, codec: String?) {
        val pos = p.currentPosition
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.media_player_no_audio_title)
            .setMessage(getString(R.string.media_player_no_audio, (codec ?: "?").uppercase()))
            .setPositiveButton(R.string.media_player_without_sound) { _, _ ->
                audioOff = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                p.seekTo(pos)
                p.prepare()
                p.playWhenReady = true
            }
            .setNegativeButton("VLC") { _, _ ->
                openInVlc(this, url, ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
            }
            .setNeutralButton(R.string.media_cancel) { _, _ -> finish() }
            .show()
    }
    private var loading: android.widget.ProgressBar? = null
    private var resumeSync = false
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
                onScreen()
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
                // the series goes along: the screen continues with the next episode as this player would
                val next = (ref as? MediaRef.InLibrary)?.let { r -> nextEps.map { MediaRef.InLibrary(r.root, it.path, r.srv) } }
                    ?.takeIf { it.isNotEmpty() }
                if (cast != null) Screens.cast(this@PlayerActivity, ref, cast, pos / 1000.0, null, next) { }
                else if (tv != null) Screens.dlna(this@PlayerActivity, ref, tv, pos / 1000.0, null, next) { }
                else Screens.play(this@PlayerActivity, ref, screen!!.key, pos / 1000.0, null, next) { }
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
        var tailDone = false
        while (isActive) {
            val (pos, dur) = withContext(Dispatchers.Main) { p.currentPosition to p.duration }
            if (dur <= 0) { delay(2_000); continue }
            val worked = runCatching {
                val size = headProbe(url, 0).first
                fileSize = size
                OfflineStore.update(this@PlayerActivity, key) { it.copy(size = size) }
                MediaCache.setLength(this@PlayerActivity, key, size)
                // the file's tail first: MKV keeps its index (Cues) and many MP4s their moov at the end, and the player
                // reads it before the first frame; without it a buffer does not start offline ("stuck buffering")
                if (!tailDone) {
                    val tail = minOf(size, TAIL_BYTES)
                    if (headProbe(url, size - tail).second >= size) {
                        cacheRange(cache, writer, size - tail, size)
                        tailDone = true
                    }
                }
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
            // nothing to fetch (buffer full, server away): wait, and with the player off screen until it is back,
            // instead of two HEAD requests every 3 s for as long as a paused player sat behind another app
            if (!worked) { delay(3_000); onScreen() }
        }
    }

    /** Until the player is visible again (a background download keeps going; idle polling doesn't). */
    private suspend fun onScreen() {
        lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
    }

    /** Writes the bytes [from, to) of the video to the disk buffer, skipping what is already there. */
    private fun cacheRange(cache: androidx.media3.datasource.cache.Cache, writer: CacheDataSource, from: Long, to: Long) {
        var at = from
        while (at < to) {
            val cached = cache.getCachedLength(key, at, to - at)
            if (cached > 0) { at += cached; continue }
            val len = minOf(-cached, to - at)
            CacheWriter(writer, DataSpec.Builder().setUri(url).setKey(key).setPosition(at).setLength(len).build(), null, null).cache()
            at += len
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
            onScreen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        syncState?.invoke()
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
            if (pos > dur / 2) prepareNextSubs(nextEps.first())  // fetching and fitting can take a minute
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
        // the version in use (next to the video, agent 1.12+) first, then by fit
        val rank = { s: SubFile -> (if (s.default) 0 else 10) + when (s.fit) { "good" -> 0; null, "unsure" -> 1; else -> 2 } }
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
            .setMediaMetadata(MediaMetadata.Builder().setTitle(shownTitle ?: ref.title).build())
            .setSubtitleConfigurations(subConfigs)
            .build()
    }

    private var subtitleButton: android.widget.ImageButton? = null

    /** Playing before a track menu opened: it plays again when the menu closes. */
    private var resumeAfterMenu = false

    /** ExoPlayer's gear (audio track, speed, quality) pauses while its menu is open: the menu is a focusable popup, so
     * the window gets the focus back when it closes. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun pauseWhileInSettings(view: PlayerView) {
        view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) player?.let { p ->
                if (p.isPlaying) { resumeAfterMenu = true; p.pause() }
            }
            false  // ExoPlayer opens its menu as usual
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumeAfterMenu) {
            resumeAfterMenu = false
            player?.play()
        }
    }

    /** Our CC button where ExoPlayer's was (same look): the subtitle menu with "Download: <language>" entries. */
    private fun installSubtitleButton(view: PlayerView) {
        val orig = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_subtitle) ?: return
        val parent = orig.parent as? android.view.ViewGroup ?: return
        val b = android.widget.ImageButton(this, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom_CC)
        b.setOnClickListener { subtitleMenu() }
        parent.addView(b, parent.indexOfChild(orig), orig.layoutParams)
        subtitleButton = b
    }

    /** An icon button in ExoPlayer's style, put before the CC button; the description shows on a long press. */
    private fun barButton(view: PlayerView, icon: Int, desc: Int, onClick: () -> Unit): android.widget.ImageButton? {
        val cc = subtitleButton ?: return null
        val parent = cc.parent as android.view.ViewGroup
        val b = android.widget.ImageButton(this, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom)
        b.setImageResource(icon)
        b.contentDescription = getString(desc)
        b.tooltipText = getString(desc)
        b.setOnClickListener { onClick() }
        parent.addView(b, parent.indexOfChild(cc), cc.layoutParams)
        return b
    }

    private var audioButton: android.widget.ImageButton? = null
    private var audioApplied = false

    /** The video's audio tracks for the sheet and the remembered choice. */
    private fun audioOpts(tracks: Tracks): List<AudioOpt> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }.mapIndexed { i, g ->
            val f = g.getTrackFormat(0)
            AudioOpt(i, f.language, f.label, f.channelCount, f.sampleMimeType, g.isSelected,
                audioKind(f.label, f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0, f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0))
        }

    private fun selectAudio(tracks: Tracks, index: Int) {
        val p = player ?: return
        val g = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }.getOrNull(index) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    /** Once the tracks are known: the button only with a choice, and the track matching the choice remembered for this
     * folder (a series) or in general. */
    private fun applyAudioChoice(tracks: Tracks) {
        val opts = audioOpts(tracks)
        audioButton?.visibility = if (opts.size > 1) android.view.View.VISIBLE else android.view.View.GONE
        if (audioApplied || opts.isEmpty()) return
        audioApplied = true
        val folder = (ref as? MediaRef.InLibrary)?.let(::folderKey)
        val pref = AudioPref.decode(folder?.let { settings.folderAudio(it) } ?: settings.audioChoice)
        pickAudio(opts, pref)?.takeIf { i -> opts.none { it.index == i && it.selected } }?.let { selectAudio(tracks, it) }
    }

    /** The audio sheet: pauses the video while open; a pick switches the track and is remembered. */
    private fun audioMenu() {
        val p = player ?: return
        val root = root ?: return
        val tracks = p.currentTracks
        val opts = audioOpts(tracks)
        val host = androidx.compose.ui.platform.ComposeView(this)
        val wasPlaying = p.isPlaying
        if (wasPlaying) p.pause()
        fun close() {
            root.removeView(host)
            if (wasPlaying) player?.play()
        }
        host.setContent {
            AppTheme {
                AudioSheet(opts, onDismiss = ::close) { o ->
                    selectAudio(tracks, o.index)
                    val pref = AudioPref.of(o, opts).encode()
                    settings.audioChoice = pref
                    (ref as? MediaRef.InLibrary)?.let { settings.setFolderAudio(folderKey(it), pref) }
                    close()
                }
            }
        }
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateSubtitleIcon(tracks: Tracks) {
        val on = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        subtitleButton?.setImageResource(if (on) androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_on
            else androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_off)
    }

    /**
     * The subtitle sheet (SubtitlesSheet, the same as on the title card and in the Remote) over the player: off, the
     * languages (files on the computer + the video's own tracks), versions and searching. What the user picks is turned
     * on in place, at the same position.
     */
    private fun subtitleMenu() {
        val p = player ?: return
        val root = root ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        // a file's track carries its name in the label (buildItem); the rest is inside the video
        val fileOf = { g: Tracks.Group -> subs.firstOrNull { g.getTrackFormat(0).label?.startsWith(it.name) == true } }
        val selectedFile = groups.firstOrNull { it.isSelected }?.let(fileOf)?.name
        val embedded = groups.mapIndexedNotNull { i, g ->
            val f = g.getTrackFormat(0)
            if (fileOf(g) != null) null else EmbeddedSub(i, f.language, g.isSelected, f.label,
                forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                sdh = f.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_CAPTION) != 0)
        }
        val host = androidx.compose.ui.platform.ComposeView(this)
        // choosing subtitles pauses the film; it plays on when the sheet closes (if it played before)
        val wasPlaying = p.isPlaying
        if (wasPlaying) p.pause()
        fun close() {
            root.removeView(host)
            if (wasPlaying) player?.play()
        }
        host.setContent {
            AppTheme {
                SubtitlesSheet(api, ref, onDismiss = ::close, embedded = embedded, selectedFile = selectedFile,
                    offSelected = groups.none { it.isSelected }) { pick, msg ->
                    when (pick) {
                        SubPick.Off -> {
                            rememberSubChoice("off")
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        }
                        is SubPick.Track -> groups.getOrNull(pick.index)?.let { g ->
                            langKey(g.getTrackFormat(0).language).takeIf { it.isNotEmpty() }?.let { rememberSubChoice(it) }
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0))
                                .build()
                        }
                        is SubPick.File -> {
                            if (pick.lang.isNotEmpty()) rememberSubChoice(pick.lang)
                            applySubFile(pick.name, pick.lang)
                        }
                        is SubPick.Shifted -> applySubFile(pick.name, pick.lang)
                        is SubPick.Sync -> startLiveSync(pick.name, pick.lang)
                    }
                    msg?.let { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
        }
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
    }

    /** A subtitle file (just picked or downloaded) on: the list again, local copies, the video reloaded where it was. */
    // ---- live subtitle timing: a bar over the film, the subtitles drawn here from the file with the shift applied
    // (ExoPlayer can't move a text track in time), saved in the file on the computer after a pause in tapping

    private class LiveCue(val start: Long, val end: Long, val text: CharSequence)

    private var syncHost: androidx.compose.ui.platform.ComposeView? = null
    private var syncTick: Runnable? = null
    private var syncSave: Runnable? = null
    private var syncState: (() -> Unit)? = null  // ends the timing bar and saves the pending shift (leaving the player)

    private fun parseSrt(text: String): List<LiveCue> {
        val time = Regex("""(\d+):(\d\d):(\d\d)[,.](\d+)\s*-->\s*(\d+):(\d\d):(\d\d)[,.](\d+)""")
        fun ms(h: String, m: String, s: String, f: String) =
            ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.take(3).padEnd(3, '0').toLong()
        return text.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.trim().lines()
            val i = lines.indexOfFirst { time.containsMatchIn(it) }.takeIf { it >= 0 } ?: return@mapNotNull null
            val g = time.find(lines[i])!!.groupValues
            val body = lines.drop(i + 1).joinToString("<br>").trim().ifEmpty { return@mapNotNull null }
            LiveCue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]),
                android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY))
        }
    }

    private fun startLiveSync(name: String, lang: String) {
        val p = player ?: return
        val root = root ?: return
        val file = subs.firstOrNull { it.name == name } ?: return
        if (syncHost != null) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    localSubUris[name]?.path?.let { java.io.File(it).readText() }
                        ?: java.net.URL(api.subUrl(ref, name)).readText()
                }.getOrNull()
            }
            val cues = text?.let(::parseSrt).orEmpty()
            if (cues.isEmpty()) {
                Toast.makeText(this@PlayerActivity, R.string.media_psubs_sync_failed, Toast.LENGTH_LONG).show(); return@launch
            }
            // ExoPlayer's text track off: the subtitles come from here now
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            val view = playerView?.subtitleView
            val pending = androidx.compose.runtime.mutableDoubleStateOf(0.0)
            var shown: List<LiveCue> = emptyList()
            val tick = object : Runnable {
                override fun run() {
                    val pos = (player ?: return).currentPosition - (pending.doubleValue * 1000).toLong()
                    val now = cues.filter { pos >= it.start && pos < it.end }
                    if (now != shown) {
                        shown = now
                        view?.setCues(now.map { androidx.media3.common.text.Cue.Builder().setText(it.text).build() })
                    }
                    ui.postDelayed(this, 80)
                }
            }
            syncTick = tick
            ui.post(tick)

            fun finish(save: Boolean) {
                syncTick?.let(ui::removeCallbacks); syncTick = null
                syncSave?.let(ui::removeCallbacks); syncSave = null
                syncState = null
                syncHost?.let { root.removeView(it) }; syncHost = null
                view?.setCues(emptyList())
                val by = pending.doubleValue
                if (!save || by == 0.0) { applySubFile(name, lang); return }
                lifecycleScope.launch {
                    val total = withContext(Dispatchers.IO) { runCatching { api.subsShift(ref, name, by) }.getOrNull() }
                    if (total == null) {
                        Toast.makeText(this@PlayerActivity, R.string.media_psubs_sync_failed, Toast.LENGTH_LONG).show()
                    } else {
                        (ref as? MediaRef.InLibrary)?.let { r -> file.source?.provider?.let { settings.setFolderSubShift(folderKey(r), it, total) } }
                        Toast.makeText(this@PlayerActivity, getString(R.string.media_psubs_sync_saved,
                            String.format(java.util.Locale.getDefault(), "%+.1f", total)), Toast.LENGTH_SHORT).show()
                    }
                    applySubFile(name, lang)
                }
            }
            syncState = { finish(true) }  // leaving the player: what is pending is saved
            fun touched() {  // a pause in tapping saves (the user is happy with it)
                syncSave?.let(ui::removeCallbacks)
                syncSave = Runnable { finish(true) }.also { ui.postDelayed(it, SYNC_IDLE_MS) }
            }
            touched()
            val host = androidx.compose.ui.platform.ComposeView(this@PlayerActivity)
            host.setContent {
                AppTheme {
                    LiveSyncBar(file.manual + pending.doubleValue,
                        onShift = { pending.doubleValue = Math.round((pending.doubleValue + it) * 10) / 10.0; touched() },
                        onReset = { pending.doubleValue = -file.manual; touched() },
                        onDone = { finish(true) })
                }
            }
            syncHost = host
            root.addView(host, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (resources.displayMetrics.density * 96).toInt()
            })
        }
    }

    private fun langKey(code: String?) = subLangKey(code)

    private fun rememberSubChoice(lang: String) {
        settings.subChoice = lang
        settings.subChoiceFolder = (ref as? MediaRef.InLibrary)?.let(::folderKey)
    }

    private var chosenSubsChecked = false

    /**
     * Once the tracks are known: when the language picked last ([Settings.subChoice]) has no subtitles here (no file,
     * no embedded track), get them (best fitting result) and turn them on. Only for the computer's library.
     * In the folder where that language was picked (the same series, also after skipped episodes) this happens on its
     * own; elsewhere (another film) a bar over the picture offers it, playback goes on.
     */
    private fun ensureChosenSubs(tracks: Tracks) {
        if (chosenSubsChecked) return
        chosenSubsChecked = true
        val lang = settings.subChoice?.takeIf { it != "off" } ?: return
        val r = ref as? MediaRef.InLibrary ?: return
        if (subs.any { it.langKey == lang }) return
        if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && langKey(it.getTrackFormat(0).language) == lang }) return
        // without the computer (outside, a downloaded copy) there is nothing to offer
        lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { api.configured && api.ping(1500) }) return@launch
            if (folderKey(r) == settings.subChoiceFolder) fetchChosenSubs(r, lang) else offerSubs(r, lang)
        }
    }

    /** "No Polish subtitles. Download?" over the picture for a while; playback is not paused. */
    private fun offerSubs(r: MediaRef.InLibrary, lang: String) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val host = androidx.compose.ui.platform.ComposeView(this)
        val hide = Runnable { root.removeView(host) }
        host.setContent {
            AppTheme {
                SubsOfferBar(langName(lang, java.util.Locale.getDefault()),
                    onYes = { ui.removeCallbacks(hide); hide.run(); fetchChosenSubs(r, lang); settings.subChoiceFolder = folderKey(r) },
                    onNo = { ui.removeCallbacks(hide); hide.run() })
            }
        }
        root.addView(host, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (resources.displayMetrics.density * 48).toInt()
        })
        ui.postDelayed(hide, OFFER_MS)
    }

    private fun fetchChosenSubs(r: MediaRef.InLibrary, lang: String) {
        lifecycleScope.launch {
            val toast = Toast.makeText(this@PlayerActivity, getString(R.string.media_psubs_auto_fetching, langName(lang, java.util.Locale.getDefault())), Toast.LENGTH_LONG)
            toast.show()
            val d = runCatching { fetchSubtitles(this@PlayerActivity, api, ref, lang, subs.mapNotNull { it.source?.key }.toSet()) }.getOrNull()
                ?: return@launch
            carryShift(api, r, d)
            OfflineSync.refreshLater(applicationContext, r)
            if (isFinishing) return@launch
            Toast.makeText(this@PlayerActivity, getString(R.string.media_psubs_auto_on, langName(lang, java.util.Locale.getDefault())), Toast.LENGTH_SHORT).show()
            applySubFile(d.name, lang)
        }
    }

    private var nextSubsStarted = false

    /** Halfway through an episode (or when the next episode's card shows): get the next one's subtitles in the
     * remembered language now, preferably from the same source, so it starts with them. */
    private fun prepareNextSubs(n: Episode) {
        if (nextSubsStarted) return
        nextSubsStarted = true
        val lang = settings.subChoice?.takeIf { it != "off" } ?: return
        val r = ref as? MediaRef.InLibrary ?: return
        val next = MediaRef.InLibrary(r.root, n.path, r.srv)
        val app = applicationContext
        val provider = subs.firstOrNull { it.langKey == lang && it.default }?.source?.provider
        lifecycleScope.launch { prepareSubsAhead(app, api, next, provider, embeddedCounts = true) }
    }

    private fun applySubFile(name: String, lang: String) {
        OfflineSync.refreshLater(applicationContext, ref)  // a downloaded copy gets the change too
        val p = player ?: return
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrNull() }
            // the server out of reach (a version picked offline, G1): the downloaded copy's own files
            val saved = if (fresh != null) emptyList()
                else OfflineStore.get(this@PlayerActivity, key)?.subs.orEmpty().filter { java.io.File(it.file).exists() }
            if (saved.isNotEmpty()) {
                subs = saved.map { it.toSubFile() }
                localSubUris = saved.associate { it.name to Uri.fromFile(java.io.File(it.file)) }
            } else {
                subs = fresh ?: subs
                localSubUris = withContext(Dispatchers.IO) { localSubs(subs) }
            }
            val pos = p.currentPosition
            val playing = p.playWhenReady
            // the file on, even when subtitles were switched off or another track was picked by hand
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .apply { if (lang.isNotEmpty()) setPreferredTextLanguage(lang) }
                .build()
            p.setMediaItem(buildItem(prefer = name), pos)
            p.prepare()
            p.playWhenReady = playing
        }
    }

    /**
     * Intro and credits of a series episode from the agent (agent 1.11+). The agent compares the sound with the
     * neighbouring episodes on the first request (5–10 s), so "pending" is asked again a few times.
     */
    private fun loadSegments() {
        val r = ref as? MediaRef.InLibrary ?: return
        // a downloaded copy: saved with it (works without the computer); the agent below may know better
        OfflineStore.get(this, key)?.let { o ->
            o.intro?.let { intro = it }
            o.creditsAt?.let { creditsAtMs = it }
        }
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
        prepareNextSubs(n)
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

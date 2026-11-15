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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
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

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(16, 16, 16, 16) })
        })

        // najpierw dekodery telefonu, FFmpeg gdy telefon nie umie (AC3/E-AC3/DTS…)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val p = ExoPlayer.Builder(this, renderers).build()
        player = p
        view.player = p
        p.addListener(object : Player.Listener {
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
            val subConfigs = subs.mapNotNull { s ->
                val mime = when (s.ext) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> null
                } ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.subUrl(ref, s.name)))
                    .setMimeType(mime)
                    .setLanguage(s.lang.takeWhile { it.isLetter() }.ifEmpty { null })
                    .setLabel(s.name)
                    .setSelectionFlags(if (s == subs.first()) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            p.setMediaItem(MediaItem.Builder().setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).build())
                .setSubtitleConfigurations(subConfigs)
                .build())
            p.seekTo(start)
            p.prepare()
            p.playWhenReady = true
        }
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

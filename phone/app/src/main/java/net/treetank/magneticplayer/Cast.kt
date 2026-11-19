package net.treetank.magneticplayer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google Cast options (referenced in the manifest): Google's default receiver, control in the notification. */
class MpCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder().setTargetActivityClassName(CastControlsActivity::class.java.name).build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notif)
            .setExpandedControllerActivityClassName(CastControlsActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Full-screen Chromecast controls from the Cast SDK (slider, subtitles, pause). */
class CastControlsActivity : ExpandedControllerActivity()

/** A Chromecast visible on the network (MediaRouter route). */
data class CastTarget(val id: String, val name: String, val desc: String)

/** Subtitles for the screen: the original (mpv) and WebVTT (Chromecast reads only VTT). */
data class SubSrc(val name: String, val lang: String, val url: String, val vtt: String?, val default: Boolean = true)

/** Where the screen gets the video: from the server (ticket) or from the phone ([PhoneShare]). */
data class MediaSrc(val url: String, val title: String, val subs: List<SubSrc>, val viaPhone: Boolean,
                    /** type forced by the server ("for Chromecast" version = video/mp4) */
                    val contentType: String? = null,
                    /** false = the subtitles go along but none is turned on (the user's last choice was "Off") */
                    val subsOn: Boolean = true) {
    /**
     * The subtitles picked last in the player ([Settings.subChoice]) first, so the screen turns them on (it turns on the
     * first one): that language's version in use, then its others, then the rest as they were. "off": none on.
     */
    fun forChoice(choice: String?): MediaSrc = when (choice) {
        null -> this
        "off" -> copy(subsOn = false)
        else -> copy(subs = subs.sortedByDescending { (if (subLangKey(it.lang) == choice) 2 else 0) + (if (it.default) 1 else 0) }
            .let { s -> if (s.firstOrNull()?.let { subLangKey(it.lang) } == choice) s else subs })
    }
}

/** Chromecasts: discovery (MediaRouter), playback through the default receiver, state for Remote. All on the UI thread. */
object Cast {
    private var cc: CastContext? = null
    private var tried = false
    private var app: Context? = null
    /** what we sent last: for saving the position and cleaning up phone sharing */
    private var current: MediaRef? = null
    private var currentUrl: String? = null
    private var viaPhone = false
    private var listening: RemoteMediaClient? = null

    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    /** CastContext or null (no Google Play services). */
    fun context(ctx: Context): CastContext? {
        if (cc == null && !tried) {
            tried = true
            app = ctx.applicationContext
            cc = runCatching { CastContext.getSharedInstance(ctx.applicationContext) }.getOrNull()
            cc?.sessionManager?.addSessionManagerListener(ended, CastSession::class.java)
        }
        return cc
    }

    fun available(ctx: Context) = context(ctx) != null

    fun session(ctx: Context): CastSession? = context(ctx)?.sessionManager?.currentCastSession?.takeIf { it.isConnected }

    fun routes(ctx: Context): List<CastTarget> {
        if (context(ctx) == null) return emptyList()
        return MediaRouter.getInstance(ctx).routes
            .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { CastTarget(it.id, it.name, it.description ?: "") }
    }

    /** Looks for Chromecasts until you call the returned function. */
    fun discover(ctx: Context, onChange: (List<CastTarget>) -> Unit): () -> Unit {
        if (context(ctx) == null) return {}
        val router = MediaRouter.getInstance(ctx)
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = onChange(routes(ctx))
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = onChange(routes(ctx))
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = onChange(routes(ctx))
        }
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        onChange(routes(ctx))
        return { router.removeCallback(cb) }
    }

    /** Type for the receiver: MKV is passed as MP4 (the player detects the container from the content anyway). */
    private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    /** Connects to the Chromecast `target` (if needed) and plays `src` from `startMs`. */
    suspend fun play(ctx: Context, target: CastTarget, ref: MediaRef, src: MediaSrc, startMs: Long) = withContext(Dispatchers.Main) {
        val c = context(ctx) ?: throw ApiException(ctx.getString(R.string.main_cast_no_play_services))
        val router = MediaRouter.getInstance(ctx)
        // After the picker closes, discovery stops and Android immediately forgets the routes, so we search again
        // (up to 10 s) and keep discovery running until connecting finishes.
        val keep = object : MediaRouter.Callback() {}
        router.addCallback(selector, keep, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        try { playOn(ctx, c, router, target, ref, src, startMs) } finally { router.removeCallback(keep) }
    }

    private suspend fun playOn(ctx: Context, c: CastContext, router: MediaRouter, target: CastTarget, ref: MediaRef,
                               src: MediaSrc, startMs: Long) {
        val route = withTimeoutOrNull(10_000) {
            var r: MediaRouter.RouteInfo? = null
            while (r == null) {
                r = router.routes.firstOrNull { it.id == target.id }
                    ?: router.routes.firstOrNull { it.name == target.name && it.matchesSelector(selector) }
                if (r == null) delay(250)
            }
            r
        } ?: throw ApiException(ctx.getString(R.string.main_cast_gone))
        var s = session(ctx)
        if (s == null || !route.isSelected) {
            s = withTimeoutOrNull(30_000) { awaitSession(ctx, c) { router.selectRoute(route) } }
                ?: throw ApiException(ctx.getString(R.string.main_not_responding, route.name))
        }
        val client = s.remoteMediaClient ?: throw ApiException(ctx.getString(R.string.main_cast_no_media, route.name))
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, src.title) }
        val tracks = src.subs.filter { it.vtt != null }.mapIndexed { i, sub ->
            MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT)
                .setName(sub.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(sub.vtt)
                .setContentType("text/vtt")
                .apply { sub.lang.takeWhile { it.isLetter() }.takeIf { it.length in 2..3 }?.let { setLanguage(it) } }
                .build()
        }
        val style = TextTrackStyle().apply {
            fontScale = 1.1f
            backgroundColor = 0x00000000
            edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
            edgeColor = 0xFF000000.toInt()
            foregroundColor = 0xFFFFFFFF.toInt()
        }
        val info = MediaInfo.Builder(src.url)
            .setContentUrl(src.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(src.contentType ?: contentType(ref.title))
            .setMetadata(meta)
            .setMediaTracks(tracks)
            .setTextTrackStyle(style)
            .build()
        val req = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(startMs)
            .apply { if (tracks.isNotEmpty() && src.subsOn) setActiveTrackIds(longArrayOf(1)) }
            .build()
        val r = suspendCancellableCoroutine { cont -> client.load(req).setResultCallback { cont.resume(it) } }
        if (!r.status.isSuccess) {
            throw ApiException(ctx.getString(R.string.main_cast_cant_play, route.name, r.status.statusCode))
        }
        // the previous phone sharing is no longer needed
        if (viaPhone && !src.viaPhone) ShareService.stop(ctx)
        current = ref; currentUrl = src.url; viaPhone = src.viaPhone
        if (listening !== client) {
            listening?.removeProgressListener(progress)
            listening?.unregisterCallback(statusCb)
            client.addProgressListener(progress, 5_000)
            client.registerCallback(statusCb)
            listening = client
        }
    }

    private suspend fun awaitSession(ctx: Context, c: CastContext, start: () -> Unit): CastSession = suspendCancellableCoroutine { cont ->
        val sm = c.sessionManager
        val l = object : SessionManagerListener<CastSession> {
            fun done(s: CastSession?, err: Int) {
                sm.removeSessionManagerListener(this, CastSession::class.java)
                if (!cont.isActive) return
                if (s != null) cont.resume(s) else cont.resumeWithException(ApiException(ctx.getString(R.string.main_cast_connect_failed, err)))
            }
            override fun onSessionStarted(s: CastSession, id: String) = done(s, 0)
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = done(s, 0)
            override fun onSessionStartFailed(s: CastSession, e: Int) = done(null, e)
            override fun onSessionResumeFailed(s: CastSession, e: Int) = done(null, e)
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, e: Int) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionSuspended(s: CastSession, reason: Int) {}
        }
        sm.addSessionManagerListener(l, CastSession::class.java)
        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post { sm.removeSessionManagerListener(l, CastSession::class.java) }
        }
        start()
    }

    /** Position of the sent video every 5 s (like Remote for mpv), so the phone and screens know where it stopped. */
    private val progress = RemoteMediaClient.ProgressListener { pos, dur ->
        val ref = current ?: return@ProgressListener
        val ctx = app ?: return@ProgressListener
        if (listening?.mediaInfo?.contentId != currentUrl || dur <= 0 || pos < 5_000) return@ProgressListener
        Settings(ctx).setPosition(ref.key(), if (pos > dur - 60_000) 0 else pos)
    }

    /** Receiver state for the log; a playback error (e.g. it cannot reach the address) is shown instead of silently stalling. */
    private val statusCb = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = listening?.mediaStatus ?: return
            android.util.Log.i("MpCast", "stan ${st.playerState} idle=${st.idleReason} pos=${st.streamPosition}")
            if (st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                app?.let { android.widget.Toast.makeText(it, it.getString(R.string.main_cast_play_error), android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** End of session: release phone sharing if it was for the Chromecast. */
    private val ended = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(s: CastSession, e: Int) {
            listening?.removeProgressListener(progress)
            listening?.unregisterCallback(statusCb)
            listening = null
            app?.let { if (viaPhone) ShareService.stop(it) }
            current = null; currentUrl = null; viaPhone = false
        }
        override fun onSessionStarted(s: CastSession, id: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
        override fun onSessionStartFailed(s: CastSession, e: Int) {}
        override fun onSessionResumeFailed(s: CastSession, e: Int) {}
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    data class State(val device: String, val title: String?, val pos: Long, val dur: Long, val playing: Boolean,
                     val buffering: Boolean, val viaPhone: Boolean, val volume: Int?, val muted: Boolean)

    fun deviceName(ctx: Context) = session(ctx)?.castDevice?.friendlyName

    private fun fixedVolume(ctx: Context) =
        MediaRouter.getInstance(ctx).selectedRoute.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED

    fun state(ctx: Context): State? {
        val s = session(ctx) ?: return null
        val c = s.remoteMediaClient
        return State(s.castDevice?.friendlyName ?: "Chromecast",
            c?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE),
            c?.approximateStreamPosition ?: 0, c?.streamDuration ?: 0, c?.isPlaying == true, c?.isBuffering == true,
            viaPhone && c?.mediaInfo?.contentId == currentUrl,
            if (fixedVolume(ctx)) null else (s.volume * 100).toInt(), runCatching { s.isMute }.getOrDefault(false))
    }

    fun setPlaying(ctx: Context, play: Boolean) {
        val c = session(ctx)?.remoteMediaClient ?: return
        if (play) c.play() else c.pause()
    }

    fun seekTo(ctx: Context, ms: Long) {
        session(ctx)?.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(ms.coerceAtLeast(0)).build())
    }

    fun setVolume(ctx: Context, v: Double) {
        if (!fixedVolume(ctx)) runCatching { session(ctx)?.volume = v.coerceIn(0.0, 1.0) }
    }

    fun setMuted(ctx: Context, m: Boolean) { runCatching { session(ctx)?.isMute = m } }

    fun togglePause(ctx: Context) { session(ctx)?.remoteMediaClient?.togglePlayback() }

    fun seekBy(ctx: Context, deltaMs: Long) {
        val c = session(ctx)?.remoteMediaClient ?: return
        c.seek(MediaSeekOptions.Builder().setPosition((c.approximateStreamPosition + deltaMs).coerceAtLeast(0)).build())
    }

    private var fixedHint = 0L

    fun volumeBy(ctx: Context, delta: Double): Boolean {
        val s = session(ctx) ?: return false
        if (fixedVolume(ctx)) {
            // e.g. a Chromecast with volume controlled by the TV (HDMI-CEC) or "fixed volume" in Google Home
            val now = System.currentTimeMillis()
            if (now - fixedHint > 4_000) {
                fixedHint = now
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.main_cast_fixed_volume), android.widget.Toast.LENGTH_SHORT).show()
            }
            return true
        }
        runCatching { s.volume = (s.volume + delta).coerceIn(0.0, 1.0) }
        return true
    }

    fun stop(ctx: Context) { context(ctx)?.sessionManager?.endCurrentSession(true) }
}

/** Chromecasts on the network, live, as long as the composable is on screen. */
@Composable
fun rememberCastTargets(): List<CastTarget> {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Cast.routes(ctx)) }
    DisposableEffect(Unit) {
        val stop = Cast.discover(ctx) { list = it }
        onDispose { stop() }
    }
    return list
}

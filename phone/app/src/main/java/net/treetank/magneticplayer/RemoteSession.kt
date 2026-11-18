package net.treetank.magneticplayer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One remote target (`kind`: mpv on a screen, DLNA TV or Chromecast) as a "player" for the system: a notification
 * with buttons and a slider (top panel, lock screen) and the device volume with the phone's buttons, also outside the app
 * (remote session, DeviceInfo REMOTE). It plays nothing itself, reads the state every ~1.5 s. Each playing target has its own session
 * and its own notification ([RemoteSessionService]).
 * A Chromecast started from this phone through the Cast SDK has the SDK's notification; one controlled directly or through
 * the agent (web page, another phone, YouTube) gets it from here.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(private val ctx: Context, val kind: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()
    @Volatile private var remote: Remote? = null
    @Volatile private var st: RemoteState? = null
    /** how many reads in a row had nothing playing (the service ends after a few) */
    var idlePolls = 0
        private set
    /** "Stop" from the notification on someone else's app: a second tap before this moment closes it (instead of a confirmation dialog) */
    @Volatile private var armedUntil = 0L

    /** "Stop" button in the notification. Someone else's app (Spotify…) is closed only by a second tap within 5 s. */
    fun stopTapped() {
        val now = System.currentTimeMillis()
        if (st?.app != null && Settings(ctx).confirmForeignStop && now > armedUntil) {
            armedUntil = now + 5000
            // hint also as a toast: text in the notification card is sometimes cut off
            val msg = ctx.getString(R.string.main_notif_confirm_stop, st?.app ?: "")
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
            invalidateState()
            scope.launch { delay(5100); invalidateState() }  // the hint disappears by itself
            return
        }
        armedUntil = 0
        run { stop() }
    }

    init {
        scope.launch {
            var n = 0
            while (isActive) {
                val r = pick()
                val s = r?.let { runCatching { it.status() }.getOrNull() }
                remote = r; st = s
                // a stopped TV (end of video, someone turned it off) counts as "nothing playing"
                val idle = s == null || (r is DlnaRemote && Dlna.last?.state in Dlna.IDLE_STATES)
                idlePolls = if (idle) idlePolls + 1 else 0
                // device name in the system (tile, card in the panel) instead of "This phone"
                RemoteRoutes.set(ctx, kind, if (s != null && r != null) r.name else null)
                invalidateState()
                if (n++ % 2 == 0) announce(r, s)
                delay(1500)
            }
        }
    }

    /** A DLNA TV knows only the phone that started the video on it: we tell the other remotes what is playing on it ([Announce]). */
    private fun announce(r: Remote?, s: RemoteState?) {
        val t = Dlna.current
        if (r !is DlnaRemote || s == null || t == null) { Announce.mine = emptyList(); return }
        val state = when { s.buffering -> "BUFFERING"; s.playing -> "PLAYING"; else -> "PAUSED" }
        val msg = Announce.playing(ctx, "dlna", t.id, t.name, Dlna.ctl(t), s.title, state, s.posNow(), s.dur)
        Announce.mine = listOf(msg)
        Announce.send(msg)
    }

    private fun pick(): Remote? = when (kind) {
        "dlna" -> if (Dlna.current != null) DlnaRemote(ctx) else null
        // a Chromecast with the Cast SDK has its own notification
        "cast" -> if (Cast.session(ctx) == null) Pilot.remote(ctx, "cast") else null
        else -> Pilot.remote(ctx, kind)
    }

    private val remoteDevice = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100)
        .setRoutingControllerId(RemoteRoutes.controllerId(kind)).build()

    override fun getState(): State {
        val s = st
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        ).apply {
            if (s?.volume != null) addAll(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        }.build()
        val b = State.Builder().setAvailableCommands(cmds).setDeviceInfo(remoteDevice)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000)
        val r = remote
        if (s == null || r == null) return b.setPlaybackState(Player.STATE_IDLE).build()
        val item = MediaItemData.Builder(r.kind)
            .setMediaItem(MediaItem.Builder().setMediaId(r.kind).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(
                if (s.app != null && System.currentTimeMillis() < armedUntil) ctx.getString(R.string.main_notif_confirm_stop, s.app)
                else r.name).build())
            .setDurationUs(if (s.dur > 0) (s.dur * 1_000_000).toLong() else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
        val base = (s.pos * 1000).toLong()
        val pos = if (s.playing && !s.buffering) PositionSupplier { (s.posNow() * 1000).toLong() } else PositionSupplier.getConstant(base)
        return b.setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
            .setPlaybackState(if (s.buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(s.playing || s.buffering, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(pos)
            .setDeviceVolume(s.volume ?: 0)
            .setIsDeviceMuted(s.muted)
            .build()
    }

    private fun run(f: suspend Remote.() -> Unit): ListenableFuture<*> {
        val r = remote ?: return Futures.immediateVoidFuture()
        scope.launch {
            runCatching { r.f() }
            st = runCatching { r.status() }.getOrNull() ?: st
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = run { setPlaying(playWhenReady) }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = run {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> seekBy(-10.0)
            Player.COMMAND_SEEK_FORWARD -> seekBy(30.0)
            else -> seekTo(positionMs / 1000.0)
        }
    }

    override fun handleStop() = run { stop() }
    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = run { setVolume(deviceVolume) }
    override fun handleIncreaseDeviceVolume(flags: Int) = run { volumeBy(1) }
    override fun handleDecreaseDeviceVolume(flags: Int) = run { volumeBy(-1) }
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int) = run { setMuted(muted) }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        RemoteRoutes.set(ctx, kind, null)
        return Futures.immediateVoidFuture()
    }
}

/**
 * A session (and notification) for every target that is playing: screen, TV, Chromecast, even several at once. A target disappears
 * after ~10 s of silence, the service ends with the last one. media3 turns the session into a service with notifications by itself.
 */
@OptIn(UnstableApi::class)
class RemoteSessionService : MediaSessionService() {
    private val sessions = linkedMapOf<String, MediaSession>()
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            // ~10 s with nothing playing: end of this target (mpv closed, TV stopped through Stop)
            for ((kind, s) in sessions.toList()) {
                if ((s.player as RemotePlayer).idlePolls < 7) continue
                // the TV plays nothing now: we stop tracking it
                if (kind == "dlna" && Dlna.last?.state in Dlna.IDLE_STATES) Dlna.forget()
                drop(kind)
            }
            if (sessions.isEmpty()) { stopSelf(); return }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // a separate id per target (the default 1001 is also used by the in-phone player): notifications do not overwrite each other
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationIdProvider { s -> 2001 + KINDS.indexOf(s.id.removePrefix("remote-")).coerceAtLeast(0) }
            .build())
        main.postDelayed(watch, 3000)
        stopListen = Announce.listen(this)  // answers other remotes' "who" while something is playing
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_KIND)?.takeIf { it in KINDS }?.let { add(it) }
        if (sessions.isEmpty()) add("screen")  // old start without a kind (e.g. a media button)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun add(kind: String) {
        if (kind in sessions) return
        val open = android.app.PendingIntent.getActivity(this, KINDS.indexOf(kind),
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val stopCmd = SessionCommand(CMD_STOP, android.os.Bundle.EMPTY)
        val stopBtn = CommandButton.Builder(CommandButton.ICON_STOP).setDisplayName(getString(R.string.main_stop))
            .setSessionCommand(stopCmd).setSlots(CommandButton.SLOT_OVERFLOW).build()
        sessions[kind] = MediaSession.Builder(this, RemotePlayer(this, kind)).setId("remote-$kind").setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(stopCmd).build())
                        .build()
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                             customCommand: SessionCommand, args: android.os.Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_STOP) (session.player as RemotePlayer).stopTapped()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setMediaButtonPreferences(ImmutableList.of(stopBtn))
            .build()
            // without this media3 shows the notification only when some controller connects (e.g. System UI)
            .also { addSession(it) }
    }

    private fun drop(kind: String) {
        val s = sessions.remove(kind) ?: return
        removeSession(s)
        s.player.release(); s.release()
    }

    private var stopListen: (() -> Unit)? = null

    // external controllers (watch, Android Auto) get the target that Remote shows
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) =
        sessions[Pilot.focus.value] ?: sessions.values.firstOrNull()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // app closed from the recents list: the video on the screen/TV keeps playing, so the notification stays
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        stopListen?.invoke()
        sessions.keys.toList().forEach { drop(it) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val CMD_STOP = "net.treetank.magneticplayer.STOP"
        private val KINDS = listOf("screen", "dlna", "cast")

        /** Notification for target `kind` ("screen" | "dlna" | "cast"), as long as something is playing on it. */
        fun start(ctx: Context, kind: String) {
            runCatching { ctx.startService(Intent(ctx, RemoteSessionService::class.java).putExtra(EXTRA_KIND, kind)) }
        }
    }
}

package eu.jm.magneticplayer

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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Zdalny odtwarzacz ([Remote]: mpv na ekranie albo telewizor DLNA) jako „odtwarzacz” dla systemu: powiadomienie
 * z przyciskami i suwakiem (panel u góry, ekran blokady) i głośność urządzenia przyciskami telefonu także poza apką
 * (sesja zdalna, DeviceInfo REMOTE). Sam nic nie odtwarza, stan czyta co ~1,5 s.
 * Chromecast puszczony z tego telefonu ma powiadomienie z Cast SDK; ten widziany tylko przez agenta (strona, YouTube)
 * dostaje je stąd, gdy wybierze się go na Pilocie.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(private val ctx: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()
    @Volatile private var remote: Remote? = null
    @Volatile private var st: RemoteState? = null
    /** ile odczytów z rzędu nic nie grało (usługa kończy się po kilku) */
    var idlePolls = 0
        private set

    init {
        scope.launch {
            var n = 0
            while (isActive) {
                val r = pick()
                val s = r?.let { runCatching { it.status() }.getOrNull() }
                remote = r; st = s
                // zatrzymany telewizor (koniec filmu, ktoś wyłączył) liczy się jak „nic nie gra”
                val idle = s == null || (r is DlnaRemote && Dlna.last?.state in Dlna.IDLE_STATES)
                idlePolls = if (idle) idlePolls + 1 else 0
                invalidateState()
                if (n++ % 2 == 0) announce(r, s)
                delay(1500)
            }
        }
    }

    /** Telewizor DLNA zna tylko telefon, który na nim puścił: mówimy innym pilotom, co na nim gra ([Announce]). */
    private fun announce(r: Remote?, s: RemoteState?) {
        val t = Dlna.current
        if (r !is DlnaRemote || s == null || t == null) { Announce.mine = emptyList(); return }
        val state = when { s.buffering -> "BUFFERING"; s.playing -> "PLAYING"; else -> "PAUSED" }
        val msg = Announce.playing(ctx, "dlna", t.id, t.name, Dlna.ctl(t), s.title, state, s.posNow(), s.dur)
        Announce.mine = listOf(msg)
        Announce.send(msg)
    }

    /** DLNA, gdy coś na nie wysłaliśmy; inaczej ekran z mpv (chyba że Pilot steruje Chromecastem). */
    private fun pick(): Remote? = when {
        Dlna.current != null -> DlnaRemote(ctx)
        // Chromecast z Cast SDK ma własne powiadomienie; widziany tylko przez agenta dostaje nasze
        Pilot.focus.value == "cast" -> if (Cast.session(ctx) == null) Pilot.remote(ctx, "cast") else null
        else -> Pilot.remote(ctx, "screen")
    }

    private val remoteDevice = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()

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
            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(r.name).build())
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
        return Futures.immediateVoidFuture()
    }
}

/** Trzyma sesję, dopóki coś gra na ekranie albo telewizorze; media3 sama robi z niej usługę z powiadomieniem. */
@OptIn(UnstableApi::class)
class RemoteSessionService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            // ~10 s bez niczego grającego: koniec (mpv zamknięte, telewizor zatrzymany przez Zakończ)
            if (((session?.player as? RemotePlayer)?.idlePolls ?: 99) >= 7) {
                if (Dlna.last?.state in Dlna.IDLE_STATES) Dlna.forget()  // telewizor już nic nie gra: przestajemy go śledzić
                stopSelf(); return
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val open = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, RemotePlayer(this)).setId("remote").setSessionActivity(open).build()
            // bez tego media3 pokaże powiadomienie dopiero, gdy podłączy się jakiś kontroler (np. System UI)
            .also { addSession(it) }
        main.postDelayed(watch, 3000)
        stopListen = Announce.listen(this)  // odpowiada na „who” innych pilotów, póki coś gra
    }

    private var stopListen: (() -> Unit)? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // apka zamknięta z listy: film na ekranie/telewizorze gra dalej, więc powiadomienie zostaje
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        stopListen?.invoke()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, RemoteSessionService::class.java)) }
        }
    }
}

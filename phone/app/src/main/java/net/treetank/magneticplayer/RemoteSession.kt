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
 * Jeden zdalny cel (`kind`: mpv na ekranie, telewizor DLNA albo Chromecast) jako „odtwarzacz” dla systemu: powiadomienie
 * z przyciskami i suwakiem (panel u góry, ekran blokady) i głośność urządzenia przyciskami telefonu także poza apką
 * (sesja zdalna, DeviceInfo REMOTE). Sam nic nie odtwarza, stan czyta co ~1,5 s. Każdy grający cel ma własną sesję
 * i własne powiadomienie ([RemoteSessionService]).
 * Chromecast puszczony z tego telefonu przez Cast SDK ma powiadomienie z SDK; ten sterowany bezpośrednio albo przez
 * agenta (strona, inny telefon, YouTube) dostaje je stąd.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(private val ctx: Context, val kind: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()
    @Volatile private var remote: Remote? = null
    @Volatile private var st: RemoteState? = null
    /** ile odczytów z rzędu nic nie grało (usługa kończy się po kilku) */
    var idlePolls = 0
        private set
    /** „Zakończ” z powiadomienia przy cudzej aplikacji: drugie dotknięcie do tej chwili zamyka (zamiast okna z pytaniem) */
    @Volatile private var armedUntil = 0L

    /** Przycisk „Zakończ” w powiadomieniu. Cudzą aplikację (Spotify…) zamyka dopiero drugie dotknięcie w ciągu 5 s. */
    fun stopTapped() {
        val now = System.currentTimeMillis()
        if (st?.app != null && Settings(ctx).confirmForeignStop && now > armedUntil) {
            armedUntil = now + 5000
            // podpowiedź też jako toast: napis w karcie powiadomienia bywa ucięty
            val msg = ctx.getString(R.string.main_notif_confirm_stop, st?.app ?: "")
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
            invalidateState()
            scope.launch { delay(5100); invalidateState() }  // podpowiedź znika sama
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
                // zatrzymany telewizor (koniec filmu, ktoś wyłączył) liczy się jak „nic nie gra”
                val idle = s == null || (r is DlnaRemote && Dlna.last?.state in Dlna.IDLE_STATES)
                idlePolls = if (idle) idlePolls + 1 else 0
                // nazwa urządzenia w systemie (kafelek, karta w panelu) zamiast „Ten telefon”
                RemoteRoutes.set(ctx, kind, if (s != null && r != null) r.name else null)
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

    private fun pick(): Remote? = when (kind) {
        "dlna" -> if (Dlna.current != null) DlnaRemote(ctx) else null
        // Chromecast z Cast SDK ma własne powiadomienie
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
 * Sesja (i powiadomienie) dla każdego celu, który gra: ekran, telewizor, Chromecast, także kilka naraz. Cel znika
 * po ~10 s ciszy, usługa kończy się razem z ostatnim. media3 sama robi z sesji usługę z powiadomieniami.
 */
@OptIn(UnstableApi::class)
class RemoteSessionService : MediaSessionService() {
    private val sessions = linkedMapOf<String, MediaSession>()
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            // ~10 s bez niczego grającego: koniec tego celu (mpv zamknięte, telewizor zatrzymany przez Zakończ)
            for ((kind, s) in sessions.toList()) {
                if ((s.player as RemotePlayer).idlePolls < 7) continue
                // telewizor już nic nie gra: przestajemy go śledzić
                if (kind == "dlna" && Dlna.last?.state in Dlna.IDLE_STATES) Dlna.forget()
                drop(kind)
            }
            if (sessions.isEmpty()) { stopSelf(); return }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // osobne id na cel (domyślne 1001 ma też odtwarzacz w telefonie): powiadomienia się nie nadpisują
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationIdProvider { s -> 2001 + KINDS.indexOf(s.id.removePrefix("remote-")).coerceAtLeast(0) }
            .build())
        main.postDelayed(watch, 3000)
        stopListen = Announce.listen(this)  // odpowiada na „who” innych pilotów, póki coś gra
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_KIND)?.takeIf { it in KINDS }?.let { add(it) }
        if (sessions.isEmpty()) add("screen")  // stary start bez rodzaju (np. przycisk multimedialny)
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
            // bez tego media3 pokaże powiadomienie dopiero, gdy podłączy się jakiś kontroler (np. System UI)
            .also { addSession(it) }
    }

    private fun drop(kind: String) {
        val s = sessions.remove(kind) ?: return
        removeSession(s)
        s.player.release(); s.release()
    }

    private var stopListen: (() -> Unit)? = null

    // kontrolery z zewnątrz (zegarek, Android Auto) dostają cel, który Pilot pokazuje
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) =
        sessions[Pilot.focus.value] ?: sessions.values.firstOrNull()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // apka zamknięta z listy: film na ekranie/telewizorze gra dalej, więc powiadomienie zostaje
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

        /** Powiadomienie dla celu `kind` ("screen" | "dlna" | "cast"), dopóki coś na nim gra. */
        fun start(ctx: Context, kind: String) {
            runCatching { ctx.startService(Intent(ctx, RemoteSessionService::class.java).putExtra(EXTRA_KIND, kind)) }
        }
    }
}

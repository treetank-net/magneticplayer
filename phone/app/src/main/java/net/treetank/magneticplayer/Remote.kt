package net.treetank.magneticplayer

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** State of the remote player, shared by Remote and the notification. Times in seconds. */
data class RemoteState(
    val title: String, val pos: Double, val dur: Double, val playing: Boolean, val buffering: Boolean = false,
    /** 0–100; null = volume cannot be controlled (e.g. a Chromecast with fixed volume) */
    val volume: Int? = null, val muted: Boolean = false,
    /** note under the title: "video from the phone", "TV not responding"… */
    val note: String? = null,
    /** someone else's app on a Chromecast (Spotify, YouTube…); "Stop" closes it, so we ask first */
    val app: String? = null,
    /** full mpv state (tracks, subtitles, full screen) */
    val mpv: JSONObject? = null,
    val at: Long = System.currentTimeMillis(),
) {
    /** position now: it advances on its own between reads */
    fun posNow() = if (playing && !buffering) (pos + (System.currentTimeMillis() - at) / 1000.0).let { if (dur > 0) it.coerceAtMost(dur) else it } else pos
}

/**
 * Remote player: mpv on the server's screen, Chromecast or DLNA TV. The same Remote and the same notification
 * for everyone. Methods may be called from any thread (each picks its own thread).
 */
interface Remote {
    val kind: String  // "screen" | "cast" | "dlna"
    val name: String
    /** null = nothing is playing (or the device disappeared) */
    suspend fun status(): RemoteState?
    suspend fun setPlaying(play: Boolean)
    suspend fun seekTo(sec: Double)
    suspend fun seekBy(sec: Double)
    /** volume step (±1 = phone button) */
    suspend fun volumeBy(steps: Int)
    suspend fun setVolume(v: Int)
    suspend fun setMuted(m: Boolean)
    suspend fun stop()
}

/** mpv on a server with a screen (commands /api/player/cmd). */
class MpvRemote(private val ctx: Context, val api: Api) : Remote {
    override val kind = "screen"
    override val name: String get() = api.srv.name
    @Volatile private var last: RemoteState? = null

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        val o = api.playerStatus()
        if (!o.optBoolean("running")) return@withContext null.also { last = null }
        fun d(k: String) = o.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0
        val pos = d("pos")
        // video opened from another server (URL with a ticket): mpv will not remember the position, so the phone saves it
        val ref = Servers.screenRef(ctx, api.srv.key)
        if (ref != null && o.optString("title") == ref.title && pos > 5) Settings(ctx).setPosition(ref.key(), (pos * 1000).toLong())
        RemoteState(o.optString("title"), pos, d("duration"), !o.optBoolean("paused"),
            volume = d("volume").toInt().coerceIn(0, 100), muted = o.optBoolean("muted"), mpv = o).also { last = it }
    }

    private suspend fun cmd(c: String, v: Any? = null) = withContext(Dispatchers.IO) { api.playerCmd(c, v) }

    override suspend fun setPlaying(play: Boolean) {
        val playing = last?.playing ?: status()?.playing ?: return
        if (playing != play) cmd("toggle_pause")
        last = last?.copy(playing = play, at = System.currentTimeMillis())
    }
    override suspend fun seekTo(sec: Double) = cmd("seek_to", sec)
    override suspend fun seekBy(sec: Double) = cmd("seek", sec)
    override suspend fun volumeBy(steps: Int) = cmd("volume_rel", steps * 5)
    override suspend fun setVolume(v: Int) = cmd("volume", v)
    override suspend fun setMuted(m: Boolean) { if ((last?.muted ?: false) != m) cmd("mute") }
    override suspend fun stop() = cmd("stop")
    suspend fun raw(c: String, v: Any? = null) = cmd(c, v)
}

/** Chromecast (Cast SDK session; the same one drives the notification and volume outside the app). */
class CastRemote(private val ctx: Context) : Remote {
    override val kind = "cast"
    override val name: String get() = Cast.deviceName(ctx) ?: "Chromecast"
    private suspend fun <T> main(f: () -> T) = withContext(Dispatchers.Main) { f() }

    override suspend fun status() = main {
        Cast.state(ctx)?.let { s ->
            RemoteState(s.title ?: "", s.pos / 1000.0, s.dur / 1000.0, s.playing, s.buffering,
                volume = s.volume, muted = s.muted, note = if (s.viaPhone) ctx.getString(R.string.main_note_from_phone) else null)
        }
    }
    override suspend fun setPlaying(play: Boolean) = main { Cast.setPlaying(ctx, play) }
    override suspend fun seekTo(sec: Double) = main { Cast.seekTo(ctx, (sec * 1000).toLong()) }
    override suspend fun seekBy(sec: Double) = main { Cast.seekBy(ctx, (sec * 1000).toLong()) }
    override suspend fun volumeBy(steps: Int) = main { Cast.volumeBy(ctx, steps * 0.05); Unit }
    override suspend fun setVolume(v: Int) = main { Cast.setVolume(ctx, v / 100.0) }
    override suspend fun setMuted(m: Boolean) = main { Cast.setMuted(ctx, m) }
    override suspend fun stop() = main { Cast.stop(ctx) }
}

/**
 * A Chromecast playing something this phone did not start through the Cast SDK (web page, another phone, YouTube…):
 * state and commands go through the agent, which listens to all Chromecasts on the network (/api/cast/status).
 */
class AgentCastRemote(private val ctx: Context, val api: Api, private val device: String) : Remote {
    override val kind = "cast"
    @Volatile private var last: RemoteState? = null
    @Volatile private var devName: String? = null
    override val name: String get() = devName ?: "Chromecast"

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        val all = api.castStatus().optJSONArray("all")
        val o = (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }.firstOrNull { it.optString("id") == device }
        Pilot.agentCast.value = o?.let { api.srv.key to it }
        if (o == null) return@withContext null.also { last = null }
        devName = o.optString("device")
        fun d(k: String) = o.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0
        val app = o.optString("app").takeIf { it.isNotBlank() && it != "Default Media Receiver" }
        RemoteState(o.optString("title"), d("pos"), d("duration"), o.optString("state") == "PLAYING",
            o.optString("state") == "BUFFERING",
            volume = if (o.optBoolean("volume_fixed")) null else o.optInt("volume"), muted = o.optBoolean("muted"),
            note = app ?: if (!o.optBoolean("mine")) ctx.getString(R.string.main_note_other_device) else null, app = app).also { last = it }
    }

    private suspend fun cmd(c: String, v: Any? = null) = withContext(Dispatchers.IO) { api.castCmd(c, v, device) }

    override suspend fun setPlaying(play: Boolean) {
        cmd(if (play) "play" else "pause")
        last = last?.copy(playing = play, at = System.currentTimeMillis())
    }
    override suspend fun seekTo(sec: Double) = cmd("seek", sec)
    override suspend fun seekBy(sec: Double) = cmd("seek_rel", sec)
    override suspend fun volumeBy(steps: Int) { val v = (last ?: status())?.volume ?: return; setVolume((v + steps * 5).coerceIn(0, 100)) }
    override suspend fun setVolume(v: Int) = cmd("volume", v)
    override suspend fun setMuted(m: Boolean) = cmd("mute", m)
    override suspend fun stop() = cmd("stop")
}

/** A Chromecast seen directly from the phone ([CastNet], Cast v2 protocol): works without the agent and without the Cast SDK. */
class DirectCastRemote(private val id: String) : Remote {
    override val kind = "cast"
    override val name: String get() = CastNet.devices[id]?.name ?: "Chromecast"
    @Volatile private var last: RemoteState? = null

    override suspend fun status(): RemoteState? {
        val s = CastNet.statusOf(id)?.takeIf { it.active } ?: return null.also { last = null }
        val app = s.app?.takeIf { it != "Default Media Receiver" }
        return RemoteState(s.title ?: "", s.pos, s.dur, s.state == "PLAYING", s.state == "BUFFERING",
            volume = if (s.fixedVolume) null else (s.volume * 100).toInt(), muted = s.muted, note = app, app = app, at = s.at).also { last = it }
    }
    override suspend fun setPlaying(play: Boolean) {
        CastNet.media(id, if (play) "PLAY" else "PAUSE")
        last = last?.copy(playing = play, at = System.currentTimeMillis())
    }
    override suspend fun seekTo(sec: Double) = CastNet.media(id, "SEEK") { put("currentTime", sec.coerceAtLeast(0.0)) }
    override suspend fun seekBy(sec: Double) { val s = last ?: status() ?: return; seekTo(s.posNow() + sec) }
    override suspend fun volumeBy(steps: Int) { val v = (last ?: status())?.volume ?: return; setVolume((v + steps * 5).coerceIn(0, 100)) }
    override suspend fun setVolume(v: Int) = CastNet.setVolume(id, level = v / 100.0)
    override suspend fun setMuted(m: Boolean) = CastNet.setVolume(id, muted = m)
    override suspend fun stop() = CastNet.stopApp(id)
}

/** A DLNA TV we sent a video to ([Dlna.current]). */
class DlnaRemote(private val ctx: Context) : Remote {
    override val kind = "dlna"
    override val name: String get() = Dlna.current?.name ?: ctx.getString(R.string.main_tv)
    private suspend fun io(f: () -> Unit) = withContext(Dispatchers.IO) { f() }

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        if (Dlna.current == null) return@withContext null
        // Remote and the notification ask at the same time: a fresh read is taken from memory
        val s = Dlna.last?.takeIf { System.currentTimeMillis() - it.at < 1200 } ?: Dlna.status(ctx) ?: return@withContext null
        val note = when (s.state) {
            null -> ctx.getString(R.string.main_note_tv_not_responding); "TRANSITIONING" -> ctx.getString(R.string.main_loading)
            "STOPPED" -> ctx.getString(R.string.main_note_stopped); "NO_MEDIA_PRESENT" -> ctx.getString(R.string.main_note_nothing_playing); else -> null
        }
        RemoteState(s.title ?: "", s.pos, s.dur, s.playing, s.state == "TRANSITIONING", s.volume, s.muted, note, at = s.at)
    }
    override suspend fun setPlaying(play: Boolean) = io { Dlna.setPlaying(play) }
    override suspend fun seekTo(sec: Double) = io { Dlna.seekTo(sec) }
    override suspend fun seekBy(sec: Double) = io { Dlna.seekBy(sec) }
    override suspend fun volumeBy(steps: Int) = io { Dlna.volumeBy(steps * 2) }
    override suspend fun setVolume(v: Int) = io { Dlna.setVolume(v) }
    override suspend fun setMuted(m: Boolean) = io { Dlna.setMuted(m) }
    override suspend fun stop() = io { Dlna.stop() }
}

/** What Remote controls: the last video sent or a choice made in Remote; without a choice Chromecast → DLNA → screen. */
object Pilot {
    /** "screen" | "cast" | "dlna" */
    val focus = mutableStateOf<String?>(null)
    /** Chromecast playing according to the agent (server key, entry from /api/cast/status), when the phone has no Cast SDK session */
    val agentCast = mutableStateOf<Pair<String, JSONObject>?>(null)

    /** Asks the agents (first the screen, then the selected server, then the rest) whether anything is playing on any Chromecast. */
    suspend fun refreshAgentCast(ctx: Context) = withContext(Dispatchers.IO) {
        val keys = (listOfNotNull(Servers.screenKey(ctx), Servers.activeKey(ctx)) + Servers.all(ctx).map { it.key }).distinct()
        for (k in keys) {
            val api = Api.of(ctx, k) ?: continue
            val o = runCatching { api.castStatus() }.getOrNull() ?: continue
            agentCast.value = if (o.optBoolean("active")) k to o else null
            return@withContext
        }
        agentCast.value = null
    }

    /** Available targets in default order. UI thread (Cast session). */
    fun available(ctx: Context): List<String> = buildList {
        if (Cast.session(ctx) != null || CastNet.playing().isNotEmpty() || agentCast.value != null) add("cast")
        if (Dlna.current != null || Announce.foreignDlna() != null) add("dlna")
        if (Servers.screenKey(ctx) != null) add("screen")
    }

    fun effective(ctx: Context): String? = available(ctx).let { a -> focus.value?.takeIf { it in a } ?: a.firstOrNull() }

    fun remote(ctx: Context, kind: String?): Remote? = when (kind) {
        "cast" -> if (Cast.session(ctx) != null) CastRemote(ctx.applicationContext)
            else CastNet.playing().firstOrNull()?.let { DirectCastRemote(it.device.id) }
            ?: agentCast.value?.let { (k, o) -> Api.of(ctx, k)?.let { AgentCastRemote(ctx.applicationContext, it, o.optString("id")) } }
        "dlna" -> {
            // a video on the TV was started by another remote (announcement): we take over control
            if (Dlna.current == null) Announce.foreignDlna()?.let { Dlna.adopt(it) }
            DlnaRemote(ctx.applicationContext)
        }
        "screen" -> Api.of(ctx, Servers.screenKey(ctx))?.let { MpvRemote(ctx.applicationContext, it) }
        else -> null
    }

    /** Chromecast name for the chip: from the Cast SDK session or from the agent. */
    fun castName(ctx: Context): String = Cast.deviceName(ctx) ?: CastNet.playing().firstOrNull()?.device?.name ?: agentCast.value?.second?.optString("device")?.takeIf { it.isNotBlank() } ?: "Chromecast"

    /**
     * Video sent to `kind`: Remote shows that target, and the target gets a notification (a Chromecast with the Cast SDK has its own
     * and then ours disappears by itself).
     */
    fun started(ctx: Context, kind: String) {
        focus.value = kind
        RemoteSessionService.start(ctx.applicationContext, kind)
    }
}

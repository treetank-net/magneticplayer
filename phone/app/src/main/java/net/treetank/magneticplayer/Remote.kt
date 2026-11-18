package net.treetank.magneticplayer

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Stan zdalnego odtwarzacza, wspólny dla Pilota i powiadomienia. Czasy w sekundach. */
data class RemoteState(
    val title: String, val pos: Double, val dur: Double, val playing: Boolean, val buffering: Boolean = false,
    /** 0–100; null = głośności nie da się sterować (np. Chromecast ze stałą głośnością) */
    val volume: Int? = null, val muted: Boolean = false,
    /** dopisek pod tytułem: „film z telefonu”, „telewizor nie odpowiada”… */
    val note: String? = null,
    /** pełny stan mpv (ścieżki, napisy, pełny ekran) */
    val mpv: JSONObject? = null,
    val at: Long = System.currentTimeMillis(),
) {
    /** pozycja teraz: między odczytami płynie sama */
    fun posNow() = if (playing && !buffering) (pos + (System.currentTimeMillis() - at) / 1000.0).let { if (dur > 0) it.coerceAtMost(dur) else it } else pos
}

/**
 * Zdalny odtwarzacz: mpv na ekranie serwera, Chromecast albo telewizor DLNA. Ten sam Pilot i to samo powiadomienie
 * dla wszystkich. Metody wolno wołać z dowolnego wątku (każda sama wybiera wątek).
 */
interface Remote {
    val kind: String  // "screen" | "cast" | "dlna"
    val name: String
    /** null = nic nie gra (albo urządzenie zniknęło) */
    suspend fun status(): RemoteState?
    suspend fun setPlaying(play: Boolean)
    suspend fun seekTo(sec: Double)
    suspend fun seekBy(sec: Double)
    /** krok głośności (±1 = przycisk telefonu) */
    suspend fun volumeBy(steps: Int)
    suspend fun setVolume(v: Int)
    suspend fun setMuted(m: Boolean)
    suspend fun stop()
}

/** mpv na serwerze z ekranem (polecenia /api/player/cmd). */
class MpvRemote(private val ctx: Context, val api: Api) : Remote {
    override val kind = "screen"
    override val name: String get() = api.srv.name
    @Volatile private var last: RemoteState? = null

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        val o = api.playerStatus()
        if (!o.optBoolean("running")) return@withContext null.also { last = null }
        fun d(k: String) = o.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0
        val pos = d("pos")
        // film otwarty z innego serwera (URL z biletem): mpv nie zapamięta pozycji, więc zapisuje ją telefon
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

/** Chromecast (sesja Cast SDK; ta sama robi powiadomienie i głośność poza apką). */
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
 * Chromecast, na którym gra coś, czego ten telefon nie puścił przez Cast SDK (strona, inny telefon, YouTube…):
 * stan i polecenia idą przez agenta, który słucha wszystkich Chromecastów w sieci (/api/cast/status).
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
            note = app ?: if (!o.optBoolean("mine")) ctx.getString(R.string.main_note_other_device) else null).also { last = it }
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

/** Chromecast widziany bezpośrednio z telefonu ([CastNet], protokół Cast v2): działa bez agenta i bez Cast SDK. */
class DirectCastRemote(private val id: String) : Remote {
    override val kind = "cast"
    override val name: String get() = CastNet.devices[id]?.name ?: "Chromecast"
    @Volatile private var last: RemoteState? = null

    override suspend fun status(): RemoteState? {
        val s = CastNet.statusOf(id)?.takeIf { it.active } ?: return null.also { last = null }
        val app = s.app?.takeIf { it != "Default Media Receiver" }
        return RemoteState(s.title ?: "", s.pos, s.dur, s.state == "PLAYING", s.state == "BUFFERING",
            volume = if (s.fixedVolume) null else (s.volume * 100).toInt(), muted = s.muted, note = app, at = s.at).also { last = it }
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

/** Telewizor DLNA, do którego wysłaliśmy film ([Dlna.current]). */
class DlnaRemote(private val ctx: Context) : Remote {
    override val kind = "dlna"
    override val name: String get() = Dlna.current?.name ?: ctx.getString(R.string.main_tv)
    private suspend fun io(f: () -> Unit) = withContext(Dispatchers.IO) { f() }

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        if (Dlna.current == null) return@withContext null
        // Pilot i powiadomienie pytają naraz: świeży odczyt bierzemy z pamięci
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

/** Czym steruje Pilot: ostatnio wysłany film albo wybór na Pilocie; bez wyboru Chromecast → DLNA → ekran. */
object Pilot {
    /** "screen" | "cast" | "dlna" */
    val focus = mutableStateOf<String?>(null)
    /** Chromecast grający według agenta (klucz serwera, wpis z /api/cast/status), gdy telefon nie ma sesji Cast SDK */
    val agentCast = mutableStateOf<Pair<String, JSONObject>?>(null)

    /** Pyta agentów (najpierw ekran, potem wybrany serwer, potem reszta), czy na jakimś Chromecaście coś gra. */
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

    /** Dostępne cele w kolejności domyślnej. Wątek UI (sesja Cast). */
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
            // film na telewizorze puścił inny pilot (rozgłoszenie): przejmujemy sterowanie
            if (Dlna.current == null) Announce.foreignDlna()?.let { Dlna.adopt(it) }
            DlnaRemote(ctx.applicationContext)
        }
        "screen" -> Api.of(ctx, Servers.screenKey(ctx))?.let { MpvRemote(ctx.applicationContext, it) }
        else -> null
    }

    /** Nazwa Chromecasta do chipa: z sesji Cast SDK albo od agenta. */
    fun castName(ctx: Context): String = Cast.deviceName(ctx) ?: CastNet.playing().firstOrNull()?.device?.name ?: agentCast.value?.second?.optString("device")?.takeIf { it.isNotBlank() } ?: "Chromecast"

    /**
     * Wysłano film na `kind`: Pilot pokazuje ten cel, a cel dostaje powiadomienie (Chromecast z Cast SDK ma swoje
     * i wtedy nasze samo znika).
     */
    fun started(ctx: Context, kind: String) {
        focus.value = kind
        RemoteSessionService.start(ctx.applicationContext, kind)
    }
}

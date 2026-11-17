package eu.jm.magneticplayer

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
                volume = s.volume, muted = s.muted, note = if (s.viaPhone) "film z telefonu" else null)
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

/** Telewizor DLNA, do którego wysłaliśmy film ([Dlna.current]). */
class DlnaRemote(private val ctx: Context) : Remote {
    override val kind = "dlna"
    override val name: String get() = Dlna.current?.name ?: "Telewizor"
    private suspend fun io(f: () -> Unit) = withContext(Dispatchers.IO) { f() }

    override suspend fun status(): RemoteState? = withContext(Dispatchers.IO) {
        if (Dlna.current == null) return@withContext null
        // Pilot i powiadomienie pytają naraz: świeży odczyt bierzemy z pamięci
        val s = Dlna.last?.takeIf { System.currentTimeMillis() - it.at < 1200 } ?: Dlna.status(ctx) ?: return@withContext null
        val note = when (s.state) {
            null -> "telewizor nie odpowiada"; "TRANSITIONING" -> "wczytuje…"; "STOPPED" -> "zatrzymany"
            "NO_MEDIA_PRESENT" -> "nic nie gra"; else -> null
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

    /** Dostępne cele w kolejności domyślnej. Wątek UI (sesja Cast). */
    fun available(ctx: Context): List<String> = buildList {
        if (Cast.session(ctx) != null) add("cast")
        if (Dlna.current != null) add("dlna")
        if (Servers.screenKey(ctx) != null) add("screen")
    }

    fun effective(ctx: Context): String? = available(ctx).let { a -> focus.value?.takeIf { it in a } ?: a.firstOrNull() }

    fun remote(ctx: Context, kind: String?): Remote? = when (kind) {
        "cast" -> CastRemote(ctx.applicationContext)
        "dlna" -> DlnaRemote(ctx.applicationContext)
        "screen" -> Api.of(ctx, Servers.screenKey(ctx))?.let { MpvRemote(ctx.applicationContext, it) }
        else -> null
    }

    /** Wysłano film na `kind`: Pilot pokazuje ten cel, a mpv i DLNA dostają powiadomienie (Chromecast ma swoje z Cast SDK). */
    fun started(ctx: Context, kind: String) {
        focus.value = kind
        if (kind != "cast") RemoteSessionService.start(ctx.applicationContext)
    }
}

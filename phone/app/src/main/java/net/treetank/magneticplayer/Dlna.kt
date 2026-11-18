package net.treetank.magneticplayer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Telewizor DLNA (UPnP MediaRenderer): adresy sterowania AVTransport i (jeśli jest) RenderingControl. */
data class DlnaTarget(
    val id: String, val name: String, val model: String, val avTransport: String, val rendering: String?,
    val samsung: Boolean,
)

/**
 * Telewizory DLNA (Samsung, LG, Sony…): SSDP M-SEARCH w LAN-ie i sterowanie SOAP (SetAVTransportURI, Play, Seek…).
 * Samsung bierze napisy SRT z `sec:CaptionInfoEx` w metadanych i z nagłówka `CaptionInfo.sec` odpowiedzi z filmem
 * (podaje go agent przy `&cap=`, a telefon sam przy udostępnianiu).
 */
object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** ostatnio znalezione (do listy w odtwarzaczu, gdzie nie ma czasu na szukanie) */
    val found = ConcurrentHashMap<String, DlnaTarget>()

    /** co gra przez nas: telewizor, film, od kiedy */
    @Volatile var current: DlnaTarget? = null
    @Volatile private var currentRef: MediaRef? = null
    @Volatile var currentTitle: String? = null

    /** Szuka telewizorów przez `timeoutMs`. Poza wątkiem UI. */
    fun discover(timeoutMs: Int = 3000): List<DlnaTarget> {
        val locations = linkedSetOf<String>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    String(p.data, 0, p.length).lines()
                        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.let { locations += it }
                }
            }
        }
        val out = locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.id }
        out.forEach { found[it.id] = it }
        return out
    }

    private fun tag(xml: String, name: String) =
        Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    private fun describe(location: String): DlnaTarget? {
        val xml = httpGet(location)
        val base = tag(xml, "URLBase")?.ifEmpty { null } ?: location
        fun control(type: String): String? {
            val svc = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                .map { it.groupValues[1] }.firstOrNull { tag(it, "serviceType")?.startsWith(type.dropLast(1)) == true }
            return svc?.let { tag(it, "controlURL") }?.let { URL(URL(base), it).toString() }
        }
        val avt = control(AVT) ?: return null
        val maker = tag(xml, "manufacturer").orEmpty()
        return DlnaTarget(
            id = tag(xml, "UDN") ?: location,
            name = unescape(tag(xml, "friendlyName") ?: res?.getString(R.string.main_tv) ?: "TV"),
            model = listOf(maker, tag(xml, "modelName").orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            avTransport = avt, rendering = control(RC), samsung = maker.contains("samsung", ignoreCase = true),
        )
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000; c.readTimeout = 3000
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private fun soap(url: String, service: String, action: String, vararg args: Pair<String, String>): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$service">""" +
            args.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 8000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull()
                val why = err?.let { tag(it, "errorDescription") ?: tag(it, "errorCode") }
                throw ApiException(why?.let { tr(R.string.main_tv_rejected, "The TV rejected %1\$s: %2\$s", action, it) }
                    ?: tr(R.string.main_tv_rejected_http, "The TV rejected %1\$s (HTTP %2\$d)", action, c.responseCode))
            }
            return c.inputStream.bufferedReader().readText()
        } catch (e: IOException) {
            throw ApiException(tr(R.string.main_tv_no_answer, "The TV isn't responding (%1\$s)", "${e.message}"))
        } finally {
            c.disconnect()
        }
    }

    /** Przewijanie po bajtach, bez konwersji, strumień (jak w agencie). */
    const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        else -> "video/x-matroska"
    }

    private fun hms(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    private fun secs(t: String?): Double = t?.split(':')?.takeIf { it.size == 3 }
        ?.let { (h, m, s) -> (h.toDoubleOrNull() ?: 0.0) * 3600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0) } ?: 0.0

    /** Wysyła film i włącza go od `startSec`. Poza wątkiem UI. */
    fun play(ctx: Context, t: DlnaTarget, ref: MediaRef, src: MediaSrc, startSec: Double) {
        res = ctx.applicationContext
        val srt = src.subs.firstOrNull { it.name.endsWith(".srt", ignoreCase = true) }
        // agent poda CaptionInfo.sec, gdy wie, które napisy (telefon wybiera pierwsze sam)
        val url = if (srt != null && t.samsung && !src.viaPhone) "${src.url}&cap=${android.net.Uri.encode(srt.name)}" else src.url
        // Samsung nie zna video/x-matroska; bilet z serwera podaje typ tylko dla wersji dla Chromecasta
        val type = (src.contentType ?: mime(ref.title)).let { if (it == "video/x-matroska") "video/x-mkv" else it }
        val didl = buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/">""")
            append("""<item id="0" parentID="-1" restricted="1"><dc:title>${esc(src.title)}</dc:title>""")
            append("<upnp:class>object.item.videoItem.movie</upnp:class>")
            append("""<res protocolInfo="http-get:*:$type:$DLNA_FEATURES">${esc(url)}</res>""")
            if (srt != null) {
                append("""<res protocolInfo="http-get:*:text/srt:*">${esc(srt.url)}</res>""")
                append("""<sec:CaptionInfoEx sec:type="srt">${esc(srt.url)}</sec:CaptionInfoEx>""")
            }
            append("</item></DIDL-Lite>")
        }
        runCatching { soap(t.avTransport, AVT, "Stop", "InstanceID" to "0") }
        soap(t.avTransport, AVT, "SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl)
        soap(t.avTransport, AVT, "Play", "InstanceID" to "0", "Speed" to "1")
        current = t; currentRef = ref; currentTitle = src.title
        remember(ctx, t, ref, src.title, url)
        Pilot.started(ctx, "dlna")  // Pilot na telewizor, powiadomienie i głośność poza apką
        if (startSec > 5) {
            // przewinąć da się dopiero, gdy telewizor zacznie grać; tuż po starcie Samsung potrafi Seek zignorować,
            // więc sprawdzamy pozycję i próbujemy jeszcze raz
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && state(t) != "PLAYING") Thread.sleep(700)
            for (attempt in 1..4) {
                runCatching { soap(t.avTransport, AVT, "Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to hms(startSec)) }
                Thread.sleep(2_000)
                val pos = runCatching { secs(tag(soap(t.avTransport, AVT, "GetPositionInfo", "InstanceID" to "0"), "RelTime")) }.getOrNull()
                android.util.Log.i("MpDlna", "start: seek do ${hms(startSec)}, próba $attempt, pozycja ${pos?.let(::hms)}")
                if (pos != null && pos >= startSec - 15) break
            }
        }
    }

    private fun state(t: DlnaTarget) = runCatching {
        tag(soap(t.avTransport, AVT, "GetTransportInfo", "InstanceID" to "0"), "CurrentTransportState")
    }.getOrNull()

    data class Status(
        val device: String, val title: String?, val pos: Double, val dur: Double, val state: String?,
        val volume: Int? = null, val muted: Boolean = false, val at: Long = System.currentTimeMillis(),
    ) {
        val playing get() = state == "PLAYING"
    }

    /** ostatni odczyt (dla sesji multimedialnej i karty, żeby nie pytać telewizora dwa razy) */
    @Volatile var last: Status? = null
        private set

    /** Stan bieżącego telewizora (zapisuje też pozycję filmu). Poza wątkiem UI. */
    fun status(ctx: Context): Status? {
        res = ctx.applicationContext
        val t = current ?: return null
        val st = state(t) ?: return Status(t.name, currentTitle, 0.0, 0.0, null).also { last = it }
        val pi = runCatching { soap(t.avTransport, AVT, "GetPositionInfo", "InstanceID" to "0") }.getOrNull()
        val pos = secs(pi?.let { tag(it, "RelTime") })
        val dur = secs(pi?.let { tag(it, "TrackDuration") })
        currentRef?.let { r ->
            if (dur > 0 && pos > 5) Settings(ctx).setPosition(r.key(), if (pos > dur - 60) 0 else (pos * 1000).toLong())
        }
        val vol = t.rendering?.let { rc ->
            runCatching { tag(soap(rc, RC, "GetVolume", "InstanceID" to "0", "Channel" to "Master"), "CurrentVolume")?.toIntOrNull() }.getOrNull()
        }
        val muted = t.rendering?.let { rc ->
            runCatching { tag(soap(rc, RC, "GetMute", "InstanceID" to "0", "Channel" to "Master"), "CurrentMute") == "1" }.getOrNull()
        } ?: false
        return Status(t.name, currentTitle, pos, dur, st, vol, muted).also { last = it }
    }

    fun toggle() {
        val t = current ?: return
        setPlaying(state(t) != "PLAYING")
    }

    fun setPlaying(play: Boolean) {
        val t = current ?: return
        if (play) soap(t.avTransport, AVT, "Play", "InstanceID" to "0", "Speed" to "1")
        else soap(t.avTransport, AVT, "Pause", "InstanceID" to "0")
        last = last?.copy(state = if (play) "PLAYING" else "PAUSED_PLAYBACK")
    }

    @Synchronized
    fun seekTo(sec: Double) {
        val t = current ?: return
        seekTarget = sec.coerceAtLeast(0.0); seekAt = System.currentTimeMillis()
        last = last?.copy(pos = seekTarget, at = seekAt)
        soap(t.avTransport, AVT, "Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to hms(seekTarget))
    }

    /** Głośność 0–100; false, gdy telewizor nie daje nią sterować. */
    fun setVolume(v: Int): Boolean {
        val rc = current?.rendering ?: return false
        val x = v.coerceIn(0, 100)
        soap(rc, RC, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "$x")
        last = last?.copy(volume = x)
        return true
    }

    fun setMuted(m: Boolean) {
        val rc = current?.rendering ?: return
        soap(rc, RC, "SetMute", "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (m) "1" else "0")
        last = last?.copy(muted = m)
    }

    // kolejne kliknięcia w ciągu kilku sekund liczymy od poprzedniego celu: telewizor podaje pozycję z opóźnieniem
    private var seekTarget = 0.0
    private var seekAt = 0L

    @Synchronized
    fun seekBy(deltaSec: Double) {
        val t = current ?: return
        val now = System.currentTimeMillis()
        val from = if (now - seekAt < 4_000) seekTarget
            else secs(tag(soap(t.avTransport, AVT, "GetPositionInfo", "InstanceID" to "0"), "RelTime"))
        val target = (from + deltaSec).coerceAtLeast(0.0)
        seekTarget = target; seekAt = now
        last = last?.copy(pos = target, at = now)
        soap(t.avTransport, AVT, "Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to hms(target))
    }

    fun volumeBy(delta: Int): Boolean {
        val t = current ?: return false
        val rc = t.rendering ?: return true
        runCatching {
            val v = last?.volume
                ?: tag(soap(rc, RC, "GetVolume", "InstanceID" to "0", "Channel" to "Master"), "CurrentVolume")?.toIntOrNull() ?: return true
            setVolume(v + delta)
        }
        return true
    }

    /** Jak sterować tym telewizorem bez nas (do rozgłoszenia: [Announce]). */
    fun ctl(t: DlnaTarget): org.json.JSONObject = org.json.JSONObject().put("avt", t.avTransport).put("rc", t.rendering ?: "")
        .put("samsung", t.samsung).put("model", t.model)

    /** Przejmuje sterowanie telewizorem, na który film puścił inny pilot (z rozgłoszenia). */
    fun adopt(e: Announce.Entry) {
        val c = e.ctl
        val t = DlnaTarget(e.targetId, e.targetName, c.optString("model"), c.getString("avt"),
            c.optString("rc").takeIf { it.isNotEmpty() }, c.optBoolean("samsung"))
        current = t; currentRef = null; currentTitle = e.title; last = null
        found[t.id] = t
    }

    /** Stany, w których na telewizorze nic nie gra. */
    val IDLE_STATES = setOf("STOPPED", "NO_MEDIA_PRESENT")

    /** Zapomina telewizor bez wysyłania mu Stop (film się skończył albo zatrzymał go ktoś inny). */
    fun forget() {
        val t = current ?: return
        Announce.stopped("dlna", t.id)
        current = null; currentRef = null; currentTitle = null; last = null
        app?.let { prefs(it).edit().remove("dlna_session").apply() }
    }

    fun stop() {
        val t = current ?: return
        Announce.stopped("dlna", t.id)
        runCatching { soap(t.avTransport, AVT, "Stop", "InstanceID" to "0") }
        current = null; currentRef = null; currentTitle = null; last = null
        app?.let { prefs(it).edit().remove("dlna_session").apply() }
    }

    // Co wysłaliśmy, zapisane na dysku: po ubiciu apki (np. aktualizacja) telewizor gra dalej, a Pilot ma go odzyskać.
    @Volatile private var app: Context? = null

    /** do komunikatów w języku telefonu (pierwszy play/status/restore); bez niego angielski zapas */
    @Volatile private var res: Context? = null
    private fun tr(id: Int, fallback: String, vararg args: Any): String = res?.getString(id, *args) ?: fallback.format(*args)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dlna", Context.MODE_PRIVATE)

    private fun remember(ctx: Context, t: DlnaTarget, ref: MediaRef, title: String, url: String) {
        app = ctx.applicationContext
        val o = org.json.JSONObject().put("id", t.id).put("name", t.name).put("model", t.model).put("avt", t.avTransport)
            .put("rc", t.rendering).put("samsung", t.samsung).put("ref", ref.encode()).put("title", title).put("url", url)
        prefs(ctx).edit().putString("dlna_session", o.toString()).apply()
    }

    /** Przy starcie apki: jeśli telewizor dalej gra nasz film, wracamy do sterowania nim. Poza wątkiem UI. */
    fun restore(ctx: Context): Boolean {
        res = ctx.applicationContext
        if (current != null) return true
        app = ctx.applicationContext
        val o = prefs(ctx).getString("dlna_session", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return false
        val t = DlnaTarget(o.getString("id"), o.getString("name"), o.optString("model"), o.getString("avt"),
            o.optString("rc").takeIf { it.isNotEmpty() && it != "null" }, o.optBoolean("samsung"))
        val st = state(t)
        val uri = runCatching { tag(soap(t.avTransport, AVT, "GetMediaInfo", "InstanceID" to "0"), "CurrentURI") }.getOrNull()?.let(::unescape)
        val ours = uri == null || uri == o.getString("url")
        if (st !in setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING") || !ours) {
            if (st != null) prefs(ctx).edit().remove("dlna_session").apply()  // telewizor odpowiada, ale gra co innego
            return false
        }
        current = t; currentTitle = o.optString("title")
        currentRef = runCatching { MediaRef.decode(o.getString("ref")) }.getOrNull()
        found[t.id] = t
        return true
    }
}

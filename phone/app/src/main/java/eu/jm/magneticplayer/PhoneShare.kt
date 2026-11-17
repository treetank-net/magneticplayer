package eu.jm.magneticplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Telefon jako serwer plików dla ekranu (Chromecast, mpv na serwerze bez tego filmu): film pobrany na telefon
 * (MediaCache) i jego napisy po HTTP w LAN-ie. Jeden film naraz, adres z losowym sekretem w ścieżce, Range, CORS.
 * Działa, dopóki trwa [ShareService] (powiadomienie z „Zatrzymaj”, sam kończy po 20 min bez zapytań).
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PhoneShare {
    private class Shared(val key: String, val size: Long, val name: String, val mime: String, val subs: List<LocalSub>)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var shared: Shared? = null
    @Volatile private var secret = ""
    @Volatile private var lastHit = 0L
    private val active = AtomicInteger()

    /** Cały film leży na telefonie (pobrany albo w całości w buforze). */
    fun hasWhole(ctx: Context, key: String): Boolean {
        val i = OfflineStore.get(ctx, key) ?: return false
        // nie ufamy samemu „complete”: liczy się to, co faktycznie jest w buforze
        return i.size > 0 && runCatching { MediaCache.get(ctx).getCachedBytes(key, 0, i.size) }.getOrDefault(0) >= i.size
    }

    /** Adres telefonu w Wi-Fi (albo Ethernecie), null gdy ich nie ma. */
    fun lanIp(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    /** Udostępnia film z telefonu i zwraca adresy dla ekranu. Poza wątkiem UI. */
    @Synchronized
    fun start(ctx: Context, ref: MediaRef): MediaSrc {
        val app = ctx.applicationContext
        val item = OfflineStore.get(app, ref.key())?.takeIf { hasWhole(app, ref.key()) }
            ?: throw ApiException("Tego filmu nie ma w całości na telefonie")
        val ip = lanIp(app) ?: throw ApiException("Telefon nie jest w Wi-Fi, więc ekran go nie zobaczy")
        val size = item.size.takeIf { it > 0 } ?: throw ApiException("Nie znam rozmiaru pliku")
        val subs = item.subs.filter { File(it.file).exists() }
        shared = Shared(item.key, size, ref.title, mime(ref.title), subs)
        val sock = server ?: ServerSocket(0).also { s ->
            secret = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            server = s
            thread(name = "phone-share", isDaemon = true) { acceptLoop(app, s) }
        }
        lastHit = System.currentTimeMillis()
        ShareService.start(app, ref.title)
        val base = "http://$ip:${sock.localPort}/$secret"
        return MediaSrc("$base/v/${Uri.encode(ref.title)}", ref.title,
            subs.mapIndexed { i, s ->
                SubSrc(s.name, s.lang, "$base/s/$i/${Uri.encode(s.name)}", if (s.ext in setOf("srt", "vtt")) "$base/t/$i.vtt" else null)
            }, true)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        shared = null
    }

    /** Ile ms nikt nie pobiera (0, gdy trwa jakieś połączenie). */
    fun idleMs() = if (active.get() > 0) 0 else System.currentTimeMillis() - lastHit

    private fun acceptLoop(app: Context, s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { handle(app, c) }
        }
    }

    private fun readLine(inp: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            if (b.length > 8192) throw IOException("za długi nagłówek")
            b.append(c.toChar())
        }
    }

    private fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val reason = mapOf(200 to "OK", 204 to "No Content", 206 to "Partial Content", 404 to "Not Found",
            405 to "Method Not Allowed", 416 to "Range Not Satisfiable")[code] ?: "Error"
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        val all = headers + listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
            "Connection" to "close",
        )
        all.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun handle(app: Context, s: Socket) = s.use {
        active.incrementAndGet()
        try {
            s.soTimeout = 60_000
            s.tcpNoDelay = true
            val inp = s.getInputStream().buffered()
            val line = readLine(inp) ?: return@use
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(inp) ?: return@use
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val parts = line.split(' ')
            val method = parts.getOrNull(0) ?: return@use
            val path = parts.getOrNull(1)?.substringBefore('?') ?: return@use
            val out = s.getOutputStream().buffered(64 * 1024)
            android.util.Log.i("PhoneShare", "${s.inetAddress.hostAddress} $method ${path.replace(secret, "…")} range=${headers["range"]}")
            route(app, method, path, headers, out)
            out.flush()
        } catch (e: IOException) {
            // widz przewinął albo zamknął: odbiornik zrywa połączenie, to normalne
            android.util.Log.i("PhoneShare", "przerwane: $e")
        } finally {
            lastHit = System.currentTimeMillis()
            active.decrementAndGet()
        }
    }

    private fun route(app: Context, method: String, path: String, h: Map<String, String>, out: OutputStream) {
        if (method == "OPTIONS") {
            return head(out, 204, listOf("Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range", "Content-Length" to "0"))
        }
        if (method != "GET" && method != "HEAD") return head(out, 405, listOf("Content-Length" to "0"))
        val seg = path.trim('/').split('/')
        val sh = shared
        if (sh == null || seg.size < 2 || seg[0] != secret) return head(out, 404, listOf("Content-Length" to "0"))
        when (seg[1]) {
            "v" -> {
                // Samsung (DLNA) pyta o napisy nagłówkiem; dostaje pierwsze SRT
                val srt = sh.subs.indexOfFirst { it.ext == "srt" }
                val cap = if (h["getcaptioninfo.sec"] != null && srt >= 0 && h["host"] != null)
                    "http://${h["host"]}/$secret/s/$srt/${Uri.encode(sh.subs[srt].name)}" else null
                val dlna = h["getcontentfeatures.dlna.org"] != null || h["user-agent"].orEmpty().contains("dlna", true)
                video(app, sh, method, h["range"], out, cap, dlna)
            }
            "s", "t" -> {
                val i = seg.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
                val sub = i?.let { sh.subs.getOrNull(it) } ?: return head(out, 404, listOf("Content-Length" to "0"))
                val raw = File(sub.file).readBytes()
                val (body, type) = if (seg[1] == "t") toVtt(raw).toByteArray() to "text/vtt; charset=utf-8"
                    else raw to when (sub.ext) { "ass", "ssa" -> "text/x-ssa"; "vtt" -> "text/vtt"; else -> "application/x-subrip" }
                head(out, 200, listOf("Content-Type" to type, "Content-Length" to "${body.size}"))
                if (method == "GET") out.write(body)
            }
            else -> head(out, 404, listOf("Content-Length" to "0"))
        }
    }

    private fun video(app: Context, sh: Shared, method: String, range: String?, out: OutputStream, caption: String?, dlna: Boolean) {
        val size = sh.size
        var start = 0L
        var end = size - 1
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        if (m != null) {
            val (a, b) = m.destructured
            if (a.isEmpty() && b.isNotEmpty()) start = size - b.toLong()
            else if (a.isNotEmpty()) {
                start = a.toLong()
                if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
            }
        }
        if (start !in 0 until size || end < start) {
            return head(out, 416, listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
        }
        val len = end - start + 1
        head(out, if (m != null) 206 else 200, buildList {
            add("Content-Type" to if (dlna && sh.mime == "video/x-matroska") "video/x-mkv" else sh.mime)
            add("Content-Length" to "$len")
            add("Accept-Ranges" to "bytes")
            if (m != null) add("Content-Range" to "bytes $start-$end/$size")
            if (caption != null) add("CaptionInfo.sec" to caption)
            if (dlna) {
                add("transferMode.dlna.org" to "Streaming")
                add("contentFeatures.dlna.org" to Dlna.DLNA_FEATURES)
            }
        })
        if (method == "HEAD") return
        val cache = MediaCache.get(app)
        // tylko z dysku telefonu: bez źródła sieciowego brak kawałka kończy się błędem, a nie zerami
        val ds = CacheDataSource(MediaCache.get(app), null, FileDataSource(), null, 0, null)
        try {
            ds.open(DataSpec.Builder().setUri(Uri.parse("phone://share")).setKey(sh.key).setPosition(start).setLength(len).build())
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = ds.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                left -= n
                lastHit = System.currentTimeMillis()
            }
        } finally {
            ds.close()
        }
    }

    /** SRT (UTF-8 albo Windows-1250) → WebVTT dla Chromecasta. */
    fun toVtt(raw: ByteArray): String {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        }.getOrElse { String(raw, charset("windows-1250")) }.removePrefix("﻿").replace("\r\n", "\n")
        if (text.trimStart().startsWith("WEBVTT")) return text
        return "WEBVTT\n\n" + text.replace(Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
    }
}

/** Trzyma udostępnianie z telefonu przy życiu (Wi-Fi i procesor nie zasypiają przy zgaszonym ekranie). */
class ShareService : Service() {
    companion object {
        private const val CHANNEL = "share"
        private const val NOTIF_ID = 43
        private const val IDLE_MS = 20 * 60_000L

        fun start(ctx: Context, title: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ShareService::class.java).putExtra("title", title))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShareService::class.java))
        }
    }

    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (PhoneShare.idleMs() > IDLE_MS) stopSelf() else handler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Udostępnianie z telefonu", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 0, Intent(this, ShareService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", TAB_REMOTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Film z telefonu na ekranie")
            .setContentText(intent?.getStringExtra("title") ?: "")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Zatrzymaj", stopPi)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magneticplayer:share")
                .apply { acquire(6 * 3600_000L) }
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "magneticplayer:share").apply { acquire() }
        }
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 60_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        PhoneShare.stop()
        runCatching { wifi?.release() }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }
}

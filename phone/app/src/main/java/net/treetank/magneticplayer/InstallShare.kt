package net.treetank.magneticplayer

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The phone as an installer source (no other server needed; the new computer downloads dependencies from the internet): for the duration of the "Install on another computer" window
 * a small HTTP server on Wi-Fi with the same routes as the agent (mp_install.py):
 * `GET /i/<code>` (join.sh), `/i/<code>/ps` (join.ps1), `/i/<code>/bundle.tar.gz` (agent embedded in the APK by
 * build-apk.sh or a newer one from a release, AgentBundle) and `POST /api/install/join` (the new agent reports in with a pairing code, the phone pairs itself).
 * One-time code; after the report or closing the window the server goes away.
 */
object InstallShare {
    data class Joined(val host: String, val port: Int, val id: String, val name: String, val pairCode: String)

    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"  // as CODE_ALPHABET in mp_devices.py
    @Volatile private var server: ServerSocket? = null
    @Volatile var code = ""; private set
    @Volatile var state = "waiting"; private set  // waiting | downloading | joined
    @Volatile var joined: Joined? = null; private set
    private val fails = ArrayDeque<Long>()

    fun available(ctx: Context) = AgentBundle.available(ctx)

    /** Starts the server and returns the address `http://<phone>:<port>/i/<code>`; null if the phone is not on Wi-Fi. */
    @Synchronized
    fun start(ctx: Context): String? {
        stop()
        val ip = PhoneShare.lanIp(ctx) ?: return null
        val rnd = SecureRandom()
        code = (1..6).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        state = "waiting"; joined = null
        synchronized(fails) { fails.clear() }
        val ss = ServerSocket(0)
        server = ss
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "install-share") {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true) { runCatching { s.use { handle(app, it) } } }
            }
        }
        return "http://$ip:${ss.localPort}/i/$code"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun normalize(c: String?) = (c ?: "").uppercase().filter { it in ALPHABET }

    /** Wrong code: limit of 10 attempts per minute (as in the agent). */
    private fun codeOk(c: String?): Boolean {
        val now = System.currentTimeMillis()
        synchronized(fails) {
            while (fails.isNotEmpty() && now - fails.first() > 60_000) fails.removeFirst()
            if (fails.size < 10 && state != "joined" && normalize(c) == code && code.isNotEmpty()) return true
            fails.addLast(now)
            return false
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.soTimeout = 15_000
        val inp = BufferedInputStream(s.getInputStream())
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val out = s.getOutputStream()
        // Like the agent (mp_i18n): Polish for Accept-Language "pl…", otherwise English.
        val pl = headers["accept-language"]?.trim()?.lowercase()?.startsWith("pl") == true
        fun tr(en: String, pl_: String) = if (pl) pl_ else en
        val badCode = tr("wrong, used or expired install code", "zły, zużyty albo przeterminowany kod instalacji")
        val m = Regex("/i/([A-Za-z0-9-]{4,12})(/bundle\\.tar\\.gz|/ps)?").matchEntire(path)
        when {
            m != null && method == "GET" -> {
                if (!codeOk(m.groupValues[1])) return send(out, 403, "text/plain; charset=utf-8", "$badCode\n".toByteArray())
                val url = "http://${s.localAddress.hostAddress}:${s.localPort}/i/$code"
                when (m.groupValues[2]) {
                    "/bundle.tar.gz" -> send(out, 200, "application/gzip",
                        AgentBundle.open(ctx, "agent.tgz").use { it.readBytes() })  // ~80 KB; from the APK or a newer one from a release
                    "/ps" -> send(out, 200, "text/plain; charset=utf-8",
                        template(ctx, "join.ps1").replace("@URL@", url).toByteArray())
                    else -> {
                        val full = Regex("(^|&)full=1").containsMatchIn(query)
                        send(out, 200, "text/x-shellscript; charset=utf-8", template(ctx, "join.sh").replace("@URL@", url)
                            .replace("@LITE@", if (full) "0" else "\${LITE:-1}").toByteArray())
                    }
                }
                if (state == "waiting") state = "downloading"
            }
            path == "/api/install/join" && method == "POST" -> {
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8192) ?: 0
                val buf = ByteArray(len)
                var got = 0
                while (got < len) { val n = inp.read(buf, got, len - got); if (n < 0) break; got += n }
                val body = runCatching { JSONObject(String(buf, 0, got)) }.getOrNull()
                val pair = normalize(body?.optString("pair_code"))
                if (body == null || !codeOk(body.optString("code"))) return sendJson(out, 403, JSONObject().put("error", badCode))
                if (pair.length != 6 || body.optString("id").isEmpty()) return sendJson(out, 400, JSONObject().put("error", tr("missing pairing code or new server id", "brak kodu parowania albo id nowego serwera")))
                joined = Joined(s.inetAddress.hostAddress ?: "", body.optInt("port", 8765), body.optString("id").take(40),
                    body.optString("name").ifEmpty { s.inetAddress.hostAddress ?: ctx.getString(R.string.srv_server_fallback) }.take(60), pair)
                state = "joined"
                sendJson(out, 200, JSONObject().put("ok", true))
            }
            else -> sendJson(out, 404, JSONObject().put("error", tr("no such endpoint", "nie ma takiego endpointu")))
        }
    }

    /** Template from the APK without a BOM (join.ps1 has a BOM for PowerShell run from a file). */
    private fun template(ctx: Context, name: String) =
        AgentBundle.open(ctx, name).use { String(it.readBytes(), Charsets.UTF_8) }.removePrefix("﻿")

    private fun readHead(inp: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 16_384) {
            val c = inp.read()
            if (c < 0) return null
            b.append(c.toChar())
            if (b.endsWith("\r\n\r\n")) return b.substring(0, b.length - 4)
        }
        return null
    }

    private fun status(code: Int) = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code ${status(code)}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sendJson(out: OutputStream, code: Int, o: JSONObject) = send(out, code, "application/json", o.toString().toByteArray())
}

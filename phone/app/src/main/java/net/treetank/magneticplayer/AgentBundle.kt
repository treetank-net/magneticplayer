package net.treetank.magneticplayer

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream

/**
 * Agent bundle that the phone serves to a new computer (InstallShare): embedded in the APK (build-apk.sh) or a newer
 * from the latest GitHub release (release-ci.sh). From a release we take the `agent.json` manifest signed with ECDSA P-256
 * (public key in assets/agent/signing.pub from phone/tools/agent-signing.pub), and the bundle only with a matching sha256.
 * The version is the time of the last commit touching server/ (in the signed manifest), so an older bundle cannot be passed off
 * as a newer one. With no network, no signature or on error, the bundle from the APK is used.
 */
object AgentBundle {
    private const val BASE = "https://github.com/treetank-net/magneticplayer/releases/latest/download/"
    private const val MAX_TGZ = 20 shl 20
    private const val CHECK_EVERY = 6 * 3600_000L
    @Volatile private var lastCheck = 0L

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "agent")
    private fun ct(v: String?) = v?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull()
    private fun embeddedCt(ctx: Context) =
        runCatching { ct(ctx.assets.open("agent/version.txt").use { String(it.readBytes()) }) }.getOrNull() ?: 0L
    private fun cachedVersion(ctx: Context) = runCatching { File(dir(ctx), "version.txt").readText() }.getOrNull()

    /** Bundle from a release, when complete and newer than the one in the APK (after an app update the built-in one may be newer). */
    private fun useCached(ctx: Context): Boolean {
        val d = dir(ctx)
        return listOf("agent.tgz", "join.sh", "join.ps1").all { File(d, it).isFile } &&
            (ct(cachedVersion(ctx)) ?: -1L) > embeddedCt(ctx)
    }

    fun available(ctx: Context) = useCached(ctx) || runCatching { ctx.assets.open("agent/agent.tgz").close() }.isSuccess

    fun open(ctx: Context, name: String): InputStream =
        if (useCached(ctx)) File(dir(ctx), name).inputStream() else ctx.assets.open("agent/$name")

    /** Description for the install window, e.g. "agent 1.3.0 from release 18.11.2026" or "agent from the app". */
    fun label(ctx: Context): String {
        if (!useCached(ctx)) return ctx.getString(R.string.srv_agent_from_app)
        val p = cachedVersion(ctx)!!.trim().split(" ")
        val day = java.text.SimpleDateFormat("d.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date(p[0].toLong() * 1000))
        return ctx.getString(R.string.srv_agent_from_release, p.getOrNull(2) ?: "", day).replace("  ", " ")
    }

    /** Checks the GitHub release (at most every few hours, unless force). Returns true if a newer bundle was downloaded. */
    fun refresh(ctx: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY) return false
        lastCheck = now
        val pem = runCatching { ctx.assets.open("agent/signing.pub").use { String(it.readBytes()) } }.getOrNull() ?: return false
        val manifest = get(BASE + "agent.json", 64 shl 10)
        val sig = get(BASE + "agent.json.sig", 1 shl 10)
        if (!verify(pem, manifest, sig)) throw SecurityException("bad signature on the agent bundle manifest")
        val m = JSONObject(String(manifest))
        val newCt = m.getLong("ct")
        if (newCt <= maxOf(embeddedCt(ctx), ct(cachedVersion(ctx)) ?: 0L)) return false
        val tgz = get(BASE + "agent.tgz", MAX_TGZ)
        val sha = MessageDigest.getInstance("SHA-256").digest(tgz).joinToString("") { "%02x".format(it) }
        if (sha != m.getString("sha256")) throw SecurityException("agent bundle does not match the signed manifest")
        val files = untar(tgz, setOf("server/join.sh", "server/join.ps1"))
        if (files.size != 2) throw IllegalStateException("agent bundle is missing the join.* templates")
        // directory alongside first, then swap: InstallShare never sees half a bundle
        val d = dir(ctx)
        val tmp = File(d.parentFile, "agent.new").apply { deleteRecursively(); mkdirs() }
        File(tmp, "agent.tgz").writeBytes(tgz)
        files.forEach { (path, bytes) -> File(tmp, path.substringAfterLast('/')).writeBytes(bytes) }
        File(tmp, "version.txt").writeText("$newCt ${m.optString("commit").take(7)} ${m.optString("agent_version")}\n")
        d.deleteRecursively()
        if (!tmp.renameTo(d)) throw IllegalStateException("could not save the agent bundle")
        return true
    }

    private fun get(url: String, max: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.instanceFollowRedirects = true  // releases/latest/download → objects.githubusercontent.com (https → https)
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}: $url")
            c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IllegalStateException("file too large: $url")
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun verify(pem: String, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        val der = Base64.decode(pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""), Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(sig) }  // DER signature from openssl
    }.getOrDefault(false)

    /** Minimal tar reader (ustar from GNU tar): returns the selected files. */
    private fun untar(tgz: ByteArray, want: Set<String>): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        GZIPInputStream(ByteArrayInputStream(tgz)).use { z ->
            val hdr = ByteArray(512)
            while (true) {
                if (!readFully(z, hdr)) break
                if (hdr.all { it == 0.toByte() }) break
                fun str(off: Int, len: Int) = String(hdr, off, len, Charsets.UTF_8).substringBefore('\u0000')
                val prefix = str(345, 155)
                val name = (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
                val size = str(124, 12).trim().ifEmpty { "0" }.toLong(8)
                if (size > MAX_TGZ) break
                val body = ByteArray(size.toInt())
                if (!readFully(z, body)) break
                val pad = ((512 - size % 512) % 512).toInt()
                if (pad > 0 && !readFully(z, ByteArray(pad))) break
                if (name.removePrefix("./") in want) out[name.removePrefix("./")] = body
            }
        }
        return out
    }

    private fun readFully(inp: InputStream, b: ByteArray): Boolean {
        var got = 0
        while (got < b.size) {
            val n = inp.read(b, got, b.size - got)
            if (n < 0) return got == 0 && b.isEmpty()
            got += n
        }
        return true
    }
}

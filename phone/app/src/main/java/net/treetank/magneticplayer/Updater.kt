package net.treetank.magneticplayer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** App version published on the computer (phone/tools/publish.sh). */
data class AppUpdate(val code: Long, val name: String)

object Updater {
    fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /** Sends the app's own file (WhatsApp, Messenger, mail…) for installing on another phone. */
    fun shareApk(ctx: Context) {
        val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
        val name = runCatching {
            "MagneticPlayer-" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName + ".apk"
        }.getOrDefault("MagneticPlayer.apk")
        dir.listFiles()?.forEach { it.delete() }
        val f = java.io.File(dir, name)
        java.io.File(ctx.applicationInfo.sourceDir).copyTo(f, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.srv_share_apk_chooser)))
    }

    /** Newer version on the computer, or null. */
    fun check(ctx: Context, api: Api): AppUpdate? = runCatching {
        val j = JSONObject(api.get("/api/app/version"))
        val code = j.optLong("version_code")
        if (code > installedCode(ctx)) AppUpdate(code, j.optString("version_name")) else null
    }.getOrNull()

    /** Downloads the app from the computer straight into a PackageInstaller session; the system shows "Update?". */
    fun downloadAndInstall(ctx: Context, api: Api, onProgress: (Int) -> Unit) {
        val c = URL(api.base + "/app/MagneticPlayer.apk").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer ${api.srv.token}")
        val installer = ctx.packageManager.packageInstaller
        var sessionId = -1
        try {
            if (c.responseCode != 200) throw ApiException(ctx.getString(R.string.srv_apk_http, c.responseCode))
            val total = c.contentLengthLong
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (total > 0) params.setSize(total)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("MagneticPlayer.apk", 0, total).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, sessionId, Intent(ctx, UpdateReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            throw ApiException(ctx.getString(R.string.srv_update_download_failed, e.javaClass.simpleName))
        } finally {
            c.disconnect()
        }
    }
}

/** Install result: the system asks for confirmation (and, the first time, for permission to install from this app). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app reloads
            else -> Toast.makeText(ctx, ctx.getString(R.string.srv_update_failed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"), Toast.LENGTH_LONG).show()
        }
    }
}

package net.treetank.magneticplayer

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Uploading videos downloaded on the phone (by the browser, e.g. at friends' places) to the server.
 *
 * 1. Detection: [UploadScanWorker] with a MediaStore trigger (works with the app closed; after every run
 *    it is set up again) and a ContentObserver while the process is alive. We take only video from `Download/…` (not the camera),
 *    finished (`IS_PENDING = 0`) and added after the option was enabled.
 * 2. Immediately a notification with a question: "Upload" or "Upload and delete from phone".
 * 3. Choice → [UploadWorker] (a unique job per file, Wi-Fi/unmetered network only). When the server does not respond
 *    (phone away from home), the job waits and retries every ~15 min. Uploading resumes from the server's `received`.
 *    With the "Display over other apps" permission the question also pops up as a window above the browser
 *    ([UploadOverlay]), and the notification arrives silently.
 * 4. After upload with deletion: with "All files access" (MANAGE_EXTERNAL_STORAGE, optional) the app deletes
 *    the file itself; without it a notification "Tap to delete" → system confirmation (the file belongs to
 *    the browser: `MediaStore.createDeleteRequest`, Android 11+).
 */
object Uploads {
    const val CH_ASK = "upload_ask"
    const val CH_PROGRESS = "upload_progress"
    private const val SCAN_WORK = "upload-scan"
    private const val TAG_UPLOAD = "upload"
    const val TAG_NOTIF = "upload"
    private const val MIN_SIZE = 1L shl 20
    private const val WINDOW_SEC = 7 * 86400L
    const val WAIT_MIN = 15L

    const val ACTION_UPLOAD = "net.treetank.magneticplayer.UPLOAD"
    const val ACTION_CANCEL = "net.treetank.magneticplayer.UPLOAD_CANCEL"

    data class PhoneVideo(val id: Long, val name: String, val size: Long, val added: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("uploads", Context.MODE_PRIVATE)

    /** Notification ids of this file (question, waiting, progress, result). */
    fun notifId(mediaId: Long) = 30_000 + (mediaId % 1_000_000).toInt()

    // ---- permissions and settings

    /** What to ask for when enabling: videos of other apps (+ notifications on Android 13+). */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun canReadVideos(ctx: Context) = ContextCompat.checkSelfPermission(ctx,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    /** "All files access": then the browser's file can be deleted without a system prompt. */
    fun canDeleteSilently() = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

    fun allFilesIntent(ctx: Context) = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Deletes a video without asking (requires [canDeleteSilently]); first via MediaStore, then by path. */
    fun deleteSilently(ctx: Context, v: PhoneVideo): Boolean {
        if (!canDeleteSilently()) return false
        if (runCatching { ctx.contentResolver.delete(v.uri, null, null) > 0 }.getOrDefault(false)) return true
        @Suppress("DEPRECATION")
        val path = runCatching {
            ctx.contentResolver.query(v.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return false
        val ok = java.io.File(path).delete()
        if (ok) runCatching { ctx.contentResolver.delete(v.uri, null, null) }
        return ok
    }

    fun canUpload(s: Server) = s.info?.optJSONObject("features")?.optBoolean("upload") == true

    /** Target server: the one chosen in Settings, and without a choice the file server (or the first) with `features.upload`. */
    fun target(ctx: Context): Server? {
        val all = Servers.all(ctx)
        Settings(ctx).uploadServer?.let { k -> all.firstOrNull { it.key == k }?.let { return it } }
        val ok = all.filter { canUpload(it) }
        return ok.firstOrNull { it.key == Servers.activeKey(ctx) } ?: ok.firstOrNull()
            // old /api/info in memory (from before the agent update): we try the file server, at worst it refuses
            ?: Servers.active(ctx).takeIf { it.key.isNotEmpty() && it.hasFiles }
    }

    /** Enabling: from now on (old files in Download are not asked about), trigger and observer. */
    fun enable(ctx: Context) {
        Settings(ctx).uploadAsk = true
        prefs(ctx).edit().putLong("since", System.currentTimeMillis() / 1000).putString("seen", "{}").apply()
        onAppStart(ctx)
    }

    fun disable(ctx: Context) {
        Settings(ctx).uploadAsk = false
        WorkManager.getInstance(ctx).cancelUniqueWork(SCAN_WORK)
        observer?.let { ctx.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /** On every app start (MainActivity): WorkManager trigger, observer and one scan. */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        if (!Settings(app).uploadAsk || !canReadVideos(app)) return
        scheduleScan(app, fromWorker = false)
        observe(app)
        Thread { runCatching { scan(app) } }.start()
    }

    /**
     * Job with a "change in MediaStore.Video" trigger. It fires once, so after each run it sets up the next
     * (APPEND_OR_REPLACE: appended after the current one; KEEP at app start does not duplicate a waiting one).
     */
    fun scheduleScan(ctx: Context, fromWorker: Boolean) {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofMinutes(1))
            .build()
        val req = OneTimeWorkRequestBuilder<UploadScanWorker>().setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(SCAN_WORK,
            if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    @Volatile private var observer: ContentObserver? = null

    private fun observe(app: Context) {
        if (observer != null) return
        val h = Handler(Looper.getMainLooper())
        val run = Runnable { Thread { runCatching { scan(app) } }.start() }
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                h.removeCallbacks(run)
                h.postDelayed(run, 3000)  // the browser changes the entry several times near the end of the download
            }
        }.also { app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it) }
    }

    // ---- detection

    /** New, finished videos from Download/ since the option was enabled that we have not asked about yet → notifications. */
    @Synchronized
    fun scan(ctx: Context) {
        if (!Settings(ctx).uploadAsk || !canReadVideos(ctx)) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis() / 1000
        val since = maxOf(p.getLong("since", now), now - WINDOW_SEC)
        val seen = runCatching { JSONObject(p.getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        val M = MediaStore.MediaColumns.DATE_ADDED  // seconds
        val (sel, cols) = if (Build.VERSION.SDK_INT >= 29)
            "$M >= ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Download/%'" to
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, M)
        else
            @Suppress("DEPRECATION")
            "$M >= ? AND ${MediaStore.MediaColumns.DATA} LIKE '%/Download/%'" to
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, M)
        val found = mutableListOf<PhoneVideo>()
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, sel, arrayOf(since.toString()), "$M ASC")
            ?.use { c ->
                while (c.moveToNext()) {
                    val v = PhoneVideo(c.getLong(0), c.getString(1) ?: "film", c.getLong(2), c.getLong(3))
                    if (seen.has(v.id.toString())) continue
                    seen.put(v.id.toString(), v.added)
                    if (v.size >= MIN_SIZE) found += v
                }
            }
        // we forget entries older than the window (they will not come back anyway)
        seen.keys().asSequence().toList().forEach { k -> if (seen.optLong(k) < now - WINDOW_SEC - 86400) seen.remove(k) }
        p.edit().putString("seen", seen.toString()).apply()
        found.forEach { ask(ctx, it) }
    }

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ASK, ctx.getString(R.string.media_upload_channel_ask), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.media_upload_channel_progress), NotificationManager.IMPORTANCE_LOW))
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(ctx: Context, id: Int, n: android.app.Notification, tag: String? = TAG_NOTIF) {
        if (!canNotify(ctx)) return
        channels(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(tag, id, n)
    }

    fun cancelNotif(ctx: Context, mediaId: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(TAG_NOTIF, notifId(mediaId))
        nm.cancel(notifId(mediaId))
    }

    private fun Intent.putVideo(v: PhoneVideo) = putExtra("id", v.id).putExtra("name", v.name)
        .putExtra("size", v.size).putExtra("added", v.added)

    private fun Intent.video() = PhoneVideo(getLongExtra("id", -1), getStringExtra("name") ?: "film",
        getLongExtra("size", 0), getLongExtra("added", 0))

    private fun receiverIntent(ctx: Context, action: String, v: PhoneVideo, del: Boolean, req: Int) =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, UploadReceiver::class.java).setAction(action).putVideo(v)
            .putExtra("delete", del), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancelAction(ctx: Context, v: PhoneVideo) =
        receiverIntent(ctx, ACTION_CANCEL, v, false, notifId(v.id) * 4 + 2)

    /** "X downloaded (1.2 GB). Upload to jmpc?" with buttons. */
    fun ask(ctx: Context, v: PhoneVideo) {
        val srv = target(ctx) ?: return
        val id = notifId(v.id)
        // window above the browser; the notification stays for later (silent, so as not to ask twice at once)
        val overlay = UploadOverlay.show(ctx, v, srv.name)
        notify(ctx, id, NotificationCompat.Builder(ctx, CH_ASK)
            .setSilent(overlay)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(R.string.media_upload_ask_title, v.name))
            .setContentText(ctx.getString(R.string.media_upload_ask_text, humanBytes(v.size), srv.name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                ctx.getString(R.string.media_upload_ask_big, v.name, humanBytes(v.size), srv.name)))
            .setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.media_upload_action), receiverIntent(ctx, ACTION_UPLOAD, v, false, id * 4))
            .addAction(0, ctx.getString(R.string.media_upload_action_delete), receiverIntent(ctx, ACTION_UPLOAD, v, true, id * 4 + 1))
            .build())
    }

    // ---- upload job

    /** Stable identifier of this file's upload (resuming after a break lands in the same `.part` on the server). */
    private fun uploadId(ctx: Context, v: PhoneVideo): String {
        val p = prefs(ctx)
        val salt = p.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("salt", it).apply() }
        val d = MessageDigest.getInstance("SHA-1").digest("$salt|${v.id}|${v.size}|${v.added}|${v.name}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun enqueue(ctx: Context, v: PhoneVideo, delete: Boolean, delay: Long = 0) {
        val srv = target(ctx) ?: run {
            Toast.makeText(ctx, ctx.getString(R.string.media_upload_no_server), Toast.LENGTH_LONG).show()
            return
        }
        val data = Data.Builder().putLong("id", v.id).putString("name", v.name).putLong("size", v.size)
            .putLong("added", v.added).putBoolean("delete", delete).putString("server", srv.key)
            .putString("upload_id", uploadId(ctx, v)).build()
        enqueueData(ctx, data, ExistingWorkPolicy.REPLACE, delay)
        waiting(ctx, v, srv.name)
    }

    /** Next attempt in [delay] min (the server did not respond). Appended after the current job, so it does not interrupt it. */
    fun enqueueData(ctx: Context, data: Data, policy: ExistingWorkPolicy, delay: Long) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, WAIT_MIN, TimeUnit.MINUTES)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(data)
            .addTag(TAG_UPLOAD)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload:${data.getLong("id", -1)}", policy, req)
    }

    fun cancel(ctx: Context, v: PhoneVideo) {
        WorkManager.getInstance(ctx).cancelUniqueWork("upload:${v.id}")
        cancelNotif(ctx, v.id)
    }

    /** "Waiting for jmpc — will upload when the phone is on its network". */
    fun waiting(ctx: Context, v: PhoneVideo, server: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(v.id))
        notify(ctx, notifId(v.id), NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(v.name)
            .setContentText(ctx.getString(R.string.media_upload_waiting, server))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.media_upload_waiting_big, server)))
            .setOngoing(false)
            .setSilent(true)
            .addAction(0, ctx.getString(R.string.media_cancel), cancelAction(ctx, v))
            .build())
    }

    fun videoOf(d: Data) = PhoneVideo(d.getLong("id", -1), d.getString("name") ?: "film", d.getLong("size", 0), d.getLong("added", 0))
    fun intentVideo(i: Intent) = i.video()
}

/** Trigger "something changed in the videos on the phone": scans Download/ and sets up the next trigger. */
class UploadScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Settings(applicationContext).uploadAsk) return Result.success()
        withContext(Dispatchers.IO) { runCatching { Uploads.scan(applicationContext) } }
        Uploads.scheduleScan(applicationContext, fromWorker = true)
        return Result.success()
    }
}

/** Notification buttons: "Upload", "Upload and delete", "Cancel". */
class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val v = Uploads.intentVideo(intent)
        if (v.id < 0) return
        when (intent.action) {
            Uploads.ACTION_UPLOAD -> Uploads.enqueue(ctx, v, intent.getBooleanExtra("delete", false))
            Uploads.ACTION_CANCEL -> Uploads.cancel(ctx, v)
        }
    }
}

/**
 * One upload: pings the server (off its network it waits and retries every [Uploads.WAIT_MIN] min), asks for `received`
 * and sends the rest in 64 MB chunks. A foreground dataSync service when the system allows; otherwise an ordinary job
 * (the system may interrupt it after ~10 min, then the next one resumes from the place on the server).
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val ctx get() = applicationContext
    private val v = Uploads.videoOf(params.inputData)
    private val nid = Uploads.notifId(v.id)
    private var lastNotify = 0L

    private fun progressNotif(done: Long, server: String) = NotificationCompat.Builder(ctx, Uploads.CH_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(ctx.getString(R.string.media_upload_progress_title, server))
        .setContentText(ctx.getString(R.string.media_upload_progress_text, v.name, humanBytes(done), humanBytes(v.size)))
        .setProgress(1000, if (v.size > 0) (done * 1000 / v.size).toInt() else 0, false)
        .setOngoing(true)
        .setSilent(true)
        .addAction(0, ctx.getString(R.string.media_cancel), Uploads.cancelAction(ctx, v))
        .build()

    private fun fgInfo(done: Long, server: String) =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, progressNotif(done, server), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(nid, progressNotif(done, server))

    private fun finish(title: String, text: String, intent: PendingIntent? = null) {
        ctx.getSystemService(NotificationManager::class.java).cancel(nid)
        Uploads.notify(ctx, nid, NotificationCompat.Builder(ctx, Uploads.CH_ASK)
            .setSmallIcon(if (intent != null) android.R.drawable.ic_menu_delete else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (intent != null) setContentIntent(intent) }
            .build())
    }

    private fun failed(text: String): Result {
        finish(ctx.getString(R.string.media_upload_failed_title, v.name), text)
        return Result.failure()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { work() }

    private suspend fun work(): Result {
        val d = inputData
        val srv = Servers.get(ctx, d.getString("server")) ?: return failed(ctx.getString(R.string.media_upload_err_server_gone))
        val api = Api(Settings(ctx), srv)
        val id = d.getString("upload_id") ?: return Result.failure()
        val size = runCatching { ctx.contentResolver.openFileDescriptor(v.uri, "r")?.use { it.statSize } }.getOrNull()
        if (size == null || size < 0) return failed(ctx.getString(R.string.media_upload_err_file_gone))
        if (size != v.size) return failed(ctx.getString(R.string.media_upload_err_file_changed, humanBytes(v.size), humanBytes(size)))
        if (!api.ping(3000)) {
            // off the server's network: next attempt in a quarter of an hour; a new job instead of retry(), because linear backoff grows
            Uploads.waiting(ctx, v, srv.name)
            Uploads.enqueueData(ctx, d, ExistingWorkPolicy.APPEND_OR_REPLACE, Uploads.WAIT_MIN)
            return Result.success()
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(Uploads.TAG_NOTIF, nid)
        val fg = runCatching { setForeground(fgInfo(0, srv.name)) }.isSuccess
        var conflicts = 0
        try {
            while (true) {
                val (code, st) = api.uploadStatus(id)
                if (code == 404 && !st.has("received"))
                    return failed(ctx.getString(R.string.media_upload_err_unsupported, srv.name))
                if (code == 401) return failed(ctx.getString(R.string.media_upload_err_unpaired, srv.name))
                if (code >= 400 && code != 404) return failed(st.optString("error").ifEmpty { "HTTP $code" })
                if (st.optBoolean("done")) return done(srv, st.optString("name"))
                var off = st.optLong("received")
                // check without data: free space and place consistency, before gigabytes start flowing
                val (c0, r0) = api.uploadPut(id, v.name, size, off, null, 0)
                if (c0 == 507) return failed(ctx.getString(R.string.media_upload_err_no_space, srv.name, r0.optString("error")))
                if (c0 == 409) { if (++conflicts > 3) return Result.retry(); continue }
                if (c0 >= 400) return failed(r0.optString("error").ifEmpty { "HTTP $c0" })
                ctx.contentResolver.openFileDescriptor(v.uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        input.channel.position(off)
                        while (off < size) {
                            val n = minOf(CHUNK, size - off)
                            val start = off
                            val (c, r) = api.uploadPut(id, v.name, size, off, input, n) { sent ->
                                if (isStopped) throw IOException("przerwane")
                                val now = System.currentTimeMillis()
                                if (now - lastNotify > 1000) {
                                    lastNotify = now
                                    val pn = progressNotif(start + sent, srv.name)
                                    if (fg) setForegroundAsync(fgInfo(start + sent, srv.name))
                                    else Uploads.notify(ctx, nid, pn, tag = null)
                                }
                            }
                            if (c == 507) return failed(ctx.getString(R.string.media_upload_err_no_space, srv.name, r.optString("error")))
                            if (c >= 400) break  // 409 and others: start over from the state on the server
                            if (r.optBoolean("done")) return done(srv, r.optString("name"))
                            off = r.optLong("received")
                        }
                    }
                } ?: return failed(ctx.getString(R.string.media_upload_err_open))
                if (++conflicts > 3) return Result.retry()
            }
        } catch (e: ApiException) {
            // broken connection (leaving Wi-Fi, a sleeping server): we will try later, from the place on the server
            if (isStopped) return Result.retry()
            Uploads.waiting(ctx, v, srv.name)
            return Result.retry()
        } catch (e: IOException) {  // reading the file on the phone
            return if (isStopped) Result.retry() else failed(ctx.getString(R.string.media_upload_err_read, e.message.toString()))
        } catch (e: SecurityException) {
            return failed(ctx.getString(R.string.media_upload_err_permission))
        }
    }

    private fun done(srv: Server, name: String): Result {
        val shown = name.ifEmpty { v.name }
        if (!inputData.getBoolean("delete", false)) {
            finish(ctx.getString(R.string.media_upload_done_title, srv.name), ctx.getString(R.string.media_upload_done_text, shown))
        } else if (Uploads.deleteSilently(ctx, v)) {
            finish(ctx.getString(R.string.media_upload_done_deleted_title, srv.name), ctx.getString(R.string.media_upload_done_text, shown))
        } else if (Build.VERSION.SDK_INT >= 30) {
            val pi = PendingIntent.getActivity(ctx, nid, Intent(ctx, UploadDeleteActivity::class.java)
                .setData(v.uri).putExtra("nid", nid), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            finish(ctx.getString(R.string.media_upload_done_tap_title, srv.name),
                ctx.getString(R.string.media_upload_done_tap_text, shown), pi)
        } else {
            finish(ctx.getString(R.string.media_upload_done_title, srv.name), ctx.getString(R.string.media_upload_done_old_text, shown))
        }
        return Result.success()
    }

    companion object {
        private const val CHUNK = 64L shl 20
    }
}

/** Transparent: the system "Delete this video?" for the browser's file (Android 11+), then it disappears */
class UploadDeleteActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Toast.makeText(this, getString(if (r.resultCode == Activity.RESULT_OK) R.string.media_upload_deleted else R.string.media_upload_kept),
            Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        getSystemService(NotificationManager::class.java).cancel(Uploads.TAG_NOTIF, intent.getIntExtra("nid", 0))
        if (savedInstanceState != null) return  // screen rotation: the system window is already there
        if (uri == null || Build.VERSION.SDK_INT < 30) { finish(); return }
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.media_upload_delete_failed, e.message.toString()), Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

/** Settings → "Ask about uploading downloaded videos" and the target server. */
@Composable
fun UploadSettings() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var on by remember { mutableStateOf(s.uploadAsk && Uploads.canReadVideos(ctx)) }
    var chosen by remember { mutableStateOf(Uploads.target(ctx)?.key) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Uploads.canReadVideos(ctx)) { Uploads.enable(ctx); on = true }
        else Toast.makeText(ctx, ctx.getString(R.string.media_upload_no_permission), Toast.LENGTH_LONG).show()
    }
    Text(stringResource(R.string.media_upload_settings_title), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.media_upload_settings_ask))
            Text(stringResource(R.string.media_upload_settings_ask_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { want ->
            if (!want) { Uploads.disable(ctx); on = false }
            else if (Uploads.canReadVideos(ctx)) { Uploads.enable(ctx); on = true }
            else perm.launch(Uploads.permissions())
        })
    }
    if (!on) return
    // permission state refreshed after returning from system settings
    var resumed by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val overlayOk = remember(resumed) { UploadOverlay.allowed(ctx) }
    val deleteOk = remember(resumed) { Uploads.canDeleteSilently() }
    PermRow(stringResource(R.string.media_upload_overlay_title), stringResource(if (overlayOk) R.string.media_upload_overlay_on
        else R.string.media_upload_overlay_off),
        overlayOk) { ctx.startActivity(UploadOverlay.settingsIntent(ctx)) }
    if (Build.VERSION.SDK_INT >= 30) PermRow(stringResource(R.string.media_upload_silent_title), stringResource(if (deleteOk)
        R.string.media_upload_silent_on else R.string.media_upload_silent_off),
        deleteOk) { ctx.startActivity(Uploads.allFilesIntent(ctx)) }
    val servers = Servers.all(ctx)
    val able = servers.filter { Uploads.canUpload(it) }
    if (able.isEmpty()) {
        Text(stringResource(R.string.media_upload_no_capable,
            Uploads.target(ctx)?.name ?: stringResource(R.string.media_upload_no_server_name)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    Text(stringResource(R.string.media_upload_to), style = MaterialTheme.typography.bodyMedium)
    able.forEach { srv ->
        Row(Modifier.fillMaxWidth().clickable { s.uploadServer = srv.key; chosen = srv.key },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(chosen == srv.key, { s.uploadServer = srv.key; chosen = srv.key })
            Text(srv.name)
        }
    }
}

/** A row with a system permission: description and a switch that opens system settings (where it is granted and revoked). */
@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(ok, { open() })
    }
}

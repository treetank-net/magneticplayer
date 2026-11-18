package net.treetank.magneticplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * "Download to phone": downloads whole videos into MediaCache in the background (foreground service with a notification, Wi-Fi lock
 * and CPU lock, so it works with the screen off). Queue = OfflineItem with `want && !complete`, one at a time.
 * Only bytes the computer already has (X-Ready-End): for a torrent in progress it waits for further pieces.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "stop"
        private const val CHUNK = 8L shl 20
        private const val MAX_FAILS = 20  // ~5 min without the computer (every 15 s), then "Interrupted"

        /** State for the "On the phone" list: video key → description (e.g. "Downloading 42%"). */
        val status = MutableStateFlow<Map<String, String>>(emptyMap())
        private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Adds a video to downloads (or resumes) and starts the service. */
        fun start(ctx: Context, ref: MediaRef, policy: String? = null) {
            val key = ref.key()
            cancelled.remove(key)
            val old = OfflineStore.get(ctx, key)
            OfflineStore.put(ctx, (old ?: OfflineItem(key, ref, ref.title)).copy(
                pinned = true, want = true, complete = false, error = null, created = if (old?.pinned == true) old.created else System.currentTimeMillis(),
                policy = policy ?: old?.takeIf { it.pinned }?.policy ?: "watched"))
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        /** Stops downloading a video (deleting from disk is done by MediaCache.remove). */
        fun cancel(key: String) {
            cancelled += key
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private val finished = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.media_dl_channel), NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "magneticplayer:download").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magneticplayer:download").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, notification(getString(R.string.media_dl_preparing), null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (intent?.action == ACTION_STOP) {
            OfflineStore.all(this).filter { it.want && !it.complete }.forEach { i ->
                cancelled += i.key
                OfflineStore.update(this, i.key) { it.copy(want = false, error = getString(R.string.media_dl_paused)) }
            }
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch {
            runQueue()
            status.value = emptyMap()
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (finished.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1,
                NotificationCompat.Builder(this@DownloadService, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.media_dl_done_title))
                    .setContentText(finished.joinToString(", "))
                    .setAutoCancel(true)
                    .setContentIntent(openList())
                    .build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+: time limit for dataSync services. We stop, and it can be resumed from the list. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        OfflineStore.all(this).filter { it.want && !it.complete }.forEach { i ->
            cancelled += i.key
            OfflineStore.update(this, i.key) { it.copy(want = false, error = getString(R.string.media_dl_timeout)) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private suspend fun runQueue() {
        while (true) {
            val item = OfflineStore.all(this).lastOrNull { it.want && !it.complete && it.key !in cancelled } ?: return
            runCatching { downloadOne(item) }.onFailure { e ->
                OfflineStore.update(this, item.key) { it.copy(want = false, error = getString(R.string.media_dl_failed, e.message ?: e.javaClass.simpleName)) }
            }
            if (item.key in cancelled) {
                // cancelled mid-chunk: clean up what CacheWriter managed to append after MediaCache.remove
                if (OfflineStore.get(this, item.key) == null) runCatching { MediaCache.get(this).removeResource(item.key) }
                cancelled -= item.key
            }
            status.value = status.value - item.key
        }
    }

    private suspend fun downloadOne(item: OfflineItem) {
        val key = item.key
        val api = Api.forRef(this, item.ref)
        val url = api.mediaUrl(item.ref)
        val cache = MediaCache.get(this)
        val writer = CacheDataSource(cache, DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(30_000).createDataSource())
        var fails = 0
        var subsDone = item.subs.isNotEmpty()
        var spaceChecked = false
        while (key !in cancelled) {
            try {
                if (!subsDone) subsDone = runCatching { saveSubs(api, item) }.isSuccess
                val size = headProbe(url, 0).first
                OfflineStore.update(this, key) { it.copy(size = size) }
                MediaCache.setLength(this, key, size)
                if (!spaceChecked) {
                    val missing = size - cache.getCachedBytes(key, 0, size)
                    if (missing > noBackupFilesDir.usableSpace - (500L shl 20)) throw NoSpaceException(getString(R.string.media_dl_no_space, humanBytes(missing)))
                    spaceChecked = true
                }
                var at = 0L
                var waiting = false
                while (at < size && key !in cancelled) {
                    val cached = cache.getCachedLength(key, at, size - at)
                    if (cached > 0) { at += cached; continue }
                    val ready = headProbe(url, at).second
                    if (ready <= at) { waiting = true; break }
                    val len = minOf(-cached, CHUNK, ready - at)
                    CacheWriter(writer, DataSpec.Builder().setUri(url).setKey(key).setPosition(at).setLength(len).build(), null, null).cache()
                    at += len
                    fails = 0
                    progress(item, cache.getCachedBytes(key, 0, size), size)
                }
                if (key in cancelled) return
                if (at >= size) {
                    OfflineStore.update(this, key) { it.copy(complete = true, want = false, error = null) }
                    finished += item.title
                    return
                }
                if (waiting) {
                    setStatus(key, getString(R.string.media_dl_waiting_pc))
                    delay(15_000)
                }
            } catch (e: IOException) {
                if (e is NoSpaceException) throw e
                if (++fails > MAX_FAILS) throw IOException(getString(R.string.media_dl_no_connection))
                setStatus(key, getString(R.string.media_dl_retrying))
                delay(15_000)
            }
        }
    }

    /** Subtitles next to the video → filesDir/offline/<hash>/, stored in OfflineItem (for offline playback). */
    private fun saveSubs(api: Api, item: OfflineItem) {
        val dir = OfflineStore.subsDir(this, item.key)
        val list = api.subsList(item.ref)
        val saved = list.mapIndexedNotNull { i, s ->
            val f = File(dir, "$i.${s.ext}")
            runCatching { download(api.subUrl(item.ref, s.name), f); LocalSub(s.name, s.lang, s.ext, f.path) }.getOrNull()
        }
        OfflineStore.update(this, item.key) { it.copy(subs = saved) }
    }

    private fun progress(item: OfflineItem, done: Long, size: Long) {
        val pct = (100 * done / size.coerceAtLeast(1)).toInt()
        setStatus(item.key, getString(R.string.media_dl_progress, pct, humanBytes(done), humanBytes(size)), notifyToo = false)
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1_000) {
            lastNotify = now
            notify(item.title, pct)
        }
    }

    /** [notifyToo] = false for progress (that goes to the notification with a bar in [progress]). */
    private fun setStatus(key: String, text: String, notifyToo: Boolean = true) {
        status.value = status.value + (key to text)
        if (notifyToo) notify(text, null)
    }

    /** No space on the phone: no retry. */
    private class NoSpaceException(msg: String) : IOException(msg)

    private fun notify(text: String, pct: Int?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, pct))
    }

    private fun openList() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).putExtra("tab", TAB_OFFLINE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(text: String, pct: Int?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.media_dl_channel))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .apply { if (pct != null) setProgress(100, pct, false) else setProgress(0, 0, true) }
        .setContentIntent(openList())
        .addAction(0, getString(R.string.media_dl_pause_action), PendingIntent.getService(this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()
}

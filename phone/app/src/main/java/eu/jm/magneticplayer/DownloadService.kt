package eu.jm.magneticplayer

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
 * „Pobierz na telefon”: pobiera całe filmy do MediaCache w tle (usługa pierwszoplanowa z powiadomieniem, blokada Wi-Fi
 * i CPU, więc działa przy zgaszonym ekranie). Kolejka = OfflineItem z `want && !complete`, po jednym naraz.
 * Tylko bajty, które komputer już ma (X-Ready-End): przy torrencie w trakcie czeka na kolejne kawałki.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "stop"
        private const val CHUNK = 8L shl 20
        private const val MAX_FAILS = 20  // ~5 min bez komputera (co 15 s), potem „Przerwane”

        /** Stan dla listy „Na telefonie”: klucz filmu → opis (np. „Pobieranie 42%”). */
        val status = MutableStateFlow<Map<String, String>>(emptyMap())
        private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Dodaje film do pobranych (albo wznawia) i uruchamia usługę. */
        fun start(ctx: Context, ref: MediaRef, policy: String? = null) {
            val key = ref.key()
            cancelled.remove(key)
            val old = OfflineStore.get(ctx, key)
            OfflineStore.put(ctx, (old ?: OfflineItem(key, ref, ref.title)).copy(
                pinned = true, want = true, error = null, created = if (old?.pinned == true) old.created else System.currentTimeMillis(),
                policy = policy ?: old?.takeIf { it.pinned }?.policy ?: "watched"))
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        /** Zatrzymuje pobieranie filmu (usuwanie z dysku robi MediaCache.remove). */
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
            NotificationChannel(CHANNEL, "Pobieranie na telefon", NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "magneticplayer:download").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magneticplayer:download").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Przygotowanie…", null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (intent?.action == ACTION_STOP) {
            OfflineStore.all(this).filter { it.want && !it.complete }.forEach { i ->
                cancelled += i.key
                OfflineStore.update(this, i.key) { it.copy(want = false, error = "Wstrzymane") }
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
                    .setContentTitle("Pobrane na telefon")
                    .setContentText(finished.joinToString(", "))
                    .setAutoCancel(true)
                    .setContentIntent(openList())
                    .build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+: limit czasu usług dataSync. Przerywamy, wznowić można z listy. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        OfflineStore.all(this).filter { it.want && !it.complete }.forEach { i ->
            cancelled += i.key
            OfflineStore.update(this, i.key) { it.copy(want = false, error = "Przerwane przez system (limit czasu), wznów ręcznie") }
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
                OfflineStore.update(this, item.key) { it.copy(want = false, error = "Przerwane: ${e.message ?: e.javaClass.simpleName}") }
            }
            if (item.key in cancelled) {
                // anulowane w trakcie kawałka: sprzątamy to, co CacheWriter zdążył dopisać po MediaCache.remove
                if (OfflineStore.get(this, item.key) == null) runCatching { MediaCache.get(this).removeResource(item.key) }
                cancelled -= item.key
            }
            status.value = status.value - item.key
        }
    }

    private suspend fun downloadOne(item: OfflineItem) {
        val key = item.key
        val api = Api(Settings(this))
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
                    if (missing > cacheDir.usableSpace - (500L shl 20)) throw IOException("za mało miejsca (potrzeba ${humanBytes(missing)})")
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
                    setStatus(key, "Czeka, aż komputer pobierze dalszą część")
                    delay(15_000)
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("za mało miejsca") == true) throw e
                if (++fails > MAX_FAILS) throw IOException("brak połączenia z komputerem")
                setStatus(key, "Brak połączenia z komputerem, ponawiam…")
                delay(15_000)
            }
        }
    }

    /** Napisy obok filmu → filesDir/offline/<hash>/, zapamiętane w OfflineItem (do odtwarzania bez sieci). */
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
        setStatus(item.key, "Pobieranie $pct% (${humanBytes(done)} z ${humanBytes(size)})")
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1_000) {
            lastNotify = now
            notify(item.title, pct)
        }
    }

    private fun setStatus(key: String, text: String) {
        status.value = status.value + (key to text)
        if (!text.startsWith("Pobieranie")) notify(text, null)
    }

    private fun notify(text: String, pct: Int?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, pct))
    }

    private fun openList() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).putExtra("tab", TAB_OFFLINE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(text: String, pct: Int?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Pobieranie na telefon")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .apply { if (pct != null) setProgress(100, pct, false) else setProgress(0, 0, true) }
        .setContentIntent(openList())
        .addAction(0, "Wstrzymaj", PendingIntent.getService(this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()
}

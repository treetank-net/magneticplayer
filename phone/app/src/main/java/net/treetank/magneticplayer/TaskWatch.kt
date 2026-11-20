package net.treetank.magneticplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether MainActivity is on screen; its polling loops wait here while it is not. */
object MainVisible {
    val state = MutableStateFlow(false)
    suspend fun await() { state.first { it } }
}

/**
 * Library tasks this phone follows: the ones it started, and ones picked with "Monitor" on a task card. Only these get a
 * progress notification and a "finished" one (other phones of the household don't hear about someone else's task).
 * Stored as "<server key>|<task id>".
 */
object TaskWatch {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)
    private const val KEY = "task_watch"

    /** Bumped on every change, so task cards redraw their "Monitor" button. */
    val version = MutableStateFlow(0)

    /** Whether the app is on screen: the service then reads every 15 s, otherwise once a minute. */
    @Volatile var appVisible = false

    fun all(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY, emptySet())!!.toSet()
    fun isWatched(ctx: Context, srv: String, id: String) = "$srv|$id" in all(ctx)

    fun watch(ctx: Context, srv: String, id: String) {
        prefs(ctx).edit().putStringSet(KEY, all(ctx) + "$srv|$id").apply()
        version.value++
        ensure(ctx)
    }

    fun unwatch(ctx: Context, srv: String, id: String) {
        prefs(ctx).edit().putStringSet(KEY, all(ctx) - "$srv|$id").apply()
        version.value++
    }

    /** Starts the service when something is followed (from the UI: a foreground service may start only then). */
    fun ensure(ctx: Context) {
        if (all(ctx).isEmpty()) return
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TaskWatchService::class.java)) }
    }
}

/**
 * Follows [TaskWatch]'s tasks: an ongoing notification with the progress, like a download, read once a minute in the
 * background (every 15 s while the app is on screen), with the brief task list (no items). A task that ends gets the
 * "finished" notification and leaves the list; the service stops when nothing is left.
 */
class TaskWatchService : Service() {
    companion object {
        private const val CHANNEL = "task_progress"
        private const val NOTIF_ID = 43
        private const val MAX_FAILS = 60  // about an hour without the server: give up, the app checks again when opened
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.lib_watch_channel), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notification(getString(R.string.lib_watch_checking), null, null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (job?.isActive != true) job = scope.launch {
            runCatching { loop() }
            ServiceCompat.stopForeground(this@TaskWatchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+: the time limit of a dataSync service; the app picks the tasks up again when opened. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun loop() {
        var fails = 0
        while (true) {
            val watched = TaskWatch.all(this).mapNotNull { s -> s.split('|', limit = 2).takeIf { it.size == 2 } }
            if (watched.isEmpty()) return
            val running = mutableListOf<Pair<Server, LibTask>>()
            var anyFailed = false
            for ((srvKey, ids) in watched.groupBy({ it[0] }, { it[1] })) {
                val api = Api.of(this, srvKey)
                if (api == null) { ids.forEach { TaskWatch.unwatch(this, srvKey, it) }; continue }
                val list = runCatching { api.tasks(brief = true) }.getOrNull()
                if (list == null) { anyFailed = true; continue }
                for (id in ids) {
                    val t = list.firstOrNull { it.id == id }
                    when {
                        t == null -> TaskWatch.unwatch(this, srvKey, id)  // removed from the list on the server
                        t.state == "queued" || t.state == "running" -> running += api.srv to t
                        else -> { Tasks.notifyFinished(this, api.srv, t); TaskWatch.unwatch(this, srvKey, id) }
                    }
                }
            }
            fails = if (anyFailed && running.isEmpty()) fails + 1 else 0
            if (fails > MAX_FAILS) return
            if (running.isEmpty() && !anyFailed) return
            show(running, anyFailed)
            delay(if (TaskWatch.appVisible) 15_000 else 60_000)
        }
    }

    private fun show(running: List<Pair<Server, LibTask>>, unreachable: Boolean) {
        val n = if (running.isEmpty()) notification(getString(R.string.lib_watch_unreachable), null, null) else {
            val (srv, t) = running.first()
            val text = buildString {
                append("${t.pct}%")
                t.current?.let { append(" · ").append(it) }
                if (running.size > 1) append(" · ").append(getString(R.string.lib_watch_more, running.size - 1))
            }
            notification(t.title.ifEmpty { srv.name }, text, t.pct, srv.name)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun notification(title: String, text: String?, pct: Int?, sub: String? = null) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) }; sub?.let { setSubText(it) } }
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(this, NOTIF_ID, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
}

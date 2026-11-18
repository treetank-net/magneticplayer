package net.treetank.magneticplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Finished file tasks (moving, shrinking, deleting; mp_tasks on the agent): one notification per task that finished since
 * the last check. Checked together with [Access] while the app is on screen, no background work.
 */
object Tasks {
    private const val CHANNEL = "tasks"
    private const val TAG = "task"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    fun check(ctx: Context, api: Api) {
        val srv = Servers.get(ctx, api.srv.key) ?: api.srv
        if (srv.isGuest || !srv.libraryTasks) return
        val list = runCatching { api.tasks() }.getOrNull() ?: return
        val key = "tasks_seen:${srv.key}"
        val p = prefs(ctx)
        val latest = list.mapNotNull { it.finished }.maxOrNull() ?: 0.0
        if (!p.contains(key)) {
            // first check on this server: tasks finished so far do not notify (the agent's clock, not the phone's)
            p.edit().putString(key, latest.toString()).apply()
            return
        }
        val seen = p.getString(key, null)?.toDoubleOrNull() ?: 0.0
        val fresh = list.filter { (it.state == "done" || it.state == "cancelled") && (it.finished ?: 0.0) > seen }
        if (fresh.isEmpty()) return
        p.edit().putString(key, maxOf(seen, latest).toString()).apply()
        fresh.forEach { notify(ctx, srv, it) }
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "Moved 14 items, 2 errors" and the like. */
    fun summary(ctx: Context, t: LibTask): String {
        val r = ctx.resources
        if (t.state == "cancelled") return ctx.getString(R.string.lib_notif_cancelled)
        val n = t.items.count { it.state == "done" }
        val main = when (t.kind) {
            "shrink" -> r.getQuantityString(R.plurals.lib_notif_shrank, n, n, humanBytes(t.saved))
            "trash" -> r.getQuantityString(R.plurals.lib_notif_deleted, n, n)
            else -> r.getQuantityString(R.plurals.lib_notif_moved, n, n)
        }
        return if (t.errors > 0) main + ", " + r.getQuantityString(R.plurals.lib_errors, t.errors, t.errors) else main
    }

    private fun notify(ctx: Context, srv: Server, t: LibTask) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.lib_notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val nid = "${srv.key}/${t.id}".hashCode()
        val open = PendingIntent.getActivity(ctx, nid, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = summary(ctx, t)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(t.title.ifEmpty { srv.name })
            .setContentText(text)
            .setSubText(srv.name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        nm.notify(TAG, nid, n)
    }
}

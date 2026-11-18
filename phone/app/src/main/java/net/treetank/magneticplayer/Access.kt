package net.treetank.magneticplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Access requests (mp_access.py): an unpaired device on the home network asks, and this phone (a household member's)
 * shows a notification with the buttons "Guest (day)" / "Household" / "Reject".
 * Checked when connecting to the server (loop in MainActivity), no background work.
 */
object Access {
    private const val CHANNEL = "access"
    private const val TAG = "access"
    const val ACTION = "net.treetank.magneticplayer.ACCESS"
    private val shown = mutableSetOf<String>()  // ids already shown (until the process dies)

    fun check(ctx: Context, api: Api) {
        val srv = Servers.get(ctx, api.srv.key) ?: api.srv
        if (srv.isGuest || !srv.canAccessRequests) return
        val list = runCatching { api.accessRequests() }.getOrNull() ?: return
        val now = mutableSetOf<String>()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val id = r.getString("id")
            now += id
            if (shown.add(id)) notify(ctx, srv, id, r.optString("name"), r.optString("ip"), r.optInt("expires_in", 300))
        }
        // requests handled elsewhere (another phone, the web page) or expired: remove the notification
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag == TAG && it.notification.extras.getString("srv") == srv.key &&
            it.notification.extras.getString("req") !in now }.forEach { nm.cancel(TAG, it.id) }
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifId(srvKey: String, id: String) = ("$srvKey/$id").hashCode()

    private fun action(ctx: Context, srvKey: String, id: String, name: String, what: String, req: Int) =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, AccessReceiver::class.java).setAction(ACTION)
            .putExtra("srv", srvKey).putExtra("req", id).putExtra("name", name).putExtra("what", what),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notify(ctx: Context, srv: Server, id: String, name: String, ip: String, expiresIn: Int) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.srv_access_channel), NotificationManager.IMPORTANCE_HIGH))
        val nid = notifId(srv.key, id)
        val open = PendingIntent.getActivity(ctx, nid, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(ctx.getString(R.string.srv_access_title, name))
            .setContentText(ctx.getString(R.string.srv_access_text, srv.name, ip))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.srv_access_big, name, ip, srv.name)))
            .setContentIntent(open)
            .setTimeoutAfter(expiresIn * 1000L)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addExtras(android.os.Bundle().apply { putString("srv", srv.key); putString("req", id) })
            .addAction(0, ctx.getString(R.string.srv_access_guest_day), action(ctx, srv.key, id, name, "guest", nid * 4))
            .addAction(0, ctx.getString(R.string.srv_access_member), action(ctx, srv.key, id, name, "owner", nid * 4 + 1))
            .addAction(0, ctx.getString(R.string.srv_decline), action(ctx, srv.key, id, name, "deny", nid * 4 + 2))
            .build()
        nm.notify(TAG, nid, n)
    }

    fun decide(ctx: Context, srvKey: String, id: String, name: String, what: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(TAG, notifId(srvKey, id))
        val api = Api.of(ctx, srvKey) ?: return
        val msg = try {
            when (what) {
                "deny" -> { api.accessDecide(id, false); ctx.getString(R.string.srv_access_declined, name) }
                "owner" -> { api.accessDecide(id, true, "owner"); ctx.getString(R.string.srv_access_member_ok, name) }
                else -> { api.accessDecide(id, true, "guest", 1); ctx.getString(R.string.srv_access_guest_ok, name) }
            }
        } catch (e: Exception) {
            ctx.getString(R.string.srv_access_error, name, e.message)
        }
        ContextCompat.getMainExecutor(ctx).execute { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}

class AccessReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Access.ACTION) return
        val pending = goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                Access.decide(app, intent.getStringExtra("srv") ?: return@thread, intent.getStringExtra("req") ?: return@thread,
                    intent.getStringExtra("name") ?: app.getString(R.string.srv_device), intent.getStringExtra("what") ?: "deny")
            } finally {
                pending.finish()
            }
        }
    }
}

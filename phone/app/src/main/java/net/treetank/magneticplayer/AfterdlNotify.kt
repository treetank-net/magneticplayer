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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * After download, this phone's part (agent 1.25+, `features.afterdl_ask`, `GET /api/afterdl/mine`): a step that asks
 * the downloader ("Shrink Sintel?") becomes a notification with "Shrink" / "No", and every run of a video this phone
 * added ends with a summary ("Sintel ready"). Checked every ~60 s while the app is on screen (MainActivity) and by
 * [AfterdlWorker] every 15 min while this phone has downloads or runs going on.
 */
object AfterdlNotify {
    private const val CHANNEL = "afterdl"
    private const val TAG_Q = "afterdl_q"
    private const val TAG_DONE = "afterdl_done"
    const val ACTION_ANSWER = "net.treetank.magneticplayer.AFTERDL_ANSWER"
    private const val WORK = "afterdl_follow"
    private const val NOTIFIED = "adl_notified"  // "<server>|<run>|<step>" of the questions already shown
    /** How long after adding a download the worker keeps checking although no run exists yet (the run starts when
     *  the download finishes; a slow torrent takes a while). */
    private const val FOLLOW_AFTER_ADD_MS = 48 * 3600 * 1000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun qKey(srvKey: String, run: String, step: Int) = "$srvKey|$run|$step"
    private fun qId(srvKey: String, run: String, step: Int) = qKey(srvKey, run, step).hashCode()

    /**
     * One server: new questions → notifications, questions gone (answered elsewhere, timed out) → their notifications
     * removed, runs finished since the last check → summaries. Then the background worker is started or stopped.
     */
    fun check(ctx: Context, api: Api) {
        val srv = Servers.get(ctx, api.srv.key) ?: api.srv
        if (srv.isGuest || !srv.hasAfterdlAsk) return
        val p = prefs(ctx)
        val seenKey = "adl_seen:${srv.key}"
        val first = !p.contains(seenKey)
        val since = p.getString(seenKey, null)?.toDoubleOrNull() ?: (System.currentTimeMillis() / 1000.0)
        val mine = try { api.afterdlMine(since) } catch (_: Exception) { return }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notified = p.getStringSet(NOTIFIED, emptySet()).orEmpty().toMutableSet()
        val open = mine.questions.map { qKey(srv.key, it.run, it.step) }.toSet()
        // this server's questions that are no longer open
        notified.filter { it.startsWith("${srv.key}|") && it !in open }.forEach {
            nm.cancel(TAG_Q, it.hashCode())
            notified -= it
        }
        for (q in mine.questions) {
            val k = qKey(srv.key, q.run, q.step)
            if (k !in notified && notifyQuestion(ctx, srv, q, mine.now)) notified += k
        }
        // the first check on this server: runs finished before it don't notify (the agent's clock, not the phone's)
        if (!first) mine.done.filter { it.state == "done" || it.state == "error" }.forEach { notifyDone(ctx, srv, it) }
        p.edit().putStringSet(NOTIFIED, notified).putString(seenKey, mine.now.toString())
            .putInt("adl_running:${srv.key}", mine.running).apply()
        reschedule(ctx)
    }

    /** This phone just added a download on [srv]: its after download steps may ask it, so follow it in the background. */
    fun added(ctx: Context, srv: Server) {
        val s = Servers.get(ctx, srv.key) ?: srv
        if (s.isGuest || !s.hasAfterdlAsk) return
        prefs(ctx).edit().putLong("adl_follow_until:${s.key}", System.currentTimeMillis() + FOLLOW_AFTER_ADD_MS).apply()
        schedule(ctx)
    }

    /** The question of step [step] got an answer in the app: its notification is stale. */
    fun answered(ctx: Context, srvKey: String, run: String, step: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(TAG_Q, qId(srvKey, run, step))
    }

    /** Servers this phone follows: a run of its own still going on, or a download added recently. */
    private fun following(ctx: Context): List<Server> {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        return Servers.all(ctx).filter { s ->
            !s.isGuest && s.hasAfterdlAsk &&
                (p.getInt("adl_running:${s.key}", 0) > 0 || p.getLong("adl_follow_until:${s.key}", 0L) > now)
        }
    }

    private fun reschedule(ctx: Context) {
        if (following(ctx).isNotEmpty()) schedule(ctx) else WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    private fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<AfterdlWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** The background round: every followed server (unreachable ones keep what they said last), then stop when none is left. */
    fun checkAll(ctx: Context) {
        following(ctx).forEach { s -> runCatching { check(ctx, Api(Settings(ctx), s)) } }
        reschedule(ctx)
    }

    // ---- texts (also the question card in a run's history)

    fun questionTitle(ctx: Context, kind: String, name: String): String =
        if (kind == "shrink") ctx.getString(R.string.adl_ask_title, name) else ctx.getString(R.string.adl_ask_title_other, name)

    /** "9.4 GB → about 2.5 GB on the graphics card, ~25 min. No answer: yes at 03:00." */
    fun questionText(ctx: Context, kind: String, a: AdlAsk): String {
        val at = android.text.format.DateFormat.getTimeFormat(ctx).format(Date((a.until * 1000).toLong()))
        val fallback = ctx.getString(if (a.default == "no") R.string.adl_ask_default_no else R.string.adl_ask_default_yes, at)
        if (kind != "shrink" || a.size <= 0) return fallback
        val what = ctx.getString(if (a.gpu) R.string.adl_ask_facts_gpu else R.string.adl_ask_facts_cpu, humanBytes(a.size), humanBytes(a.est))
        val mins = a.secs?.let { ctx.getString(R.string.adl_ask_minutes, maxOf(1L, Math.round(it / 60))) } ?: ""
        return "$what$mins. $fallback"
    }

    // ---- notifications

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.adl_notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }

    private fun openApp(ctx: Context, req: Int) = PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun answerAction(ctx: Context, srvKey: String, q: AdlQuestion, yes: Boolean, req: Int) =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, AfterdlReceiver::class.java).setAction(ACTION_ANSWER)
            .putExtra("srv", srvKey).putExtra("run", q.run).putExtra("step", q.step).putExtra("name", q.name)
            .putExtra("yes", yes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** → whether it was shown (no permission: it is tried again on the next check). */
    private fun notifyQuestion(ctx: Context, srv: Server, q: AdlQuestion, now: Double): Boolean {
        if (!canNotify(ctx)) return false
        val nm = channel(ctx)
        val nid = qId(srv.key, q.run, q.step)
        val text = questionText(ctx, q.kind, q.ask)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(questionTitle(ctx, q.kind, q.name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(srv.name)
            .setContentIntent(openApp(ctx, nid))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { if (q.ask.until > now) setTimeoutAfter(((q.ask.until - now) * 1000).toLong()) }
            .addAction(0, ctx.getString(R.string.adl_ask_yes), answerAction(ctx, srv.key, q, true, nid * 2))
            .addAction(0, ctx.getString(R.string.adl_ask_no), answerAction(ctx, srv.key, q, false, nid * 2 + 1))
            .build()
        nm.notify(TAG_Q, nid, n)
        return true
    }

    private fun notifyDone(ctx: Context, srv: Server, r: AdlRun) {
        if (!canNotify(ctx)) return
        val nm = channel(ctx)
        val nid = "${srv.key}/${r.id}".hashCode()
        val failed = r.state == "error"
        val text = if (failed) r.steps.getOrNull(r.step)?.note.orEmpty()
            else r.steps.filter { it.state == "done" }.mapNotNull { it.note?.takeIf { n -> n.isNotBlank() } }.joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(if (failed) R.string.adl_notif_failed else R.string.adl_notif_ready, r.name))
            .apply { if (text.isNotEmpty()) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .setSubText(srv.name)
            .setContentIntent(openApp(ctx, nid))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        nm.notify(TAG_DONE, nid, n)
    }

    /** A button on the question's notification (off the main thread). */
    fun answer(ctx: Context, srvKey: String, run: String, step: Int, name: String, yes: Boolean) {
        val api = Api.of(ctx, srvKey) ?: return
        val msg = try {
            api.afterdlAnswer(run, step, yes)
            answered(ctx, srvKey, run, step)
            ctx.getString(if (yes) R.string.adl_notif_answered_yes else R.string.adl_notif_answered_no, name)
        } catch (e: Exception) {
            // 400: answered elsewhere or the step already started; the next check removes the notification
            ctx.getString(R.string.adl_notif_answer_error, name, e.message ?: e.javaClass.simpleName)
        }
        ContextCompat.getMainExecutor(ctx).execute { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}

class AfterdlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != AfterdlNotify.ACTION_ANSWER) return
        val pending = goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                AfterdlNotify.answer(app, intent.getStringExtra("srv") ?: return@thread, intent.getStringExtra("run") ?: return@thread,
                    intent.getIntExtra("step", -1), intent.getStringExtra("name").orEmpty(), intent.getBooleanExtra("yes", true))
            } finally {
                pending.finish()
            }
        }
    }
}

/** Every 15 min while this phone follows a download or a run on some server ([AfterdlNotify.following]). */
class AfterdlWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) { runCatching { AfterdlNotify.checkAll(applicationContext) } }
        return Result.success()
    }
}

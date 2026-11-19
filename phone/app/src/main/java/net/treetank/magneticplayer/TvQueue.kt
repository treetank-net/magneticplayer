package net.treetank.magneticplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * What this phone last sent to a screen, TV or Chromecast, with what a series needs there as in the phone's player:
 * the following episodes, the intro and the credits (from the agent, or saved with a downloaded copy when the phone
 * is the source). Remote offers "Skip intro" and "Next episode"; from the credits (or the last 10 s) a 10 s countdown
 * starts the next episode on the same target, in the notification too. [RemoteSessionService] reads the target every
 * 1.5 s also with the phone locked and calls [tick]; near the end a CPU lock keeps that going.
 * From halfway the next episode's subtitles in the language picked last are fetched ahead ([prepareSubsAhead]).
 */
object TvQueue {
    sealed class Target {
        data class Screen(val key: String) : Target()
        data class Chromecast(val t: CastTarget) : Target()
        data class Tv(val t: DlnaTarget) : Target()
    }

    class Entry(val kind: String, val ref: MediaRef, val target: Target) {
        @Volatile var next: List<MediaRef.InLibrary> = emptyList()
        /** seconds */
        @Volatile var intro: Pair<Double, Double>? = null
        @Volatile var creditsAt: Double? = null
        @Volatile var cancelled = false
        @Volatile var started = false
        var subsAhead = false
        var pos = 0.0
        var dur = 0.0
        var left = COUNT_S.toDouble()
        var lastTick = 0L
    }

    private const val COUNT_S = 10
    private const val CHANNEL = "next_episode"
    private const val NOTIF_ID = 2101
    const val ACTION_NOW = "net.treetank.magneticplayer.NEXT_NOW"
    const val ACTION_CANCEL = "net.treetank.magneticplayer.NEXT_CANCEL"

    /** the last video sent (Remote shows its buttons when it controls that target) */
    val current = mutableStateOf<Entry?>(null)
    /** seconds left to the next episode, null = no countdown */
    val countdown = mutableStateOf<Int?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null

    /** A video went to `kind` (from [Screens]): it replaces the previous one; episodes and intro/credits load in the background. */
    fun sent(ctx: Context, kind: String, ref: MediaRef, target: Target, next: List<MediaRef.InLibrary>?) {
        val app = ctx.applicationContext
        val e = Entry(kind, ref, target)
        scope.launch {
            current.value = e
            countdown.value = null
            notifyCountdown(app, null)
        }
        val r = ref as? MediaRef.InLibrary ?: return
        scope.launch(Dispatchers.IO) {
            e.next = next ?: nextOf(app, r)
            if (e.next.isNotEmpty() || OfflineStore.get(app, r.key())?.intro != null) loadSegments(app, r, e)
        }
    }

    /** The folder's episodes after `r`: from the agent, or saved with the downloaded copy when the agent does not answer. */
    private fun nextOf(ctx: Context, r: MediaRef.InLibrary): List<MediaRef.InLibrary> {
        val api = Api.of(ctx, r.srv)?.takeIf { it.srv.libraryEpisodes && it.ping(1500) }
        val paths = api?.let { a -> runCatching { a.episodes(r.root, r.path.substringBeforeLast('/', "")).episodes.map { it.path } }.getOrNull() }
            ?: OfflineStore.get(ctx, r.key())?.episodes ?: return emptyList()
        val i = paths.indexOf(r.path)
        return if (i < 0) emptyList() else paths.drop(i + 1).map { MediaRef.InLibrary(r.root, it, r.srv) }
    }

    private suspend fun loadSegments(ctx: Context, r: MediaRef.InLibrary, e: Entry) {
        OfflineStore.get(ctx, r.key())?.let { o ->
            e.intro = o.intro?.let { it.first / 1000.0 to it.second / 1000.0 }
            e.creditsAt = o.creditsAt?.let { it / 1000.0 }
        }
        val api = Api.of(ctx, r.srv)?.takeIf { it.srv.segments } ?: return
        repeat(8) {  // the agent compares the sound with the neighbouring episodes on the first request
            val s = runCatching { api.segments(r.root, r.path) }.getOrNull() ?: return
            if (!s.pending) {
                if (s.introStart != null && s.introEnd != null && s.introEnd > s.introStart) e.intro = s.introStart / 1000.0 to s.introEnd / 1000.0
                s.creditsAt?.let { e.creditsAt = it / 1000.0 }
                return
            }
            delay(15_000)
        }
    }

    /** Is `s` still our video? mpv: its path; a TV and a Chromecast: the title we gave it. */
    private fun playsIt(e: Entry, s: RemoteState): Boolean {
        val t = e.ref.title
        val path = s.mpv?.optString("path")?.takeIf { it.isNotEmpty() }?.let { Uri.decode(it) }
        return if (path != null) path.contains(t) else s.title.isBlank() || s.title == t
    }

    /**
     * The target's state every ~1.5 s (UI thread). `idle`: it plays nothing now (stopped, ended); after our video was
     * near its end that counts as the end.
     */
    fun tick(ctx: Context, kind: String, s: RemoteState?, idle: Boolean) {
        val e = current.value?.takeIf { it.kind == kind } ?: return
        // something with a length plays there: ours (else forget it) or nothing (mpv left open without a file, a stopped TV)
        val live = s != null && !idle && s.dur > 0
        if (live && !playsIt(e, s!!)) { clear(ctx); return }  // something else plays there now
        val now = SystemClock.elapsedRealtime()
        val dt = if (e.lastTick == 0L) 0.0 else (now - e.lastTick) / 1000.0
        e.lastTick = now
        if (live) { e.pos = s!!.posNow(); e.dur = s.dur }
        val dur = e.dur
        if (dur <= 0 || e.started) return
        val ended = (!live && e.pos > dur - 30) || (live && !s!!.playing && !s.buffering && e.pos >= dur - 2)
        if (!live && !ended) { keepAwake(ctx, false); return }
        val n = e.next.firstOrNull()
        if (n != null && !e.subsAhead && e.pos > dur / 2) {
            e.subsAhead = true
            subsAhead(ctx, e, n)
        }
        keepAwake(ctx, n != null && dur - e.pos < 240)
        if (n == null) return
        val from = e.creditsAt?.takeIf { it > 0 && it < dur } ?: (dur - COUNT_S)
        if (!ended && e.pos < from - 1) {  // before the credits, or seeking back out of them
            e.cancelled = false
            e.left = COUNT_S.toDouble()
            setCountdown(ctx, e, null)
            return
        }
        if (e.cancelled) return setCountdown(ctx, e, null)
        if (ended || (live && s!!.playing && !s.buffering)) e.left -= dt  // stands while paused
        if (e.left <= 0) playNext(ctx) else setCountdown(ctx, e, ceil(e.left).toInt())
    }

    private fun subsAhead(ctx: Context, e: Entry, n: MediaRef.InLibrary) {
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            val api = Api.of(app, n.srv) ?: return@launch
            val lang = api.s.subChoice
            val provider = runCatching { api.subsList(e.ref) }.getOrNull()
                ?.firstOrNull { it.langKey == lang && it.default }?.source?.provider
            // mpv on the server that has the file shows the video's own tracks; a TV and a Chromecast do not
            val embedded = e.target is Target.Screen && e.target.key == n.srv
            prepareSubsAhead(app, api, n, provider, embeddedCounts = embedded)
        }
    }

    private fun setCountdown(ctx: Context, e: Entry, left: Int?) {
        if (countdown.value == left) return
        countdown.value = left
        notifyCountdown(ctx, left?.let { e.next.firstOrNull()?.title?.let { t -> t to it } })
    }

    /** Is a countdown running on `kind` (the service keeps that target's session meanwhile)? */
    fun holds(kind: String) = current.value?.kind == kind && countdown.value != null

    fun cancel(ctx: Context) {
        val e = current.value ?: return
        e.cancelled = true
        setCountdown(ctx, e, null)
    }

    fun skipIntro(ctx: Context) {
        val e = current.value ?: return
        val to = e.intro?.second ?: return
        scope.launch { runCatching { Pilot.remote(ctx, e.kind)?.seekTo(to) } }
    }

    /** The next episode on the same target, now. */
    fun playNext(ctx: Context) {
        val e = current.value ?: return
        val n = e.next.firstOrNull() ?: return
        if (e.started) return
        e.started = true
        setCountdown(ctx, e, null)
        keepAwake(ctx, false)
        val app = ctx.applicationContext
        Settings(app).markWatched(e.ref.key())
        Settings(app).setPosition(e.ref.key(), 0)
        val rest = e.next.drop(1)
        scope.launch {
            try {
                when (val t = e.target) {
                    is Target.Screen -> Screens.play(app, n, t.key, null, null, rest) { }
                    is Target.Chromecast -> Screens.cast(app, n, t.t, null, null, rest) { }
                    is Target.Tv -> Screens.dlna(app, n, t.t, null, null, rest) { }
                }
            } catch (ex: Exception) {
                e.started = false
                e.cancelled = true
                android.widget.Toast.makeText(app, ex.message ?: app.getString(R.string.media_error), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clear(ctx: Context) {
        current.value = null
        countdown.value = null
        notifyCountdown(ctx, null)
        keepAwake(ctx, false)
    }

    /** Near the end of an episode with a next one: the CPU stays on, so the countdown runs with the screen off. */
    private fun keepAwake(ctx: Context, on: Boolean) {
        if (on && wake?.isHeld != true) {
            wake = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magneticplayer:next-episode").apply { acquire(10 * 60_000L) }
        } else if (!on) {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
    }

    /** "Next: <episode> in 7 s" with Play now / Cancel; null removes it. */
    private fun notifyCountdown(ctx: Context, next: Pair<String, Int>?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (next == null) { nm.cancel(NOTIF_ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.tvq_channel), NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, req: Int) = PendingIntent.getService(ctx, req,
            Intent(ctx, RemoteSessionService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_next)
            .setContentTitle(ctx.getString(R.string.tvq_next_in, next.second))
            .setContentText(next.first)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, ctx.getString(R.string.tvq_play_now), action(ACTION_NOW, 11))
            .addAction(0, ctx.getString(R.string.tvq_cancel), action(ACTION_CANCEL, 12))
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/** Remote: "Skip intro" while it plays, "Next episode", and the countdown card, for the video sent from this phone. */
@Composable
fun TvQueueControls(kind: String, pos: Double) {
    val ctx = LocalContext.current
    val e by TvQueue.current
    val left by TvQueue.countdown
    val q = e?.takeIf { it.kind == kind } ?: return
    val intro = q.intro
    val next = q.next.firstOrNull()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (left != null && next != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tvq_next_in, left ?: 0), style = MaterialTheme.typography.titleSmall)
                    Text(next.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ TvQueue.cancel(ctx) }) { Text(stringResource(R.string.tvq_cancel)) }
                        Button({ TvQueue.playNext(ctx) }) { Text(stringResource(R.string.tvq_play_now)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (intro != null && pos >= intro.first && pos < intro.second - 1) FilledTonalButton({ TvQueue.skipIntro(ctx) }) {
                Icon(Icons.Filled.FastForward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_skip_intro))
            }
            if (next != null && left == null) FilledTonalButton({ TvQueue.playNext(ctx) }) {
                Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tvq_next_episode))
            }
        }
    }
}

/** For [Screens]: "No subtitles in <language>. Download them before sending?" as a dialog the sending waits for. */
class SubsAsk {
    val pending = mutableStateOf<Pair<String, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null)

    suspend fun ask(language: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pending.value = language to d }
        return try { d.await() } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { pending.value = null } }
    }
}

@Composable
fun SubsAskDialog(a: SubsAsk) {
    val (language, d) = a.pending.value ?: return
    androidx.compose.material3.AlertDialog(onDismissRequest = { d.complete(false) },
        title = { Text(stringResource(R.string.tvq_subs_ask_title, language)) },
        text = { Text(stringResource(R.string.tvq_subs_ask_text)) },
        confirmButton = { TextButton({ d.complete(true) }) { Text(stringResource(R.string.media_psubs_offer_yes)) } },
        dismissButton = { TextButton({ d.complete(false) }) { Text(stringResource(R.string.tvq_subs_ask_no)) } })
}

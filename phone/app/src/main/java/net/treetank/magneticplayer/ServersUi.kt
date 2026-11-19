package net.treetank.magneticplayer

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

val OK_GREEN = Color(0xFF3FA96B)
private val WARN = Color(0xFFF2B84B)

// ---------------------------------------------------------------- playback on a screen

object Screens {
    /**
     * Plays `ref` on screen `screenKey` (mpv on a server), waking it if needed. Same server: a local file
     * as before. Different one: a URL with a ticket from the file server or from the phone ([source]). Remembers the screen for Remote.
     */
    suspend fun play(ctx: Context, ref: MediaRef, screenKey: String, startSec: Double?, onStatus: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val screen = Api.of(ctx, screenKey) ?: throw ApiException(ctx.getString(R.string.srv_screen_gone))
            screen.ensureAwake(onStatus)
            if (screenKey == ref.srv) {
                // position from the phone: the video may have last been watched elsewhere (Chromecast, TV, phone)
                screen.playerOpen(ref, startSec ?: savedStart(ctx, ref))
            } else {
                val src = source(ctx, ref, onStatus)
                onStatus(ctx.getString(R.string.srv_opening_on, screen.srv.name))
                screen.playerOpenUrl(Ticket(src.url, src.subs.map { it.url }, ref.title), startSec ?: savedStart(ctx, ref))
            }
            Servers.setScreen(ctx, screenKey)
            Servers.setScreenRef(ctx, screenKey, ref)
            withContext(Dispatchers.Main) { Pilot.started(ctx, "screen") }
        }

    /** Plays `ref` on a Chromecast (Google's default receiver, subtitles as WebVTT). */
    suspend fun cast(ctx: Context, ref: MediaRef, target: CastTarget, startSec: Double?, onStatus: (String) -> Unit) {
        val src = withContext(Dispatchers.IO) { source(ctx, ref, onStatus, variant = "cast") }
        onStatus(ctx.getString(R.string.srv_connecting_to, target.name))
        val start = startSec ?: savedStart(ctx, ref)
        Cast.play(ctx, target, ref, src, ((start ?: 0.0) * 1000).toLong())
        Pilot.started(ctx, "cast")
        Settings(ctx).lastTv = target.name
    }

    /** Plays `ref` on a DLNA TV (Samsung and others): the original file, SRT subtitles in its own way. */
    suspend fun dlna(ctx: Context, ref: MediaRef, target: DlnaTarget, startSec: Double?, onStatus: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val src = source(ctx, ref, onStatus)
            onStatus(ctx.getString(R.string.srv_sending_to, target.name))
            Dlna.play(ctx, target, ref, src, startSec ?: savedStart(ctx, ref) ?: 0.0)
            Settings(ctx).lastTv = target.name
        }

    private fun savedStart(ctx: Context, ref: MediaRef) =
        Settings(ctx).position(ref.key()).takeIf { it > 5_000 }?.let { it / 1000.0 }

    /**
     * Where the screen gets the file: from the server (ticket) when it responds or the phone does not have the whole video (then we wake it).
     * The server does not respond (e.g. at someone's place as a guest), and the whole video is on the phone: the phone shares it.
     */
    suspend fun source(ctx: Context, ref: MediaRef, onStatus: (String) -> Unit, variant: String? = null): MediaSrc {
        val file = Api.of(ctx, ref.srv)
        val onPhone = PhoneShare.hasWhole(ctx, ref.key())
        if (file != null && (!onPhone || file.ping(1500))) {
            file.ensureAwake(onStatus)
            val t = file.ticket(ref, variant)
            return MediaSrc(t.url, ref.title, t.subInfo, false, t.contentType)
        }
        if (!onPhone) throw ApiException(ctx.getString(R.string.srv_file_server_gone))
        onStatus(ctx.getString(R.string.srv_sharing_from_phone))
        return PhoneShare.start(ctx, ref)
    }
}

/** Screen state for the picker list: null = checking, false = not responding, true = working (+ what is playing). */
private data class ScreenState(val online: Boolean?, val playing: String? = null)

/** "Where to play?": this phone or mpv on some server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenChooser(ref: MediaRef, onPhone: (() -> Unit)?, onPick: (String) -> Unit, onCast: (CastTarget) -> Unit,
                  onDlna: (DlnaTarget) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val screens = remember { Servers.screens(ctx) }
    val last = remember { Servers.screenKey(ctx) }
    val fileName = remember { Servers.get(ctx, ref.srv)?.name ?: "?" }
    val states = remember { mutableStateMapOf<String, ScreenState>() }
    val casts = rememberCastTargets()
    val castOk = remember { Cast.available(ctx) }
    val castNow = remember { Cast.session(ctx)?.castDevice?.friendlyName }
    val onPhoneWhole = remember { PhoneShare.hasWhole(ctx, ref.key()) }
    var searching by remember { mutableStateOf(true) }
    val lastTv = remember { Settings(ctx).lastTv }
    // DLNA TVs: first those known from the previous search, after 3 s those that have responded now
    var dlnas by remember { mutableStateOf(Dlna.found.values.toList()) }
    LaunchedEffect(Unit) { dlnas = withContext(Dispatchers.IO) { Dlna.discover() } }
    LaunchedEffect(Unit) { delay(8_000); searching = false }
    LaunchedEffect(Unit) {
        screens.forEach { s ->
            launch(Dispatchers.IO) {
                val a = Api(Settings(ctx), s)
                states[s.key] = if (a.ping(1500)) ScreenState(true, runCatching {
                    a.playerStatus().takeIf { it.optBoolean("running") && !it.optBoolean("idle") }?.optString("title")
                }.getOrNull()) else ScreenState(false)
            }
        }
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.srv_where_to_play), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.srv_title_file_from, ref.title, fileName), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onPhone != null) SheetRow(Icons.Filled.Smartphone, stringResource(R.string.srv_this_phone), null, null, false) { onPhone() }
            screens.forEach { s ->
                val st = states[s.key]
                val sub = buildList {
                    add(if (s.key == ref.srv) stringResource(R.string.srv_file_is_here) else stringResource(R.string.srv_over_network_from, fileName))
                    when {
                        st == null -> add(stringResource(R.string.srv_checking))
                        st.online == false -> add(stringResource(R.string.srv_asleep_will_wake))
                        st.playing != null -> add(stringResource(R.string.srv_now_playing, st.playing))
                    }
                }.joinToString(" · ")
                SheetRow(Icons.Filled.Tv, if (s.key == last && screens.size > 1) stringResource(R.string.srv_name_last, s.name) else s.name, sub,
                    when (st?.online) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null },
                    s.key == last) { onPick(s.key) }
            }
            if (castOk) {
                casts.forEach { c ->
                    val sub = listOfNotNull(
                        c.desc.takeIf { it.isNotBlank() },
                        if (onPhoneWhole) stringResource(R.string.srv_video_from_or_phone, fileName) else stringResource(R.string.srv_video_from, fileName),
                    ).joinToString(" · ")
                    SheetRow(Icons.Filled.Cast, when (c.name) { castNow -> stringResource(R.string.srv_name_connected, c.name); lastTv -> stringResource(R.string.srv_name_last, c.name); else -> c.name },
                        sub, null, c.name == castNow) { onCast(c) }
                }
                if (casts.isEmpty()) SheetRow(Icons.Filled.Cast, "Chromecast",
                    if (searching) stringResource(R.string.srv_searching_network) else stringResource(R.string.srv_no_chromecast), null, false, enabled = false) {}
                CastFormat(ref)
            }
            dlnas.forEach { d ->
                val sub = listOfNotNull("DLNA", d.model.ifBlank { null },
                    if (onPhoneWhole) stringResource(R.string.srv_video_from_or_phone, fileName) else stringResource(R.string.srv_video_from, fileName)).joinToString(" · ")
                SheetRow(Icons.Filled.ConnectedTv, if (d.name == lastTv) stringResource(R.string.srv_name_last, d.name) else d.name, sub, null,
                    Dlna.current?.id == d.id) { onDlna(d) }
            }
        }
    }
}

/** Under the Chromecasts: whether this file will play, and "Prepare version for Chromecast" (agent 1.2+ with ffmpeg). */
@Composable
private fun CastFormat(ref: MediaRef) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { Api.of(ctx, ref.srv) } ?: return
    var probe by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        while (true) {
            probe = withContext(Dispatchers.IO) { runCatching { if (api.ping(1500)) api.mediaProbe(ref) else null }.getOrNull() }
            val st = probe?.optJSONObject("job")?.optString("state")
            if (st != "queued" && st != "running") break
            delay(2_000)
        }
    }
    fun op(o: String?) = scope.launch {
        err = withContext(Dispatchers.IO) { runCatching { api.mediaConvert(ref, o) }.exceptionOrNull()?.message }
        tick++
    }
    val p = probe?.takeIf { it.optBoolean("ffmpeg") } ?: return
    val v = p.optJSONObject("variant")
    val c = p.optJSONObject("cast")
    val devs = p.optJSONArray("cast_devices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    val j = p.optJSONObject("job")
    val state = j?.optString("state")
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(start = 60.dp, end = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            v != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.srv_cast_prepared, humanBytes(v.optLong("size"))), Modifier.weight(1f),
                    style = small, color = OK_GREEN)
                TextButton({ op("remove") }) { Text(stringResource(R.string.srv_remove)) }
            }
            state == "queued" || state == "running" -> {
                val pct = j!!.optDouble("pct", 0.0)
                val speed = j.optDouble("speed").takeIf { !it.isNaN() }?.let { " · " + stringResource(R.string.srv_speed, it) } ?: ""
                if (j.optBoolean("finishing")) {
                    // ffmpeg +faststart rewrites the file at the end: without it the bar sits at 99% for up to a minute
                    Text(stringResource(R.string.srv_finishing), style = small)
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.srv_preparing, j.optString("encoder").ifEmpty { stringResource(R.string.srv_queued) }, pct.toInt(), speed),
                        style = small)
                    androidx.compose.material3.LinearProgressIndicator({ (pct / 100).toFloat() }, Modifier.fillMaxWidth())
                }
                TextButton({ op("cancel") }) { Text(stringResource(R.string.srv_stop)) }
            }
            // the agent knows the model of our Chromecasts: a rating for each, "Prepare" only when one has trouble
            devs.isNotEmpty() && devs.all { it.optString("verdict") == "ok" } -> devs.forEach { d ->
                Text(stringResource(R.string.srv_dev_plays_without, d.optString("name"), d.optString("label")), style = small, color = muted)
            }
            devs.isEmpty() && c?.optString("verdict") == "ok" -> Text(stringResource(R.string.srv_format_ok), style = small, color = muted)
            c != null -> {
                if (devs.isNotEmpty()) devs.forEach { d ->
                    val v = d.optString("verdict")
                    Text(stringResource(R.string.srv_dev_verdict, d.optString("name"), d.optString("label"), when (v) {
                        "ok" -> stringResource(R.string.srv_verdict_ok); "no" -> stringResource(R.string.srv_verdict_no)
                        else -> stringResource(R.string.srv_verdict_maybe) }),
                        style = small, color = if (v == "ok") muted else MaterialTheme.colorScheme.error)
                    val pr = d.optJSONArray("problems")
                    for (i in 0 until (pr?.length() ?: 0)) Text("· " + pr!!.getString(i), style = small, color = muted)
                } else {
                    val probs = c.optJSONArray("problems")
                    Text(if (c.optString("verdict") == "no") stringResource(R.string.srv_cast_no) else stringResource(R.string.srv_cast_maybe),
                        style = small, color = MaterialTheme.colorScheme.error)
                    for (i in 0 until (probs?.length() ?: 0)) Text("· " + probs!!.getString(i), style = small, color = muted)
                }
                if (state == "error") Text(stringResource(R.string.srv_last_attempt, j!!.optString("error").takeLast(160)), style = small,
                    color = MaterialTheme.colorScheme.error)
                val enc = p.optJSONObject("encoder")?.optString("label") ?: "?"
                val how = (if (c.optBoolean("video_copy")) stringResource(R.string.srv_video_copy) else stringResource(R.string.srv_video_enc, enc)) +
                    ", " + (if (c.optBoolean("audio_copy")) stringResource(R.string.srv_audio_copy) else stringResource(R.string.srv_audio_aac))
                TextButton({ op(null) }, enabled = p.optBoolean("complete")) {
                    Text(if (p.optBoolean("complete")) stringResource(R.string.srv_prepare_for_cast, how) else stringResource(R.string.srv_prepare_when_complete))
                }
            }
        }
        err?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, dot: Color?, selected: Boolean,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (dot != null) Text("●", color = dot, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---------------------------------------------------------------- server switcher

/** Bottom panel: which server the videos are from (Torrents, Library). A server with only a screen is there, but greyed out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(current: String?, onPick: (String) -> Unit, onWake: (String) -> Unit, onAdd: () -> Unit,
                onManage: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember { Servers.all(ctx) }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        while (true) {
            servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
            delay(4000)
        }
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.srv_videos_from_server), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
            servers.forEach { s ->
                val on = online[s.key]
                val status = when (on) { true -> stringResource(R.string.srv_status_online); false -> stringResource(R.string.srv_status_asleep); null -> "…" }
                SheetRow(if (s.hasPlayer) Icons.Filled.Computer else Icons.Filled.Dns, s.name,
                    if (s.hasFiles) "${s.rolesText(ctx)} · $status" else stringResource(R.string.srv_screen_only_status, status),
                    when (on) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null },
                    s.key == current, enabled = s.hasFiles,
                    trailing = if (on == false && s.mac.isNotBlank()) ({ TextButton({ onWake(s.key) }) { Text(stringResource(R.string.srv_wake)) } }) else null,
                ) { onPick(s.key) }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetRow(Icons.Filled.Add, stringResource(R.string.srv_add_server), null, null, false) { onAdd() }
            SheetRow(Icons.Filled.Settings, stringResource(R.string.srv_manage_servers), null, null, false) { onManage() }
        }
    }
}

/** The selected server does not respond: instead of an "asleep" error, with a wake button. */
@Composable
fun AsleepPane(server: Server, waking: String?, wrongServer: Boolean, onWake: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (waking != null) Icons.Filled.PowerSettingsNew else Icons.Filled.Bedtime, null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (wrongServer) {
            Text(stringResource(R.string.srv_other_server_at, server.host), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.srv_router_new_address, server.name),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(if (waking != null) stringResource(R.string.srv_waking, server.name) else stringResource(R.string.srv_is_asleep, server.name),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (waking != null) {
            LinearProgressIndicator(Modifier.width(200.dp))
            Spacer(Modifier.height(8.dp))
            Text(waking, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val (txt, col) = when (server.wakeConfidence) {
            "yes" -> stringResource(R.string.srv_wake_worked) to OK_GREEN
            "no" -> stringResource(R.string.srv_wake_unlikely, server.wakeReason(ctx)) to MaterialTheme.colorScheme.error
            else -> stringResource(R.string.srv_wake_unchecked, server.wakeReason(ctx)) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(txt, style = MaterialTheme.typography.bodySmall, color = col, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (server.mac.isNotBlank()) Button(onWake) { Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.srv_wake)) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.srv_offline_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** "Suspend?" with a warning depending on whether it can be woken afterwards. */
@Composable
fun SuspendDialog(server: Server, poweroff: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val what = if (poweroff) stringResource(R.string.srv_poweroff) else stringResource(R.string.srv_suspend)
    val whatAnyway = if (poweroff) stringResource(R.string.srv_poweroff_anyway) else stringResource(R.string.srv_suspend_anyway)
    val conf = server.wakeConfidence
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (conf == "no") ({ Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = { Text(if (poweroff) stringResource(R.string.srv_poweroff_q, server.name) else stringResource(R.string.srv_suspend_q, server.name)) },
        text = {
            Column {
                Text(stringResource(R.string.srv_downloads_stop) + if (conf == "yes") " " + stringResource(R.string.srv_wake_from_app) else "")
                if (conf == "maybe") Surface(color = WARN.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.srv_wake_not_checked_phone, server.wakeReason(ctx)),
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (conf == "no") Text(stringResource(R.string.srv_cant_wake, server.wakeReason(ctx)),
                    Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(if (conf == "no") whatAnyway else what,
                    color = if (conf == "no") MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.srv_cancel)) } },
    )
}

// ---------------------------------------------------------------- Settings: servers

/** List of servers in Settings: details, adding, pairing further devices. */
@Composable
fun ServersSection(ver: Int, snack: SnackbarHostState, openAdd: Boolean, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember(ver) { Servers.all(ctx) }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var details by remember { mutableStateOf<String?>(null) }
    var add by remember(openAdd) { mutableStateOf(openAdd) }
    var invite by remember { mutableStateOf(false) }
    LaunchedEffect(ver) {
        servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
    }
    Text(stringResource(R.string.srv_servers), style = MaterialTheme.typography.titleMedium)
    if (servers.isEmpty()) Text(stringResource(R.string.srv_no_servers_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        servers.forEach { s ->
            SheetRow(if (s.hasPlayer) Icons.Filled.Computer else Icons.Filled.Dns, s.name, "${s.host} · ${s.rolesText(ctx)}",
                when (online[s.key]) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null }, false) {
                details = s.key
            }
        }
        SheetRow(Icons.Filled.Add, stringResource(R.string.srv_add_server), null, null, false) { add = true }
    }
    if (servers.any { !it.isGuest }) OutlinedButton({ invite = true }) {
        Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.srv_pair_another))
    }
    var install by remember { mutableStateOf(false) }
    if (servers.any { it.canInstall } || InstallShare.available(ctx)) OutlinedButton({ install = true }) {
        Icon(Icons.Filled.InstallDesktop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.srv_install_other))
    }
    if (install) InstallDialog(onDone = onChanged) { install = false }
    details?.let { k -> ServerDetailsDialog(k, snack, onChanged = onChanged) { details = null } }
    if (add) AddServerDialog(onDone = { onChanged() }) { add = false }
    if (invite) InviteDialog(Servers.activeKey(ctx)) { invite = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerDetailsDialog(key: String, snack: SnackbarHostState, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Servers.get(ctx, key)) }
    val s = server ?: return onDismiss()
    var name by remember { mutableStateOf(s.name) }
    var devices by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    var invite by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var searches by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        try {
            withContext(Dispatchers.IO) {
                val api = Api(Settings(ctx), s)
                api.info()
                val a = api.devices()
                devices = List(a.length()) { a.getJSONObject(it) }
            }
            server = Servers.get(ctx, key)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${s.host}:${s.port}" + (s.info?.optString("version")?.takeIf { it.isNotEmpty() }?.let { " · " + stringResource(R.string.srv_agent_version, it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.hasTorrent) AssistChip({}, { Text(stringResource(R.string.srv_chip_torrents_client, s.info?.optJSONObject("features")?.optString("torrent") ?: stringResource(R.string.srv_yes))) })
                    if (s.hasLibrary) AssistChip({}, { Text(stringResource(R.string.srv_chip_library)) })
                    if (s.hasPlayer) AssistChip({}, { Text(stringResource(R.string.srv_chip_screen_mpv)) })
                    if (s.canUnlock && s.hasPlayer) AssistChip({}, { Text(stringResource(R.string.srv_chip_unlock)) })
                    s.transcode?.let { AssistChip({}, { Text(stringResource(R.string.srv_chip_cast_versions, it)) }) }
                }
                if (!s.knowsFeatures) Text(stringResource(R.string.srv_old_agent),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val wake = when (s.wakeConfidence) {
                    "yes" -> stringResource(R.string.srv_wake_works)
                    "no" -> stringResource(R.string.srv_wake_no, s.wakeReason(ctx))
                    else -> stringResource(R.string.srv_wake_untested, s.wakeReason(ctx))
                }
                Text(stringResource(R.string.srv_suspend_line, if (s.canSuspend) stringResource(R.string.srv_yes) else stringResource(R.string.srv_no), wake), style = MaterialTheme.typography.bodySmall)
                s.appUpdates?.takeIf { !s.isGuest }?.let { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.srv_app_updates), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (mode == "auto") R.string.srv_app_updates_auto else R.string.srv_app_updates_manual),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(mode == "auto", { on ->
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Api(Settings(ctx), s).run { setAppUpdates(on); info() } }
                                    server = Servers.get(ctx, key)
                                } catch (e: ApiException) {
                                    snack.showSnackbar(e.message ?: ctx.getString(R.string.srv_error))
                                }
                            }
                        })
                    }
                }
                s.agentUpdates?.takeIf { !s.isGuest }?.let { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.srv_agent_updates, s.info?.optString("version") ?: "?"), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (mode == "auto") R.string.srv_agent_updates_auto else R.string.srv_agent_updates_manual),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(mode == "auto", { on ->
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Api(Settings(ctx), s).run { setAgentUpdates(on); info() } }
                                    server = Servers.get(ctx, key)
                                } catch (e: ApiException) {
                                    snack.showSnackbar(e.message ?: ctx.getString(R.string.srv_error))
                                }
                            }
                        })
                    }
                    s.agentAvailable?.let { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.srv_agent_available, v), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton({
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { Api(Settings(ctx), s).updateAgent() }
                                        snack.showSnackbar(ctx.getString(R.string.srv_agent_update_started))
                                    } catch (e: ApiException) {
                                        snack.showSnackbar(e.message ?: ctx.getString(R.string.srv_error))
                                    }
                                }
                            }) { Text(stringResource(R.string.srv_agent_update_now)) }
                        }
                    }
                }
                if (s.shrinkEncoder != null && !s.isGuest) ShrinkSettings(s, snack, reload) { server = Servers.get(ctx, key) }
                // search engines for titles that are not on the disks (agent 1.8+): the user's own, none by default
                if (s.hasCatalog && !s.isGuest) Row(Modifier.fillMaxWidth().clickable { searches = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cat_searches), style = MaterialTheme.typography.bodyMedium)
                        Text(if (s.searchEngines == 0) stringResource(R.string.cat_searches_none_set)
                            else pluralStringResource(R.plurals.cat_searches_n, s.searchEngines, s.searchEngines),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.cat_searches_edit), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                if (searches) SearchEnginesDialog(Api(Settings(ctx), s)) { searches = false; server = Servers.get(ctx, key) }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.srv_name_in_app)) }, singleLine = true)
                HorizontalDivider()
                Text(stringResource(R.string.srv_paired_devices), style = MaterialTheme.typography.titleSmall)
                when (val list = devices) {
                    null -> if (error == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> list.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.optString("name") + (if (d.optString("role") == "guest") " · " + stringResource(R.string.srv_dev_guest) +
                                    (if (d.optDouble("expires", 0.0) > 0) " " + stringResource(R.string.srv_dev_until, java.text.SimpleDateFormat("d.MM", java.util.Locale("pl"))
                                        .format(java.util.Date((d.optDouble("expires") * 1000).toLong()))) else "") else "") +
                                    if (d.optBoolean("current")) " · " + stringResource(R.string.srv_dev_this_phone) else "")
                                Text(lastSeen(ctx, d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!d.optBoolean("current")) TextButton({ revoke = d }) {
                                Text(if (d.optBoolean("legacy")) stringResource(R.string.srv_disable) else stringResource(R.string.srv_revoke), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ invite = true }) { Text(stringResource(R.string.srv_pair_more)) }
                    TextButton({ confirmRemove = true }) { Text(stringResource(R.string.srv_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (name.isNotBlank() && name != s.name) {
                    Servers.update(ctx, key) { it.copy(name = name.trim()) }
                    onChanged()
                }
                onDismiss()
            }) { Text(stringResource(R.string.srv_close)) }
        },
        dismissButton = { TextButton({ reload++ }) { Text(stringResource(R.string.srv_test)) } },
    )
    if (invite) InviteDialog(key) { invite = false }
    revoke?.let { d ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text(if (d.optBoolean("legacy")) stringResource(R.string.srv_disable_legacy_q) else stringResource(R.string.srv_revoke_q)) },
            text = {
                Text(if (d.optBoolean("legacy")) stringResource(R.string.srv_disable_legacy_text, s.name)
                else stringResource(R.string.srv_revoke_text, d.optString("name"), s.name))
            },
            confirmButton = {
                TextButton({
                    revoke = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api(Settings(ctx), s).revoke(d.getString("id")) }
                            reload++
                        } catch (e: ApiException) {
                            snack.showSnackbar(e.message ?: ctx.getString(R.string.srv_error))
                        }
                    }
                }) { Text(if (d.optBoolean("legacy")) stringResource(R.string.srv_disable) else stringResource(R.string.srv_revoke), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ revoke = null }) { Text(stringResource(R.string.srv_cancel)) } },
        )
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.srv_remove_q, s.name)) },
        text = { Text(stringResource(R.string.srv_remove_text)) },
        confirmButton = {
            TextButton({ confirmRemove = false; Servers.remove(ctx, key); onChanged(); onDismiss() }) {
                Text(stringResource(R.string.srv_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.srv_cancel)) } },
    )
}

/** "Processor (x265)" -> "Processor": the short name of an encoder for "Automatic (…)". */
private fun shortLabel(label: String) = label.substringBefore(" (").ifEmpty { label }

/** Shrinking settings of a server (agent 1.7+, owner only): which encoder and whether to go on while someone watches. */
@Composable
private fun ShrinkSettings(s: Server, snack: SnackbarHostState, reload: Int, onInfo: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf<ShrinkConfig?>(null) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        // while the agent is still checking the graphics card, ask again a few times
        repeat(10) {
            val c = runCatching { withContext(Dispatchers.IO) { Api(Settings(ctx), s).shrinkConfig() } }.getOrNull() ?: return@LaunchedEffect
            cfg = c
            if (!c.detecting) return@LaunchedEffect
            delay(3000)
        }
    }
    fun save(encoder: String? = null, whileWatching: String? = null) {
        scope.launch {
            try {
                cfg = withContext(Dispatchers.IO) { Api(Settings(ctx), s).run { setShrinkConfig(encoder, whileWatching).also { info() } } }
                onInfo()
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: ctx.getString(R.string.srv_error))
            }
        }
    }
    val c = cfg
    val current = c?.encoder ?: s.shrinkEncoder ?: "auto"
    val currentText = when {
        c == null -> if (current == "auto") stringResource(R.string.srv_shrink_auto) else current
        current == "auto" -> c.label(c.auto)?.let { stringResource(R.string.srv_shrink_auto_is, shortLabel(it)) }
            ?: stringResource(R.string.srv_shrink_auto)
        else -> c.label(current) ?: current
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = c != null) { pick = true }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.srv_shrink_encoder), style = MaterialTheme.typography.bodyMedium)
            Text(currentText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c?.detecting == true) Text(stringResource(R.string.srv_shrink_detecting), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c != null) TextButton({ pick = true }) { Text(stringResource(R.string.srv_shrink_change)) }
    }
    val watching = c?.whileWatching ?: s.shrinkWhileWatching ?: "continue"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.srv_shrink_while_watching), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (watching == "continue") R.string.srv_shrink_while_watching_on else R.string.srv_shrink_while_watching_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(watching == "continue", { on -> save(whileWatching = if (on) "continue" else "pause") })
    }
    if (pick && c != null) {
        var chosen by remember { mutableStateOf(c.encoder) }
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text(stringResource(R.string.srv_shrink_encoder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val options = listOf(Triple("auto", c.label(c.auto)?.let { stringResource(R.string.srv_shrink_auto_is, shortLabel(it)) }
                        ?: stringResource(R.string.srv_shrink_auto), stringResource(R.string.srv_shrink_auto_note))) +
                        c.encoders.map { Triple(it.id, it.label, it.note) }
                    options.forEach { (id, label, note) ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(chosen == id, { chosen = id })
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ pick = false; if (chosen != c.encoder) save(encoder = chosen) }) { Text(stringResource(R.string.srv_shrink_save)) }
            },
            dismissButton = { TextButton({ pick = false }) { Text(stringResource(R.string.srv_cancel)) } },
        )
    }
}

private fun lastSeen(ctx: Context, d: JSONObject): String {
    val t = d.optDouble("last_seen").takeIf { !it.isNaN() && it > 0 } ?: return ctx.getString(R.string.srv_never_connected)
    val ago = (System.currentTimeMillis() / 1000.0 - t).toLong()
    return ctx.getString(R.string.srv_last_seen, when {
        ago < 120 -> ctx.getString(R.string.srv_now)
        ago < 3600 -> ctx.getString(R.string.srv_min_ago, (ago / 60).toInt())
        ago < 86400 -> ctx.getString(R.string.srv_h_ago, (ago / 3600).toInt())
        else -> (ago / 86400).toInt().let { ctx.resources.getQuantityString(R.plurals.srv_days_ago, it, it) }
    })
}

/** "Add server": address + code (or a pairing link from the clipboard); after connecting, a summary of capabilities. */
@Composable
fun AddServerDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<List<Server>?>(null) }

    fun run(targets: List<PairTarget>) {
        busy = true; error = null
        scope.launch {
            try {
                added = withContext(Dispatchers.IO) { targets.map { Pairing.pair(ctx, it) } }
                onDone()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = ctx.getString(R.string.srv_failed, e.message)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (added != null) stringResource(R.string.srv_connected) else stringResource(R.string.srv_add_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val done = added
                if (done != null) {
                    done.forEach { s -> ServerSummary(s) }
                    return@Column
                }
                Text(stringResource(R.string.srv_pair_code_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.srv_ip_address)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.srv_port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.srv_pair_code_or_token)) },
                    singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    val link = Regex("""magneticplayer://pair\S+""").find(text)?.value
                    val targets = link?.let { Pairing.parse(android.net.Uri.parse(it)) }.orEmpty()
                    if (targets.isEmpty()) error = ctx.getString(R.string.srv_no_pair_link) else run(targets)
                }, enabled = !busy) { Text(stringResource(R.string.srv_paste_pair_link)) }
            }
        },
        confirmButton = {
            if (added != null) TextButton(onDismiss) { Text(stringResource(R.string.srv_done)) }
            else TextButton({
                val p = port.toIntOrNull() ?: 8765
                run(listOf(if (Pairing.looksLikeCode(code)) PairTarget(host, p, code, null, null) else PairTarget(host, p, null, code, null)))
            }, enabled = !busy && host.isNotBlank() && code.isNotBlank()) { Text(stringResource(R.string.srv_connect)) }
        },
        dismissButton = { if (added == null) TextButton(onDismiss) { Text(stringResource(R.string.srv_cancel)) } },
    )
}

/** What the server can and cannot do (after adding). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerSummary(s: Server) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("✓ ${s.name}", style = MaterialTheme.typography.titleSmall, color = OK_GREEN)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.hasTorrent) AssistChip({}, { Text(stringResource(R.string.srv_chip_torrents)) })
            if (s.hasLibrary) AssistChip({}, { Text(stringResource(R.string.srv_chip_library)) })
            if (s.hasPlayer) AssistChip({}, { Text(stringResource(R.string.srv_chip_screen)) })
        }
        if (!s.hasPlayer) Text(stringResource(R.string.srv_no_screen),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (s.wakeConfidence) {
            "maybe" -> Text(stringResource(R.string.srv_wake_maybe_summary, s.wakeReason(ctx)), style = MaterialTheme.typography.bodySmall, color = WARN)
            "no" -> if (s.canSuspend) Text(stringResource(R.string.srv_wake_no_summary, s.wakeReason(ctx)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * "Pair another device": one-time codes from the selected servers in one QR (a new phone scans once
 * and knows all of them). Codes work once and expire after 10 minutes.
 */
@Composable
fun InviteDialog(preselect: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember { Servers.all(ctx).filter { !it.isGuest } }
    val checked = remember { mutableStateMapOf<String, Boolean>().apply { servers.forEach { put(it.key, servers.size == 1 || it.key == preselect) } } }
    var guest by remember { mutableStateOf(false) }
    var guestDays by remember { mutableIntStateOf(7) }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var codes by remember { mutableStateOf<List<Pair<Server, JSONObject>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
    }
    LaunchedEffect(codes) {
        val c = codes ?: return@LaunchedEffect
        left = c.minOfOrNull { it.second.optInt("expires_in", 600) } ?: 600
        while (left > 0) { delay(1000); left-- }
    }
    fun generate() = scope.launch {
        error = null
        try {
            codes = withContext(Dispatchers.IO) {
                servers.filter { checked[it.key] == true }.map { s -> s to Api(Settings(ctx), s).pairCode(if (guest) guestDays else null) }
            }
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val link = codes?.let { c -> Pairing.link(c.map { (s, o) -> Triple(s.host, s.port, o.getString("code")) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.srv_pair_another)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (link == null) {
                    Text(stringResource(R.string.srv_which_servers), Modifier.fillMaxWidth())
                    servers.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { checked[s.key] = checked[s.key] != true }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked[s.key] == true, { checked[s.key] = it }, enabled = online[s.key] != false)
                            Text(if (online[s.key] == false) stringResource(R.string.srv_not_responding_suffix, s.name) else s.name)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.srv_for_whom), Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!guest, { guest = false }, { Text(stringResource(R.string.srv_role_member)) })
                        FilterChip(guest, { guest = true }, { Text(stringResource(R.string.srv_role_guest)) })
                    }
                    Text(if (guest) stringResource(R.string.srv_guest_desc) else stringResource(R.string.srv_member_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (guest) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to stringResource(R.string.srv_limit_day), 7 to stringResource(R.string.srv_limit_week), 0 to stringResource(R.string.srv_limit_none)).forEach { (d, label) ->
                            FilterChip(guestDays == d, { guestDays = d }, { Text(label) })
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    return@Column
                }
                val qr = remember(link) { qrBitmap(link, 720) }
                androidx.compose.foundation.Image(qr.asImageBitmap(), stringResource(R.string.srv_qr_desc),
                    Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                Spacer(Modifier.height(8.dp))
                Text(if (left <= 0) stringResource(R.string.srv_code_expired)
                    else if (left >= 3600) stringResource(R.string.srv_valid_h, left / 3600, (left % 3600) / 60)
                    else stringResource(R.string.srv_valid_ms, left / 60, left % 60),
                    style = MaterialTheme.typography.labelMedium, color = if (left > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.srv_new_phone_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                codes!!.forEach { (s, o) ->
                    val c = o.getString("code")
                    Text(stringResource(R.string.srv_code_line, s.name, s.host, c.take(3), c.drop(3)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                val (ws, wo) = codes!!.first()
                val web = "http://${ws.host}:${ws.port}/#p=${wo.getString("code")}"
                Text(stringResource(R.string.srv_laptop_browser, ws.name), style = MaterialTheme.typography.labelMedium)
                Text(web, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        copyText(ctx, ctx.getString(R.string.srv_clip_web), web)
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.srv_copied_once), android.widget.Toast.LENGTH_SHORT).show()
                    })
            }
        },
        confirmButton = {
            if (link == null) TextButton({ generate() }, enabled = checked.values.any { it }) { Text(stringResource(R.string.srv_show_code)) }
            else TextButton(onDismiss) { Text(stringResource(R.string.srv_close)) }
        },
        dismissButton = {
            if (link != null) TextButton({
                val text = if (!guest) link else {
                    val (ws, wo) = codes!!.first()
                    ctx.getString(R.string.srv_invite_text, codes!!.joinToString { it.first.name },
                        "http://${ws.host}:${ws.port}/#p=${wo.getString("code")}", link,
                        if (guestDays > 0) ctx.getString(R.string.srv_invite_access,
                            ctx.resources.getQuantityString(R.plurals.srv_invite_days, guestDays, guestDays)) else ".")
                }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), ctx.getString(if (guest) R.string.srv_invite_chooser_guest else R.string.srv_invite_chooser_link)))
            }) { Text(if (guest) stringResource(R.string.srv_send_invite) else stringResource(R.string.srv_share_link)) }
            else TextButton(onDismiss) { Text(stringResource(R.string.srv_cancel)) }
        },
    )
}

fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}


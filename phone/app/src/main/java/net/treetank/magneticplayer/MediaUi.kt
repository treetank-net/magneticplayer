package net.treetank.magneticplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Buttons for a video: phone, screen (mpv on some server), and in the menu VLC, subtitles, download.
 * [big] (catalog title screen): a large "Play" on the phone, then screen, TV/Chromecast and download as icons, the rest in
 * the menu; the same actions. [imdb] lets the phone player offer "What next" after the film.
 */
@Composable
fun MediaActions(api: Api, ref: MediaRef, snack: SnackbarHostState, onPlayingOnPc: () -> Unit,
                 imdb: String? = null, big: Boolean = false, onPlay: (() -> Unit)? = null, playLabel: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var subsDialog by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    val screens = remember { Servers.screens(ctx) }
    fun playOn(key: String?, cast: CastTarget? = null, tv: DlnaTarget? = null) = scope.launch {
        opening = ctx.getString(R.string.media_opening)
        try {
            if (cast != null) Screens.cast(ctx, ref, cast, null) { opening = it }
            else if (tv != null) Screens.dlna(ctx, ref, tv, null) { opening = it }
            else Screens.play(ctx, ref, key!!, null) { opening = it }
            onPlayingOnPc()
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
        } finally {
            opening = null
        }
    }
    // notification with download progress (Android 13+ asks for permission; downloading works without it anyway)
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    fun download() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(ctx, ref)
        scope.launch { snack.showSnackbar(ctx.getString(R.string.media_downloading_snack)) }
    }
    fun onScreen() {
        // one screen and on the same server: immediately, as before; otherwise a screen picker
        if (screens.size == 1 && screens[0].key == ref.srv) playOn(screens[0].key) else chooser = true
    }
    @Composable
    fun moreMenu(withDownload: Boolean) = Box {
        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.media_cd_more)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.media_open_vlc)) }, {
                menu = false
                scope.launch {
                    val subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
                    openInVlc(ctx, api.mediaUrl(ref), ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
                }
            }, leadingIcon = { Icon(Icons.Filled.OndemandVideo, null) })
            DropdownMenuItem({ Text(stringResource(R.string.media_subs_menu)) }, { menu = false; subsDialog = true },
                leadingIcon = { Icon(Icons.Filled.Subtitles, null) })
            if (withDownload) DropdownMenuItem({ Text(stringResource(R.string.media_download_to_phone)) }, {
                menu = false; download()
            }, leadingIcon = { Icon(Icons.Filled.DownloadForOffline, null) })
        }
    }
    if (big) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Button({ onPlay?.invoke() ?: ctx.startActivity(PlayerActivity.intent(ctx, ref, imdb)) },
            Modifier.weight(1f).height(48.dp)) {
            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
            Text(playLabel ?: stringResource(R.string.cat_play), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (screens.isNotEmpty()) androidx.compose.material3.FilledTonalIconButton(::onScreen, Modifier.size(48.dp)) {
            Icon(Icons.Filled.Computer, stringResource(R.string.cat_cd_on_computer))
        }
        androidx.compose.material3.FilledTonalIconButton({ chooser = true }, Modifier.size(48.dp)) {
            Icon(Icons.Filled.Cast, stringResource(R.string.media_cd_cast))
        }
        androidx.compose.material3.FilledTonalIconButton(::download, Modifier.size(48.dp)) {
            Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.media_download_to_phone))
        }
        moreMenu(withDownload = false)
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton({ ctx.startActivity(PlayerActivity.intent(ctx, ref, imdb)) }) {
            Icon(Icons.Filled.Smartphone, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.media_phone_button))
        }
        if (screens.isNotEmpty()) FilledTonalButton(::onScreen) {
            Icon(Icons.Filled.Tv, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (screens.size == 1) screens[0].name.take(14) else stringResource(R.string.media_on_screen_ellipsis))
        }
        // Chromecasts (and the other screens) in a single picker
        IconButton({ chooser = true }) { Icon(Icons.Filled.Cast, stringResource(R.string.media_cd_cast)) }
        moreMenu(withDownload = true)
    }
    if (subsDialog) SubsDialog(api, ref) { subsDialog = false }
    if (chooser) ScreenChooser(ref, onPhone = null, onPick = { chooser = false; playOn(it) },
        onCast = { chooser = false; playOn(null, it) }, onDlna = { chooser = false; playOn(null, tv = it) }) { chooser = false }
    opening?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text(msg)
            }
        })
    }
}

private val PROVIDER_NAMES = mapOf(
    "opensubtitlescom" to "OpenSubtitles", "podnapisi" to "Podnapisi", "bsplayer" to "BSPlayer",
    "gestdown" to "Gestdown (Addic7ed)", "napiprojekt" to "NapiProjekt", "tvsubtitles" to "TVsubtitles",
)

/** Subtitles: those already next to the video, + a search on the computer with progress for each source. */
@Composable
fun SubsDialog(api: Api, ref: MediaRef, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var have by remember { mutableStateOf<List<SubFile>?>(null) }
    var progress by remember { mutableStateOf<SubsProgress?>(null) }
    var found by remember { mutableStateOf<SubsFound?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<SubsDownloaded?>(null) }
    // manual title correction (e.g. a Spanish file, subtitles under the English title)
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var override by remember { mutableStateOf<Pair<String?, Int?>>(null to null) }
    var nonce by remember { mutableIntStateOf(0) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }

    LaunchedEffect(ref) {
        have = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(ref, nonce) {
        found = null; error = null; progress = null; stopping = false
        try {
            val (t, y) = override
            val job = withContext(Dispatchers.IO) { api.subsSearchStart(ref, t, y) }
            jobId = job
            while (true) {
                val p = withContext(Dispatchers.IO) { api.subsJob(job) }
                progress = p
                if (p.done) {
                    if (p.error != null) error = p.error
                    found = p.found
                    p.found?.let { f -> if (title.isEmpty()) title = f.title; if (year.isEmpty()) year = f.year?.toString() ?: "" }
                    break
                }
                delay(700)
            }
        } catch (e: ApiException) {
            error = e.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_subs_title), maxLines = 1) },
        text = {
            Column {
                Text(ref.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                have?.takeIf { it.isNotEmpty() }?.let { list ->
                    val bad = stringResource(R.string.media_subs_bad_mark)
                    Text(stringResource(R.string.media_subs_have, list.joinToString {
                        it.name.takeLast(30) + when (it.fit) { "good" -> " ✓"; "bad" -> bad; else -> "" }
                    }), style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FA96B))
                    Spacer(Modifier.height(4.dp))
                }
                done?.let {
                    Text(stringResource(R.string.media_subs_done, it.name),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FA96B))
                    // agent 1.9+: how they fit this copy of the film (speech detection)
                    when (it.fit) {
                        "good" -> stringResource(if (it.changed) R.string.media_subs_fit_fixed else R.string.media_subs_fit_good)
                        "bad" -> stringResource(R.string.media_subs_fit_bad)
                        "unsure", "error" -> stringResource(R.string.media_subs_fit_unsure)
                        else -> null
                    }?.let { t ->
                        Text(t, style = MaterialTheme.typography.bodySmall,
                            color = if (it.fit == "bad") MaterialTheme.colorScheme.error else Color(0xFF3FA96B))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (busy != null && api.srv.hasSubsFit) {
                    Text(stringResource(R.string.media_subs_fitting), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                val f = found
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    f == null -> {
                        SearchProgress(progress)
                        val p = progress
                        // one source already has something while another is still working: let the user skip the wait
                        if (p != null && p.phase == "search" && p.providers.any { it.state == "running" || it.state == "pending" }) {
                            TextButton({
                                stopping = true
                                jobId?.let { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { api.subsStop(id) } } } }
                            }, enabled = !stopping) {
                                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (stopping) R.string.media_subs_stopping else R.string.media_subs_enough))
                            }
                        }
                    }
                    else -> {
                        if (f.hidden > 0) Text(pluralStringResource(R.plurals.media_subs_hidden, f.hidden, f.hidden),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.results.isEmpty()) Text(stringResource(R.string.media_subs_nothing, f.query, api.s.subLanguages),
                            style = MaterialTheme.typography.bodyMedium)
                        else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(f.results, key = { it.key }) { r ->
                                SubRow(r, busy) {
                                    busy = r.key
                                    scope.launch {
                                        try {
                                            done = withContext(Dispatchers.IO) { api.subsDownload(ref, r, override.first, override.second) }
                                            have = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrNull() }
                                        } catch (e: ApiException) {
                                            error = e.message
                                        } finally {
                                            busy = null
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                if (found != null || error != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.media_subs_field_title)) }, singleLine = true)
                        OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.width(76.dp),
                            label = { Text(stringResource(R.string.media_subs_field_year)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    TextButton({ override = title.ifBlank { null } to year.toIntOrNull(); nonce++ }) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.media_subs_search_title))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_close)) } },
    )
}

@Composable
private fun SearchProgress(p: SubsProgress?) {
    val phase = when {
        p == null -> stringResource(R.string.media_subs_connecting)
        p.phase == "file" -> stringResource(R.string.media_subs_hashing)
        p.phase == "match" -> stringResource(R.string.media_subs_matching, p.matched, p.total)
        p.round == 2 -> stringResource(R.string.media_subs_round2, p.query.toString())
        else -> stringResource(R.string.media_subs_searching, p.query ?: "…")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(phase, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        p?.let { Text("${it.elapsed.toInt()} s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (p?.phase == "match" && p.total > 0) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { p.matched.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    p?.providers?.forEach { pr ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                when (pr.state) {
                    "running" -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    "done" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = Color(0xFF3FA96B))
                    "error" -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    "skip" -> Icon(Icons.Filled.Remove, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(PROVIDER_NAMES[pr.name] ?: pr.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            val info = when (pr.state) {
                "running" -> stringResource(R.string.media_subs_prov_running)
                "done" -> pluralStringResource(R.plurals.media_subs_prov_results, pr.count ?: 0, pr.count ?: 0) +
                    (pr.secs?.let { " · ${"%.0f".format(it)} s" } ?: "")
                "pending" -> stringResource(R.string.media_subs_prov_pending)
                else -> pr.error ?: pr.state
            }
            Text(info, style = MaterialTheme.typography.labelSmall,
                color = if (pr.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubRow(r: SubResult, busy: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = busy == null, onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.lang.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            if (r.hashMatch) {
                Icon(Icons.Filled.CheckCircle, stringResource(R.string.media_subs_matches_file), Modifier.size(16.dp), tint = Color(0xFF3FA96B))
                Text(" " + stringResource(R.string.media_subs_matches_file), style = MaterialTheme.typography.labelSmall, color = Color(0xFF3FA96B))
            }
            Spacer(Modifier.weight(1f))
            if (busy == r.key) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(PROVIDER_NAMES[r.provider] ?: r.provider, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.release, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (r.hi) Text(stringResource(R.string.media_subs_hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

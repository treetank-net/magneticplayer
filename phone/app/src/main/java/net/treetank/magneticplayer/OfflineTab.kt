package net.treetank.magneticplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** List row: entry + how much is actually on disk. */
private data class OfflineRow(val item: OfflineItem, val cached: Long, val fromStart: Long)

/** "On the phone": downloaded videos and the watching buffer. Works offline. */
@Composable
fun OfflineTab(onPlaying: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dl by DownloadService.status.collectAsState()
    var rows by remember { mutableStateOf<List<OfflineRow>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<OfflineItem?>(null) }
    var chooser by remember { mutableStateOf<OfflineItem?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    val canScreen = true  // mpv screens, Chromecasts, DLNA TVs: the picker shows what is on the network
    fun playOn(item: OfflineItem, key: String?, cast: CastTarget?, tv: DlnaTarget? = null) = scope.launch {
        opening = ctx.getString(R.string.media_opening)
        try {
            if (cast != null) Screens.cast(ctx, item.ref, cast, null) { opening = it }
            else if (tv != null) Screens.dlna(ctx, item.ref, tv, null) { opening = it }
            else Screens.play(ctx, item.ref, key!!, null) { opening = it }
            onPlaying()  // as from the Library: Remote right away
        } catch (e: ApiException) {
            android.widget.Toast.makeText(ctx, e.message, android.widget.Toast.LENGTH_LONG).show()
        } finally {
            opening = null
        }
    }
    LaunchedEffect(refresh) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                val cache = MediaCache.get(ctx)
                OfflineStore.all(ctx).map { i ->
                    val size = i.size.takeIf { it > 0 } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
                    OfflineRow(i,
                        runCatching { cache.getCachedBytes(i.key, 0, size) }.getOrDefault(0),
                        runCatching { cache.getCachedLength(i.key, 0, size) }.getOrDefault(0).coerceAtLeast(0))
                }.filter { it.item.pinned || it.cached > 0 }
            }
            delay(2_000)
        }
    }
    val list = rows ?: return
    if (list.isEmpty()) return Hint(stringResource(R.string.media_offline_empty))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.item.key }) { r ->
            OfflineCard(r, dl[r.item.key],
                onPlay = { ctx.startActivity(PlayerActivity.intent(ctx, r.item.ref)) },
                onScreen = if (canScreen) ({ chooser = r.item }) else null,
                onDownload = { DownloadService.start(ctx, r.item.ref); refresh++ },
                onPolicy = { p ->
                    scope.launch(Dispatchers.IO) { OfflineStore.update(ctx, r.item.key) { it.copy(policy = p, created = System.currentTimeMillis()) } }
                    refresh++
                },
                onDelete = { confirmDelete = r.item })
        }
    }
    chooser?.let { item ->
        ScreenChooser(item.ref, onPhone = null, onPick = { chooser = null; playOn(item, it, null) },
            onCast = { chooser = null; playOn(item, null, it) }, onDlna = { chooser = null; playOn(item, null, null, it) }) { chooser = null }
    }
    opening?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text(msg)
            }
        })
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.media_offline_delete_title)) },
            text = { Text(stringResource(R.string.media_offline_delete_text, item.title)) },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { MediaCache.remove(ctx, item.key) }; refresh++ }
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.media_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineCard(
    r: OfflineRow, dlStatus: String?,
    onPlay: () -> Unit, onScreen: (() -> Unit)?, onDownload: () -> Unit, onPolicy: (String) -> Unit, onDelete: () -> Unit,
) {
    val i = r.item
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(i.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val frac = if (i.size > 0) (r.cached.toDouble() / i.size).coerceIn(0.0, 1.0) else 0.0
            val done = i.size > 0 && r.cached >= i.size
            val line = when {
                dlStatus != null -> dlStatus
                done -> stringResource(R.string.media_offline_whole, humanBytes(i.size))
                i.complete -> stringResource(R.string.media_offline_gone, (frac * 100).toInt())
                i.error != null -> "${i.error} · ${(frac * 100).toInt()}%"
                i.want -> stringResource(R.string.media_offline_queued)
                i.size > 0 -> stringResource(R.string.media_offline_partial, (frac * 100).toInt(), humanBytes(r.cached), humanBytes(i.size))
                else -> humanBytes(r.cached)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // which server (when there are several): "Resume" goes there and the subtitles come from there
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val from = remember(i.ref.srv) { if (Servers.all(ctx).size > 1) Servers.get(ctx, i.ref.srv)?.name ?: ctx.getString(R.string.media_offline_deleted_server) else null }
            if (from != null) Text(stringResource(R.string.media_offline_from, from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (!done && i.size > 0) LinearProgressIndicator({ frac.toFloat() }, Modifier.fillMaxWidth())
            // how much can be watched from the start without a network (estimate from the average bitrate)
            if (i.durationMs > 0 && i.size > 0 && !done && r.fromStart > 0) {
                Text(stringResource(R.string.media_offline_from_start, clock(i.durationMs / 1000.0 * r.fromStart / i.size), clock(i.durationMs / 1000.0)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // buttons wrap onto the next line (large system font, narrow screen), the trash can is always on the right
            Row(verticalAlignment = Alignment.CenterVertically) {
              FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onPlay) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.media_play))
                }
                if (onScreen != null) OutlinedButton(onScreen) {
                    Icon(Icons.Filled.Cast, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.media_to_screen))
                }
                if (!done && dlStatus == null && !i.want) OutlinedButton(onDownload) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (i.pinned) R.string.media_resume else R.string.media_download_whole))
                }
              }
                IconButton(onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.media_cd_delete_from_phone)) }
            }
            if (i.pinned) Box {
                TextButton({ menu = true }) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.media_offline_retention, retentionLabel(ctx, i.policy)) + daysLeft(ctx, i))
                }
                DropdownMenu(menu, { menu = false }) {
                    RETENTION.forEach { (p, label) -> DropdownMenuItem({ Text(stringResource(label)) }, { menu = false; onPolicy(p) }) }
                }
            } else {
                Text(stringResource(R.string.media_offline_buffer_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** For the "after N days" policy: how much is left (counted from the download or from the policy change). */
private fun daysLeft(ctx: android.content.Context, i: OfflineItem): String {
    val days = i.policy.removePrefix("days:").toLongOrNull() ?: return ""
    val leftH = (i.created + days * 24 * 3600_000L - System.currentTimeMillis()) / 3600_000L
    return " " + when {
        leftH >= 48 -> (leftH / 24).toInt().let { ctx.resources.getQuantityString(R.plurals.media_offline_days_left, it, it) }
        leftH > 0 -> ctx.getString(R.string.media_offline_hours_left, leftH.toInt())
        else -> ctx.getString(R.string.media_offline_next_start)
    }
}

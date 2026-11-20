package net.treetank.magneticplayer

import org.json.JSONObject
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
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
    // selection mode (long press on a card): delete several at once
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmMany by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(selecting) { selected = emptySet() }
    fun toggle(keys: Collection<String>) {
        selected = if (keys.all { it in selected }) selected - keys.toSet() else selected + keys
    }
    val canScreen = true  // mpv screens, Chromecasts, DLNA TVs: the picker shows what is on the network
    val subsAsk = remember { SubsAsk() }
    SubsAskDialog(subsAsk)
    fun playOn(item: OfflineItem, key: String?, cast: CastTarget?, tv: DlnaTarget? = null) = scope.launch {
        opening = ctx.getString(R.string.media_opening)
        try {
            if (cast != null) Screens.cast(ctx, item.ref, cast, null, subsAsk::ask) { opening = it }
            else if (tv != null) Screens.dlna(ctx, item.ref, tv, null, subsAsk::ask) { opening = it }
            else Screens.play(ctx, item.ref, key!!, null, subsAsk::ask) { opening = it }
            onPlaying()  // as from the Library: Remote right away
        } catch (e: ApiException) {
            android.widget.Toast.makeText(ctx, e.message, android.widget.Toast.LENGTH_LONG).show()
        } finally {
            opening = null
        }
    }
    LaunchedEffect(refresh) {
        while (true) {
            MainVisible.await()  // nothing while the app is off screen (a service may keep the process alive)
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
    // a selected video that disappeared meanwhile (deleted after watching) is no longer selected
    val keys = list.map { it.item.key }.toSet()
    if (selected.any { it !in keys }) selected = selected.intersect(keys)
    val groups = remember(list) { offlineGroups(list) }
    // compact rows instead of cards (many short episodes), remembered; groups fold (big ones start folded)
    val ui = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var compact by remember { mutableStateOf(ui.getBoolean("offline_compact", false)) }
    var unfolded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(setOf<String>()) }
    var folded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(setOf<String>()) }
    fun isFolded(f: String, n: Int) = if (n > FOLD_OVER && f != OTHER) f !in unfolded else f in folded
    // names of the library folders ("srv:root" → name) for videos lying directly in one, remembered for offline use
    val rootNames = remember { mutableStateOf(runCatching { JSONObject(ui.getString("root_names", "{}")!!) }.getOrDefault(JSONObject())) }
    val needRoots = groups.mapNotNull { (f, _) -> f?.split(":", limit = 3)?.takeIf { it.size == 3 && it[2].isEmpty() }?.let { "${it[0]}:${it[1]}" } }.toSet()
    LaunchedEffect(needRoots) {
        val missing = needRoots.filter { !rootNames.value.has(it) }
        if (missing.isEmpty()) return@LaunchedEffect
        val j = JSONObject(rootNames.value.toString())
        withContext(Dispatchers.IO) {
            missing.map { it.substringBeforeLast(':') }.distinct().forEach { srv ->
                runCatching { Api.of(ctx, srv)?.libraryRoots() }.getOrNull()?.forEach { r -> j.put("$srv:${r.index}", r.name) }
            }
        }
        ui.edit().putString("root_names", j.toString()).apply()
        rootNames.value = j
    }
    Column(Modifier.fillMaxSize()) {
    if (selecting) SelectionBar(
        count = selected.size, bytes = list.filter { it.item.key in selected }.sumOf { it.cached },
        onClose = { selected = emptySet() },
        onAll = { selected = if (selected.size == list.size) emptySet() else keys },
        onWatched = {
            val s = Settings(ctx)
            selected = selected + list.filter { s.watchedAt(it.item.key) > 0 }.map { it.item.key }
        },
        onDelete = { confirmMany = true })
    if (!selecting) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // and the phone's free space: the place to see whether another season fits
        val free = remember(list.size) { runCatching { ctx.noBackupFilesDir.usableSpace }.getOrDefault(0L) }
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.media_offline_total, list.size, list.size, humanBytes(list.sumOf { it.cached })) +
            (if (free > 0) " · " + stringResource(R.string.media_offline_free, humanBytes(free)) else ""),
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton({ compact = !compact; ui.edit().putBoolean("offline_compact", compact).apply() }) {
            Icon(if (compact) Icons.Filled.ViewAgenda else Icons.AutoMirrored.Filled.ViewList,
                stringResource(if (compact) R.string.media_view_cards else R.string.media_view_list))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
      groups.forEach { (folder, rowsIn) ->
        // several episodes of one folder (a series, a season): a header, which selects them all in selection mode
        val fold = folder != null && isFolded(folder, rowsIn.size)
        if (folder != null) item(key = "group/$folder") {
            val gk = rowsIn.map { it.item.key }
            val s = remember(rowsIn) { Settings(ctx) }
            GroupHeader(groupLabel(folder, rootNames.value), rowsIn.size, selecting,
                checked = gk.all { it in selected }, bytes = rowsIn.sumOf { it.cached },
                watched = rowsIn.count { s.watchedAt(it.item.key) > 0 }, folded = fold,
                onFold = {
                    if (rowsIn.size > FOLD_OVER) unfolded = if (folder in unfolded) unfolded - folder else unfolded + folder
                    else folded = if (folder in folded) folded - folder else folded + folder
                }) { toggle(gk) }
        }
        if (!fold) items(rowsIn, key = { it.item.key }) { r ->
          if (compact) OfflineLine(r, dl[r.item.key], selecting = selecting, checked = r.item.key in selected,
              onToggle = { toggle(listOf(r.item.key)) }, onPlay = { ctx.startActivity(PlayerActivity.intent(ctx, r.item.ref)) })
          else
            OfflineCard(r, dl[r.item.key], selecting = selecting, checked = r.item.key in selected,
                onToggle = { toggle(listOf(r.item.key)) },
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
    }
    }
    if (confirmMany) {
        val chosen = list.filter { it.item.key in selected }
        AlertDialog(
            onDismissRequest = { confirmMany = false },
            title = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.media_sel_delete_title, chosen.size, chosen.size)) },
            text = { Text(stringResource(R.string.media_sel_delete_text, humanBytes(chosen.sumOf { it.cached }))) },
            confirmButton = {
                TextButton({
                    confirmMany = false
                    val gone = chosen.map { it.item.key }
                    selected = emptySet()
                    scope.launch { withContext(Dispatchers.IO) { gone.forEach { MediaCache.remove(ctx, it) } }; refresh++ }
                }) { Text(stringResource(R.string.media_delete)) }
            },
            dismissButton = { TextButton({ confirmMany = false }) { Text(stringResource(R.string.media_cancel)) } },
        )
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

/**
 * The list in groups: videos of one library folder (two or more: a series, a season) together under its name, at the
 * place of its newest one; the rest one by one (folder null). The order otherwise stays the newest first.
 */
private fun offlineGroups(list: List<OfflineRow>): List<Pair<String?, List<OfflineRow>>> {
    fun folder(r: OfflineRow) = (r.item.ref as? MediaRef.InLibrary)?.let { "${it.srv}:${it.root}:${it.path.substringBeforeLast('/', "")}" }
    val counts = list.groupingBy { folder(it) }.eachCount()
    val out = mutableListOf<Pair<String?, List<OfflineRow>>>()
    val singles = mutableListOf<OfflineRow>()
    val done = mutableSetOf<String>()
    for (r in list) {
        val f = folder(r)?.takeIf { (counts[it] ?: 0) > 1 }
        if (f == null) { singles += r; continue }
        if (!done.add(f)) continue
        // episodes in their order (by path), not by download time
        out += f to list.filter { folder(it) == f }.sortedBy { (it.item.ref as MediaRef.InLibrary).path }
    }
    // with groups on the list, the single videos get their own header: right under a group they looked like its part
    if (singles.isNotEmpty()) out += (if (out.isEmpty()) null else OTHER) to singles
    return out
}

/** The group of single videos (not a folder). */
private const val OTHER = "~other"

/**
 * A group's name: its folder; "Season 1" and the like with the series' folder before it; videos lying directly in a
 * library folder under that folder's name ([roots]: "srv:root" → name).
 */
@Composable
private fun groupLabel(folder: String, roots: JSONObject): String {
    if (folder == OTHER) return stringResource(R.string.media_group_other)
    val (srv, root, path) = folder.split(":", limit = 3).let { if (it.size == 3) it else listOf("", "", folder) }
    val srvRoot = "$srv:$root"
    if (path.isEmpty()) return roots.optString(srvRoot).ifEmpty { stringResource(R.string.media_group_root) }
    val last = path.substringAfterLast('/')
    val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
    return if (parent.isNotEmpty() && SEASON_DIR.matches(last)) "$parent · $last" else last
}

private val SEASON_DIR = Regex("(?i)(season|sezon|series|seria|staffel|saison|temporada|s)[ ._-]*\\d+|specials|extras|dodatki")

/** Selection mode: how many and how much, close, select all, select watched, delete. */
@Composable
private fun SelectionBar(count: Int, bytes: Long, onClose: () -> Unit, onAll: () -> Unit, onWatched: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(androidx.compose.material.icons.Icons.Filled.Close, stringResource(R.string.media_sel_close)) }
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.media_sel_count, count, count, humanBytes(bytes)),
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onWatched) { Text(stringResource(R.string.media_sel_watched)) }
            IconButton(onAll) { Icon(androidx.compose.material.icons.Icons.Filled.SelectAll, stringResource(R.string.media_sel_all)) }
            IconButton(onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.media_delete)) }
        }
    }
}

/** Groups bigger than this start folded. */
private const val FOLD_OVER = 6

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(name: String, n: Int, selecting: Boolean, checked: Boolean, bytes: Long, watched: Int, folded: Boolean,
                        onFold: () -> Unit, onToggle: () -> Unit) {
    // a tap folds or unfolds (selects the group while selecting), a long press selects it
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else onFold() }, onLongClick = onToggle)
        .padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) androidx.compose.material3.Checkbox(checked, { onToggle() })
        Column(Modifier.weight(1f)) {
            Text("$name ($n)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(humanBytes(bytes) + if (watched > 0) " · " + stringResource(R.string.media_group_watched, watched) else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!selecting) IconButton(onFold) {
            Icon(if (folded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                stringResource(if (folded) R.string.media_group_unfold else R.string.media_group_fold))
        }
    }
}

/** The compact view: one line per video, a tap plays it (selects while selecting), a long press selects. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OfflineLine(r: OfflineRow, dlStatus: String?, selecting: Boolean, checked: Boolean, onToggle: () -> Unit, onPlay: () -> Unit) {
    val i = r.item
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val watched = remember(i.key) { Settings(ctx).watchedAt(i.key) > 0 }
    val done = i.size > 0 && r.cached >= i.size
    Row(Modifier.fillMaxWidth()
        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .combinedClickable(onClick = { if (selecting) onToggle() else onPlay() }, onLongClick = onToggle)
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) androidx.compose.material3.Checkbox(checked, { onToggle() })
        else Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified)
        Text(when {
            dlStatus != null -> dlStatus.substringAfterLast(' ')
            done -> humanBytes(i.size)
            i.size > 0 -> "${(100 * r.cached / i.size).toInt()}%"
            else -> humanBytes(r.cached)
        }, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (watched) Text("✓", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OfflineCard(
    r: OfflineRow, dlStatus: String?, selecting: Boolean, checked: Boolean, onToggle: () -> Unit,
    onPlay: () -> Unit, onScreen: (() -> Unit)?, onDownload: () -> Unit, onPolicy: (String) -> Unit, onDelete: () -> Unit,
) {
    val i = r.item
    var menu by remember { mutableStateOf(false) }
    // a long press starts selecting; while selecting a tap (de)selects and the buttons are hidden
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onToggle),
        colors = if (checked) androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else androidx.compose.material3.CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) androidx.compose.material3.Checkbox(checked, { onToggle() })
                Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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
            if (selecting) return@Column
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

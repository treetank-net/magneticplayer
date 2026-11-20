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
import androidx.compose.material3.SnackbarHostState
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

/** List row: entry + how much is actually on disk + the library title it belongs to (this phone's catalog copy). */
private data class OfflineRow(val item: OfflineItem, val cached: Long, val fromStart: Long, val title: CatItem? = null)

/**
 * "On the phone" (D5): downloaded videos and the watching buffer. Works offline. Tapping a title opens its library
 * page (D6, [PhoneTitlePage]); ▶ plays at once; ⋮ has quick actions; "Select" (or a long press) selects.
 */
@Composable
fun OfflineTab(onPlaying: () -> Unit = {}, snack: SnackbarHostState? = null,
               onOpenFolder: (String, Int, String) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dl by DownloadService.status.collectAsState()
    var rows by remember { mutableStateOf<List<OfflineRow>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf<OfflineItem?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    // D6: the library title page of a tapped video ("srv", "id", "path" of the tapped episode), Back returns here
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // selection: "Select" in the header or a long press; delete several at once
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }
    var confirmMany by remember { mutableStateOf(false) }
    val selecting = selectMode || selected.isNotEmpty()
    fun endSelect() { selected = emptySet(); selectMode = false }
    androidx.activity.compose.BackHandler(selecting && page == null) { endSelect() }
    fun toggle(keys: Collection<String>) {
        selected = if (keys.all { it in selected }) selected - keys.toSet() else selected + keys
    }
    val canScreen = true  // mpv screens, Chromecasts, DLNA TVs: the picker shows what is on the network
    val subsAsk = remember { SubsAsk() }
    SubsAskDialog(subsAsk)
    val localSnack = remember { SnackbarHostState() }
    fun playOn(item: OfflineItem, key: String?, cast: CastTarget?, tv: DlnaTarget? = null) = Screens.ui.launch {
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
                        runCatching { cache.getCachedLength(i.key, 0, size) }.getOrDefault(0).coerceAtLeast(0),
                        runCatching { PhoneCopies.titleOf(ctx, i.ref) }.getOrNull())
                }.filter { it.item.pinned || it.cached > 0 }
            }
            delay(2_000)
        }
    }
    page?.let { p ->
        val o = remember(p) { JSONObject(p) }
        return PhoneTitlePage(o.optString("srv"), o.optString("id"), o.optString("path").ifEmpty { null }, snack ?: localSnack,
            onPlaying, onOpenFolder) { page = null; refresh++ }
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
    /** D6 for a row with a library title, else (a torrent file, a server without the catalog) it plays as before */
    fun open(r: OfflineRow) {
        val t = r.title
        val ref = r.item.ref
        if (t == null || ref !is MediaRef.InLibrary) ctx.startActivity(PlayerActivity.intent(ctx, ref))
        else page = JSONObject().put("srv", ref.srv).put("id", t.id).put("path", ref.path).toString()
    }
    fun play(r: OfflineRow) = ctx.startActivity(PlayerActivity.intent(ctx, r.item.ref))
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // and the phone's free space: the place to see whether another season fits
        val free = remember(list.size) { runCatching { ctx.noBackupFilesDir.usableSpace }.getOrDefault(0L) }
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.media_offline_total, list.size, list.size, humanBytes(list.sumOf { it.cached })) +
            (if (free > 0) " · " + stringResource(R.string.media_offline_free, humanBytes(free)) else ""),
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ if (selecting) endSelect() else selectMode = true }) {
            Text(stringResource(if (selecting) R.string.st4_done else R.string.st4_select))
        }
        if (!selecting) IconButton({ compact = !compact; ui.edit().putBoolean("offline_compact", compact).apply() }) {
            Icon(if (compact) Icons.Filled.ViewAgenda else Icons.AutoMirrored.Filled.ViewList,
                stringResource(if (compact) R.string.media_view_cards else R.string.media_view_list))
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
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
          val menu: @Composable () -> Unit = {
              CopyMenuButton(r.item.ref, r.item.title, onPlay = { play(r) },
                  onScreen = if (canScreen) ({ chooser = r.item }) else null, onChanged = { refresh++ })
          }
          if (compact) OfflineLine(r, dl[r.item.key], selecting = selecting, checked = r.item.key in selected,
              onToggle = { toggle(listOf(r.item.key)) }, onOpen = { open(r) }, onPlay = { play(r) }, menu = menu)
          else
            OfflineCard(r, dl[r.item.key], selecting = selecting, checked = r.item.key in selected,
                onToggle = { toggle(listOf(r.item.key)) }, onOpen = { open(r) },
                onPlay = { play(r) }, menu = menu,
                onDownload = { DownloadService.start(ctx, r.item.ref); refresh++ },
                onPolicy = { p ->
                    scope.launch(Dispatchers.IO) { OfflineStore.update(ctx, r.item.key) { it.copy(policy = p, created = System.currentTimeMillis()) } }
                    refresh++
                })
        }
      }
    }
    if (selecting) {
        val s = remember { Settings(ctx) }
        val lang = remember { s.subLanguages.split(',').map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() } ?: "en" }
        fun hasLang(r: OfflineRow) = r.item.subs.any { it.lang.lowercase().startsWith(lang) }
        PhoneSelectionBar(
            count = selected.size, bytes = list.filter { it.item.key in selected }.sumOf { it.cached }, lang = lang,
            onAll = { selected = if (selected.size == list.size) emptySet() else keys },
            onWatched = { selected = selected + list.filter { s.watchedAt(it.item.key) > 0 }.map { it.item.key } },
            onSubs = { has -> selected = selected + list.filter { hasLang(it) == has }.map { it.item.key } },
            onDelete = { confirmMany = true })
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
                    endSelect()
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

/**
 * Selection (D5), at the bottom: how many and how much, "All", "Watched", a subtitle filter ([lang]: with / without),
 * "Delete · N".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSelectionBar(count: Int, bytes: Long, lang: String, onAll: () -> Unit, onWatched: () -> Unit,
                              onSubs: (Boolean) -> Unit, onDelete: () -> Unit) {
    var subsMenu by remember { mutableStateOf(false) }
    val code = lang.uppercase(java.util.Locale.ROOT)
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.media_sel_count, count, count, humanBytes(bytes)),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.AssistChip(onAll, { Text(stringResource(R.string.st4_sel_all)) })
                    androidx.compose.material3.AssistChip(onWatched, { Text(stringResource(R.string.st4_sel_watched)) })
                    Box {
                        androidx.compose.material3.AssistChip({ subsMenu = true }, { Text(stringResource(R.string.st4_sel_subs, code) + " ▾") })
                        DropdownMenu(subsMenu, { subsMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.st4_sel_with, code)) }, { subsMenu = false; onSubs(true) })
                            DropdownMenuItem({ Text(stringResource(R.string.st4_sel_without, code)) }, { subsMenu = false; onSubs(false) })
                        }
                    }
                }
                FilledTonalButton(onDelete, enabled = count > 0) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.st4_sel_delete, count))
                }
            }
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

/**
 * The compact (short) view: one line per video. A tap opens its title page (selects while selecting), ▶ plays at once,
 * ⋮ quick actions, a long press selects.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OfflineLine(r: OfflineRow, dlStatus: String?, selecting: Boolean, checked: Boolean, onToggle: () -> Unit,
                        onOpen: () -> Unit, onPlay: () -> Unit, menu: @Composable () -> Unit) {
    val i = r.item
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val watched = remember(i.key) { Settings(ctx).watchedAt(i.key) > 0 }
    val done = i.size > 0 && r.cached >= i.size
    Row(Modifier.fillMaxWidth()
        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onToggle)
        .padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) androidx.compose.material3.Checkbox(checked, { onToggle() })
        else IconButton(onPlay, Modifier.size(36.dp)) {
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.st4_play_now), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified)
        Text(when {
            dlStatus != null -> dlStatus.substringAfterLast(' ')
            done -> humanBytes(i.size)
            i.size > 0 -> "${(100 * r.cached / i.size).toInt()}%"
            else -> humanBytes(r.cached)
        }, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (watched) Text("✓", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.primary)
        if (!selecting) menu()
    }
}

/**
 * A card per video: a tap on it opens its title page (D6), ▶ plays at once, ⋮ quick actions; the download state, and
 * "Download whole" / "Resume" while it isn't whole; the delete policy. A long press selects.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OfflineCard(
    r: OfflineRow, dlStatus: String?, selecting: Boolean, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit,
    onPlay: () -> Unit, menu: @Composable () -> Unit, onDownload: () -> Unit, onPolicy: (String) -> Unit,
) {
    val i = r.item
    var policyMenu by remember { mutableStateOf(false) }
    // a long press starts selecting; while selecting a tap (de)selects and the buttons are hidden
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onToggle),
        colors = if (checked) androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else androidx.compose.material3.CardDefaults.cardColors()) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) androidx.compose.material3.Checkbox(checked, { onToggle() })
                Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!selecting) {
                    androidx.compose.material3.FilledTonalIconButton(onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.st4_play_now)) }
                    menu()
                }
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
            if (!done && i.size > 0) LinearProgressIndicator({ frac.toFloat() }, Modifier.fillMaxWidth().padding(end = 8.dp))
            // how much can be watched from the start without a network (estimate from the average bitrate)
            if (i.durationMs > 0 && i.size > 0 && !done && r.fromStart > 0) {
                Text(stringResource(R.string.media_offline_from_start, clock(i.durationMs / 1000.0 * r.fromStart / i.size), clock(i.durationMs / 1000.0)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selecting) return@Column
            if (!done && dlStatus == null && !i.want) OutlinedButton(onDownload) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(if (i.pinned) R.string.media_resume else R.string.media_download_whole))
            }
            if (i.pinned) Box {
                TextButton({ policyMenu = true }) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.media_offline_retention, retentionLabel(ctx, i.policy)) + daysLeft(ctx, i))
                }
                DropdownMenu(policyMenu, { policyMenu = false }) {
                    RETENTION.forEach { (p, label) -> DropdownMenuItem({ Text(stringResource(label)) }, { policyMenu = false; onPolicy(p) }) }
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

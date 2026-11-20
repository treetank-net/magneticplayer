package net.treetank.magneticplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------- covers

/** Folder and video covers from the agent (`/api/library/cover`), decoded small and kept in memory. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(32 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val frames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun key(api: Api, root: Int, path: String, wide: Boolean = false) = "${api.srv.key}|$root|$path" + if (wide) "|w" else ""
    fun cached(k: String): Bitmap? = cache.get(k)
    fun isMissing(k: String) = k in missing
    /** The cover under [k] is a frame from the video (no title on it), not a poster. */
    fun isFrame(k: String) = k in frames

    /** Downloads and decodes a cover to about [px] on the shorter side; null = none (remembered until the app dies).
     *  [wide]: the agent's bigger frame (720 px), for full-width pictures. */
    fun load(api: Api, root: Int, path: String, px: Int, wide: Boolean = false): Bitmap? {
        val k = key(api, root, path, wide)
        cache.get(k)?.let { return it }
        val got = api.cover(root, path, wide) ?: run { missing += k; return null }
        val bytes = got.bytes
        if (!wide) CoverStore.keep(api.s.ctx, api.srv.key, root, path, bytes, got.frame)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: run { missing += k; return null }
        if (got.frame) frames += k
        cache.put(k, bmp)
        return bmp
    }

    private val pings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** Whether the server answers a ping; one ping per server every 5 s at most, shared by all covers waiting on it. */
    private fun reachable(api: Api): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        pings[api.srv.key]?.let { (at, ok) -> if (now - at < 5000) return ok }
        return api.ping().also { pings[api.srv.key] = now to it }
    }

    /** [load], tried again while the server can't be reached (an offline start, Wi-Fi back later): once a ping answers,
     *  the cover comes. Null = the server has no cover for it. Ends when the caller's coroutine is cancelled. */
    suspend fun loadRetrying(api: Api, root: Int, path: String, px: Int, wide: Boolean = false): Bitmap? {
        var wait = 5_000L
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { load(api, root, path, px, wide) } }
            if (r.isSuccess) return r.getOrNull()
            do {
                kotlinx.coroutines.delay(wait)
                wait = minOf(wait * 2, 60_000L)
            } while (!withContext(Dispatchers.IO) { reachable(api) })
        }
    }
}

/** A small rounded cover of a folder or a video; [fallback] (the usual icon) while loading and when there is none. */
@Composable
fun CoverThumb(api: Api, root: Int, path: String, size: Dp = 52.dp, fallback: @Composable () -> Unit) {
    val k = Covers.key(api, root, path)
    var bmp by remember(k) { mutableStateOf(Covers.cached(k)) }
    val px = with(LocalDensity.current) { size.roundToPx() }
    if (bmp == null && !Covers.isMissing(k)) LaunchedEffect(k) {
        bmp = Covers.loadRetrying(api, root, path, px)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        else fallback()
    }
}

// ---------------------------------------------------------------- storage profiles

private val DEFAULT_PROFILES = listOf("original", "tidy", "good", "compact", "tiny")

/** Profiles of this server in display order (an old list without `tidy` stays as the agent sent it). */
fun profilesOf(srv: Server) = srv.libraryProfiles.ifEmpty { DEFAULT_PROFILES }

@Composable
fun profileLabel(p: String) = when (p) {
    "original" -> stringResource(R.string.lib_profile_original)
    "tidy" -> stringResource(R.string.lib_profile_tidy)
    "good" -> stringResource(R.string.lib_profile_good)
    "compact" -> stringResource(R.string.lib_profile_compact)
    "tiny" -> stringResource(R.string.lib_profile_tiny)
    else -> p
}

@Composable
private fun profileShort(p: String) = when (p) {
    "original" -> stringResource(R.string.lib_profile_original_short)
    "tidy" -> stringResource(R.string.lib_profile_tidy_short)
    "good" -> stringResource(R.string.lib_profile_good_short)
    "compact" -> stringResource(R.string.lib_profile_compact_short)
    "tiny" -> stringResource(R.string.lib_profile_tiny_short)
    else -> p
}

/** Radio list of storage profiles. */
@Composable
fun ProfileChoice(profiles: List<String>, selected: String, onSelect: (String) -> Unit) {
    profiles.forEach { p ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(p) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == p, { onSelect(p) }); Text(profileLabel(p), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Shrink this folder…": picks a profile (saved for this folder) and opens the preview. */
@Composable
fun ShrinkDialog(srv: Server, folder: String, onDismiss: () -> Unit, onPreview: (String) -> Unit) {
    val choices = profilesOf(srv).filter { it != "original" }
    var chosen by remember { mutableStateOf(choices.firstOrNull { it == "good" } ?: choices.firstOrNull() ?: "good") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_shrink_folder_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.StartEllipsis)
                Spacer(Modifier.height(8.dp))
                ProfileChoice(choices, chosen) { chosen = it }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.lib_shrink_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onPreview(chosen) }) { Text(stringResource(R.string.lib_preview)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

/** One line about copying a folder to its replica; null when there is nothing to say. */
@Composable
fun ReplicaLine(rep: Replica?, modifier: Modifier = Modifier) {
    rep ?: return
    val (text, err) = when {
        rep.error != null -> stringResource(R.string.lib_replica_error, rep.error) to true
        rep.pending > 0 -> pluralStringResource(R.plurals.lib_replica_pending, rep.pending, rep.pending) to false
        else -> stringResource(R.string.lib_replica_ok) to false
    }
    Text(text, modifier, style = MaterialTheme.typography.labelSmall,
        color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private val GB = 1L shl 30

/** Plan request bodies (JSON as a string, so it survives in rememberSaveable). */
object PlanBody {
    fun free(root: Int, need: Long?) = JSONObject().put("kind", "free").put("root", root).apply { if (need != null) put("need", need) }.toString()
    fun shrink(root: Int, path: String, profile: String?) =
        JSONObject().put("kind", "shrink").put("root", root).put("path", path).apply { if (profile != null) put("profile", profile) }.toString()
    fun simple(kind: String) = JSONObject().put("kind", kind).toString()
    /** missing subtitles in `langs` ("pl,en"): the whole library (root null) or one folder or file */
    fun subsGet(langs: String, root: Int? = null, path: String? = null) = JSONObject().put("kind", "subs_get").put("languages", langs)
        .apply { if (root != null) put("root", root).put("path", path ?: "") }.toString()
    /** explicit moves: (root, path, target root), into the target's top level */
    fun move(items: List<Triple<Int, String, Int>>) = JSONObject().put("kind", "move").put("items",
        JSONArray(items.map { (r, p, t) -> JSONObject().put("root", r).put("path", p).put("to_root", t) })).toString()
    fun trash(items: List<Pair<Int, String>>) = JSONObject().put("kind", "trash").put("items",
        JSONArray(items.map { (r, p) -> JSONObject().put("root", r).put("path", p) })).toString()
}

// ---------------------------------------------------------------- disks and tasks

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (onRefresh != null) IconButton(onRefresh) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.lib_refresh))
        }
    }
    HorizontalDivider()
}

/** Disks with library folders, tidy-up tools, the trash and background tasks (household members, agent 1.6+). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisksScreen(api: Api, snack: SnackbarHostState, onBack: () -> Unit, onPlan: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var disks by remember { mutableStateOf<Disks?>(null) }
    var roots by remember { mutableStateOf<Map<Int, LibRoot>>(emptyMap()) }
    var tasks by remember { mutableStateOf<List<LibTask>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var freeFor by remember { mutableStateOf<Int?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var addOn by remember { mutableStateOf<Disk?>(null) }
    var subsGet by remember { mutableStateOf(false) }
    if (subsGet) SubsGetDialog(api, onDismiss = { subsGet = false }) { langs -> subsGet = false; onPlan(PlanBody.subsGet(langs)) }
    LaunchedEffect(reload) {
        try {
            disks = withContext(Dispatchers.IO) { api.disks() }
            roots = withContext(Dispatchers.IO) { runCatching { api.libraryRoots(true) }.getOrDefault(emptyList()) }.associateBy { it.index }
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
            if (disks == null) disks = Disks(emptyList(), emptyList(), 14)
        }
    }
    // tasks every 2 s while the screen is visible; a task that finishes changes the disks, so they are read again
    LaunchedEffect(Unit) {
        var finished = -1
        while (true) {
            MainVisible.await()  // nothing while the app is off screen (a service may keep the process alive)
            runCatching { withContext(Dispatchers.IO) { api.tasks(brief = true) } }.getOrNull()?.let { list ->
                tasks = list
                val n = list.count { it.state == "done" || it.state == "cancelled" }
                if (finished >= 0 && n != finished) reload++
                finished = n
            }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        // free space changes behind the app's back too (downloads, other phones): read it again on demand
        ScreenHeader(stringResource(R.string.lib_disks_title), onBack) {
            reload++
            scope.launch { runCatching { withContext(Dispatchers.IO) { api.tasks(brief = true) } }.getOrNull()?.let { tasks = it } }
        }
        val d = disks ?: return Hint(stringResource(R.string.media_loading))
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onPlan(PlanBody.simple("tidy")) }) { Text(stringResource(R.string.lib_tidy)) }
                    OutlinedButton({ onPlan(PlanBody.simple("dupes")) }) { Text(stringResource(R.string.lib_dupes)) }
                    // old picture formats (DivX 3, WMV…) that phones and TVs no longer play (agent 1.8.2+)
                    if (api.srv.hasModernize) OutlinedButton({ onPlan(PlanBody.simple("modernize")) }) { Text(stringResource(R.string.lib_modernize)) }
                    if (api.srv.hasSubsFit) OutlinedButton({ onPlan(PlanBody.simple("subs")) }) { Text(stringResource(R.string.lib_subs)) }
                    if (api.srv.hasSubsGetTask) OutlinedButton({ subsGet = true }) { Text(stringResource(R.string.subs_get_short)) }
                }
                Text(stringResource(R.string.lib_tools_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            items(d.disks, key = { "disk/${it.id}/${it.mount}" }) { disk -> DiskCard(disk, roots, onAdd = { addOn = disk }) { freeFor = it } }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.lib_trash), style = MaterialTheme.typography.titleSmall)
                        val size = d.trash.sumOf { it.size }
                        val files = d.trash.sumOf { it.files }
                        Text(if (files == 0) stringResource(R.string.lib_trash_empty_now)
                            else pluralStringResource(R.plurals.lib_trash_summary, files, files, humanBytes(size)),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(pluralStringResource(R.plurals.lib_trash_kept, d.trashDays, d.trashDays),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (files > 0) TextButton({ confirmEmpty = true }) { Text(stringResource(R.string.lib_trash_empty)) }
                    }
                }
            }
            if (tasks.isNotEmpty()) item {
                Text(stringResource(R.string.lib_tasks), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(tasks, key = { "task/${it.id}" }) { t ->
                TaskCard(t, api.srv.key, loadItems = { runCatching { withContext(Dispatchers.IO) { api.taskGet(t.id).items } }.getOrNull() }, onRemove = if (api.srv.hasTaskRemove) ({
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.taskRemove(t.id) }
                            tasks = tasks.filter { it.id != t.id }  // at once, not at the next read
                        } catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                    }
                }) else null) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.taskCancel(t.id) }
                            tasks = withContext(Dispatchers.IO) { runCatching { api.tasks(brief = true) }.getOrDefault(tasks) }
                        } catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                    }
                }
            }
        }
    }
    addOn?.let { disk ->
        DirPicker(api, onDismiss = { addOn = null }, start = disk.home.ifEmpty { disk.mount }) { chosen ->
            addOn = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.libraryAdd(chosen) }
                    reload++
                } catch (e: ApiException) {
                    snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
                }
            }
        }
    }
    freeFor?.let { root ->
        FreeSpaceDialog(onDismiss = { freeFor = null }) { need -> freeFor = null; onPlan(PlanBody.free(root, need)) }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text(stringResource(R.string.lib_trash_empty_title)) },
        text = { Text(stringResource(R.string.lib_trash_empty_text)) },
        confirmButton = {
            TextButton({
                confirmEmpty = false
                scope.launch {
                    try {
                        val freed = withContext(Dispatchers.IO) { api.trashEmpty() }
                        reload++
                        snack.showSnackbar(ctx.getString(R.string.lib_trash_freed, humanBytes(freed)))
                    } catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                }
            }) { Text(stringResource(R.string.lib_trash_empty)) }
        },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text(stringResource(R.string.media_cancel)) } },
    )
}

@Composable
private fun DiskCard(disk: Disk, roots: Map<Int, LibRoot>, onAdd: () -> Unit, onFree: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(disk.name.ifEmpty { disk.mount } + if (disk.system) " · " + stringResource(R.string.lib_disk_system) else "",
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (disk.name.isNotEmpty() && disk.mount.isNotEmpty()) Text(disk.mount, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val used = if (disk.total > 0) (disk.total - disk.free).toFloat() / disk.total else 0f
            LinearProgressIndicator(progress = { used.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                color = if (used > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.lib_disk_free, humanBytes(disk.free), humanBytes(disk.total)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            // how much of the used space is the library: moving the library can free at most that much
            val lib = disk.roots.sumOf { it.size }
            val other = (disk.total - disk.free - lib).coerceAtLeast(0)
            if (disk.roots.isNotEmpty()) Text(stringResource(R.string.lib_disk_split, humanBytes(lib), humanBytes(other)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (disk.roots.isNotEmpty() && used > 0.85f && lib < other) Text(stringResource(R.string.lib_disk_mostly_other, humanBytes(lib)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 2.dp))
            // a disk with many library folders takes the whole screen: the list starts folded
            var open by rememberSaveable(disk.mount) { mutableStateOf(disk.roots.size <= 2) }
            if (disk.roots.size > 2) Row(Modifier.padding(top = 6.dp).clickable { open = !open }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
                Text(pluralStringResource(R.plurals.lib_disk_folders, disk.roots.size, disk.roots.size), style = MaterialTheme.typography.labelMedium)
            }
            if (open) disk.roots.forEach { r ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val parts = buildList {
                        add(humanBytes(r.size))
                        add(pluralStringResource(R.plurals.lib_files, r.files, r.files))
                        add(profileShort(r.profile))
                        if (r.profiles.isNotEmpty()) add(pluralStringResource(R.plurals.lib_subfolder_profiles, r.profiles.size, r.profiles.size))
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplicaLine(roots[r.root]?.replica)
                }
            }
            disk.roots.firstOrNull()?.let { first ->
                TextButton({ onFree(first.root) }, Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.lib_free_up)) }
            }
            // agent 1.8+ also lists disks without library folders, so a new disk can be put to use from here
            if (disk.roots.isEmpty()) {
                Text(stringResource(R.string.lib_disk_no_folders), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(onAdd, Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.media_lib_add_folder)) }
            }
        }
    }
}

/** How much to free on a disk: the default (up to 15 % free) or a fixed amount. */
@Composable
private fun FreeSpaceDialog(onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    var need by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_free_up)) },
        text = {
            Column {
                listOf<Long?>(null, 25 * GB, 50 * GB, 100 * GB).forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable { need = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(need == v, { need = v })
                        Text(if (v == null) stringResource(R.string.lib_free_default) else stringResource(R.string.lib_free_amount, humanBytes(v)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.lib_free_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onPick(need) }) { Text(stringResource(R.string.lib_preview)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

@Composable
private fun taskState(s: String) = when (s) {
    "queued" -> stringResource(R.string.lib_task_queued)
    "running" -> stringResource(R.string.lib_task_running)
    "done" -> stringResource(R.string.lib_task_done)
    "cancelled" -> stringResource(R.string.lib_task_cancelled)
    else -> s
}

@Composable
private fun itemState(s: String) = when (s) {
    "pending" -> stringResource(R.string.lib_item_pending)
    "running" -> stringResource(R.string.lib_item_running)
    "done" -> stringResource(R.string.lib_item_done)
    "error" -> stringResource(R.string.lib_item_error)
    "skipped" -> stringResource(R.string.lib_item_skipped)
    else -> s
}

@Composable
private fun TaskCard(t: LibTask, srvKey: String, loadItems: suspend () -> List<TaskItem>?, onRemove: (() -> Unit)? = null, onCancel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // the list comes without items (brief): an opened card reads its own, again only when the counts change
    var items by remember(t.id) { mutableStateOf(t.items) }
    LaunchedEffect(open, t.state, t.itemsDone, t.errors) {
        if (open && t.items.isEmpty() && t.itemCount > 0) loadItems()?.let { items = it }
    }
    // the agent finishes the current item before the task becomes "cancelled": show that the tap was taken
    var cancelling by remember(t.id) { mutableStateOf(false) }
    // a running task may take hours: cancelling asks first (it sat next to "Monitor", one slip away)
    var confirmCancel by remember(t.id) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val active = t.state == "queued" || t.state == "running"
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.lib_task_cancel_q, t.title)) },
        text = { Text(stringResource(R.string.lib_task_cancel_text)) },
        confirmButton = { TextButton({ confirmCancel = false; cancelling = true; onCancel() }) {
            Text(stringResource(R.string.lib_task_cancel_yes), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.lib_task_cancel_no)) } },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (active && cancelling) stringResource(R.string.lib_task_cancelling) else taskState(t.state), style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    t.encoder?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (active && !cancelling) TextButton({ confirmCancel = true }) { Text(stringResource(R.string.media_cancel)) }
                if (!active && onRemove != null) TextButton(onRemove) { Text(stringResource(R.string.lib_task_remove)) }
            }
            if (active) {
                LinearProgressIndicator(progress = { (t.pct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp))
                Text("${t.pct}% · ${humanBytes(t.doneBytes)} / ${humanBytes(t.total)}", style = MaterialTheme.typography.bodySmall)
                t.current?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                // agent 1.7+ says why it is slower or waiting; an older one only has "paused"
                (t.note ?: if (t.paused) stringResource(R.string.lib_task_paused) else null)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!cancelling) {
                    // a task started elsewhere (another phone, the web page): follow it here too, with the notification;
                    // under the progress, away from "Cancel"
                    val wv by TaskWatch.version.collectAsState()
                    val watched = remember(wv, t.id) { TaskWatch.isWatched(ctx, srvKey, t.id) }
                    TextButton({ if (watched) TaskWatch.unwatch(ctx, srvKey, t.id) else TaskWatch.watch(ctx, srvKey, t.id) },
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(if (watched) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (watched) R.string.lib_task_unwatch else R.string.lib_task_watch))
                    }
                }
            }
            if (t.kind == "shrink" && t.saved > 0) Text(stringResource(R.string.lib_task_saved, humanBytes(t.saved)),
                style = MaterialTheme.typography.bodySmall)
            if (t.kind == "subs" && (t.fixed > 0 || t.unfit > 0)) Text(stringResource(R.string.lib_task_subs, t.fixed, t.unfit),
                style = MaterialTheme.typography.bodySmall)
            if (t.errors > 0) Text(pluralStringResource(R.plurals.lib_errors, t.errors, t.errors), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            // why, once per reason (agent 1.14.2+), instead of the same error on every file of the list
            t.errorGroups.forEach { (e, n) ->
                Text(if (n > 1) "$n × $e" else e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (t.itemCount > 0) {
                Row(Modifier.clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
                    Text(pluralStringResource(R.plurals.lib_items, t.itemCount, t.itemCount), style = MaterialTheme.typography.labelMedium)
                }
                if (open) (if (t.items.isNotEmpty()) t.items else items).forEach { i ->
                    Column(Modifier.padding(start = 18.dp, bottom = 4.dp)) {
                        Text(i.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val extra = buildList {
                            add(itemState(i.state))
                            if (i.size > 0) add(humanBytes(i.size))
                            if (i.saved > 0) add(stringResource(R.string.lib_task_saved, humanBytes(i.saved)))
                        }
                        Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = if (i.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        i.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        i.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- plan (preview) screen

/**
 * Makes a plan from [body] (a preview: nothing changes on disk), shows it with checkboxes and starts the picked items as a
 * task ([onStarted]). Duplicates are shown as groups; deleting some of them makes a new "trash" plan ([onPlan]).
 */
@Composable
fun PlanScreen(api: Api, body: String, onBack: () -> Unit, onStarted: () -> Unit, onPlan: (String) -> Unit) {
    val ctx = LocalContext.current
    var plan by remember(body) { mutableStateOf<LibPlan?>(null) }
    var error by remember(body) { mutableStateOf<String?>(null) }
    var attempt by remember(body) { mutableIntStateOf(0) }
    // shrink (agent 1.7+): another encoder picked in the preview = the same request again with "encoder"
    var encoder by remember(body) { mutableStateOf<String?>(null) }
    LaunchedEffect(body, attempt, encoder) {
        plan = null; error = null
        try {
            val req = JSONObject(body).apply { encoder?.let { put("encoder", it) } }
            var p = withContext(Dispatchers.IO) { api.planStart(req) }
            plan = p
            while (p.state == "running") {
                delay(1000)
                p = withContext(Dispatchers.IO) { api.planGet(p.id) }
                plan = p
            }
            if (p.state == "error") error = p.error ?: ctx.getString(R.string.media_error)
        } catch (e: ApiException) {
            error = if (e.code == 404) ctx.getString(R.string.lib_plan_expired) else e.message ?: ctx.getString(R.string.media_error)
        }
    }
    val kind = JSONObject(body).optString("kind")
    val p = plan
    val result = p?.result?.takeIf { p.state == "done" }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(result?.optString("title")?.ifEmpty { null } ?: planKindTitle(kind), onBack)
        when {
            error != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton({ attempt++ }) { Text(stringResource(R.string.lib_plan_again)) }
            }
            result == null -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(stringResource(R.string.lib_plan_preparing), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { ((p?.progress ?: 0) / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.lib_plan_nothing_changes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            result.optString("kind") == "dupes" -> DupesContent(result, onPlan)
            else -> PlanContent(api, p!!.id, result, onStarted, onEncoder = { encoder = it }) { error = it }
        }
    }
}

@Composable
private fun planKindTitle(kind: String) = when (kind) {
    "free" -> stringResource(R.string.lib_free_up)
    "shrink" -> stringResource(R.string.lib_shrink)
    "tidy" -> stringResource(R.string.lib_tidy)
    "dupes" -> stringResource(R.string.lib_dupes)
    "modernize" -> stringResource(R.string.lib_modernize)
    "subs" -> stringResource(R.string.lib_subs)
    "subs_get" -> stringResource(R.string.subs_get_title)
    "trash" -> stringResource(R.string.lib_delete)
    else -> stringResource(R.string.lib_move)
}

private fun JSONObject.objects(k: String): List<JSONObject> =
    optJSONArray(k)?.let { a -> List(a.length()) { a.optJSONObject(it) ?: JSONObject() } } ?: emptyList()

@Composable
private fun PlanContent(api: Api, planId: String, r: JSONObject, onStarted: () -> Unit, onEncoder: (String) -> Unit,
                        onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = r.optString("kind")
    val items = remember(planId) { r.objects("items") }
    val skipped = remember(planId) { r.objects("skipped") }
    val encoders = remember(planId) { if (kind == "shrink") r.objects("encoders") else emptyList() }
    var picked by remember(planId) { mutableStateOf(items.indices.filter { !items[it].optBoolean("unsure") }.toSet()) }
    var showSkipped by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { PlanSummary(r, items.size) }
            if (encoders.size > 1) item { EncoderChoice(encoders, r.optString("encoder_id"), onEncoder) }
            if (items.isEmpty()) item {
                Text(stringResource(R.string.lib_plan_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(items) { i, item ->
                Row(Modifier.fillMaxWidth().clickable { picked = if (i in picked) picked - i else picked + i }
                    .padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(i in picked, { picked = if (it) picked + i else picked - i })
                    Column(Modifier.weight(1f)) { PlanItem(kind, item) }
                }
                HorizontalDivider()
            }
            if (skipped.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showSkipped = !showSkipped }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (showSkipped) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lib_skipped, skipped.size), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (showSkipped) items(skipped) { s ->
                    Column(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp)) {
                        Text(s.optString("name").ifEmpty { s.optString("path").substringAfterLast('/') },
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        s.optStr("reason")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val bytes = picked.sumOf { items[it].optLong("size") }
                Text(if (kind == "subs_get") pluralStringResource(R.plurals.lib_items, picked.size, picked.size)
                    else pluralStringResource(R.plurals.lib_picked, picked.size, picked.size, humanBytes(bytes)),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Button({
                    starting = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.taskStart(planId, picked.sorted()) }
                            onStarted()
                        } catch (e: ApiException) {
                            starting = false
                            onError(if (e.code == 404) ctx.getString(R.string.lib_plan_expired) else e.message ?: ctx.getString(R.string.media_error))
                        }
                    }
                }, enabled = picked.isNotEmpty() && !starting) {
                    Text(when (kind) {
                        "shrink" -> stringResource(R.string.lib_shrink)
                        "subs" -> stringResource(R.string.lib_subs_go)
                        "subs_get" -> stringResource(R.string.subs_get_go)
                        "trash" -> stringResource(R.string.lib_delete)
                        else -> stringResource(R.string.lib_move)
                    })
                }
            }
        }
    }
}

@Composable
private fun PlanSummary(r: JSONObject, count: Int) {
    val lines = mutableListOf<String>()
    val kind = r.optString("kind")
    lines += pluralStringResource(R.plurals.lib_items, count, count) + if (kind == "subs_get") "" else " · " + humanBytes(r.optLong("bytes"))
    if (r.has("free_before")) lines += stringResource(R.string.lib_sum_free, humanBytes(r.optLong("free_before")),
        humanBytes(r.optLong("free_after")), humanBytes(r.optLong("total")))
    if (r.has("checked")) r.optInt("checked").let { lines += pluralStringResource(R.plurals.lib_sum_checked, it, it) }
    if (kind == "shrink") {
        lines += stringResource(R.string.lib_sum_shrink, humanBytes(r.optLong("bytes")), humanBytes(r.optLong("est")),
            humanBytes(r.optLong("saved")))
        lines += stringResource(R.string.lib_sum_hours, String.format(java.util.Locale.ROOT, "%.1f", r.optDouble("hours", 0.0)))
        r.optStr("encoder")?.let { lines += stringResource(R.string.lib_sum_encoder, it) }
    }
    if (kind == "subs") {
        lines += stringResource(R.string.lib_sum_subs_time, r.optInt("minutes"))
        lines += stringResource(R.string.lib_sum_subs_hint)
    }
    if (kind == "subs_get") {
        lines += stringResource(R.string.lib_sum_subs_time, r.optInt("minutes"))
        lines += stringResource(R.string.subs_get_hint)
    }
    if (r.has("trash_days")) r.optInt("trash_days").let {
        lines += pluralStringResource(if (kind == "shrink") R.plurals.lib_sum_originals_kept else R.plurals.lib_sum_trash_kept, it, it)
    }
    val warnings = r.optJSONArray("warnings")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (kind == "move" && count > 0) Text(stringResource(R.string.lib_sum_move_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    HorizontalDivider()
}

/** Shrink preview (agent 1.7+): the encoders to choose from, each with what it saves and how long the computer works. */
@Composable
private fun EncoderChoice(encoders: List<JSONObject>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.lib_encoder), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
        encoders.forEach { e ->
            val id = e.optString("id")
            val pick = { if (id != selected) onPick(id) }
            Row(Modifier.fillMaxWidth().clickable(onClick = pick).padding(end = 16.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(id == selected, pick)
                Column(Modifier.weight(1f)) {
                    Text(e.optString("label").ifEmpty { id }, style = MaterialTheme.typography.bodyMedium)
                    val parts = buildList {
                        add(stringResource(R.string.lib_encoder_saves, humanBytes(e.optLong("saved"))))
                        e.optDouble("work_hours").takeIf { !it.isNaN() }?.let {
                            add(stringResource(R.string.lib_encoder_work, String.format(java.util.Locale.getDefault(), "%.1f", it)))
                        }
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    e.optStr("note")?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun guessKind(k: String) = when (k) {
    "movie", "film" -> stringResource(R.string.lib_guess_movie)
    "series", "tv", "show" -> stringResource(R.string.lib_guess_series)
    "kids" -> stringResource(R.string.media_lib_tag_kids)
    else -> k
}

@Composable
private fun confidence(c: String) = when (c) {
    "high" -> stringResource(R.string.lib_conf_high)
    "medium" -> stringResource(R.string.lib_conf_medium)
    "low" -> stringResource(R.string.lib_conf_low)
    else -> c
}

@Composable
private fun PlanItem(kind: String, it: JSONObject) {
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val name = it.optString("name").ifEmpty { it.optString("path").substringAfterLast('/') }
    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    when (kind) {
        "shrink" -> if (it.optBoolean("audio_fix")) {
            // damaged sound in a modern picture (agent 1.15.0+): only the sound is re-encoded
            val codecs = it.optJSONArray("audio_codecs")?.let { a -> List(a.length()) { k -> a.optString(k) } }.orEmpty()
            Text(stringResource(R.string.lib_item_audio_fix, codecs.joinToString().ifEmpty { "?" }) + " · " +
                humanBytes(it.optLong("size")), style = small, color = dim)
        } else {
            val h = it.optInt("height"); val to = it.optInt("to_height")
            val codec = it.optString("codec").uppercase()
            val from = listOfNotNull(codec.ifEmpty { null }, h.takeIf { v -> v > 0 }?.let { v -> "${v}p" }).joinToString(" ")
            Text("$from → HEVC" + (if (to > 0) " ${to}p" else "") + " · ${humanBytes(it.optLong("size"))} → ${humanBytes(it.optLong("est"))}",
                style = small, color = dim)
        }
        "subs" -> {
            val locked = stringResource(R.string.lib_subs_locked)
            it.objects("subs").forEach { x ->
                Text(x.optString("name") + (if (x.optBoolean("locked")) " · $locked" else ""), style = small, color = dim,
                    maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        "subs_get" -> {
            val ui = java.util.Locale.getDefault()
            val langs = it.optJSONArray("langs")?.let { a -> List(a.length()) { k -> langName(a.optString(k), ui) } }.orEmpty()
            Text(stringResource(R.string.subs_get_missing, langs.joinToString()) + " · " +
                it.optString("path").substringBeforeLast('/', it.optString("root_name")),
                style = small, color = dim, maxLines = 2, overflow = TextOverflow.StartEllipsis)
        }
        "trash" -> Text(listOf(it.optString("root_name"), it.optString("path").substringBeforeLast('/', "")).filter { s -> s.isNotEmpty() }
            .joinToString(" / ") + " · " + humanBytes(it.optLong("size")), style = small, color = dim, maxLines = 2, overflow = TextOverflow.StartEllipsis)
        else -> {
            val to = it.optString("to_name") + (it.optStr("to_sub")?.let { s -> " / $s" } ?: "")
            Text("${humanBytes(it.optLong("size"))} · ${it.optString("root_name")} → $to", style = small, color = dim,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (it.has("torrent") && !it.isNull("torrent")) Text(stringResource(R.string.lib_via_torrent), style = small,
                color = MaterialTheme.colorScheme.tertiary)
            it.optJSONObject("guess")?.let { g ->
                val what = listOfNotNull(g.optStr("kind")?.let { k -> guessKind(k) }, g.optStr("title"),
                    g.optInt("year", 0).takeIf { y -> y > 0 }?.let { y -> "($y)" }).joinToString(" ")
                Text(stringResource(R.string.lib_guess_line, what, confidence(g.optString("confidence"))), style = small,
                    color = if (it.optBoolean("unsure")) MaterialTheme.colorScheme.error else dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Duplicates: groups of identical files; at least one file of each group must stay, so not all of them can be ticked. */
@Composable
private fun DupesContent(r: JSONObject, onPlan: (String) -> Unit) {
    val groups = remember(r) { r.objects("groups") }
    var sel by remember(r) { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (groups.isEmpty()) stringResource(R.string.lib_dupes_none)
                        else pluralStringResource(R.plurals.lib_dupes_summary, groups.size, groups.size, humanBytes(r.optLong("wasted"))),
                        style = MaterialTheme.typography.bodyMedium)
                    if (groups.isNotEmpty()) Text(stringResource(R.string.lib_dupes_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            itemsIndexed(groups) { g, grp ->
                val files = grp.objects("files")
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(pluralStringResource(R.plurals.lib_dupes_group, files.size, files.size, humanBytes(grp.optLong("size"))),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    files.forEachIndexed { i, f ->
                        val on = (g to i) in sel
                        val othersOn = files.indices.count { k -> k != i && (g to k) in sel }
                        val canTick = on || othersOn < files.size - 1
                        Row(Modifier.fillMaxWidth().clickable(enabled = canTick) { sel = if (on) sel - (g to i) else sel + (g to i) }
                            .padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { sel = if (it) sel + (g to i) else sel - (g to i) }, enabled = canTick)
                            Column(Modifier.weight(1f)) {
                                Text(f.optString("path").substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(f.optString("root_name") + f.optString("path").substringBeforeLast('/', "").let { p -> if (p.isEmpty()) "" else " / $p" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.StartEllipsis)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        if (groups.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.lib_dupes_selected, sel.size, sel.size), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Button({
                    val items = sel.sortedWith(compareBy({ it.first }, { it.second })).map { (g, i) ->
                        val f = groups[g].objects("files")[i]
                        f.optInt("root") to f.optString("path")
                    }
                    onPlan(PlanBody.trash(items))
                }, enabled = sel.isNotEmpty()) { Text(stringResource(R.string.lib_delete_selected)) }
            }
        }
    }
}

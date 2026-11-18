package net.treetank.magneticplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder to open after switching the active server from the Library (folders of another server). */
object LibraryNav {
    @Volatile private var pending: Pair<String, Int>? = null
    fun openAfterSwitch(srvKey: String, root: Int) { pending = srvKey to root }
    fun take(srvKey: String): Int? = pending?.takeIf { it.first == srvKey }?.second?.also { pending = null }
}

/**
 * Library: selected directories of the computer, only video/music. [onSwitchServer] makes another server active (its
 * folders are listed too) and opens the given folder there.
 */
@Composable
fun LibraryTab(api: Api, online: Boolean, snack: SnackbarHostState, onPlayingOnPc: () -> Unit,
               onSwitchServer: (String, Int) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by rememberSaveable { mutableStateOf(LibraryNav.take(api.srv.key)) }
    var path by rememberSaveable { mutableStateOf("") }
    // disks and tidy-up, and a plan (preview) on top of whatever was open: its request body, or null
    var disks by rememberSaveable { mutableStateOf(false) }
    var plan by rememberSaveable { mutableStateOf<String?>(null) }
    if (!online) return Hint(stringResource(R.string.media_lib_offline))
    val r = root
    val p = plan
    when {
        p != null -> {
            BackHandler { plan = null }
            PlanScreen(api, p, onBack = { plan = null }, onPlan = { plan = it }, onStarted = {
                plan = null; disks = true
                scope.launch { snack.showSnackbar(ctx.getString(R.string.lib_task_started)) }
            })
        }
        disks -> {
            BackHandler { disks = false }
            DisksScreen(api, snack, onBack = { disks = false }) { plan = it }
        }
        r == null -> RootsScreen(api, snack, onPlayingOnPc, onSwitchServer, onDisks = { disks = true }, onPlan = { plan = it }) { r0, p0 ->
            root = r0; path = p0
        }
        else -> {
            BackHandler { if (path.isEmpty()) root = null else path = path.substringBeforeLast('/', "") }
            DirScreen(api, r, path, snack, onPlayingOnPc,
                onOpen = { path = if (path.isEmpty()) it else "$path/$it" },
                onUp = { if (path.isEmpty()) root = null else path = path.substringBeforeLast('/', "") },
                onPlan = { plan = it })
        }
    }
}

@Composable
private fun RootsScreen(api: Api, snack: SnackbarHostState, onPlayingOnPc: () -> Unit, onSwitchServer: (String, Int) -> Unit,
                        onDisks: () -> Unit, onPlan: (String) -> Unit, onOpen: (Int, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<LibRoot>?>(null) }
    var picker by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LibRoot?>(null) }
    var editing by remember { mutableStateOf<LibRoot?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // a household member on a server with tags (agent 1.4+) also sees directories hidden from this phone, in order to set them up
    // fresh server entry: `api.srv` may predate the /api/info refresh (e.g. after an agent update)
    val srv = remember(reload) { Servers.get(ctx, api.srv.key) ?: api.srv }
    val manage = srv.libraryTags && !srv.isGuest

    LaunchedEffect(reload, manage) {
        roots = try { withContext(Dispatchers.IO) { api.libraryRoots(manage) } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)); emptyList()
        }
    }
    // folders of the other servers with a library that respond now (decision 11: one library across servers)
    var others by remember { mutableStateOf<List<Pair<Server, List<LibRoot>>>>(emptyList()) }
    LaunchedEffect(Unit) {
        val candidates = Servers.all(ctx).filter { it.key != api.srv.key && it.hasLibrary && it.host.isNotBlank() && it.token.isNotBlank() }
        others = withContext(Dispatchers.IO) {
            candidates.map { s ->
                async {
                    val a = Api(api.s, s)
                    if (!a.ping(1500)) null
                    else runCatching { s to a.libraryRoots().filter { it.exists && it.open && "kopia" !in it.tags } }.getOrNull()
                        ?.takeIf { it.second.isNotEmpty() }
                }
            }.awaitAll().filterNotNull()
        }
    }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val tasks = srv.libraryTasks && !srv.isGuest
    Column(Modifier.fillMaxSize()) {
    if (srv.librarySearch || tasks) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (srv.librarySearch) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.media_lib_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.media_cancel)) } })
        } else Spacer(Modifier.weight(1f))
        if (tasks) IconButton(onDisks) { Icon(Icons.Filled.CleaningServices, stringResource(R.string.lib_disks_title)) }
    }
    if (query.trim().length >= 2) {
        SearchResults(api, query.trim(), snack, onPlayingOnPc, onOpen)
        return@Column
    }
    val allTags = ((roots ?: emptyList()) + others.flatMap { it.second }).flatMap { it.tags }.distinct()
    if (allTags.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(tagFilter == null, { tagFilter = null }, label = { Text(stringResource(R.string.lib_tag_all)) })
        allTags.forEach { t -> FilterChip(tagFilter == t, { tagFilter = if (tagFilter == t) null else t }, label = { Text(tagLabel(t)) }) }
    }
    fun shown(r: LibRoot) = tagFilter == null || tagFilter in r.tags
    Box(Modifier.fillMaxSize()) {
        val list = roots?.filter { shown(it) }
        if (list == null) Hint(stringResource(R.string.media_loading))
        else if (list.isEmpty() && others.isEmpty()) Hint(stringResource(if (tagFilter == null) R.string.media_lib_no_roots else R.string.lib_tag_none))
        else LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.index }) { r ->
                Card(Modifier.fillMaxWidth().clickable(enabled = r.exists && r.open) { onOpen(r.index, "") }) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.exists) Icons.Filled.Storage else Icons.Filled.FolderOff, null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (r.exists) r.path else stringResource(R.string.media_lib_unavailable, r.path), style = MaterialTheme.typography.bodySmall,
                                color = if (r.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            rootSummary(r)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (manage) IconButton({ editing = r }) { Icon(Icons.Filled.Edit, stringResource(R.string.media_lib_edit)) }
                        IconButton({ removing = r }) { Icon(Icons.Filled.Delete, stringResource(R.string.media_cd_remove_from_library)) }
                    }
                }
            }
            others.forEach { (s, rs) ->
                val shownHere = rs.filter { shown(it) }
                if (shownHere.isNotEmpty()) item(key = "srv/${s.key}") {
                    Text(stringResource(R.string.lib_on_server, s.name), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
                items(shownHere, key = { "srv/${s.key}/${it.index}" }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { onSwitchServer(s.key, r.index) }) {
                        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall)
                                if (r.tags.isNotEmpty()) Text(r.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picker = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.media_lib_add_folder)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    }
    if (picker) DirPicker(api, onDismiss = { picker = false }) { chosen ->
        picker = false
        scope.launch {
            try {
                roots = withContext(Dispatchers.IO) { api.libraryAdd(chosen) }
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
            }
        }
    }
    editing?.let { r ->
        RootEditDialog(api, srv, r, roots ?: emptyList(), onDismiss = { editing = null },
            onShrink = { body -> editing = null; onPlan(body) }) { list -> editing = null; roots = list }
    }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.media_lib_remove_title)) },
            text = { Text(stringResource(R.string.media_lib_remove_text, r.path)) },
            confirmButton = {
                TextButton({
                    removing = null
                    scope.launch {
                        try { roots = withContext(Dispatchers.IO) { api.libraryRemove(r.index) } }
                        catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                    }
                }) { Text(stringResource(R.string.media_remove)) }
            },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.media_cancel)) } },
        )
    }
}

/** Search across the library folders this device sees: folders open, files have the usual actions. */
@Composable
private fun SearchResults(api: Api, query: String, snack: SnackbarHostState, onPlayingOnPc: () -> Unit, onOpen: (Int, String) -> Unit) {
    val ctx = LocalContext.current
    var hits by remember { mutableStateOf<List<LibHit>?>(null) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(350)  // wait until typing pauses
        hits = try { withContext(Dispatchers.IO) { api.librarySearch(query) } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)); emptyList()
        }
    }
    val list = hits ?: return Hint(stringResource(R.string.media_loading))
    if (list.isEmpty()) return Hint(stringResource(R.string.media_lib_search_none))
    val covers = remember { (Servers.get(ctx, api.srv.key) ?: api.srv).libraryCovers }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { "${it.root}/${it.path}" }) { h ->
            val name = h.path.substringAfterLast('/')
            val parent = h.path.substringBeforeLast('/', "")
            val where = h.rootName + (if (parent.isNotEmpty()) " / $parent" else "")
            if (h.dir) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(h.root, h.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (covers) CoverThumb(api, h.root, h.path, 48.dp) { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) }
                    else Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                }
            } else {
                val ref = MediaRef.InLibrary(h.root, h.path, api.srv.key)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (covers && h.media != "audio") Row(Modifier.clickable { onOpen(h.root, parent) }, verticalAlignment = Alignment.CenterVertically) {
                        CoverThumb(api, h.root, h.path) { Icon(Icons.Filled.Movie, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("$where · ${humanBytes(h.size)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.StartEllipsis)
                        }
                    } else {
                    Row(Modifier.clickable { onOpen(h.root, parent) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (h.media == "audio") Icons.Filled.MusicNote else Icons.Filled.Movie, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text(name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text("$where · ${humanBytes(h.size)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp),
                        maxLines = 2, overflow = TextOverflow.StartEllipsis)
                    }
                    Box(Modifier.padding(start = 24.dp, top = 4.dp)) { MediaActions(api, ref, snack, onPlayingOnPc) }
                }
            }
            HorizontalDivider()
        }
    }
}

/** One line under the path: tags, who sees it, how many excluded. Null when the directory is "as before". */
@Composable
private fun rootSummary(r: LibRoot): String? {
    val parts = mutableListOf<String>()
    if (r.tags.isNotEmpty()) parts += r.tags.joinToString(" ") { "#$it" }
    if ("kopia" in r.tags) parts += stringResource(R.string.media_lib_hidden_backup)
    when (r.visible) {
        "owners" -> parts += stringResource(R.string.media_lib_vis_owners_short)
        "devices" -> parts += pluralStringResource(R.plurals.media_lib_vis_devices_short, r.devices.size, r.devices.size)
    }
    if (r.exclude.isNotEmpty()) parts += pluralStringResource(R.plurals.media_lib_excluded_short, r.exclude.size, r.exclude.size)
    if (!r.open && "kopia" !in r.tags) parts += stringResource(R.string.media_lib_not_for_this_phone)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Directory settings: name, tags, who sees it, excluded subdirectories; on agent 1.6+ also the storage profile and
 * the folder it is copied to ([all]: the other folders). [onShrink] opens the shrink preview (a plan request body).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RootEditDialog(api: Api, srv: Server, r: LibRoot, all: List<LibRoot>, onDismiss: () -> Unit, onShrink: (String) -> Unit,
                           onSaved: (List<LibRoot>) -> Unit) {
    val scope = rememberCoroutineScope()
    val tasks = srv.libraryTasks && !srv.isGuest
    var profile by remember { mutableStateOf<String?>(null) }  // null until read from the agent
    var savedProfile by remember { mutableStateOf<String?>(null) }
    var replicateTo by remember { mutableStateOf(r.replicateTo) }
    var diskOf by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }  // folder index → disk name
    var repMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(r.name) }
    var tags by remember { mutableStateOf(r.tags) }
    var newTag by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(r.visible) }
    var chosen by remember { mutableStateOf(r.devices.toSet()) }
    var exclude by remember { mutableStateOf(r.exclude.toSet()) }
    var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }  // id, nazwa
    var subdirs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val guestSuffix = stringResource(R.string.media_lib_guest_suffix)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            devices = try {
                val a = api.devices()
                List(a.length()) { a.getJSONObject(it) }.filter { !it.optBoolean("legacy") }
                    .map { it.getString("id") to it.optString("name") + if (it.optString("role") == "guest") guestSuffix else "" }
            } catch (e: ApiException) { emptyList() }
            subdirs = try {
                if (r.exists) api.fsDirs(r.path).second.map { it.removePrefix(r.path.trimEnd('/') + "/") } else emptyList()
            } catch (e: ApiException) { emptyList() }
            if (tasks) runCatching { api.disks() }.getOrNull()?.let { d ->
                diskOf = d.disks.flatMap { disk -> disk.roots.map { it.root to disk.name.ifEmpty { disk.mount } } }.toMap()
                val p = d.disks.flatMap { it.roots }.firstOrNull { it.root == r.index }?.profile ?: "original"
                profile = p; savedProfile = p
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(r.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.media_lib_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())

                Section(stringResource(R.string.media_lib_tags))
                Text(stringResource(R.string.media_lib_tags_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (LIB_TAGS + tags.filter { it !in LIB_TAGS }).forEach { t ->
                        FilterChip(t in tags, { tags = if (t in tags) tags - t else tags + t }, label = { Text(tagLabel(t)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTag, { newTag = it }, label = { Text(stringResource(R.string.media_lib_own_tag)) },
                        singleLine = true, modifier = Modifier.weight(1f))
                    val t = newTag.trim().lowercase()
                    TextButton({ if (t !in tags) tags = tags + t; newTag = "" }, enabled = t.isNotEmpty()) {
                        Text(stringResource(R.string.media_lib_tag_add))
                    }
                }
                if ("kopia" in tags) Text(stringResource(R.string.media_lib_backup_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section(stringResource(R.string.media_lib_who_sees))
                listOf("all" to R.string.media_lib_vis_all, "owners" to R.string.media_lib_vis_owners, "devices" to R.string.media_lib_vis_devices)
                    .forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { visible = v }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(visible == v, { visible = v }); Text(stringResource(label))
                        }
                    }
                if (visible == "devices") {
                    val list = devices
                    if (list == null) Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodySmall)
                    else list.forEach { (id, n) ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (id in chosen) chosen - id else chosen + id }
                            .padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in chosen, { chosen = if (it) chosen + id else chosen - id }); Text(n)
                        }
                    }
                }

                Section(stringResource(R.string.media_lib_excluded))
                Text(stringResource(R.string.media_lib_excluded_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val dirs = subdirs
                if (dirs == null) Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodySmall)
                else (dirs + exclude.filter { it !in dirs }.sorted()).forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { exclude = if (d in exclude) exclude - d else exclude + d },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d in exclude, { exclude = if (it) exclude + d else exclude - d })
                        Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (tasks) {
                    Section(stringResource(R.string.lib_profile))
                    Text(stringResource(R.string.lib_profile_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val p = profile
                    if (p == null) Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodySmall)
                    else {
                        ProfileChoice(profilesOf(srv), p) { profile = it }
                        OutlinedButton({ onShrink(PlanBody.shrink(r.index, "", p)) }, enabled = p != "original" && r.exists) {
                            Text(stringResource(R.string.lib_shrink_now))
                        }
                        Text(stringResource(R.string.lib_shrink_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Section(stringResource(R.string.lib_replicate))
                    Text(stringResource(R.string.lib_replicate_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val targets = all.filter { it.index != r.index && it.id.isNotEmpty() }
                    fun label(t: LibRoot?) = t?.let { x -> x.name + (diskOf[x.index]?.let { d -> " ($d)" } ?: "") }
                    val noneLabel = stringResource(R.string.lib_replicate_none)
                    Box {
                        OutlinedButton({ repMenu = true }) {
                            Text(label(targets.firstOrNull { it.id == replicateTo }) ?: replicateTo ?: noneLabel,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(repMenu, { repMenu = false }) {
                            DropdownMenuItem({ Text(noneLabel) }, { replicateTo = null; repMenu = false })
                            targets.forEach { t -> DropdownMenuItem({ Text(label(t)!!) }, { replicateTo = t.id; repMenu = false }) }
                        }
                    }
                    if (replicateTo != null && replicateTo == r.replicateTo) ReplicaLine(r.replica)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                saving = true
                scope.launch {
                    try {
                        onSaved(withContext(Dispatchers.IO) {
                            val p = profile
                            if (tasks && p != null && p != savedProfile) api.setProfile(r.index, "", p)
                            api.libraryUpdate(r.index, name.trim(), tags, visible, chosen.toList(), exclude.toList(),
                                if (tasks && replicateTo != r.replicateTo) true to replicateTo else null)
                        })
                    } catch (e: ApiException) { error = e.message; saving = false }
                }
            }, enabled = !saving && name.isNotBlank() && (visible != "devices" || chosen.isNotEmpty())) {
                Text(stringResource(R.string.media_lib_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

private val LIB_TAGS = listOf("filmy", "seriale", "bajki", "kopia")

/** Built-in tags are shown in the phone's language, but always stored in Polish (that is how the agent knows them). */
@Composable
internal fun tagLabel(t: String) = when (t) {
    "filmy" -> stringResource(R.string.media_lib_tag_movies)
    "seriale" -> stringResource(R.string.media_lib_tag_series)
    "bajki" -> stringResource(R.string.media_lib_tag_kids)
    "kopia" -> stringResource(R.string.media_lib_tag_backup)
    else -> t
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** Directory picker on the computer (folder navigation). */
@Composable
private fun DirPicker(api: Api, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<String?>(null) }
    var dirs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        dirs = null
        try {
            val (p, d) = withContext(Dispatchers.IO) { api.fsDirs(path) }
            parent = p; dirs = d; error = null
        } catch (e: ApiException) {
            error = e.message; dirs = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (path.isEmpty()) stringResource(R.string.media_lib_pick_folder) else path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (path.isNotEmpty()) Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (path.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().clickable { path = parent ?: "" }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(12.dp)); Text("..")
                        }
                    }
                    items(dirs ?: emptyList()) { d ->
                        Row(Modifier.fillMaxWidth().clickable { path = d }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(12.dp))
                            Text(if (path.isEmpty()) d else d.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onPick(path) }, enabled = path.isNotEmpty()) { Text(stringResource(R.string.media_lib_add_this)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

@Composable
private fun DirScreen(
    api: Api, root: Int, path: String, snack: SnackbarHostState, onPlayingOnPc: () -> Unit,
    onOpen: (String) -> Unit, onUp: () -> Unit, onPlan: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val srv = remember { Servers.get(ctx, api.srv.key) ?: api.srv }
    val covers = srv.libraryCovers
    var shrinking by remember { mutableStateOf(false) }
    var dir by remember(root, path) { mutableStateOf<LibDir?>(null) }
    LaunchedEffect(root, path) {
        dir = try { withContext(Dispatchers.IO) { api.browse(root, path) } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)); LibDir(root, path, emptyList(), emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            Text(if (path.isEmpty()) "/" else path, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            if (srv.libraryTasks && !srv.isGuest) IconButton({ shrinking = true }) {
                Icon(Icons.Filled.Compress, stringResource(R.string.lib_shrink_folder))
            }
        }
        if (shrinking) ShrinkDialog(srv, if (path.isEmpty()) "/" else path, onDismiss = { shrinking = false }) { profile ->
            shrinking = false
            onPlan(PlanBody.shrink(root, path, profile))
        }
        HorizontalDivider()
        val d = dir ?: return Hint(stringResource(R.string.media_loading))
        if (d.dirs.isEmpty() && d.files.isEmpty()) return Hint(stringResource(R.string.media_lib_no_videos))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(d.dirs, key = { "d/$it" }) { name ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (covers) CoverThumb(api, root, if (path.isEmpty()) name else "$path/$name", 48.dp) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    } else Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            items(d.files, key = { "f/${it.name}" }) { f ->
                val ref = MediaRef.InLibrary(root, if (path.isEmpty()) f.name else "$path/${f.name}", api.srv.key)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val info = buildList {
                        add(humanBytes(f.size))
                        if (f.subs.isNotEmpty()) add(ctx.getString(R.string.media_lib_subs, f.subs.joinToString()))
                    }
                    if (covers && f.media != "audio") Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverThumb(api, root, ref.path) { Icon(Icons.Filled.Movie, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.media == "audio") Icons.Filled.MusicNote else Icons.Filled.Movie, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp))
                    }
                    Box(Modifier.padding(start = 24.dp, top = 4.dp)) { MediaActions(api, ref, snack, onPlayingOnPc) }
                }
                HorizontalDivider()
            }
        }
    }
}

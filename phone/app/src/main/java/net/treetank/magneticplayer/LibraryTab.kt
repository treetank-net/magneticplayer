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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
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

/** Biblioteka: wybrane katalogi komputera, tylko filmy/muzyka. */
@Composable
fun LibraryTab(api: Api, online: Boolean, snack: SnackbarHostState, onPlayingOnPc: () -> Unit) {
    var root by rememberSaveable { mutableStateOf<Int?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    if (!online) return Hint(stringResource(R.string.media_lib_offline))
    val r = root
    if (r == null) {
        RootsScreen(api, snack) { root = it; path = "" }
    } else {
        BackHandler { if (path.isEmpty()) root = null else path = path.substringBeforeLast('/', "") }
        DirScreen(api, r, path, snack, onPlayingOnPc,
            onOpen = { path = if (path.isEmpty()) it else "$path/$it" },
            onUp = { if (path.isEmpty()) root = null else path = path.substringBeforeLast('/', "") })
    }
}

@Composable
private fun RootsScreen(api: Api, snack: SnackbarHostState, onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<LibRoot>?>(null) }
    var picker by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LibRoot?>(null) }
    var editing by remember { mutableStateOf<LibRoot?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // domownik na serwerze z tagami (agent 1.4+) widzi też katalogi ukryte przed tym telefonem, żeby je ustawić
    // świeży wpis serwera: `api.srv` bywa sprzed odświeżenia /api/info (np. po aktualizacji agenta)
    val srv = remember(reload) { Servers.get(ctx, api.srv.key) ?: api.srv }
    val manage = srv.libraryTags && !srv.isGuest

    LaunchedEffect(reload, manage) {
        roots = try { withContext(Dispatchers.IO) { api.libraryRoots(manage) } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)); emptyList()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val list = roots
        if (list == null) Hint(stringResource(R.string.media_loading))
        else if (list.isEmpty()) Hint(stringResource(R.string.media_lib_no_roots))
        else LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.index }) { r ->
                Card(Modifier.fillMaxWidth().clickable(enabled = r.exists && r.open) { onOpen(r.index) }) {
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
        }
        ExtendedFloatingActionButton(
            onClick = { picker = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.media_lib_add_folder)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
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
        RootEditDialog(api, r, onDismiss = { editing = null }) { list -> editing = null; roots = list }
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

/** Jedna linijka pod ścieżką: tagi, kto widzi, ile wykluczonych. Null, gdy katalog jest „jak dawniej”. */
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

/** Ustawienia katalogu: nazwa, tagi, kto go widzi, wykluczone podkatalogi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RootEditDialog(api: Api, r: LibRoot, onDismiss: () -> Unit, onSaved: (List<LibRoot>) -> Unit) {
    val scope = rememberCoroutineScope()
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                saving = true
                scope.launch {
                    try {
                        onSaved(withContext(Dispatchers.IO) {
                            api.libraryUpdate(r.index, name.trim(), tags, visible, chosen.toList(), exclude.toList())
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

/** Wbudowane tagi pokazujemy w języku telefonu, ale zapisujemy zawsze po polsku (tak je zna agent). */
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

/** Wybór katalogu na komputerze (nawigacja po folderach). */
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
    onOpen: (String) -> Unit, onUp: () -> Unit,
) {
    val ctx = LocalContext.current
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
        }
        HorizontalDivider()
        val d = dir ?: return Hint(stringResource(R.string.media_loading))
        if (d.dirs.isEmpty() && d.files.isEmpty()) return Hint(stringResource(R.string.media_lib_no_videos))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(d.dirs, key = { "d/$it" }) { name ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            items(d.files, key = { "f/${it.name}" }) { f ->
                val ref = MediaRef.InLibrary(root, if (path.isEmpty()) f.name else "$path/${f.name}", api.srv.key)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.media == "audio") Icons.Filled.MusicNote else Icons.Filled.Movie, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val info = buildList {
                        add(humanBytes(f.size))
                        if (f.subs.isNotEmpty()) add(ctx.getString(R.string.media_lib_subs, f.subs.joinToString()))
                    }
                    Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp))
                    Box(Modifier.padding(start = 24.dp, top = 4.dp)) { MediaActions(api, ref, snack, onPlayingOnPc) }
                }
                HorizontalDivider()
            }
        }
    }
}

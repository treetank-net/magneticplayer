package eu.jm.magneticplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
    if (!online) return Hint("Komputer nie odpowiada. Pobrane filmy są w zakładce „Na telefonie”.")
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
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<LibRoot>?>(null) }
    var picker by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LibRoot?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        roots = try { withContext(Dispatchers.IO) { api.libraryRoots() } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: "Błąd"); emptyList()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val list = roots
        if (list == null) Hint("Wczytuję…")
        else if (list.isEmpty()) Hint("Brak katalogów. Dodaj katalog z filmami przyciskiem niżej.")
        else LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.index }) { r ->
                Card(Modifier.fillMaxWidth().clickable(enabled = r.exists) { onOpen(r.index) }) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.exists) Icons.Filled.Storage else Icons.Filled.FolderOff, null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (r.exists) r.path else "${r.path} — niedostępny", style = MaterialTheme.typography.bodySmall,
                                color = if (r.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ removing = r }) { Icon(Icons.Filled.Delete, "usuń z biblioteki") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picker = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Dodaj katalog") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (picker) DirPicker(api, onDismiss = { picker = false }) { chosen ->
        picker = false
        scope.launch {
            try {
                roots = withContext(Dispatchers.IO) { api.libraryAdd(chosen) }
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: "Błąd")
            }
        }
    }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Usunąć z biblioteki?") },
            text = { Text("„${r.path}” zniknie z listy. Pliki na dysku zostają.") },
            confirmButton = {
                TextButton({
                    removing = null
                    scope.launch {
                        try { roots = withContext(Dispatchers.IO) { api.libraryRemove(r.index) } }
                        catch (e: ApiException) { snack.showSnackbar(e.message ?: "Błąd") }
                    }
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Anuluj") } },
        )
    }
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
        title = { Text(if (path.isEmpty()) "Wybierz katalog" else path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        confirmButton = { TextButton({ onPick(path) }, enabled = path.isNotEmpty()) { Text("Dodaj ten katalog") } },
        dismissButton = { TextButton(onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun DirScreen(
    api: Api, root: Int, path: String, snack: SnackbarHostState, onPlayingOnPc: () -> Unit,
    onOpen: (String) -> Unit, onUp: () -> Unit,
) {
    var dir by remember(root, path) { mutableStateOf<LibDir?>(null) }
    LaunchedEffect(root, path) {
        dir = try { withContext(Dispatchers.IO) { api.browse(root, path) } } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: "Błąd"); LibDir(root, path, emptyList(), emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "wyżej") }
            Text(if (path.isEmpty()) "/" else path, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        val d = dir ?: return Hint("Wczytuję…")
        if (d.dirs.isEmpty() && d.files.isEmpty()) return Hint("Brak filmów w tym katalogu.")
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
                val ref = MediaRef.InLibrary(root, if (path.isEmpty()) f.name else "$path/${f.name}")
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.media == "audio") Icons.Filled.MusicNote else Icons.Filled.Movie, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val info = buildList {
                        add(humanBytes(f.size))
                        if (f.subs.isNotEmpty()) add("napisy: " + f.subs.joinToString())
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

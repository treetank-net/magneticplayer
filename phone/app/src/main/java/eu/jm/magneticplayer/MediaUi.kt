package eu.jm.magneticplayer

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OndemandVideo
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

/** Przyciski dla filmu: telefon, ekran (mpv na którymś serwerze), a w menu VLC, napisy, pobieranie. */
@Composable
fun MediaActions(api: Api, ref: MediaRef, snack: SnackbarHostState, onPlayingOnPc: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var subsDialog by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    val screens = remember { Servers.screens(ctx) }
    fun playOn(key: String) = scope.launch {
        opening = "Otwieram…"
        try {
            Screens.play(ctx, ref, key, null) { opening = it }
            onPlayingOnPc()
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: "Błąd")
        } finally {
            opening = null
        }
    }
    // powiadomienie z postępem pobierania (Android 13+ pyta o zgodę; bez niej pobieranie i tak działa)
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton({ ctx.startActivity(PlayerActivity.intent(ctx, ref)) }) {
            Icon(Icons.Filled.Smartphone, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Telefon")
        }
        if (screens.isNotEmpty()) FilledTonalButton({
            // jeden ekran i to ten sam serwer: od razu, jak dawniej; inaczej wybór ekranu
            if (screens.size == 1 && screens[0].key == ref.srv) playOn(screens[0].key) else chooser = true
        }) {
            Icon(Icons.Filled.Tv, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (screens.size == 1) screens[0].name.take(14) else "Na ekranie…")
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "więcej") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Otwórz w VLC") }, {
                    menu = false
                    scope.launch {
                        val subs = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
                        openInVlc(ctx, api.mediaUrl(ref), ref.title, subs.firstOrNull()?.let { api.subUrl(ref, it.name) })
                    }
                }, leadingIcon = { Icon(Icons.Filled.OndemandVideo, null) })
                DropdownMenuItem({ Text("Napisy…") }, { menu = false; subsDialog = true },
                    leadingIcon = { Icon(Icons.Filled.Subtitles, null) })
                DropdownMenuItem({ Text("Pobierz na telefon") }, {
                    menu = false
                    if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                            android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    DownloadService.start(ctx, ref)
                    scope.launch { snack.showSnackbar("Pobieram na telefon — postęp w zakładce „Na telefonie”") }
                }, leadingIcon = { Icon(Icons.Filled.DownloadForOffline, null) })
            }
        }
    }
    if (subsDialog) SubsDialog(api, ref) { subsDialog = false }
    if (chooser) ScreenChooser(ref, onPhone = null, onPick = { chooser = false; playOn(it) }) { chooser = false }
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

/** Napisy: te, które już leżą obok filmu, + wyszukiwanie na komputerze z podglądem postępu każdego źródła. */
@Composable
fun SubsDialog(api: Api, ref: MediaRef, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var have by remember { mutableStateOf<List<SubFile>?>(null) }
    var progress by remember { mutableStateOf<SubsProgress?>(null) }
    var found by remember { mutableStateOf<SubsFound?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    // ręczna poprawka tytułu (np. plik po hiszpańsku, napisy pod angielskim tytułem)
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
        title = { Text("Napisy", maxLines = 1) },
        text = {
            Column {
                Text(ref.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                have?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("Już są: " + list.joinToString { it.name.takeLast(30) }, style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3FA96B))
                    Spacer(Modifier.height(4.dp))
                }
                done?.let {
                    Text("Pobrano: $it — gra na komputerze? Włączyły się same. W telefonie: otwórz film jeszcze raz.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FA96B))
                    Spacer(Modifier.height(4.dp))
                }
                val f = found
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    f == null -> {
                        SearchProgress(progress)
                        val p = progress
                        // jedne źródło już coś ma, a inne jeszcze mieli — pozwól nie czekać
                        if (p != null && p.phase == "search" && p.providers.any { it.state == "running" || it.state == "pending" }) {
                            TextButton({
                                stopping = true
                                jobId?.let { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { api.subsStop(id) } } } }
                            }, enabled = !stopping) {
                                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (stopping) "Kończę…" else "Wystarczy — pokaż, co już jest")
                            }
                        }
                    }
                    else -> {
                        if (f.hidden > 0) Text("Ukryto ${f.hidden} wyników do innych filmów (inny tytuł, hash się nie zgadza).",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.results.isEmpty()) Text("Nic nie znaleziono dla „${f.query}” (${api.s.subLanguages}). " +
                            "Jeśli plik ma tytuł w innym języku, wpisz niżej tytuł angielski albo oryginalny.",
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
                        OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text("Tytuł") }, singleLine = true)
                        OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.width(76.dp),
                            label = { Text("Rok") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    TextButton({ override = title.ifBlank { null } to year.toIntOrNull(); nonce++ }) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Szukaj z tym tytułem")
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Zamknij") } },
    )
}

@Composable
private fun SearchProgress(p: SubsProgress?) {
    val phase = when {
        p == null -> "Łączę z komputerem…"
        p.phase == "file" -> "Liczę hash pliku i rozpoznaję tytuł…"
        p.phase == "match" -> "Porównuję wyniki z plikiem: ${p.matched}/${p.total}"
        p.round == 2 -> "Nic pasującego — druga runda z tytułem z nazwy: „${p.query}”"
        else -> "Szukam: „${p.query ?: "…"}”"
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
                "running" -> "szukam…"
                "done" -> "${pr.count ?: 0} wyn." + (pr.secs?.let { " · ${"%.0f".format(it)} s" } ?: "")
                "pending" -> "czeka"
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
                Icon(Icons.Filled.CheckCircle, "pasuje do pliku", Modifier.size(16.dp), tint = Color(0xFF3FA96B))
                Text(" pasuje do pliku", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3FA96B))
            }
            Spacer(Modifier.weight(1f))
            if (busy == r.key) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(PROVIDER_NAMES[r.provider] ?: r.provider, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.release, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (r.hi) Text("dla niesłyszących", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package eu.jm.magneticplayer

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val TAB_TORRENTS = 0
const val TAB_LIBRARY = 1
const val TAB_REMOTE = 2
const val TAB_SETTINGS = 3

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var api: Api
    private val tab = mutableIntStateOf(0)
    private val settingsVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = Settings(this)
        api = Api(settings)
        if (!settings.configured) tab.intValue = TAB_SETTINGS
        handleIntent(intent)
        setContent { AppTheme { MainScreen(api, tab, settingsVersion) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        if (i.hasExtra("tab")) tab.intValue = i.getIntExtra("tab", 0)
        val data = i.data ?: return
        if (data.scheme == "magneticplayer" && settings.applyPairUri(data)) {
            settingsVersion.intValue++
            tab.intValue = TAB_TORRENTS
            Toast.makeText(this, "Sparowano z ${settings.host}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Na zakładce Pilot przyciski głośności telefonu sterują głośnością mpv na komputerze. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (tab.intValue == TAB_REMOTE && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val d = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
            lifecycleScope.launch(Dispatchers.IO) { runCatching { api.playerCmd("volume_rel", d) } }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Wywołanie API w tle; błąd trafia do snackbara. */
private fun CoroutineScope.io(snack: SnackbarHostState, block: suspend () -> Unit) = launch {
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: ApiException) {
        snack.showSnackbar(e.message ?: "Błąd")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(api: Api, tab: MutableIntState, settingsVersion: MutableIntState) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var hostname by remember { mutableStateOf<String?>(null) }
    var waking by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmSuspend by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updating by remember { mutableStateOf<Int?>(null) }
    val ver = settingsVersion.intValue
    LaunchedEffect(online == true, ver) {
        if (online == true) update = withContext(Dispatchers.IO) { Updater.check(ctx, api) }
    }

    LaunchedEffect(ver) {
        while (true) {
            val ok = withContext(Dispatchers.IO) { api.ping() }
            online = ok
            if (ok && hostname == null) {
                hostname = withContext(Dispatchers.IO) { runCatching { api.info().optString("hostname") }.getOrNull() }
            }
            delay(if (ok) 5000 else 3000)
        }
    }

    fun wake() = scope.launch {
        try {
            api.ensureAwake { waking = it }
            online = true
            snack.showSnackbar("Komputer działa")
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: "Błąd")
        } finally {
            waking = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MagneticPlayer")
                        val (txt, col) = when {
                            waking != null -> waking!! to MaterialTheme.colorScheme.tertiary
                            online == true -> "● ${hostname ?: api.s.host} — działa" to Color(0xFF3FA96B)
                            online == false -> "● ${api.s.host} — śpi albo wyłączony" to MaterialTheme.colorScheme.error
                            else -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(txt, style = MaterialTheme.typography.labelMedium, color = col)
                    }
                },
                actions = {
                    if (online == false && waking == null) {
                        TextButton({ wake() }) { Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(4.dp)); Text("Obudź") }
                    }
                    if (online == true && waking == null) {
                        TextButton({ confirmSuspend = true }) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(4.dp)); Text("Uśpij") }
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Włącz i odblokuj ekran") }, {
                                menu = false; scope.io(snack) { api.screenOn() }
                            }, leadingIcon = { Icon(Icons.Filled.LightMode, null) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab.intValue == TAB_TORRENTS, { tab.intValue = TAB_TORRENTS }, { Icon(Icons.Filled.Download, null) }, label = { Text("Torrenty") })
                NavigationBarItem(tab.intValue == TAB_LIBRARY, { tab.intValue = TAB_LIBRARY }, { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text("Biblioteka") })
                NavigationBarItem(tab.intValue == TAB_REMOTE, { tab.intValue = TAB_REMOTE }, { Icon(Icons.Filled.SmartDisplay, null) }, label = { Text("Pilot") })
                NavigationBarItem(tab.intValue == TAB_SETTINGS, { tab.intValue = TAB_SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Ustawienia") })
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
          update?.let { u ->
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (updating != null) "Pobieram aktualizację… $updating%" else "Jest aktualizacja ${u.name}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({
                        updating = 0
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Updater.downloadAndInstall(ctx, api) { updating = it } }
                            } catch (e: ApiException) {
                                snack.showSnackbar(e.message ?: "Błąd")
                            } finally {
                                updating = null
                            }
                        }
                    }, enabled = updating == null) { Text("Zaktualizuj") }
                }
            }
          }
          Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab.intValue) {
                TAB_TORRENTS -> TorrentsTab(api, online == true, snack) { tab.intValue = TAB_REMOTE }
                TAB_LIBRARY -> LibraryTab(api, online == true, snack) { tab.intValue = TAB_REMOTE }
                TAB_REMOTE -> RemoteTab(api, online == true, snack)
                else -> SettingsTab(api, ver, snack) { settingsVersion.intValue++; hostname = null }
            }
          }
        }
    }

    if (confirmSuspend) {
        AlertDialog(
            onDismissRequest = { confirmSuspend = false },
            title = { Text("Uśpić komputer?") },
            text = { Text("Pobieranie się zatrzyma. Obudzisz go z apki albo klikając magnet link.") },
            confirmButton = {
                TextButton({
                    confirmSuspend = false
                    scope.io(snack) { api.suspendPc(); delay(3000) }
                    online = false
                }) { Text("Uśpij") }
            },
            dismissButton = { TextButton({ confirmSuspend = false }) { Text("Anuluj") } },
        )
    }
}

// ---------------------------------------------------------------- Torrenty

@Composable
private fun TorrentsTab(api: Api, online: Boolean, snack: SnackbarHostState, onPlayingOnPc: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var torrents by remember { mutableStateOf<List<Torrent>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }

    LaunchedEffect(online, refresh, onlyMine) {
        while (online) {
            try {
                torrents = withContext(Dispatchers.IO) { api.torrents() }
                if (onlyMine) jobs = withContext(Dispatchers.IO) { api.jobs() }
                error = null
            } catch (e: ApiException) {
                error = e.message
            }
            delay(3000)
        }
    }

    val sel = torrents.firstOrNull { it.id == selected }
    if (selected != null) {
        BackHandler { selected = null }
        FilesScreen(api, selected!!, sel, snack, onBack = { selected = null }, onPlayingOnPc = onPlayingOnPc)
        return
    }

    Box(Modifier.fillMaxSize()) {
        when {
            !online -> Hint("Komputer nie odpowiada. Obudź go przyciskiem u góry albo po prostu kliknij magnet link — obudzi się sam.")
            error != null -> Hint(error!!)
            else -> LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!onlyMine, { onlyMine = false }, { Text("Wszystkie (${torrents.size})") })
                        FilterChip(onlyMine, { onlyMine = true }, { Text("Zlecone z telefonu") },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp)) })
                    }
                }
                val shown = if (onlyMine) torrents.filter { it.mine } else torrents
                if (shown.isEmpty()) item {
                    Text(if (onlyMine) "Nic z telefonu nie jest teraz w Deluge." else "Brak torrentów. Kliknij magnet link w przeglądarce albo wklej go przyciskiem niżej.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { t ->
                    TorrentCard(t, onClick = { selected = t.id }, onAfter = { after ->
                        scope.io(snack) { api.setAfter(t.id, after); refresh++ }
                    }) { action ->
                        scope.io(snack) { api.action(t.id, action); refresh++ }
                    }
                }
                if (onlyMine) {
                    val gone = jobs.filter { !it.inClient }
                    if (gone.isNotEmpty()) item {
                        Text("Wcześniej zlecone (już nie ma ich w Deluge)", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                    }
                    items(gone, key = { "job/" + it.id }) { j ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(j.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                        .format(java.util.Date((j.added * 1000).toLong())), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (j.magnet != null) TextButton({
                                    ctx.startActivity(Intent(ctx, MagnetActivity::class.java).setAction(Intent.ACTION_SEND)
                                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, j.magnet))
                                }) { Text("Pobierz znowu") }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                val m = Regex("""magnet:\?\S+""").find(text)?.value
                if (m == null) {
                    scope.launch { snack.showSnackbar("W schowku nie ma magnet linka") }
                } else {
                    ctx.startActivity(Intent(ctx, MagnetActivity::class.java).setAction(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, m))
                }
            },
            icon = { Icon(Icons.Filled.ContentPaste, null) },
            text = { Text("Wklej magnet") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stateLabel(s: String) = when (s) {
    "Downloading" -> "pobiera"
    "Seeding" -> "gotowe, udostępnia"
    "Paused" -> "wstrzymany"
    "Queued" -> "w kolejce"
    "Checking" -> "sprawdza"
    "Error" -> "błąd"
    "Moving" -> "przenosi"
    "Allocating" -> "rezerwuje miejsce"
    else -> s.lowercase()
}

@Composable
private fun TorrentCard(t: Torrent, onClick: () -> Unit, onAfter: (String) -> Unit, onAction: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var afterDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(t.name, Modifier.weight(1f).padding(top = 4.dp), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "akcje") }
                    DropdownMenu(menu, { menu = false }) {
                        if (t.state == "Paused") DropdownMenuItem({ Text("Wznów") }, { menu = false; onAction("resume") })
                        else DropdownMenuItem({ Text("Wstrzymaj") }, { menu = false; onAction("pause") })
                        DropdownMenuItem({ Text(if (t.sequential) "Wyłącz sekwencyjne" else "Pobieraj sekwencyjnie") }, {
                            menu = false; onAction(if (t.sequential) "sequential_off" else "sequential_on")
                        })
                        DropdownMenuItem({ Text("Po pobraniu…") }, { menu = false; afterDialog = true })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Usuń z listy") }, { menu = false; onAction("remove") })
                        DropdownMenuItem({ Text("Usuń razem z plikami") }, { menu = false; confirmRemove = true })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (t.progress / 100).toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 10.dp))
            Spacer(Modifier.height(6.dp))
            val parts = buildList {
                add(stateLabel(t.state))
                add("${t.progress}% z ${humanBytes(t.size)}")
                if (t.downRate > 0) add("↓ ${humanBytes(t.downRate)}/s")
                if (t.state == "Downloading" && t.eta > 0) add("zostało ${humanTime(t.eta)}")
                if (t.sequential) add("sekw.")
                if (t.mine) add("📱")
                if (t.after != "none") add("potem: " + (AFTER_LABELS[t.after] ?: t.after))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (afterDialog) AfterDialog(t.after, { afterDialog = false }) { afterDialog = false; onAfter(it) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Usunąć z plikami?") },
            text = { Text("„${t.name}” zniknie razem z pobranymi plikami z dysku komputera.") },
            confirmButton = { TextButton({ confirmRemove = false; onAction("remove_data") }) { Text("Usuń") } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun FilesScreen(api: Api, torrentId: String, t: Torrent?, snack: SnackbarHostState, onBack: () -> Unit, onPlayingOnPc: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<TFile>?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(torrentId) {
        while (true) {
            try {
                files = withContext(Dispatchers.IO) { api.files(torrentId) }
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: "Błąd")
            }
            delay(4000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "wstecz") }
            Text(t?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val list = files
        if (list == null) {
            Hint("Wczytuję pliki…")
            return
        }
        if (list.isEmpty()) {
            Hint("Torrent jeszcze nie pobrał listy plików (metadane z magneta). Chwilę…")
            return
        }
        val media = list.filter { it.media != null }.sortedBy { it.path.lowercase() }
        val shown = if (showAll || media.isEmpty()) list.sortedBy { it.path.lowercase() } else media
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Wszystkie pliki (${list.size})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(showAll, { showAll = it })
        }
        if (t != null && t.progress < 100 && !t.sequential) {
            Text("Wskazówka: do oglądania w trakcie pobierania włącz „Pobieraj sekwencyjnie” w menu torrenta.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary)
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.index }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (f.path.contains('/')) {
                            Text(f.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${humanBytes(f.size)} · pobrane ${f.progress}%", style = MaterialTheme.typography.bodySmall)
                        if (f.media != null) WatchableInfo(f)
                        if (f.media != null) {
                            Spacer(Modifier.height(8.dp))
                            MediaActions(api, MediaRef.InTorrent(torrentId, f.index, f.name), snack, onPlayingOnPc)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AfterDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gdy się pobierze") },
        text = {
            Column {
                AFTER_LABELS.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(k) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current == k, { onPick(k) }); Text(label)
                    }
                }
                Text("Uśpienie czeka, aż skończą się wszystkie pobierania i nic nie gra.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Anuluj") } },
    )
}

/** Ile da się obejrzeć: część pliku pobrana ciągiem od początku (pasek + minuty). */
@Composable
private fun WatchableInfo(f: TFile) {
    val frac = if (f.size > 0) (f.ready.toDouble() / f.size).coerceIn(0.0, 1.0) else 0.0
    val pct = (frac * 100).toInt()
    val color = when {
        frac >= 0.999 -> Color(0xFF3FA96B)
        frac > 0.02 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        frac >= 0.999 -> "do obejrzenia całość" + (f.duration?.let { " (${clock(it)})" } ?: "")
        f.ready <= 0 -> "jeszcze nie da się oglądać — początek się nie pobrał"
        f.readySec != null && f.duration != null -> "do obejrzenia: ok. ${clock(f.readySec)} z ${clock(f.duration)} ($pct%)"
        else -> "do obejrzenia: $pct% od początku"
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(progress = { frac.toFloat() }, modifier = Modifier.fillMaxWidth(), color = color)
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

// ---------------------------------------------------------------- Pilot

@Composable
private fun RemoteTab(api: Api, online: Boolean, snack: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var subsDialog by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(online) {
        while (online) {
            st = withContext(Dispatchers.IO) { runCatching { api.playerStatus() }.getOrNull() }
            delay(1000)
        }
    }
    fun cmd(c: String, v: Any? = null) = scope.io(snack) {
        api.playerCmd(c, v)
        st = runCatching { api.playerStatus() }.getOrNull()
    }

    val s = st
    if (!online) return Hint("Komputer nie odpowiada.")
    if (s == null || !s.optBoolean("running")) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Na komputerze nic nie gra.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Wybierz film w zakładce Torrenty → „Na komputerze”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedButton({ scope.io(snack) { api.screenOn() } }) { Icon(Icons.Filled.LightMode, null); Spacer(Modifier.width(6.dp)); Text("Włącz i odblokuj ekran") }
        }
        return
    }
    val pos = s.optDouble("pos", 0.0).takeIf { !it.isNaN() } ?: 0.0
    val dur = s.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0
    val paused = s.optBoolean("paused")
    val fs = s.optBoolean("fullscreen")
    val muted = s.optBoolean("muted")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Slider(
            value = if (dragging) dragPos else pos.toFloat(),
            onValueChange = { dragging = true; dragPos = it },
            onValueChangeFinished = { dragging = false; cmd("seek_to", dragPos.toDouble()) },
            valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(if (dragging) dragPos.toDouble() else pos), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(clock(dur), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteButton(Icons.Filled.FastRewind, "-60 s") { cmd("seek", -60) }
            RemoteButton(Icons.Filled.Replay10, "-10 s") { cmd("seek", -10) }
            FilledIconButton({ cmd("toggle_pause") }, Modifier.size(80.dp)) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "play/pauza", Modifier.size(44.dp))
            }
            RemoteButton(Icons.Filled.Forward10, "+10 s") { cmd("seek", 10) }
            RemoteButton(Icons.Filled.FastForward, "+60 s") { cmd("seek", 60) }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "ciszej") { cmd("volume_rel", -5) }
            RemoteButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "wycisz") { cmd("mute") }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "głośniej") { cmd("volume_rel", 5) }
        }
        Text("Głośność ${s.optDouble("volume", 0.0).toInt()}% (działają też przyciski głośności telefonu)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton({ cmd("fullscreen") }) {
                Icon(if (fs) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, null)
                Spacer(Modifier.width(6.dp)); Text(if (fs) "Wyjdź z pełnego" else "Pełny ekran")
            }
            FilledTonalButton({ cmd("stop") }) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Zakończ") }
        }
        Spacer(Modifier.height(10.dp))
        val tracks = s.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ trackDialog = "sub" }) { Icon(Icons.Filled.Subtitles, null); Spacer(Modifier.width(6.dp)); Text("Napisy") }
            OutlinedButton({ trackDialog = "audio" }) { Icon(Icons.Filled.Audiotrack, null); Spacer(Modifier.width(6.dp)); Text("Audio") }
            if (tracks.count { it.optString("type") == "video" } > 1)
                OutlinedButton({ trackDialog = "video" }) { Text("Wideo") }
        }
        trackDialog?.let { type ->
            TrackDialog(type, tracks.filter { it.optString("type") == type }, onDismiss = { trackDialog = null }) { id ->
                trackDialog = null
                cmd("track", JSONObject().put("type", type).put("id", id ?: "no"))
            }
        }
        val sub = s.optString("sub").takeIf { it.isNotEmpty() && it != "null" } ?: "brak"
        val audio = s.optString("audio").takeIf { it.isNotEmpty() && it != "null" } ?: "domyślna"
        Text("napisy: $sub · audio: $audio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ cmd("sub_delay", -0.1) }) { Text("napisy −0,1 s") }
            TextButton({ cmd("sub_delay", 0.1) }) { Text("napisy +0,1 s") }
        }
        OutlinedButton({ subsDialog = true }) { Icon(Icons.Filled.Subtitles, null); Spacer(Modifier.width(6.dp)); Text("Szukaj napisów") }
    }
    if (subsDialog) SubsDialog(api, MediaRef.Playing(s.optString("title"))) { subsDialog = false }
}

/** Wybór ścieżki w mpv: lista z językiem, tytułem, kodekiem; dla napisów też „wyłączone”. */
@Composable
private fun TrackDialog(type: String, tracks: List<JSONObject>, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val title = when (type) { "sub" -> "Napisy"; "audio" -> "Ścieżka dźwiękowa"; else -> "Ścieżka wideo" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (type == "sub") item {
                    TrackRow("Wyłączone", null, tracks.none { it.optBoolean("selected") }) { onPick(null) }
                }
                items(tracks, key = { it.optInt("id") }) { t ->
                    fun str(k: String) = t.optString(k).takeIf { it.isNotEmpty() && it != "null" }
                    val ttl = str("title")?.let { if (t.optBoolean("external")) it.substringAfterLast('/').takeLast(28) else it }
                    val name = listOfNotNull(str("lang")?.uppercase(), ttl).joinToString(" — ").ifEmpty { "ścieżka ${t.optInt("id")}" }
                    val info = listOfNotNull(
                        str("codec")?.let { CODECS[it] ?: it },
                        t.optInt("demux-channel-count").takeIf { it > 0 }?.let { "${it}ch" },
                        t.optInt("demux-h").takeIf { it > 0 }?.let { "${t.optInt("demux-w")}×$it" },
                        if (t.optBoolean("external")) "z pliku obok" else null,
                        if (t.optBoolean("forced")) "wymuszone" else null,
                        if (t.optBoolean("default")) "domyślna" else null,
                    ).joinToString(" · ")
                    TrackRow(name, info, t.optBoolean("selected")) { onPick(t.optInt("id")) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Zamknij") } },
    )
}

private val CODECS = mapOf(
    "hdmv_pgs_subtitle" to "PGS (obrazkowe)", "dvd_subtitle" to "VobSub (obrazkowe)", "subrip" to "SRT", "ass" to "ASS",
    "mov_text" to "tekst", "webvtt" to "WebVTT", "eac3" to "E-AC3", "ac3" to "AC3", "aac" to "AAC", "dts" to "DTS",
    "truehd" to "TrueHD", "opus" to "Opus", "flac" to "FLAC", "hevc" to "HEVC", "h264" to "H.264", "av1" to "AV1",
)

@Composable
private fun TrackRow(name: String, info: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected, onClick)
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (!info.isNullOrEmpty()) Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, Modifier.size(56.dp)) { Icon(icon, desc) }
}

// ---------------------------------------------------------------- Ustawienia

@Composable
private fun SettingsTab(api: Api, ver: Int, snack: SnackbarHostState, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = api.s
    var host by remember(ver) { mutableStateOf(s.host) }
    var port by remember(ver) { mutableStateOf(s.port.toString()) }
    var token by remember(ver) { mutableStateOf(s.token) }
    var mac by remember(ver) { mutableStateOf(s.mac) }
    var seq by remember(ver) { mutableStateOf(s.sequential) }
    var after by remember(ver) { mutableStateOf(s.afterDefault) }
    var afterDialog by remember { mutableStateOf(false) }
    var pairDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun save() {
        s.host = host; s.port = port.toIntOrNull() ?: 8765; s.token = token; s.mac = mac; s.sequential = seq
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Komputer", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Adres IP") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token (z install.sh)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(mac, { mac = it }, Modifier.fillMaxWidth(), label = { Text("MAC do budzenia (uzupełni się sam)") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pobieranie sekwencyjne")
                Text("Nowe torrenty pobierają się po kolei — można oglądać w trakcie", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(seq, { seq = it; s.sequential = it })
        }
        Row(Modifier.fillMaxWidth().clickable { afterDialog = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Domyślnie po pobraniu")
                Text(AFTER_LABELS[after] ?: after, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                save(); onSaved()
                result = "Łączę…"
                scope.launch {
                    result = try {
                        val i = withContext(Dispatchers.IO) { api.info() }
                        mac = s.mac
                        buildString {
                            append("OK: ${i.optString("hostname")}, ${i.optString("backend")}\n")
                            append("karta ${i.optString("iface")}, MAC ${i.optString("mac")}\n")
                            val wol = i.optString("wake_on")
                            if (i.optBoolean("wireless")) append("⚠ komputer jest na Wi-Fi — budzenie zwykle nie zadziała\n")
                            else if (wol.isNotEmpty() && wol != "null" && !wol.contains('g')) append("⚠ Wake-on-LAN wyłączony ($wol) — uruchom install.sh\n")
                            else append("Wake-on-LAN: ${if (wol.isEmpty() || wol == "null") "nieznany" else wol}\n")
                        }.trim()
                    } catch (e: ApiException) {
                        "Błąd: ${e.message}"
                    }
                }
            }) { Text("Zapisz i testuj") }
            OutlinedButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                val link = Regex("""magneticplayer://pair\S+""").find(text)?.value
                if (link != null && s.applyPairUri(android.net.Uri.parse(link))) {
                    onSaved(); scope.launch { snack.showSnackbar("Sparowano z ${s.host}") }
                } else scope.launch { snack.showSnackbar("W schowku nie ma linka parowania") }
            }) { Text("Wklej link parowania") }
        }
        OutlinedButton({ pairDialog = true }, enabled = s.configured) {
            Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("Sparuj kolejne urządzenie")
        }
        OutlinedButton({
            scope.launch {
                try { withContext(Dispatchers.IO) { Updater.shareApk(ctx) } }
                catch (e: Exception) { snack.showSnackbar("Nie udało się: ${e.message}") }
            }
        }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Wyślij apkę (WhatsApp itp.)") }
        OutlinedButton({
            copyText(ctx, "MagneticPlayer — wersja webowa", s.webUri())
            scope.launch { snack.showSnackbar("Skopiowano link do wersji webowej — otwórz go na laptopie/tablecie w domowej sieci") }
        }, enabled = s.configured) { Icon(Icons.Filled.Language, null); Spacer(Modifier.width(6.dp)); Text("Kopiuj link do wersji webowej") }
        Text("Wersja webowa: torrenty, biblioteka, pilot i napisy w przeglądarce, na laptopie albo tablecie bez apki.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider()
        OpenSubsSettings(api, snack)
        HorizontalDivider()
        Text("Magnet linki: przy pierwszym kliknięciu magneta Android zapyta, czym otworzyć — wybierz MagneticPlayer → „Zawsze”. " +
            "Działa też „Udostępnij” z dowolnej apki.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (afterDialog) AfterDialog(after, { afterDialog = false }) { after = it; s.afterDefault = it; afterDialog = false }
    if (pairDialog) PairDialog(s.pairUri(), s.webUri()) { pairDialog = false }
}

/** QR z linkiem parowania: drugi telefon skanuje aparatem (albo dostaje link przez „Udostępnij”). */
@Composable
private fun PairDialog(link: String, webLink: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val qr = remember(link) { qrBitmap(link, 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sparuj kolejne urządzenie") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(qr.asImageBitmap(), "kod QR parowania",
                    Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                Spacer(Modifier.height(12.dp))
                Text("Na drugim telefonie zainstaluj MagneticPlayer, zeskanuj kod aparatem i otwórz link. " +
                    "Jeśli aparat nie da otworzyć linku: Udostępnij → skopiuj tam → w apce Ustawienia → „Wklej link parowania”. " +
                    "Kod zawiera token do komputera — pokazuj go tylko domownikom.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Laptop albo tablet bez Androida: otwórz w przeglądarce", style = MaterialTheme.typography.labelMedium)
                Text(webLink, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        copyText(ctx, "MagneticPlayer — wersja webowa", webLink)
                        android.widget.Toast.makeText(ctx, "Skopiowano", android.widget.Toast.LENGTH_SHORT).show()
                    })
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Zamknij") } },
        dismissButton = {
            TextButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link), "Link parowania MagneticPlayer"))
            }) { Text("Udostępnij link") }
        },
    )
}

fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}

/** Konto OpenSubtitles trzyma agent na komputerze (to on szuka i zapisuje napisy obok filmów). */
@Composable
private fun OpenSubsSettings(api: Api, snack: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var langs by remember { mutableStateOf(api.s.subLanguages) }
    var key by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        state = runCatching {
            val o = withContext(Dispatchers.IO) { api.openSubsConfig() }
            user = o.optString("username")
            when {
                !o.optBoolean("api_key") -> "wyłączone (brak klucza)"
                !o.optBoolean("password") -> "włączone (bez konta, pobrania anonimowe)"
                else -> "skonfigurowane (${o.optString("username")})"
            }
        }.getOrNull()
    }
    Text("Napisy", style = MaterialTheme.typography.titleMedium)
    Text("Szuka komputer (subliminal): BSPlayer, Podnapisi, Addic7ed, TVsubtitles, NapiProjekt — bez kont. " +
        "Opcjonalnie OpenSubtitles (największa baza angielskich): konto na opensubtitles.com (nie .org), " +
        "menu konta → API consumers → New consumer. Login i hasło opcjonalne (większy limit pobrań). " + (state?.let { "OpenSubtitles: $it." } ?: ""),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(langs, { langs = it }, Modifier.fillMaxWidth(), label = { Text("Języki napisów, w kolejności (np. en,pl)") }, singleLine = true)
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("OpenSubtitles: klucz API (puste = bez zmian)") }, singleLine = true)
    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("OpenSubtitles: login (opcjonalnie)") }, singleLine = true)
    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("OpenSubtitles: hasło (opcjonalnie)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            api.s.subLanguages = langs
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.setOpenSubsConfig(key.ifBlank { null }, user, pass) }
                    key = ""; pass = ""
                    state = "zapisane"
                    snack.showSnackbar("Zapisano na komputerze")
                } catch (e: ApiException) {
                    snack.showSnackbar(e.message ?: "Błąd")
                }
            }
        }) { Text("Zapisz napisy") }
        OutlinedButton({
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.opensubtitles.com/en/consumers")))
        }) { Text("Strona kluczy") }
    }
}

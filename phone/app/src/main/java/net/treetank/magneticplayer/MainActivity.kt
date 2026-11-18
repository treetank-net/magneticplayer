package net.treetank.magneticplayer

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
import androidx.compose.material.icons.filled.DownloadForOffline
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Tv
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
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
const val TAB_OFFLINE = 4

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private val settingsVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Servers.all(this).isEmpty()) tab.intValue = TAB_SETTINGS
        // bufor na dysku: obejrzane i stare filmy, także po ubiciu apki w trakcie oglądania
        Thread { MediaCache.sweep(this) }.start()
        // telewizor DLNA, który grał przed ubiciem apki: Pilot i powiadomienie wracają
        lifecycleScope.launch(Dispatchers.IO) { if (Dlna.restore(this@MainActivity)) RemoteSessionService.start(applicationContext, "dlna") }
        Uploads.onAppStart(this)  // przesyłanie pobranych filmów: wyzwalacz w tle i obserwator (Uploads.kt)
        handleIntent(intent)
        setContent { AppTheme { MainScreen(tab, settingsVersion) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** magneticplayer://pair… (QR z komputera albo z innego telefonu): parowanie z jednym lub kilkoma serwerami. */
    private fun handleIntent(i: Intent) {
        if (i.hasExtra("tab")) tab.intValue = i.getIntExtra("tab", 0)
        val data = i.data ?: return
        if (data.scheme != "magneticplayer") return
        val targets = Pairing.parse(data)
        if (targets.isEmpty()) return
        Toast.makeText(this, getString(R.string.main_pairing_progress), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val ok = mutableListOf<String>()
                val bad = mutableListOf<String>()
                targets.forEach { t ->
                    try {
                        ok += Pairing.pair(this@MainActivity, t).name
                    } catch (e: Exception) {
                        bad += "${t.host}: ${e.message}"
                    }
                }
                listOfNotNull(ok.takeIf { it.isNotEmpty() }?.let { getString(R.string.main_paired, it.joinToString()) },
                    bad.takeIf { it.isNotEmpty() }?.let { getString(R.string.main_failed, it.joinToString("; ")) }).joinToString("\n")
            }
            settingsVersion.intValue++
            if (Servers.all(this@MainActivity).isNotEmpty()) tab.intValue = TAB_TORRENTS
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        appResumes.intValue++
    }

    /** Na zakładce Pilot przyciski głośności telefonu sterują głośnością mpv na wybranym ekranie. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vol = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        // głośność celu Pilota: Chromecast i telewizor na każdej zakładce, ekran z mpv tylko na Pilocie
        val target = if (vol) Pilot.effective(this) else null
        if (target != null && (target != "screen" || tab.intValue == TAB_REMOTE)) {
            val r = Pilot.remote(this, target) ?: return super.onKeyDown(keyCode, event)
            val d = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            lifecycleScope.launch { runCatching { r.volumeBy(d) } }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Wywołanie API w tle; błąd trafia do snackbara. */
private fun CoroutineScope.io(ctx: Context, snack: SnackbarHostState, block: suspend () -> Unit) = launch {
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: ApiException) {
        snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
    }
}

/** licznik powrotów apki na ekran (onResume), do sprawdzania aktualizacji */
private val appResumes = androidx.compose.runtime.mutableIntStateOf(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(tab: MutableIntState, settingsVersion: MutableIntState) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val ver = settingsVersion.intValue
    fun changed() { settingsVersion.intValue++ }
    // serwer plików (Torrenty, Biblioteka, Uśpij) i ekran Pilota
    val servers = remember(ver) { Servers.all(ctx) }
    val api = remember(ver) { Api.active(ctx) }
    var screenKey by remember(ver) { mutableStateOf(Servers.screenKey(ctx)) }
    val screenApi = remember(ver, screenKey) { Api.of(ctx, screenKey) }
    var online by remember(ver) { mutableStateOf<Boolean?>(null) }
    var wrong by remember(ver) { mutableStateOf(false) }
    var infoVer by remember { mutableIntStateOf(0) }
    var waking by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmSuspend by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var addServer by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updating by remember { mutableStateOf<Int?>(null) }
    // aktualizacja: przy otwarciu apki i powrocie na ekran (po połączeniu z serwerem) oraz po zmianie serwera; jedno
    // zapytanie, bez pamięci podręcznej. W trakcie (np. na Pilocie przy oglądaniu) nie sprawdza.
    LaunchedEffect(online == true, ver, appResumes.intValue) {
        if (online == true) update = withContext(Dispatchers.IO) { Updater.check(ctx, api) }
    }

    LaunchedEffect(ver) {
        var first = true
        var pings = 0
        var fails = 0
        while (true) {
            val ok = withContext(Dispatchers.IO) { api.ping() }
            // „śpi” dopiero po dwóch nieudanych próbach z rzędu (pojedyncza może przepaść w Wi-Fi)
            fails = if (ok) 0 else fails + 1
            if (ok || fails >= 2 || online == null) online = ok
            wrong = api.wrongServer
            // możliwości serwera (przyciski, budzenie) odświeżane raz po połączeniu
            if (ok && first) {
                first = false
                withContext(Dispatchers.IO) { runCatching { api.info() } }
                infoVer++
            }
            // prośby o dostęp z sieci domowej (Access.kt): co ~15 s, póki apka jest na ekranie i serwer odpowiada
            if (ok && pings++ % 3 == 0) withContext(Dispatchers.IO) { Access.check(ctx, api) }
            delay(if (ok) 5000 else 3000)
        }
    }
    val srv = remember(ver, online, infoVer) { Servers.get(ctx, api.srv.key) ?: api.srv }

    fun wake(key: String = api.srv.key) = scope.launch {
        val a = Api.of(ctx, key) ?: return@launch
        try {
            a.ensureAwake { waking = it }
            if (key == api.srv.key) online = true
            withContext(Dispatchers.IO) { runCatching { a.info() } }
            infoVer++
            snack.showSnackbar(ctx.getString(R.string.main_server_online, a.srv.name))
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
        } finally {
            waking = null
        }
    }
    val onPlaying = { screenKey = Servers.screenKey(ctx); tab.intValue = TAB_REMOTE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(if (servers.size > 1) Modifier.clickable { sheet = true } else Modifier) {
                        Text(if (servers.isEmpty()) "MagneticPlayer" else srv.name + if (servers.size > 1) " ▾" else "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (txt, col) = when {
                            servers.isEmpty() -> stringResource(R.string.main_add_server_in_settings) to MaterialTheme.colorScheme.onSurfaceVariant
                            waking != null -> waking!! to MaterialTheme.colorScheme.tertiary
                            wrong -> stringResource(R.string.main_status_wrong_server) to MaterialTheme.colorScheme.error
                            online == true -> stringResource(R.string.main_status_online, srv.rolesText(LocalContext.current)) to OK_GREEN
                            online == false -> stringResource(R.string.main_status_asleep) to MaterialTheme.colorScheme.error
                            else -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(txt, style = MaterialTheme.typography.labelMedium, color = col)
                    }
                },
                actions = {
                    if (online == false && !wrong && waking == null && srv.mac.isNotBlank()) {
                        TextButton({ wake() }) { Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.main_wake)) }
                    }
                    if (online == true && waking == null && srv.canSuspend) {
                        TextButton({ confirmSuspend = true }) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.main_sleep)) }
                    }
                    if (servers.isNotEmpty()) Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "menu") }
                        DropdownMenu(menu, { menu = false }) {
                            if (srv.hasPlayer && srv.canUnlock) DropdownMenuItem({ Text(stringResource(R.string.main_screen_on_unlock)) }, {
                                menu = false; scope.io(ctx, snack) { api.screenOn() }
                            }, leadingIcon = { Icon(Icons.Filled.LightMode, null) })
                            if (servers.size > 1) DropdownMenuItem({ Text(stringResource(R.string.main_switch_server)) }, { menu = false; sheet = true })
                            DropdownMenuItem({ Text(stringResource(R.string.main_servers_pairing)) }, { menu = false; tab.intValue = TAB_SETTINGS })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab.intValue == TAB_TORRENTS, { tab.intValue = TAB_TORRENTS }, { Icon(Icons.Filled.Download, null) }, label = { Text(stringResource(R.string.main_tab_torrents)) })
                NavigationBarItem(tab.intValue == TAB_LIBRARY, { tab.intValue = TAB_LIBRARY }, { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text(stringResource(R.string.main_tab_library)) })
                NavigationBarItem(tab.intValue == TAB_OFFLINE, { tab.intValue = TAB_OFFLINE }, { Icon(Icons.Filled.DownloadForOffline, null) }, label = { Text(stringResource(R.string.main_tab_offline), maxLines = 1) })
                NavigationBarItem(tab.intValue == TAB_REMOTE, { tab.intValue = TAB_REMOTE }, { Icon(Icons.Filled.SmartDisplay, null) }, label = { Text(stringResource(R.string.main_tab_remote)) })
                NavigationBarItem(tab.intValue == TAB_SETTINGS, { tab.intValue = TAB_SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.main_tab_settings)) })
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
          update?.let { u ->
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (updating != null) stringResource(R.string.main_update_downloading, updating ?: 0) else stringResource(R.string.main_update_available, u.name),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({
                        updating = 0
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Updater.downloadAndInstall(ctx, api) { updating = it } }
                            } catch (e: ApiException) {
                                snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
                            } finally {
                                updating = null
                            }
                        }
                    }, enabled = updating == null) { Text(stringResource(R.string.main_update_install)) }
                }
            }
          }
          Box(Modifier.weight(1f).fillMaxWidth()) {
            val t = tab.intValue
            when {
                (t == TAB_TORRENTS || t == TAB_LIBRARY) && servers.isEmpty() ->
                    Hint(stringResource(R.string.main_hint_no_server))
                (t == TAB_TORRENTS || t == TAB_LIBRARY) && (online == false || waking != null) ->
                    AsleepPane(srv, waking, wrong) { wake() }
                t == TAB_TORRENTS && !srv.hasTorrent ->
                    Hint(stringResource(R.string.main_hint_no_torrent, srv.name))
                t == TAB_LIBRARY && !srv.hasLibrary -> Hint(stringResource(R.string.main_hint_no_library, srv.name))
                else -> key(api.srv.key) {
                    when (t) {
                        TAB_TORRENTS -> TorrentsTab(api, online == true, snack, onPlaying)
                        TAB_LIBRARY -> LibraryTab(api, online == true, snack, onPlaying)
                        TAB_REMOTE -> PilotTab(screenApi, snack, onPickScreen = { Servers.setScreen(ctx, it); screenKey = it }) { wake(it) }
                        TAB_OFFLINE -> OfflineTab(onPlaying)
                        else -> SettingsTab(api, ver, snack, addServer) { addServer = false; changed() }
                    }
                }
            }
          }
        }
    }

    if (sheet) ServerSheet(api.srv.key,
        onPick = { sheet = false; Servers.setActive(ctx, it); changed() },
        onWake = { sheet = false; Servers.setActive(ctx, it); changed(); wake(it) },
        onAdd = { sheet = false; addServer = true; tab.intValue = TAB_SETTINGS },
        onManage = { sheet = false; tab.intValue = TAB_SETTINGS },
    ) { sheet = false }

    if (confirmSuspend) SuspendDialog(srv, onConfirm = {
        confirmSuspend = false
        scope.io(ctx, snack) { api.suspendPc(); delay(3000) }
        online = false
    }) { confirmSuspend = false }
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
            !online -> Hint(stringResource(R.string.main_connecting))
            error != null -> Hint(error!!)
            else -> LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!onlyMine, { onlyMine = false }, { Text(stringResource(R.string.main_filter_all, torrents.size)) })
                        FilterChip(onlyMine, { onlyMine = true }, { Text(stringResource(R.string.main_filter_mine)) },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp)) })
                    }
                }
                val shown = if (onlyMine) torrents.filter { it.mine } else torrents
                if (shown.isEmpty()) item {
                    Text(if (onlyMine) stringResource(R.string.main_empty_mine) else stringResource(R.string.main_empty_torrents),
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { t ->
                    TorrentCard(t, onClick = { selected = t.id }, onAfter = { after ->
                        scope.io(ctx, snack) { api.setAfter(t.id, after); refresh++ }
                    }) { action ->
                        scope.io(ctx, snack) { api.action(t.id, action); refresh++ }
                    }
                }
                if (onlyMine) {
                    val gone = jobs.filter { !it.inClient }
                    if (gone.isNotEmpty()) item {
                        Text(stringResource(R.string.main_earlier_requests), style = MaterialTheme.typography.titleSmall,
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
                                }) { Text(stringResource(R.string.main_download_again)) }
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
                    scope.launch { snack.showSnackbar(ctx.getString(R.string.main_no_magnet_in_clipboard)) }
                } else {
                    ctx.startActivity(Intent(ctx, MagnetActivity::class.java).setAction(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, m))
                }
            },
            icon = { Icon(Icons.Filled.ContentPaste, null) },
            text = { Text(stringResource(R.string.main_paste_magnet)) },
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

@Composable
private fun stateLabel(s: String) = when (s) {
    "Downloading" -> stringResource(R.string.main_state_downloading)
    "Seeding" -> stringResource(R.string.main_state_seeding)
    "Paused" -> stringResource(R.string.main_state_paused)
    "Queued" -> stringResource(R.string.main_state_queued)
    "Checking" -> stringResource(R.string.main_state_checking)
    "Error" -> stringResource(R.string.main_state_error)
    "Moving" -> stringResource(R.string.main_state_moving)
    "Allocating" -> stringResource(R.string.main_state_allocating)
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
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.main_actions)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (t.state == "Paused") DropdownMenuItem({ Text(stringResource(R.string.main_resume)) }, { menu = false; onAction("resume") })
                        else DropdownMenuItem({ Text(stringResource(R.string.main_pause)) }, { menu = false; onAction("pause") })
                        DropdownMenuItem({ Text(if (t.sequential) stringResource(R.string.main_sequential_off) else stringResource(R.string.main_sequential_on)) }, {
                            menu = false; onAction(if (t.sequential) "sequential_off" else "sequential_on")
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.main_after_download_menu)) }, { menu = false; afterDialog = true })
                        HorizontalDivider()
                        DropdownMenuItem({ Text(stringResource(R.string.main_remove_from_list)) }, { menu = false; onAction("remove") })
                        DropdownMenuItem({ Text(stringResource(R.string.main_remove_with_files)) }, { menu = false; confirmRemove = true })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (t.progress / 100).toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 10.dp))
            Spacer(Modifier.height(6.dp))
            val parts = buildList {
                add(stateLabel(t.state))
                add(stringResource(R.string.main_progress_of, "${t.progress}", humanBytes(t.size)))
                if (t.downRate > 0) add("↓ ${humanBytes(t.downRate)}/s")
                if (t.state == "Downloading" && t.eta > 0) add(stringResource(R.string.main_time_left, humanTime(t.eta)))
                if (t.sequential) add(stringResource(R.string.main_seq_short))
                if (t.mine) add("📱")
                if (t.after != "none") add(stringResource(R.string.main_then, afterLabels(LocalContext.current)[t.after] ?: t.after))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (afterDialog) AfterDialog(t.after, { afterDialog = false }) { afterDialog = false; onAfter(it) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.main_remove_files_title)) },
            text = { Text(stringResource(R.string.main_remove_files_text, t.name)) },
            confirmButton = { TextButton({ confirmRemove = false; onAction("remove_data") }) { Text(stringResource(R.string.main_remove)) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.main_cancel)) } },
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
                snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
            }
            delay(4000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) }
            Text(t?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val list = files
        if (list == null) {
            Hint(stringResource(R.string.main_loading_files))
            return
        }
        if (list.isEmpty()) {
            Hint(stringResource(R.string.main_no_file_list))
            return
        }
        val media = list.filter { it.media != null }.sortedBy { it.path.lowercase() }
        val shown = if (showAll || media.isEmpty()) list.sortedBy { it.path.lowercase() } else media
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.main_all_files, list.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(showAll, { showAll = it })
        }
        if (t != null && t.progress < 100 && !t.sequential) {
            Text(stringResource(R.string.main_tip_sequential),
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
                        Text(stringResource(R.string.main_file_downloaded, humanBytes(f.size), "${f.progress}"), style = MaterialTheme.typography.bodySmall)
                        if (f.media != null) WatchableInfo(f)
                        if (f.media != null) {
                            Spacer(Modifier.height(8.dp))
                            MediaActions(api, MediaRef.InTorrent(torrentId, f.index, f.name, api.srv.key), snack, onPlayingOnPc)
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
        title = { Text(stringResource(R.string.main_after_title)) },
        text = {
            Column {
                afterLabels(LocalContext.current).forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(k) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current == k, { onPick(k) }); Text(label)
                    }
                }
                Text(stringResource(R.string.main_after_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
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
        frac >= 0.999 -> f.duration?.let { stringResource(R.string.main_watch_all_dur, clock(it)) } ?: stringResource(R.string.main_watch_all)
        f.ready <= 0 -> stringResource(R.string.main_watch_none)
        f.readySec != null && f.duration != null -> stringResource(R.string.main_watch_part_time, clock(f.readySec), clock(f.duration), pct)
        else -> stringResource(R.string.main_watch_part_pct, pct)
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(progress = { frac.toFloat() }, modifier = Modifier.fillMaxWidth(), color = color)
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

// ---------------------------------------------------------------- Pilot

/**
 * Pilot: na górze cele (ekrany z mpv, Chromecast i telewizor DLNA, gdy coś na nie wysłaliśmy), pod spodem ten sam
 * układ przycisków dla każdego. Czego cel nie umie (ścieżki w DLNA, głośność Chromecasta ze stałą głośnością), tego nie ma.
 */
@Composable
private fun PilotTab(screenApi: Api?, snack: SnackbarHostState, onPickScreen: (String) -> Unit, onWake: (String) -> Unit) {
    val ctx = LocalContext.current
    var avail by remember { mutableStateOf(Pilot.available(ctx)) }
    // Chromecasty w sieci szukane z telefonu (działa też bez agenta, np. w gościach)
    DisposableEffect(Unit) {
        val stop = CastNet.discover(ctx)
        val stopListen = Announce.listen(ctx)  // co grają inne piloty (telewizory DLNA)
        onDispose { stop(); stopListen() }
    }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (n++ % 3 == 0 && Cast.session(ctx) == null) {
                CastNet.refresh()  // co gra na Chromecastach (dowolny nadawca)
                if (CastNet.playing().isEmpty()) Pilot.refreshAgentCast(ctx)  // zapas: agent
            }
            avail = Pilot.available(ctx); delay(1000)
        }
    }
    val focus = Pilot.focus.value
    val target = focus?.takeIf { it in avail } ?: avail.firstOrNull()
    val screens = remember { Servers.screens(ctx) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screenApi != null) Box {
                FilterChip(target == "screen", {
                    if (target == "screen" && screens.size > 1) menu = true else Pilot.focus.value = "screen"
                }, { Text(screenApi.srv.name + if (screens.size > 1) " ▾" else "") },
                    leadingIcon = { Icon(Icons.Filled.Computer, null, Modifier.size(18.dp)) })
                DropdownMenu(menu, { menu = false }) {
                    screens.forEach { s -> DropdownMenuItem({ Text(s.name) }, { menu = false; Pilot.focus.value = "screen"; onPickScreen(s.key) }) }
                }
            }
            if ("cast" in avail) FilterChip(target == "cast", { Pilot.focus.value = "cast" },
                { Text(Pilot.castName(ctx)) }, leadingIcon = { Icon(Icons.Filled.Cast, null, Modifier.size(18.dp)) })
            if ("dlna" in avail) FilterChip(target == "dlna", { Pilot.focus.value = "dlna" },
                { Text(Dlna.current?.name ?: Announce.foreignDlna()?.targetName ?: stringResource(R.string.main_tv)) }, leadingIcon = { Icon(Icons.Filled.Tv, null, Modifier.size(18.dp)) })
        }
        OpenCastApps(snack)
        when (target) {
            "cast", "dlna" -> key(target, target == "cast" && Cast.session(ctx) == null) {
                val remote = remember { Pilot.remote(ctx, target) }
                if (remote is AgentCastRemote || remote is DirectCastRemote || remote is DlnaRemote) LaunchedEffect(Unit) {
                    Pilot.focus.value = target  // powiadomienie ma pokazać ten cel (też przejęty od innego pilota)
                    RemoteSessionService.start(ctx.applicationContext, target)
                }
                if (remote != null) RemoteView(remote, snack, onGone = {
                    if (target == "cast") Pilot.agentCast.value = null
                    if (Pilot.focus.value == target) Pilot.focus.value = null
                })
            }
            "screen" -> if (screenApi != null) key(screenApi.srv.key) { ScreenRemote(screenApi, snack, onWake) }
            else -> Hint(stringResource(R.string.main_no_screen))
        }
    }
}

/** „Zamknąć Spotify na Duży pokój?”: zamknięcie cudzej aplikacji na Chromecaście (ktoś może z niej korzystać). */
@Composable
private fun CloseAppDialog(app: String, device: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_close_app_title, app, device)) },
        text = { Text(stringResource(R.string.main_close_app_text)) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.main_close)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } })
}

/**
 * Chromecasty z otwartą aplikacją, która nic nie gra (Spotify zostawione po słuchaniu dalej pokazuje okładkę na
 * telewizorze): wiersz z „Zamknij”. Stan z [CastNet.refresh] w pętli Pilota.
 */
@Composable
private fun OpenCastApps(snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<CastNet.Status?>(null) }
    fun close(st: CastNet.Status) = scope.io(ctx, snack) { CastNet.stopApp(st.device.id) }
    CastNet.openIdle().forEach { st ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Cast, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.main_open_app, st.device.name, st.app ?: ""), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ if (Settings(ctx).confirmForeignStop) ask = st else close(st) }) { Text(stringResource(R.string.main_close)) }
        }
    }
    ask?.let { st -> CloseAppDialog(st.app ?: "", st.device.name, { ask = null }) { ask = null; close(st) } }
}

/** mpv na ekranie serwera: budzenie, „nic nie gra”, a gdy gra, wspólny pilot plus ścieżki, napisy i pełny ekran. */
@Composable
private fun ScreenRemote(api: Api, snack: SnackbarHostState, onWake: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = remember(api) { MpvRemote(ctx.applicationContext, api) }
    var reachable by remember(api) { mutableStateOf<Boolean?>(null) }
    var running by remember(api) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(api) {
        while (true) {
            val r = runCatching { remote.status() }
            running = r.getOrNull() != null
            reachable = r.isSuccess || withContext(Dispatchers.IO) { api.ping() }
            delay(if (running == true) 5000 else 1500)  // gdy gra, stan czyta RemoteView
        }
    }
    when {
        reachable == false -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.main_screen_not_responding, api.srv.name), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (api.srv.mac.isNotBlank()) Button({ onWake(api.srv.key) }) {
                Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_wake))
            }
        }
        running == false -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.main_nothing_on_screen, api.srv.name), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.main_pick_video_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (api.srv.canUnlock) OutlinedButton({ scope.io(ctx, snack) { api.screenOn() } }) {
                Icon(Icons.Filled.LightMode, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_screen_on_unlock))
            }
        }
        running == true -> {
            LaunchedEffect(Unit) { RemoteSessionService.start(ctx.applicationContext, "screen") }  // powiadomienie i głośność poza apką
            RemoteView(remote, snack, onGone = { running = false }) { s -> MpvExtras(api, remote, s, snack) }
        }
    }
}

/** Wspólny pilot: tytuł, suwak, przewijanie, pauza, głośność, Zakończ; `extras` = to, co umie tylko dany cel. */
@Composable
private fun RemoteView(remote: Remote, snack: SnackbarHostState, onGone: () -> Unit,
                       extras: @Composable ColumnScope.(RemoteState) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember(remote) { mutableStateOf<RemoteState?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(remote) {
        var misses = 0
        while (true) {
            val s = runCatching { remote.status() }.getOrNull()
            if (s != null) { st = s; misses = 0 } else if (++misses >= 3) { st = null; onGone() }
            repeat(4) { delay(250); tick++ }  // pozycja płynie między odczytami
        }
    }
    fun act(f: suspend Remote.() -> Unit) = scope.launch {
        try {
            remote.f()
            runCatching { remote.status() }.getOrNull()?.let { st = it }
        } catch (e: Exception) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.main_not_responding, remote.name))
        }
    }
    val s = st ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    @Suppress("UNUSED_EXPRESSION") tick
    // jak w odtwarzaczach: ekran z pilotem nie gaśnie, gdy coś gra (ustawienie)
    val view = androidx.compose.ui.platform.LocalView.current
    val keep = remember { Settings(view.context).pilotKeepScreen } && s.playing
    DisposableEffect(keep) {
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }
    val pos = s.posNow()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val note = listOfNotNull(s.note, if (s.buffering && s.note == null) stringResource(R.string.main_loading) else null).joinToString(" · ")
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Slider(
            value = drag ?: pos.toFloat().coerceIn(0f, s.dur.toFloat().coerceAtLeast(1f)),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { d -> act { seekTo(d.toDouble()) } }; drag = null },
            valueRange = 0f..(if (s.dur > 0) s.dur.toFloat() else 1f),
            enabled = s.dur > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(drag?.toDouble() ?: pos), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(clock(s.dur), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteButton(Icons.Filled.FastRewind, "-60 s") { act { seekBy(-60.0) } }
            RemoteButton(Icons.Filled.Replay10, "-10 s") { act { seekBy(-10.0) } }
            FilledIconButton({ act { setPlaying(!s.playing) } }, Modifier.size(80.dp)) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) stringResource(R.string.main_cd_pause) else stringResource(R.string.main_cd_play), Modifier.size(44.dp))
            }
            RemoteButton(Icons.Filled.Forward10, "+10 s") { act { seekBy(10.0) } }
            RemoteButton(Icons.Filled.FastForward, "+60 s") { act { seekBy(60.0) } }
        }
        Spacer(Modifier.height(24.dp))
        if (s.volume != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.main_cd_volume_down)) { act { volumeBy(-1) } }
                RemoteButton(if (s.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    if (s.muted) stringResource(R.string.main_cd_unmute) else stringResource(R.string.main_cd_mute)) { act { setMuted(!s.muted) } }
                RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.main_cd_volume_up)) { act { volumeBy(1) } }
            }
            Text(if (s.muted) stringResource(R.string.main_volume_muted, s.volume) else stringResource(R.string.main_volume_level, s.volume),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.main_volume_fixed, remote.name),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        extras(s)
        Spacer(Modifier.height(10.dp))
        var ask by remember { mutableStateOf(false) }
        fun doStop() { act { stop() }; if (remote.kind != "screen") onGone() }
        FilledTonalButton({ if (s.app != null && Settings(ctx).confirmForeignStop) ask = true else doStop() }) {
            Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_stop))
        }
        if (ask) CloseAppDialog(s.app ?: "", remote.name, { ask = false }) { ask = false; doStop() }
    }
}

/** Tylko mpv: pełny ekran, ścieżki, opóźnienie i wyszukiwanie napisów. */
@Composable
private fun ColumnScope.MpvExtras(api: Api, remote: MpvRemote, st: RemoteState, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var subsDialog by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf<String?>(null) }
    val s = st.mpv ?: return
    fun cmd(c: String, v: Any? = null) = scope.io(ctx, snack) { remote.raw(c, v) }
    val fs = s.optBoolean("fullscreen")
    FilledTonalButton({ cmd("fullscreen") }) {
        Icon(if (fs) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, null)
        Spacer(Modifier.width(6.dp)); Text(if (fs) stringResource(R.string.main_exit_fullscreen) else stringResource(R.string.main_fullscreen))
    }
    Spacer(Modifier.height(10.dp))
    val tracks = s.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton({ trackDialog = "sub" }) { Icon(Icons.Filled.Subtitles, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_subtitles)) }
        OutlinedButton({ trackDialog = "audio" }) { Icon(Icons.Filled.Audiotrack, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_audio)) }
        if (tracks.count { it.optString("type") == "video" } > 1)
            OutlinedButton({ trackDialog = "video" }) { Text(stringResource(R.string.main_video)) }
    }
    trackDialog?.let { type ->
        TrackDialog(type, tracks.filter { it.optString("type") == type }, onDismiss = { trackDialog = null }) { id ->
            trackDialog = null
            cmd("track", JSONObject().put("type", type).put("id", id ?: "no"))
        }
    }
    val sub = s.optString("sub").takeIf { it.isNotEmpty() && it != "null" } ?: stringResource(R.string.main_none)
    val audio = s.optString("audio").takeIf { it.isNotEmpty() && it != "null" } ?: stringResource(R.string.main_default_track)
    Text(stringResource(R.string.main_subs_audio_line, sub, audio), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ cmd("sub_delay", -0.1) }) { Text(stringResource(R.string.main_sub_delay_minus)) }
        TextButton({ cmd("sub_delay", 0.1) }) { Text(stringResource(R.string.main_sub_delay_plus)) }
    }
    OutlinedButton({ subsDialog = true }) { Icon(Icons.Filled.Subtitles, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_search_subtitles)) }
    if (subsDialog) SubsDialog(api, MediaRef.Playing(st.title, api.srv.key)) { subsDialog = false }
}

/** Wybór ścieżki w mpv: lista z językiem, tytułem, kodekiem; dla napisów też „wyłączone”. */
@Composable
private fun TrackDialog(type: String, tracks: List<JSONObject>, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val title = when (type) { "sub" -> stringResource(R.string.main_subtitles); "audio" -> stringResource(R.string.main_audio_track); else -> stringResource(R.string.main_video_track) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (type == "sub") item {
                    TrackRow(stringResource(R.string.main_track_off), null, tracks.none { it.optBoolean("selected") }) { onPick(null) }
                }
                items(tracks, key = { it.optInt("id") }) { t ->
                    fun str(k: String) = t.optString(k).takeIf { it.isNotEmpty() && it != "null" }
                    val ttl = str("title")?.let { if (t.optBoolean("external")) it.substringAfterLast('/').takeLast(28) else it }
                    val name = listOfNotNull(str("lang")?.uppercase(), ttl).joinToString(" — ").ifEmpty { stringResource(R.string.main_track_n, t.optInt("id")) }
                    val info = listOfNotNull(
                        str("codec")?.let { c -> CODECS_RES[c]?.let { stringResource(it) } ?: CODECS[c] ?: c },
                        t.optInt("demux-channel-count").takeIf { it > 0 }?.let { "${it}ch" },
                        t.optInt("demux-h").takeIf { it > 0 }?.let { "${t.optInt("demux-w")}×$it" },
                        if (t.optBoolean("external")) stringResource(R.string.main_track_external) else null,
                        if (t.optBoolean("forced")) stringResource(R.string.main_track_forced) else null,
                        if (t.optBoolean("default")) stringResource(R.string.main_default_track) else null,
                    ).joinToString(" · ")
                    TrackRow(name, info, t.optBoolean("selected")) { onPick(t.optInt("id")) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_close)) } },
    )
}

/** kodeki z opisem zależnym od języka */
private val CODECS_RES = mapOf(
    "hdmv_pgs_subtitle" to R.string.main_codec_pgs, "dvd_subtitle" to R.string.main_codec_vobsub, "mov_text" to R.string.main_codec_text,
)

private val CODECS = mapOf(
    "subrip" to "SRT", "ass" to "ASS", "webvtt" to "WebVTT", "eac3" to "E-AC3", "ac3" to "AC3", "aac" to "AAC", "dts" to "DTS",
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
private fun SettingsTab(api: Api, ver: Int, snack: SnackbarHostState, openAdd: Boolean, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = api.s
    var seq by remember(ver) { mutableStateOf(s.sequential) }
    var after by remember(ver) { mutableStateOf(s.afterDefault) }
    var afterDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ServersSection(ver, snack, openAdd, onSaved)
        Text(stringResource(R.string.main_web_version_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({
            scope.launch {
                try { withContext(Dispatchers.IO) { Updater.shareApk(ctx) } }
                catch (e: Exception) { snack.showSnackbar(ctx.getString(R.string.main_failed, "${e.message}")) }
            }
        }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_share_app)) }
        HorizontalDivider()
        Text(stringResource(R.string.main_tab_torrents), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.main_sequential_download))
                Text(stringResource(R.string.main_sequential_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(seq, { seq = it; s.sequential = it })
        }
        Row(Modifier.fillMaxWidth().clickable { afterDialog = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.main_default_after))
                Text(afterLabels(LocalContext.current)[after] ?: after, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (Servers.magnetKey(ctx) != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.main_magnets_always_on, Servers.get(ctx, Servers.magnetKey(ctx))?.name ?: "?"), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            TextButton({ Servers.setMagnetKey(ctx, null); onSaved() }) { Text(stringResource(R.string.main_ask)) }
        }
        HorizontalDivider()
        BufferSettings(s)
        HorizontalDivider()
        var keepScreen by remember { mutableStateOf(s.pilotKeepScreen) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.main_keep_screen))
                Text(stringResource(R.string.main_keep_screen_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(keepScreen, { keepScreen = it; s.pilotKeepScreen = it })
        }
        HorizontalDivider()
        var confirmStop by remember { mutableStateOf(s.confirmForeignStop) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.main_confirm_foreign_stop))
                Text(stringResource(R.string.main_confirm_foreign_stop_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(confirmStop, { confirmStop = it; s.confirmForeignStop = it })
        }
        HorizontalDivider()
        UploadSettings()
        HorizontalDivider()
        if (api.configured) OpenSubsSettings(api, snack)
        HorizontalDivider()
        Text(stringResource(R.string.main_magnet_links_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (afterDialog) AfterDialog(after, { afterDialog = false }) { after = it; s.afterDefault = it; afterDialog = false }
}

fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

/** Bufor na dysku (PlayerActivity.prefetch, MediaCache); działa od następnego otwarcia filmu. */
@Composable
private fun BufferSettings(s: Settings) {
    var text by remember { mutableStateOf(s.bufferMinutes.toString()) }
    val ctx = LocalContext.current
    val maxGb = remember { MediaCache.maxBytes(ctx) shr 30 }
    Text(stringResource(R.string.main_buffer_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.main_buffer_desc, maxGb),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // zapis od razu przy każdej poprawnej wartości 1–999; przy błędnej zostaje poprzednia
    val v = text.toIntOrNull()
    val ok = v != null && v in 1..999
    OutlinedTextField(text, { t ->
        text = t.filter { it.isDigit() }.take(3)
        text.toIntOrNull()?.takeIf { it in 1..999 }?.let { s.bufferMinutes = it }
    }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.main_buffer_label)) }, singleLine = true, isError = !ok,
        supportingText = if (ok) null else ({ Text(stringResource(R.string.main_buffer_error, s.bufferMinutes)) }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
                !o.optBoolean("api_key") -> ctx.getString(R.string.main_os_off)
                !o.optBoolean("password") -> ctx.getString(R.string.main_os_anonymous)
                else -> ctx.getString(R.string.main_os_configured, o.optString("username"))
            }
        }.getOrNull()
    }
    Text(stringResource(R.string.main_subtitles), style = MaterialTheme.typography.titleMedium)
    Text(listOfNotNull(stringResource(R.string.main_subs_info, api.srv.name),
        state?.let { stringResource(R.string.main_subs_state, it) }).joinToString(" "),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(langs, { langs = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.main_subs_langs_label)) }, singleLine = true)
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.main_os_key_label)) }, singleLine = true)
    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.main_os_user_label)) }, singleLine = true)
    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.main_os_pass_label)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            api.s.subLanguages = langs
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.setOpenSubsConfig(key.ifBlank { null }, user, pass) }
                    key = ""; pass = ""
                    state = ctx.getString(R.string.main_os_saved)
                    snack.showSnackbar(ctx.getString(R.string.main_saved_on, api.srv.name))
                } catch (e: ApiException) {
                    snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
                }
            }
        }) { Text(stringResource(R.string.main_save_subs)) }
        OutlinedButton({
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.opensubtitles.com/en/consumers")))
        }) { Text(stringResource(R.string.main_keys_page)) }
    }
}

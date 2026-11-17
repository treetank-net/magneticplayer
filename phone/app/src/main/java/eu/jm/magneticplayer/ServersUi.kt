package eu.jm.magneticplayer

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

val OK_GREEN = Color(0xFF3FA96B)
private val WARN = Color(0xFFF2B84B)

// ---------------------------------------------------------------- odtwarzanie na ekranie

object Screens {
    /**
     * Odtwarza `ref` na ekranie `screenKey` (mpv na serwerze), budząc go w razie potrzeby. Ten sam serwer: plik lokalny
     * jak dawniej. Inny: URL z biletu serwera plików albo z telefonu ([source]). Zapamiętuje ekran dla Pilota.
     */
    suspend fun play(ctx: Context, ref: MediaRef, screenKey: String, startSec: Double?, onStatus: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val screen = Api.of(ctx, screenKey) ?: throw ApiException("Nie ma już tego ekranu")
            screen.ensureAwake(onStatus)
            if (screenKey == ref.srv) {
                // pozycja z telefonu: film mógł być ostatnio oglądany gdzie indziej (Chromecast, telewizor, telefon)
                screen.playerOpen(ref, startSec ?: savedStart(ctx, ref))
            } else {
                val src = source(ctx, ref, onStatus)
                onStatus("Otwieram na ${screen.srv.name}…")
                screen.playerOpenUrl(Ticket(src.url, src.subs.map { it.url }, ref.title), startSec ?: savedStart(ctx, ref))
            }
            Servers.setScreen(ctx, screenKey)
            Servers.setScreenRef(ctx, screenKey, ref)
            withContext(Dispatchers.Main) { Pilot.started(ctx, "screen") }
        }

    /** Odtwarza `ref` na Chromecaście (odbiornik domyślny Google'a, napisy jako WebVTT). */
    suspend fun cast(ctx: Context, ref: MediaRef, target: CastTarget, startSec: Double?, onStatus: (String) -> Unit) {
        val src = withContext(Dispatchers.IO) { source(ctx, ref, onStatus, variant = "cast") }
        onStatus("Łączę z ${target.name}…")
        val start = startSec ?: savedStart(ctx, ref)
        Cast.play(ctx, target, ref, src, ((start ?: 0.0) * 1000).toLong())
        Pilot.started(ctx, "cast")
        Settings(ctx).lastTv = target.name
    }

    /** Odtwarza `ref` na telewizorze DLNA (Samsung i inne): oryginalny plik, napisy SRT po swojemu. */
    suspend fun dlna(ctx: Context, ref: MediaRef, target: DlnaTarget, startSec: Double?, onStatus: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val src = source(ctx, ref, onStatus)
            onStatus("Wysyłam do ${target.name}…")
            Dlna.play(ctx, target, ref, src, startSec ?: savedStart(ctx, ref) ?: 0.0)
            Settings(ctx).lastTv = target.name
        }

    private fun savedStart(ctx: Context, ref: MediaRef) =
        Settings(ctx).position(ref.key()).takeIf { it > 5_000 }?.let { it / 1000.0 }

    /**
     * Skąd ekran weźmie plik: z serwera (bilet), gdy odpowiada albo telefon nie ma całego filmu (wtedy budzimy).
     * Serwer nie odpowiada (np. u kogoś w gościach), a cały film jest na telefonie: telefon go udostępnia.
     */
    suspend fun source(ctx: Context, ref: MediaRef, onStatus: (String) -> Unit, variant: String? = null): MediaSrc {
        val file = Api.of(ctx, ref.srv)
        val onPhone = PhoneShare.hasWhole(ctx, ref.key())
        if (file != null && (!onPhone || file.ping(1500))) {
            file.ensureAwake(onStatus)
            val t = file.ticket(ref, variant)
            return MediaSrc(t.url, ref.title, t.subInfo, false, t.contentType)
        }
        if (!onPhone) throw ApiException("Nie ma już serwera z tym filmem")
        onStatus("Udostępniam film z telefonu…")
        return PhoneShare.start(ctx, ref)
    }
}

/** Stan ekranu do listy wyboru: null = sprawdzam, false = nie odpowiada, true = działa (+ co gra). */
private data class ScreenState(val online: Boolean?, val playing: String? = null)

/** „Gdzie odtworzyć?”: ten telefon albo mpv na którymś serwerze. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenChooser(ref: MediaRef, onPhone: (() -> Unit)?, onPick: (String) -> Unit, onCast: (CastTarget) -> Unit,
                  onDlna: (DlnaTarget) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val screens = remember { Servers.screens(ctx) }
    val last = remember { Servers.screenKey(ctx) }
    val fileName = remember { Servers.get(ctx, ref.srv)?.name ?: "?" }
    val states = remember { mutableStateMapOf<String, ScreenState>() }
    val casts = rememberCastTargets()
    val castOk = remember { Cast.available(ctx) }
    val castNow = remember { Cast.session(ctx)?.castDevice?.friendlyName }
    val onPhoneWhole = remember { PhoneShare.hasWhole(ctx, ref.key()) }
    var searching by remember { mutableStateOf(true) }
    val lastTv = remember { Settings(ctx).lastTv }
    // telewizory DLNA: najpierw znane z poprzedniego szukania, po 3 s te, które odpowiedziały teraz
    var dlnas by remember { mutableStateOf(Dlna.found.values.toList()) }
    LaunchedEffect(Unit) { dlnas = withContext(Dispatchers.IO) { Dlna.discover() } }
    LaunchedEffect(Unit) { delay(8_000); searching = false }
    LaunchedEffect(Unit) {
        screens.forEach { s ->
            launch(Dispatchers.IO) {
                val a = Api(Settings(ctx), s)
                states[s.key] = if (a.ping(1500)) ScreenState(true, runCatching {
                    a.playerStatus().takeIf { it.optBoolean("running") && !it.optBoolean("idle") }?.optString("title")
                }.getOrNull()) else ScreenState(false)
            }
        }
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Gdzie odtworzyć?", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
            Text("${ref.title} · plik z: $fileName", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onPhone != null) SheetRow(Icons.Filled.Smartphone, "Ten telefon", null, null, false) { onPhone() }
            screens.forEach { s ->
                val st = states[s.key]
                val sub = buildList {
                    add(if (s.key == ref.srv) "plik jest na nim" else "przez sieć z $fileName")
                    when {
                        st == null -> add("sprawdzam…")
                        st.online == false -> add("śpi albo wyłączony · obudzę i odtworzę")
                        st.playing != null -> add("teraz gra: ${st.playing}")
                    }
                }.joinToString(" · ")
                SheetRow(Icons.Filled.Tv, s.name + if (s.key == last && screens.size > 1) " · ostatnio" else "", sub,
                    when (st?.online) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null },
                    s.key == last) { onPick(s.key) }
            }
            if (castOk) {
                casts.forEach { c ->
                    val sub = listOfNotNull(
                        c.desc.takeIf { it.isNotBlank() },
                        if (onPhoneWhole) "film z $fileName albo z telefonu" else "film z $fileName",
                    ).joinToString(" · ")
                    SheetRow(Icons.Filled.Cast, c.name + when (c.name) { castNow -> " · połączony"; lastTv -> " · ostatnio"; else -> "" },
                        sub, null, c.name == castNow) { onCast(c) }
                }
                if (casts.isEmpty()) SheetRow(Icons.Filled.Cast, "Chromecast",
                    if (searching) "szukam w sieci…" else "nie widzę żadnego w tej sieci Wi-Fi", null, false, enabled = false) {}
                CastFormat(ref)
            }
            dlnas.forEach { d ->
                val sub = listOfNotNull("DLNA", d.model.ifBlank { null },
                    if (onPhoneWhole) "film z $fileName albo z telefonu" else "film z $fileName").joinToString(" · ")
                SheetRow(Icons.Filled.ConnectedTv, d.name + if (d.name == lastTv) " · ostatnio" else "", sub, null,
                    Dlna.current?.id == d.id) { onDlna(d) }
            }
        }
    }
}

/** Pod Chromecastami: czy ten plik zagra, i „Przygotuj wersję dla Chromecasta” (agent 1.2+ z ffmpeg). */
@Composable
private fun CastFormat(ref: MediaRef) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { Api.of(ctx, ref.srv) } ?: return
    var probe by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        while (true) {
            probe = withContext(Dispatchers.IO) { runCatching { if (api.ping(1500)) api.mediaProbe(ref) else null }.getOrNull() }
            val st = probe?.optJSONObject("job")?.optString("state")
            if (st != "queued" && st != "running") break
            delay(2_000)
        }
    }
    fun op(o: String?) = scope.launch {
        err = withContext(Dispatchers.IO) { runCatching { api.mediaConvert(ref, o) }.exceptionOrNull()?.message }
        tick++
    }
    val p = probe?.takeIf { it.optBoolean("ffmpeg") } ?: return
    val v = p.optJSONObject("variant")
    val c = p.optJSONObject("cast")
    val devs = p.optJSONArray("cast_devices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    val j = p.optJSONObject("job")
    val state = j?.optString("state")
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(start = 60.dp, end = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            v != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Chromecast dostanie wersję przygotowaną dla niego (${humanBytes(v.optLong("size"))})", Modifier.weight(1f),
                    style = small, color = OK_GREEN)
                TextButton({ op("remove") }) { Text("Usuń") }
            }
            state == "queued" || state == "running" -> {
                val pct = j!!.optDouble("pct", 0.0)
                val speed = j.optDouble("speed").takeIf { !it.isNaN() }?.let { " · %.1f× szybciej niż film".format(it) } ?: ""
                if (j.optBoolean("finishing")) {
                    // ffmpeg +faststart przepisuje plik na końcu: bez tego pasek stoi na 99% nawet minutę
                    Text("Kończę zapis (przenoszę indeks na początek pliku), to może potrwać minutę…", style = small)
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("Przygotowuję dla Chromecasta (${j.optString("encoder").ifEmpty { "w kolejce" }}): ${pct.toInt()}%$speed",
                        style = small)
                    androidx.compose.material3.LinearProgressIndicator({ (pct / 100).toFloat() }, Modifier.fillMaxWidth())
                }
                TextButton({ op("cancel") }) { Text("Przerwij") }
            }
            // agent zna model naszych Chromecastów: ocena dla każdego, „Przygotuj” tylko gdy któryś ma kłopot
            devs.isNotEmpty() && devs.all { it.optString("verdict") == "ok" } -> devs.forEach { d ->
                Text("${d.optString("name")} (${d.optString("label")}): zagra bez przygotowania", style = small, color = muted)
            }
            devs.isEmpty() && c?.optString("verdict") == "ok" -> Text("Format: Chromecast powinien to zagrać", style = small, color = muted)
            c != null -> {
                if (devs.isNotEmpty()) devs.forEach { d ->
                    val v = d.optString("verdict")
                    Text("${d.optString("name")} (${d.optString("label")}): " + when (v) {
                        "ok" -> "zagra"; "no" -> "raczej nie zagra"; else -> "może mieć kłopot" },
                        style = small, color = if (v == "ok") muted else MaterialTheme.colorScheme.error)
                    val pr = d.optJSONArray("problems")
                    for (i in 0 until (pr?.length() ?: 0)) Text("· " + pr!!.getString(i), style = small, color = muted)
                } else {
                    val probs = c.optJSONArray("problems")
                    Text(if (c.optString("verdict") == "no") "Chromecast raczej tego nie zagra:" else "Chromecast może mieć kłopot:",
                        style = small, color = MaterialTheme.colorScheme.error)
                    for (i in 0 until (probs?.length() ?: 0)) Text("· " + probs!!.getString(i), style = small, color = muted)
                }
                if (state == "error") Text("Ostatnia próba: " + j!!.optString("error").takeLast(160), style = small,
                    color = MaterialTheme.colorScheme.error)
                val enc = p.optJSONObject("encoder")?.optString("label") ?: "?"
                val how = (if (c.optBoolean("video_copy")) "obraz bez zmian" else "obraz: $enc") +
                    (if (c.optBoolean("audio_copy")) ", dźwięk bez zmian" else ", dźwięk → AAC")
                TextButton({ op(null) }, enabled = p.optBoolean("complete")) {
                    Text(if (p.optBoolean("complete")) "Przygotuj wersję dla Chromecasta ($how)" else "Przygotuję, gdy film pobierze się w całości")
                }
            }
        }
        err?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, dot: Color?, selected: Boolean,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (dot != null) Text("●", color = dot, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---------------------------------------------------------------- przełącznik serwera

/** Panel od dołu: z którego serwera są filmy (Torrenty, Biblioteka). Serwer z samym ekranem jest, ale wyszarzony. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(current: String?, onPick: (String) -> Unit, onWake: (String) -> Unit, onAdd: () -> Unit,
                onManage: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember { Servers.all(ctx) }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        while (true) {
            servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
            delay(4000)
        }
    }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Filmy z serwera", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
            servers.forEach { s ->
                val on = online[s.key]
                val status = when (on) { true -> "działa"; false -> "śpi albo wyłączony"; null -> "…" }
                SheetRow(if (s.hasPlayer) Icons.Filled.Computer else Icons.Filled.Dns, s.name,
                    if (s.hasFiles) "${s.roles} · $status" else "tylko ekran · $status",
                    when (on) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null },
                    s.key == current, enabled = s.hasFiles,
                    trailing = if (on == false && s.mac.isNotBlank()) ({ TextButton({ onWake(s.key) }) { Text("Obudź") } }) else null,
                ) { onPick(s.key) }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetRow(Icons.Filled.Add, "Dodaj serwer", null, null, false) { onAdd() }
            SheetRow(Icons.Filled.Settings, "Zarządzaj serwerami", null, null, false) { onManage() }
        }
    }
}

/** Wybrany serwer nie odpowiada: zamiast błędu „śpi” z przyciskiem budzenia. */
@Composable
fun AsleepPane(server: Server, waking: String?, wrongServer: Boolean, onWake: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (waking != null) Icons.Filled.PowerSettingsNew else Icons.Filled.Bedtime, null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (wrongServer) {
            Text("Pod adresem ${server.host} jest teraz inny serwer", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Router mógł przydzielić ${server.name} nowy adres. Sparuj go jeszcze raz (Ustawienia → Dodaj serwer).",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(if (waking != null) "Budzę ${server.name}" else "${server.name} śpi albo jest wyłączony",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (waking != null) {
            LinearProgressIndicator(Modifier.width(200.dp))
            Spacer(Modifier.height(8.dp))
            Text(waking, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val (txt, col) = when (server.wakeConfidence) {
            "yes" -> "Budzenie przez sieć już działało." to OK_GREEN
            "no" -> "Raczej nie obudzisz go z telefonu: ${server.wakeReason}." to MaterialTheme.colorScheme.error
            else -> "Budzenia jeszcze nie sprawdzono (${server.wakeReason})." to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(txt, style = MaterialTheme.typography.bodySmall, color = col, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (server.mac.isNotBlank()) Button(onWake) { Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text("Obudź") }
        Spacer(Modifier.height(20.dp))
        Text("Filmy pobrane na telefon są w zakładce „Na telefonie”.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** „Uśpić?” z ostrzeżeniem zależnym od tego, czy da się go potem obudzić. */
@Composable
fun SuspendDialog(server: Server, poweroff: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val what = if (poweroff) "Wyłączyć" else "Uśpić"
    val conf = server.wakeConfidence
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (conf == "no") ({ Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = { Text("$what ${server.name}?") },
        text = {
            Column {
                Text("Pobieranie się zatrzyma." + if (conf == "yes") " Obudzisz go z apki albo klikając magnet link." else "")
                if (conf == "maybe") Surface(color = WARN.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 12.dp)) {
                    Text("Jeszcze nie sprawdzono, czy da się go obudzić z telefonu (${server.wakeReason}).",
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (conf == "no") Text("Nie obudzisz go z telefonu (${server.wakeReason}). Trzeba będzie do niego podejść.",
                    Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(if (conf == "no") "$what mimo to" else what,
                    color = if (conf == "no") MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Anuluj") } },
    )
}

// ---------------------------------------------------------------- Ustawienia: serwery

/** Lista serwerów w Ustawieniach: szczegóły, dodawanie, parowanie kolejnych urządzeń. */
@Composable
fun ServersSection(ver: Int, snack: SnackbarHostState, openAdd: Boolean, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember(ver) { Servers.all(ctx) }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var details by remember { mutableStateOf<String?>(null) }
    var add by remember(openAdd) { mutableStateOf(openAdd) }
    var invite by remember { mutableStateOf(false) }
    LaunchedEffect(ver) {
        servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
    }
    Text("Serwery", style = MaterialTheme.typography.titleMedium)
    if (servers.isEmpty()) Text("Nie ma jeszcze serwera. Po instalacji agenta komputer pokazuje kod parowania (QR) — " +
        "zeskanuj go aparatem albo wpisz w „Dodaj serwer”.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        servers.forEach { s ->
            SheetRow(if (s.hasPlayer) Icons.Filled.Computer else Icons.Filled.Dns, s.name, "${s.host} · ${s.roles}",
                when (online[s.key]) { true -> OK_GREEN; false -> MaterialTheme.colorScheme.outline; null -> null }, false) {
                details = s.key
            }
        }
        SheetRow(Icons.Filled.Add, "Dodaj serwer", null, null, false) { add = true }
    }
    if (servers.isNotEmpty()) OutlinedButton({ invite = true }) {
        Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("Sparuj kolejne urządzenie")
    }
    details?.let { k -> ServerDetailsDialog(k, snack, onChanged = onChanged) { details = null } }
    if (add) AddServerDialog(onDone = { onChanged() }) { add = false }
    if (invite) InviteDialog(Servers.activeKey(ctx)) { invite = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerDetailsDialog(key: String, snack: SnackbarHostState, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Servers.get(ctx, key)) }
    val s = server ?: return onDismiss()
    var name by remember { mutableStateOf(s.name) }
    var devices by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    var invite by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var wakeTest by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        try {
            withContext(Dispatchers.IO) {
                val api = Api(Settings(ctx), s)
                api.info()
                val a = api.devices()
                devices = List(a.length()) { a.getJSONObject(it) }
            }
            server = Servers.get(ctx, key)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${s.host}:${s.port}" + (s.info?.optString("version")?.takeIf { it.isNotEmpty() }?.let { " · agent $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.hasTorrent) AssistChip({}, { Text("torrenty: " + (s.info?.optJSONObject("features")?.optString("torrent") ?: "tak")) })
                    if (s.hasLibrary) AssistChip({}, { Text("biblioteka") })
                    if (s.hasPlayer) AssistChip({}, { Text("ekran (mpv)") })
                    if (s.canUnlock && s.hasPlayer) AssistChip({}, { Text("odblokowanie") })
                    s.transcode?.let { AssistChip({}, { Text("wersje dla Chromecasta: $it") }) }
                }
                if (!s.knowsFeatures) Text("Stara wersja agenta: zaktualizuj ją (install.sh), żeby apka wiedziała, co ten serwer umie.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val wake = when (s.wakeConfidence) {
                    "yes" -> "budzenie przez sieć działa"
                    "no" -> "nie da się obudzić przez sieć: ${s.wakeReason}"
                    else -> "budzenie niesprawdzone: ${s.wakeReason}"
                }
                Text("Usypianie: ${if (s.canSuspend) "tak" else "nie"} · $wake", style = MaterialTheme.typography.bodySmall)
                if (s.canSuspend && s.mac.isNotBlank() && s.wakeConfidence != "no") OutlinedButton({ wakeTest = true }) {
                    Text(if (s.wakeConfidence == "yes") "Sprawdź budzenie jeszcze raz" else "Sprawdź budzenie")
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nazwa w apce") }, singleLine = true)
                HorizontalDivider()
                Text("Sparowane urządzenia", style = MaterialTheme.typography.titleSmall)
                when (val list = devices) {
                    null -> if (error == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> list.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.optString("name") + if (d.optBoolean("current")) " · ten telefon" else "")
                                Text(lastSeen(d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!d.optBoolean("current")) TextButton({ revoke = d }) {
                                Text(if (d.optBoolean("legacy")) "Wyłącz" else "Odbierz", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ invite = true }) { Text("Sparuj kolejne") }
                    TextButton({ confirmRemove = true }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (name.isNotBlank() && name != s.name) {
                    Servers.update(ctx, key) { it.copy(name = name.trim()) }
                    onChanged()
                }
                onDismiss()
            }) { Text("Zamknij") }
        },
        dismissButton = { TextButton({ reload++ }) { Text("Testuj") } },
    )
    if (invite) InviteDialog(key) { invite = false }
    if (wakeTest) WakeTestDialog(s) { wakeTest = false; reload++ }
    revoke?.let { d ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text(if (d.optBoolean("legacy")) "Wyłączyć stary wspólny token?" else "Odebrać dostęp?") },
            text = {
                Text(if (d.optBoolean("legacy")) "Urządzenia, które nie zostały sparowane na nowo (np. starsza wersja apki), stracą dostęp do ${s.name}."
                else "„${d.optString("name")}” straci dostęp do ${s.name}. Żeby wrócić, trzeba będzie sparować je jeszcze raz.")
            },
            confirmButton = {
                TextButton({
                    revoke = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api(Settings(ctx), s).revoke(d.getString("id")) }
                            reload++
                        } catch (e: ApiException) {
                            snack.showSnackbar(e.message ?: "Błąd")
                        }
                    }
                }) { Text(if (d.optBoolean("legacy")) "Wyłącz" else "Odbierz", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ revoke = null }) { Text("Anuluj") } },
        )
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Usunąć ${s.name} z apki?") },
        text = { Text("Serwer zniknie tylko z tego telefonu. Filmy pobrane z niego na telefon zostają w „Na telefonie”.") },
        confirmButton = {
            TextButton({ confirmRemove = false; Servers.remove(ctx, key); onChanged(); onDismiss() }) {
                Text("Usuń", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Anuluj") } },
    )
}

private fun lastSeen(d: JSONObject): String {
    val t = d.optDouble("last_seen").takeIf { !it.isNaN() && it > 0 } ?: return "jeszcze nie łączył się"
    val ago = (System.currentTimeMillis() / 1000.0 - t).toLong()
    return "ostatnio: " + when {
        ago < 120 -> "teraz"
        ago < 3600 -> "${ago / 60} min temu"
        ago < 86400 -> "${ago / 3600} h temu"
        else -> "${ago / 86400} dni temu"
    }
}

/** „Dodaj serwer”: adres + kod (albo link parowania ze schowka); po połączeniu podsumowanie możliwości. */
@Composable
fun AddServerDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<List<Server>?>(null) }

    fun run(targets: List<PairTarget>) {
        busy = true; error = null
        scope.launch {
            try {
                added = withContext(Dispatchers.IO) { targets.map { Pairing.pair(ctx, it) } }
                onDone()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Nie udało się: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (added != null) "Połączono" else "Dodaj serwer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val done = added
                if (done != null) {
                    done.forEach { s -> ServerSummary(s) }
                    return@Column
                }
                Text("Kod parowania pokazuje komputer po instalacji (albo: magneticplayer_agent.py --pair), a sparowany " +
                    "telefon w Ustawieniach → „Sparuj kolejne urządzenie”. QR zeskanuj zwykłym aparatem — otworzy tę apkę.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Adres IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Kod parowania (albo stary token)") },
                    singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    val link = Regex("""magneticplayer://pair\S+""").find(text)?.value
                    val targets = link?.let { Pairing.parse(android.net.Uri.parse(it)) }.orEmpty()
                    if (targets.isEmpty()) error = "W schowku nie ma linku parowania" else run(targets)
                }, enabled = !busy) { Text("Wklej link parowania") }
            }
        },
        confirmButton = {
            if (added != null) TextButton(onDismiss) { Text("Gotowe") }
            else TextButton({
                val p = port.toIntOrNull() ?: 8765
                run(listOf(if (Pairing.looksLikeCode(code)) PairTarget(host, p, code, null, null) else PairTarget(host, p, null, code, null)))
            }, enabled = !busy && host.isNotBlank() && code.isNotBlank()) { Text("Połącz") }
        },
        dismissButton = { if (added == null) TextButton(onDismiss) { Text("Anuluj") } },
    )
}

/** Co serwer umie, a czego nie (po dodaniu). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerSummary(s: Server) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("✓ ${s.name}", style = MaterialTheme.typography.titleSmall, color = OK_GREEN)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.hasTorrent) AssistChip({}, { Text("torrenty") })
            if (s.hasLibrary) AssistChip({}, { Text("biblioteka") })
            if (s.hasPlayer) AssistChip({}, { Text("ekran") })
        }
        if (!s.hasPlayer) Text("Brak ekranu (nie ma mpv albo sesji graficznej): filmy z niego obejrzysz na telefonie albo na innym ekranie.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (s.wakeConfidence) {
            "maybe" -> Text("Budzenie: ${s.wakeReason}. Uśpij i obudź go raz, żeby się upewnić.", style = MaterialTheme.typography.bodySmall, color = WARN)
            "no" -> if (s.canSuspend) Text("Budzenie: ${s.wakeReason}, więc po uśpieniu trzeba będzie do niego podejść.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * „Sparuj kolejne urządzenie”: jednorazowe kody z zaznaczonych serwerów w jednym QR (nowy telefon skanuje raz
 * i zna wszystkie). Kody działają raz i wygasają po 10 minutach.
 */
@Composable
fun InviteDialog(preselect: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val servers = remember { Servers.all(ctx) }
    val checked = remember { mutableStateMapOf<String, Boolean>().apply { servers.forEach { put(it.key, servers.size == 1 || it.key == preselect) } } }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var codes by remember { mutableStateOf<List<Pair<Server, JSONObject>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        servers.forEach { s -> launch(Dispatchers.IO) { online[s.key] = Api(Settings(ctx), s).ping(1500) } }
    }
    LaunchedEffect(codes) {
        val c = codes ?: return@LaunchedEffect
        left = c.minOfOrNull { it.second.optInt("expires_in", 600) } ?: 600
        while (left > 0) { delay(1000); left-- }
    }
    fun generate() = scope.launch {
        error = null
        try {
            codes = withContext(Dispatchers.IO) {
                servers.filter { checked[it.key] == true }.map { s -> s to Api(Settings(ctx), s).pairCode() }
            }
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val link = codes?.let { c -> Pairing.link(c.map { (s, o) -> Triple(s.host, s.port, o.getString("code")) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sparuj kolejne urządzenie") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (link == null) {
                    Text("Z którymi serwerami?", Modifier.fillMaxWidth())
                    servers.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { checked[s.key] = checked[s.key] != true }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked[s.key] == true, { checked[s.key] = it }, enabled = online[s.key] != false)
                            Text(s.name + if (online[s.key] == false) " (nie odpowiada)" else "")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    return@Column
                }
                val qr = remember(link) { qrBitmap(link, 720) }
                androidx.compose.foundation.Image(qr.asImageBitmap(), "kod QR parowania",
                    Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                Spacer(Modifier.height(8.dp))
                Text(if (left > 0) "Ważny jeszcze ${left / 60}:${"%02d".format(left % 60)}, działa raz." else "Kod wygasł — zamknij i wygeneruj nowy.",
                    style = MaterialTheme.typography.labelMedium, color = if (left > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text("Na nowym telefonie: zainstaluj MagneticPlayer i zeskanuj kod aparatem. Albo w apce: Ustawienia → Dodaj serwer i wpisz:",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                codes!!.forEach { (s, o) ->
                    val c = o.getString("code")
                    Text("${s.name}: ${s.host}, kod ${c.take(3)}-${c.drop(3)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                val (ws, wo) = codes!!.first()
                val web = "http://${ws.host}:${ws.port}/#p=${wo.getString("code")}"
                Text("Laptop albo tablet: otwórz w przeglądarce (${ws.name})", style = MaterialTheme.typography.labelMedium)
                Text(web, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        copyText(ctx, "MagneticPlayer — wersja webowa", web)
                        android.widget.Toast.makeText(ctx, "Skopiowano (kod działa raz)", android.widget.Toast.LENGTH_SHORT).show()
                    })
            }
        },
        confirmButton = {
            if (link == null) TextButton({ generate() }, enabled = checked.values.any { it }) { Text("Pokaż kod") }
            else TextButton(onDismiss) { Text("Zamknij") }
        },
        dismissButton = {
            if (link != null) TextButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link), "Link parowania MagneticPlayer"))
            }) { Text("Udostępnij link") }
            else TextButton(onDismiss) { Text("Anuluj") }
        },
    )
}

fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * „Sprawdź budzenie”: usypia serwer, czeka, aż zniknie z sieci, i budzi go jak przy oglądaniu (WoL z telefonu
 * i przez inne serwery). Udane budzenie zapisuje się na serwerze (/api/wake/verified), więc „Uśpij” przestaje ostrzegać.
 */
@Composable
private fun WakeTestDialog(s: Server, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<String?>(null) }  // null = pytanie, potem opis kroku
    var result by remember { mutableStateOf<Boolean?>(null) }
    fun run() = scope.launch {
        val api = Api(Settings(ctx), s)
        result = try {
            withContext(Dispatchers.IO) {
                phase = "Usypiam ${s.name}…"
                api.suspendPc()
                phase = "Czekam, aż ${s.name} zaśnie…"
                var asleep = false
                repeat(30) { if (!asleep) { delay(2_000); asleep = !api.ping(1500) } }
                if (!asleep) throw ApiException("${s.name} nie zasnął (może coś go trzyma: pobieranie, odtwarzanie)")
                delay(10_000)  // karta sieciowa przechodzi w tryb czuwania dopiero po chwili
                api.ensureAwake { phase = it }
            }
            true
        } catch (e: ApiException) {
            phase = e.message
            false
        }
    }
    AlertDialog(
        onDismissRequest = { if (phase == null || result != null) onDone() },
        title = { Text("Budzenie ${s.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    phase == null -> Text("Uśpię ${s.name} i spróbuję go obudzić przez sieć. To potrwa 1–2 minuty, a w tym czasie " +
                        "nic na nim nie zagra ani się nie pobierze. Ktoś przy nim siedzi?")
                    result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(phase!!)
                    }
                    result == true -> Text("Działa: ${s.name} obudził się przez sieć. Apka i serwer to zapamiętały.", color = OK_GREEN)
                    else -> Text("Nie wyszło: $phase\n\nSprawdź Wake-on-LAN w BIOS-ie i ustawienia karty sieciowej (docs/instalacja-i-utrzymanie.md).",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                phase == null -> TextButton({ run() }) { Text("Uśpij i obudź") }
                result != null -> TextButton(onDone) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = { if (phase == null) TextButton(onDone) { Text("Anuluj") } },
    )
}

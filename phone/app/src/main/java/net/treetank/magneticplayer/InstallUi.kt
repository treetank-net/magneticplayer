package net.treetank.magneticplayer

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * „Zainstaluj na innym komputerze”: serwer daje jednorazowe polecenie (`curl … /i/<kod> | bash`), nowy komputer
 * pobiera z niego agenta, instaluje się i zgłasza z kodem parowania, a ten telefon paruje się sam (mp_install.py).
 * Bez serwera (albo z wyboru) źródłem jest sam telefon: agent zaszyty w APK, serwer HTTP na czas okna (InstallShare).
 * Zależności (pip, mpv, Python na Windows) nowy komputer i tak pobiera z internetu.
 */
@Composable
fun InstallDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { Servers.all(ctx).filter { it.canInstall } }
    var source by remember { mutableStateOf(sources.firstOrNull { it.key == Servers.activeKey(ctx) } ?: sources.firstOrNull()) }
    val phoneOk = remember { InstallShare.available(ctx) }
    var fromPhone by remember { mutableStateOf(source == null) }
    DisposableEffect(Unit) { onDispose { InstallShare.stop() } }
    // nowsza paczka agenta z wydania na GitHubie (podpisana); bez sieci zostaje ta z apki
    var bundleLabel by remember { mutableStateOf(AgentBundle.label(ctx)) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { AgentBundle.refresh(ctx) } }
        bundleLabel = AgentBundle.label(ctx)
    }
    var lite by remember { mutableStateOf(true) }
    var windows by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<JSONObject?>(null) }
    var state by remember { mutableStateOf("waiting") }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<Server?>(null) }

    // czekanie na nowy komputer: co 2 s stan kodu; po zgłoszeniu parowanie jego kodem
    LaunchedEffect(code) {
        val c = code ?: return@LaunchedEffect
        if (c.optBoolean("phone")) {  // źródłem jest ten telefon: stan prosto z InstallShare
            while (added == null) {
                delay(1000)
                state = InstallShare.state
                val j = InstallShare.joined ?: continue
                InstallShare.stop()
                try {
                    added = withContext(Dispatchers.IO) { Pairing.pair(ctx, PairTarget(j.host, j.port, j.pairCode, null, null)) }
                    onDone()
                } catch (e: Exception) {
                    error = "Nowy komputer się zgłosił, ale parowanie nie wyszło: ${e.message}. Kod ręczny: na nim " +
                        "magneticplayer_agent.py --pair."
                    return@LaunchedEffect
                }
            }
            return@LaunchedEffect
        }
        val src = source ?: return@LaunchedEffect
        val api = Api(Settings(ctx), src)
        while (added == null) {
            delay(2000)
            val st = runCatching { withContext(Dispatchers.IO) { api.installStatus(c.getString("code")) } }.getOrNull() ?: continue
            state = st.optString("state")
            val srv = st.optJSONObject("server") ?: continue
            try {
                added = withContext(Dispatchers.IO) {
                    Pairing.pair(ctx, PairTarget(srv.getString("host"), srv.optInt("port", 8765), srv.getString("pair_code"), null, null))
                }
                onDone()
            } catch (e: Exception) {
                error = "Nowy komputer się zgłosił, ale parowanie nie wyszło: ${e.message}. Kod ręczny: na nim " +
                    "magneticplayer_agent.py --pair."
                return@LaunchedEffect
            }
        }
    }

    fun start() = scope.launch {
        error = null
        if (fromPhone) {
            val url = withContext(Dispatchers.IO) { runCatching { InstallShare.start(ctx) }.getOrNull() }
            if (url == null) { error = "Telefon musi być w tym samym Wi-Fi co nowy komputer."; return@launch }
            code = JSONObject().put("phone", true).put("code", InstallShare.code).put("url", url)
                .put("command", "curl -fsSL $url | bash").put("command_full", "curl -fsSL '$url?full=1' | bash")
                .put("command_windows", "irm $url/ps | iex")
            return@launch
        }
        val src = source ?: return@launch
        try {
            code = withContext(Dispatchers.IO) { Api(Settings(ctx), src).installCode() }
        } catch (e: ApiException) {
            error = e.message
        }
    }

    val c = code
    val command = c?.optString(if (windows) "command_windows" else if (lite) "command" else "command_full").orEmpty()
        .ifEmpty { if (c != null && windows) "Ten serwer nie ma jeszcze instalatora dla Windows — zaktualizuj na nim agenta." else "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (added != null) "Dodano serwer" else "Zainstaluj na innym komputerze") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                added?.let { ServerSummary(it); return@Column }
                if (c == null) {
                    Text("Komputer w tej samej sieci (Linux albo Windows 10/11) pobierze agenta z serwera i sam się sparuje z tym telefonem.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sources.size + (if (phoneOk) 1 else 0) > 1) {
                        Text("Skąd pobrać agenta", style = MaterialTheme.typography.labelLarge)
                        sources.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable { source = s; fromPhone = false }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(!fromPhone && source?.key == s.key, { source = s; fromPhone = false }); Text(s.name)
                            }
                        }
                        if (phoneOk) Row(Modifier.fillMaxWidth().clickable { fromPhone = true }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fromPhone, { fromPhone = true })
                            Column {
                                Text("Ten telefon")
                                Text("$bundleLabel; bez innego serwera; okno musi zostać otwarte do końca instalacji. Dodatki (napisy, mpv, na Windows Python) komputer pobierze z internetu",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else if (fromPhone) Text("Agent pochodzi z tego telefonu ($bundleLabel), bez innego serwera. Zostaw to okno otwarte do końca instalacji. Dodatki (napisy, mpv, na Windows Python) komputer pobierze z internetu; bez niego na Linuksie zadziała biblioteka i oglądanie na telefonie.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (windows) "Na nowym komputerze otwórz PowerShell (prawy klik na Start → Terminal) i wklej:"
                        else "Na nowym komputerze otwórz terminal i wklej:", style = MaterialTheme.typography.bodyMedium)
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().clickable {
                            copyText(ctx, "MagneticPlayer — instalacja", command)
                            android.widget.Toast.makeText(ctx, "Skopiowano", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                        Text(command, Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Polecenie działa raz, przez 30 min. Dotknij, żeby skopiować, albo wyślij je sobie przyciskiem niżej.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (state == "downloading") "Komputer pobrał agenta, instaluje się…" else "Czekam na nowy komputer…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!windows, { windows = false }, { Text("Linux") })
                    FilterChip(windows, { windows = true }, { Text("Windows") })
                }
                if (windows) Text("Lekka instalacja: Biblioteka, przesyłanie z telefonu, napisy i ekran (mpv dociąga sam). " +
                    "Wklej polecenie do okna PowerShell, nie do „Uruchom” (Win+R): Defender blokuje takie polecenie podane wprost.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bez hasła administratora", style = MaterialTheme.typography.bodyMedium)
                        Text(if (lite) "Lekka: Biblioteka, przesyłanie z telefonu, napisy i ekran (gdy jest mpv). Bez torrentów, usypiania i budzenia z sieci."
                            else "Pełna: doinstaluje mpv i Deluge, usypianie i budzenie z sieci. Zapyta o hasło administratora.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(lite, { lite = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when {
                added != null -> TextButton(onDismiss) { Text("Gotowe") }
                c == null -> TextButton({ start() }, enabled = source != null || fromPhone) { Text("Pokaż polecenie") }
                else -> TextButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, command), "Polecenie instalacji MagneticPlayer"))
                }) { Text("Wyślij") }
            }
        },
        dismissButton = { if (added == null) TextButton(onDismiss) { Text("Zamknij") } },
    )
}

package eu.jm.magneticplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Otwierana przez kliknięty magnet link albo „Udostępnij”: budzi serwer i wysyła mu magneta (kilka serwerów z torrentami: wybór). */
class MagnetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val magnet = extractMagnet(intent)
        setContent {
            AppTheme {
                MagnetDialog(
                    magnet = magnet,
                    onOpenApp = { tab ->
                        startActivity(Intent(this, MainActivity::class.java).putExtra("tab", tab)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun extractMagnet(i: Intent): String? {
        i.dataString?.takeIf { it.startsWith("magnet:", ignoreCase = true) }?.let { return it }
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE).find(text)?.value
    }
}

private enum class Phase { Working, Done, Error }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MagnetDialog(magnet: String?, onOpenApp: (Int) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val targets = remember { Servers.all(ctx).filter { it.hasTorrent && !it.isGuest } }
    // zapamiętany wybór, jedyny serwer z torrentami albo null = zapytaj
    var target by remember {
        mutableStateOf(Servers.magnetKey(ctx)?.takeIf { k -> targets.any { it.key == k } } ?: targets.singleOrNull()?.key)
    }
    if (target == null && targets.size > 1 && magnet != null) {
        return MagnetTargetDialog(targets, magnetName(magnet) ?: "torrent", onClose) { k, keep ->
            if (keep) Servers.setMagnetKey(ctx, k)
            target = k
        }
    }
    val api = remember(target) { Api.of(ctx, target) ?: Api(Settings(ctx), Server.NONE) }
    var phase by remember { mutableStateOf(Phase.Working) }
    var status by remember { mutableStateOf("Sprawdzam ${api.srv.name.ifEmpty { "serwer" }}…") }
    var attempt by remember { mutableIntStateOf(0) }
    var sequential by remember { mutableStateOf(api.s.sequential) }
    var autoClose by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(8) }
    var after by remember { mutableStateOf(api.s.afterDefault) }
    var tid by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val name = magnet?.let { magnetName(it) } ?: "torrent"

    LaunchedEffect(attempt) {
        if (magnet == null) {
            phase = Phase.Error; status = "W tym, co przyszło, nie ma magnet linka."; return@LaunchedEffect
        }
        if (!api.configured) {
            phase = Phase.Error; status = "Najpierw dodaj serwer z torrentami (Ustawienia → Dodaj serwer)."; return@LaunchedEffect
        }
        phase = Phase.Working
        try {
            api.ensureAwake { status = it }
            status = "Wysyłam do ${api.srv.name}…"
            tid = api.addMagnetRetrying(magnet, sequential, after) { status = it }
            phase = Phase.Done
            status = "Dodano. Pobiera się na ${api.srv.name}."
        } catch (e: ApiException) {
            phase = Phase.Error
            status = e.message ?: "Błąd"
        }
    }
    LaunchedEffect(phase, autoClose) {
        if (phase == Phase.Done && autoClose) {
            while (countdown > 0) { delay(1000); countdown-- }
            onClose()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, null) { if (phase != Phase.Working) onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.padding(24.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { autoClose = false },
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("MagneticPlayer → ${api.srv.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (phase) {
                        Phase.Working -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Phase.Done -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF3FA96B))
                        Phase.Error -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
                if (phase == Phase.Done && tid != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Gdy się pobierze:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to "nic", "stop" to "zatrzymaj", "suspend" to "uśpij serwer")
                            .forEach { (k, label) ->
                                FilterChip(after == k, {
                                    autoClose = false
                                    after = k
                                    scope.launch(Dispatchers.IO) { runCatching { api.setAfter(tid!!, k) } }
                                }, { Text(label) })
                            }
                    }
                }
                if (phase == Phase.Error && magnet != null && api.configured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sequential, { sequential = it })
                        Text("pobieranie sekwencyjne (do oglądania w trakcie)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (phase) {
                        Phase.Working -> TextButton(onClose) { Text("Anuluj") }
                        Phase.Done -> {
                            TextButton({ onOpenApp(TAB_TORRENTS) }) { Text("Pokaż") }
                            TextButton(onClose) { Text(if (autoClose) "OK ($countdown)" else "OK") }
                        }
                        Phase.Error -> {
                            if (!api.configured) TextButton({ onOpenApp(TAB_SETTINGS) }) { Text("Ustawienia") }
                            else if (magnet != null) TextButton({ attempt++ }) { Text("Ponów") }
                            TextButton(onClose) { Text("Zamknij") }
                        }
                    }
                }
            }
        }
    }
}

/** Kilka serwerów pobiera torrenty: na który wysłać (z „zapamiętaj”, do zmiany w Ustawieniach). */
@Composable
private fun MagnetTargetDialog(targets: List<Server>, name: String, onClose: () -> Unit, onPick: (String, Boolean) -> Unit) {
    var keep by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Gdzie pobrać?") },
        text = {
            Column {
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                targets.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(s.key, keep) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(s.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.clickable { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it })
                    Text("zapamiętaj (zmienisz w Ustawieniach)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Anuluj") } },
    )
}

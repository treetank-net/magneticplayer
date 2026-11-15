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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Subtitles
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Przyciski dla filmu: telefon, komputer, a w menu VLC i napisy. */
@Composable
fun MediaActions(api: Api, ref: MediaRef, snack: SnackbarHostState, onPlayingOnPc: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var subsDialog by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton({ ctx.startActivity(PlayerActivity.intent(ctx, ref)) }) {
            Icon(Icons.Filled.Smartphone, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Telefon")
        }
        FilledTonalButton({
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.playerOpen(ref) }
                    onPlayingOnPc()
                } catch (e: ApiException) {
                    snack.showSnackbar(e.message ?: "Błąd")
                }
            }
        }) {
            Icon(Icons.Filled.Computer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Komputer")
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
            }
        }
    }
    if (subsDialog) SubsDialog(api, ref) { subsDialog = false }
}

/** Napisy: te, które już leżą obok filmu, + wyszukiwanie przez subliminal na komputerze (po hashu i po nazwie). */
@Composable
fun SubsDialog(api: Api, ref: MediaRef, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var have by remember { mutableStateOf<List<SubFile>?>(null) }
    var results by remember { mutableStateOf<List<SubResult>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ref) {
        have = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(emptyList()) }
        try {
            val (q, r) = withContext(Dispatchers.IO) { api.subsSearch(ref) }
            query = q; results = r
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
                Spacer(Modifier.padding(4.dp))
                have?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("Już są: " + list.joinToString { it.name.takeLast(30) }, style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3FA96B))
                    Spacer(Modifier.padding(4.dp))
                }
                done?.let {
                    Text("Pobrano: $it — gra na komputerze? Włączyły się same. W telefonie: otwórz film jeszcze raz.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FA96B))
                    Spacer(Modifier.padding(4.dp))
                }
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    results == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Szukam (BSPlayer, Podnapisi, Addic7ed, NapiProjekt…)")
                    }
                    results!!.isEmpty() -> Text("Nic nie znaleziono dla „$query” (${api.s.subLanguages}).")
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(results!!, key = { it.key }) { r ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                                busy = r.key
                                scope.launch {
                                    try {
                                        done = withContext(Dispatchers.IO) { api.subsDownload(ref, r) }
                                        have = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrNull() }
                                    } catch (e: ApiException) {
                                        error = e.message
                                    } finally {
                                        busy = null
                                    }
                                }
                            }.padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.lang.uppercase(), style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    if (r.hashMatch) {
                                        Icon(Icons.Filled.CheckCircle, "pasuje do pliku", Modifier.size(16.dp), tint = Color(0xFF3FA96B))
                                        Text(" pasuje do pliku", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3FA96B))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (busy == r.key) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text(r.provider, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(r.release, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val tags = listOfNotNull(if (r.hi) "dla niesłyszących" else null)
                                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Zamknij") } },
    )
}

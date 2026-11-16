package eu.jm.magneticplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wiersz listy: wpis + ile faktycznie leży na dysku. */
private data class OfflineRow(val item: OfflineItem, val cached: Long, val fromStart: Long)

/** „Na telefonie”: pobrane filmy i bufor z oglądania. Działa bez sieci. */
@Composable
fun OfflineTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dl by DownloadService.status.collectAsState()
    var rows by remember { mutableStateOf<List<OfflineRow>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<OfflineItem?>(null) }
    LaunchedEffect(refresh) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                val cache = MediaCache.get(ctx)
                OfflineStore.all(ctx).map { i ->
                    val size = i.size.takeIf { it > 0 } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
                    OfflineRow(i,
                        runCatching { cache.getCachedBytes(i.key, 0, size) }.getOrDefault(0),
                        runCatching { cache.getCachedLength(i.key, 0, size) }.getOrDefault(0).coerceAtLeast(0))
                }.filter { it.item.pinned || it.cached > 0 }
            }
            delay(2_000)
        }
    }
    val list = rows ?: return
    if (list.isEmpty()) return Hint("Nic nie ma na telefonie. Żeby obejrzeć film bez sieci (w drodze, poza domem), " +
        "w Bibliotece albo przy torrencie otwórz menu ⋮ filmu → „Pobierz na telefon”.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.item.key }) { r ->
            OfflineCard(r, dl[r.item.key],
                onPlay = { ctx.startActivity(PlayerActivity.intent(ctx, r.item.ref)) },
                onDownload = { DownloadService.start(ctx, r.item.ref); refresh++ },
                onPolicy = { p ->
                    scope.launch(Dispatchers.IO) { OfflineStore.update(ctx, r.item.key) { it.copy(policy = p, created = System.currentTimeMillis()) } }
                    refresh++
                },
                onDelete = { confirmDelete = r.item })
        }
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Usunąć z telefonu?") },
            text = { Text("${item.title}\n\nNa komputerze zostaje.") },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { MediaCache.remove(ctx, item.key) }; refresh++ }
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun OfflineCard(
    r: OfflineRow, dlStatus: String?,
    onPlay: () -> Unit, onDownload: () -> Unit, onPolicy: (String) -> Unit, onDelete: () -> Unit,
) {
    val i = r.item
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(i.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val frac = if (i.size > 0) (r.cached.toDouble() / i.size).coerceIn(0.0, 1.0) else 0.0
            val done = i.complete || (i.size > 0 && r.cached >= i.size)
            val line = when {
                dlStatus != null -> dlStatus
                done -> "Cały film na telefonie · ${humanBytes(i.size)}"
                i.error != null -> "${i.error} · ${(frac * 100).toInt()}%"
                i.want -> "W kolejce do pobrania"
                i.size > 0 -> "Na telefonie ${(frac * 100).toInt()}% · ${humanBytes(r.cached)} z ${humanBytes(i.size)}"
                else -> humanBytes(r.cached)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done && i.size > 0) LinearProgressIndicator({ frac.toFloat() }, Modifier.fillMaxWidth())
            // ile da się obejrzeć od początku bez sieci (szacunek ze średniego bitrate)
            if (i.durationMs > 0 && i.size > 0 && !done && r.fromStart > 0) {
                Text("Bez sieci od początku: do ${clock(i.durationMs / 1000.0 * r.fromStart / i.size)} z ${clock(i.durationMs / 1000.0)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onPlay) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Odtwórz")
                }
                if (!done && dlStatus == null && !i.want) OutlinedButton(onDownload) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (i.pinned) "Wznów" else "Pobierz całość")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onDelete) { Icon(Icons.Filled.Delete, "usuń z telefonu") }
            }
            if (i.pinned) Box {
                TextButton({ menu = true }) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Kasowanie: ${retentionLabel(i.policy)}" + daysLeft(i))
                }
                DropdownMenu(menu, { menu = false }) {
                    RETENTION.forEach { (p, label) -> DropdownMenuItem({ Text(label) }, { menu = false; onPolicy(p) }) }
                }
            } else {
                Text("Bufor z oglądania: zniknie po obejrzeniu albo po 2 dniach bez oglądania. „Pobierz całość” zatrzyma go na dłużej.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Dla zasady „po N dniach”: ile zostało (liczone od pobrania albo zmiany zasady). */
private fun daysLeft(i: OfflineItem): String {
    val days = i.policy.removePrefix("days:").toLongOrNull() ?: return ""
    val leftH = (i.created + days * 24 * 3600_000L - System.currentTimeMillis()) / 3600_000L
    return when {
        leftH >= 48 -> " (zostało ${leftH / 24} dni)"
        leftH > 0 -> " (zostało $leftH h)"
        else -> " (przy najbliższym starcie apki)"
    }
}

package net.treetank.magneticplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * "Download to phone" for many episodes at once from a series' screen: the season shown, from the episode it continues
 * at to the end, the whole series, or the extras. Episodes already on the phone (or downloading) are left out. The
 * retention is picked for the whole batch: a season kept "manually only" does not vanish episode by episode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesDownloadDialog(srv: String, root: Int, list: List<Episode>, extras: List<Episode>, cont: Int, season: Int?,
                         onDismiss: () -> Unit, onStarted: (Int) -> Unit) {
    val ctx = LocalContext.current
    fun missing(eps: List<Episode>) = eps.filter { e ->
        OfflineStore.get(ctx, MediaRef.InLibrary(root, e.path, srv).key())?.let { it.pinned && (it.complete || it.want) } != true
    }
    val seasonLabel = season?.let { stringResource(R.string.cat_season_n, it) } ?: stringResource(R.string.cat_season_other)
    val options = remember(list, extras, cont, season) {
        listOfNotNull(
            "season" to list.filter { it.season == season },
            if (cont > 0) "rest" to list.drop(cont) else null,
            "all" to list,
            if (extras.isNotEmpty()) "extras" to extras else null,
        ).map { (k, eps) -> Triple(k, eps, missing(eps)) }.filter { it.second.isNotEmpty() }
    }
    var pick by remember { mutableStateOf(options.firstOrNull { it.third.isNotEmpty() }?.first ?: "season") }
    var policy by remember { mutableStateOf("manual") }
    val free = remember { runCatching { ctx.noBackupFilesDir.usableSpace }.getOrDefault(0L) }
    val chosen = options.firstOrNull { it.first == pick }?.third.orEmpty()
    val bytes = chosen.sumOf { it.size }
    AlertDialog(onDismiss, title = { Text(stringResource(R.string.ser_dl_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (k, eps, miss) ->
                    val label = when (k) {
                        "season" -> seasonLabel
                        "rest" -> stringResource(R.string.ser_dl_rest, list[cont].label.ifEmpty { (cont + 1).toString() })
                        "all" -> stringResource(R.string.ser_dl_all)
                        else -> stringResource(R.string.ser_dl_extras)
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = miss.isNotEmpty()) { pick = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(pick == k, { pick = k }, enabled = miss.isNotEmpty())
                        Column {
                            Text(label)
                            Text(if (miss.isEmpty()) stringResource(R.string.ser_dl_all_there)
                                else pluralStringResource(R.plurals.ser_dl_count, miss.size, miss.size, humanBytes(miss.sumOf { it.size })) +
                                    if (miss.size < eps.size) " · " + stringResource(R.string.ser_dl_some_there, eps.size - miss.size) else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ser_dl_delete), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RETENTION.forEach { (k, res) -> FilterChip(policy == k, { policy = k }, { Text(stringResource(res)) }) }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ser_dl_free, humanBytes(free)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bytes > free) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                chosen.forEach { DownloadService.start(ctx, MediaRef.InLibrary(root, it.path, srv), policy) }
                onStarted(chosen.size)
            }, enabled = chosen.isNotEmpty() && bytes <= free) { Text(stringResource(R.string.ser_dl_go)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

/** The phone's copy of an episode: "on the phone" ✓ or downloading (for the episode list). */
fun offlineMark(ctx: android.content.Context, ref: MediaRef): String? = OfflineStore.get(ctx, ref.key())?.takeIf { it.pinned }?.let {
    if (it.complete) "complete" else if (it.want) "downloading" else null
}

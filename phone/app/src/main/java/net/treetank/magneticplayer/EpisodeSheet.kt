package net.treetank.magneticplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The episode picker inside the phone player (plan G2): the season of the episode playing (chips for the other seasons),
 * each row "E3 · name" with what this phone knows of it: playing now, watched (or how far), on the phone. A tap picks
 * the episode; the player switches to it without going back to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSheet(srv: String, root: Int, eps: List<Episode>, current: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val seasons = remember(eps) { eps.map { it.season }.distinct() }
    val cur = eps.firstOrNull { it.path == current }
    var season by remember { mutableStateOf(cur?.season ?: seasons.firstOrNull()) }
    val shown = eps.withIndex().filter { it.value.season == season }
    val list = rememberLazyListState()
    LaunchedEffect(season) {
        val at = shown.indexOfFirst { it.value.path == current }
        if (at > 0) list.scrollToItem((at - 1).coerceAtLeast(0))
    }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(stringResource(R.string.player2_episodes), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium)
            if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it ?: -1 }) { s ->
                    FilterChip(s == season, { season = s }, {
                        Text(s?.let { stringResource(R.string.cat_season_n, it) } ?: stringResource(R.string.cat_season_other))
                    })
                }
            }
            LazyColumn(Modifier.heightIn(max = maxH), state = list) {
                items(shown, key = { it.value.path }) { (i, e) ->
                    EpisodePickRow(srv, root, e, e.path == current, settings) { onPick(i) }
                }
            }
        }
    }
}

@Composable
private fun EpisodePickRow(srv: String, root: Int, e: Episode, now: Boolean, settings: Settings, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val ref = MediaRef.InLibrary(root, e.path, srv)
    val k = ref.key()
    val pos = remember(k) { settings.position(k) }
    val watched = remember(k) { settings.watchedAt(k) > 0 && pos <= 0 }
    val total = remember(k) { OfflineStore.get(ctx, k)?.durationMs ?: 0L }
    val mark = remember(k) { offlineMark(ctx, ref, e.size) }
    val part = remember(k, mark) { if (mark == null) offlinePart(ctx, ref, e.size) else 0 }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .background(if (now) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (watched && !now) Icons.Filled.Check else Icons.Filled.PlayArrow, null, Modifier.size(20.dp),
            tint = if (now) MaterialTheme.colorScheme.primary else muted.copy(alpha = 0.6f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(listOf(e.episode?.let { "E$it" } ?: e.label, e.shortName).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (watched && !now) muted else Color.Unspecified)
            val line = listOfNotNull(
                if (now) stringResource(R.string.player2_now_playing) else null,
                when {
                    now -> null
                    watched -> stringResource(R.string.player2_watched)
                    pos > 0 && total > 0 -> stringResource(R.string.player2_part_watched, (pos * 100 / total).toInt().coerceIn(1, 99))
                    else -> null
                },
            )
            if (line.isNotEmpty()) Text(line.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = if (now) MaterialTheme.colorScheme.primary else muted)
            if (mark != null || part > 0) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mark == "downloading") Icons.Filled.Downloading else Icons.Filled.DownloadDone, null,
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(when {
                    mark == "complete" -> stringResource(R.string.ser_dl_on_phone)
                    mark == "downloading" -> stringResource(R.string.ser_dl_downloading)
                    else -> stringResource(R.string.ser_dl_on_phone_part, part)
                }, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

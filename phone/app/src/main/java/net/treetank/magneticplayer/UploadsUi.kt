package net.treetank.magneticplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "Sent from phones" in the Downloads tab (agent 1.8+, `/api/uploads`): files a phone uploaded wait in the downloads
// folder until somebody puts them away into the library (Move / Other folder… / Keep here).

/** How many uploads wait for a decision on the active server (badge on the Downloads tab). */
object UploadsBadge {
    val count = mutableIntStateOf(0)
}

/** Uploads of the active server, polled while the tab is open; [bump] forces a reload after an action. */
class UploadsState(val data: MutableState<PhoneUploads?>, val bump: () -> Unit)

@Composable
fun rememberUploads(api: Api, online: Boolean, enabled: Boolean): UploadsState {
    val data = remember(api.srv.key) { mutableStateOf<PhoneUploads?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(api.srv.key, online, enabled, tick) {
        while (online && enabled) {
            runCatching { withContext(Dispatchers.IO) { api.uploads() } }.onSuccess {
                data.value = it
                UploadsBadge.count.intValue = it.new
            }
            delay(if (data.value?.items?.any { it.state == "moving" } == true) 2000 else 8000)
        }
    }
    return UploadsState(data) { tick++ }
}

/** "To put away" (new and moving) at the top, "Put away this week" at the bottom ([recent]). */
fun LazyListScope.uploadSections(api: Api, st: UploadsState, snack: SnackbarHostState, recent: Boolean) {
    val items = st.data.value?.items.orEmpty()
    val weekAgo = System.currentTimeMillis() / 1000.0 - 7 * 86400
    if (!recent) {
        val waiting = items.filter { it.state == "new" || it.state == "moving" }
        if (waiting.isEmpty()) return
        item(key = "up/head") { UpHeader(stringResource(R.string.up_to_put_away), waiting.count { it.state == "new" }) }
        items(waiting, key = { "up/" + it.id }) { u -> UploadCard(api, u, snack, st.bump) }
    } else {
        val done = items.filter { (it.state == "moved" || it.state == "kept") && (it.movedAt ?: it.at) >= weekAgo }
        if (done.isEmpty()) return
        item(key = "up/done") { UpHeader(stringResource(R.string.up_this_week), null) }
        items(done, key = { "upd/" + it.id }) { u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = OK_GREEN)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (u.state == "kept") stringResource(R.string.up_kept) else stringResource(R.string.up_moved_to, u.to ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UpHeader(text: String, n: Int?) {
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        if (n != null && n > 0) Text("  $n", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UploadCard(api: Api, u: UploadItem, snack: SnackbarHostState, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pick = u.pick?.let { u.targets.getOrNull(it) }
    fun run(block: () -> Unit) {
        busy = true
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { block() }.exceptionOrNull() }
            busy = false
            if (err != null) snack.showSnackbar(err.message ?: ctx.getString(R.string.media_error))
            onChanged()
        }
    }
    fun move(t: UpTarget) = run { api.uploadMove(u, t, u.guessTag == null || u.guessTag !in t.tags) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(u.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(listOfNotNull(u.device ?: stringResource(R.string.up_unknown_device), agoText(u.at), humanBytes(u.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                u.state == "moving" -> {
                    Text(stringResource(R.string.up_moving, u.to ?: ""), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> {
                    u.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val what = u.guessTitle?.let { t -> t + (u.guessYear?.let { " ($it)" } ?: "") }
                    if (pick != null) Text(
                        (what?.let { stringResource(R.string.up_recognised, it) + " " } ?: "") +
                            stringResource(R.string.up_suggested, pick.name + (if (pick.sub.isNotEmpty()) " / ${pick.sub}" else "")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(R.string.up_no_suggestion), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pick != null) Button({ move(pick) }, enabled = !busy) { Text(stringResource(R.string.up_move)) }
                        OutlinedButton({ picking = true }, enabled = !busy && u.targets.isNotEmpty()) { Text(stringResource(R.string.up_other_folder)) }
                        TextButton({ run { api.uploadKeep(u.id) } }, enabled = !busy) { Text(stringResource(R.string.up_keep)) }
                    }
                }
            }
        }
    }
    if (picking) {
        val dl = u.targets.map { it.asDl() }
        TargetPicker(dl, u.targets.indexOfFirst { it.here }.takeIf { it >= 0 }?.let { dl[it] }, { picking = false }) { t ->
            picking = false
            u.targets.getOrNull(dl.indexOf(t))?.let { move(it) }
        }
    }
}

package net.treetank.magneticplayer

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat

// The built-in "Internet Archive" search site (agent 1.19+, `features.archive_search`): public-domain and Creative
// Commons films from archive.org, searched by the agent (`GET /api/archive/search`). A result goes to MagnetActivity like
// a magnet (same card: server, recognised folder, "Change", after download), but is added by its .torrent address:
// archive.org serves through web seeds listed only in the .torrent, so a magnet would never start.

/** One archive.org item: what the user sees and what adding it needs. */
data class ArchiveHit(
    val id: String, val title: String, val year: Int?, val size: Long, val downloads: Int,
    /** "public_domain", "cc", "other" or null (no licence stated) */
    val licence: String?, val licenceCode: String?, val page: String, val torrentUrl: String,
)

/** What MagnetActivity adds instead of a magnet: the .torrent address and the title and year the user saw. */
data class ArchivePick(val torrentUrl: String, val title: String, val year: Int?) {
    /** "Night of the Living Dead (1968)": the name the agent gives the folder and the file. */
    val label get() = title + (year?.takeIf { y -> y.toString() !in title }?.let { " ($it)" } ?: "")

    fun toIntent(ctx: Context, srv: String?): Intent = Intent(ctx, MagnetActivity::class.java)
        .putExtra(EXTRA_URL, torrentUrl).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_YEAR, year ?: 0)
        .putExtra(EXTRA_SERVER, srv)

    companion object {
        const val EXTRA_URL = "archive_torrent_url"
        const val EXTRA_TITLE = "archive_title"
        const val EXTRA_YEAR = "archive_year"
        const val EXTRA_SERVER = "archive_server"

        fun from(i: Intent): ArchivePick? {
            val url = i.getStringExtra(EXTRA_URL) ?: return null
            if (!url.startsWith("https://archive.org/download/")) return null
            return ArchivePick(url, i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getIntExtra(EXTRA_YEAR, 0).takeIf { it > 0 })
        }
    }
}

fun Api.archiveSearch(q: String, anyLicence: Boolean): List<ArchiveHit> {
    val path = "/api/archive/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + (if (anyLicence) "&any=1" else "")
    val a = JSONObject(request("GET", path, timeout = 30000)).optJSONArray("results") ?: return emptyList()
    return List(a.length()) { i ->
        val x = a.getJSONObject(i)
        ArchiveHit(x.getString("id"), x.optString("title"), x.optInt("year", 0).takeIf { it > 0 }, x.optLong("size"),
            x.optInt("downloads"), x.optStr("licence"), x.optStr("licence_code"), x.optString("page"),
            x.getString("torrent_url"))
    }
}

/** Which search is open (shown by [ArchiveSearchHost] in MainActivity): the server that searches and the phrase. */
object ArchiveSearch {
    data class Req(val srv: String, val query: String)

    val req = mutableStateOf<Req?>(null)
    fun open(srv: String, query: String) { req.value = Req(srv, query) }
}

/**
 * The search as a full-screen dialog: the phrase (prefilled with "Title Year" from a missing title's page), "Also
 * without a stated licence", results with title (year), licence, item size, downloads, "Item page" and "Download".
 * "Download" opens MagnetActivity; the list stays open for another pick.
 */
@Composable
fun ArchiveSearchHost() {
    val req = ArchiveSearch.req.value ?: return
    val ctx = LocalContext.current
    val close = { ArchiveSearch.req.value = null }
    val api = remember(req.srv) { Api.of(ctx, req.srv) } ?: run {
        LaunchedEffect(req) { close() }  // the server was removed meanwhile
        return
    }
    var q by remember(req) { mutableStateOf(req.query) }
    var any by remember(req) { mutableStateOf(false) }
    var run by remember(req) { mutableIntStateOf(if (req.query.isNotBlank()) 1 else 0) }
    var busy by remember(req) { mutableStateOf(false) }
    var error by remember(req) { mutableStateOf<String?>(null) }
    var results by remember(req) { mutableStateOf<List<ArchiveHit>?>(null) }
    LaunchedEffect(req, run, any) {
        val phrase = q.trim()
        if (run == 0 || phrase.isEmpty()) return@LaunchedEffect
        busy = true; error = null
        try {
            results = withContext(Dispatchers.IO) { api.archiveSearch(phrase, any) }
        } catch (e: ApiException) {
            error = e.message; results = null
        } finally {
            busy = false
        }
    }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(close) { Icon(Icons.Filled.Close, stringResource(R.string.media_close)) }
                    Text(stringResource(R.string.ia_title), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ia_intro), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(q, { q = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.ia_query)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run++ }),
                        trailingIcon = { IconButton({ run++ }) { Icon(Icons.Filled.Search, stringResource(R.string.ia_search)) } })
                    FilterChip(any, { any = !any; if (run == 0) run++ }, { Text(stringResource(R.string.ia_any)) })
                }
                when {
                    busy -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.ia_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                    error != null -> Column(Modifier.padding(16.dp)) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton({ run++ }) { Text(stringResource(R.string.media_retry)) }
                    }
                    results?.isEmpty() == true -> Text(stringResource(R.string.ia_none), Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                val list = results
                if (!busy && error == null && !list.isNullOrEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        items(list, key = { it.id }) { hit -> ArchiveRow(hit) {
                            ctx.startActivity(ArchivePick(hit.torrentUrl, hit.title, hit.year).toIntent(ctx, req.srv))
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(hit: ArchiveHit, onDownload: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ArchivePick(hit.torrentUrl, hit.title, hit.year).label, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (hit.licence) {
                "public_domain" -> stringResource(R.string.ia_pd)
                "cc" -> stringResource(R.string.ia_cc, hit.licenceCode.orEmpty())
                else -> stringResource(R.string.ia_no_licence)
            }, style = MaterialTheme.typography.bodySmall,
                color = if (hit.licence == "public_domain" || hit.licence == "cc") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.ia_item_size, humanBytes(hit.size)) + " · " +
                pluralStringResource(R.plurals.ia_downloads, hit.downloads, NumberFormat.getIntegerInstance().format(hit.downloads)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(hit.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.ia_page)) }
                Button(onDownload) { Text(stringResource(R.string.ia_download)) }
            }
        }
    }
}

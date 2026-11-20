package net.treetank.magneticplayer

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Stage 4 of docs/plan-ux-tasks.md: D3 Settings (sections), D4 Settings › Search engines, D5 On the phone (title page,
// ▶, ⋮ quick actions, selection), D6 the library title page reached from the phone ("On this phone" card, episode ⋮).

/** Which Settings page is open (null = the list, D3) and whose search engines D4 edits. */
object SettingsNav {
    val page = mutableStateOf<String?>(null)
    val enginesSrv = mutableStateOf<String?>(null)
    fun openEngines(key: String?) { enginesSrv.value = key; page.value = "engines" }
}

// ---------------------------------------------------------------- search engines (D4)

/**
 * Per-engine `kind` and `open` remembered on this phone: agents before stage 4 keep only `{name, url}` and drop the
 * rest. What the agent returns wins; this fills the gap.
 */
object EngineExtras {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("engine_extras", Context.MODE_PRIVATE)
    private fun k(name: String, url: String) = name.trim() + "\n" + url.trim()
    fun get(ctx: Context, name: String, url: String): Pair<String, String>? =
        prefs(ctx).getString(k(name, url), null)?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun put(ctx: Context, name: String, url: String, kind: String, open: String) {
        prefs(ctx).edit().putString(k(name, url), "$kind|$open").apply()
    }
}

/** A page opened "in the app" (an engine with open = "app"); shown by [InAppBrowserHost] in MainActivity. */
object InAppBrowser {
    val url = mutableStateOf<String?>(null)
}

/** Opens an engine's address for [phrase]: in the browser, or in the in-app browser. */
fun openEngine(ctx: Context, e: SearchEngine, phrase: String, imdb: String? = null) {
    val u = e.urlForPhrase(phrase, imdb)
    if (e.open == "app") InAppBrowser.url.value = u
    else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun engineKindLabel(kind: String): String = stringResource(when (kind) {
    "video" -> R.string.st4_kind_video
    "subtitles" -> R.string.st4_kind_subtitles
    else -> R.string.st4_kind_torrents
})

/** "2 sites · torrents, subtitles" for the Settings row. */
@Composable
fun engineSummary(list: List<SearchEngine>): String =
    if (list.isEmpty()) stringResource(R.string.st4_engines_none)
    else {
        val kinds = SearchEngine.KINDS.filter { k -> list.any { it.kind == k } }.map { engineKindLabel(it) }.joinToString(", ")
        pluralStringResource(R.plurals.st4_engines_n, list.size, list.size, kinds)
    }

/**
 * D4: Settings › Search engines as a full screen. Kept on the agent ([api]'s server, shared by the household). Back
 * returns to where it was opened from (Settings or a missing title's page).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchEnginesScreen(api: Api, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SearchEngine>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(-1) }  // -1 = a new one
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("torrents") }
    var open by remember { mutableStateOf("app") }
    LaunchedEffect(Unit) {
        try { list = withContext(Dispatchers.IO) { api.searchEngines() } } catch (e: ApiException) { error = e.message; list = emptyList() }
    }
    fun clearForm() { editing = -1; name = ""; url = ""; kind = "torrents"; open = "app" }
    fun save(new: List<SearchEngine>, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            try {
                list = withContext(Dispatchers.IO) { api.setSearchEngines(new).also { runCatching { api.info() } } }
                error = null
                after()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            Text(stringResource(R.string.st4_engines), style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.st4_engines_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.st4_engines_where, api.srv.name), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val l = list
            if (l == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (l.isEmpty()) Text(stringResource(R.string.cat_searches_empty), style = MaterialTheme.typography.bodyMedium)
            else l.forEachIndexed { i, e ->
                Card(Modifier.fillMaxWidth().clickable {
                    editing = i; name = e.name; url = e.url; kind = e.kind; open = e.open
                }) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(engineKindLabel(e.kind) + " · " + stringResource(R.string.st4_engines_opens,
                                stringResource(if (e.open == "app") R.string.st4_open_app else R.string.st4_open_browser)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(e.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ save(l.filterIndexed { j, _ -> j != i }) { if (editing == i) clearForm() } }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.media_remove))
                        }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(if (editing >= 0) R.string.st4_engines_edit else R.string.st4_engines_new), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.st4_engines_name)) },
                placeholder = { Text(stringResource(R.string.cat_searches_name_hint)) })
            OutlinedTextField(url, { url = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.st4_engines_url)) },
                placeholder = { Text("https://example.org/search?q={q}") },
                isError = url.isNotBlank() && !SearchEngine.valid("x", url))
            Text(stringResource(R.string.st4_engines_rules), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.st4_engines_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.KINDS.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(engineKindLabel(k)) }) }
            }
            listOf("app" to (R.string.st4_in_app to R.string.st4_in_app_hint), "browser" to (R.string.st4_in_browser to R.string.st4_in_browser_hint))
                .forEach { (k, res) ->
                    Row(Modifier.fillMaxWidth().clickable { open = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(open == k, { open = k })
                        Column {
                            Text(stringResource(res.first))
                            Text(stringResource(res.second), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            val ok = SearchEngine.valid(name, url) && (editing >= 0 || (list?.size ?: 0) < 10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // a sample phrase from a public-domain film: what the address gives, before saving
                OutlinedButton({ openEngine(ctx, SearchEngine(name, url, kind, open), "Metropolis 1927") }, enabled = ok) {
                    Text(stringResource(R.string.st4_check))
                }
                FilledTonalButton({
                    val e = SearchEngine(name.trim(), url.trim(), kind, open)
                    val cur = list.orEmpty()
                    save(if (editing in cur.indices) cur.mapIndexed { j, x -> if (j == editing) e else x } else cur + e) { clearForm() }
                }, enabled = ok && !busy && list != null) { Text(stringResource(R.string.st4_save)) }
                if (editing >= 0) TextButton({ clearForm() }) { Text(stringResource(R.string.media_cancel)) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * D2: one button per engine (of [kinds]) for [phrase], then "Set up search engines ›" ([onSetUp] → D4). With no
 * engine only the link.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineButtons(engines: List<SearchEngine>, phrase: String, imdb: String?, onSetUp: () -> Unit,
                  kinds: Set<String> = setOf("torrents", "video")) {
    val ctx = LocalContext.current
    val shown = engines.filter { it.kind in kinds }.ifEmpty { engines.filter { it.kind != "subtitles" } }
    if (shown.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { e ->
            OutlinedButton({ openEngine(ctx, e, phrase, imdb) }) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(e.name)
            }
        }
    }
    Text(stringResource(R.string.st4_set_up_engines), Modifier.clickable(onClick = onSetUp).padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}

/**
 * The in-app browser for engines with open = "app": a tapped magnet goes to MagnetActivity (to the server, the usual
 * flow); a downloaded file goes to the phone's Download folder, where the uploads observer offers it to the server
 * as for any browser download (Uploads.kt).
 */
@Composable
fun InAppBrowserHost() {
    val url = InAppBrowser.url.value ?: return
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(url) }
    Dialog({ InAppBrowser.url.value = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(canBack) { web?.goBack() }
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ InAppBrowser.url.value = null }) { Icon(Icons.Filled.Close, stringResource(R.string.media_close)) }
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                AndroidView({ c ->
                    android.webkit.WebView(c).apply {
                        settings.javaScriptEnabled = true  // search sites need it
                        settings.domStorageEnabled = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (u.startsWith("magnet:")) {
                                    ctx.startActivity(Intent(ctx, MagnetActivity::class.java).setAction(Intent.ACTION_SEND)
                                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, u))
                                    return true
                                }
                                return !(u.startsWith("http://") || u.startsWith("https://"))
                            }
                            override fun doUpdateVisitedHistory(view: android.webkit.WebView, u: String?, isReload: Boolean) {
                                canBack = view.canGoBack(); title = u ?: title
                            }
                        }
                        setDownloadListener { u, agent, disposition, mime, _ ->
                            runCatching {
                                val file = android.webkit.URLUtil.guessFileName(u, disposition, mime)
                                val req = android.app.DownloadManager.Request(android.net.Uri.parse(u)).setMimeType(mime)
                                    .addRequestHeader("User-Agent", agent)
                                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, file)
                                (c.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                                android.widget.Toast.makeText(c, c.getString(R.string.st4_browser_download, file), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        loadUrl(url)
                        web = this
                    }
                }, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- copies on the phone (D5, D6)

object PhoneCopies {
    private fun ui(ctx: Context) = ctx.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** The delete policy for new downloads (Settings › Space for copies); one of [RETENTION]. */
    fun defaultPolicy(ctx: Context): String = ui(ctx).getString("default_policy", null)?.takeIf { p -> RETENTION.any { it.first == p } } ?: "watched"
    fun setDefaultPolicy(ctx: Context, p: String) { ui(ctx).edit().putString("default_policy", p).apply() }

    private fun inTitle(x: CatItem, r: MediaRef.InLibrary) =
        r.root == x.root && (r.path == x.path || (x.dir != null && r.path.startsWith(x.dir + "/")))

    /** The library title a video on the phone belongs to (this phone's copy of the catalog). Off the main thread. */
    fun titleOf(ctx: Context, ref: MediaRef): CatItem? {
        if (ref !is MediaRef.InLibrary) return null
        val cat = CatalogCache.cached(ctx, ref.srv) ?: return null
        return cat.items.firstOrNull { inTitle(it, ref) }
    }

    /** The copies of a title on this phone (downloaded or a buffer with something in it) and their bytes. Off the main thread. */
    fun copiesOf(ctx: Context, srv: String, x: CatItem): List<Pair<OfflineItem, Long>> =
        OfflineStore.all(ctx).mapNotNull { i ->
            val r = i.ref as? MediaRef.InLibrary ?: return@mapNotNull null
            if (r.srv != srv || !inTitle(x, r)) return@mapNotNull null
            val have = cachedBytes(ctx, i)
            if (i.pinned || have > 0) i to have else null
        }

    /** How many bytes of [i] the phone holds (MediaCache). Off the main thread. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun cachedBytes(ctx: Context, i: OfflineItem): Long {
        val size = i.size.takeIf { it > 0 } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
        return runCatching { MediaCache.get(ctx).getCachedBytes(i.key, 0, size) }.getOrDefault(0L)
    }
}

/** "They are deleted after a week, unless you keep them for good." for a set of copies. */
@Composable
fun policyText(items: List<OfflineItem>): String {
    val ctx = LocalContext.current
    val p = items.map { if (it.pinned) it.policy else "watched" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "watched"
    return if (p == "manual") stringResource(R.string.st4_policy_manual)
    else stringResource(R.string.st4_policy_text, retentionLabel(ctx, p))
}

/**
 * D6 "On this phone": how many copies and how big, the delete policy in words, "Download the next 3" (a series),
 * "Keep for good", "Delete from the phone…". Nothing when the phone holds no copy of the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnThisPhoneCard(srv: String, x: CatItem, eps: List<Episode>, cont: Int, tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val copies by produceState<List<Pair<OfflineItem, Long>>>(emptyList(), srv, x.id, tick, refresh) {
        value = withContext(Dispatchers.IO) { PhoneCopies.copiesOf(ctx, srv, x) }
    }
    var confirm by remember { mutableStateOf(false) }
    if (copies.isEmpty()) return
    val bytes = copies.sumOf { it.second }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.st4_on_this_phone), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(if (x.series) pluralStringResource(R.plurals.st4_phone_eps, copies.size, copies.size, humanBytes(bytes))
                else pluralStringResource(R.plurals.st4_phone_copies, copies.size, copies.size, humanBytes(bytes)),
                style = MaterialTheme.typography.bodyMedium)
            Text(policyText(copies.map { it.first }), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (x.series && eps.isNotEmpty()) {
                    // after the last episode on the phone (else where watching continues), the next three not there yet
                    val keys = copies.map { it.first.key }.toSet()
                    val last = eps.indexOfLast { MediaRef.InLibrary(x.root, it.path, srv).key() in keys }
                    val from = if (last >= 0) last + 1 else cont.coerceAtLeast(0)
                    val next = eps.drop(from).filter { offlineMark(ctx, MediaRef.InLibrary(x.root, it.path, srv), it.size) == null }.take(3)
                    OutlinedButton({
                        next.forEach { DownloadService.start(ctx, MediaRef.InLibrary(x.root, it.path, srv), PhoneCopies.defaultPolicy(ctx)) }
                        android.widget.Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.ser_dl_started, next.size, next.size),
                            android.widget.Toast.LENGTH_SHORT).show()
                        refresh++; onChanged()
                    }, enabled = next.isNotEmpty()) { Text(stringResource(R.string.st4_next3)) }
                }
                if (copies.any { !it.first.pinned || it.first.policy != "manual" }) OutlinedButton({
                    copies.forEach { (i, _) -> keepForGood(ctx, i.ref) }
                    refresh++; onChanged()
                }) { Text(stringResource(R.string.st4_keep)) }
                OutlinedButton({ confirm = true }) { Text(stringResource(R.string.st4_delete_phone)) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.st4_delete_all_title)) },
        text = { Text(pluralStringResource(R.plurals.st4_delete_all_text, copies.size, copies.size, humanBytes(bytes))) },
        confirmButton = {
            TextButton({
                confirm = false
                val gone = copies.map { it.first.key }
                scope.launch { withContext(Dispatchers.IO) { gone.forEach { MediaCache.remove(ctx, it) } }; refresh++; onChanged() }
            }) { Text(stringResource(R.string.media_delete)) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.media_cancel)) } },
    )
}

/** "Keep for good": a downloaded copy stops being deleted; a buffer or a video not on the phone is downloaded and kept. */
fun keepForGood(ctx: Context, ref: MediaRef) {
    val i = OfflineStore.get(ctx, ref.key())
    if (i != null && i.pinned) OfflineStore.update(ctx, i.key) { it.copy(policy = "manual") }
    else DownloadService.start(ctx, ref, "manual")
}

/**
 * Subtitle languages of each episode in [eps] (default versions next to the video), for the season summary and the
 * "search the engines" item: from the server, else from the phone's copies. null for an episode = not known yet.
 */
@Composable
fun rememberEpisodeSubs(api: Api?, srv: String, root: Int, eps: List<Episode>): Map<String, List<String>?> {
    val ctx = LocalContext.current
    val map = remember(srv, root) { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(eps.map { it.path }) {
        for (e in eps) {
            if (map.containsKey(e.path)) continue
            val ref = MediaRef.InLibrary(root, e.path, srv)
            val langs = withContext(Dispatchers.IO) {
                runCatching { api!!.subsList(ref).filter { it.default }.map { it.lang.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() } }.getOrNull()
                    ?: OfflineStore.get(ctx, ref.key())?.subs?.filter { it.default }?.map { it.lang.lowercase(Locale.ROOT) }
            }
            if (langs != null) map[e.path] = langs.distinct()
        }
    }
    return eps.associate { it.path to map[it.path] }
}

/** "subtitles: PL 2 · EN 1 · none 1" for one season. */
@Composable
fun SeasonSubsLine(subs: Map<String, List<String>?>) {
    val known = subs.values.filterNotNull()
    if (known.isEmpty()) return
    val counts = known.flatten().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val none = known.count { it.isEmpty() }
    val parts = counts.map { "${it.key.uppercase(Locale.ROOT)} ${it.value}" } +
        (if (none > 0) listOf(stringResource(R.string.st4_subs_none_short) + " $none") else emptyList())
    Text(stringResource(R.string.st4_season_subs, parts.joinToString(" · ")), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * ⋮ on a video on the phone (D5) or an episode row (D6): play from position, on a screen, download subtitles (on the
 * server and into the copy), fit subtitles, keep for good, delete from the phone (the server copy stays), search the
 * subtitle engines when there are none ([searchPhrase] with [noSubs]), details.
 */
@Composable
fun CopyMenuButton(ref: MediaRef, title: String, onPlay: () -> Unit, onScreen: (() -> Unit)?, onChanged: () -> Unit,
                   details: Boolean = true, searchPhrase: String? = null, noSubs: Boolean = false,
                   /** "Optimise…" (E2 with this file as the scope, plan stage 3); null = not offered */
                   onOptimise: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var playFrom by remember { mutableStateOf(false) }
    var subs by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var engines by remember { mutableStateOf<List<SearchEngine>>(emptyList()) }
    val api = remember(ref.srv) { Api.of(ctx, ref.srv) }
    if (menu && noSubs && searchPhrase != null && api != null) LaunchedEffect(Unit) {
        engines = withContext(Dispatchers.IO) { runCatching { api.searchEngines() }.getOrDefault(emptyList()) }.filter { it.kind == "subtitles" }
    }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.st4_more)) }
        DropdownMenu(menu, { menu = false }) {
            val item = remember(menu) { OfflineStore.get(ctx, ref.key()) }
            DropdownMenuItem({ Text(stringResource(R.string.st4_play_from)) }, { menu = false; playFrom = true })
            if (onScreen != null) DropdownMenuItem({ Text(stringResource(R.string.st4_on_screen)) }, { menu = false; onScreen() })
            if (api != null && ref is MediaRef.InLibrary) {
                DropdownMenuItem({ Text(stringResource(R.string.st4_download_subs)) }, { menu = false; subs = true })
                if (api.srv.hasSubsFit && !api.srv.isGuest) DropdownMenuItem({ Text(stringResource(R.string.st4_fit_subs)) }, {
                    menu = false
                    scope.launch { toast(ctx, fitSubs(ctx, api, ref)) }
                })
            }
            if (item?.pinned != true || item.policy != "manual") DropdownMenuItem({ Text(stringResource(R.string.st4_keep)) }, {
                menu = false; keepForGood(ctx, ref); toast(ctx, ctx.getString(R.string.st4_kept)); onChanged()
            })
            if (item != null) DropdownMenuItem({ Text(stringResource(R.string.st4_delete_phone)) }, { menu = false; delete = true })
            if (searchPhrase != null && noSubs) engines.forEach { e ->
                DropdownMenuItem({ Text(stringResource(R.string.st4_search_subs, e.name)) }, { menu = false; openEngine(ctx, e, searchPhrase) })
            }
            if (details && item != null) DropdownMenuItem({ Text(stringResource(R.string.st4_details)) }, { menu = false; info = true })
            if (onOptimise != null) DropdownMenuItem({ Text(stringResource(R.string.s35_optimise_file)) }, { menu = false; onOptimise() })
        }
    }
    if (playFrom) PlayFromDialog(ref, { playFrom = false }) { fromStart ->
        playFrom = false
        if (fromStart) Settings(ctx).setPosition(ref.key(), 0)
        onPlay()
    }
    if (subs && api != null) SubtitlesSheet(api, ref, onDismiss = { subs = false; OfflineSync.refreshLater(ctx, ref); onChanged() })
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text(stringResource(R.string.st4_delete_all_title)) },
        text = { Text(stringResource(R.string.st4_delete_phone_text, title)) },
        confirmButton = {
            TextButton({
                delete = false
                scope.launch { withContext(Dispatchers.IO) { MediaCache.remove(ctx, ref.key()) }; onChanged() }
            }) { Text(stringResource(R.string.media_delete)) }
        },
        dismissButton = { TextButton({ delete = false }) { Text(stringResource(R.string.media_cancel)) } },
    )
    if (info) {
        val i = remember { OfflineStore.get(ctx, ref.key()) }
        if (i == null) info = false else CopyDetailsDialog(i) { info = false }
    }
}

private fun toast(ctx: Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()

/**
 * Fits the subtitles next to the video to its speech (`/api/subs/fit`, agent 1.9+), each language's version in use;
 * then the phone's copy takes them. Returns the message for the user.
 */
suspend fun fitSubs(ctx: Context, api: Api, ref: MediaRef): String = withContext(Dispatchers.IO) {
    toastMain(ctx, ctx.getString(R.string.st4_fit_running))
    try {
        val files = api.subsList(ref).filter { it.default }
        if (files.isEmpty()) return@withContext ctx.getString(R.string.st4_fit_none)
        var fitted = 0; var same = 0; var bad = 0
        for (f in files) {
            val o = org.json.JSONObject(api.request("POST", "/api/subs/fit", ref.json().put("name", f.name), timeout = 300_000))
            when {
                o.optString("verdict") == "bad" || o.optString("verdict") == "error" -> bad++
                o.optBoolean("changed") -> fitted++
                else -> same++
            }
        }
        OfflineSync.refreshLater(ctx, ref)
        ctx.getString(R.string.st4_fit_result, fitted, same, bad)
    } catch (e: ApiException) {
        e.message ?: ctx.getString(R.string.media_error)
    }
}

private suspend fun toastMain(ctx: Context, msg: String) = withContext(Dispatchers.Main) { toast(ctx, msg) }

/** "Resume from 9:12" or "From the start". */
@Composable
fun PlayFromDialog(ref: MediaRef, onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val pos = remember { Settings(ctx).position(ref.key()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.st4_play_from)) },
        text = {
            Column {
                if (pos > 0) TextButton({ onPick(false) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.st4_resume_from, clock(pos / 1000.0)), Modifier.fillMaxWidth())
                }
                TextButton({ onPick(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.st4_from_start), Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } })
}

/** Details of a video on the phone: server, file, size, length, how much is here, delete policy, subtitles. */
@Composable
fun CopyDetailsDialog(i: OfflineItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val have by produceState(-1L, i.key) {
        value = withContext(Dispatchers.IO) { PhoneCopies.cachedBytes(ctx, i) }
    }
    @Composable
    fun line(label: Int, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(label), Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(i.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                line(R.string.st4_det_server, Servers.get(ctx, i.ref.srv)?.name ?: ctx.getString(R.string.media_offline_deleted_server))
                (i.ref as? MediaRef.InLibrary)?.let { line(R.string.st4_det_path, it.path) }
                if (i.size > 0) line(R.string.st4_det_size, humanBytes(i.size))
                if (i.durationMs > 0) line(R.string.st4_det_length, clock(i.durationMs / 1000.0))
                if (have >= 0) line(R.string.st4_det_on_phone, humanBytes(have) +
                    if (i.size > 0 && have < i.size) " (${(100 * have / i.size).toInt()}%)" else "")
                line(R.string.st4_det_policy, if (i.pinned) retentionLabel(ctx, i.policy) else stringResource(R.string.st4_det_buffer))
                line(R.string.st4_det_subs, i.subs.map { it.lang.uppercase(Locale.ROOT).ifEmpty { "?" } }.distinct().joinToString(", ")
                    .ifEmpty { stringResource(R.string.st4_subs_none_short) })
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_close)) } })
}

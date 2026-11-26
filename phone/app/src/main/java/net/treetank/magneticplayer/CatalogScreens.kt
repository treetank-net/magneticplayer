package net.treetank.magneticplayer

import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.LinearProgressIndicator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// The Library as films (agent 1.8+): Discover, Browse, Results, Title, Missing title, Person, What next, Want to watch.
// Mockups: Odkrywaj/Przeglądaj/Wyniki/Film/Brak/Aktor/CoDalej/Lista. Navigation inside the Library is a stack of JSON
// entries (`k` + arguments) kept as a string, so it survives rotation; Back pops it.

/** "What next" requested by the phone player after a film: server key and IMDb id (MainActivity → LibraryTab). */
object CatalogNav {
    val pending = mutableStateOf<Pair<String, String>?>(null)
}

/** Stack entries. */
object Nav {
    fun title(id: String) = JSONObject().put("k", "title").put("id", id)
    fun missing(imdb: String, why: String? = null) = JSONObject().put("k", "missing").put("imdb", imdb).put("why", why.orEmpty())
    fun person(q: String) = JSONObject().put("k", "person").put("q", q)
    fun next(imdb: String, ended: Boolean = false) = JSONObject().put("k", "next").put("imdb", imdb).put("ended", ended)
    fun results(conds: List<String>, sort: String = "rating") = JSONObject().put("k", "results").put("conds", JSONArray(conds)).put("sort", sort)
    fun wishlist() = JSONObject().put("k", "wishlist")
    /** Settings › Search engines (D4) opened from a missing title's page: Back returns there */
    fun engines() = JSONObject().put("k", "engines")
}

fun tagKey(srv: String, root: Int) = "$srv|$root"

/** What every catalog screen needs: the server, the data and navigation. */
class CatCtx(
    val api: Api, val snack: SnackbarHostState, val onPlayingOnPc: () -> Unit, val cat: CatalogData?,
    val wish: List<WishItem>?, val rootTags: Map<String, List<String>>, val push: (JSONObject) -> Unit, val pop: () -> Unit,
    val onOpenFolder: (Int, String) -> Unit, private val scope: CoroutineScope, private val setWish: (List<WishItem>) -> Unit,
    /** "All" in the switcher: the servers merged into [cat] and their Api; null = one server ([api]) */
    val all: List<String>? = null, private val apiFor: (String) -> Api? = { null },
) {
    val srv get() = api.srv.key
    /** The server an item's copy is on, and its Api (a merged catalog mixes servers). */
    fun srvOf(x: CatItem) = x.srv.ifEmpty { srv }
    fun apiOf(x: CatItem): Api = if (x.srv.isEmpty() || x.srv == srv) api else apiFor(x.srv) ?: api
    fun tagsOf(x: CatItem): List<String> = rootTags[tagKey(srvOf(x), x.root)].orEmpty()
    /** Positions on this phone matched to the catalog: of every merged server. */
    fun resumes(ctx: Context, cat: CatalogData, withDone: Boolean = false): List<Resume> =
        if (all == null) resumes(ctx, srv, cat, withDone)
        else all.flatMap { resumes(ctx, it, cat, withDone) }.sortedByDescending { it.at }.distinctBy { it.item.imdb ?: it.item.id }
    fun listed(imdb: String) = wish?.any { it.imdb == imdb } == true

    /** Adds to or removes from the shared "want to watch" list. */
    fun toggleWish(ctx: Context, imdb: String, title: String, year: Int?, genres: List<String>, why: String?) = scope.launch {
        try {
            setWish(withContext(Dispatchers.IO) {
                if (listed(imdb)) api.wishRemove(imdb) else api.wishAdd(imdb, title, year, genres, why)
            })
        } catch (e: ApiException) {
            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
        }
    }

    fun open(x: CatItem) = push(Nav.title(x.id))
}

/**
 * The Library on a server with a catalog: Discover / Browse / Folders on top, catalog screens on a stack.
 * [folders] is today's folder view (unchanged).
 */
@Composable
fun CatalogArea(api: Api, snack: SnackbarHostState, onPlayingOnPc: () -> Unit, mode: String, onMode: (String) -> Unit,
                stack: String, onStack: (String) -> Unit, onOpenFolder: (Int, String) -> Unit, offline: Boolean = false,
                all: List<String>? = null, folders: @Composable () -> Unit) {
    // covers: the server's own posters or frames from the films (agent 1.6+), drawn ones otherwise
    val ctx = LocalContext.current
    val coverApi = remember(api.srv.key) { (Servers.get(ctx, api.srv.key) ?: api.srv).let { if (it.libraryCovers) Api(api.s, it) else null } }
    // "All": every merged server's Api (covers, episodes, playing come from the server the copy is on)
    val apis = remember(all) { all.orEmpty().associateWith { Api.of(ctx, it) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCoverApi provides coverApi, LocalCoverApiFor provides { k -> apis[k] }) {
        CatalogAreaBody(api, snack, onPlayingOnPc, mode, onMode, stack, onStack, onOpenFolder, offline, all, { k -> apis[k] }, folders)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogAreaBody(api: Api, snack: SnackbarHostState, onPlayingOnPc: () -> Unit, mode: String, onMode: (String) -> Unit,
                stack: String, onStack: (String) -> Unit, onOpenFolder: (Int, String) -> Unit, offline: Boolean,
                all: List<String>?, apiFor: (String) -> Api?, folders: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val srvKey = api.srv.key
    val ver by CatalogCache.version
    // "All": the kept catalogs of every server merged (a server that is asleep shows what it had at the last sync)
    fun merged(get: (String) -> CatalogData?): CatalogData? =
        if (all == null) get(srvKey) else all.mapNotNull { k -> get(k)?.let { k to it } }.takeIf { it.isNotEmpty() }?.let(::mergeCatalogs)
    val catState = produceState(remember(all) { merged { CatalogCache.peek(it) } }, srvKey, all, ver) {
        value = withContext(Dispatchers.IO) { merged { CatalogCache.cached(ctx, it) } }
    }
    val cat = catState.value
    var error by remember { mutableStateOf<String?>(null) }
    // D1: one query for every Library tab, kept while a title opened from the results is on top
    val searchQ = rememberSaveable { mutableStateOf("") }
    LaunchedEffect(srvKey, all) {
        while (true) {
            MainVisible.await()  // nothing while the app is off screen (a service may keep the process alive)
            val c = try {
                withContext(Dispatchers.IO) {
                    all.orEmpty().filter { it != srvKey }.forEach { k -> apiFor(k)?.let { runCatching { CatalogCache.refresh(ctx, it) } } }
                    CatalogCache.refresh(ctx, api)
                }.also { error = null }
            } catch (e: ApiException) {
                error = e.message; null
            }
            delay(if (c?.scanning == true) 10_000 else 60_000)  // while the agent walks the library, more often
        }
    }
    var wish by remember(srvKey) { mutableStateOf<List<WishItem>?>(null) }
    // shared with the other phones: re-read now and then, and whenever the screen changes
    LaunchedEffect(srvKey, stack) {
        while (true) {
            MainVisible.await()  // nothing while the app is off screen (a service may keep the process alive)
            withContext(Dispatchers.IO) { runCatching { api.wishlist() }.getOrNull() }?.let { wish = it }
            delay(30_000)
        }
    }
    var rootTags by remember(srvKey) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(srvKey) {
        rootTags = withContext(Dispatchers.IO) {
            (all ?: listOf(srvKey)).flatMap { k ->
                val a = if (k == srvKey) api else Api.of(ctx, k)
                runCatching { a!!.libraryRoots().map { tagKey(k, it.index) to it.tags } }.getOrDefault(emptyList())
            }.toMap()
        }
    }
    val entries = remember(stack) { runCatching { JSONArray(stack) }.getOrDefault(JSONArray()).let { a -> List(a.length()) { a.getJSONObject(it) } } }
    fun push(o: JSONObject) = onStack(JSONArray(entries.map { it } + o).toString())
    fun pop() = onStack(JSONArray(entries.dropLast(1)).toString())
    // "What next" from the phone player
    val pending = CatalogNav.pending.value
    LaunchedEffect(pending) {
        if (pending != null && pending.first == srvKey) {
            CatalogNav.pending.value = null
            push(Nav.next(pending.second, ended = true))
        }
    }
    val c = CatCtx(api, snack, onPlayingOnPc, cat, wish, rootTags, ::push, ::pop, onOpenFolder, scope, { wish = it }, all, apiFor)
    val top = entries.lastOrNull()
    if (top != null) {
        BackHandler { pop() }
        androidx.compose.runtime.key(entries.size) { CatalogStackScreen(c, top) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (offline) {
            val at = CatalogCache.syncedAt(srvKey)
            Text(if (at > 0) stringResource(R.string.cat_offline_from, agoText(at / 1000.0)) else stringResource(R.string.cat_offline),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        val modes = listOf("discover" to R.string.cat_mode_discover, "browse" to R.string.cat_mode_browse, "folders" to R.string.cat_mode_folders)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(mode == m, { onMode(m) }, SegmentedButtonDefaults.itemShape(i, modes.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        when (mode) {
            "folders" -> androidx.compose.runtime.CompositionLocalProvider(LocalCatSearch provides CatSearch(c, searchQ)) { folders() }
            else -> when {
                cat == null -> Hint(error ?: stringResource(R.string.media_loading))
                cat.items.isEmpty() -> Hint(if (cat.scanning) stringResource(R.string.cat_building, cat.done, cat.total)
                    else stringResource(R.string.cat_empty))
                else -> Column(Modifier.fillMaxSize()) {
                    LibrarySearchField(searchQ.value, { searchQ.value = it })
                    val q = searchQ.value.trim()
                    when {
                        q.length >= 2 -> CatalogSearchResults(c, cat, q)
                        mode == "browse" -> BrowseScreen(c, cat)
                        else -> DiscoverScreen(c, cat)
                    }
                }
            }
        }
    }
}

/** The screen on top of the Library's stack. [focusPath]/[fromPhone]: a title page opened from "On the phone" (D6). */
@Composable
private fun CatalogStackScreen(c: CatCtx, top: JSONObject, focusPath: String? = null, fromPhone: Boolean = false) {
    val cat = c.cat
    when (top.optString("k")) {
        "title" -> cat?.byId(top.optString("id"))?.let { TitleScreen(c, it, focusPath, fromPhone) }
            ?: if (cat == null) Hint(stringResource(R.string.media_loading)) else Hint(stringResource(R.string.cat_gone))
        "missing" -> MissingScreen(c, top.optString("imdb"), top.optString("why").ifEmpty { null })
        "person" -> PersonScreen(c, top.optString("q"))
        "next" -> NextScreen(c, top.optString("imdb"), top.optBoolean("ended"))
        "results" -> ResultsScreen(c, top.optJSONArray("conds")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            top.optString("sort", "rating"))
        "wishlist" -> WishlistScreen(c)
        "engines" -> SearchEnginesScreen(c.api, c.pop)
        else -> c.pop()
    }
}

/**
 * D6: the Library's title page opened from "On the phone": the same [TitleScreen], scrolled to the tapped episode
 * ([focusPath]); its own small stack (a person, similar titles…), and Back from the title returns to the list
 * ([onBack]). Works without the server from this phone's copy of the catalog and of the series' episode list.
 */
@Composable
fun PhoneTitlePage(srvKey: String, itemId: String, focusPath: String?, snack: SnackbarHostState, onPlayingOnPc: () -> Unit,
                   onOpenFolder: (String, Int, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val api = remember(srvKey) { Api.of(ctx, srvKey) }
    if (api == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val cat by produceState(CatalogCache.peek(srvKey), srvKey) { value = withContext(Dispatchers.IO) { CatalogCache.cached(ctx, srvKey) } }
    var stack by rememberSaveable(srvKey, itemId) { mutableStateOf(JSONArray().put(Nav.title(itemId)).toString()) }
    var wish by remember(srvKey) { mutableStateOf<List<WishItem>?>(null) }
    LaunchedEffect(srvKey) { wish = withContext(Dispatchers.IO) { runCatching { api.wishlist() }.getOrNull() } }
    var rootTags by remember(srvKey) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(srvKey) {
        rootTags = withContext(Dispatchers.IO) {
            runCatching { api.libraryRoots().associate { tagKey(srvKey, it.index) to it.tags } }.getOrDefault(emptyMap())
        }
    }
    val entries = remember(stack) { runCatching { JSONArray(stack) }.getOrDefault(JSONArray()).let { a -> List(a.length()) { a.getJSONObject(it) } } }
    fun push(o: JSONObject) { stack = JSONArray(entries + o).toString() }
    fun pop() { if (entries.size <= 1) onBack() else stack = JSONArray(entries.dropLast(1)).toString() }
    val coverApi = remember(srvKey) { (Servers.get(ctx, srvKey) ?: api.srv).let { if (it.libraryCovers) Api(api.s, it) else null } }
    val c = CatCtx(api, snack, onPlayingOnPc, cat, wish, rootTags, ::push, ::pop, { root, path -> onOpenFolder(srvKey, root, path) },
        scope, { wish = it })
    val top = entries.lastOrNull() ?: return onBack()
    BackHandler { pop() }
    androidx.compose.runtime.CompositionLocalProvider(LocalCoverApi provides coverApi) {
        androidx.compose.runtime.key(entries.size) {
            CatalogStackScreen(c, top, focusPath.takeIf { entries.size == 1 }, fromPhone = entries.size == 1)
        }
    }
}

// ---------------------------------------------------------------- Discover

@Composable
private fun DiscoverScreen(c: CatCtx, cat: CatalogData) {
    val ctx = LocalContext.current
    val back = rememberResumedTick()
    val resumesAll = remember(cat, back) { c.resumes(ctx, cat) }
    // series: the episode they continue at ("Next: S1 E5"); a series finished to its last episode leaves the row
    val nextEp by produceState<Map<String, Episode?>>(emptyMap(), resumesAll) {
        value = withContext(Dispatchers.IO) {
            resumesAll.filter { it.item.series && it.item.dir != null && c.apiOf(it.item).srv.libraryEpisodes }.associate { r ->
                r.item.id to runCatching { c.apiOf(r.item).episodes(r.item.root, r.item.dir!!).episodes }.getOrNull()?.let { eps ->
                    val i = continueIndex(Settings(ctx), c.srvOf(r.item), r.item.root, eps)
                    val last = eps.indexOfFirst { it.path == r.path }
                    if (r.posMs <= 0 && last == eps.size - 1) null else eps.getOrNull(i)
                }
            }
        }
    }
    val resumes = resumesAll.filter { !(it.item.series && it.item.id in nextEp && nextEp[it.item.id] == null) }
    val movies = cat.items.count { !it.series }
    val series = cat.items.size - movies
    val now = System.currentTimeMillis() / 1000.0
    val genres = remember(cat) {
        cat.items.flatMap { it.genres }.groupingBy { it }.eachCount().filter { it.value >= 3 }.entries.sortedByDescending { it.value }
            .take(3).map { it.key }
    }
    val actor = remember(cat) {
        cat.items.flatMap { it.cast.take(5) }.groupingBy { it }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }
    val kids = remember(cat) { cat.items.filter { it.fitsAge(7) } }
    // "Similar to": a title watched lately (also a film finished; at random among the last ten, CatalogSuggest.kt) and
    // what on the disks is like it, minus what was watched
    val seen = remember(cat, back) { c.resumes(ctx, cat, withDone = true) }
    val seenIds = remember(seen) { seen.map { it.item.id }.toSet() }
    val seenImdb = remember(seen) { seen.mapNotNull { it.item.imdb }.toSet() }  // other copies of a watched title too
    fun likeIt(x: CatItem) = similar(ctx, cat, x).filter { it.first.id !in seenIds && it.first.imdb !in seenImdb }
    val seeds = remember(cat, seen) { discoverSeeds(seen.map { it.item }, cat) { likeIt(it).size >= 3 } }
    val because = remember(seeds) { seeds.seen?.let { x -> x to likeIt(x) }?.takeIf { it.second.size >= 3 } }
    // G4: titles that aren't on the disks, after another title watched lately and after a good one on the disks
    val suggestSeen = rememberSuggestMissing(c, cat, seeds.seenMissing)
    val suggestDisk = rememberSuggestMissing(c, cat, seeds.disk)
    // the hero: a few slides of different kinds ("New on your disks", "Because you watched…", "Top rated", a random
    // pick), the first one picked at random each time the screen is built, turning by itself like a shop window
    val heroNewLabel = stringResource(R.string.cat_hero_new)
    val heroTopLabel = stringResource(R.string.cat_hero_top)
    val heroRandomLabel = stringResource(R.string.cat_hero_random)
    val heroBecause = because?.let { stringResource(R.string.cat_row_because, it.first.title) }
    val slides = remember(cat, because) {
        val out = mutableListOf<Pair<String, CatItem>>()
        fun add(label: String, x: CatItem?) { if (x != null && out.none { it.second.id == x.id }) out += label to x }
        cat.items.filter { it.rating != null && now - it.added < 30 * 86400 }.sortedByDescending { it.rating }.take(2)
            .forEach { add(heroNewLabel, it) }
        add(heroBecause ?: "", because?.second?.firstOrNull()?.first?.takeIf { heroBecause != null })
        add(heroTopLabel, cat.items.filter { (it.votes ?: 0) >= 1000 && out.none { o -> o.second.id == it.id } }.maxByOrNull { it.rating ?: 0.0 })
        add(heroRandomLabel, cat.items.filter { (it.rating ?: 0.0) >= 6.5 && out.none { o -> o.second.id == it.id } }.randomOrNull())
        if (out.isEmpty()) add(heroNewLabel, cat.items.first())
        out.shuffled()
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(listOfNotNull(pluralStringResource(R.plurals.cat_films, movies, movies),
                if (series > 0) pluralStringResource(R.plurals.cat_series, series, series) else null,
                if (cat.scanning) stringResource(R.string.cat_scanning, cat.done, cat.total) else null).joinToString(" · "),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            HeroCarousel(c, slides)
        }
        item {
            val moods = listOf(
                stringResource(R.string.cat_mood_kids) to { c.push(Nav.results(listOf("age:7"))) },
                stringResource(R.string.cat_mood_short) to { c.push(Nav.results(listOf("short"))) },
                stringResource(R.string.cat_mood_pl_audio) to { c.push(Nav.results(listOf("lang:plaudio"))) },
                stringResource(R.string.cat_mood_classics) to { c.push(Nav.results(listOf("classic"))) },
                stringResource(R.string.cat_mood_top) to { c.push(Nav.results(listOf("rated"))) },
                stringResource(R.string.cat_want_title) + (c.wish?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: "") to { c.push(Nav.wishlist()) },
                stringResource(R.string.cat_mood_random) to { c.open(cat.items.random()) },
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(moods, key = { it.first }) { (label, go) -> SuggestionChip(go, { Text(label) }) }
            }
        }
        if (resumes.isNotEmpty()) item {
            CoverRow(stringResource(R.string.cat_row_continue), null, resumes, null, { it.item.id }) { r ->
                val total = (r.item.minutes ?: 0) * 60_000L
                CoverCard(r.item, if (total > 0 && !r.item.series) stringResource(R.string.cat_left_min, ((total - r.posMs) / 60_000).coerceAtLeast(1))
                    else nextEp[r.item.id]?.label?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.cat_next_ep, it) }
                    ?: cardMeta(r.item), progress = if (total > 0 && !r.item.series) r.posMs.toFloat() / total else null) {
                    c.open(r.item)  // the title's page: "Resume … from 1:02:10" on top, every other action below (owner's wish)
                }
            }
        }
        if (because != null) item {
            val (x, list) = because
            CoverRow(stringResource(R.string.cat_row_because, x.title), null, list.distinctBy { it.first.imdb ?: it.first.id }, null,
                { it.first.id }) { (y, why) ->
                CoverCard(y, why) { c.open(y) }
            }
        }
        seeds.seenMissing?.let { x -> if (suggestSeen != null) item(key = "suggest") {
            SuggestMissingRow(c, stringResource(R.string.suggest_row, x.title), suggestSeen)
        } }
        seeds.disk?.let { x -> if (suggestDisk != null) item(key = "suggest-disk") {
            SuggestMissingRow(c, stringResource(R.string.suggest_row_disk, x.title), suggestDisk.filter { it.imdb !in seenImdb })
        } }
        if (kids.isNotEmpty()) item {
            CoverRow(stringResource(R.string.cat_row_kids), stringResource(R.string.cat_all_n, kids.size), kids.oneCopyEach().take(15),
                { c.push(Nav.results(listOf("age:7"))) }, { it.id }) { x -> CoverCard(x, cardMeta(x)) { c.open(x) } }
        }
        genres.forEach { g ->
            item(key = "g/$g") {
                val list = cat.items.filter { g in it.genres }.sortedByDescending { it.rating ?: 0.0 }
                CoverRow(genreLabel(g), stringResource(R.string.cat_all_n, list.size), list.oneCopyEach().take(15),
                    { c.push(Nav.results(listOf("genre:$g"))) }, { it.id }) { x -> CoverCard(x, cardMeta(x)) { c.open(x) } }
            }
        }
        if (actor != null) item {
            val list = cat.items.filter { actor in it.cast }
            CoverRow(stringResource(R.string.cat_row_with, cat.person(actor)), stringResource(R.string.cat_person_more), list,
                { c.push(Nav.person(actor)) }, { it.id }) { x -> CoverCard(x, cardMeta(x)) { c.open(x) } }
        }
        item {
            val list = cat.items.sortedByDescending { it.added }.take(15)
            CoverRow(stringResource(R.string.cat_row_added), stringResource(R.string.cat_all), list,
                { c.push(Nav.results(listOf("added"), "added")) }, { it.id }) { x ->
                CoverCard(x, agoText(x.added) + " · " + x.rootName) { c.open(x) }
            }
        }
        val fromList = c.wish.orEmpty().filter { it.have }.mapNotNull { w -> w.id?.let { cat.byId(it) } }
        if (fromList.isNotEmpty()) item {
            CoverRow(stringResource(R.string.cat_row_from_list), stringResource(R.string.cat_want_title), fromList,
                { c.push(Nav.wishlist()) }, { it.id }) { x -> CoverCard(x, cardMeta(x)) { c.open(x) } }
        }
    }
}

/** The hero slides: swiped by hand, turning by themselves every 8 s while nobody touches them; dots below. */
@Composable
private fun HeroCarousel(c: CatCtx, slides: List<Pair<String, CatItem>>) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { slides.size }
    // a finger on the hero (about to tap "Play", reading, swiping) holds the turning for 15 s: a slide must never
    // change under a tap and start another title than the one looked at
    var touched by remember { mutableStateOf(0L) }
    if (slides.size > 1) LaunchedEffect(pager, slides) {
        while (true) {
            visibleDelay(8000)
            if (!pager.isScrollInProgress && System.currentTimeMillis() - touched > 15_000)
                pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
        }
    }
    Column(Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                touched = System.currentTimeMillis()
            }
        }
    }) {
        androidx.compose.foundation.pager.HorizontalPager(pager, key = { slides[it].second.id }) { i ->
            Hero(c, slides[i].second, slides[i].first)
        }
        if (slides.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(slides.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun Hero(c: CatCtx, x: CatItem, label: String) {
    val ctx = LocalContext.current
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).clickable { c.open(x) }) {
        SyntheticCover("", null, x.genres, Modifier.fillMaxSize(), corner = 18.dp, art = x.art(), wide = true, artSrv = x.srv)
        // a dark fade under the texts: a bright poster made the label and the title hard to read
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(label.uppercase(Locale.getDefault()), Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(x.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(yearMinutes(x.year, x.minutes).ifEmpty { null },
                x.genres.firstOrNull()?.let { genreLabel(it).lowercase(Locale.getDefault()) }, x.age?.let { "$it+" },
                plBadge(x.audio, x.subs)).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ playItem(ctx, x, c.srvOf(x)) }) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cat_play))
                }
                OutlinedButton({ c.open(x) }) { Text(stringResource(R.string.cat_details), color = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- Browse

private val AGE_TILES = listOf(0 to Color(0xFF1B8BD0), 7 to Color(0xFF2E8B57), 12 to Color(0xFFC97A00), 16 to Color(0xFF8C1D18))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseScreen(c: CatCtx, cat: CatalogData) {
    // the search field is above the tab (D1: the same on every Library tab, CatalogSearch.kt)
    Column(Modifier.fillMaxSize()) {
        val counts = remember(cat, c.rootTags) { BrowseCounts.of(cat, c::tagsOf) }
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle(stringResource(R.string.cat_who_watches)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGE_TILES.forEach { (age, col) ->
                        val n = cat.items.count { it.fitsAge(age) }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(col)
                            .clickable { c.push(Nav.results(listOf("age:$age"))) }.padding(10.dp)) {
                            Text("$age+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            Text(n.toString(), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (counts.genres.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.cat_genres)) }
                counts.genres.chunked(2).forEach { pair ->
                    item(key = "gr/" + pair.joinToString { it.first }) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (g, n) ->
                                val (a, b) = genreColors(listOf(g))
                                Column(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Brush.linearGradient(listOf(a, b))).clickable { c.push(Nav.results(listOf("genre:$g"))) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
                                    Text(genreLabel(g), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(pluralStringResource(R.plurals.cat_films, n, n), color = Color.White.copy(alpha = 0.8f),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (counts.topics.isNotEmpty()) item {
                ChipSection(stringResource(R.string.cat_topics), counts.topics.map { (q, n) -> "${cat.topics[q] ?: q} $n" to "topic:$q" }, c)
            }
            if (counts.langs.isNotEmpty()) item {
                ChipSection(stringResource(R.string.cat_language), counts.langs.map { (k, n) -> "${langCondLabel(k)} $n" to "lang:$k" }, c)
            }
            if (counts.decades.isNotEmpty()) item {
                ChipSection(stringResource(R.string.cat_decades), counts.decades.map { (d, n) -> "${decadeLabel(d)} $n" to "decade:$d" }, c)
            }
            if (counts.tags.isNotEmpty()) item {
                ChipSection(stringResource(R.string.cat_your_tags), counts.tags.map { (t, n) -> "#${tagLabel(t)} $n" to "tag:$t" }, c)
            }
            if (counts.people.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.cat_frequent_people))
                Spacer(Modifier.height(8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(counts.people, key = { it.first }) { (q, n) ->
                        PersonTile(cat.person(q), pluralStringResource(R.plurals.cat_films, n, n)) { c.push(Nav.person(q)) }
                    }
                }
            }
        }
    }
}

/** What Browse lists, counted once per catalog version. */
private class BrowseCounts(
    val genres: List<Pair<String, Int>>, val topics: List<Pair<String, Int>>, val langs: List<Pair<String, Int>>,
    val decades: List<Pair<Int, Int>>, val tags: List<Pair<String, Int>>, val people: List<Pair<String, Int>>,
) {
    companion object {
        private fun <K> count(keys: List<K>) = keys.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

        fun of(cat: CatalogData, tagsOf: (CatItem) -> List<String>): BrowseCounts {
            val items = cat.items
            val langs = listOf("plaudio" to items.count { it.plAudio }, "plsubs" to items.count { it.plSubs },
                "en" to items.count { "en" in it.audio }, "ja" to items.count { "ja" in it.audio },
                "nopl" to items.count { !it.plAudio && !it.plSubs }).filter { it.second > 0 }
            return BrowseCounts(count(items.flatMap { it.genres }), count(items.flatMap { it.topics }).take(16), langs,
                count(items.mapNotNull { it.year?.let { y -> y / 10 * 10 } }).sortedBy { it.first },
                count(items.flatMap { tagsOf(it) }),
                count(items.flatMap { (it.cast.take(6) + it.directors).distinct() }).filter { it.second >= 2 }.take(12))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(title: String, chips: List<Pair<String, String>>, c: CatCtx) {
    Column {
        SectionTitle(title)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (label, cond) -> AssistChip({ c.push(Nav.results(listOf(cond))) }, { Text(label) }) }
        }
    }
}

@Composable
private fun langCondLabel(k: String) = when (k) {
    "plaudio" -> stringResource(R.string.cat_lang_pl_audio)
    "plsubs" -> stringResource(R.string.cat_lang_pl_subs)
    "nopl" -> stringResource(R.string.cat_lang_no_pl)
    else -> langName(k).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

@Composable
private fun decadeLabel(d: Int) = stringResource(R.string.cat_decade, d, d + 9)

/** Label of a filter condition ([Cond]). */
@Composable
private fun condLabel(cond: String, cat: CatalogData): String {
    val v = cond.substringAfter(':', "")
    return when (cond.substringBefore(':')) {
        "age" -> if (v == "0") stringResource(R.string.cat_cond_age0) else stringResource(R.string.cat_cond_age, v.toIntOrNull() ?: 0)
        "genre" -> genreLabel(v)
        "topic" -> cat.topics[v] ?: v
        "person" -> cat.person(v)
        "lang" -> langCondLabel(v)
        "decade" -> decadeLabel(v.toIntOrNull() ?: 0)
        "tag" -> "#" + tagLabel(v)
        "q" -> "„$v”"
        "short" -> stringResource(R.string.cat_cond_short)
        "pl" -> stringResource(R.string.cat_cond_pl)
        "rated" -> stringResource(R.string.cat_cond_rated)
        "classic" -> stringResource(R.string.cat_mood_classics)
        "kind" -> stringResource(R.string.cat_cond_series)
        "added" -> stringResource(R.string.cat_row_added)
        else -> cond
    }
}

// ---------------------------------------------------------------- Results

private val SORTS = listOf("rating" to R.string.cat_sort_rating, "year" to R.string.cat_sort_newest, "short" to R.string.cat_sort_shortest,
    "added" to R.string.cat_sort_added)

@Composable
private fun ResultsScreen(c: CatCtx, conds: List<String>, sort0: String) {
    val cat = c.cat ?: return Hint(stringResource(R.string.media_loading))
    val chips = remember(conds) { conds + listOf("age:7", "pl", "short", "rated").filter { it !in conds } }
    var on by rememberSaveable { mutableStateOf(conds.joinToString("\n")) }
    val active = on.split("\n").filter { it.isNotEmpty() }.toSet()
    var sort by rememberSaveable { mutableStateOf(sort0) }
    val sorts = SORTS.filter { it.first != "added" || sort0 == "added" }
    val list = remember(cat, active, sort, c.rootTags) {
        val l = cat.items.filter { x -> active.all { Cond.matches(it, x, cat, c::tagsOf) } }
        when (sort) {
            "year" -> l.sortedByDescending { it.year ?: 0 }
            "short" -> l.sortedBy { it.minutes ?: Int.MAX_VALUE }
            "added" -> l.sortedByDescending { it.added }
            else -> l.sortedByDescending { it.rating ?: 0.0 }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(c.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            Column(Modifier.weight(1f)) {
                val labels = chips.filter { it in active }.map { condLabel(it, cat) }
                Text(labels.joinToString(" · ").ifEmpty { stringResource(R.string.cat_all_titles) }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.cat_count_of, list.size, cat.items.size), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // a category (genre, tag) and/or an age group: the icon makes the filter the scope of "Optimise" (stage 3)
            val ctx = LocalContext.current
            val fs = remember(active) { filterScope(active) }
            if (fs != null && canOptimise(ctx, c.srv)) {
                val label = chips.filter { it in active }.map { condLabel(it, cat) }.joinToString(" · ")
                IconButton({ JobsNav.openOptimise(c.srv, fs.copy(label = label)) }) {
                    Icon(Icons.Filled.AutoFixHigh, stringResource(R.string.job_optimise))
                }
            }
            TextButton({ sort = sorts[(sorts.indexOfFirst { it.first == sort } + 1) % sorts.size].first }) {
                Icon(Icons.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(sorts.firstOrNull { it.first == sort }?.second ?: R.string.cat_sort_rating))
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it }) { k ->
                FilterChip(k in active, { on = (if (k in active) active - k else active + k).joinToString("\n") },
                    { Text(condLabel(k, cat)) })
            }
        }
        if (list.isEmpty()) Hint(stringResource(R.string.cat_results_none)) else CoverGrid(c, list)
    }
}

/**
 * The Results filter as an "Optimise" scope when it is only a category (`genre:`/`tag:`, one of them) and age groups
 * (`age:N` = the groups up to N; the agent's `tag` matches folder tags and genres); null for anything else.
 */
private fun filterScope(active: Set<String>): JobScope? {
    var tag: String? = null
    var upTo: Int? = null
    for (c in active) {
        val v = c.substringAfter(':', "")
        when (c.substringBefore(':')) {
            "genre", "tag" -> { if (tag != null || v.isEmpty()) return null; tag = v }
            "age" -> { val n = v.toIntOrNull() ?: return null; upTo = minOf(upTo ?: n, n) }
            else -> return null
        }
    }
    if (tag == null && upTo == null) return null
    return JobScope(tag = tag, age = upTo?.let { Scopes.ageUpTo(it) } ?: emptyList())
}

/** Three columns of covers with "year · minutes · ★" under each. */
@Composable
private fun CoverGrid(c: CatCtx, list: List<CatItem>) {
    LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val x = list[i]
            Column(Modifier.clickable { c.open(x) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SyntheticCover(x.title, null, x.genres, Modifier.fillMaxWidth().aspectRatio(2f / 3f), badge = plBadge(x.audio, x.subs), art = x.art(), onPhone = onPhone(x), artSrv = x.srv,
                    titleSize = 13.sp, padding = 7.dp, labelPoster = true)
                Text(listOfNotNull(yearMinutes(x.year, x.minutes).ifEmpty { null }, x.rating?.let { ratingText(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Title

/** "On servers": this copy and the same title's copies on the other servers (a merged catalog, "All"); the mark is on
 * the copy "Play" uses (the one watched last, [lastWatchedCopy]). */
@Composable
private fun CopiesOnServers(c: CatCtx, x: CatItem, plays: CatItem) {
    val ctx = LocalContext.current
    val copies = listOf(x) + x.alsoOn
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.cat_on_servers), style = MaterialTheme.typography.labelLarge)
        copies.forEach { y ->
            val name = Servers.get(ctx, y.srv)?.name ?: y.srv
            Row(Modifier.fillMaxWidth().clickable(enabled = y !== x) { c.open(y) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = if (y.id == plays.id) FontWeight.Bold else null)
                    Text(listOfNotNull(y.rootName, qualityText(y.height), plBadge(y.audio, y.subs)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (y.id == plays.id) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleScreen(c: CatCtx, x: CatItem, focusPath: String? = null, fromPhone: Boolean = false) {
    val cat = c.cat ?: return
    val ctx = LocalContext.current
    // the copy that plays, with its server: this one, or in "All" the copy of the title watched last (positions are
    // per server and file); the screen still shows the title as merged
    val back = rememberResumedTick()  // back from the player: the copy watched last may have changed
    val (px, xs) = remember(x, back) { lastWatchedCopy(ctx, x, c.srvOf(x)) }
    val xa = Api.of(ctx, xs) ?: c.apiOf(x)
    val similar = remember(cat, x) { similar(ctx, cat, x) }
    // a series: its episodes in order (agent 1.10+), where this phone left off, extras apart
    val xsSrv = Servers.get(ctx, xs) ?: xa.srv
    val canEps = x.series && px.dir != null && xsSrv.libraryEpisodes
    // E1 (agent 1.16+, plan stage 5): every season and episode from the catalog; both lists are read again when the
    // app comes back (from a search site: a new episode shows up)
    val lists = x.series && x.imdb != null && xsSrv.hasEpisodeLists
    var eps by remember(x.id) { mutableStateOf<Episodes?>(null) }
    if (canEps) LaunchedEffect(x.id, if (lists) back else 0) {
        eps = withContext(Dispatchers.IO) { runCatching { xa.episodes(px.root, px.dir!!) }.getOrNull() } ?: eps
    }
    var seriesEps by remember(x.id) { mutableStateOf<SeriesEpisodes?>(null) }
    if (lists) LaunchedEffect(x.id, back) {
        withContext(Dispatchers.IO) { runCatching { xa.catalogEpisodes(x.imdb!!) }.getOrNull() }?.let { seriesEps = it }
    }
    // "Optimise" (E2) for the title, a season, a file, a selection (plan stage 3)
    val jobs = canOptimise(ctx, xs)
    var selecting by remember(x.id) { mutableStateOf(false) }
    var selected by remember(x.id) { mutableStateOf(setOf<Pair<Int, String>>()) }
    if (selecting) BackHandler { selecting = false; selected = emptySet() }
    val onSelect: (Pair<Int, String>, Boolean) -> Unit = { p, on -> selected = if (on) selected + p else selected - p }
    val settings = remember { Settings(ctx) }
    val list = eps?.episodes.orEmpty()
    val cont = remember(eps, back) { if (list.isEmpty()) -1 else continueIndex(settings, xs, px.root, list) }
    var phoneTick by remember { mutableStateOf(0) }  // a copy on the phone changed (the card, an episode's ⋮)
    // D6: opened from "On the phone", scrolled to the tapped episode (item 2 = the episode list, then the row's offset)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var focusY by remember { mutableStateOf<Int?>(null) }
    var scrolled by remember(x.id) { mutableStateOf(false) }
    if (focusPath != null && list.isNotEmpty() && !scrolled) LaunchedEffect(list.size, focusY) {
        val y = focusY
        if (y == null) listState.scrollToItem(2) else { listState.scrollToItem(2, (y - 120).coerceAtLeast(0)); scrolled = true }
    }
    val se = seriesEps?.takeIf { it.seasons.isNotEmpty() }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = if (selecting) 88.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                SyntheticCover(x.title, null, x.genres, Modifier.fillMaxSize(), titleSize = 34.sp, corner = 0.dp, padding = 20.dp, art = x.art(), wide = true, artSrv = x.srv)
                // header (E1): back, breadcrumb, "Select" / "Done" for the episodes on the disk
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BackOnCover(c.pop)
                    Box(Modifier.weight(1f)) {
                        Text(stringResource(when {
                            selecting -> R.string.s35_crumb_select
                            x.series -> R.string.s35_crumb_series
                            else -> R.string.s35_crumb_film
                        }), Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (jobs && list.isNotEmpty()) TextButton({ selecting = !selecting; selected = emptySet() },
                        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))) {
                        Text(stringResource(if (selecting) R.string.s35_done else R.string.s35_select), color = Color.White)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(x.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                x.titlePl?.let { Text(stringResource(R.string.cat_title_pl, it), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(ratingLine(x.year, x.minutes, x.rating, x.votes), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    x.age?.let { AgeBadge(it) }
                    qualityText(x.height)?.let { InfoBadge(it) }
                    val seasons = se?.seasons?.size ?: list.mapNotNull { it.season }.distinct().size
                    val episodes = se?.total ?: x.episodes
                    if (x.series && seasons > 1) InfoBadge(pluralStringResource(R.plurals.st4_seasons, seasons, seasons))
                    if (x.series && episodes > 0) InfoBadge(pluralStringResource(R.plurals.cat_episodes, episodes, episodes))
                }
                // "You have 5 of 12 · 9.8 GB · disk" (E1)
                if (se != null) Text(listOfNotNull(stringResource(R.string.s35_have_of, se.have, se.total), humanBytes(x.size), px.rootName)
                    .joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                // ▶ and the Optimise button (→ E2 with the title as the scope)
                WithOptimise(jobs, { JobsNav.openOptimise(xs, Scopes.title(px)) }) {
                    if (cont >= 0) {
                        val e = list[cont]
                        val pos = settings.position(MediaRef.InLibrary(px.root, e.path, xs).key())
                        val label = e.label.ifEmpty { (cont + 1).toString() }
                        // "▶ S1 E3 from 9:12" (D6, E1)
                        MediaActions(xa, MediaRef.InLibrary(px.root, e.path, xs), c.snack, c.onPlayingOnPc, big = true, subsFolder = px.dir,
                            onPlay = { playEpisode(ctx, xs, px.root, list, cont) },
                            playLabel = if (pos > 0) stringResource(R.string.st4_play_ep_from, label, clock(pos / 1000.0))
                                else stringResource(R.string.cat_play_ep, label), oneChooser = fromPhone)
                    } else {
                        // a film watched part-way: "▶ Resume from 1:02:10" (the player starts there anyway)
                        val pos = remember(px, back) { if (x.series) 0L else settings.position(px.ref(xs).key()) }
                        MediaActions(xa, px.ref(xs), c.snack, c.onPlayingOnPc, imdb = x.imdb.takeIf { !x.series }, big = true,
                            playLabel = if (pos > 0) stringResource(R.string.cat_resume_from, clock(pos / 1000.0)) else null,
                            oneChooser = fromPhone)
                    }
                }
                // D6 "On this phone": shown whenever the phone holds copies of this title
                OnThisPhoneCard(xs, px, list, cont, phoneTick + back) { phoneTick++ }
                // what the server did with the file after it was downloaded (the episode the ▶ button plays for a series)
                if (cont >= 0) AfterdlFileCard(xa, px.root, list[cont].path, c.snack)
                else if (!x.series) AfterdlFileCard(xa, px.root, px.ref(xs).path, c.snack)
                // "All": where the copies are; another copy plays from its server (it opens its own title screen)
                // only with a choice to make: one copy says nothing ("Available" says where it is)
                if (x.srv.isNotEmpty() && x.alsoOn.isNotEmpty()) CopiesOnServers(c, x, px)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    x.genres.forEach { g -> AssistChip({ c.push(Nav.results(listOf("genre:$g"))) }, { Text(genreLabel(g)) }) }
                    x.topics.forEach { q -> cat.topics[q]?.let { t -> AssistChip({ c.push(Nav.results(listOf("topic:$q"))) }, { Text(t) }) } }
                    c.tagsOf(x).forEach { t -> AssistChip({ c.push(Nav.results(listOf("tag:$t"))) }, { Text("#" + tagLabel(t)) }) }
                }
                if (x.audio.isNotEmpty()) MetaRow(stringResource(R.string.cat_audio)) { Text(x.audio.distinct().map { langName(it) }.joinToString()) }
                if (x.subs.isNotEmpty()) MetaRow(stringResource(R.string.cat_subtitles)) { Text(x.subs.distinct().map { langName(it) }.joinToString()) }
                if (x.directors.isNotEmpty()) MetaRow(stringResource(R.string.cat_directed)) { PeopleLinks(x.directors.map { it to cat.person(it) }, c) }
                // which server and disk (D6)
                MetaRow(stringResource(R.string.cat_where)) {
                    Text(listOfNotNull(Servers.get(ctx, xs)?.name, px.rootName, humanBytes(x.size)).joinToString(" · "))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (x.dir != null) OutlinedButton({ c.onOpenFolder(x.root, x.dir) }) {
                        Text(stringResource(if (x.series) R.string.cat_episodes_open else R.string.cat_open_folder))
                    }
                    if (x.imdb != null) OutlinedButton({ c.push(Nav.next(x.imdb)) }) { Text(stringResource(R.string.cat_what_next)) }
                }
            }
        }
        if (se != null) item {
            SeriesSeasonsList(c, px, x.title, se, list, eps?.extras.orEmpty(), cont, settings, back + phoneTick, focusPath, xa, jobs,
                selecting, selected, onSelect, onFocusY = { if (focusY == null) focusY = it }) { phoneTick++ }
        } else if (list.isNotEmpty()) item {
            EpisodeList(c, px, list, eps?.extras.orEmpty(), cont, settings, back + phoneTick, focusPath, xa, x.title,
                onFocusY = { if (focusY == null) focusY = it }, onChanged = { phoneTick++ }, jobs = jobs, selecting = selecting,
                selected = selected, onSelect = onSelect)
        }
        if (x.cast.isNotEmpty()) item {
            SectionTitle(stringResource(R.string.cat_cast))
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(x.cast.take(20).withIndex().toList(), key = { it.value }) { (i, q) ->
                    PersonTile(cat.person(q), x.roles.getOrNull(i)) { c.push(Nav.person(q)) }
                }
            }
        }
        if (similar.isNotEmpty()) item {
            CoverRow(stringResource(R.string.cat_similar), null, similar, null, { it.first.id }) { (y, why) ->
                CoverCard(y, why, width = 100.dp) { c.open(y) }
            }
        }
        // the same, from the agent's IMDb index: titles like this one that aren't on the disks (agent 1.18+); asked only
        // once the row is scrolled to, the first answer for a title takes 10-30 s
        if (x.imdb != null) item(key = "similar-missing") {
            rememberSuggestMissing(c, cat, x)?.let { SuggestMissingRow(c, stringResource(R.string.cat_similar_missing), it) }
        }
    }
    if (selecting) SelectionBar(selected.size, Modifier.align(Alignment.BottomCenter)) {
        JobsNav.openOptimise(xs, Scopes.files(selected.toList(), ctx.getString(R.string.s35_selected_label, x.title)))
        selecting = false; selected = emptySet()
    }
    }
}

/** Shared actors and directors count most, then topics, then genres. Genres alone need two specific ones in common
 *  ("Drama" says little), and kids' titles stay with kids' titles. The reason is the strongest link. */
private fun similar(ctx: android.content.Context, cat: CatalogData, x: CatItem): List<Pair<CatItem, String>> =
    // (another server's copy of the same title is not "similar": the same IMDb id is left out too)
    cat.items.asSequence().filter { it.id != x.id && (x.imdb == null || it.imdb != x.imdb) && it.kids == x.kids }.mapNotNull { y ->
        val actors = x.cast.take(8).filter { it in y.cast }
        val directors = x.directors.filter { it in y.directors }
        val topics = x.topics.filter { it in y.topics }
        val genres = x.genres.filter { it in y.genres && it !in GENERIC_GENRES }
        val people = actors.size + directors.size
        if (people == 0 && topics.isEmpty() && genres.size < 2) return@mapNotNull null
        val score = people * 3 + topics.size * 2 + genres.size
        val why = directors.firstOrNull()?.let { cat.person(it) } ?: actors.firstOrNull()?.let { cat.person(it) }
            ?: topics.firstOrNull()?.let { cat.topics[it] } ?: genres.joinToString(", ") { genreLabel(ctx, it) }
        Triple(y, why, score)
    }.sortedWith(compareByDescending<Triple<CatItem, String, Int>> { it.third }.thenByDescending { it.first.rating ?: 0.0 })
        .take(12).map { it.first to it.second }.toList()

private val GENERIC_GENRES = setOf("Drama")

@Composable
private fun ratingLine(year: Int?, minutes: Int?, rating: Double?, votes: Int?) = listOfNotNull(
    yearMinutes(year, minutes).ifEmpty { null },
    rating?.let { ratingText(it) + (votes?.takeIf { v -> v > 0 }?.let { v -> " (${votesText(v)})" } ?: "") },
).joinToString(" · ")

@Composable
private fun BackOnCover(onBack: () -> Unit) {
    IconButton(onBack, Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up), tint = Color.White)
    }
}

/**
 * A series' episodes: season chips when there are several seasons, each row "E3 · name" with this phone's progress (a bar)
 * or ✓ when watched to the end; the episode it continues at is highlighted. Extras (making-of, deleted scenes, samples…)
 * fold away under their own row and play alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeList(c: CatCtx, x: CatItem, list: List<Episode>, extras: List<Episode>, cont: Int, settings: Settings, back: Int,
                        focusPath: String? = null, api: Api? = null, title: String = x.title, onFocusY: (Int) -> Unit = {},
                        onChanged: () -> Unit = {}, jobs: Boolean = false, selecting: Boolean = false,
                        selected: Set<Pair<Int, String>> = emptySet(), onSelect: (Pair<Int, String>, Boolean) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val seasons = remember(list) { list.map { it.season }.distinct() }
    var season by rememberSaveable(x.id) { mutableStateOf<Int?>(null) }
    // the season of the episode tapped on "On the phone" (D6), else the one watching continues in
    val focus = list.firstOrNull { it.path == focusPath }
    val shown = season ?: focus?.season ?: list.getOrNull(cont.coerceAtLeast(0))?.season ?: seasons.first()
    val subsLang = remember { settings.subLanguages.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "" }
    var showExtras by rememberSaveable(x.id) { mutableStateOf(false) }
    var download by remember { mutableStateOf(false) }
    var dlTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    if (download) SeriesDownloadDialog(c.srvOf(x), x.root, list, extras, cont, shown, onDismiss = { download = false }) { n ->
        download = false; dlTick++
        scope.launch { c.snack.showSnackbar(ctx.resources.getQuantityString(R.plurals.ser_dl_started, n, n)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.cat_episodes_title)) }
            // "Optimise this season" (plan stage 3): the files of the season shown
            if (jobs) IconButton({
                JobsNav.openOptimise(c.srvOf(x), Scopes.files(list.filter { it.season == shown }.map { x.root to it.path },
                    listOfNotNull(title, shown?.let { String.format(Locale.ROOT, "S%02d", it) }).joinToString(" ")))
            }) { Icon(Icons.Filled.AutoFixHigh, stringResource(R.string.s35_optimise_season)) }
            IconButton({ download = true }, Modifier.padding(end = 4.dp)) {
                Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.ser_dl_title))
            }
        }
        if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons, key = { it ?: -1 }) { s ->
                FilterChip(s == shown, { season = s }, { Text(s?.let { stringResource(R.string.cat_season_n, it) } ?: stringResource(R.string.cat_season_other)) })
            }
        }
        // "subtitles: PL 2 · EN 1 · none 1" for the season shown (D6)
        val seasonSubs = rememberEpisodeSubs(api, c.srvOf(x), x.root, list.filter { it.season == shown })
        SeasonSubsLine(seasonSubs)
        list.forEachIndexed { i, e ->
            if (e.season != shown) return@forEachIndexed
            val code = if (e.season != null && e.episode != null) String.format(Locale.ROOT, "S%02dE%02d", e.season, e.episode) else e.label
            OwnedEpisodeRow(c.srvOf(x), x.root, e, listOf(e.episode?.let { "E$it" } ?: e.label, e.shortName).filter { it.isNotEmpty() }.joinToString(" · "),
                current = i == cont, focused = e.path == focusPath, settings = settings, back = back, dlTick = dlTick,
                searchPhrase = listOf(title, code, subsLang).filter { it.isNotEmpty() }.joinToString(" "),
                noSubs = seasonSubs[e.path]?.isEmpty() == true, onPlay = { playEpisode(ctx, c.srvOf(x), x.root, list, i) },
                onChanged = onChanged, onFocusY = onFocusY, selecting = selecting, selected = (x.root to e.path) in selected,
                onSelect = { onSelect(x.root to e.path, it) },
                onOptimise = if (jobs) ({ JobsNav.openOptimise(c.srvOf(x), Scopes.files(listOf(x.root to e.path), "$title $code".trim())) }) else null)
        }
        if (extras.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { showExtras = !showExtras }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cat_extras_n, extras.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showExtras) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (showExtras) extras.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { playEpisode(ctx, c.srvOf(x), x.root, listOf(e), 0) }
                    .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Movie, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(e.path.substringAfter((x.dir ?: "") + "/").substringBeforeLast('.'), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) { value() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleLinks(people: List<Pair<String, String>>, c: CatCtx) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        people.forEach { (q, name) ->
            Text(name, Modifier.clickable { c.push(Nav.person(q)) }, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------------------------------------------------------- Missing title

/**
 * D2: a title that is not on the disks (opened from the library search, a person, "What next", the wishlist): cover and
 * facts, "SEARCH" with the household's search sites (phrase "Title Year"), "Want to watch" and "Paste magnet", the
 * description, similar titles marked "on disk" / "you don't have". A title that turns out to be on the disks offers
 * its page instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingScreen(c: CatCtx, imdb: String, why: String?) {
    val ctx = LocalContext.current
    var info by remember(imdb) { mutableStateOf<TitleInfo?>(null) }
    var error by remember(imdb) { mutableStateOf<String?>(null) }
    var engines by remember { mutableStateOf<List<SearchEngine>>(emptyList()) }
    var similar by remember(imdb) { mutableStateOf<List<CatCard>?>(null) }
    LaunchedEffect(imdb) {
        try { info = withContext(Dispatchers.IO) { c.api.catalogTitle(imdb) } } catch (e: ApiException) { error = e.message }
    }
    LaunchedEffect(Unit) { engines = withContext(Dispatchers.IO) { runCatching { c.api.searchEngines() }.getOrDefault(emptyList()) } }
    // "What next" for this title: the ones on the disks first (Wikidata, the first answer may take ~10 s)
    LaunchedEffect(imdb) { similar = withContext(Dispatchers.IO) { runCatching { c.api.catalogNext(imdb) }.getOrDefault(emptyList()) } }
    val t = info
    val cat = c.cat
    Column(Modifier.fillMaxSize()) {
        // header: ← and what this page is about
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(c.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            if (t != null) Text(stringResource(if (t.have) R.string.cat_on_disks else R.string.d2_header_missing),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (t == null) {
            Hint(error ?: stringResource(R.string.media_loading))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // cover beside title, "2001 · 108 min · ★ 7.2", "animation · anime · 12+", "dir. …"
            Row {
                SyntheticCover(t.title, t.year, t.genres, Modifier.width(110.dp).height(160.dp), missing = !t.have, titleSize = 14.sp,
                    corner = 10.dp, padding = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(ratingLine(t.year, t.minutes, t.rating, null), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val g = listOfNotNull(if (t.kind == "series") stringResource(R.string.srch_series) else null) +
                        t.genres.map { genreLabel(ctx, it).lowercase(Locale.getDefault()) } + listOfNotNull(t.age?.let { "$it+" })
                    if (g.isNotEmpty()) Text(g.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.directors.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.directors.forEachIndexed { i, (q, name) ->
                            Text(if (i == 0) stringResource(R.string.d2_dir, name) else name, Modifier.clickable { c.push(Nav.person(q)) },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (t.have) {
                val copy = t.copies.firstOrNull()
                if (copy != null) Button({ c.open(cat?.byId(copy.id) ?: copy) }, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.cat_open_title))
                }
            } else {
                // SEARCH: one button per site and "Set up search sites ›" (D4, Back returns here); no site: only the link
                val phrase = listOfNotNull(t.title, t.year?.toString()).joinToString(" ")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cat_search).uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // the built-in Internet Archive site (agent 1.19+): films only, searched by this server
                    val srv = Servers.get(ctx, c.api.srv.key) ?: c.api.srv
                    EngineRow(engines, phrase, t.imdb, onSetUp = { c.push(Nav.engines()) },
                        onArchive = if (srv.archiveSearch && !srv.isGuest && t.kind != "series") { { ArchiveSearch.open(srv.key, phrase) } } else null)
                    if (engines.isNotEmpty() || (srv.archiveSearch && !srv.isGuest)) Text(stringResource(R.string.d2_phrase, phrase), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val listed = c.listed(imdb)
                    val toggle = { c.toggleWish(ctx, imdb, t.title, t.year, t.genres, why) }
                    if (listed) FilledTonalButton({ toggle() }, Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.Bookmark, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cat_want_on), maxLines = 1)
                    } else Button({ toggle() }, Modifier.weight(1f).height(48.dp)) {
                        Text(stringResource(R.string.cat_want_add), maxLines = 1)
                    }
                    OutlinedButton({
                        if (!pasteMagnet(ctx)) c.scopeSnack(ctx.getString(R.string.main_no_magnet_in_clipboard))
                    }, Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.main_paste_magnet), maxLines = 1) }
                }
            }
            // the description (agent 1.17+: the lead of the Wikipedia article)
            t.about?.let { about ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(about, style = MaterialTheme.typography.bodyMedium)
                    t.aboutSource?.let { Text(stringResource(R.string.d2_source, it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            // SIMILAR · YOU HAVE N: covers marked "on disk" / "you don't have"
            val sim = similar.orEmpty().distinctBy { it.imdb }.sortedByDescending { it.have }.take(12)
            if (sim.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.d2_similar, sim.count { it.have }), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sim, key = { it.imdb }) { s ->
                        val mine = s.id?.let { cat?.byId(it) }
                        Column(Modifier.width(80.dp).clickable {
                            if (mine != null) c.open(mine) else if (!s.have) c.push(Nav.missing(s.imdb, s.why.ifEmpty { null }))
                        }) {
                            SyntheticCover(s.title, null, s.genres, Modifier.width(80.dp).height(116.dp), missing = !s.have, titleSize = 10.sp,
                                corner = 8.dp, padding = 6.dp, art = mine?.art(), artSrv = mine?.srv ?: "")
                            Spacer(Modifier.height(4.dp))
                            Text(listOfNotNull(s.title, s.year?.toString()).joinToString(" "), style = MaterialTheme.typography.labelMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (s.have) R.string.d2_on_disk else R.string.d2_not_have), style = MaterialTheme.typography.labelSmall,
                                color = if (s.have) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The first magnet in the clipboard goes to MagnetActivity (the usual flow); false when there is none. */
fun pasteMagnet(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    val m = Regex("""magnet:\?\S+""").find(text)?.value ?: return false
    ctx.startActivity(Intent(ctx, MagnetActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m))
    return true
}

private fun CatCtx.scopeSnack(msg: String) = kotlinx.coroutines.MainScope().launch { snack.showSnackbar(msg) }

// ---------------------------------------------------------------- Person

@Composable
private fun PersonScreen(c: CatCtx, q: String) {
    val ctx = LocalContext.current
    var info by remember(q) { mutableStateOf<PersonInfo?>(null) }
    var error by remember(q) { mutableStateOf<String?>(null) }
    LaunchedEffect(q) {
        try { info = withContext(Dispatchers.IO) { c.api.catalogPerson(q) } } catch (e: ApiException) { error = e.message }
    }
    val cat = c.cat
    // the films on the disks are known at once from the catalog; the person's other films need Wikidata (a few seconds)
    val local = remember(cat, q) {
        cat?.items?.mapNotNull { x ->
            val i = x.cast.indexOf(q)
            when {
                i >= 0 -> PersonFilm(x, x.roles.getOrNull(i), false)
                q in x.directors -> PersonFilm(x, null, true)
                else -> null
            }
        }.orEmpty()
    }
    val films = info?.films ?: local
    val name = info?.name ?: cat?.people?.get(q) ?: q
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.padding(4.dp)) { IconButton(c.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) } } }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, 72.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    val director = info?.role?.let { it == "director" } ?: (films.isNotEmpty() && films.all { it.asDirector })
                    val role = stringResource(if (director) R.string.cat_director else R.string.cat_actor)
                    Text(role + " · " + pluralStringResource(R.plurals.cat_in_collection, films.size, films.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val top = films.flatMap { it.item.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2)
                    if (top.isNotEmpty()) Text(stringResource(R.string.cat_mostly, top.joinToString { genreLabel(ctx, it.key).lowercase(Locale.getDefault()) }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(films, key = { "f/" + it.item.id }) { f ->
            val x = f.item
            FilmRow(x.title, x.year, x.genres, false,
                listOfNotNull(yearMinutes(x.year, x.minutes).ifEmpty { null },
                    x.genres.take(2).joinToString(", ") { genreLabel(ctx, it).lowercase(Locale.getDefault()) }.ifEmpty { null }).joinToString(" · "),
                if (f.asDirector) stringResource(R.string.cat_as_director) else f.role?.let { stringResource(R.string.cat_as_role, it) },
                onClick = { c.open(cat?.byId(x.id) ?: x) })
        }
        val missing = info?.missing.orEmpty()
        if (info == null && error == null) item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.cat_person_loading), style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { e -> item { Text(e, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) } }
        if (missing.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.cat_not_on_disks), Modifier.padding(top = 8.dp)) }
            items(missing, key = { "m/" + it.imdb }) { m ->
                FilmRow(m.title, m.year, m.genres, true, listOfNotNull(yearMinutes(m.year, m.minutes).ifEmpty { null },
                    m.rating?.let { ratingText(it) }).joinToString(" · "), null,
                    onClick = { c.push(Nav.missing(m.imdb)) })
            }
        }
        item {
            Text(stringResource(R.string.cat_person_note), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A list row with a small cover: title, a meta line, an optional second line and an action on the right. */
@Composable
private fun FilmRow(title: String, year: Int?, genres: List<String>, missing: Boolean, meta: String, line: String?,
                    lineColor: Color = Color.Unspecified, onClick: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SyntheticCover(title, null, genres, Modifier.width(60.dp).height(90.dp), missing = missing, titleSize = 9.sp, corner = 8.dp, padding = 5.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!line.isNullOrEmpty()) Text(line, style = MaterialTheme.typography.bodySmall,
                color = if (lineColor == Color.Unspecified) MaterialTheme.colorScheme.primary else lineColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

// ---------------------------------------------------------------- What next

private val REASONS = listOf("all" to R.string.cat_next_all, "actor" to R.string.cat_next_actor, "genre" to R.string.cat_next_genre,
    "topic" to R.string.cat_next_topic)

/** Suggestions after a film: the collection first, the rest greyed with "+ List". [ended]: opened by the player. */
@Composable
private fun NextScreen(c: CatCtx, imdb: String, ended: Boolean) {
    val ctx = LocalContext.current
    var items by remember(imdb) { mutableStateOf<List<CatCard>?>(null) }
    var error by remember(imdb) { mutableStateOf<String?>(null) }
    var why by rememberSaveable { mutableStateOf("all") }
    LaunchedEffect(imdb) {
        try { items = withContext(Dispatchers.IO) { c.api.catalogNext(imdb) } } catch (e: ApiException) { error = e.message }
    }
    val src = c.cat?.byImdb(imdb)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(c.pop) { Icon(if (ended) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (ended) R.string.cat_next_ended else R.string.cat_what_next), style = MaterialTheme.typography.titleMedium)
                if (src != null) Text(src.title + (src.year?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val list = items
        if (list == null) {
            Hint(error ?: stringResource(R.string.cat_next_loading))
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(REASONS.filter { (k, _) -> k == "all" || list.any { it.reason == k } }, key = { it.first }) { (k, label) ->
                FilterChip(why == k, { why = k }, { Text(stringResource(label)) })
            }
        }
        val shown = list.filter { why == "all" || it.reason == why }
        if (shown.isEmpty()) {
            Hint(stringResource(R.string.cat_next_none))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.imdb }) { m ->
                val item = m.id?.let { c.cat?.byId(it) }
                val whyText = src?.let { s -> ctx.getString(R.string.cat_why_after, s.title, m.why) } ?: m.why
                FilmRow(m.titlePl?.let { "${m.title} · $it" } ?: m.title, m.year, m.genres, !m.have, yearMinutes(m.year, m.minutes), m.why,
                    onClick = { if (m.have && m.id != null) c.push(Nav.title(item?.id ?: m.id)) else c.push(Nav.missing(m.imdb, whyText)) }) {
                    if (m.have && item != null) FilledTonalButton({ playItem(ctx, item, c.srvOf(item)) }) {
                        Text(stringResource(R.string.cat_play))
                    } else if (!m.have) OutlinedButton({ c.toggleWish(ctx, m.imdb, m.title, m.year, m.genres, whyText) }) {
                        Text(stringResource(if (c.listed(m.imdb)) R.string.cat_listed_short else R.string.cat_list_add_short))
                    }
                }
            }
            item {
                Text(stringResource(R.string.cat_next_note), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- Want to watch

@Composable
private fun WishlistScreen(c: CatCtx) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("all") }
    val all = c.wish
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(c.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
            Column {
                Text(stringResource(R.string.cat_want_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cat_want_shared), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (all == null) return@Column Hint(stringResource(R.string.media_loading))
        val tabs = listOf(Triple("all", R.string.cat_want_tab_all, all.size), Triple("have", R.string.cat_want_tab_have, all.count { it.have }),
            Triple("missing", R.string.cat_want_tab_missing, all.count { !it.have }))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs, key = { it.first }) { (k, label, n) -> FilterChip(tab == k, { tab = k }, { Text(stringResource(label, n)) }) }
        }
        val list = all.filter { tab == "all" || (tab == "have") == it.have }
        if (list.isEmpty()) return@Column Hint(stringResource(if (all.isEmpty()) R.string.cat_want_empty else R.string.cat_results_none))
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.imdb }) { w ->
                val state = if (w.have) stringResource(R.string.cat_want_have, w.where ?: "")
                    else listOfNotNull(stringResource(R.string.cat_not_on_disks).lowercase(Locale.getDefault()),
                        w.why.ifEmpty { null }, w.by?.let { stringResource(R.string.cat_want_by, it) }).joinToString(" · ")
                FilmRow(w.title, w.year, w.genres, !w.have, w.year?.toString().orEmpty(), state,
                    lineColor = if (w.have) OK_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { if (w.have && w.id != null) c.push(Nav.title(w.id)) else c.push(Nav.missing(w.imdb, w.why.ifEmpty { null })) }) {
                    IconButton({ c.toggleWish(ctx, w.imdb, w.title, w.year, w.genres, null) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.cat_want_remove))
                    }
                }
            }
            item {
                Text(stringResource(R.string.cat_want_note), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One card per title in the Discover rows: the same series kept per season on one disk and whole on another showed up
 *  three or four times side by side. The copy with most episodes wins, then the sharper picture; order is kept. */
private fun List<CatItem>.oneCopyEach(): List<CatItem> =
    groupBy { it.imdb ?: it.id }.values.map { g -> g.maxWith(compareBy<CatItem>({ it.episodes }, { it.height ?: 0 })) }

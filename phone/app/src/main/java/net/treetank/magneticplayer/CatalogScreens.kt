package net.treetank.magneticplayer

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
}

/** What every catalog screen needs: the server, the data and navigation. */
class CatCtx(
    val api: Api, val snack: SnackbarHostState, val onPlayingOnPc: () -> Unit, val cat: CatalogData?,
    val wish: List<WishItem>?, val rootTags: Map<Int, List<String>>, val push: (JSONObject) -> Unit, val pop: () -> Unit,
    val onOpenFolder: (Int, String) -> Unit, private val scope: CoroutineScope, private val setWish: (List<WishItem>) -> Unit,
) {
    val srv get() = api.srv.key
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
                stack: String, onStack: (String) -> Unit, onOpenFolder: (Int, String) -> Unit, folders: @Composable () -> Unit) {
    // covers: the server's own posters or frames from the films (agent 1.6+), drawn ones otherwise
    val ctx = LocalContext.current
    val coverApi = remember(api.srv.key) { (Servers.get(ctx, api.srv.key) ?: api.srv).let { if (it.libraryCovers) Api(api.s, it) else null } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCoverApi provides coverApi) {
        CatalogAreaBody(api, snack, onPlayingOnPc, mode, onMode, stack, onStack, onOpenFolder, folders)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogAreaBody(api: Api, snack: SnackbarHostState, onPlayingOnPc: () -> Unit, mode: String, onMode: (String) -> Unit,
                stack: String, onStack: (String) -> Unit, onOpenFolder: (Int, String) -> Unit, folders: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val srvKey = api.srv.key
    val ver by CatalogCache.version
    val cat = remember(srvKey, ver) { CatalogCache.cached(srvKey) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(srvKey) {
        while (true) {
            val c = try {
                withContext(Dispatchers.IO) { CatalogCache.refresh(api) }.also { error = null }
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
            withContext(Dispatchers.IO) { runCatching { api.wishlist() }.getOrNull() }?.let { wish = it }
            delay(30_000)
        }
    }
    var rootTags by remember(srvKey) { mutableStateOf<Map<Int, List<String>>>(emptyMap()) }
    LaunchedEffect(srvKey) {
        rootTags = withContext(Dispatchers.IO) { runCatching { api.libraryRoots().associate { it.index to it.tags } }.getOrDefault(emptyMap()) }
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
    val c = CatCtx(api, snack, onPlayingOnPc, cat, wish, rootTags, ::push, ::pop, onOpenFolder, scope) { wish = it }
    val top = entries.lastOrNull()
    if (top != null) {
        BackHandler { pop() }
        androidx.compose.runtime.key(entries.size) {
            when (top.optString("k")) {
                "title" -> cat?.byId(top.optString("id"))?.let { TitleScreen(c, it) }
                    ?: if (cat == null) Hint(stringResource(R.string.media_loading)) else Hint(stringResource(R.string.cat_gone))
                "missing" -> MissingScreen(c, top.optString("imdb"), top.optString("why").ifEmpty { null })
                "person" -> PersonScreen(c, top.optString("q"))
                "next" -> NextScreen(c, top.optString("imdb"), top.optBoolean("ended"))
                "results" -> ResultsScreen(c, top.optJSONArray("conds")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                    top.optString("sort", "rating"))
                "wishlist" -> WishlistScreen(c)
                else -> pop()
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val modes = listOf("discover" to R.string.cat_mode_discover, "browse" to R.string.cat_mode_browse, "folders" to R.string.cat_mode_folders)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(mode == m, { onMode(m) }, SegmentedButtonDefaults.itemShape(i, modes.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        when (mode) {
            "folders" -> folders()
            else -> when {
                cat == null -> Hint(error ?: stringResource(R.string.media_loading))
                cat.items.isEmpty() -> Hint(if (cat.scanning) stringResource(R.string.cat_building, cat.done, cat.total)
                    else stringResource(R.string.cat_empty))
                mode == "browse" -> BrowseScreen(c, cat)
                else -> DiscoverScreen(c, cat)
            }
        }
    }
}

// ---------------------------------------------------------------- Discover

@Composable
private fun DiscoverScreen(c: CatCtx, cat: CatalogData) {
    val ctx = LocalContext.current
    val back = rememberResumedTick()
    val resumes = remember(cat, back) { resumes(ctx, c.srv, cat) }
    val movies = cat.items.count { !it.series }
    val series = cat.items.size - movies
    val now = System.currentTimeMillis() / 1000.0
    val (hero, heroNew) = remember(cat) {
        cat.items.filter { it.rating != null && now - it.added < 30 * 86400 }.maxByOrNull { it.rating!! }?.let { it to true }
            ?: cat.items.filter { (it.votes ?: 0) >= 1000 }.maxByOrNull { it.rating ?: 0.0 }?.let { it to false }
            ?: (cat.items.first() to true)
    }
    val genres = remember(cat) {
        cat.items.flatMap { it.genres }.groupingBy { it }.eachCount().filter { it.value >= 3 }.entries.sortedByDescending { it.value }
            .take(3).map { it.key }
    }
    val actor = remember(cat) {
        cat.items.flatMap { it.cast.take(5) }.groupingBy { it }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }
    val kids = remember(cat) { cat.items.filter { it.fitsAge(7) } }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(listOfNotNull(pluralStringResource(R.plurals.cat_films, movies, movies),
                if (series > 0) pluralStringResource(R.plurals.cat_series, series, series) else null,
                if (cat.scanning) stringResource(R.string.cat_scanning, cat.done, cat.total) else null).joinToString(" · "),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Hero(c, hero, heroNew)
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
                    else cardMeta(r.item), progress = if (total > 0 && !r.item.series) r.posMs.toFloat() / total else null) {
                    if (r.item.series) playItem(ctx, r.item, c.srv)  // the episode being watched, the next ones queued
                    else playOnPhone(ctx, MediaRef.InLibrary(r.item.root, r.path, c.srv), r.item.imdb, r.item.kind)
                }
            }
        }
        if (kids.isNotEmpty()) item {
            CoverRow(stringResource(R.string.cat_row_kids), stringResource(R.string.cat_all_n, kids.size), kids.take(15),
                { c.push(Nav.results(listOf("age:7"))) }, { it.id }) { x -> CoverCard(x, cardMeta(x)) { c.open(x) } }
        }
        genres.forEach { g ->
            item(key = "g/$g") {
                val list = cat.items.filter { g in it.genres }.sortedByDescending { it.rating ?: 0.0 }
                CoverRow(genreLabel(g), stringResource(R.string.cat_all_n, list.size), list.take(15),
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

@Composable
private fun Hero(c: CatCtx, x: CatItem, isNew: Boolean) {
    val ctx = LocalContext.current
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).clickable { c.open(x) }) {
        SyntheticCover("", null, x.genres, Modifier.fillMaxSize(), corner = 18.dp, art = x.art(), wide = true)
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(stringResource(if (isNew) R.string.cat_hero_new else R.string.cat_hero_top).uppercase(Locale.getDefault()),
                color = Color(0xFFFFDAD6), fontSize = 12.sp, letterSpacing = 1.5.sp)
            Text(x.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(yearMinutes(x.year, x.minutes).ifEmpty { null },
                x.genres.firstOrNull()?.let { genreLabel(it).lowercase(Locale.getDefault()) }, x.age?.let { "$it+" },
                plBadge(x.audio, x.subs)).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ playItem(ctx, x, c.srv) }) {
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
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text(stringResource(R.string.cat_search_hint)) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.media_cancel)) } })
        if (query.trim().length >= 2) {
            val hits = remember(cat, query) { cat.items.filter { Cond.search(it, cat, query.trim()) } }
            if (hits.isEmpty()) Hint(stringResource(R.string.cat_results_none))
            else CoverGrid(c, hits)
            return@Column
        }
        val counts = remember(cat, c.rootTags) { BrowseCounts.of(cat, c.rootTags) }
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

        fun of(cat: CatalogData, rootTags: Map<Int, List<String>>): BrowseCounts {
            val items = cat.items
            val langs = listOf("plaudio" to items.count { it.plAudio }, "plsubs" to items.count { it.plSubs },
                "en" to items.count { "en" in it.audio }, "ja" to items.count { "ja" in it.audio },
                "nopl" to items.count { !it.plAudio && !it.plSubs }).filter { it.second > 0 }
            return BrowseCounts(count(items.flatMap { it.genres }), count(items.flatMap { it.topics }).take(16), langs,
                count(items.mapNotNull { it.year?.let { y -> y / 10 * 10 } }).sortedBy { it.first },
                count(items.flatMap { rootTags[it.root].orEmpty() }),
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
        val l = cat.items.filter { x -> active.all { Cond.matches(it, x, cat, c.rootTags) } }
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

/** Three columns of covers with "year · minutes · ★" under each. */
@Composable
private fun CoverGrid(c: CatCtx, list: List<CatItem>) {
    LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val x = list[i]
            Column(Modifier.clickable { c.open(x) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SyntheticCover(x.title, null, x.genres, Modifier.fillMaxWidth().aspectRatio(2f / 3f), badge = plBadge(x.audio, x.subs), art = x.art(),
                    titleSize = 13.sp, padding = 7.dp)
                Text(listOfNotNull(yearMinutes(x.year, x.minutes).ifEmpty { null }, x.rating?.let { ratingText(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Title

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleScreen(c: CatCtx, x: CatItem) {
    val cat = c.cat ?: return
    val ctx = LocalContext.current
    val similar = remember(cat, x) { similar(ctx, cat, x) }
    // a series: its episodes in order (agent 1.10+), where this phone left off, extras apart
    val canEps = x.series && x.dir != null && (Servers.get(ctx, c.srv) ?: c.api.srv).libraryEpisodes
    var eps by remember(x.id) { mutableStateOf<Episodes?>(null) }
    if (canEps) LaunchedEffect(x.id) { eps = withContext(Dispatchers.IO) { runCatching { c.api.episodes(x.root, x.dir!!) }.getOrNull() } }
    val back = rememberResumedTick()
    val settings = remember { Settings(ctx) }
    val list = eps?.episodes.orEmpty()
    val cont = remember(eps, back) { if (list.isEmpty()) -1 else continueIndex(settings, c.srv, x.root, list) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                SyntheticCover(x.title, null, x.genres, Modifier.fillMaxSize(), titleSize = 34.sp, corner = 0.dp, padding = 20.dp, art = x.art(), wide = true)
                BackOnCover(c.pop)
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
                    if (x.series && x.episodes > 0) InfoBadge(pluralStringResource(R.plurals.cat_episodes, x.episodes, x.episodes))
                }
                Spacer(Modifier.height(4.dp))
                if (cont >= 0) {
                    val e = list[cont]
                    val started = settings.position(MediaRef.InLibrary(x.root, e.path, c.srv).key()) > 0
                    MediaActions(c.api, MediaRef.InLibrary(x.root, e.path, c.srv), c.snack, c.onPlayingOnPc, big = true,
                        onPlay = { playEpisode(ctx, c.srv, x.root, list, cont) },
                        playLabel = stringResource(if (started) R.string.cat_play_resume_ep else R.string.cat_play_ep,
                            e.label.ifEmpty { (cont + 1).toString() }))
                } else MediaActions(c.api, x.ref(c.srv), c.snack, c.onPlayingOnPc, imdb = x.imdb.takeIf { !x.series }, big = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    x.genres.forEach { g -> AssistChip({ c.push(Nav.results(listOf("genre:$g"))) }, { Text(genreLabel(g)) }) }
                    x.topics.forEach { q -> cat.topics[q]?.let { t -> AssistChip({ c.push(Nav.results(listOf("topic:$q"))) }, { Text(t) }) } }
                    c.rootTags[x.root].orEmpty().forEach { t -> AssistChip({ c.push(Nav.results(listOf("tag:$t"))) }, { Text("#" + tagLabel(t)) }) }
                }
                if (x.audio.isNotEmpty()) MetaRow(stringResource(R.string.cat_audio)) { Text(x.audio.distinct().map { langName(it) }.joinToString()) }
                if (x.subs.isNotEmpty()) MetaRow(stringResource(R.string.cat_subtitles)) { Text(x.subs.distinct().map { langName(it) }.joinToString()) }
                if (x.directors.isNotEmpty()) MetaRow(stringResource(R.string.cat_directed)) { PeopleLinks(x.directors.map { it to cat.person(it) }, c) }
                MetaRow(stringResource(R.string.cat_where)) { Text(x.rootName + " · " + humanBytes(x.size)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (x.dir != null) OutlinedButton({ c.onOpenFolder(x.root, x.dir) }) {
                        Text(stringResource(if (x.series) R.string.cat_episodes_open else R.string.cat_open_folder))
                    }
                    if (x.imdb != null) OutlinedButton({ c.push(Nav.next(x.imdb)) }) { Text(stringResource(R.string.cat_what_next)) }
                }
            }
        }
        if (list.isNotEmpty()) item { EpisodeList(c, x, list, eps?.extras.orEmpty(), cont, settings, back) }
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
    }
}

/** Shared actors and directors count most, then topics, then genres. Genres alone need two specific ones in common
 *  ("Drama" says little), and kids' titles stay with kids' titles. The reason is the strongest link. */
private fun similar(ctx: android.content.Context, cat: CatalogData, x: CatItem): List<Pair<CatItem, String>> =
    cat.items.asSequence().filter { it.id != x.id && it.kids == x.kids }.mapNotNull { y ->
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
private fun EpisodeList(c: CatCtx, x: CatItem, list: List<Episode>, extras: List<Episode>, cont: Int, settings: Settings, back: Int) {
    val ctx = LocalContext.current
    val seasons = remember(list) { list.map { it.season }.distinct() }
    var season by rememberSaveable(x.id) { mutableStateOf<Int?>(null) }
    val shown = season ?: list.getOrNull(cont.coerceAtLeast(0))?.season ?: seasons.first()
    var showExtras by rememberSaveable(x.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(R.string.cat_episodes_title))
        if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons, key = { it ?: -1 }) { s ->
                FilterChip(s == shown, { season = s }, { Text(s?.let { stringResource(R.string.cat_season_n, it) } ?: stringResource(R.string.cat_season_other)) })
            }
        }
        list.forEachIndexed { i, e ->
            if (e.season != shown) return@forEachIndexed
            val k = MediaRef.InLibrary(x.root, e.path, c.srv).key()
            val pos = remember(k, back) { settings.position(k) }
            val watched = remember(k, back) { settings.watchedAt(k) > 0 && pos <= 0 }
            val total = remember(k, back) { OfflineStore.get(ctx, k)?.durationMs ?: 0L }
            Row(Modifier.fillMaxWidth().clickable { playEpisode(ctx, c.srv, x.root, list, i) }
                .background(if (i == cont) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp),
                    tint = if (i == cont) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(listOf(e.episode?.let { "E$it" } ?: e.label, e.shortName).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    if (pos > 0 && total > 0) LinearProgressIndicator(progress = { (pos.toFloat() / total).coerceIn(0f, 1f) },
                        Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp))
                    else if (pos > 0) Text(stringResource(R.string.cat_ep_at, pos / 60_000), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (watched) Text("✓", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        if (extras.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { showExtras = !showExtras }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cat_extras_n, extras.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showExtras) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (showExtras) extras.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { playEpisode(ctx, c.srv, x.root, listOf(e), 0) }
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

/** A title that is not on the disks: the wishlist, the user's own search engines, pasting a magnet. */
@Composable
private fun MissingScreen(c: CatCtx, imdb: String, why: String?) {
    val ctx = LocalContext.current
    var info by remember(imdb) { mutableStateOf<TitleInfo?>(null) }
    var error by remember(imdb) { mutableStateOf<String?>(null) }
    var engines by remember { mutableStateOf<List<SearchEngine>>(emptyList()) }
    var chooser by remember { mutableStateOf(false) }
    LaunchedEffect(imdb) {
        try { info = withContext(Dispatchers.IO) { c.api.catalogTitle(imdb) } } catch (e: ApiException) { error = e.message }
    }
    LaunchedEffect(Unit) { engines = withContext(Dispatchers.IO) { runCatching { c.api.searchEngines() }.getOrDefault(emptyList()) } }
    val t = info ?: return Column {
        Row { BackOnCover(c.pop) }
        Hint(error ?: stringResource(R.string.media_loading))
    }
    val cat = c.cat
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            SyntheticCover(t.title, null, t.genres, Modifier.fillMaxSize(), missing = !t.have, titleSize = 30.sp, corner = 0.dp, padding = 20.dp)
            BackOnCover(c.pop)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.have) {
                InfoBadge(stringResource(R.string.cat_on_disks).uppercase(Locale.getDefault()))
            } else {
                Text(stringResource(R.string.cat_not_on_disks).uppercase(Locale.getDefault()),
                    Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(ratingLine(t.year, t.minutes, t.rating, t.votes), style = MaterialTheme.typography.bodyMedium)
            val g = listOfNotNull(t.genres.joinToString(", ") { genreLabel(ctx, it).lowercase(Locale.getDefault()) }.ifEmpty { null }, t.age?.let { "$it+" })
            if (g.isNotEmpty()) Text(g.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (t.have) {
                val copy = t.copies.firstOrNull()
                if (copy != null) Button({ c.open(cat?.byId(copy.id) ?: copy) }, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.cat_open_title))
                }
            } else {
                val listed = c.listed(imdb)
                val toggle = { c.toggleWish(ctx, imdb, t.title, t.year, t.genres, why) }
                if (listed) FilledTonalButton({ toggle() }, Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Bookmark, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cat_want_on))
                } else Button({ toggle() }, Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.BookmarkBorder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cat_want_add))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (engines.isNotEmpty()) Box(Modifier.weight(1f)) {
                        OutlinedButton({ if (engines.size == 1) openSearch(ctx, engines[0], t) else chooser = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cat_search))
                        }
                        DropdownMenu(chooser, { chooser = false }) {
                            engines.forEach { e -> DropdownMenuItem({ Text(e.name) }, { chooser = false; openSearch(ctx, e, t) }) }
                        }
                    }
                    OutlinedButton({
                        if (!pasteMagnet(ctx)) c.scopeSnack(ctx.getString(R.string.main_no_magnet_in_clipboard))
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.main_paste_magnet))
                    }
                }
                Text(stringResource(if (engines.isEmpty()) R.string.cat_missing_hint_no_search else R.string.cat_missing_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.directors.isNotEmpty()) MetaRow(stringResource(R.string.cat_directed)) { PeopleLinks(t.directors, c) }
            if (why != null) MetaRow(stringResource(R.string.cat_why)) { Text(why) }
        }
        if (t.cast.isNotEmpty()) {
            SectionTitle(stringResource(R.string.cat_cast))
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(t.cast.take(20), key = { it.first }) { (q, name, role) ->
                    val n = cat?.items?.count { q in it.cast } ?: 0
                    PersonTile(name, if (n > 0) stringResource(R.string.cat_in_collection_n, n) else role) { c.push(Nav.person(q)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openSearch(ctx: Context, e: SearchEngine, t: TitleInfo) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(e.urlFor(t.title, t.year, t.imdb)))) }
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
                    onClick = { if (m.have && m.id != null) c.push(Nav.title(m.id)) else c.push(Nav.missing(m.imdb, whyText)) }) {
                    if (m.have && item != null) FilledTonalButton({ playItem(ctx, item, c.srv) }) {
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

// ---------------------------------------------------------------- search engines (server settings)

/**
 * Search engines for titles that are not on the disks (`/api/config/search`): set by the user, none by default. The app
 * only opens the address in the browser; it never queries anything itself and suggests no sites.
 */
@Composable
fun SearchEnginesDialog(api: Api, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SearchEngine>?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { list = withContext(Dispatchers.IO) { api.searchEngines() } } catch (e: ApiException) { error = e.message; list = emptyList() }
    }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cat_searches)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cat_searches_intro, api.srv.name), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val l = list
                if (l == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (l.isEmpty()) Text(stringResource(R.string.cat_searches_empty), style = MaterialTheme.typography.bodyMedium)
                else l.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                            Text(e.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ save(l.filterIndexed { j, _ -> j != i }) }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.media_remove))
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.cat_searches_add), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.cat_searches_name)) },
                    placeholder = { Text(stringResource(R.string.cat_searches_name_hint)) })
                OutlinedTextField(url, { url = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.cat_searches_url)) },
                    placeholder = { Text("https://example.org/search?q={title} {year}") },
                    isError = url.isNotBlank() && !SearchEngine.valid("x", url))
                Text(stringResource(R.string.cat_searches_vars), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ok = SearchEngine.valid(name, url) && (list?.size ?: 0) < 10
                FilledTonalButton({ save(list.orEmpty() + SearchEngine(name.trim(), url.trim())) { name = ""; url = "" } },
                    enabled = ok && !busy && list != null) { Text(stringResource(R.string.cat_searches_add)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_close)) } },
    )
}

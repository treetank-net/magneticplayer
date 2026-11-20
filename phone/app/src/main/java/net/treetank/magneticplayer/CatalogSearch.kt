package net.treetank.magneticplayer

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// D1 of docs/plan-ux-tasks.md: the Library's search on every tab (Discover, Browse, Folders) in groups: "on your
// disks", "you don't have it · from the film database" (the agent's local IMDb index, agent 1.16.1+,
// `features.catalog_search`) and people. A "don't have" row opens the missing title's page (D2, CatalogScreens.kt).

/** Agent 1.16.1+: `GET /api/catalog/search` and `about` on a missing title's page. */
val Server.hasCatalogSearch: Boolean get() = info?.optJSONObject("features")?.optBoolean("catalog_search", false) ?: false

/** A title from the agent's IMDb index (`/api/catalog/search` item). */
data class DbTitle(
    val imdb: String, val title: String, val year: Int?, val end: Int?, val kind: String, val genres: List<String>,
    val rating: Double?, val minutes: Int?, val have: Boolean, val id: String?,
)

/** Titles from the index whose title starts with [q] (the server's answer and whether it has an index at all). */
fun Api.catalogSearch(q: String, limit: Int = 20): Pair<List<DbTitle>, Boolean> {
    val o = JSONObject(request("GET", "/api/catalog/search?q=" + Uri.encode(q) + "&limit=$limit", timeout = 10000))
    val a = o.optJSONArray("items") ?: JSONArray()
    fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    val list = List(a.length()) { i ->
        val x = a.getJSONObject(i)
        DbTitle(x.optString("imdb"), x.optString("title"), x.int("year"), x.int("end"), x.optString("kind", "movie"),
            x.optJSONArray("genres")?.let { g -> List(g.length()) { g.optString(it) } } ?: emptyList(),
            if (x.isNull("rating") || !x.has("rating")) null else x.optDouble("rating").takeIf { !it.isNaN() },
            x.int("minutes"), x.optBoolean("have"), x.optStr("id"))
    }
    return list to o.optBoolean("index", true)
}

/** The query shared by the Library's tabs (kept by CatalogArea), and the catalog context the Folders tab needs to
 *  show the "don't have" group. Null outside a server with a catalog. */
class CatSearch(val c: CatCtx, val query: MutableState<String>)

val LocalCatSearch = androidx.compose.runtime.compositionLocalOf<CatSearch?> { null }

/** The search field on top of a Library tab; [hint] adds the line saying where it searches (D1). */
@Composable
fun LibrarySearchField(query: String, onQuery: (String) -> Unit, placeholder: String = stringResource(R.string.srch_field),
                       hint: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.media_cancel)) } })
        if (hint && query.isNotEmpty()) Text(stringResource(R.string.srch_hint), Modifier.padding(start = 4.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A group header: "ON YOUR DISKS · 1", "PEOPLE". */
@Composable
fun SearchGroupLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** D1 on Discover and Browse: titles from the catalog, then the film database, then people. */
@Composable
fun CatalogSearchResults(c: CatCtx, cat: CatalogData, query: String) {
    val have = remember(cat, query) {
        val q = norm(query)
        cat.items.filter { Cond.search(it, cat, query) }
            .sortedWith(compareByDescending<CatItem> { norm(it.title) == q || (it.titlePl?.let { t -> norm(t) == q } ?: false) }
                .thenByDescending { it.rating ?: 0.0 })
    }
    val people = remember(cat, query) {
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        cat.people.entries.filter { (_, name) -> val n = norm(name); words.all { it in n } }.map { (q, name) ->
            val acted = cat.items.count { q in it.cast }
            val directed = cat.items.count { q in it.directors }
            Triple(q, name, acted to directed)
        }.filter { it.third.first + it.third.second > 0 }.sortedByDescending { it.third.first + it.third.second }.take(6)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "have") { SearchGroupLabel(stringResource(R.string.srch_have, have.size)) }
        if (have.isEmpty()) item(key = "have-none") {
            Text(stringResource(R.string.srch_have_none), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(have, key = { "h/" + it.id }) { x -> HaveRow(c, x) }
        item(key = "missing") { MissingTitles(c, query) }
        if (people.isNotEmpty()) {
            item(key = "people") { SearchGroupLabel(stringResource(R.string.srch_people)) }
            items(people, key = { "p/" + it.first }) { (q, name, n) ->
                val (acted, directed) = n
                val role = stringResource(if (directed > acted) R.string.cat_director else R.string.cat_actor).lowercase(Locale.getDefault())
                Row(Modifier.fillMaxWidth().clickable { c.push(Nav.person(q)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(" · $role · " + pluralStringResource(R.plurals.srch_person_films, acted + directed, acted + directed),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A title on the disks: cover, title, "1927 · 153 min · folder", "Play" (a series opens its page). */
@Composable
private fun HaveRow(c: CatCtx, x: CatItem) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { c.open(x) }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SyntheticCover(x.title, null, x.genres, Modifier.width(48.dp).height(72.dp), art = x.art(), artSrv = x.srv,
            titleSize = 8.sp, corner = 6.dp, padding = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(x.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val server = x.srv.takeIf { it.isNotEmpty() }?.let { Servers.get(ctx, it)?.name }
            val length = if (x.series && x.episodes > 0) pluralStringResource(R.plurals.cat_episodes, x.episodes, x.episodes)
                else x.minutes?.let { "$it min" }
            Text(listOfNotNull(x.year?.toString(), length, listOfNotNull(x.rootName.ifEmpty { null }, server).joinToString(", ").ifEmpty { null })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton({
            if (x.series) c.open(x) else playOnPhone(ctx, x.ref(c.srvOf(x)), x.imdb, x.kind)
        }) { Text(stringResource(R.string.srch_play)) }
    }
}

/**
 * "YOU DON'T HAVE IT · FROM THE FILM DATABASE": titles from the agent's IMDb index matching [query] that are not in the
 * catalog, each opening the missing title's page (D2). Nothing at all when the agent is older than 1.16.1.
 */
@Composable
fun MissingTitles(c: CatCtx, query: String) {
    val ctx = LocalContext.current
    val srv = remember(c.srv) { Servers.get(ctx, c.srv) ?: c.api.srv }
    if (!srv.hasCatalogSearch) return
    var res by remember(query, c.srv) { mutableStateOf<Pair<List<DbTitle>, Boolean>?>(null) }
    LaunchedEffect(query, c.srv) {
        delay(350)  // wait until typing pauses
        res = withContext(Dispatchers.IO) { runCatching { c.api.catalogSearch(query) }.getOrNull() } ?: (emptyList<DbTitle>() to true)
    }
    val cat = c.cat
    Column {
        SearchGroupLabel(stringResource(R.string.srch_missing))
        val r = res
        when {
            r == null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.srch_missing_loading), style = MaterialTheme.typography.bodySmall)
            }
            !r.second -> Text(stringResource(R.string.srch_no_index), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val list = r.first.filter { !it.have && cat?.byImdb(it.imdb) == null }
                if (list.isEmpty()) Text(stringResource(R.string.srch_missing_none), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { m -> MissingRow(c, m) }
            }
        }
    }
}

/** A title you don't have: a greyed cover, title, "2001 · animation · 108 min · ★ 7.2", "want" when on the list. */
@Composable
private fun MissingRow(c: CatCtx, m: DbTitle) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { c.push(Nav.missing(m.imdb)) }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SyntheticCover(m.title, null, m.genres, Modifier.width(48.dp).height(72.dp), missing = true, titleSize = 8.sp, corner = 6.dp,
            padding = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val years = if (m.kind == "series" && m.year != null && m.end != null && m.end != m.year) "${m.year}–${m.end}" else m.year?.toString()
            val kind = if (m.kind == "series") stringResource(R.string.srch_series)
                else m.genres.firstOrNull()?.let { genreLabel(ctx, it).lowercase(Locale.getDefault()) }
            Text(listOfNotNull(years, kind, m.minutes?.takeIf { m.kind != "series" }?.let { "$it min" }, m.rating?.let { ratingText(it) })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c.listed(m.imdb)) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.srch_want_tag), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * D2 "SEARCH": one button per household search site (every kind, subtitles too) with [phrase], opened in the app or
 * the browser as the site is set, and "Set up search sites ›" in the same row; with no site only the link.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineRow(engines: List<SearchEngine>, phrase: String, imdb: String?, onSetUp: () -> Unit) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        engines.forEach { e ->
            OutlinedButton({ openEngine(ctx, e, phrase, imdb) }, Modifier.height(44.dp)) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(e.name)
            }
        }
        TextButton(onSetUp, Modifier.height(44.dp)) { Text(stringResource(R.string.st4_set_up_engines)) }
    }
}

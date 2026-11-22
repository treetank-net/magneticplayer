package net.treetank.magneticplayer

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// G4 of docs/plan-ux-tasks.md: Discover's row "Because you watched <title>: not on your disks". The agent (1.18+,
// `features.suggest_missing`) picks popular titles from its local IMDb index that share people, genres and era with the
// title watched last and that no library folder has (`GET /api/catalog/suggest`). A tap opens the missing title's page
// (D2: search engines, magnet, want to watch). Older agents: no row.

/** Agent 1.18+: `GET /api/catalog/suggest`. */
val Server.hasSuggestMissing: Boolean get() = info?.optJSONObject("features")?.optBoolean("suggest_missing", false) ?: false

/** One suggestion: a title from the agent's IMDb index that is not on the disks. [why]: "directed by …", "also …", genres. */
data class Suggestion(val imdb: String, val title: String, val year: Int?, val genres: List<String>, val why: String)

/** The agent's suggestions after [imdb] (the first answer may take ~10-30 s: Wikidata; later ones come from its cache). */
fun Api.catalogSuggest(imdb: String, limit: Int = 20): List<Suggestion> {
    val o = JSONObject(request("GET", "/api/catalog/suggest?imdb=" + Uri.encode(imdb) + "&limit=$limit", timeout = 60000))
    val a = o.optJSONArray("items") ?: return emptyList()
    return List(a.length()) { a.getJSONObject(it) }.filter { !it.optBoolean("have") }.map { x ->
        val g = x.optJSONArray("genres")
        Suggestion(x.optString("imdb"), x.optString("title"), if (x.isNull("year") || !x.has("year")) null else x.optInt("year"),
            g?.let { List(it.length()) { i -> it.optString(i) } } ?: emptyList(), x.optString("why"))
    }
}

/** Answers kept for this process (server key + IMDb id → time, list): Discover is rebuilt on every return to it. */
private object SuggestCache {
    private val map = HashMap<String, Pair<Long, List<Suggestion>>>()
    @Synchronized fun get(k: String): List<Suggestion>? = map[k]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.second
    @Synchronized fun put(k: String, v: List<Suggestion>) { map[k] = System.currentTimeMillis() to v }
}

/**
 * Suggestions after [x] (the title watched last), asked from the server that has [x]; titles any merged server has are
 * left out. Null while loading, without an IMDb id, on older agents or with fewer than 3 suggestions (then no row).
 */
@Composable
fun rememberSuggestMissing(c: CatCtx, cat: CatalogData, x: CatItem?): List<Suggestion>? {
    val imdb = x?.imdb
    val api = x?.let { c.apiOf(it) }
    val key = if (imdb != null && api != null && api.srv.hasSuggestMissing) api.srv.key + "/" + imdb else null
    val list by produceState(key?.let { SuggestCache.get(it) }, key) {
        if (value == null && key != null && api != null && imdb != null) value = withContext(Dispatchers.IO) {
            runCatching { api.catalogSuggest(imdb) }.getOrNull()?.also { SuggestCache.put(key, it) }
        }
    }
    if (key == null) return null
    return list?.filter { cat.byImdb(it.imdb) == null }?.takeIf { it.size >= 3 }
}

/** The row "Because you watched <[x]>: not on your disks"; a tap opens the missing title's page (D2). */
@Composable
fun SuggestMissingRow(c: CatCtx, x: CatItem, shown: List<Suggestion>) {
    val want = stringResource(R.string.suggest_want)
    CoverRow(stringResource(R.string.suggest_row, x.title), null, shown, null, { it.imdb }) { s ->
        Column(Modifier.width(112.dp).clickable { c.push(Nav.missing(s.imdb, s.why.ifEmpty { null })) },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SyntheticCover(s.title, s.year, s.genres, Modifier.width(112.dp).height(168.dp), missing = true)
            Text(listOfNotNull(want.takeIf { c.listed(s.imdb) }, s.why.ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

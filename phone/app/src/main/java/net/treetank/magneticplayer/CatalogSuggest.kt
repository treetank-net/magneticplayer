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
 * Seeds of Discover's suggestion rows, picked on the phone (the agent only answers per title): [seen] for "Similar to"
 * (titles on the disks) and [seenMissing] for "After watching" (titles that aren't), each at random from the last ten
 * titles watched and different from each other, and [disk] for "Because you have" (a well rated title on the disks
 * not watched yet). The same for a day and the same history: a change of history (another title watched) or of the
 * day picks again; returning to the screen doesn't.
 */
data class DiscoverSeeds(val seen: CatItem?, val seenMissing: CatItem?, val disk: CatItem?)

fun discoverSeeds(recentlySeen: List<CatItem>, cat: CatalogData, hasSimilar: (CatItem) -> Boolean): DiscoverSeeds {
    val recent = recentlySeen.distinctBy { it.imdb ?: it.id }.take(10)
    val rnd = kotlin.random.Random(java.time.LocalDate.now().toEpochDay() * 31 + recent.map { it.imdb ?: it.id }.hashCode())
    val order = recent.shuffled(rnd)
    val seen = order.firstOrNull(hasSimilar)
    val seenMissing = order.filter { it.imdb != null }.let { l -> l.firstOrNull { it.id != seen?.id } }
    val seenImdb = recent.mapNotNull { it.imdb }.toSet()
    val disk = cat.items.filter { it.imdb != null && it.imdb !in seenImdb && (it.rating ?: 0.0) >= 7.0 && (it.votes ?: 0) >= 1000 }
        .distinctBy { it.imdb }.sortedBy { it.imdb }.randomOrNull(rnd)
    return DiscoverSeeds(seen, seenMissing, disk)
}

/**
 * Suggestions after [x] (a title watched lately, one on the disks or the title on screen), asked from the server that has [x]; titles any merged server has are
 * left out. Null while loading, without an IMDb id, on older agents or with fewer than 3 suggestions (then no row).
 */
@Composable
fun rememberSuggestMissing(c: CatCtx, cat: CatalogData, x: CatItem?): List<Suggestion>? {
    val imdb = x?.imdb
    val api = x?.let { c.apiOf(it) }
    val key = if (imdb != null && api != null && api.srv.hasSuggestMissing) api.srv.key + "/" + imdb else null
    val list by produceState(key?.let { SuggestCache.get(it) }, key) {
        // produceState keeps its state across keys: without this a new title kept showing the previous title's list
        value = key?.let { SuggestCache.get(it) }
        if (value == null && key != null && api != null && imdb != null) value = withContext(Dispatchers.IO) {
            runCatching { api.catalogSuggest(imdb) }.getOrNull()?.also { SuggestCache.put(key, it) }
        }
    }
    if (key == null) return null
    return list?.filter { cat.byImdb(it.imdb) == null }?.takeIf { it.size >= 3 }
}

/** A row of titles that aren't on the disks under [label] (their covers say so); a tap opens the title's page (D2). */
@Composable
fun SuggestMissingRow(c: CatCtx, label: String, shown: List<Suggestion>) {
    val want = stringResource(R.string.suggest_want)
    CoverRow(label, null, shown, null, { it.imdb }) { s ->
        Column(Modifier.width(112.dp).clickable { c.push(Nav.missing(s.imdb, s.why.ifEmpty { null })) },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SyntheticCover(s.title, s.year, s.genres, Modifier.width(112.dp).height(168.dp), missing = true)
            Text(listOfNotNull(want.takeIf { c.listed(s.imdb) }, s.why.ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

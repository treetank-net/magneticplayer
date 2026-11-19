package net.treetank.magneticplayer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// The library as films (agent 1.8+, `features.catalog`): models, API calls, the in-memory cache per server, filters.
// Screens: CatalogScreens.kt, building blocks: CatalogUi.kt. API: docs/api.md "Catalog: films instead of folders".

/** A title on the disks (`/api/catalog` item). `path` is the main video (a series: its first episode). */
data class CatItem(
    val id: String, val root: Int, val rootName: String, val path: String, val dir: String?,
    val title: String, val titlePl: String?, val year: Int?, val kind: String, val imdb: String?,
    val genres: List<String>, val rating: Double?, val votes: Int?, val minutes: Int?, val height: Int?,
    val audio: List<String>, val subs: List<String>, val size: Long, val added: Double, val kids: Boolean,
    val episodes: Int, val age: Int?, val cast: List<String>, val roles: List<String?>, val directors: List<String>,
    val topics: List<String>,
) {
    val series get() = kind == "series"
    fun ref(srv: String) = MediaRef.InLibrary(root, path, srv)
    /** Suitable for children up to [maxAge]: a known minimum age, or an unknown one in a kids' folder. */
    fun fitsAge(maxAge: Int) = age?.let { it <= maxAge } ?: kids
    val plAudio get() = "pl" in audio
    val plSubs get() = "pl" in subs
}

/** The whole catalog of one server; `people` and `topics` map Wikidata ids to names. */
data class CatalogData(
    val version: String, val scanning: Boolean, val done: Int, val total: Int, val items: List<CatItem>,
    val people: Map<String, String>, val topics: Map<String, String>,
) {
    fun byId(id: String) = items.firstOrNull { it.id == id }
    fun byImdb(imdb: String) = items.firstOrNull { it.imdb == imdb }
    fun person(q: String) = people[q] ?: q
}

/** A suggestion or a film of a person that may not be on the disks (`/api/catalog/next`, `person.missing`). */
data class CatCard(
    val imdb: String, val title: String, val titlePl: String?, val year: Int?, val genres: List<String>,
    val rating: Double?, val votes: Int?, val minutes: Int?, val why: String, val reason: String?,
    val have: Boolean, val id: String?, val root: Int?, val path: String?,
)

/** `/api/catalog/title`: a title by IMDb id, on the disks or not. */
data class TitleInfo(
    val imdb: String, val title: String, val year: Int?, val kind: String?, val genres: List<String>, val rating: Double?,
    val votes: Int?, val minutes: Int?, val have: Boolean, val copies: List<CatItem>,
    val cast: List<Triple<String, String, String?>>, val directors: List<Pair<String, String>>,
    val topics: List<Pair<String, String>>, val age: Int?,
)

data class PersonFilm(val item: CatItem, val role: String?, val asDirector: Boolean)
data class PersonInfo(val id: String, val name: String, val role: String?, val films: List<PersonFilm>, val missing: List<CatCard>)

/** One entry of the shared "want to watch" list. */
data class WishItem(
    val imdb: String, val title: String, val year: Int?, val genres: List<String>, val why: String, val by: String?,
    val at: Double, val have: Boolean, val id: String?, val where: String?,
)

/** A search engine set by the user on the server: `url` with `{title}`, `{year}`, `{imdb}`. */
data class SearchEngine(val name: String, val url: String) {
    /** The address with the film's data URL-encoded into the template. */
    fun urlFor(title: String, year: Int?, imdb: String?): String = url
        .replace("{title}", Uri.encode(title)).replace("{year}", year?.toString() ?: "")
        .replace("{imdb}", imdb.orEmpty())

    companion object {
        /** Same rule as the agent: http(s) address with `{title}` or `{imdb}`. */
        fun valid(name: String, url: String) = name.isNotBlank() && name.trim().length <= 40 &&
            Regex("""^https?://[^\s/]+.*""").matches(url.trim()) && Regex("""\{(title|imdb)\}""").containsMatchIn(url) &&
            url.trim().length <= 500
    }
}

/** A target folder for a file sent from a phone (`targets` in `/api/uploads`); `here` = where it lies now. */
data class UpTarget(val root: Int, val name: String, val sub: String, val free: Long, val tags: List<String>, val here: Boolean) {
    fun asDl() = DlTarget(root, name, sub, free, tags, false)
}

/** A file a phone sent to the server (`/api/uploads`). Times are epoch seconds. */
data class UploadItem(
    val id: String, val name: String, val device: String?, val size: Long, val at: Double, val state: String,
    val to: String?, val movedAt: Double?, val error: String?, val guess: JSONObject?, val targets: List<UpTarget>, val pick: Int?,
) {
    val guessTag get() = guess?.optStr("tag")
    val guessTitle get() = guess?.optStr("title")
    val guessYear get() = guess?.optInt("year", 0)?.takeIf { it > 0 }
}

data class PhoneUploads(val new: Int, val items: List<UploadItem>)

// ---------------------------------------------------------------- parsing

private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun catItem(x: JSONObject) = CatItem(
    x.optString("id"), x.optInt("root"), x.optString("root_name"), x.optString("path"), x.optStr("dir"),
    x.optString("title"), x.optStr("title_pl"), x.optIntOrNull("year"), x.optString("kind", "movie"), x.optStr("imdb"),
    x.optJSONArray("genres").strings(), x.optDoubleOrNull("rating"), x.optIntOrNull("votes"), x.optIntOrNull("minutes"),
    x.optIntOrNull("height"), x.optJSONArray("audio").strings(), x.optJSONArray("subs").strings(), x.optLong("size"),
    x.optDouble("added", 0.0), x.optBoolean("kids"), x.optInt("episodes"), x.optIntOrNull("age"),
    x.optJSONArray("cast").strings(),
    x.optJSONArray("roles")?.let { a -> List(a.length()) { if (a.isNull(it)) null else a.optString(it).ifEmpty { null } } } ?: emptyList(),
    x.optJSONArray("directors").strings(), x.optJSONArray("topics").strings(),
)

private fun card(x: JSONObject) = CatCard(
    x.optString("imdb"), x.optString("title"), x.optStr("title_pl"), x.optIntOrNull("year"), x.optJSONArray("genres").strings(),
    x.optDoubleOrNull("rating"), x.optIntOrNull("votes"), x.optIntOrNull("minutes"), x.optString("why"), x.optStr("reason"),
    x.optBoolean("have"), x.optStr("id"), x.optIntOrNull("root"), x.optStr("path"),
)

private fun JSONObject.map(k: String): Map<String, String> =
    optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

private fun objects(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getJSONObject(i) } } ?: emptyList()

// ---------------------------------------------------------------- API (agent 1.8+)

fun Api.catalog(since: String?): CatalogData? {
    val o = JSONObject(request("GET", "/api/catalog" + (since?.let { "?since=" + Uri.encode(it) } ?: ""), timeout = 30000))
    if (o.optBoolean("same")) return null
    return CatalogData(o.optString("version"), o.optBoolean("scanning"), o.optInt("done"), o.optInt("total"),
        objects(o.optJSONArray("items")).map(::catItem), o.map("people"), o.map("topics"))
}

fun Api.catalogTitle(imdb: String): TitleInfo {
    val o = JSONObject(request("GET", "/api/catalog/title?imdb=" + Uri.encode(imdb), timeout = 30000))
    fun pairs(k: String): List<JSONArray> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getJSONArray(it) } } ?: emptyList()
    return TitleInfo(o.optString("imdb", imdb), o.optString("title", imdb), o.optIntOrNull("year"), o.optStr("kind"),
        o.optJSONArray("genres").strings(), o.optDoubleOrNull("rating"), o.optIntOrNull("votes"), o.optIntOrNull("minutes"),
        o.optBoolean("have"), objects(o.optJSONArray("copies")).map(::catItem),
        pairs("cast").map { Triple(it.optString(0), it.optString(1), if (it.isNull(2)) null else it.optString(2).ifEmpty { null }) },
        pairs("directors").map { it.optString(0) to it.optString(1) },
        pairs("topics").map { it.optString(0) to it.optString(1) }, o.optIntOrNull("age"))
}

fun Api.catalogPerson(qid: String): PersonInfo {
    val o = JSONObject(request("GET", "/api/catalog/person?id=" + Uri.encode(qid), timeout = 45000))
    return PersonInfo(o.optString("id", qid), o.optString("name", qid), o.optStr("role"),
        objects(o.optJSONArray("films")).map { PersonFilm(catItem(it), it.optStr("role"), it.optBoolean("as_director")) },
        objects(o.optJSONArray("missing")).map(::card))
}

/** "What next" after a film; the first answer may take ~10 s (Wikidata). */
fun Api.catalogNext(imdb: String): List<CatCard> =
    objects(JSONObject(request("GET", "/api/catalog/next?imdb=" + Uri.encode(imdb), timeout = 60000)).optJSONArray("items")).map(::card)

private fun wishes(o: JSONObject) = objects(o.optJSONArray("items")).map {
    WishItem(it.optString("imdb"), it.optString("title"), it.optIntOrNull("year"), it.optJSONArray("genres").strings(),
        it.optString("why"), it.optStr("by"), it.optDouble("at", 0.0), it.optBoolean("have"), it.optStr("id"), it.optStr("where"))
}

fun Api.wishlist(): List<WishItem> = wishes(JSONObject(request("GET", "/api/wishlist")))
fun Api.wishAdd(imdb: String, title: String, year: Int?, genres: List<String>, why: String?): List<WishItem> =
    wishes(JSONObject(request("POST", "/api/wishlist", JSONObject().put("imdb", imdb).put("title", title)
        .put("year", year ?: JSONObject.NULL).put("genres", JSONArray(genres)).put("why", why.orEmpty()))))
fun Api.wishRemove(imdb: String): List<WishItem> = wishes(JSONObject(request("POST", "/api/wishlist/remove", JSONObject().put("imdb", imdb))))

private fun engines(o: JSONObject) = objects(o.optJSONArray("engines")).map { SearchEngine(it.optString("name"), it.optString("url")) }
fun Api.searchEngines(): List<SearchEngine> = engines(JSONObject(request("GET", "/api/config/search")))
fun Api.setSearchEngines(list: List<SearchEngine>): List<SearchEngine> = engines(JSONObject(request("POST", "/api/config/search",
    JSONObject().put("engines", JSONArray(list.map { JSONObject().put("name", it.name).put("url", it.url) })))))

fun Api.uploads(): PhoneUploads {
    val o = JSONObject(request("GET", "/api/uploads", timeout = 15000))
    return PhoneUploads(o.optInt("new"), objects(o.optJSONArray("items")).map { x ->
        UploadItem(x.optString("id"), x.optString("name"), x.optStr("device"), x.optLong("size"), x.optDouble("at", 0.0),
            x.optString("state"), x.optStr("to"), x.optDoubleOrNull("moved_at"), x.optStr("error"), x.optJSONObject("guess"),
            objects(x.optJSONArray("targets")).map { t ->
                UpTarget(t.optInt("root"), t.optString("name"), t.optString("sub"), t.optLong("free"),
                    t.optJSONArray("tags").strings(), t.optBoolean("here"))
            }, x.optIntOrNull("pick"))
    })
}

/** Moves a file sent from a phone into a library folder; `learn`: next time this title goes to a folder with this tag. */
fun Api.uploadMove(u: UploadItem, t: UpTarget, learn: Boolean) {
    request("POST", "/api/uploads/move", JSONObject().put("id", u.id).put("root", t.root).put("sub", t.sub).put("learn", learn)
        .apply { if (u.guess != null) put("guess", u.guess) }, 20000)
}
fun Api.uploadKeep(id: String) { request("POST", "/api/uploads/keep", JSONObject().put("id", id)) }

// ---------------------------------------------------------------- cache

/** The catalog in memory per server, refreshed with `?since=<version>` (an unchanged catalog costs one tiny answer). */
object CatalogCache {
    private val data = java.util.concurrent.ConcurrentHashMap<String, CatalogData>()
    /** Bumped on every change, so screens can recompute. */
    val version = mutableStateOf(0)

    fun cached(srv: String): CatalogData? = data[srv]

    /** Fetches what changed (blocking, call off the main thread). */
    fun refresh(api: Api): CatalogData {
        val old = data[api.srv.key]
        val fresh = api.catalog(old?.version) ?: return old!!
        data[api.srv.key] = fresh
        version.value++
        return fresh
    }

    /** The catalog item that a library video belongs to (its main video, or an episode inside a series folder). */
    fun itemFor(srv: String, root: Int, path: String): CatItem? = data[srv]?.items?.let { list ->
        list.firstOrNull { it.root == root && it.path == path }
            ?: list.firstOrNull { it.root == root && it.dir != null && path.startsWith(it.dir + "/") }
    }
}

/**
 * A video watched part of the way on this phone, matched to a catalog item (Continue watching). `posMs` 0: a series
 * whose episode at `path` was watched to the end; the row shows the next one (or drops the series after its last).
 */
data class Resume(val item: CatItem, val path: String, val posMs: Long, val at: Long)

/** Positions saved on this phone that belong to items of this server, most recent first. */
fun resumes(ctx: Context, srv: String, cat: CatalogData, withDone: Boolean = false): List<Resume> {
    val prefix = Servers.prefixOf(srv) + "lib"
    val byRoot = cat.items.groupBy { it.root }
    val s = Settings(ctx)
    // a series episode watched to the end keeps the series in the row (its next episode), like an interrupted one
    val finished = s.watchedAll().filterKeys { it.startsWith(prefix) }.mapValues { 0L to it.value }
    return (finished + s.positions()).mapNotNull { (key, v) ->
        if (!key.startsWith(prefix)) return@mapNotNull null
        val rest = key.removePrefix(prefix)
        val root = rest.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
        val path = rest.substringAfter('/', "")
        val items = byRoot[root] ?: return@mapNotNull null
        val stem = Settings.stemOf(path)
        val item = items.firstOrNull { it.path == path }
            // re-encoded on the server: same name, another extension (Settings.follow moves the position on first read)
            ?: items.firstOrNull { stem != null && it.dir == null && Settings.stemOf(it.path) == stem }
            ?: items.firstOrNull { it.dir != null && path.startsWith(it.dir + "/") } ?: return@mapNotNull null
        if (v.first <= 0 && item.dir == null && !withDone) return@mapNotNull null  // a film watched to the end is done
        Resume(item, if (item.path != path && item.dir == null) item.path else path, v.first, v.second)
    }.sortedByDescending { it.at }.distinctBy { it.item.id }
}

// ---------------------------------------------------------------- filters

/** Lowercase without diacritics, for search ("Żywych" matches "zywych"). */
fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    .replace('ł', 'l').replace('Ł', 'L').lowercase()

/**
 * One filter condition of the Results screen, as a string so it survives navigation: `age:7`, `genre:Comedy`,
 * `topic:Q…`, `person:Q…`, `lang:plaudio|plsubs|en|ja|nopl`, `decade:1990`, `tag:filmy`, `q:<text>`, `short`
 * (≤ 95 min), `pl` (Polish audio or subtitles), `rated` (★ 7+), `classic` (before 1970), `kind:series`, `added`
 * (recently added, newest first).
 */
object Cond {
    fun matches(c: String, x: CatItem, cat: CatalogData, rootTags: Map<Int, List<String>>): Boolean {
        val v = c.substringAfter(':', "")
        return when (c.substringBefore(':')) {
            "age" -> x.fitsAge(v.toIntOrNull() ?: 0)
            "genre" -> v in x.genres
            "topic" -> v in x.topics
            "person" -> v in x.cast || v in x.directors
            "lang" -> when (v) {
                "plaudio" -> x.plAudio
                "plsubs" -> x.plSubs
                "nopl" -> !x.plAudio && !x.plSubs
                else -> v in x.audio
            }
            "decade" -> x.year?.let { it / 10 * 10 == v.toIntOrNull() } ?: false
            "tag" -> v in rootTags[x.root].orEmpty()
            "q" -> search(x, cat, v)
            "short" -> (x.minutes ?: Int.MAX_VALUE) <= 95
            "pl" -> x.plAudio || x.plSubs
            "rated" -> (x.rating ?: 0.0) >= 7.0
            "classic" -> (x.year ?: 9999) < 1970
            "kind" -> x.kind == v
            "added" -> true
            else -> true
        }
    }

    /** Title, Polish title, cast and directors: every word of [q] must match somewhere. */
    fun search(x: CatItem, cat: CatalogData, q: String): Boolean {
        val hay = norm(listOfNotNull(x.title, x.titlePl, x.year?.toString()).joinToString(" ") + " " +
            (x.cast + x.directors).joinToString(" ") { cat.person(it) })
        return norm(q).split(' ').filter { it.isNotBlank() }.all { it in hay }
    }
}

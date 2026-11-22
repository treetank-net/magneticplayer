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
    /** "All" in the switcher: the server this copy is on ("" = the one the catalog came from) and the same title's
     * copies on the other servers (mergeCatalogs). */
    val srv: String = "", val alsoOn: List<CatItem> = emptyList(),
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
    /** Agent 1.18+: where this answer stands, sent back as `since` for the changes only (`features.catalog_delta`). */
    val cursor: String? = null,
) {
    fun byId(id: String) = items.firstOrNull { it.id == id } ?: items.firstNotNullOfOrNull { p -> p.alsoOn.firstOrNull { it.id == id } }
        // a merged catalog ("All") prefixes ids with the server: an id from a server's answer (wishlist, "what next")
        ?: items.firstOrNull { it.srv.isNotEmpty() && it.id == "${it.srv}/$id" }
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
    /** Agent 1.17+, a title not on the disks: the lead of its Wikipedia article and where it comes from. */
    val about: String? = null, val aboutSource: String? = null,
)

data class PersonFilm(val item: CatItem, val role: String?, val asDirector: Boolean)
data class PersonInfo(val id: String, val name: String, val role: String?, val films: List<PersonFilm>, val missing: List<CatCard>)

/** One entry of the shared "want to watch" list. */
data class WishItem(
    val imdb: String, val title: String, val year: Int?, val genres: List<String>, val why: String, val by: String?,
    val at: Double, val have: Boolean, val id: String?, val where: String?,
)

/**
 * A search engine set by the user on the server: `url` with `{q}` (the phrase), `{title}`, `{year}`, `{imdb}`.
 * [kind]: "torrents" / "video" / "subtitles"; [open]: "browser" or "app" (the in-app browser, where a magnet goes to the
 * server). Agents that don't keep [kind] and [open] drop them: the phone then remembers them itself ([EngineExtras]).
 */
data class SearchEngine(val name: String, val url: String, val kind: String = "torrents", val open: String = "browser") {
    /** The address with the film's data URL-encoded into the template. */
    fun urlFor(title: String, year: Int?, imdb: String?): String = url
        .replace("{q}", Uri.encode(listOfNotNull(title, year?.toString()).joinToString(" ")))
        .replace("{title}", Uri.encode(title)).replace("{year}", year?.toString() ?: "")
        .replace("{imdb}", imdb.orEmpty())

    /** The address for a whole phrase ("Title 1927", "Title S02E05", "Title S01E03 pl"): `{q}` and `{title}` get the
     *  phrase, `{year}` stays empty (the phrase has it). */
    fun urlForPhrase(phrase: String, imdb: String? = null): String = url
        .replace("{q}", Uri.encode(phrase.trim())).replace("{title}", Uri.encode(phrase.trim())).replace("{year}", "")
        .replace("{imdb}", imdb.orEmpty())

    /** What is sent to the agent: `{q}` becomes `{title}` (agents before stage 4 accept only `{title}`/`{imdb}`). */
    fun forAgent() = copy(name = name.trim(), url = url.trim().replace("{q}", "{title}"))

    companion object {
        val KINDS = listOf("torrents", "video", "subtitles")

        /** Same rule as the agent: http(s) address with `{title}` or `{imdb}` (or `{q}`, sent as `{title}`). */
        fun valid(name: String, url: String) = name.isNotBlank() && name.trim().length <= 40 &&
            Regex("""^https?://[^\s/]+.*""").matches(url.trim()) && Regex("""\{(title|imdb|q)\}""").containsMatchIn(url) &&
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

/** The catalog when it changed since [since] (with the answer's text, for the copy on disk), null when it is the same. */
fun Api.catalog(since: String?): Pair<CatalogData, String>? {
    val raw = request("GET", "/api/catalog" + (since?.let { "?since=" + Uri.encode(it) } ?: ""), timeout = 30000)
    val o = JSONObject(raw)
    if (o.optBoolean("same")) return null
    return parseCatalog(o) to raw
}

private fun parseCatalog(o: JSONObject) = CatalogData(o.optString("version"), o.optBoolean("scanning"), o.optInt("done"),
    o.optInt("total"), objects(o.optJSONArray("items")).map(::catItem), o.map("people"), o.map("topics"), o.optStr("cursor"))

/** Agent 1.18+: the answer to `?since=<cursor>`, whole or only the changes (`delta`), null when nothing changed. */
fun Api.catalogSince(cursor: String): JSONObject? {
    val o = JSONObject(request("GET", "/api/catalog?since=" + Uri.encode(cursor), timeout = 30000))
    return if (o.optBoolean("same")) null else o
}

/**
 * A `delta` answer applied to the previous answer (both as the agent's JSON, the form kept on disk): changed and new
 * items replace theirs, `removed` ids go, names of people and topics are added, the status fields and the cursor are
 * the new answer's. Newest first, as the agent sorts a whole answer.
 */
internal fun applyCatalogDelta(old: JSONObject, d: JSONObject): JSONObject {
    val changed = objects(d.optJSONArray("items"))
    val drop = changed.map { it.optString("id") }.toSet() + d.optJSONArray("removed").strings()
    val items = (objects(old.optJSONArray("items")).filter { it.optString("id") !in drop } + changed)
        .sortedByDescending { it.optDouble("added", 0.0) }
    val out = JSONObject()
    for (k in d.keys()) if (k !in setOf("items", "removed", "people", "topics", "delta", "since")) out.put(k, d.get(k))
    fun names(k: String) = JSONObject().apply {
        for (src in listOf(old.optJSONObject(k), d.optJSONObject(k))) src?.let { o -> o.keys().forEach { put(it, o.get(it)) } }
    }
    return out.put("items", JSONArray(items)).put("people", names("people")).put("topics", names("topics"))
}

fun Api.catalogTitle(imdb: String): TitleInfo {
    val o = JSONObject(request("GET", "/api/catalog/title?imdb=" + Uri.encode(imdb), timeout = 30000))
    fun pairs(k: String): List<JSONArray> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getJSONArray(it) } } ?: emptyList()
    return TitleInfo(o.optString("imdb", imdb), o.optString("title", imdb), o.optIntOrNull("year"), o.optStr("kind"),
        o.optJSONArray("genres").strings(), o.optDoubleOrNull("rating"), o.optIntOrNull("votes"), o.optIntOrNull("minutes"),
        o.optBoolean("have"), objects(o.optJSONArray("copies")).map(::catItem),
        pairs("cast").map { Triple(it.optString(0), it.optString(1), if (it.isNull(2)) null else it.optString(2).ifEmpty { null }) },
        pairs("directors").map { it.optString(0) to it.optString(1) },
        pairs("topics").map { it.optString(0) to it.optString(1) }, o.optIntOrNull("age"), o.optStr("about"), o.optStr("about_source"))
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

/** `kind` and `open` per engine: from the agent when it keeps them, else what this phone remembered (EngineExtras). */
private fun Api.engines(o: JSONObject): List<SearchEngine> = objects(o.optJSONArray("engines")).map { e ->
    val name = e.optString("name")
    val url = e.optString("url")
    val local = EngineExtras.get(s.ctx, name, url)
    SearchEngine(name, url,
        e.optString("kind").takeIf { it in SearchEngine.KINDS } ?: local?.first ?: "torrents",
        e.optString("open").takeIf { it == "app" || it == "browser" } ?: local?.second ?: "browser")
}
fun Api.searchEngines(): List<SearchEngine> = engines(JSONObject(request("GET", "/api/config/search")))
fun Api.setSearchEngines(list: List<SearchEngine>): List<SearchEngine> {
    val sent = list.map { it.forAgent() }
    sent.forEach { EngineExtras.put(s.ctx, it.name, it.url, it.kind, it.open) }
    return engines(JSONObject(request("POST", "/api/config/search", JSONObject().put("engines", JSONArray(sent.map {
        JSONObject().put("name", it.name).put("url", it.url).put("kind", it.kind).put("open", it.open)
    })))))
}

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

/**
 * The catalog per server, in memory and on disk (`files/catalog/<server>.json`, the agent's last answer, ~0.5 MB), so
 * the library opens without the computer: the phone's last sync point. Refreshed with `?since=<version>`: an unchanged
 * catalog costs one tiny answer, a changed one comes whole; agent 1.18+ (`catalog_delta`) takes the answer's cursor
 * instead and sends only the changed and removed items, applied to the copy on disk ([applyCatalogDelta]). A refresh is
 * a sync point ([SyncPoint]): positions and "watched" marks go both ways first, downloaded copies are checked after.
 */
object CatalogCache {
    private val data = java.util.concurrent.ConcurrentHashMap<String, CatalogData>()
    /** When the copy was last confirmed by its server (epoch ms) */
    private val synced = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Bumped on every change, so screens can recompute. */
    val version = mutableStateOf(0)

    private fun file(ctx: Context, srv: String) = java.io.File(ctx.filesDir, "catalog/" + srv.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    /** A catalog of [srv] is known (in memory or on disk), so the library can open without the server. */
    fun hasCopy(ctx: Context, srv: String): Boolean = data.containsKey(srv) || file(ctx, srv).exists()

    /** In memory only (no disk read: safe on the main thread). */
    fun peek(srv: String): CatalogData? = data[srv]

    /** In memory, else the copy on disk (blocking: read once per server and process). */
    fun cached(ctx: Context, srv: String): CatalogData? = data[srv] ?: runCatching {
        val f = file(ctx, srv)
        parseCatalog(JSONObject(f.readText())).also { data.putIfAbsent(srv, it); synced.putIfAbsent(srv, f.lastModified()) }
    }.getOrNull()

    /** When the catalog of [srv] was last confirmed by the server (epoch ms, 0 = never). */
    fun syncedAt(srv: String): Long = synced[srv] ?: 0

    /** Fetches what changed (blocking, call off the main thread). */
    fun refresh(ctx: Context, api: Api): CatalogData {
        val key = api.srv.key
        // the server moved files between disks: re-key positions and "watched" before the catalog shows them
        if (api.srv.hasMoves) runCatching {
            val s = Settings(ctx)
            val (moves, now) = api.libraryMoves(s.movesSeen(key))
            moves.forEach { (fr, f, tr, t) -> s.applyMove(key, fr as Int, f as String, tr as Int, t as String) }
            s.setMovesSeen(key, now)
        }
        // positions, "watched" marks and subtitle choices made offline go out (and come back) under the current keys
        runCatching { WatchSync.sync(ctx, api) }
        val old = cached(ctx, key)
        val f = file(ctx, key)
        // agent 1.18+: only what changed since the copy's cursor (a copy without one is fetched whole once)
        val got: Pair<CatalogData, String>? = when {
            !api.srv.hasCatalogDelta -> api.catalog(old?.version)
            old?.cursor == null -> api.catalog(null)
            else -> api.catalogSince(old.cursor)?.let { d ->
                if (!d.optBoolean("delta")) parseCatalog(d) to d.toString()
                else runCatching { applyCatalogDelta(JSONObject(f.readText()), d) }.getOrNull()
                    ?.let { parseCatalog(it) to it.toString() } ?: api.catalog(null)
            }
        }
        if (got == null) {
            synced[key] = System.currentTimeMillis()
            runCatching { f.setLastModified(synced[key]!!) }
            CoverStore.sync(ctx, api, old!!)
            runCatching { OfflineSync.verify(ctx, api) }
            runCatching { OfflineSync.refreshSubs(ctx, api) }  // the server's newer subtitles for downloaded copies
            return old
        }
        val (fresh, raw) = got
        data[key] = fresh
        synced[key] = System.currentTimeMillis()
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(raw)
            tmp.renameTo(f)
        }
        version.value++
        CoverStore.sync(ctx, api, fresh)
        runCatching { OfflineSync.verify(ctx, api) }
        runCatching { OfflineSync.refreshSubs(ctx, api) }  // the server's newer subtitles for downloaded copies
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

/**
 * The catalogs of several servers as one ("All" in the switcher). Ids get the server's key; a title on several servers
 * (the same IMDb id) is one item, the best copy (higher resolution, then Polish audio or subtitles), with the other
 * copies in `alsoOn`. The order of the first catalog's items is kept, the others' follow.
 */
fun mergeCatalogs(parts: List<Pair<String, CatalogData>>): CatalogData {
    val all = parts.flatMap { (k, c) -> c.items.map { it.copy(srv = k, id = "$k/${it.id}") } }
    val groups = all.filter { it.imdb != null }.groupBy { it.imdb }
    // only copies on other servers fold into the best one: two copies on one server stay two items, as without "All"
    val best = groups.mapValues { (_, g) ->
        val b = g.maxWith(compareBy<CatItem>({ it.height ?: 0 }, { if (it.plAudio || it.plSubs) 1 else 0 }))
        b to g.filter { it.srv != b.srv }
    }
    val items = all.mapNotNull { x ->
        val (b, others) = best[x.imdb ?: return@mapNotNull x]!!
        when {
            x === b -> b.copy(alsoOn = others)
            x.srv == b.srv -> x
            else -> null
        }
    }
    return CatalogData(parts.joinToString(",") { (k, c) -> "$k:${c.version}" }, parts.any { it.second.scanning },
        parts.sumOf { it.second.done }, parts.sumOf { it.second.total }, items,
        parts.fold(emptyMap()) { m, p -> m + p.second.people }, parts.fold(emptyMap()) { m, p -> m + p.second.topics })
}

/** Positions saved on this phone that belong to items of this server, most recent first. */
fun resumes(ctx: Context, srv: String, cat: CatalogData, withDone: Boolean = false): List<Resume> {
    val prefix = Servers.prefixOf(srv) + "lib"
    // a merged catalog ("All"): only this server's copies, also those folded into another server's item
    val byRoot = cat.items.flatMap { listOf(it) + it.alsoOn }.filter { it.srv.isEmpty() || it.srv == srv }.groupBy { it.root }
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
    fun matches(c: String, x: CatItem, cat: CatalogData, tagsOf: (CatItem) -> List<String>): Boolean {
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
            "tag" -> v in tagsOf(x)
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

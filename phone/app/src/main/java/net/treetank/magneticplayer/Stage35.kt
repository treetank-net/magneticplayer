package net.treetank.magneticplayer

// Stages 3 and 5 of docs/plan-ux-tasks.md. Stage 3: the scope entry points of "Optimise" (E2): a folder, a library
// filter (category, age group), a title, a season, a file, a selection, and A1's "Narrow…" with every filter. Stage 5
// (E1): a series page with every season and episode from the catalog (`/api/catalog/episodes`, agent 1.16+,
// `features.episode_lists`), the missing ones searchable on the household's search sites.

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------------------------------------------------------------- scopes for the entry points (stage 3)

/** The age groups a scope knows (docs/api.md "scope"). */
val AGE_GROUPS = listOf(0, 7, 12, 16, 18)

/** Ready-made scopes for the "Optimise" entry points; [JobScope.label] is the app's name until the agent's arrives. */
object Scopes {
    /** A folder of the library ("" = the whole library folder). */
    fun folder(root: Int, path: String, label: String) = JobScope(roots = listOf(root to path), label = label)

    /** A film or a series: its catalog title when recognised, else its folder, else its one file. */
    fun title(x: CatItem) = when {
        x.imdb != null -> JobScope(imdb = x.imdb, label = x.title)
        x.dir != null -> JobScope(roots = listOf(x.root to x.dir), label = x.title)
        else -> JobScope(files = listOf(x.root to x.path), label = x.title)
    }

    fun files(files: List<Pair<Int, String>>, label: String) = JobScope(files = files, label = label)

    /** "Who watches: 7+" covers the titles for 7-year-olds: the groups 0 and 7. */
    fun ageUpTo(n: Int) = AGE_GROUPS.filter { it <= n }

    fun ageLabel(groups: List<Int>) = groups.joinToString(", ") { "$it+" }
}

/** True when [srvKey] can open "Optimise" for this phone (agent 1.16+, not a guest). */
fun canOptimise(ctx: Context, srvKey: String?): Boolean =
    Servers.get(ctx, srvKey)?.let { it.hasJobsV2 && !it.isGuest } ?: false

/** The Optimise entry: the icon (Material `AutoFixHigh`), with the word "Optimise" when [wide] room allows it. */
@Composable
fun OptimiseButton(onClick: () -> Unit, withText: Boolean = false) {
    if (withText) FilledTonalButton(onClick, Modifier.height(48.dp)) {
        Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.job_optimise), maxLines = 1)
    } else FilledTonalIconButton(onClick, Modifier.size(48.dp)) {
        Icon(Icons.Filled.AutoFixHigh, stringResource(R.string.job_optimise))
    }
}

/** [content] (the play row) with the Optimise button at its end; the word "Optimise" only when the row is wide. */
@Composable
fun WithOptimise(show: Boolean, onOptimise: () -> Unit, content: @Composable () -> Unit) {
    if (!show) return content()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 440.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) { content() }
            OptimiseButton(onOptimise, withText = wide)
        }
    }
}

/** The selection bar at the bottom of a title page: "Selected N" and "✨ Optimise · N ›". */
@Composable
fun SelectionBar(count: Int, modifier: Modifier = Modifier, onOptimise: () -> Unit) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s35_selected_n, count), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Button(onOptimise, enabled = count > 0) { Text(stringResource(R.string.s35_optimise_n, count)) }
        }
    }
}

// ---------------------------------------------------------------- A1 "Narrow…": folder, category, age group, title

/**
 * A1's "Narrow…": library folders, one category (a folder tag or a genre), age groups and a title, all combined (every
 * chosen part must match). Applying opens E2 with that scope.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NarrowScopeDialog(api: Api, onDismiss: () -> Unit, onScope: (JobScope) -> Unit) {
    val ctx = LocalContext.current
    var roots by remember { mutableStateOf<List<LibRoot>?>(null) }
    LaunchedEffect(Unit) {
        roots = runCatching { withContext(Dispatchers.IO) { api.libraryRoots(true) } }.getOrDefault(emptyList()).filter { it.exists }
    }
    val cat by produceState(CatalogCache.peek(api.srv.key), api.srv.key) {
        value = withContext(Dispatchers.IO) { CatalogCache.cached(ctx, api.srv.key) }
    }
    var folders by remember { mutableStateOf(setOf<Int>()) }
    var category by remember { mutableStateOf<String?>(null) }
    var ages by remember { mutableStateOf(listOf<Int>()) }
    var query by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<CatItem?>(null) }
    // categories: the folder tags, then the catalog's most common genres
    val tags = roots.orEmpty().flatMap { it.tags }.filter { it != "kopia" }.distinct()
    val genres = remember(cat) {
        cat?.items.orEmpty().flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
    }
    val tagNames = tags.associateWith { "#" + tagLabel(it) }
    val genreNames = genres.associateWith { genreLabel(it) }
    @Composable
    fun heading(id: Int) = Text(stringResource(id), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.job_narrow_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                heading(R.string.s35_narrow_folder)
                val list = roots
                if (list == null) Text(stringResource(R.string.media_loading))
                else list.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { folders = if (r.index in folders) folders - r.index else folders + r.index },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(r.index in folders, { folders = if (it) folders + r.index else folders - r.index })
                        Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (tags.isNotEmpty() || genres.isNotEmpty()) {
                    heading(R.string.s35_narrow_category)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (tags + genres.filter { g -> tags.none { it.equals(g, true) } }).forEach { k ->
                            FilterChip(category == k, { category = if (category == k) null else k },
                                { Text(tagNames[k] ?: genreNames[k] ?: k) })
                        }
                    }
                }
                heading(R.string.s35_narrow_age)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AGE_GROUPS.forEach { a ->
                        FilterChip(a in ages, { ages = if (a in ages) ages - a else (ages + a).sorted() }, { Text("$a+") })
                    }
                }
                heading(R.string.s35_narrow_title)
                val t = title
                if (t != null) InputChip(true, { title = null }, { Text(t.title + (t.year?.let { " ($it)" } ?: "")) },
                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.media_cancel), Modifier.size(16.dp)) })
                else {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.s35_narrow_title_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) })
                    val c = cat
                    if (query.trim().length >= 2 && c != null) {
                        val hits = remember(c, query) { c.items.filter { it.imdb != null && Cond.search(it, c, query.trim()) }.take(6) }
                        if (hits.isEmpty()) Text(stringResource(R.string.cat_results_none), Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        hits.forEach { x ->
                            Text(x.title + (x.year?.let { " ($it)" } ?: ""), Modifier.fillMaxWidth().clickable { title = x; query = "" }
                                .padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(stringResource(R.string.s35_narrow_and), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                val picked = roots.orEmpty().filter { it.index in folders }
                val label = listOfNotNull(
                    title?.title,
                    picked.joinToString(", ") { it.name }.ifEmpty { null },
                    category?.let { tagNames[it] ?: genreNames[it] ?: it },
                    ages.takeIf { it.isNotEmpty() }?.let { Scopes.ageLabel(it) },
                ).joinToString(" · ")
                if (picked.isEmpty() && category == null && ages.isEmpty() && title == null) onDismiss()
                else onScope(JobScope(roots = picked.map { it.index to "" }, imdb = title?.imdb, tag = category, age = ages,
                    label = label, fromList = true))
            }) { Text(stringResource(R.string.job_apply)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

// ---------------------------------------------------------------- episode lists from the catalog (F5, E1)

/** One episode of a series from the catalog; [root]/[path] when it is on this server. */
data class CatEp(val season: Int?, val episode: Int?, val title: String?, val imdb: String?, val aired: String?, val year: Int?,
                 val state: String, val root: Int?, val path: String?) {
    val have get() = state == "have" && path != null
    val upcoming get() = state == "upcoming"
    val code get() = listOfNotNull(season?.let { String.format(Locale.ROOT, "S%02d", it) },
        episode?.let { String.format(Locale.ROOT, "E%02d", it) }).joinToString("")
}

data class CatSeason(val season: Int?, val episodes: List<CatEp>, val have: Int, val missing: Int, val upcoming: Int, val total: Int) {
    /** The year the season started: its first known air year. */
    val year get() = episodes.mapNotNull { it.year ?: it.aired?.take(4)?.toIntOrNull() }.minOrNull()
}

/** `/api/catalog/episodes`: seasons, the counts for the whole series, own videos that match no episode ([other]). */
data class SeriesEpisodes(val seasons: List<CatSeason>, val have: Int, val missing: Int, val upcoming: Int, val total: Int,
                          val other: List<Pair<Int, String>>, val source: String?, val running: Boolean, val pending: Boolean)

private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)

/** Every season and episode of the series [imdb] (agent 1.16+, `features.episode_lists`); the first call may wait ~30 s. */
fun Api.catalogEpisodes(imdb: String): SeriesEpisodes {
    val o = JSONObject(request("GET", "/api/catalog/episodes?imdb=" + android.net.Uri.encode(imdb), timeout = 45000))
    fun objs(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getJSONObject(i) } } ?: emptyList()
    val seasons = objs(o.optJSONArray("seasons")).map { s ->
        val eps = objs(s.optJSONArray("episodes")).map { e ->
            val state = e.optStr("state") ?: if (e.optBoolean("have")) "have" else "missing"
            CatEp(e.intOrNull("season") ?: s.intOrNull("season"), e.intOrNull("episode"), e.optStr("title"), e.optStr("imdb"),
                e.optStr("aired"), e.intOrNull("year"), state, e.intOrNull("root"), e.optStr("path"))
        }
        CatSeason(s.intOrNull("season"), eps, s.optInt("have", eps.count { it.have }), s.optInt("missing", eps.count { it.state == "missing" }),
            s.optInt("upcoming", eps.count { it.upcoming }), s.optInt("total", eps.size))
    }
    return SeriesEpisodes(seasons, o.optInt("have"), o.optInt("missing"), o.optInt("upcoming"), o.optInt("total"),
        objs(o.optJSONArray("other")).map { it.optInt("root") to it.optString("path") }, o.optStr("source"),
        o.optBoolean("running"), o.optBoolean("pending"))
}

/** "12 May 2025" for an air date "2025-05-12" (as given when it doesn't parse). */
fun airDate(s: String?): String? = s?.takeIf { it.isNotBlank() }?.let {
    runCatching {
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .format(java.time.LocalDate.parse(it.take(10)))
    }.getOrDefault(it)
}

/**
 * Where a magnet found from a series page goes: the series' folder ([set] when the search sheet opens, for two hours).
 * MagnetActivity uses it when the magnet's name (or the agent's recognition) carries the series' title.
 */
object MagnetHint {
    private const val TTL = 2 * 3600_000L
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magnet_hint", Context.MODE_PRIVATE)
    private fun key(s: String) = norm(s).filter { it.isLetterOrDigit() }

    fun set(ctx: Context, srv: String, root: Int, dir: String, title: String) {
        prefs(ctx).edit().putString("srv", srv).putInt("root", root).putString("dir", dir).putString("title", title)
            .putLong("at", System.currentTimeMillis()).apply()
    }

    fun target(ctx: Context, srv: String, magnet: String, guessTitle: String?): DlTarget? {
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("at", 0) > TTL || p.getString("srv", null) != srv) return null
        val title = key(p.getString("title", null) ?: return null).takeIf { it.length >= 2 } ?: return null
        val dir = p.getString("dir", null) ?: return null
        if (listOfNotNull(magnetName(magnet), guessTitle).none { key(it).contains(title) }) return null
        val root = p.getInt("root", -1).takeIf { it >= 0 } ?: return null
        return DlTarget(root, RootNames.get(srv, root) ?: "", dir, 0, emptyList(), true)
    }
}

// ---------------------------------------------------------------- episode rows

/**
 * An episode on the disk: ▶, "E3 · name" ([label]), what of it is on the phone, this phone's progress or ✓, an
 * optional [info] line (E1: watched, size, subtitles) and ⋮. While [selecting], a checkbox instead of ▶ and a tap
 * selects. The row of the old list and of E1 alike.
 */
@Composable
fun OwnedEpisodeRow(srv: String, root: Int, e: Episode, label: String, current: Boolean, focused: Boolean, settings: Settings,
                    back: Int, dlTick: Int, searchPhrase: String, noSubs: Boolean, onPlay: () -> Unit, onChanged: () -> Unit,
                    onFocusY: (Int) -> Unit = {}, info: Boolean = false, subs: List<String>? = null,
                    selecting: Boolean = false, selected: Boolean = false, onSelect: (Boolean) -> Unit = {},
                    onOptimise: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val ref = MediaRef.InLibrary(root, e.path, srv)
    val k = ref.key()
    val pos = remember(k, back) { settings.position(k) }
    val watched = remember(k, back) { settings.watchedAt(k) > 0 && pos <= 0 }
    val total = remember(k, back) { OfflineStore.get(ctx, k)?.durationMs ?: 0L }
    Row(Modifier.fillMaxWidth()
        .then(if (focused) Modifier.onGloballyPositioned { onFocusY(it.positionInParent().y.toInt()) } else Modifier)
        .clickable { if (selecting) onSelect(!selected) else onPlay() }
        .background(if (current || focused) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
        .padding(start = if (selecting) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(selected, onSelect)
        else {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp),
                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            if (info) {
                val line = listOfNotNull(
                    if (watched) stringResource(R.string.s35_ep_watched) else null,
                    e.size.takeIf { it > 0 }?.let { humanBytes(it) },
                    subs?.let { if (it.isEmpty()) stringResource(R.string.s35_ep_no_subs)
                        else stringResource(R.string.s35_ep_subs, it.joinToString(", ") { l -> l.uppercase(Locale.ROOT) }) },
                )
                if (line.isNotEmpty()) Text(line.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // what of it is on the phone, in words (a grey icon at the end read as "watched")
            val mark = remember(k, back, dlTick) { offlineMark(ctx, ref, e.size) }
            val part = remember(k, back, dlTick, mark) { if (mark == null) offlinePart(ctx, ref, e.size) else 0 }
            if (mark != null || part > 0) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mark == "downloading") Icons.Filled.Downloading else Icons.Filled.DownloadDone, null,
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(when {
                    mark == "complete" -> stringResource(R.string.ser_dl_on_phone)
                    mark == "downloading" -> stringResource(R.string.ser_dl_downloading)
                    else -> stringResource(R.string.ser_dl_on_phone_part, part)
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (pos > 0 && total > 0) LinearProgressIndicator(progress = { (pos.toFloat() / total).coerceIn(0f, 1f) },
                Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp))
            else if (pos > 0) Text(stringResource(R.string.cat_ep_at, pos / 60_000), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (watched) Text("✓", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
        // ⋮ (D6): play, subtitles (server and the phone's copy), keep for good, delete from the phone, details, Optimise…
        if (!selecting) CopyMenuButton(ref, e.shortName, onPlay = onPlay, onScreen = null, onChanged = onChanged,
            searchPhrase = searchPhrase, noSubs = noSubs, onOptimise = onOptimise)
    }
}

/** A dashed line under a row (missing episodes and seasons). */
private fun Modifier.dashedBottom(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())))
}

/** A missing episode (grey, dashed, "Search") or one that isn't out yet (its air date instead of "Search"). */
@Composable
private fun MissingEpisodeRow(ep: CatEp, onSearch: () -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().dashedBottom(grey.copy(alpha = 0.5f)).padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(ep.episode?.let { String.format(Locale.ROOT, "E%02d", it) }, ep.title).joinToString(" · "),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = grey.copy(alpha = 0.75f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (ep.upcoming) Text(airDate(ep.aired) ?: stringResource(R.string.s35_no_date), Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium, color = grey)
        else TextButton(onSearch) { Text(stringResource(R.string.cat_search)) }
    }
}

// ---------------------------------------------------------------- E1: seasons and episodes, have and don't have

/**
 * E1: every season of the series [x] from the catalog ([series]), one collapsible header each ("Season 1 · 2021",
 * "have 5 of 6 · missing E04" / "you don't have it · 6 ep."). Episodes on the disk play as before ([local] = the
 * folder's list from `/api/library/episodes`, which "continue" and the player use); missing ones are grey and dashed
 * with "Search" (a sheet with the phrase and the search sites); a missing season offers "Search the whole season".
 * While [selecting], the episodes on the disk have checkboxes ([selected], [onSelect]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesSeasonsList(c: CatCtx, x: CatItem, title: String, series: SeriesEpisodes, local: List<Episode>, extras: List<Episode>,
                      cont: Int, settings: Settings, back: Int, focusPath: String?, api: Api?, jobs: Boolean,
                      selecting: Boolean, selected: Set<Pair<Int, String>>, onSelect: (Pair<Int, String>, Boolean) -> Unit,
                      onFocusY: (Int) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val srv = c.srvOf(x)
    val subsLang = remember { settings.subLanguages.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "" }
    var sheet by remember { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf(false) }
    var dlTick by remember { mutableStateOf(0) }
    var showOther by remember(x.id) { mutableStateOf(false) }
    var showExtras by remember(x.id) { mutableStateOf(false) }
    val byPath = remember(local) { local.withIndex().associate { it.value.path to it.index } }
    val owned = remember(series) { series.seasons.flatMap { s -> s.episodes.filter { it.have }.mapNotNull { it.path } }.toSet() }
    val other = remember(local, owned) { local.filter { it.path !in owned } }
    // open: the season of the tapped episode (D6) or the one watching continues in, else the first one you have
    val focusSeason = (local.firstOrNull { it.path == focusPath } ?: local.getOrNull(cont.coerceAtLeast(0)))?.season
    val first = focusSeason?.takeIf { s -> series.seasons.any { it.season == s } } ?: series.seasons.firstOrNull { it.have > 0 }?.season
    val open = remember(x.id) { mutableStateMapOf<Int, Boolean>() }
    fun isOpen(s: CatSeason) = open[s.season ?: -1] ?: (s.season == first)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (download) SeriesDownloadDialog(srv, x.root, local, extras, cont, focusSeason, onDismiss = { download = false }) { n ->
        download = false; dlTick++
        scope.launch { c.snack.showSnackbar(ctx.resources.getQuantityString(R.plurals.ser_dl_started, n, n)) }
    }
    fun play(root: Int, e: Episode) {
        val i = byPath[e.path]
        if (i != null && root == x.root) playEpisode(ctx, srv, x.root, local, i) else playEpisode(ctx, srv, root, listOf(e), 0)
    }
    fun optimise(files: List<Pair<Int, String>>, label: String) = JobsNav.openOptimise(srv, Scopes.files(files, label))
    // the rows are children of this one Column, so a row's y (D6 focus) is relative to the list item as before
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.cat_episodes_title)) }
            if (local.isNotEmpty()) IconButton({ download = true }, Modifier.padding(end = 4.dp)) {
                Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.ser_dl_title))
            }
        }
        series.seasons.forEach { s -> androidx.compose.runtime.key(s.season ?: -1) {
            val n = s.season
            val missingAll = s.have == 0
            val expanded = isOpen(s)
            val grey = MaterialTheme.colorScheme.onSurfaceVariant
            val summary = when {
                missingAll && s.missing > 0 -> stringResource(R.string.s35_season_none, s.total)
                missingAll -> stringResource(R.string.s35_season_future, s.total)
                else -> listOfNotNull(
                    stringResource(R.string.s35_season_have, s.have, s.total),
                    s.episodes.filter { it.state == "missing" }.takeIf { it.isNotEmpty() }?.let { m ->
                        stringResource(R.string.s35_season_missing, m.take(3).joinToString(", ") { ep ->
                            ep.episode?.let { String.format(Locale.ROOT, "E%02d", it) } ?: ep.code } + if (m.size > 3) "…" else "")
                    },
                    s.upcoming.takeIf { it > 0 }?.let { stringResource(R.string.s35_season_upcoming, it) },
                ).joinToString(" · ")
            }
            Row(Modifier.fillMaxWidth().then(if (missingAll) Modifier.dashedBottom(grey.copy(alpha = 0.5f)) else Modifier)
                .clickable { open[n ?: -1] = !expanded }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val y = s.year
                    Text(when {
                        n == null -> stringResource(R.string.cat_season_other)
                        y != null -> stringResource(R.string.s35_season_year, n, y)
                        else -> stringResource(R.string.cat_season_n, n)
                    }, style = MaterialTheme.typography.titleSmall, color = if (missingAll) grey else Color.Unspecified)
                    Text(summary, style = MaterialTheme.typography.labelMedium,
                        color = if (missingAll) grey else MaterialTheme.colorScheme.primary)
                }
                // ⋮ → "Optimise this season" (files of the season on the disk)
                if (jobs && s.have > 0) Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.st4_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.s35_optimise_season)) }, {
                            menu = false
                            optimise(s.episodes.filter { it.have }.map { (it.root ?: x.root) to it.path!! },
                                listOfNotNull(title, n?.let { String.format(Locale.ROOT, "S%02d", it) }).joinToString(" "))
                        }, leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null) })
                    }
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(end = 12.dp))
            }
            if (expanded) {
                val mine = s.episodes.filter { it.have }.map { ep ->
                    val p = ep.path!!
                    ep to (local.firstOrNull { it.path == p } ?: Episode(p, p.substringAfterLast('/'), ep.season, ep.episode, 0))
                }
                val seasonSubs = rememberEpisodeSubs(api, srv, x.root, mine.map { it.second })
                SeasonSubsLine(seasonSubs)
                if (missingAll && s.missing > 0 && n != null) OutlinedButton({ sheet = String.format(Locale.ROOT, "%s S%02d", title, n) },
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.s35_search_season))
                }
                s.episodes.forEach { ep ->
                    if (!ep.have) MissingEpisodeRow(ep) {
                        sheet = listOf(title, ep.code).filter { it.isNotEmpty() }.joinToString(" ")
                    } else {
                        val e = mine.first { it.first === ep }.second
                        val root = ep.root ?: x.root
                        val label = listOfNotNull(ep.episode?.let { String.format(Locale.ROOT, "E%02d", it) } ?: e.label.ifEmpty { null },
                            ep.title ?: e.shortName).joinToString(" · ")
                        val i = byPath[e.path]
                        OwnedEpisodeRow(srv, root, e, label, current = i != null && i == cont, focused = e.path == focusPath, settings = settings,
                            back = back, dlTick = dlTick, searchPhrase = listOf(title, ep.code, subsLang).filter { it.isNotEmpty() }.joinToString(" "),
                            noSubs = seasonSubs[e.path]?.isEmpty() == true, onPlay = { play(root, e) }, onChanged = onChanged,
                            onFocusY = onFocusY, info = true, subs = seasonSubs[e.path], selecting = selecting,
                            selected = (root to e.path) in selected, onSelect = { onSelect(root to e.path, it) },
                            onOptimise = if (jobs) ({ optimise(listOf(root to e.path), listOf(title, ep.code).joinToString(" ").trim()) }) else null)
                    }
                }
            }
        } }
        // own videos that match no catalog episode: as in the folder list, under their own row
        if (other.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { showOther = !showOther }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.s35_other_files, other.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showOther) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (showOther || other.any { it.path == focusPath }) other.forEach { e ->
                val i = byPath[e.path] ?: 0
                OwnedEpisodeRow(srv, x.root, e, listOf(e.label, e.shortName).filter { it.isNotEmpty() }.joinToString(" · "),
                    current = i == cont, focused = e.path == focusPath, settings = settings, back = back, dlTick = dlTick,
                    searchPhrase = listOf(title, e.label, subsLang).filter { it.isNotEmpty() }.joinToString(" "), noSubs = false,
                    onPlay = { play(x.root, e) }, onChanged = onChanged, onFocusY = onFocusY, selecting = selecting,
                    selected = (x.root to e.path) in selected, onSelect = { onSelect(x.root to e.path, it) },
                    onOptimise = if (jobs) ({ optimise(listOf(x.root to e.path), e.shortName) }) else null)
            }
        }
        if (extras.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { showExtras = !showExtras }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cat_extras_n, extras.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (showExtras) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (showExtras) extras.forEach { e ->
                Text(e.path.substringAfter((x.dir ?: "") + "/").substringBeforeLast('.'),
                    Modifier.fillMaxWidth().clickable { playEpisode(ctx, srv, x.root, listOf(e), 0) }.padding(horizontal = 46.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    sheet?.let { phrase0 ->
        EpisodeSearchSheet(c, api ?: c.api, x, phrase0) { sheet = null }
    }
}

/**
 * The search sheet of E1: the phrase ("Title S01E04", "Title S02"), editable; one button per search site; "Set up ›"
 * (D4); "Close". A magnet from a result goes to the series' folder ([MagnetHint]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSearchSheet(c: CatCtx, api: Api, x: CatItem, phrase0: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var phrase by remember(phrase0) { mutableStateOf(phrase0) }
    var engines by remember { mutableStateOf<List<SearchEngine>?>(null) }
    LaunchedEffect(Unit) {
        x.dir?.let { MagnetHint.set(ctx, c.srvOf(x), x.root, it, x.title) }
        engines = withContext(Dispatchers.IO) { runCatching { api.searchEngines() }.getOrDefault(emptyList()) }
    }
    ModalBottomSheet(onClose) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.s35_sheet_title, phrase), style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.s35_sheet_phrase)) })
            Text(x.dir?.let { stringResource(R.string.s35_sheet_note, it.substringAfterLast('/')) } ?: stringResource(R.string.s35_sheet_note_nodir),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = engines
            if (list == null) Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodySmall)
            else EngineButtons(list, phrase, null, onSetUp = { onClose(); c.push(Nav.engines()) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClose) { Text(stringResource(R.string.media_close)) }
            }
        }
    }
}

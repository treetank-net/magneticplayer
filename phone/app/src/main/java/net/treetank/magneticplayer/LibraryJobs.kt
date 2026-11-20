package net.treetank.magneticplayer

// Stage 1 of docs/plan-ux-tasks.md (agent 1.16+, `features.jobs_v2`): the task list "Disks and tidy-up" (A1), its
// scoped form "Optimise" (E2, basics), and one screen per task (A4, A5, B1-B4, C1, C2) on one template (F6), fed by
// the counters of `/api/library/jobs`. "Preview" opens the existing PlanScreen with the task's plan body.

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------------------------------------------------------------- scope (F1)

/**
 * What a task list or a task covers: folders, files, a catalog title, a tag, age groups (docs/api.md, "scope").
 * The filter parts (`roots`, `tag`, `age`) combine with AND; `files` and `imdb` are exact sets. [label] is the
 * caller's own name for it until the agent's `scope_label` arrives; [fromList]: made by "Narrow…" on the
 * whole-computer list, so back returns there.
 */
data class JobScope(
    val roots: List<Pair<Int, String>> = emptyList(),
    val files: List<Pair<Int, String>> = emptyList(),
    val imdb: String? = null,
    val tag: String? = null,
    val age: List<Int> = emptyList(),
    val label: String? = null,
    val fromList: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (roots.isNotEmpty()) put("roots", pairs(roots))
        if (files.isNotEmpty()) put("files", pairs(files))
        imdb?.let { put("imdb", it) }
        tag?.let { put("tag", it) }
        if (age.isNotEmpty()) put("age", JSONArray(age))
    }

    /** For rememberSaveable: the scope plus the app-only fields. */
    fun encode(): String = toJson().apply { label?.let { put("label", it) }; if (fromList) put("from_list", true) }.toString()

    companion object {
        private fun pairs(l: List<Pair<Int, String>>) = JSONArray(l.map { (r, p) -> JSONObject().put("root", r).put("path", p) })
        private fun pairsOf(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getJSONObject(i).let { o -> o.optInt("root") to o.optString("path") } } }
            ?: emptyList()

        fun decode(s: String?): JobScope? = s?.let {
            runCatching {
                val o = JSONObject(it)
                JobScope(pairsOf(o.optJSONArray("roots")), pairsOf(o.optJSONArray("files")), o.optStr("imdb"), o.optStr("tag"),
                    o.optJSONArray("age")?.let { a -> List(a.length()) { i -> a.optInt(i) } } ?: emptyList(),
                    o.optStr("label"), o.optBoolean("from_list"))
            }.getOrNull()
        }

        /** Library folders as a scope's `roots` (null for none = no restriction). */
        fun rootsJson(keys: Collection<Pair<Int, String>>): JSONObject? =
            if (keys.isEmpty()) null else JSONObject().put("roots", pairs(keys.toList()))
    }
}

/**
 * Opening "Optimise" (E2) with a scope from elsewhere: MainActivity switches to the Library of [srvKey], whose tab picks
 * it up and shows the scoped task list. Entry points (plan stage 3, Stage35.kt): folder ⋮, a library filter, a title
 * page, a season, a file ⋮, a selection.
 */
object JobsNav {
    val pending = mutableStateOf<Pair<String, JobScope>?>(null)
    fun openOptimise(srvKey: String, scope: JobScope) { pending.value = srvKey to scope }
}

// ---------------------------------------------------------------- counters (GET /api/library/jobs)

/** One task kind's counters for a scope; `options` holds the kind's extras (docs/api.md). */
data class JobInfo(val applicable: Boolean, val count: Int, val size: Long, val saving: Long, val eta: Long, val why: String?,
                   val pending: Int, val options: JSONObject)

data class JobsInfo(val scopeLabel: String?, val files: Int, val size: Long, val checkedAt: Long?, val jobs: Map<String, JobInfo>)

/** Counters for [scope] (null = the whole computer); cheap on the agent (cached background scans). */
fun Api.jobs(scope: JSONObject?, languages: String? = null, levels: String? = null): JobsInfo {
    val q = buildList {
        if (scope != null && scope.length() > 0) add("scope=" + Uri.encode(scope.toString()))
        if (!languages.isNullOrEmpty()) add("languages=" + Uri.encode(languages))
        // agent 1.17+: the folder layout counter for these levels ("kind,age"), not the saved ones
        if (!levels.isNullOrEmpty()) add("levels=" + Uri.encode(levels))
    }
    val o = JSONObject(request("GET", "/api/library/jobs" + if (q.isEmpty()) "" else "?" + q.joinToString("&"), timeout = 20000))
    val j = o.optJSONObject("jobs") ?: JSONObject()
    val map = j.keys().asSequence().associateWith { k ->
        val x = j.optJSONObject(k) ?: JSONObject()
        JobInfo(x.optBoolean("applicable", true), x.optInt("count"), x.optLong("size"), x.optLong("saving"), x.optLong("eta"),
            x.optStr("why"), x.optInt("pending"), x.optJSONObject("options") ?: JSONObject())
    }
    return JobsInfo(o.optStr("scope_label"), o.optInt("files"), o.optLong("size"),
        o.optLong("checked_at").takeIf { it > 0 && !o.isNull("checked_at") }, map)
}

/** Task settings kept on the agent: `audio_fix_auto` (B1), `subs_auto` (C1, "pl,en" or ""). */
fun Api.jobsConfig(): JSONObject = JSONObject(request("GET", "/api/config/jobs"))
fun Api.setJobsConfig(o: JSONObject) { request("POST", "/api/config/jobs", o) }

internal class JobsLoad(val info: JobsInfo?, val error: String?)

/** Counters for [scope], read again when it, [languages], [levels] or [reload] change; the old ones stay while loading. */
@Composable
internal fun loadJobs(api: Api, scope: JSONObject?, languages: String? = null, reload: Int = 0, levels: String? = null): JobsLoad {
    val ctx = LocalContext.current
    val key = scope?.toString()
    var state by remember { mutableStateOf(JobsLoad(null, null)) }
    LaunchedEffect(key, languages, reload, levels) {
        state = try {
            JobsLoad(withContext(Dispatchers.IO) { api.jobs(scope, languages, levels) }, null)
        } catch (e: ApiException) {
            JobsLoad(state.info, e.message ?: ctx.getString(R.string.media_error))
        }
    }
    return state
}

/** The subtitle languages of this phone, as the subtitle dialogs use them. */
private fun subLanguages(api: Api): List<String> =
    (listOfNotNull(api.s.subChoice?.takeIf { it != "off" }) + api.s.subLanguages.split(','))
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("en") }

// Choices on task screens outlive the screen (Preview replaces it, back brings it again) until the app dies.
private val jobChoices = mutableStateMapOf<String, Any>()

private class Choice<T : Any>(val key: String, val default: T) : MutableState<T> {
    @Suppress("UNCHECKED_CAST")
    override var value: T
        get() = (jobChoices[key] as? T) ?: default
        set(v) { jobChoices[key] = v }
    override fun component1() = value
    override fun component2(): (T) -> Unit = { value = it }
}

@Composable
internal fun <T : Any> choice(key: String, default: T): MutableState<T> = remember(key) { Choice(key, default) }

// ---------------------------------------------------------------- the area: list, task screens, background work

/**
 * Disks and tidy-up for an agent with `jobs_v2`. [scopeStr]: [JobScope.encode] or null (whole computer); [job]: the
 * open task screen (its kind); [bgWork]: "Background work" open. State lives in the caller (it survives the preview).
 */
@Composable
fun JobsArea(api: Api, snack: SnackbarHostState, scopeStr: String?, onScope: (String?) -> Unit, job: String?, onJob: (String?) -> Unit,
             bgWork: Boolean, onBgWork: (Boolean) -> Unit, onBack: () -> Unit, onPlan: (String) -> Unit) {
    val scope = remember(scopeStr) { JobScope.decode(scopeStr) }
    when {
        bgWork -> {
            BackHandler { onBgWork(false) }
            // agent 1.17+: pause, resume and queue order (C4, LibraryJobs2.kt); older ones keep the task cards
            if (api.srv.hasTaskQueue) BackgroundWork2(api, snack) { onBgWork(false) }
            else BackgroundWorkScreen(api, snack) { onBgWork(false) }
        }
        job != null -> {
            BackHandler { onJob(null) }
            JobScreen(JobCtx(api, snack, scope, onBack = { onJob(null) }, onClearScope = { onScope(null) }, onPlan = onPlan, onOpenJob = onJob), job)
        }
        else -> {
            // E2 made by A1's "Narrow…" goes back to A1; opened from elsewhere, back to where it came from
            val back: () -> Unit = if (scope?.fromList == true) ({ onScope(null) }) else onBack
            BackHandler(onBack = back)
            JobsListScreen(api, snack, scope, back, onScope = { onScope(it?.encode()) }, onOpen = { onJob(it) },
                onBackground = { onBgWork(true) })
        }
    }
}

// Rows of the list, by group. "layout" (A2), "balance" (A3) and "cast_ready" (B5) are plan stage 2 (agent 1.17+,
// screens in LibraryJobs2.kt); "reencode" (B6, under "Advanced") comes in stage 6.
private val TIDY = listOf("layout", "balance", "dupes", "free")
private val RENOVATION = listOf("audio_fix", "modernize", "downscale", "compress", "cast_ready")
/** Shown only when the agent lists them in `features.jobs` (an older agent's counters lack them anyway). */
private val STAGE2 = setOf("layout", "balance", "cast_ready")
private val SUBTITLES = listOf("subs_get", "subs")
private val CATALOG = listOf("tags")
/** Whole disks only: not on the scoped list, replaced there by one note. */
private val DISK_WIDE = setOf("free", "trash", "balance")

private enum class Tone { ACCENT, DIM, WARN }

@Composable
private fun jobName(kind: String) = when (kind) {
    "dupes" -> stringResource(R.string.lib_dupes)
    "free" -> stringResource(R.string.lib_free_up)
    "trash" -> stringResource(R.string.lib_trash)
    "audio_fix" -> stringResource(R.string.job_audio)
    "modernize" -> stringResource(R.string.job_modern)
    "downscale" -> stringResource(R.string.job_down)
    "compress" -> stringResource(R.string.job_comp)
    "subs_get" -> stringResource(R.string.job_subsget)
    "subs" -> stringResource(R.string.job_subs)
    "tags" -> stringResource(R.string.job_tags)
    "layout" -> stringResource(R.string.j2_layout)
    "balance" -> stringResource(R.string.j2_balance)
    "cast_ready" -> stringResource(R.string.j2_cast)
    else -> kind
}

@Composable
private fun jobRowText(kind: String) = when (kind) {
    "dupes" -> stringResource(R.string.job_dupes_row)
    "free" -> stringResource(R.string.job_free_row)
    "audio_fix" -> stringResource(R.string.job_audio_row)
    "modernize" -> stringResource(R.string.job_modern_row)
    "downscale" -> stringResource(R.string.job_down_row)
    "compress" -> stringResource(R.string.job_comp_row)
    "subs_get" -> stringResource(R.string.job_subsget_row)
    "subs" -> stringResource(R.string.job_subs_row)
    "tags" -> stringResource(R.string.job_tags_row)
    "layout" -> stringResource(R.string.j2_layout_row)
    "balance" -> stringResource(R.string.j2_balance_row)
    "cast_ready" -> stringResource(R.string.j2_cast_row)
    else -> ""
}

/** The counter on a task's row, and how it is coloured. */
@Composable
private fun jobCounter(kind: String, j: JobInfo?): Pair<String?, Tone> {
    j ?: return null to Tone.DIM
    // balance: the free share of each disk ("39% / 42% free"), also when nothing would move
    if (kind == "balance" && j.applicable) {
        val pcts = j.options.list("disks").map { "${it.optInt("pct")}%" }
        if (pcts.size >= 2) return stringResource(R.string.j2_bal_counter, pcts.joinToString(" / ")) to
            (if (j.count > 0) Tone.ACCENT else Tone.DIM)
    }
    if (!j.applicable || (j.count == 0 && j.pending == 0))
        return stringResource(if (kind == "free") R.string.job_not_needed else R.string.job_nothing) to Tone.DIM
    if (j.count == 0) return stringResource(R.string.job_checking) to Tone.DIM
    val n = j.count
    return when (kind) {
        "dupes" -> {
            val sf = j.options.optJSONObject("same_files")
            val groups = sf?.optInt("groups", n) ?: n
            pluralStringResource(R.plurals.job_n_groups, groups, groups, humanBytes(sf?.optLong("saving")?.takeIf { it > 0 } ?: j.saving))
        }
        "free" -> pluralStringResource(R.plurals.job_n_titles_size, n, n, humanBytes(j.size))
        "downscale" -> {
            val k4 = j.options.optJSONObject("by_target")?.optJSONObject("1080")?.optInt("count") ?: 0
            if (k4 > 0) pluralStringResource(R.plurals.job_n_in4k, k4, k4) else pluralStringResource(R.plurals.lib_files, n, n)
        }
        "subs_get" -> pluralStringResource(R.plurals.lib_items, n, n)
        "subs" -> n.toString()
        "tags" -> return pluralStringResource(R.plurals.job_n_to_check, n, n) to Tone.WARN
        "layout" -> pluralStringResource(R.plurals.j2_n_to_move, n, n)
        else -> pluralStringResource(R.plurals.lib_files, n, n)
    } to Tone.ACCENT
}

@Composable
private fun toneColor(t: Tone): Color = when (t) {
    Tone.ACCENT -> MaterialTheme.colorScheme.primary
    Tone.WARN -> MaterialTheme.colorScheme.tertiary
    Tone.DIM -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A1 (whole computer) and E2 ("Optimise", [scope] set): every task as a row that opens its own screen. */
@Composable
private fun JobsListScreen(api: Api, snack: SnackbarHostState, scope: JobScope?, onBack: () -> Unit, onScope: (JobScope?) -> Unit,
                           onOpen: (String) -> Unit, onBackground: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val langs = remember { subLanguages(api).joinToString(",") }
    val load = loadJobs(api, scope?.toJson(), langs, reload)
    var disks by remember { mutableStateOf<Disks?>(null) }
    var tasks by remember { mutableStateOf<List<LibTask>?>(null) }
    var narrow by remember { mutableStateOf(false) }
    LaunchedEffect(reload, scope == null) {
        if (scope == null) runCatching { withContext(Dispatchers.IO) { api.disks() } }.getOrNull()?.let { disks = it }
    }
    // background work every 3 s while visible; a task that finishes changes the counters, so they are read again
    LaunchedEffect(Unit) {
        var finished = -1
        while (true) {
            MainVisible.await()
            runCatching { withContext(Dispatchers.IO) { api.tasks(brief = true) } }.getOrNull()?.let { list ->
                tasks = list
                val n = list.count { it.state == "done" || it.state == "cancelled" }
                if (finished >= 0 && n != finished) reload++
                finished = n
            }
            delay(3000)
        }
    }
    LaunchedEffect(load.error) { load.error?.let { snack.showSnackbar(it) } }
    val info = load.info
    // a series as the scope: "missing M" episodes from the catalog (agent 1.16+, `episode_lists`)
    val ctx = LocalContext.current
    var missing by remember { mutableStateOf<Int?>(null) }
    val imdb = scope?.imdb
    LaunchedEffect(imdb) {
        missing = if (imdb != null && (Servers.get(ctx, api.srv.key) ?: api.srv).hasEpisodeLists)
            withContext(Dispatchers.IO) { runCatching { api.catalogEpisodes(imdb) }.getOrNull() }?.takeIf { it.total > 0 }?.missing
        else null
    }
    Column(Modifier.fillMaxSize()) {
        if (scope == null) ScreenHeader(stringResource(R.string.lib_disks_title), onBack) { reload++ }
        else OptimiseHeader(onBack) { reload++ }
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "scope") {
                if (scope == null) WholeScopeBar { narrow = true }
                else ScopeBar(info?.scopeLabel ?: scope.label ?: "…",
                    info?.let { pluralStringResource(R.plurals.lib_files, it.files, it.files) + " · " + humanBytes(it.size) +
                        (missing?.takeIf { m -> m > 0 }?.let { m -> " · " + stringResource(R.string.s35_missing_n, m) } ?: "") }) { onScope(null) }
            }
            item(key = "bg") { BackgroundRow(tasks, onBackground) }
            if (scope == null) disks?.disks?.filter { it.roots.isNotEmpty() }?.let { list ->
                items(list, key = { "disk/${it.id}/${it.mount}" }) { DiskBar(it) }
            }
            if (info == null && load.error == null) item(key = "loading") {
                Text(stringResource(R.string.media_loading), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // without counters (an error) the rows of this stage still lead to their screens
                fun shown(k: String) = (k !in STAGE2 || k in api.srv.jobKinds) && (info == null || k in info.jobs) &&
                    (scope == null || k !in DISK_WIDE)
                val groups = buildList {
                    val tidy = R.string.job_grp_tidy to TIDY.filter(::shown)
                    val renovation = R.string.job_grp_renovation to RENOVATION.filter(::shown)
                    val subtitles = R.string.job_grp_subs to SUBTITLES.filter(::shown)
                    val catalog = R.string.job_grp_catalog to CATALOG.filter(::shown)
                    // the scoped list starts with what changes the files in it (board E2)
                    if (scope == null) addAll(listOf(tidy, renovation, subtitles, catalog)) else addAll(listOf(renovation, subtitles, tidy, catalog))
                }
                groups.forEach { (title, kinds) ->
                    val trash = scope == null && title == R.string.job_grp_tidy && (info == null || "trash" in info.jobs)
                    val note = scope != null && title == R.string.job_grp_tidy
                    if (kinds.isEmpty() && !trash && !note) return@forEach
                    item(key = "grp/$title") { GroupHeading(stringResource(title)) }
                    items(kinds, key = { "job/$it" }) { k ->
                        val j = info?.jobs?.get(k)
                        val (counter, tone) = jobCounter(k, j)
                        JobRow(jobName(k), jobRowText(k), counter, tone, dim = j != null && tone == Tone.DIM,
                            why = j?.why?.takeIf { tone == Tone.DIM }) { onOpen(k) }
                    }
                    if (trash) item(key = "job/trash") {
                        val t = info?.jobs?.get("trash")
                        val days = t?.options?.optInt("days", 14) ?: 14
                        JobRow(stringResource(R.string.lib_trash), pluralStringResource(R.plurals.job_trash_row, days, days),
                            t?.let { if (it.size > 0) humanBytes(it.size) else stringResource(R.string.job_trash_empty) },
                            if ((t?.size ?: 0L) > 0) Tone.ACCENT else Tone.DIM, dim = false) { onOpen("trash") }
                    }
                    if (note) item(key = "note/disks") {
                        Text(stringResource(R.string.job_disk_wide_note), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (narrow) NarrowDialog(api, onDismiss = { narrow = false }) { narrow = false; onScope(it) }
}

@Composable
private fun OptimiseHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_cd_up)) }
        Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.job_optimise), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.lib_refresh)) }
    }
    HorizontalDivider()
}

@Composable
private fun WholeScopeBar(onNarrow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.job_scope_whole), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.job_scope_whole_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onNarrow, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.job_narrow)) }
        }
    }
}

/** "Scope: <label>" with what it holds and "✕ whole computer" (E2). */
@Composable
private fun ScopeBar(label: String, stats: String?, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.job_scope, label), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            stats?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClear, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.job_scope_clear)) }
        }
    }
}

@Composable
private fun BackgroundRow(tasks: List<LibTask>?, onClick: () -> Unit) {
    val running = tasks?.count { it.state == "running" } ?: 0
    val waiting = tasks?.count { it.state == "queued" } ?: 0
    val parts = buildList {
        if (running > 0) add(pluralStringResource(R.plurals.job_bg_running, running, running))
        if (waiting > 0) add(pluralStringResource(R.plurals.job_bg_waiting, waiting, waiting))
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (parts.isEmpty()) stringResource(R.string.job_bg_idle) else stringResource(R.string.job_bg_row, parts.joinToString(", ")),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun DiskBar(disk: Disk) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(disk.name.ifEmpty { disk.mount }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.lib_disk_free, humanBytes(disk.free), humanBytes(disk.total)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val used = if (disk.total > 0) (disk.total - disk.free).toFloat() / disk.total else 0f
        LinearProgressIndicator(progress = { used.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = if (used > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun GroupHeading(text: String) {
    Text(text.uppercase(Locale.getDefault()), Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

/** One task: name, one line on what it does, its counter; tapping opens its screen. A task with nothing to do is grey. */
@Composable
private fun JobRow(name: String, desc: String, counter: String?, tone: Tone, dim: Boolean, why: String? = null, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp).alpha(if (dim) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                why?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            counter?.let {
                Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = toneColor(tone),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** Checkboxes over the library folders (only folders that exist); [extra] goes under the list. */
@Composable
private fun RootsDialog(api: Api, title: String, picked: Set<Int>, extra: String? = null, confirm: String,
                        onDismiss: () -> Unit, onPick: (List<LibRoot>) -> Unit) {
    var roots by remember { mutableStateOf<List<LibRoot>?>(null) }
    LaunchedEffect(Unit) { roots = runCatching { withContext(Dispatchers.IO) { api.libraryRoots(true) } }.getOrDefault(emptyList()).filter { it.exists } }
    var sel by remember { mutableStateOf(picked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = roots
                if (list == null) Text(stringResource(R.string.media_loading))
                else list.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { sel = if (r.index in sel) sel - r.index else sel + r.index },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(r.index in sel, { sel = if (it) sel + r.index else sel - r.index })
                        Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                extra?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton({ onPick(roots.orEmpty().filter { it.index in sel }) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

/** A1 "Narrow…": folders, a category, age groups and a title, combined (Stage35.kt). */
@Composable
private fun NarrowDialog(api: Api, onDismiss: () -> Unit, onScope: (JobScope) -> Unit) = NarrowScopeDialog(api, onDismiss, onScope)

/** "Folders: all ▾" on a task screen (whole computer); a scope replaces it with the scope bar. */
@Composable
private fun FoldersPicker(api: Api, picked: Set<Pair<Int, String>>, names: Map<Int, String>, onPick: (Set<Pair<Int, String>>, Map<Int, String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.job_folders), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(if (picked.isEmpty()) stringResource(R.string.job_folders_all) else picked.joinToString(", ") { names[it.first] ?: "#${it.first}" },
            Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.ArrowDropDown, null)
    }
    if (open) RootsDialog(api, stringResource(R.string.job_folders), picked.map { it.first }.toSet(), confirm = stringResource(R.string.job_apply),
        onDismiss = { open = false }) { list ->
        open = false
        onPick(list.map { it.index to "" }.toSet(), list.associate { it.index to it.name })
    }
}

// ---------------------------------------------------------------- the task screen template (F6)

internal class JobCtx(val api: Api, val snack: SnackbarHostState, val scope: JobScope?, val onBack: () -> Unit,
                     val onClearScope: () -> Unit, val onPlan: (String) -> Unit, val onOpenJob: (String) -> Unit) {
    val scopeJson: JSONObject? get() = scope?.toJson()
}

internal fun planBody(kind: String, scope: JSONObject?, fill: JSONObject.() -> Unit = {}): String =
    JSONObject().put("kind", kind).apply(fill).apply { if (scope != null) put("scope", scope) }.toString()

/**
 * Back arrow, title, one sentence on what the task does, the scope bar when there is a scope (instead of the
 * folder picker), the task's sections, and the primary button ("Preview") at the bottom.
 */
@Composable
internal fun JobFrame(c: JobCtx, title: String, description: String, scopeLabel: String?, primary: String?,
                     primaryEnabled: Boolean = true, onPrimary: () -> Unit = {}, onRefresh: (() -> Unit)? = null,
                     content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, c.onBack, onRefresh)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.scope != null) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.job_scope, scopeLabel ?: c.scope.label ?: "…"), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(c.onClearScope) { Icon(Icons.Filled.Close, stringResource(R.string.job_scope_clear_cd)) }
                }
            }
            content()
        }
        if (primary != null) Surface(tonalElevation = 3.dp) {
            Button(onPrimary, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = primaryEnabled) { Text(primary) }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) -> FilterChip(selected == v, { onSelect(v) }, label = { Text(l) }) }
    }
}

@Composable
internal fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
internal fun SwitchRow(text: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Note(it) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
internal fun RadioRow(text: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Note(it) }
        }
    }
}

/** The summary card: what the background check found for this task and the settings above. */
@Composable
internal fun Summary(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** First examples with "… and N more". */
@Composable
internal fun Examples(list: List<JSONObject>, total: Int, line: @Composable (JSONObject) -> String) {
    list.forEach { x ->
        Column {
            Text(x.optString("name"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Note(line(x))
        }
    }
    if (total > list.size) Note(pluralStringResource(R.plurals.job_and_more, total - list.size, total - list.size))
}

private fun JSONObject.list(k: String): List<JSONObject> =
    optJSONArray(k)?.let { a -> List(a.length()) { a.optJSONObject(it) ?: JSONObject() } } ?: emptyList()

/** "about 1 h 20 min" style time, or "" for none. */
internal fun eta(sec: Long) = humanTime(sec).ifEmpty { "?" }

/** The trash keeps originals this many days (from the trash counters, 14 when unknown). */
internal fun trashDays(info: JobsInfo?) = info?.jobs?.get("trash")?.options?.optInt("days", 14) ?: 14

@Composable
internal fun checkedLine(at: Long?): String {
    val ctx = LocalContext.current
    at ?: return stringResource(R.string.job_not_checked)
    val d = java.util.Date(at * 1000)
    val time = android.text.format.DateFormat.getTimeFormat(ctx).format(d)
    val whenS = if (android.text.format.DateUtils.isToday(at * 1000)) stringResource(R.string.job_today_at, time)
        else android.text.format.DateFormat.getDateFormat(ctx).format(d) + " " + time
    return stringResource(R.string.job_checked_at, whenS)
}

@Composable
private fun JobScreen(c: JobCtx, kind: String) {
    when (kind) {
        "dupes" -> DupesJob(c)
        "free" -> FreeJob(c)
        "trash" -> TrashJob(c)
        "audio_fix" -> AudioFixJob(c)
        "modernize" -> ModernizeJob(c)
        "downscale" -> DownscaleJob(c)
        "compress" -> CompressJob(c)
        "subs_get" -> SubsGetJob(c)
        "subs" -> SubsFitJob(c)
        "tags" -> TagsJob(c)
        "layout" -> LayoutJob(c)
        "balance" -> BalanceJob(c)
        "cast_ready" -> CastReadyJob(c)
        else -> LaunchedEffect(kind) { c.onBack() }
    }
}

// ---------------------------------------------------------------- A4 Duplicates

@Composable
private fun DupesJob(c: JobCtx) {
    var what by choice("dupes.what", "same_files")
    var keep by choice("dupes.keep", "folder")
    var reload by remember { mutableIntStateOf(0) }
    val load = loadJobs(c.api, c.scopeJson, reload = reload)
    val o = load.info?.jobs?.get("dupes")?.options ?: JSONObject()
    val preview = { c.onPlan(planBody("dupes", c.scopeJson) { put("what", what); put("keep", keep) }) }
    JobFrame(c, stringResource(R.string.lib_dupes), stringResource(R.string.job_dupes_desc), load.info?.scopeLabel,
        stringResource(R.string.lib_preview), onPrimary = preview, onRefresh = { reload++ }) {
        Section(stringResource(R.string.job_dupes_what)) {
            Chips(listOf("same_files" to stringResource(R.string.job_dupes_same_files), "same_title" to stringResource(R.string.job_dupes_same_title)), what) { what = it }
            Note(stringResource(if (what == "same_files") R.string.job_dupes_same_files_hint else R.string.job_dupes_same_title_hint))
        }
        Section(stringResource(R.string.job_dupes_keep)) {
            listOf("folder" to R.string.job_keep_folder, "quality" to R.string.job_keep_quality, "space" to R.string.job_keep_space,
                "manual" to R.string.job_keep_manual).forEach { (k, s) -> RadioRow(stringResource(s), keep == k) { keep = k } }
        }
        Summary {
            if (what == "same_files") o.optJSONObject("same_files").let { x ->
                val n = x?.optInt("groups") ?: 0
                Text(pluralStringResource(R.plurals.job_dupes_found_groups, n, n, humanBytes(x?.optLong("saving") ?: 0L)), style = MaterialTheme.typography.bodyMedium)
            } else o.optJSONObject("same_title").let { x ->
                val n = x?.optInt("titles") ?: 0
                Text(pluralStringResource(R.plurals.job_dupes_found_titles, n, n, humanBytes(x?.optLong("saving") ?: 0L)), style = MaterialTheme.typography.bodyMedium)
            }
            if (o.optInt("seeding") > 0) Note(stringResource(R.string.job_dupes_seeding, o.optInt("seeding")))
            // "check now" runs the preview, which checks every file again
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note(checkedLine(load.info?.checkedAt))
                Text(stringResource(R.string.job_check_now), Modifier.clickable(onClick = preview).padding(4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------- A5 Free up space

private val GIB = 1L shl 30

@Composable
private fun FreeJob(c: JobCtx) {
    var disks by remember { mutableStateOf<Disks?>(null) }
    LaunchedEffect(Unit) { disks = runCatching { withContext(Dispatchers.IO) { c.api.disks() } }.getOrNull() ?: Disks(emptyList(), emptyList(), 14) }
    val load = loadJobs(c.api, null)
    var mount by choice("free.disk", "")
    var amount by choice("free.amount", "pct15")
    var order by choice("free.order", "watched_oldest")
    var skipKept by choice("free.skip_kept", true)
    val list = disks?.disks?.filter { it.roots.isNotEmpty() }.orEmpty()
    fun pct(d: Disk) = if (d.total > 0) (d.free * 100 / d.total).toInt() else 0
    // the fullest disk until one is picked
    val disk = list.firstOrNull { it.mount == mount } ?: list.minByOrNull { pct(it) }
    JobFrame(c, stringResource(R.string.lib_free_up), stringResource(R.string.job_free_desc), null, stringResource(R.string.lib_preview),
        primaryEnabled = disk != null, onPrimary = {
            if (disk != null) c.onPlan(planBody("free", null) {
                put("root", disk.roots.first().root)
                put("amount", amount.toLongOrNull()?.let { it * GIB } ?: JSONObject.NULL)
                put("to_percent", if (amount == "pct15") 15 else JSONObject.NULL)
                put("order", order); put("skip_kept", skipKept)
            })
        }) {
        Section(stringResource(R.string.job_disk)) {
            if (disks == null) Note(stringResource(R.string.media_loading))
            Chips(list.map { it.mount to stringResource(R.string.job_disk_chip, it.name.ifEmpty { it.mount }, pct(it)) }, disk?.mount) { mount = it ?: "" }
        }
        Section(stringResource(R.string.job_how_much)) {
            Chips(listOf("50", "100", "250").map { it to humanBytes(it.toLong() * GIB) } + ("pct15" to stringResource(R.string.job_free_to15)), amount) { amount = it }
            if (amount == "pct15" && disk != null && pct(disk) >= 15) Note(stringResource(R.string.job_free_already, pct(disk)), MaterialTheme.colorScheme.error)
        }
        Section(stringResource(R.string.job_what_first)) {
            RadioRow(stringResource(R.string.job_free_watched), order == "watched_oldest") { order = "watched_oldest" }
            RadioRow(stringResource(R.string.job_free_largest), order == "largest") { order = "largest" }
            SwitchRow(stringResource(R.string.job_free_skip_kept), skipKept) { skipKept = it }
        }
        val jobs = load.info?.jobs
        val down = jobs?.get("downscale")?.saving ?: 0L
        val dupes = jobs?.get("dupes")?.saving ?: 0L
        // agent 1.17+: what "Balance between disks" would move to the emptier disk
        val bal = jobs?.get("balance")?.takeIf { it.applicable && it.count > 0 && "balance" in c.api.srv.jobKinds }
        if (down > 0 || dupes > 0 || bal != null) Section(stringResource(R.string.job_instead)) {
            if (bal != null) LinkRow(stringResource(R.string.j2_instead_balance, humanBytes(bal.size))) { c.onOpenJob("balance") }
            if (down > 0) LinkRow(stringResource(R.string.job_instead_down, humanBytes(down))) { c.onOpenJob("downscale") }
            if (dupes > 0) LinkRow(stringResource(R.string.job_instead_dupes, humanBytes(dupes))) { c.onOpenJob("dupes") }
            Note(stringResource(R.string.job_instead_note))
        }
    }
}

@Composable
internal fun LinkRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

// ---------------------------------------------------------------- Trash (row on A1)

@Composable
private fun TrashJob(c: JobCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var disks by remember { mutableStateOf<Disks?>(null) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { disks = runCatching { withContext(Dispatchers.IO) { c.api.disks() } }.getOrNull() ?: disks ?: Disks(emptyList(), emptyList(), 14) }
    val d = disks
    val files = d?.trash?.sumOf { it.files } ?: 0
    JobFrame(c, stringResource(R.string.lib_trash), stringResource(R.string.job_trash_desc), null,
        if (files > 0) stringResource(R.string.lib_trash_empty) else null, onPrimary = { confirm = true }, onRefresh = { reload++ }) {
        if (d == null) Note(stringResource(R.string.media_loading))
        else Summary {
            Text(if (files == 0) stringResource(R.string.lib_trash_empty_now)
                else pluralStringResource(R.plurals.lib_trash_summary, files, files, humanBytes(d.trash.sumOf { it.size })),
                style = MaterialTheme.typography.bodyMedium)
            Note(pluralStringResource(R.plurals.lib_trash_kept, d.trashDays, d.trashDays))
        }
        d?.trash?.forEach { t ->
            Column {
                Text("${t.day} · ${t.rootName}", style = MaterialTheme.typography.bodyMedium)
                Note(pluralStringResource(R.plurals.lib_trash_summary, t.files, t.files, humanBytes(t.size)))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.lib_trash_empty_title)) },
        text = { Text(stringResource(R.string.lib_trash_empty_text)) },
        confirmButton = {
            TextButton({
                confirm = false
                scope.launch {
                    try {
                        val freed = withContext(Dispatchers.IO) { c.api.trashEmpty() }
                        reload++
                        c.snack.showSnackbar(ctx.getString(R.string.lib_trash_freed, humanBytes(freed)))
                    } catch (e: ApiException) { c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                }
            }) { Text(stringResource(R.string.lib_trash_empty)) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.media_cancel)) } },
    )
}

// ---------------------------------------------------------------- B1 Fix sound

@Composable
private fun AudioFixJob(c: JobCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var quality by choice("audio.quality", "standard")
    var auto by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { auto = runCatching { withContext(Dispatchers.IO) { c.api.jobsConfig().optBoolean("audio_fix_auto") } }.getOrNull() }
    val load = loadJobs(c.api, c.scopeJson)
    val j = load.info?.jobs?.get("audio_fix")
    val o = j?.options ?: JSONObject()
    val n = j?.count ?: 0
    JobFrame(c, stringResource(R.string.job_audio), stringResource(R.string.job_audio_desc), load.info?.scopeLabel,
        if (n > 0) pluralStringResource(R.plurals.job_preview_n, n, n) else stringResource(R.string.lib_preview),
        onPrimary = { c.onPlan(planBody("audio_fix", c.scopeJson) { put("quality", quality) }) }) {
        Summary {
            if (j == null) Note(stringResource(R.string.media_loading))
            else {
                Text(pluralStringResource(R.plurals.job_found_files, n, n), style = MaterialTheme.typography.titleSmall)
                val checked = o.optInt("checked"); val total = o.optInt("total")
                if (total > 0) Note(if (checked < total) stringResource(R.string.job_audio_checked, checked, total)
                    else stringResource(R.string.job_audio_checked_all, total))
                if (n > 0 && j.eta > 0) Note(stringResource(R.string.job_audio_eta, eta(j.eta / n), eta(j.eta)))
            }
        }
        if (n > 0) Section(stringResource(R.string.job_files)) {
            Examples(o.list("examples"), n) { x ->
                // null: checked before agent 1.16, which didn't count the damaged frames
                if (x.isNull("errors")) x.optString("codec").uppercase()
                else x.optInt("errors").let { e -> pluralStringResource(R.plurals.job_audio_errors, e, x.optString("codec").uppercase(), e) }
            }
        }
        Section(stringResource(R.string.job_settings)) {
            Text(stringResource(R.string.job_aac_quality), style = MaterialTheme.typography.bodyMedium)
            Chips(listOf("standard" to stringResource(R.string.job_aac_standard), "high" to stringResource(R.string.job_aac_high)), quality) { quality = it }
            SwitchRow(stringResource(R.string.job_audio_auto), auto ?: false, enabled = auto != null) { on ->
                auto = on
                scope.launch {
                    try { withContext(Dispatchers.IO) { c.api.setJobsConfig(JSONObject().put("audio_fix_auto", on)) } }
                    catch (e: ApiException) { auto = !on; c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                }
            }
        }
        Note(pluralStringResource(R.plurals.job_originals_trash, trashDays(load.info), trashDays(load.info)))
    }
}

// ---------------------------------------------------------------- B2 Modernise old picture

@Composable
private fun EncoderChips(encoder: String, gpu: Boolean, cpuLabel: Int = R.string.job_enc_cpu, onPick: (String) -> Unit) {
    Chips(buildList {
        if (gpu) add("gpu" to stringResource(R.string.job_enc_gpu))
        add("cpu" to stringResource(cpuLabel))
    }, encoder, onPick)
}

@Composable
private fun ModernizeJob(c: JobCtx) {
    var encoder by choice("modern.encoder", "")
    var audio by choice("modern.audio", "keep_if_ok")
    val load = loadJobs(c.api, c.scopeJson)
    val j = load.info?.jobs?.get("modernize")
    val o = j?.options ?: JSONObject()
    val n = j?.count ?: 0
    val gpu = o.optLong("eta_gpu") > 0
    val enc = encoder.ifEmpty { if (gpu) "gpu" else "cpu" }
    val time = if (enc == "gpu") o.optLong("eta_gpu") else o.optLong("eta_cpu").takeIf { it > 0 } ?: (j?.eta ?: 0L)
    JobFrame(c, stringResource(R.string.job_modern), stringResource(R.string.job_modern_desc), load.info?.scopeLabel,
        if (n > 0 && time > 0) pluralStringResource(R.plurals.job_preview_n_time, n, n, eta(time)) else stringResource(R.string.lib_preview),
        onPrimary = { c.onPlan(planBody("modernize", c.scopeJson) { put("encoder", enc); put("audio", audio) }) }) {
        Section(pluralStringResource(R.plurals.job_found_files, n, n)) {
            if (j == null) Note(stringResource(R.string.media_loading))
            Examples(o.list("examples"), n) { x -> x.optString("format") + " · " + humanBytes(x.optLong("size")) }
            Note(stringResource(R.string.job_modern_skip))
        }
        Section(stringResource(R.string.job_encoder)) {
            EncoderChips(enc, gpu) { encoder = it }
            Note(stringResource(if (enc == "gpu") R.string.job_enc_gpu_hint else R.string.job_enc_cpu_hint) +
                if (time > 0) " " + stringResource(R.string.job_total_time, eta(time)) else "")
        }
        Section(stringResource(R.string.job_sound)) {
            Chips(listOf("keep_if_ok" to stringResource(R.string.job_sound_keep), "always_aac" to stringResource(R.string.job_sound_aac)), audio) { audio = it }
        }
    }
}

// ---------------------------------------------------------------- B3 Downscale

private val TARGETS = listOf(1080, 720, 480, 360)

@Composable
private fun DownscaleJob(c: JobCtx) {
    var target by choice("down.target", 1080)
    var keepProfile by choice("down.remember", false)
    // folders switched off (key "root|path"); all are on at first
    var off by choice("down.off", emptySet<String>())
    val base = loadJobs(c.api, c.scopeJson)
    val folders = base.info?.jobs?.get("downscale")?.options?.list("folders").orEmpty()
    fun key(f: JSONObject) = f.optInt("root").toString() + "|" + f.optString("path")
    val on = folders.filter { key(it) !in off }
    val onKeys = on.map { it.optInt("root") to it.optString("path") }
    // a few folders switched off: their own counters (the folder list stays from the first read)
    val subset = c.scope == null && off.isNotEmpty() && folders.isNotEmpty()
    val part = loadJobs(c.api, if (subset) JobScope.rootsJson(onKeys) else c.scopeJson)
    val info = if (subset) part.info else base.info
    val t = info?.jobs?.get("downscale")?.options?.optJSONObject("by_target")?.optJSONObject(target.toString())
    val count = if (subset && onKeys.isEmpty()) 0 else t?.optInt("count") ?: 0
    // the folders' profile is kept for the folders sent in the scope: send them when it should be remembered
    val bodyScope = c.scopeJson ?: if (subset || keepProfile) JobScope.rootsJson(onKeys) else null
    JobFrame(c, stringResource(R.string.job_down), stringResource(R.string.job_down_desc), base.info?.scopeLabel,
        stringResource(R.string.lib_preview), primaryEnabled = !(subset && onKeys.isEmpty()), onPrimary = {
            c.onPlan(planBody("downscale", bodyScope) { put("target", target); put("encoder", "auto"); put("remember", keepProfile) })
        }) {
        Section(stringResource(R.string.job_to)) {
            TARGETS.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { target = p }, verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(target == p, { target = p }, label = { Text("${p}p") })
                    Spacer(Modifier.width(12.dp))
                    Note(stringResource(when (p) {
                        1080 -> R.string.job_down_look_1080
                        720 -> R.string.job_down_look_720
                        480 -> R.string.job_down_look_480
                        else -> R.string.job_down_look_360
                    }))
                }
            }
        }
        if (c.scope == null) Section(stringResource(R.string.job_folders)) {
            if (base.info == null) Note(stringResource(R.string.media_loading))
            folders.forEach { f ->
                val k = key(f)
                Row(Modifier.fillMaxWidth().clickable { off = if (k in off) off - k else off + k }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(k !in off, { off = if (it) off - k else off + k })
                    Column(Modifier.weight(1f)) {
                        Text(f.optString("name").ifEmpty { f.optString("path") }, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val saving = f.optJSONObject("saving_by_target")?.optLong(target.toString()) ?: 0L
                        Note(listOfNotNull(f.optStr("mix"), if (saving > 0) "−" + humanBytes(saving) else null).joinToString(" · "))
                    }
                }
            }
        }
        if (c.scope == null || c.scope.roots.isNotEmpty())
            SwitchRow(stringResource(R.string.job_down_remember), keepProfile, sub = stringResource(R.string.job_down_remember_sub)) { keepProfile = it }
        Summary {
            if (info == null) Note(stringResource(R.string.media_loading))
            else if (count == 0) Text(stringResource(R.string.job_nothing_folders), style = MaterialTheme.typography.bodyMedium)
            else Text(pluralStringResource(R.plurals.job_summary_files, count, count, humanBytes(t?.optLong("saving") ?: 0L),
                eta(t?.optLong("eta") ?: 0L)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- B4 Compression

@Composable
private fun CompressJob(c: JobCtx) {
    var quality by choice("comp.quality", "balanced")
    var high by choice("comp.high", true)
    var skipHevc by choice("comp.skip_hevc", true)
    var skipSeed by choice("comp.skip_seed", true)
    var folders by choice("comp.folders", emptySet<Pair<Int, String>>())
    var names by choice("comp.names", emptyMap<Int, String>())
    var encoder by choice("comp.encoder", "gpu")
    val eff = c.scopeJson ?: JobScope.rootsJson(folders)
    val load = loadJobs(c.api, eff)
    val o = load.info?.jobs?.get("compress")?.options ?: JSONObject()
    val byQ = o.optJSONObject("by_quality") ?: JSONObject()
    val sel = byQ.optJSONObject(quality)
    JobFrame(c, stringResource(R.string.job_comp), stringResource(R.string.job_comp_desc), load.info?.scopeLabel,
        stringResource(R.string.lib_preview), onPrimary = {
            c.onPlan(planBody("compress", eff) {
                put("quality", quality); put("only_high_bitrate", high); put("skip_hevc_av1", skipHevc); put("skip_seeding", skipSeed)
                put("encoder", encoder)
            })
        }) {
        Section(stringResource(R.string.job_quality)) {
            listOf("transparent" to (R.string.job_q_transparent to R.string.job_q_transparent_hint),
                "balanced" to (R.string.job_q_balanced to R.string.job_q_balanced_hint),
                "saving" to (R.string.job_q_saving to R.string.job_q_saving_hint)).forEach { (q, s) ->
                val x = byQ.optJSONObject(q)
                val n = x?.optInt("count") ?: 0
                val line = stringResource(s.second) + if (x != null && n > 0) " " + pluralStringResource(R.plurals.job_summary_files, n, n,
                    humanBytes(x.optLong("saving")), eta(x.optLong("eta"))) else ""
                RadioRow(stringResource(s.first), quality == q, line) { quality = q }
            }
        }
        Section(stringResource(R.string.job_which_files)) {
            SwitchRow(stringResource(R.string.job_comp_high), high) { high = it }
            SwitchRow(stringResource(R.string.job_comp_skip_hevc), skipHevc) { skipHevc = it }
            SwitchRow(stringResource(R.string.job_comp_skip_seed), skipSeed) { skipSeed = it }
            // the counters are made with the default filters (agent): say so when they differ
            if (!high || !skipHevc || !skipSeed) Note(stringResource(R.string.job_comp_filters_note))
            if (c.scope == null) FoldersPicker(c.api, folders, names) { f, nm -> folders = f; names = nm }
        }
        Section(stringResource(R.string.job_encoder)) {
            EncoderChips(encoder, gpu = true, cpuLabel = R.string.job_enc_cpu_day) { encoder = it }
        }
        Summary {
            val n = sel?.optInt("count") ?: 0
            if (load.info == null) Note(stringResource(R.string.media_loading))
            else if (n == 0) Text(stringResource(R.string.job_nothing_folders), style = MaterialTheme.typography.bodyMedium)
            else Text(pluralStringResource(R.plurals.job_summary_files, n, n, humanBytes(sel?.optLong("saving") ?: 0L), eta(sel?.optLong("eta") ?: 0L)),
                style = MaterialTheme.typography.bodyMedium)
            if (o.optLong("need_free") > 0) Note(stringResource(R.string.job_comp_need_free, humanBytes(o.optLong("need_free"))))
        }
    }
}

// ---------------------------------------------------------------- C1 Download missing subtitles

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubsGetJob(c: JobCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine = remember { subLanguages(c.api) }
    var langs by choice("sg.langs", mine.toSet())  // all of them, as the A1 counter
    var which by choice("sg.which", "no_subs")
    var order by choice("sg.order", "recent")
    var folders by choice("sg.folders", emptySet<Pair<Int, String>>())
    var names by choice("sg.names", emptyMap<Int, String>())
    val langList = mine.filter { it in langs }.joinToString(",")
    val eff = c.scopeJson ?: JobScope.rootsJson(folders)
    val load = loadJobs(c.api, eff, langList)
    val j = load.info?.jobs?.get("subs_get")
    val o = j?.options ?: JSONObject()
    val n = when (which) { "no_subs" -> o.optInt("no_subs"); "no_subs_in_lang" -> o.optInt("no_subs_in_lang"); else -> 0 }
    val time = if (j != null && j.count > 0) j.eta * n / j.count else 0L
    val newOnly = which == "new_only"
    JobFrame(c, stringResource(R.string.subs_get_title), stringResource(R.string.job_subsget_desc), load.info?.scopeLabel,
        stringResource(if (newOnly) R.string.job_save else R.string.lib_preview), primaryEnabled = langList.isNotEmpty(), onPrimary = {
            if (newOnly) scope.launch {
                // new files get subtitles by themselves (agent setting `subs_auto`)
                try {
                    withContext(Dispatchers.IO) { c.api.setJobsConfig(JSONObject().put("subs_auto", langList)) }
                    c.snack.showSnackbar(ctx.getString(R.string.job_saved))
                } catch (e: ApiException) { c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
            } else c.onPlan(planBody("subs_get", eff) { put("languages", langList); put("which", which); put("order", order) })
        }) {
        Section(stringResource(R.string.job_languages)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mine.forEach { l ->
                    FilterChip(l in langs, { langs = if (l in langs) langs - l else langs + l }, label = { Text(langName(l)) })
                }
            }
        }
        Section(stringResource(R.string.job_which_films)) {
            RadioRow(stringResource(R.string.job_sg_no_subs), which == "no_subs") { which = "no_subs" }
            RadioRow(stringResource(R.string.job_sg_no_lang), which == "no_subs_in_lang") { which = "no_subs_in_lang" }
            RadioRow(stringResource(R.string.job_sg_new), which == "new_only") { which = "new_only" }
            if (c.scope == null && !newOnly) FoldersPicker(c.api, folders, names) { f, nm -> folders = f; names = nm }
        }
        if (!newOnly) Section(stringResource(R.string.job_order)) {
            Chips(listOf("recent" to stringResource(R.string.job_order_recent), "alpha" to stringResource(R.string.job_order_alpha)), order) { order = it }
        }
        Summary {
            if (newOnly) Text(stringResource(R.string.job_sg_new_note), style = MaterialTheme.typography.bodyMedium)
            else if (j == null) Note(stringResource(R.string.media_loading))
            else {
                Text(pluralStringResource(R.plurals.job_sg_summary, n, n, eta(time)), style = MaterialTheme.typography.bodyMedium)
                val perDay = o.optInt("per_day")
                if (perDay > 0 && n > 0) {
                    val days = (n + perDay - 1) / perDay
                    Note(pluralStringResource(R.plurals.job_sg_days, days, perDay, days))
                }
                val have = o.optInt("have")
                if (have > 0) Note(pluralStringResource(R.plurals.job_sg_have, have, have))
            }
        }
    }
}

// ---------------------------------------------------------------- C2 Fit subtitles to the film

@Composable
private fun SubsFitJob(c: JobCtx) {
    var which by choice("subs.which", "unchecked")
    var toSrt by choice("subs.srt", true)
    var mark by choice("subs.mark", true)
    var queue by choice("subs.queue", false)
    var folders by choice("subs.folders", emptySet<Pair<Int, String>>())
    var names by choice("subs.names", emptyMap<Int, String>())
    val eff = c.scopeJson ?: JobScope.rootsJson(folders)
    val load = loadJobs(c.api, eff)
    val j = load.info?.jobs?.get("subs")
    val o = j?.options ?: JSONObject()
    val n = o.optInt(which)
    val time = if (j != null && j.count > 0) j.eta * n / j.count else 0L
    JobFrame(c, stringResource(R.string.job_subs_title), stringResource(R.string.job_subs_desc), load.info?.scopeLabel,
        stringResource(R.string.lib_preview), onPrimary = {
            c.onPlan(planBody("subs", eff) { put("which", which); put("sub_to_srt", toSrt); put("mark_bad", mark); put("queue_bad", queue) })
        }) {
        Section(stringResource(R.string.job_which_subs)) {
            listOf("unchecked" to R.string.job_subs_unchecked, "watched_now" to R.string.job_subs_watched, "all" to R.string.job_subs_all).forEach { (k, s) ->
                RadioRow(stringResource(s) + if (j != null) " · " + o.optInt(k) else "", which == k) { which = k }
            }
            if (c.scope == null) FoldersPicker(c.api, folders, names) { f, nm -> folders = f; names = nm }
        }
        Section(stringResource(R.string.job_along)) {
            SwitchRow(stringResource(R.string.job_subs_srt), toSrt) { toSrt = it }
            SwitchRow(stringResource(R.string.job_subs_mark), mark) { mark = it }
            SwitchRow(stringResource(R.string.job_subs_queue), queue) { queue = it }
            Note(stringResource(R.string.job_subs_orig))
        }
        Summary {
            if (j == null) Note(stringResource(R.string.media_loading))
            else {
                Text(pluralStringResource(R.plurals.job_subs_summary, n, n, eta(time)), style = MaterialTheme.typography.bodyMedium)
                val before = o.optInt("fitted_before")
                if (before > 0) Note(pluralStringResource(R.plurals.job_subs_before, before, before))
            }
        }
    }
}

// ---------------------------------------------------------------- C3 Catalog and tags (counters only in this stage)

@Composable
private fun TagsJob(c: JobCtx) {
    val load = loadJobs(c.api, c.scopeJson)
    val j = load.info?.jobs?.get("tags")
    val o = j?.options ?: JSONObject()
    // TODO(plan C3): the "to check" rows with "Choose", tag kinds and folder tags
    JobFrame(c, stringResource(R.string.job_tags), stringResource(R.string.job_tags_desc), load.info?.scopeLabel, null) {
        Summary {
            if (j == null) Note(stringResource(R.string.media_loading))
            else {
                Text(stringResource(R.string.job_tags_recognised, o.optInt("recognised"), o.optInt("total")), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.job_tags_to_check, j.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Note(stringResource(R.string.job_tags_later))
    }
}

// ---------------------------------------------------------------- Background work (agents before 1.17; C4 is in LibraryJobs2.kt)

@Composable
private fun BackgroundWorkScreen(api: Api, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<LibTask>?>(null) }
    suspend fun refresh() { runCatching { withContext(Dispatchers.IO) { api.tasks(brief = true) } }.getOrNull()?.let { tasks = it } }
    LaunchedEffect(Unit) {
        while (true) {
            MainVisible.await()
            refresh()
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.job_bg_title), onBack) { scope.launch { refresh() } }
        val list = tasks ?: return Hint(stringResource(R.string.media_loading))
        if (list.isEmpty()) return Hint(stringResource(R.string.job_bg_empty))
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { "task/${it.id}" }) { t ->
                TaskCard(t, api.srv.key, loadItems = { runCatching { withContext(Dispatchers.IO) { api.taskGet(t.id).items } }.getOrNull() },
                    onRemove = if (api.srv.hasTaskRemove) ({
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { api.taskRemove(t.id) }
                                tasks = tasks?.filter { it.id != t.id }
                            } catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                        }
                    }) else null) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.taskCancel(t.id) }
                            refresh()
                        } catch (e: ApiException) { snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
                    }
                }
            }
        }
    }
}

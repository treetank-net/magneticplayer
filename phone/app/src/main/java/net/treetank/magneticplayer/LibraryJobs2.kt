package net.treetank.magneticplayer

// Stage 2 of docs/plan-ux-tasks.md (agent 1.17+): Folder layout (A2), Balance between disks (A3), For Chromecast (B5)
// on the task screen template of LibraryJobs.kt (F6), and Background work with pause, resume and queue order (C4).
// The rows on A1/E2 are in LibraryJobs.kt; they appear only when the agent lists the kinds in `features.jobs`.

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.objs2(k: String): List<JSONObject> =
    optJSONArray(k)?.let { a -> List(a.length()) { a.optJSONObject(it) ?: JSONObject() } } ?: emptyList()

private fun JSONObject.strings2(k: String): List<String> =
    optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()

// ---------------------------------------------------------------- A2 Folder layout

/** Levels in the order the board shows them; the order of taps is the order of the folders. */
private val LAYOUT_LEVELS = listOf("kind", "age", "category", "genre", "collection", "decade")
private val AGE_GROUPS = listOf("0", "7", "12", "16", "18")
private val BUCKETS = listOf("kids", "teens", "films")

@Composable
private fun levelName(k: String) = when (k) {
    "kind" -> stringResource(R.string.j2_lv_kind)
    "age" -> stringResource(R.string.j2_lv_age)
    "category" -> stringResource(R.string.j2_lv_category)
    "genre" -> stringResource(R.string.j2_lv_genre)
    "collection" -> stringResource(R.string.j2_lv_collection)
    "decade" -> stringResource(R.string.j2_lv_decade)
    else -> k
}

@Composable
private fun bucketName(b: String) = when (b) {
    "kids" -> stringResource(R.string.j2_bucket_kids)
    "teens" -> stringResource(R.string.j2_bucket_teens)
    else -> stringResource(R.string.j2_bucket_films)
}

/** The counter's `tree` ([{path, titles}], "Films/Teens") as indented folders with the number of titles moving in. */
private fun treeText(tree: List<JSONObject>): String {
    val out = StringBuilder()
    val seen = HashSet<String>()
    tree.sortedBy { it.optString("path") }.forEach { t ->
        val parts = t.optString("path").split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { i, p ->
            val key = parts.take(i + 1).joinToString("/")
            if (seen.add(key)) {
                out.append("  ".repeat(i)).append(p).append('/')
                if (i == parts.lastIndex) out.append("  +").append(t.optInt("titles"))
                out.append('\n')
            }
        }
    }
    return out.toString().trimEnd()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LayoutJob(c: JobCtx) {
    val ctx = LocalContext.current
    val cs = rememberCoroutineScope()
    // the levels in tap order ("kind,age"); "" = the agent's saved ones, "none" = all taken off
    var levels by choice("layout.levels", "")
    var keepUnsure by choice("layout.keep_unsure", true)
    var keepSeeding by choice("layout.keep_seeding", false)
    var reload by remember { mutableIntStateOf(0) }
    val load = loadJobs(c.api, c.scopeJson, reload = reload, levels = levels.ifEmpty { null })
    val j = load.info?.jobs?.get("layout")
    val o = j?.options ?: JSONObject()
    val chosen = if (levels.isEmpty()) o.strings2("levels") else levels.split(',').filter { it in LAYOUT_LEVELS }
    val ages = o.optJSONObject("ages")?.let { a -> AGE_GROUPS.associateWith { g -> a.optString(g, "films") } } ?: emptyMap()
    val hand = o.objs2("hand_folders")
    val confirmed = o.optBoolean("confirmed", true)
    var agesOpen by remember { mutableStateOf(false) }
    var handOpen by remember { mutableStateOf(false) }
    var previewAfter by remember { mutableStateOf(false) }
    val preview = {
        c.onPlan(planBody("layout", c.scopeJson) {
            put("levels", JSONArray(chosen)); put("keep_unsure", keepUnsure)
            put("seeding", if (keepSeeding) "skip" else "move"); put("remember", true)
        })
    }
    fun save(body: JSONObject, then: () -> Unit = {}) {
        cs.launch {
            try {
                withContext(Dispatchers.IO) { c.api.setJobsConfig(body) }
                reload++
                then()
            } catch (e: ApiException) {
                c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
            }
        }
    }
    val n = j?.count ?: 0
    JobFrame(c, stringResource(R.string.j2_layout), stringResource(R.string.j2_layout_desc), load.info?.scopeLabel,
        if (n > 0) pluralStringResource(R.plurals.j2_layout_cta, n, n) else stringResource(R.string.lib_preview),
        primaryEnabled = chosen.isNotEmpty() && load.info != null,
        // the folders arranged by hand are confirmed once, before the first preview
        onPrimary = { if (!confirmed && hand.isNotEmpty()) { previewAfter = true; handOpen = true } else preview() },
        onRefresh = { reload++ }) {
        Section(stringResource(R.string.j2_layout_levels)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LAYOUT_LEVELS.forEach { k ->
                    val pos = chosen.indexOf(k)
                    FilterChip(pos >= 0, {
                        val next = if (pos >= 0) chosen - k else chosen + k
                        levels = next.joinToString(",").ifEmpty { "none" }
                    }, label = { Text(if (pos >= 0) "${pos + 1}. ${levelName(k)}" else levelName(k)) })
                }
            }
            Note(stringResource(R.string.j2_layout_levels_hint))
        }
        Summary {
            Text(stringResource(R.string.j2_layout_tree).uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val tree = o.objs2("tree")
            when {
                load.info == null -> Note(stringResource(R.string.media_loading))
                tree.isEmpty() -> Note(j?.why ?: stringResource(R.string.j2_layout_tree_none))
                else -> Text(treeText(tree), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Section(stringResource(R.string.j2_layout_ages)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val and = stringResource(R.string.j2_and)
                BUCKETS.forEach { b ->
                    val gs = AGE_GROUPS.filter { ages[it] == b }
                    if (gs.isNotEmpty()) FilterChip(false, { agesOpen = true }, label = {
                        Text(stringResource(R.string.j2_age_chip, gs.joinToString(" $and ") { "$it+" }, bucketName(b)))
                    })
                }
            }
            Note(stringResource(R.string.j2_layout_ages_hint))
        }
        Section(stringResource(R.string.j2_layout_keep)) {
            val kept = hand.filter { it.optBoolean("keep", true) }
            val names = kept.take(3).joinToString(", ") { it.optString("name") } + if (kept.size > 3) "…" else ""
            KeepRow(if (hand.isEmpty()) stringResource(R.string.j2_keep_hand_none) else stringResource(R.string.j2_keep_hand, names.ifEmpty { "–" }),
                kept.isNotEmpty(), enabled = hand.isNotEmpty(),
                sub = if (hand.isNotEmpty() && !confirmed) stringResource(R.string.j2_keep_hand_unconfirmed) else null) { handOpen = true }
            KeepRow(stringResource(R.string.j2_keep_unsure, o.optInt("unsure")), keepUnsure) { keepUnsure = !keepUnsure }
            KeepRow(stringResource(R.string.j2_keep_seeding), keepSeeding,
                sub = if (keepSeeding) null else stringResource(R.string.j2_keep_seeding_sub)) { keepSeeding = !keepSeeding }
            Note(stringResource(R.string.j2_layout_note))
        }
    }
    if (agesOpen) AgesDialog(ages, onDismiss = { agesOpen = false }) { m ->
        agesOpen = false
        save(JSONObject().put("layout_ages", JSONObject(m)))
    }
    if (handOpen) HandDialog(hand, onDismiss = { handOpen = false; previewAfter = false }) { picked ->
        handOpen = false
        val go = previewAfter
        previewAfter = false
        save(JSONObject().put("layout_keep", JSONArray(picked.map { JSONObject().put("root", it.optInt("root")).put("path", it.optString("path")) }))) {
            if (go) preview()
        }
    }
}

/** A checkbox row of "Don't touch" (tapping the text toggles it too). */
@Composable
private fun KeepRow(text: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Note(it) }
        }
    }
}

/** Age group → folder ("12+ → Teens"), saved on the agent (`layout_ages`). */
@Composable
private fun AgesDialog(ages: Map<String, String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var m by remember { mutableStateOf(ages) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.j2_layout_ages)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AGE_GROUPS.forEach { g ->
                    Text("$g+", style = MaterialTheme.typography.titleSmall)
                    Chips(BUCKETS.map { it to bucketName(it) }, m[g] ?: "films") { b -> m = m + (g to b) }
                }
            }
        },
        confirmButton = { TextButton({ onSave(m) }) { Text(stringResource(R.string.job_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

/** The folders the owner arranged by hand (agent's guess, all ticked at first): ticked ones stay as they are. */
@Composable
private fun HandDialog(hand: List<JSONObject>, onDismiss: () -> Unit, onSave: (List<JSONObject>) -> Unit) {
    var sel by remember { mutableStateOf(hand.indices.filter { hand[it].optBoolean("keep", true) }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.j2_hand_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Note(stringResource(R.string.j2_hand_text))
                hand.forEachIndexed { i, h ->
                    Row(Modifier.fillMaxWidth().clickable { sel = if (i in sel) sel - i else sel + i }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i in sel, { sel = if (it) sel + i else sel - i })
                        Column(Modifier.weight(1f)) {
                            Text(h.optString("name"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val t = h.optInt("titles")
                            Note(h.optString("root_name") + " / " + h.optString("path") + " · " +
                                pluralStringResource(R.plurals.j2_n_titles, t, t))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(sel.sorted().map { hand[it] }) }) { Text(stringResource(R.string.j2_hand_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

// ---------------------------------------------------------------- A3 Balance between disks

@Composable
internal fun BalanceJob(c: JobCtx) {
    var goal by choice("balance.goal", "even")
    var watching by choice("balance.skip_watching", true)
    var seeding by choice("balance.seeding", true)
    var backup by choice("balance.backup", false)
    var reload by remember { mutableIntStateOf(0) }
    val load = loadJobs(c.api, null, reload = reload)
    val j = load.info?.jobs?.get("balance")
    val g = j?.options?.optJSONObject("by_goal")?.optJSONObject(goal)
    val ok = j?.applicable == true
    JobFrame(c, stringResource(R.string.j2_balance), stringResource(R.string.j2_bal_desc), null, stringResource(R.string.lib_preview),
        primaryEnabled = ok, onPrimary = {
            c.onPlan(planBody("balance", null) {
                put("goal", goal)
                put("rules", JSONObject().put("skip_watching", watching).put("seeding", seeding).put("backup", backup))
            })
        }, onRefresh = { reload++ }) {
        Section(stringResource(R.string.j2_bal_goal)) {
            Chips(listOf("even" to stringResource(R.string.j2_goal_even), "kind_per_disk" to stringResource(R.string.j2_goal_kind),
                "fill_newer" to stringResource(R.string.j2_goal_fill)), goal) { goal = it }
            Note(stringResource(when (goal) {
                "kind_per_disk" -> R.string.j2_goal_kind_hint
                "fill_newer" -> R.string.j2_goal_fill_hint
                else -> R.string.j2_goal_even_hint
            }))
        }
        Summary {
            Text(stringResource(R.string.j2_bal_now_after).uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                load.info == null -> Note(stringResource(R.string.media_loading))
                !ok -> Note(j?.why ?: stringResource(R.string.job_nothing))
                else -> {
                    (g?.objs2("disks") ?: j?.options?.objs2("disks").orEmpty()).forEach { d ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(d.optString("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.j2_bal_pct, d.optInt("pct"), d.optInt("pct_after", d.optInt("pct"))),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val n = g?.optInt("count") ?: 0
                    if (n == 0) Note(stringResource(R.string.j2_bal_nothing))
                    else Text(stringResource(R.string.j2_bal_moves, pluralStringResource(R.plurals.j2_n_titles, n, n) + " · " +
                        humanBytes(g?.optLong("size") ?: 0L) + " · " + stringResource(R.string.j2_about, eta(g?.optLong("eta") ?: 0L))),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Note(stringResource(R.string.j2_bal_default_rules))
                }
            }
        }
        Section(stringResource(R.string.j2_bal_rules)) {
            // whole titles always: a series moves with all its seasons (not a choice on the agent)
            KeepRow(stringResource(R.string.j2_rule_series), true, enabled = false) {}
            KeepRow(stringResource(R.string.j2_rule_watching), watching) { watching = !watching }
            KeepRow(stringResource(R.string.j2_rule_seeding), seeding,
                sub = if (seeding) null else stringResource(R.string.j2_rule_seeding_off)) { seeding = !seeding }
            KeepRow(stringResource(R.string.j2_rule_backup), backup, sub = stringResource(R.string.j2_rule_backup_sub)) { backup = !backup }
        }
    }
}

// ---------------------------------------------------------------- B5 For Chromecast

@Composable
internal fun CastReadyJob(c: JobCtx) {
    var aac by choice("cast.add_aac", true)
    var subs by choice("cast.subs_to_srt", true)
    var avi by choice("cast.avi_to_mkv", true)
    var reload by remember { mutableIntStateOf(0) }
    val load = loadJobs(c.api, c.scopeJson, reload = reload)
    val j = load.info?.jobs?.get("cast_ready")
    val o = j?.options ?: JSONObject()
    val n = j?.count ?: 0
    JobFrame(c, stringResource(R.string.j2_cast), stringResource(R.string.j2_cast_desc), load.info?.scopeLabel,
        if (n > 0) pluralStringResource(R.plurals.j2_cast_cta, n, n) else stringResource(R.string.lib_preview),
        primaryEnabled = aac || subs || avi, onPrimary = {
            c.onPlan(planBody("cast_ready", c.scopeJson) { put("add_aac", aac); put("subs_to_srt", subs); put("avi_to_mkv", avi) })
        }, onRefresh = { reload++ }) {
        Section(pluralStringResource(R.plurals.j2_cast_found, n, n)) {
            when {
                load.info == null -> Note(stringResource(R.string.media_loading))
                n == 0 -> Note(j?.why ?: stringResource(R.string.job_nothing))
                else -> {
                    o.objs2("by_title").forEach { t ->
                        val files = t.optInt("files")
                        val name = if (t.optBoolean("series"))
                            stringResource(R.string.j2_cast_series, t.optString("title"), pluralStringResource(R.plurals.j2_n_episodes, files, files))
                        else t.optString("title")
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.padding(4.dp))
                            Note(t.optString("why"))
                        }
                    }
                    val more = o.optInt("more_files")
                    if (more > 0) Note(pluralStringResource(R.plurals.job_and_more, more, more))
                }
            }
            if ((j?.pending ?: 0) > 0) Note(pluralStringResource(R.plurals.j2_cast_pending, j!!.pending, j.pending))
        }
        Section(stringResource(R.string.j2_cast_what)) {
            KeepRow(stringResource(R.string.j2_cast_aac), aac) { aac = !aac }
            KeepRow(stringResource(R.string.j2_cast_subs), subs) { subs = !subs }
            KeepRow(stringResource(R.string.j2_cast_avi), avi) { avi = !avi }
            val per = if (n > 0) (j?.eta ?: 0L) / n else 0L
            Note(stringResource(R.string.j2_cast_note, if (per > 0) humanTime(maxOf(per, 60L)) else "?"))
        }
    }
}

// ---------------------------------------------------------------- C4 Background work

/**
 * Running, queued and recent tasks (agent 1.17+, `task_queue`): pause / resume a running one, cancel it with the inline
 * confirmation, move a queued one up or off the queue, and "Empty the trash and retry" under a task that ran out of space.
 */
@Composable
internal fun BackgroundWork2(api: Api, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var fix by remember { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }  // task, error group
    suspend fun refresh() {
        runCatching { withContext(Dispatchers.IO) { JSONArray(api.request("GET", "/api/tasks?brief=1")) } }.getOrNull()
            ?.let { a -> list = List(a.length()) { a.getJSONObject(it) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            MainVisible.await()
            refresh()
            delay(2000)
        }
    }
    fun act(path: String, body: JSONObject = JSONObject()) {
        cs.launch {
            try {
                withContext(Dispatchers.IO) { api.request("POST", path, body, 20000) }
                refresh()
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.job_bg_title), onBack) { cs.launch { refresh() } }
        val all = list ?: return Hint(stringResource(R.string.media_loading))
        if (all.isEmpty()) return Hint(stringResource(R.string.job_bg_empty))
        fun state(t: JSONObject) = t.optString("state")
        // a task paused by a person goes back to "queued" but keeps its card among the running ones
        val running = all.filter { state(it) == "running" || (state(it) == "queued" && it.optBoolean("held")) }
        val queued = all.filter { state(it) == "queued" && !it.optBoolean("held") }.sortedBy { it.optInt("queue", 999) }
        val recent = all.filter { state(it) != "queued" && state(it) != "running" }
            .sortedByDescending { it.optDouble("finished", 0.0) }.take(15)
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running.isNotEmpty()) {
                item(key = "h/run") { GroupHeading(stringResource(R.string.j2_bg_running)) }
                items(running, key = { "run/" + it.optString("id") }) { t ->
                    val id = t.optString("id")
                    RunningCard(t, api.srv.key, onPause = { act("/api/tasks/$id/pause") }, onResume = { act("/api/tasks/$id/resume") },
                        onCancel = { act("/api/tasks/$id/cancel") })
                }
            }
            if (queued.isNotEmpty()) {
                item(key = "h/queue") { GroupHeading(stringResource(R.string.j2_bg_queued)) }
                items(queued, key = { "q/" + it.optString("id") }) { t ->
                    val id = t.optString("id")
                    QueuedCard(t, canUp = queued.firstOrNull() !== t, onUp = { act("/api/tasks/$id/move", JSONObject().put("to", "up")) },
                        onRemove = { act("/api/tasks/$id/remove") })
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "h/recent") { GroupHeading(stringResource(R.string.j2_bg_recent)) }
                items(recent, key = { "done/" + it.optString("id") }) { t ->
                    val id = t.optString("id")
                    RecentLine(t, onFix = { g -> fix = t to g }, onRemove = if (api.srv.hasTaskRemove) ({ act("/api/tasks/$id/remove") }) else null)
                }
            }
        }
    }
    fix?.let { (t, g) ->
        val n = g.optInt("count")
        AlertDialog(
            onDismissRequest = { fix = null },
            title = { Text(stringResource(R.string.j2_fix_q, humanBytes(g.optLong("trash")))) },
            text = { Text(pluralStringResource(R.plurals.j2_fix_text, n, n)) },
            confirmButton = {
                TextButton({
                    fix = null
                    cs.launch {
                        try {
                            val freed = withContext(Dispatchers.IO) { api.trashEmpty() }
                            withContext(Dispatchers.IO) { api.request("POST", "/api/tasks/${t.optString("id")}/retry", JSONObject(), 20000) }
                            refresh()
                            snack.showSnackbar(ctx.getString(R.string.j2_fix_done, humanBytes(freed)))
                        } catch (e: ApiException) {
                            snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error))
                        }
                    }
                }) { Text(stringResource(R.string.j2_fix_link), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ fix = null }) { Text(stringResource(R.string.media_cancel)) } },
        )
    }
}

/** A task's name with what it was made for ("Compression · Anime"), unless the name already says it. */
private fun taskTitle(t: JSONObject): String {
    val title = t.optString("title")
    val scope = t.optStr("scope_label")
    return if (scope != null && scope !in title) "$title · $scope" else title
}

@Composable
private fun RunningCard(t: JSONObject, srvKey: String, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val id = t.optString("id")
    var asking by remember(id) { mutableStateOf(false) }
    var cancelling by remember(id) { mutableStateOf(false) }
    val held = t.optBoolean("held")
    val total = t.optInt("item_count")
    val done = t.optInt("items_done")
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(t.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (cancelling) R.string.lib_task_cancelling
                    else if (t.optBoolean("paused")) R.string.j2_state_paused else R.string.j2_state_running),
                    Modifier.padding(start = 8.dp), style = small, color = dim)
            }
            val sub = buildList {
                t.optStr("scope_label")?.let { add(it) }
                if (total > 0) add(stringResource(R.string.j2_files_of, done, total))
                t.optLong("saved").takeIf { it > 0 }?.let { add(stringResource(R.string.j2_saved_so_far, humanBytes(it))) }
            }
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = small, color = dim)
            LinearProgressIndicator(progress = { (t.optInt("pct") / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            val now = buildList {
                t.optStr("current")?.let { add(stringResource(R.string.j2_now, it)) }
                t.optStr("encoder")?.let { add(it) }
                t.optLong("eta").takeIf { it > 0 && !held }?.let { add(stringResource(R.string.j2_left, humanTime(it))) }
            }
            if (now.isNotEmpty()) Text(now.joinToString(" · "), style = small, color = dim, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            // why it is slower or waiting (someone watches from the same disk); "Paused" by a person is the state above
            if (!held) t.optStr("note")?.let { Text(it, style = small, color = MaterialTheme.colorScheme.tertiary) }
            if (!cancelling) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(if (held) onResume else onPause) {
                    Text(stringResource(if (held) R.string.j2_resume else R.string.j2_pause))
                }
                WatchToggle(srvKey, id)
                Spacer(Modifier.weight(1f))
                TextButton({ asking = !asking }) { Text(stringResource(R.string.j2_cancel), color = MaterialTheme.colorScheme.error) }
            }
            if (asking && !cancelling) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    val current = if (t.optStr("current") != null && !held) 1 else 0
                    val left = maxOf(0, total - done - current)
                    Text(stringResource(if (current > 0) R.string.j2_cancel_q else R.string.j2_cancel_q_between, t.optString("title"), done, left),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ asking = false }) { Text(stringResource(R.string.j2_no)) }
                        Button({ asking = false; cancelling = true; onCancel() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError)) { Text(stringResource(R.string.j2_stop)) }
                    }
                }
            }
        }
    }
}

/** Follow a task started elsewhere with the notification (as on the old task cards). */
@Composable
private fun WatchToggle(srvKey: String, id: String) {
    val ctx = LocalContext.current
    val wv by TaskWatch.version.collectAsState()
    val watched = remember(wv, id) { TaskWatch.isWatched(ctx, srvKey, id) }
    IconButton({ if (watched) TaskWatch.unwatch(ctx, srvKey, id) else TaskWatch.watch(ctx, srvKey, id) }) {
        Icon(if (watched) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive,
            stringResource(if (watched) R.string.lib_task_unwatch else R.string.lib_task_watch))
    }
}

@Composable
private fun QueuedCard(t: JSONObject, canUp: Boolean, onUp: () -> Unit, onRemove: () -> Unit) {
    val n = t.optInt("item_count")
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(taskTitle(t) + if (n > 0) " · " + pluralStringResource(R.plurals.lib_files, n, n) else "",
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val est = t.optStr("estimate") ?: t.optLong("eta").takeIf { it > 0 }?.let { stringResource(R.string.j2_about, humanTime(it)) }
                est?.let { Note(it) }
            }
            IconButton(onUp, enabled = canUp) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.j2_up)) }
            IconButton(onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.j2_remove_q)) }
        }
    }
}

@Composable
private fun RecentLine(t: JSONObject, onFix: (JSONObject) -> Unit, onRemove: (() -> Unit)?) {
    val ctx = LocalContext.current
    val fin = t.optDouble("finished", 0.0).takeIf { !it.isNaN() && it > 0 }
    val day = fin?.let {
        val ms = (it * 1000).toLong()
        when {
            android.text.format.DateUtils.isToday(ms) -> stringResource(R.string.j2_today)
            android.text.format.DateUtils.isToday(ms + 86_400_000L) -> stringResource(R.string.j2_yesterday)
            else -> android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(ms))
        }
    }
    val n = t.optInt("item_count")
    val errors = t.optInt("errors")
    val what = when {
        t.optString("state") == "cancelled" -> stringResource(R.string.j2_recent_cancelled)
        errors > 0 -> pluralStringResource(R.plurals.j2_recent_failed, errors, errors)
        else -> pluralStringResource(R.plurals.j2_recent_ok, n, n)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(day, taskTitle(t)).joinToString(" · ") + ": " + what, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            onRemove?.let { IconButton(it) { Icon(Icons.Filled.Close, stringResource(R.string.lib_task_remove)) } }
        }
        t.objs2("error_groups").forEach { g ->
            val c = g.optInt("count")
            val e = g.optString("error")
            val err = MaterialTheme.colorScheme.error
            Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                if (g.optString("fix") == "empty_trash_retry") {
                    val disk = g.optStr("disk")
                    Text((if (c > 1) "$c × " else "") + (disk?.let { stringResource(R.string.j2_fix_space, it, humanBytes(g.optLong("trash"))) } ?: e),
                        style = MaterialTheme.typography.bodySmall, color = err)
                    if (g.optLong("trash") > 0) Text(stringResource(R.string.j2_fix_link), Modifier.clickable { onFix(g) }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else Text(if (c > 1) "$c × $e" else e, style = MaterialTheme.typography.bodySmall, color = err, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

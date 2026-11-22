package net.treetank.magneticplayer

// Stage 6 of docs/plan-ux-tasks.md (agent 1.18.1+, "reencode" in `features.jobs`): Re-encode (advanced), board B6, on the
// task screen template of LibraryJobs.kt (F6). Six steps chosen by the owner, one ffmpeg run per file on the agent
// (mp_tasks_reencode.py); presets ("Start from") and "Save set" are kept on the agent (`reencode_presets` in
// /api/config/jobs); the summary card comes from GET /api/library/jobs?only=reencode&reencode=<steps>.

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The board's order of the six steps (the encode's own order is fixed on the agent). */
private val RE_STEPS = listOf("resolution", "codec", "picture", "sound", "cast", "container")

/** A step set offered under "Start from": built-in (named by the agent in the phone's language) or saved. */
internal data class ReencodePreset(val id: String, val name: String, val builtin: Boolean, val steps: JSONObject)

private fun presetsOf(a: org.json.JSONArray?): List<ReencodePreset> =
    a?.let { List(it.length()) { i -> it.getJSONObject(i) } }?.map {
        ReencodePreset(it.optString("id"), it.optString("name"), it.optBoolean("builtin"), it.optJSONObject("steps") ?: JSONObject())
    } ?: emptyList()

/** Every step off, with the agent's default options (what "Empty" starts from). */
private fun emptySteps(): JSONObject = JSONObject().apply {
    put("resolution", JSONObject().put("on", false).put("height", 720))
    put("codec", JSONObject().put("on", false).put("mode", "hevc_saving"))
    put("picture", JSONObject().put("on", false).put("deinterlace", true).put("denoise", false).put("crop", true))
    put("sound", JSONObject().put("on", false).put("mode", "fix"))
    put("cast", JSONObject().put("on", false).put("add_aac", true).put("subs_to_srt", true))
    put("container", JSONObject().put("on", false).put("format", "mkv"))
}

/** A copy of [steps] with one option of one step changed. */
private fun withOpt(steps: String, step: String, opt: String, v: Any): String {
    val o = JSONObject(steps)
    val s = o.optJSONObject(step) ?: JSONObject().also { o.put(step, it) }
    s.put(opt, v)
    return o.toString()
}

/** Whether a step can change anything (on, with something chosen inside), as the agent decides it. */
private fun stepActive(s: JSONObject?): Boolean {
    s ?: return false
    if (!s.optBoolean("on")) return false
    if (s.has("deinterlace")) return s.optBoolean("deinterlace") || s.optBoolean("denoise") || s.optBoolean("crop")
    if (s.has("add_aac")) return s.optBoolean("add_aac") || s.optBoolean("subs_to_srt")
    if (s.has("mode") && s.optString("mode") == "leave") return false
    return true
}

/** The re-encode counter for [steps] in [scope] (agent 1.18.1+), only that one. */
private fun Api.reencodeCounter(scope: JSONObject?, steps: String): JobInfo? {
    val q = buildList {
        if (scope != null && scope.length() > 0) add("scope=" + Uri.encode(scope.toString()))
        add("only=reencode")
        add("reencode=" + Uri.encode(steps))
    }
    val o = JSONObject(request("GET", "/api/library/jobs?" + q.joinToString("&"), timeout = 20000))
    val x = o.optJSONObject("jobs")?.optJSONObject("reencode") ?: return null
    return JobInfo(x.optBoolean("applicable", true), x.optInt("count"), x.optLong("size"), x.optLong("saving"), x.optLong("eta"),
        x.optStr("why"), x.optInt("pending"), x.optJSONObject("options") ?: JSONObject())
}

@Composable
private fun stepName(k: String) = stringResource(when (k) {
    "resolution" -> R.string.re_step_resolution
    "codec" -> R.string.re_step_codec
    "picture" -> R.string.re_step_picture
    "sound" -> R.string.re_step_sound
    "cast" -> R.string.re_step_cast
    else -> R.string.re_step_container
})

@Composable
private fun codecName(m: String) = stringResource(when (m) {
    "hevc_quality" -> R.string.re_codec_hevc_quality
    "h264" -> R.string.re_codec_h264
    else -> R.string.re_codec_hevc_saving
})

@Composable
private fun soundName(m: String) = stringResource(when (m) {
    "loudness" -> R.string.re_sound_loudness
    "leave" -> R.string.re_sound_leave
    else -> R.string.re_sound_fix
})

/** The one-line summary of a step's chosen option, or "skipped". */
@Composable
private fun stepSummary(k: String, s: JSONObject?): String {
    if (s == null || !stepActive(s)) return stringResource(R.string.re_skipped)
    return when (k) {
        "resolution" -> "${s.optInt("height", 720)}p"
        "codec" -> codecName(s.optString("mode"))
        "picture" -> listOfNotNull(
            if (s.optBoolean("deinterlace")) stringResource(R.string.re_pic_deinterlace) else null,
            if (s.optBoolean("denoise")) stringResource(R.string.re_pic_denoise) else null,
            if (s.optBoolean("crop")) stringResource(R.string.re_pic_crop) else null).joinToString(", ")
        "sound" -> soundName(s.optString("mode"))
        "cast" -> listOfNotNull(
            if (s.optBoolean("add_aac")) stringResource(R.string.re_cast_aac) else null,
            if (s.optBoolean("subs_to_srt")) stringResource(R.string.re_cast_subs) else null).joinToString(", ")
        else -> s.optString("format", "mkv").uppercase()
    }
}

/** B6: presets, the files, six steps (one expanded at a time), the summary card, "Save set" and "Preview". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReencodeJob(c: JobCtx) {
    val ctx = LocalContext.current
    val co = rememberCoroutineScope()
    var steps by choice("re.steps", "")
    var preset by choice("re.preset", "series_archive")
    var modified by choice("re.modified", false)
    var open by choice("re.open", "")
    var folders by choice("re.folders", emptySet<Pair<Int, String>>())
    var names by choice("re.names", emptyMap<Int, String>())
    var presets by remember { mutableStateOf<List<ReencodePreset>?>(null) }
    var reloadPresets by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(reloadPresets) {
        runCatching { withContext(Dispatchers.IO) { presetsOf(c.api.jobsConfig().optJSONArray("reencode_presets")) } }
            .onSuccess { list ->
                presets = list
                // first visit: start from the first built-in set ("Series archive")
                if (steps.isEmpty()) steps = (list.firstOrNull { it.id == preset }?.steps ?: emptySteps()).toString()
            }
            .onFailure { e -> c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
    }
    val cur = remember(steps) { if (steps.isEmpty()) emptySteps() else JSONObject(steps) }
    val anyOn = RE_STEPS.any { stepActive(cur.optJSONObject(it)) }
    val eff = c.scopeJson ?: JobScope.rootsJson(folders)
    // the summary card: read again (after a short pause) when the steps or the files change
    var info by remember { mutableStateOf<JobInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(steps, eff?.toString()) {
        if (steps.isEmpty() || !anyOn) { info = null; return@LaunchedEffect }
        loading = true
        delay(400)
        info = runCatching { withContext(Dispatchers.IO) { c.api.reencodeCounter(eff, steps) } }
            .onFailure { e -> c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }.getOrNull()
        loading = false
    }
    fun edit(step: String, opt: String, v: Any) {
        steps = withOpt(steps.ifEmpty { emptySteps().toString() }, step, opt, v)
        modified = true
    }
    val presetName = presets?.firstOrNull { it.id == preset }?.name?.takeIf { !modified }
    JobFrame(c, stringResource(R.string.re_title), stringResource(R.string.re_desc), null,
        stringResource(R.string.lib_preview), primaryEnabled = anyOn,
        secondary = stringResource(R.string.re_save), onSecondary = { saving = true },
        onPrimary = {
            c.onPlan(planBody("reencode", eff) {
                put("steps", JSONObject(steps)); put("encoder", "auto"); presetName?.let { put("name", it) }
            })
        }) {
        Section(stringResource(R.string.re_start_from)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.orEmpty().forEach { p ->
                    FilterChip(preset == p.id, {
                        preset = p.id; steps = p.steps.toString(); modified = false
                    }, label = { Text(p.name) })
                }
                FilterChip(preset == "empty", { preset = "empty"; steps = emptySteps().toString(); modified = false },
                    label = { Text(stringResource(R.string.re_empty)) })
            }
            if (presets == null) Note(stringResource(R.string.media_loading))
            else if (modified) Note(stringResource(R.string.re_modified))
        }
        // the files: a folder picker on the whole computer; in a scope, JobFrame's scope bar stands instead
        if (c.scope == null) Section(stringResource(R.string.re_files)) {
            FoldersPicker(c.api, folders, names) { f, nm -> folders = f; names = nm }
        }
        Section(stringResource(R.string.re_steps)) {
            RE_STEPS.forEach { k ->
                val s = cur.optJSONObject(k) ?: JSONObject()
                StepCard(k, s, expanded = open == k, onExpand = { open = if (open == k) "" else k },
                    onSwitch = { on -> edit(k, "on", on); if (on) open = k }) {
                    StepOptions(k, s) { opt, v -> edit(k, opt, v); if (!s.optBoolean("on")) edit(k, "on", true) }
                }
            }
        }
        Summary {
            val j = info
            when {
                !anyOn -> Text(stringResource(R.string.re_none_on), style = MaterialTheme.typography.bodyMedium)
                j == null -> Note(stringResource(R.string.media_loading))
                j.count == 0 -> Text(j.why ?: stringResource(R.string.job_nothing), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val k = j.options.optInt("passes")
                    val sav = if (j.saving >= 0) "−" + humanBytes(j.saving) else "+" + humanBytes(-j.saving)
                    Text(if (k > 1) pluralStringResource(R.plurals.re_summary, j.count, j.count, k, eta(j.eta), sav)
                        else pluralStringResource(R.plurals.re_summary_one, j.count, j.count, eta(j.eta), sav),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (loading && j != null) Note(stringResource(R.string.media_loading))
            j?.options?.optInt("unmeasured")?.takeIf { it > 0 }?.let { n -> Note(pluralStringResource(R.plurals.re_unmeasured, n, n)) }
            j?.options?.optInt("skipped")?.takeIf { it > 0 }?.let { n -> Note(pluralStringResource(R.plurals.re_skipped_files, n, n)) }
            Note(stringResource(R.string.re_order_note))
            val warns = j?.options?.optJSONArray("warnings")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            warns.forEach { Note("⚠ $it", MaterialTheme.colorScheme.tertiary) }
        }
    }
    if (saving) SaveSetDialog(presetName ?: "", onDismiss = { saving = false }) { name ->
        saving = false
        co.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    c.api.setJobsConfig(JSONObject().put("reencode_preset", JSONObject().put("name", name).put("steps", cur)))
                    presetsOf(c.api.jobsConfig().optJSONArray("reencode_presets"))
                }
            }.onSuccess { list ->
                presets = list
                list.lastOrNull { !it.builtin && it.name.equals(name, ignoreCase = true) }?.let { preset = it.id; modified = false }
                c.snack.showSnackbar(ctx.getString(R.string.re_saved))
            }.onFailure { e -> c.snack.showSnackbar(e.message ?: ctx.getString(R.string.media_error)) }
        }
    }
}

/** One step: switch, name and the chosen option in one line; tapping the row opens its options (one at a time). */
@Composable
private fun StepCard(k: String, s: JSONObject, expanded: Boolean, onExpand: () -> Unit, onSwitch: (Boolean) -> Unit,
                     options: @Composable () -> Unit) {
    val on = s.optBoolean("on")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (on) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
                Switch(on, onSwitch)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stepName(k), style = MaterialTheme.typography.titleSmall)
                    Text(stepSummary(k, s), style = MaterialTheme.typography.bodySmall,
                        color = if (stepActive(s)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (expanded) Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options()
            }
        }
    }
}

/** A step's options: single choice chips, or several (old picture, Chromecast). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepOptions(k: String, s: JSONObject, set: (String, Any) -> Unit) {
    when (k) {
        "resolution" -> {
            Chips(listOf(720, 480, 360).map { it to "${it}p" }, s.optInt("height", 720)) { set("height", it) }
            Note(stringResource(R.string.re_res_hint))
        }
        "codec" -> {
            Chips(listOf("hevc_saving", "hevc_quality", "h264").map { it to codecName(it) }, s.optString("mode", "hevc_saving")) { set("mode", it) }
            Note(stringResource(when (s.optString("mode")) {
                "hevc_quality" -> R.string.re_codec_hevc_quality_hint
                "h264" -> R.string.re_codec_h264_hint
                else -> R.string.re_codec_hevc_saving_hint
            }))
        }
        "picture" -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("deinterlace" to R.string.re_pic_deinterlace, "denoise" to R.string.re_pic_denoise, "crop" to R.string.re_pic_crop)
                    .forEach { (opt, label) ->
                        FilterChip(s.optBoolean(opt), { set(opt, !s.optBoolean(opt)) }, label = { Text(stringResource(label)) })
                    }
            }
            Note(stringResource(R.string.re_pic_hint))
        }
        "sound" -> {
            Chips(listOf("fix", "loudness", "leave").map { it to soundName(it) }, s.optString("mode", "fix")) { set("mode", it) }
            Note(stringResource(when (s.optString("mode")) {
                "loudness" -> R.string.re_sound_loudness_hint
                "leave" -> R.string.re_sound_leave_hint
                else -> R.string.re_sound_fix_hint
            }))
        }
        "cast" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("add_aac" to R.string.re_cast_aac, "subs_to_srt" to R.string.re_cast_subs).forEach { (opt, label) ->
                FilterChip(s.optBoolean(opt), { set(opt, !s.optBoolean(opt)) }, label = { Text(stringResource(label)) })
            }
        }
        else -> {
            Chips(listOf("mkv" to "MKV", "mp4" to "MP4"), s.optString("format", "mkv")) { set("format", it) }
            Note(stringResource(if (s.optString("format") == "mp4") R.string.re_mp4_hint else R.string.re_mkv_hint))
        }
    }
}

@Composable
private fun SaveSetDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.re_save_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.re_save_name)) }, singleLine = true)
                Note(stringResource(R.string.re_save_hint))
            }
        },
        confirmButton = { TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.re_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.media_cancel)) } },
    )
}

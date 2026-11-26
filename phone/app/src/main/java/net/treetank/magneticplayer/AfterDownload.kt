@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.treetank.magneticplayer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * After download (agent 1.23+, `features.afterdl`, docs/plan-after-download.md stage 2): Settings → After download
 * (sources, the film and series recipes with effect labels and warnings, a screen per step, templates), the runs in
 * the Downloads tab and one video's history. Field names follow docs/api.md "After download".
 */

// ---------------------------------------------------------------- model

data class AdlEffect(val labels: List<String>, val warning: String?)
data class AdlTemplate(val id: String, val title: String, val steps: List<JSONObject>, val effects: List<AdlEffect>)
data class AdlConfig(
    val enabled: Boolean, val sources: List<String>, val recipes: Map<String, List<JSONObject>>,
    val effects: Map<String, List<AdlEffect>>, val templates: List<AdlTemplate>,
)
data class AdlStep(val kind: String, val state: String, val note: String?, val auto: Boolean)
data class AdlEvent(val at: Double, val text: String, val step: Int?, val auto: Boolean)
data class AdlRun(
    val id: String, val torrent: String, val name: String, val source: String, val by: String?, val kind: String,
    val state: String, val created: Double, val finished: Double?, val step: Int, val steps: List<AdlStep>,
    val events: List<AdlEvent>,
)

private val ADL_KINDS = listOf("move", "subs", "cast_ready", "shrink", "seed_end")
private val ADL_SOURCES = listOf("app", "client", "archive")

private fun adlObjects(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getJSONObject(it) }
private fun adlStrings(a: JSONArray?) = List(a?.length() ?: 0) { a!!.optString(it) }
private fun adlEffects(a: JSONArray?) = adlObjects(a).map { AdlEffect(adlStrings(it.optJSONArray("labels")), it.optStr("warning")) }

fun adlConfig(o: JSONObject): AdlConfig {
    val rec = o.optJSONObject("recipes") ?: JSONObject()
    val fx = o.optJSONObject("effects") ?: JSONObject()
    return AdlConfig(
        o.optBoolean("enabled"), adlStrings(o.optJSONArray("sources")),
        rec.keys().asSequence().associateWith { adlObjects(rec.optJSONArray(it)) },
        fx.keys().asSequence().associateWith { adlEffects(fx.optJSONArray(it)) },
        adlObjects(o.optJSONArray("templates")).map {
            AdlTemplate(it.optString("id"), it.optString("title"), adlObjects(it.optJSONArray("steps")), adlEffects(it.optJSONArray("effects")))
        },
    )
}

fun adlRun(o: JSONObject) = AdlRun(
    o.optString("id"), o.optString("torrent"), o.optString("name"), o.optString("source"), o.optStr("by"),
    o.optString("kind", "film"), o.optString("state"), o.optDouble("created", 0.0),
    o.optDouble("finished").takeIf { !it.isNaN() && !o.isNull("finished") }, o.optInt("step"),
    adlObjects(o.optJSONArray("steps")).map { AdlStep(it.optString("kind"), it.optString("state"), it.optStr("note"), it.optBoolean("auto")) },
    adlObjects(o.optJSONArray("events")).map {
        AdlEvent(it.optDouble("at", 0.0), it.optString("text"), if (it.has("step") && !it.isNull("step")) it.optInt("step") else null,
            it.optBoolean("auto"))
    },
)

/** The same steps, option for option (a recipe that is still a template). */
private fun adlSame(a: List<JSONObject>, b: List<JSONObject>) = a.size == b.size && a.indices.all { i ->
    val x = a[i]; val y = b[i]
    val keys = x.keys().asSequence().toSet() + y.keys().asSequence().toSet()
    keys.all { k -> x.opt(k)?.toString() == y.opt(k)?.toString() || (x.opt(k) as? Number)?.toDouble() == (y.opt(k) as? Number)?.toDouble() }
}

private fun JSONObject.adlWith(k: String, v: Any): JSONObject = JSONObject(toString()).put(k, v)

// ---------------------------------------------------------------- shared look

private enum class AdlTone { PLAIN, OK, RUN, AUTO, WARN, ERR, SEED }

@Composable
private fun adlToneColors(t: AdlTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return when (t) {
        AdlTone.PLAIN -> cs.surfaceVariant to cs.onSurfaceVariant
        AdlTone.OK -> if (dark) Color(0xFF1F3A27) to Color(0xFFA5D6A7) else Color(0xFFDCF1E0) to Color(0xFF1B5E20)
        AdlTone.RUN -> cs.primaryContainer to cs.onPrimaryContainer
        AdlTone.AUTO -> cs.tertiaryContainer to cs.onTertiaryContainer
        AdlTone.WARN -> if (dark) Color(0xFF3E3115) to Color(0xFFFFD580) else Color(0xFFFFF0CC) to Color(0xFF6D4C00)
        AdlTone.ERR -> cs.errorContainer to cs.onErrorContainer
        AdlTone.SEED -> cs.secondaryContainer to cs.onSecondaryContainer
    }
}

/** A small label: an effect of a step, a step of a run. [spin] shows a progress ring instead of the icon. */
@Composable
private fun AdlTag(text: String, icon: ImageVector? = null, tone: AdlTone = AdlTone.PLAIN, spin: Boolean = false, faded: Boolean = false) {
    val (bg, fg) = adlToneColors(tone)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp), modifier = if (faded) Modifier.alpha(0.6f) else Modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (spin) CircularProgressIndicator(Modifier.size(12.dp), color = fg, strokeWidth = 1.5.dp)
            else if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AdlSection(icon: ImageVector, text: String) {
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AdlHint(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row inside a card: icon, title with a line under it, whatever is at the end. */
@Composable
private fun AdlRow(icon: ImageVector?, title: String, sub: String? = null, onClick: (() -> Unit)? = null,
                   trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun AdlValue(text: String, more: Boolean = true) {
    Text(if (more) "$text ›" else text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AdlSwitchRow(icon: ImageVector?, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    AdlRow(icon, title, sub, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
private fun AdlRadio(selected: Boolean, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A page of its own: back arrow, title, scrolled content (Back returns). */
@Composable
private fun AdlPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

private fun adlNum(x: Double, decimals: Int = 0): String =
    if (decimals == 0 && x == Math.floor(x)) x.toLong().toString() else String.format(Locale.getDefault(), "%.${maxOf(decimals, 1)}f", x)

private fun adlWhen(ctx: Context, at: Double): String {
    val d = Date((at * 1000).toLong())
    val hm = DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
    val c = Calendar.getInstance().apply { time = d }
    val now = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        same(c, now) -> hm
        same(c, yesterday) -> ctx.getString(R.string.adl_yesterday_at, hm)
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(d)
    }
}

// ---------------------------------------------------------------- steps: names, icons, labels

private fun adlStepIcon(kind: String): ImageVector = when (kind) {
    "move" -> Icons.AutoMirrored.Filled.DriveFileMove
    "subs" -> Icons.Filled.Subtitles
    "cast_ready" -> Icons.Filled.Smartphone
    "shrink" -> Icons.Filled.Compress
    "seed_end" -> Icons.Filled.CloudOff
    else -> Icons.Filled.Tune
}

private fun adlStepTone(kind: String): AdlTone = when (kind) {
    "move" -> AdlTone.AUTO
    "subs" -> AdlTone.RUN
    "cast_ready" -> AdlTone.OK
    "shrink" -> AdlTone.WARN
    "seed_end" -> AdlTone.SEED // its own tile: a plain one vanished into the card
    else -> AdlTone.PLAIN
}

@Composable
private fun adlStepName(kind: String): String = when (kind) {
    "move" -> stringResource(R.string.adl_step_move)
    "subs" -> stringResource(R.string.adl_step_subs)
    "cast_ready" -> stringResource(R.string.adl_step_cast_ready)
    "shrink" -> stringResource(R.string.adl_step_shrink)
    "seed_end" -> stringResource(R.string.adl_step_seed_end)
    else -> kind
}

private fun adlStepName(ctx: Context, kind: String): String = ctx.getString(when (kind) {
    "move" -> R.string.adl_step_move
    "subs" -> R.string.adl_step_subs
    "cast_ready" -> R.string.adl_step_cast_ready
    "shrink" -> R.string.adl_step_shrink
    else -> R.string.adl_step_seed_end
})

/** The short name on a run's pills (Downloads tab). */
@Composable
private fun adlShortName(kind: String): String = when (kind) {
    "cast_ready" -> stringResource(R.string.adl_short_cast_ready)
    "seed_end" -> stringResource(R.string.adl_short_seed_end)
    else -> adlStepName(kind)
}

@Composable
private fun adlDays(d: Double): String = pluralStringResource(R.plurals.adl_days, if (d == Math.floor(d)) d.toInt() else 5, adlNum(d))

/** What a step costs (the server's labels) and what its options say, as tags. */
@Composable
private fun AdlStepTags(step: JSONObject, fx: AdlEffect?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fx?.labels?.forEach { l ->
            when (l) {
                "keeps_file" -> AdlTag(stringResource(R.string.adl_fx_keeps_file), Icons.Filled.CheckCircle, AdlTone.OK)
                "changes_file" -> AdlTag(stringResource(R.string.adl_fx_changes_file), Icons.Filled.Sync, AdlTone.AUTO)
                "ends_seeding" -> AdlTag(stringResource(R.string.adl_fx_ends_seeding), Icons.Filled.ContentCut, AdlTone.WARN)
                "gpu" -> AdlTag(stringResource(R.string.adl_fx_gpu), Icons.Filled.Memory, AdlTone.RUN)
                "moves" -> {
                    AdlTag(stringResource(R.string.adl_fx_moves), Icons.Filled.CloudUpload, AdlTone.OK)
                    AdlTag(stringResource(R.string.adl_fx_moves_pause), Icons.Filled.Schedule, AdlTone.WARN)
                }
                "no_effect" -> AdlTag(stringResource(R.string.adl_fx_no_effect), Icons.Filled.Block, AdlTone.PLAIN)
            }
        }
        when (step.optString("kind")) {
            "subs" -> {
                val langs = adlStrings(step.optJSONArray("langs"))
                if (langs.isNotEmpty()) AdlTag(stringResource(R.string.adl_tag_always, langs.joinToString(", ")), Icons.Filled.Translate)
                if (step.optBoolean("downloader", true)) AdlTag(stringResource(R.string.adl_tag_downloader), Icons.Filled.Person)
            }
            "cast_ready" -> if (step.optString("mode") == "keep_seeding") AdlTag(stringResource(R.string.adl_tag_keep_seeding), Icons.Filled.CloudUpload, AdlTone.OK)
            "shrink" -> AdlTag(stringResource(R.string.adl_tag_shrink_above, adlNum(step.optDouble("min_gb", 8.0)),
                adlNum(step.optDouble("min_mbps", 6.0))), Icons.Filled.Straighten)
            "seed_end" -> {
                AdlTag(stringResource(R.string.adl_tag_ratio, adlNum(step.optDouble("ratio", 2.0), 1)), Icons.Filled.Percent, AdlTone.OK)
                AdlTag(stringResource(R.string.adl_tag_or, adlDays(step.optDouble("days", 7.0))), Icons.Filled.Event, AdlTone.OK)
            }
        }
    }
}

// ---------------------------------------------------------------- Settings → After download

/**
 * Settings → After download and its pages: the recipe of films or episodes, one step's options, templates.
 * Every change is saved at once and the page shows what the server answered (with the effects of the new order).
 */
@Composable
fun AfterDownloadSettings(api: Api, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember(api.srv.key) { mutableStateOf<AdlConfig?>(null) }
    var error by remember(api.srv.key) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    // "" the main page, "recipe/<film|series>", "templates/<kind>", "step/<kind>/<index>"
    var page by rememberSaveable(api.srv.key) { mutableStateOf("") }

    LaunchedEffect(api.srv.key, reload) {
        try {
            cfg = withContext(Dispatchers.IO) { api.afterdlConfig() }
            error = null
        } catch (e: ApiException) {
            error = e.message ?: ctx.getString(R.string.main_error)
        }
    }

    fun save(changes: JSONObject) {
        scope.launch {
            try {
                cfg = withContext(Dispatchers.IO) { api.setAfterdl(changes) }
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
                reload++
            }
        }
    }
    fun saveRecipe(kind: String, steps: List<JSONObject>) {
        cfg = cfg?.let { it.copy(recipes = it.recipes + (kind to steps)) }  // at once; the answer brings the effects
        save(JSONObject().put("recipes", JSONObject().put(kind, JSONArray(steps))))
    }
    fun useTemplate(kind: String, id: String) = save(JSONObject().put("recipes", JSONObject().put(kind, id)))

    val c = cfg
    if (c == null) {
        AdlPage(stringResource(R.string.adl_title), onBack) {
            if (error != null) {
                AdlHint(error!!)
                OutlinedButton({ error = null; reload++ }) { Text(stringResource(R.string.adl_retry_load)) }
            } else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val parts = page.split('/')
    val kind = parts.getOrNull(1) ?: "film"
    val steps = c.recipes[kind].orEmpty()
    when (parts[0]) {
        "recipe" -> AdlRecipe(c, kind, onBack = { page = "" }, onSave = { saveRecipe(kind, it) },
            onTemplate = { useTemplate(kind, it) }, onTemplates = { page = "templates/$kind" }) { page = "step/$kind/$it" }
        "templates" -> AdlTemplates(c, kind, onBack = { page = "recipe/$kind" }) { useTemplate(kind, it); page = "recipe/$kind" }
        "step" -> {
            val i = parts.getOrNull(2)?.toIntOrNull() ?: -1
            val step = steps.getOrNull(i)
            if (step == null) LaunchedEffect(Unit) { page = "recipe/$kind" }
            else AdlStepScreen(step, onBack = { page = "recipe/$kind" }) { n -> saveRecipe(kind, steps.toMutableList().also { it[i] = n }) }
        }
        else -> AdlMain(api.srv.name, c, onBack, { save(it) }) { page = "recipe/$it" }
    }
}

@Composable
private fun AdlMain(server: String, c: AdlConfig, onBack: () -> Unit, save: (JSONObject) -> Unit, openRecipe: (String) -> Unit) {
    AdlPage(stringResource(R.string.adl_title), onBack) {
        AdlHint(stringResource(R.string.adl_hint, server))
        Card(Modifier.fillMaxWidth()) {
            AdlSwitchRow(Icons.Filled.PlayCircle, stringResource(R.string.adl_enabled), stringResource(R.string.adl_enabled_desc),
                c.enabled) { save(JSONObject().put("enabled", it)) }
        }
        AdlSection(Icons.Filled.Download, stringResource(R.string.adl_sec_sources))
        Card(Modifier.fillMaxWidth()) {
            ADL_SOURCES.forEach { src ->
                val (icon, title, sub) = when (src) {
                    "app" -> Triple(Icons.Filled.Smartphone, R.string.adl_src_app, null)
                    "client" -> Triple(Icons.Filled.Computer, R.string.adl_src_client, R.string.adl_src_client_desc)
                    else -> Triple(Icons.Filled.AccountBalance, R.string.adl_src_archive, null)
                }
                AdlSwitchRow(icon, stringResource(title), sub?.let { stringResource(it) }, src in c.sources) { on ->
                    val n = if (on) ADL_SOURCES.filter { it in c.sources || it == src } else c.sources - src
                    save(JSONObject().put("sources", JSONArray(n)))
                }
            }
        }
        AdlSection(Icons.Filled.FormatListNumbered, stringResource(R.string.adl_sec_recipes))
        Card(Modifier.fillMaxWidth()) {
            listOf("film", "series").forEach { k ->
                val names = c.recipes[k].orEmpty().map { adlStepName(it.optString("kind")) }
                val list = if (names.isEmpty()) stringResource(R.string.adl_no_steps) else names.joinToString(" · ")
                AdlRow(if (k == "film") Icons.Filled.Movie else Icons.Filled.LiveTv,
                    stringResource(if (k == "film") R.string.adl_recipe_film else R.string.adl_recipe_series),
                    if (k == "film") list else stringResource(R.string.adl_series_each, list), onClick = { openRecipe(k) }) {
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun adlTemplateIcon(id: String): ImageVector = when (id) {
    "right_away" -> Icons.Filled.Smartphone
    "free_gpu" -> Icons.Filled.Memory
    "archive" -> Icons.Filled.Inventory2
    "seeder" -> Icons.Filled.CloudUpload
    else -> Icons.Filled.Tune
}

/** Screen 2: the steps of one recipe, in order, with their labels and warnings; templates at the top. */
@Composable
private fun AdlRecipe(c: AdlConfig, kind: String, onBack: () -> Unit, onSave: (List<JSONObject>) -> Unit, onTemplate: (String) -> Unit,
                      onTemplates: () -> Unit, onOpen: (Int) -> Unit) {
    val steps = c.recipes[kind].orEmpty()
    val fx = c.effects[kind].orEmpty().takeIf { it.size == steps.size }  // stale while a change is being saved
    val current = c.templates.firstOrNull { adlSame(it.steps, steps) }?.id
    var ask by remember { mutableStateOf<AdlTemplate?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val left = remember(kind) { mutableStateListOf<String>() }  // warnings "Leave" was tapped on (this visit)
    val title = stringResource(if (kind == "film") R.string.adl_recipe_film else R.string.adl_recipe_series)

    fun move(from: Int, to: Int) {
        val l = steps.toMutableList()
        val s = l.removeAt(from)
        l.add(to.coerceIn(0, l.size), s)
        onSave(l)
    }

    AdlPage(title, onBack) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.templates.forEach { t ->
                FilterChip(current == t.id, { if (current != t.id) ask = t }, { Text(t.title, maxLines = 1) },
                    leadingIcon = { Icon(adlTemplateIcon(t.id), null, Modifier.size(18.dp)) })
            }
            FilterChip(current == null, onTemplates, { Text(stringResource(R.string.adl_tpl_custom)) },
                leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.adl_recipe_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onTemplates) { Text(stringResource(R.string.adl_templates)) }
        }
        val seedAt = steps.indexOfFirst { it.optString("kind") == "seed_end" }
        val belowSeed = stringResource(R.string.adl_fix_below_seed)
        val leave = stringResource(R.string.adl_fix_leave)
        val ctx = LocalContext.current
        steps.forEachIndexed { i, step ->
            val e = fx?.getOrNull(i)
            val warnKey = "$i:${step.optString("kind")}:${e?.warning}"
            AdlStepCard(i, step, e, last = i == steps.lastIndex,
                warning = e?.warning?.takeIf { warnKey !in left },
                fixes = buildList<Pair<String, () -> Unit>> {
                    val labels = e?.labels.orEmpty()
                    if ("ends_seeding" in labels && seedAt > i) add(belowSeed to { move(i, seedAt) })
                    if (step.optString("kind") == "seed_end" && "no_effect" in labels) {
                        val ends = fx?.indexOfFirst { "ends_seeding" in it.labels } ?: -1
                        if (ends in 0 until i) add(ctx.getString(R.string.adl_fix_above, ends + 1) to { move(i, ends) })
                    }
                    add(leave to { left += warnKey })
                },
                onUp = { move(i, i - 1) }, onDown = { move(i, i + 1) },
                onRemove = { onSave(steps.toMutableList().also { it.removeAt(i) }) }) { onOpen(i) }
        }
        if (steps.isEmpty()) AdlHint(stringResource(R.string.adl_recipe_empty))
        Box {
            OutlinedButton({ addMenu = true }, enabled = steps.size < 10) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.adl_add_step))
            }
            DropdownMenu(addMenu, { addMenu = false }) {
                ADL_KINDS.forEach { k ->
                    val once = k == "seed_end" && seedAt >= 0
                    DropdownMenuItem({ Text(adlStepName(k)) }, {
                        addMenu = false
                        val defaults = c.templates.flatMap { it.steps }.firstOrNull { it.optString("kind") == k }
                        onSave(steps + (defaults?.let { JSONObject(it.toString()) } ?: JSONObject().put("kind", k)))
                    }, leadingIcon = { Icon(adlStepIcon(k), null) }, enabled = !once)
                }
            }
        }
    }
    ask?.let { t ->
        AlertDialog(
            onDismissRequest = { ask = null },
            icon = { Icon(adlTemplateIcon(t.id), null) },
            title = { Text(stringResource(R.string.adl_tpl_apply_title, t.title)) },
            text = { Text(stringResource(R.string.adl_tpl_apply_text, title)) },
            confirmButton = { TextButton({ ask = null; onTemplate(t.id) }) { Text(stringResource(R.string.adl_tpl_use)) } },
            dismissButton = { TextButton({ ask = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}

@Composable
private fun AdlStepCard(i: Int, step: JSONObject, fx: AdlEffect?, last: Boolean, warning: String?, fixes: List<Pair<String, () -> Unit>>,
                        onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    val kind = step.optString("kind")
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg) = adlToneColors(adlStepTone(kind))
                Box(Modifier.size(40.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(adlStepIcon(kind), null, tint = fg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.adl_step_n, i + 1), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(adlStepName(kind), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onUp, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.adl_move_up)) }
                IconButton(onDown, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.adl_move_down)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.adl_step_menu)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.adl_step_options)) }, { menu = false; onOpen() },
                            leadingIcon = { Icon(Icons.Filled.Tune, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.adl_remove_step)) }, { menu = false; onRemove() },
                            leadingIcon = { Icon(Icons.Filled.Close, null) })
                    }
                }
            }
            Box(Modifier.padding(end = 12.dp)) { AdlStepTags(step, fx) }
            if (warning != null) {
                val (bg, fg) = adlToneColors(AdlTone.WARN)
                Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            fixes.forEach { (label, act) -> TextButton(act) { Text(label, color = fg) } }
                        }
                    }
                }
            }
        }
    }
}

/** Screen 2c: the templates, each a starting point to change afterwards. */
@Composable
private fun AdlTemplates(c: AdlConfig, kind: String, onBack: () -> Unit, onUse: (String) -> Unit) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf<AdlTemplate?>(null) }
    val title = stringResource(if (kind == "film") R.string.adl_recipe_film else R.string.adl_recipe_series)
    AdlPage(stringResource(R.string.adl_templates), onBack) {
        AdlHint(stringResource(R.string.adl_tpl_hint))
        c.templates.forEach { t ->
            Card(Modifier.fillMaxWidth().clickable { ask = t }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(adlTemplateIcon(t.id), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (adlSame(t.steps, c.recipes[kind].orEmpty())) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(t.steps.joinToString(" → ") { adlStepName(ctx, it.optString("kind")) }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val warn = t.effects.mapNotNull { it.warning }
                    val cuts = t.effects.indexOfFirst { "ends_seeding" in it.labels }
                    if (cuts >= 0 || warn.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cuts >= 0) AdlTag(stringResource(R.string.adl_tpl_cuts_at, cuts + 1), Icons.Filled.ContentCut, AdlTone.WARN)
                        if (warn.isNotEmpty()) AdlTag(pluralStringResource(R.plurals.adl_warnings, warn.size, warn.size), Icons.Filled.Warning, AdlTone.WARN)
                    }
                }
            }
        }
    }
    ask?.let { t ->
        AlertDialog(
            onDismissRequest = { ask = null },
            icon = { Icon(adlTemplateIcon(t.id), null) },
            title = { Text(stringResource(R.string.adl_tpl_apply_title, t.title)) },
            text = { Text(stringResource(R.string.adl_tpl_apply_text, title)) },
            confirmButton = { TextButton({ ask = null; onUse(t.id) }) { Text(stringResource(R.string.adl_tpl_use)) } },
            dismissButton = { TextButton({ ask = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}

// ---------------------------------------------------------------- one step's screen (2b, 3, 4, 5)

@Composable
private fun AdlStepScreen(step: JSONObject, onBack: () -> Unit, onChange: (JSONObject) -> Unit) {
    val kind = step.optString("kind")
    fun set(k: String, v: Any) = onChange(step.adlWith(k, v))
    AdlPage(adlStepName(kind), onBack) {
        when (kind) {
            "move" -> AdlMoveOptions()
            "subs" -> AdlSubsOptions(step, ::set)
            "cast_ready" -> AdlCastOptions(step, ::set)
            "shrink" -> AdlShrinkOptions(step, ::set)
            "seed_end" -> AdlSeedOptions(step, ::set)
        }
    }
}

/** Screen 3: "Move" has no options yet; it says what it does. */
@Composable
private fun AdlMoveOptions() {
    AdlHint(stringResource(R.string.adl_move_no_options))
    AdlSection(Icons.Filled.Folder, stringResource(R.string.adl_move_where))
    Card(Modifier.fillMaxWidth()) {
        AdlRow(Icons.Filled.Folder, stringResource(R.string.adl_move_folder), stringResource(R.string.adl_move_folder_desc))
        AdlRow(Icons.Filled.Balance, stringResource(R.string.adl_move_disk), stringResource(R.string.adl_move_disk_desc))
        AdlRow(Icons.Filled.LiveTv, stringResource(R.string.adl_move_series), stringResource(R.string.adl_move_series_desc))
        AdlRow(Icons.Filled.HourglassTop, stringResource(R.string.adl_move_unsure), stringResource(R.string.adl_move_unsure_desc))
    }
    AdlSection(Icons.Filled.CloudUpload, stringResource(R.string.adl_move_seeding_sec))
    Card(Modifier.fillMaxWidth()) {
        AdlRow(Icons.Filled.CloudUpload, stringResource(R.string.adl_move_seeding), stringResource(R.string.adl_move_seeding_desc))
    }
}

/** Screen 4: subtitle languages: the downloader's and always these (up to 3). */
@Composable
private fun AdlSubsOptions(step: JSONObject, set: (String, Any) -> Unit) {
    val langs = adlStrings(step.optJSONArray("langs"))
    var add by remember { mutableStateOf(false) }
    AdlSection(Icons.Filled.Translate, stringResource(R.string.adl_subs_langs))
    Card(Modifier.fillMaxWidth()) {
        AdlSwitchRow(Icons.Filled.Person, stringResource(R.string.adl_subs_downloader), stringResource(R.string.adl_subs_downloader_desc),
            step.optBoolean("downloader", true)) { set("downloader", it) }
        AdlRow(Icons.Filled.Translate, stringResource(R.string.adl_subs_always), stringResource(R.string.adl_subs_always_desc))
        FlowRow(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            langs.forEach { l ->
                InputChip(true, { set("langs", JSONArray(langs - l)) }, { Text(l) },
                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.adl_lang_remove, l), Modifier.size(16.dp)) })
            }
            if (langs.size < 3) InputChip(false, { add = true }, { Text(stringResource(R.string.adl_subs_add)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
        }
    }
    AdlHint(stringResource(R.string.adl_subs_hint))
    if (add) {
        var code by remember { mutableStateOf("") }
        val ok = code.trim().lowercase().matches(Regex("[a-z]{2,3}")) && code.trim().lowercase() !in langs
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text(stringResource(R.string.adl_subs_add)) },
            text = {
                OutlinedTextField(code, { code = it.take(3) }, label = { Text(stringResource(R.string.adl_subs_code)) }, singleLine = true)
            },
            confirmButton = {
                TextButton({ add = false; set("langs", JSONArray(langs + code.trim().lowercase())) }, enabled = ok) {
                    Text(stringResource(R.string.adl_add))
                }
            },
            dismissButton = { TextButton({ add = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}

/** "Ready for the phone": while it seeds (keep seeding or in place) and the three switches. */
@Composable
private fun AdlCastOptions(step: JSONObject, set: (String, Any) -> Unit) {
    val mode = step.optString("mode", "keep_seeding")
    AdlSection(Icons.Filled.CloudUpload, stringResource(R.string.adl_cast_mode))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            AdlRadio(mode == "keep_seeding", stringResource(R.string.adl_cast_keep), stringResource(R.string.adl_cast_keep_desc)) { set("mode", "keep_seeding") }
            AdlRadio(mode == "in_place", stringResource(R.string.adl_cast_in_place), stringResource(R.string.adl_cast_in_place_desc)) { set("mode", "in_place") }
        }
    }
    AdlSection(Icons.Filled.Tune, stringResource(R.string.adl_cast_what))
    Card(Modifier.fillMaxWidth()) {
        AdlSwitchRow(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.adl_cast_aac), stringResource(R.string.adl_cast_aac_desc),
            step.optBoolean("add_aac", true)) { set("add_aac", it) }
        AdlSwitchRow(Icons.Filled.Subtitles, stringResource(R.string.adl_cast_srt), stringResource(R.string.adl_cast_srt_desc),
            step.optBoolean("subs_to_srt", true)) { set("subs_to_srt", it) }
        AdlSwitchRow(Icons.Filled.MovieFilter, stringResource(R.string.adl_cast_avi), stringResource(R.string.adl_cast_avi_desc),
            step.optBoolean("avi_to_mkv", true)) { set("avi_to_mkv", it) }
    }
}

/** Screen 5: "Shrink": when (size, bitrate), with what (encoder, quality, skip HEVC/AV1). */
@Composable
private fun AdlShrinkOptions(step: JSONObject, set: (String, Any) -> Unit) {
    AdlSection(Icons.Filled.Schedule, stringResource(R.string.adl_shrink_when))
    Card(Modifier.fillMaxWidth()) {
        AdlNumberRow(Icons.Filled.Straighten, stringResource(R.string.adl_shrink_gb), null, step.optDouble("min_gb", 8.0),
            { stringResource(R.string.adl_unit_gb, it) }) { set("min_gb", it) }
        AdlNumberRow(Icons.Filled.Speed, stringResource(R.string.adl_shrink_mbps), null, step.optDouble("min_mbps", 6.0),
            { stringResource(R.string.adl_unit_mbps, it) }) { set("min_mbps", it) }
    }
    AdlSection(Icons.Filled.Memory, stringResource(R.string.adl_shrink_with))
    Card(Modifier.fillMaxWidth()) {
        AdlChoiceRow(Icons.Filled.Memory, stringResource(R.string.adl_shrink_encoder), stringResource(R.string.adl_shrink_encoder_desc),
            step.optString("encoder", "auto"), listOf("auto" to R.string.adl_enc_auto, "gpu" to R.string.adl_enc_gpu, "cpu" to R.string.adl_enc_cpu)) {
            set("encoder", it)
        }
        AdlChoiceRow(Icons.Filled.HighQuality, stringResource(R.string.adl_shrink_quality), null, step.optString("quality", "balanced"),
            listOf("transparent" to R.string.adl_q_transparent, "balanced" to R.string.adl_q_balanced, "saving" to R.string.adl_q_saving)) {
            set("quality", it)
        }
        AdlSwitchRow(Icons.Filled.MovieFilter, stringResource(R.string.adl_shrink_skip_hevc), stringResource(R.string.adl_shrink_skip_hevc_desc),
            step.optBoolean("skip_hevc_av1", true)) { set("skip_hevc_av1", it) }
        AdlChoiceRow(Icons.Filled.Delete, stringResource(R.string.adl_shrink_original), stringResource(R.string.adl_shrink_original_desc),
            step.optString("original", "delete"), listOf("delete" to R.string.adl_orig_delete, "trash" to R.string.adl_orig_trash)) {
            set("original", it)
        }
    }
    AdlHint(stringResource(R.string.adl_shrink_hint))
}

/** Screen 2b: "End seeding": when (ratio or days, never before a minimum), then pause or remove from the client. */
@Composable
private fun AdlSeedOptions(step: JSONObject, set: (String, Any) -> Unit) {
    AdlHint(stringResource(R.string.adl_seed_boundary))
    AdlSection(Icons.Filled.Schedule, stringResource(R.string.adl_seed_when))
    Card(Modifier.fillMaxWidth()) {
        AdlNumberRow(Icons.Filled.Percent, stringResource(R.string.adl_seed_ratio), null, step.optDouble("ratio", 2.0),
            { it }, decimals = 1) { set("ratio", it) }
        AdlNumberRow(Icons.Filled.Event, stringResource(R.string.adl_seed_days), stringResource(R.string.adl_seed_days_desc),
            step.optDouble("days", 7.0), { _ -> adlDays(step.optDouble("days", 7.0)) }) { set("days", it) }
        AdlNumberRow(Icons.Filled.HourglassTop, stringResource(R.string.adl_seed_min), stringResource(R.string.adl_seed_min_desc),
            step.optDouble("min_days", 1.0), { _ -> adlDays(step.optDouble("min_days", 1.0)) }) { set("min_days", it) }
    }
    AdlSection(Icons.Filled.CloudOff, stringResource(R.string.adl_seed_then))
    val then = step.optString("then", "remove")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            AdlRadio(then == "pause", stringResource(R.string.adl_seed_pause), null) { set("then", "pause") }
            AdlRadio(then == "remove", stringResource(R.string.adl_seed_remove), null) { set("then", "remove") }
        }
    }
    AdlHint(stringResource(R.string.adl_seed_hint))
}

/** A number in a row; tapping asks for a new one (0-10000, a comma works too). */
@Composable
private fun AdlNumberRow(icon: ImageVector, title: String, sub: String?, value: Double, shown: @Composable (String) -> String,
                         decimals: Int = 0, onSet: (Double) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    AdlRow(icon, title, sub, onClick = { edit = true }) { AdlValue(shown(adlNum(value, decimals)), more = false) }
    if (edit) {
        var text by remember { mutableStateOf(adlNum(value, decimals)) }
        val v = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10000.0 }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(text, { text = it.take(8) }, singleLine = true, isError = v == null,
                    supportingText = { if (v == null) Text(stringResource(R.string.adl_number_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = { TextButton({ edit = false; if (v != null && v != value) onSet(v) }, enabled = v != null) { Text(stringResource(R.string.adl_ok)) } },
            dismissButton = { TextButton({ edit = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}

/** One of a few choices in a row, picked from a menu. */
@Composable
private fun AdlChoiceRow(icon: ImageVector, title: String, sub: String?, value: String, choices: List<Pair<String, Int>>, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AdlRow(icon, title, sub, onClick = { menu = true }) {
        Box {
            AdlValue(choices.firstOrNull { it.first == value }?.let { stringResource(it.second) } ?: value)
            DropdownMenu(menu, { menu = false }) {
                choices.forEach { (k, res) ->
                    DropdownMenuItem({ Text(stringResource(res)) }, { menu = false; if (k != value) onPick(k) },
                        leadingIcon = { if (k == value) Icon(Icons.Filled.Check, null) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads tab: the runs (screen 7)

/** Runs that are going on or finished in the last 24 h, newest first; refreshed while the app is on screen. */
@Composable
fun rememberAfterdlRuns(api: Api, active: Boolean): State<List<AdlRun>> {
    val runs = remember(api.srv.key) { mutableStateOf<List<AdlRun>>(emptyList()) }
    LaunchedEffect(api.srv.key, active) {
        while (active) {
            try {
                val since = System.currentTimeMillis() / 1000.0 - 86400
                runs.value = withContext(Dispatchers.IO) { api.afterdlRuns(since) }
            } catch (_: ApiException) {
                // the torrent list above already says when the server is gone
            }
            visibleDelay(8000)
        }
    }
    return runs
}

/** The "After download" section of the Downloads tab: one row per video, a season's episodes as one row. */
fun LazyListScope.afterdlSection(runs: List<AdlRun>, names: Map<String, String>, onOpen: (String) -> Unit) {
    if (runs.isEmpty()) return
    item(key = "afterdl/head") {
        AdlSection(Icons.Filled.DoneAll, stringResource(R.string.adl_title))
    }
    // the server lists runs oldest first; the newest group goes on top
    val groups = runs.groupBy { it.torrent.ifEmpty { it.id } }.values.sortedByDescending { g -> g.maxOf { it.created } }
    items(groups, key = { "afterdl/" + it.first().torrent.ifEmpty { it.first().id } }) { g ->
        if (g.size == 1) AdlRunCard(g[0], onOpen) else AdlSeasonCard(g, names[g[0].torrent], onOpen)
    }
}

@Composable
private fun adlSourceLabel(src: String): String = stringResource(when (src) {
    "app" -> R.string.adl_src_app_short
    "client" -> R.string.adl_src_client_short
    else -> R.string.adl_src_archive_short
})

private fun adlSourceIcon(src: String): ImageVector = when (src) {
    "app" -> Icons.Filled.Smartphone
    "client" -> Icons.Filled.Computer
    else -> Icons.Filled.AccountBalance
}

@Composable
private fun AdlRunPill(s: AdlStep) {
    val name = adlShortName(s.kind)
    when (s.state) {
        "done" -> if (s.auto) AdlTag(stringResource(R.string.adl_name_auto, name), Icons.Filled.SmartToy, AdlTone.AUTO)
                  else AdlTag(name, Icons.Filled.Check, AdlTone.OK)
        "running" -> AdlTag(name, tone = AdlTone.RUN, spin = true)
        "error" -> AdlTag(name, Icons.Filled.Error, AdlTone.ERR)
        "skipped" -> AdlTag(name, Icons.Filled.SkipNext, AdlTone.PLAIN, faded = true)
        else -> AdlTag(name)
    }
}

@Composable
private fun AdlRunCard(r: AdlRun, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { onOpen(r.id) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (r.kind == "series") Icons.Filled.LiveTv else adlSourceIcon(r.source), null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(listOfNotNull(adlSourceLabel(r.source), adlWhen(ctx, r.created), adlRunState(r.state)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.state == "done") {
                    val took = r.finished?.let { (it - r.created).toLong() }?.takeIf { it > 0 }
                    AdlTag(if (took != null) stringResource(R.string.adl_all_done_in, humanTime(took)) else stringResource(R.string.adl_all_done),
                        Icons.Filled.Check, AdlTone.OK)
                } else r.steps.forEach { AdlRunPill(it) }
            }
            r.steps.getOrNull(r.step)?.takeIf { r.state == "running" || r.state == "error" }?.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A title page's "After download": the newest run of this library file and its history on a tap. Nothing when the
 * server is older (no `afterdl_file`), the viewer is a guest, or the file had no run (the library from before).
 */
@Composable
fun AfterdlFileCard(api: Api?, root: Int, path: String, snack: SnackbarHostState) {
    if (api == null || !api.srv.hasAfterdlFile || api.srv.isGuest) return
    val runs by produceState<List<AdlRun>>(emptyList(), api.srv.key, root, path) {
        value = try { withContext(Dispatchers.IO) { api.afterdlRunsOf(root, path) } } catch (_: Exception) { emptyList() }
    }
    var open by rememberSaveable(root, path) { mutableStateOf<String?>(null) }
    val r = runs.firstOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AdlSection(Icons.Filled.DoneAll, stringResource(R.string.adl_title))
        AdlRunCard(r) { open = it }
    }
    open?.let { id ->
        Dialog({ open = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { AfterdlRunScreen(api, id, snack) { open = null } }
        }
    }
}

@Composable
private fun adlRunState(state: String): String? = when (state) {
    "error" -> stringResource(R.string.adl_state_error)
    "cancelled" -> stringResource(R.string.adl_state_cancelled)
    else -> null
}

/** A season (several videos of one torrent): counts, and the episodes when opened. */
@Composable
private fun AdlSeasonCard(g: List<AdlRun>, torrentName: String?, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val done = g.count { it.state == "done" }
    val running = g.count { r -> r.state == "running" && r.steps.getOrNull(r.step)?.state == "running" }
    val waiting = g.count { r -> r.state == "running" && r.steps.getOrNull(r.step)?.state != "running" }
    val failed = g.count { it.state == "error" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LiveTv, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(listOf(torrentName ?: g[0].name, pluralStringResource(R.plurals.adl_episodes, g.size, g.size)).joinToString(" · "),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            Text(listOf(adlSourceLabel(g[0].source), adlWhen(ctx, g.minOf { it.created }), stringResource(R.string.adl_each_own))
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (done > 0) AdlTag(done.toString(), Icons.Filled.Check, AdlTone.OK)
                if (running > 0) AdlTag(pluralStringResource(R.plurals.adl_n_running, running, running), tone = AdlTone.RUN, spin = true)
                if (waiting > 0) AdlTag(pluralStringResource(R.plurals.adl_n_waiting, waiting, waiting))
                if (failed > 0) AdlTag(pluralStringResource(R.plurals.adl_n_failed, failed, failed), Icons.Filled.Error, AdlTone.ERR)
            }
        }
        if (open) {
            HorizontalDivider()
            g.sortedBy { it.name }.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            r.steps.forEach { AdlRunPill(it) }
                        }
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- one video's history (screen 8)

@Composable
fun AfterdlRunScreen(api: Api, id: String, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var run by remember(id) { mutableStateOf<AdlRun?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    LaunchedEffect(id) {
        while (true) {
            try {
                run = withContext(Dispatchers.IO) { api.afterdlRun(id) }
                error = null
            } catch (e: ApiException) {
                error = e.message ?: ctx.getString(R.string.main_error)
            }
            visibleDelay(5000)
        }
    }
    fun act(action: String) {
        scope.launch {
            try {
                run = withContext(Dispatchers.IO) { api.afterdlAction(id, action) }
            } catch (e: ApiException) {
                snack.showSnackbar(e.message ?: ctx.getString(R.string.main_error))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) }
            Text(run?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
        val r = run
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant) else CircularProgressIndicator()
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(adlSourceIcon(r.source), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(adlSourceLabel(r.source), adlWhen(ctx, r.created), adlRunState(r.state)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.adl_file, r.name), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val canSkip = (r.state == "running" || r.state == "error") && r.step < r.steps.size
            if (canSkip || r.state == "error" || r.state == "running") item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.state == "error") FilledTonalButton({ act("retry") }) { Text(stringResource(R.string.adl_act_retry)) }
                    if (canSkip) OutlinedButton({ act("skip") }) {
                        Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.adl_act_skip))
                    }
                    if (r.state == "running") TextButton({ confirmCancel = true }) { Text(stringResource(R.string.adl_act_cancel)) }
                }
            }
            item { AdlSection(Icons.Filled.FormatListNumbered, stringResource(R.string.adl_history_steps)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        r.steps.forEachIndexed { i, s -> AdlTimelineStep(s, last = i == r.steps.lastIndex) }
                    }
                }
            }
            item { AdlSection(Icons.Filled.Schedule, stringResource(R.string.adl_history_events)) }
            if (r.events.isEmpty()) item { AdlHint(stringResource(R.string.adl_no_events)) }
            items(r.events.sortedByDescending { it.at }) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(adlWhen(ctx, e.at), Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.text, style = MaterialTheme.typography.bodyMedium)
                        val stepKind = e.step?.let { r.steps.getOrNull(it)?.kind }
                        if (stepKind != null || e.auto) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (stepKind != null) AdlTag(adlStepName(stepKind), adlStepIcon(stepKind))
                            if (e.auto) AdlTag(stringResource(R.string.adl_auto), Icons.Filled.SmartToy, AdlTone.AUTO)
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.adl_cancel_title)) },
        text = { Text(stringResource(R.string.adl_cancel_text)) },
        confirmButton = { TextButton({ confirmCancel = false; act("cancel") }) { Text(stringResource(R.string.adl_act_cancel)) } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun AdlTimelineStep(s: AdlStep, last: Boolean) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (s.state) {
        "done" -> if (s.auto) Icons.Filled.SmartToy to cs.tertiary else Icons.Filled.CheckCircle to adlToneColors(AdlTone.OK).second
        "error" -> Icons.Filled.Error to cs.error
        "skipped" -> Icons.Filled.SkipNext to cs.onSurfaceVariant
        "running" -> null to cs.primary
        else -> Icons.Filled.RadioButtonUnchecked to cs.outline
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon == null) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.size(22.dp), tint = tint)
            if (!last) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 2.dp).background(cs.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(adlStepName(s.kind), style = MaterialTheme.typography.bodyLarge,
                    color = if (s.state == "skipped") cs.onSurfaceVariant else Color.Unspecified)
                when {
                    s.state == "running" -> AdlTag(stringResource(R.string.adl_running), tone = AdlTone.RUN)
                    s.state == "done" && s.auto -> AdlTag(stringResource(R.string.adl_auto), tone = AdlTone.AUTO)
                    s.state == "error" -> AdlTag(stringResource(R.string.adl_state_error), tone = AdlTone.ERR)
                    s.state == "skipped" -> AdlTag(stringResource(R.string.adl_skipped))
                }
            }
            val note = s.note ?: if (s.state == "pending") stringResource(R.string.adl_waiting) else null
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

package net.treetank.magneticplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val PROVIDER_NAMES = mapOf(
    "opensubtitlescom" to "OpenSubtitles", "opensubtitles" to "OpenSubtitles", "podnapisi" to "Podnapisi",
    "bsplayer" to "BSPlayer", "gestdown" to "Gestdown (Addic7ed)", "napiprojekt" to "NapiProjekt", "tvsubtitles" to "TVsubtitles",
)
private val GREEN = Color(0xFF3FA96B)

/** Languages offered for downloading: the user's own (settings) first, then common ones. */
fun subLanguageChoices(s: Settings): List<String> =
    (s.subLanguages.split(',') + listOf("pl", "en", "de", "fr", "es", "it", "pt", "nl", "cs", "sk", "uk", "ru",
        "sv", "no", "da", "fi", "hu", "ro", "hr", "tr", "el", "he", "ar", "ja", "ko", "zh"))
        .map { it.trim().lowercase() }.filter { it.length in 2..3 }.distinct()

fun langName(code: String, ui: java.util.Locale = java.util.Locale.getDefault()): String =
    java.util.Locale(code).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }.ifEmpty { code }

/** A subtitle track inside the video (the phone's player only). */
data class EmbeddedSub(val index: Int, val lang: String?, val selected: Boolean)

sealed class SubPick {
    data object Off : SubPick()
    data class Track(val index: Int) : SubPick()
    /** a file next to the video (its name, after it became the default of its language) */
    data class File(val name: String, val lang: String) : SubPick()
}

/**
 * Subtitles of one video, the same in the phone's player, on the title card and in the Remote:
 * 1. languages (tap = the language's default version on; › = its versions), "Download: <language>" for the user's
 *    languages missing (the best fitting result, at most 3 tried) and "another language…";
 * 2. one language: the versions on the computer's disk (tap = it becomes the default, with agent 1.12+: what other
 *    players see too), built-in tracks, and a search in that language whose results the user already has are marked and
 *    switched to without downloading.
 * [embedded] (null outside the player) adds "Off" and the video's own tracks. [selectedFile] = the file playing now.
 * [onPick] gets what to turn on and a message for the user (fit); the player closes the sheet then.
 */
@Composable
fun SubtitlesSheet(
    api: Api, ref: MediaRef, onDismiss: () -> Unit,
    embedded: List<EmbeddedSub>? = null, selectedFile: String? = null, offSelected: Boolean = false,
    closeOnPick: Boolean = embedded != null, onPick: (SubPick, String?) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val ui = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<SubFile>?>(null) }
    var page by remember { mutableStateOf<String?>(null) }  // null = languages, "*" = pick another language, else one language
    var fetching by remember { mutableStateOf<String?>(null) }  // auto download in progress: status line
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    val versions = api.srv.hasSubsVersions

    suspend fun reload() {
        files = withContext(Dispatchers.IO) { runCatching { api.subsList(ref) }.getOrDefault(files ?: emptyList()) }
    }
    LaunchedEffect(ref) { reload() }

    fun fitMessage(fit: String?, name: String) = ctx.getString(when (fit) {
        "bad" -> R.string.media_psubs_on_bad; "good" -> R.string.media_psubs_on_good; else -> R.string.media_psubs_on
    }, name)

    fun picked(p: SubPick, msg: String?) {
        onPick(p, msg)
        if (closeOnPick) onDismiss() else message = msg
    }

    /** A version on disk: it becomes the default of its language (agent 1.12+), then on. */
    fun choose(f: SubFile) = scope.launch {
        error = null
        try {
            val name = if (!f.default && versions) withContext(Dispatchers.IO) { api.subsDefault(ref, f.name) } else f.name
            reload()
            picked(SubPick.File(name, f.langKey), ctx.getString(R.string.media_psubs_on, name))
        } catch (e: ApiException) {
            error = e.message
        }
    }

    /** "Download: <language>": search that language, try the best results until one fits this copy (at most 3). */
    fun autoFetch(lang: String) {
        fetchJob = scope.launch {
            error = null; message = null
            val name = langName(lang, ui)
            fetching = ctx.getString(R.string.media_psubs_searching, name)
            var job: String? = null
            try {
                val id = withContext(Dispatchers.IO) { api.subsSearchStart(ref, langs = lang) }
                job = id
                var found: SubsFound? = null
                for (n in 0 until 150) {  // BSPlayer can think for 45 s
                    val pr = withContext(Dispatchers.IO) { api.subsJob(id) }
                    if (pr.error != null) throw ApiException(pr.error)
                    if (pr.done) { found = pr.found; break }
                    delay(800)
                }
                job = null
                val have = files.orEmpty().mapNotNull { it.source?.key }.toSet()
                val results = found?.results.orEmpty().filter { it.key !in have }
                    .sortedWith(compareByDescending<SubResult> { it.hashMatch }.thenByDescending { it.score })
                var got: SubsDownloaded? = null
                for ((k, r) in results.take(3).withIndex()) {
                    fetching = ctx.getString(R.string.media_psubs_trying, k + 1, minOf(3, results.size), r.release.ifEmpty { r.provider })
                    val d = withContext(Dispatchers.IO) { runCatching { api.subsDownload(ref, r, langs = lang) }.getOrNull() } ?: continue
                    got = d
                    if (d.fit != "bad") break
                }
                fetching = null
                val d = got ?: run { error = ctx.getString(R.string.media_psubs_none, name); return@launch }
                reload()
                picked(SubPick.File(d.name, lang), fitMessage(d.fit, d.name))
            } catch (e: ApiException) {
                fetching = null
                error = e.message
            } finally {
                job?.let { j -> Thread { runCatching { api.subsStop(j) } }.start() }
            }
        }
    }

    Dialog(onDismissRequest = { fetchJob?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).padding(vertical = 16.dp), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                // header: title or "← language"
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    if (page != null && fetching == null) IconButton({ page = null; error = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_psubs_back))
                    }
                    Text(when (page) {
                        null -> stringResource(R.string.media_subs_title)
                        "*" -> stringResource(R.string.media_psubs_pick_lang)
                        "" -> stringResource(R.string.media_psubs_unknown_lang)
                        else -> langName(page!!, ui)
                    }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(ref.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GREEN, modifier = Modifier.padding(horizontal = 16.dp)) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    val list = files
                    when {
                        fetching != null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(fetching!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        page == null -> Languages(api, list, embedded, selectedFile, offSelected, ui,
                            onOff = { picked(SubPick.Off, null) },
                            onLang = { lang ->
                                val def = list.filter { it.langKey == lang }.let { l -> l.firstOrNull { it.default } ?: l.firstOrNull() }
                                val emb = embedded?.firstOrNull { (it.lang ?: "").startsWith(lang) && lang.isNotEmpty() || (it.lang == null && lang.isEmpty()) }
                                when {
                                    embedded == null -> page = lang  // outside the player: nothing to turn on, show the versions
                                    def != null -> choose(def)
                                    emb != null -> picked(SubPick.Track(emb.index), null)
                                    else -> page = lang
                                }
                            },
                            onVersions = { page = it }, onDownload = ::autoFetch, onOther = { page = "*" })
                        page == "*" -> subLanguageChoices(api.s).forEach { c ->
                            SheetRow(langName(c, ui), onClick = { page = null; autoFetch(c) }, leading = {
                                Icon(Icons.Filled.Language, null, Modifier.size(20.dp))
                            })
                        }
                        else -> LanguagePage(api, ref, page!!, list, embedded, selectedFile, ui,
                            onChoose = ::choose,
                            onTrack = { picked(SubPick.Track(it), null) },
                            onDownloaded = { d, lang -> scope.launch { reload(); picked(SubPick.File(d.name, lang), fitMessage(d.fit, d.name)) } },
                            onError = { error = it })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton({ fetchJob?.cancel(); onDismiss() }) {
                        Text(stringResource(if (fetching != null) R.string.media_next_cancel else R.string.media_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String? = null, onClick: () -> Unit, leading: @Composable () -> Unit = {},
                     trailing: @Composable () -> Unit = {}, subtitleColor: Color? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun fitText(fit: String?) = when (fit) {
    "good" -> stringResource(R.string.media_psubs_fit_good)
    "bad" -> stringResource(R.string.media_psubs_fit_bad)
    else -> null
}

@Composable
private fun Languages(
    api: Api, files: List<SubFile>, embedded: List<EmbeddedSub>?, selectedFile: String?, offSelected: Boolean, ui: java.util.Locale,
    onOff: () -> Unit, onLang: (String) -> Unit, onVersions: (String) -> Unit, onDownload: (String) -> Unit, onOther: () -> Unit,
) {
    if (embedded != null) SheetRow(stringResource(R.string.media_psubs_off), onClick = onOff,
        leading = { RadioButton(offSelected, onOff) })
    val mine = api.s.subLanguages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val present = (files.map { it.langKey } + embedded.orEmpty().map { (it.lang ?: "").takeWhile { c -> c.isLetter() }.lowercase() })
        .distinct().sortedBy { l -> mine.indexOf(l).let { if (it < 0) 99 else it } }
    for (lang in present) {
        val fs = files.filter { it.langKey == lang }
        val def = fs.firstOrNull { it.default } ?: fs.firstOrNull()
        val emb = embedded.orEmpty().filter { (it.lang ?: "").takeWhile { c -> c.isLetter() }.lowercase() == lang }
        val on = fs.any { it.name == selectedFile } || emb.any { it.selected }
        val count = fs.size + emb.size
        val sub = listOfNotNull(
            fitText(def?.fit),
            if (def == null && emb.isNotEmpty()) stringResource(R.string.media_psubs_embedded).trim('(', ')') else null,
            if (count > 1) pluralStringResource(R.plurals.media_psubs_versions, count, count) else null,
        ).joinToString(" · ").ifEmpty { null }
        SheetRow(if (lang.isEmpty()) stringResource(R.string.media_psubs_unknown_lang) else langName(lang, ui), sub,
            onClick = { onLang(lang) },
            leading = { if (embedded != null) RadioButton(on, { onLang(lang) }) else Icon(Icons.Filled.Language, null, Modifier.size(20.dp)) },
            trailing = {
                IconButton({ onVersions(lang) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.media_psubs_versions_cd))
                }
            }, subtitleColor = if (def?.fit == "bad") MaterialTheme.colorScheme.error else null)
    }
    for (lang in mine.filter { it !in present }) {
        SheetRow(stringResource(R.string.media_psubs_download, langName(lang, ui)), onClick = { onDownload(lang) },
            leading = { Icon(Icons.Filled.Download, null, Modifier.size(20.dp)) },
            trailing = {
                IconButton({ onVersions(lang) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.media_psubs_versions_cd))
                }
            })
    }
    SheetRow(stringResource(R.string.media_psubs_download_other), onClick = onOther,
        leading = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp)) })
}

/** A file's label: the release it came from, else its name without the video's name and the language. */
private fun versionLabel(f: SubFile, videoStem: String?): String {
    f.source?.release?.takeIf { it.isNotBlank() }?.let { return it }
    var n = f.name.substringAfterLast('/')
    n = n.substringBeforeLast('.')  // .srt
    if (videoStem != null && n.startsWith(videoStem)) n = n.removePrefix(videoStem).trim('.', ' ').ifEmpty { f.name.substringAfterLast('/') }
    if (f.langKey.isNotEmpty() && n.endsWith("." + f.lang)) n = n.removeSuffix("." + f.lang)
    return n.ifEmpty { f.name.substringAfterLast('/') }
}

@Composable
private fun LanguagePage(
    api: Api, ref: MediaRef, lang: String, files: List<SubFile>, embedded: List<EmbeddedSub>?, selectedFile: String?,
    ui: java.util.Locale, onChoose: (SubFile) -> Unit, onTrack: (Int) -> Unit,
    onDownloaded: (SubsDownloaded, String) -> Unit, onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fs = files.filter { it.langKey == lang }.sortedByDescending { it.default }
    val stem = files.firstOrNull { it.default && '/' !in it.name }?.name?.let { n ->
        n.substringBeforeLast('.').let { s -> if (langTail(s).isNotEmpty()) s.substringBeforeLast('.') else s }
    }
    val haveByKey = files.mapNotNull { f -> f.source?.let { it.key to f } }.toMap()
    // the disk
    if (fs.isNotEmpty() || embedded.orEmpty().isNotEmpty()) SectionTitle(stringResource(R.string.media_psubs_on_disk))
    for (f in fs) {
        val on = if (embedded != null) f.name == selectedFile else f.default
        val sub = listOfNotNull(
            if (f.default) stringResource(R.string.media_psubs_default) else null,
            f.source?.provider?.let { PROVIDER_NAMES[it] ?: it },
            fitText(f.fit),
        ).joinToString(" · ").ifEmpty { null }
        SheetRow(versionLabel(f, stem), sub, onClick = { onChoose(f) },
            leading = { RadioButton(on, { onChoose(f) }) },
            subtitleColor = if (f.fit == "bad") MaterialTheme.colorScheme.error else if (f.fit == "good") GREEN else null)
    }
    embedded.orEmpty().filter { (it.lang ?: "").takeWhile { c -> c.isLetter() }.lowercase() == lang }.forEach { e ->
        SheetRow(stringResource(R.string.media_psubs_embedded_track, e.index + 1), onClick = { onTrack(e.index) },
            leading = { RadioButton(e.selected, { onTrack(e.index) }) })
    }
    if (lang.isEmpty()) return
    // the internet, in this language only
    SectionTitle(stringResource(R.string.media_psubs_online))
    var progress by remember { mutableStateOf<SubsProgress?>(null) }
    var found by remember { mutableStateOf<SubsFound?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var override by remember { mutableStateOf<Pair<String?, Int?>>(null to null) }
    var nonce by remember { mutableIntStateOf(0) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lang, nonce) {
        found = null; progress = null; stopping = false; searchError = null
        try {
            val (t, y) = override
            val job = withContext(Dispatchers.IO) { api.subsSearchStart(ref, t, y, langs = lang) }
            jobId = job
            while (true) {
                val p = withContext(Dispatchers.IO) { api.subsJob(job) }
                progress = p
                if (p.done) {
                    if (p.error != null) searchError = p.error
                    found = p.found
                    p.found?.let { f -> if (title.isEmpty()) title = f.title; if (year.isEmpty()) year = f.year?.toString() ?: "" }
                    break
                }
                delay(700)
            }
        } catch (e: ApiException) {
            searchError = e.message
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        val f = found
        when {
            searchError != null -> Text(searchError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            f == null -> {
                SearchProgress(progress)
                val p = progress
                if (p != null && p.phase == "search" && p.providers.any { it.state == "running" || it.state == "pending" }) {
                    TextButton({
                        stopping = true
                        jobId?.let { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { api.subsStop(id) } } } }
                    }, enabled = !stopping) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (stopping) R.string.media_subs_stopping else R.string.media_subs_enough))
                    }
                }
            }
            else -> {
                if (f.hidden > 0) Text(pluralStringResource(R.plurals.media_subs_hidden, f.hidden, f.hidden),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (f.results.isEmpty()) Text(stringResource(R.string.media_subs_nothing, f.query, langName(lang, ui)),
                    style = MaterialTheme.typography.bodyMedium)
                if (busy != null && api.srv.hasSubsFit) Text(stringResource(R.string.media_subs_fitting),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.results.sortedWith(compareByDescending<SubResult> { it.hashMatch }.thenByDescending { it.score }).forEach { r ->
                    val mine = haveByKey[r.key]
                    SubRow(r, busy, have = mine != null) {
                        if (mine != null) { onChoose(mine); return@SubRow }  // already on disk: switch, no download
                        busy = r.key
                        onError(null)
                        scope.launch {
                            try {
                                val d = withContext(Dispatchers.IO) { api.subsDownload(ref, r, override.first, override.second, langs = lang) }
                                onDownloaded(d, lang)
                            } catch (e: ApiException) {
                                onError(e.message)
                            } finally {
                                busy = null
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (found != null || searchError != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.media_subs_field_title)) }, singleLine = true)
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.width(76.dp),
                    label = { Text(stringResource(R.string.media_subs_field_year)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            TextButton({ override = title.ifBlank { null } to year.toIntOrNull(); nonce++ }) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.media_subs_search_title))
            }
        }
    }
}

/** "pl" of "Film.pl" when the last part looks like a language code, else "". */
private fun langTail(s: String): String =
    s.substringAfterLast('.', "").takeIf { Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?").matches(it) } ?: ""

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
internal fun SearchProgress(p: SubsProgress?) {
    val phase = when {
        p == null -> stringResource(R.string.media_subs_connecting)
        p.phase == "file" -> stringResource(R.string.media_subs_hashing)
        p.phase == "match" -> stringResource(R.string.media_subs_matching, p.matched, p.total)
        p.round == 2 -> stringResource(R.string.media_subs_round2, p.query.toString())
        else -> stringResource(R.string.media_subs_searching, p.query ?: "…")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(phase, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        p?.let { Text("${it.elapsed.toInt()} s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (p?.phase == "match" && p.total > 0) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { p.matched.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    p?.providers?.forEach { pr ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                when (pr.state) {
                    "running" -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    "done" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = GREEN)
                    "error" -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    "skip" -> Icon(Icons.Filled.Remove, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(PROVIDER_NAMES[pr.name] ?: pr.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            val info = when (pr.state) {
                "running" -> stringResource(R.string.media_subs_prov_running)
                "done" -> pluralStringResource(R.plurals.media_subs_prov_results, pr.count ?: 0, pr.count ?: 0) +
                    (pr.secs?.let { " · ${"%.0f".format(it)} s" } ?: "")
                "pending" -> stringResource(R.string.media_subs_prov_pending)
                else -> pr.error ?: pr.state
            }
            Text(info, style = MaterialTheme.typography.labelSmall,
                color = if (pr.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubRow(r: SubResult, busy: String?, have: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = busy == null, onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.hashMatch) {
                Icon(Icons.Filled.CheckCircle, stringResource(R.string.media_subs_matches_file), Modifier.size(16.dp), tint = GREEN)
                Text(" " + stringResource(R.string.media_subs_matches_file), style = MaterialTheme.typography.labelSmall, color = GREEN)
                Spacer(Modifier.width(8.dp))
            }
            if (have) Text(stringResource(R.string.media_psubs_have), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (busy == r.key) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(PROVIDER_NAMES[r.provider] ?: r.provider, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.release, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (r.hi) Text(stringResource(R.string.media_subs_hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

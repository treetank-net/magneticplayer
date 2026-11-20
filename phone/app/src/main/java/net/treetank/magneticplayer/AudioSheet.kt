package net.treetank.magneticplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * One audio track of the video in the player. `kind` is read from the track's title and flags: "lector" (Polish
 * voice-over), "dubbing", "description" (audio description), "commentary", or "" (nothing said: the original when
 * other tracks are lector or dubbing).
 */
data class AudioOpt(
    val index: Int, val lang: String?, val label: String?, val channels: Int, val codec: String?, val selected: Boolean,
    val kind: String,
)

/** The kind of an audio track from its title and role flags (see [AudioOpt]). */
fun audioKind(label: String?, describes: Boolean, commentary: Boolean): String {
    val l = norm(label.orEmpty())
    return when {
        commentary || Regex("comment|komentarz").containsMatchIn(l) -> "commentary"
        describes || Regex("audiodeskrypc|audio descr|\\bad\\b|descriptive").containsMatchIn(l) -> "description"
        Regex("lekt|lector|voice.?over|\\bvo\\b").containsMatchIn(l) -> "lector"
        Regex("dub").containsMatchIn(l) -> "dubbing"
        Regex("orig").containsMatchIn(l) -> "original"
        else -> ""
    }
}

/** What was picked, remembered as a kind and a language rather than a track number: "original|", "lector|pl", "|en". */
data class AudioPref(val kind: String, val lang: String) {
    fun encode() = "$kind|$lang"
    companion object {
        fun decode(s: String?): AudioPref? = s?.split('|')?.takeIf { it.size == 2 }?.let { AudioPref(it[0], it[1]) }
        fun of(o: AudioOpt, all: List<AudioOpt>): AudioPref {
            // a track with nothing said, among lector/dubbing ones, is the original
            val kind = o.kind.ifEmpty { if (all.any { it.kind == "lector" || it.kind == "dubbing" }) "original" else "" }
            return AudioPref(kind, if (kind == "original") "" else subLangKey(o.lang))
        }
    }
}

/**
 * The track that matches a remembered choice, or null (leave the file's default): the same kind and language first,
 * then the same kind ("original" = the track that is neither lector nor dubbing), then the same language without
 * description or commentary.
 */
fun pickAudio(opts: List<AudioOpt>, pref: AudioPref?): Int? {
    if (pref == null || opts.size < 2) return null
    val plain = opts.filter { it.kind != "description" && it.kind != "commentary" }
    fun lang(o: AudioOpt) = subLangKey(o.lang)
    val hit = when (pref.kind) {
        "original" -> plain.firstOrNull { it.kind == "original" }
            ?: plain.firstOrNull { it.kind == "" }.takeIf { opts.any { o -> o.kind == "lector" || o.kind == "dubbing" } }
        "" -> plain.firstOrNull { lang(it) == pref.lang }
        else -> opts.firstOrNull { it.kind == pref.kind && lang(it) == pref.lang } ?: opts.firstOrNull { it.kind == pref.kind }
            ?: plain.firstOrNull { lang(it) == pref.lang && it.kind == "" }
    }
    return hit?.index
}

/** "5.1", "stereo", "mono" from the channel count. */
fun channelsLabel(n: Int): String? = when {
    n <= 0 -> null
    n == 1 -> "mono"
    n == 2 -> "stereo"
    else -> "${n - 1}.1"
}

/** A short codec name from the MIME type (audio/ac3 → AC-3). */
fun codecLabel(mime: String?): String? = when (mime?.removePrefix("audio/")) {
    null -> null
    "ac3" -> "AC-3"
    "eac3", "eac3-joc" -> "E-AC-3"
    "mp4a-latm" -> "AAC"
    "vnd.dts", "vnd.dts.hd" -> "DTS"
    "true-hd" -> "TrueHD"
    "mpeg" -> "MP3"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "flac" -> "FLAC"
    else -> mime.removePrefix("audio/").uppercase()
}

/**
 * A track's own title without what the detail line says better: releases write "English: 5.1ch (AC3 640Kbps)" into
 * the title, often wrong (the file had AAC), and the language is already the group's header. Codecs, channels and
 * bitrates are dropped, and so is a title that is only the language. Null when nothing is left.
 */
fun audioTitle(label: String?, lang: String, ui: java.util.Locale): String? {
    if (label.isNullOrBlank()) return null
    val tech = Regex("(?i)\\b(e-?ac-?3|ac-?3|aac(-lc)?|dts(-hd)?( ma)?|truehd|atmos|flac|mp3|mp2|opus|vorbis|pcm|lpcm|ddp?\\+?(\\d\\.\\d)?|" +
        "\\d\\.\\d( ?ch)?|\\d ?ch|stereo|mono|\\d+ ?kbps|\\d+ ?kb/s|\\d+ ?bit|\\d+(\\.\\d)? ?khz)\\b")
    var t = tech.replace(label, " ")
    repeat(2) { t = t.replace(Regex("\\(\\s*[,;:/]*\\s*\\)|\\[\\s*[,;:/]*\\s*]"), " ") }
    t = t.replace(Regex("\\s+"), " ").trim().trim(':', '-', ',', ';', '/', '·', '|', ' ')
    if (t.isEmpty()) return null
    val names = listOfNotNull(lang.takeIf { it.isNotEmpty() }?.let { langName(it, ui) },
        lang.takeIf { it.isNotEmpty() }?.let { langName(it, java.util.Locale.ENGLISH) }, lang)
    return t.takeUnless { names.any { n -> n.equals(t, true) } }
}

/** The audio sheet over the player: the tracks grouped by language, each with its title, channels and codec. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSheet(opts: List<AudioOpt>, onDismiss: () -> Unit, onPick: (AudioOpt) -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.audio_title), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium)
            opts.groupBy { subLangKey(it.lang) }.forEach { (lang, list) ->
                Text(if (lang.isEmpty()) stringResource(R.string.audio_lang_unknown) else langName(lang, ui),
                    Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                list.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(o.selected, { onPick(o) })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            val kind = when (o.kind) {
                                "lector" -> stringResource(R.string.audio_kind_lector)
                                "dubbing" -> stringResource(R.string.audio_kind_dubbing)
                                "description" -> stringResource(R.string.audio_kind_description)
                                "commentary" -> stringResource(R.string.audio_kind_commentary)
                                "original" -> stringResource(R.string.audio_kind_original)
                                else -> null
                            }
                            // the title without codec/channel claims: the line below (from the file itself) says those
                            val title = audioTitle(o.label, lang, ui)
                            Text(listOfNotNull(kind, title?.takeIf { !it.equals(kind, true) }).joinToString(" · ")
                                .ifEmpty { stringResource(R.string.audio_track_n, o.index + 1) })
                            listOfNotNull(channelsLabel(o.channels), codecLabel(o.codec)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.audio_remember_note), Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

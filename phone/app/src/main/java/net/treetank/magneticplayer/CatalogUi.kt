package net.treetank.magneticplayer

import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

// Building blocks of the catalog screens: synthetic covers, cover cards, person avatars, row headers, labels.
// Covers are drawn, never fetched: a frame from the video would spin up the disks for every card.

/** Gradient colours (light, dark) by genre; the first genre of a title that has one wins, in this order. */
private val PALETTE = linkedMapOf(
    "Animation" to (Color(0xFF1B8BD0) to Color(0xFF0B3D5C)),
    "Horror" to (Color(0xFF8C1D18) to Color(0xFF3B0D0D)),
    "Sci-Fi" to (Color(0xFF5B3FD1) to Color(0xFF1A1446)),
    "Film-Noir" to (Color(0xFF55565C) to Color(0xFF1B1B1F)),
    "Western" to (Color(0xFFB8642E) to Color(0xFF4A220B)),
    "Documentary" to (Color(0xFF8A6D3B) to Color(0xFF3A2C12)),
    "Comedy" to (Color(0xFFC99A00) to Color(0xFF5C4300)),
    "Romance" to (Color(0xFFB03A76) to Color(0xFF4A1030)),
    "Adventure" to (Color(0xFF2E8B57) to Color(0xFF0F3D24)),
    "Crime" to (Color(0xFF55565C) to Color(0xFF1B1B1F)),
    "Drama" to (Color(0xFF6B7A99) to Color(0xFF2A2F3A)),
)
private val NEUTRAL = Color(0xFF4A4F5A) to Color(0xFF1E2128)

fun genreColors(genres: List<String>): Pair<Color, Color> =
    PALETTE.entries.firstOrNull { it.key in genres }?.value ?: genres.firstNotNullOfOrNull { PALETTE[it] } ?: NEUTRAL

private fun grey(c: Color): Color {
    val l = (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue) * 0.7f
    return Color(l, l, l)
}

/** The server whose covers the catalog shows (set by the catalog area); null = drawn covers only. */
val LocalCoverApi = androidx.compose.runtime.staticCompositionLocalOf<Api?> { null }
/** "All": the covers of another server's items come from that server (null = no covers there). */
val LocalCoverApiFor = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Api?> { { null } }

/** Where a catalog item's picture comes from: its folder (poster.jpg…, or a frame of its main video) or the file. */
fun CatItem.art(): Pair<Int, String> = root to (dir ?: path)

/**
 * A cover: the picture that came with the film (poster.jpg…) or, when there is none, a frame the agent took from the
 * video with the title over a dark fade; while it loads and when there is nothing, a drawn one: a gradient by genre with a
 * soft circle and the title in a heavy font at the bottom.
 * [art]: root + path for the agent's cover (needs [LocalCoverApi]); [wide]: the bigger frame, for full-width pictures.
 * [missing] = the title is not on the disks (greyscale). [badge] in the top left corner (e.g. "PL audio").
 */
@Composable
fun SyntheticCover(title: String, year: Int?, genres: List<String>, modifier: Modifier, missing: Boolean = false,
                   badge: String? = null, titleSize: TextUnit = 14.sp, corner: Dp = 10.dp, padding: Dp = 8.dp,
                   art: Pair<Int, String>? = null, wide: Boolean = false, onPhone: Boolean = false, artSrv: String = "") {
    val (a0, b0) = genreColors(genres)
    val a = if (missing) grey(a0) else a0
    val b = if (missing) grey(b0) else b0
    val base = LocalCoverApi.current
    val forSrv = LocalCoverApiFor.current
    val api = (if (artSrv.isEmpty() || artSrv == base?.srv?.key) base else forSrv(artSrv))?.takeIf { art != null && it.srv.libraryCovers }
    val k = if (api != null && art != null) Covers.key(api, art.first, art.second, wide) else null
    var bmp by remember(k) { mutableStateOf(k?.let { Covers.cached(it) }) }
    // a cover kept on the phone (Settings → covers): shown at once, also without the server; a tiny one blurred
    var kept by remember(k) { mutableStateOf<Triple<android.graphics.Bitmap, Boolean, Boolean>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (k != null && bmp == null) LaunchedEffect(k) {
        kept = withContext(Dispatchers.IO) { runCatching { CoverStore.read(ctx, api!!.srv.key, art!!.first, art.second) }.getOrNull() }
        if (Covers.isMissing(k)) return@LaunchedEffect
        // without the server (an offline start) the kept cover stays blurred until the server answers, then the real one
        bmp = Covers.loadRetrying(api!!, art!!.first, art.second, if (wide) 720 else 300, wide)
    }
    val pic = bmp ?: kept?.first
    val blurred = bmp == null && kept?.third == true
    val frame = pic != null && k != null && (if (bmp != null) Covers.isFrame(k) else kept?.second == true)
    Box(modifier.clip(RoundedCornerShape(corner))) {
        if (pic == null) Canvas(Modifier.fillMaxSize()) {
            // about 160°: mostly top to bottom, light → dark → almost black
            drawRect(Brush.linearGradient(listOf(a, b, Color(0xFF050608)), start = Offset(size.width * 0.3f, 0f),
                end = Offset(size.width * 0.7f, size.height * 1.05f)))
            val r = size.minDimension * 0.24f
            drawCircle(Color.White.copy(alpha = 0.14f), r, Offset(size.width * 0.8f, size.height * 0.18f))
            drawCircle(Color.White.copy(alpha = 0.06f), r * 1.6f, Offset(size.width * 0.8f, size.height * 0.18f))
        } else {
            Image(pic.asImageBitmap(), null, Modifier.fillMaxSize().let { if (blurred) it.blur(6.dp) else it }, contentScale = ContentScale.Crop,
                colorFilter = if (missing) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) else null)
            // a frame has no title of its own: a tint of the genre's colour and a dark fade at the bottom carry it
            if (frame) Canvas(Modifier.fillMaxSize()) {
                drawRect(a.copy(alpha = 0.18f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.88f)),
                    startY = size.height * 0.35f, endY = size.height))
            }
        }
        if (badge != null) Text(badge, Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        // downloaded to this phone (a film, or at least one episode of a series): plays without the server
        if (onPhone) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.DownloadDone,
            stringResource(R.string.cat_on_phone), Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)).padding(3.dp), tint = Color.White)
        if (pic == null || frame) Column(Modifier.align(Alignment.BottomStart).padding(padding)) {
            Text(title, color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Black, lineHeight = titleSize * 1.1f,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (year != null) Text(year.toString(), color = Color.White.copy(alpha = 0.75f), fontSize = (titleSize.value * 0.7f).sp)
        }
    }
}

/**
 * Whether this phone holds a whole copy of [item] (a film) or of one of its episodes (a series), for the cover mark.
 * The downloaded keys are read at most every few seconds: a row draws many covers.
 */
object OnPhoneMarks {
    private var at = 0L
    private var keys: Set<String> = emptySet()
    fun keys(ctx: android.content.Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - at > 5000) {
            // downloaded, or a watching buffer that holds the whole video (as in the episode list's marks)
            keys = runCatching {
                val cache = MediaCache.get(ctx)
                OfflineStore.all(ctx).filter { it.complete || (it.size > 0 && cache.getCachedBytes(it.key, 0, it.size) >= it.size) }
                    .map { it.key }.toSet()
            }.getOrDefault(emptySet())
            at = now
        }
        return keys
    }
    fun has(ctx: android.content.Context, srv: String, item: CatItem): Boolean {
        val k = keys(ctx)
        if (k.isEmpty()) return false
        val p = Servers.prefixOf(srv) + "lib${item.root}/"
        return if (item.dir != null) k.any { it.startsWith(p + item.dir + "/") } else (p + item.path) in k
    }
}

@Composable
fun onPhone(item: CatItem): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val srv = item.srv.ifEmpty { LocalCoverApi.current?.srv?.key ?: return false }
    return remember(item.id, srv) { OnPhoneMarks.has(ctx, srv, item) }
}

/** "PL audio" / "PL subs" on a cover, or null. */
@Composable
fun plBadge(audio: List<String>, subs: List<String>): String? = when {
    "pl" in audio -> stringResource(R.string.cat_badge_pl_audio)
    "pl" in subs -> stringResource(R.string.cat_badge_pl_subs)
    else -> null
}

/** A cover card in a horizontal row: 2:3 cover, optional progress bar, one line under it. */
@Composable
fun CoverCard(item: CatItem, meta: String?, width: Dp = 112.dp, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SyntheticCover(item.title, item.year, item.genres, Modifier.width(width).height(width * 1.5f),
            badge = plBadge(item.audio, item.subs), art = item.art(), onPhone = onPhone(item), artSrv = item.srv)
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(4.dp))
        // "All": which server it is on ("jmpc", "jmpc +1" when another server has it too), after the card's own line:
        // the line is cut at the end, and "Next: S1 E3" matters more than the server ("jmpc · Next: S1 …" before)
        val where = item.srv.takeIf { it.isNotEmpty() }?.let { k ->
            (Servers.get(androidx.compose.ui.platform.LocalContext.current, k)?.name ?: k) + if (item.alsoOn.isNotEmpty()) " +${item.alsoOn.size}" else ""
        }
        val meta = listOfNotNull(meta, where).joinToString(" · ").ifEmpty { null }
        if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A horizontal row of cards with a header ("Comedy … All 57"). */
@Composable
fun <T> CoverRow(title: String, more: String?, items: List<T>, onMore: (() -> Unit)?, key: (T) -> Any, card: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier)
            .padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (more != null) Text(more, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = key) { card(it) }
        }
    }
}

/** Initials in a circle, the hue from the name (no photos: nothing is fetched from the internet). */
@Composable
fun Avatar(name: String, size: Dp = 60.dp, missing: Boolean = false) {
    var h = 0
    for (c in name) h = (h * 31 + c.code) % 360
    val ini = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(Modifier.size(size).clip(CircleShape).background(if (missing) Color(0xFF4A4A4A) else Color.hsl(h.toFloat(), 0.45f, 0.32f)),
        contentAlignment = Alignment.Center) {
        Text(ini, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp)
    }
}

/** A person under an avatar (cast row, frequent people). */
@Composable
fun PersonTile(name: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Avatar(name)
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
}

// ---------------------------------------------------------------- labels

private val GENRES = mapOf(
    "Action" to R.string.genre_action, "Adult" to R.string.genre_adult, "Adventure" to R.string.genre_adventure,
    "Animation" to R.string.genre_animation, "Biography" to R.string.genre_biography, "Comedy" to R.string.genre_comedy,
    "Crime" to R.string.genre_crime, "Documentary" to R.string.genre_documentary, "Drama" to R.string.genre_drama,
    "Family" to R.string.genre_family, "Fantasy" to R.string.genre_fantasy, "Film-Noir" to R.string.genre_noir,
    "Game-Show" to R.string.genre_game_show, "History" to R.string.genre_history, "Horror" to R.string.genre_horror,
    "Music" to R.string.genre_music, "Musical" to R.string.genre_musical, "Mystery" to R.string.genre_mystery,
    "News" to R.string.genre_news, "Reality-TV" to R.string.genre_reality, "Romance" to R.string.genre_romance,
    "Sci-Fi" to R.string.genre_scifi, "Short" to R.string.genre_short, "Sport" to R.string.genre_sport,
    "Talk-Show" to R.string.genre_talk_show, "Thriller" to R.string.genre_thriller, "War" to R.string.genre_war,
    "Western" to R.string.genre_western,
)

/** IMDb genre (English name from the agent) in the phone's language. */
@Composable
fun genreLabel(g: String): String = GENRES[g]?.let { stringResource(it) } ?: g

fun genreLabel(ctx: android.content.Context, g: String): String = GENRES[g]?.let { ctx.getString(it) } ?: g

/** A two-letter language code in the phone's language ("pl" → "Polish"/"polski"); `?` = unknown. */
@Composable
fun langName(code: String): String = if (code == "?" || code.isBlank()) stringResource(R.string.cat_lang_unknown)
    else Locale(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }

/** ★ 7.8 with the phone's decimal separator. */
fun ratingText(r: Double) = "★ " + String.format(Locale.getDefault(), "%.1f", r)

@Composable
fun votesText(v: Int): String = when {
    v >= 1_000_000 -> stringResource(R.string.cat_votes_m, String.format(Locale.getDefault(), "%.1f", v / 1_000_000.0))
    v >= 1000 -> stringResource(R.string.cat_votes_k, v / 1000)
    else -> v.toString()
}

fun qualityText(height: Int?): String? = when {
    height == null || height <= 0 -> null
    height >= 2000 -> "4K"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    else -> "${height}p"
}

/** "yesterday", "3 days ago" in the phone's language. */
fun agoText(epochSec: Double): String = android.text.format.DateUtils.getRelativeTimeSpanString(
    (epochSec * 1000).toLong(), System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

/** "1968 · 96 min" */
fun yearMinutes(year: Int?, minutes: Int?) = listOfNotNull(year?.toString(), minutes?.let { "$it min" }).joinToString(" · ")

/** Short line under a card: first genre and the length (or episodes of a series). */
@Composable
fun cardMeta(x: CatItem): String = listOfNotNull(x.genres.firstOrNull()?.let { genreLabel(it).lowercase(Locale.getDefault()) },
    if (x.series && x.episodes > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.cat_episodes, x.episodes, x.episodes)
    else x.minutes?.let { "$it min" }).joinToString(" · ")

@Composable
fun AgeBadge(age: Int) {
    Text("$age+", Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.errorContainer)
        .padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun InfoBadge(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium)
}

/** Plays a catalog item on the phone (the same activity as the library's "Phone" button); [imdb] enables "What next". */
/**
 * A library video's recognised name for the player, as the agent gives mpv and the Remote: "Series · S2 E3",
 * "Film (1968)"; null when this phone's copy of the catalog doesn't know it (the file name stays). Reads the catalog
 * from disk when it isn't in memory: call off the main thread.
 */
fun libraryTitle(ctx: android.content.Context, ref: MediaRef): String? {
    if (ref !is MediaRef.InLibrary) return null
    val cat = CatalogCache.cached(ctx, ref.srv) ?: return null
    val it = cat.items.firstOrNull { x -> x.root == ref.root && (x.path == ref.path || (x.dir != null && ref.path.startsWith(x.dir + "/"))) }
        ?: return null
    // an unrecognised item is only a folder's name (a folder holding many series became one item): the file says more
    if (it.imdb == null) return null
    if (it.series) {
        val name = ref.path.substringAfterLast('/')
        val m = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})""").find(name) ?: Regex("""\b(\d{1,2})x(\d{2,3})\b""").find(name)
        return if (m != null) "${it.title} · S${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}" else it.title
    }
    return if (it.year != null) "${it.title} (${it.year})" else it.title
}

fun playOnPhone(ctx: android.content.Context, ref: MediaRef, imdb: String?, kind: String) {
    ctx.startActivity(PlayerActivity.intent(ctx, ref, imdb.takeIf { kind == "movie" }))
}

/** Grows each time the screen comes back (e.g. from the player), for state read from this phone's history. */
@Composable
fun rememberResumedTick(): Int {
    var n by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return n
}

/**
 * Which episode a series continues at: the one being watched (a saved position), else the one after the last one watched
 * to the end, else the first. Only this phone's history counts.
 */
fun continueIndex(s: Settings, srv: String, root: Int, eps: List<Episode>): Int {
    var best = -1
    var bestAt = 0L
    eps.forEachIndexed { i, e ->
        val k = MediaRef.InLibrary(root, e.path, srv).key()
        val at = maxOf(s.positionAt(k), s.watchedAt(k))
        if (at > bestAt) { bestAt = at; best = i }
    }
    if (best < 0) return 0
    val k = MediaRef.InLibrary(root, eps[best].path, srv).key()
    val finished = s.position(k) <= 0 && s.watchedAt(k) > 0
    return if (finished) (best + 1).takeIf { it < eps.size } ?: 0 else best
}

/** Episode [i] in the phone's player, the following ones queued ("Next episode", a countdown at the end). */
fun playEpisode(ctx: android.content.Context, srv: String, root: Int, eps: List<Episode>, i: Int) {
    ctx.startActivity(PlayerActivity.intent(ctx, MediaRef.InLibrary(root, eps[i].path, srv), null, eps.drop(i + 1), series = true))
}

/**
 * Of a merged title's copies ([CatItem.alsoOn], "All servers"), the one this phone watched last, with its server; the
 * title itself when no other copy was watched more recently or that copy's server isn't paired any more.
 */
fun lastWatchedCopy(ctx: android.content.Context, item: CatItem, srv: String): Pair<CatItem, String> {
    if (item.alsoOn.isEmpty()) return item to srv
    val s = Settings(ctx)
    val pos = s.positions()
    val done = s.watchedAll()
    fun last(x: CatItem, k: String): Long {
        val key = MediaRef.InLibrary(x.root, x.path, k).key()
        val dir = x.dir?.let { MediaRef.InLibrary(x.root, "$it/", k).key() }
        fun hit(key2: String) = if (dir != null) key2.startsWith(dir) else key2 == key
        return maxOf(pos.filterKeys(::hit).values.maxOfOrNull { it.second } ?: 0L, done.filterKeys(::hit).values.maxOrNull() ?: 0L)
    }
    return (listOf(item to srv) + item.alsoOn.map { it to it.srv.ifEmpty { srv } })
        .filter { (_, k) -> Api.of(ctx, k) != null }
        .maxByOrNull { (x, k) -> last(x, k) }?.takeIf { (x, k) -> last(x, k) > last(item, srv) } ?: (item to srv)
}

/** "Play" on a catalog card: a film from its main file; a series where this phone left off, without its extras. */
fun playItem(ctx: android.content.Context, item0: CatItem, srv0: String) {
    // "All": a title on two servers resumes on the copy watched last (positions are saved per server and file)
    val (item, srv) = lastWatchedCopy(ctx, item0, srv0)
    val api = Api.of(ctx, srv)
    if (!item.series || item.dir == null || api == null || !api.srv.libraryEpisodes) {
        return playOnPhone(ctx, item.ref(srv), item.imdb, item.kind)
    }
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        val eps = withContext(Dispatchers.IO) { runCatching { api.episodes(item.root, item.dir).episodes }.getOrNull() }
        if (eps.isNullOrEmpty()) playOnPhone(ctx, item.ref(srv), item.imdb, item.kind)
        else playEpisode(ctx, srv, item.root, eps, continueIndex(Settings(ctx), srv, item.root, eps))
    }
}

package net.treetank.magneticplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// G1, sync points (docs/plan-ux-tasks.md): the phone works without the computer and catches up when it reaches the
// server again. What is sent and fetched: docs/api.md "Watch state" and "Catalog"; how: docs/architecture.md.

/**
 * The moments the phone catches up with a server: MainActivity's ping loop calls [reached] when the server answers
 * again (and when the app comes back on screen after a while); the Library screen's catalog refresh is one too. One
 * sync point = positions and "watched" marks both ways ([WatchSync]), the catalog's changes ([CatalogCache.refresh]),
 * downloaded copies checked and their subtitles refreshed ([OfflineSync]). Nothing runs while the app is off screen.
 */
object SyncPoint {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Blocking (call off the main thread): the server of [api] answers again. At most every 2 minutes per server. */
    fun reached(ctx: Context, api: Api) {
        val now = System.currentTimeMillis()
        if (now - (last[api.srv.key] ?: 0) < 2 * 60_000) return
        last[api.srv.key] = now
        val srv = Servers.get(ctx, api.srv.key) ?: api.srv
        if (srv.isGuest) return
        if (srv.hasCatalog) runCatching { CatalogCache.refresh(ctx, api) }  // runs the watch sync and the copies' checks
        else runCatching { WatchSync.sync(ctx, api) }
    }
}

/**
 * Positions, "watched" marks and subtitle choices made on this phone, sent to the server they belong to (agent 1.18+,
 * `features.watch_sync`, POST /api/watch/sync) and what the server knows newer brought back; the newer change wins per
 * title. The phone keeps them in Settings ("pos:", "pt:", "wd:" per video key) as before; this only remembers up to
 * when they were sent ("wsync_at:<server>") and the server's counter ("wsync_seq:<server>"). Library videos only:
 * a torrent's file has no stable place on the server.
 */
object WatchSync {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("magneticplayer", Context.MODE_PRIVATE)
    private fun picks(ctx: Context) = ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val lastRun = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A subtitle version picked in the player while the server was out of reach: the downloaded copy uses it now
     *  (it becomes the default of its language there) and the server gets the choice at the next sync. */
    fun pickSubOffline(ctx: Context, ref: MediaRef, name: String): Boolean {
        val item = OfflineStore.get(ctx, ref.key()) ?: return false
        val picked = item.subs.firstOrNull { it.name == name } ?: return false
        val lang = subLangKey(picked.lang)
        OfflineStore.update(ctx, ref.key()) { o ->
            o.copy(subs = o.subs.map { s -> if (subLangKey(s.lang) == lang) s.copy(default = s.name == name) else s })
        }
        if (ref is MediaRef.InLibrary) picks(ctx).edit().putString(ref.key(), JSONObject().put("name", name)
            .put("at", System.currentTimeMillis()).toString()).apply()
        return true
    }

    /** A subtitle choice for the video [key] made offline and not settled with its server yet. */
    fun pickPending(ctx: Context, key: String) = picks(ctx).contains(key)

    /**
     * Blocking: sends what changed since the last sync (all of it the first time) and applies what the server has
     * newer. Skipped for agents without `watch_sync`, and when nothing is waiting and the last sync was under 5 min ago.
     */
    @Synchronized
    fun sync(ctx: Context, api: Api) {
        val srv = Servers.get(ctx, api.srv.key) ?: api.srv
        if (!srv.hasWatchSync || srv.isGuest) return
        val p = prefs(ctx)
        val key = api.srv.key
        val prefix = Servers.prefixOf(key) + "lib"
        val sentAt = p.getLong("wsync_at:$key", 0)
        val started = System.currentTimeMillis()
        // the keys of this server's library videos with something saved after the last sync
        val all = p.all
        val keys = all.keys.asSequence().filter { it.startsWith("pos:") || it.startsWith("wd:") || it.startsWith("pt:") }
            .map { it.substringAfter(':') }.filter { it.startsWith(prefix) }.toSet()
        val pend = picks(ctx).all.filterKeys { it.startsWith(prefix) }
        val items = (keys + pend.keys).mapNotNull { k ->
            val rest = k.removePrefix(prefix)
            val root = rest.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
            val path = rest.substringAfter('/', "").ifEmpty { return@mapNotNull null }
            val pt = (all["pt:$k"] as? Long) ?: 0L
            val wd = (all["wd:$k"] as? Long) ?: 0L
            val pick = (pend[k] as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (maxOf(pt, wd) <= sentAt && pick == null) return@mapNotNull null
            JSONObject().put("root", root).put("path", path).apply {
                if (pt > 0) put("pos", (all["pos:$k"] as? Long) ?: 0L).put("pos_at", pt)
                if (wd > 0) put("watched_at", wd)
                pick?.let { put("sub", it.optString("name")).put("sub_at", it.optLong("at")) }
            }
        }
        if (items.isEmpty() && started - (lastRun[key] ?: 0) < 5 * 60_000) return
        lastRun[key] = started
        var seq = p.getLong("wsync_seq:$key", 0)
        val got = mutableListOf<JSONObject>()
        val applied = mutableSetOf<String>()
        // a first sync of a phone with a long history: in parts (the agent takes up to 300 per request)
        for (part in (if (items.isEmpty()) listOf(emptyList()) else items.chunked(200))) {
            val o = api.watchSync(seq, part)
            seq = o.optLong("seq", seq)
            o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) got += a.getJSONObject(i) }
            for (name in listOf("subs_applied", "subs_kept")) o.optJSONArray(name)?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { applied += prefix + it.optInt("root") + "/" + it.optString("path") }
            }
        }
        val e = p.edit()
        for (x in got) {
            val k = prefix + x.optInt("root") + "/" + x.optString("path")
            val posAt = x.optLong("pos_at")
            if (posAt > ((all["pt:$k"] as? Long) ?: 0L)) e.putLong("pos:$k", x.optLong("pos")).putLong("pt:$k", posAt)
            val wd = x.optLong("watched_at")
            if (wd > ((all["wd:$k"] as? Long) ?: 0L)) e.putLong("wd:$k", wd)
        }
        e.putLong("wsync_at:$key", started).putLong("wsync_seq:$key", seq).apply()
        // a choice the server took, or turned down because its subtitles are newer: either way it is settled, and the
        // downloaded copy gets the server's subtitles right after (OfflineSync.refreshSubs)
        if (applied.isNotEmpty()) picks(ctx).edit().apply { applied.forEach { remove(it) } }.apply()
    }
}

/** POST /api/watch/sync (agent 1.18+): [items] changed on this phone, [since] the counter of the last answer. */
fun Api.watchSync(since: Long, items: List<JSONObject>): JSONObject =
    JSONObject(request("POST", "/api/watch/sync", JSONObject().put("now", System.currentTimeMillis()).put("since", since)
        .put("items", JSONArray(items)), 20000))

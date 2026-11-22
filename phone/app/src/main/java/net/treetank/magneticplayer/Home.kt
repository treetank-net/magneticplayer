package net.treetank.magneticplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether each paired server is on the network the phone is on now (docs/architecture.md, "Away from home"). Checked once
 * per network change instead of every loop pinging an address that isn't there: on mobile data or at somebody else's
 * Wi-Fi (often the same 192.168.x.0/24) nothing is asked until the network changes again.
 *
 * A server is [Here.HERE] when it answers its ping with its own id (another device at the same address doesn't count),
 * [Here.ASLEEP] when it doesn't but something that stands next to it does (a Chromecast or a DLNA TV seen while the server
 * answered, remembered per server: they never travel, unlike a phone or a laptop), [Here.AWAY] otherwise. A server that
 * can't be woken (no MAC, or a laptop on Wi-Fi) learns no such neighbours: it is only ever here or away.
 * Only reachability: the library stays one, every title already says which server it is on. No location permission,
 * no outside service.
 */
object Home {
    enum class Here { UNKNOWN, HERE, ASLEEP, AWAY }

    private const val TAG = "MpHome"
    private const val PREFS = "home"
    private const val LOOK_MS = 4000L  // mDNS and SSDP answers come within a second or two

    val state = MutableStateFlow<Map<String, Here>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var registered = false
    @Volatile private var lastCheck = 0L

    fun of(key: String): Here = state.value[key] ?: Here.UNKNOWN
    fun away(key: String) = of(key) == Here.AWAY

    /** Once per process: follow network changes. Later calls (the app back on screen) check again after a long break. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!registered) {
            registered = true
            val cm = app.getSystemService(ConnectivityManager::class.java)
            runCatching {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = recheck(app)
                    override fun onLost(network: Network) = recheck(app)
                })
            }.onFailure { Log.w(TAG, "network callback", it); recheck(app) }
        } else if (System.currentTimeMillis() - lastCheck > 10 * 60_000) recheck(app)
    }

    /** A new check shortly (a network change brings several callbacks in a row). */
    fun recheck(ctx: Context) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch { delay(1500); check(app) }
    }

    /** The server answered after all (woken, reached by hand): no need to wait for the next network change. */
    fun mark(key: String, here: Here) { state.value = state.value + (key to here) }

    /** While [key] is away: until the verdict changes, at most [ms] (one ping from a screen that is open stays cheap). */
    suspend fun whileAway(key: String, ms: Long = 60_000) {
        if (away(key)) withTimeoutOrNull(ms) { state.first { it[key] != Here.AWAY } }
    }

    private suspend fun check(ctx: Context) {
        lastCheck = System.currentTimeMillis()
        val servers = Servers.all(ctx)
        if (servers.isEmpty()) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val local = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        if (!local) {  // mobile data only, or no network: no home server is reachable
            state.value = servers.associate { it.key to Here.AWAY }
            Log.d(TAG, "no local network: all away")
            return
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = coroutineScope {
            val pings = servers.associate { s ->
                s.key to async { Api.of(ctx, s.key)?.let { it.ping(1500) || it.ping(3000) } == true }
            }
            val since = System.currentTimeMillis()
            val stop = withContext(Dispatchers.Main) { CastNet.discover(ctx) }
            val tvs = async { runCatching { Dlna.discover() }.getOrDefault(emptyList()).map { "tv:" + it.id } }
            try { delay(LOOK_MS) } finally { withContext(Dispatchers.Main) { stop() } }
            val seen = CastNet.seenSince(since).map { "cast:$it" }.toSet() + tvs.await()
            servers.associate { s ->
                val known = prefs.getStringSet("near/" + s.key, null).orEmpty()
                val here = when {
                    pings.getValue(s.key).await() -> {
                        // only a computer that can be woken where it stands learns its neighbours: a laptop on Wi-Fi
                        // ("wake: no") travels, and would look "asleep here" in every place it has been
                        if (s.mac.isNotBlank() && s.wakeConfidence != "no" && !known.containsAll(seen))
                            prefs.edit().putStringSet("near/" + s.key, (known + seen).toList().takeLast(16).toSet()).apply()
                        Here.HERE
                    }
                    seen.any { it in known } -> Here.ASLEEP
                    else -> Here.AWAY
                }
                s.key to here
            }
        }
        state.value = result
        Log.d(TAG, "verdict ${result.values.groupingBy { it }.eachCount()}")
    }
}

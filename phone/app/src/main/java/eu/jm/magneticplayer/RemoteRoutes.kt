package eu.jm.magneticplayer

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Nazwa urządzenia w systemie (kafelek multimediów, karta w panelu) zamiast „Ten telefon” dla mpv, telewizora DLNA
 * i Chromecasta sterowanego bez Cast SDK.
 *
 * System UI łączy sesję multimediów ze „zdalną sesją trasy” (MediaRouter2), gdy `controlId` z VolumeProvider sesji
 * (media3: `DeviceInfo.routingControllerId`) jest równe `originalId` sesji trasy, a jej właściciel to nasz pakiet.
 * Dlatego apka sama jest dostawcą tras ([RemoteRouteProvider]): na każdy grający cel publikuje trasę i od razu
 * zakłada dla niej sesję (`REQUEST_ID_NONE`, bez `transferTo`, więc kilka celów naraz się nie wypiera).
 * Android 11+; starsze zostają przy „Ten telefon”. Opis: docs/plan-piloty.md (krok 4).
 */
object RemoteRoutes {
    private const val TAG = "MpRoutes"
    const val FEATURE = "eu.jm.magneticplayer.REMOTE"

    /** co gra: rodzaj → nazwa urządzenia */
    private val wanted = linkedMapOf<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private var router: Any? = null  // MediaRouter2 (API 30)
    private var callback: Any? = null

    /** id sesji trasy dla celu (`DeviceInfo.routingControllerId`); null poniżej Androida 11 */
    fun controllerId(kind: String): String? = if (Build.VERSION.SDK_INT >= 30) "mp-session-$kind" else null

    /** Cel `kind` gra na urządzeniu `name` (null = przestał). Z dowolnego wątku. */
    fun set(ctx: Context, kind: String, name: String?) {
        if (Build.VERSION.SDK_INT < 30) return
        val app = ctx.applicationContext
        main.post {
            if (wanted[kind] == name) return@post
            if (name == null) wanted.remove(kind) else wanted[kind] = name
            bind(app)
            RemoteRouteProvider.instance?.sync(wanted.toMap())
        }
    }

    fun current(): Map<String, String> = wanted.toMap()

    /**
     * System wiąże dostawcę tras, dopóki ktoś szuka tras z jego cechą: nasz MediaRouter2 szuka (bez aktywnego
     * skanowania), póki cokolwiek gra.
     */
    @RequiresApi(30)
    private fun bind(app: Context) {
        val r = (router as? MediaRouter2) ?: MediaRouter2.getInstance(app).also { router = it }
        if (wanted.isNotEmpty() && callback == null) {
            val cb = object : MediaRouter2.RouteCallback() {}
            r.registerRouteCallback({ it.run() }, cb, RouteDiscoveryPreference.Builder(listOf(FEATURE), false).build())
            callback = cb
            Log.d(TAG, "szukam tras $FEATURE")
        } else if (wanted.isEmpty() && callback != null) {
            r.unregisterRouteCallback(callback as MediaRouter2.RouteCallback)
            callback = null
        }
    }
}

/** Dostawca tras: trasa i sesja na każdy grający cel ([RemoteRoutes]). Głośność i przełączanie zostają w apce. */
@RequiresApi(30)
class RemoteRouteProvider : MediaRoute2ProviderService() {
    private val sessions = mutableMapOf<String, String>()  // rodzaj → nazwa, dla których jest sesja

    override fun onCreate() {
        super.onCreate()
        instance = this
        sync(RemoteRoutes.current())
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Publikuje trasy i sesje dokładnie dla `want` (wątek główny). */
    fun sync(want: Map<String, String>) {
        notifyRoutes(want.map { (kind, name) ->
            MediaRoute2Info.Builder(routeId(kind), name)
                .addFeature(RemoteRoutes.FEATURE)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .setDescription(when (kind) { "screen" -> "ekran komputera"; "dlna" -> "telewizor"; else -> "Chromecast" })
                .setExtras(Bundle().apply { putString("kind", kind) })
                .build()
        })
        for (kind in sessions.keys - want.keys) {
            notifySessionReleased(sessionId(kind))
            sessions.remove(kind)
        }
        for ((kind, name) in want) {
            if (sessions[kind] == name) continue
            val info = RoutingSessionInfo.Builder(sessionId(kind), packageName)
                .addSelectedRoute(routeId(kind))
                .setName(name)
                .build()
            if (kind in sessions) notifySessionUpdated(info) else notifySessionCreated(REQUEST_ID_NONE, info)
            sessions[kind] = name
            Log.d("MpRoutes", "sesja ${sessionId(kind)}: $name")
        }
    }

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) {
        // przełączanie na nasze trasy z zewnątrz (systemowy wybór wyjścia) nic nie robi: cel wybiera się w apce
        notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        notifyRequestFailed(requestId, REASON_REJECTED)
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) = notifyRequestFailed(requestId, REASON_REJECTED)
    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) = notifyRequestFailed(requestId, REASON_REJECTED)
    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) = notifyRequestFailed(requestId, REASON_REJECTED)
    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) = notifyRequestFailed(requestId, REASON_REJECTED)
    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) = notifyRequestFailed(requestId, REASON_REJECTED)

    companion object {
        @Volatile var instance: RemoteRouteProvider? = null
        fun routeId(kind: String) = "mp-route-$kind"
        fun sessionId(kind: String) = "mp-session-$kind"
    }
}

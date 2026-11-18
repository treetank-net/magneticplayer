package net.treetank.magneticplayer

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
 * Device name in the system (media tile, card in the panel) instead of "This phone" for mpv, DLNA TV
 * and Chromecast controlled without the Cast SDK.
 *
 * System UI links a media session to a "remote route session" (MediaRouter2) when the `controlId` from the session's VolumeProvider
 * (media3: `DeviceInfo.routingControllerId`) equals the route session's `originalId` and its owner is our package.
 * That is why the app is itself a route provider ([RemoteRouteProvider]): for every playing target it publishes a route and immediately
 * creates a session for it (`REQUEST_ID_NONE`, no `transferTo`, so several targets at once do not displace each other).
 * Android 11+; older versions keep "This phone". Description: docs/plan-remotes.md (step 4).
 */
object RemoteRoutes {
    private const val TAG = "MpRoutes"
    const val FEATURE = "net.treetank.magneticplayer.REMOTE"

    /** what is playing: kind → device name */
    private val wanted = linkedMapOf<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private var router: Any? = null  // MediaRouter2 (API 30)
    private var callback: Any? = null

    /** route session id for a target (`DeviceInfo.routingControllerId`); null below Android 11 */
    fun controllerId(kind: String): String? = if (Build.VERSION.SDK_INT >= 30) "mp-session-$kind" else null

    /** Target `kind` plays on device `name` (null = stopped). From any thread. */
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
     * The system binds the route provider as long as someone is looking for routes with its feature: our MediaRouter2 looks (without active
     * scanning) as long as anything is playing.
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

/** Route provider: a route and a session for every playing target ([RemoteRoutes]). Volume and switching stay in the app. */
@RequiresApi(30)
class RemoteRouteProvider : MediaRoute2ProviderService() {
    private val sessions = mutableMapOf<String, String>()  // kind → name, for which a session exists

    override fun onCreate() {
        super.onCreate()
        instance = this
        sync(RemoteRoutes.current())
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Publishes routes and sessions exactly for `want` (main thread). */
    fun sync(want: Map<String, String>) {
        notifyRoutes(want.map { (kind, name) ->
            MediaRoute2Info.Builder(routeId(kind), name)
                .addFeature(RemoteRoutes.FEATURE)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .setDescription(when (kind) { "screen" -> getString(R.string.main_route_screen); "dlna" -> getString(R.string.main_route_tv); else -> "Chromecast" })
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
        // switching to our routes from outside (the system output picker) does nothing: the target is chosen in the app
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

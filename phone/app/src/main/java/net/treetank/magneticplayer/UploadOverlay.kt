package net.treetank.magneticplayer

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Okienko nad innymi aplikacjami (przeglądarką), gdy pojawi się pobrany film: „Pobrano X. Przesłać na jmpc?”
 * z przyciskami, jak pytanie menedżera haseł o zapisanie hasła. Wymaga uprawnienia „Wyświetlanie nad innymi
 * aplikacjami” (SYSTEM_ALERT_WINDOW, nadaje użytkownik w ustawieniach systemu). Bez niego, albo gdy nikt nie
 * kliknie przez [TIMEOUT_MS], zostaje zwykłe powiadomienie z tymi samymi przyciskami.
 */
object UploadOverlay {
    private const val TIMEOUT_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<Uploads.PhoneVideo, String>>()
    private var shown: ComposeView? = null
    private var owner: Owner? = null

    fun allowed(ctx: Context) = SysSettings.canDrawOverlays(ctx)

    /** Ekran systemowy z przełącznikiem „Wyświetlanie nad innymi aplikacjami” dla tej apki. */
    fun settingsIntent(ctx: Context) = Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Pokazuje pytanie (z dowolnego wątku). false = brak uprawnienia, zostaje samo powiadomienie. */
    fun show(ctx: Context, v: Uploads.PhoneVideo, server: String): Boolean {
        val app = ctx.applicationContext
        if (!allowed(app)) return false
        main.post {
            queue.addLast(v to server)
            if (shown == null) next(app)
        }
        return true
    }

    private fun next(app: Context) {
        val (v, server) = queue.removeFirstOrNull() ?: return
        val wm = app.getSystemService(WindowManager::class.java)
        val o = Owner().also { owner = it }
        val view = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                AppTheme {
                    Card(v, server,
                        upload = { del -> close(app); Uploads.enqueue(app, v, del) },
                        dismiss = { close(app); Uploads.cancelNotif(app, v.id) })
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // bez fokusu: klawiatura i przeglądarka pod spodem działają dalej; ALT_FOCUSABLE_IM kładzie okno pod
            // warstwą klawiatury (samo NOT_FOCUSABLE rysuje je na niej), więc dostaje jej wysokość i staje nad nią
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // nad paskami systemu i nad klawiaturą (inaczej klawiatura je zasłania, gdy ktoś akurat pisze)
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
            windowAnimations = android.R.style.Animation_InputMethod  // wjeżdża od dołu
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {  // uprawnienie cofnięte w międzyczasie: zostaje powiadomienie
            o.destroy(); owner = null; return
        }
        o.resume()
        shown = view
        main.postDelayed({ if (shown === view) close(app) }, TIMEOUT_MS)  // nietknięte: zostaje powiadomienie
    }

    private fun close(app: Context) {
        val view = shown ?: return
        shown = null
        runCatching { app.getSystemService(WindowManager::class.java).removeView(view) }
        owner?.destroy(); owner = null
        main.postDelayed({ if (shown == null) next(app) }, 300)
    }

    @androidx.compose.runtime.Composable
    private fun Card(v: Uploads.PhoneVideo, server: String, upload: (Boolean) -> Unit, dismiss: () -> Unit) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 8.dp) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudUpload, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.media_overlay_title, server), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.padding(top = 6.dp))
                Text(v.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.media_overlay_size, humanBytes(v.size)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(if (Uploads.canDeleteSilently()) R.string.media_overlay_delete_note_silent
                    else R.string.media_overlay_delete_note_ask),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(dismiss) { Text(stringResource(R.string.media_no)) }
                    FilledTonalButton({ upload(false) }) { Text(stringResource(R.string.media_upload_action)) }
                    Button({ upload(true) }) { Text(stringResource(R.string.media_overlay_send_delete)) }
                }
            }
        }
    }

    /** Cykl życia dla Compose w oknie bez aktywności. */
    private class Owner : SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ss = SavedStateRegistryController.create(this).apply { performRestore(null) }
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ss.savedStateRegistry
        fun resume() { reg.currentState = Lifecycle.State.RESUMED }
        fun destroy() { if (reg.currentState.isAtLeast(Lifecycle.State.CREATED)) reg.currentState = Lifecycle.State.DESTROYED }
    }
}

package com.jarvys.agent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.IdentityHashMap

/** Call at the top of every standalone private memory surface; do not compose its content on null. */
@Composable
internal fun memoryUiProtection(onClose: (() -> Unit)? = null): MemoryUiAutomationGuard.Lease? {
    val view = LocalView.current
    var lease by remember(view) { mutableStateOf<MemoryUiAutomationGuard.Lease?>(null) }
    DisposableEffect(view) {
        val acquired = MemoryUiAutomationGuard.enterProtectedSurface()
        val secureWindow = MemorySecureWindows.acquire(view.context.activityOrNull()?.window)
        lease = acquired
        onDispose { releaseMemoryProtectionAfterRemoval(view, acquired, secureWindow) }
    }
    val current = lease
    if (current?.isReadyForUser == true) return current
    Column(Modifier.fillMaxWidth().padding(20.dp).testTag("memory-ui-protected"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.memory_ui_user_only_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (current == null) R.string.memory_loading else R.string.memory_ui_reopen_manually))
        if (onClose != null) Button(onClick = onClose, modifier = Modifier.testTag("memory-ui-protected-close")) {
            Text(stringResource(R.string.memory_scope_close))
        }
    }
    return null
}

/** Recheck at the native callback, not only when a button was composed. */
internal fun MemoryUiAutomationGuard.Lease.allowsHumanUiAction(): Boolean =
    runCatching { requireHumanUiInteraction(); true }.getOrDefault(false)

private fun Context.activityOrNull(): Activity? {
    var candidate: Context = this
    val seen = HashSet<Context>()
    while (seen.add(candidate)) {
        if (candidate is Activity) return candidate
        candidate = (candidate as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/** Ref-count window security independently of nested screen/dialog composition lifetimes. */
private object MemorySecureWindows {
    private data class State(var users: Int, val originallySecure: Boolean)
    private val windows = IdentityHashMap<Window, State>()

    class Lease(private val window: Window?) {
        private var closed = false
        fun close(clearOnSafeDraw: Boolean) {
            if (closed) return
            closed = true
            if (window == null) return
            val state = windows[window] ?: return
            state.users--
            if (state.users == 0) {
                windows.remove(window)
                // A detached window may survive in Recents. Leave it secure rather than expose old pixels.
                if (clearOnSafeDraw && !state.originallySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun acquire(window: Window?): Lease {
        if (window != null) {
            val existing = windows[window]
            if (existing != null) existing.users++
            else {
                windows[window] = State(1, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        return Lease(window)
    }
}

/** Never unblock automation on a timer while pixels from a removed memory surface remain visible. */
private fun releaseMemoryProtectionAfterRemoval(
    view: View,
    lease: MemoryUiAutomationGuard.Lease,
    secureWindow: MemorySecureWindows.Lease,
) {
    val root = view.rootView
    if (!root.isAttachedToWindow) {
        secureWindow.close(clearOnSafeDraw = false)
        lease.close()
        return
    }
    var released = false
    var drawQueued = false
    lateinit var drawListener: ViewTreeObserver.OnDrawListener
    lateinit var attachListener: View.OnAttachStateChangeListener
    fun release(drawn: Boolean) {
        if (released) return
        released = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(drawListener)
        root.removeOnAttachStateChangeListener(attachListener)
        secureWindow.close(clearOnSafeDraw = drawn)
        lease.close()
    }
    drawListener = ViewTreeObserver.OnDrawListener {
        if (!drawQueued) {
            drawQueued = true
            // The callback is before draw completion; post to release only after this frame's draw.
            root.post { release(drawn = true) }
        }
    }
    attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { release(drawn = false) }
    }
    root.addOnAttachStateChangeListener(attachListener)
    root.viewTreeObserver.addOnDrawListener(drawListener)
    root.invalidate()
}

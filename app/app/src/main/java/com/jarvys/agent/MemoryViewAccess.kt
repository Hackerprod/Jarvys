package com.jarvys.agent

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selection identity survives content refresh, but never navigation or a lifecycle interruption. */
internal class MemoryViewRoute(private val lifecycle: Lifecycle) {
    private val epoch = java.util.concurrent.atomic.AtomicLong(0)
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    fun currentEpoch() = epoch.get()
    fun requireCurrent(expected: Long) {
        check(active.get() && epoch.get() == expected && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    fun pause() { epoch.incrementAndGet() }
    fun close() { active.set(false); epoch.incrementAndGet() }
}

/** Authority for exactly one currently selected native review. It does not authorize data sharing. */
internal class MemoryViewAccess(
    private val view: View,
    private val store: MemoryStore,
    private val key: String,
    private val shared: Boolean,
    private val lifecycle: Lifecycle,
    private val route: MemoryViewRoute,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val routeEpoch = route.currentEpoch()
    @Volatile private var previouslyDisplayed = false
    private val invalidated = java.util.concurrent.atomic.AtomicBoolean(false)
    private val childWindows = java.util.concurrent.CopyOnWriteArrayList<android.view.Window>()
    private val childProtection = java.util.IdentityHashMap<android.view.Window, Pair<MemoryUiAutomationGuard.Lease, MemorySecureWindows.Lease>>()
    fun bindWindow(window: android.view.Window?): AutoCloseable {
        if (window != null) { childWindows.add(window); if (!ready) protect() }
        return AutoCloseable {
            if (window != null) {
                childWindows.remove(window)
                childProtection.remove(window)?.let { (guard, secure) ->
                    releaseMemoryProtectionAfterRemoval(window.decorView, guard, secure)
                }
            }
        }
    }
    private fun protectChildWindows() {
        childWindows.forEach { window ->
            if (!childProtection.containsKey(window)) childProtection[window] =
                MemoryUiAutomationGuard.enterViewTransition() to MemorySecureWindows.acquire(window)
        }
    }
    private fun releaseChildWindowsAfterFrame() {
        val releasing = childProtection.toMap(); childProtection.clear()
        releasing.forEach { (window, leases) -> releaseMemoryProtectionAfterRemoval(window.decorView, leases.first, leases.second) }
    }
    @Volatile private var alive = true
    @Volatile private var ready = false
    @Volatile private var displayReady = false
    var state: String = ""
        private set
    private var transition: MemoryUiAutomationGuard.Lease? = null
    private var secure: MemorySecureWindows.Lease? = null
    private var registration: AutoCloseable? = null
    fun attach() {
        protect()
        registration = MemoryUiAutomationGuard.registerViewAuthority { captureAllowed() }
    }
    private fun protect() {
        MemoryUiAutomationGuard.invalidateQueuedAutomation()
        if (transition == null) transition = MemoryUiAutomationGuard.enterViewTransition()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (secure == null) secure = MemorySecureWindows.acquire(view.context.activityOrNull()?.window)
            protectChildWindows()
        } else view.post {
            if (alive && secure == null) secure = MemorySecureWindows.acquire(view.context.activityOrNull()?.window)
            protectChildWindows()
        }
    }
    fun prepare() {
        check(alive)
        store.ensureInitialized()
        state = store.nativeStateFingerprint(shared)
    }
    fun authorize() {
        store.withNativeState(state, shared, { requireActive() }) {
            ready = true; displayReady = true; previouslyDisplayed = true
        }
    }
    private fun requireActive() { check(alive && !invalidated.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    /** Short exact-selection check; data revision is checked separately before a read. */
    fun requireSelection() { requireActive(); check(displayReady) }
    /** Already-bound effects retain route identity while their own mutations invalidate capture. */
    fun requireEffectSelection() { route.requireCurrent(routeEpoch); check(previouslyDisplayed) }
    fun requireCurrent() {
        requireSelection()
        if (store.nativeStateFingerprint(shared) != state) { invalidate(); error(view.context.getString(R.string.memory_native_selection_changed)) }
    }
    fun allowsUiAction(): Boolean = runCatching { requireCurrent(); true }.getOrDefault(false)
    fun <T> read(block: () -> T): T {
        requireCurrent()
        val result = store.withNativeState(state, shared, { requireActive(); check(ready) }) { block() }
        // Internal seeding/recovery may have changed the view. Never publish it under the old revision.
        requireCurrent()
        return result
    }
    /** A draft edit is a content revision. Keep human editing usable. */
    fun draftChanged() {
        requireSelection()
        ready = false
        protect()
        authorize()
    }
    private fun captureAllowed(): Boolean = runCatching {
        requireCurrent(); check(ready)
        store.withNativeState(state, shared, { requireActive(); check(ready) }) { true }
    }.getOrDefault(false)
    fun releaseTransitionAfterFrame() {
        if (!ready) return
        val guard = transition ?: return
        val window = secure ?: return
        transition = null; secure = null
        releaseMemoryProtectionAfterRemoval(view, guard, window)
        releaseChildWindowsAfterFrame()
    }
    /** Must run before route/tab/new-content mutation, not after a different selection has rendered. */
    fun invalidateIfDataChanged() {
        if (state.isNotEmpty() && runCatching { store.nativeStateFingerprint(shared) == state }.getOrDefault(false).not()) invalidate()
    }
    fun invalidate() {
        if (!alive || !invalidated.compareAndSet(false, true)) return
        ready = false
        protect()
        view.post { if (alive) changed() }
    }
    fun navigate(action: () -> Unit) { route.close(); invalidate(); action() }
    override fun close() {
        ready = false; displayReady = false; alive = false
        protect()
        val guard = transition; val window = secure
        transition = null; secure = null
        val registered = registration; registration = null
        // Keep the invalid authority until old pixels are gone; the transition outlives composition.
        if (guard != null && window != null) releaseMemoryProtectionAfterRemoval(view, guard, window)
        else guard?.close()
        releaseChildWindowsAfterFrame()
        registered?.close()
    }
}

@Composable
internal fun memoryViewProtection(store: MemoryStore, key: String, shared: Boolean = false,
                                  onClose: (() -> Unit)? = null): MemoryViewAccess? {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var generation by remember(store, key) { mutableIntStateOf(0) }
    var ready by remember(store, key, generation) { mutableStateOf(false) }
    var error by remember(store, key, generation) { mutableStateOf<String?>(null) }
    var prepared by remember(store, key, generation) { mutableStateOf(false) }
    val route = remember(store, key, shared, owner) { MemoryViewRoute(owner.lifecycle) }
    DisposableEffect(route) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) route.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { route.close(); owner.lifecycle.removeObserver(observer) }
    }
    val access = remember(store, key, shared, generation, owner) {
        MemoryViewAccess(view, store, key, shared, owner.lifecycle, route) { generation++ }
    }
    DisposableEffect(access) {
        access.attach()
        var resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { resumed=false; access.invalidate() }
            if (event == Lifecycle.Event.ON_RESUME && !resumed) { resumed=true; generation++ }
        }
        val listener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) = access.invalidateIfDataChanged()
            override fun onMemoryCleared() = access.invalidateIfDataChanged()
        }
        owner.lifecycle.addObserver(observer)
        MemoryStore.addGlobalSearchListener(listener)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            MemoryStore.removeGlobalSearchListener(listener)
            access.close()
        }
    }
    LaunchedEffect(access) {
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { access.prepare() } }.onSuccess {
            prepared = true
            runCatching {
                access.authorize(); ready = true
            }.onFailure { error = it.message }
        }.onFailure { error = it.message }
    }
    if (ready) {
        SideEffect { access.releaseTransitionAfterFrame() }
        return access
    }
    Column(Modifier.padding(20.dp)) {
        Text(error ?: stringResource(R.string.memory_loading))
        if (onClose != null) Button(onClick = { access.navigate(onClose) }) { Text(stringResource(R.string.memory_native_close)) }
    }
    return null
}

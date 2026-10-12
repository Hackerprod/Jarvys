package com.jarvys.agent

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarvys.agent.connectors.ConnectorStateStore
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
    private val initialDraftHash: String,
    private val lifecycle: Lifecycle,
    private val route: MemoryViewRoute,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val policy = NativeActionPolicy(view.context)
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
    var captureReviewNeeded by mutableStateOf(false)
        private set
    @Volatile private var request: NativeActionPolicy.Request? = null
    var state: String = ""
        private set
    private var transition: MemoryUiAutomationGuard.Lease? = null
    private var secure: MemorySecureWindows.Lease? = null
    private var registration: AutoCloseable? = null
    private var ask: MemoryUiAutomationGuard.Lease? = null
    val requiresApproval get() = request?.requiresApproval == true
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
        var candidate = policy.prepare(NativeActionPolicy.Operation.MEMORY_VIEW,
            NativeActionPolicy.binding(store.nativeScopeIdentity(), key, shared.toString()))
        try {
            candidate.requireCurrent()
            store.ensureInitialized()
            state = store.nativeStateFingerprint(shared)
            candidate = candidate.rebind(NativeActionPolicy.binding(store.nativeScopeIdentity(), key, shared.toString(), state, initialDraftHash))
            request = candidate
        } catch (failure: RuntimeException) { candidate.close(); throw failure }
    }
    fun openReview() {
        if (requiresApproval && ask == null) {
            val acquired = MemoryUiAutomationGuard.enterProtectedSurface()
            if (!acquired.isReadyForUser) { acquired.close(); error(view.context.getString(R.string.memory_native_finish_interaction)) }
            ask = acquired
        }
    }
    fun authorize() {
        val exact = checkNotNull(request)
        if (exact.requiresApproval) exact.approveOnce { checkNotNull(ask).requireHumanUiInteraction(); requireActive() }
        store.withNativeState(state, shared, { exact.admit { requireActive() } }) {
            ready = true; displayReady = true; previouslyDisplayed = true; captureReviewNeeded = false
        }
        ask?.close(); ask = null
    }
    private fun requireActive() { check(alive && !invalidated.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    /** Short exact-selection check; safe inside final policy admission (no disk reads). */
    fun requireSelection() { requireActive(); check(displayReady); checkNotNull(request).requireCurrent() }
    /** Already-bound effects retain route identity while their own mutations invalidate capture. */
    fun requireEffectSelection() { route.requireCurrent(routeEpoch); check(previouslyDisplayed) }
    fun requireCurrent() {
        requireSelection()
        if (store.nativeStateFingerprint(shared) != state) { invalidate(); error(view.context.getString(R.string.memory_native_selection_changed)) }
    }
    fun allowsUiAction(): Boolean = runCatching { requireCurrent(); true }.getOrDefault(false)
    fun <T> read(block: () -> T): T {
        requireCurrent()
        val result = store.withNativeState(state, shared, { checkNotNull(request).admit { requireActive(); check(ready) } }) { block() }
        // Internal seeding/recovery may have changed the view. Never publish it under the old revision.
        requireCurrent()
        return result
    }
    /** A draft edit is a content revision, not a new permission family. Keep human editing usable. */
    fun draftChanged(exactDraftHash: String) {
        requireSelection()
        ready = false
        protect()
        request = checkNotNull(request).rebind(NativeActionPolicy.binding(store.nativeScopeIdentity(), key, state, exactDraftHash))
        captureReviewNeeded = requiresApproval
        if (!requiresApproval) authorize()
    }
    fun approveDraftCapture() {
        requireSelection()
        openReview()
        authorize()
    }
    private fun captureAllowed(): Boolean = runCatching {
        requireCurrent(); check(ready)
        store.withNativeState(state, shared, { checkNotNull(request).admit { requireActive(); check(ready) } }) { true }
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
    fun invalidateIfPolicyChanged(key: String?) {
        // A SharedPreferences callback may run inside the policy setter lock. Never take a Request/store lock here.
        val id = NativeActionPolicy.CONNECTOR_ID
        val operation = NativeActionPolicy.Operation.MEMORY_VIEW.id
        if (request != null && (key == null || key == com.jarvys.agent.connectors.PersistedActionPolicies.policyKey(id, operation) ||
                key == com.jarvys.agent.connectors.PersistedActionPolicies.metadataKey(id, operation))) invalidate()
    }
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
        request?.close(); request = null
        ask?.close(); ask = null
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
                                  onClose: (() -> Unit)? = null, draftHash: String = ""): MemoryViewAccess? {
    val view = LocalView.current
    val context = LocalContext.current
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
        MemoryViewAccess(view, store, key, shared, draftHash, owner.lifecycle, route) { generation++ }
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
        // Any durable policy edit invalidates the existing exact selection before another capture.
        val preferences = context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, android.content.Context.MODE_PRIVATE)
        val policyListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> access.invalidateIfPolicyChanged(key) }
        owner.lifecycle.addObserver(observer)
        MemoryStore.addGlobalSearchListener(listener)
        preferences.registerOnSharedPreferenceChangeListener(policyListener)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            MemoryStore.removeGlobalSearchListener(listener)
            preferences.unregisterOnSharedPreferenceChangeListener(policyListener)
            access.close()
        }
    }
    LaunchedEffect(access) {
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { access.prepare() } }.onSuccess {
            prepared = true
            runCatching {
                if (access.requiresApproval) access.openReview()
                else { access.authorize(); ready = true }
            }.onFailure { error = it.message }
        }.onFailure { error = it.message }
    }
    if (ready) {
        SideEffect { access.releaseTransitionAfterFrame() }
        return access
    }
    Column(Modifier.padding(20.dp)) {
        Text(error ?: stringResource(if (!prepared) R.string.memory_native_review_loading else R.string.memory_native_review_prompt))
        if (prepared && access.requiresApproval) {
            Text(stringResource(R.string.memory_native_review_scope, store.nativeScopeIdentity(), key, access.state))
            Button(onClick = {
                runCatching { access.authorize() }.onSuccess { ready = true }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.memory_native_allow_once)) }
        }
        if (onClose != null) Button(onClick = { access.navigate(onClose) }) { Text(stringResource(R.string.memory_native_close)) }
    }
    return null
}

package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose callbacks with owner requests; latches make lifecycle ordering observable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionRequestUiLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "fixture.permission.lifecycle"
    private val permission = "fixture.permission.REQUEST"

    private class PermissionResults : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = AtomicInteger()
        @Volatile var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                    options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
            launches.incrementAndGet()
        }
        fun grant(permission: String) { check(dispatchResult(requireNotNull(requestCode), mapOf(permission to true))) }
    }

    private class Runtime : ConnectorRuntime {
        val connects = AtomicInteger()
        val disconnects = AtomicInteger()
        var connectEntered: CountDownLatch? = null
        var connectRelease: CountDownLatch? = null
        var disconnectEntered: CountDownLatch? = null
        var disconnectRelease: CountDownLatch? = null
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) {
            connects.incrementAndGet(); connectEntered?.countDown()
            connectRelease?.let { check(it.await(10, TimeUnit.SECONDS)) { "Connect release missing" } }
        }
        override fun disconnect() {
            disconnects.incrementAndGet(); disconnectEntered?.countDown()
            disconnectRelease?.let { check(it.await(10, TimeUnit.SECONDS)) { "Disconnect release missing" } }
        }
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("Synthetic action", emptyList()), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject().put("ok", true)
    }

    private fun registry(access: ConnectionAccess, runtime: Runtime, needsPermission: Boolean = false,
                         granted: () -> Boolean = { true }): ConnectorRegistry =
        ConnectorRegistry.createForTests(access, { granted() }).also {
            it.register(ConnectorDefinition(id, "Lifecycle fixture", "1", "Synthetic connector", runtime = runtime,
                readPermissions = if (needsPermission) listOf(permission) else emptyList()))
        }

    private fun currentRequest(access: ConnectionAccess, registry: ConnectorRegistry): ConnectorRegistry.ConnectionRequest {
        val op = requireNotNull(access.captureChoice(id).coreView?.intents?.get(id)).command.operationId
        return requireNotNull(registry.resolvePendingRequest(op))
    }

    private fun disconnect() {
        val label = compose.activity.getString(R.string.connector_disconnect)
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.connector_disconnect_confirm_title, "Lifecycle fixture"))
            .assertIsDisplayed()
        compose.onAllNodesWithText(label).onLast().performClick()
    }

    @Test fun configurationRecreationUsesTheSamePermissionRequestAndStartsExactlyOnce() {
        val access = ConnectionAccess.forTests(emptyMap())
        val runtime = Runtime()
        var granted = false
        val registry = registry(access, runtime, needsPermission = true) { granted }
        val permissionResults = PermissionResults()
        compose.activity.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove("permission_asked_$id").commit()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissionResults) {
                MaterialTheme { ConnectorsDeviceDetailScreen(registry, id, {}, { error("Missing definition") }) }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.connector_connect)).performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle() // Drain main-executor callbacks posted by the settings worker.
            permissionResults.launches.get() == 1
        }
        val original = currentRequest(access, registry)
        val epoch = requireNotNull(access.captureChoice(id).coreView).intentEpoch(id)
        // StateRestorationTester itself is not a configuration change. Set the actual Activity flag
        // for this controlled recreation and restore it afterward, matching the production branch.
        ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", true)
        try {
            assertTrue("The recreation must exercise the real configuration-change branch", compose.activity.isChangingConfigurations)
            restoration.emulateSavedInstanceStateRestore()
        }
        finally { ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", false) }
        assertSame(original, registry.resolvePendingRequest(original.requestId))
        assertEquals(epoch, requireNotNull(access.captureChoice(id).coreView).intentEpoch(id))
        compose.runOnIdle { granted = true; permissionResults.grant(permission) }
        compose.waitUntil(10_000) { registry.state(id) == ConnectorState.CONNECTED }
        assertEquals(1, runtime.connects.get())
        assertEquals(1, permissionResults.launches.get())
        assertEquals(epoch, requireNotNull(access.captureChoice(id).coreView).intentEpoch(id))
    }

    @Test fun navigationDisposalAbortsUntouchedPermissionRequestAndLateResultCannotConnect() {
        val access = ConnectionAccess.forTests(emptyMap())
        val runtime = Runtime()
        var granted = false
        val registry = registry(access, runtime, needsPermission = true) { granted }
        val permissionResults = PermissionResults()
        val shown = mutableStateOf(true)
        compose.activity.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove("permission_asked_$id").commit()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissionResults) {
                MaterialTheme { if (shown.value) ConnectorsDeviceDetailScreen(registry, id, {}, {}) }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.connector_connect)).performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle() // Drain main-executor callbacks posted by the settings worker.
            permissionResults.launches.get() == 1
        }
        val original = currentRequest(access, registry)
        assertFalse(compose.activity.isChangingConfigurations)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag("connectors-detail-screen").assertCountEquals(0)
        compose.waitUntil(10_000) { registry.resolvePendingRequest(original.requestId) == null }
        compose.runOnIdle { granted = true; permissionResults.grant(permission); shown.value = true }
        compose.waitForIdle()
        assertEquals(0, runtime.connects.get())
        assertEquals(ConnectorState.DISCONNECTED, registry.state(id))
        assertEquals(1, permissionResults.launches.get())
        assertEquals(ConnectionStateCoordinator.Outcome.NOT_ATTEMPTED,
            registry.submitConnectionRequest(original).get(10, TimeUnit.SECONDS).choiceOutcome)
    }

    @Test fun acceptedDisconnectContinuesAfterDisposalWithoutNavigatingTheOldScreen() {
        val access = ConnectionAccess.forTests(mapOf(id to true))
        val runtime = Runtime().apply {
            disconnectEntered = CountDownLatch(1); disconnectRelease = CountDownLatch(1)
        }
        val registry = registry(access, runtime)
        val shown = mutableStateOf(true)
        val navigations = AtomicInteger()
        compose.setContent { MaterialTheme {
            if (shown.value) ConnectorsDeviceDetailScreen(registry, id, { navigations.incrementAndGet() }, {})
        } }
        try {
            disconnect()
            assertTrue(runtime.disconnectEntered!!.await(10, TimeUnit.SECONDS))
            val request = currentRequest(access, registry)
            val completion = registry.submitConnectionRequest(request) // Observe the same operation, not a new request.
            assertFalse(completion.isDone)
            assertEquals(0, navigations.get())
            assertEquals(ConnectorState.DISCONNECTED, registry.state(id))
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle() // Disposal must complete before the accepted operation settles.
            compose.onAllNodesWithTag("connectors-detail-screen").assertCountEquals(0)
            runtime.disconnectRelease!!.countDown()
            assertTrue(completion.get(10, TimeUnit.SECONDS).persistenceConfirmed)
            compose.waitForIdle()
            assertEquals(1, runtime.disconnects.get())
            assertEquals(0, navigations.get())
        } finally { runtime.disconnectRelease!!.countDown() }
    }

    @Test fun disconnectRemainsAvailableWhileConnectRuntimeIsPending() {
        val access = ConnectionAccess.forTests(emptyMap())
        val runtime = Runtime().apply { connectEntered = CountDownLatch(1); connectRelease = CountDownLatch(1) }
        val registry = registry(access, runtime)
        compose.setContent { MaterialTheme { ConnectorsDeviceDetailScreen(registry, id, {}, {}) } }
        try {
            compose.onNodeWithText(compose.activity.getString(R.string.connector_connect)).performClick()
            assertTrue(runtime.connectEntered!!.await(10, TimeUnit.SECONDS))
            val old = currentRequest(access, registry)
            val oldCompletion = registry.submitConnectionRequest(old)
            disconnect()
            val off = currentRequest(access, registry)
            assertEquals(ConnectionStateCoordinator.Kind.DISCONNECT, off.kind)
            assertNotEquals(old.requestId, off.requestId)
            assertFalse(registry.isConnectionRequestCurrent(old))
            assertEquals(ConnectorState.DISCONNECTED, registry.state(id))
            runtime.connectRelease!!.countDown()
            oldCompletion.get(10, TimeUnit.SECONDS)
            registry.submitConnectionRequest(off).get(10, TimeUnit.SECONDS)
            assertEquals(1, runtime.connects.get())
            assertTrue(runtime.disconnects.get() >= 1)
            assertEquals(ConnectorState.DISCONNECTED, registry.state(id))
        } finally { runtime.connectRelease!!.countDown() }
    }

    @Test fun bootstrapPendingStillExposesOffWithoutEnablingOnOrWaitingForStorage() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val access = ConnectionAccess.forTestsControlled(mapOf(id to true), Executor { queued.add(it) })
        val runtime = Runtime()
        val registry = registry(access, runtime)
        val navigations = AtomicInteger()
        compose.setContent { MaterialTheme {
            ConnectorsDeviceDetailScreen(registry, id, { navigations.incrementAndGet() }, {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.connector_connect)).assertIsNotEnabled()
        disconnect()
        compose.waitUntil(10_000) { runtime.disconnects.get() == 1 }
        assertFalse(access.isReady())
        assertEquals(0, navigations.get())
        assertEquals(ConnectorState.DISCONNECTED, registry.state(id))
        while (true) { val next = queued.poll() ?: break; next.run() }
        compose.waitUntil(10_000) { access.isReady() && access.captureChoice(id).coreView?.choices?.get(id)?.selected() == false }
        assertEquals(0, runtime.connects.get())
    }
}

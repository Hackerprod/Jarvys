package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.connectors.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No real provider, socket, OAuth browser or network. Exercises the production owner and leases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpActionLeaseIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixtures = mutableListOf<Harness>()
    private val actionPolicyPreferences = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
    private var priorDefaultPolicy: String? = null
    private var hadPriorDefaultPolicy = false
    private var priorWizardCompleted = false
    private var hadPriorWizardCompleted = false

    @Before fun clear() {
        context.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
        hadPriorDefaultPolicy = actionPolicyPreferences.contains(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
        priorDefaultPolicy = actionPolicyPreferences.getString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, null)
        hadPriorWizardCompleted = actionPolicyPreferences.contains(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
        priorWizardCompleted = actionPolicyPreferences.getBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, false)
        actionPolicyPreferences.edit().remove(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
            .remove(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY).commit()
        ActionPolicyDefaults.bindDefaultPolicyPreferences(actionPolicyPreferences)
    }

    @After fun close() {
        fixtures.forEach(Harness::close)
        context.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
        actionPolicyPreferences.edit().apply {
            if (hadPriorDefaultPolicy) putString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, priorDefaultPolicy)
            else remove(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
            if (hadPriorWizardCompleted) putBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, priorWizardCompleted)
            else remove(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
        }.commit()
        ActionPolicyDefaults.bindDefaultPolicyPreferences(null)
    }

    @Test fun defaultAllowDispatchesOnceWithoutSelectingAnyNewCapability() {
        val h = harness()
        assertFalse(h.repository.get(h.config.id)!!.tools.single().enabled)
        assertThrows(IllegalArgumentException::class.java) { h.call() }
        h.enable()
        h.call()
        assertEquals(1, h.calls.size)
        assertTrue(h.presenter.requests.isEmpty())
        assertNull(h.store.savedPolicy("mcp:${h.config.id}", "update_record"))
    }

    @Test fun askFreezesArgumentsAndConfigSchemaExposureAccountChangesBlockDispatch() {
        val mutations: List<(Harness) -> Unit> = listOf(
            { h -> h.repository.upsert(h.current().copy(endpoint = "https://changed.example/mcp")) },
            { h -> h.repository.updateToolEnabled(h.config.id, "update_record", false) },
            { h -> h.repository.updateTools(h.config.id, h.current().tools.map { it.copy(inputSchemaJson = "{\"type\":\"array\"}") }) },
            { h -> h.repository.saveBearerToken(h.config.id, h.config.endpoint, "replacement-fixture-account") },
            { h -> h.manager.disconnect(h.config.id) },
            { h ->
                h.repository.updateToolEnabled(h.config.id, "update_record", false)
                h.repository.updateToolEnabled(h.config.id, "update_record", true)
            },
        )
        mutations.forEach { mutate ->
            val h = harness(bearer = true)
            h.enable(); h.policy(AutonomyPolicy.ASK)
            val args = JSONObject().put("value", "original")
            val pending = h.worker.submit<JSONObject> { h.call(args) }
            val (id, _) = h.awaitApproval()
            args.put("value", "changed")
            mutate(h)
            h.gate.resolve(id, ApprovalDecision.APPROVED)
            assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(h.calls.isEmpty())
        }
    }

    @Test fun approvedAskUsesFrozenExactArgumentsOnce() {
        val h = harness(); h.enable(); h.policy(AutonomyPolicy.ASK)
        val nested = JSONObject().put("value", "approved")
        val args = JSONObject().put("nested", nested)
        val pending = h.worker.submit<JSONObject> { h.call(args) }
        val (id, _) = h.awaitApproval()
        nested.put("value", "unreviewed")
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        pending.get(5, TimeUnit.SECONDS)
        assertEquals("approved", h.calls.single().getJSONObject("nested").getString("value"))
    }

    @Test fun oauth401GetsFreshAdmissionAndChangedPolicyDuringRefreshBlocksRetry() {
        for (revoke in listOf(false, true)) {
            val h = harness(oauth = true); h.enable()
            val expectedConfig = h.current()
            val expectedRevision = h.repository.authorityRevision(h.config.id)
            h.response = { if (h.calls.size == 1) throw McpTransportClient.McpAuthorizationException("fixture 401", null) else success() }
            h.refresh = {
                if (revoke) h.policy(AutonomyPolicy.DENY)
                JSONObject().put("access_token", "fixture-refreshed").put("expires_in", 3600)
            }
            if (revoke) assertThrows(IllegalStateException::class.java) { h.call() } else h.call()
            assertEquals(1, h.refreshes.get())
            assertEquals(if (revoke) 1 else 2, h.calls.size)
            assertEquals(expectedConfig, h.current())
            assertEquals(expectedRevision, h.repository.authorityRevision(h.config.id))
            assertTrue(h.presenter.requests.isEmpty())
        }
    }

    @Test fun transportRetryHookMustAdmitEveryAttemptAndRetainPolicyRevision() {
        val h = harness(); h.enable()
        h.retryInsideSession = true
        h.response = { h.policy(AutonomyPolicy.ASK); success() }
        assertThrows(IllegalStateException::class.java) { h.call() }
        assertEquals(1, h.calls.size)
        assertTrue(h.presenter.requests.isEmpty())
    }

    @Test fun returningToolRetainsItsSavedDenyAndNeverGetsSeededBackToAllow() {
        val h = harness(); h.enable(); h.policy(AutonomyPolicy.DENY)
        h.discover(emptyList())
        h.discover(listOf(h.tool))
        h.enable()
        assertThrows(McpWriteDeniedException::class.java) { h.call() }
        assertTrue(h.calls.isEmpty())
    }

    @Test fun wizardAskFallbackDoesNotRequireMaterializedPerToolRows() {
        com.jarvys.agent.ui.settings.SetupWizardPreferences(actionPolicyPreferences).complete(AutonomyPolicy.ASK)
        val h = harness(); h.enable()
        assertEquals(McpInitialToolPolicy.ASK, h.current().initialToolPolicy)
        val pending = h.worker.submit<JSONObject> { h.call() }
        val (id, _) = h.awaitApproval()
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        pending.get(5, TimeUnit.SECONDS)
        assertEquals(1, h.calls.size)
        assertNull(h.store.savedPolicy("mcp:${h.config.id}", "update_record"))
    }

    private fun success() = JSONObject().put("result", JSONObject().put("content", JSONArray()).put("isError", false))
    private fun harness(oauth: Boolean = false, bearer: Boolean = false): Harness = Harness(oauth, bearer).also {
        fixtures += it; it.connect()
    }
    private class Presenter : ApprovalPresenter {
        val requests = LinkedBlockingQueue<Pair<String, ApprovalSummary>>()
        override fun show(id: String, summary: ApprovalSummary) { requests.put(id to summary) }
        override fun update(id: String, decision: ApprovalDecision) = Unit
    }
    private inner class Harness(oauthEnabled: Boolean, bearer: Boolean) : AutoCloseable {
        private val values = ConcurrentHashMap<Pair<String, String>, String>()
        val repository = McpServerRepository(context, object : McpCredentialVault {
            override fun get(serverId: String, key: String) = values[serverId to key]
            override fun save(serverId: String, key: String, value: String) { values[serverId to key] = value }
            override fun clear(serverId: String) { values.keys.removeAll { it.first == serverId } }
        })
        val config = McpServerConfig("lease-${fixtures.size}", "Fixture", "https://fixture.example/mcp",
            authMode = when { oauthEnabled -> McpAuthMode.OAUTH; bearer -> McpAuthMode.BEARER; else -> McpAuthMode.NONE },
            oauthClientId = "fixture-client")
        val tool = McpToolConfig("update_record", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false))
        val presenter = Presenter()
        val gate = ApprovalGate(5_000, presenter)
        val store = InMemoryConnectorAutonomyStore()
        val approval = McpWriteApprovalCoordinator(store, gate, NoopAutonomyActionNotifier)
        val worker = Executors.newSingleThreadExecutor()
        val calls = CopyOnWriteArrayList<JSONObject>()
        val refreshes = AtomicInteger()
        var response: () -> JSONObject = ::success
        var refresh: () -> JSONObject = { error("Unexpected refresh") }
        var retryInsideSession = false
        private lateinit var discovery: (List<McpToolConfig>) -> Unit
        val oauth = McpOAuthManager(repository, { 1_000_000L }) { _, _ -> refreshes.incrementAndGet(); refresh() }
        val manager: McpConnectionManager
        init {
            repository.upsert(config)
            if (bearer) repository.saveBearerToken(config.id, config.endpoint, "fixture-initial-account")
            if (oauthEnabled) {
                mapOf("oauth_endpoint_binding" to config.endpoint, "oauth_access_token" to "fixture-access",
                    "oauth_refresh_token" to "fixture-refresh", "oauth_expires_at" to "2000000",
                    "oauth_token_endpoint" to "https://fixture.example/token").forEach { (key, value) -> repository.saveOAuthValue(config.id, key, value) }
            }
            manager = McpConnectionManager(repository, oauth, approval, sessionFactory = { _, _, changed ->
                discovery = changed
                changed(listOf(tool))
                object : McpProtocolSession {
                    override val tools = listOf(tool)
                    override val serverInfo = McpServerInfo("fixture", "1", null)
                    override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                        calls += JSONObject(arguments.toString()); return response()
                    }
                    override fun callTool(name: String, arguments: JSONObject, token: CancellationToken,
                                          beforeDispatch: (McpDispatchKind) -> Unit): JSONObject {
                        beforeDispatch(McpDispatchKind.TOOL_CALL)
                        val result = callTool(name, arguments, token)
                        if (retryInsideSession) {
                            beforeDispatch(McpDispatchKind.TOOL_CALL)
                            return callTool(name, arguments, token)
                        }
                        return result
                    }
                    override fun close() = Unit
                }
            })
        }
        fun connect() {
            manager.connect(config.id)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (manager.state(config.id).status == McpConnectionStatus.CONNECTING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(manager.state(config.id).message, McpConnectionStatus.READY, manager.state(config.id).status)
        }
        fun current() = repository.get(config.id)!!
        fun enable() = repository.updateToolEnabled(config.id, "update_record", true)
        fun policy(policy: AutonomyPolicy) = store.setPolicy("mcp:${config.id}", "update_record", policy)
        fun call(arguments: JSONObject = JSONObject()) = manager.callTool(config.id, "update_record", arguments, CancellationToken.uncancellable())
        fun discover(tools: List<McpToolConfig>) = discovery(tools)
        fun awaitApproval() = presenter.requests.poll(5, TimeUnit.SECONDS) ?: error("Expected one approval")
        override fun close() { worker.shutdownNow(); manager.close() }
    }
}

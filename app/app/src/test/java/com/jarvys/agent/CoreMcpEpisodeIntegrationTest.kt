package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.connectors.*
import com.jarvys.agent.mcp.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Real adapter, policy coordinator and manager; only the remote session is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoreMcpEpisodeIntegrationTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var database: ChatDatabaseBridge
    private lateinit var manager: McpConnectionManager
    private lateinit var tool: CoreMcpTool
    private val conversation = "mcp-episode-test"
    private val owner = "request"
    private var effects = 0
    private var response = JSONObject().put("result", JSONObject().put("content", JSONArray()))
    private var atDispatch: () -> Unit = {}

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        val prefix = "effect-" + java.util.UUID.randomUUID() + "-"
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        database = ChatDatabaseBridge.getInstance(context)
        database.ensureConversationSync(conversation)
        val secrets = ConcurrentHashMap<Pair<String, String>, String>()
        val repository = McpServerRepository(context, object : McpCredentialVault {
            override fun get(serverId: String, key: String) = secrets[serverId to key]
            override fun save(serverId: String, key: String, value: String) { secrets[serverId to key] = value }
            override fun clear(serverId: String) { secrets.keys.removeAll { it.first == serverId } }
        })
        val config = McpServerConfig("fixture", "Fixture", "https://fixture.example/mcp", authMode = McpAuthMode.NONE)
        repository.upsert(config)
        val declared = McpToolConfig("update_record", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false))
        val policies = InMemoryConnectorAutonomyStore()
        policies.setPolicy("mcp:fixture", "update_record", AutonomyPolicy.ALLOW)
        manager = McpConnectionManager(repository, McpOAuthManager(repository),
            McpWriteApprovalCoordinator(policies, ApprovalGate(2_000, object : ApprovalPresenter {
                override fun show(id: String, summary: ApprovalSummary) { error("ALLOW must not request approval") }
                override fun update(id: String, decision: ApprovalDecision) = Unit
            }), NoopAutonomyActionNotifier), sessionFactory = { _, _, discovery ->
                discovery(listOf(declared))
                object : McpProtocolSession {
                    override val tools = listOf(declared)
                    override val serverInfo = McpServerInfo("fixture", "1", null)
                    override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                        atDispatch()
                        effects++
                        return response
                    }
                    override fun close() = Unit
                }
            })
        manager.connect(config.id)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.state(config.id).status == McpConnectionStatus.CONNECTING && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(McpConnectionStatus.READY, manager.state(config.id).status)
        repository.updateToolEnabled(config.id, declared.wireName, true)
        tool = CoreMcpTool(manager, repository.exposedTools().single())
    }
    @After fun close() { if (::manager.isInitialized) manager.close() }
    private fun invocation() = CoreToolRegistry(listOf(tool)).withInvocationObserver(EpisodeRecorder(context, conversation, owner))
    private fun evidence() = database.personalAgentEpisodeNarrationInputSync(
        database.personalAgentEpisodesForSourceMessagesSync(conversation, listOf(owner)).single().id)!!.evidence.single()

    @Test fun realMcpAdapterPersistsAdmissionBeforeDispatchAndAcceptsStructuredCompletion() {
        atDispatch = { assertEquals("started", evidence().outcome); assertEquals("allow", evidence().authorization) }
        val result = invocation().invoke(tool.declaration().name, emptyMap(), CancellationToken.cancellable(), null, "call")
        assertTrue(result.success)
        assertEquals(1, effects)
        assertEquals("ok", evidence().outcome)
    }
    @Test fun malformedProtocolResponseCannotBecomeSuccessfulEffect() {
        response = JSONObject().put("result", JSONObject().put("content", "not-an-array"))
        invocation().invoke(tool.declaration().name, emptyMap(), CancellationToken.cancellable(), null, "call")
        assertEquals(1, effects)
        assertEquals("uncertain", evidence().outcome)
    }
    @Test fun failedDurableAdmissionPreventsRemoteDispatch() {
        val registry = CoreToolRegistry(listOf(tool)).withInvocationObserver(object : CoreToolRegistry.InvocationObserver {
            override fun beforeDispatch(tool: CoreTool, callId: String, arguments: Map<String, Any>) = Unit
            override fun onEffectAdmission(tool: CoreTool, callId: String, admission: EffectInvocation.Admission) {
                throw IllegalStateException("simulated persistence failure")
            }
            override fun afterDispatch(tool: CoreTool, callId: String, result: CoreToolResult?, failure: Throwable?) = Unit
        })
        assertFalse(registry.invoke(tool.declaration().name, emptyMap(), CancellationToken.cancellable(), null, "call").success)
        assertEquals(0, effects)
    }
}

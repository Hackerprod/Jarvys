package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.connectors.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises Runtime's installation and WRITE transport, not a manually equipped source provider.
 * READ connector execute4 currently delegates execute2 without context; that adapter is not covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
class CentralRuntimeSourceIntegrationTest {
    @get:Rule val fixture = ChatTestStore()

    @Test fun realCentralRuntimeInstallsExactProvenanceEvenWhenMemoryIsOff() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        val access = ConnectionAccess.forTests(emptyMap())
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()), ConnectionAccessProvider {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun connectionAccess() = access
        }
        val registry = ConnectorRegistry.createForTests(access, { true })
        val registryField = ConnectorRegistry::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val secretsField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        val previousRegistry = registryField.get(null)
        val previousSecrets = secretsField.get(null)
        val policy = MemoryWritePolicy.forContext(context)
        val previousMemory = policy.isEnabled
        val received = AtomicReference<CentralInvocationWitness>()
        val gatewayCalls = AtomicInteger()
        val modelCalls = AtomicInteger()
        val operation = ConnectorOperation("record_observation", "Record local fixture observation", JSONObject("{\"type\":\"object\",\"properties\":{}}"), write = true)
        val runtimeGateway = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun hasCompleteWriteAdmissionCoverage() = true
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation =
                ConnectorWritePreparation(ApprovalSummary("Record local fixture", emptyList()), arguments)
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
                throw AssertionError("Registry must preserve the explicit invocation context")
            override fun invokePreparedWithContext(operation: String, arguments: JSONObject,
                preparation: ConnectorWritePreparation, token: CancellationToken,
                context: ConnectorInvocationContext): JSONObject {
                preparation.authorizeWriteEffect!!.invoke()
                val invocation = requireNotNull(context.effectInvocation)
                assertTrue("Admission must precede the local fixture effect", invocation.admitted())
                val witness = requireNotNull(invocation.centralSource())
                witness.requireInvocationCurrent()
                assertFalse(policy.isEnabled)
                received.set(witness); gatewayCalls.incrementAndGet()
                preparation.reportWriteOutcome!!.invoke(EffectInvocation.Outcome.OK)
                return JSONObject().put("observed", true)
            }
        }
        val definition = ConnectorDefinition("runtime_source_fixture", "Runtime source", "1", "Local instrumented fixture",
            operations = listOf(operation), runtime = runtimeGateway)
        val toolName = CoreConnectorTool.toolName(definition.id, operation.name)
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                throw AssertionError("Use the real conversation model path")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String,
                token: CancellationToken): ModelReply {
                assertEquals(WorkspaceIdentity.CENTRAL_SESSION, sessionId)
                assertTrue(tools.any { it.name == toolName })
                return when (modelCalls.incrementAndGet()) {
                    1 -> ModelReply("", listOf(ModelReply.Call("runtime-source-call", toolName, emptyMap())))
                    2 -> {
                        assertTrue(history.any { it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolCallId == "runtime-source-call" })
                        ModelReply("Local observation complete.", emptyList())
                    }
                    else -> throw AssertionError("No other provider/model calls are expected")
                }
            }
        }
        try {
            registryField.set(null, registry)
            val emptySecrets = context.getSharedPreferences("central-runtime-source-empty-secrets", Context.MODE_PRIVATE)
            assertTrue(emptySecrets.edit().clear().commit())
            secretsField.set(null, SecretStore(emptySecrets))
            policy.setEnabledBlocking(false)
            registry.register(definition)
            assertTrue(registry.connect(definition.id).persistenceConfirmed)
            registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
            val store = LocalRunStore(context)
            val owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Record a local fixture observation")
            val runtime = CoreAgentRuntime(context, WorkspaceIdentity.CENTRAL_SESSION, emptyList(), CorePromptBudget.standard(), provider)
            runtime.setCurrentUserMessageId(owner)
            runtime.setCurrentTurnOwnerMessageId(owner)
            val bridge = ChatDatabaseBridge.getInstance(context)
            val semanticBefore = bridge.personalAgentBrowse().filter { it.memoryKind != "episode" }.associateBy { it.id }
            val result = try {
                runtime.runMainChat("Record a local fixture observation",
                    store.loadConversationContext(WorkspaceIdentity.CENTRAL_SESSION), emptyList(), CancellationToken.cancellable(), null)
            } catch (failure: Throwable) {
                // Diagnostic identities only; never substitute a payload or relax canonical source validation.
                val visible = runCatching { store.loadConversationContext(WorkspaceIdentity.CENTRAL_SESSION).map { turn ->
                    val source=turn.source()
                    "${turn.kind}:${source?.kind}:${source?.sourceId}:${source?.callId}"
                } }.getOrElse { listOf("reader-failure:${it.javaClass.simpleName}") }
                val observed=received.get()?.source()
                throw AssertionError("Canonical source diagnostic: modelCalls=${modelCalls.get()}, gatewayCalls=${gatewayCalls.get()}, " +
                    "owner=$owner, observedBatch=${observed?.sourceBatchId}, observedCall=${observed?.sourceCallId}, visible=$visible",failure)
            }
            assertEquals("COMPLETED", result.outcome)
            assertEquals(2, modelCalls.get()); assertEquals(1, gatewayCalls.get())
            val witness = requireNotNull(received.get())
            val source = witness.source()
            assertEquals(owner, source.sourceMessageId)
            assertEquals(WorkspaceIdentity.CENTRAL_SESSION, source.ownerCentralSessionId)
            assertEquals("runtime-source-call", source.sourceCallId)
            assertEquals(result.runId, source.sourceRunId)
            assertEquals(com.jarvys.agent.crew.CrewProcessIdentity.ID, source.sourceProcessId)
            assertEquals(bridge.connectionProjectionEvidenceSync().generation(), source.storageGeneration)
            val persisted = requireNotNull(bridge.readEpisodeToolSourceSync(source.ownerCentralSessionId, source.sourceBatchId, source.sourceCallId))
            assertEquals(source.sourceBatchId, persisted.batchId)
            assertEquals(toolName, persisted.toolName)
            assertNotNull(persisted.output)
            assertFalse(policy.isEnabled)
            // OFF blocks semantic memory, not the mandatory neutral WRITE audit (§7.5 A).
            val episodes = bridge.personalAgentEpisodesForSourceMessagesSync(source.ownerCentralSessionId, listOf(owner))
            assertEquals(1, episodes.size)
            val episode = bridge.personalAgentGet(listOf(episodes.single().id), true).single()
            assertEquals("episode", episode.kind)
            assertTrue(episode.title.isNullOrEmpty())
            assertEquals("", episode.text)
            assertEquals("none", episode.narration)
            val toolEvidence = episode.evidence.filter { it.refKind == "tool_call" }.single()
            assertEquals(source.ownerCentralSessionId, toolEvidence.refConversationId)
            assertEquals(source.sourceBatchId, toolEvidence.sourceBatchId)
            assertEquals(source.sourceCallId, toolEvidence.refId)
            assertEquals(source.sourceRunId, toolEvidence.runId)
            assertEquals(source.sourceProcessId, toolEvidence.processId)
            assertEquals(toolName, toolEvidence.toolName)
            assertEquals("ok", toolEvidence.outcome)
            assertEquals("allow", toolEvidence.authorization)
            val semanticAfter = bridge.personalAgentBrowse().filter { it.memoryKind != "episode" }.associateBy { it.id }
            assertEquals("No new or changed semantic memory/digest while OFF", semanticBefore, semanticAfter)
            assertThrows(IllegalStateException::class.java) { witness.requireInvocationCurrent() }
            assertNotNull(witness.source()) // Historical identity remains readable after sealing.
        } finally {
            try { policy.setEnabledBlocking(previousMemory) }
            finally {
                registryField.set(null, previousRegistry)
                secretsField.set(null, previousSecrets)
            }
        }
    }
}

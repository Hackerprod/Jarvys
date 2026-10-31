package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeCompletionSemanticsTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private val assignment = EffectInvocation.EffectReference(EffectInvocation.EffectReferenceKind.ASSIGNMENT,
        "workspace-1", "assignment-1")
    private fun decision() = EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
        "native-central", "delegate", "1", "test")
    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        bridge.ensureConversationSync(central)
        MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
    }
    private fun invoke(call: String, operation: (EffectInvocation) -> CoreToolResult): CoreToolResult {
        val tool = object : CoreTool {
            override fun declaration() = ToolSpec("synthetic_coordination", "test", "Synthetic local action", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun effectClass() = CoreTool.EffectClass.WRITE
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = error("Context required")
            override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                                 progress: CoreTool.ProgressListener?, invocation: EffectInvocation?): CoreToolResult =
                operation(requireNotNull(invocation))
        }
        return CoreToolRegistry(listOf(tool)).withInvocationObserver(EpisodeRecorder(context, central, call))
            .invoke("synthetic_coordination", emptyMap(), CancellationToken.cancellable(), null, call)
    }
    private fun input(call: String) = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(
        bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf(call)).single().id))

    @Test fun coordinationMeaningSurvivesRegistryRecorderAndNarratorWithoutParsingToolJson() {
        invoke("local-acceptance") { invocation ->
            invocation.admit(decision())
            CoreToolResult.success("untrusted display text: mission complete").withEffectReceipt(invocation.receipt(
                EffectInvocation.Outcome.OK, EffectInvocation.CompletionSemantics.COORDINATION_ACCEPTED, assignment))
        }
        val facts = JSONObject(input("local-acceptance").evidence.single().factsJson)
        assertEquals("coordination_accepted", facts.getString("completion_semantics"))
        assertFalse(facts.getBoolean("goal_verified"))
        assertEquals("assignment-1", facts.getJSONObject("effect_reference").getString("id"))
        var calls = 0
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                                  tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation boundary required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                calls++
                assertTrue(history.single().content.contains("coordination_accepted"))
                assertTrue(history.single().content.contains("goal_verified"))
                assertTrue(userPrompt.contains("never worker consumption, mission completion or a Coding command result"))
                assertTrue(userPrompt.contains("existing_reference"))
                return ModelReply("""{"title":"Assignment accepted","narration":"The local assignment was accepted; completion is not verified."}""", emptyList())
            }
        }
        assertTrue(EpisodeNarrator(context, central, CoreAgentModel(provider, "semantics-test"))
            .narratePending(CancellationToken.cancellable()))
        assertEquals(1, calls)
    }
    @Test fun foreignReceiptCannotPersistConfirmedMeaningEvenWithMatchingAdmission() {
        val foreign = EffectInvocation { }.also { it.admit(decision()) }
        invoke("foreign") { invocation ->
            invocation.admit(decision())
            CoreToolResult.success("accepted").withEffectReceipt(foreign.receipt(EffectInvocation.Outcome.OK,
                EffectInvocation.CompletionSemantics.COORDINATION_ACCEPTED, assignment))
        }
        val row = input("foreign").evidence.single()
        assertEquals("uncertain", row.outcome)
        assertFalse(JSONObject(row.factsJson).has("completion_semantics"))
        assertFalse(JSONObject(row.factsJson).has("effect_reference"))
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
    }
    @Test fun existingFastPathRecordsNoNewExecutionOrAuthorization() {
        invoke("existing") { invocation -> CoreToolResult.success("existing assignment").withEffectReceipt(
            invocation.receipt(EffectInvocation.Outcome.NOT_EXECUTED, EffectInvocation.CompletionSemantics.EXISTING_REFERENCE, assignment)) }
        val row = input("existing").evidence.single()
        assertEquals("not_executed", row.outcome)
        assertNull(row.authorization)
        assertEquals("existing_reference", JSONObject(row.factsJson).getString("completion_semantics"))
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
    }
    @Test fun duplicateFoundAfterAdmitRetainsUncertaintyAndOriginalReferenceWithoutNewOk() {
        invoke("raced-existing") { invocation ->
            invocation.admit(decision())
            CoreToolResult.success("existing assignment").withEffectReceipt(invocation.receipt(
                EffectInvocation.Outcome.UNCERTAIN, EffectInvocation.CompletionSemantics.EXISTING_REFERENCE, assignment))
        }
        val row = input("raced-existing").evidence.single()
        assertEquals("uncertain", row.outcome)
        assertEquals("allow", row.authorization)
        assertEquals("existing_reference", JSONObject(row.factsJson).getString("completion_semantics"))
        assertEquals("assignment-1", JSONObject(row.factsJson).getJSONObject("effect_reference").getString("id"))
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
    }
}

package com.jarvys.agent

import android.app.Application
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class NativeWorkspaceCheckpointRecoveryTest {
    private val storage = ChatTestStore(bridgeExecutorFactory = { ChatTestStore.directExecutorService() })
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val role = CrewRole("trip-planner", "Trip planner", "planner", "Prepare the requested itinerary; ask for dates.",
        listOf("ask_chief"), null, "General-purpose itinerary planning", 1, emptyList(), CrewProfile.WorkspaceMode.LEGACY_CHAT)

    private fun manager(f: WorkspaceMemoryFixture, session: String, calls: AtomicInteger, ask: Boolean, expectedAnswer: String? = null, forbiddenAnswer: String? = null): CrewManager {
        val empty = CoreToolRegistry(emptyList())
        val manager = CrewManager(session, empty,
            { bot, crew -> CoreToolRegistry(CrewTools.bot(bot, crew, null)) },
            { _, tools, incoming -> CoreAgentLoop({ history, _, _, _ ->
                calls.incrementAndGet()
                if (expectedAnswer != null) assertTrue("Exact retained answer did not precede successor model call",
                    history.any { it.content.contains(expectedAnswer) })
                if (forbiddenAnswer != null) assertFalse("Superseded retained answer reached corrected model",
                    history.any { it.content.contains(forbiddenAnswer) })
                if (ask) ModelReply("", listOf(ModelReply.Call("dates-question", "ask_chief", mapOf("question" to "Which travel dates?"))))
                else ModelReply("The itinerary can now continue.", emptyList())
            }, tools, "", session, CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, incoming, null) }, null)
        manager.configureProfiles { role }
        manager.configureCheckpoints(CrewCheckpointCoordinator(f.context, session) { role })
        manager.configureCoordinationEvidenceListener { evidence -> f.store.appendCrewCoordinationEvidence(evidence) }
        manager.configureResultEvidenceListener { evidence -> f.store.appendCrewResultEvidence(evidence) }
        manager.configureConversationInputPipeline(
            { bot, message -> WorkspaceConversationInputStore.append(f.context, session, bot, message) },
            { _, ids -> WorkspaceConversationInputStore.acknowledge(f.context, session, ids) },
            { bot, message -> WorkspaceConversationInputStore.resolve(f.context, session, bot, message) })
        return manager
    }

    private fun waitDone(bot: CrewManager.Bot) {
        val thread = Thread { bot.awaitTermination() }.apply { isDaemon = true; start() }
        thread.join(30000)
        assertFalse("Worker did not terminate", thread.isAlive)
        assertTrue(bot.isQuiescent)
    }

    private fun seeded(f: WorkspaceMemoryFixture, calls: AtomicInteger): Triple<WorkspaceRecord, CrewManager.Bot, CrewCheckpointStore.ConfirmedSnapshot> {
        val workspace = f.createProject("Itinerary")
        val manager = manager(f, workspace.executionSessionId, calls, true)
        val store = CrewCheckpointStore(f.files)
        val delegated = WorkspaceCoordinator(f.context, { manager }, {}).delegateContinuing(
            workspace.workspaceId, "trip-assignment", role.id, "Plan a trip with missing travel dates",
            listOf("ask_chief"), "Planner", "standard", "trip-request", "Plan a trip",
            CancellationToken.uncancellable(), Runnable {}, null)
        val bot = checkNotNull(manager.bot(delegated.getString("bot_id")))
        run {
            waitDone(bot)
            assertEquals(bot.error(), CrewManager.Status.PARTIAL, bot.status())
            assertEquals(1, calls.get())
            assertTrue(bot.requiresExplicitResume())
            assertTrue(f.store.listCrewResultEvidence(workspace.executionSessionId, 20, null).isEmpty())
            val saved = checkNotNull(store.confirmDurable(workspace.executionSessionId, bot.id, bot.scopeIdentity()))
            assertEquals("RESULT", saved.loop.toolLifecycle["dates-question"])
            // Process-loss simulation only: global close/STOP deliberately cancels the continuation.
            val executor = CrewManager::class.java.getDeclaredField("executor").apply { isAccessible = true }
                .get(manager) as java.util.concurrent.ExecutorService
            executor.shutdownNow()
            return Triple(workspace, bot, checkNotNull(store.confirmDurable(workspace.executionSessionId, bot.id, bot.scopeIdentity())))
        }
    }

    @Test fun generalistQuestionHasSafeCheckpointAndSameAssignmentRestoresWithoutDispatch() {
        WorkspaceMemoryFixture(storage).use { f ->
            val calls = AtomicInteger()
            val (workspace, previous, saved) = seeded(f, calls)
            val coordinator = CrewCheckpointCoordinator(f.context, workspace.executionSessionId) { role }
            manager(f, workspace.executionSessionId, calls, false, "June 4").use { restored ->
                coordinator.restore(restored, emptyList())
                val bot = checkNotNull(restored.bot(previous.id))
                assertTrue(bot.hasDurableCheckpoint())
                assertEquals(previous.assignmentId(), bot.assignmentId())
                assertEquals(previous.cycleId(), bot.cycleId())
                assertTrue(bot.canResume())
                assertEquals(1, calls.get())
                assertThrows(IllegalStateException::class.java) { restored.resumeExact(bot.id, bot.token, null) }
                assertEquals(1, calls.get())
                assertEquals(saved.payloadSha256, CrewCheckpointStore(f.files).confirmDurable(workspace.executionSessionId,
                    bot.id, bot.scopeIdentity())!!.payloadSha256)
                val assignment = WorkspaceRegistry(f.context).use { it.getAssignment(workspace.workspaceId, bot.assignmentId())!! }
                val sourceId = f.store.listCrewCoordinationEvidence(workspace.executionSessionId, 20, null).single()
                val question = f.store.readCrewCoordinationEvidence(workspace.executionSessionId, sourceId)!!
                val ref = WorkspaceCoordinationReference.create(question, WorkspaceIdentity.CENTRAL_SESSION,
                    workspace.workspaceId, assignment.assignmentId, assignment.requestId, assignment.revision)
                f.store.appendWorkspaceCoordinationReferenceIfAbsent(ref, Runnable {})
                f.store.appendWorkspaceCoordinationNoticeIfAbsent(ref, Runnable {})
                WorkspaceContinuationAnswers(f.context).retain(ref, "answer-dates", "June 4", assignment.revision,
                    CancellationToken.uncancellable(), Runnable {})
                val control = WorkspaceRegistry(f.context).use { it.nativeControls().submit(UUID.randomUUID().toString(), workspace.workspaceId,
                    assignment.assignmentId, bot.id, assignment.revision, WorkspaceControlType.RESUME, "Resume the itinerary") }
                val coordinatorAdapter = WorkspaceCoordinator(f.context, { restored }, {})
                val receipt = coordinatorAdapter.executeNativeControl(control.requestId, CancellationToken.uncancellable())
                assertTrue(receipt.contains("completed"))
                waitDone(bot)
                assertEquals(bot.error(), CrewManager.Status.DONE, bot.status())
                assertEquals(2, calls.get())
                assertNotEquals(previous.cycleId(), bot.cycleId())
                val successor = CrewCheckpointStore(f.files).confirmDurable(workspace.executionSessionId, bot.id, bot.scopeIdentity())!!
                val record = CrewCheckpointStore.continuation(successor).toJson()
                assertEquals(previous.cycleId(), record.getJSONArray("links").getJSONObject(0).getString("oldCycleId"))
                assertEquals(bot.cycleId(), record.getString("expectedCycleId"))
                val answer = record.getJSONArray("answerBindings").getJSONObject(0)
                val proof = successor.loop.incorporations[answer.getString("outboxMessageId")]
                assertNotNull("Checkpoint must prove incorporation, not merely mailbox admission", proof)
                assertEquals(bot.cycleId(), proof!!.binding.admissionCycleId)
                WorkspaceInputIncorporationDelivery(f.context).reconcile(workspace.workspaceId, CancellationToken.uncancellable())
                val incorporated = f.store.readWorkspaceInputIncorporation(WorkspaceIdentity.CENTRAL_SESSION,
                    WorkspaceInputIncorporation.SourceKind.CONTINUATION_ANSWER, proof.binding.sourceId)
                assertNotNull(incorporated)
                assertEquals(proof.binding.deliverySha256, incorporated!!.deliveredContentSha256)
                // Actual production result registration/notice/continuation path, not a test-manufactured result ref.
                val resultDelivery = WorkspaceResultDelivery(f.context)
                resultDelivery.reconcile(workspace.workspaceId, CancellationToken.uncancellable())
                val selected = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!).toJson()
                assertEquals("PREPARED", selected.getString("dispatchState"))
                assertEquals(bot.cycleId(), selected.getJSONObject("selectedResult").getString("producerCycleId"))
                assertEquals(1, selected.getJSONArray("consumedResults").length())
                assertNotNull(f.store.readConversationMessageByStableId(WorkspaceIdentity.CENTRAL_SESSION,
                    selected.getString("inputStableId")))
                resultDelivery.reconcile(workspace.workspaceId, CancellationToken.uncancellable())
                val selectedAgain = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!).toJson()
                assertEquals(selected.toString(), selectedAgain.toString())
                val oldResult = CrewResultEvidence.create(workspace.executionSessionId, bot.missionId, bot.id,
                    previous.cycleId(), "DONE", "Late old-cycle result", System.currentTimeMillis())
                f.store.appendCrewResultEvidence(oldResult)
                val oldRef = WorkspaceResultReference.create(oldResult, WorkspaceIdentity.CENTRAL_SESSION, workspace.workspaceId,
                    assignment.assignmentId, assignment.requestId, assignment.revision)
                f.store.appendWorkspaceResultReferenceIfAbsent(oldRef, Runnable {})
                val beforeOld = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!).toJson()
                WorkspaceContinuationDelivery.result(f.context, oldRef, CancellationToken.uncancellable())
                val afterOld = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!).toJson()
                if (!afterOld.isNull("selectedResult"))
                    assertEquals(bot.cycleId(), afterOld.getJSONObject("selectedResult").getString("producerCycleId"))
                assertTrue((0 until afterOld.getJSONArray("consumedResults").length()).none {
                    afterOld.getJSONArray("consumedResults").getJSONObject(it).getString("producerCycleId") == previous.cycleId()
                })
                assertTrue(coordinatorAdapter.executeNativeControl(control.requestId, CancellationToken.uncancellable()).contains("No action was repeated"))
                assertEquals(2, calls.get())
                // A global stop must fence even an already-DONE child before Central claims it.
                restored.stopAll()
                val cancelled = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!)
                assertEquals("CANCELLED", cancelled.dispatchState())
                val currentResultId = f.store.listCrewResultEvidence(workspace.executionSessionId, 20, null)
                    .first { f.store.readCrewResultEvidence(workspace.executionSessionId, it)!!.cycleId == bot.cycleId() }
                val currentResult = f.store.readCrewResultEvidence(workspace.executionSessionId, currentResultId)!!
                val staleRef = WorkspaceResultReference.create(currentResult, WorkspaceIdentity.CENTRAL_SESSION,
                    workspace.workspaceId, assignment.assignmentId, assignment.requestId, assignment.revision)
                assertThrows(IllegalStateException::class.java) {
                    WorkspaceContinuationDelivery.result(f.context, staleRef, CancellationToken.uncancellable())
                }
                val currentAssignment = WorkspaceRegistry(f.context).use {
                    it.getAssignment(workspace.workspaceId, assignment.assignmentId)!!
                }
                val currentRef = WorkspaceResultReference.create(currentResult, WorkspaceIdentity.CENTRAL_SESSION,
                    workspace.workspaceId, currentAssignment.assignmentId, currentAssignment.requestId, currentAssignment.revision)
                f.store.appendWorkspaceResultReferenceIfAbsent(currentRef, Runnable {})
                WorkspaceContinuationDelivery.result(f.context, currentRef, CancellationToken.uncancellable())
                val afterStop = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!)
                assertEquals(cancelled.toJson().toString(), afterStop.toJson().toString())
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun unresolvedAndUninspectableEffectsNeverBecomeSafeByReconciliation() {
        WorkspaceMemoryFixture(storage).use { f ->
            val (workspace, _, saved) = seeded(f, AtomicInteger())
            val coordinator = CrewCheckpointCoordinator(f.context, workspace.executionSessionId) { role }
            val store = CrewCheckpointStore(f.files)
            val metadata = saved.metadataJson().apply { remove(CentralContinuationRecord.FIELD) }
            val call = ModelReply.Call("external", "connector_send", mapOf("body" to "synthetic"))
            val transcript = listOf(ConversationTurn.toolCalls("", listOf(call)))
            val uncertain = CoreAgentLoop.Checkpoint(transcript, emptySet(), mapOf("external" to "STARTED"), -1)
            val written = store.saveConfirmed(workspace.executionSessionId, saved.botId, metadata, saved.scopeIdentity,
                uncertain, saved.artifactOwnershipJson())
            assertThrows(IllegalStateException::class.java) { coordinator.requireNativeSafePoint(written) }
            val fakeComplete = CoreAgentLoop.Checkpoint(transcript + ConversationTurn.toolResult("external", "connector_send", "ok"),
                emptySet(), mapOf("external" to "RESULT"), -1)
            val finished = store.saveConfirmed(workspace.executionSessionId, saved.botId, metadata, saved.scopeIdentity,
                fakeComplete, saved.artifactOwnershipJson())
            assertThrows(IllegalStateException::class.java) { coordinator.requireNativeSafePoint(finished) }
        }
    }

    @Test fun correctionCancelsOriginalWaitWithoutReplayingRetainedAnswer() {
        WorkspaceMemoryFixture(storage).use { f ->
            val calls = AtomicInteger()
            val (workspace, previous, _) = seeded(f, calls)
            manager(f, workspace.executionSessionId, calls, false, "Use July instead", "June 4").use { restored ->
                CrewCheckpointCoordinator(f.context, workspace.executionSessionId) { role }.restore(restored, emptyList())
                val bot = checkNotNull(restored.bot(previous.id))
                val assignment = WorkspaceRegistry(f.context).use { it.getAssignment(workspace.workspaceId, bot.assignmentId())!! }
                val sourceId = f.store.listCrewCoordinationEvidence(workspace.executionSessionId, 20, null).single()
                val question = f.store.readCrewCoordinationEvidence(workspace.executionSessionId, sourceId)!!
                val reference = WorkspaceCoordinationReference.create(question, WorkspaceIdentity.CENTRAL_SESSION,
                    workspace.workspaceId, assignment.assignmentId, assignment.requestId, assignment.revision)
                f.store.appendWorkspaceCoordinationReferenceIfAbsent(reference, Runnable {})
                f.store.appendWorkspaceCoordinationNoticeIfAbsent(reference, Runnable {})
                WorkspaceContinuationAnswers(f.context).retain(reference, "superseded-dates", "June 4", assignment.revision,
                    CancellationToken.uncancellable(), Runnable {})
                val control = WorkspaceRegistry(f.context).use { it.nativeControls().submit(UUID.randomUUID().toString(), workspace.workspaceId,
                    assignment.assignmentId, bot.id, assignment.revision, WorkspaceControlType.CORRECT, "Use July instead") }
                val result = WorkspaceCoordinator(f.context, { restored }, {}).executeNativeControl(control.requestId,
                    CancellationToken.uncancellable())
                assertTrue(result.contains("completed"))
                waitDone(bot)
                assertEquals(bot.error(), CrewManager.Status.DONE, bot.status())
                assertEquals(2, calls.get())
                assertNotEquals(previous.cycleId(), bot.cycleId())
                val saved = CrewCheckpointStore(f.files).confirmDurable(workspace.executionSessionId, bot.id, bot.scopeIdentity())!!
                val record = CrewCheckpointStore.continuation(saved).toJson()
                assertEquals("CANCELLED", record.getString("dispatchState"))
                val answer = record.getJSONArray("answerBindings").getJSONObject(0)
                assertEquals("PREPARED", answer.getString("deliveryState"))
                assertFalse(saved.loop.incorporations.containsKey(answer.getString("outboxMessageId")))
                assertTrue(record.isNull("selectedResult"))
                assertEquals(0, record.getJSONArray("consumedResults").length())
                WorkspaceResultDelivery(f.context).reconcile(workspace.workspaceId, CancellationToken.uncancellable())
                val after = CrewCheckpointStore.continuation(CrewCheckpointStore(f.files).confirmDurable(
                    workspace.executionSessionId, bot.id, bot.scopeIdentity())!!).toJson()
                assertEquals(record.toString(), after.toString())
            }
        }
    }

    @Test fun repeatedQuestionCannotReuseAnotherCallsExactReceipt() {
        WorkspaceMemoryFixture(storage).use { f ->
            val (workspace, _, saved) = seeded(f, AtomicInteger())
            val raw = saved.loopForEvidence()
            val questionResult = raw.transcript.single { it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolCallId == "dates-question" }
            val repeated = ModelReply.Call("another-question", "ask_chief", mapOf("question" to "Which travel dates?"))
            val transcript = raw.transcript + ConversationTurn.toolCalls("", listOf(repeated)) +
                ConversationTurn.toolResult(repeated.id, repeated.name, questionResult.content)
            val forged = CoreAgentLoop.Checkpoint(transcript, emptySet(), raw.toolLifecycle + (repeated.id to "RESULT"), -1)
            val metadata = saved.metadataJson().apply { remove(CentralContinuationRecord.FIELD) }
            val written = CrewCheckpointStore(f.files).saveConfirmed(workspace.executionSessionId, saved.botId,
                metadata, saved.scopeIdentity, forged, saved.artifactOwnershipJson())
            assertThrows(IllegalStateException::class.java) {
                CrewCheckpointCoordinator(f.context, workspace.executionSessionId) { role }.requireNativeSafePoint(written)
            }
        }
    }

    @Test fun changedNativeOwnershipFailsClosedWithoutProjectScopeCreation() {
        WorkspaceMemoryFixture(storage).use { f ->
            val calls = AtomicInteger()
            val (workspace, previous, saved) = seeded(f, calls)
            val bad = saved.metadataJson().apply { remove(CentralContinuationRecord.FIELD); put("assignmentId", UUID.randomUUID().toString()) }
            // Store rejects loss of coordination identity before restore can fabricate another owner.
            assertThrows(RuntimeException::class.java) {
                CrewCheckpointStore(f.files).saveConfirmed(workspace.executionSessionId, previous.id, bad,
                    saved.scopeIdentity, saved.loop, saved.artifactOwnershipJson())
            }
            assertEquals(1, calls.get())
            assertEquals(saved.payloadSha256, CrewCheckpointStore(f.files).confirmDurable(workspace.executionSessionId,
                previous.id, saved.scopeIdentity)!!.payloadSha256)
        }
    }
}

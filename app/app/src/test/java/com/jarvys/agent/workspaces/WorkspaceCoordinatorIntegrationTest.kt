package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.ModelReply
import com.jarvys.agent.WorkspaceConversationInputStore
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.crew.CrewProfile
import java.io.File
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Coordinator integration only: real registry, Crew scheduling and LocalRunStore; fake local
 * inference and keepalive. Plain Application prevents real providers, services and app startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceCoordinatorIntegrationTest {
    @Rule @JvmField val chatTestStore = ChatTestStore(registerApplicationFilesDirectory = true)

    private lateinit var context: Application
    private lateinit var coordinator: WorkspaceCoordinator
    private lateinit var managerFactory: (String) -> CrewManager
    private val managers = ConcurrentHashMap<String, CrewManager>()
    private val managerLookups = Collections.synchronizedList(ArrayList<String>())
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val keepalives = AtomicInteger()
    private val modelCalls = AtomicInteger()
    private val noAdmission = Runnable {}
    private val tools = CoreToolRegistry(emptyList())

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        CrewMode.write(context, CrewMode.AUTO)
        managerFactory = { session -> manager(session, CoreAgentLoop.Model { _, _, _, _ ->
            modelCalls.incrementAndGet()
            done()
        }) }
        coordinator = freshCoordinator()
    }

    @After fun tearDown() {
        // Release synthetic barriers before stopping workers, even after a failed assertion.
        gates.forEach { it.countDown() }
        managers.values.forEach { it.close() }
        threads.forEach { it.join(5000); if (it.isAlive) it.interrupt() }
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }

    @Test fun crewOffKeepsAssignmentPreparedAndSamePayloadCanStartAfterUserEnablesCrew() {
        val scope = workspace("crew-off")
        CrewMode.write(context, CrewMode.OFF)
        val failure = assertThrows(WorkspacePreAdmissionException::class.java) { delegate(scope) }
        assertEquals(WorkspacePreAdmissionException.Reason.CREW_DISABLED, failure.reason)
        val prepared = WorkspaceRegistry(context).use { it.listAssignments(scope.workspaceId).single() }
        assertEquals(WorkspaceAssignmentState.PREPARED, prepared.state)
        assertNull(prepared.botId)
        assertTrue(managerLookups.isEmpty())
        assertEquals(0, modelCalls.get())
        CrewMode.write(context, CrewMode.AUTO) // Simulates the user's explicit settings action.
        val admitted = delegate(scope)
        await(bot(scope, admitted))
        assertEquals(prepared.assignmentId, admitted.getString("assignment_id"))
        assertEquals(1, modelCalls.get())
    }

    @Test fun rejectedRoleRemainsPreparedAndDoesNotInventAnUncertainSpawn() {
        val scope = workspace("bad-role")
        val failure = assertThrows(WorkspacePreAdmissionException::class.java) {
            coordinator.delegate(scope.workspaceId, "bad-role", "unknown-role", MISSION, null, null,
                "standard", REQUEST_ID, ORIGINAL_REQUEST, CancellationToken.cancellable(), noAdmission)
        }
        assertEquals(WorkspacePreAdmissionException.Reason.WORKER_CONFIGURATION_REJECTED, failure.reason)
        val prepared = WorkspaceRegistry(context).use { it.listAssignments(scope.workspaceId).single() }
        assertEquals(WorkspaceAssignmentState.PREPARED, prepared.state)
        assertNull(prepared.botId)
        assertFalse(prepared.needsReconciliation)
        assertTrue(managers.getValue(scope.executionSessionId).bots().isEmpty())
        assertTrue(managers.getValue(scope.executionSessionId).missionSnapshots().isEmpty())
        assertEquals(0, modelCalls.get())
    }

    @Test fun roleRevokedAfterPreflightStaysUncertainAndSameKeyCannotReplay() {
        val available = AtomicBoolean(true)
        managerFactory = { session -> manager(session, CoreAgentLoop.Model { _, _, _, _ ->
            modelCalls.incrementAndGet(); done()
        }).also { manager ->
            manager.configureProfiles({ role -> if (role == "race-profile" && available.get())
                com.jarvys.agent.crew.CrewRole("race-profile", "Synthetic worker", "test", "Instructions", emptyList(), null)
                else null })
        } }
        val scope = workspace("post-preflight-revocation")
        val admissions = AtomicInteger()
        val revokeAtStarted = Runnable {
            // prepareAssignment, explicit preflight admission, then markStarted's beforeWrite.
            if (admissions.incrementAndGet() == 3) available.set(false)
        }
        fun attempt(admission: Runnable) = coordinator.delegate(scope.workspaceId, "race", "race-profile",
            MISSION, null, null, "standard", REQUEST_ID, ORIGINAL_REQUEST, CancellationToken.cancellable(), admission)
        val failure = assertThrows(IllegalArgumentException::class.java) { attempt(revokeAtStarted) }
        val uncertain = WorkspaceRegistry(context).use { it.listAssignments(scope.workspaceId).single() }
        assertEquals(WorkspaceAssignmentState.UNCERTAIN, uncertain.state)
        assertTrue(uncertain.needsReconciliation)
        available.set(true)
        val duplicate = attempt(noAdmission)
        assertEquals(uncertain.assignmentId, duplicate.getString("assignment_id"))
        assertEquals("UNCERTAIN", duplicate.getString("admission_state"))
        assertTrue(duplicate.getBoolean("no_replay"))
        assertTrue(managers.getValue(scope.executionSessionId).bots().isEmpty())
        assertEquals(0, modelCalls.get())
    }

    @Test fun delegatesUseDistinctExecutionSessionsAndDuplicatePayloadDoesNotSpawnAgain() {
        val firstScope = workspace("first")
        val secondScope = workspace("second")
        val first = delegate(firstScope)
        val second = delegate(secondScope)
        await(bot(firstScope, first))
        await(bot(secondScope, second))

        val reopened = freshCoordinator()
        val duplicate = delegate(firstScope, owner = reopened)
        assertEquals(first.getString("assignment_id"), duplicate.getString("assignment_id"))
        assertEquals(first.getString("bot_id"), duplicate.getString("bot_id"))
        assertEquals(first.getLong("revision"), duplicate.getLong("revision"))
        assertEquals(2, modelCalls.get())
        assertEquals(2, keepalives.get())
        assertEquals(listOf(firstScope.executionSessionId, secondScope.executionSessionId), managerLookups.toList())
        assertNotEquals(firstScope.executionSessionId, secondScope.executionSessionId)
        assertFalse(managerLookups.contains(WorkspaceIdentity.CENTRAL_SESSION))
        assertEquals(1, managers.getValue(firstScope.executionSessionId).bots().size)
        assertEquals(1, managers.getValue(secondScope.executionSessionId).bots().size)
        assertEquals(ORIGINAL_REQUEST, bot(firstScope, first).originalInstructions())
        assertNotEquals(first.getString("mission_id"), second.getString("mission_id"))

        // The coordinator reads only the actual execution session's durable ledger.
        managers.values.forEach { owner -> owner.missionSnapshots().forEach { LocalRunStore(context).appendCrewMissionSnapshot(it) } }
        val read = read(firstScope, first, reopened)
        assertEquals(first.getString("bot_id"), read.getString("bot_id"))
        assertEquals("DONE", read.getString("status"))
        assertFalse(read.getBoolean("result_verified"))
        WorkspaceRegistry(context).use { store ->
            assertTrue(store.isRead(firstScope.workspaceId, first.getString("assignment_id"), read.getString("observation_digest")))
        }
    }

    @Test fun startedWithoutBindingAfterReopenReturnsUncertaintyWithoutPreparingAnyManager() {
        val scope = workspace("interrupted")
        val started = WorkspaceRegistry(context).use { store ->
            val prepared = prepare(store, scope)
            store.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "previous-process")
        }
        val reopened = freshCoordinator()
        val duplicate = delegate(scope, owner = reopened)
        assertEquals(started.assignmentId, duplicate.getString("assignment_id"))
        assertEquals("STARTED", duplicate.getString("admission_state"))
        assertEquals("INTERRUPTED_UNCERTAIN", duplicate.getString("status"))
        assertTrue(duplicate.getBoolean("no_replay"))
        assertTrue(duplicate.isNull("bot_id"))
        assertEquals("INTERRUPTED_UNCERTAIN", read(scope, duplicate, reopened).getString("status"))
        assertTrue(managerLookups.isEmpty())
        assertTrue(managers.isEmpty())
        assertEquals(0, modelCalls.get())
        assertEquals(0, keepalives.get())
        WorkspaceRegistry(context).use { store ->
            assertEquals(WorkspaceAssignmentState.STARTED, store.getAssignment(scope.workspaceId, started.assignmentId)!!.state)
        }
    }

    @Test fun nativePauseRequiresExactTargetAndRemainsStartedUntilOnlyItsOwnedCleanupFinishes() {
        val modelsEntered = CountDownLatch(3)
        managerFactory = { session -> manager(session, blockingModel(modelsEntered)) }
        val scope = workspace("selected")
        val otherScope = workspace("unrelated")
        val selected = delegate(scope)
        val sibling = delegate(scope, key = "sibling")
        val unrelated = delegate(otherScope)
        requireLatch(modelsEntered, "Three fake workers did not start")
        val selectedBot = bot(scope, selected)
        val siblingBot = bot(scope, sibling)
        val unrelatedBot = bot(otherScope, unrelated)

        fails<IllegalStateException> {
            coordinator.submitNativeControl(scope.workspaceId, selected.getString("assignment_id"),
                selected.getLong("revision") + 1, WorkspaceControlType.PAUSE, "Pause selected")
        }
        fails<IllegalArgumentException> {
            coordinator.submitNativeControl(otherScope.workspaceId, selected.getString("assignment_id"),
                selected.getLong("revision"), WorkspaceControlType.PAUSE, "Pause selected")
        }
        assertFalse(selectedBot.token.isCancellationRequested)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = gate()
        val pending = AtomicBoolean(true)
        managers.getValue(scope.executionSessionId).registerOwnedWork(selectedBot, object : CrewManager.OwnedWork {
            override fun pending() = pending.get()
            override fun cancelAndAwait() {
                cleanupEntered.countDown()
                awaitUninterruptibly(releaseCleanup, "Owned cleanup was not released")
                pending.set(false)
            }
        })

        val control = submit(scope, selected, WorkspaceControlType.PAUSE, "Pause selected")
        assertEquals(selectedBot.id, control.botId)
        assertEquals(selected.getLong("revision"), control.assignmentRevision)
        val result = async { coordinator.executeNativeControl(control.requestId, CancellationToken.cancellable()) }
        requireLatch(cleanupEntered, "PAUSE did not enter owned cleanup")
        assertEquals(WorkspaceControlState.STARTED, savedControl(control).state)
        assertFalse("Receipt cannot complete before owned cleanup", result.isDone)
        assertTrue(pending.get())
        assertFalse(selectedBot.isQuiescent)
        assertFalse("Sibling worker was stopped", siblingBot.token.isCancellationRequested)
        assertFalse("Another workspace was stopped", unrelatedBot.token.isCancellationRequested)
        assertFalse(siblingBot.isQuiescent)
        assertFalse(unrelatedBot.isQuiescent)

        releaseCleanup.countDown()
        assertTrue(result.get(10, TimeUnit.SECONDS).contains("PAUSE: completed"))
        assertEquals(WorkspaceControlState.COMPLETE, savedControl(control).state)
        assertTrue(selectedBot.isQuiescent)
        assertFalse(pending.get())
        assertEquals("STOPPED", read(scope, selected).getString("status"))
        assertFalse(siblingBot.token.isCancellationRequested)
        assertFalse(unrelatedBot.token.isCancellationRequested)
        val revision = currentAssignment(scope, selected).revision
        assertTrue(coordinator.executeNativeControl(control.requestId, CancellationToken.cancellable()).contains("No action was repeated"))
        assertEquals(revision, currentAssignment(scope, selected).revision)
    }

    @Test fun queuedNativePauseNotifiesAfterDurableAdmissionAndObserverFailureCannotStrandDispatch() {
        val modelEntered = CountDownLatch(1)
        managerFactory = { session -> manager(session, blockingModel(modelEntered)) }
        val scope = workspace("queued-pause")
        val assignment = delegate(scope)
        val worker = bot(scope, assignment)
        requireLatch(modelEntered, "Fake worker did not start")
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = gate()
        val ownedPending = AtomicBoolean(true)
        val cleanupCalls = AtomicInteger()
        managers.getValue(scope.executionSessionId).registerOwnedWork(worker, object : CrewManager.OwnedWork {
            override fun pending() = ownedPending.get()
            override fun cancelAndAwait() {
                cleanupCalls.incrementAndGet()
                cleanupEntered.countDown()
                awaitUninterruptibly(releaseCleanup, "Queued PAUSE cleanup was not released")
                ownedPending.set(false)
            }
        })
        val admitted = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val admissionCalls = AtomicInteger()
        val completionCalls = AtomicInteger()
        val callbackAssertion = AtomicReference<Throwable>()
        val dispatchFailure = AtomicReference<Throwable>()
        val receipt = AtomicReference<String>()
        val admittedControl = AtomicReference<WorkspaceUserControl>()
        val userText = "Pause this exact worker from the central UI"

        coordinator.enqueueNativeControl(scope.workspaceId, assignment.getString("assignment_id"),
            assignment.getLong("revision"), WorkspaceControlType.PAUSE, userText,
            onAdmitted = {
                admissionCalls.incrementAndGet()
                try {
                    val control = WorkspaceRegistry(context).use { it.listControls(scope.workspaceId).single() }
                    admittedControl.set(control)
                    assertEquals(WorkspaceControlState.PENDING, control.state)
                    assertEquals(worker.id, control.botId)
                    assertEquals(assignment.getLong("revision"), control.assignmentRevision)
                    assertEquals(userText, control.userText)
                    val centralMessages = LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
                    val user = centralMessages.single { it.getString("role") == "user" }
                    assertEquals("$userText\n[Workspace ${scope.workspaceId}; assignment ${control.assignmentId}; native control ${control.requestId}]",
                        user.getString("content"))
                    assertTrue(centralMessages.none { it.getString("role") == "assistant" })
                    assertTrue("Admission notification arrived after cleanup", ownedPending.get())
                    assertEquals(0, completionCalls.get())
                } catch (failure: Throwable) { callbackAssertion.set(failure) }
                finally { admitted.countDown() }
                // A UI-observation failure must not cancel the already-durable, authorized PAUSE.
                throw IllegalStateException("Synthetic onAdmitted observer failure")
            }, completion = { result ->
                result.fold({ receipt.set(it) }, { dispatchFailure.set(it) })
                completionCalls.incrementAndGet()
                completed.countDown()
            })

        requireLatch(admitted, "Admission notification was not delivered")
        callbackAssertion.get()?.let { throw AssertionError("Admission ordering assertion failed", it) }
        requireLatch(cleanupEntered, "Observer exception stranded the durable PAUSE before dispatch")
        val control = admittedControl.get()
        assertEquals(1, admissionCalls.get())
        assertEquals(0, completionCalls.get())
        assertEquals(WorkspaceControlState.STARTED, savedControl(control).state)
        assertTrue(ownedPending.get())
        assertFalse(worker.isQuiescent)
        assertTrue(coordinator.executeNativeControl(control.requestId, CancellationToken.cancellable())
            .contains("No action was repeated"))
        assertEquals(1, cleanupCalls.get())
        assertEquals(0, completionCalls.get())

        releaseCleanup.countDown()
        requireLatch(completed, "Completion notification did not follow cleanup")
        assertNull("Observation callback must not replace the dispatch result", dispatchFailure.get())
        assertTrue(receipt.get().contains("PAUSE: completed"))
        assertEquals(1, completionCalls.get())
        assertEquals(1, admissionCalls.get())
        assertEquals(1, cleanupCalls.get())
        assertEquals(1, modelCalls.get())
        assertFalse(ownedPending.get())
        assertTrue(worker.isQuiescent)
        assertEquals(WorkspaceControlState.COMPLETE, savedControl(control).state)
        assertEquals(control.assignmentRevision + 1, currentAssignment(scope, assignment).revision)
        val centralMessages = LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        assertEquals(1, centralMessages.count { it.getString("role") == "user" })
        assertEquals(1, centralMessages.count { it.getString("role") == "assistant" && it.getString("content") == receipt.get() })
        WorkspaceRegistry(context).use { assertEquals(1, it.listControls(scope.workspaceId).size) }
    }

    @Test fun globalStopCancelsTheTokenActuallyUsedByQueuedCorrectionBeforeDispatch() {
        val scope = workspace("queued-correction-stop")
        val assignment = delegate(scope)
        val worker = bot(scope, assignment)
        await(worker)
        val originalCycle = worker.token
        val initialRevision = assignment.getLong("revision")
        val admitted = CountDownLatch(1)
        val releaseAdmission = gate()
        val completed = CountDownLatch(1)
        val controlRef = AtomicReference<WorkspaceUserControl>()
        val dispatchFailure = AtomicReference<Throwable>()
        val receipt = AtomicReference<String>()
        val completions = AtomicInteger()
        val correction = "Correction that must not start after STOP"
        assertEquals(1, modelCalls.get())

        coordinator.enqueueNativeControl(scope.workspaceId, assignment.getString("assignment_id"), initialRevision,
            WorkspaceControlType.CORRECT, correction,
            onAdmitted = {
                controlRef.set(WorkspaceRegistry(context).use { it.listControls(scope.workspaceId).single() })
                admitted.countDown()
                awaitUninterruptibly(releaseAdmission, "Controlled admission observation was not released")
            }, completion = { result ->
                result.fold({ receipt.set(it) }, { dispatchFailure.set(it) })
                completions.incrementAndGet()
                completed.countDown()
            })
        try {
            requireLatch(admitted, "Queued correction did not reach its controlled admission boundary")
            val pending = controlRef.get()
            assertEquals(WorkspaceControlState.PENDING, savedControl(pending).state)
            assertEquals(initialRevision, currentAssignment(scope, assignment).revision)
            val observedPending = read(scope, assignment).getJSONObject("pending_control")
            assertEquals(pending.requestId, observedPending.getString("request_id"))
            assertEquals("PENDING", observedPending.getString("state"))
            assertFalse(observedPending.getBoolean("dispatched"))
            assertFalse(observedPending.getBoolean("automatic_retry"))
            // Exercise the public STOP entry point, not the owner helper or a test-owned token.
            async { WorkspaceCoordinator.cancelPendingControls() }.get(10, TimeUnit.SECONDS)
            assertEquals(0, completions.get())
            releaseAdmission.countDown()
            requireLatch(completed, "Cancelled enqueue did not complete")

            assertTrue("enqueue must pass its registered token through to dispatch; found ${dispatchFailure.get()}",
                dispatchFailure.get() is java.util.concurrent.CancellationException)
            assertNull(receipt.get())
            assertEquals(1, completions.get())
            assertEquals(1, modelCalls.get())
            assertEquals(1, keepalives.get())
            assertSame("No correction cycle may have been admitted", originalCycle, worker.token)
            assertTrue(worker.isQuiescent)
            assertEquals(CrewManager.Status.DONE, worker.status())
            assertFalse(managers.getValue(scope.executionSessionId).messageBus().snapshot().any {
                it.from == "user" && it.type == CrewMessage.Type.USER && it.text == correction
            })
            val saved = savedControl(pending)
            assertEquals(WorkspaceControlState.PENDING, saved.state)
            assertEquals(1L, saved.revision)
            assertNull(saved.admittedAssignmentRevision)
            assertNull(saved.processId)
            assertEquals(initialRevision, currentAssignment(scope, assignment).revision)
            val afterStopPending = read(scope, assignment).getJSONObject("pending_control")
            assertEquals(pending.requestId, afterStopPending.getString("request_id"))
            assertEquals("PENDING", afterStopPending.getString("state"))
            assertFalse(afterStopPending.getBoolean("dispatched"))
            assertFalse(afterStopPending.getBoolean("automatic_retry"))
            val centralMessages = LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
            assertEquals(1, centralMessages.count { it.getString("role") == "user" && it.getString("content").contains(pending.requestId) })
            assertTrue("A cancelled, undispatched command must not claim success", centralMessages.none { it.getString("role") == "assistant" })
        } finally {
            releaseAdmission.countDown()
            completed.await(10, TimeUnit.SECONDS)
        }
    }

    @Test fun recoveredNativeCorrectionPersistsExactUserTextAndClaimBeforeStartingRecoveredLoop() {
        val scope = workspace("recovered")
        val target = WorkspaceRegistry(context).use { store ->
            val prepared = prepare(store, scope)
            val started = store.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "previous-process")
            store.bindBot(scope.workspaceId, started.assignmentId, started.revision, "recovered-worker")
        }
        val correction = "  Correct only this workspace.\nKeep the original evidence.  "
        val persisted = File(context.filesDir, "synthetic-correction.txt")
        val controlRef = AtomicReference<WorkspaceUserControl>()
        val assertions = AtomicReference<Throwable>()
        val owner = manager(scope.executionSessionId, CoreAgentLoop.Model { history, _, _, _ ->
            try {
                assertTrue("Correction checkpoint did not precede inference", persisted.isFile)
                assertEquals(correction, persisted.readText())
                assertTrue(history.any { it.role == "user" && it.content == correction })
                val control = savedControl(controlRef.get())
                assertTrue(control.state == WorkspaceControlState.STARTED || control.state == WorkspaceControlState.COMPLETE)
                assertEquals(correction, control.userText)
                assertEquals("recovered-worker", control.botId)
                modelCalls.incrementAndGet()
                done()
            } catch (failure: Throwable) { assertions.set(failure); throw failure }
        })
        managers[scope.executionSessionId] = owner
        val recovered = restore(owner, scope, target)
        owner.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                if (!persisted.exists() && pending.any { it.from == "user" && it.type == CrewMessage.Type.USER && it.text == correction }) {
                    val control = savedControl(controlRef.get())
                    assertEquals(WorkspaceControlState.STARTED, control.state)
                    assertEquals(target.revision + 1, control.admittedAssignmentRevision)
                    persisted.writeText(correction)
                }
            }
            override fun reconcile(bot: CrewManager.Bot) = CrewManager.ResumePlan(bot.role, "Synthetic read-only reconciliation")
        })
        assertTrue(recovered.canResume())
        val control = coordinator.submitNativeControl(scope.workspaceId, target.assignmentId, target.revision,
            WorkspaceControlType.CORRECT, correction)
        controlRef.set(control)
        assertEquals(WorkspaceControlState.PENDING, savedControl(control).state)
        coordinator.executeNativeControl(control.requestId, CancellationToken.cancellable())
        await(recovered)
        assertions.get()?.let { throw AssertionError("Recovered-loop assertion failed", it) }
        assertEquals(1, modelCalls.get())
        assertEquals(CrewManager.Status.DONE, recovered.status())
        assertEquals(WorkspaceControlState.COMPLETE, savedControl(control).state)
        assertEquals(1, owner.bots().size)
        assertEquals(1, keepalives.get())
        assertEquals(1, owner.messageBus().snapshot().count { it.from == "user" && it.type == CrewMessage.Type.USER && it.text == correction })
    }

    @Test fun explicitResumeDeliversPendingSupervisorInputAfterAssignmentRevisionAdvances() {
        val scope = workspace("resume-supervisor-outbox")
        val inputId = AtomicReference("")
        val deliveredCounts = CopyOnWriteArrayList<Int>()
        managerFactory = { session ->
            manager(session, CoreAgentLoop.Model { history, _, _, _ ->
                val id = inputId.get()
                if (id.isNotEmpty()) deliveredCounts += history.count { it.durableMessageId == id }
                done()
            }).also { configureWorkspaceInputRecovery(it, session) }
        }
        coordinator = freshCoordinator()
        val assignmentRow = delegate(scope)
        val worker = bot(scope, assignmentRow)
        await(worker)
        val manager = managers.getValue(scope.executionSessionId)
        val assignment = currentAssignment(scope, assignmentRow)
        val pending = WorkspaceConversationInputStore.appendSupervisorIntent(context,
            scope.executionSessionId, assignment, worker.id, "resume-after-revision", "Inspect the updated manifest.", null)
        inputId.set(pending.messageId)
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(context,
            scope.executionSessionId, assignment.assignmentId).any { it.messageId == pending.messageId })

        val resume = coordinator.submitNativeControl(scope.workspaceId, assignment.assignmentId,
            assignment.revision, WorkspaceControlType.RESUME, "Continue the existing assignment.")
        coordinator.executeNativeControl(resume.requestId, CancellationToken.uncancellable())
        await(worker)

        val afterResume = currentAssignment(scope, assignmentRow)
        assertEquals(assignment.revision + 1, afterResume.revision)
        assertEquals("pending supervisor text must appear in exactly one model transcript; counts=$deliveredCounts",
            1, deliveredCounts.count { it == 1 })
        assertTrue("one model turn must not duplicate the pending input; counts=$deliveredCounts",
            deliveredCounts.all { it <= 1 })
        assertTrue(worker.checkpoint().appliedIncomingIds.contains(pending.messageId))
        assertEquals("incorporated", durableInputState(scope, pending.messageId))
        assertEquals(1, manager.messageBus().snapshot().count { it.id == pending.messageId })
    }

    @Test fun correctionSupersedesOlderSupervisorMessageAndStaleTypedRowsAreResolved() {
        val scope = workspace("correct-supersedes-supervisor")
        val seenUserCorrections = CopyOnWriteArrayList<String>()
        managerFactory = { session ->
            manager(session, CoreAgentLoop.Model { history, _, _, _ ->
                history.filter { it.role == "user" }.mapTo(seenUserCorrections) { it.content }
                done()
            }).also { configureWorkspaceInputRecovery(it, session) }
        }
        coordinator = freshCoordinator()
        val assignmentRow = delegate(scope)
        val worker = bot(scope, assignmentRow)
        await(worker)
        val assignment = currentAssignment(scope, assignmentRow)
        val oldMessage = WorkspaceConversationInputStore.appendSupervisorIntent(context,
            scope.executionSessionId, assignment, worker.id, "before-correction", "Use the prior plan.", null)
        val staleAnswer = appendSupervisorInput(scope, assignment, worker.id,
            "stale-answer-${UUID.randomUUID()}", ConversationInput.Kind.ANSWER, "Old answer")
        val replacedAssignment = WorkspaceRegistry(context).use { registry ->
            registry.prepareAssignment(scope.workspaceId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "Owner request", "custom", MISSION, emptyList(), "Replacement", CrewMissionAccess.STANDARD)
        }
        val missingAssignmentTask = appendSupervisorInput(scope, assignment, worker.id,
            "replaced-task-${UUID.randomUUID()}", ConversationInput.Kind.TASK, "Replaced task",
            assignmentId = replacedAssignment.assignmentId)
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(context,
            scope.executionSessionId, assignment.assignmentId).any { it.messageId == oldMessage.messageId })

        val correctionText = "Follow the user's corrected instruction."
        val correction = coordinator.submitNativeControl(scope.workspaceId, assignment.assignmentId,
            assignment.revision, WorkspaceControlType.CORRECT, correctionText)
        coordinator.executeNativeControl(correction.requestId, CancellationToken.uncancellable())
        await(worker)

        val store = LocalRunStore(context)
        for (id in listOf(oldMessage.messageId, staleAnswer, missingAssignmentTask))
            assertEquals("stale input $id must be terminally resolved", "resolved",
                durableInputState(scope, id))
        assertTrue("native CORRECT remains in the worker transcript", seenUserCorrections.any { it == correctionText })
        assertFalse("older supervisor text must not be admitted after CORRECT",
            managers.getValue(scope.executionSessionId).messageBus().snapshot().any { it.id == oldMessage.messageId })

        val modelCallsBeforeRecovery = modelCalls.get()
        WorkspaceConversationInputStore.recoverCompletedSupervisorIntents(context,
            scope.executionSessionId, managers.getValue(scope.executionSessionId))
        assertEquals(modelCallsBeforeRecovery, modelCalls.get())
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(context,
            scope.executionSessionId).isEmpty())
    }

    @Test fun completedRecoveryResolvesSupervisorRowsOlderThanAcceptedCorrection() {
        val scope = workspace("recovery-correction-authority")
        managerFactory = { session -> manager(session, CoreAgentLoop.Model { _, _, _, _ -> done() })
            .also { configureWorkspaceInputRecovery(it, session) } }
        coordinator = freshCoordinator()
        val assignmentRow = delegate(scope)
        val worker = bot(scope, assignmentRow)
        await(worker)
        val assignment = currentAssignment(scope, assignmentRow)
        val oldMessage = WorkspaceConversationInputStore.appendSupervisorIntent(context,
            scope.executionSessionId, assignment, worker.id, "before-accepted-correction", "Use the older plan.", null)
        val oldAnswer = appendSupervisorInput(scope, assignment, worker.id,
            "old-answer-${UUID.randomUUID()}", ConversationInput.Kind.ANSWER, "Older answer")
        val oldTask = appendSupervisorInput(scope, assignment, worker.id,
            "old-task-${UUID.randomUUID()}", ConversationInput.Kind.TASK, "Older task")

        val correction = coordinator.submitNativeControl(scope.workspaceId, assignment.assignmentId,
            assignment.revision, WorkspaceControlType.CORRECT, "Apply the newer user correction.")
        val started = WorkspaceRegistry(context).use { registry ->
            registry.markControlStarted(correction.requestId, correction.revision, "recovery-correction-test")
        }
        WorkspaceRegistry(context).use { registry -> registry.completeControl(started.requestId, started.revision) }

        val persistedCorrection = WorkspaceRegistry(context).use {
            it.getLatestCorrection(scope.workspaceId, assignment.assignmentId)
        }
        assertNotNull("accepted native correction must be recorded", persistedCorrection)
        assertEquals(assignment.revision + 1, persistedCorrection!!.admittedAssignmentRevision)
        assertTrue("stale inputs must exist in the pending outbox before recovery",
            LocalRunStore(context).readPendingInternalConversationInputs(scope.executionSessionId)
                .any { it.optJSONObject("provenance")?.optString("messageId") == oldMessage.messageId })
        assertFalse("old supervisor messages must be filtered by the accepted correction",
            WorkspaceConversationInputStore.pendingSupervisorIntents(context,
                scope.executionSessionId).any { it.messageId == oldMessage.messageId })

        val callsBeforeRecovery = modelCalls.get()
        WorkspaceConversationInputStore.recoverCompletedSupervisorIntents(context,
            scope.executionSessionId, managers.getValue(scope.executionSessionId))

        val store = LocalRunStore(context)
        for (id in listOf(oldMessage.messageId, oldAnswer, oldTask))
            assertEquals("recovery must resolve supervisor input superseded by CORRECT", "resolved",
                durableInputState(scope, id))
        assertEquals("stale recovery must not start another model turn", callsBeforeRecovery, modelCalls.get())
        assertFalse(managers.getValue(scope.executionSessionId).messageBus().snapshot().any { it.id == oldMessage.messageId })
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(context,
            scope.executionSessionId).isEmpty())
    }

    @Test fun supervisorRecoveryLeavesPeerAndSubordinateWorkerInputsPending() {
        val scope = workspace("worker-input-recovery-scope")
        val prepared = WorkspaceRegistry(context).use { prepare(it, scope) }
        val started = WorkspaceRegistry(context).use {
            it.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "worker-input-filter")
        }
        val botId = "recipient-${UUID.randomUUID()}"
        val assignment = WorkspaceRegistry(context).use {
            it.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, botId)
        }
        val store = LocalRunStore(context)
        val workerRows = listOf(
            ConversationInput.Provenance.PrincipalRole.PEER,
            ConversationInput.Provenance.PrincipalRole.SUBORDINATE,
        ).mapIndexed { index, principal ->
            val messageId = "worker-input-${UUID.randomUUID()}"
            val provenance = ConversationInput.Provenance(scope.executionSessionId, scope.workspaceId,
                assignment.assignmentId, "sender-$index", messageId, ConversationInput.Kind.MESSAGE, principal,
                "", "", "", assignment.revision, assignment.requestId, assignment.missionId, "")
            val rowId = store.appendConversationInput(ConversationInput(scope.executionSessionId, messageId,
                "Worker message $index", ConversationInput.Origin.AGENT, provenance))
            messageId to rowId
        }
        val staleSupervisorId = appendSupervisorInput(scope, assignment, botId,
            "stale-supervisor-${UUID.randomUUID()}", ConversationInput.Kind.MESSAGE,
            "Obsolete central supervisor message", assignmentId = UUID.randomUUID().toString())

        for ((messageId, rowId) in workerRows) {
            assertNull("$messageId starts pending in the worker transcript",
                store.readConversationInputState(scope.executionSessionId, rowId))
        }
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(context,
            scope.executionSessionId).isEmpty())
        for ((messageId, rowId) in workerRows) {
            assertNull("$messageId must remain pending after the supervisor filter",
                store.readConversationInputState(scope.executionSessionId, rowId))
        }
        val staleRowId = requireNotNull(store.readConversationMessageByStableId(scope.executionSessionId,
            ConversationInput.stableId(ConversationInput.Origin.AGENT, WorkspaceIdentity.CENTRAL_SESSION,
                staleSupervisorId))).getString("messageId")
        assertEquals("resolved", store.readConversationInputState(scope.executionSessionId, staleRowId))

        val crewManager = managers.computeIfAbsent(scope.executionSessionId) {
            manager(it, CoreAgentLoop.Model { _, _, _, _ -> done() })
        }
        WorkspaceConversationInputStore.recoverCompletedSupervisorIntents(context,
            scope.executionSessionId, crewManager)
        for ((messageId, rowId) in workerRows) {
            assertNull("$messageId must remain pending after completed-intent recovery",
                store.readConversationInputState(scope.executionSessionId, rowId))
        }
        assertEquals("the obsolete Central supervisor intent remains terminally resolved", "resolved",
            store.readConversationInputState(scope.executionSessionId, staleRowId))
    }

    @Test fun lateCentralStopCannotCancelNativeCorrectionCycleWhileOriginalCleanupRegistrationStillExists() {
        val firstCleanup = CountDownLatch(1)
        val releaseCleanup = gate()
        val nextTurn = CountDownLatch(1)
        val releaseNextTurn = gate()
        val ended = AtomicInteger()
        managerFactory = { session -> manager(session, CoreAgentLoop.Model { _, _, _, token ->
            if (modelCalls.incrementAndGet() == 2) {
                nextTurn.countDown()
                awaitModelGate(releaseNextTurn, token)
            }
            done()
        }, object : CrewManager.WorkerLifecycle {
            override fun start(bot: CrewManager.Bot) {}
            override fun end(bot: CrewManager.Bot) {
                if (ended.incrementAndGet() == 1) {
                    firstCleanup.countDown()
                    awaitUninterruptibly(releaseCleanup, "First-cycle lifecycle cleanup was not released")
                }
            }
        }) }
        val centralToken = CancellationToken.cancellable()
        val scope = workspace("late-stop")
        val assignment = delegate(scope, token = centralToken)
        val worker = bot(scope, assignment)
        requireLatch(firstCleanup, "First cycle did not reach terminal cleanup")
        val originalCycle = worker.token
        assertFalse(worker.isQuiescent)
        val control = submit(scope, assignment, WorkspaceControlType.CORRECT, "Now apply this correction")
        coordinator.executeNativeControl(control.requestId, CancellationToken.cancellable())
        releaseCleanup.countDown()
        requireLatch(nextTurn, "Native correction did not start a distinct cycle")
        assertNotSame(originalCycle, worker.token)
        assertTrue(centralToken.cancel())
        assertFalse("A stale central STOP cancelled the correction cycle", worker.token.isCancellationRequested)
        assertFalse(worker.isQuiescent)
        releaseNextTurn.countDown()
        await(worker)
        assertEquals(CrewManager.Status.DONE, worker.status())
        assertEquals(2, modelCalls.get())
        assertEquals(WorkspaceControlState.COMPLETE, savedControl(control).state)
    }

    @Test fun failedDurableControlSnapshotRemainsUncertainAndFreshPauseSafelyStopsTheExactWorker() {
        val correctionEntered = CountDownLatch(1)
        val failSnapshot = AtomicBoolean(false)
        managerFactory = { session -> manager(session, CoreAgentLoop.Model { _, _, _, token ->
            if (modelCalls.incrementAndGet() == 2) {
                correctionEntered.countDown()
                awaitModelGate(gate(), token)
            }
            done()
        }) }
        val scope = workspace("snapshot-failure")
        val assignment = delegate(scope)
        val worker = bot(scope, assignment)
        await(worker)
        val local = LocalRunStore(context)
        local.appendCrewMissionSnapshot(managers.getValue(scope.executionSessionId).missionSnapshots().single())
        coordinator = freshCoordinator { target, snapshot ->
            if (failSnapshot.get()) throw IllegalStateException("Synthetic durable snapshot failure")
            LocalRunStore(target).appendCrewMissionSnapshot(snapshot)
        }
        val correction = submit(scope, assignment, WorkspaceControlType.CORRECT, "Continue selected worker")
        failSnapshot.set(true)
        try {
            fails<IllegalStateException> {
                coordinator.executeNativeControl(correction.requestId, CancellationToken.cancellable())
            }
        } finally {
            failSnapshot.set(false)
        }
        requireLatch(correctionEntered, "Admitted correction did not begin")
        assertEquals(WorkspaceControlState.UNCERTAIN, savedControl(correction).state)
        assertFalse(worker.isQuiescent)
        val reopened = freshCoordinator()
        val uncertain = read(scope, assignment, reopened)
        assertEquals("CONTROL_UNCERTAIN", uncertain.getString("status"))
        assertEquals("UNCERTAIN", uncertain.getString("control_state"))
        assertTrue(uncertain.getBoolean("result_is_prior_evidence"))
        assertEquals("Synthetic verified test result", uncertain.getString("result_excerpt"))
        assertFalse(uncertain.getBoolean("result_verified"))
        assertTrue(reopened.executeNativeControl(correction.requestId, CancellationToken.cancellable()).contains("No action was repeated"))
        assertEquals(2, modelCalls.get())

        val current = currentAssignment(scope, assignment)
        val pause = reopened.submitNativeControl(scope.workspaceId, current.assignmentId, current.revision,
            WorkspaceControlType.PAUSE, "Stop this worker safely")
        reopened.executeNativeControl(pause.requestId, CancellationToken.cancellable())
        assertTrue(worker.isQuiescent)
        assertTrue(worker.token.isCancellationRequested)
        assertEquals(CrewManager.Status.STOPPED, worker.status())
        assertEquals(WorkspaceControlState.COMPLETE, savedControl(pause).state)
        assertEquals(WorkspaceControlState.UNCERTAIN, savedControl(correction).state)
        assertEquals("CONTROL_UNCERTAIN", read(scope, assignment, reopened).getString("status"))
        assertEquals(2, modelCalls.get())
    }

    private fun freshCoordinator(
        persistMissionSnapshot: ((android.content.Context, CrewMissionSnapshot) -> Unit)? = null,
    ) = WorkspaceCoordinator(context, { session ->
        managerLookups.add(session)
        managers.computeIfAbsent(session) { managerFactory(it) }
    }, { keepalives.incrementAndGet(); Unit }, persistMissionSnapshot ?: { target, snapshot ->
        LocalRunStore(target).appendCrewMissionSnapshot(snapshot)
    })

    private fun workspace(key: String): WorkspaceRecord {
        val id = coordinator.create(key, "Workspace $key", "create-$key", "Create synthetic workspace", noAdmission)
            .getString("workspace_id")
        return WorkspaceRegistry(context).use { it.getWorkspace(id)!! }
    }

    private fun delegate(scope: WorkspaceRecord, key: String = "assignment", token: CancellationToken = CancellationToken.cancellable(),
                         owner: WorkspaceCoordinator = coordinator): JSONObject = owner.delegate(scope.workspaceId, key,
        "custom", MISSION, emptyList(), "Synthetic worker", "standard", REQUEST_ID, ORIGINAL_REQUEST, token, noAdmission)

    private fun prepare(store: WorkspaceRegistry, scope: WorkspaceRecord) = store.prepareAssignment(scope.workspaceId,
        "assignment", REQUEST_ID, ORIGINAL_REQUEST, "custom", MISSION, emptyList(), "Synthetic worker", CrewMissionAccess.STANDARD)

    private fun currentAssignment(scope: WorkspaceRecord, row: JSONObject) = WorkspaceRegistry(context).use {
        it.getAssignment(scope.workspaceId, row.getString("assignment_id"))!!
    }

    private fun bot(scope: WorkspaceRecord, row: JSONObject) = managers.getValue(scope.executionSessionId).bot(row.getString("bot_id"))!!

    private fun read(scope: WorkspaceRecord, row: JSONObject, owner: WorkspaceCoordinator = coordinator) =
        owner.read(scope.workspaceId, row.getString("assignment_id"), 10, null, noAdmission).getJSONArray("assignments").getJSONObject(0)

    private fun submit(scope: WorkspaceRecord, row: JSONObject, type: WorkspaceControlType, text: String) =
        coordinator.submitNativeControl(scope.workspaceId, row.getString("assignment_id"), row.getLong("revision"), type, text)

    private fun savedControl(control: WorkspaceUserControl) = WorkspaceRegistry(context).use { it.getControl(control.requestId)!! }

    private fun manager(session: String, model: CoreAgentLoop.Model, lifecycle: CrewManager.WorkerLifecycle? = null): CrewManager {
        val factory = CrewManager.LoopFactory { _, registry, inbox -> CoreAgentLoop(model, registry, "", session,
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null) }
        val store = LocalRunStore(context)
        return CrewManager(session, tools, { _, _ -> tools }, factory, null).also {
            it.configureResultEvidenceListener { evidence -> store.appendCrewResultEvidence(evidence) }
            if (lifecycle != null) it.configure(tools, { _, _ -> tools }, factory, lifecycle)
        }
    }

    private fun configureWorkspaceInputRecovery(manager: CrewManager, session: String) {
        manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun canPersist(bot: CrewManager.Bot) = true
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) = Unit
            override fun reconcile(bot: CrewManager.Bot) = CrewManager.ResumePlan(bot.role, "Robolectric checkpoint reconciliation")
        })
        manager.configureConversationInputPipeline(
            { bot, message -> WorkspaceConversationInputStore.append(context, session, bot, message) },
            { _, ids -> WorkspaceConversationInputStore.acknowledge(context, session, ids) },
        )
    }

    private fun appendSupervisorInput(scope: WorkspaceRecord, assignment: WorkspaceAssignment, botId: String,
                                      messageId: String, kind: ConversationInput.Kind, text: String,
                                      assignmentId: String = assignment.assignmentId): String {
        val provenance = ConversationInput.Provenance(WorkspaceIdentity.CENTRAL_SESSION, scope.workspaceId,
            assignmentId, botId, messageId, kind,
            ConversationInput.Provenance.PrincipalRole.SUPERVISOR, "", "", "",
            assignment.revision, assignment.requestId, assignment.missionId, "")
        LocalRunStore(context).appendConversationInput(ConversationInput(scope.executionSessionId,
            messageId, text, ConversationInput.Origin.AGENT, provenance))
        return messageId
    }

    private fun durableInputState(scope: WorkspaceRecord, sourceMessageId: String): String? {
        val store = LocalRunStore(context)
        val stableId = ConversationInput.stableId(ConversationInput.Origin.AGENT,
            WorkspaceIdentity.CENTRAL_SESSION, sourceMessageId)
        val row = requireNotNull(store.readConversationMessageByStableId(scope.executionSessionId, stableId))
        return store.readConversationInputState(scope.executionSessionId, row.getString("messageId"))
    }

    private fun restore(owner: CrewManager, scope: WorkspaceRecord, target: WorkspaceAssignment): CrewManager.Bot {
        val profile = CrewProfile("coding", 1, "Coding", "Synthetic project work", "Synthetic mission", emptyList(),
            emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
        val saved = CrewBotSnapshot(target.botId, "coding", "Coding", "Recovered worker", "coding", MISSION,
            "INTERRUPTED", "", "", "", emptyList(), 1L, 0L)
        val mission = CrewMissionSnapshot(target.missionId, scope.executionSessionId, "previous-process", "Recovery",
            ORIGINAL_REQUEST, "legacy", "INTERRUPTED", "", 1L, 0L, listOf(saved), emptyList())
        return owner.restoreBot(mission, saved, profile.resolveRole(emptyList(), emptyList()), CoreAgentLoop.Checkpoint.empty(),
            JSONObject(), "synthetic-scope", emptyList(), 1L, emptyList(), emptyList(), "")
    }

    private fun blockingModel(entered: CountDownLatch) = CoreAgentLoop.Model { _, _, _, token ->
        modelCalls.incrementAndGet()
        entered.countDown()
        awaitModelGate(gate(), token)
        done()
    }

    private fun gate() = CountDownLatch(1).also { gates.add(it) }
    private fun done() = ModelReply("Synthetic verified test result", emptyList())
    private fun requireLatch(latch: CountDownLatch, message: String) = assertTrue(message, latch.await(10, TimeUnit.SECONDS))

    private fun awaitModelGate(latch: CountDownLatch, token: CancellationToken) {
        val unregister = token.registerCancelAction { latch.countDown() }
        try {
            try { requireLatch(latch, "Synthetic inference was not released") }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled(); throw interrupted }
            token.throwIfCancelled()
        } finally { unregister.run() }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch, message: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var interrupted = false
        try {
            while (latch.count != 0L) {
                val left = deadline - System.nanoTime()
                assertTrue(message, left > 0)
                try { assertTrue(message, latch.await(left, TimeUnit.NANOSECONDS)) }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun <T> async(block: () -> T): FutureTask<T> = FutureTask<T> { block() }.also { future ->
        Thread(future, "workspace-coordinator-integration-test").apply { isDaemon = true; threads.add(this); start() }
    }

    private fun await(bot: CrewManager.Bot) { async { bot.awaitTermination() }.get(10, TimeUnit.SECONDS) }

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, found $failure", failure is T)
        return failure as T
    }

    companion object {
        private const val REQUEST_ID = "synthetic-request"
        private const val ORIGINAL_REQUEST = "  Exact original request.\nPreserve its whitespace.  "
        private const val MISSION = "Perform this synthetic local assignment"
    }
}

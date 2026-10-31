package com.jarvys.agent.workspaces

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.jarvys.agent.crew.CrewMissionAccess
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Synthetic local metadata only; plain Application avoids real providers and background startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [com.jarvys.agent.ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceRegistryTest {
    private lateinit var context: Application
    private lateinit var registry: WorkspaceRegistry
    private val opened = ArrayList<WorkspaceRegistry>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        registry = open()
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }

    private fun open() = WorkspaceRegistry(context).also { opened.add(it) }
    private fun workspace(key: String = "synthetic-key") = registry.createWorkspace(key, "Synthetic workspace")
    private fun prepare(
        workspace: WorkspaceRecord = workspace(), key: String = "assignment-key", request: String = "request-key",
        user: String = "Synthetic user task", role: String = "coding", mission: String = "Synthetic mission",
        tools: List<String>? = listOf("read", "ls"), name: String? = "Synthetic worker",
        access: CrewMissionAccess = CrewMissionAccess.STANDARD, store: WorkspaceRegistry = registry,
    ) = store.prepareAssignment(workspace.workspaceId, key, request, user, role, mission, tools, name, access)

    private fun bound(workspace: WorkspaceRecord = workspace()): WorkspaceAssignment {
        val prepared = prepare(workspace)
        val started = registry.markStarted(workspace.workspaceId, prepared.assignmentId, prepared.revision, "process-1")
        return registry.bindBot(workspace.workspaceId, prepared.assignmentId, started.revision, "bot-synthetic")
    }

    private fun control(target: WorkspaceAssignment, request: String = UUID.randomUUID().toString(),
                        type: WorkspaceControlType = WorkspaceControlType.PAUSE, user: String = "") =
        registry.nativeControls().submit(request, target.workspaceId, target.assignmentId, requireNotNull(target.botId),
            target.revision, type, user)

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, found $failure", failure is T)
        return failure as T
    }

    private fun mutate(sql: String, arguments: Array<Any>) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,
            null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql, arguments) }
    }

    @Test fun workspaceCanonicalKeyDeduplicatesAcrossReopenAndRejectsConflicts() {
        var admissions = 0
        val first = registry.createWorkspace("create-1", "  Synthetic workspace  ", Runnable { admissions++ })
        val again = open().createWorkspace("create-1", "Synthetic workspace", Runnable { admissions++ })
        assertEquals(first, again)
        assertEquals(1, admissions)
        fails<WorkspaceIdempotencyConflictException> { registry.createWorkspace("create-1", "Different") }
        assertEquals(listOf(first), registry.listWorkspaces())
    }

    @Test fun workspaceIdentityIsDistinctFromCentralLegacyAndOtherScopes() {
        val first = workspace("first")
        val second = workspace("second")
        assertNotEquals(first.workspaceId, second.workspaceId)
        assertEquals("jarvys-central", first.ownerCentralSessionId)
        assertEquals("jarvys-workspace-" + first.workspaceId, first.executionSessionId)
        assertNotEquals(first.executionSessionId, second.executionSessionId)
        assertNotEquals(first.ownerCentralSessionId, first.executionSessionId)
        assertTrue(WorkspaceIdentity.isExecutionSession(first.executionSessionId))
        assertNull(registry.getWorkspace(UUID.randomUUID().toString()))
        fails<IllegalArgumentException> { registry.getWorkspace("jarvys-central") }
        fails<IllegalArgumentException> { registry.getWorkspace("legacy-chat") }
        fails<IllegalArgumentException> { registry.getWorkspace("../../escape") }
    }

    @Test fun listingIsBoundedAndUsesValidatedStableCursors() {
        repeat(5) { workspace("workspace-$it") }
        val all = registry.listWorkspaces(100)
        val first = registry.listWorkspaces(2)
        val second = registry.listWorkspaces(2, first.last().workspaceId)
        val third = registry.listWorkspaces(2, second.last().workspaceId)
        assertEquals(all, first + second + third)
        assertEquals(5, (first + second + third).map { it.workspaceId }.distinct().size)
        fails<IllegalArgumentException> { registry.listWorkspaces(0) }
        fails<IllegalArgumentException> { registry.listWorkspaces(101) }
        fails<IllegalArgumentException> { registry.listWorkspaces(5, "' OR 1=1 --") }
        fails<UnsupportedOperationException> { (first as MutableList<WorkspaceRecord>).clear() }
    }

    @Test fun prepareCanonicalPayloadIsIdempotentAndToolsAreImmutable() {
        val scope = workspace()
        val input = arrayListOf("read", "ls", "read")
        val first = prepare(scope, tools = input)
        input.clear()
        val duplicate = prepare(scope, tools = listOf("ls", "read"))
        assertEquals(first.assignmentId, duplicate.assignmentId)
        assertEquals(first.missionId, duplicate.missionId)
        assertEquals(1L, first.revision)
        assertEquals(WorkspaceAssignmentState.PREPARED, first.state)
        assertEquals(listOf("ls", "read"), first.tools)
        fails<UnsupportedOperationException> { (first.tools as MutableList<String>).add("write") }
        assertEquals(1, registry.listAssignments(scope.workspaceId).size)
    }

    @Test fun prepareConflictsCoverEveryPayloadFieldAndNullIsNotEmpty() {
        val scope = workspace()
        prepare(scope)
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, request = "other-request") }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, user = "Changed request") }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, role = "research") }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, mission = "Changed mission") }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, tools = null) }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, name = null) }
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, access = CrewMissionAccess.READ_ONLY) }
        prepare(scope, key = "null-tools", tools = null)
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, key = "null-tools", tools = emptyList()) }
    }

    @Test fun requestMissionIsStableAcrossAssignmentsAndDistinctAcrossScopesAndRequests() {
        val scope = workspace()
        val first = prepare(scope, key = "first")
        val second = prepare(scope, key = "second", role = "research", mission = "Second task")
        val otherRequest = prepare(scope, key = "third", request = "other-request")
        val otherScope = prepare(workspace("other-scope"), key = "first")
        assertNotEquals(first.assignmentId, second.assignmentId)
        assertEquals(first.missionId, second.missionId)
        assertNotEquals(first.missionId, otherRequest.missionId)
        assertNotEquals(first.missionId, otherScope.missionId)
        fails<WorkspaceIdempotencyConflictException> { prepare(scope, key = "fourth", user = "Changed causal request") }
        assertEquals(3, registry.listAssignments(scope.workspaceId).size)
    }

    @Test fun concurrentInstancesPrepareOneCanonicalAssignmentWithoutLostRows() {
        val scope = workspace()
        val stores = List(4) { open() }
        // Open helpers before racing transactions, so initialization and contention are tested separately.
        stores.forEach { assertNotNull(it.getWorkspace(scope.workspaceId)) }
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(stores.size)
        try {
            val futures = stores.map { store -> pool.submit(Callable {
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                prepare(scope, store = store)
            }) }
            gate.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.assignmentId }.distinct().size)
            assertEquals(1, results.map { it.missionId }.distinct().size)
            assertEquals(1, registry.listAssignments(scope.workspaceId).size)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun concurrentDifferentAssignmentsPreserveOneRequestMission() {
        val scope = workspace()
        val stores = List(4) { open() }
        stores.forEach { it.getWorkspace(scope.workspaceId) }
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = stores.mapIndexed { index, store -> pool.submit(Callable {
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                prepare(scope, key = "assignment-$index", store = store)
            }) }
            gate.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(4, results.map { it.assignmentId }.distinct().size)
            assertEquals(1, results.map { it.missionId }.distinct().size)
            assertEquals(4, registry.listAssignments(scope.workspaceId).size)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun startedSurvivesReopenAsUncertainWithoutExecutionOrAutomaticWrites() {
        val prepared = prepare()
        var admissions = 0
        val started = registry.markStarted(prepared.workspaceId, prepared.assignmentId, 1, "process-old", Runnable { admissions++ })
        registry.close()
        val reopened = open()
        val restored = requireNotNull(reopened.getAssignment(prepared.workspaceId, prepared.assignmentId))
        assertEquals(WorkspaceAssignmentState.STARTED, restored.state)
        assertEquals(started.revision, restored.revision)
        assertEquals("process-old", restored.processId)
        assertTrue(restored.needsReconciliation)
        assertNull(restored.botId)
        assertEquals(1, admissions)
        fails<IllegalStateException> { reopened.markStarted(restored.workspaceId, restored.assignmentId, restored.revision, "process-new") }
        assertEquals(restored.revision, reopened.getAssignment(restored.workspaceId, restored.assignmentId)!!.revision)
    }

    @Test fun staleRevisionFailsBeforeCallbackAndUncertainCannotBeBoundOrReplayed() {
        val prepared = prepare()
        val started = registry.markStarted(prepared.workspaceId, prepared.assignmentId, 1, "process")
        var called = false
        fails<StaleWorkspaceRevisionException> {
            registry.bindBot(started.workspaceId, started.assignmentId, 1, "bot", Runnable { called = true })
        }
        assertFalse(called)
        val uncertain = registry.markUncertain(started.workspaceId, started.assignmentId, started.revision)
        assertEquals(WorkspaceAssignmentState.UNCERTAIN, uncertain.state)
        assertTrue(uncertain.needsReconciliation)
        fails<IllegalStateException> { registry.bindBot(uncertain.workspaceId, uncertain.assignmentId, uncertain.revision, "bot") }
        fails<IllegalStateException> { registry.markStarted(uncertain.workspaceId, uncertain.assignmentId, uncertain.revision, "process-new") }
    }

    @Test fun assignmentMutationCannotCrossWorkspaceAndPagingDoesNotLeakOtherRows() {
        val firstScope = workspace("first")
        val secondScope = workspace("second")
        repeat(3) { prepare(firstScope, key = "assignment-$it") }
        val second = prepare(secondScope)
        assertNull(registry.getAssignment(firstScope.workspaceId, second.assignmentId))
        fails<IllegalArgumentException> { registry.markStarted(firstScope.workspaceId, second.assignmentId, 1, "process") }
        val first = registry.listAssignments(firstScope.workspaceId, 1)
        val rest = registry.listAssignments(firstScope.workspaceId, 100, first.single().assignmentId)
        assertEquals(3, (first + rest).size)
        assertTrue((first + rest).all { it.workspaceId == firstScope.workspaceId })
    }

    @Test fun policyCallbackDenialRollsBackEveryPreparationAndStart() {
        fails<SecurityException> { registry.createWorkspace("denied", "Denied", Runnable { throw SecurityException("STOP") }) }
        assertTrue(registry.listWorkspaces().isEmpty())
        val scope = workspace()
        fails<SecurityException> {
            registry.prepareAssignment(scope.workspaceId, "key", "request", "Synthetic request", "coding", "Mission",
                null, null, CrewMissionAccess.STANDARD, Runnable { throw SecurityException("STOP") })
        }
        assertTrue(registry.listAssignments(scope.workspaceId).isEmpty())
        val prepared = prepare(scope)
        fails<SecurityException> {
            registry.markStarted(scope.workspaceId, prepared.assignmentId, 1, "process", Runnable { throw SecurityException("STOP") })
        }
        assertEquals(WorkspaceAssignmentState.PREPARED, registry.getAssignment(scope.workspaceId, prepared.assignmentId)!!.state)
    }

    @Test fun controlsRequireBoundExactTargetNativeOriginAndOriginalText() {
        val prepared = prepare()
        val request = UUID.randomUUID().toString()
        fails<IllegalStateException> {
            registry.nativeControls().submit(request, prepared.workspaceId, prepared.assignmentId, "bot-synthetic", 1,
                WorkspaceControlType.CORRECT, "Preserve this exact text\nincluding spacing.")
        }
        val target = bound()
        fails<IllegalStateException> {
            registry.nativeControls().submit(request, target.workspaceId, target.assignmentId, "other-bot", target.revision,
                WorkspaceControlType.CORRECT, "Correction")
        }
        fails<StaleWorkspaceRevisionException> {
            registry.nativeControls().submit(request, target.workspaceId, target.assignmentId, target.botId!!, 1,
                WorkspaceControlType.CORRECT, "Correction")
        }
        val text = "  Preserve this exact text\nincluding spacing.  "
        val result = control(target, request, WorkspaceControlType.CORRECT, text)
        assertEquals(text, result.userText)
        assertEquals("NATIVE_UI", result.origin)
        assertEquals(WorkspaceControlState.PENDING, result.state)
        assertNull(result.admittedAssignmentRevision)
        assertEquals(target.revision, result.assignmentRevision)
        assertEquals(result, control(target, request, WorkspaceControlType.CORRECT, text))
        fails<WorkspaceIdempotencyConflictException> { control(target, request, WorkspaceControlType.PAUSE, text) }
        fails<WorkspaceIdempotencyConflictException> { control(target, request, WorkspaceControlType.CORRECT, "Changed") }
    }

    @Test fun startingControlAtomicallyClaimsRevisionAndInvalidatesSiblingPendingControls() {
        val target = bound()
        val pause = control(target)
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Correction")
        val started = registry.markControlStarted(pause.requestId, 1, "process")
        assertEquals(target.revision + 1, started.admittedAssignmentRevision)
        assertEquals(started.admittedAssignmentRevision, registry.getAssignment(target.workspaceId, target.assignmentId)!!.revision)
        fails<StaleWorkspaceRevisionException> { registry.markControlStarted(correction.requestId, 1, "process") }
        val finished = registry.completeControl(pause.requestId, started.revision)
        assertEquals(WorkspaceControlState.COMPLETE, finished.state)
        fails<IllegalStateException> { registry.markControlStarted(finished.requestId, finished.revision, "process") }
        val fresh = requireNotNull(registry.getAssignment(target.workspaceId, target.assignmentId))
        val resume = control(fresh, type = WorkspaceControlType.RESUME)
        val resumed = registry.markControlStarted(resume.requestId, 1, "process")
        assertEquals(fresh.revision + 1, resumed.admittedAssignmentRevision)
        registry.completeControl(resume.requestId, resumed.revision)
        assertEquals(pause.requestId, control(target, pause.requestId).requestId) // Original request is still idempotent.
    }

    @Test fun concurrentControlsCannotBothStartForTheSameTargetRevision() {
        val target = bound()
        val controls = listOf(control(target), control(target, type = WorkspaceControlType.RESUME))
        val stores = List(2) { open() }
        stores.forEach { it.getWorkspace(target.workspaceId) }
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = controls.mapIndexed { index, pending -> pool.submit(Callable {
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                runCatching { stores[index].markControlStarted(pending.requestId, pending.revision, "process-$index") }
            }) }
            gate.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is StaleWorkspaceRevisionException)
            assertEquals(target.revision + 1, registry.getAssignment(target.workspaceId, target.assignmentId)!!.revision)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun controlAdmissionFailureDoesNotConsumeAssignmentRevision() {
        val target = bound()
        val pending = control(target)
        fails<SecurityException> {
            registry.markControlStarted(pending.requestId, pending.revision, "process", Runnable { throw SecurityException("STOP") })
        }
        assertEquals(target.revision, registry.getAssignment(target.workspaceId, target.assignmentId)!!.revision)
        assertEquals(WorkspaceControlState.PENDING, registry.getControl(pending.requestId)!!.state)
    }

    @Test fun startedControlsSurviveReopenAndUncertainControlBlocksNewEffects() {
        val target = bound()
        val pending = control(target)
        val started = registry.markControlStarted(pending.requestId, 1, "process-old")
        registry.close()
        val reopened = open()
        assertEquals(started, reopened.getControl(started.requestId))
        assertTrue(reopened.getControl(started.requestId)!!.needsReconciliation)
        val uncertain = reopened.markControlUncertain(started.requestId, started.revision)
        assertTrue(uncertain.needsReconciliation)
        fails<IllegalStateException> { reopened.completeControl(uncertain.requestId, uncertain.revision) }
        fails<IllegalStateException> { reopened.markControlStarted(uncertain.requestId, uncertain.revision, "process-new") }
        val freshTarget = reopened.getAssignment(target.workspaceId, target.assignmentId)!!
        val other = reopened.nativeControls().submit(UUID.randomUUID().toString(), target.workspaceId, target.assignmentId,
            target.botId!!, freshTarget.revision, WorkspaceControlType.RESUME, "")
        fails<IllegalStateException> { reopened.markControlStarted(other.requestId, other.revision, "process-new") }
    }

    @Test fun uncertainCorrectionDoesNotBlockExactSafetyPauseOrReplayTheCorrection() {
        val target = bound()
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Synthetic correction")
        var correctionAdmissions = 0
        val correcting = registry.markControlStarted(correction.requestId, correction.revision, "process-old",
            Runnable { correctionAdmissions++ })
        val uncertain = registry.markControlUncertain(correcting.requestId, correcting.revision)
        val fresh = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        val pause = control(fresh, type = WorkspaceControlType.PAUSE)
        var pauseAdmissions = 0
        val pausing = registry.markControlStarted(pause.requestId, pause.revision, "process-new",
            Runnable { pauseAdmissions++ })
        assertEquals(fresh.revision + 1, pausing.admittedAssignmentRevision)
        assertEquals(WorkspaceControlState.COMPLETE, registry.completeControl(pausing.requestId, pausing.revision).state)
        assertEquals(uncertain, registry.getControl(uncertain.requestId))
        fails<IllegalStateException> { registry.completeControl(uncertain.requestId, uncertain.revision) }
        fails<IllegalStateException> {
            registry.markControlStarted(uncertain.requestId, uncertain.revision, "process-new",
                Runnable { correctionAdmissions++ })
        }
        assertEquals(1, correctionAdmissions)
        assertEquals(1, pauseAdmissions)
        assertEquals(2, registry.listControls(target.workspaceId).size)
        assertEquals(WorkspaceControlState.UNCERTAIN, registry.getControl(correction.requestId)!!.state)
    }

    @Test fun safetyPauseInvalidatesLateStartedCompletionWithoutResolvingTheOlderControl() {
        val target = bound()
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Synthetic correction")
        val correcting = registry.markControlStarted(correction.requestId, correction.revision, "process-old")
        val stalePause = registry.nativeControls()
        fails<StaleWorkspaceRevisionException> {
            stalePause.submit(UUID.randomUUID().toString(), target.workspaceId, target.assignmentId, target.botId!!,
                target.revision, WorkspaceControlType.PAUSE, "")
        }
        val fresh = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        val pause = control(fresh, type = WorkspaceControlType.PAUSE)
        val pausing = registry.markControlStarted(pause.requestId, pause.revision, "process-new")
        var lateCompletionAdmission = false
        fails<StaleWorkspaceRevisionException> {
            registry.completeControl(correcting.requestId, correcting.revision, Runnable { lateCompletionAdmission = true })
        }
        assertFalse(lateCompletionAdmission)
        assertEquals(correcting, registry.getControl(correcting.requestId))
        assertEquals(WorkspaceControlState.COMPLETE, registry.completeControl(pausing.requestId, pausing.revision).state)
        assertEquals(WorkspaceControlState.UNCERTAIN,
            registry.markControlUncertain(correcting.requestId, correcting.revision).state)
    }

    @Test fun safetyPauseDoesNotAuthorizeResumeCorrectionOrCrossTargetActionsAfterUncertainty() {
        val target = bound(workspace("first"))
        val other = bound(workspace("second"))
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Synthetic correction")
        val correcting = registry.markControlStarted(correction.requestId, correction.revision, "process-old")
        registry.markControlUncertain(correcting.requestId, correcting.revision)
        val fresh = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        fails<IllegalArgumentException> {
            registry.nativeControls().submit(UUID.randomUUID().toString(), fresh.workspaceId, other.assignmentId,
                other.botId!!, other.revision, WorkspaceControlType.PAUSE, "")
        }
        fails<IllegalStateException> {
            registry.nativeControls().submit(UUID.randomUUID().toString(), fresh.workspaceId, fresh.assignmentId,
                "different-bot", fresh.revision, WorkspaceControlType.PAUSE, "")
        }
        val pause = control(fresh, type = WorkspaceControlType.PAUSE)
        val pausing = registry.markControlStarted(pause.requestId, pause.revision, "process-new")
        registry.completeControl(pausing.requestId, pausing.revision)
        val afterPause = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        for (type in listOf(WorkspaceControlType.RESUME, WorkspaceControlType.CORRECT)) {
            val pending = control(afterPause, type = type, user = "Synthetic instruction")
            var admitted = false
            fails<IllegalStateException> {
                registry.markControlStarted(pending.requestId, pending.revision, "process-new", Runnable { admitted = true })
            }
            assertFalse(admitted)
            assertEquals(WorkspaceControlState.PENDING, registry.getControl(pending.requestId)!!.state)
        }
        assertEquals(afterPause.revision, registry.getAssignment(target.workspaceId, target.assignmentId)!!.revision)
    }

    @Test fun unsettledProjectionSelectsLatestWithoutDrainingOlderControlsAndValidatesScope() {
        val target = bound(workspace("projection-first"))
        val other = bound(workspace("projection-second"))
        assertNull(registry.getUnsettledControl(target.workspaceId, target.assignmentId))
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Synthetic correction")
        assertNull(registry.getUnsettledControl(target.workspaceId, target.assignmentId))
        val correcting = registry.markControlStarted(correction.requestId, correction.revision, "process-old")
        val uncertain = registry.markControlUncertain(correcting.requestId, correcting.revision)
        mutate("UPDATE user_control SET updated_at=? WHERE request_id=?", arrayOf(1001L, uncertain.requestId))
        val fresh = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        val pause = control(fresh, type = WorkspaceControlType.PAUSE)
        val pausing = registry.markControlStarted(pause.requestId, pause.revision, "process-new")
        mutate("UPDATE user_control SET updated_at=? WHERE request_id=?", arrayOf(1002L, pausing.requestId))
        assertEquals(pausing.requestId, registry.getUnsettledControl(target.workspaceId, target.assignmentId)!!.requestId)
        assertEquals(2, registry.listControls(target.workspaceId).size)
        assertNull(registry.getUnsettledControl(other.workspaceId, other.assignmentId))
        fails<IllegalArgumentException> { registry.getUnsettledControl(target.workspaceId, other.assignmentId) }
        fails<IllegalArgumentException> { registry.getUnsettledControl("jarvys-central", target.assignmentId) }
        registry.completeControl(pausing.requestId, pausing.revision)
        val remaining = registry.getUnsettledControl(target.workspaceId, target.assignmentId)!!
        assertEquals(uncertain.requestId, remaining.requestId)
        assertEquals(WorkspaceControlState.UNCERTAIN, remaining.state)
        assertEquals(remaining, open().getUnsettledControl(target.workspaceId, target.assignmentId))
        assertEquals(2, registry.listControls(target.workspaceId).size)
    }

    @Test fun unsettledProjectionUsesStableRequestIdTieBreakAndRejectsCorruptSelectedRow() {
        val target = bound()
        val correction = control(target, type = WorkspaceControlType.CORRECT, user = "Synthetic correction")
        val correcting = registry.markControlStarted(correction.requestId, correction.revision, "process-old")
        registry.markControlUncertain(correcting.requestId, correcting.revision)
        val fresh = registry.getAssignment(target.workspaceId, target.assignmentId)!!
        val pause = control(fresh, type = WorkspaceControlType.PAUSE)
        val pausing = registry.markControlStarted(pause.requestId, pause.revision, "process-new")
        registry.markControlUncertain(pausing.requestId, pausing.revision)
        mutate("UPDATE user_control SET updated_at=? WHERE assignment_id=?", arrayOf(2000L, target.assignmentId))
        val expected = listOf(correction.requestId, pause.requestId).maxOrNull()!!
        repeat(3) {
            assertEquals(expected, registry.getUnsettledControl(target.workspaceId, target.assignmentId)!!.requestId)
        }
        mutate("UPDATE user_control SET bot_id=? WHERE request_id=?", arrayOf("../invalid", expected))
        fails<WorkspaceRegistryCorruptException> { registry.getUnsettledControl(target.workspaceId, target.assignmentId) }
    }

    @Test fun completionRejectsChangedTargetButStillAllowsPersistingUncertainty() {
        val target = bound()
        val pending = control(target)
        val started = registry.markControlStarted(pending.requestId, 1, "process")
        mutate("UPDATE assignment SET revision=revision+1 WHERE assignment_id=?", arrayOf(target.assignmentId))
        fails<StaleWorkspaceRevisionException> { registry.completeControl(started.requestId, started.revision) }
        assertEquals(WorkspaceControlState.UNCERTAIN,
            registry.markControlUncertain(started.requestId, started.revision).state)
    }

    @Test fun controlsArePagedByExactWorkspaceAndRejectCrossScopeSubmissions() {
        val first = bound(workspace("first"))
        val second = bound(workspace("second"))
        repeat(4) { control(first) }
        control(second)
        fails<IllegalArgumentException> {
            registry.nativeControls().submit(UUID.randomUUID().toString(), first.workspaceId, second.assignmentId,
                second.botId!!, second.revision, WorkspaceControlType.PAUSE, "")
        }
        val page = registry.listControls(first.workspaceId, 2)
        val rest = registry.listControls(first.workspaceId, 2, page.last().requestId)
        assertEquals(4, (page + rest).size)
        assertTrue((page + rest).all { it.workspaceId == first.workspaceId })
    }

    @Test fun receiptsAreIdempotentSnapshotSpecificAndNeverDrainOrChangeAssignments() {
        val assignment = prepare()
        val before = registry.getAssignment(assignment.workspaceId, assignment.assignmentId)!!
        var callbacks = 0
        val digestA = "a".repeat(64)
        val digestB = "b".repeat(64)
        assertFalse(registry.isRead(assignment.workspaceId, assignment.assignmentId, digestA))
        val first = registry.markRead(assignment.workspaceId, assignment.assignmentId, digestA, Runnable { callbacks++ })
        val again = open().markRead(assignment.workspaceId, assignment.assignmentId, digestA, Runnable { callbacks++ })
        assertEquals(first, again)
        assertEquals(1, callbacks)
        assertTrue(registry.isRead(assignment.workspaceId, assignment.assignmentId, digestA))
        assertFalse(registry.isRead(assignment.workspaceId, assignment.assignmentId, digestB))
        registry.markRead(assignment.workspaceId, assignment.assignmentId, digestB)
        val page = registry.listReadReceipts(assignment.workspaceId, assignment.assignmentId, 1)
        val next = registry.listReadReceipts(assignment.workspaceId, assignment.assignmentId, 1, page.single().snapshotDigest)
        assertEquals(listOf(digestA, digestB), (page + next).map { it.snapshotDigest })
        assertEquals(2, registry.listReadReceipts(assignment.workspaceId, assignment.assignmentId).size)
        assertEquals(before.revision, registry.getAssignment(assignment.workspaceId, assignment.assignmentId)!!.revision)
        assertEquals(before.state, registry.getAssignment(assignment.workspaceId, assignment.assignmentId)!!.state)
        fails<IllegalArgumentException> { registry.markRead(assignment.workspaceId, assignment.assignmentId, "bad") }
        fails<IllegalArgumentException> { registry.markRead(workspace("other").workspaceId, assignment.assignmentId, digestA) }
    }

    @Test fun receiptCallbackDenialLeavesNoReceipt() {
        val assignment = prepare()
        val digest = "0".repeat(64)
        fails<SecurityException> {
            registry.markRead(assignment.workspaceId, assignment.assignmentId, digest, Runnable { throw SecurityException("STOP") })
        }
        assertFalse(registry.isRead(assignment.workspaceId, assignment.assignmentId, digest))
    }

    @Test fun malformedWorkspaceRowsFailClearlyAndNeverRecreateScope() {
        val scope = workspace()
        mutate("UPDATE workspace SET execution_session_id=? WHERE workspace_id=?", arrayOf("legacy-chat", scope.workspaceId))
        fails<WorkspaceRegistryCorruptException> { registry.getWorkspace(scope.workspaceId) }
        fails<WorkspaceRegistryCorruptException> { registry.createWorkspace("synthetic-key", scope.title) }
        fails<WorkspaceRegistryCorruptException> { registry.listWorkspaces() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*),execution_session_id FROM workspace", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)); assertEquals("legacy-chat", cursor.getString(1))
            }
        }
    }

    @Test fun malformedAssignmentFieldsAndTypesFailWithoutDeletingMetadata() {
        val assignment = prepare()
        mutate("UPDATE assignment SET tools_json=? WHERE assignment_id=?", arrayOf("[1]", assignment.assignmentId))
        fails<WorkspaceRegistryCorruptException> { registry.getAssignment(assignment.workspaceId, assignment.assignmentId) }
        mutate("UPDATE assignment SET tools_json=?,revision=? WHERE assignment_id=?", arrayOf("[\"ls\",\"read\"]", "bad", assignment.assignmentId))
        fails<WorkspaceRegistryCorruptException> { registry.listAssignments(assignment.workspaceId) }
        assertEquals(1, registry.listWorkspaces().size)
    }

    @Test fun malformedControlOriginAndReceiptOwnerFailClosed() {
        val target = bound()
        val control = control(target)
        // Valid native origin is constrained in SQL; a corrupt target ID must also fail when decoded.
        mutate("UPDATE user_control SET bot_id=? WHERE request_id=?", arrayOf("../wrong", control.requestId))
        fails<WorkspaceRegistryCorruptException> { registry.getControl(control.requestId) }
        registry.markRead(target.workspaceId, target.assignmentId, "a".repeat(64))
        mutate("UPDATE central_read_receipt SET reader_central_session_id=? WHERE assignment_id=?", arrayOf("legacy", target.assignmentId))
        fails<WorkspaceRegistryCorruptException> { registry.isRead(target.workspaceId, target.assignmentId, "a".repeat(64)) }
    }

    @Test fun validationRejectsMalformedIdsSizesToolsAndEmptyCorrections() {
        fails<IllegalArgumentException> { registry.createWorkspace("bad/key", "Title") }
        fails<IllegalArgumentException> { registry.createWorkspace("key", " ") }
        fails<IllegalArgumentException> { registry.createWorkspace("key", "x".repeat(161)) }
        fails<IllegalArgumentException> { registry.createWorkspace("key", "bad\u0000text") }
        fails<IllegalArgumentException> { registry.createWorkspace("key", "bad\uD800") }
        val scope = workspace()
        fails<IllegalArgumentException> { prepare(scope, mission = "x".repeat(65537)) }
        fails<IllegalArgumentException> { prepare(scope, tools = List(129) { "read" }) }
        fails<IllegalArgumentException> { prepare(scope, tools = listOf("../write")) }
        val target = bound(scope)
        fails<IllegalArgumentException> { control(target, request = "not-a-uuid") }
        fails<IllegalArgumentException> { control(target, type = WorkspaceControlType.CORRECT, user = " ") }
    }

    @Test fun registryDoesNotMigrateLegacyFilesOrInventWorkOnOpen() {
        val legacy = File(context.filesDir, "synthetic-legacy-chat.txt")
        val bytes = "Synthetic old chat bytes\r\n".toByteArray()
        legacy.writeBytes(bytes)
        registry.close()
        val reopened = open()
        assertTrue(reopened.listWorkspaces().isEmpty())
        assertArrayEquals(bytes, legacy.readBytes())
        workspace()
        assertArrayEquals(bytes, legacy.readBytes())
    }

    @Test fun unsupportedSchemaVersionFailsWithoutDestructiveUpgrade() {
        val scope = workspace()
        registry.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        fails<WorkspaceRegistryCorruptException> { open().getWorkspace(scope.workspaceId) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT workspace_id FROM workspace", null).use {
                assertTrue(it.moveToFirst()); assertEquals(scope.workspaceId, it.getString(0))
            }
        }
    }
}

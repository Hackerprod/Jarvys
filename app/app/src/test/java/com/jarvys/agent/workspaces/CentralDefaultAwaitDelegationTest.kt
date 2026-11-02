package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.*
import com.jarvys.agent.crew.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Enabled-only acceptance: real Central invocation, coordinator, registry and generalist worker.
 * Not part of the OFF pre-write cohort. No assumption/skip or policy override enables admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CentralDefaultAwaitDelegationTest {
    @Rule @JvmField val storage = ChatTestStore(registerApplicationFilesDirectory = true)
    private lateinit var app: Application
    private lateinit var coordinator: WorkspaceCoordinator
    private val managers = ConcurrentHashMap<String, CrewManager>()
    private val lookups = AtomicInteger()
    private val modelCalls = AtomicInteger()
    private val keepalives = AtomicInteger()
    private val tools = CoreToolRegistry(emptyList())

    @Before fun setUp() {
        assertTrue("Run only after explicit local admission activation", CentralAvailabilityPolicy.admissionEnabled())
        app = RuntimeEnvironment.getApplication()
        CrewMode.write(app, CrewMode.AUTO)
        coordinator = WorkspaceCoordinator(app, { session ->
            lookups.incrementAndGet()
            managers.computeIfAbsent(session) {
                val model = CoreAgentLoop.Model { _, _, _, _ ->
                    modelCalls.incrementAndGet(); ModelReply("Synthetic completion", emptyList())
                }
                val factory = CrewManager.LoopFactory { _, registry, inbox ->
                    CoreAgentLoop(model, registry, "", session, CorePromptBudget.standard(), null,
                        CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null)
                }
                CrewManager(session, tools, { _, _ -> tools }, factory, null).also { owner ->
                    NativeCentralTreeEpisodeFixture.installManager(app, session, owner)
                    owner.configureResultEvidenceListener { LocalRunStore(app).appendCrewResultEvidence(it) }
                }
            }
        }, { keepalives.incrementAndGet(); Unit })
    }

    @After fun closeManagers() { managers.values.forEach { it.close() } }

    @Test fun omittedPolicyCreatesAwaitedAssignmentThroughActualToolAndCoordinator() = newPolicy(null, TreeReply.AWAIT)
    @Test fun explicitFalseCreatesDetachedAssignmentThroughActualToolAndCoordinator() = newPolicy(false, TreeReply.NONE)
    @Test fun explicitTrueStillCreatesAwaitedAssignment() = newPolicy(true, TreeReply.AWAIT)
    @Test fun omittedPreparedRetryPreservesDetachedKeyModeAndOriginalSource() = preparedRetry(false)
    @Test fun omittedPreparedRetryPreservesAwaitedKeyModeAndOriginalSource() = preparedRetry(true)

    @Test fun omittedStartedObservationNeverCreatesManagerOrReplaysEitherRetainedMode() {
        for (awaited in listOf(false, true)) {
            val workspace = workspace("started-$awaited")
            val key = retainedKey(awaited)
            val started = WorkspaceRegistry(app).use { db ->
                val prepared = prepare(db, workspace, key)
                db.markStarted(workspace.workspaceId, prepared.assignmentId, prepared.revision, "previous-process")
            }
            episode({ frame ->
                val result = execute(workspace, frame, null)
                assertTrue(result.content, result.success)
                val row = responseData(result)
                assertEquals(started.assignmentId, row.getString("assignment_id"))
                assertEquals("STARTED", row.getString("admission_state"))
                assertTrue(row.getBoolean("no_replay"))
                assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, result.effectReceipt.outcome)
            })
            val retained = assignment(workspace)
            assertEquals(started.assignmentId, retained.assignmentId)
            assertEquals(started.revision, retained.revision)
            assertEquals(key, retained.assignmentKey)
        }
        assertEquals(0, lookups.get()); assertEquals(0, modelCalls.get()); assertEquals(0, keepalives.get())
    }

    @Test fun omittedPolicyRejectsAmbiguousRetainedKeysWithoutMutatingEitherAssignment() {
        val workspace = workspace("ambiguous")
        val before = WorkspaceRegistry(app).use { db ->
            listOf(prepare(db, workspace, retainedKey(false)), prepare(db, workspace, retainedKey(true)))
                .map { listOf(it.assignmentId, it.assignmentKey, it.revision, it.state, it.botId) }
        }
        episode({ frame -> assertFalse(execute(workspace, frame, null).success) })
        val after = WorkspaceRegistry(app).use { db -> db.listAssignments(workspace.workspaceId)
            .map { listOf(it.assignmentId, it.assignmentKey, it.revision, it.state, it.botId) } }
        assertEquals(before.toSet(), after.toSet())
        assertEquals(0, lookups.get()); assertEquals(0, modelCalls.get()); assertEquals(0, keepalives.get())
    }

    @Test fun nonBooleanPolicyIsRejectedBeforeBackendAdmission() {
        val workspace = workspace("invalid")
        episode({ frame ->
            val result = execute(workspace, frame, "false")
            assertFalse(result.success); assertFalse(frame.invocation.admitted())
        })
        WorkspaceRegistry(app).use { assertTrue(it.listAssignments(workspace.workspaceId).isEmpty()) }
        assertEquals(0, lookups.get()); assertEquals(0, modelCalls.get())
    }

    private fun newPolicy(policy: Boolean?, expected: TreeReply) {
        val workspace = workspace("new-${policy}")
        episode({ frame ->
            val result = execute(workspace, frame, policy)
            assertTrue(result.content, result.success)
            awaitWorker(workspace, result)
            val row = assignment(workspace)
            assertEquals(retainedKey(expected == TreeReply.AWAIT), row.assignmentKey)
            WorkspaceRegistry(app).use { db ->
                assertEquals(expected, requireNotNull(WorkspaceTreeStore(db).findAssignment(workspace.workspaceId, row.assignmentId)).reply)
            }
        })
        assertEquals(1, modelCalls.get())
    }

    private fun preparedRetry(awaited: Boolean) {
        val workspace = workspace("retry-$awaited")
        var original: WorkspaceAssignment? = null
        episode({ frame ->
            CrewMode.write(app, CrewMode.OFF)
            assertFalse(execute(workspace, frame, awaited).success)
            original = assignment(workspace)
            assertEquals(WorkspaceAssignmentState.PREPARED, original!!.state)
            assertEquals(retainedKey(awaited), original!!.assignmentKey)
            assertNotNull(original!!.centralSource)
            assertEquals(0, lookups.get()); assertEquals(0, modelCalls.get())
            CrewMode.write(app, CrewMode.AUTO)
        }, { frame ->
            val result = execute(workspace, frame, null)
            assertTrue(result.content, result.success); awaitWorker(workspace, result)
            val row = assignment(workspace)
            assertEquals(original!!.assignmentId, row.assignmentId)
            assertEquals(original!!.assignmentKey, row.assignmentKey)
            assertEquals(original!!.centralSource, row.centralSource)
            WorkspaceRegistry(app).use { db ->
                assertEquals(if (awaited) TreeReply.AWAIT else TreeReply.NONE,
                    requireNotNull(WorkspaceTreeStore(db).findAssignment(workspace.workspaceId, row.assignmentId)).reply)
            }
        }, { frame ->
            val result = execute(workspace, frame, null)
            assertTrue(result.content, result.success)
            assertEquals(original!!.assignmentId, responseData(result).getString("assignment_id"))
            assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, result.effectReceipt.outcome)
            assertEquals(1, modelCalls.get())
        })
    }

    private fun execute(workspace: WorkspaceRecord, frame: NativeCentralTreeEpisodeFixture.Frame, policy: Any?): CoreToolResult {
        val arguments = mutableMapOf<String, Any>("action" to "delegate", "workspace_id" to workspace.workspaceId,
            "key" to KEY, "role" to ROLE, "mission" to MISSION, "tools" to emptyList<String>(),
            "name" to "Synthetic worker", "mission_access" to "standard")
        if (policy != null) arguments["continue_result"] = policy
        return CentralWorkspaceTool(WorkspaceIdentity.CENTRAL_SESSION, REQUEST, ORIGINAL, coordinator, frame.authority)
            .execute(arguments, frame.token, null, frame.invocation).also { frame.result = it }
    }
    private fun episode(vararg steps: (NativeCentralTreeEpisodeFixture.Frame) -> Unit) =
        NativeCentralTreeEpisodeFixture.run(app, CancellationToken.cancellable(), ORIGINAL, steps.toList())
    private fun workspace(key: String): WorkspaceRecord {
        val id = coordinator.create(key, "Synthetic workspace", "create-$key", ORIGINAL, Runnable {}).getString("workspace_id")
        return WorkspaceRegistry(app).use { requireNotNull(it.getWorkspace(id)) }
    }
    private fun assignment(workspace: WorkspaceRecord) = WorkspaceRegistry(app).use { it.listAssignments(workspace.workspaceId).single() }
    private fun prepare(db: WorkspaceRegistry, workspace: WorkspaceRecord, key: String) = db.prepareAssignment(
        workspace.workspaceId, key, REQUEST, ORIGINAL, ROLE, MISSION, emptyList(), "Synthetic worker", CrewMissionAccess.STANDARD)
    private fun retainedKey(awaited: Boolean) = if (awaited) "continuation-v1:" + UUID.nameUUIDFromBytes(KEY.toByteArray(Charsets.UTF_8)) else KEY
    private fun responseData(result: CoreToolResult): JSONObject {
        assertTrue(result.content, result.success)
        val envelope = JSONObject(result.content)
        assertEquals("workspace_data", envelope.getString("kind"))
        return envelope.getJSONObject("data")
    }
    private fun awaitWorker(workspace: WorkspaceRecord, result: CoreToolResult) {
        val bot = requireNotNull(managers.getValue(workspace.executionSessionId).bot(responseData(result).getString("bot_id")))
        val task = FutureTask<Unit> { bot.awaitTermination(); Unit }
        val thread = Thread(task, "default-await-fixture-join").apply { isDaemon = true; start() }
        try { task.get(10, TimeUnit.SECONDS) } finally { if (thread.isAlive) thread.interrupt() }
    }
    companion object {
        private const val KEY = "assignment"
        private const val REQUEST = "synthetic-request"
        private const val ORIGINAL = "Synthetic general-purpose request"
        private const val ROLE = "native-fixture-generalist"
        private const val MISSION = "Synthetic native mission"
    }
}

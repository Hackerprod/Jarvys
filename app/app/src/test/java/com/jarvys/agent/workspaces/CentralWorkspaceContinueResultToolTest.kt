package com.jarvys.agent.workspaces

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import com.jarvys.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Adapter contract only: actual latest-user-command and selected-result checks live in the backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
class CentralWorkspaceContinueResultToolTest {
    @Rule @JvmField val storage = ChatTestStore()

    // Match CentralInvocationSourceTest: isolated registered Room root and real host lstat semantics.
    private fun context(): Context {
        val files = storage.newFilesDirectory()
        storage.localRunStore(files)
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
    }
    private val workspace = "12345678-1234-1234-1234-123456789abc"
    private val resultId = "workspace-result-${"a".repeat(64)}"
    private val sha = "b".repeat(64)
    private val args get() = mapOf<String, Any>("action" to "continue_result", "workspace_id" to workspace,
        "result_id" to resultId, "expected_sha256" to sha, "expected_assignment_revision" to 7L)

    private open class Backend : CentralWorkspaceTool.Backend {
        override fun create(key: String, title: String, requestId: String, originalUserRequest: String,
                            admission: Runnable): JSONObject = throw AssertionError("No create")
        override fun list(limit: Int, afterWorkspaceId: String?, admission: Runnable): JSONObject = throw AssertionError("No list")
        override fun delegate(workspaceId: String, key: String, role: String, mission: String,
                              tools: List<String>?, name: String?, missionAccess: String, requestId: String,
                              originalUserRequest: String, token: CancellationToken, admission: Runnable): JSONObject =
            throw AssertionError("No delegation")
        override fun read(workspaceId: String, assignmentId: String?, limit: Int, afterAssignmentId: String?,
                          admission: Runnable): JSONObject = throw AssertionError("No read")
    }
    private fun tool(backend: CentralWorkspaceTool.Backend = Backend()) = CentralWorkspaceTool(
        WorkspaceIdentity.CENTRAL_SESSION, "explicit-result-command", "/continue_result $resultId", backend)

    @Test fun continuationIsWriteButResultReadingRemainsRead() {
        val tool = tool()
        assertEquals(CoreTool.EffectClass.WRITE, tool.effectClass(args))
        assertEquals(CoreTool.EffectClass.READ, tool.effectClass(mapOf("action" to "read_result")))
        assertFalse(tool.canDelegate())
        assertTrue(tool.declaration().description.contains("/continue_result <result_id>"))
    }

    @Test fun legacyAndWitnesslessInvocationCannotReachBackendOrAdmit() {
        var calls = 0
        val backend = object : Backend() {
            override fun continueResult(workspaceId: String, resultId: String, expectedSha256: String,
                expectedAssignmentRevision: Long, token: CancellationToken, admission: Runnable,
                context: WorkspaceMutationContext): JSONObject { calls++; throw AssertionError("No witness") }
        }
        assertFalse(tool(backend).execute(args, CancellationToken.cancellable()).success)
        var effects = 0
        val invocation = EffectInvocation { effects++ }
        val answer = tool(backend).execute(args, CancellationToken.cancellable(), null, invocation)
        assertFalse(answer.success)
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, answer.effectReceipt.outcome)
        assertFalse(invocation.admitted()); assertEquals(0, effects); assertEquals(0, calls)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceMutationContext("continue_result", workspace, null, invocation, Runnable {})
        }
    }

    @Test fun strictFieldsAreParsedBeforeExactLiveSourceDispatch() {
        var calls = 0
        CentralInvocationTestSource.runWithWitness(context()) { owner, witness ->
            val backend = object : Backend() {
                override fun continueResult(workspaceId: String, resultId: String, expectedSha256: String,
                    expectedAssignmentRevision: Long, token: CancellationToken, admission: Runnable,
                    context: WorkspaceMutationContext): JSONObject {
                    admission.run(); calls++
                    assertEquals(workspace, workspaceId); assertEquals(this@CentralWorkspaceContinueResultToolTest.resultId, resultId)
                    assertEquals(sha, expectedSha256); assertEquals(7L, expectedAssignmentRevision)
                    assertSame(witness.source(), context.rootSource)
                    assertEquals("continue_result", context.action)
                    context.existing(EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT, workspaceId, "selected-input")
                    return JSONObject().put("state", "PREPARED")
                }
            }
            val tool = tool(backend)
            val invalid = listOf(
                args - "workspace_id", args - "result_id", args - "expected_sha256", args - "expected_assignment_revision",
                args + ("workspace_id" to "other"), args + ("result_id" to "bad-result"),
                args + ("expected_sha256" to sha.uppercase()), args + ("expected_assignment_revision" to 0L),
                args + ("expected_assignment_revision" to -1L), args + ("expected_assignment_revision" to 1.5),
                args + ("expected_assignment_revision" to "7"), args + ("offset" to 0),
                args + ("continue_result" to true), args + ("key" to "new-attempt"),
                args + ("origin" to "user"), args + ("action" to "CONTINUE_RESULT"))
            invalid.forEach { assertFalse(it.toString(), tool.execute(it, CancellationToken.cancellable(), null, owner).success) }
            assertEquals(0, calls); assertFalse(owner.admitted())
            val response = tool.execute(args, CancellationToken.cancellable(), null, owner)
            assertTrue(response.content, response.success)
            assertEquals(1, calls)
            assertEquals(EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT, response.effectReceipt.reference.kind)
            assertEquals("selected-input", response.effectReceipt.reference.id)
        }
    }

    @Test fun expiredWitnessAndUnsupportedBackendNeverAdmit() {
        var captured: EffectInvocation? = null
        CentralInvocationTestSource.runWithWitness(context()) { owner, _ ->
            captured = owner
            val result = tool().execute(args, CancellationToken.cancellable(), null, owner)
            assertFalse(result.success); assertFalse(owner.admitted())
        }
        val result = tool().execute(args, CancellationToken.cancellable(), null, requireNotNull(captured))
        assertFalse(result.success); assertFalse(requireNotNull(captured).admitted())
    }
}

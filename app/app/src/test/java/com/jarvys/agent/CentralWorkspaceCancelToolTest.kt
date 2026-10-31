package com.jarvys.agent

import com.jarvys.agent.workspaces.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** Actual Central capability, committed source provider and execute4; queued Registry targets are explicit data fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CentralWorkspaceCancelToolTest {
    private val storage=ChatTestStore()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private inner class Session(val f:WorkspaceMemoryFixture):AutoCloseable {
        val token=CancellationToken.cancellable()
        val user=f.store.appendConversationMessage(central,"user","Cancel only the requested subtree")
        val admission=CentralTreeAdmission.beginUser(f.context,f.store,central,user,token)
        val registry=WorkspaceRegistry(f.context)
        val core=WorkspaceTreeStore(registry)
        val root=core.getNode(admission.rootId(),admission.rootId())!!
        val workspace=f.createProject("Cancellation fixture")
        var backendFailure:Throwable?=null
        private val nativeBackend=WorkspaceCoordinator(f.context,{ error("Queued cancellation must not create or restore a runtime") },{})
        val backend=object:CentralWorkspaceTool.Backend by nativeBackend {
            override fun cancel(workspaceId:String,assignmentId:String,botId:String?,revision:Long,key:String,
                token:CancellationToken,admission:Runnable,context:WorkspaceMutationContext):JSONObject = try {
                nativeBackend.cancel(workspaceId,assignmentId,botId,revision,key,token,admission,context)
            } catch(failure:Throwable) { backendFailure=failure;throw failure }
        }
        val tool=CentralWorkspaceTool(f.context,central,"native-runtime-${UUID.randomUUID()}","Cancel only the requested subtree",backend,admission)
        val results=ArrayList<CoreToolResult>()
        var captured:EffectInvocation?=null
        var beforeExecute:((EffectInvocation)->Unit)?=null
        fun queued(key:String)=core.admitChild(TreeNodeFence.of(root),TreeChildRequest(workspace.workspaceId,key,"request","Original task",
            "analyst","Queued $key",emptyList(),null,TreeMode.WORK,TreeReply.AWAIT,"synthetic-queued-data-$key"))
        fun args(target:TreeNode,key:String="cancel-key",revision:Long=target.assignmentRevision)=linkedMapOf<String,Any>(
            "action" to "cancel","workspace_id" to target.workspaceId!!,"assignment_id" to target.assignmentId!!,
            "expected_assignment_revision" to revision,"key" to key).apply { target.botId?.let { put("bot_id",it) } }
        fun run(calls:List<Map<String,Any>>) {
            val run="actual-transcript-${UUID.randomUUID()}"
            val writer=MainChatTranscriptStore(f.files,central,f.store)
            val forwarding=object:CoreTool {
                override fun declaration()=tool.declaration()
                override fun effectClass(arguments:Map<String,Any>)=tool.effectClass(arguments)
                override fun execute(arguments:Map<String,Any>?,token:CancellationToken):CoreToolResult=error("execute4 required")
                override fun execute(arguments:Map<String,Any>?,token:CancellationToken,progress:CoreTool.ProgressListener?,invocation:EffectInvocation?):CoreToolResult {
                    val actual=requireNotNull(invocation);requireNotNull(actual.centralSource()).requireInvocationCurrent()
                    captured=actual;beforeExecute?.invoke(actual)
                    return tool.execute(arguments,token,progress,actual).also { results.add(it) }
                }
            }
            val tools=CoreToolRegistry(listOf(forwarding)).withInvocationSourceProvider(writer.centralSourceProvider(user,run))
            var step=0
            val loop=CoreAgentLoop(CoreAgentLoop.Model { _,_,_,_ ->
                if(step<calls.size) { val index=step++;ModelReply("",listOf(ModelReply.Call("call-$index",CentralWorkspaceTool.NAME,calls[index]))) }
                else ModelReply("Finished cancellation checks",emptyList())
            },tools,"Use the exact cancellation fixture",central)
            writer.attach(loop,emptyList(),user,run)
            loop.run("Cancel only the requested subtree",emptyList(),emptyList(),token,null,user,run)
        }
        fun node(n:TreeNode)=core.getNode(n.rootId,n.nodeId)!!
        override fun close() { admission.close();token.completeRun();registry.close() }
    }
    @Before fun requiresEnabledIndependentGate() { check(WorkspaceTreePolicy.admissionEnabled()) { "Requires approved post-D3 enabled candidate; no gate bypass" } }
    @Test fun actualExecute4CancelsExactQueuedTargetOnceAndConservesSiblingAndTypedSource() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("target");val sibling=s.queued("sibling")
            s.run(listOf(s.args(target),s.args(target)))
            assertEquals(2,s.results.size);assertTrue(s.backendFailure?.stackTraceToString(),s.results.all { it.success })
            val cancelled=s.node(target)
            assertEquals(TreeNodeState.TERMINAL,cancelled.state);assertEquals("CANCELLED",cancelled.terminalOutcome)
            assertNull(cancelled.botId);assertNull(cancelled.executionEventId);assertEquals(sibling,s.node(sibling))
            assertEquals("cancelled-admission:"+cancelled.terminalHash,cancelled.reportReceipt)
            assertEquals(WorkspaceAssignmentState.PREPARED,s.registry.getAssignment(s.workspace.workspaceId,target.assignmentId!!)!!.state)
            s.registry.treeRead { db ->
                db.rawQuery("SELECT receipt,payload_digest FROM bot_tree_event WHERE root_id=? AND node_id=? AND kind='TERMINAL'",
                    arrayOf(target.rootId,target.nodeId)).use { c ->
                    assertTrue(c.moveToFirst());assertEquals(1,c.count)
                    val receipt=c.getString(0);assertEquals("CANCELLED_ADMISSION",JSONObject(receipt).getString("kind"))
                    assertEquals(target.assignmentId,JSONObject(receipt).getString("assignmentId"))
                    assertEquals(WorkspaceTreeStore.identity(receipt),c.getString(1))
                }
                db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind='CANCEL_REQUEST'",arrayOf(target.rootId)).use { c -> c.moveToFirst();assertEquals(1,c.getInt(0)) }
            }
            assertTrue(s.results.last().content.contains("\\\"captured_targets\\\":0") || s.results.last().content.contains("\"captured_targets\":0"))
        } }
    }
    @Test fun sameStableCancellationKeyCannotRetargetSiblingWithinOriginalRoot() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("target");val sibling=s.queued("sibling")
            s.run(listOf(s.args(target,"same-key"),s.args(sibling,"same-key")))
            assertTrue(s.backendFailure?.stackTraceToString(),s.results.first().success);assertFalse(s.results.last().success)
            assertEquals(sibling,s.node(sibling));assertEquals(TreeNodeState.TERMINAL,s.node(target).state)
        } }
    }
    @Test fun staleOriginalUserCannotCancelNewerRootDespiteExactTarget() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { first -> Session(f).use { second ->
            val foreign=second.queued("foreign");val local=first.queued("local")
            first.run(listOf(first.args(foreign)))
            assertFalse(first.results.single().success);assertEquals(foreign,second.node(foreign));assertEquals(local,first.node(local))
        } } }
    }
    @Test fun wrongObservedRevisionAndExpiredInvocationCannotReachTarget() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("target")
            s.run(listOf(s.args(target,revision=target.assignmentRevision+1)))
            assertFalse(s.results.single().success);assertEquals(target,s.node(target))
            val stale=runCatching { s.tool.execute(s.args(target),s.token,null,s.captured!!) }
            assertTrue(stale.isFailure || !stale.getOrThrow().success);assertEquals(target,s.node(target))
        } }
    }
    @Test fun ordinaryReadsBackgroundAndMissingExecute4AuthorityDoNotCancel() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("target")
            s.run(listOf(mapOf("action" to "read","workspace_id" to s.workspace.workspaceId)))
            assertTrue(s.results.single().success);assertEquals(target,s.node(target))
            assertFalse(s.tool.execute(s.args(target),s.token).success)
            assertFalse(s.tool.execute(s.args(target),CancellationToken.crewChild()).success)
            assertEquals(target,s.node(target))
        } }
    }
    @Test fun revokedExactTreeCapabilityDeniesEvenFreshCommittedExecute4Source() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("target")
            s.beforeExecute={ s.admission.close() }
            s.run(listOf(s.args(target)))
            assertFalse(s.results.single().success);assertEquals(target,s.node(target))
        } }
    }
}

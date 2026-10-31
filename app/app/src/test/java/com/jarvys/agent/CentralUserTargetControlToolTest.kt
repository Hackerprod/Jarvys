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
class CentralUserTargetControlToolTest {
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
        var afterExecute:(()->Unit)?=null
        var replaceArguments:((Map<String,Any>?)->Map<String,Any>?)?=null
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
                    captured=actual
                    try {
                        beforeExecute?.invoke(actual)
                        return tool.execute(replaceArguments?.invoke(arguments) ?: arguments,token,progress,actual).also { results.add(it) }
                    } finally { afterExecute?.invoke() }
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
    @Test fun naturalLaterUserCancelsOriginalOwnedRootWithoutRebindingOrSiblingReach() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { original ->
            val target=original.queued("old-target");val sibling=original.queued("old-sibling")
            original.admission.close() // Old execution gone; original USER/root remain durable.
            Session(f).use { later ->
                val before=original.core.getRoot(target.rootId)!!
                later.run(listOf(later.args(target)))
                assertTrue(later.backendFailure?.stackTraceToString(),later.results.single().success)
                assertEquals(TreeNodeState.TERMINAL,original.node(target).state)
                assertEquals(sibling,original.node(sibling))
                val after=original.core.getRoot(target.rootId)!!
                assertEquals(before.origin,after.origin)
                // Native first-child cancellation settlement resets its normal progress window, not the grant.
                assertEquals(before.windowEpoch+1,after.windowEpoch);assertEquals(0L,after.executions);assertEquals(0L,after.requests)
                original.registry.treeRead { db -> db.rawQuery("SELECT receipt FROM bot_tree_event WHERE root_id=? AND kind='USER_CONTROL_AUTH'",arrayOf(target.rootId)).use { c ->
                    assertTrue(c.moveToFirst());val receipt=JSONObject(c.getString(0))
                    assertEquals(later.user,receipt.getString("user"));assertEquals(target.nodeId,receipt.getString("node"))
                    assertEquals("cancel",receipt.getString("action"))
                } }
            }
        } }
    }
    @Test fun committedArgumentsCannotBeTransferredToSiblingOrDifferentAction() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { old ->
            val target=old.queued("selected");val sibling=old.queued("not-selected")
            Session(f).use { later ->
                later.replaceArguments={ later.args(sibling) }
                later.run(listOf(later.args(target)))
                assertFalse(later.results.single().success)
                assertEquals(target,old.node(target));assertEquals(sibling,old.node(sibling))
            }
            Session(f).use { later ->
                later.replaceArguments={ args -> args!!.toMutableMap().apply { put("action","delegate") } }
                later.run(listOf(later.args(target)))
                assertFalse(later.results.single().success);assertEquals(target,old.node(target))
            }
        } }
    }
    @Test fun newerCanonicalUserInvalidatesOtherwiseLiveCurrentInvocation() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { old ->
            val target=old.queued("selected")
            Session(f).use { later ->
                later.beforeExecute={ f.store.appendConversationMessage(central,"user","Wait, clarify the target first") }
                later.run(listOf(later.args(target)))
                assertFalse(later.results.single().success);assertEquals(target,old.node(target))
            }
        } }
    }
    @Test fun originalRootStopAllowsOnlyExactDenialWithoutReopeningRoot() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { old ->
            val target=old.queued("selected")
            old.core.stopRequester(TreeNodeFence.of(old.root))
            val stopped=old.core.getRoot(target.rootId)!!
            val retained=old.node(target)
            Session(f).use { later ->
                later.run(listOf(later.args(retained)))
                assertTrue(later.backendFailure?.stackTraceToString(),later.results.single().success)
                assertEquals(stopped,old.core.getRoot(target.rootId))
                assertEquals(retained,old.node(target))
                assertTrue(runCatching { old.core.reserveFirstExecution(TreeNodeFence.of(retained),com.jarvys.agent.crew.CrewProcessIdentity.ID) }.isFailure)
                assertEquals(stopped,old.core.getRoot(target.rootId))
            }
        } }
    }

    @Test fun opaqueGrantRejectsActionPayloadReplacementAndExpiredContextWithinActualExecute4() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { old ->
            val target=old.queued("selected");val sibling=old.queued("sibling")
            Session(f).use { later ->
                var checked=false
                later.beforeExecute={ actual ->
                    TreeUserTargetControlAssertions.rejectTransfers(f.context,old.core,target,
                        WorkspaceTreeEvidenceStore.canonical(JSONObject(later.args(target))),
                        WorkspaceTreeEvidenceStore.canonical(JSONObject(later.args(sibling))),actual,later.admission,later.token)
                    checked=true
                }
                later.run(listOf(later.args(target)))
                assertTrue(checked);assertTrue(later.backendFailure?.stackTraceToString(),later.results.single().success);assertEquals(sibling,old.node(sibling))
            }
        } }
    }
    @Test fun canonicalWorkspaceOwnerMutationDeniesLaterUserControl() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { old ->
            val target=old.queued("selected")
            Session(f).use { later ->
                // Corrupt only during the real live execute4, after its canonical transcript source exists.
                // Restoring fixture bytes afterward lets the independent result checkpoint persist; it grants no authority.
                later.beforeExecute={ old.registry.treeTransaction { db -> db.execSQL("UPDATE workspace SET owner_central_session_id=? WHERE workspace_id=?",arrayOf("foreign-owner",target.workspaceId)) } }
                later.afterExecute={ old.registry.treeTransaction { db -> db.execSQL("UPDATE workspace SET owner_central_session_id=? WHERE workspace_id=?",arrayOf(central,target.workspaceId)) } }
                later.run(listOf(later.args(target)))
                assertFalse(later.results.single().success)
                assertEquals(target,old.node(target))
            }
        } }
    }

    @Test fun actualStartedCallHasNoResultLifecycleButNativeCallReaderConfirmsExactSource() {
        WorkspaceMemoryFixture(storage).use { f -> Session(f).use { s ->
            val target=s.queued("source-reader")
            var checked=false;var retainedSource:CentralInvocationSource?=null
            val payload=WorkspaceTreeEvidenceStore.canonical(JSONObject(s.args(target)))
            s.beforeExecute={ invocation ->
                val source=requireNotNull(invocation.centralSource()).source();retainedSource=source
                val row=f.store.readEpisodeToolSource(source.ownerCentralSessionId,source.sourceBatchId,source.sourceCallId)!!
                assertNull(row.lifecycle);assertNull(row.output) // Result lifecycle absent for real STARTED call.
                val confirmed=CentralTargetControlSource.capture(f.store,source,payload)
                assertEquals(s.admission.originDigest(),confirmed.originDigest)
                val changed=JSONObject(s.args(target)).put("key","different-request")
                assertTrue(runCatching { CentralTargetControlSource.capture(f.store,source,
                    WorkspaceTreeEvidenceStore.canonical(changed)) }.isFailure)
                checked=true
            }
            s.run(listOf(s.args(target)))
            assertTrue(checked);assertTrue(s.backendFailure?.stackTraceToString(),s.results.single().success)
            // The same data tuple cannot become authority after RESULT is committed.
            assertTrue(runCatching { CentralTargetControlSource.capture(f.store,retainedSource!!,payload) }.isFailure)
        } }
    }

}

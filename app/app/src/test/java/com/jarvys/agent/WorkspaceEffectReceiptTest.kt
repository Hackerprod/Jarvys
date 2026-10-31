package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real native stores; Android/Room execution still requires the authorized SDK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
class WorkspaceEffectReceiptTest {
    @get:Rule val chatStore = ChatTestStore()

    @Test fun realWriteAndEditHaveDurableStartedBeforeChangingTheFile() {
        val files = chatStore.newFilesDirectory("native-effect")
        chatStore.withLocalRunStore(files) { store ->
            val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = files
            }
            val db = ChatDatabaseBridge.getInstance(context)
            val session = com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION
            val owner = store.appendConversationMessage(session, "user", "Write and edit a local note")
            val runId = java.util.UUID.randomUUID().toString()
            val transcript = MainChatTranscriptStore(files, session, store)
            MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
            val token = CancellationToken.cancellable()
            val memorySession = db.beginMemorySession(MemoryWritePolicy.forContext(context).captureTicket(),
                token, java.util.UUID.randomUUID().toString(), session, owner, runId,
                com.jarvys.agent.crew.CrewProcessIdentity.ID)
            transcript.setMemorySessionBinding(memorySession)
            val workspace = WorkspaceStore(File(files, "workspace"), WorkspaceStore.projectIdForSession(session))
            val target = File(File(files, "workspace"), "${workspace.projectId()}/note.txt")
            val recorder = EpisodeRecorder(context, session, owner, runId, transcript)
            var admissions = 0
            val observer = object : CoreToolRegistry.InvocationObserver {
                override fun beforeDispatch(tool: CoreTool, callId: String, arguments: Map<String, Any>) =
                    recorder.beforeDispatch(tool, callId, arguments)
                override fun onEffectAdmission(tool: CoreTool, callId: String, admission: EffectInvocation.Admission) {
                    recorder.onEffectAdmission(tool, callId, admission)
                    val episode = db.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner)).single()
                    val rows = db.personalAgentEpisodeNarrationInputSync(episode.id)!!.evidence
                    val binding = transcript.committedCall(callId)
                    val source = db.readEpisodeToolSourceSync(session, binding.batchId, binding.callId)
                    assertNotNull("Batch must be committed before native mutation", source)
                    assertNull("No result may exist before native mutation", source!!.output)
                    val started = rows.single { it.outcome == "started" }
                    assertEquals(runId, started.runId)
                    assertEquals(binding.batchId, started.sourceBatchId)
                    assertEquals("allow", started.authorization)
                    if (admissions++ == 0) assertFalse(target.exists()) else assertEquals("before", target.readText())
                }
                override fun afterDispatch(tool: CoreTool, callId: String, result: CoreToolResult?, failure: Throwable?) = Unit
                override fun afterDispatch(tool: CoreTool, callId: String, result: CoreToolResult?, failure: Throwable?, receipt: EffectInvocation.Receipt?) =
                    recorder.afterDispatch(tool, callId, result, failure, receipt)
            }
            val registry = CoreToolRegistry(WorkspaceTools.create(workspace)).withInvocationObserver(observer)
                .withInvocationSourceProvider(transcript.centralSourceProvider(owner, runId))
            var turns = 0
            val model = CoreAgentLoop.Model { _, _, _, _ ->
                when (turns++) {
                    0 -> ModelReply("", listOf(ModelReply.Call("native-write", "write",
                        mapOf("path" to "note.txt", "content" to "before"))))
                    1 -> ModelReply("", listOf(ModelReply.Call("native-edit", "edit",
                        mapOf("path" to "note.txt", "old_text" to "before", "new_text" to "after"))))
                    else -> ModelReply("Local note updated", emptyList())
                }
            }
            val loop = CoreAgentLoop(model, registry, "Use the requested local file operations", session)
            transcript.attach(loop, emptyList(), owner, runId)
            val result = loop.run("Write and edit a local note", emptyList(), emptyList(),
                token, null, owner, runId)
            assertEquals("COMPLETED", result.outcome)
            assertEquals(runId, result.runId)
            assertEquals("after", workspace.read("note.txt"))
            assertEquals(2, admissions)
            val episode = db.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner)).single()
            val rows = db.personalAgentEpisodeNarrationInputSync(episode.id)!!.evidence
            assertEquals(2, rows.size)
            for ((callId, name) in listOf("native-write" to "write", "native-edit" to "edit")) {
                val binding = transcript.committedCall(callId)
                val row = rows.single { it.toolName == name }
                assertEquals("ok", row.outcome)
                assertEquals(runId, row.runId)
                assertEquals(binding.batchId, row.sourceBatchId)
                assertNotNull(db.readEpisodeToolSourceSync(session, binding.batchId, binding.callId)!!.output)
            }
        }
    }

    @Test fun admissionFailurePreventsRealWriteAndEdit() {
        val files = chatStore.newFilesDirectory("native-fail-closed")
        val workspace = WorkspaceStore(files, WorkspaceStore.projectIdForSession("native-fail"))
        workspace.write("note.txt", "original")
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        for ((name, args) in listOf(
            "write" to mapOf("path" to "note.txt", "content" to "changed"),
            "edit" to mapOf("path" to "note.txt", "old_text" to "original", "new_text" to "changed")
        )) {
            val invocation = EffectInvocation { throw IllegalStateException("Evidence unavailable") }
            val result = tools.invoke(name, args, CancellationToken.cancellable(), null, "blocked-$name", invocation)
            assertFalse(result.success)
            assertFalse(invocation.admitted())
            assertEquals("original", workspace.read("note.txt"))
        }
    }

    @Test fun realMemoryMutationsAreExcludedWithoutPretendingTheyAreReads() {
        val files = chatStore.newFilesDirectory("native-memory")
        chatStore.withLocalRunStore(files) { store ->
            val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = files
            }
            FreshStorageBootstrap.ensure(context)
            MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
            val session = com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION
            val db = ChatDatabaseBridge.getInstance(context)
            val owner = store.appendConversationMessage(session, "user", "Save this note, update it, then forget it")
            val runId = java.util.UUID.randomUUID().toString()
            val transcript = MainChatTranscriptStore(files, session, store)
            MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
            val token = CancellationToken.cancellable()
            val memorySession = db.beginMemorySession(MemoryWritePolicy.forContext(context).captureTicket(),
                token, java.util.UUID.randomUUID().toString(), session, owner, runId,
                com.jarvys.agent.crew.CrewProcessIdentity.ID)
            transcript.setMemorySessionBinding(memorySession)
            var completed = 0
            val native = PersonalMemoryTools.create(context, owner, session).filter {
                it.declaration().name in setOf("memory_save", "memory_update", "memory_forget")
            }
            assertEquals(3, native.size)
            val observed = native.map { actual -> object : CoreTool by actual {
                override fun execute(args: Map<String, Any>, token: CancellationToken,
                                     progress: CoreTool.ProgressListener?, invocation: EffectInvocation): CoreToolResult {
                    assertEquals(CoreTool.EffectClass.WRITE, actual.effectClass(args))
                    assertEquals(CoreTool.PersistenceEffect.MEMORY, actual.persistenceEffect(args))
                    val source = invocation.centralSource()!!.source()
                    val committed = db.readEpisodeToolSourceSync(session, source.sourceBatchId, source.sourceCallId)!!
                    assertEquals(actual.declaration().name, committed.toolName)
                    assertNull(committed.output)
                    val result = actual.execute(args, token, progress, invocation)
                    assertTrue(result.content, result.success)
                    assertNull(result.effectReceipt)
                    assertFalse(invocation.admitted())
                    assertFalse(result.persistenceEvidence.isNullOrEmpty())
                    completed++
                    return result
                }
            } }
            val tools = CoreToolRegistry(observed).withInvocationObserver(object : CoreToolRegistry.InvocationObserver {
                override fun beforeDispatch(tool: CoreTool, callId: String, arguments: Map<String, Any>) { fail("Memory must not create an episode") }
                override fun afterDispatch(tool: CoreTool, callId: String, result: CoreToolResult?, failure: Throwable?) { fail("Memory must not finish an episode") }
            }).withInvocationSourceProvider(transcript.centralSourceProvider(owner, runId))
            var turn = 0
            var savedId = ""
            val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
                when (turn++) {
                    0 -> ModelReply("", listOf(ModelReply.Call("save-note", "memory_save",
                        mapOf("title" to "Local note", "content" to "Original note."))))
                    1 -> {
                        savedId = db.personalAgentSearch("Original note").single().id
                        ModelReply("", listOf(ModelReply.Call("update-note", "memory_update",
                            mapOf("id" to savedId, "expected_project" to session, "content" to "Updated note."))))
                    }
                    2 -> {
                        assertEquals("Updated note.", db.personalAgentGet(listOf(savedId)).single().text)
                        ModelReply("", listOf(ModelReply.Call("forget-note", "memory_forget", mapOf("id" to savedId))))
                    }
                    else -> ModelReply("Memory updated and forgotten", emptyList())
                }
            }, tools, "Use the requested native observation operations", session)
            transcript.attach(loop, emptyList(), owner, runId)
            val result = loop.run("Save this note, update it, then forget it", emptyList(), emptyList(),
                token, null, owner, runId)
            assertEquals("COMPLETED", result.outcome)
            assertEquals(3, completed)
            assertTrue(db.personalAgentGet(listOf(savedId)).isEmpty())
            assertTrue(db.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner)).isEmpty())
        }
    }

    @Test fun removedMemoryFileAliasesCannotGrantMemoryOrExternalWriteAuthority() {
        val files = chatStore.newFilesDirectory("native-memory-boundaries")
        val workspace = WorkspaceStore(File(files, "workspace"), WorkspaceStore.projectIdForSession("memory-boundary-session"))
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        for (path in listOf("/memory/note.md", "  /memory/note.md  ", "/memory/../escape.md", "/memory/skills/note.md")) {
            val invocation = EffectInvocation { fail("Removed memory alias must not admit a write") }
            val rejected = tools.invoke("write", mapOf("path" to path, "content" to "not applied"),
                CancellationToken.cancellable(), null, "removed-${path.hashCode()}", invocation)
            assertFalse(rejected.content, rejected.success)
            assertFalse(invocation.admitted())
            assertNull(rejected.administrativeReceipt)
        }
        assertFalse(File(files, "memory").exists())
        assertTrue(File(files, "workspace").walkTopDown().none { it.isFile })
    }

    @Test fun cancellationAfterAdmissionNeverWritesAndMalformedPathsRemainClassifiable() {
        val files = chatStore.newFilesDirectory("native-cancel")
        val workspace = WorkspaceStore(files, WorkspaceStore.projectIdForSession("cancel-session"))
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        val token = CancellationToken.cancellable()
        val invocation = EffectInvocation { token.cancel() }
        try {
            tools.invoke("write", mapOf("path" to "note.txt", "content" to "not written"), token, null, "cancel", invocation)
            fail("Cancellation should propagate")
        } catch (expected: java.util.concurrent.CancellationException) { }
        assertTrue(invocation.admitted())
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, invocation.resolveFailure(true).outcome)
        assertFalse(File(File(files, workspace.projectId()), "note.txt").exists())
        val write = tools.get("write")
        assertEquals(CoreTool.PersistenceEffect.NONE, write.persistenceEffect(mapOf("path" to "../escape")))
        assertEquals(CoreTool.EffectClass.WRITE, write.effectClass(mapOf<String, Any?>("result_cursor" to null)))
        assertEquals(CoreTool.EffectClass.WRITE, write.effectClass(mapOf("result_cursor" to 7)))
    }

    @Test fun codingRouterDoesNotSealOrInventCompletionAndCursorNeverAdmits() {
        val files = chatStore.newFilesDirectory("native-coding")
        val workspace = WorkspaceStore(StorageGeneration(files), WorkspaceStore.projectIdForSession("coding-session"),
            null, null, "coding-session")
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        val scope = workspace.codingProjectScope()
        val write = tools.get("write")
        val invocation = EffectInvocation { }
        val result = write.execute(mapOf("path" to "/project/note.txt", "content" to "local content",
            "expected_sha256" to "missing", "expected_scope_version" to scope.version()), CancellationToken.cancellable(), null, invocation)
        assertTrue(result.content, result.success)
        assertTrue(invocation.admitted())
        assertNull(result.effectReceipt)
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, invocation.resolve(result, true).outcome)
        invocation.noteDecision(invocation.admission()) // Would throw if nested registry sealed the owner.
        for (name in listOf("write", "edit")) {
            val tool = tools.get(name)
            val args = mapOf<String, Any>("result_cursor" to "invalid", "path" to "/project/note.txt", "content" to "must not write")
            assertEquals(CoreTool.EffectClass.READ, tool.effectClass(args))
            val readInvocation = EffectInvocation { fail("Cursor must not admit") }
            val page = tool.execute(args, CancellationToken.cancellable(), null, readInvocation)
            assertFalse(page.success)
            assertFalse(readInvocation.admitted())
            readInvocation.noteDecision(EffectInvocation.Admission(EffectInvocation.Authorization.UNSPECIFIED,
                "test", "cursor", null, "read")) // Would throw if router sealed it.
        }
        for (cursor in listOf(null, 7)) {
            val invocationForInvalid = EffectInvocation { fail("Invalid cursor must not admit") }
            val invalid = tools.invoke("write", mapOf<String, Any?>("result_cursor" to cursor,
                "path" to "/project/note.txt", "content" to "not applied"), CancellationToken.cancellable(), null,
                "invalid-cursor", invocationForInvalid)
            assertFalse(invalid.success)
            assertFalse(invocationForInvalid.admitted())
        }
        assertEquals("local content", File(scope.rootDirectory(), "note.txt").readText())
    }
}

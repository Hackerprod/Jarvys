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
@Config(sdk = [34], application = Application::class)
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
            val session = "native-effect-session"
            val owner = store.appendConversationMessage(session, "user", "Write and edit a local note")
            val runId = "native-effect-run"
            val transcript = MainChatTranscriptStore(files, session, store)
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
            transcript.attach(loop, emptyList(), owner)
            val result = loop.run("Write and edit a local note", emptyList(), emptyList(),
                CancellationToken.cancellable(), null, owner, runId)
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
        val memory = MemoryStore(File(files, "memory"), true, testMemorySeedProvider())
        val workspace = WorkspaceStore(File(files, "workspace"), WorkspaceStore.projectIdForSession("memory-session"),
            null, null, memory, "memory-session")
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace)).withInvocationObserver(object : CoreToolRegistry.InvocationObserver {
            override fun beforeDispatch(tool: CoreTool, callId: String, arguments: Map<String, Any>) { fail("Memory must not create an episode") }
            override fun afterDispatch(tool: CoreTool, callId: String, result: CoreToolResult?, failure: Throwable?) { fail("Memory must not finish an episode") }
        })
        val content = "---\nname: Note\ndescription: A local note.\n---\nOriginal note."
        val operations = listOf(
            "write" to mapOf("path" to "/memory/note.md", "content" to content),
            "edit" to mapOf("path" to "/memory/note.md", "old_text" to "Original note.", "new_text" to "Updated note."),
            "delete" to mapOf("path" to "/memory/note.md")
        )
        for ((name, args) in operations) {
            val tool = tools.get(name)
            assertEquals(CoreTool.EffectClass.WRITE, tool.effectClass(args))
            assertEquals(CoreTool.PersistenceEffect.MEMORY, tool.persistenceEffect(args))
            val invocation = EffectInvocation { fail("Memory must not admit an external effect") }
            val result = tools.invoke(name, args, CancellationToken.cancellable(), null, "memory-$name", invocation)
            assertTrue(result.content, result.success)
            assertNull(result.effectReceipt)
            assertTrue(invocation.ownsMemoryCompletion(result.administrativeReceipt))
            assertFalse(invocation.ownsReadCompletion(result.administrativeReceipt))
            assertFalse(invocation.admitted())
        }
    }

    @Test fun disabledMemoryAliasesAndMemoryOnlyScopePreserveAuthority() {
        val files = chatStore.newFilesDirectory("native-memory-boundaries")
        val session = "memory-boundary-session"
        val memory = MemoryStore(File(files, "memory"), true, testMemorySeedProvider())
        val workspace = WorkspaceStore(File(files, "workspace"), WorkspaceStore.projectIdForSession(session),
            null, null, memory, session)
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        val content = "---\nname: Note\ndescription: A local note.\n---\nRetained text."
        val aliasInvocation = EffectInvocation { fail("Memory alias must not admit an external effect") }
        val alias = tools.invoke("write", mapOf("path" to "  /memory/note.md  ", "content" to content),
            CancellationToken.cancellable(), null, "memory-alias", aliasInvocation)
        assertTrue(alias.content, alias.success)
        assertTrue(aliasInvocation.ownsMemoryCompletion(alias.administrativeReceipt))
        assertEquals(content, workspace.read("/memory/note.md"))
        for (path in listOf("/memory/../escape.md", "/memory/skills/note.md")) {
            val rejected = tools.invoke("write", mapOf("path" to path, "content" to content), CancellationToken.cancellable())
            assertFalse(rejected.success)
            assertNull(rejected.administrativeReceipt)
        }
        val memoryOnly = WorkspaceStore(File(files, "only-workspace"), WorkspaceStore.projectIdForSession("only"),
            null, null, memory, session, true, MemoryStore.Actor.AGENT, null, true)
        val onlyTools = CoreToolRegistry(WorkspaceTools.create(memoryOnly))
        val onlyInvocation = EffectInvocation { fail("Memory-only scope must not admit an external effect") }
        val forbidden = onlyTools.invoke("write", mapOf("path" to "outside.txt", "content" to "not applied"),
            CancellationToken.cancellable(), null, "outside", onlyInvocation)
        assertFalse(forbidden.success)
        assertFalse(onlyInvocation.admitted())
        assertNull(forbidden.administrativeReceipt)
        assertFalse(File(File(files, "only-workspace"), "${memoryOnly.projectId()}/outside.txt").exists())
        memory.forConversation(session).bindAutomaticWritesForToolset().setEnabled(false)
        val disabledInvocation = EffectInvocation { fail("Disabled memory must not admit an external effect") }
        val disabled = tools.invoke("write", mapOf("path" to "/memory/note.md", "content" to "not applied"),
            CancellationToken.cancellable(), null, "disabled", disabledInvocation)
        assertFalse(disabled.success)
        assertFalse(disabledInvocation.admitted())
        assertNull(disabled.administrativeReceipt)
        assertEquals(content, File(memory.forConversation(session).bindAutomaticWritesForToolset().rootDirectory(), "note.md").readText())
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
            null, null, null, "coding-session", false)
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

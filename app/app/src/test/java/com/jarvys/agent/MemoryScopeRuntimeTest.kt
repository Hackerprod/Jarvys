package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic only: exercises the actual tool boundary and durable search coordinator. */
class MemoryScopeRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val marker = "PulsoUniqueProjectCanaryV64"
    private val preference = "PersonalPreferenceCanaryV64 concise answers"
    private fun note(name: String, text: String) = "---\nname: $name\ndescription: $name\n---\n$text\n"
    private fun root() = MemoryStore(File(temporary.root, "memory"), true, testMemorySeedProvider())
    private fun workspace(id: String, root: MemoryStore = root()) = WorkspaceStore(
        File(temporary.root, "workspaces"), WorkspaceStore.projectIdForSession(id),
        File(temporary.root, "skills").apply { mkdirs() }, null, root, id)
    private fun coordinator(id: String) = MemorySearchIndexCoordinator(
        FileMemorySearchIndex(File(temporary.root, "index/$id.bin")), true)
    private fun invoke(registry: CoreToolRegistry, tool: String, arguments: Map<String, Any>) =
        registry.invoke(tool, arguments, CancellationToken.uncancellable())

    @Test fun mainPromptAllToolPathsAndReopenedIndexDoNotCarryAnotherProjectsContext() {
        val root = root()
        val a = workspace("chat-A", root)
        val b = workspace("chat-B", root)
        a.write("/memory/project.md", note(marker, marker))
        a.write("/memory/preferences.md", note("Preference", preference))
        a.write("/memory/MEMORY.md", "# $marker\n- [Project $marker](project.md)\n")
        val first = coordinator("chat-B")
        try {
            val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(b, first))
            assertTrue(a.read("/memory/project.md").contains(marker))
            for (zone in listOf("all", "memory")) {
                val result = invoke(tools, "search_files", mapOf("query" to marker, "zone" to zone))
                assertTrue(result.success)
                assertFalse(result.content.contains(marker))
            }
            val listing = invoke(tools, "ls", mapOf("path" to "/memory/"))
            assertTrue(listing.content, listing.success)
            assertTrue(listing.content.contains("human.md"))
            assertFalse(listing.content.contains(marker))
            assertTrue(invoke(tools, "read", mapOf("path" to "/memory/human.md")).success)
            assertFalse(invoke(tools, "read", mapOf("path" to "/memory/project.md")).success)
            val original = a.read("/memory/project.md")
            assertTrue(runCatching { b.edit("/memory/project.md", marker, "changed") }.isFailure)
            runCatching { b.deleteMemoryFile("/memory/project.md") } // A missing local file may be a no-op.
            assertEquals(original, a.read("/memory/project.md"))
            assertFalse(b.searchDocuments(setOf("memory")).any { it.content.contains(marker) || it.path.contains(marker) })
            val prompt = CoreAgentRuntime.withMemoryInstructions("captain", root.forConversation("chat-B"), CorePromptBudget.standard())
            assertFalse(prompt.contains(marker)); assertFalse(prompt.contains(preference))
            assertTrue(CoreAgentRuntime.withMemoryInstructions("captain", root.forConversation("chat-A"), CorePromptBudget.standard()).contains(marker))
        } finally { first.disposeForTests() }
        val reopened = coordinator("chat-B")
        try {
            val restored = workspace("chat-B")
            assertTrue(reopened.search(restored, marker, setOf("memory", "workspace"), null).isEmpty())
            assertFalse(reopened.snapshot().any { it.content.contains(marker) })
        } finally { reopened.disposeForTests() }
    }

    @Test fun oldGlobalCachedMemoryAndConcurrentCallbacksCannotPopulateAnotherChat() {
        val indexFile = File(temporary.root, "index/legacy.bin")
        val durable = FileMemorySearchIndex(indexFile)
        durable.replaceAll(listOf(SearchDocument("memory", "foreign.md", marker, 1L)))
        val b = workspace("chat-B")
        val search = MemorySearchIndexCoordinator(durable, true)
        val a = root().forConversation("chat-A")
        val start = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val writer = Thread {
            try {
                start.await()
                repeat(30) { a.write("project.md", note(marker, "$marker $it"), MemoryStore.Actor.AGENT, "chat-A") }
            } catch (t: Throwable) { failures.add(t) } finally { finished.countDown() }
        }
        try {
            writer.start(); start.countDown()
            repeat(30) {
                assertTrue(search.search(b, marker, setOf("memory", "workspace"), null).isEmpty())
                assertFalse(search.snapshot().any { it.content.contains(marker) })
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(search.search(b, marker, setOf("memory"), null).isEmpty())
        } finally { start.countDown(); writer.join(10_000); search.disposeForTests() }
    }

    @Test fun explicitSharingRevocationAndEditedSourceAreCheckedOnEveryCachedSearch() {
        val root = root()
        val a = root.forConversation("chat-A")
        a.write("preference.md", note("Preference", preference), MemoryStore.Actor.AGENT, "chat-A")
        val b = workspace("chat-B", root)
        val search = coordinator("shared")
        try {
            assertTrue(search.search(b, "PersonalPreferenceCanaryV64", setOf("memory"), null).isEmpty())
            val grant = a.approveSharedPersonal(a.reviewForSharing("preference.md"))
            assertEquals(1, search.search(b, "PersonalPreferenceCanaryV64", setOf("memory"), null).size)
            assertTrue(root.forConversation("chat-B").compileSystemPromptProjection().contains(preference))
            a.write("preference.md", note(marker, marker), MemoryStore.Actor.AGENT, "chat-A")
            assertTrue(search.search(b, "PersonalPreferenceCanaryV64", setOf("memory"), null).isEmpty())
            assertTrue(search.search(b, marker, setOf("memory"), null).isEmpty())
            a.revokeSharedPersonal(grant.id)
            assertFalse(root.forConversation("chat-B").compileSystemPromptProjection().contains(marker))
            a.write("preference.md", note("Preference", preference), MemoryStore.Actor.AGENT, "chat-A")
            val next = a.approveSharedPersonal(a.reviewForSharing("preference.md"))
            assertEquals(1, search.search(b, "PersonalPreferenceCanaryV64", setOf("memory"), null).size)
            a.revokeSharedPersonal(next.id)
            assertTrue(search.search(b, "PersonalPreferenceCanaryV64", setOf("memory"), null).isEmpty())
        } finally { search.disposeForTests() }
    }

    @Test fun actualRuntimeModelRequestsRecompileSharedMemoryAfterNativeRevokeAndSourceEdit() {
        val owner = root()
        val donor = owner.forConversation("chat-A")
        val recipientId = "tool-assembly-test"
        val recipient = owner.forConversation(recipientId)
        val localMarker = "RecipientOnlyCanaryV64"
        donor.write("preference.md", note("Preference", preference), MemoryStore.Actor.AGENT, "chat-A")
        recipient.write("local.md", note("Recipient", localMarker), MemoryStore.Actor.AGENT, recipientId)
        val runtime = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        // Use the existing constructor without account/provider discovery; only synthetic memory is injected.
        CoreAgentRuntime::class.java.getDeclaredField("memoryStore").apply {
            isAccessible = true
            set(runtime, recipient)
            assertSame(recipient, get(runtime))
        }
        val declarations = listOf(ToolSpec("scope_probe", "test", "Synthetic scope probe", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList()))
        val token = CancellationToken.uncancellable()
        val prompts = ArrayList<String>()
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                sessionId: String, currentToken: CancellationToken): ModelReply =
                error("The runtime must use the conversation provider boundary")

            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>,
                userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                sessionId: String, currentToken: CancellationToken): ModelReply {
                assertEquals(recipientId, sessionId)
                assertEquals("Continue this conversation", userPrompt)
                assertSame(declarations, tools)
                assertSame(token, currentToken)
                assertTrue(images.isEmpty())
                prompts += systemPrompt
                return ModelReply("Synthetic response", emptyList())
            }
        }
        val model = CoreAgentModel(provider, recipientId)
        val requestModel = runtime.requestModel(model, CoreToolRegistry(emptyList()), true, null)
        assertEquals(model.contextWindow(token), requestModel.contextWindow(token))
        fun nextPrompt(): String {
            val before = prompts.size
            assertEquals("Synthetic response", requestModel.complete(emptyList(), "Continue this conversation",
                declarations, token).text)
            assertEquals(before + 1, prompts.size)
            return prompts.last().also { assertTrue(it.contains(localMarker)) }
        }

        assertFalse(nextPrompt().contains(preference))
        val grant = donor.approveSharedPersonal(donor.reviewForSharing("preference.md"))
        assertTrue(nextPrompt().contains(preference))
        donor.revokeSharedPersonal(grant.id)
        assertFalse(nextPrompt().contains(preference))
        donor.approveSharedPersonal(donor.reviewForSharing("preference.md"))
        assertTrue(nextPrompt().contains(preference))
        donor.write("preference.md", note("Project", marker), MemoryStore.Actor.AGENT, "chat-A")
        val editedSourcePrompt = nextPrompt()
        assertFalse(editedSourcePrompt.contains(preference))
        assertFalse(editedSourcePrompt.contains(marker))
        assertEquals(5, prompts.size)
        assertTrue("Previously supplied system text is immutable evidence, not silently recalled",
            prompts[1].contains(preference))
    }

    @Test fun reflectionAndCompactionWritesAreLocalAndNoMemoryWorkspaceRemainsUnavailable() {
        val root = root()
        val a = root.forConversation("chat-A")
        a.beginReflectionGroup("compaction-reflection", "chat-A")
        val reflection = WorkspaceStore(File(temporary.root, "workspaces"), WorkspaceStore.projectIdForSession("chat-A"),
            null, null, root, "chat-A", true, MemoryStore.Actor.REFLECTION, "compaction-reflection", true)
        val tools = CoreToolRegistry(WorkspaceTools.createReflectionMemoryOnly(reflection))
        val write = invoke(tools, "write", mapOf("path" to "/memory/project.md", "content" to note(marker, marker)))
        assertTrue(write.content, write.success)
        a.finishReflectionGroup("compaction-reflection", "completed")
        assertTrue(a.read("project.md").contains(marker))
        assertFalse(root.forConversation("chat-B").compileSystemPromptProjection().contains(marker))
        assertFalse(workspace("chat-B", root).searchDocuments(setOf("memory")).any { it.content.contains(marker) })
        a.undoReflectionGroup("compaction-reflection", "chat-A")
        assertTrue(runCatching { a.read("project.md") }.isFailure)
        assertFalse(root.forConversation("chat-B").compileSystemPromptProjection().contains(marker))
        val denied = WorkspaceStore(File(temporary.root, "workspaces"), WorkspaceStore.projectIdForSession("chat-C"), null, null, root, "chat-C", false)
        assertFalse(denied.memoryEnabled())
        assertTrue(runCatching { denied.read("/memory/project.md") }.isFailure)
    }

    @Test(timeout = 20_000) fun captainAndActualCrewMissionPromptsExcludeAnotherProjectsMemory() {
        val root = root()
        root.forConversation("chat-A").write("project.md", note(marker, marker), MemoryStore.Actor.AGENT, "chat-A")
        val b = root.forConversation("chat-B")
        val empty = CoreToolRegistry(emptyList())
        val captured = java.util.concurrent.atomic.AtomicReference<String>()
        val complete = CountDownLatch(1)
        fun provider(answer: (String, List<ConversationTurn>, String) -> ModelReply) = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                sessionId: String, token: CancellationToken) = answer(systemPrompt, emptyList(), userPrompt)
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>,
                userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                sessionId: String, token: CancellationToken) = answer(systemPrompt, history, userPrompt)
        }
        val manager = com.jarvys.agent.crew.CrewManager("chat-B", empty, { _, _ -> empty },
            { bot, tools, incoming ->
                val instructions = CoreAgentRuntime.crewBotInstructions(bot)
                val workerModel = CoreAgentModel(provider { system, history, mission ->
                    captured.set(system + "\n" + history.joinToString("\n") { it.content } + "\n" + mission)
                    complete.countDown()
                    ModelReply("Synthetic review complete", emptyList())
                }, "chat-B-worker")
                CoreAgentLoop(object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                        declarations: List<ToolSpec>, token: CancellationToken): ModelReply =
                        workerModel.complete(instructions, transcript, prompt, declarations, token)
                    override fun contextWindow(token: CancellationToken): Int = workerModel.contextWindow(token)
                }, tools, instructions, "chat-B-worker", CorePromptBudget.standard(), null,
                    CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
            }, null)
        try {
            val tools = CoreToolRegistry(com.jarvys.agent.crew.CrewTools.captain(manager, empty))
            var calls = 0
            val captain = CoreAgentModel(provider { system, history, prompt ->
                assertFalse(system.contains(marker))
                assertFalse(prompt.contains(marker))
                assertFalse(history.any { it.content.contains(marker) })
                if (calls++ == 0) ModelReply("", listOf(ModelReply.Call("spawn-clean", "crew_spawn", mapOf(
                    "role" to "custom", "name" to "Ping reviewer", "tools" to emptyList<String>(),
                    "mission" to "Review the current ping application requirements.", "task_title" to "Review ping application"))))
                else ModelReply("Delegated current conversation", emptyList())
            }, "chat-B")
            CoreAgentLoop(captain, tools, CoreAgentRuntime.withMemoryInstructions("Captain", b,
                CorePromptBudget.standard()), "chat-B").run("Review a ping app", emptyList(),
                CancellationToken.uncancellable(), null)
            assertTrue(complete.await(10, TimeUnit.SECONDS))
            assertTrue(captured.get().contains("current ping application"))
            assertFalse(captured.get().contains(marker))
            assertEquals(1, manager.bots().size)
            manager.bots().single().awaitTermination()
        } finally { manager.close() }
    }

}

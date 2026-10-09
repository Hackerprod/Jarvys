package com.jarvys.agent

import com.jarvys.agent.device.ScreenData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MemoryReflectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun compactionAndManualTriggersKeepMemoryPrivacyAndUsefulnessGates() {
        assertTrue(MemoryReflectionPreferences.DEFAULT_ENABLED)
        assertTrue(MemoryReflectionPolicy.shouldTrigger(true, false))
        assertFalse(MemoryReflectionPolicy.shouldTrigger(false, false))
        assertTrue(MemoryReflectionPolicy.shouldTrigger(false, true))
        assertTrue(MemoryReflectionPolicy.failureBackoffElapsed(100L, 100L))
        assertFalse(MemoryReflectionPolicy.failureBackoffElapsed(99L, 100L))
        assertFalse(MemoryReflectionPolicy.canReflect(false, true, true, true, true))
        assertFalse(MemoryReflectionPolicy.canReflect(true, false, true, true, true))
        assertFalse(MemoryReflectionPolicy.canReflect(true, true, false, true, false))
        assertFalse(MemoryReflectionPolicy.canReflect(true, true, true, false, false))
        assertTrue(MemoryReflectionPolicy.canReflect(true, true, false, true, true))
        assertTrue(MemoryReflectionPrompt.SYSTEM.contains("only an explicit user-authored confirmation"))
        assertTrue(MemoryReflectionPrompt.SYSTEM.contains("Never store secrets"))
        assertTrue(MemoryReflectionPrompt.SYSTEM.contains("exact coordinates"))
        assertFalse(MemoryReflectionPolicy.isUseful(1, 2_000))
        assertFalse(MemoryReflectionPolicy.isUseful(4, 11))
        assertTrue(MemoryReflectionPolicy.isUseful(2, 12))
    }

    @Test
    fun eachCompactionCanStartAfterThePreviousReflectionFinishesAndRunsAreCancelable() {
        val first = requireNotNull(MemoryReflectionRuntime.begin("first-reflection"))
        try {
            assertNull(MemoryReflectionRuntime.begin("second-reflection"))
            assertTrue(first.cancel())
        } finally {
            MemoryReflectionRuntime.finish("first-reflection", first)
        }
        val second = requireNotNull(MemoryReflectionRuntime.begin("second-reflection"))
        MemoryReflectionRuntime.finish("second-reflection", second)
        assertTrue(MemoryReflectionPolicy.shouldTrigger(true, false))
        assertTrue(MemoryReflectionPolicy.shouldTrigger(true, false))
        val minute = 60_000L
        assertEquals(15 * minute, MemoryReflectionPolicy.failureBackoffMillis(1))
        assertEquals(30 * minute, MemoryReflectionPolicy.failureBackoffMillis(2))
        assertEquals(24 * 60 * minute, MemoryReflectionPolicy.failureBackoffMillis(20))
    }

    @Test
    fun transcriptCheckpointTruncationSecretAndExactLocationFiltering() {
        val builderEntries = listOf(
            ReflectionTranscriptBuilder.Entry("user", "I prefer concise answers and clear headings.", "user_authored"),
            ReflectionTranscriptBuilder.Entry("user", "API key: sk-" + "A".repeat(30), "user_authored"),
            ReflectionTranscriptBuilder.Entry("user", "Meet me at 37.7749, -122.4194, 123 Market Street.", "user_authored"),
            ReflectionTranscriptBuilder.Entry("tool", "[connector result omitted as sensitive, untrusted data]", "connector:contacts"),
        )
        val payload = ReflectionTranscriptBuilder.build(builderEntries)
        assertTrue(payload.contains("I prefer concise"))
        assertFalse(payload.contains("sk-"))
        assertFalse(payload.contains("37.7749"))
        assertFalse(payload.contains("123 Market Street"))
        assertTrue(payload.contains("connector result omitted"))
        assertTrue(payload.length <= ReflectionTranscriptBuilder.MAX_PAYLOAD_CHARS)
        val oversized = JSONArray().put(JSONObject().put("role", "user").put("content", "x".repeat(5_000))).toString()
        assertTrue(ReflectionTranscriptBuilder.limitSerializedPayload(oversized, 1_000).length <= 1_000)
    }

    @Test
    fun localTranscriptPayloadUsesCheckpointMarkersOmitsExternalResultsAndNeverIncludesCompactionSummary() {
        val store = LocalRunStore(temporaryFolder.newFolder("transcript-files"))
        val session = "reflection-session"
        val firstUser = store.appendConversationMessage(session, "user", "I prefer concise answers and examples.")
        store.appendReflectionToolEvent(session, firstUser, "Contacts", "connector:contacts", "tool_result", "call-contacts")
        store.appendConversationMessage(session, "assistant",
            "Contact Alice is at 37.7749, -122.4194", null, "run-1", firstUser, "COMPLETED")
        val secondUser = store.appendConversationMessage(session, "user", "I also prefer Spanish replies.")
        val lastAssistant = store.appendConversationMessage(session, "assistant", "Entendido.", null, "run-2", secondUser, "COMPLETED")
        store.appendCompaction(session, "THIS COMPACTION SUMMARY MUST NOT BE COPIED", 1,
            "auto_preflight", "all", 1)

        val firstPayload = store.buildReflectionPayload(session)
        assertEquals(lastAssistant, firstPayload.endMessageId)
        assertTrue(firstPayload.text.contains("I prefer concise answers"))
        assertTrue(firstPayload.text.contains("connector result omitted"))
        assertFalse(firstPayload.text.contains("Contact Alice"))
        assertFalse(firstPayload.text.contains("37.7749"))
        assertFalse(firstPayload.text.contains("THIS COMPACTION SUMMARY"))
        assertEquals(2, firstPayload.completedAssistantSteps)
        assertTrue(store.hasPendingCompactionReflection(session))

        store.appendReflectionCheckpoint(session, firstPayload, "reflection-1", "completed")
        assertFalse(store.hasPendingCompactionReflection(session))
        store.appendConversationMessage(session, "user", "Please keep replies brief.")
        store.appendConversationMessage(session, "assistant", "Understood.", null, "run-3", "", "COMPLETED")
        val nextPayload = store.buildReflectionPayload(session)
        assertFalse(nextPayload.text.contains("I prefer concise answers"))
        assertTrue(nextPayload.text.contains("Please keep replies brief"))
    }

    @Test
    fun automaticPayloadBoundaryExcludesMessagesThatArriveWhileReflectionIsQueued() {
        val store = LocalRunStore(temporaryFolder.newFolder("snapshot-files"))
        val session = "snapshot-session"
        store.appendConversationMessage(session, "user", "I prefer short answers and examples.")
        val firstAssistant = store.appendConversationMessage(session, "assistant", "I will use concise examples.",
            null, "run-1", store.latestUserMessageId(session), "COMPLETED")
        store.appendConversationMessage(session, "user", "Also use Spanish for explanations.")
        store.appendConversationMessage(session, "assistant", "Entendido.", null,
            "run-2", store.latestUserMessageId(session), "COMPLETED")

        val snapshot = store.buildReflectionPayload(session, firstAssistant)
        assertEquals(firstAssistant, snapshot.endMessageId)
        assertTrue(snapshot.text.contains("short answers"))
        assertFalse(snapshot.text.contains("use Spanish"))
        assertEquals(1, snapshot.completedAssistantSteps)
    }

    @Test
    fun reflectionCommitAtomicallyStoresCheckpointChatSummaryAndGroupUndoMarker() {
        val store = LocalRunStore(temporaryFolder.newFolder("reflection-commit-files"))
        val session = "commit-session"
        store.appendConversationMessage(session, "user", "I prefer compact explanations.")
        val assistantId = store.appendConversationMessage(session, "assistant", "Understood.",
            null, "run", store.latestUserMessageId(session), "COMPLETED")
        val payload = store.buildReflectionPayload(session)
        store.appendReflectionCommit(session, payload, "group-commit", "You prefer compact explanations.",
            listOf(1L, 2L), "completed", "compaction-event")
        assertFalse(store.hasPendingCompactionReflection(session))
        val row = store.readConversationTimeline(session).last { it.memoryReflectionGroupId == "group-commit" }
        assertEquals("You prefer compact explanations.", row.text)
        assertEquals(listOf(1L, 2L), row.memoryRevisionIds)
        assertEquals(assistantId, payload.endMessageId)
        store.appendReflectionUndoEvent(session, "group-commit")
        assertEquals("undone", store.readConversationTimeline(session).last { it.memoryReflectionGroupId == "group-commit" }.detail)
    }

    @Test
    fun memoryReflectionGroupIsJournaledAndUndoneAsAWholeWithConflictProtection() {
        val root = temporaryFolder.newFolder("reflection-memory")
        val store = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val legacyRevision = store.write("persona.md", note("Interaction", "Legacy preference."),
            MemoryStore.Actor.AGENT, "old-session")
        assertNull(legacyRevision.reflectionGroupId)
        val oldPersona = File(root, "persona.md").readText()
        val oldHuman = File(root, "human.md").readText()
        val group = "reflection-group-1"
        store.beginReflectionGroup(group, "session")
        store.write("persona.md", note("Interaction", "User prefers concise, direct answers."),
            MemoryStore.Actor.REFLECTION, "session", group)
        store.write("human.md", note("Person", "User explicitly shared stable preferences."),
            MemoryStore.Actor.REFLECTION, "session", group)
        store.finishReflectionGroup(group, "completed")
        val revisions = store.reflectionGroupRevisions(group)
        assertEquals(2, revisions.size)
        assertTrue(revisions.all { it.actor == MemoryStore.Actor.REFLECTION && it.reflectionGroupId == group })

        val undone = store.undoReflectionGroup(group, "session")
        assertEquals(2, undone.size)
        assertTrue(undone.all { it.actor == MemoryStore.Actor.USER })
        assertEquals(oldPersona, File(root, "persona.md").readText())
        assertEquals(oldHuman, File(root, "human.md").readText())

        val conflictGroup = "reflection-group-2"
        store.beginReflectionGroup(conflictGroup, "session")
        store.write("persona.md", note("Interaction", "User likes examples."),
            MemoryStore.Actor.REFLECTION, "session", conflictGroup)
        store.finishReflectionGroup(conflictGroup, "completed")
        store.write("persona.md", note("Interaction", "User likes examples and brevity."), MemoryStore.Actor.USER, "session")
        val before = File(root, "persona.md").readText()
        runCatching { store.undoReflectionGroup(conflictGroup, "session") }
            .onSuccess { error("Group undo must not overwrite a newer user edit") }
        assertEquals(before, File(root, "persona.md").readText())
    }

    @Test
    fun reflectionWorkerOnlyHasMemoryToolsAndRejectsConnectorMcpAndOutsidePaths() {
        val files = temporaryFolder.newFolder("worker-files")
        val memoryRoot = File(files, "memory")
        val memory = MemoryStore(memoryRoot, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val workspace = WorkspaceStore(File(files, "workspaces"), "a".repeat(24), null, null,
            memory, "reflection-test", true, MemoryStore.Actor.REFLECTION, "worker-group", true)
        val tools = WorkspaceTools.createReflectionMemoryOnly(workspace)
        val registry = CoreToolRegistry(tools)
        assertEquals(listOf("ls", "read", "write", "edit", "delete"), registry.names())
        assertFalse(registry.names().any { it.contains("mcp") || it.contains("contact") || it.contains("delegate") })
        val outside = registry.invoke("write", mapOf("path" to "/workspace/outside.md", "content" to "x"), CancellationToken.uncancellable())
        assertFalse(outside.success)

        var calls = 0
        val provider = ScriptedProvider { systemPrompt, _, _, declarations, _ ->
            assertTrue(systemPrompt.contains("only an explicit user-authored confirmation"))
            assertTrue(systemPrompt.contains("exact coordinates"))
            assertEquals(listOf("ls", "read", "write", "edit", "delete"), declarations.map { it.name })
            when (calls++) {
                0 -> ModelReply("", listOf(ModelReply.Call("bad-mcp", "mcp_read", emptyMap())))
                1 -> ModelReply("", listOf(ModelReply.Call("bad-connector", "contacts_read_contacts", emptyMap())))
                2 -> ModelReply("", listOf(ModelReply.Call("bad-delegate", "delegate_subtask", emptyMap())))
                3 -> ModelReply("", listOf(ModelReply.Call("bad-path", "write",
                    mapOf("path" to "/skills/secret.md", "content" to "not allowed"))))
                4 -> ModelReply("", listOf(ModelReply.Call("precise-location", "write", mapOf(
                    "path" to "human.md", "content" to note("Person", "Home coordinates 37.7749, -122.4194.")))))
                5 -> ModelReply("", listOf(ModelReply.Call("write-memory", "write", mapOf(
                    "path" to "/memory/persona.md",
                    "content" to note("Interaction", "User prefers short responses and examples."),
                ))))
                else -> ModelReply("Recordé una preferencia expresada por la persona.", emptyList())
            }
        }
        val worker = MemoryReflectionWorker("reflection-test", "worker-group", memory, tools,
            CoreAgentModel(provider, "reflection-test"))
        val result = worker.run("""[{"role":"user","content":"I prefer short responses and examples."}]""",
            CancellationToken.uncancellable())
        memory.finishReflectionGroup(result.reflectionId, "completed")
        assertEquals(1, result.revisions.size)
        assertEquals(MemoryStore.Actor.REFLECTION, result.revisions.single().actor)
        assertEquals("worker-group", result.revisions.single().reflectionGroupId)
        assertEquals("User prefers short responses and examples.", MemoryUiLogic.parseDocument(
            "persona.md", File(memoryRoot, "persona.md").readText()).body.trim())
        assertFalse(File(files, "workspace/outside.md").exists())
        assertFalse(File(files, "skills/secret.md").exists())
        assertFalse(File(memoryRoot, "human.md").readText().contains("37.7749"))
    }

    @Test
    fun fakeReflectionWritesDatedEvidenceToPreferencesAndTheIncrementalIndexFindsIt() {
        val root = temporaryFolder.newFolder("dated-evidence-memory")
        val memory = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val coordinator = MemorySearchIndexCoordinator(
            FileMemorySearchIndex(File(temporaryFolder.root, "dated-evidence-index.bin")), true)
        val group = "dated-evidence-group"
        val workspace = WorkspaceStore(File(temporaryFolder.root, "dated-evidence-workspace"),
            "d".repeat(24), null, null, memory, "dated-evidence-session", true,
            MemoryStore.Actor.REFLECTION, group, true)
        val tools = WorkspaceTools.createReflectionMemoryOnly(workspace)
        var calls = 0
        val datedPage = """---
name: Preferences
description: Stable preferences shared by the user.
---
- Prefers concise explanations. (said by user in chat, 2026-10-05)
""".trimIndent()
        val provider = ScriptedProvider { systemPrompt, _, _, declarations, _ ->
            assertTrue(systemPrompt.contains("captured user message"))
            assertTrue(systemPrompt.contains("short evidence source"))
            assertTrue(systemPrompt.contains("preferences.md"))
            assertEquals(listOf("ls", "read", "write", "edit", "delete"), declarations.map { it.name })
            if (calls++ == 0) ModelReply("", listOf(ModelReply.Call("dated-write", "write",
                mapOf("path" to "/memory/preferences.md", "content" to datedPage))))
            else ModelReply("Updated the dated preference.", emptyList())
        }
        try {
            val payload = JSONArray().put(JSONObject()
                .put("kind", "user")
                .put("text", "I prefer concise explanations.")
                .put("source_message_id", "user-1")
                .put("captured_at", "2026-10-05T10:15:00.000Z")).toString()
            val result = MemoryReflectionWorker("dated-evidence-session", group, memory, tools,
                CoreAgentModel(provider, "dated-evidence-session")).run(payload, CancellationToken.uncancellable())
            assertEquals(1, result.revisions.size)
            assertEquals(datedPage, memory.readUserFile("preferences.md"))
            val indexed = coordinator.snapshot().single { it.zone == "memory" && it.path == "preferences.md" }
            assertEquals("preferences.md", Bm25SearchRanker().rank("concise 2026-10-05",
                listOf(indexed)).single().document.path)
            memory.finishReflectionGroup(group, "completed")
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun cancellationAfterAWriteLeavesAnExplicitPartialGroupThatCanBeUndone() {
        val files = temporaryFolder.newFolder("cancel-worker-files")
        val memoryRoot = File(files, "memory")
        val memory = MemoryStore(memoryRoot, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val workspace = WorkspaceStore(File(files, "workspaces"), "b".repeat(24), null, null,
            memory, "cancel-worker", true, MemoryStore.Actor.REFLECTION, "cancel-group", true)
        val tools = WorkspaceTools.createReflectionMemoryOnly(workspace)
        val token = CancellationToken.cancellable()
        var calls = 0
        val provider = ScriptedProvider { _, _, _, _, currentToken ->
            when (calls++) {
                0 -> ModelReply("", listOf(ModelReply.Call("write", "write", mapOf(
                    "path" to "/memory/persona.md", "content" to note("Interaction", "User likes short answers."),
                ))))
                else -> { currentToken.cancel(); ModelReply("done", emptyList()) }
            }
        }
        val worker = MemoryReflectionWorker("cancel-worker", "cancel-group", memory, tools,
            CoreAgentModel(provider, "cancel-worker"))
        val failure = runCatching {
            worker.run("[{\"role\":\"user\",\"content\":\"I prefer short answers.\"}]", token)
        }.exceptionOrNull()
        assertTrue(failure is MemoryReflectionWorker.Failure)
        assertTrue((failure as MemoryReflectionWorker.Failure).partial)
        assertTrue(File(memoryRoot, "persona.md").readText().contains("User likes short answers"))
        assertEquals(1, memory.reflectionGroupRevisions("cancel-group").size)
        memory.undoReflectionGroup("cancel-group", "cancel-worker")
        assertFalse(File(memoryRoot, "persona.md").readText().contains("User likes short answers"))
    }

    @Test
    fun providerInterruptionBeforeAnyWriteFailsAndLeavesARolledBackGroupWithoutRetry() {
        val files = temporaryFolder.newFolder("provider-before-write-files")
        val root = File(files, "memory")
        val memory = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val session = "provider-before-write"
        val group = "provider-before-write-group"
        val originalPersona = File(root, "persona.md").readText()
        val originalHuman = File(root, "human.md").readText()
        val originalRevisionIds = memory.listRevisions(null, null).map { it.id }.sorted()
        val workspace = WorkspaceStore(File(files, "workspaces"), "e".repeat(24), null, null,
            memory, session, true, MemoryStore.Actor.REFLECTION, group, true)
        var writes = 0
        val tools = countReflectionWrites(WorkspaceTools.createReflectionMemoryOnly(workspace)) { writes++ }
        var requests = 0
        var cancellations = 0
        val token = CancellationToken.cancellable()
        token.registerCancelAction { cancellations++ }
        val transportCause = IOException("Synthetic connection reset")
        val providerFailure = ProviderTransportException("Synthetic provider interruption", transportCause)
        val provider = ScriptedProvider { _, _, _, _, current ->
            assertSame(token, current)
            assertEquals("Reflection must not retry an interrupted provider operation", 1, ++requests)
            throw providerFailure
        }
        val worker = MemoryReflectionWorker(session, group, memory, tools, CoreAgentModel(provider, session))

        val failure = assertThrows(MemoryReflectionWorker.Failure::class.java) {
            worker.run("""[{"role":"user","content":"I prefer short answers."}]""", token)
        }

        assertSame(providerFailure, failure.cause)
        assertSame(transportCause, failure.cause?.cause)
        assertEquals(group, failure.reflectionId)
        assertFalse(failure.partial)
        assertTrue(failure.revisions.isEmpty())
        assertEquals("rolled_back", memory.reflectionGroupStatus(group))
        assertTrue(memory.reflectionGroupRevisions(group).isEmpty())
        assertEquals(originalRevisionIds, memory.listRevisions(null, null).map { it.id }.sorted())
        assertEquals(originalPersona, File(root, "persona.md").readText())
        assertEquals(originalHuman, File(root, "human.md").readText())
        assertEquals(1, requests)
        assertEquals(0, writes)
        assertEquals(0, cancellations)
        assertFalse(token.isCancellationRequested)
        assertFalse(token.isTimedOut)

        val reopened = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        assertEquals("rolled_back", reopened.reflectionGroupStatus(group))
        assertTrue(reopened.reflectionGroupRevisions(group).isEmpty())
        assertEquals(originalRevisionIds, reopened.listRevisions(null, null).map { it.id }.sorted())
    }

    @Test
    fun providerInterruptionAfterOneWriteFailsAndRetainsExactlyOnePartialRevisionWithoutReplay() {
        val files = temporaryFolder.newFolder("provider-after-write-files")
        val root = File(files, "memory")
        val memory = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val session = "provider-after-write"
        val group = "provider-after-write-group"
        val originalPersona = File(root, "persona.md").readText()
        val originalHuman = File(root, "human.md").readText()
        val originalRevisionIds = memory.listRevisions(null, null).map { it.id }.sorted()
        val updatedPersona = note("Interaction", "User prefers concise answers and examples.")
        val workspace = WorkspaceStore(File(files, "workspaces"), "f".repeat(24), null, null,
            memory, session, true, MemoryStore.Actor.REFLECTION, group, true)
        var writes = 0
        val tools = countReflectionWrites(WorkspaceTools.createReflectionMemoryOnly(workspace)) { writes++ }
        var requests = 0
        var cancellations = 0
        val token = CancellationToken.cancellable()
        token.registerCancelAction { cancellations++ }
        val providerFailure = ProviderHttpException("Synthetic service unavailable after memory write", 503, 0L, null)
        val provider = ScriptedProvider { _, history, _, _, current ->
            assertSame(token, current)
            when (++requests) {
                1 -> ModelReply("", listOf(ModelReply.Call("retained-memory-write", "write", mapOf(
                    "path" to "/memory/persona.md", "content" to updatedPersona,
                ))))
                2 -> {
                    assertEquals(1, writes)
                    assertEquals(1, memory.reflectionGroupRevisions(group).size)
                    assertEquals(1, history.count {
                        it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolCallId == "retained-memory-write"
                    })
                    throw providerFailure
                }
                else -> error("Interrupted memory reflection must not retry inference or replay its write")
            }
        }
        val worker = MemoryReflectionWorker(session, group, memory, tools, CoreAgentModel(provider, session))

        val failure = assertThrows(MemoryReflectionWorker.Failure::class.java) {
            worker.run("""[{"role":"user","content":"I prefer concise answers and examples."}]""", token)
        }

        assertSame(providerFailure, failure.cause)
        assertEquals(group, failure.reflectionId)
        assertTrue(failure.partial)
        assertEquals("partial", memory.reflectionGroupStatus(group))
        val revision = memory.reflectionGroupRevisions(group).single()
        assertEquals(listOf(revision.id), failure.revisions.map { it.id })
        assertEquals(MemoryStore.Actor.REFLECTION, revision.actor)
        assertEquals(session, revision.conversationId)
        assertEquals(group, revision.reflectionGroupId)
        assertEquals("persona.md", revision.path)
        assertEquals("EDIT", revision.operation)
        assertTrue(revision.previousExists)
        assertTrue(revision.newExists)
        assertEquals(originalPersona, revision.previousContent)
        assertEquals(updatedPersona, revision.newContent)
        assertEquals((originalRevisionIds + revision.id).sorted(), memory.listRevisions(null, null).map { it.id }.sorted())
        assertEquals(updatedPersona, File(root, "persona.md").readText())
        assertEquals(originalHuman, File(root, "human.md").readText())
        assertEquals(2, requests)
        assertEquals(1, writes)
        assertEquals(0, cancellations)
        assertFalse(token.isCancellationRequested)
        assertFalse(token.isTimedOut)

        val reopened = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        assertEquals("partial", reopened.reflectionGroupStatus(group))
        assertEquals(listOf(revision.id), reopened.reflectionGroupRevisions(group).map { it.id })
        assertEquals(updatedPersona, File(root, "persona.md").readText())
        assertEquals((originalRevisionIds + revision.id).sorted(), reopened.listRevisions(null, null).map { it.id }.sorted())
        assertEquals(1, writes)
        assertEquals(2, requests)
    }

    @Test
    fun abandonedInProgressReflectionRecoversAsPartialAfterProcessRestart() {
        val root = temporaryFolder.newFolder("abandoned-group")
        val activeStore = MemoryStore(root, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val group = "abandoned-reflection"
        activeStore.beginReflectionGroup(group, "session")
        activeStore.write("persona.md", note("Interaction", "User prefers brief replies."),
            MemoryStore.Actor.REFLECTION, "session", group)
        activeStore.releaseReflectionGroup(group)

        val restartedStore = MemoryStore(root, true, testMemorySeedProvider())
        restartedStore.ensureInitialized()
        assertEquals("partial", restartedStore.reflectionGroupStatus(group))
        assertEquals(1, restartedStore.reflectionGroupRevisions(group).size)
        assertTrue(File(root, "persona.md").readText().contains("brief replies"))
        restartedStore.undoReflectionGroup(group, "session")
    }

    @Test
    fun reflectionWorkerHasFiniteModelTurnLimitAndDoesNotLoopRetryingTools() {
        val files = temporaryFolder.newFolder("turn-limit-files")
        val memoryRoot = File(files, "memory")
        val memory = MemoryStore(memoryRoot, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val workspace = WorkspaceStore(File(files, "workspaces"), "c".repeat(24), null, null,
            memory, "turn-limit", true, MemoryStore.Actor.REFLECTION, "turn-limit-group", true)
        val tools = WorkspaceTools.createReflectionMemoryOnly(workspace)
        var providerCalls = 0
        val provider = ScriptedProvider { _, _, _, declarations, _ ->
            assertEquals(listOf("ls", "read", "write", "edit", "delete"), declarations.map { it.name })
            val index = providerCalls++
            ModelReply("", listOf(ModelReply.Call("list-$index", "ls", mapOf("path" to "/memory/missing-$index"))))
        }
        val worker = MemoryReflectionWorker("turn-limit", "turn-limit-group", memory, tools,
            CoreAgentModel(provider, "turn-limit"))
        val result = runCatching {
            worker.run("[{\"role\":\"user\",\"content\":\"Remember this preference.\"}]",
                CancellationToken.uncancellable())
        }.exceptionOrNull()
        assertTrue(result is MemoryReflectionWorker.Failure)
        assertEquals(MemoryReflectionWorker.MAX_MODEL_TURNS, providerCalls)
        assertTrue(memory.reflectionGroupRevisions("turn-limit-group").isEmpty())
    }

    private fun note(name: String, body: String) = "---\nname: $name\ndescription: Durable user preference\n---\n$body\n"

    private fun countReflectionWrites(tools: List<CoreTool>, onWrite: () -> Unit): List<CoreTool> = tools.map { tool ->
        if (tool.declaration().name != "write") tool else object : CoreTool {
            override fun declaration(): ToolSpec = tool.declaration()
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                onWrite()
                return tool.execute(arguments, token)
            }
        }
    }

    private class ScriptedProvider(
        private val responder: (String, List<ConversationTurn>, String, List<ToolSpec>, CancellationToken) -> ModelReply,
    ) : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>,
                              sessionId: String, token: CancellationToken): ModelReply =
            responder(systemPrompt, emptyList(), userPrompt, tools, token)

        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                                          images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String,
                                          token: CancellationToken): ModelReply =
            responder(systemPrompt, history, userPrompt, tools, token)
    }
}

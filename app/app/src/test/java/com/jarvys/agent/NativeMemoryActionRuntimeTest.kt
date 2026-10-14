package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic memory only. Native memory tools run directly while keeping their real state boundaries. */
class NativeMemoryActionRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token = CancellationToken.uncancellable()
    private fun root() = MemoryStore(temporary.newFolder(), true, testMemorySeedProvider())
    private fun note(value: String) = "---\nname: Note\ndescription: Synthetic note\n---\n$value\n"
    private fun workspace(root: MemoryStore, enabled: Boolean = true) = WorkspaceStore(
        File(temporary.root, "workspaces"), WorkspaceStore.projectIdForSession("session"),
        File(temporary.root, "skills").apply { mkdirs() }, null, root, "session", enabled)

    @Test fun memoryWorkspaceToolsWriteEditDeleteAndPreserveOrdinaryFiles() {
        val root = root()
        val workspace = workspace(root)
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        val selected = root.forConversation("session")

        val write = tools.invoke("write", mapOf("path" to "/memory/new.md", "content" to note("first")), token)
        assertTrue(write.content, write.success)
        assertTrue(selected.read("new.md").contains("first"))

        val edit = tools.invoke("edit", mapOf("path" to "/memory/new.md", "old_text" to "first", "new_text" to "updated"), token)
        assertTrue(edit.content, edit.success)
        assertTrue(selected.read("new.md").contains("updated"))

        val delete = tools.invoke("delete", mapOf("path" to "/memory/new.md"), token)
        assertTrue(delete.content, delete.success)
        assertFalse(File(selected.rootDirectory(), "new.md").exists())

        workspace.write("/ordinary.txt", "ordinary")
        assertEquals("ordinary", workspace.read("/ordinary.txt"))
    }

    @Test fun disabledMemoryRemainsUnavailableWhileOrdinaryWorkspaceWritesWork() {
        val root = root()
        val workspace = workspace(root, enabled = false)
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        assertFalse(tools.invoke("write", mapOf("path" to "/memory/new.md", "content" to note("no")), token).success)
        workspace.write("/ordinary.txt", "still available")
        assertEquals("still available", workspace.read("/ordinary.txt"))
    }

    @Test fun exactToolArgumentsAreCapturedOnceBeforeTheSharedEffect() {
        val root = root()
        val workspace = workspace(root)
        var contentReads = 0
        val args = object : LinkedHashMap<String, Any>() {
            override fun get(key: String): Any? = if (key == "content") {
                contentReads++; note(if (contentReads == 1) "captured" else "changed")
            } else super.get(key)
        }.apply { put("path", "/memory/note.md"); put("content", "placeholder") }
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))

        val result = tools.invoke("write", args, token)

        assertTrue(result.content, result.success)
        assertEquals(1, contentReads)
        assertTrue(workspace.read("/memory/note.md").contains("captured"))
        assertFalse(workspace.read("/memory/note.md").contains("changed"))
    }

    @Test fun compoundNativeMutationCompletesAndCancelledActionDoesNotRun() {
        val store = root().forConversation("session")
        val actions = MemoryNativeActions.forTool(store)
        val changes = AtomicInteger()
        val listener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) { changes.incrementAndGet() }
            override fun onMemoryCleared() = Unit
        }
        store.addChangeListener(listener)
        try {
            actions.run(store, token) { store.createDirectoryForUser("two/levels", "session") }
            assertEquals(2, changes.get())
            assertTrue(File(store.rootDirectory(), "two/levels/MEMORY.md").exists())

            val cancelled = CancellationToken.cancellable().also { it.cancel() }
            assertThrows(CancellationException::class.java) {
                actions.run(store, cancelled) { store.write("must-not-exist.md", note("cancelled"), MemoryStore.Actor.AGENT, "session") }
            }
            assertFalse(File(store.rootDirectory(), "must-not-exist.md").exists())
        } finally { store.removeChangeListener(listener) }
    }

    @Test fun memoryViewStateChangeInvalidatesPreparedActionWithoutPartialEffect() {
        val store = root().forConversation("session")
        val actions = MemoryNativeActions.forTool(store)
        val prepared = actions.prepare(store, token)
        store.write("changed.md", note("new state"), MemoryStore.Actor.USER, "session")
        val failure = runCatching { prepared.execute { store.write("must-not-exist.md", note("stale"), MemoryStore.Actor.AGENT, "session") } }
            .exceptionOrNull()
        assertNotNull(failure)
        assertFalse(File(store.rootDirectory(), "must-not-exist.md").exists())
        prepared.close()
    }

    @Test fun reflectionRestoreCompletesAllUndoRevisions() {
        val store = root().forConversation("session").also { it.ensureInitialized() }
        val beforeHuman = store.read("human.md"); val beforePersona = store.read("persona.md")
        store.beginReflectionGroup("group", "session")
        store.write("human.md", note("new human"), MemoryStore.Actor.REFLECTION, "session", "group")
        store.write("persona.md", note("new persona"), MemoryStore.Actor.REFLECTION, "session", "group")
        store.finishReflectionGroup("group", "completed")

        val result = MemoryNativeActions.forTool(store).run(store, token) { store.undoReflectionGroup("group", "session") }

        assertEquals(2, result.size)
        assertEquals(beforeHuman, store.read("human.md")); assertEquals(beforePersona, store.read("persona.md"))
    }
}

package com.jarvys.agent

import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Synthetic memory only. Real family-lock, journal and shared tool-class effect boundaries. */
class NativeMemoryActionRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token = CancellationToken.uncancellable()
    private val modify = NativeActionPolicy.Operation.MEMORY_MODIFY
    private fun root() = MemoryStore(temporary.newFolder(), true, testMemorySeedProvider())
    private fun note(value: String) = "---\nname: Note\ndescription: Synthetic note\n---\n$value\n"
    private fun actions(policy: NativeActionPolicy, review: (ApprovalSummary, CancellationToken) -> ApprovalDecision = { _, _ -> error("Unexpected ASK") }): MemoryNativeActions {
        val constructor = MemoryNativeActions::class.java.declaredConstructors.single { it.parameterTypes.size == 2 }
        constructor.isAccessible = true
        return constructor.newInstance(policy, review) as MemoryNativeActions
    }
    private fun workspace(root: MemoryStore, enabled: Boolean = true) = WorkspaceStore(
        File(temporary.root, "workspaces"), WorkspaceStore.projectIdForSession("session"),
        File(temporary.root, "skills").apply { mkdirs() }, null, root, "session", enabled)

    @Test fun freshScopeAllowsFirstWriteInOneAttemptAndDenyDoesNotInitialize() {
        val root = root()
        val workspace = workspace(root)
        val registry = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        root.nativeActionPolicy().set(modify, AutonomyPolicy.DENY)
        val selected = root.forConversation("session")
        val before = selected.nativeStateFingerprint(false)
        assertFalse(registry.invoke("write", mapOf("path" to "/memory/new.md", "content" to note("first")), token).success)
        assertEquals(before, selected.nativeStateFingerprint(false))
        assertFalse(File(selected.rootDirectory(), "human.md").exists())
        root.nativeActionPolicy().set(modify, AutonomyPolicy.ALLOW)
        val result = registry.invoke("write", mapOf("path" to "/memory/new.md", "content" to note("first")), token)
        assertTrue(result.content, result.success)
        assertTrue(selected.read("new.md").contains("first"))
    }

    @Test fun freshScopeAskBindsAfterInitializationAndOnlyReviewsOnce() {
        val selected = root().forConversation("session")
        val policy = selected.nativeActionPolicy().apply { set(modify, AutonomyPolicy.ASK) }
        var reviews = 0
        val actions = actions(policy) { summary, _ ->
            reviews++
            assertTrue(File(selected.rootDirectory(), "human.md").exists())
            assertTrue(summary.lines.any { it == note("reviewed") })
            ApprovalDecision.APPROVED
        }
        actions.run(modify, selected, listOf("new.md", note("reviewed")), token) {
            selected.write("new.md", note("reviewed"), MemoryStore.Actor.AGENT, "session")
        }
        assertEquals(1, reviews)
        assertTrue(selected.read("new.md").contains("reviewed"))
    }

    @Test fun sameToolClassesEnforceModifyDeleteAndCapabilityWithoutAffectingOrdinaryFiles() {
        val root = root()
        val ws = workspace(root)
        ws.write("/memory/note.md", note("old"))
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(ws, null))
        root.nativeActionPolicy().set(modify, AutonomyPolicy.DENY)
        assertFalse(tools.invoke("edit", mapOf("path" to "/memory/note.md", "old_text" to "old", "new_text" to "new"), token).success)
        root.nativeActionPolicy().set(NativeActionPolicy.Operation.MEMORY_DELETE, AutonomyPolicy.DENY)
        assertFalse(tools.invoke("delete", mapOf("path" to "/memory/note.md"), token).success)
        assertTrue(ws.read("/memory/note.md").contains("old"))
        val denied = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace(root, false), null))
        root.nativeActionPolicy().set(modify, AutonomyPolicy.ALLOW)
        assertFalse(denied.invoke("write", mapOf("path" to "/memory/other.md", "content" to note("no")), token).success)
        var ordinary = 0
        MemoryToolActions.run(ws, modify, "ordinary.txt", emptyList(), token) { ordinary++; Unit }
        assertEquals(1, ordinary)
    }

    @Test fun exactArgumentsAreReadOnlyOnceBeforeTheSharedToolExecution() {
        val root = root()
        val ws = workspace(root)
        var contentReads = 0
        val args = object : LinkedHashMap<String, Any>() {
            override fun get(key: String): Any? = if (key == "content") {
                contentReads++; note(if (contentReads == 1) "reviewed" else "unreviewed")
            } else super.get(key)
        }.apply { put("path", "/memory/note.md"); put("content", "placeholder") }
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(ws, null))
        val result = tools.invoke("write", args, token)
        assertTrue(result.content, result.success)
        assertEquals(1, contentReads)
        assertTrue(ws.read("/memory/note.md").contains("reviewed"))
        assertFalse(ws.read("/memory/note.md").contains("unreviewed"))
    }

    @Test fun unchangedPolicyCompletesBothDirectoryTransactionsButRevocationStopsTheSecond() {
        for (revoke in listOf(false, true)) {
            val store = root().forConversation("session")
            val policy = store.nativeActionPolicy()
            val prepared = actions(policy).prepare(modify, store, listOf("two/levels"), token)
            var changes = 0
            val listener = object : MemoryStore.MemoryChangeListener {
                override fun onMemoryChanged(revision: MemoryStore.Revision) {
                    changes++
                    if (revoke && changes == 1) policy.set(modify, AutonomyPolicy.DENY)
                }
                override fun onMemoryCleared() = Unit
            }
            store.addChangeListener(listener)
            try {
                val outcome = runCatching { prepared.use { it.execute { store.createDirectoryForUser("two/levels", "session") } } }
                assertEquals(!revoke, outcome.isSuccess)
                assertEquals(if (revoke) 1 else 2, changes)
                assertTrue(File(store.rootDirectory(), "two/MEMORY.md").exists())
                assertEquals(!revoke, File(store.rootDirectory(), "two/levels/MEMORY.md").exists())
            } finally { store.removeChangeListener(listener) }
        }
    }

    @Test fun revocationWhileWaitingForMemoryLockBlocksActualJournalMutationIncludingReflectionParent() {
        for (revokeParent in listOf(false, true)) {
            val store = root().forConversation("session")
            val policy = store.nativeActionPolicy()
            val prepared = actions(policy).prepare(modify, store, listOf("delayed.md", note("delayed")), token)
            val parent = policy.prepare(NativeActionPolicy.Operation.MEMORY_REFLECTION, NativeActionPolicy.binding("exact reflection"))
            val lock = MemoryStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(store) as ReentrantLock
            val waiting = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            lock.lock()
            val thread = Thread {
                try {
                    NativeEffectDispatchScope.enter { parent.admit(token) }.use {
                        prepared.executeUnlocked { waiting.countDown(); store.write("delayed.md", note("delayed"), MemoryStore.Actor.AGENT, "session") }
                    }
                } catch (e: Throwable) { failure.set(e) }
            }
            try {
                thread.start(); assertTrue(waiting.await(5, TimeUnit.SECONDS))
                policy.set(if (revokeParent) NativeActionPolicy.Operation.MEMORY_REFLECTION else modify, AutonomyPolicy.DENY)
            } finally { lock.unlock() }
            thread.join(5_000); prepared.close(); parent.close()
            assertFalse(thread.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertFalse(File(store.rootDirectory(), "delayed.md").exists())
        }
    }

    @Test fun exactRestoreActionCompletesAllReflectionUndoRevisions() {
        val store = root().forConversation("session").also { it.ensureInitialized() }
        val beforeHuman = store.read("human.md"); val beforePersona = store.read("persona.md")
        store.beginReflectionGroup("group", "session")
        store.write("human.md", note("new human"), MemoryStore.Actor.REFLECTION, "session", "group")
        store.write("persona.md", note("new persona"), MemoryStore.Actor.REFLECTION, "session", "group")
        store.finishReflectionGroup("group", "completed")
        val result = actions(store.nativeActionPolicy()).run(NativeActionPolicy.Operation.MEMORY_RESTORE, store, listOf("group"), token) {
            store.undoReflectionGroup("group", "session")
        }
        assertEquals(2, result.size)
        assertEquals(beforeHuman, store.read("human.md")); assertEquals(beforePersona, store.read("persona.md"))
    }
}

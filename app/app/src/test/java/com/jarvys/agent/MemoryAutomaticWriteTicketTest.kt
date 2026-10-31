package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryAutomaticWriteTicketTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = MemoryStore(temporary.newFolder(), true, testMemorySeedProvider())
    private fun note(body: String) = "---\nname: Note\ndescription: Synthetic note.\n---\n$body\n"
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejected automatic write") }
        catch (_: MemoryWriteGate.RejectedException) { }
    }
    private fun otherThread(block: () -> Unit) {
        val work = FutureTask { block() }
        Thread(work, "memory-gate-toggle-test").apply { isDaemon = true; start() }
        work.get(3, TimeUnit.SECONDS)
    }

    @Test fun unboundAutomaticWriteFailsButExplicitUserWriteWorksOff() {
        val owner = root()
        val memory = owner.forConversation("session")
        memory.ensureInitialized()
        rejected { memory.write("automatic.md", note("automatic"), MemoryStore.Actor.AGENT, "session") }
        owner.setEnabled(false)
        memory.write("user.md", note("explicit"), MemoryStore.Actor.USER, "session")
        assertTrue(memory.readForUser("user.md").contains("explicit"))
    }

    @Test fun offOnRejectsOldTicketIncludingScopedCloneAndWorkspaceCopies() {
        val owner = root()
        val bound = owner.withAutomaticWriteTicket(owner.writeGate().capture())
        val scoped = bound.forConversation("session")
        scoped.ensureInitialized()
        val workspace = WorkspaceStore(temporary.newFolder(), WorkspaceStore.projectIdForSession("session"),
            null, null, scoped, "session", true)
        val copy = workspace.withoutAttachments()
        owner.setEnabled(false)
        owner.setEnabled(true)
        rejected { scoped.write("old.md", note("stale"), MemoryStore.Actor.AGENT, "session") }
        rejected { copy.write("/memory/old.md", note("stale copy")) }
        assertFalse(copy.forDelegatedAgent().memoryEnabled())
        val fresh = owner.forConversation("session").withAutomaticWriteTicket(owner.writeGate().capture())
        fresh.write("new.md", note("fresh"), MemoryStore.Actor.AGENT, "session")
        assertEquals(scoped.rootDirectory(), fresh.rootDirectory())
    }

    @Test fun boundStoreCannotReplaceItsTicket() {
        val owner = root()
        val ticket = owner.writeGate().capture()
        val bound = owner.withAutomaticWriteTicket(ticket)
        assertSame(bound, bound.withAutomaticWriteTicket(ticket))
        try { bound.withAutomaticWriteTicket(owner.writeGate().capture()); fail("Rebound run") }
        catch (_: IllegalStateException) { }
    }

    @Test fun newReflectionGroupRejectedAfterAbaButExistingGroupFinishesOff() {
        val owner = root()
        val scoped = owner.forConversation("session").bindAutomaticWritesForToolset()
        scoped.ensureInitialized()
        scoped.beginReflectionGroup("existing", "session")
        owner.setEnabled(false)
        scoped.finishReflectionGroup("existing", "rolled_back")
        assertEquals("rolled_back", scoped.reflectionGroupStatus("existing"))
        owner.setEnabled(true)
        rejected { scoped.beginReflectionGroup("late", "session") }
        assertEquals("", scoped.reflectionGroupStatus("late"))
    }

    @Test fun reflectionUndoCleanupRemainsAvailableOff() {
        val owner = root()
        val scoped = owner.forConversation("session").bindAutomaticWritesForToolset()
        scoped.beginReflectionGroup("group", "session")
        scoped.write("reflection.md", note("reflected"), MemoryStore.Actor.REFLECTION, "session", "group")
        scoped.finishReflectionGroup("group", "completed")
        owner.setEnabled(false)
        assertEquals(1, scoped.undoReflectionGroup("group", "session").size)
        assertFalse(java.io.File(scoped.rootDirectory(), "reflection.md").exists())
    }

    @Test fun admissionCallbackRunsBeforeGateAndAbaBlocksCommit() {
        val owner = root()
        val scoped = owner.forConversation("session").bindAutomaticWritesForToolset()
        scoped.ensureInitialized()
        NativeEffectDispatchScope.enter {
            otherThread { owner.setEnabled(false); owner.setEnabled(true) }
        }.use {
            rejected { scoped.write("blocked.md", note("blocked"), MemoryStore.Actor.AGENT, "session") }
        }
        assertFalse(java.io.File(scoped.rootDirectory(), "blocked.md").exists())
    }

    @Test fun listenersRunAfterGateReleasedAndObserveDurableCommit() {
        val owner = root()
        val scoped = owner.forConversation("session").bindAutomaticWritesForToolset()
        scoped.ensureInitialized()
        var failure: Throwable? = null
        var notified = false
        val listener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) {
                try { otherThread { owner.setEnabled(false) }; notified = true }
                catch (error: Throwable) { failure = error }
            }
            override fun onMemoryCleared() = Unit
        }
        scoped.addChangeListener(listener)
        try {
            scoped.write("committed.md", note("committed"), MemoryStore.Actor.AGENT, "session")
            assertNull(failure)
            assertTrue(notified)
            assertFalse(owner.isEnabled)
            assertTrue(scoped.readForUser("committed.md").contains("committed"))
        } finally { scoped.removeChangeListener(listener) }
    }

    @Test fun cancellationWhileWaitingForCommitGateRejectsWithoutWriting() {
        val owner = root()
        val ticket = owner.writeGate().capture()
        val scoped = owner.forConversation("session").withAutomaticWriteTicket(ticket)
        scoped.ensureInitialized()
        val workspace = WorkspaceStore(temporary.newFolder(), WorkspaceStore.projectIdForSession("session"),
            null, null, scoped, "session", true)
        val token = CancellationToken.cancellable()
        val admissionReached = java.util.concurrent.CountDownLatch(1)
        val gateHeld = java.util.concurrent.CountDownLatch(1)
        val releaseGate = java.util.concurrent.CountDownLatch(1)
        val holder = FutureTask {
            check(admissionReached.await(3, TimeUnit.SECONDS))
            owner.writeGate().commit(ticket, { true }) {
                gateHeld.countDown()
                check(releaseGate.await(3, TimeUnit.SECONDS))
            }
        }
        val writer = FutureTask {
            NativeEffectDispatchScope.enter {
                admissionReached.countDown()
                check(gateHeld.await(3, TimeUnit.SECONDS))
            }.use {
                rejected { workspace.writeWithRevision("/memory/cancelled.md", note("cancelled"), token) }
            }
        }
        Thread(holder, "hold-memory-commit-gate").apply { isDaemon = true; start() }
        Thread(writer, "cancelled-memory-writer").apply { isDaemon = true; start() }
        try {
            assertTrue(gateHeld.await(3, TimeUnit.SECONDS))
            assertTrue(token.cancel())
        } finally { releaseGate.countDown() }
        holder.get(3, TimeUnit.SECONDS)
        writer.get(3, TimeUnit.SECONDS)
        assertFalse(java.io.File(scoped.rootDirectory(), "cancelled.md").exists())
        assertTrue(scoped.listRevisions("cancelled.md", null).isEmpty())
    }

}

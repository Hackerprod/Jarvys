package com.jarvys.agent.linux

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.coding.ProjectScopeStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class CodingJobRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val direct = Executor { it.run() }
    private fun success() = CodingJobManager.Backend { _, _, _, _, _, output, launch ->
        launch(); output.onChunk(LinuxOutputStream.STDOUT, "ok 😀\n"); LinuxExecResult(0)
    }
    private fun throws(block: () -> Unit) { try { block(); fail("Expected refusal") } catch (_: Exception) { } }

    @Test fun completionHasRealExitCodeAndMutatesScopeOnce() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val manager = CodingJobManager(files, success(), executor = direct)
        val job = manager.start(scope, "chat/bot/1", "test", ".", 0, null, CancellationToken.cancellable())
        assertEquals(CodingJobManager.State.SUCCEEDED, job.state); assertEquals(0, job.exitCode)
        assertEquals(1L, scope.version()); assertTrue(job.logComplete)
        assertTrue(manager.read("chat/bot/1", scope, job.id, 0, 100).text.contains("ok 😀"))
    }

    @Test fun noLaunchHookCannotClaimSuccess() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val backend = CodingJobManager.Backend { _, _, _, _, _, _, _ -> LinuxExecResult(0) }
        val manager = CodingJobManager(files, backend, executor = direct)
        val job = manager.start(scope, "owner", "test", ".", 0, null, CancellationToken.cancellable())
        assertEquals(CodingJobManager.State.NOT_STARTED, job.state); assertNull(job.exitCode); assertEquals(0L, scope.version())
    }

    @Test fun recoveryNeverExecutesAndDistinguishesUnstartedFromUnknown() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val manager = CodingJobManager(files, success(), executor = direct)
        val pending = manager.start(scope, "chat/bot/1", "test", ".", 0, null, CancellationToken.cancellable())
        val journal = files.walkTopDown().single { it.name == "${pending.id}.json" }
        // Simulate each crash boundary using the durably written record, without a live worker.
        journal.writeText(JSONObject(journal.readText()).put("state", "LAUNCH_INTENT").put("startedAt", JSONObject.NULL).toString())
        val fresh = ProjectScopeStore(files).open("chat")
        val initialVersion = fresh.version()
        var executions = 0
        val never = CodingJobManager.Backend { _, _, _, _, _, _, _ -> executions++; error("must not run") }
        val restarted = CodingJobManager(files, never)
        assertEquals(CodingJobManager.State.INTERRUPTED, restarted.list("chat/bot/1", fresh).single().state)
        val json = JSONObject(journal.readText()).put("state", "RUNNING").put("startedAt", 42L)
        journal.writeText(json.toString())
        val uncertain = CodingJobManager(files, never)
        val unknown = uncertain.list("chat/bot/1", fresh).single()
        assertEquals(CodingJobManager.State.UNCERTAIN, unknown.state); assertNull(unknown.exitCode); assertFalse(unknown.logComplete)
        assertEquals(initialVersion + 1, fresh.version()); assertEquals(0, executions)
        assertTrue(uncertain.cancel("chat/bot/1", fresh, pending.id).state.terminal)
        assertEquals(0, executions)
    }

    @Test fun jobsAreBoundToConversationOwnerAndDurableIdentity() {
        val files = folder.newFolder(); val scopes = ProjectScopeStore(files)
        val scope = scopes.open("chat"); val other = scopes.open("other")
        val manager = CodingJobManager(files, success(), executor = direct)
        val job = manager.start(scope, "chat/bot/1", "test", ".", 0, null, CancellationToken.cancellable())
        throws { manager.read("chat/other/1", scope, job.id, 0, 100) }
        throws { manager.read("chat/bot/1", other, job.id, 0, 100) }
        throws { manager.bindRecoveredOwners("chat/other/2", scope, listOf("chat/bot/1")) }
        manager.bindRecoveredOwners("chat/bot/2", scope, listOf("chat/bot/1"))
        assertEquals(job.id, manager.read("chat/bot/2", scope, job.id, 0, 100).snapshot.id)
        throws { manager.cancel("chat/bot/2", scope, job.id) }
    }

    @Test fun paginationPreservesUtf8AndRejectsInteriorOffsets() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val manager = CodingJobManager(files, success(), executor = direct)
        val job = manager.start(scope, "owner", "test", ".", 0, null, CancellationToken.cancellable())
        val whole = manager.read("owner", scope, job.id, 0, 100).text
        var offset = 0L; val result = StringBuilder()
        do { val page = manager.read("owner", scope, job.id, offset, 4); result.append(page.text)
            assertTrue(page.nextOffset > offset); offset = page.nextOffset
        } while (page.hasMore)
        assertEquals(whole, result.toString())
        val emoji = whole.substringBefore("😀").toByteArray().size
        throws { manager.read("owner", scope, job.id, emoji + 1L, 4) }
        throws { manager.read("owner", scope, job.id, job.logBytes + 1, 4) }
    }

    @Test fun failedLogJournalIsPreservedAndCounted() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val manager = CodingJobManager(files, success(), executor = direct)
        val job = manager.start(scope, "owner", "test", ".", 0, null, CancellationToken.cancellable())
        val journal = files.walkTopDown().single { it.name == "${job.id}.json" }
        journal.writeText("{broken")
        val restarted = CodingJobManager(files, success(), executor = direct)
        assertEquals(1, restarted.recoveryIssueCount(scope)); assertEquals("{broken", journal.readText())
        assertTrue(restarted.list("owner", scope).isEmpty())
    }

    @Test fun cancellationRemainsPendingUntilWorkerReapsAndTimeoutIsDistinct() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        val queued = mutableListOf<Runnable>()
        val manager = CodingJobManager(files, success(), executor = Executor { queued += it })
        val token = CancellationToken.cancellable()
        val job = manager.start(scope, "owner", "test", ".", 0, null, token)
        token.cancel()
        assertEquals(CodingJobManager.State.CANCELLING, manager.list("owner", scope).single().state)
        assertTrue(manager.hasPending("owner")); queued.single().run()
        assertEquals(CodingJobManager.State.CANCELLED, manager.list("owner", scope).single().state)
        assertFalse(manager.hasPending("owner")); assertEquals(0L, scope.version())
        val timeoutManager = CodingJobManager(files, CodingJobManager.Backend { _, _, _, _, _, _, launch -> launch(); LinuxExecResult(137, timedOut = true) }, executor = direct)
        val timedOut = timeoutManager.start(scope, "owner", "test", ".", 0, 100L, CancellationToken.cancellable())
        assertEquals(CodingJobManager.State.TIMED_OUT, timedOut.state)
    }

    @Test fun projectVersionAndPreflightAreRecheckedAtActualLaunch() {
        val files = folder.newFolder(); val scope = ProjectScopeStore(files).open("chat")
        var allowed = true; var launches = 0
        val backend = CodingJobManager.Backend { _, _, _, _, _, _, launch -> allowed = false; launch(); launches++; LinuxExecResult(0) }
        val manager = CodingJobManager(files, backend, executor = direct)
        val result = manager.start(scope, "owner", "test", ".", 0, null, CancellationToken.cancellable(), { check(allowed) })
        assertEquals(CodingJobManager.State.NOT_STARTED, result.state); assertEquals(0, launches)
        throws { manager.start(scope, "owner", "test", ".", 9, null, CancellationToken.cancellable()) }
    }
}

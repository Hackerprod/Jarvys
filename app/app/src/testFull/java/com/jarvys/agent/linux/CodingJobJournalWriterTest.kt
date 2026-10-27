package com.jarvys.agent.linux

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real temporary-file IO with injected boundary failures, not a device power-loss/fsync proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobJournalWriterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobId = "00000000-0000-0000-0000-000000000001"
    private val old = "old synthetic journal".toByteArray()
    private val next = "new synthetic journal".toByteArray()
    private fun target(root: File) = File(root, "$jobId.json")

    private open class Backend : CodingJobJournalWriter.Backend {
        val calls = mutableListOf<String>()
        val stagedFiles = mutableListOf<File>()
        var failAt: CodingJobJournalWriter.Stage? = null
        var failAfter = false
        var cleanupFailure = false
        private fun boundary(stage: CodingJobJournalWriter.Stage, block: () -> Unit) {
            calls += stage.name
            if (failAt == stage && !failAfter) throw IOException("synthetic boundary failure")
            block()
            if (failAt == stage && failAfter) throw IOException("synthetic boundary failure")
        }
        override fun open(staged: File): CodingJobJournalWriter.Sink {
            calls += "OPEN"; stagedFiles += staged
            if (failAt == CodingJobJournalWriter.Stage.OPEN) throw IOException("synthetic open failure")
            val sink = CodingJobJournalWriter.RealBackend.open(staged)
            return object : CodingJobJournalWriter.Sink {
                private var closeAttempted = false
                override fun write(bytes: ByteArray) = boundary(CodingJobJournalWriter.Stage.WRITE) { sink.write(bytes) }
                override fun sync() = boundary(CodingJobJournalWriter.Stage.SYNC) { sink.sync() }
                override fun close() {
                    if (closeAttempted) { sink.close(); return }
                    closeAttempted = true
                    boundary(CodingJobJournalWriter.Stage.CLOSE) { sink.close() }
                }
            }
        }
        override fun rename(staged: File, target: File) = boundary(CodingJobJournalWriter.Stage.RENAME) {
            CodingJobJournalWriter.RealBackend.rename(staged, target)
        }
        override fun syncDirectory(directory: File) = boundary(CodingJobJournalWriter.Stage.DIRECTORY_SYNC) {
            CodingJobJournalWriter.RealBackend.syncDirectory(directory)
        }
        override fun deleteStaged(staged: File) {
            calls += "CLEANUP"
            CodingJobJournalWriter.RealBackend.deleteStaged(staged)
            if (cleanupFailure) throw IOException("synthetic cleanup failure")
        }
    }

    @Test fun realWriteReturnsExactDefensiveBytesOnlyAfterAllBoundaries() {
        val root = temporary.newFolder(); val file = target(root); file.writeBytes(old)
        val backend = Backend(); val input = next.copyOf()
        val commit = CodingJobJournalWriter(root, backend).write(file, jobId, 2, input)
        assertEquals(listOf("OPEN", "WRITE", "SYNC", "CLOSE", "RENAME", "DIRECTORY_SYNC", "CLEANUP"), backend.calls)
        assertArrayEquals(next, file.readBytes())
        assertEquals(file, commit.candidate.target); assertEquals(jobId, commit.candidate.jobId)
        assertEquals(2, commit.candidate.schema)
        input.fill(0); commit.candidate.copyBytes().fill(1)
        assertArrayEquals(next, commit.candidate.copyBytes())
        assertEquals(listOf(file.name), root.list()!!.toList())
    }

    @Test fun failuresBeforeRenameKeepOldTargetAndNeverReturnCommit() {
        for (stage in listOf(CodingJobJournalWriter.Stage.OPEN, CodingJobJournalWriter.Stage.WRITE,
            CodingJobJournalWriter.Stage.SYNC, CodingJobJournalWriter.Stage.CLOSE, CodingJobJournalWriter.Stage.RENAME)) {
            val root = temporary.newFolder(); val file = target(root); file.writeBytes(old)
            val backend = Backend().apply { failAt = stage }
            val failure = assertThrows(CodingJobJournalWriter.Failure::class.java) {
                CodingJobJournalWriter(root, backend).write(file, jobId, 1, next)
            }
            assertEquals(stage, failure.attempted)
            val prior = when (stage) {
                CodingJobJournalWriter.Stage.OPEN -> null
                CodingJobJournalWriter.Stage.WRITE -> CodingJobJournalWriter.Stage.OPEN
                CodingJobJournalWriter.Stage.SYNC -> CodingJobJournalWriter.Stage.WRITE
                CodingJobJournalWriter.Stage.CLOSE -> CodingJobJournalWriter.Stage.SYNC
                else -> CodingJobJournalWriter.Stage.CLOSE
            }
            assertEquals(prior, failure.confirmed)
            assertArrayEquals(old, file.readBytes()); assertArrayEquals(next, failure.candidate.copyBytes())
            assertFalse(backend.calls.contains("DIRECTORY_SYNC"))
            assertFalse(failure.message!!.contains(String(next)))
            assertTrue(backend.stagedFiles.none { it.exists() })
        }
    }

    @Test fun writeSyncOrCloseAppliedThenThrownDoesNotAdvanceConfirmationOrRename() {
        for (stage in listOf(CodingJobJournalWriter.Stage.WRITE, CodingJobJournalWriter.Stage.SYNC, CodingJobJournalWriter.Stage.CLOSE)) {
            val root = temporary.newFolder(); val file = target(root); file.writeBytes(old)
            val backend = Backend().apply { failAt = stage; failAfter = true }
            val failure = assertThrows(CodingJobJournalWriter.Failure::class.java) {
                CodingJobJournalWriter(root, backend).write(file, jobId, 2, next)
            }
            assertEquals(stage, failure.attempted)
            assertEquals(when(stage) {
                CodingJobJournalWriter.Stage.WRITE -> CodingJobJournalWriter.Stage.OPEN
                CodingJobJournalWriter.Stage.SYNC -> CodingJobJournalWriter.Stage.WRITE
                else -> CodingJobJournalWriter.Stage.SYNC
            }, failure.confirmed)
            assertFalse(backend.calls.contains("RENAME")); assertArrayEquals(old, file.readBytes())
            assertTrue(backend.stagedFiles.none { it.exists() })
        }
    }

    @Test fun renameAppliedThenThrownIsUnconfirmedDespiteReadableNewBytes() {
        val root = temporary.newFolder(); val file = target(root); file.writeBytes(old)
        val backend = Backend().apply { failAt = CodingJobJournalWriter.Stage.RENAME; failAfter = true }
        val failure = assertThrows(CodingJobJournalWriter.Failure::class.java) {
            CodingJobJournalWriter(root, backend).write(file, jobId, 2, next)
        }
        assertEquals(CodingJobJournalWriter.Stage.RENAME, failure.attempted)
        assertEquals(CodingJobJournalWriter.Stage.CLOSE, failure.confirmed)
        assertArrayEquals(next, file.readBytes()); assertArrayEquals(next, failure.candidate.copyBytes())
        assertFalse(backend.calls.contains("DIRECTORY_SYNC"))
    }

    @Test fun directorySyncFailureBeforeOrAfterCallCannotMintCommit() {
        for (after in listOf(false, true)) {
            val root = temporary.newFolder(); val file = target(root); file.writeBytes(old)
            val backend = Backend().apply { failAt = CodingJobJournalWriter.Stage.DIRECTORY_SYNC; failAfter = after }
            val failure = assertThrows(CodingJobJournalWriter.Failure::class.java) {
                CodingJobJournalWriter(root, backend).write(file, jobId, 2, next)
            }
            assertEquals(CodingJobJournalWriter.Stage.DIRECTORY_SYNC, failure.attempted)
            assertEquals(CodingJobJournalWriter.Stage.RENAME, failure.confirmed)
            assertArrayEquals(next, file.readBytes())
            assertTrue(backend.stagedFiles.none { it.exists() })
        }
    }

    @Test fun cleanupFailureDoesNotHidePrimaryAndAttemptsHaveDistinctIdentity() {
        val root = temporary.newFolder(); val file = target(root)
        val backend = Backend().apply { failAt = CodingJobJournalWriter.Stage.WRITE; cleanupFailure = true }
        val writer = CodingJobJournalWriter(root, backend)
        val first = assertThrows(CodingJobJournalWriter.Failure::class.java) { writer.write(file, jobId, 2, next) }
        val second = assertThrows(CodingJobJournalWriter.Failure::class.java) { writer.write(file, jobId, 2, next) }
        assertEquals(CodingJobJournalWriter.Stage.WRITE, first.attempted)
        assertEquals("synthetic boundary failure", first.cause!!.message)
        assertEquals("synthetic cleanup failure", first.suppressed.single().message)
        assertNotEquals(first.candidate.attemptId, second.candidate.attemptId)
        assertNotEquals(backend.stagedFiles[0], backend.stagedFiles[1])
        assertFalse(file.exists())
    }

    @Test fun schemaIdentityOutsideRootAndSymlinkTargetsAreRejectedBeforeOpening() {
        val root = temporary.newFolder(); val backend = Backend(); val writer = CodingJobJournalWriter(root, backend)
        assertThrows(IllegalArgumentException::class.java) { writer.write(target(root), jobId, 3, next) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(File(root, "wrong.json"), jobId, 2, next) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(target(temporary.newFolder()), jobId, 2, next) }
        val outside = temporary.newFile(); outside.writeBytes(old)
        Files.createSymbolicLink(target(root).toPath(), outside.toPath())
        assertThrows(IOException::class.java) { writer.write(target(root), jobId, 2, next) }
        val alias = File(temporary.root, "root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertThrows(IOException::class.java) { CodingJobJournalWriter(alias, backend) }
        assertTrue(backend.calls.isEmpty()); assertArrayEquals(old, outside.readBytes())
    }

    @Test fun separateWriterInstancesSerializeSameRootWithoutClaimingRevisionCas() {
        val root = temporary.newFolder(); val file = target(root)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val secondOpen = CountDownLatch(1); val count = AtomicInteger()
        val backend = object : Backend() {
            override fun open(staged: File): CodingJobJournalWriter.Sink {
                if (count.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                } else secondOpen.countDown()
                return super.open(staged)
            }
        }
        val firstWriter = CodingJobJournalWriter(root, backend)
        val secondWriter = CodingJobJournalWriter(root, backend)
        val lockField = CodingJobJournalWriter::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val sharedLock = lockField.get(firstWriter)
        assertSame(sharedLock, lockField.get(secondWriter))
        val firstThread = AtomicReference<Thread>(); val secondThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<CodingJobJournalWriter.Commit> {
                firstThread.set(Thread.currentThread()); firstWriter.write(file, jobId, 2, old)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<CodingJobJournalWriter.Commit> {
                secondThread.set(Thread.currentThread()); secondStarted.countDown(); secondWriter.write(file, jobId, 2, next)
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            // Android's compile stubs omit management APIs; the reviewed host-JDK test runner has them.
            // Invoke declared public management types, not JDK-private implementation classes.
            val managementFactory = Class.forName("java.lang.management.ManagementFactory")
            val threadBean = managementFactory.getMethod("getThreadMXBean").invoke(null)
            val beanType = Class.forName("java.lang.management.ThreadMXBean")
            val infoType = Class.forName("java.lang.management.ThreadInfo")
            val lockInfoType = Class.forName("java.lang.management.LockInfo")
            val getThreadInfo = beanType.getMethod("getThreadInfo", java.lang.Long.TYPE)
            val getThreadState = infoType.getMethod("getThreadState")
            val getLockInfo = infoType.getMethod("getLockInfo")
            val getLockOwnerId = infoType.getMethod("getLockOwnerId")
            val getIdentityHashCode = lockInfoType.getMethod("getIdentityHashCode")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var observedBlockedOnOwner = false
            while (System.nanoTime() < deadline) {
                assertFalse("Second writer entered IO before first released", second.isDone || secondOpen.count == 0L)
                val info = getThreadInfo.invoke(threadBean, secondThread.get().id)
                val lockInfo = if (info == null) null else getLockInfo.invoke(info)
                if (info != null && getThreadState.invoke(info) == Thread.State.BLOCKED &&
                    lockInfo != null && getIdentityHashCode.invoke(lockInfo) == System.identityHashCode(sharedLock) &&
                    getLockOwnerId.invoke(info) == firstThread.get().id) {
                    observedBlockedOnOwner = true; break
                }
                Thread.yield()
            }
            assertTrue("Second writer must reach the exact shared monitor, not merely be scheduled", observedBlockedOnOwner)
            release.countDown()
            val firstCommit = first.get(5, TimeUnit.SECONDS); val secondCommit = second.get(5, TimeUnit.SECONDS)
            assertArrayEquals(old, firstCommit.candidate.copyBytes()); assertArrayEquals(next, secondCommit.candidate.copyBytes())
            assertArrayEquals(next, file.readBytes()); assertNotEquals(firstCommit.candidate.attemptId, secondCommit.candidate.attemptId)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}

package com.jarvys.agent.mcp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.BoundedGitHubWriteJournal
import com.jarvys.agent.connectors.GitHubNativeBridge
import com.jarvys.agent.connectors.GitHubOperationPolicy
import com.jarvys.agent.connectors.GitHubWriteJournalStore
import com.jarvys.agent.connectors.GitHubWriteJournals
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubWriteJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("jarvys_github_write_journal", Context.MODE_PRIVATE)
    private val first = GitHubOperationPolicy.digest("reviewed-write-one")
    private val second = GitHubOperationPolicy.digest("reviewed-write-two")

    @Before fun setUp() { preferences.edit().clear().commit() }
    @After fun tearDown() { preferences.edit().clear().commit() }

    @Test fun actualPersistentFactoryCommitsOnlyIntentHashesAndSharesThemAcrossInstances() {
        val journal = GitHubWriteJournals.persistent(context)
        journal.reserve(first)
        assertEquals(setOf(first), preferences.getStringSet("uncertain_intents", emptySet()))
        assertEquals(setOf("uncertain_intents"), preferences.all.keys)
        val recreated = GitHubWriteJournals.persistent(context)
        assertTrue(recreated.isUncertain(first))
        assertThrows(IllegalStateException::class.java) { recreated.reserve(first) }
        assertTrue(recreated.resolve(first))
        assertFalse(journal.isUncertain(first))
        assertEquals(emptySet<String>(), preferences.getStringSet("uncertain_intents", emptySet()))
    }

    @Test fun freshStoreIdentityLoadsDurableMarkersWithoutProcessRetainedState() {
        val directory = Files.createTempDirectory("github-journal-test").toFile()
        val file = File(directory, "intent-hashes.txt")
        try {
            val previousProcess = BoundedGitHubWriteJournal(FileStore(file))
            previousProcess.reserve(first)
            previousProcess.reserve(second)
            assertEquals(setOf(first, second), file.readLines().toSet())
            // New backing identity deliberately cannot see the static in-process retained set.
            val restarted = BoundedGitHubWriteJournal(FileStore(file))
            assertTrue(restarted.isUncertain(first))
            assertTrue(restarted.isUncertain(second))
            assertThrows(IllegalStateException::class.java) { restarted.reserve(first) }
            assertTrue(restarted.resolve(first))
            assertTrue(restarted.resolve(second))
            val nextRestart = BoundedGitHubWriteJournal(FileStore(file))
            assertFalse(nextRestart.isUncertain(first))
            assertFalse(nextRestart.isUncertain(second))
            assertTrue(file.readText().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun persistentStorageIsLazyUntilTheFirstRealJournalOperation() {
        val counting = CountingContext(context)
        val journal = GitHubWriteJournals.persistent(counting)
        GitHubNativeBridge().tools()
        assertEquals("Catalog construction must not open private journal storage", 0, counting.opens.get())
        assertFalse(journal.isUncertain(first))
        assertEquals(1, counting.opens.get())
        journal.reserve(first)
        assertTrue(journal.resolve(first))
        assertEquals("Lazy backing journal must initialize only once", 1, counting.opens.get())
    }

    @Test fun unavailableStorageDoesNotBreakConstructionButFailsClosedBeforeAWrite() {
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw IllegalStateException("Simulated unavailable journal storage")
        }
        val journal = GitHubWriteJournals.persistent(denied)
        assertThrows(IllegalStateException::class.java) { journal.reserve(first) }
        assertThrows(IllegalStateException::class.java) { journal.isUncertain(first) }
    }

    @Test fun failedReservationCommitRetainsMarkerEvenWhenVisiblePreferencesRollBack() {
        val backing = Backing()
        val store = MemoryStore(backing)
        val journal = BoundedGitHubWriteJournal(store)
        backing.failWrite = true
        assertThrows(IllegalStateException::class.java) { journal.reserve(first) }
        assertEquals(emptySet<String>(), backing.durable)
        backing.visible = backing.durable
        val recreated = BoundedGitHubWriteJournal(MemoryStore(backing))
        assertTrue(recreated.isUncertain(first))
        assertThrows(IllegalStateException::class.java) { recreated.reserve(first) }
        backing.failWrite = false
        assertTrue(recreated.resolve(first))
        assertFalse(journal.isUncertain(first))
    }

    @Test fun failedResolutionCommitCannotRemoveReplayGuardEvenAfterInMemoryRemoval() {
        val backing = Backing()
        val journal = BoundedGitHubWriteJournal(MemoryStore(backing))
        journal.reserve(first)
        backing.failWrite = true
        assertFalse(journal.resolve(first))
        assertEquals("Simulate SharedPreferences updating memory despite failed commit", emptySet<String>(), backing.visible)
        assertEquals(setOf(first), backing.durable)
        val recreated = BoundedGitHubWriteJournal(MemoryStore(backing))
        assertTrue(recreated.isUncertain(first))
        assertThrows(IllegalStateException::class.java) { recreated.reserve(first) }
        // A cold process reloads the still-durable marker independently of retained memory.
        val cold = Backing().apply { visible = backing.durable; durable = backing.durable }
        assertTrue(BoundedGitHubWriteJournal(MemoryStore(cold)).isUncertain(first))
        backing.failWrite = false
        assertTrue(recreated.resolve(first))
        assertFalse(journal.isUncertain(first))
    }

    @Test fun writeExceptionsCannotClearAnUncertainIntent() {
        val backing = Backing()
        val journal = BoundedGitHubWriteJournal(MemoryStore(backing))
        journal.reserve(first)
        backing.throwWrite = true
        assertFalse(journal.resolve(first))
        assertTrue(journal.isUncertain(first))
        assertTrue(BoundedGitHubWriteJournal(MemoryStore(backing)).isUncertain(first))
        assertThrows(IllegalStateException::class.java) { journal.reserve(first) }
    }

    @Test fun malformedOrOverfullStorageFailsClosedAndIsNeverSilentlyReplaced() {
        for (corrupt in listOf(setOf("not-an-intent-hash"), (0..BoundedGitHubWriteJournal.MAX_ENTRIES).map { GitHubOperationPolicy.digest("entry-$it") }.toSet())) {
            val backing = Backing().apply { visible = corrupt; durable = corrupt }
            val journal = BoundedGitHubWriteJournal(MemoryStore(backing))
            assertThrows(IllegalStateException::class.java) { journal.isUncertain(first) }
            assertThrows(IllegalStateException::class.java) { journal.reserve(first) }
            assertFalse(journal.resolve(first))
            assertEquals(corrupt, backing.visible)
            assertEquals(0, backing.writes)
        }
    }

    @Test fun fullJournalBlocksNewReservationButCanResolveExistingIntent() {
        val backing = Backing()
        val journal = BoundedGitHubWriteJournal(MemoryStore(backing))
        val hashes = (0 until BoundedGitHubWriteJournal.MAX_ENTRIES).map { GitHubOperationPolicy.digest("entry-$it") }
        hashes.forEach(journal::reserve)
        assertThrows(IllegalStateException::class.java) { journal.reserve(first) }
        assertFalse(journal.isUncertain(first))
        assertTrue(journal.resolve(hashes.first()))
        journal.reserve(first)
        assertTrue(journal.isUncertain(first))
        assertEquals(BoundedGitHubWriteJournal.MAX_ENTRIES, backing.durable.size)
    }

    @Test fun duplicateConcurrentReservationAcrossWrappersHasExactlyOneWinner() {
        val backing = Backing()
        val journals = listOf(BoundedGitHubWriteJournal(MemoryStore(backing)), BoundedGitHubWriteJournal(MemoryStore(backing)))
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val attempts = journals.map { journal -> workers.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                runCatching { journal.reserve(first) }.isSuccess
            } }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, backing.writes)
            assertEquals(setOf(first), backing.durable)
            journals.forEach { assertTrue(it.isUncertain(first)) }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidCallerHashesAreRejectedBeforeReadingOrWritingStorage() {
        val backing = Backing()
        val journal = BoundedGitHubWriteJournal(MemoryStore(backing))
        for (hash in listOf("", "a".repeat(63), "a".repeat(65), "G".repeat(64), "secret=do-not-store")) {
            assertThrows(IllegalArgumentException::class.java) { journal.reserve(hash) }
            assertThrows(IllegalArgumentException::class.java) { journal.isUncertain(hash) }
            assertThrows(IllegalArgumentException::class.java) { journal.resolve(hash) }
        }
        assertEquals(0, backing.reads)
        assertEquals(0, backing.writes)
    }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        val opens = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            opens.incrementAndGet()
            return super.getSharedPreferences(name, mode)
        }
    }
    private class FileStore(private val file: File) : GitHubWriteJournalStore {
        override fun read(): Set<String> = if (file.exists()) file.readLines().filter(String::isNotBlank).toSet() else emptySet()
        override fun write(hashes: Set<String>): Boolean { file.writeText(hashes.sorted().joinToString("\n")); return true }
    }
    private class Backing {
        var visible = emptySet<String>()
        var durable = emptySet<String>()
        var failWrite = false
        var throwWrite = false
        var reads = 0
        var writes = 0
    }
    private class MemoryStore(private val backing: Backing) : GitHubWriteJournalStore {
        override val identity: Any get() = backing
        override fun read(): Set<String> { backing.reads++; return backing.visible.toSet() }
        override fun write(hashes: Set<String>): Boolean {
            backing.writes++
            if (backing.throwWrite) throw IllegalStateException("Simulated commit failure")
            backing.visible = hashes.toSet()
            if (backing.failWrite) return false
            backing.durable = hashes.toSet()
            return true
        }
    }
}

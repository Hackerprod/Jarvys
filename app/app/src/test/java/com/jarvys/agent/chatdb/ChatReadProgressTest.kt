package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatReadProgressTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-read-progress-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase
    private lateinit var repository: ConversationRepository
    private var databaseClosed = false

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "read-progress.db"))
        repository = ConversationRepository(database)
    }

    @After fun closeDatabase() {
        testExecutor.shutdownNow()
        check(testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            "Database test worker remained active during @After; refusing to close Room under its operation"
        }
        if (::database.isInitialized && !databaseClosed) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T {
        val workerThread = AtomicReference<Thread?>()
        val taskFinished = CountDownLatch(1)
        val work = object : FutureTask<T>(java.util.concurrent.Callable {
            workerThread.set(Thread.currentThread())
            runBlocking { block() }
        }) {
            override fun run() {
                try {
                    super.run()
                } finally {
                    taskFinished.countDown()
                }
            }
        }
        testExecutor.execute(work)
        return try {
            work.get(30, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            work.cancel(true)
            val taskStopped = taskFinished.await(5, TimeUnit.SECONDS)
            throw AssertionError(
                "Database test operation timed out and was interrupted; taskStillActive=${!taskStopped}, " +
                    "worker=${workerThread.get()?.name}",
                timeout,
            )
        }
    }

    @Test fun multiStatementProjectionFinishesConsistentlyDuringContinuousCommits() = onDatabaseThread {
        success(repository.ensureConversation("read-progress"))
        database.withChatWriteTransaction {
            repeat(256) { index ->
                success(repository.appendMessage(message("read-progress", "seed-$index")))
            }
        }

        val stopWriter = AtomicBoolean(false)
        val commits = AtomicInteger(0)
        val writerStarted = CountDownLatch(1)
        val writerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "chatdb-continuous-writer").apply { isDaemon = true }
        }
        val writer = writerExecutor.submit {
            while (!stopWriter.get()) {
                success(kotlinx.coroutines.runBlocking {
                    val appended = repository.appendMessage(message("read-progress", "concurrent-${commits.get()}"))
                    delay(5L)
                    appended
                })
                commits.incrementAndGet()
                writerStarted.countDown()
            }
        }
        try {
            assertTrue("continuous writer commits before the projection begins", writerStarted.await(10, TimeUnit.SECONDS))
            val attempts = AtomicInteger(0)
            val snapshot = withTimeout(20_000L) {
                database.withChatReadTransaction {
                    val attempt = attempts.incrementAndGet()
                    val countBefore = countMessages()
                    val sumBefore = sumMessageSequences()
                    if (attempt == 1) {
                        val target = commits.get() + 5
                        withTimeout(10_000L) {
                            while (commits.get() < target) delay(1L)
                        }
                    }
                    val countAfter = countMessages()
                    val sumAfter = sumMessageSequences()
                    listOf(countBefore, sumBefore, countAfter, sumAfter)
                }
            }

            assertTrue("the changing fence takes the guaranteed-progress writer retry", attempts.get() >= 2)
            assertEquals("returned projection has one message count", snapshot[0], snapshot[2])
            assertEquals("returned projection has one sequence sum", snapshot[1], snapshot[3])
            assertEquals("the count and sequence sum describe the same contiguous snapshot",
                snapshot[0] * (snapshot[0] + 1L) / 2L, snapshot[1])
        } finally {
            stopWriter.set(true)
            try {
                writer.get(10, TimeUnit.SECONDS)
            } finally {
                writerExecutor.shutdownNow()
                assertTrue("the continuous writer exits before the database can close", writerExecutor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun continuousWritesUntilReadRetryThenDatabaseCloseCompletes() = onDatabaseThread {
        success(repository.ensureConversation("read-retry-close"))

        val stopWriter = AtomicBoolean(false)
        val commits = AtomicInteger(0)
        val firstReadStarted = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val writerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "chatdb-close-regression-writer").apply { isDaemon = true }
        }
        val writer = writerExecutor.submit {
            check(firstReadStarted.await(10, TimeUnit.SECONDS)) { "the first read attempt did not begin" }
            while (!stopWriter.get()) {
                success(kotlinx.coroutines.runBlocking {
                    val appended = repository.appendMessage(message("read-retry-close", "concurrent-${commits.get()}"))
                    delay(5L)
                    appended
                })
                commits.incrementAndGet()
            }
        }

        try {
            val attempts = AtomicInteger(0)
            val snapshot = withTimeout(20_000L) {
                database.withChatReadTransaction {
                    val attempt = attempts.incrementAndGet()
                    val before = countMessages("read-retry-close")
                    val sumBefore = sumMessageSequences("read-retry-close")
                    if (attempt == 1) {
                        firstReadStarted.countDown()
                        withTimeout(10_000L) {
                            while (commits.get() < 3) delay(1L)
                        }
                    } else {
                        retryStarted.countDown()
                    }
                    val after = countMessages("read-retry-close")
                    val sumAfter = sumMessageSequences("read-retry-close")
                    listOf(before, sumBefore, after, sumAfter)
                }
            }

            assertTrue("the read-version fence invokes its writer-lease retry", retryStarted.await(10, TimeUnit.SECONDS))
            assertEquals("the first mixed snapshot is retried", 2, attempts.get())
            assertEquals("retry returns a single committed count", snapshot[0], snapshot[2])
            assertEquals("retry returns a single committed sequence sum", snapshot[1], snapshot[3])
            assertEquals(snapshot[0] * (snapshot[0] + 1L) / 2L, snapshot[1])
        } finally {
            stopWriter.set(true)
            try {
                writer.get(10, TimeUnit.SECONDS)
            } finally {
                writerExecutor.shutdownNow()
                assertTrue("the regression writer exits before Room is closed", writerExecutor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

        // This exercises the same bounded close helper used by @After and guards against the
        // historical Room CloseBarrier spin after concurrent writes and the read retry.
        try {
            JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        } finally {
            databaseClosed = true
        }
    }

    @Test fun rawReadQueriesPreserveNumericBindTypes() = onDatabaseThread {
        val isLessThan = database.chatSql.query("SELECT 10 < ?", arrayOf(2L)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        assertEquals("the INTEGER bind compares numerically instead of sorting after TEXT", 0L, isLessThan)
    }

    @Test fun cancellingReadWhilePoolIsExhaustedInterruptsItsWait() = onDatabaseThread {
        val borrowed = (1..4).map { JarvysChatDatabaseProvider.acquireReadDatabaseBlocking(database) }
        try {
            coroutineScope {
                val waiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    database.withChatReadTransaction { Unit }
                }
                delay(100L)
                assertFalse("all raw handles are borrowed, so the projection is still waiting", waiting.isCompleted)
                waiting.cancel()
                withTimeout(5_000L) { waiting.join() }
                assertTrue("cancellation reaches the waiting projection", waiting.isCancelled)
            }
        } finally {
            borrowed.forEach { JarvysChatDatabaseProvider.releaseReadDatabase(database, it) }
        }
    }

    @Test fun synchronousQueryAndProjectionRejectTheMainLooper() {
        val queryFailure = AtomicReference<Throwable?>()
        val transactionFailure = AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post {
            queryFailure.set(runCatching { database.chatSql.query("SELECT 1").close() }.exceptionOrNull())
            transactionFailure.set(runCatching { runBlocking { database.withChatReadTransaction { Unit } } }.exceptionOrNull())
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("a pool-backed synchronous query rejects the main looper", queryFailure.get() is IllegalStateException)
        assertTrue("a read projection rejects the main looper", transactionFailure.get() is IllegalStateException)
    }

    @Test fun closingReadPoolWakesWaiterAndClosesBorrowedHandlesOnReturn() {
        val borrowed = (1..4).map { JarvysChatDatabaseProvider.acquireReadDatabaseBlocking(database) }
        val waiting = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val waiter = Thread {
            waiting.countDown()
            try {
                JarvysChatDatabaseProvider.acquireReadDatabaseBlocking(database)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true }
        waiter.start()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (waiter.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
        assertEquals("the fifth borrower waits on the exhausted pool", Thread.State.WAITING, waiter.state)

        try {
            JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
            databaseClosed = true
            waiter.join(5_000L)
            assertFalse("closing wakes the blocked pool acquisition", waiter.isAlive)
            assertNotNull("closed pool reports a failure to its waiter", failure.get())
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            borrowed.forEach { JarvysChatDatabaseProvider.releaseReadDatabase(database, it) }
        }
    }

    private fun countMessages(conversationId: String = "read-progress"): Long = database.chatSql.query(
        "SELECT COUNT(*) FROM messages WHERE conversation_id=?", arrayOf(conversationId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun sumMessageSequences(conversationId: String = "read-progress"): Long = database.chatSql.query(
        "SELECT SUM(seq) FROM messages WHERE conversation_id=?", arrayOf(conversationId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun message(conversationId: String, text: String) = AppendMessageInput(
        conversationId,
        "user",
        listOf(MessagePartInput(MessagePartKinds.TEXT, text)),
    )

    private fun <T> success(result: ChatDbResult<T>): T {
        assertTrue("expected successful chat DB operation, got $result", result is ChatDbResult.Success)
        return (result as ChatDbResult.Success<T>).value
    }
}

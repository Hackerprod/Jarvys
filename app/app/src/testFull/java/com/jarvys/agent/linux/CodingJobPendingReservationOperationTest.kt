package com.jarvys.agent.linux

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queued original reservation callable, before task.call: no claim of an open or committed TX. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobPendingReservationOperationTest {
    private val bridgeExecutor = PendingReservationExecutor()
    @get:Rule val chat = ChatTestStore(true, false) { bridgeExecutor }

    private class PendingReservationExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor { work -> Thread(work, "coding-reservation-ack-fixture").apply { isDaemon = true } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean()
        val rejected = AtomicReference<Throwable>()
        val completed = CountDownLatch(1)
        override fun <T> submit(task: Callable<T>): Future<T> {
            val hold=armed.compareAndSet(true,false)
            if(hold) assertTrue("Preflight must target reservation, not another bridge call",
                Thread.currentThread().stackTrace.any { it.className==ChatDatabaseBridge::class.java.name && it.methodName=="reserveCodingJobSync" })
            return delegate.submit(Callable {
                if(hold) {entered.countDown();check(release.await(30,TimeUnit.SECONDS)) {"Pending reservation fixture deadline"}}
                try {task.call()} catch(failure:Throwable) {if(hold)rejected.set(failure);throw failure}
                finally {if(hold)completed.countDown()}
            })
        }
        override fun execute(command: Runnable) = delegate.execute(command)
        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown(): Boolean = delegate.isShutdown
        override fun isTerminated(): Boolean = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }

    @Test fun queuedOriginalOperationRetainsPinUntilActualRejectionAndNeverInventsReservation() {
        val files = chat.newFilesDirectory(); chat.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val database = JarvysChatDatabaseProvider.getInstance(context)
        fun rows(): List<Pair<String, String>> = runBlocking {
            JarvysChatDatabaseProvider.onQueryExecutor(database) {
                database.withChatReadTransaction {
                    database.chatSql.query("SELECT observation_kind,evidence_json FROM coding_job_observations ORDER BY revision").use { c ->
                        buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                    }
                }
            }
        }
        val ticket = MemoryWritePolicy.forContext(context).captureTicket()
        val enqueues = AtomicInteger(); val backends = AtomicInteger(); val calls = AtomicInteger()
        val manager = CodingJobManager(files, CodingJobManager.Backend { _, _, _, _, _, _, _ ->
            backends.incrementAndGet(); throw AssertionError("Unacknowledged acceptance must not dispatch")
        }, executor = Executor { enqueues.incrementAndGet(); it.run() })
        val caller = AtomicReference<Thread>(); val failedWait = CountDownLatch(1)
        val sourceFailure = AtomicReference<Throwable>(); val invocationRef = AtomicReference<EffectInvocation>()
        val scopeRef = AtomicReference<ProjectScope>(); val ownerRef = AtomicReference<String>()
        val driver = Thread({
            try {
                CodingSubmissionTestSource.runAsync(context) { invocation, token ->
                    calls.incrementAndGet(); invocationRef.set(invocation); caller.set(Thread.currentThread())
                    val source = invocation.crewSource().source()
                    val scope = ProjectScopeStore(files).open(source.executionSessionId)
                    val owner = "${source.executionSessionId}/${source.botId}/1"
                    scopeRef.set(scope); ownerRef.set(owner)
                    try {
                        assertThrows(ChatDatabaseWaitInterruptedException::class.java) {
                            manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null,
                                token, invocation, ChatDatabaseBridge.getInstance(context),
                                CodingJobEvidence.AuthorizationSnapshot(CodingJobEvidence.Authorization.ALLOW,
                                    "native", "project_exec", "fixture", "local_fixture"), ticket,
                                preflight = { assertTrue(bridgeExecutor.armed.compareAndSet(false,true)) })
                        }
                        assertTrue("Caller interruption must remain visible", Thread.currentThread().isInterrupted)
                        assertNull("Lost ACK must not bind acceptance to the call", invocation.acceptedSubmission())
                    } finally {
                        Thread.interrupted() // Test thread can finish the real loop after checking preservation.
                        failedWait.countDown()
                    }
                    CoreToolResult.failure("Submission acknowledgement interrupted; never repeat the job")
                }
            } catch (failure: Throwable) { sourceFailure.set(failure) }
        }, "coding-reservation-source-fixture")
        driver.isDaemon = true
        try {
            driver.start()
            assertTrue(bridgeExecutor.entered.await(30, TimeUnit.SECONDS))
            assertTrue(rows().isEmpty())
            assertEquals(0, enqueues.get()); assertEquals(0, backends.get())
            requireNotNull(caller.get()).interrupt()
            assertTrue(failedWait.await(30, TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            var noLongerCurrent=false
            while(!noLongerCurrent && System.nanoTime()<deadline) {
                try {invocationRef.get().crewSource().requireInvocationCurrent()}
                catch(_:IllegalStateException){noLongerCurrent=true}
                if(!noLongerCurrent)Thread.yield()
            }
            assertTrue("Real invocation must expire before queued reservation runs",noLongerCurrent)
            val draining=manager.requestQuiesce()
            assertFalse("Original operation pin survives interruption until its callable really finishes",draining.isDone)
            assertTrue(rows().isEmpty())
            assertEquals(0,enqueues.get());assertEquals(0,backends.get())
            bridgeExecutor.release.countDown()
            assertTrue(bridgeExecutor.completed.await(30,TimeUnit.SECONDS))
            draining.get(30,TimeUnit.SECONDS)
            driver.join(30_000);assertFalse(driver.isAlive)
            sourceFailure.get()?.let {throw AssertionError(it)}
            assertTrue("Expired source must reject the original callable",bridgeExecutor.rejected.get() is IllegalStateException)
            val retained=manager.list(ownerRef.get(),scopeRef.get()).single()
            assertEquals(CodingJobManager.TechnicalProjection.PENDING,manager.technicalProjection(ownerRef.get(),scopeRef.get(),retained.id))
            assertTrue(rows().isEmpty())
            val bindingCount=runBlocking {JarvysChatDatabaseProvider.onQueryExecutor(database) {
                database.withChatReadTransaction {database.chatSql.query("SELECT COUNT(*) FROM coding_job_bindings").use {c ->c.moveToFirst();c.getLong(0)}}
            }}
            assertEquals(0L,bindingCount)
            assertFalse("No legacy or technical journal may be invented",files.walkTopDown().any {it.isFile && it.name==retained.id+".json"})
            assertNull(invocationRef.get().acceptedSubmission())
            assertEquals(1,calls.get());assertEquals(0,enqueues.get());assertEquals(0,backends.get())
        } finally {
            bridgeExecutor.release.countDown(); driver.join(30_000)
            manager.requestQuiesce().get(30, TimeUnit.SECONDS)
        }
    }

}

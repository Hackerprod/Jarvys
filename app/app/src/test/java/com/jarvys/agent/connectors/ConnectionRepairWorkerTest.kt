package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionRepairWorkerTest {
    private fun context(access: ConnectionAccess): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()), ConnectionAccessProvider {
        override fun getApplicationContext(): Context = this
        override fun connectionAccess() = access
    }
    @Test fun repairRequestUsesBackoffAndContainsNoPermissionOrNetworkRequirement() {
        val request = ConnectionRepairWorker.request()
        assertEquals(ConnectionRepairWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertFalse(request.workSpec.constraints.hasContentUriTriggers())
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }
    @Test fun workerClassifiesRetryAndTerminalWithoutGrantOrRuntimeConnect() = runBlocking {
        for ((outcome, expected) in listOf(ConnectionAccess.RepairOutcome.RETRY to ListenableWorker.Result.retry(),
            ConnectionAccess.RepairOutcome.BLOCKED to ListenableWorker.Result.failure())) {
            val access = ConnectionAccess.forTestsControlled(emptyMap(), Executor { it.run() }, projection = { outcome })
            val worker = TestListenableWorkerBuilder.from(context(access), ConnectionRepairWorker::class.java).build()
            assertEquals(expected, worker.doWork())
            assertFalse(access.captureChoice("fixture").selected)
            assertFalse(access.captureChoice("fixture").hasChoice)
        }
        val missingProvider = TestListenableWorkerBuilder.from(ApplicationProvider.getApplicationContext<Context>(), ConnectionRepairWorker::class.java).build()
        assertEquals(ListenableWorker.Result.failure(), missingProvider.doWork())
    }
    @Test fun uniqueReplacementCancelsOnlyObserversWhileProjectionRemainsProcessOwned() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val access = ConnectionAccess.forTestsControlled(emptyMap(), Executor { it.run() }, projection = {
            calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
            ConnectionAccess.RepairOutcome.SUCCESS
        })
        val context = context(access)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(Executor { it.run() }).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS)
        val manager = WorkManager.getInstance(context)
        try {
            ConnectionRepairWorker.enqueue(context).result.get(5, TimeUnit.SECONDS)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ConnectionRepairWorker.enqueue(context).result.get(5, TimeUnit.SECONDS)
            val active = manager.getWorkInfosForUniqueWork(ConnectionRepairWorker.UNIQUE_WORK).get(5, TimeUnit.SECONDS).filterNot { it.state.isFinished }
            assertEquals(1, active.size)
            assertEquals(1, calls.get())
            release.countDown()
            val finished = withTimeout(10_000) { manager.getWorkInfoByIdFlow(active.single().id).first { it?.state?.isFinished == true } }
            assertEquals(WorkInfo.State.SUCCEEDED, finished!!.state)
            assertEquals(1, calls.get())
        } finally {
            release.countDown(); manager.cancelUniqueWork(ConnectionRepairWorker.UNIQUE_WORK).result.get(5, TimeUnit.SECONDS)
        }
    }
}

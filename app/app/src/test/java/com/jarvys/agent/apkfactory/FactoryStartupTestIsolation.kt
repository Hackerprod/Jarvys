package com.jarvys.agent.apkfactory

import com.jarvys.agent.MemoryUiAutomationGuard
import java.util.concurrent.TimeUnit

/** Robolectric reuses process statics between Application configurations. Unit fixtures that do
 * not test startup must finish any previous synthetic startup before replacing its singleton.
 * Never cancel a Future or clear unrelated guards/admission owners to manufacture an idle state.
 */
internal object FactoryStartupTestIsolation {
    fun awaitSharingWorkerCompletion() {
        val worker = FactoryFileShareCoordinator.WORKER
        check(!worker.isShutdown)
        worker.prestartCoreThread()
        val barrier = java.util.concurrent.FutureTask(java.util.concurrent.Callable { Unit })
        // The capacity-one queue may still contain restore while a prior task is returning.
        // Bounded test-only backpressure avoids racing execute() against that transition.
        check(worker.queue.offer(barrier, 10, TimeUnit.SECONDS)) { "Synthetic sharing worker queue remained occupied" }
        barrier.get(10, TimeUnit.SECONDS)
    }
    fun releaseCompletedSharingStartup() {
        awaitSharingWorkerCompletion()
        val singleton = FactoryFileShareCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null) as? FactoryFileShareCoordinator ?: return
        check(!previous.isRestoring()) { "Previous synthetic startup is still active" }
        val owner = FactoryFileShareCoordinator::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val busy = FactoryFileShareCoordinator::class.java.getDeclaredField("busy").apply { isAccessible = true }
        check(owner.get(previous) == null && !busy.getBoolean(previous)) { "Cannot reset a live synthetic sharing interaction" }
        val lease = FactoryFileShareCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
        (lease.get(previous) as? MemoryUiAutomationGuard.Lease)?.close()
        lease.set(previous, null)
        FactoryInteractionAdmission.release(previous)
        singleton.set(null, null)
    }
}

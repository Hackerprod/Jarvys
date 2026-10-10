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
        val external = FactoryExternalLaunchCoordinator.WORKER
        check(!external.isShutdown); external.prestartCoreThread()
        val externalBarrier = java.util.concurrent.FutureTask(java.util.concurrent.Callable { Unit })
        check(external.queue.offer(externalBarrier, 10, TimeUnit.SECONDS)) { "Synthetic external worker queue remained occupied" }
        externalBarrier.get(10, TimeUnit.SECONDS)
        val browser = FactoryBrowserCoordinator.WORKER
        check(!browser.isShutdown); browser.prestartCoreThread()
        val browserBarrier = java.util.concurrent.FutureTask(java.util.concurrent.Callable { Unit })
        check(browser.queue.offer(browserBarrier, 10, TimeUnit.SECONDS)) { "Synthetic browser worker queue remained occupied" }
        browserBarrier.get(10, TimeUnit.SECONDS)
        val audio = FactoryAudioCoordinator.WORKER
        check(!audio.isShutdown); audio.prestartCoreThread()
        val audioBarrier = java.util.concurrent.FutureTask(java.util.concurrent.Callable { Unit })
        check(audio.queue.offer(audioBarrier, 10, TimeUnit.SECONDS)) { "Synthetic audio worker queue remained occupied" }
        audioBarrier.get(10, TimeUnit.SECONDS)
    }
    fun releaseCompletedSharingStartup() {
        awaitSharingWorkerCompletion()
        val externalSingleton = FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (externalSingleton.get(null) as? FactoryExternalLaunchCoordinator)?.let { external ->
            check(!external.isBusy() && !external.needsRecovery() && external.session() == null) { "Cannot reset a live synthetic external interaction" }
            val externalLease = FactoryExternalLaunchCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
            check(externalLease.get(external) == null) { "Cannot erase live external protection" }
            FactoryInteractionAdmission.release(external); externalSingleton.set(null, null)
        }
        val browserSingleton = FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (browserSingleton.get(null) as? FactoryBrowserCoordinator)?.let { browser ->
            check(!browser.isBusy() && !browser.needsRecovery() && browser.session() == null) { "Cannot reset a live synthetic browser interaction" }
            val browserLease = FactoryBrowserCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
            check(browserLease.get(browser) == null) { "Cannot erase live browser protection" }
            FactoryInteractionAdmission.release(browser); browserSingleton.set(null, null)
        }
        val audioSingleton = FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (audioSingleton.get(null) as? FactoryAudioCoordinator)?.let { audio ->
            check(!audio.isBusy() && !audio.needsRecovery() && audio.session() == null) { "Cannot reset a live synthetic audio interaction" }
            val audioLease = FactoryAudioCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
            check(audioLease.get(audio) == null) { "Cannot erase live audio protection" }
            FactoryInteractionAdmission.release(audio); audioSingleton.set(null, null)
        }
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

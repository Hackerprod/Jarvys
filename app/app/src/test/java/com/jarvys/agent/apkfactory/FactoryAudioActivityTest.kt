package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic Application and fake native audio ports: never opens real audio or user media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryAudioActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val policy = FactoryOperationPolicy.inMemory(FactoryOperationPolicy.Mode.ASK)
    private val root get() = File(context.noBackupFilesDir, "factory-audio")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryAudioCoordinator
    private val controllers = mutableListOf<ActivityController<out FactoryAudioActivity>>()
    private val io = Executors.newSingleThreadExecutor()
    private class FakeAudio : FactoryAudioPlayer.Platform, FactoryAudioPlayer.Track {
        var stops = 0; var releases = 0; var plays = 0; var writes = 0; var releaseFails = false
        var beforeWrite: (() -> Unit)? = null
        var onMarker: (() -> Unit)? = null
        override fun requireMain() { }
        override fun requireWorker() { }
        override fun create(pcm: FactoryAudioPcm): FactoryAudioPlayer.Track = this
        override fun acquireFocus(onLoss: () -> Unit) = true
        override fun abandonFocus() { }
        override fun observeRoutes(onChange: () -> Unit) { }
        override fun unobserveRoutes() { }
        override fun now() = 0L
        override fun schedule(delayMs: Long, callback: () -> Unit): Any = callback
        override fun cancel(token: Any) { }
        override fun prepare() { writes++; beforeWrite?.invoke() }
        override fun marker(frames: Int, callback: () -> Unit) { onMarker = callback }
        override fun play(onFailure: () -> Unit) { plays++ }
        override fun stop() { stops++ }
        override fun release() { releases++; if (releaseFails) error("synthetic release failure") }
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        coordinator = FactoryAudioCoordinator(context, policy, { proof }, { _, active -> active(); syntheticWav() })
        FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
    }
    @After fun cleanup() {
        drain()
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        drain()
        io.shutdown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    private fun drain() {
        repeat(3) {
            val worker = FactoryAudioCoordinator.WORKER; worker.prestartCoreThread()
            val barrier = FutureTask(Callable { Unit })
            check(worker.queue.offer(barrier, 5, TimeUnit.SECONDS)); barrier.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun launch(saved: Bundle? = null, intent: Intent = FactoryAudioCoordinator.recoveryIntent(context), reviewFixture: Boolean = true): ActivityController<FactoryAudioActivity> {
        val controller = Robolectric.buildActivity(FactoryAudioActivity::class.java, intent)
        controllers += controller; controller.create(saved).start().resume().visible()
        // Existing cleanup tests inject a verified live session at the same point as real incoming registration.
        // A genuine recovery Activity never gains playback authority.
        if (reviewFixture && coordinator.session() != null && !controller.get().isFinishing) {
            set(controller.get(), "recoveryOnly", false)
            if (coordinator.session()!!.authority.requiresApproval) set(controller.get(), "guard", MemoryUiAutomationGuard.enterProtectedSurface())
        }
        controller.get().onWindowFocusChanged(true)
        return controller
    }
    private fun button(activity: FactoryAudioActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-audio-$name")
    private fun set(activity: FactoryAudioActivity, name: String, value: Any?) = FactoryAudioActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun render(activity: FactoryAudioActivity) = FactoryAudioActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun session(): FactoryAudioCoordinator.Session = io.submit(Callable {
        coordinator.begin(proof.appId, FactoryAudioCoordinator.Request("d".repeat(64), syntheticWav().size, "e".repeat(64), Binder(), Binder())) {}
    }).get(5, TimeUnit.SECONDS)
    private fun startFake(value: FactoryAudioCoordinator.Session): FakeAudio {
        val backend = FakeAudio(); val player = FactoryAudioPlayer(backend); value.player = player
        val prepared = player.prepare(value.pcm!!)
        value.playerClean.set(false); assertTrue(player.start(prepared, { true }) { value.playerClean.set(it.cleanupConfirmed) })
        return backend
    }
    private fun register(value: FactoryAudioCoordinator.Session) {
        val type = com.jarvys.factory.runtime.AudioPlaybackControl.Registration::class.java
        val constructor = type.getDeclaredConstructor(android.os.IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
        value.registration = constructor.newInstance(Binder(), Runnable { value.revoke() })
    }
    private fun incoming(backend: FakeAudio): FactoryAudioRecordingActivity {
        val uid = Binder.getCallingUid(); val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true }
        })
        pm.setPackagesForUid(uid, proof.appId)
        val nonce = "d".repeat(64)
        val control = com.jarvys.factory.runtime.AudioPlaybackControl(nonce) { caller -> caller == uid }
        val request = Intent(context, FactoryAudioRecordingActivity::class.java).putExtras(Bundle().apply {
            putInt("protocolVersion", 1); putString("nonce", nonce); putInt("size", syntheticWav().size)
            putString("sha256", "e".repeat(64)); putBinder("transfer", Binder()); putBinder("control", control)
        })
        FactoryAudioRecordingActivity.playerFactory = { FactoryAudioPlayer(backend) }
        val controller = Robolectric.buildActivity(FactoryAudioRecordingActivity::class.java, request); controllers += controller
        shadowOf(controller.get()).setCallingPackage(proof.appId)
        controller.create().start().resume().visible(); controller.get().onWindowFocusChanged(true); drain()
        return controller.get()
    }
    @Test fun allowAutomaticallyPlaysOnceAndCompletesAfterConfirmedNativeCleanup() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        val backend = FakeAudio(); val activity = incoming(backend)
        assertEquals(1, backend.plays); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(android.view.View.GONE, button(activity, "play").visibility)
        backend.onMarker!!.invoke(); drain()
        assertEquals(1, backend.releases); assertEquals(1, backend.plays); assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode); assertNull(coordinator.session())
        assertTrue(shadowOf(activity).resultIntent.getBooleanExtra("playbackAttempted", false))
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("audibilityConfirmed", true))
    }
    @Test fun askGetsOneMeaningfulApprovalAndReleasesGuardBeforePlayback() {
        val backend = FakeAudio(); val activity = incoming(backend)
        assertEquals(0, backend.plays); assertTrue(MemoryUiAutomationGuard.isProtected())
        button(activity, "play").performClick(); drain()
        assertEquals(1, backend.plays); assertFalse(MemoryUiAutomationGuard.isProtected())
        backend.onMarker!!.invoke(); drain(); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }
    @Test fun deniedAudioAcquiresNoPcmPlayerOrJournal() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
        val backend = FakeAudio(); val activity = incoming(backend)
        assertTrue(activity.isFinishing); assertNull(coordinator.session()); assertEquals(0, backend.writes); assertEquals(0, backend.plays)
        assertFalse(File(root, "interaction.json").exists()); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun policyChangeStopsActiveAudioAndReturnsNoAudibilityClaim() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        val backend = FakeAudio(); val activity = incoming(backend); assertEquals(1, backend.plays)
        policy.setMode(FactoryOperationPolicy.Subject.installed(proof), FactoryOperationPolicy.Operation.AUDIO_PLAY, FactoryOperationPolicy.Mode.DENY)
        shadowOf(Looper.getMainLooper()).idle(); drain()
        assertEquals(1, backend.releases); assertEquals(1, backend.plays); assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
        assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun recoveryNeverReplaysAnAllowSession() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        val value = session(); register(value); val backend = FakeAudio(); value.player = FactoryAudioPlayer(backend)
        val activity = launch(reviewFixture = false).get(); drain()
        assertFalse(button(activity, "play").isEnabled); assertEquals(0, backend.plays); assertFalse(value.attempted)
    }
    @Test fun actualWorkerPreparationMustReturnAndDiscardBeforeNewRecoveryCanClose() {
        val value = session(); register(value)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val backend = FakeAudio().apply { beforeWrite = {
            check(Looper.myLooper() != Looper.getMainLooper())
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        } }
        value.player = FactoryAudioPlayer(backend)
        val original = launch(); val activity = original.get()
        assertTrue(button(activity, "play").isEnabled)
        var recovery: FactoryAudioActivity? = null
        try {
            button(activity, "play").performClick()
            assertTrue("Native preparation did not reach worker", entered.await(5, TimeUnit.SECONDS))
            assertTrue(value.preparing.get()); assertFalse(value.playerClean.get())
            original.pause()
            recovery = launch().get()
            assertTrue(value.revoked.get()); assertFalse(button(recovery, "close").isEnabled)
            button(recovery, "close").performClick()
            assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
            assertEquals(0, backend.releases); assertEquals(0, backend.plays)
        } finally { release.countDown() }
        drain()
        assertFalse(value.preparing.get()); assertTrue(value.playerClean.get())
        assertEquals(1, backend.writes); assertEquals(1, backend.releases); assertEquals(0, backend.plays)
        assertFalse(FactoryInteractionAdmission.available())
        val current = recovery!!; render(current); assertTrue(button(current, "close").isEnabled)
        button(current, "close").performClick(); drain()
        assertEquals("closed_outcome_unknown", coordinator.status()); assertTrue(FactoryInteractionAdmission.available())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(current).resultCode)
    }
    @Test fun saturatedWorkerRejectsOnlyUnstartedPreparationAndPreservesCleanState() = rejectedPreparation(false)
    @Test fun saturatedWorkerRejectionCannotErasePreviouslyUnconfirmedNativeCleanup() = rejectedPreparation(true)
    private fun rejectedPreparation(retainUnclean: Boolean) {
        val value = session(); register(value)
        val backend = FakeAudio(); val player = FactoryAudioPlayer(backend); value.player = player
        if (retainUnclean) {
            val prepared = player.prepare(value.pcm!!); backend.releaseFails = true
            assertFalse(player.discard(prepared).cleanupConfirmed); value.playerClean.set(false)
        }
        val activity = launch().get(); assertTrue(button(activity, "play").isEnabled)
        drain()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val queuedRan = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = FactoryAudioCoordinator.WORKER
        worker.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.execute { queuedRan.set(true) }
            assertEquals(1, worker.queue.size)
            button(activity, "play").performClick()
            assertFalse(value.preparing.get()); assertEquals(!retainUnclean, value.playerClean.get())
            assertTrue(value.revoked.get()); assertFalse(value.attempted)
            assertEquals(if (retainUnclean) 1 else 0, backend.writes); assertEquals(0, backend.plays)
            assertEquals(1, worker.queue.size); assertFalse(queuedRan.get())
            assertFalse(FactoryInteractionAdmission.available())
        } finally { release.countDown() }
        drain(); assertTrue(queuedRan.get())
        if (retainUnclean) {
            button(activity, "close").performClick(); drain()
            assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
            backend.releaseFails = false
        }
        button(activity, "close").performClick(); drain()
        assertEquals("closed_outcome_unknown", coordinator.status()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun missingCallerOrRecreatedRequestHasNoAuthority() {
        for (saved in listOf<Bundle?>(null, Bundle())) {
            val activity = launch(saved, Intent(context, FactoryAudioActivity::class.java)).get()
            assertTrue(activity.isFinishing); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertNull(button(activity, "play")); assertNull(shadowOf(activity).nextStartedActivity)
        }
        assertEquals("idle", coordinator.status())
    }
    @Test fun secureRecoveryControlsNeverAutoPlayAndRequireWindowFocus() {
        val value = session(); val activity = launch().get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        for (name in listOf("play", "close")) assertTrue(button(activity, name).filterTouchesWhenObscured)
        assertFalse(button(activity, "play").isEnabled); assertFalse(value.attempted)
        activity.onWindowFocusChanged(false); button(activity, "close").performClick()
        assertTrue(value.revoked.get()); assertFalse(button(activity, "close").isEnabled)
        assertEquals("review", coordinator.status()); assertTrue(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun pauseRevokesAndStopsImmediatelyWithoutWorkerOrCoordinatorLock() {
        val value = session(); val backend = startFake(value); val controller = launch()
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(coordinator) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }.apply { start() }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime(); controller.pause()
            assertTrue("Urgent stop waited for coordinator lock", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1))
        } finally { release.countDown(); holder.join(5000) }
        assertTrue(value.revoked.get()); assertTrue(backend.stops > 0); assertEquals(1, backend.releases)
        assertTrue(value.playerClean.get()); assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun focusLossStopsAndCannotRestorePlaybackByRegainingFocus() {
        val value = session(); val backend = startFake(value); val activity = launch().get()
        activity.onWindowFocusChanged(false); activity.onWindowFocusChanged(true)
        assertTrue(value.revoked.get()); assertEquals(1, backend.releases); assertEquals(1, backend.plays)
        assertFalse(button(activity, "play").isEnabled); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun backWhilePreparingRetainsRecoveryGuardAndNeverClaimsCleanup() {
        val value = session(); val activity = launch().get()
        value.preparing.set(true); value.playerClean.set(false); set(activity, "working", true)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing); assertTrue(value.revoked.get()); assertFalse(value.playerClean.get())
        assertTrue(value.preparing.get()); assertFalse(FactoryInteractionAdmission.available())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun preparationInFlightDisablesRecoveryCloseAfterRecreation() {
        val value = session(); value.revoke(); value.preparing.set(true); value.playerClean.set(false)
        val activity = launch().get(); render(activity)
        assertFalse(button(activity, "close").isEnabled); button(activity, "close").performClick(); drain()
        assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available()); assertFalse(activity.isFinishing)
    }
    @Test fun explicitRecoveryRetriesCleanupAndOnlyThenReleasesDurableGuard() {
        val value = session(); val backend = startFake(value); backend.releaseFails = true
        val activity = launch().get(); activity.onWindowFocusChanged(false); activity.onWindowFocusChanged(true)
        assertFalse(value.playerClean.get()); button(activity, "close").performClick(); drain()
        assertTrue(coordinator.needsRecovery()); assertFalse(activity.isFinishing); assertFalse(FactoryInteractionAdmission.available())
        backend.releaseFails = false; button(activity, "close").performClick(); drain()
        assertTrue(activity.isFinishing); assertEquals("closed_outcome_unknown", coordinator.status())
        assertTrue(FactoryInteractionAdmission.available()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent); assertEquals(1, backend.plays)
    }
    @Test fun closeWithoutPlaybackReturnsOnlyUnknownAudibilityReceipt() {
        session(); val activity = launch().get(); button(activity, "close").performClick(); drain()
        assertEquals("closed", coordinator.status()); assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("audibilityConfirmed", true))
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("playbackAttempted", true))
    }
}

/** Uses the real request/registration flow with a fake native audio implementation. */
class FactoryAudioRecordingActivity : FactoryAudioActivity() {
    override fun createAudioPlayer(): FactoryAudioPlayer = playerFactory()
    companion object { lateinit var playerFactory: () -> FactoryAudioPlayer }
}

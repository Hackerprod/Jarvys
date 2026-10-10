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
    private val root get() = File(context.noBackupFilesDir, "factory-audio")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryAudioCoordinator
    private val controllers = mutableListOf<ActivityController<FactoryAudioActivity>>()
    private val io = Executors.newSingleThreadExecutor()
    private class FakeAudio : FactoryAudioPlayer.Platform, FactoryAudioPlayer.Track {
        var stops = 0; var releases = 0; var plays = 0; var releaseFails = false
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
        override fun write(pcm: FactoryAudioPcm) = pcm.pcmBytes
        override fun marker(frames: Int, callback: () -> Unit) { }
        override fun play() { plays++ }
        override fun stop() { stops++ }
        override fun release() { releases++; if (releaseFails) error("synthetic release failure") }
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        coordinator = FactoryAudioCoordinator(context, { proof }, { _, active -> active(); syntheticWav() })
        FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
    }
    @After fun cleanup() {
        drain()
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        io.shutdown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    private fun drain() {
        val worker = FactoryAudioCoordinator.WORKER; worker.prestartCoreThread()
        val barrier = FutureTask(Callable { Unit })
        check(worker.queue.offer(barrier, 5, TimeUnit.SECONDS)); barrier.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun launch(saved: Bundle? = null, intent: Intent = FactoryAudioCoordinator.recoveryIntent(context)): ActivityController<FactoryAudioActivity> {
        val controller = Robolectric.buildActivity(FactoryAudioActivity::class.java, intent)
        controllers += controller; controller.create(saved).start().resume().visible()
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

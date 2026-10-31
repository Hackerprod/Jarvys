package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
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
        coordinator = FactoryAudioCoordinator(context, { proof }, { _, active -> active(); syntheticWav() })
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
    @Test fun authenticatedAudioRequestStartsPlaybackWithoutJarvysApproval() {
        val backend = FakeAudio(); val activity = incoming(backend)
        assertEquals(1, backend.plays); assertFalse(MemoryUiAutomationGuard.isProtected())
        backend.onMarker!!.invoke(); drain(); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }
    @Test fun missingCallerOrRecreatedRequestHasNoAuthority() {
        for (saved in listOf<Bundle?>(null, Bundle())) {
            val controller = Robolectric.buildActivity(FactoryAudioActivity::class.java, Intent(context, FactoryAudioActivity::class.java))
            controllers += controller
            val activity = controller.create(saved).start().resume().visible().get()
            assertTrue(activity.isFinishing); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        assertEquals("idle", coordinator.status())
    }
    @Test fun focusLossStopsAndCannotRestorePlaybackByRegainingFocus() {
        val backend = FakeAudio(); val activity = incoming(backend); val value = coordinator.session()!!
        activity.onWindowFocusChanged(false); drain()
        assertTrue(value.revoked.get()); assertEquals(1, backend.releases); assertEquals(1, backend.plays)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
}

/** Uses the real request/registration flow with a fake native audio implementation. */
class FactoryAudioRecordingActivity : FactoryAudioActivity() {
    override fun createAudioPlayer(): FactoryAudioPlayer = playerFactory()
    companion object { lateinit var playerFactory: () -> FactoryAudioPlayer }
}

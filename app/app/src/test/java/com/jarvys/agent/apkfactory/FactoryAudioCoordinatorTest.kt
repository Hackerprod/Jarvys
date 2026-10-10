package com.jarvys.agent.apkfactory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.AudioPlaybackControl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryAudioCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-audio")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryAudioCoordinator
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected rejection", runCatching(block).isFailure)
    private fun intent() = Intent(context, FactoryAudioActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "d".repeat(64)); putInt("size", syntheticWav().size)
        putString("sha256", "e".repeat(64)); putBinder("transfer", Binder()); putBinder("control", Binder())
    })
    private fun request() = FactoryAudioCoordinator.Request.parse(intent())
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun register(value: FactoryAudioCoordinator.Session) {
        val ctor = AudioPlaybackControl.Registration::class.java.getDeclaredConstructor(android.os.IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
        value.registration = ctor.newInstance(Binder(), Runnable { value.revoke() })
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        coordinator = FactoryAudioCoordinator(context, { proof }, { _, check -> check(); syntheticWav() })
    }
    @After fun cleanup() {
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    @Test fun exactRequestShapeRejectsTransportAndTypeConfusion() {
        val invalid = listOf(intent().putExtra("extra", true), intent().putExtra("protocolVersion", 1L), intent().putExtra("nonce", "D".repeat(64)),
            intent().putExtra("size", 46L), intent().putExtra("size", FactoryAudioPcm.MAX_BYTES + 1), intent().putExtra("sha256", "x"),
            intent().putExtra("transfer", "binder"), intent().setData(Uri.parse("content://synthetic/audio")),
            intent().apply { component = null }, intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            intent().apply { clipData = ClipData.newPlainText("x", "y") }, intent().apply { selector = Intent("synthetic") })
        invalid.forEach { denied { FactoryAudioCoordinator.Request.parse(it) } }
        assertEquals(syntheticWav().size, request().size)
    }
    @Test fun callerProofMustSucceedBeforeAnyPullOrJournal() {
        var pulled = false
        coordinator = FactoryAudioCoordinator(context, { proof }, { _, _ -> pulled = true; syntheticWav() })
        denied { io { coordinator.begin(null, request()) {} } }
        denied { io { coordinator.begin("evil.app", request()) {} } }
        assertFalse(pulled); assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
    }
    @Test fun journalIsMinimalAndCloseClearsOwnedBytesWithoutClaimingAudibility() {
        val value = begin(); val bytes = value.pcm!!.bytes
        val journal = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet())
        val result = io { coordinator.close(value, false) {} }!!
        assertEquals(setOf("nonce", "playbackAttempted", "audibilityConfirmed"), result.extras!!.keySet())
        assertFalse(result.getBooleanExtra("audibilityConfirmed", true)); assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        denied { begin() }
    }
    @Test fun preparationRequiresNativeHumanRegistrationAndStableIdentity() {
        val value = begin()
        denied { io { coordinator.preparePlay(value) { error("no human") } } }
        denied { io { coordinator.preparePlay(value) {} } }
        register(value)
        val original = proof
        for (changed in listOf(proof.copy(certificate="f".repeat(64)), proof.copy(apk="f".repeat(64)), proof.copy(version=3), proof.copy(record="f".repeat(64)))) {
            proof = changed; denied { io { coordinator.preparePlay(value) {} } }; assertFalse(value.attempted)
        }
        proof = original; io { coordinator.preparePlay(value) {} }; assertEquals("play_pending", coordinator.status())
        denied { io { coordinator.preparePlay(value) {} } }; io { coordinator.verifyReady(value) {} }
        proof = proof.copy(version = 4); denied { io { coordinator.verifyReady(value) {} } }
    }
    @Test fun recoveryNeverRehydratesAuthorityAndCorruptionKeepsGuard() {
        begin()
        val restored = FactoryAudioCoordinator(context, { proof }); io { restored.restore() }
        assertNull(restored.session()); assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
        denied { io { restored.close(null, true) { error("no human") } } }
        io { restored.close(null, true) {} }
        // Original owner remains independently protected, as after an unclosed synthetic process.
        assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun malformedAndOversizedJournalsFailClosed() {
        for (body in listOf("{", "x".repeat(4097), "{\"schemaVersion\":1,\"state\":\"review\",\"open\":false,\"nonce\":\"${"d".repeat(64)}\"}")) {
            root.mkdirs(); File(root, "interaction.json").writeText(body)
            val restored = FactoryAudioCoordinator(context, { proof }); io { restored.restore() }
            assertTrue(restored.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
            io { restored.close(null, true) {} }; assertEquals("closed_outcome_unknown", restored.status())
        }
    }
    @Test fun preparingOrUnconfirmedPlayerBlocksEvenExplicitRecovery() {
        val value = begin(); value.revoke(); value.preparing.set(true)
        denied { io { coordinator.close(value, true) {} } }; assertTrue(MemoryUiAutomationGuard.isProtected())
        value.preparing.set(false); value.playerClean.set(false)
        denied { io { coordinator.close(value, true) {} } }; assertFalse(FactoryInteractionAdmission.available())
        value.playerClean.set(true); assertNull(io { coordinator.close(value, true) {} }); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun stalledCanceledTransferKeepsAdmissionUntilActualReturnAndRecovery() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        coordinator = FactoryAudioCoordinator(context, { proof }, { _, active -> entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); active(); syntheticWav() })
        val pending = executor.submit(Callable { coordinator.begin(proof.appId, request()) {} })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); val value = coordinator.session()!!
            synchronized(coordinator) { value.revoke(); assertTrue(value.revoked.get()) }
            assertTrue(coordinator.isBusy()); assertFalse(FactoryInteractionAdmission.available()); assertFalse(coordinator.canBegin())
            val other = Executors.newSingleThreadExecutor()
            try { denied { other.submit(Callable { coordinator.close(value, true) {} }).get(1, TimeUnit.SECONDS) } } finally { other.shutdownNow() }
        } finally { finish.countDown() }
        denied { pending.get(5, TimeUnit.SECONDS) }; assertFalse(coordinator.isBusy()); assertTrue(coordinator.needsRecovery())
        assertFalse(FactoryInteractionAdmission.available()); io { coordinator.close(coordinator.session(), true) {} }; assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun recoveryTokenIsExactOneUseAndNotGrantableByExtra() {
        val token = FactoryAudioCoordinator.recoveryIntent(context)
        assertFalse(FactoryAudioCoordinator.consumeRecoveryToken(Intent(token).putExtra("play", true)))
        assertFalse(FactoryAudioCoordinator.consumeRecoveryToken(Intent(token).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
        assertTrue(FactoryAudioCoordinator.consumeRecoveryToken(token)); assertFalse(FactoryAudioCoordinator.consumeRecoveryToken(token))
        assertFalse(FactoryAudioCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "fake")))
    }
}

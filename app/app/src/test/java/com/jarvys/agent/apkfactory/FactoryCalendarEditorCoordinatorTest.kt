package com.jarvys.agent.apkfactory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.ExternalLaunchControl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic typed actions and Binder fixtures only. No maps, dialer, calls, navigation, or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryCalendarEditorCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private val owners = mutableListOf<FactoryExternalLaunchCoordinator>()
    private val browserOwners = mutableListOf<FactoryBrowserCoordinator>()
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected rejection", runCatching(block).isFailure)
    private fun make(verify: (String, String) -> FactoryDocumentCoordinator.Proof = { _, _ -> proof }) = FactoryExternalLaunchCoordinator(context, verify).also { owners += it }
    private fun intent(method: String = "calendar.insert", args: String = ARGS) = Intent(context, FactoryExternalActionActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "d".repeat(64)); putString("method", method); putString("args", args); putBinder("control", Binder())
    })
    private fun request(method: String = "calendar.insert", args: String = ARGS) = FactoryExternalLaunchCoordinator.Request.parse(intent(method, args))
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun register(value: FactoryExternalLaunchCoordinator.Session, callback: Runnable = Runnable { value.revoke() }) {
        val constructor = ExternalLaunchControl.Registration::class.java.getDeclaredConstructor(IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
        value.registration = constructor.newInstance(Binder(), callback)
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively(); coordinator = make()
    }
    @After fun cleanup() {
        owners.asReversed().forEach { owner ->
            owner.session()?.preparing = false
            if (owner.needsRecovery()) io { owner.close(owner.session(), true, true) {} }
            else owner.session()?.let { io { owner.close(it, false, true) {} } }
        }
        browserOwners.asReversed().forEach { owner ->
            if (owner.needsRecovery()) io { owner.close(owner.session(), true, true) {} }
            else owner.session()?.let { io { owner.close(it, false, true) {} } }
        }
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively(); File(context.noBackupFilesDir, "factory-browser").deleteRecursively()
    }
    @Test fun absentWrongOrRejectedCallerProducesNoJournalOrAdmission() {
        denied { io { coordinator.begin(null, request()) {} } }; denied { io { coordinator.begin("example.wrong.app", request()) {} } }
        coordinator = make { _, _ -> error("No latest signed evidence") }
        denied { begin() }; assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
    }
    @Test fun failedDurableWriteRetainsGuardUntilActualHumanRecoveryCanPersist() {
        root.writeText("Synthetic filesystem obstruction")
        denied { begin() }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); denied { io { coordinator.close(coordinator.session(), true, true) {} } }
        assertTrue(coordinator.needsRecovery()); check(root.delete()); check(root.mkdirs())
        assertNull(io { coordinator.close(coordinator.session(), true, true) {} }); assertEquals("closed_outcome_unknown", coordinator.status())
        assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun externalAttemptNeedsExplicitHumanClosureAndNeverConfirmsAction() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }; value.launchRequested = true
        denied { io { coordinator.close(value, false, false) {} } }; assertTrue(MemoryUiAutomationGuard.isProtected())
        val receipt = io { coordinator.close(value, false, true) {} }!!
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertTrue(value.registration == null); assertTrue(value.revoked.get()); assertEquals("closed", coordinator.status())
    }
    @Test fun restoreKeepsUnknownGuardButNeverTypedDataLaunchOrBinderAuthority() {
        val original = begin(); register(original); io { coordinator.prepareLaunch(original) {} }
        val restored = make(); io { restored.restore() }
        assertNull(restored.session()); assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
        denied { io { restored.close(null, true, false) {} } }; denied { io { restored.close(null, true, true) { error("No human") } } }
        assertNull(io { restored.close(null, true, true) {} }); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(File(root, "interaction.json").readText().contains(QUERY))
    }
    @Test fun pendingPreparationRetainsAdmissionEvenAfterRevocationAndExplicitRecovery() {
        val value = io { coordinator.begin(proof.appId, request(), preparing = true) {} }; assertTrue(value.preparing)
        denied { io { coordinator.close(value, false, false) {} } }; value.revoke()
        denied { io { coordinator.close(value, true, true) {} } }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        value.preparing = false; assertNull(io { coordinator.close(value, true, true) {} }); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun closedJournalPreventsNonceReplayAcrossRestore() {
        val value = begin(); io { coordinator.close(value, false, false) {} }
        val restored = make(); io { restored.restore() }; assertTrue(restored.canBegin()); assertFalse(restored.needsRecovery())
        denied { io { restored.begin(proof.appId, request()) {} } }
        val fresh = io { restored.begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} }; assertNotNull(fresh)
    }
    @Test fun sourceVerifierPinsOneInstalledUidAndRejectsSharedUid() {
        val value = begin(); val pm = shadowOf(context.packageManager); val uid = 20002
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid } })
        pm.setPackagesForUid(uid, proof.appId)
        val verifier = io { coordinator.sourceVerifier(value) }; assertTrue(verifier.allowed(uid)); assertFalse(verifier.allowed(uid + 1))
        pm.setPackagesForUid(uid, proof.appId, "example.shared.app"); denied { io { coordinator.sourceVerifier(value) } }
    }
    @Test fun calendarCapabilityIsRecheckedForTimedAndAllDayAtEveryStage() {
        for ((index, spec) in FactoryCalendarEditorTestPackages.SPECS.withIndex()) {
            val seen = mutableListOf<String>()
            coordinator = make { appId, capability -> check(appId == proof.appId); check(capability == spec.capability); seen += capability; proof }
            val req = request(FactoryCalendarEditorTestPackages.method(spec), FactoryCalendarEditorTestPackages.args(spec)).copy(nonce = (index + 1).toString().repeat(64))
            val value = io { coordinator.begin(proof.appId, req) {} }; register(value)
            io { coordinator.prepareLaunch(value) {} }; io { coordinator.verifyReady(value) {} }
            assertEquals(List(3) { spec.capability }, seen)
            val raw = File(root, "interaction.json").readText()
            for (secret in listOf(req.args, spec.calendar.title, spec.calendar.location, spec.calendar.description, spec.calendar.timeZone, req.method, proof.appId)) assertFalse(raw.contains(secret))
            assertEquals("launch_pending", JSONObject(raw).getString("state"))
            val receipt = io { coordinator.close(value, false, true) {} }!!
            assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        }
    }
    @Test fun approvingOtherCapabilitiesNeverApprovesCalendar() {
        for (spec in FactoryCalendarEditorTestPackages.SPECS) {
            val permitted = "contacts"
            var requested: String? = null
            coordinator = make { _, capability -> requested = capability; check(capability == permitted); proof }
            denied { io { coordinator.begin(proof.appId, request(FactoryCalendarEditorTestPackages.method(spec), FactoryCalendarEditorTestPackages.args(spec))) {} } }
            assertEquals(spec.capability, requested); assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
        }
    }
    @Test fun latestSourceProofEveryFieldRemainsPinnedForTimedCalendar() = pinLatest(FactoryCalendarEditorTestPackages.TIMED)
    @Test fun latestSourceProofEveryFieldRemainsPinnedForAllDayCalendar() = pinLatest(FactoryCalendarEditorTestPackages.ALL_DAY)
    private fun pinLatest(spec: com.jarvys.factory.contract.ExternalLaunchSpec) {
        val value = io { coordinator.begin(proof.appId, request(FactoryCalendarEditorTestPackages.method(spec), FactoryCalendarEditorTestPackages.args(spec))) {} }
        denied { io { coordinator.prepareLaunch(value) {} } }; register(value)
        val original = proof
        for (changed in listOf(proof.copy(appId = "example.other.app"), proof.copy(certificate = "f".repeat(64)), proof.copy(apk = "f".repeat(64)), proof.copy(version = 3), proof.copy(record = "f".repeat(64)))) {
            proof = changed; denied { io { coordinator.prepareLaunch(value) {} } }; assertFalse(value.attempted)
        }
        proof = original; io { coordinator.prepareLaunch(value) {} }; assertTrue(value.attempted)
        proof = proof.copy(record = "e".repeat(64)); denied { io { coordinator.verifyReady(value) {} } }
        assertFalse(value.launchRequested)
    }
    companion object {
        const val QUERY = FactoryCalendarEditorTestPackages.TITLE
        val ARGS = FactoryCalendarEditorTestPackages.args(FactoryCalendarEditorTestPackages.TIMED)
    }
}

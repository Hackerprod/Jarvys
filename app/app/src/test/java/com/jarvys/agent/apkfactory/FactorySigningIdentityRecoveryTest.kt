package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.factory.contract.ManifestPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Independent host storage contexts, never Android installations or device acceptance.
 * All RSA and wrapping keys are ephemeral RAM fixtures. Only encrypted private-key records
 * and encrypted backups may reach TemporaryFolder. Every AndroidKeyStore hook is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactorySigningIdentityRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private class RamProtection : FactorySigningIdentity.PortableProtection {
        val keys = mutableMapOf<String, SecretKey>()
        var beforeWrap: () -> Unit = {}
        var afterWrap: () -> Unit = {}
        var wrapCalls = 0
        var unwrapCalls = 0
        var lastWrapBuffer: ByteArray? = null
        var lastUnwrapBuffer: ByteArray? = null

        override fun present(appId: String) = keys.containsKey(appId)
        override fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject {
            wrapCalls++
            lastWrapBuffer = bytes
            beforeWrap()
            val key = keys.getOrPut(appId) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(appId, fingerprint))
            val result = JSONObject().put("format", 1)
                .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
                .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(bytes)))
            afterWrap()
            return result
        }

        override fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray {
            unwrapCalls++
            check(value.getInt("format") == 1)
            val key = keys[appId] ?: error("RAM wrapping protection was lost")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, Base64.getDecoder().decode(value.getString("iv"))))
            cipher.updateAAD(aad(appId, fingerprint))
            return cipher.doFinal(Base64.getDecoder().decode(value.getString("ciphertext")))
                .also { lastUnwrapBuffer = it }
        }

        private fun aad(appId: String, fingerprint: String) =
            "ram-only-factory-test:$appId:$fingerprint".toByteArray(Charsets.UTF_8)
    }

    private class RamIdentity(
        context: Context,
        protection: RamProtection,
        private val legacyKeys: MutableMap<String, FactorySigningIdentity.Identity>,
        private val creations: AtomicInteger,
    ) : FactorySigningIdentity(context, protection) {
        var finishOverride: ((AtomicFile, FileOutputStream) -> Unit)? = null
        override fun legacyPresent(appId: String) = legacyKeys.containsKey(appId)
        override fun legacyIdentity(appId: String) = legacyKeys[appId] ?: error("RAM legacy key missing")
        override fun createLegacy(appId: String) {
            creations.incrementAndGet()
            check(!legacyKeys.containsKey(appId))
            legacyKeys[appId] = Identity(primary.key, primary.certificate, fingerprint(primary))
        }
        override fun finishRecord(target: AtomicFile, stream: FileOutputStream) {
            val injected = finishOverride
            if (injected == null) super.finishRecord(target, stream) else injected(target, stream)
        }
    }

    private inner class Fixture(val protection: RamProtection = RamProtection()) {
        val root = folder.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = root
        }
        val legacyKeys = mutableMapOf<String, FactorySigningIdentity.Identity>()
        val creations = AtomicInteger()
        val identities = newService()
        val recordFile get() = File(root, "apk-factory/identities/$APP_ID.json")
        fun newService() = RamIdentity(context, protection, legacyKeys, creations)
        fun create(material: FactoryIdentityBackup.Material = primary) {
            identities.createRecoverableAfterApproval(material)
        }
        fun state() = identities.state(APP_ID)
        fun snapshot() = identities.recoverySnapshot(APP_ID)
        fun json() = JSONObject(recordFile.readText())
        fun replace(json: JSONObject) = recordFile.writeText(json.toString())
        fun record(version: Int = 5, sha: String = HASH_A, withScope: Boolean = true) {
            identities.recordSigned(APP_ID, state().fingerprint!!, version, sha,
                if (withScope) scope() else null)
        }
        fun installLegacy(withKey: Boolean = true, reserved: Boolean = false) {
            if (withKey) legacyKeys[APP_ID] = FactorySigningIdentity.Identity(
                primary.key, primary.certificate, fingerprint(primary))
            recordFile.parentFile!!.mkdirs()
            recordFile.writeText(JSONObject().put("schemaVersion", 1).put("appId", APP_ID)
                .put("state", if (reserved) "reserved" else "ready")
                .put("certificateSha256", fingerprint(primary)).put("lastVersion", 5)
                .put("lastApkSha256", HASH_A).toString())
        }
    }

    @Test fun noRecordIsNewAndReadingNeverCreatesAnyKey() {
        val f = Fixture()
        val state = f.state()
        assertFalse(state.existing)
        assertNull(state.fingerprint)
        assertNull(state.recordSha256)
        assertEquals(0, state.lastVersion)
        assertEquals(FactorySigningIdentity.LEGACY, state.mode)
        assertTrue(state.continuityKnown)
        assertNull(f.snapshot())
        assertFalse(f.recordFile.exists())
        assertEquals(0, f.creations.get())
        assertEquals(0, f.protection.wrapCalls)
    }

    @Test fun explicitNewRecoverableIdentityIsEncryptedAndBuffersAreCleared() {
        val f = Fixture()
        f.create()
        val state = f.state()
        assertTrue(state.existing)
        assertEquals(FactorySigningIdentity.RECOVERABLE, state.mode)
        assertEquals(fingerprint(primary), state.fingerprint)
        assertEquals(0, state.lastVersion)
        assertTrue(state.continuityKnown)
        assertEquals("local_history", state.continuityResolution)
        assertEquals("ready", f.json().getString("state"))
        assertEquals(0, f.creations.get())
        assertNotNull(f.protection.lastWrapBuffer)
        assertTrue(f.protection.lastWrapBuffer!!.all { it == 0.toByte() })
        assertTrue(f.protection.lastUnwrapBuffer!!.all { it == 0.toByte() })
        assertNoPlaintextPrivateKeys(f.root)
        val identity = f.identities.obtainAfterApproval(APP_ID, state)
        assertEquals(state.fingerprint, identity.fingerprint)
        provePossession(identity)
    }

    @Test fun ordinaryFirstSigningStillCreatesOnlyNonExportableLegacyIdentity() {
        val f = Fixture()
        val identity = f.identities.obtainAfterApproval(APP_ID, f.state())
        assertEquals(1, f.creations.get())
        assertEquals(FactorySigningIdentity.LEGACY, f.state().mode)
        assertEquals(identity.fingerprint, f.state().fingerprint)
        assertEquals(1, f.json().getInt("schemaVersion"))
        assertEquals(0, f.protection.wrapCalls)
        val before = f.recordFile.readBytes()
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, f.state()) }
        assertArrayEquals(before, f.recordFile.readBytes())
    }

    @Test fun creatingOverExistingRecoverableIdentityNeverRotatesItsCertificate() {
        val f = Fixture()
        f.create()
        f.record()
        val before = f.state()
        rejects { f.create(alternate) }
        rejects { f.create() }
        assertEquals(before, f.state())
        assertEquals(1, f.protection.wrapCalls)
    }

    @Test fun newCreationRejectsClaimedReleaseHistoryAndMismatchedPrivateKey() {
        for (material in listOf(primary.copy(lastVersion = 1),
            primary.copy(lastVersion = 1, lastApkSha256 = HASH_A),
            primary.copy(key = alternate.key))) {
            val f = Fixture()
            rejects { f.create(material) }
            assertFalse(f.recordFile.exists())
            assertEquals(0, f.protection.wrapCalls)
        }
    }

    @Test fun encryptedExportRestoresInIndependentStorageWithIndependentWrappingKey() {
        val source = Fixture()
        source.create()
        source.record()
        val export = source.identities.exportRecoverableAfterApproval(APP_ID, source.state())
        val encrypted = encryptedRoundTrip(export)
        val restored = Fixture()
        restored.identities.importRecoverableAfterApproval(encrypted, null)
        val state = restored.state()
        assertNotEquals(source.root, restored.root)
        assertNotSame(source.protection.keys[APP_ID], restored.protection.keys[APP_ID])
        assertEquals(source.state().fingerprint, state.fingerprint)
        assertEquals(5, state.lastVersion)
        assertEquals(HASH_A, state.lastApkSha256)
        assertNull(state.lastScope) // The key backup cannot reconstruct an absent local receipt/scope.
        assertFalse(state.continuityKnown)
        assertEquals("restored_unknown", state.continuityResolution)
        assertNoPlaintextPrivateKeys(folder.root)
    }

    @Test fun unresolvedRestoreBlocksKeyObtainingAndSignedHistoryMutation() {
        val f = Fixture()
        f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 5, lastApkSha256 = HASH_A), null)
        val unresolved = f.state()
        val snapshot = f.snapshot()
        rejects { f.identities.obtainAfterApproval(APP_ID, unresolved) }
        rejects { f.identities.recordSigned(APP_ID, unresolved.fingerprint!!, 6, HASH_B, scope()) }
        assertEquals(snapshot, f.snapshot())
        assertFalse(f.state().continuityKnown)
        assertEquals(0, f.creations.get())
    }

    @Test fun explicitEqualVersionReconciliationPreservesAnchoredScopeAndEvidence() {
        val f = Fixture()
        f.create()
        f.record()
        val backup = f.identities.exportRecoverableAfterApproval(APP_ID, f.state())
        f.identities.importRecoverableAfterApproval(backup, f.snapshot())
        val restored = f.state()
        assertFalse(restored.continuityKnown)
        assertEquals(scope(), restored.lastScope)
        f.identities.reconcileAfterApproval(APP_ID, restored, 5)
        val reconciled = f.state()
        assertTrue(reconciled.continuityKnown)
        assertEquals("user_declared_floor", reconciled.continuityResolution)
        assertEquals(5, reconciled.lastVersion)
        assertEquals(HASH_A, reconciled.lastApkSha256)
        assertEquals(scope(), reconciled.lastScope)
        provePossession(f.identities.obtainAfterApproval(APP_ID, reconciled))
        rejects { f.identities.recordSigned(APP_ID, reconciled.fingerprint!!, 5, HASH_B) }
        f.record(6, HASH_B)
        assertEquals(6, f.state().lastVersion)
    }

    @Test fun explicitHigherFloorIsMonotonicAndClearsUnsupportedApkAndScopeEvidence() {
        val f = Fixture()
        f.create()
        f.record()
        val old = f.state()
        rejects { f.identities.reconcileAfterApproval(APP_ID, old, 4) }
        rejects { f.identities.reconcileAfterApproval(APP_ID, old, -1) }
        assertEquals(old, f.state())
        f.identities.reconcileAfterApproval(APP_ID, old, 9)
        val raised = f.state()
        assertEquals(9, raised.lastVersion)
        assertNull(raised.lastApkSha256)
        assertNull(raised.lastScope)
        assertTrue(raised.continuityKnown)
        rejects { f.identities.reconcileAfterApproval(APP_ID, raised, 8) }
        rejects { f.identities.recordSigned(APP_ID, raised.fingerprint!!, 9, HASH_B) }
        f.record(10, HASH_B)
        assertEquals(10, f.state().lastVersion)
    }

    @Test fun everyStateApprovalIncludesRecordDigestEvenWhenPublicHistoryIsUnchanged() {
        val f = Fixture()
        f.create()
        f.identities.reconcileAfterApproval(APP_ID, f.state(), 0)
        val approved = f.state()
        f.identities.importRecoverableAfterApproval(primary, f.snapshot())
        f.identities.reconcileAfterApproval(APP_ID, f.state(), 0)
        val changed = f.state()
        assertEquals(approved.fingerprint, changed.fingerprint)
        assertEquals(approved.lastVersion, changed.lastVersion)
        assertNotEquals(approved.recordSha256, changed.recordSha256)
        assertEquals(approved.copy(recordSha256 = changed.recordSha256), changed)
        rejects { f.identities.obtainAfterApproval(APP_ID, approved) }
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, approved) }
        rejects { f.identities.reconcileAfterApproval(APP_ID, approved, 1) }
        assertEquals(changed, f.state())
    }

    @Test fun staleRestoreApprovalCannotOverwriteANewerSignedRelease() {
        val f = Fixture()
        f.create()
        val staleDigest = f.snapshot()
        f.record(7, HASH_B)
        val current = f.state()
        rejects { f.identities.importRecoverableAfterApproval(primary, staleDigest) }
        rejects { f.identities.importRecoverableAfterApproval(primary, null) }
        assertEquals(current, f.state())
    }

    @Test fun olderBackupPreservesMaximumVersionCertificateHashAndScope() {
        val f = Fixture()
        f.create()
        f.record(11, HASH_B)
        val current = f.state()
        f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 3, lastApkSha256 = HASH_A), f.snapshot())
        val restored = f.state()
        assertEquals(current.fingerprint, restored.fingerprint)
        assertEquals(11, restored.lastVersion)
        assertEquals(HASH_B, restored.lastApkSha256)
        assertEquals(scope(), restored.lastScope)
        assertFalse(restored.continuityKnown)
    }

    @Test fun newerBackupAdvancesFloorWithoutReusingAnOlderAnchoredScope() {
        val f = Fixture()
        f.create()
        f.record(3, HASH_A)
        f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 8, lastApkSha256 = HASH_B), f.snapshot())
        val restored = f.state()
        assertEquals(8, restored.lastVersion)
        assertEquals(HASH_B, restored.lastApkSha256)
        assertNull(restored.lastScope)
        assertFalse(restored.continuityKnown)
    }

    @Test fun equalVersionBackupWithoutHashRetainsStrongerLocalEvidence() {
        val f = Fixture()
        f.create()
        f.record()
        f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 5), f.snapshot())
        assertEquals(5, f.state().lastVersion)
        assertEquals(HASH_A, f.state().lastApkSha256)
        assertEquals(scope(), f.state().lastScope)
    }

    @Test fun differentCertificateAndSameVersionConflictingHashAreRejectedWithoutMutation() {
        val f = Fixture()
        f.create()
        f.record()
        val before = f.state()
        rejects { f.identities.importRecoverableAfterApproval(alternate.copy(lastVersion = 8), f.snapshot()) }
        rejects { f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 5, lastApkSha256 = HASH_B), f.snapshot()) }
        assertEquals(before, f.state())
        assertEquals(1, f.protection.wrapCalls)
    }

    @Test fun schemaOneIdentityRemainsNonExportableAndCannotBeConverted() {
        val f = Fixture()
        f.installLegacy()
        val existing = f.state()
        assertEquals(FactorySigningIdentity.LEGACY, existing.mode)
        assertEquals(5, existing.lastVersion)
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, existing) }
        rejects { f.identities.importRecoverableAfterApproval(primary, f.snapshot()) }
        rejects { f.create() }
        assertEquals(existing, f.state())
        assertEquals(0, f.creations.get())
        assertEquals(0, f.protection.wrapCalls)
        provePossession(f.identities.obtainAfterApproval(APP_ID, existing))
    }

    @Test fun missingLegacyKeyBlocksSigningAndImportRatherThanCreatingAReplacement() {
        val f = Fixture()
        f.installLegacy()
        val approved = f.state()
        f.legacyKeys.clear()
        val before = f.recordFile.readBytes()
        rejects { f.state() }
        rejects { f.identities.obtainAfterApproval(APP_ID, approved) }
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, approved) }
        rejects { f.identities.importRecoverableAfterApproval(primary, f.snapshot()) }
        rejects { f.create() }
        assertArrayEquals(before, f.recordFile.readBytes())
        assertEquals(0, f.creations.get())
        assertEquals(0, f.protection.wrapCalls)
    }

    @Test fun interruptedLegacyReservationCannotBeReplacedByARecoverableKey() {
        for (withKey in listOf(false, true)) {
            val f = Fixture()
            f.installLegacy(withKey = withKey, reserved = true)
            val before = f.recordFile.readBytes()
            rejects { f.state() }
            rejects { f.create() }
            rejects { f.identities.importRecoverableAfterApproval(primary, f.snapshot()) }
            assertArrayEquals(before, f.recordFile.readBytes())
            assertEquals(0, f.creations.get())
        }
    }

    @Test fun orphanLegacyOrWrappingKeyWithoutRecordBlocksCreationAndImport() {
        for (legacy in listOf(false, true)) {
            val f = Fixture()
            if (legacy) {
                f.legacyKeys[APP_ID] = FactorySigningIdentity.Identity(primary.key, primary.certificate, fingerprint(primary))
            } else {
                f.protection.keys[APP_ID] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
            rejects { f.state() }
            rejects { f.create() }
            rejects { f.identities.importRecoverableAfterApproval(primary, null) }
            assertFalse(f.recordFile.exists())
            assertEquals(0, f.creations.get())
        }
    }

    @Test fun wrappingFailurePinsReservationAndOnlyMatchingBackupCanRepairIt() {
        val f = Fixture()
        f.protection.beforeWrap = { error("Injected RAM wrapping failure") }
        rejects { f.create() }
        val reserved = f.json()
        assertEquals("reserved", reserved.getString("state"))
        assertEquals(fingerprint(primary), reserved.getString("certificateSha256"))
        assertFalse(reserved.has("protectedKey"))
        assertTrue(f.protection.lastWrapBuffer!!.all { it == 0.toByte() })
        val pinned = f.snapshot()
        rejects { f.state() }
        rejects { f.create(alternate) }
        rejects { f.identities.importRecoverableAfterApproval(alternate, pinned) }
        assertEquals(pinned, f.snapshot())
        f.protection.beforeWrap = {}
        f.identities.importRecoverableAfterApproval(primary, pinned)
        assertEquals(fingerprint(primary), f.state().fingerprint)
        assertFalse(f.state().continuityKnown)
        assertEquals(0, f.creations.get())
    }

    @Test fun finalPersistFailureLeavesPinnedReservationRecoverableWithMatchingBackup() {
        val f = Fixture()
        val directory = f.recordFile.parentFile!!
        val interruptedDirectory = File(directory.parentFile, "identities-persist-failure")
        f.protection.afterWrap = {
            check(directory.renameTo(interruptedDirectory))
            directory.writeText("Injected storage obstruction")
        }
        try {
            rejects { f.create() }
        } finally {
            f.protection.afterWrap = {}
            if (directory.isFile) check(directory.delete())
            if (interruptedDirectory.exists()) check(interruptedDirectory.renameTo(directory))
        }
        assertEquals("reserved", f.json().getString("state"))
        assertTrue(f.protection.present(APP_ID))
        val pinned = f.snapshot()
        rejects { f.state() }
        rejects { f.identities.importRecoverableAfterApproval(alternate, pinned) }
        f.identities.importRecoverableAfterApproval(primary, pinned)
        assertEquals(fingerprint(primary), f.state().fingerprint)
        assertFalse(f.state().continuityKnown)
        assertNoPlaintextPrivateKeys(f.root)
    }

    @Test fun silentlyFailedReservationCommitIsDetectedBeforeWrappingAnyKey() {
        val f = Fixture()
        f.identities.finishOverride = { target, stream -> target.failWrite(stream) }
        rejects { f.create() }
        assertFalse(f.recordFile.exists())
        assertEquals(0, f.protection.wrapCalls)
        assertFalse(f.protection.present(APP_ID))
        assertEquals(0, f.creations.get())
        assertFalse(f.state().existing)
    }

    @Test fun silentlyFailedReadyCommitRetainsReservationAndRequiresMatchingRepair() {
        val f = Fixture()
        var commits = 0
        f.identities.finishOverride = { target, stream ->
            if (++commits == 1) target.finishWrite(stream) else target.failWrite(stream)
        }
        rejects { f.create() }
        assertEquals(2, commits)
        assertEquals("reserved", f.json().getString("state"))
        assertEquals(fingerprint(primary), f.json().getString("certificateSha256"))
        assertEquals(1, f.protection.wrapCalls)
        val snapshot = f.snapshot()
        rejects { f.state() }
        rejects { f.identities.importRecoverableAfterApproval(alternate, snapshot) }
        f.identities.finishOverride = null
        f.identities.importRecoverableAfterApproval(primary, snapshot)
        assertEquals(fingerprint(primary), f.state().fingerprint)
        assertFalse(f.state().continuityKnown)
    }

    @Test fun silentlyFailedSignedHistoryCommitCannotReportAnAdvancedVersion() {
        val f = Fixture()
        f.create()
        f.record()
        val before = f.state()
        f.identities.finishOverride = { target, stream -> target.failWrite(stream) }
        rejects { f.record(6, HASH_B) }
        assertEquals(before, f.state())
        assertEquals(HASH_A, f.state().lastApkSha256)
        assertEquals(scope(), f.state().lastScope)
    }

    @Test fun errorAfterCommitDoesNotRollBackAlreadyDurableVersionHistory() {
        val f = Fixture()
        f.create()
        f.record()
        f.identities.finishOverride = { target, stream ->
            target.finishWrite(stream)
            error("Injected failure after the durable commit")
        }
        rejects { f.record(6, HASH_B) }
        assertEquals(6, f.state().lastVersion)
        assertEquals(HASH_B, f.state().lastApkSha256)
        assertEquals(scope(), f.state().lastScope)
        f.identities.finishOverride = null
        rejects { f.record(6, HASH_A) }
    }

    @Test fun failedReimportWrapPreservesEntireReadyRecordAndLatestRelease() {
        val f = Fixture()
        f.create()
        f.record(12, HASH_B)
        val before = f.state()
        val bytes = f.recordFile.readBytes()
        f.protection.beforeWrap = { error("Injected wrapping failure on existing identity") }
        rejects { f.identities.importRecoverableAfterApproval(primary, f.snapshot()) }
        assertArrayEquals(bytes, f.recordFile.readBytes())
        assertEquals(before, f.state())
        assertTrue(f.protection.lastWrapBuffer!!.all { it == 0.toByte() })
    }

    @Test fun lostLocalWrappingProtectionRestoresSameCertificateAndKeepsNewerHistory() {
        val f = Fixture()
        f.create()
        f.record(9, HASH_B)
        val snapshot = f.snapshot()
        f.protection.keys.clear()
        rejects { f.state() }
        rejects { f.create() }
        rejects { f.identities.importRecoverableAfterApproval(alternate, snapshot) }
        f.identities.importRecoverableAfterApproval(primary.copy(lastVersion = 2, lastApkSha256 = HASH_A), snapshot)
        val restored = f.state()
        assertEquals(fingerprint(primary), restored.fingerprint)
        assertEquals(9, restored.lastVersion)
        assertEquals(HASH_B, restored.lastApkSha256)
        assertEquals(scope(), restored.lastScope)
        assertFalse(restored.continuityKnown)
        assertEquals(0, f.creations.get())
    }

    @Test fun malformedJsonAndOversizeRecordsNeverBecomeNewIdentity() {
        for (bytes in listOf("{".toByteArray(), " ".repeat(65537).toByteArray(), byteArrayOf())) {
            val f = Fixture()
            f.recordFile.parentFile!!.mkdirs()
            f.recordFile.writeBytes(bytes)
            rejects { f.state() }
            rejects { f.create() }
            rejects { f.identities.importRecoverableAfterApproval(primary, null) }
            assertArrayEquals(bytes, f.recordFile.readBytes())
            assertEquals(0, f.protection.wrapCalls)
            assertEquals(0, f.creations.get())
        }
    }

    @Test fun malformedReadyRecordMetadataFailsClosedWithoutAnyMutation() {
        val f = Fixture()
        f.create()
        f.record()
        val original = f.recordFile.readText()
        val mutations = listOf<Pair<String, (JSONObject) -> Unit>>(
            "wrong app ID" to { it.put("appId", "org.example.other") },
            "future schema" to { it.put("schemaVersion", 3) },
            "string schema" to { it.put("schemaVersion", "2") },
            "fractional schema" to { it.put("schemaVersion", 2.5) },
            "unsupported mode" to { it.put("mode", "automatic") },
            "missing state" to { it.remove("state") },
            "negative version" to { it.put("lastVersion", -1) },
            "string version" to { it.put("lastVersion", "5") },
            "fractional version" to { it.put("lastVersion", 5.5) },
            "overflow version" to { it.put("lastVersion", 4294967301L) },
            "missing continuity" to { it.remove("continuityKnown") },
            "string continuity" to { it.put("continuityKnown", "true") },
            "unknown resolution" to { it.put("continuityResolution", "guessed") },
            "invalid hash" to { it.put("lastApkSha256", "invalid") },
            "certificate mismatch" to { it.put("certificateSha256", HASH_B) },
        )
        for ((description, mutate) in mutations) {
            f.replace(JSONObject(original).also(mutate))
            val bytes = f.recordFile.readBytes()
            assertThrows(description, Exception::class.java) { f.state() }
            assertArrayEquals(description, bytes, f.recordFile.readBytes())
        }
        f.recordFile.writeText(original)
        assertEquals(5, f.state().lastVersion)
    }

    @Test fun damagedEncryptedLocalKeyBlocksSigningButMatchingBackupCanRepairIt() {
        val f = Fixture()
        f.create()
        f.record()
        val approved = f.state()
        val json = f.json()
        val protected = json.getJSONObject("protectedKey")
        val ciphertext = Base64.getDecoder().decode(protected.getString("ciphertext"))
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        protected.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        f.replace(json)
        rejects { f.state() }
        rejects { f.identities.obtainAfterApproval(APP_ID, approved) }
        f.identities.importRecoverableAfterApproval(primary, f.snapshot())
        assertEquals(fingerprint(primary), f.state().fingerprint)
        assertEquals(5, f.state().lastVersion)
        assertEquals(HASH_A, f.state().lastApkSha256)
        assertFalse(f.state().continuityKnown)
    }

    @Test fun cancellationBeforeEveryExplicitIdentityActionLeavesStorageUntouched() {
        val empty = Fixture()
        rejects { empty.identities.createRecoverableAfterApproval(primary) { false } }
        rejects { empty.identities.importRecoverableAfterApproval(primary, null) { false } }
        assertFalse(empty.recordFile.exists())
        assertEquals(0, empty.protection.wrapCalls)
        val f = Fixture()
        f.create()
        f.record()
        val approved = f.state()
        val before = f.recordFile.readBytes()
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, approved) { false } }
        rejects { f.identities.importRecoverableAfterApproval(primary, f.snapshot()) { false } }
        rejects { f.identities.reconcileAfterApproval(APP_ID, approved, 9) { false } }
        assertArrayEquals(before, f.recordFile.readBytes())
        assertEquals(approved, f.state())
        assertEquals(1, f.protection.wrapCalls)
    }

    @Test fun approvalRevokedDuringValidationIsRecheckedBeforeAnyDurableMutation() {
        val creating = Fixture()
        var calls = 0
        rejects { creating.identities.createRecoverableAfterApproval(primary) { ++calls == 1 } }
        assertEquals(2, calls)
        assertFalse(creating.recordFile.exists())
        assertEquals(0, creating.protection.wrapCalls)

        val importing = Fixture()
        calls = 0
        rejects { importing.identities.importRecoverableAfterApproval(primary, null) { ++calls == 1 } }
        assertEquals(2, calls)
        assertFalse(importing.recordFile.exists())
        assertEquals(0, importing.protection.wrapCalls)

        val f = Fixture()
        f.create()
        f.record()
        val state = f.state()
        val before = f.recordFile.readBytes()
        calls = 0
        rejects { f.identities.importRecoverableAfterApproval(primary, state.recordSha256) { ++calls == 1 } }
        assertEquals(2, calls)
        assertArrayEquals(before, f.recordFile.readBytes())
        assertEquals(1, f.protection.wrapCalls)
        calls = 0
        rejects { f.identities.reconcileAfterApproval(APP_ID, state, 9) { ++calls == 1 } }
        assertEquals(2, calls)
        assertArrayEquals(before, f.recordFile.readBytes())
        val unwrapCalls = f.protection.unwrapCalls
        calls = 0
        rejects { f.identities.exportRecoverableAfterApproval(APP_ID, state) { ++calls == 1 } }
        assertEquals(2, calls)
        // state() reads the key once; export must not unwrap a second time after revocation.
        assertEquals(unwrapCalls + 1, f.protection.unwrapCalls)
        assertArrayEquals(before, f.recordFile.readBytes())
    }

    @Test fun atomicFileBackupRecoversPreviousDurableRecordWithoutRotatingIdentity() {
        val f = Fixture()
        f.create()
        f.record()
        val durable = f.state()
        val backup = File(f.recordFile.path + ".bak")
        check(f.recordFile.renameTo(backup))
        f.recordFile.writeText("{interrupted replacement")
        assertEquals(durable, f.state())
        assertFalse(backup.exists())
        assertEquals(1, f.protection.wrapCalls)
        assertEquals(0, f.creations.get())
    }

    @Test fun racingCreatorsAcrossServiceInstancesProduceOnePinnedIdentity() {
        val f = Fixture()
        val other = f.newService()
        val results = race(listOf(
            { f.identities.createRecoverableAfterApproval(primary) },
            { other.createRecoverableAfterApproval(alternate) },
        ))
        assertEquals(1, results.count { it })
        assertEquals(1, f.protection.wrapCalls)
        assertTrue(f.state().fingerprint in setOf(fingerprint(primary), fingerprint(alternate)))
        assertEquals(0, f.creations.get())
        assertNoPlaintextPrivateKeys(f.root)
    }

    @Test fun racingSameVersionCommitsAcrossInstancesAllowExactlyOneReceiptAnchor() {
        val f = Fixture()
        f.create()
        val other = f.newService()
        val fingerprint = f.state().fingerprint!!
        val results = race(listOf(
            { f.identities.recordSigned(APP_ID, fingerprint, 1, HASH_A, scope()) },
            { other.recordSigned(APP_ID, fingerprint, 1, HASH_B, scope()) },
        ))
        assertEquals(1, results.count { it })
        val state = f.state()
        assertEquals(1, state.lastVersion)
        assertEquals(if (results[0]) HASH_A else HASH_B, state.lastApkSha256)
        assertEquals(scope(), state.lastScope)
        assertEquals(state, other.state(APP_ID))
    }

    @Test fun racingRestoreAndSignedCommitNeverRollBackRecordedVersion() {
        val f = Fixture()
        f.create()
        f.record(5, HASH_A)
        val approved = f.state()
        val other = f.newService()
        val results = race(listOf(
            { f.identities.importRecoverableAfterApproval(primary, approved.recordSha256) },
            { other.recordSigned(APP_ID, approved.fingerprint!!, 6, HASH_B, scope()) },
        ))
        assertEquals(1, results.count { it })
        val state = f.state()
        if (results[0]) {
            assertEquals(5, state.lastVersion)
            assertEquals(HASH_A, state.lastApkSha256)
            assertFalse(state.continuityKnown)
        } else {
            assertEquals(6, state.lastVersion)
            assertEquals(HASH_B, state.lastApkSha256)
            assertTrue(state.continuityKnown)
        }
        assertEquals(scope(), state.lastScope)
    }

    @Test fun invalidApplicationIdsAreRejectedBeforeStorageOrKeyAccess() {
        val f = Fixture()
        for (appId in listOf("../outside", "/tmp/outside", "org..example", "android.example", "com.jarvys.other", "Single", "a".repeat(128))) {
            rejects { f.identities.state(appId) }
            rejects { f.identities.recoverySnapshot(appId) }
            rejects { f.create(primary.copy(appId = appId)) }
        }
        assertTrue(f.root.listFiles()!!.isEmpty())
        assertEquals(0, f.protection.wrapCalls)
        assertEquals(0, f.creations.get())
    }

    @Test fun validAppIdsCannotCollideBetweenLegacyAndPortableProtectionAliases() {
        val ids = listOf("org.example", "wrap.org.example", "wrap.wrap.org.example", APP_ID, "wrap.$APP_ID")
        ids.forEach(FactoryIdentityBackup::validateAppId)
        val legacy = ids.map { FactorySigningIdentity.legacyAlias(it) }.toSet()
        val protection = ids.map { FactorySigningIdentity.portableProtectionAlias(it) }.toSet()
        assertEquals(ids.size, legacy.size)
        assertEquals(ids.size, protection.size)
        assertTrue(legacy.intersect(protection).isEmpty())
        assertNotEquals(FactorySigningIdentity.legacyAlias("wrap.org.example"),
            FactorySigningIdentity.portableProtectionAlias("org.example"))
        rejects { FactoryIdentityBackup.validateAppId("wrap:org.example") }
    }

    @Test fun restoredFixtureKeySignsVerifiableHigherVersionWithOriginalCertificate() {
        val original = Fixture()
        original.create()
        val first = buildAndSign(original, 1)
        val backup = encryptedRoundTrip(original.identities.exportRecoverableAfterApproval(APP_ID, original.state()))
        val recovered = Fixture()
        recovered.identities.importRecoverableAfterApproval(backup, null)
        val restored = recovered.state()
        rejects { recovered.identities.obtainAfterApproval(APP_ID, restored) }
        recovered.identities.reconcileAfterApproval(APP_ID, restored, 1)
        val update = buildAndSign(recovered, 2)
        assertEquals(original.state().fingerprint, recovered.state().fingerprint)
        assertEquals(1, original.state().lastVersion)
        assertEquals(2, recovered.state().lastVersion)
        assertNotEquals(ProjectScope.sha256(first.readBytes()), ProjectScope.sha256(update.readBytes()))
        for ((apk, version) in listOf(first to 1, update to 2)) {
            val verification = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify()
            assertTrue("$version signature errors: ${verification.errors}", verification.isVerified)
            assertTrue(verification.isVerifiedUsingV2Scheme)
            assertTrue(verification.isVerifiedUsingV3Scheme)
            assertEquals(fingerprint(primary), ProjectScope.sha256(verification.signerCertificates.single().encoded))
            val info = TemplateApk.inspect(apk.readBytes())
            assertEquals(APP_ID, info.appId)
            assertEquals(version, info.versionCode)
        }
        assertNoPlaintextPrivateKeys(folder.root)
        System.getProperty("jarvys.factory.evidenceDir")?.let { path ->
            val evidence = File(path).apply { check(isDirectory || mkdirs()) }
            for ((apk, version) in listOf(first to 1, update to 2)) {
                val unsigned = File(apk.parentFile, "$version-unsigned.apk")
                unsigned.copyTo(File(evidence, "recovery-v$version-unsigned.apk"), overwrite = true)
                apk.copyTo(File(evidence, "recovery-v$version-signed.apk"), overwrite = true)
                val info = TemplateApk.inspect(apk.readBytes())
                File(evidence, "recovery-v$version.json").writeText(JSONObject()
                    .put("appId", info.appId).put("versionCode", info.versionCode)
                    .put("versionName", info.versionName).put("certificateSha256", fingerprint(primary))
                    .put("signedSha256", ProjectScope.sha256(apk.readBytes()))
                    .put("unsignedSha256", ProjectScope.sha256(unsigned.readBytes()))
                    .put("scope", FactorySigningScope.fromPlan(info.plan).toJson())
                    .put("restoredFromEncryptedBackup", version == 2)
                    .put("independentTemporaryStorage", true).put("ephemeralFixtureKeyOnly", true)
                    .put("rawPrivateKeyPersisted", false).put("deviceRestoreTested", false).toString())
            }
        }
    }

    private fun buildAndSign(f: Fixture, version: Int): File {
        val template = f.context.assets.open("apk_factory/template.apk").use { it.readBytes() }
        val icon = FactoryIcon.render("icon.json", """{"schemaVersion":1,"background":"#112233","shapes":[{"type":"circle","cx":96,"cy":96,"r":60,"fill":"#FFFFFF"}]}""".toByteArray())
        val config = JSONObject().put("schemaVersion", 1).put("appId", APP_ID)
            .put("name", "Restored identity fixture").put("entryPoint", "www/index.html")
            .put("capabilities", JSONArray())
        val bytes = TemplateApk.build(template, TemplateApk.Spec(APP_ID, "Restored identity fixture", version, "$version.0"), icon,
            mapOf("assets/factory-app.json" to config.toString().toByteArray(),
                "assets/www/index.html" to "<!doctype html><title>Recovery fixture $version</title>".toByteArray()))
        val input = File(f.root, "$version-unsigned.apk").apply { writeBytes(bytes) }
        val output = File(f.root, "$version-signed.apk")
        val identity = f.identities.obtainAfterApproval(APP_ID, f.state())
        val fingerprint = FactoryApkSigner.sign(input, output, identity.key, identity.certificate)
        f.identities.recordSigned(APP_ID, fingerprint, version, ProjectScope.sha256(output.readBytes()),
            FactorySigningScope.fromPlan(TemplateApk.inspect(output.readBytes()).plan))
        return output
    }

    private fun encryptedRoundTrip(material: FactoryIdentityBackup.Material): FactoryIdentityBackup.Material {
        val password = "RAM-only fixture passphrase".toCharArray()
        try {
            val container = FactoryIdentityBackup.encrypt(material, password)
            val backup = folder.newFile().apply { writeBytes(container) }
            return FactoryIdentityBackup.decrypt(backup.readBytes(), password)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun assertNoPlaintextPrivateKeys(root: File) {
        for (material in listOf(primary, alternate)) {
            val raw = material.key.encoded
            try {
                val base64 = Base64.getEncoder().encodeToString(raw)
                for (file in root.walkTopDown().filter { it.isFile }) {
                    val bytes = file.readBytes()
                    assertFalse("Private-key encoding in ${file.name}", bytes.toString(Charsets.ISO_8859_1).contains(base64))
                    assertFalse("Raw private key in ${file.name}", containsBytes(bytes, raw))
                    assertFalse("PEM private key in ${file.name}", bytes.toString(Charsets.ISO_8859_1).contains("BEGIN PRIVATE KEY"))
                }
            } finally {
                raw.fill(0)
            }
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { offset ->
            needle.indices.all { haystack[offset + it] == needle[it] }
        }
    }

    private fun provePossession(identity: FactorySigningIdentity.Identity) {
        val challenge = "RAM-only recovery challenge".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run {
            initSign(identity.key); update(challenge); sign()
        }
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(identity.certificate.publicKey); update(challenge); verify(signed)
        })
    }

    private fun race(actions: List<() -> Unit>): List<Boolean> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { action -> executor.submit(Callable {
                ready.countDown()
                check(start.await(30, TimeUnit.SECONDS))
                try { action(); true } catch (_: IllegalStateException) { false }
            }) }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun rejects(action: () -> Unit) = assertThrows(Exception::class.java) { action() }

    companion object {
        private const val APP_ID = "org.example.recoverable"
        private val HASH_A = "a".repeat(64)
        private val HASH_B = "b".repeat(64)
        private val primary by lazy { newMaterial() }
        private val alternate by lazy { newMaterial() }

        private fun newMaterial(): FactoryIdentityBackup.Material {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certificate = EphemeralFactoryCertificate.create(pair.public.encoded) { bytes ->
                Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(bytes); sign() }
            }
            return FactoryIdentityBackup.Material(APP_ID, pair.private, certificate, 0, null)
        }

        private fun fingerprint(material: FactoryIdentityBackup.Material) = ProjectScope.sha256(material.certificate.encoded)
        private fun scope() = FactorySigningScope.fromPlan(ManifestPlan(APP_ID, "Recovery fixture", 1, "1.0", 0x7f010001, 0x7f020001, listOf("storage")))
    }
}

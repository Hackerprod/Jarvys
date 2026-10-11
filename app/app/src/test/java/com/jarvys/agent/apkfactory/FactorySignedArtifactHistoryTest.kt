package com.jarvys.agent.apkfactory

import android.util.AtomicFile
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/** Synthetic private receipts only. No signing key, installed APK, document, network or credential access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactorySignedArtifactHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val appId = "org.example.history"
    @After fun releaseAuthorities() { FactoryArtifactAuthority.invalidate(appId) }
    private val certificate = "a".repeat(64)
    private val root get() = File(folder.root, "signed-artifacts")
    private val file get() = File(root, "$appId.json")
    private fun sha(version: Int) = ProjectScope.sha256("artifact-$version".toByteArray())
    private fun state(version: Int, capabilities: List<String> = listOf("documents"), cert: String = certificate): FactorySigningIdentity.State {
        val scope = JSONObject().put("capabilities", JSONArray(capabilities))
            .put("permissions", JSONArray(listOf("android.permission.INTERNET")))
            .put("exportedComponents", JSONArray(listOf("activity:org.example.history.Main")))
            .put("features", JSONArray(listOf("feature.camera:false")))
            .put("queries", JSONArray(listOf("android.intent.action.VIEW")))
            .put("allowedHosts", JSONArray(listOf("example.org")))
        val anchor = JSONObject().put("schemaVersion", 1).put("appId", appId).put("certificateSha256", cert)
            .put("versionCode", version).put("apkSha256", sha(version)).put("scope", scope)
        val baseline = FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256", sha(version)).put("lastSignedScope", anchor), appId, cert, version)!!
        return FactorySigningIdentity.State(true, cert, version, baseline, sha(version), ProjectScope.sha256("identity-$version".toByteArray()))
    }
    private fun verify(history: FactorySignedArtifactHistory, version: Int, current: FactorySigningIdentity.State,
        capabilities: Set<String> = setOf("documents")) = history.verify(appId, certificate, sha(version), version.toLong(), current, capabilities)
    private fun reject(block: () -> Unit) { assertThrows(Exception::class.java, block) }
    private fun edit(change: (JSONObject) -> Unit) { val saved = JSONObject(file.readText()); change(saved); file.writeText(saved.toString()) }

    @Test fun olderInstalledArtifactKeepsItsOwnCapabilitiesAndStableProofAfterNewSignatures() {
        val history = FactorySignedArtifactHistory(root)
        history.recordCurrent(appId, state(1))
        val before = verify(history, 1, state(1))
        history.recordCurrent(appId, state(2, listOf("clipboard")))
        assertEquals(before, verify(FactorySignedArtifactHistory(root), 1, state(2, listOf("clipboard"))))
        reject { verify(history, 1, state(2), setOf("clipboard")) }
        reject { verify(history, 2, state(2)) }
        verify(history, 2, state(2), setOf("clipboard"))
        history.recordCurrent(appId, state(3, listOf("network")))
        assertEquals(before, verify(history, 1, state(3)))
        reject { verify(history, 3, state(3), setOf("clipboard")) }
    }

    @Test fun identityCertificateAppVersionAndExactApkRemainBound() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(2))
        reject { history.verify(appId, "b".repeat(64), sha(2), 2, state(2), setOf("documents")) }
        reject { history.verify(appId, "b".repeat(64), sha(2), 2, state(2, cert = "b".repeat(64)), setOf("documents")) }
        reject { history.verify("org.example.other", certificate, sha(1), 1, state(2), setOf("documents")) }
        reject { history.verify(appId, certificate, sha(1), 2, state(2), setOf("documents")) }
        reject { history.verify(appId, certificate, sha(2), 1, state(2), setOf("documents")) }
        reject { verify(history, 2, state(2), emptySet()) }
        for (bad in listOf(state(2).copy(existing = false), state(2).copy(continuityKnown = false),
            state(2).copy(recordSha256 = null), state(2).copy(recordSha256 = "invalid"))) reject { verify(history, 2, bad) }
    }

    @Test fun supersededArtifactDoesNotLowerSigningFloorOrReplaceDifferentVersionEvidence() {
        val history = FactorySignedArtifactHistory(root)
        history.recordCurrent(appId, state(1)); history.recordCurrent(appId, state(2))
        val bytes = file.readBytes()
        reject { history.recordCurrent(appId, state(1)) }
        reject { history.recordCurrent(appId, state(2).copy(lastApkSha256 = sha(3))) }
        reject { history.recordCurrent(appId, state(2, listOf("clipboard"))) }
        reject { verify(history, 1, state(1)) }
        assertArrayEquals(bytes, file.readBytes())
        verify(history, 1, state(2))
    }

    @Test fun onlyAbsentHistoryUsesCompatibleLegacyLatestReceipt() {
        val history = FactorySignedArtifactHistory(root); val latest = state(2)
        assertEquals(latest.recordSha256, verify(history, 2, latest).record)
        reject { verify(history, 1, latest) }
        history.recordCurrent(appId, state(1))
        // A missing retained receipt cannot borrow the latest identity scope or digest.
        reject { verify(history, 2, latest) }
        verify(history, 1, latest)
    }

    @Test fun unknownLegacyScopeIsNotInventedDuringMigration() {
        val history = FactorySignedArtifactHistory(root)
        history.preserveLatest(appId, state(1).copy(lastScope = null))
        assertEquals(0, JSONObject(file.readText()).getJSONArray("artifacts").length())
        reject { verify(history, 1, state(1)) }
        history.recordCurrent(appId, state(2))
        reject { verify(history, 1, state(2)) }
        verify(history, 2, state(2))
    }

    @Test fun malformedCorruptAndOversizeHistoryNeverFallsBackToLatest() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val original = file.readBytes()
        for (body in listOf("{", "{}", String(ByteArray(FactorySignedArtifactHistory.MAX_HISTORY_BYTES + 1) { 'x'.code.toByte() }),
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"appId\":\"$appId\",\"artifacts\":[]}")) {
            file.writeText(body); reject { verify(history, 1, state(1)) }; reject { history.recordCurrent(appId, state(2)) }
            file.writeBytes(original)
        }
    }

    @Test fun capabilityAndManifestMutationInvalidatesReceiptIntegrity() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val original = file.readBytes()
        for (field in listOf("capabilities", "permissions", "exportedComponents", "features", "queries", "allowedHosts")) {
            edit { saved ->
                val entry = saved.getJSONArray("artifacts").getJSONObject(0)
                val record = JSONObject(entry.getString("record"))
                record.getJSONObject("scope").put(field, JSONArray(listOf("changed")))
                entry.put("record", record.toString())
            }
            reject { verify(history, 1, state(1)) }
            file.writeBytes(original)
        }
    }

    @Test fun duplicateEntriesAndInvalidBindingsAreRejectedEvenWithMatchingEntryDigests() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val original = file.readBytes()
        edit { it.getJSONArray("artifacts").put(it.getJSONArray("artifacts").getJSONObject(0)) }
        reject { verify(history, 1, state(1)) }; file.writeBytes(original)
        for ((field, value) in listOf("schemaVersion" to 2, "versionCode" to "1", "appId" to "org.example.other", "certificateSha256" to "invalid", "extra" to true)) {
            edit { saved ->
                val entry = saved.getJSONArray("artifacts").getJSONObject(0)
                val record = JSONObject(entry.getString("record")).put(field, value).toString()
                entry.put("record", record).put("sha256", ProjectScope.sha256(record.toByteArray()))
            }
            reject { verify(history, 1, state(1)) }; file.writeBytes(original)
        }
    }

    @Test fun interruptedFirstHistoryCannotMasqueradeAsAbsentLegacyHistory() {
        root.mkdirs(); File(file.path + ".new").writeText("interrupted")
        reject { verify(FactorySignedArtifactHistory(root), 1, state(1)) }
    }

    @Test fun retentionKeeps64RecentPlusInstalledArtifactWithoutUnboundedGrowth() {
        var installed: Long? = 1
        val history = FactorySignedArtifactHistory(root) { _, _ -> installed }
        for (version in 1..66) history.recordCurrent(appId, state(version))
        assertEquals(65, JSONObject(file.readText()).getJSONArray("artifacts").length())
        verify(history, 1, state(66)); verify(history, 3, state(66)); verify(history, 66, state(66))
        reject { verify(history, 2, state(66)) }
        installed = null
        history.recordCurrent(appId, state(67))
        assertEquals(64, JSONObject(file.readText()).getJSONArray("artifacts").length())
        reject { verify(history, 1, state(67)) }; verify(history, 67, state(67))
    }

    @Test fun retentionUncertaintyStopsCommitRatherThanEvictingPossibleInstalledArtifact() {
        val history = FactorySignedArtifactHistory(root) { _, _ -> error("PackageManager unavailable") }
        for (version in 1..64) history.recordCurrent(appId, state(version))
        val prior = file.readBytes()
        reject { history.recordCurrent(appId, state(65)) }
        assertArrayEquals(prior, file.readBytes())
        verify(history, 1, state(65)); reject { verify(history, 65, state(65)) }
    }

    @Test fun failedAtomicCommitPreservesPriorAuthorityAndDoesNotGrantNewArtifact() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val prior = file.readBytes()
        val failing = object : FactorySignedArtifactHistory(root) {
            override fun finishRecord(target: AtomicFile, stream: FileOutputStream) {
                target.failWrite(stream)
                error("Synthetic failed atomic commit")
            }
        }
        reject { failing.recordCurrent(appId, state(2)) }
        assertArrayEquals(prior, file.readBytes())
        verify(history, 1, state(2)); reject { verify(history, 2, state(2)) }
    }

    @Test fun firstSigningInitializesDurableHistoryBeforeIdentityAdvance() {
        val history = FactorySignedArtifactHistory(root)
        history.preserveLatest(appId, FactorySigningIdentity.State(false, null, 0))
        assertEquals(0, JSONObject(file.readText()).getJSONArray("artifacts").length())
        val failing = object : FactorySignedArtifactHistory(root) {
            override fun finishRecord(target: AtomicFile, stream: FileOutputStream) { target.failWrite(stream); error("Synthetic failure") }
        }
        reject { failing.recordCurrent(appId, state(1)) }
        reject { verify(history, 1, state(1)) }
        history.recordCurrent(appId, state(1)); verify(history, 1, state(1))
    }

    @Test fun ambiguousAtomicFinishDoesNotDeleteCommittedReceiptAndNeverReturnsSuccess() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val failing = object : FactorySignedArtifactHistory(root) {
            override fun finishRecord(target: AtomicFile, stream: FileOutputStream) { super.finishRecord(target, stream); error("Synthetic ambiguous finish") }
        }
        reject { failing.recordCurrent(appId, state(2)) }
        // The exact fully committed receipt remains useful; the caller still receives failure and cannot publish.
        verify(history, 1, state(2)); verify(history, 2, state(2))
    }
    @Test fun liveRetainedTokenSurvivesNewerSigningButLegacyLatestTokenDoesNot() {
        val history = FactorySignedArtifactHistory(root)
        val legacy = history.capture(verify(history, 1, state(1)), state(1), setOf("documents"))
        FactoryArtifactAuthority.beforeIdentityWrite(appId, preserveRetained = true)
        reject { legacy.requireCurrent() }
        history.recordCurrent(appId, state(1))
        val retained = history.capture(verify(history, 1, state(1)), state(1), setOf("documents"))
        FactoryArtifactAuthority.beforeIdentityWrite(appId, preserveRetained = true)
        history.recordCurrent(appId, state(2, listOf("clipboard")))
        FactoryArtifactAuthority.observeIdentity(appId, state(2))
        retained.requireCurrent()
        FactoryArtifactAuthority.beforeIdentityWrite(appId, preserveRetained = false)
        reject { retained.requireCurrent() }
    }

    @Test fun observedHistoryCorruptionPermanentlyRevokesTokenDespiteLaterRepair() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val proof = verify(history, 1, state(1))
        val token = history.capture(proof, state(1), setOf("documents"))
        val bytes = file.readBytes(); file.writeText("{")
        reject { verify(history, 1, state(1)) }; reject { token.requireCurrent() }
        file.writeBytes(bytes)
        history.capture(proof, state(1), setOf("documents")).use { it.requireCurrent() }
        reject { token.requireCurrent() }
    }

    @Test fun observedHistoryDeletionRevokesRetainedTokenEvenWhenLegacyLatestIsValid() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val token = history.capture(verify(history, 1, state(1)), state(1), setOf("documents"))
        assertTrue(file.delete())
        assertEquals(state(1).recordSha256, verify(history, 1, state(1)).record)
        reject { token.requireCurrent() }
    }

    @Test fun liveTokenCheckNeverWaitsForGlobalSigningLock() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val token = history.capture(verify(history, 1, state(1)), state(1), setOf("documents"))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            synchronized(FactorySigningIdentity.LOCK) {
                worker.submit { token.requireCurrent() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } finally { token.close(); worker.shutdownNow() }
    }

    @Test fun tokenRevocationListenerIsImmediateExactlyOnceAndRemovable() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val proof = verify(history, 1, state(1))
        val token = history.capture(proof, state(1), setOf("documents"))
        var calls = 0
        token.onRevoked { calls++ }.use {
            FactoryArtifactAuthority.invalidate(appId)
            assertEquals(1, calls)
            token.close(); assertEquals(1, calls)
        }
        token.onRevoked { calls++ }.close()
        assertEquals(2, calls)
        val next = history.capture(proof, state(1), setOf("documents"))
        next.onRevoked { calls++ }.close()
        next.close(); assertEquals(2, calls)
    }

    @Test fun failedHistoryWriteRevokesCachedTokensEvenWhenOldReceiptSurvives() {
        val history = FactorySignedArtifactHistory(root); history.recordCurrent(appId, state(1))
        val token = history.capture(verify(history, 1, state(1)), state(1), setOf("documents"))
        val failing = object : FactorySignedArtifactHistory(root) {
            override fun finishRecord(target: AtomicFile, stream: FileOutputStream) { target.failWrite(stream); error("Synthetic failure") }
        }
        reject { failing.recordCurrent(appId, state(2)) }
        reject { token.requireCurrent() }
        verify(history, 1, state(2))
    }

}

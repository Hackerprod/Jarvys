package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Host-only installer contract tests. No installer, network, real identity or persisted private key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryInstallCoordinatorTest {
    private lateinit var fixture: InstallTestFixture
    @Before fun before() { fixture = InstallTestFixture() }
    @After fun after() { fixture.close() }
    private fun fails(action: () -> Unit) { assertTrue("Expected closed authority boundary", runCatching(action).isFailure) }

    @Test fun preparationPersistsExactBindingAndOneShotLaunchWithoutCreatingSession() {
        val result = fixture.prepare()
        assertEquals("awaiting_user", result.getString("state"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.creates)
        assertEquals(fixture.binding.toString(), fixture.record().getJSONObject("binding").toString())
        assertFalse(fixture.coordinator.consumeLaunch("forged"))
        assertTrue(fixture.coordinator.consumeLaunch(result.getString("launch_token")))
        assertFalse(fixture.coordinator.consumeLaunch(result.getString("launch_token")))
        assertFalse(fixture.coordinator.status().has("nonce"))
        assertFalse(fixture.coordinator.status().has("launch_token"))
    }

    @Test fun playGateNeverCallsBackendOrCreatesJournal() {
        fixture.close()
        fixture = InstallTestFixture(fullDistribution = false)
        fails { fixture.prepare() }
        assertEquals(0, fixture.backend.permissionChecks)
        assertEquals(0, fixture.backend.creates)
        assertFalse(fixture.journal.exists())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun deniedSourceNeverStartsSettingsOrCreatesSession() {
        fixture.backend.allowed = false
        fails { fixture.prepare() }
        assertEquals(0, fixture.backend.creates)
        assertFalse(fixture.journal.exists())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun automatedEntryCannotAcquireNativeApproval() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { fails { fixture.prepare() } })
        assertFalse(fixture.journal.exists())
        assertEquals(0, fixture.backend.creates)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun statusAndCancellationRejectDifferentProjectReceiptOrArtifact() {
        fixture.prepare()
        for (key in listOf("project_id", "scope_version", "receipt_sha256", "apk_sha256", "certificate_sha256", "input_path")) {
            val other = JSONObject(fixture.binding.toString()).put(key, "different")
            fails { fixture.coordinator.status(other) }
            fails { fixture.coordinator.cancel(other) }
        }
        fails { FactoryInstallCoordinator.requireBinding(fixture.binding, JSONObject(fixture.binding.toString()).put("extra", true)) }
        assertEquals("awaiting_user", fixture.state())
    }

    @Test fun cancellationRevokesApprovalButProtectionRequiresExplicitNativeClose() {
        fixture.prepare()
        fixture.token.cancel()
        assertEquals("revoked", fixture.state())
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction { error("Not foreground") } }
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(fixture.artifact.exists())
    }

    @Test fun scopeRevokedBeforeApprovalNeverCreatesSession() {
        var authorized = true
        fixture.prepare { check(authorized) }
        authorized = false
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun changedArtifactOrCertificateFailsBeforeSessionCreation() {
        fixture.signed(); fixture.prepare()
        fixture.artifact.appendBytes(byteArrayOf(1))
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun changedCertificateFailsBeforeCreatingSession() {
        fixture.signed()
        fixture.binding.put("certificate_sha256", "0".repeat(64))
        fixture.prepare()
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun authorityIsRecheckedAfterStagingAndSessionIsAbandonedOnRevocation() {
        fixture.signed(); var checks = 0
        fixture.prepare { check(++checks < 3) }
        fails { fixture.coordinator.install {} }
        assertEquals(3, checks)
        assertEquals(1, fixture.backend.creates)
        assertEquals(1, fixture.backend.writes)
        assertEquals(0, fixture.backend.commits)
        assertEquals(1, fixture.backend.abandons)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun artifactTamperedDuringStagingIsNeverCommitted() {
        fixture.signed(); fixture.prepare()
        fixture.backend.afterWrite = { fixture.artifact.appendBytes(byteArrayOf(9)) }
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.commits)
        assertEquals(1, fixture.backend.abandons)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun commitIsDurableBeforeDispatchAndBinderFailureIsUnknownNeverRetried() {
        fixture.signed(); fixture.prepare()
        fixture.backend.onCommit = { assertEquals("committing", fixture.state()); error("Synthetic Binder uncertainty") }
        fails { fixture.coordinator.install {} }
        assertEquals("outcome_unknown", fixture.state())
        fails { fixture.coordinator.install {} }
        assertEquals(1, fixture.backend.commits)
        assertEquals(0, fixture.backend.abandons)
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun forgedNonceSessionAndUnknownCallbacksCannotInventSuccess() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback("forged", 17, PackageInstaller.STATUS_SUCCESS, null)
        fixture.coordinator.callback(fixture.nonce(), 18, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("committing", fixture.state())
        fixture.coordinator.callback(fixture.nonce(), 17, 777, null)
        assertEquals("outcome_unknown", fixture.state())
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("succeeded", fixture.state())
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_FAILURE, null)
        assertEquals("succeeded", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun systemDenialAndBlockedStatusesRemainExplicitAndProtected() {
        for ((status, expected) in listOf(PackageInstaller.STATUS_FAILURE_ABORTED to "aborted_by_system", PackageInstaller.STATUS_FAILURE_BLOCKED to "blocked_by_system", PackageInstaller.STATUS_FAILURE_INVALID to "failed_by_system")) {
            fixture.seed("committing", 17)
            fixture.coordinator.callback(fixture.nonce(), 17, status, null)
            assertEquals(expected, fixture.state())
            assertEquals(status, fixture.coordinator.status().getInt("system_status"))
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
    }

    @Test fun pendingSystemUiRequiresFreshHumanCheckAndIntentIsOneShot() {
        fixture.seed("committing", 17)
        val confirmation = Intent("synthetic.confirm")
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)
        assertEquals("pending_system", fixture.state())
        fails { fixture.coordinator.takeSystemIntent { error("Not a human") } }
        assertSame(confirmation, fixture.coordinator.takeSystemIntent {})
        assertEquals("system_ui_open", fixture.state())
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun restartNeverReplaysApprovalCommitOrUnavailableSystemIntent() {
        for ((prior, expected) in listOf("awaiting_user" to "interrupted_before_commit", "staging" to "interrupted_before_commit", "committing" to "outcome_unknown", "pending_system" to "outcome_unknown", "system_ui_open" to "outcome_unknown")) {
            fixture.seed(prior, 17)
            fixture.restart()
            assertEquals(expected, fixture.state())
            fails { fixture.coordinator.install {} }
            fails { fixture.coordinator.takeSystemIntent {} }
            assertFalse(fixture.coordinator.consumeLaunch("forged"))
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun cancellationPreservesAuthenticatedSuccessEvenIfSessionStillExists() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        fixture.backend.exists = true
        assertEquals("succeeded", fixture.coordinator.cancel().getString("state"))
        assertEquals(PackageInstaller.STATUS_SUCCESS, fixture.coordinator.status().getInt("system_status"))
        assertEquals(0, fixture.backend.abandons)
        fails { fixture.coordinator.closeInteraction {} }
        fixture.backend.exists = false
        fixture.coordinator.closeInteraction {}
        assertEquals("succeeded", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun absentSessionCancellationIsUnknownAndCannotClaimInstallSucceeded() {
        fixture.seed("outcome_unknown", 17)
        assertEquals("cancelled_outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.closeInteraction {}
        assertEquals("cancelled_outcome_unknown", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun sessionStillPresentOrAbandonFailureCannotReleaseProtection() {
        fixture.seed("committing", 17); fixture.backend.exists = true
        fixture.backend.keepOnAbandon = true
        assertEquals("cancel_requested", fixture.coordinator.cancel().getString("state"))
        fails { fixture.coordinator.closeInteraction {} }
        fixture.backend.failAbandon = true
        assertEquals("outcome_unknown", fixture.coordinator.cancel().getString("state"))
        fails { fixture.coordinator.closeInteraction {} }
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun finalScopeCheckRunsAfterDurableCommitRecordButBeforeBackendCommit() {
        fixture.signed()
        fixture.coordinator.prepare(fixture.binding, fixture.bytes, {}, fixture.token, finalCheck = {
            assertEquals("committing", fixture.state())
            error("Synthetic final scope revocation")
        })
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.commits)
        assertEquals(1, fixture.backend.abandons)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun foregroundLostDuringFinalValidationCannotDispatchCommit() {
        fixture.signed()
        var foreground = true
        fixture.coordinator.prepare(fixture.binding, fixture.bytes, {}, fixture.token, finalCheck = {
            foreground = false
        })
        fails { fixture.coordinator.install { check(foreground) } }
        assertEquals(0, fixture.backend.commits)
        assertEquals(1, fixture.backend.abandons)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun sourcePermissionRevokedWhileStagingNeverCommits() {
        fixture.signed(); fixture.prepare()
        fixture.backend.afterWrite = { fixture.backend.allowed = false }
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.commits)
        assertEquals(1, fixture.backend.abandons)
        assertEquals("failed_before_commit", fixture.state())
    }

    @Test fun cancellationDoesNotWaitForStagingAndCannotCloseUntilWorkerStops() {
        fixture.signed(); fixture.prepare()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        fixture.backend.afterWrite = {
            writing.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val work = executor.submit<Boolean> { runCatching { fixture.coordinator.install {} }.isFailure }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            fixture.token.cancel()
            assertEquals("revoked", fixture.state())
            fails { fixture.coordinator.closeInteraction {} }
            assertTrue(MemoryUiAutomationGuard.isProtected())
            release.countDown()
            assertTrue(work.get(10, TimeUnit.SECONDS))
            assertEquals(0, fixture.backend.commits)
            assertEquals(1, fixture.backend.abandons)
            fixture.coordinator.closeInteraction {}
            assertFalse(MemoryUiAutomationGuard.isProtected())
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun delayedPendingCallbackCannotReopenCancelledOrAlreadyOpenedSystemUi() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("first"))
        fixture.coordinator.takeSystemIntent {}
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("duplicate"))
        assertEquals("system_ui_open", fixture.state())
        fails { fixture.coordinator.takeSystemIntent {} }
        fixture.backend.exists = true; fixture.backend.keepOnAbandon = true
        fixture.coordinator.cancel()
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("late"))
        assertEquals("cancel_requested", fixture.state())
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun backupOnlyAtomicJournalRestoresBoundaryWithoutReplaying() {
        fixture.seed("awaiting_user")
        assertTrue(fixture.journal.renameTo(File(fixture.root, "session.json.bak")))
        fixture.restart()
        assertEquals("interrupted_before_commit", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun corruptJournalRestoresFailClosedBoundary() {
        fixture.journal.parentFile!!.mkdirs(); fixture.journal.writeText("{broken")
        fixture.coordinator.restore()
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction {} }
        fails { fixture.prepare() }
        assertEquals(0, fixture.backend.creates)
    }
}

/** Shared synthetic fixture. Reflection replaces only the process singleton and releases test-owned leases. */
internal class InstallTestFixture(private val fullDistribution: Boolean = true) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val root = File(context.noBackupFilesDir, "factory-install")
    val journal = File(root, "session.json")
    val artifact = File(root, "approved.apk")
    val backend = FakeInstallBackend()
    val token = CancellationToken.cancellable()
    var bytes = "synthetic unsigned approval-only fixture".toByteArray()
    val binding = JSONObject().put("app_id", "org.example.installfixture").put("app_name", "Synthetic fixture")
        .put("version_code", 1).put("version_name", "1.0").put("project_id", "synthetic-project")
        .put("scope_version", 3).put("receipt_sha256", "b".repeat(64)).put("input_path", "dist/test.apk")
        .put("apk_sha256", ProjectScope.sha256(bytes)).put("certificate_sha256", "a".repeat(64))
    var coordinator: FactoryInstallCoordinator
    init {
        singleton()?.let { release(it) }
        root.deleteRecursively()
        coordinator = FactoryInstallCoordinator(context, backend, fullDistribution)
        setSingleton(coordinator)
    }
    fun prepare(validate: () -> Unit = {}) = coordinator.prepare(binding, bytes, validate, token)
    fun record() = JSONObject(journal.readText())
    fun nonce() = record().getString("nonce")
    fun state() = coordinator.status().getString("state")
    fun seed(state: String, sessionId: Int = -1) {
        root.mkdirs()
        journal.writeText(JSONObject().put("schemaVersion", 1).put("nonce", "12345678-1234-1234-1234-123456789abc")
            .put("binding", binding).put("sessionId", sessionId).put("state", state)
            .put("interactionOpen", true).put("systemStatus", JSONObject.NULL).toString())
        // Restore protection without changing the deliberately seeded state under test.
        coordinator.restore()
        val restored = record().put("state", state)
        journal.writeText(restored.toString())
    }
    fun restart() { release(coordinator); coordinator = FactoryInstallCoordinator(context, backend, fullDistribution); setSingleton(coordinator); coordinator.restore() }
    fun signed() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = EphemeralFactoryCertificate.create(pair.public.encoded) { data ->
            Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(data); sign() }
        }
        val input = File.createTempFile("fixture-unsigned", ".apk", context.cacheDir)
        val output = File.createTempFile("fixture-signed", ".apk", context.cacheDir)
        try {
            input.writeBytes(context.assets.open("apk_factory/template.apk").use { it.readBytes() })
            binding.put("certificate_sha256", FactoryApkSigner.sign(input, output, pair.private, certificate))
            bytes = output.readBytes(); binding.put("apk_sha256", ProjectScope.sha256(bytes))
        } finally { input.delete(); output.delete() }
    }
    fun close() { token.cancel(); release(coordinator); setSingleton(null); root.deleteRecursively() }
    private fun release(value: FactoryInstallCoordinator) {
        val field = FactoryInstallCoordinator::class.java.getDeclaredField("protection").apply { isAccessible = true }
        (field.get(value) as? MemoryUiAutomationGuard.Lease)?.close(); field.set(value, null)
    }
    private fun singleton() = FactoryInstallCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryInstallCoordinator
    private fun setSingleton(value: FactoryInstallCoordinator?) { FactoryInstallCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, value) }
}

internal class FakeInstallBackend : FactoryInstallCoordinator.Backend {
    var allowed = true
    var exists = false
    var keepOnAbandon = false
    var failAbandon = false
    var permissionChecks = 0
    var creates = 0
    var writes = 0
    var commits = 0
    var abandons = 0
    var afterWrite: () -> Unit = {}
    var onCommit: () -> Unit = {}
    override fun allowed(): Boolean { permissionChecks++; return allowed }
    override fun create(appId: String, size: Long): Int { creates++; exists = true; return 17 }
    override fun write(sessionId: Int, apk: File) { writes++; afterWrite() }
    override fun commit(sessionId: Int, nonce: String) { commits++; onCommit() }
    override fun abandon(sessionId: Int) { abandons++; check(!failAbandon); if (!keepOnAbandon) exists = false }
    override fun exists(sessionId: Int) = exists
}

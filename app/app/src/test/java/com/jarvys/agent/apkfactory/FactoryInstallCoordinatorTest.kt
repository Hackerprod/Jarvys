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
        assertEquals(0, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
        assertFalse(fixture.journal.exists())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun deniedSourceCreatesProtectedNativeReviewWithoutInstallerSession() {
        fixture.backend.allowed = false
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val result = fixture.prepare()
        assertEquals("permission_required", result.getString("state"))
        assertEquals(-1, result.getInt("session_id"))
        assertTrue(result.has("launch_token"))
        assertTrue(fixture.journal.exists())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        var dispatched = false
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { dispatched = true })
        assertFalse(dispatched)
        fails { fixture.coordinator.install {} }
        fails { fixture.coordinator.takeSystemIntent {} }
        fails { fixture.coordinator.closeInteraction { error("Not foreground") } }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun permissionIsQueriedOnlyAfterProtectionAndDurableReviewExist() {
        fixture.backend.beforeAvailability = {
            assertTrue(MemoryUiAutomationGuard.isProtected())
            assertTrue(fixture.journal.exists())
            assertTrue(fixture.record().getBoolean("interactionOpen"))
            assertEquals(-1, fixture.record().getInt("sessionId"))
        }
        fixture.backend.allowed = false
        assertEquals("permission_required", fixture.prepare().getString("state"))
        assertEquals(1, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun failedPermissionQueryReturnsDurableProtectedUnavailableReview() {
        fixture.backend.availabilityFailure = true
        assertEquals("permission_unavailable", fixture.prepare().getString("state"))
        assertEquals("permission_unavailable", fixture.record().getString("state"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun unsupportedInstallerReturnsProtectedUnavailableReviewWithoutApproval() {
        fixture.backend.availabilityOverride = FactoryInstallCoordinator.Availability.UNSUPPORTED
        assertEquals("installation_unavailable", fixture.prepare().getString("state"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun permissionBoundarySurvivesRestartForAllUnavailableStates() {
        for (state in listOf("permission_required", "permission_unavailable", "installation_unavailable")) {
            fixture.seed(state)
            fixture.restart()
            assertEquals(state, fixture.state())
            assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
            assertTrue(MemoryUiAutomationGuard.isProtected())
            fails { fixture.coordinator.install {} }
            fails { fixture.prepare() }
            fixture.coordinator.closeInteraction {}
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun manualPermissionGrantDoesNotResumeOldRequestAndRequiresCloseThenFreshRequest() {
        fixture.backend.allowed = false
        fixture.prepare()
        val oldNonce = fixture.nonce()
        fixture.backend.allowed = true
        assertEquals("permission_required", fixture.state())
        fails { fixture.coordinator.install {} }
        fails { fixture.prepare() }
        assertEquals(1, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
        fixture.coordinator.closeInteraction {}
        assertEquals("awaiting_user", fixture.prepare().getString("state"))
        assertNotEquals(oldNonce, fixture.nonce())
        assertEquals(2, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun automatedEntryCannotAcquireNativeApproval() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { fails { fixture.prepare() } })
        assertEquals(0, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.permissionChecks)
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

    @Test fun lateAuthenticatedSuccessReconcilesCancelledUnknownWithoutLiftingProtection() {
        fixture.seed("outcome_unknown", 17)
        fixture.coordinator.cancel()
        assertEquals("cancelled_outcome_unknown", fixture.state())
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("succeeded", fixture.state())
        assertEquals(PackageInstaller.STATUS_SUCCESS, fixture.coordinator.status().getInt("system_status"))
        assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun lateKnownFailuresReconcileCancelledUnknownToSpecificTerminalOutcomes() {
        for ((status, expected) in listOf(
            PackageInstaller.STATUS_FAILURE_ABORTED to "aborted_by_system",
            PackageInstaller.STATUS_FAILURE_BLOCKED to "blocked_by_system",
            PackageInstaller.STATUS_FAILURE to "failed_by_system",
            PackageInstaller.STATUS_FAILURE_CONFLICT to "failed_by_system",
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to "failed_by_system",
            PackageInstaller.STATUS_FAILURE_INVALID to "failed_by_system",
            PackageInstaller.STATUS_FAILURE_STORAGE to "failed_by_system",
            PackageInstaller.STATUS_FAILURE_TIMEOUT to "failed_by_system"
        )) {
            fixture.seed("outcome_unknown", 17)
            fixture.coordinator.cancel()
            fixture.coordinator.callback(fixture.nonce(), 17, status, null)
            assertEquals(expected, fixture.state())
            assertEquals(status, fixture.coordinator.status().getInt("system_status"))
            assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun cancelledUnknownIgnoresLatePendingUnknownAndUnauthenticatedCallbacks() {
        fixture.seed("outcome_unknown", 17)
        fixture.coordinator.cancel()
        val unchanged = fixture.record().toString()
        for ((nonce, session, status) in listOf(
            Triple(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION),
            Triple(fixture.nonce(), 17, 777),
            Triple("forged", 17, PackageInstaller.STATUS_SUCCESS),
            Triple(fixture.nonce(), 18, PackageInstaller.STATUS_SUCCESS),
            Triple(fixture.nonce(), -1, PackageInstaller.STATUS_FAILURE_ABORTED)
        )) {
            fixture.coordinator.callback(nonce, session, status, Intent("synthetic.late.confirmation"))
            assertEquals(unchanged, fixture.record().toString())
            assertEquals("cancelled_outcome_unknown", fixture.state())
            fails { fixture.coordinator.takeSystemIntent {} }
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun lateTerminalReconciliationNeverReopensHumanClosedInteraction() {
        for ((status, expected) in listOf(
            PackageInstaller.STATUS_SUCCESS to "succeeded",
            PackageInstaller.STATUS_FAILURE_ABORTED to "aborted_by_system"
        )) {
            fixture.seed("outcome_unknown", 17)
            fixture.coordinator.cancel()
            fixture.coordinator.closeInteraction {}
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
            val automationEpoch = MemoryUiAutomationGuard.captureAutomationEpoch()
            fixture.coordinator.callback(fixture.nonce(), 17, status, null)
            assertEquals(expected, fixture.state())
            assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertTrue(MemoryUiAutomationGuard.isAutomationEpochValid(automationEpoch))
            fixture.restart()
            assertEquals(expected, fixture.state())
            assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
    }

    @Test fun newOperationRejectsOldCallbackEvenWhenSyntheticSessionIdIsReused() {
        fixture.seed("outcome_unknown", 17)
        val oldNonce = fixture.nonce()
        fixture.coordinator.cancel()
        fixture.coordinator.closeInteraction {}
        fixture.signed()
        fixture.prepare()
        fixture.coordinator.install {}
        val newNonce = fixture.nonce()
        assertNotEquals(oldNonce, newNonce)
        assertEquals(17, fixture.coordinator.status().getInt("session_id"))
        assertEquals("committing", fixture.state())
        for (status in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_PENDING_USER_ACTION)) {
            fixture.coordinator.callback(oldNonce, 17, status, Intent("synthetic.old.confirmation"))
            assertEquals("committing", fixture.state())
            assertEquals(newNonce, fixture.nonce())
        }
        assertEquals(1, fixture.backend.commits)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.callback(newNonce, 17, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("succeeded", fixture.state())
    }

    @Test fun missingSessionAfterUnknownCanCloseWithoutAbandon() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = false
        fixture.backend.failAbandon = true
        assertEquals("cancelled_outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertEquals(0, fixture.backend.abandons)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.closeInteraction {}
        assertEquals("cancelled_outcome_unknown", fixture.state())
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun sessionDisappearingBetweenExistenceCheckAndAbandonRemainsUnknownAndClosable() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = true
        fixture.backend.beforeAbandon = {
            fixture.backend.exists = false
            fixture.backend.failAbandon = true
        }
        assertEquals("cancelled_outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertEquals(1, fixture.backend.abandons)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.closeInteraction {}
        assertEquals("cancelled_outcome_unknown", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun failedAbandonAndFailedExistenceQueryKeepOutcomeUnknownAndProtected() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = true
        fixture.backend.beforeAbandon = {
            fixture.backend.failAbandon = true
            fixture.backend.existsFailure = true
        }
        assertEquals("outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertEquals(1, fixture.backend.abandons)
        assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction {} }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun authenticatedTimeoutFromCommittingRecordsTerminalFailure() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_FAILURE_TIMEOUT, null)
        assertEquals("failed_by_system", fixture.state())
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, fixture.coordinator.status().getInt("system_status"))
        assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
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

    private fun damageInstallJournal() {
        fixture.journal.parentFile!!.mkdirs()
        fixture.journal.writeText("{broken")
        fixture.coordinator.restore()
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun damagedRecoveryAbandonsOnlyOwnedSessionsAndRequiresSeparateHumanClose() {
        damageInstallJournal()
        fixture.backend.ownedIds = listOf(17, 18)
        fails { fixture.coordinator.recoverDamagedJournal { error("Not foreground") } }
        assertEquals(0, fixture.backend.abandons)
        fixture.coordinator.recoverDamagedJournal {}
        assertEquals(listOf(17, 18), fixture.backend.abandonedIds)
        assertEquals("recovery_outcome_unknown", fixture.state())
        assertEquals(-1, fixture.coordinator.status().getInt("session_id"))
        assertEquals(0, fixture.record().getJSONObject("binding").length())
        assertTrue(fixture.coordinator.status().getBoolean("automation_protected"))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        assertFalse(fixture.coordinator.consumeLaunch("forged"))
        fails { fixture.coordinator.closeInteraction { error("Not foreground") } }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals("recovery_outcome_unknown", fixture.state())
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun damagedRecoverySessionQueryFailureCannotLiftProtectionOrInventOutcome() {
        damageInstallJournal()
        fixture.backend.ownedQueryFailure = true
        fails { fixture.coordinator.recoverDamagedJournal {} }
        assertEquals(0, fixture.backend.abandons)
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction {} }
    }

    @Test fun damagedRecoveryRejectsOversizedDuplicateAndNegativeSessionListsBeforeAbandon() {
        damageInstallJournal()
        for (ids in listOf((0..16).toList(), listOf(17, 17), listOf(-1, 17))) {
            fixture.backend.ownedIds = ids
            fails { fixture.coordinator.recoverDamagedJournal {} }
            assertEquals(0, fixture.backend.abandons)
            assertEquals("journal_unavailable", fixture.state())
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
    }

    @Test fun damagedRecoveryStopsBeforeNextAbandonWhenForegroundIsLost() {
        damageInstallJournal()
        fixture.backend.ownedIds = listOf(17, 18)
        var foreground = true
        fixture.backend.beforeAbandon = { foreground = false }
        fails { fixture.coordinator.recoverDamagedJournal { check(foreground) } }
        assertEquals(listOf(17), fixture.backend.abandonedIds)
        assertEquals(listOf(18), fixture.backend.ownedIds)
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction {} }
    }

    @Test fun damagedRecoveryRetainsBoundaryIfFinalQueryFindsNewSession() {
        damageInstallJournal()
        fixture.backend.ownedIds = listOf(17)
        fixture.backend.beforeOwnedQuery = {
            if (fixture.backend.ownedQueries == 2) fixture.backend.ownedIds = listOf(99)
        }
        fails { fixture.coordinator.recoverDamagedJournal {} }
        assertEquals(listOf(17), fixture.backend.abandonedIds)
        assertEquals(listOf(99), fixture.backend.ownedIds)
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun damagedRecoveryCannotOverlapAnotherRecoveryOrOrdinaryCancellation() {
        damageInstallJournal()
        val querying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        fixture.backend.beforeOwnedQuery = {
            if (fixture.backend.ownedQueries == 1) {
                querying.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val recovery = executor.submit<Boolean> { runCatching { fixture.coordinator.recoverDamagedJournal {} }.isSuccess }
        try {
            assertTrue(querying.await(10, TimeUnit.SECONDS))
            fails { fixture.coordinator.recoverDamagedJournal {} }
            fails { fixture.coordinator.cancel() }
            fails { fixture.coordinator.closeInteraction {} }
            assertTrue(MemoryUiAutomationGuard.isProtected())
            release.countDown()
            assertTrue(recovery.get(10, TimeUnit.SECONDS))
            assertEquals("recovery_outcome_unknown", fixture.state())
            assertEquals(0, fixture.backend.abandons)
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun damagedRecoveryCannotRaceActiveStaging() {
        fixture.signed(); fixture.prepare()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        fixture.backend.afterWrite = { writing.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val work = executor.submit<Boolean> { runCatching { fixture.coordinator.install {} }.isFailure }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            fails { fixture.coordinator.recoverDamagedJournal {} }
            assertEquals(0, fixture.backend.ownedQueries)
            assertEquals(0, fixture.backend.abandons)
            fixture.token.cancel()
            release.countDown()
            assertTrue(work.get(10, TimeUnit.SECONDS))
            assertEquals(0, fixture.backend.commits)
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun damagedRecoveryJournalWriteFailureCannotReleaseProtection() {
        damageInstallJournal()
        fixture.backend.beforeOwnedQuery = {
            if (fixture.backend.ownedQueries == 2) {
                check(fixture.root.deleteRecursively())
                fixture.root.writeText("synthetic path collision preventing durable recovery")
            }
        }
        fails { fixture.coordinator.recoverDamagedJournal {} }
        assertEquals("journal_unavailable", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.closeInteraction {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun recoveredInteractionCloseRequeriesOwnedSessionsAndFailsClosed() {
        damageInstallJournal()
        fixture.coordinator.recoverDamagedJournal {}
        fixture.backend.ownedIds = listOf(88)
        fails { fixture.coordinator.closeInteraction {} }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.abandons)
        fixture.backend.ownedIds = emptyList()
        fixture.backend.ownedQueryFailure = true
        fails { fixture.coordinator.closeInteraction {} }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fixture.backend.ownedQueryFailure = false
        fixture.coordinator.closeInteraction {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals("recovery_outcome_unknown", fixture.state())
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
    var availabilityChecks = 0
    var availabilityFailure = false
    var availabilityOverride: FactoryInstallCoordinator.Availability? = null
    var beforeAvailability: () -> Unit = {}
    var exists = false
    var keepOnAbandon = false
    var failAbandon = false
    var existsFailure = false
    var beforeAbandon: () -> Unit = {}
    var ownedIds: List<Int> = emptyList()
    var ownedQueries = 0
    var ownedQueryFailure = false
    var beforeOwnedQuery: () -> Unit = {}
    val abandonedIds = mutableListOf<Int>()
    var permissionChecks = 0
    var creates = 0
    var writes = 0
    var commits = 0
    var abandons = 0
    var afterWrite: () -> Unit = {}
    var onCommit: () -> Unit = {}
    override fun availability(): FactoryInstallCoordinator.Availability {
        availabilityChecks++
        beforeAvailability()
        check(!availabilityFailure)
        return availabilityOverride ?: if (allowed()) FactoryInstallCoordinator.Availability.READY
            else FactoryInstallCoordinator.Availability.SOURCE_PERMISSION_REQUIRED
    }
    override fun allowed(): Boolean { permissionChecks++; return allowed }
    override fun create(appId: String, size: Long): Int { creates++; exists = true; return 17 }
    override fun write(sessionId: Int, apk: File) { writes++; afterWrite() }
    override fun commit(sessionId: Int, nonce: String) { commits++; onCommit() }
    override fun abandon(sessionId: Int) {
        abandons++; abandonedIds.add(sessionId); beforeAbandon(); check(!failAbandon)
        if (!keepOnAbandon) { exists = false; ownedIds = ownedIds.filter { it != sessionId } }
    }
    override fun ownedSessionIds(): List<Int> {
        ownedQueries++; beforeOwnedQuery(); check(!ownedQueryFailure); return ownedIds.toList()
    }
    override fun exists(sessionId: Int): Boolean { check(!existsFailure); return exists }
}

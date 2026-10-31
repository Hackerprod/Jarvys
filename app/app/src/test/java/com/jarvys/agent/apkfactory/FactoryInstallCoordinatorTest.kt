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
@Config(sdk = [34], application = android.app.Application::class)
class FactoryInstallCoordinatorTest {
    private lateinit var fixture: InstallTestFixture
    @Before fun before() { FactoryStartupTestIsolation.releaseCompletedSharingStartup(); fixture = InstallTestFixture() }
    @After fun after() { fixture.close() }
    private fun fails(action: () -> Unit) { assertTrue("Expected closed authority boundary", runCatching(action).isFailure) }

    @Test fun prepareAutomaticallyRetiresPriorOpenAndUnknownInstallations() {
        for (prior in listOf("awaiting_user", "outcome_unknown")) {
            fixture.close(); fixture = InstallTestFixture()
            fixture.seed(prior, if (prior == "outcome_unknown") 17 else -1)
            fixture.backend.exists = prior == "outcome_unknown"
            val oldNonce = fixture.nonce()
            val result = fixture.prepare()
            assertEquals("awaiting_user", result.getString("state"))
            assertNotEquals(oldNonce, fixture.nonce())
            assertEquals("awaiting_user", fixture.record().getString("state"))
            assertTrue(fixture.record().getBoolean("interactionOpen"))
            assertEquals(-1, fixture.record().getInt("sessionId"))
            if (prior == "outcome_unknown") assertEquals(1, fixture.backend.abandons)
        }
    }

    @Test fun prepareDoesNotProceedWhilePreviousPackageInstallerSessionCannotBeAbandoned() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = true
        fixture.backend.failAbandon = true

        fails { fixture.prepare() }

        assertEquals(0, fixture.backend.availabilityChecks)
        assertEquals(1, fixture.backend.abandons)
        assertTrue(fixture.record().getBoolean("interactionOpen"))
        assertEquals("outcome_unknown", fixture.record().getString("state"))
        assertTrue(fixture.backend.exists)
    }

    @Test fun prepareAbandonsOwnedInstallerSessionsWhenPreviousJournalHasNoSessionId() {
        fixture.seed("staging", -1)
        fixture.backend.exists = true
        fixture.backend.ownedIds = listOf(17)

        val result = fixture.prepare()

        assertEquals("awaiting_user", result.getString("state"))
        assertEquals(listOf(17), fixture.backend.abandonedIds)
        assertFalse(fixture.backend.exists)
        assertEquals("awaiting_user", fixture.state())
    }

    @Test fun preparationPersistsExactBindingAndOneShotLaunchWithoutCreatingSession() {
        val result = fixture.prepare()
        assertEquals("awaiting_user", result.getString("state"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.creates)
        assertEquals(fixture.binding.toString(), fixture.record().getJSONObject("binding").toString())
        assertFalse(fixture.coordinator.consumeLaunch("forged"))
        assertTrue(fixture.coordinator.consumeLaunch(result.getString("launch_token")))
        assertFalse(fixture.coordinator.consumeLaunch(result.getString("launch_token")))
        assertFalse(fixture.coordinator.status().has("nonce"))
        assertFalse(fixture.coordinator.status().has("launch_token"))
    }


    @Test fun installationStagesAndCommitsWithoutJarvysApproval() {
        fixture.close(); fixture = InstallTestFixture()
        fixture.signed()
        val result = fixture.prepare()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.creates)
        fixture.coordinator.install {}
        assertEquals("committing", fixture.state())
        assertEquals(1, fixture.backend.creates)
        assertEquals(1, fixture.backend.writes)
        assertEquals(1, fixture.backend.commits)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun stopAfterCommitPreventsOpeningSystemConfirmation() {
        fixture.close(); fixture = InstallTestFixture()
        fixture.signed(); fixture.prepare(); fixture.coordinator.install {}
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("synthetic.confirm"))
        fixture.token.cancel()
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(1, fixture.backend.commits)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun historicalPendingCallbackCannotCreateNewSystemUiAuthority() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("synthetic.confirm"))
        assertEquals("pending_system", fixture.state())
        fails { fixture.coordinator.takeSystemIntent {} }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun actualSystemConfirmationRefusesAutomatedInFlightEntryWithoutConsumingIntent() {
        fixture.close(); fixture = InstallTestFixture()
        fixture.signed(); fixture.prepare(); fixture.coordinator.install {}
        val confirmation = Intent("synthetic.confirm")
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { fails { fixture.coordinator.takeSystemIntent {} } })
        assertEquals("pending_system", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertSame(confirmation, fixture.coordinator.takeSystemIntent {})
        assertTrue(MemoryUiAutomationGuard.isProtected())
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

    @Test fun missingSourcePermissionRecordsUnavailableWithoutKeepingGlobalGuard() {
        fixture.backend.allowed = false
        val result = fixture.prepare()
        assertEquals("permission_required", result.getString("state"))
        assertEquals(-1, result.getInt("session_id"))
        assertTrue(result.has("launch_token"))
        assertTrue(fixture.journal.exists())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        var dispatched = false
        assertTrue(MemoryUiAutomationGuard.runAutomated(MemoryUiAutomationGuard.captureAutomationEpoch()) { dispatched = true })
        assertTrue(dispatched)
        fails { fixture.coordinator.install {} }
        fails { fixture.coordinator.takeSystemIntent {} }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun permissionIsQueriedOnlyAfterInstallRequestIsDurable() {
        fixture.backend.beforeAvailability = {
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertTrue(fixture.journal.exists())
            assertTrue(fixture.record().getBoolean("interactionOpen"))
            assertEquals(-1, fixture.record().getInt("sessionId"))
        }
        fixture.backend.allowed = false
        assertEquals("permission_required", fixture.prepare().getString("state"))
        assertEquals(1, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun failedPermissionQueryReturnsDurableUnavailableStatusWithoutGlobalGuard() {
        fixture.backend.availabilityFailure = true
        assertEquals("permission_unavailable", fixture.prepare().getString("state"))
        assertEquals("permission_unavailable", fixture.record().getString("state"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun unsupportedInstallerReturnsUnavailableWithoutApprovalOrGlobalGuard() {
        fixture.backend.availabilityOverride = FactoryInstallCoordinator.Availability.UNSUPPORTED
        assertEquals("installation_unavailable", fixture.prepare().getString("state"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun unavailableInstallRequestIsClosedAutomaticallyOnRestart() {
        for (state in listOf("permission_required", "permission_unavailable", "installation_unavailable")) {
            fixture.seed(state)
            fixture.restart()
            assertEquals("closed_outcome_unknown", fixture.state())
            assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
            fails { fixture.coordinator.install {} }
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun newRequestRechecksAndroidInstallSourceAvailability() {
        fixture.backend.allowed = false
        fixture.prepare()
        val oldNonce = fixture.nonce()
        fixture.backend.allowed = true
        assertEquals("permission_required", fixture.state())
        fails { fixture.coordinator.install {} }
        assertEquals(1, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
        assertEquals("awaiting_user", fixture.prepare().getString("state"))
        assertNotEquals(oldNonce, fixture.nonce())
        assertEquals(2, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun automatedPreparationNeedsNoJarvysApproval() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { assertEquals("awaiting_user", fixture.prepare().getString("state")) })
        assertEquals(1, fixture.backend.availabilityChecks)
        assertEquals(0, fixture.backend.permissionChecks)
        assertTrue(fixture.journal.exists())
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

    @Test fun cancellationRevokesInstallRequestBeforeStaging() {
        fixture.prepare()
        fixture.token.cancel()
        assertEquals("revoked", fixture.state())
        fails { fixture.coordinator.install {} }
        assertEquals(0, fixture.backend.creates)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(fixture.artifact.exists())
    }

    @Test fun scopeRevokedBeforeInstallNeverCreatesSession() {
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
        assertFalse(MemoryUiAutomationGuard.isProtected())
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
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun systemDenialAndBlockedStatusesRemainExplicitWithoutGlobalGuard() {
        for ((status, expected) in listOf(PackageInstaller.STATUS_FAILURE_ABORTED to "aborted_by_system", PackageInstaller.STATUS_FAILURE_BLOCKED to "blocked_by_system", PackageInstaller.STATUS_FAILURE_INVALID to "failed_by_system")) {
            fixture.seed("committing", 17)
            fixture.coordinator.callback(fixture.nonce(), 17, status, null)
            assertEquals(expected, fixture.state())
            assertEquals(status, fixture.coordinator.status().getInt("system_status"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
    }

    @Test fun pendingSystemUiRequiresCurrentAuthorityAndForegroundAndIntentIsOneShot() {
        fixture.signed(); fixture.prepare(); fixture.coordinator.install {}
        assertFalse(MemoryUiAutomationGuard.isProtected())
        val confirmation = Intent("synthetic.confirm")
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)
        assertEquals("pending_system", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.takeSystemIntent { error("Not foreground") } }
        assertSame(confirmation, fixture.coordinator.takeSystemIntent {})
        assertEquals("system_ui_open", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.takeSystemIntent {} }
        assertEquals(1, fixture.backend.commits)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun restartNeverReplaysCommitOrUnavailableSystemIntent() {
        for (prior in listOf("awaiting_user", "staging", "committing", "pending_system", "system_ui_open", "outcome_unknown")) {
            fixture.seed(prior, 17)
            fixture.restart()
            assertEquals("closed_outcome_unknown", fixture.state())
            fails { fixture.coordinator.install {} }
            fails { fixture.coordinator.takeSystemIntent {} }
            assertFalse(fixture.coordinator.consumeLaunch("forged"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
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
        fixture.backend.exists = false
        assertEquals("succeeded", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun absentSessionCancellationIsUnknownAndCannotClaimInstallSucceeded() {
        fixture.seed("outcome_unknown", 17)
        assertEquals("cancelled_outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals("cancelled_outcome_unknown", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun lateAuthenticatedSuccessReconcilesCancelledUnknownWithoutCreatingGuard() {
        fixture.seed("outcome_unknown", 17)
        fixture.coordinator.cancel()
        assertEquals("cancelled_outcome_unknown", fixture.state())
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("succeeded", fixture.state())
        assertEquals(PackageInstaller.STATUS_SUCCESS, fixture.coordinator.status().getInt("system_status"))
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
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
            assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
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
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun lateTerminalReconciliationNeverReopensClosedInteraction() {
        for ((status, expected) in listOf(
            PackageInstaller.STATUS_SUCCESS to "succeeded",
            PackageInstaller.STATUS_FAILURE_ABORTED to "aborted_by_system"
        )) {
            fixture.seed("outcome_unknown", 17)
            fixture.coordinator.cancel()
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
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fixture.coordinator.callback(newNonce, 17, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals("succeeded", fixture.state())
    }

    @Test fun missingSessionAfterUnknownCanCloseWithoutAbandon() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = false
        fixture.backend.failAbandon = true
        assertEquals("cancelled_outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertEquals(0, fixture.backend.abandons)
        assertFalse(MemoryUiAutomationGuard.isProtected())
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
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals("cancelled_outcome_unknown", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun failedAbandonAndFailedExistenceQueryKeepFamilyOutcomeUnknown() {
        fixture.seed("outcome_unknown", 17)
        fixture.backend.exists = true
        fixture.backend.beforeAbandon = {
            fixture.backend.failAbandon = true
            fixture.backend.existsFailure = true
        }
        assertEquals("outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertEquals(1, fixture.backend.abandons)
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun authenticatedTimeoutFromCommittingRecordsTerminalFailure() {
        fixture.seed("committing", 17)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_FAILURE_TIMEOUT, null)
        assertEquals("failed_by_system", fixture.state())
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, fixture.coordinator.status().getInt("system_status"))
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun sessionStillPresentOrAbandonFailureCannotCloseFamilyInteraction() {
        fixture.seed("committing", 17); fixture.backend.exists = true
        fixture.backend.keepOnAbandon = true
        assertEquals("cancel_requested", fixture.coordinator.cancel().getString("state"))
        fixture.backend.failAbandon = true
        assertEquals("outcome_unknown", fixture.coordinator.cancel().getString("state"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
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

    @Test fun stopLatchedDuringFinalValidationCannotCommitWhileItsCallbackWaitsForCoordinatorLock() {
        fixture.close(); fixture = InstallTestFixture()
        fixture.signed()
        val stopper = Executors.newSingleThreadExecutor()
        var stopped: java.util.concurrent.Future<Boolean>? = null
        try {
            fixture.coordinator.prepare(fixture.binding, fixture.bytes, {}, fixture.token, finalCheck = {
                assertEquals("committing", fixture.state())
                assertTrue("Final validation must hold the coordinator monitor", Thread.holdsLock(fixture.coordinator))
                stopped = stopper.submit<Boolean> { fixture.token.cancel() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                // Observe only the STOP latch. Its callback deliberately cannot finish while
                // this final validation holds the coordinator monitor needed by stopOperation.
                while (!fixture.token.isCancellationRequested && System.nanoTime() < deadline) Thread.yield()
                assertTrue("STOP must latch before returning from final validation", fixture.token.isCancellationRequested)
                assertFalse("The cancellation callback must still be blocked by this monitor", stopped!!.isDone)
            })
            fails { fixture.coordinator.install {} }
            assertTrue(stopped!!.get(10, TimeUnit.SECONDS))
            assertEquals(1, fixture.backend.creates)
            assertEquals(1, fixture.backend.writes)
            assertEquals("A latched STOP must win even before its callback acquires the monitor", 0, fixture.backend.commits)
            assertTrue(fixture.backend.abandons >= 1)
            assertFalse(fixture.backend.exists)
            assertNotEquals("succeeded", fixture.state())
            assertFalse(MemoryUiAutomationGuard.isProtected())
        } finally {
            stopper.shutdownNow()
            assertTrue(stopper.awaitTermination(10, TimeUnit.SECONDS))
        }
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
            assertFalse(MemoryUiAutomationGuard.isProtected())
            release.countDown()
            assertTrue(work.get(10, TimeUnit.SECONDS))
            assertEquals(0, fixture.backend.commits)
            assertEquals(1, fixture.backend.abandons)
            assertFalse(MemoryUiAutomationGuard.isProtected())
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun delayedPendingCallbackCannotReopenCancelledOrAlreadyOpenedSystemUi() {
        fixture.signed(); fixture.prepare(); fixture.coordinator.install {}
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
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(1, fixture.backend.commits)
    }

    @Test fun backupOnlyAtomicJournalRestoresFamilyStateWithoutGlobalGuardOrReplay() {
        fixture.seed("awaiting_user")
        assertTrue(fixture.journal.renameTo(File(fixture.root, "session.json.bak")))
        fixture.restart()
        assertEquals("closed_outcome_unknown", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fails { fixture.coordinator.install {} }
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
    var bytes = "synthetic unsigned installer fixture".toByteArray()
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

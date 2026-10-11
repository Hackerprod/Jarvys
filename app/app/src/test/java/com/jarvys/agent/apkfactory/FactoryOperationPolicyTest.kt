package com.jarvys.agent.apkfactory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryOperationPolicyTest {
    private val subject = FactoryOperationPolicy.Subject.signedArtifact("example.factory.app", "a".repeat(64))
    private val other = FactoryOperationPolicy.Subject.signedArtifact("example.factory.other", "b".repeat(64))
    private val operation = FactoryOperationPolicy.Operation.DOCUMENT_OPEN
    private val binding = FactoryOperationPolicy.binding("immutable request", "nonce")
    private fun rejected(action: () -> Unit) { assertTrue("Expected revoked or denied authority", runCatching(action).isFailure) }
    @Test fun absentFactoryPolicyAllowsEveryCatalogOperationWithoutAnApprovalCallback() {
        val policy = FactoryOperationPolicy.inMemory()
        for (operation in FactoryOperationPolicy.Operation.entries) {
            policy.acquire(subject, operation, binding).use { grant ->
                assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, grant.mode)
                assertFalse(grant.requiresApproval)
                grant.approveOnce { fail("ALLOW must not request a human approval") }
                grant.checkBeforeEffect()
            }
        }
    }
    @Test fun askApprovesWholeBoundRequestOnceAndDoesNotAlterTheSavedMode() {
        val policy = FactoryOperationPolicy.inMemory(FactoryOperationPolicy.Mode.ASK)
        policy.acquire(subject, operation, binding).use { grant ->
            assertTrue(grant.requiresApproval); grant.requireCurrent(); rejected { grant.checkBeforeEffect() }
            var asks = 0
            grant.approveOnce { asks++ }; grant.approveOnce { asks++ }
            assertEquals(1, asks); assertFalse(grant.requiresApproval); grant.checkBeforeEffect()
        }
        assertEquals(FactoryOperationPolicy.Mode.ASK, policy.mode(subject, operation))
        assertTrue(policy.acquire(subject, operation, binding).use { it.requiresApproval })
    }
    @Test fun denyCreatesNoAuthorityAndChangingPolicyRevokesBeforeNextEffect() {
        val policy = FactoryOperationPolicy.inMemory()
        val grant = policy.acquire(subject, operation, binding)
        var notified = 0; grant.onRevoked { notified++ }
        policy.setMode(subject, operation, FactoryOperationPolicy.Mode.DENY)
        assertEquals(1, notified); rejected { grant.requireCurrent() }; rejected { grant.checkBeforeEffect() }
        rejected { policy.acquire(subject, operation, binding) }
        policy.setMode(subject, operation, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        rejected { grant.checkBeforeEffect() }
        policy.acquire(subject, operation, binding).use { it.checkBeforeEffect() }
    }
    @Test fun unrelatedAppOperationAndExplicitOverrideSurviveDefaultChanges() {
        val policy = FactoryOperationPolicy.inMemory()
        policy.setMode(subject, operation, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        val explicit = policy.acquire(subject, operation, binding)
        val inherited = policy.acquire(other, operation, binding)
        policy.setMode(other, FactoryOperationPolicy.Operation.PHOTO_PICK, FactoryOperationPolicy.Mode.DENY)
        explicit.checkBeforeEffect(); inherited.checkBeforeEffect()
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        explicit.checkBeforeEffect(); rejected { inherited.checkBeforeEffect() }
        explicit.close()
    }
    @Test fun certificateChangeCannotInheritAnotherIdentityOverride() {
        val policy = FactoryOperationPolicy.inMemory()
        policy.setMode(subject, operation, FactoryOperationPolicy.Mode.DENY)
        val replacement = FactoryOperationPolicy.Subject.signedArtifact(subject.appId, "c".repeat(64))
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.mode(replacement, operation))
        assertNull(policy.overrideMode(replacement, operation))
        rejected { policy.acquire(subject, operation, binding) }
    }
    @Test fun removingOverrideAndRevokingPendingRequestsNeverResurrectOldGrant() {
        val policy = FactoryOperationPolicy.inMemory()
        policy.setMode(subject, operation, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        val first = policy.acquire(subject, operation, binding)
        policy.setMode(subject, operation, null); rejected { first.checkBeforeEffect() }
        val next = policy.acquire(subject, operation, binding)
        policy.revokeActive(subject); rejected { next.checkBeforeEffect() }
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.mode(subject, operation))
        policy.acquire(subject, operation, binding).use { it.checkBeforeEffect() }
    }
    @Test fun failedPersistenceDoesNotAuthorizeEffectsAndSuccessfulRetryCanRecover() {
        val store = object : FactoryOperationPolicy.Store {
            var state = FactoryOperationPolicy.State(); var fail = false
            override fun read() = state
            override fun write(state: FactoryOperationPolicy.State) { check(!fail) { "Synthetic disk failure" }; this.state = state }
        }
        val policy = FactoryOperationPolicy(store)
        val grant = policy.acquire(subject, operation, binding)
        var notifications = 0; grant.onRevoked { notifications++ }
        store.fail = true
        rejected { policy.setMode(subject, operation, FactoryOperationPolicy.Mode.DENY) }
        assertFalse(policy.isHealthy()); assertEquals(1, notifications)
        rejected { grant.checkBeforeEffect() }; rejected { policy.acquire(other, operation, binding) }
        store.fail = false; policy.setMode(subject, operation, FactoryOperationPolicy.Mode.DENY)
        assertTrue(policy.isHealthy()); rejected { policy.acquire(subject, operation, binding) }
    }
    @Test fun explicitDecisionsPersistAcrossNewInstancesAndCorruptionNeverDefaultsToAllow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "factory-operation-policy")
        directory.deleteRecursively()
        try {
            val first = FactoryOperationPolicy.persisted(context)
            first.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
            first.setMode(subject, operation, FactoryOperationPolicy.Mode.DENY)
            val second = FactoryOperationPolicy.persisted(context)
            assertEquals(FactoryOperationPolicy.Mode.ASK, second.defaultMode())
            assertEquals(FactoryOperationPolicy.Mode.DENY, second.overrideMode(subject, operation))
            rejected { second.acquire(subject, operation, binding) }
            File(directory, "policy.json").writeText("{broken}")
            rejected { FactoryOperationPolicy.persisted(context) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun explicitBootstrapChoiceMovesToKnownCertificateWithoutOverwritingExistingChoice() {
        val policy = FactoryOperationPolicy.inMemory()
        val bootstrap = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "d".repeat(64))
        policy.setMode(bootstrap, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.Mode.ASK)
        policy.bindBootstrap(bootstrap, subject)
        assertEquals(FactoryOperationPolicy.Mode.ASK, policy.overrideMode(subject, FactoryOperationPolicy.Operation.SIGN))
        policy.setMode(subject, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.Mode.DENY)
        policy.bindBootstrap(bootstrap, subject)
        assertEquals(FactoryOperationPolicy.Mode.DENY, policy.overrideMode(subject, FactoryOperationPolicy.Operation.SIGN))
    }
    @Test fun requestBindingsCannotCollideByConcatenationAndClosedGrantsStayClosed() {
        assertNotEquals(FactoryOperationPolicy.binding("ab", "c"), FactoryOperationPolicy.binding("a", "bc"))
        val policy = FactoryOperationPolicy.inMemory()
        rejected { policy.acquire(subject, operation, "not a digest") }
        val grant = policy.acquire(subject, operation, binding); grant.close(); rejected { grant.checkBeforeEffect() }
        var called = false; grant.onRevoked { called = true }; policy.revokeActive()
        assertFalse(called)
    }
    @Test fun failedNewSubjectRegistrationRevokesExistingGrantPermanently() {
        val store = object : FactoryOperationPolicy.Store {
            var state = FactoryOperationPolicy.State(); var fail = false
            override fun read() = state
            override fun write(state: FactoryOperationPolicy.State) { check(!fail); this.state = state }
        }
        val policy = FactoryOperationPolicy(store)
        val old = policy.acquire(subject, operation, binding)
        var calls = 0; old.onRevoked { calls++ }
        store.fail = true; rejected { policy.acquire(other, operation, binding) }
        assertEquals(1, calls); rejected { old.checkBeforeEffect() }
        store.fail = false; policy.setMode(other, operation, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        rejected { old.checkBeforeEffect() }
    }
    @Test fun promotedFirstSigningGrantDependsOnCertificatePolicyAndRevocation() {
        for (deny in listOf(false, true)) {
            val policy = FactoryOperationPolicy.inMemory()
            val bootstrap = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "d".repeat(64))
            val grant = policy.acquire(bootstrap, FactoryOperationPolicy.Operation.SIGN, binding)
            policy.prepareBootstrapBinding(bootstrap); policy.bindBootstrap(bootstrap, subject)
            grant.checkBeforeEffect()
            if (deny) policy.setMode(subject, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.Mode.DENY)
            else policy.revokeActive(subject)
            rejected { grant.checkBeforeEffect() }
        }
    }
    @Test fun bootstrapRecoveryFromAnotherProjectPreservesChoiceAndExplicitInheritanceTombstone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "factory-operation-policy"); directory.deleteRecursively()
        try {
            val original = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "d".repeat(64))
            val otherProject = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "e".repeat(64))
            val first = FactoryOperationPolicy.persisted(context)
            first.setMode(original, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.Mode.ASK)
            first.prepareBootstrapBinding(original) // Durable before the real key is created.
            val recovered = FactoryOperationPolicy.persisted(context)
            recovered.bindBootstrap(otherProject, subject)
            assertEquals(FactoryOperationPolicy.Mode.ASK, recovered.overrideMode(subject, FactoryOperationPolicy.Operation.SIGN))
            recovered.setMode(subject, FactoryOperationPolicy.Operation.SIGN, null)
            recovered.bindBootstrap(otherProject, subject)
            assertNull(recovered.overrideMode(subject, FactoryOperationPolicy.Operation.SIGN))
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, recovered.mode(subject, FactoryOperationPolicy.Operation.SIGN))
            val differentCertificate = FactoryOperationPolicy.Subject.signedArtifact(subject.appId, "f".repeat(64))
            recovered.bindBootstrap(otherProject, differentCertificate)
            assertNull(recovered.overrideMode(differentCertificate, FactoryOperationPolicy.Operation.SIGN))
        } finally { directory.deleteRecursively() }
    }

    @Test fun staleCompetingBootstrapCannotReplaceCertificateBoundOwner() {
        val policy = FactoryOperationPolicy.inMemory()
        val first = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "d".repeat(64))
        val stale = FactoryOperationPolicy.Subject.bootstrap(subject.appId, "e".repeat(64))
        policy.prepareBootstrapBinding(first); policy.bindBootstrap(first, subject)
        policy.setMode(stale, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.Mode.DENY)
        rejected { policy.prepareBootstrapBinding(stale) }
        policy.bindBootstrap(stale, subject)
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.mode(subject, FactoryOperationPolicy.Operation.SIGN))
    }

}

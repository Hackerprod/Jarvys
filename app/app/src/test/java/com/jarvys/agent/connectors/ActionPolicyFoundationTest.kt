package com.jarvys.agent.connectors

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionPolicyFoundationTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var scope: String
    private val id = "foundation"
    private val operation = "action"
    private val key get() = PersistedActionPolicies.policyKey(id, operation)
    private val metadataKey get() = PersistedActionPolicies.metadataKey(id, operation)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = "fixture-" + UUID.randomUUID().toString()
        preferences = context.getSharedPreferences(scope, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
    }

    private fun store(prefs: SharedPreferences = preferences) = SharedPreferencesConnectorAutonomyStore(prefs, scope)
    private fun rejected(block: () -> Unit) { assertTrue("Expected a fail-closed result", runCatching(block).isFailure) }

    @Test fun missingAndFutureFamiliesUseOneAllowDefaultWithoutMaterializingUserChoices() {
        val store = store()
        val snapshot = store.snapshot(id, operation)
        assertEquals(AutonomyPolicy.ALLOW, snapshot.policy)
        assertEquals(ActionPolicyOrigin.CATALOG_DEFAULT, snapshot.origin)
        assertNull(store.savedPolicy(id, operation))
        assertFalse(preferences.contains(key))
        assertEquals(AutonomyPolicy.ALLOW, store.snapshot("future_family", "new_action").policy)
        assertEquals(AutonomyPolicy.ALLOW, ActionPolicyDefaults.descriptor("mcp:new-server", "dynamic-tool").defaultPolicy)
        assertFalse(ActionPolicyDefaults.registeredActions().any { it.connectorId == "native" || it.connectorId == "factory" })
        store.admit(snapshot)
    }

    @Test fun catalogCoversDeviceAndExternalOperationsWithoutNativePermissionFamilies() {
        val catalog = ActionPolicyDefaults.registeredActions().associateBy { it.connectorId to it.operationName }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = ConnectorRegistry.deviceDefinitions(context).flatMap { definition ->
            definition.operations.map { definition.id to it.name }
        } + listOf("linux" to "linux_exec", "linux" to "linux_setup", "linux" to "linux_uninstall", "coding" to "project_exec")
        expected.forEach { key ->
            val descriptor = catalog[key]
            assertNotNull("Missing policy catalog entry: $key", descriptor)
            assertEquals(AutonomyPolicy.ALLOW, descriptor!!.defaultPolicy)
            assertTrue(descriptor.availabilityIsIndependent)
        }
        assertFalse(catalog.keys.any { it.first == "native" || it.first == "factory" })
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun legacyValidValuesArePreservedWithIdempotentUnknownProvenance() {
        for (choice in AutonomyPolicy.entries) {
            check(preferences.edit().clear().putString(key, choice.name).commit())
            val first = store().snapshot(id, operation)
            val metadata = preferences.getString(metadataKey, null)
            val second = store().snapshot(id, operation)
            assertEquals(choice, first.policy)
            assertEquals(ActionPolicyOrigin.LEGACY_UNKNOWN, first.origin)
            assertEquals(metadata, preferences.getString(metadataKey, null))
            assertTrue(first.sameAuthorizationAs(second))
        }
    }

    @Test fun explicitChoicesStayUserChoicesAcrossIndependentInstances() {
        for (choice in AutonomyPolicy.entries) {
            val receipt = store().setPolicyAndSnapshot(id, operation, choice)
            val recreated = store().snapshot(id, operation)
            assertEquals(choice, recreated.policy)
            assertEquals(ActionPolicyOrigin.USER, recreated.origin)
            assertTrue(receipt.sameAuthorizationAs(recreated))
        }
    }

    @Test fun invalidEnumAndWrongTypeAreAskNotAbsence() {
        for (invalid in listOf<Any>("CORRUPT", 12, true, setOf("ASK"))) {
            val editor = preferences.edit().remove(metadataKey)
            when (invalid) {
                is String -> editor.putString(key, invalid)
                is Int -> editor.putInt(key, invalid)
                is Boolean -> editor.putBoolean(key, invalid)
                is Set<*> -> editor.putStringSet(key, setOf("ASK"))
            }
            check(editor.commit())
            val store = store()
            val snapshot = store.snapshot(id, operation)
            assertEquals(AutonomyPolicy.ASK, store.savedPolicy(id, operation))
            assertEquals(AutonomyPolicy.ASK, snapshot.policy)
            assertEquals(ActionPolicyOrigin.INVALID, snapshot.origin)
            rejected { store.admit(snapshot) }
        }
    }

    @Test fun malformedMetadataCannotTurnSavedDenyIntoDefault() {
        check(preferences.edit().putString(key, "DENY").putString(metadataKey, "not-json").commit())
        val store = store()
        val snapshot = store.snapshot(id, operation)
        assertEquals(AutonomyPolicy.DENY, snapshot.policy)
        assertEquals(ActionPolicyOrigin.LEGACY_UNKNOWN, snapshot.origin)
        rejected { store.admit(snapshot, approvedAsk = true) }
    }

    @Test fun askRequiresExactCallerApprovalAndCannotBeReused() {
        val store = store()
        val snapshot = store.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ASK)
        rejected { store.admit(snapshot) }
        store.admit(snapshot, approvedAsk = true)
        rejected { store.admit(snapshot, approvedAsk = true) }
    }

    @Test fun denyCannotBeApprovedAndAllowDoesNotAcceptAnAskReceipt() {
        val store = store()
        rejected { store.admit(store.setPolicyAndSnapshot(id, operation, AutonomyPolicy.DENY), approvedAsk = true) }
        val allowed = store.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        rejected { store.admit(allowed, approvedAsk = true) }
        store.admit(allowed)
    }

    @Test fun independentInstancesSeeAskThenDenyRevocation() {
        val first = store()
        val snapshot = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ASK)
        store().setPolicyChecked(id, operation, AutonomyPolicy.DENY)
        rejected { first.admit(snapshot, approvedAsk = true) }
    }

    @Test fun allowToAskNeedsNewSnapshotAndNewRequest() {
        val first = store()
        val allowed = first.snapshot(id, operation)
        store().setPolicyChecked(id, operation, AutonomyPolicy.ASK)
        rejected { first.admit(allowed) }
        rejected { first.admit(allowed, approvedAsk = true) }
        first.admit(first.snapshot(id, operation), approvedAsk = true)
    }

    @Test fun allowDenyAllowAbaInvalidatesOriginalCandidate() {
        val first = store()
        val original = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        store().setPolicy(id, operation, AutonomyPolicy.DENY)
        store().setPolicy(id, operation, AutonomyPolicy.ALLOW)
        rejected { first.admit(original) }
        rejected { first.snapshotMatching(original) }
    }

    @Test fun fullOldMetadataAndWireRestoreCannotReviveSnapshotAfterObservedMutation() {
        val first = store()
        val original = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        val oldMetadata = preferences.getString(metadataKey, null)
        store().setPolicy(id, operation, AutonomyPolicy.DENY)
        check(preferences.edit().putString(key, "ALLOW").putString(metadataKey, oldMetadata).commit())
        val restored = store().snapshot(id, operation)
        assertEquals(original.revision, restored.revision)
        assertEquals(original.policy, restored.policy)
        assertFalse(original.sameAuthorizationAs(restored))
        rejected { first.admit(original) }
        rejected { first.snapshotMatching(original) }
    }

    @Test fun sameDecisionRewriteHasNewRevisionAndAdmissionIdentity() {
        val first = store()
        val original = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        val newer = store().setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        assertTrue(newer.revision > original.revision)
        assertFalse(original.sameAuthorizationAs(newer))
        rejected { first.admit(original) }
    }

    @Test fun restoreDifferentRawChoiceReconcilesMetadataWithoutChangingTheChoice() {
        val first = store()
        val original = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ALLOW)
        check(preferences.edit().putString(key, "ASK").commit())
        val restored = store().snapshot(id, operation)
        assertEquals(AutonomyPolicy.ASK, restored.policy)
        assertEquals(ActionPolicyOrigin.LEGACY_UNKNOWN, restored.origin)
        assertTrue(restored.revision > original.revision)
        rejected { first.admit(original) }
    }

    @Test fun throwingValidationDoesNotConsumeAndReentrantSetterCannotSlipThrough() {
        val first = store()
        val snapshot = first.snapshot(id, operation)
        rejected { first.admit(snapshot) { error("capability unavailable") } }
        first.admit(snapshot)
        val another = first.snapshotMatching(snapshot)
        rejected { first.admit(another) { store().setPolicy(id, operation, AutonomyPolicy.ASK) } }
        rejected { first.admit(another, approvedAsk = true) }
    }

    @Test fun setterAndAdmissionValidationLinearizeAcrossInstances() {
        val first = store()
        val second = store()
        val snapshot = first.snapshot(id, operation)
        val started = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        lateinit var worker: Thread
        first.admit(snapshot) {
            worker = Thread {
                started.countDown()
                try { second.setPolicy(id, operation, AutonomyPolicy.DENY) }
                catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }.apply { start() }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(completed.await(30, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        worker.join(2000)
        assertNull(failure.get())
        rejected { first.snapshotMatching(snapshot) }
    }

    @Test fun crossAuthorityAndDifferentActionDoNotMatch() {
        val first = store()
        val snapshot = first.snapshot(id, operation)
        val unrelated = SharedPreferencesConnectorAutonomyStore(preferences, scope + "-unrelated")
        rejected { unrelated.admit(snapshot) }
        rejected { unrelated.snapshotMatching(snapshot) }
        assertFalse(snapshot.sameAuthorizationAs(first.snapshot(id, "different")))
    }

    @Test fun consumedAnchorCanMintOnlyFreshMatchingAdmissionCandidates() {
        val first = store()
        val anchor = first.snapshot(id, operation)
        first.admit(anchor)
        first.admit(first.snapshotMatching(anchor))
        first.admit(first.snapshotMatching(anchor))
        store().setPolicy(id, operation, AutonomyPolicy.ASK)
        rejected { first.snapshotMatching(anchor) }
    }

    @Test fun inheritedPolicyDoesNotOverwriteMissingOrExplicitRawChoices() {
        val first = store()
        val inherited = first.snapshot(id, operation, AutonomyPolicy.ASK)
        assertEquals(ActionPolicyOrigin.INHERITED_SERVER_DEFAULT, inherited.origin)
        assertEquals(AutonomyPolicy.ASK, inherited.policy)
        assertFalse(preferences.contains(key))
        assertFalse(inherited.sameAuthorizationAs(first.snapshot(id, operation, AutonomyPolicy.DENY)))
        first.admit(inherited, approvedAsk = true)
        first.setPolicy(id, operation, AutonomyPolicy.DENY)
        val explicit = first.snapshot(id, operation, AutonomyPolicy.ALLOW)
        assertEquals(AutonomyPolicy.DENY, explicit.policy)
        assertEquals(ActionPolicyOrigin.USER, explicit.origin)
    }

    @Test fun newMetadataKeysAreCollisionFreeWhileLegacyKeysStayStable() {
        assertEquals("autonomy_a_b_c", PersistedActionPolicies.policyKey("a_b", "c"))
        assertNotEquals(PersistedActionPolicies.metadataKey("a_b", "c"), PersistedActionPolicies.metadataKey("a", "b_c"))
        assertNotEquals(PersistedActionPolicies.metadataKey("x:", "é"), PersistedActionPolicies.metadataKey("x", ":é"))
    }

    @Test fun failedCommitRestoresPriorRawTypeAndInvalidatesOutstandingSnapshot() {
        check(preferences.edit().putInt(key, 12).commit())
        val unreliable = FailingPreferences(preferences)
        val first = store(unreliable)
        val old = first.snapshot(id, operation)
        val oldMetadata = preferences.getString(metadataKey, null)
        unreliable.failCommits = 1
        rejected { first.setPolicy(id, operation, AutonomyPolicy.ALLOW) }
        assertEquals(12, preferences.getInt(key, 0))
        assertEquals(oldMetadata, preferences.getString(metadataKey, null))
        assertEquals(AutonomyPolicy.ASK, store().policy(id, operation))
        rejected { first.admit(old, approvedAsk = true) }
    }

    @Test fun failedCommitRestoresAbsenceWithoutCreatingAnExplicitPolicy() {
        val unreliable = FailingPreferences(preferences)
        val first = store(unreliable)
        first.snapshot(id, operation)
        unreliable.failCommits = 1
        rejected { first.setPolicy(id, operation, AutonomyPolicy.DENY) }
        assertFalse(preferences.contains(key))
        assertNull(store().savedPolicy(id, operation))
        assertEquals(AutonomyPolicy.ALLOW, store().policy(id, operation))
    }

    @Test fun failedRollbackPoisonsAllInstancesUntilProcessRecovery() {
        val unreliable = FailingPreferences(preferences)
        val first = store(unreliable)
        val snapshot = first.snapshot(id, operation)
        unreliable.failCommits = 2
        rejected { first.setPolicy(id, operation, AutonomyPolicy.DENY) }
        rejected { first.admit(snapshot) }
        rejected { store().snapshot(id, operation) }
        rejected { store().setPolicy(id, operation, AutonomyPolicy.ALLOW) }
    }

    @Test fun failedMetadataMigrationCannotAuthorizeAnAction() {
        val unreliable = FailingPreferences(preferences)
        val first = store(unreliable)
        unreliable.failCommits = 1
        rejected { first.snapshot(id, operation) }
        assertFalse(preferences.contains(metadataKey))
        first.admit(first.snapshot(id, operation))
    }

    @Test fun clearRemovesPolicyAndMetadataAndInvalidatesOldAdmissions() {
        val first = store()
        val previous = first.setPolicyAndSnapshot(id, operation, AutonomyPolicy.ASK)
        first.clearPolicies(id)
        assertFalse(preferences.contains(key))
        assertFalse(preferences.contains(metadataKey))
        assertEquals(ActionPolicyOrigin.CATALOG_DEFAULT, first.snapshot(id, operation).origin)
        rejected { first.admit(previous, approvedAsk = true) }
    }

    @Test fun inMemorySupportsTheSameOneUseRevisionAndInheritedContract() {
        val first = InMemoryConnectorAutonomyStore()
        val snapshot = first.snapshot(id, operation)
        assertEquals(AutonomyPolicy.ALLOW, snapshot.policy)
        first.admit(snapshot)
        first.admit(first.snapshotMatching(snapshot))
        first.setPolicy(id, operation, AutonomyPolicy.ASK)
        rejected { first.snapshotMatching(snapshot) }
        val ask = first.snapshot(id, operation)
        rejected { first.admit(ask) }
        first.admit(ask, approvedAsk = true)
        rejected { first.admit(ask, approvedAsk = true) }
        first.clearPolicies(id)
        assertEquals(ActionPolicyOrigin.INHERITED_SERVER_DEFAULT, first.snapshot(id, operation, AutonomyPolicy.DENY).origin)
    }

    /** Models Android's observable memory mutation despite commit failure, without relying on disk errors. */
    private class FailingPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var failCommits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val result = editor.commit()
                    return if (failCommits > 0) { failCommits--; false } else result
                }
            }
        }
    }
}

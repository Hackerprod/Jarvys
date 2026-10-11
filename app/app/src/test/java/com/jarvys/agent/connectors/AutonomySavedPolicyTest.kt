package com.jarvys.agent.connectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomySavedPolicyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
    private val connector = "saved-policy-fixture"
    private val operation = "write"
    private val key get() = "autonomy_${connector}_$operation"

    @Before fun setup() { preferences.edit().remove(key).commit() }
    @After fun cleanup() { preferences.edit().remove(key).commit() }

    @Test fun inMemoryDistinguishesAbsentFromEveryExplicitChoiceWithoutChangingDefault() {
        val store = InMemoryConnectorAutonomyStore()
        assertNull(store.savedPolicy(connector, operation))
        assertEquals(AutonomyPolicy.ASK, store.policy(connector, operation))
        for (choice in AutonomyPolicy.entries) {
            store.setPolicyChecked(connector, operation, choice)
            assertEquals(choice, store.savedPolicy(connector, operation))
            assertEquals(choice, store.policy(connector, operation))
        }
        store.clearPolicies(connector)
        assertNull(store.savedPolicy(connector, operation))
        assertEquals(AutonomyPolicy.ASK, store.policy(connector, operation))
    }

    @Test fun persistedAbsenceAndExplicitAskRemainDistinctAfterStoreRecreation() {
        val store = SharedPreferencesConnectorAutonomyStore(context)
        assertNull(store.savedPolicy(connector, operation))
        assertEquals(AutonomyPolicy.ASK, store.policy(connector, operation))
        for (choice in AutonomyPolicy.entries) {
            store.setPolicyChecked(connector, operation, choice)
            val reloaded = SharedPreferencesConnectorAutonomyStore(context)
            assertEquals(choice, reloaded.savedPolicy(connector, operation))
            assertEquals(choice, reloaded.policy(connector, operation))
        }
        store.clearPolicies(connector)
        assertNull(SharedPreferencesConnectorAutonomyStore(context).savedPolicy(connector, operation))
    }

    @Test fun malformedSavedChoiceIsConservativeAskRatherThanAbsence() {
        val store = SharedPreferencesConnectorAutonomyStore(context)
        preferences.edit().putString(key, "CORRUPTED").commit()
        assertEquals(AutonomyPolicy.ASK, store.savedPolicy(connector, operation))
        preferences.edit().putInt(key, 1).commit()
        assertEquals(AutonomyPolicy.ASK, store.savedPolicy(connector, operation))
    }

    @Test fun legacyStoreWithoutPresenceSupportRetainsItsEffectiveAsk() {
        val store = object : ConnectorAutonomyStore {
            override fun policy(connectorId: String, operationName: String) = AutonomyPolicy.ASK
            override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) = Unit
            override fun appendAudit(record: AutonomyAuditRecord) = Unit
            override fun recentAudit(connectorId: String, limit: Int) = emptyList<AutonomyAuditRecord>()
        }
        assertEquals(AutonomyPolicy.ASK, store.savedPolicy(connector, operation))
    }
}

package com.jarvys.agent.connectors

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomyAuditPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val preferences get() = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0)

    @Before fun clearAudit() { preferences.edit().remove("autonomy_audit").remove("autonomy_hourly_reservations").commit() }
    @After fun cleanupAudit() { preferences.edit().remove("autonomy_audit").remove("autonomy_hourly_reservations").commit() }

    @Test fun twoHundredAuditEntriesSurviveStoreRecreationAndRecentAuditUsesTheRequestedPageSize() {
        val store = SharedPreferencesConnectorAutonomyStore(context)
        repeat(200) { index ->
            store.appendAudit(AutonomyAuditRecord(
                timestampMillis = index.toLong(),
                connectorId = "calendar",
                connectorName = "Calendar",
                operationName = "create_event",
                operationLabel = "Create event",
                summary = "Recorded event $index",
            ))
        }

        val reloaded = SharedPreferencesConnectorAutonomyStore(context)
        val all = reloaded.recentAudit("calendar", 200)
        assertEquals(200, all.size)
        assertEquals("Recorded event 199", all.first().summary)
        assertEquals("Recorded event 0", all.last().summary)
        assertEquals(25, reloaded.recentAudit("calendar", 25).size)
    }
}

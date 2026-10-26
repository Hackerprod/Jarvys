package com.jarvys.agent.connectors

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
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

    @Before fun clearAudit() {
        kotlinx.coroutines.runBlocking { com.jarvys.agent.SingleSessionReset.awaitReady(context) }
        preferences.edit().remove("autonomy_audit").remove("autonomy_audit_room_migrated")
            .remove("autonomy_hourly_reservations").commit()
    }
    @After fun cleanupAudit() { preferences.edit().remove("autonomy_audit").remove("autonomy_audit_room_migrated")
        .remove("autonomy_hourly_reservations").commit() }

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

    @Test fun corruptLegacyDoesNotEraseSourceOrBlockANewDurableAudit() {
        val original = "[invalid legacy audit"
        preferences.edit().putString("autonomy_audit", original).commit()
        val store = SharedPreferencesConnectorAutonomyStore(context)
        val connector = "audit-corrupt-fixture"
        store.appendAudit(AutonomyAuditRecord(timestampMillis = 9L, connectorId = connector,
            connectorName = "Fixture", operationName = "save", operationLabel = "Save", summary = "New local event"))
        assertEquals(original, preferences.getString("autonomy_audit", null))
        assertFalse(preferences.getBoolean("autonomy_audit_room_migrated", false))
        val bridge = com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context)
        assertEquals(1, bridge.recentConnectorAuditSync(connector, 10).size)
        try { store.recentAudit(connector, 10); fail("Incomplete history must be reported") }
        catch (_: IllegalStateException) { }
    }

    @Test fun batchConflictRollsBackEarlierRowsAndIdenticalReplayIsIdempotent() {
        val bridge = com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context)
        val connector = "audit-transaction-fixture"
        val original = AutonomyAuditRecord(id = "fixture-existing", timestampMillis = 12L,
            connectorId = connector, connectorName = "Fixture", operationName = "save",
            operationLabel = "Save", summary = "Original local event")
        bridge.appendConnectorAuditSync(original)
        val newRow = original.copy(id = "fixture-new")
        try {
            bridge.importConnectorAuditSync(listOf(newRow, original.copy(summary = "Conflicting event")))
            fail("Conflicting identity must reject the batch")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(original), bridge.recentConnectorAuditSync(connector, 10))
        bridge.importConnectorAuditSync(listOf(original, newRow))
        bridge.importConnectorAuditSync(listOf(original, newRow))
        assertEquals(2, bridge.recentConnectorAuditSync(connector, 10).size)
    }

}

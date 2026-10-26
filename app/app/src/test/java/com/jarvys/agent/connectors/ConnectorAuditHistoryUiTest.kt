package com.jarvys.agent.connectors

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorAuditHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun auditLoadsOffMainAndFailureIsVisibleUntilExplicitRetrySucceeds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = AtomicInteger()
        val mainThreadReads = AtomicInteger()
        val store = object : ConnectorAutonomyStore by InMemoryConnectorAutonomyStore() {
            override fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord> {
                if (Looper.myLooper() == Looper.getMainLooper()) mainThreadReads.incrementAndGet()
                assertEquals("audit_ui", connectorId)
                assertEquals(10, limit)
                if (calls.incrementAndGet() == 1) error("Synthetic migration pending")
                return listOf(AutonomyAuditRecord(id = "ui-event", timestampMillis = 1L,
                    connectorId = connectorId, connectorName = "Audit fixture", operationName = "save",
                    operationLabel = "Save", summary = "Synthetic completed event"))
            }
        }
        val preferences = object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = error("History must not change connection")
        }
        val registry = ConnectorRegistry.createForTestsWithAutonomy(preferences, { true },
            ApprovalGate.INSTANCE, store, NoopAutonomyActionNotifier)
        val definition = ConnectorDefinition("audit_ui", "Audit fixture", "1", "Synthetic local audit")
        registry.register(definition)
        compose.setContent { MaterialTheme {
            ConnectorDeviceDetailPage(definition, ConnectorState.DISCONNECTED, registry, 0L,
                onConnect = { error("History must not connect") },
                onDisconnect = { error("History must not disconnect") }, openSettings = {})
        } }
        compose.onNodeWithText(context.getString(R.string.connector_advanced)).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText(context.getString(R.string.connector_audit_incomplete)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.connector_audit_incomplete)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_history_load_retry)).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Synthetic completed event", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Synthetic completed event", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.connector_audit_incomplete)).assertDoesNotExist()
        assertEquals(2, calls.get())
        assertEquals(0, mainThreadReads.get())
    }
}

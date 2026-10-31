package com.jarvys.agent.connectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemovedConnectorStateCleanupTest {
    @Test fun startupExcludesRetiredChoicesWithoutRewritingLegacyBytesAndKeepsOtherConnectorState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear()
            .putBoolean("connected_email", true)
            .putBoolean("connected_sms_draft", true)
            .putBoolean("connected_phone", true)
            .putString("autonomy_email_compose_email", "DENY")
            .putString("autonomy_sms_draft_send_sms", "ASK")
            .putString(PersistedActionPolicies.metadataKey("email", "compose_email"), metadata("email", "compose_email"))
            .putString(PersistedActionPolicies.metadataKey("sms_draft", "send_sms"), metadata("sms_draft", "send_sms"))
            .putString("autonomy_audit", JSONArray().put(record("email").toJson())
                .put(record("sms_draft").toJson()).put(record("phone").toJson()).toString())
            .commit()

        val autonomy = SharedPreferencesConnectorAutonomyStore(context)
        autonomy.setPolicy("phone", "open_dialer", AutonomyPolicy.ALLOW)

        ConnectorStateStore(context)
        val reloaded = SharedPreferencesConnectorAutonomyStore(context)

        // Retired choice bytes are not imported or rewritten by a constructor; operational access remains excluded.
        assertTrue(preferences.getBoolean("connected_email", false))
        assertTrue(preferences.getBoolean("connected_sms_draft", false))
        val access = ConnectionAccess.forTests(mapOf("email" to true, "sms_draft" to true, "phone" to true))
        assertTrue(access.captureChoice("email").excluded)
        assertTrue(access.captureChoice("sms_draft").excluded)
        assertFalse(access.captureChoice("email").selected)
        assertFalse(access.captureChoice("sms_draft").selected)
        assertTrue(access.captureChoice("phone").selected)
        assertTrue(preferences.getBoolean("connected_phone", false))
        assertFalse(preferences.contains("autonomy_email_compose_email"))
        assertFalse(preferences.contains("autonomy_sms_draft_send_sms"))
        val orphanMetadata = preferences.all.filterKeys { it.startsWith(PersistedActionPolicies.METADATA_PREFIX) }
            .values.mapNotNull { value -> (value as? String)?.let { runCatching { org.json.JSONObject(it).optString("connectorId") }.getOrNull() } }
        assertFalse(orphanMetadata.any { it == "email" || it == "sms_draft" })
        assertEquals(listOf("phone"), reloaded.recentAudit("phone", 10).map { it.connectorId })
        assertEquals(AutonomyPolicy.ALLOW, reloaded.policy("phone", "open_dialer"))
        assertTrue(reloaded.recentAudit("email", 10).isEmpty())
        assertTrue(reloaded.recentAudit("sms_draft", 10).isEmpty())
        assertTrue(ConnectorRegistry.deviceDefinitions(context).none { it.id == "email" || it.id == "sms_draft" })
    }

    private fun record(connectorId: String) = AutonomyAuditRecord(
        timestampMillis = 1L,
        connectorId = connectorId,
        connectorName = connectorId,
        operationName = "operation",
        operationLabel = "Operation",
        summary = "Completed automatically",
    )

    private fun metadata(connectorId: String, operation: String) = JSONObject()
        .put("connectorId", connectorId).put("operationName", operation).toString()

    private fun AutonomyAuditRecord.toJson() = JSONObject()
        .put("id", id).put("timestampMillis", timestampMillis).put("connectorId", connectorId)
        .put("connectorName", connectorName).put("operationName", operationName)
        .put("operationLabel", operationLabel).put("summary", summary)
}

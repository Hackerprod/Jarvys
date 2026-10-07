package com.jarvys.agent.connectors

import android.Manifest
import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogConnectorTest {
    private class FakeGateway : CallLogGateway {
        var query: CallLogQuery? = null
        var requestedLimit = 0
        var records = emptyList<CallLogRecord>()
        override fun list(query: CallLogQuery, limit: Int): List<CallLogRecord> {
            this.query = query
            requestedLimit = limit
            return records.take(limit)
        }
    }

    private val token = CancellationToken.uncancellable()

    @Test fun listDefaultsToTwentyRecentCallsAndUsesAnUntrustedBoundedEnvelope() {
        val gateway = FakeGateway().apply {
            records = (1..21).map { CallLogRecord(it.toLong(), "+15550000000", "Mom", 1, it.toLong(), 43) }
        }
        val connector = CallLogConnector(gateway, { true }, { 1_000_000L })
        val result = connector.invoke(CallLogConnector.LIST_CALLS, JSONObject().put("number", "555"), token)
        assertEquals(CallLogConnector.DEFAULT_LIMIT + 1, gateway.requestedLimit)
        assertEquals("555", gateway.query?.number)
        assertEquals(20, result.getJSONArray("items").length())
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        assertEquals("android.call_log", result.getString("source"))
    }

    @Test fun schemaIncludesTypedFiltersAndConnectorIsReadOnly() {
        val schema = CallLogConnector.listSchema()
        val properties = schema.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("number").getString("type"))
        assertEquals("integer", properties.getJSONObject("type").getString("type"))
        assertEquals(50, properties.getJSONObject("limit").getInt("maximum"))
        val definition = CallLogConnector.definition(FakeGateway()) { true }
        assertFalse(definition.operations.any { it.write })
        assertEquals(listOf(Manifest.permission.READ_CALL_LOG), definition.readPermissions)
        assertEquals(emptyList<String>(), definition.writePermissions)
    }

    @Test fun rejectsRangesAboveNinetyDaysAndInvalidLimits() {
        val now = 1_700_000_000_000L
        val connector = CallLogConnector(FakeGateway(), { true }, { now })
        assertTrue(runCatching {
            connector.invoke(CallLogConnector.LIST_CALLS,
                JSONObject().put("startTimeMs", now - CallLogConnector.MAX_RANGE_MS - 1).put("endTimeMs", now), token)
        }.isFailure)
        assertTrue(runCatching {
            connector.invoke(CallLogConnector.LIST_CALLS, JSONObject().put("limit", 51), token)
        }.isFailure)
    }
}

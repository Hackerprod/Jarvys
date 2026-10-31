package com.jarvys.agent.proactive

import android.provider.CallLog
import com.jarvys.agent.connectors.CallLogRecord
import com.jarvys.agent.connectors.FullSmsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullProactiveNormalizersTest {
    @Test fun fullSourceSpecsDescribeLocalTimeInBothLanguagesAndKeepPermissionPerFlavor() {
        assertEquals("android.permission.READ_SMS", FullProactiveSources.sms.requiredPermission)
        assertEquals("sms_provider", FullProactiveSources.sms.trigger)
        assertEquals("android.permission.READ_CALL_LOG", FullProactiveSources.callLog.requiredPermission)
        assertEquals("call_log_provider", FullProactiveSources.callLog.trigger)
        listOf(FullProactiveSources.sms, FullProactiveSources.callLog).forEach { spec ->
            org.junit.Assert.assertTrue(spec.descriptionForModel.en.contains("local"))
            org.junit.Assert.assertTrue(spec.descriptionForModel.es.contains("zona horaria local") ||
                spec.descriptionForModel.es.contains("hora original local"))
        }
    }

    @Test fun smsNormalizationRetainsProviderTimeAndSetsDirection() {
        val inbound = FullProactiveNormalizers.sms(
            FullSmsRecord(1, "+15551234567", "Payment received", 1_700_000_000_123, "inbox"),
            1_700_000_010_000,
        )
        val outbound = FullProactiveNormalizers.sms(
            FullSmsRecord(2, "+15551234567", "Thanks", 1_700_000_000_456, "sent"),
            1_700_000_011_000,
        )
        assertEquals("inbound", inbound.direction)
        assertEquals("outbound", outbound.direction)
        assertEquals("msg", inbound.category)
        assertEquals(1_700_000_000_123, inbound.receivedAtMillis)
        assertEquals(1_700_000_010_000, inbound.observedAtMillis)
        assertEquals(FullProactiveSources.SMS, inbound.sourceId)
    }

    @Test fun callNormalizationMapsMissedIncomingAndOutgoingCalls() {
        val missed = FullProactiveNormalizers.call(
            CallLogRecord(1, "+15550001111", "Alex", CallLog.Calls.MISSED_TYPE, 1_700_000_000_111, 0),
            1_700_000_020_000,
        )
        val outgoing = FullProactiveNormalizers.call(
            CallLogRecord(2, "+15550002222", null, CallLog.Calls.OUTGOING_TYPE, 1_700_000_000_222, 30),
            1_700_000_021_000,
        )
        assertEquals("missed_call", missed.category)
        assertEquals("inbound", missed.direction)
        assertEquals("call", outgoing.category)
        assertEquals("outbound", outgoing.direction)
        assertNull(outgoing.body)
        assertEquals(1_700_000_000_111, missed.receivedAtMillis)
    }
}

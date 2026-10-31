package com.jarvys.agent.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskRunCauseTest {
    @Test fun legacyLedgerAndProcessorReasonsMapToStableCodes() {
        val legacy = mapOf(
            "Task execution failed" to TaskRunCause.EXECUTION_FAILED,
            "Previous occurrence was interrupted" to TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED,
            "Task run stopped after an effect" to TaskRunCause.EFFECT_OCCURRED,
            "sin proveedor" to TaskRunCause.NO_PROVIDER,
            "provider_http_503" to TaskRunCause.PROVIDER_UNAVAILABLE,
            "429" to TaskRunCause.PROVIDER_RATE_LIMITED,
            "5xx" to TaskRunCause.PROVIDER_UNAVAILABLE,
            "network" to TaskRunCause.NETWORK,
            "connector_unavailable:calendar.search" to TaskRunCause.CONNECTOR_UNAVAILABLE,
            "connector unavailable" to TaskRunCause.CONNECTOR_UNAVAILABLE,
        )

        legacy.forEach { (oldValue, expected) ->
            assertEquals(oldValue, expected, TaskRunCause.fromLegacy(oldValue))
        }
        assertEquals(TaskRunCause.NO_PROVIDER, TaskRunCause.fromCode("NO_PROVIDER"))
        assertEquals(TaskRunCause.UNKNOWN, TaskRunCause.fromLegacy("future provider failure"))
        assertEquals("future provider failure",
            TaskRunCause.legacyDetail("future provider failure", TaskRunCause.UNKNOWN))
        assertNull(TaskRunCause.fromLegacy(null))
    }
}

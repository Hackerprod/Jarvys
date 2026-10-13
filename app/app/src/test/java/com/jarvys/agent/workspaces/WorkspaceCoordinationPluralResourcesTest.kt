package com.jarvys.agent.workspaces

import com.jarvys.agent.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class WorkspaceCoordinationPluralResourcesTest {
    @Test fun englishCounterResourcesUseCorrectZeroOneAndTwoForms() {
        assertQuantities(R.plurals.workspace_coordination_live, listOf(
            "0 questions or blockers can receive an exact reply.",
            "1 question or blocker can receive an exact reply.",
            "2 questions or blockers can receive an exact reply.",
        ))
        assertQuantities(R.plurals.workspace_coordination_uncertain, listOf(
            "0 reply attempts have an uncertain outcome. Do not resend automatically.",
            "1 reply attempt has an uncertain outcome. Do not resend automatically.",
            "2 reply attempts have an uncertain outcome. Do not resend automatically.",
        ))
        assertQuantities(R.plurals.workspace_coordination_historical, listOf(
            "0 historical or superseded requests. Reopening does not resume work.",
            "1 historical or superseded request. Reopening does not resume work.",
            "2 historical or superseded requests. Reopening does not resume work.",
        ))
    }

    @Test @Config(qualifiers = "es-rES")
    fun spanishCounterResourcesUseCorrectZeroOneAndTwoForms() {
        assertQuantities(R.plurals.workspace_coordination_live, listOf(
            "0 preguntas o bloqueos pueden recibir una respuesta dirigida.",
            "1 pregunta o bloqueo puede recibir una respuesta dirigida.",
            "2 preguntas o bloqueos pueden recibir una respuesta dirigida.",
        ))
        assertQuantities(R.plurals.workspace_coordination_uncertain, listOf(
            "0 intentos de respuesta tienen resultado incierto. No reenviar automáticamente.",
            "1 intento de respuesta tiene resultado incierto. No reenviar automáticamente.",
            "2 intentos de respuesta tienen resultado incierto. No reenviar automáticamente.",
        ))
        assertQuantities(R.plurals.workspace_coordination_historical, listOf(
            "0 solicitudes históricas o sustituidas. Reabrir no reanuda el trabajo.",
            "1 solicitud histórica o sustituida. Reabrir no reanuda el trabajo.",
            "2 solicitudes históricas o sustituidas. Reabrir no reanuda el trabajo.",
        ))
    }

    @Test @Config(qualifiers = "es-rES")
    fun spanishMillionCountersUseManyPluralForms() {
        val resources = RuntimeEnvironment.getApplication().resources
        val count = 1_000_000
        assertEquals("1000000 preguntas o bloqueos pueden recibir una respuesta dirigida.",
            resources.getQuantityString(R.plurals.workspace_coordination_live, count, count))
        assertEquals("1000000 intentos de respuesta tienen resultado incierto. No reenviar automáticamente.",
            resources.getQuantityString(R.plurals.workspace_coordination_uncertain, count, count))
        assertEquals("1000000 solicitudes históricas o sustituidas. Reabrir no reanuda el trabajo.",
            resources.getQuantityString(R.plurals.workspace_coordination_historical, count, count))
    }

    private fun assertQuantities(resource: Int, expected: List<String>) {
        val resources = RuntimeEnvironment.getApplication().resources
        expected.forEachIndexed { count, text ->
            assertEquals("${resources.getResourceEntryName(resource)} at $count", text,
                resources.getQuantityString(resource, count, count))
        }
    }
}

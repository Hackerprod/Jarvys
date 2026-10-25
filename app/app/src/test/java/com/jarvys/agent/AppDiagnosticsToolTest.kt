package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppDiagnosticsToolTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun tool(session: String = "chat",
                     read: (String?, Int, Boolean) -> JSONObject = { _, _, _ -> JSONObject().put("records", JSONArray()) }) =
        AppDiagnosticsTool(session, read)

    @Test fun productionConstructorRegistersTheDiagnosticsTool() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(AppDiagnosticsTool.NAME, AppDiagnosticsTool(context, "chat").declaration().name)
    }

    @Test fun readsExactSelectionAndReportDisclosesLimits() {
        val calls = mutableListOf<Triple<String?, Int, Boolean>>()
        val tool = tool(read = { i, n, frames -> calls.add(Triple(i, n, frames)); JSONObject().put("status", "ok") })
        val result = tool.execute(mapOf("action" to "report", "id" to id), CancellationToken.uncancellable())
        assertTrue(result.success)
        assertTrue(result.completeContentRequired)
        assertTrue(AppDiagnosticsTool.GUIDANCE.contains("retry with a smaller limit"))
        assertTrue(AppDiagnosticsTool.GUIDANCE.contains("do not repeat unchanged batched calls"))
        assertEquals(listOf(Triple(id, 1, true)), calls)
        assertTrue(result.content.contains("user_shareable_diagnostic_metadata"))
        assertTrue(result.content.contains("Missing records do not prove"))
        assertFalse(tool.canDelegate())
    }

    @Test fun localReadReturnsEvidenceWithoutAnAdditionalPolicyChoice() {
        val tool = tool(read = { _, _, _ -> JSONObject().put("local", "evidence") })
        val result = tool.execute(mapOf("action" to "list"), CancellationToken.uncancellable())
        assertTrue(result.success)
        assertTrue(result.content.contains("evidence"))
    }

    @Test fun invalidArgumentsAndBackgroundScopesNeverRead() {
        var reads = 0
        val reader: (String?, Int, Boolean) -> JSONObject = { _, _, _ -> reads++; JSONObject() }
        val tool = tool(read = reader)
        val invalid = listOf(emptyMap(), mapOf("action" to "delete"), mapOf("action" to "list", "path" to "/private"),
            mapOf("action" to "read", "id" to "../../secret"), mapOf("action" to "list", "limit" to 1.5),
            mapOf("action" to "list", "limit" to 11), mapOf("action" to "report", "id" to id, "limit" to 1),
            mapOf("action" to "list", "id" to id))
        invalid.forEach { assertFalse(tool.execute(it, CancellationToken.uncancellable()).success) }
        assertFalse(tool(session = "", read = reader).execute(mapOf("action" to "list"), CancellationToken.uncancellable()).success)
        assertFalse(tool.execute(mapOf("action" to "list"), CancellationToken.crewChild()).success)
        assertEquals(0, reads)
    }

    @Test fun stoppedBeforeAndDuringReadNeverReturnsData() {
        val token = CancellationToken.cancellable().also { it.cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            tool().execute(mapOf("action" to "list"), token)
        }
        val running = CancellationToken.cancellable()
        val tool = tool(read = { _, _, _ -> running.cancel(); JSONObject() })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            tool.execute(mapOf("action" to "list"), running)
        }
    }
}

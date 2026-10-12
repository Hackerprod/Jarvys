package com.jarvys.agent

import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.AutonomyPolicy
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
    private fun tool(policy: NativeActionPolicy = NativeActionPolicy.inMemory(), session: String = "chat",
                     read: (String?, Int, Boolean) -> JSONObject = { _, _, _ -> JSONObject().put("records", JSONArray()) },
                     ask: (com.jarvys.agent.connectors.ApprovalSummary, CancellationToken) -> ApprovalDecision = { _, _ -> error("Unexpected Ask") }) =
        AppDiagnosticsTool(session, policy, read, ask, "Read diagnostics", "Selected metadata enters configured model")
    @Test fun productionConstructorUsesLocalizedPolicyResources() {
        val base = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(R.string.native_permission_diagnostics_read,
            NativePermissionsActivity.operationLabel(NativeActionPolicy.Operation.DIAGNOSTICS_READ))
        val texts = mutableListOf<String>()
        for (language in listOf("en", "es")) {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale(language))
            val context = base.createConfigurationContext(config)
            val title = context.getString(R.string.native_permission_diagnostics_read)
            val disclosure = context.getString(R.string.diagnostics_policy_disclosure)
            assertTrue(title.isNotBlank()); assertTrue(disclosure.isNotBlank())
            assertTrue(context.getString(R.string.diagnostics_policy_latest).isNotBlank())
            assertTrue(context.getString(R.string.diagnostics_policy_id).isNotBlank())
            assertEquals(AppDiagnosticsTool.NAME, AppDiagnosticsTool(context, "chat").declaration().name)
            texts.add(title + disclosure)
        }
        assertNotEquals(texts[0], texts[1])
    }
    @Test @Config(sdk = [28]) fun savedLanguageOverridesApplicationContextSystemLanguage() {
        val base = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val previousLocale = java.util.Locale.getDefault()
        val previousChoice = AppLanguageRuntime.current(base)
        try {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale.ENGLISH)
            val english = base.createConfigurationContext(config)
            AppLanguageRuntime.select(english, AppLanguageChoice.SPANISH)
            val tool = AppDiagnosticsTool(english, "chat")
            val title = AppDiagnosticsTool::class.java.getDeclaredField("title").apply { isAccessible = true }.get(tool)
            val disclosure = AppDiagnosticsTool::class.java.getDeclaredField("disclosure").apply { isAccessible = true }.get(tool)
            assertEquals("Leer diagnósticos locales de la app", title)
            assertTrue(disclosure.toString().startsWith("Compartir los metadatos"))
        } finally {
            AppLanguageRuntime.select(base, previousChoice)
            java.util.Locale.setDefault(previousLocale)
        }
    }
    @Test fun allowReadsExactSelectionWithoutPromptAndReportDisclosesLimits() {
        val calls = mutableListOf<Triple<String?, Int, Boolean>>()
        val tool = tool(read = { i, n, frames -> calls.add(Triple(i,n,frames)); JSONObject().put("status", "ok") })
        val result = tool.execute(mapOf("action" to "report", "id" to id), CancellationToken.uncancellable())
        assertTrue(result.success)
        assertTrue(result.completeContentRequired)
        assertTrue(AppDiagnosticsTool.GUIDANCE.contains("retry with a smaller limit"))
        assertTrue(AppDiagnosticsTool.GUIDANCE.contains("do not repeat unchanged batched calls"))
        assertEquals(listOf(Triple(id,1,true)), calls)
        assertTrue(result.content.contains("user_shareable_diagnostic_metadata"))
        assertTrue(result.content.contains("Missing records do not prove"))
        assertFalse(tool.canDelegate())
    }
    @Test fun denyNeverReadsAndAskRequiresOneApproval() {
        var reads = 0; var asks = 0
        val policy = NativeActionPolicy.inMemory(AutonomyPolicy.DENY)
        val tool = tool(policy, read = { _,_,_ -> reads++; JSONObject() }, ask = { _,_ -> asks++; ApprovalDecision.APPROVED })
        assertFalse(tool.execute(mapOf("action" to "list"), CancellationToken.uncancellable()).success)
        assertEquals(0, reads); assertEquals(0, asks)
        policy.set(NativeActionPolicy.Operation.DIAGNOSTICS_READ, AutonomyPolicy.ASK)
        assertTrue(tool.execute(mapOf("action" to "list", "limit" to 2), CancellationToken.uncancellable()).success)
        assertEquals(1, reads); assertEquals(1, asks)
    }
    @Test fun changeDuringAskOrReadPreventsExposure() {
        for (duringAsk in listOf(true, false)) {
            val policy = NativeActionPolicy.inMemory(if(duringAsk) AutonomyPolicy.ASK else AutonomyPolicy.ALLOW)
            var reads=0
            val tool=tool(policy, read={_,_,_->reads++; policy.set(NativeActionPolicy.Operation.DIAGNOSTICS_READ, AutonomyPolicy.DENY); JSONObject().put("marker","must not expose")},
                ask={_,_->policy.set(NativeActionPolicy.Operation.DIAGNOSTICS_READ, AutonomyPolicy.DENY);ApprovalDecision.APPROVED})
            val result=tool.execute(mapOf("action" to "list"),CancellationToken.uncancellable())
            assertFalse(result.success); assertFalse(result.content.contains("must not expose"))
            assertEquals(if(duringAsk) 0 else 1, reads)
        }
    }
    @Test fun invalidArgumentsAndBackgroundScopesNeverRead() {
        var reads=0
        val reader: (String?,Int,Boolean)->JSONObject={_,_,_->reads++;JSONObject()}
        val tool=tool(read=reader)
        val invalid=listOf(emptyMap(),mapOf("action" to "delete"),mapOf("action" to "list","path" to "/private"),
            mapOf("action" to "read","id" to "../../secret"),mapOf("action" to "list","limit" to 1.5),
            mapOf("action" to "list","limit" to 11),mapOf("action" to "report","id" to id,"limit" to 1),
            mapOf("action" to "list","id" to id))
        invalid.forEach { assertFalse(tool.execute(it,CancellationToken.uncancellable()).success) }
        listOf("",com.jarvys.agent.proactive.ProactiveConversation.SESSION_ID,
            com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID).forEach { session ->
            assertFalse(tool(session=session,read=reader).execute(mapOf("action" to "list"),CancellationToken.uncancellable()).success)
        }
        assertFalse(tool.execute(mapOf("action" to "list"),CancellationToken.crewChild()).success)
        assertEquals(0,reads)
    }
    @Test fun stoppedBeforeAndDuringReadNeverReturnsData() {
        val token=CancellationToken.cancellable();token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { tool().execute(mapOf("action" to "list"),token) }
        val running=CancellationToken.cancellable()
        val tool=tool(read={_,_,_->running.cancel();JSONObject()})
        assertThrows(java.util.concurrent.CancellationException::class.java) { tool.execute(mapOf("action" to "list"),running) }
    }
}

package com.jarvys.agent.modules

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.junit.Assert.*
import org.junit.Test

class ModuleEffectAdmissionTest {
    private val manifest = """{"schemaVersion":1,"id":"com.test.effect","version":"1.0.0","name":"Effect test",
        "artifactTypes":[],"skills":[],"capabilities":[],"tools":[
        {"name":"write_item","description":"Write","inputSchema":{"type":"object","properties":{}},
        "access":"write","handler":"logic/tools.js#write"}]}"""

    private fun tool(runtime: ModuleRuntime, read: Boolean = false): com.jarvys.agent.ModuleTool {
        val modules = ModuleRegistry.fromManifests(listOf(if (read) manifest.replace("\"access\":\"write\"", "\"access\":\"read\"") else manifest))
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            private var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }, { true })
        val definition = modules.installConnectorDefinitions(registry, runtime).single()
        registry.connect(definition.id)
        return modules.tools(registry, runtime).single()
    }

    @Test fun writerPrecedesHandlerAndGenericSuccessDoesNotInventCompletion() {
        var durable = false
        var handlers = 0
        val tool = tool(ModuleRuntime { request, _, _ ->
            assertTrue(durable)
            assertNotNull(request.authorizeHandler)
            request.authorizeHandler!!.invoke()
            handlers++
            CoreToolResult.success("unverified handler claim")
        })
        val call = EffectInvocation { durable = true }
        val result = tool.execute(emptyMap(), CancellationToken.uncancellable(), null, call)
        assertEquals(1, handlers)
        assertTrue(call.admitted())
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, call.resolve(result, true).outcome)
    }

    @Test fun failedDurableAdmissionNeverCallsHandler() {
        var handlers = 0
        val tool = tool(ModuleRuntime { _, _, _ -> handlers++; CoreToolResult.success("unexpected") })
        val call = EffectInvocation { error("persistence unavailable") }
        assertTrue(runCatching { tool.execute(emptyMap(), CancellationToken.uncancellable(), null, call) }.isFailure)
        assertEquals(0, handlers)
    }
    @Test fun localResultMetadataSurvivesConnectorEnvelope() {
        val samples = listOf(CoreToolResult.finish("done"), CoreToolResult.complete("full"),
            CoreToolResult.preview("preview", "preview-id"), CoreToolResult.persisted("stored", "local-receipt"))
        for (source in samples) {
            val tool = tool(ModuleRuntime { _, _, _ -> source })
            val call = EffectInvocation { }
            val result = tool.execute(emptyMap(), CancellationToken.uncancellable(), null, call)
            assertEquals(source.finishRun, result.finishRun)
            assertEquals(source.completeContentRequired, result.completeContentRequired)
            assertEquals(source.previewId, result.previewId)
            assertEquals(source.persistenceEvidence, result.persistenceEvidence)
            assertEquals(EffectInvocation.Outcome.UNCERTAIN, result.effectReceipt.outcome)
        }
    }
    @Test fun successfulReadUsesAdministrativeReceiptWithoutWriteAdmission() {
        val tool = tool(ModuleRuntime { _, _, _ -> CoreToolResult.success("read") }, read = true)
        val call = EffectInvocation { fail("READ cannot admit a write") }
        val result = tool.execute(emptyMap(), CancellationToken.uncancellable(), null, call)
        assertNull(result.effectReceipt)
        assertTrue(call.ownsReadCompletion(result.administrativeReceipt))
        assertFalse(call.admitted())
    }
}

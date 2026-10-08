package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class ConnectorArtifactScopeTest {
    private fun tool(): CoreConnectorTool {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "connector-scope-${UUID.randomUUID()}"
        LocalRunStore(context).appendConversationMessage(session, "user", "Download the requested file")
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation = error("Read only")
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
                ConnectorArtifactAccess.publish(byteArrayOf(0, 1, 2), "scoped.bin", "application/octet-stream", token)
        }
        val operation = ConnectorOperation("download", "Download into current main chat", JSONObject().put("type", "object"))
        val definition = ConnectorDefinition("test_cloud", "Cloud test", "1", "Hermetic scope test",
            operations = listOf(operation), runtime = runtime)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, ApprovalGate(presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) = error("Read operation requested approval")
            override fun update(id: String, decision: ApprovalDecision) = Unit
        }))
        registry.register(definition); registry.connect(definition.id)
        return CoreConnectorTool(registry, definition, operation).withConversation(context, session)
    }
    @Test fun directMainConnectorCarriesExplicitChatBinding() {
        assertTrue(JSONObject(tool().execute(emptyMap(), CancellationToken.uncancellable()).content).getBoolean("attached"))
    }
    @Test fun genericDelegatedScopeDropsChatBindingEvenWithParentCancellationToken() {
        val scoped = CoreToolRegistry(listOf(tool())).forDelegatedAgent()
        val delegated = scoped.handlers().single()
        assertThrows(IllegalStateException::class.java) { delegated.execute(emptyMap(), CancellationToken.uncancellable()) }
    }
    @Test fun requesterAndAttachmentRestrictedViewsCannotRestoreParentFiles() {
        assertThrows(IllegalStateException::class.java) {
            tool().withRequester("Delegated worker", "blue").execute(emptyMap(), CancellationToken.uncancellable())
        }
        val restricted = CoreToolRegistry(listOf(tool())).withoutAttachments().handlers().single()
        assertThrows(IllegalStateException::class.java) { restricted.execute(emptyMap(), CancellationToken.uncancellable()) }
    }
    @Test fun paginatedConnectorResultsRequireCompleteContextSoRowsAndCursorStayTogether() {
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation = error("Read only")
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
                JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("id", "file-1"))).put("next_page_token", "opaque")
        }
        val operation = ConnectorOperation("list", "List a complete page", JSONObject().put("type", "object"))
        val definition = ConnectorDefinition("test_pages", "Pages", "1", "Hermetic page test", operations = listOf(operation), runtime = runtime)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, ApprovalGate.INSTANCE)
        registry.register(definition); registry.connect(definition.id)
        val result = CoreConnectorTool(registry, definition, operation).execute(emptyMap(), CancellationToken.uncancellable())
        assertTrue(result.completeContentRequired)
        assertEquals("opaque", JSONObject(result.content).getString("next_page_token"))
        assertEquals("file-1", JSONObject(result.content).getJSONArray("items").getJSONObject(0).getString("id"))
    }

}

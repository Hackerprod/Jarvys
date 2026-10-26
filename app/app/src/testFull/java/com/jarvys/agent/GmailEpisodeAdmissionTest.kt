package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.connectors.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Gmail mutation producer + CoreConnectorTool + recorder/Room; only provider transport is fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GmailEpisodeAdmissionTest {
    @get:Rule val fixture = ChatTestStore()

    private fun scenario(skipped: Boolean = false, unknown: Boolean = false, failAdmission: Boolean = false) {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val bridge = ChatDatabaseBridge.getInstance(context)
        val conversation = "gmail-real-producer"
        val owner = "gmail-request"
        bridge.ensureConversationSync(conversation)
        fun evidence() = bridge.personalAgentEpisodesForSourceMessagesSync(conversation, listOf(owner)).single().let {
            bridge.personalAgentEpisodeNarrationInputSync(it.id)!!.evidence.single { row -> row.refKind == "tool_call" }
        }
        var mutations = 0
        var reads = 0
        var messageReads = 0
        val api = object : GoogleRestAuthorization {
            override fun isScopeGranted(scope: String) = true
            override fun verifyGmailAccount(capability: String, expectedAccount: String?, token: CancellationToken,
                epoch: Long, beforeDispatch: (() -> Unit)?): String {
                val response = requestCancellable(capability, "GET", "${GoogleRestEndpoints.GMAIL}/users/me/profile",
                    token = token, expectedAuthorizationEpoch = epoch, beforeDispatch = beforeDispatch)
                return JSONObject(response.body).getString("emailAddress")
            }
            override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
                val row = evidence()
                if (method == "GET") {
                    reads++
                    assertEquals(if (mutations == 0) "intent" else "started", row.outcome)
                    if (url.endsWith("/profile")) return GoogleHttpResponse(200, """{"emailAddress":"fixture@example.test"}""")
                    messageReads++
                    val labels = if (mutations > 0 && !unknown) listOf("TRASH") else listOf("INBOX")
                    return GoogleHttpResponse(200, JSONObject().put("id", "m1").put("threadId", "t1")
                        .put("historyId", if (skipped && messageReads > 1) "2" else "1")
                        .put("labelIds", JSONArray(labels)).toString())
                }
                assertEquals("POST", method)
                assertTrue(url.endsWith("/m1/trash"))
                assertEquals("started", row.outcome)
                assertEquals("allow", row.authorization)
                assertTrue(JSONObject(row.factsJson).getString("classification") == "WRITE")
                mutations++
                return if (unknown) GoogleHttpResponse(503, "{}") else GoogleHttpResponse(200, "{}")
            }
        }
        val contacts = object : ContactsGateway {
            override fun search(query: String, limit: Int) = emptyList<ContactRecord>()
            override fun find(contactId: Long): ContactRecord? = null
        }
        val runtime = GmailConnector(api, contacts, { false }, { false })
        val definition = GmailConnector.definition(runtime)
        val operation = definition.operations.single { it.name == GmailManagement.TRASH }
        val policies = InMemoryConnectorAutonomyStore()
        policies.setPolicy(definition.id, operation.name, AutonomyPolicy.ALLOW)
        val connectors = ConnectorRegistry.createForTestsWithAutonomy(object : ConnectorConnectionPreferences {
            var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }, { true }, ApprovalGate.INSTANCE, policies, NoopAutonomyActionNotifier)
        connectors.register(definition); connectors.connect(definition.id)
        val tool = CoreConnectorTool(connectors, definition, operation)
        val registry = CoreToolRegistry(listOf(tool)).withInvocationObserver(EpisodeRecorder(context, conversation, owner))
        val token = CancellationToken.cancellable()
        runtime.beginAgentRun(token.generation(), "Trash the explicit selected message")
        val supplied = if (failAdmission) EffectInvocation { error("durable writer unavailable") } else null
        val result = registry.invoke(tool.declaration().name, mapOf("ids" to listOf("m1")), token, null, "gmail-call", supplied)
        assertTrue(reads > 0)
        assertEquals(if (skipped || failAdmission) 0 else 1, mutations)
        assertEquals(when { failAdmission || skipped -> "not_executed"; unknown -> "uncertain"; else -> "ok" }, evidence().outcome)
        if (failAdmission) assertFalse(result.success)
        assertFalse(evidence().factsJson.contains("fixture@example.test"))
        runtime.endAgentRun(token.generation())
    }

    @Test fun verifiedTrashPersistsStartedOnlyAtMutationAndFinishesOk() = scenario()
    @Test fun preflightChangedAllTargetsIsExplicitNoDispatchNotSuccess() = scenario(skipped = true)
    @Test fun unknownProviderResultCannotBecomeOk() = scenario(unknown = true)
    @Test fun failingAdmissionWriterAllowsReadsButNoMutation() = scenario(failAdmission = true)
}

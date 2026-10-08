package com.jarvys.agent.connectors

import android.Manifest
import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class GoogleWorkspaceConnectorTest {
    private class FakeAuthorization : GoogleRestAuthorization {
        val requests = mutableListOf<Triple<String, String, String?>>()
        val responses = ArrayDeque<GoogleHttpResponse>()
        val grants = GoogleOAuthProtocol.ALLOWED_SCOPES.toMutableSet()
        override fun isScopeGranted(scope: String) = scope in grants
        override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
            requests += Triple(scope, url, body)
            return if (responses.isEmpty()) GoogleHttpResponse(200, """{"id":"accepted-id","message":{"id":"message-id"}}""") else responses.removeFirst()
        }
    }

    private class FakeContacts(private val rows: List<ContactRecord> = emptyList()) : ContactsGateway {
        override fun search(query: String, limit: Int) = rows.take(limit)
        override fun find(contactId: Long): ContactRecord? = rows.firstOrNull { it.id == contactId }
    }

    @Test fun gmailMultipartBase64HtmlAndAttachmentsAreParsedToBoundedUntrustedText() {
        val plain = GoogleOAuthProtocol.base64Url("Hello from plain text".toByteArray(StandardCharsets.UTF_8))
        val html = GoogleOAuthProtocol.base64Url("<html><script>secret()</script><p>Hello <b>HTML</b>&amp; welcome</p></html>"
            .toByteArray(StandardCharsets.UTF_8))
        val payload = JSONObject().put("mimeType", "multipart/mixed").put("headers", org.json.JSONArray()
            .put(JSONObject().put("name", "Subject").put("value", "Quarterly note"))
            .put(JSONObject().put("name", "From").put("value", "Sender <sender@example.com>"))
            .put(JSONObject().put("name", "To").put("value", "me@example.com"))
            .put(JSONObject().put("name", "Date").put("value", "today")))
            .put("parts", org.json.JSONArray()
                .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", plain)))
                .put(JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", html)))
                .put(JSONObject().put("mimeType", "application/pdf").put("filename", "report.pdf")
                    .put("body", JSONObject().put("attachmentId", "metadata-only").put("size", 20))))
        val row = GmailContent.messageRow(JSONObject().put("id", "msg-1").put("threadId", "thread-1")
            .put("snippet", "external snippet").put("payload", payload))
        assertEquals("Quarterly note", row.getString("subject"))
        assertEquals("Hello from plain text", row.getString("text"))
        assertEquals("report.pdf", row.getJSONArray("attachments").getJSONObject(0).getString("name"))
        assertEquals("metadata-only", row.getJSONArray("attachments").getJSONObject(0).getString("attachmentId"))

        val htmlOnly = JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", html))
        val text = GmailContent.messageRow(JSONObject().put("payload", htmlOnly)).getString("text")
        assertTrue(text.contains("Hello HTML"))
        assertTrue(text.contains("welcome"))
        assertTrue(text.contains("&"))
        assertFalse(text.contains("secret()"))
    }

    @Test fun gmailBodyTruncatesAndEnvelopeLabelsMailUntrusted() {
        val data = GoogleOAuthProtocol.base64Url("z".repeat(GoogleApiLimits.MAX_TEXT_CHARS + 50).toByteArray())
        val row = GmailContent.messageRow(JSONObject().put("payload", JSONObject().put("mimeType", "text/plain")
            .put("body", JSONObject().put("data", data))))
        assertTrue(row.getBoolean("truncated"))
        val envelope = ConnectorResultEnvelope.bounded("gmail.message", org.json.JSONArray().put(row), 1,
            mapOf("text" to GoogleApiLimits.MAX_TEXT_CHARS), initiallyTruncated = row.optBoolean("truncated"), maxBytes = 20 * 1024)
        assertTrue(envelope.getBoolean("untrusted_content"))
        assertTrue(envelope.getBoolean("truncated"))
    }

    @Test fun gmailCreateDraftRequiresAskAndApprovalShowsRecipientsSubjectAndBody() {
        val auth = FakeAuthorization()
        auth.responses += GoogleHttpResponse(200, """{"id":"draft-1","message":{"id":"msg-1"}}""")
        val definition = GmailConnector(auth, FakeContacts(), { false }, { false }).let(GmailConnector::definition)
        val summaries = mutableListOf<ApprovalSummary>()
        val gate = autoApprovingGate(summaries::add)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, gate)
        registry.register(definition)
        registry.connect(GmailConnector.ID)
        val op = definition.operations.first { it.name == GmailConnector.CREATE_DRAFT }
        val result = registry.invoke(definition, op, JSONObject().put("to", "owner@example.com")
            .put("subject", "Plan").put("body", "Private draft body"), CancellationToken.uncancellable())
        assertEquals("draft_created", result.getJSONArray("items").getJSONObject(0).getString("status"))
        assertTrue(summaries.single().lines.any { it.contains("Private draft body") })
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, auth.requests.single().first)
        assertTrue(auth.requests.single().second.endsWith("/users/me/drafts"))
        assertTrue(auth.requests.single().third.orEmpty().contains("raw"))
    }

    @Test fun gmailDirectSendAlwaysUsesWriteApprovalAndTheSendScope() {
        val auth = FakeAuthorization()
        auth.responses += GoogleHttpResponse(200, """{"id":"sent-1","threadId":"thread-1"}""")
        val definition = GmailConnector.definition(GmailConnector(auth, FakeContacts(), { false }, { false }))
        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, autoApprovingGate(summaries::add))
        registry.register(definition)
        registry.connect(GmailConnector.ID)
        val result = registry.invoke(definition, definition.operations.first { it.name == GmailConnector.SEND_MESSAGE },
            JSONObject().put("to", "owner@example.com").put("subject", "Status").put("body", "Send body"),
            CancellationToken.uncancellable())
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().lines.any { it.contains("Send body") })
        assertEquals(GoogleOAuthProtocol.GMAIL_SEND, auth.requests.single().first)
        assertEquals("send_accepted", result.getJSONArray("items").getJSONObject(0).getString("status"))
    }

    @Test fun gmailAllowDowngradesUnverifiedRecipientsAndAllowsOnlyUserTypedSavedContactOrListedSender() {
        val auth = FakeAuthorization()
        auth.responses += GoogleHttpResponse(200, """{"id":"accepted-id","message":{"id":"message-id"}}""")
        auth.responses += GoogleHttpResponse(200, """{"id":"accepted-id","message":{"id":"message-id"}}""")
        val contacts = FakeContacts(listOf(ContactRecord(1, "Known", emails = listOf("saved@example.com"))))
        val runtime = GmailConnector(auth, contacts, { true }, { true })
        val definition = GmailConnector.definition(runtime)
        val summaries = mutableListOf<ApprovalSummary>()
        val store = InMemoryConnectorAutonomyStore()
        val gate = autoApprovingGate(summaries::add)
        val registry = ConnectorRegistry.createForTestsWithAutonomy(FakeConnectorPreferences(), { false }, gate,
            store, NoopAutonomyActionNotifier)
        registry.register(definition)
        registry.connect(GmailConnector.ID)
        val operation = definition.operations.first { it.name == GmailConnector.SEND_MESSAGE }
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
        registry.beginAgentRun(0, "Send a note to typed@example.com")

        registry.invoke(definition, operation, JSONObject().put("to", "other@example.com")
            .put("subject", "S").put("body", "B"), CancellationToken.uncancellable())
        assertEquals(1, summaries.size) // unverified recipient falls back from Allow to Ask

        registry.invoke(definition, operation, JSONObject().put("to", "typed@example.com")
            .put("subject", "S").put("body", "B"), CancellationToken.uncancellable())
        assertEquals(1, summaries.size) // user-typed recipient can use the explicit Allow policy
        assertEquals(2, auth.requests.size)
    }

    @Test fun driveSearchExportReadAndCreateUseExactScopesAndUntrustedEnvelope() {
        val auth = FakeAuthorization()
        auth.responses += GoogleHttpResponse(200, """{"files":[{"id":"file-1","name":"Plan","mimeType":"text/csv","size":"12"}]}""")
        auth.responses += GoogleHttpResponse(200, """{"id":"doc-1","name":"Doc","mimeType":"${DriveConnector.GOOGLE_DOC}","size":"12"}""")
        auth.responses += GoogleHttpResponse(200, "document text")
        auth.responses += GoogleHttpResponse(200, """{"id":"new-file","name":"created.txt","mimeType":"text/plain"}""")
        val definition = DriveConnector.definition(auth)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, autoApprovingGate())
        registry.register(definition)
        registry.connect(DriveConnector.ID)

        val found = registry.invoke(definition, definition.operations[0], JSONObject().put("query", "Plan"), CancellationToken.uncancellable())
        assertTrue(found.getBoolean("untrusted_content"))
        assertEquals(GoogleOAuthProtocol.DRIVE_READ, auth.requests[0].first)
        val read = registry.invoke(definition, definition.operations[2], JSONObject().put("id", "doc-1"), CancellationToken.uncancellable())
        assertTrue(read.getBoolean("untrusted_content"))
        assertTrue(auth.requests[2].second.contains("/export?mimeType=text%2Fplain"))

        val created = registry.invoke(definition, definition.operations[3], JSONObject().put("name", "created.txt")
            .put("mime", "text/plain").put("content", "text body"), CancellationToken.uncancellable())
        assertTrue(created.getBoolean("untrusted_content"))
        assertEquals(GoogleOAuthProtocol.DRIVE_FILE, auth.requests.last().first)
        assertTrue(auth.requests.last().third.orEmpty().contains("text body"))
        assertFalse(definition.operations[3].autonomyAllowed)
    }

    @Test fun driveEnforcesResultCountTextMimeAndOneMiBLimit() {
        assertTrue(runCatching { SafDocumentPolicy.validateMime("application/pdf") }.isFailure)
        assertTrue(runCatching { SafDocumentPolicy.validateMime("application/octet-stream") }.isFailure)
        assertTrue(runCatching {
            val definition = DriveConnector.definition(FakeAuthorization().apply {
                responses += GoogleHttpResponse(200, """{"id":"file","name":"huge","mimeType":"text/plain","size":"2000000"}""")
            })
            definition.runtime!!.invoke(DriveConnector.READ_FILE, JSONObject().put("id", "file"), CancellationToken.uncancellable())
        }.isFailure)
        val auth = FakeAuthorization()
        val runtime = DriveConnector(auth)
        assertTrue(runCatching { runtime.invoke(DriveConnector.SEARCH_FILES,
            JSONObject().put("query", "file").put("max_results", 26), CancellationToken.uncancellable()) }.isFailure)
        assertTrue(auth.requests.isEmpty())
        assertEquals(GoogleApiLimits.MAX_RESULTS, DriveConnector.searchSchema().getJSONObject("properties")
            .getJSONObject("max_results").getInt("maximum"))
    }

    private fun autoApprovingGate(onSummary: (ApprovalSummary) -> Unit = {}): ApprovalGate {
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(2_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { onSummary(summary); gate.resolve(id, ApprovalDecision.APPROVED) }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        return gate
    }
}

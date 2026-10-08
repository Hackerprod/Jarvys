package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/** Transport-contract tests: no Google account, OAuth grant, SDK, or network call is used. */
class GmailApiContractTest {
    private data class Request(val scope: String, val method: String, val url: String, val body: String?, val contentType: String)
    private class Api : GoogleRestAuthorization {
        val calls = mutableListOf<Request>()
        val replies = ArrayDeque<() -> GoogleHttpResponse>()
        var granted = true
        var epoch = 1L
        var beforeLease: (() -> Unit)? = null
        val expectedEpochs = mutableListOf<Long?>()
        override fun currentAuthorizationEpoch() = epoch
        override fun requestCancellable(scope: String, method: String, url: String, body: String?, contentType: String,
                                        token: CancellationToken, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleHttpResponse {
            expectedEpochs += expectedAuthorizationEpoch
            beforeLease?.also { beforeLease = null }?.invoke()
            return super<GoogleRestAuthorization>.requestCancellable(scope, method, url, body, contentType, token, requestHeaders, expectedAuthorizationEpoch)
        }
        override fun requestBytes(scope: String, method: String, url: String, body: ByteArray?, contentType: String,
                                  token: CancellationToken, maxResponseBytes: Int, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleBinaryResponse {
            expectedEpochs += expectedAuthorizationEpoch
            beforeLease?.also { beforeLease = null }?.invoke()
            return super<GoogleRestAuthorization>.requestBytes(scope, method, url, body, contentType, token, maxResponseBytes, requestHeaders, expectedAuthorizationEpoch)
        }
        fun respond(value: JSONObject, status: Int = 200) { replies += { GoogleHttpResponse(status, value.toString()) } }
        override fun isScopeGranted(scope: String) = granted
        override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
            calls += Request(scope, method, url, body, contentType)
            check(replies.isNotEmpty()) { "Unexpected request: $method $url" }
            return replies.removeFirst().invoke()
        }
    }
    private class Files : GoogleWorkspaceArtifactSink {
        val content = linkedMapOf<String, GoogleWorkspaceArtifact>()
        val published = mutableListOf<GoogleWorkspaceArtifact>()
        override fun publish(bytes: ByteArray, name: String, mime: String, token: CancellationToken): JSONObject {
            token.throwIfCancelled()
            published += GoogleWorkspaceArtifact(bytes.copyOf(), name, mime, GmailContent.digest(bytes))
            return JSONObject().put("artifact_id", "delivered:test").put("name", name).put("mime", mime).put("size", bytes.size)
        }
        override fun read(artifactId: String, token: CancellationToken): GoogleWorkspaceArtifact = content[artifactId] ?: error("Unapproved artifact")
    }
    private val contacts = object : ContactsGateway {
        override fun search(query: String, limit: Int) = emptyList<ContactRecord>()
        override fun find(contactId: Long): ContactRecord? = null
    }
    private fun runtime(api: Api, files: Files = Files()) = GmailConnector(api, contacts, { false }, { false }, files)
    private fun args() = JSONObject().put("to", "owner@example.com").put("subject", "Status").put("body", "Reviewed body")
    private fun message(id: String = "m1", text: String = "Draft body", subject: String = "Status"): JSONObject = JSONObject()
        .put("id", id).put("threadId", "thread1").put("snippet", "A summary")
        .put("payload", JSONObject().put("mimeType", "text/plain").put("headers", JSONArray()
            .put(header("To", "Owner <owner@example.com>"))
            .put(header("From", "Sender <sender@example.com>"))
            .put(header("Subject", subject)).put(header("Content-Type", "text/plain; charset=UTF-8")))
            .put("body", JSONObject().put("size", text.toByteArray().size).put("data", GoogleOAuthProtocol.base64Url(text.toByteArray()))))
    private fun header(name: String, value: String) = JSONObject().put("name", name).put("value", value)
    private fun draft(id: String = "d1", revision: String = "m1") = JSONObject().put("id", id).put("message", message(revision))
    private fun rawDraft(raw: String, revision: String = "m1") = JSONObject().put("id", "d1")
        .put("message", JSONObject().put("id", revision).put("threadId", "thread1").put("raw", raw))
    private fun sent() = JSONObject().put("id", "sent1").put("threadId", "thread1")
    private fun mime(payload: JSONObject) = String(GoogleOAuthProtocol.base64UrlDecode(payload.getString("raw")), StandardCharsets.UTF_8)
    private fun perform(runtime: GmailConnector, operation: String, args: JSONObject, token: CancellationToken = CancellationToken.uncancellable()): JSONObject {
        val approval = runtime.prepareWrite(operation, args, token)
        return runtime.invokePrepared(operation, approval.executionArguments, approval, token)
    }
    private fun assertFailure(fragment: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertNotNull("Expected failure containing $fragment", error)
        assertTrue("Unexpected failure: ${error?.message}", error!!.message.orEmpty().contains(fragment, true))
    }

    @Test fun catalogKeepsOriginalOrderAndRequiresApprovalForEveryNewMutation() {
        val api = Api()
        val connector = runtime(api)
        val definition = GmailConnector.definition(connector)
        assertEquals(listOf("search_messages", "get_message", "list_labels", "create_draft", "send_message"), definition.operations.take(5).map { it.name })
        for (op in definition.operations.filter { it.name in setOf("reply_message", "update_draft", "send_draft") }) {
            assertTrue(op.write); assertFalse(op.autonomyAllowed)
            assertFailure("approval") { connector.invoke(op.name, JSONObject(), CancellationToken.uncancellable()) }
        }
        assertTrue(definition.connectionAccessGranted!!.invoke())
        api.granted = false
        assertFalse(definition.connectionAccessGranted!!.invoke())
        assertFalse(definition.operations.any { it.name.contains("delete") || it.name.contains("share") })
    }

    @Test fun directSendHasRawAtJsonRootAndUsesOnlyTheReviewedSnapshot() {
        val api = Api().apply { respond(sent()) }
        val connector = runtime(api)
        val original = args()
        val prepared = connector.prepareWrite(GmailConnector.SEND_MESSAGE, original, CancellationToken.uncancellable())
        original.put("to", "attacker@example.com").put("body", "Changed")
        prepared.executionArguments.put("to", "attacker@example.com").put("body", "Changed")
        val result = connector.invokePrepared(GmailConnector.SEND_MESSAGE, original, prepared, CancellationToken.uncancellable())
        val call = api.calls.single()
        assertEquals(GoogleOAuthProtocol.GMAIL_SEND, call.scope)
        assertEquals("POST", call.method)
        assertEquals("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", call.url)
        assertEquals("application/json", call.contentType)
        val json = JSONObject(call.body!!)
        assertTrue(json.has("raw")); assertFalse(json.has("message"))
        assertTrue(mime(json).contains("To: owner@example.com"))
        assertFalse(mime(json).contains("attacker"))
        assertEquals("send_accepted", result.getJSONArray("items").getJSONObject(0).getString("status"))
    }

    @Test fun createDraftWrapsRawInsideMessageAndReturnsBothDraftAndRevisionIds() {
        val api = Api().apply { respond(draft()) }
        val result = perform(runtime(api), GmailConnector.CREATE_DRAFT, args())
        val call = api.calls.single()
        assertEquals("POST", call.method)
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, call.scope)
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts", call.url)
        val payload = JSONObject(call.body!!)
        assertFalse(payload.has("raw")); assertTrue(payload.getJSONObject("message").has("raw"))
        assertEquals("d1", result.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("m1", result.getJSONArray("items").getJSONObject(0).getString("revision"))
    }

    @Test fun replyUsesVerifiedParentThreadMessageIdReferencesSubjectAndReplyTo() {
        val parent = message()
        parent.getJSONObject("payload").getJSONArray("headers").put(header("Message-ID", "<parent@example.com>"))
            .put(header("References", "<root@example.com> <older@example.com>"))
            .put(header("Reply-To", "Reply here <replies@example.com>"))
        val api = Api().apply { respond(parent); respond(sent()) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.REPLY_MESSAGE,
            JSONObject().put("message_id", "m1").put("body", "Approved reply"), CancellationToken.uncancellable())
        assertTrue(prepared.approval.lines.any { it.contains("replies@example.com") })
        assertTrue(prepared.approval.lines.any { it.contains("m1") && it.contains("thread1") })
        connector.invokePrepared(GmailConnector.REPLY_MESSAGE, JSONObject().put("threadId", "wrong"), prepared, CancellationToken.uncancellable())
        assertEquals("GET", api.calls[0].method)
        assertTrue(api.calls[0].url.startsWith("${GoogleRestEndpoints.GMAIL}/users/me/messages/m1?format=metadata&"))
        assertTrue(api.calls[0].url.contains("metadataHeaders=Message-ID"))
        val json = JSONObject(api.calls[1].body!!)
        assertEquals("thread1", json.getString("threadId"))
        assertFalse(json.has("message"))
        val mime = mime(json)
        assertTrue(mime.contains("To: replies@example.com"))
        assertTrue(mime.contains("Subject: Status"))
        assertTrue(mime.contains("In-Reply-To: <parent@example.com>"))
        assertTrue(mime.replace("\r\n ", " ").contains("References: <root@example.com> <older@example.com> <parent@example.com>"))
    }

    @Test fun replyRejectsMissingMessageIdAndCannotChangeParentSubject() {
        val api = Api().apply { respond(message()) }
        assertFailure("Message-ID") { perform(runtime(api), GmailConnector.REPLY_MESSAGE, JSONObject().put("message_id", "m1").put("body", "No")) }
        assertEquals(1, api.calls.size)
        val parent = message().apply { getJSONObject("payload").getJSONArray("headers").put(header("Message-ID", "<parent@example.com>")) }
        val changed = Api().apply { respond(parent) }
        assertFailure("subject") { perform(runtime(changed), GmailConnector.CREATE_DRAFT, args().put("reply_to_message_id", "m1").put("subject", "Changed")) }
        assertEquals(1, changed.calls.size)
    }

    @Test fun searchUsesOneEscapedQueryPageAndReturnsBoundedOpaqueCursor() {
        val api = Api().apply {
            respond(JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "m1"))).put("nextPageToken", "NEXT+/=").put("resultSizeEstimate", 100))
            respond(message())
        }
        val result = runtime(api).invoke(GmailConnector.SEARCH_MESSAGES,
            JSONObject().put("query", "from:someone@example.com has:attachment").put("max_results", 2).put("page_token", "PAGE+/="), CancellationToken.uncancellable())
        assertEquals("GET", api.calls[0].method)
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/messages?q=from%3Asomeone%40example.com%20has%3Aattachment&maxResults=2&pageToken=PAGE%2B%2F%3D", api.calls[0].url)
        assertTrue(api.calls[1].url.contains("format=metadata"))
        assertEquals(2, api.calls.size)
        assertEquals("NEXT+/=", result.getString("next_page_token")); assertTrue(result.getBoolean("has_more"))
        assertTrue(result.getBoolean("untrusted_content")); assertEquals(100, result.getInt("result_size_estimate"))
    }

    @Test fun oversizedCursorAndResultLimitFailBeforeAnyRequest() {
        val api = Api(); val connector = runtime(api)
        assertFailure("page_token") { connector.invoke(GmailConnector.SEARCH_MESSAGES, JSONObject().put("query", "").put("page_token", "x".repeat(2049)), CancellationToken.uncancellable()) }
        assertFailure("max_results") { connector.invoke(GmailConnector.SEARCH_MESSAGES, JSONObject().put("query", "").put("max_results", 26), CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun threadReadBoundsMessagesAndMarksTruncationWithoutFabricatingCursor() {
        val api = Api().apply { respond(JSONObject().put("id", "thread1").put("messages", JSONArray().put(message("m1")).put(message("m2")))) }
        val result = runtime(api).invoke(GmailConnector.GET_THREAD, JSONObject().put("id", "thread1").put("max_results", 1), CancellationToken.uncancellable())
        assertEquals("GET", api.calls.single().method)
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/threads/thread1?format=full", api.calls.single().url)
        assertEquals(1, result.getJSONArray("items").length()); assertEquals(2, result.getInt("message_count"))
        assertTrue(result.getBoolean("truncated")); assertFalse(result.has("next_page_token"))
    }

    @Test fun draftListAndGetUseComposeScopeAndExposeStableDraftIdWithRevision() {
        val api = Api().apply {
            respond(JSONObject().put("drafts", JSONArray().put(draft())).put("nextPageToken", "next"))
            respond(draft())
        }
        val connector = runtime(api)
        val list = connector.invoke(GmailConnector.LIST_DRAFTS, JSONObject().put("query", "subject:Status").put("max_results", 4).put("page_token", "previous"), CancellationToken.uncancellable())
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts?q=subject%3AStatus&maxResults=4&pageToken=previous", api.calls[0].url)
        assertEquals("m1", list.getJSONArray("items").getJSONObject(0).getString("revision"))
        val read = connector.invoke(GmailConnector.GET_DRAFT, JSONObject().put("id", "d1"), CancellationToken.uncancellable())
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts/d1?format=full", api.calls[1].url)
        assertTrue(api.calls.all { it.method == "GET" && it.scope == GoogleOAuthProtocol.GMAIL_COMPOSE })
        assertEquals("d1", read.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("Draft body", read.getJSONArray("items").getJSONObject(0).getString("text"))
        assertFalse(read.toString().contains("\"raw\""))
    }

    @Test fun draftUpdateUsesPutExactReviewedReplacementAndRequiresUnchangedRevision() {
        val api = Api().apply { respond(draft()); respond(rawDraft("b2xk")); respond(draft(revision = "m2")) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.UPDATE_DRAFT, args().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable())
        assertTrue(prepared.approval.lines.any { it.contains("previous attachments") })
        val result = connector.invokePrepared(GmailConnector.UPDATE_DRAFT, JSONObject().put("id", "wrong"), prepared, CancellationToken.uncancellable())
        val call = api.calls.last()
        assertEquals("PUT", call.method); assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts/d1", call.url)
        assertEquals("d1", JSONObject(call.body!!).getString("id"))
        assertTrue(mime(JSONObject(call.body).getJSONObject("message")).contains("To: owner@example.com"))
        assertEquals("m2", result.getJSONArray("items").getJSONObject(0).getString("revision"))
    }

    @Test fun changedDraftRevisionAfterApprovalPreventsUpdateOrSend() {
        val api = Api().apply { respond(draft()); respond(rawDraft("bmV3", "m2")) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.UPDATE_DRAFT, args().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable())
        assertFailure("changed") { connector.invokePrepared(GmailConnector.UPDATE_DRAFT, JSONObject(), prepared, CancellationToken.uncancellable()) }
        assertEquals(2, api.calls.size); assertTrue(api.calls.all { it.method == "GET" })
        val old = Api().apply { respond(draft(revision = "m2")) }
        assertFailure("revision") { runtime(old).prepareWrite(GmailConnector.SEND_DRAFT, JSONObject().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable()) }
        assertEquals(1, old.calls.size)
    }

    @Test fun sendDraftPostsFrozenRawWithIdAndChecksRawDigestImmediatelyBeforeDispatch() {
        val raw = GmailContent.rawMessage(EmailDraft("owner@example.com", "Status", "Draft body", "", ""))
        val api = Api().apply { respond(draft()); respond(rawDraft(raw)); respond(rawDraft(raw)); respond(sent()) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_DRAFT, JSONObject().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable())
        assertTrue(prepared.approval.lines.any { it.contains("Draft body") })
        connector.invokePrepared(GmailConnector.SEND_DRAFT, JSONObject(), prepared, CancellationToken.uncancellable())
        assertEquals(listOf("GET", "GET", "GET", "POST"), api.calls.map { it.method })
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts/send", api.calls.last().url)
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, api.calls.last().scope)
        val payload = JSONObject(api.calls.last().body!!)
        assertEquals("d1", payload.getString("id")); assertEquals(raw, payload.getJSONObject("message").getString("raw"))
        assertEquals("thread1", payload.getJSONObject("message").getString("threadId"))
    }

    @Test fun sameRevisionButChangedRawIsRejectedBeforeSending() {
        val raw = GmailContent.rawMessage(EmailDraft("owner@example.com", "Status", "Draft body", "", ""))
        val api = Api().apply { respond(draft()); respond(rawDraft(raw)); respond(rawDraft("bXV0YXRlZA")) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_DRAFT, JSONObject().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable())
        assertFailure("changed") { connector.invokePrepared(GmailConnector.SEND_DRAFT, JSONObject(), prepared, CancellationToken.uncancellable()) }
        assertTrue(api.calls.all { it.method == "GET" })
    }

    @Test fun canceledApprovalNeverDispatchesMutation() {
        val api = Api(); val connector = runtime(api)
        val token = CancellationToken.cancellable()
        val preparation = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), token)
        token.cancel()
        assertTrue(runCatching { connector.invokePrepared(GmailConnector.SEND_MESSAGE, args(), preparation, token) }.exceptionOrNull() is CancellationException)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun ambiguousSendCannotReuseApprovalOrReprepareSameContentAfterDisconnect() {
        val api = Api().apply { replies += { throw IOException("lost response") } }
        val connector = runtime(api)
        val preparation = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable())
        assertFailure("unknown") { connector.invokePrepared(GmailConnector.SEND_MESSAGE, args(), preparation, CancellationToken.uncancellable()) }
        assertFailure("unknown") { connector.invokePrepared(GmailConnector.SEND_MESSAGE, args(), preparation, CancellationToken.uncancellable()) }
        connector.disconnect()
        assertFailure("unknown") { connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable()) }
        assertEquals(1, api.calls.size)
    }

    @Test fun malformedSuccessAndServerFailureRemainIndeterminate() {
        for (status in listOf(200, 503)) {
            val api = Api().apply { respond(JSONObject(), status) }
            val connector = runtime(api)
            assertFailure("unknown") { perform(connector, GmailConnector.SEND_MESSAGE, args()) }
            assertFailure("unknown") { connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable()) }
            assertEquals(1, api.calls.size)
        }
    }

    @Test fun cancellationDuringDispatchIsStillCancellationAndBlocksRetry() {
        val token = CancellationToken.cancellable()
        val api = Api().apply { replies += { token.cancel(); GoogleHttpResponse(200, sent().toString()) } }
        val connector = runtime(api)
        val error = runCatching { perform(connector, GmailConnector.SEND_MESSAGE, args(), token) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFailure("unknown") { connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable()) }
        assertEquals(1, api.calls.size)
    }

    @Test fun approvalCannotBeMovedToAnotherConnectorOrOperation() {
        val api = Api(); val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable())
        assertFailure("match") { connector.invokePrepared(GmailConnector.CREATE_DRAFT, args(), prepared, CancellationToken.uncancellable()) }
        assertFailure("match") { runtime(api).invokePrepared(GmailConnector.SEND_MESSAGE, args(), prepared, CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
    }

    private fun attachmentMessage(bytes: ByteArray, inline: Boolean = false, declaredSize: Long = bytes.size.toLong()): JSONObject {
        val body = JSONObject().put("size", declaredSize)
        if (inline) body.put("data", GoogleOAuthProtocol.base64Url(bytes)) else body.put("attachmentId", "ATT_01")
        return message().apply {
            getJSONObject("payload").put("mimeType", "multipart/mixed").put("parts", JSONArray()
                .put(JSONObject().put("partId", "1").put("mimeType", "application/octet-stream").put("filename", "payload.bin").put("body", body)))
        }
    }

    @Test fun attachmentDownloadPreservesArbitraryBytesAndPublishesOnlyArtifactMetadata() {
        val bytes = byteArrayOf(0, 1, 13, 10, 127, -128, -1, -40, 4)
        val api = Api().apply { respond(attachmentMessage(bytes)); respond(JSONObject().put("size", bytes.size).put("data", GoogleOAuthProtocol.base64Url(bytes))) }
        val files = Files()
        val result = runtime(api, files).invoke(GmailConnector.GET_ATTACHMENT, JSONObject().put("message_id", "m1").put("attachment_id", "ATT_01"), CancellationToken.uncancellable())
        assertEquals("GET", api.calls[1].method)
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/messages/m1/attachments/ATT_01", api.calls[1].url)
        assertEquals(GoogleOAuthProtocol.GMAIL_READ, api.calls[1].scope)
        assertArrayEquals(bytes, files.published.single().bytes)
        assertEquals("payload.bin", files.published.single().name)
        assertFalse(result.toString().contains(GoogleOAuthProtocol.base64Url(bytes)))
        assertFalse(result.toString().contains("\"data\"")); assertTrue(result.getBoolean("untrusted_content"))
        assertEquals("delivered:test", result.getJSONArray("items").getJSONObject(0).getString("artifact_id"))
    }

    @Test fun inlineAttachmentUsesParentBytesWithoutCallingAttachmentEndpoint() {
        val bytes = byteArrayOf(-1, 0, -127, 64)
        val api = Api().apply { respond(attachmentMessage(bytes, inline = true)) }
        val files = Files()
        runtime(api, files).invoke(GmailConnector.GET_ATTACHMENT, JSONObject().put("message_id", "m1").put("part_id", "1"), CancellationToken.uncancellable())
        assertEquals(1, api.calls.size); assertArrayEquals(bytes, files.published.single().bytes)
    }

    @Test fun unverifiedOversizedOrMismatchedAttachmentNeverPublishes() {
        val bytes = byteArrayOf(1, 2)
        val files = Files()
        val wrong = Api().apply { respond(attachmentMessage(bytes)) }
        assertFailure("not uniquely") { runtime(wrong, files).invoke(GmailConnector.GET_ATTACHMENT, JSONObject().put("message_id", "m1").put("attachment_id", "not-parent"), CancellationToken.uncancellable()) }
        assertEquals(1, wrong.calls.size)
        val tooBig = Api().apply { respond(attachmentMessage(bytes, declaredSize = GmailConnector.MAX_ATTACHMENT_BYTES.toLong() + 1)) }
        assertFailure("5 MiB") { runtime(tooBig, files).invoke(GmailConnector.GET_ATTACHMENT, JSONObject().put("message_id", "m1").put("attachment_id", "ATT_01"), CancellationToken.uncancellable()) }
        assertEquals(1, tooBig.calls.size)
        val mismatch = Api().apply { respond(attachmentMessage(bytes)); respond(JSONObject().put("size", 3).put("data", GoogleOAuthProtocol.base64Url(bytes))) }
        assertFailure("size mismatch") { runtime(mismatch, files).invoke(GmailConnector.GET_ATTACHMENT, JSONObject().put("message_id", "m1").put("attachment_id", "ATT_01"), CancellationToken.uncancellable()) }
        assertTrue(files.published.isEmpty())
    }

    @Test fun outgoingAttachmentUsesFrozenApprovedBytesAndCannotExceedTotalLimit() {
        val bytes = byteArrayOf(0, -1, 42, 8)
        val files = Files().apply { content["attachment:one"] = GoogleWorkspaceArtifact(bytes, "résumé.bin", "application/octet-stream", "untrusted-hash") }
        val api = Api().apply { respond(sent()) }; val connector = runtime(api, files)
        val prepared = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args().put("attachment_ids", JSONArray().put("attachment:one")), CancellationToken.uncancellable())
        assertTrue(prepared.approval.lines.any { it.contains("résumé.bin") && it.contains(GmailContent.digest(bytes)) })
        bytes.fill(7)
        connector.invokePrepared(GmailConnector.SEND_MESSAGE, JSONObject(), prepared, CancellationToken.uncancellable())
        val mime = mime(JSONObject(api.calls.single().body!!))
        assertTrue(mime.contains("AP8qCA==")); assertFalse(mime.contains("BwcHBw=="))
        files.content["attachment:big"] = GoogleWorkspaceArtifact(ByteArray(GmailConnector.MAX_OUTGOING_ATTACHMENT_BYTES + 1), "too-big.bin", "application/octet-stream", "")
        assertFailure("4 MiB") { connector.prepareWrite(GmailConnector.CREATE_DRAFT, args().put("attachment_ids", JSONArray().put("attachment:big")), CancellationToken.uncancellable()) }
        assertEquals(1, api.calls.size)
    }

    @Test fun deniedApprovalNeverSendsThePreparedMail() {
        val api = Api()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(2_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { gate.resolve(id, ApprovalDecision.DENIED) }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, gate)
        val definition = GmailConnector.definition(runtime(api))
        registry.register(definition); registry.connect(GmailConnector.ID)
        assertTrue(runCatching { registry.invoke(definition, definition.operations.first { it.name == GmailConnector.SEND_MESSAGE },
            args(), CancellationToken.uncancellable()) }.isFailure)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun durableCrashMarkerExistsBeforeDispatchAndBlocksRecreatedConnector() {
        val store = object : GoogleWorkspaceWriteJournalStore {
            var hashes = emptySet<String>()
            override fun read() = hashes
            override fun write(hashes: Set<String>): Boolean { this.hashes = hashes.toSet(); return true }
        }
        val api = Api().apply { replies += {
            assertEquals(1, store.hashes.size)
            throw IOException("process lost response")
        } }
        val first = GmailConnector(api, contacts, { false }, { false }, Files(), BoundedGoogleWorkspaceWriteJournal(store))
        assertFailure("unknown") { perform(first, GmailConnector.SEND_MESSAGE, args()) }
        val recreated = GmailConnector(api, contacts, { false }, { false }, Files(), BoundedGoogleWorkspaceWriteJournal(store))
        assertFailure("unknown") { recreated.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable()) }
        assertEquals(1, api.calls.size)
    }

    @Test fun journalPersistenceFailurePreventsNetworkAndDefinitiveRejectionClearsMarker() {
        val broken = object : GoogleWorkspaceWriteJournalStore {
            override fun read() = emptySet<String>()
            override fun write(hashes: Set<String>) = false
        }
        val blocked = Api()
        val connector = GmailConnector(blocked, contacts, { false }, { false }, Files(), BoundedGoogleWorkspaceWriteJournal(broken))
        assertFailure("could not be saved") { perform(connector, GmailConnector.SEND_MESSAGE, args()) }
        assertTrue(blocked.calls.isEmpty())
        for (status in listOf(400, 401, 403, 404, 409, 429)) {
            val api = Api().apply { respond(JSONObject(), status); respond(sent()) }
            val runtime = runtime(api)
            assertTrue(runCatching { perform(runtime, GmailConnector.SEND_MESSAGE, args()) }.isFailure)
            perform(runtime, GmailConnector.SEND_MESSAGE, args())
            assertEquals(2, api.calls.size)
        }
    }

    @Test fun requestTimeoutRetainsJournalAndSuccessfulNewIntentDoesNotBlockLaterIdenticalIntent() {
        val timeout = Api().apply { respond(JSONObject(), 408) }
        val connector = runtime(timeout)
        assertFailure("unknown") { perform(connector, GmailConnector.SEND_MESSAGE, args()) }
        assertFailure("unknown") { connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable()) }
        val ok = Api().apply { respond(sent()); respond(sent()) }
        val healthy = runtime(ok)
        perform(healthy, GmailConnector.SEND_MESSAGE, args())
        perform(healthy, GmailConnector.SEND_MESSAGE, args())
        assertEquals(2, ok.calls.size)
    }

    @Test fun unexpectedFieldsAndWrongTypesFailBeforeReadingOrWriting() {
        val api = Api(); val connector = runtime(api)
        assertFailure("Unsupported") { connector.prepareWrite(GmailConnector.SEND_MESSAGE, args().put("threadId", "injected"), CancellationToken.uncancellable()) }
        assertFailure("integer") { connector.invoke(GmailConnector.SEARCH_MESSAGES, JSONObject().put("query", "").put("max_results", "2"), CancellationToken.uncancellable()) }
        assertFailure("string") { connector.invoke(GmailConnector.GET_MESSAGE, JSONObject().put("id", 123), CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
    }


    private fun withHeader(message: JSONObject, name: String, value: String): JSONObject {
        val headers = message.getJSONObject("payload").getJSONArray("headers")
        for (i in 0 until headers.length()) if (headers.getJSONObject(i).getString("name").equals(name, true)) {
            headers.getJSONObject(i).put("value", value)
            return message
        }
        headers.put(header(name, value))
        return message
    }

    @Test fun replyIgnoresAddressesInsideQuotedDisplayNamesAndComments() {
        for (sender in listOf("\"support@other.example\" <actual@example.com>",
            "Actual (support@other.example) <actual@example.com>",
            "actual@example.com (support@other.example)",
            "\"support@other.example, also@other.example\" (comment@other.example) <actual@example.com>")) {
            val parent = withHeader(withHeader(message(), "From", sender), "Message-ID", "<parent@example.com>")
            val api = Api().apply { respond(parent); respond(sent()) }
            perform(runtime(api), GmailConnector.REPLY_MESSAGE, JSONObject().put("message_id", "m1").put("body", "Reply"))
            val mime = mime(JSONObject(api.calls.last().body!!))
            assertTrue(sender, mime.contains("To: actual@example.com\r\n"))
            assertFalse(sender, mime.contains("other.example"))
        }
    }

    @Test fun observedSenderTrustNeverIncludesQuotedDisplayNamesOrComments() {
        val sender = "\"support@other.example\" (comment@other.example) <actual@example.com>"
        val api = Api().apply { respond(withHeader(message(), "From", sender)) }
        val connector = runtime(api)
        connector.beginAgentRun(0, "Please read this mail")
        connector.invoke(GmailConnector.GET_MESSAGE, JSONObject().put("id", "m1"), CancellationToken.uncancellable())
        connector.validateAutonomousWrite(GmailConnector.SEND_MESSAGE, args().put("to", "actual@example.com"), 0)
        for (untrusted in listOf("support@other.example", "comment@other.example")) {
            assertTrue(runCatching { connector.validateAutonomousWrite(GmailConnector.SEND_MESSAGE, args().put("to", untrusted), 0) }
                .exceptionOrNull() is AutonomousWriteValidationFailure)
        }
        // User-authored text remains its separate source of explicit recipient trust.
        connector.beginAgentRun(1, "Send to support@other.example")
        connector.validateAutonomousWrite(GmailConnector.SEND_MESSAGE, args().put("to", "support@other.example"), 1)
    }

    @Test fun replyDraftUpdatePreservesItsThreadSubjectAndValidatedReplyHeaders() {
        val existing = draft()
        withHeader(existing.getJSONObject("message"), "In-Reply-To", "<parent@example.com>")
        withHeader(existing.getJSONObject("message"), "References", "<root@example.com> <parent@example.com>")
        val api = Api().apply { respond(existing); respond(rawDraft("b2xk")); respond(draft(revision = "m2")) }
        perform(runtime(api), GmailConnector.UPDATE_DRAFT, args().put("id", "d1").put("expected_revision", "m1"))
        val posted = JSONObject(api.calls.last().body!!).getJSONObject("message")
        assertEquals("thread1", posted.getString("threadId"))
        val mime = mime(posted).replace("\r\n ", " ")
        assertTrue(mime.contains("In-Reply-To: <parent@example.com>"))
        assertTrue(mime.contains("References: <root@example.com> <parent@example.com>"))
        assertTrue(mime.contains("Subject: Status"))
        val changed = Api().apply { respond(existing) }
        assertFailure("retain its subject") { perform(runtime(changed), GmailConnector.UPDATE_DRAFT,
            args().put("id", "d1").put("expected_revision", "m1").put("subject", "New conversation")) }
        assertTrue(changed.calls.all { it.method == "GET" })
    }

    @Test fun unsupportedReplyDraftHeadersRequireAnExplicitVerifiedParent() {
        val existing = draft()
        withHeader(existing.getJSONObject("message"), "References", "<parent@example.com>")
        val api = Api().apply { respond(existing) }
        assertFailure("verified") { perform(runtime(api), GmailConnector.UPDATE_DRAFT, args().put("id", "d1").put("expected_revision", "m1")) }
        assertTrue(api.calls.all { it.method == "GET" })
    }

    @Test fun changedAuthorizationAfterApprovalBlocksAllNetworkDispatch() {
        val api = Api(); val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable())
        api.epoch = 2
        assertFailure("authorization changed") { connector.invokePrepared(GmailConnector.SEND_MESSAGE, args(), prepared, CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun authorizationEpochIsPassedToAtomicMutationLeaseAndCannotRaceAccountSwitch() {
        val api = Api().apply { respond(sent()) }; val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable())
        api.beforeLease = { api.epoch = 2 }
        assertFailure("unknown") { connector.invokePrepared(GmailConnector.SEND_MESSAGE, args(), prepared, CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
        assertEquals(listOf(1L), api.expectedEpochs)
    }

    @Test fun draftPreparationAndPostApprovalPreflightCarryTheSameAuthorizationEpoch() {
        val raw = GmailContent.rawMessage(EmailDraft("owner@example.com", "Status", "Draft body", "", ""))
        val api = Api().apply { respond(draft()); respond(rawDraft(raw)); respond(rawDraft(raw)); respond(sent()) }
        val connector = runtime(api)
        val prepared = connector.prepareWrite(GmailConnector.SEND_DRAFT, JSONObject().put("id", "d1").put("expected_revision", "m1"), CancellationToken.uncancellable())
        api.beforeLease = { api.epoch = 2 }
        assertFailure("connection changed") { connector.invokePrepared(GmailConnector.SEND_DRAFT, JSONObject(), prepared, CancellationToken.uncancellable()) }
        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { it.method == "GET" })
        assertEquals(listOf(1L, 1L, 1L), api.expectedEpochs)
    }


    @Test fun replyParsesEncodedDisplayNamesBeforeRfc2047DecodingForFromAndReplyTo() {
        val encoded = "=?UTF-8?Q?support=40other.example=2C?= <actual@example.com>"
        for (name in listOf("From", "Reply-To")) {
            val parent = withHeader(withHeader(message(), name, encoded), "Message-ID", "<parent@example.com>")
            val api = Api().apply { respond(parent); respond(sent()) }
            perform(runtime(api), GmailConnector.REPLY_MESSAGE, JSONObject().put("message_id", "m1").put("body", "Reply"))
            val outgoing = mime(JSONObject(api.calls.last().body!!))
            assertTrue(outgoing.contains("To: actual@example.com\r\n"))
            assertFalse(outgoing.contains("support@other.example"))
        }
    }

    @Test fun encodedDisplayNameNeverBecomesObservedSenderTrustThroughMessageSearchOrThreadReads() {
        val encoded = "=?UTF-8?Q?support=40other.example=2C?= <actual@example.com>"
        for (operation in listOf(GmailConnector.GET_MESSAGE, GmailConnector.SEARCH_MESSAGES, GmailConnector.GET_THREAD)) {
            val parent = withHeader(message(), "From", encoded)
            val api = Api().apply {
                when (operation) {
                    GmailConnector.SEARCH_MESSAGES -> { respond(JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "m1")))); respond(parent) }
                    GmailConnector.GET_THREAD -> respond(JSONObject().put("messages", JSONArray().put(parent)))
                    else -> respond(parent)
                }
            }
            val connector = runtime(api)
            connector.beginAgentRun(0, "Read my mail")
            val readArgs = if (operation == GmailConnector.SEARCH_MESSAGES) JSONObject().put("query", "") else JSONObject().put("id", "m1")
            connector.invoke(operation, readArgs, CancellationToken.uncancellable())
            connector.validateAutonomousWrite(GmailConnector.SEND_MESSAGE, args().put("to", "actual@example.com"), 0)
            assertTrue(operation, runCatching { connector.validateAutonomousWrite(GmailConnector.SEND_MESSAGE,
                args().put("to", "support@other.example"), 0) }.exceptionOrNull() is AutonomousWriteValidationFailure)
        }
    }

    @Test fun missingWritePermissionFailsBeforeApprovalReadsOrAmbiguityReservation() {
        val operations = listOf(
            GmailConnector.CREATE_DRAFT to args(), GmailConnector.SEND_MESSAGE to args(),
            GmailConnector.REPLY_MESSAGE to JSONObject().put("message_id", "m1").put("body", "Reply"),
            GmailConnector.UPDATE_DRAFT to args().put("id", "d1").put("expected_revision", "m1"),
            GmailConnector.SEND_DRAFT to JSONObject().put("id", "d1").put("expected_revision", "m1"),
        )
        operations.forEach { (operation, input) ->
            val api = Api().apply { granted = false }
            assertFailure("permission") { runtime(api).prepareWrite(operation, input, CancellationToken.uncancellable()) }
            assertTrue(api.calls.isEmpty())
        }
        val api = Api().apply { granted = false }
        val connector = runtime(api)
        assertFailure("permission") { perform(connector, GmailConnector.SEND_MESSAGE, args()) }
        api.granted = true
        api.respond(sent())
        assertEquals("send_accepted", perform(connector, GmailConnector.SEND_MESSAGE, args()).getJSONArray("items").getJSONObject(0).getString("status"))
        assertEquals(1, api.calls.size)
    }

    @Test fun permissionLostAfterApprovalDoesNotReserveAnUnsentWrite() {
        val api = Api()
        val connector = runtime(api)
        val preparation = connector.prepareWrite(GmailConnector.SEND_MESSAGE, args(), CancellationToken.uncancellable())
        api.granted = false
        assertFailure("permission") { connector.invokePrepared(GmailConnector.SEND_MESSAGE, preparation.executionArguments,
            preparation, CancellationToken.uncancellable()) }
        assertTrue(api.calls.isEmpty())
        api.granted = true
        api.respond(sent())
        assertEquals("send_accepted", perform(connector, GmailConnector.SEND_MESSAGE, args()).getJSONArray("items").getJSONObject(0).getString("status"))
    }

}

package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GmailInventoryPaginationTest {
    private class Api : GoogleRestAuthorization {
        val responses = ArrayDeque<JSONObject>()
        val urls = mutableListOf<String>()
        var epoch = 1L
        var afterRead: (() -> Unit)? = null
        override fun currentAuthorizationEpoch() = epoch
        override fun isScopeGranted(scope: String) = true
        override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
            assertEquals("GET", method); urls += url
            val response = responses.removeFirst()
            afterRead?.also { afterRead = null }?.invoke()
            return GoogleHttpResponse(200, response.toString())
        }
    }
    private fun runtime(api: Api) = GmailConnector(api, object : ContactsGateway {
        override fun search(query: String, limit: Int) = emptyList<ContactRecord>()
        override fun find(contactId: Long): ContactRecord? = null
    }, { false }, { false })
    private fun invoke(connector: GmailConnector, operation: String, args: JSONObject) = connector.invoke(operation, args, CancellationToken.uncancellable())
    private fun message(id: String, history: String = "10") = JSONObject().put("id", id).put("threadId", "t1")
        .put("historyId", history).put("labelIds", JSONArray().put("INBOX").put("UNREAD"))
        .put("payload", JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", "eA")))
    private fun thread(vararg messages: JSONObject) = JSONObject().put("id", "t1").put("messages", JSONArray(messages.toList()))
    private fun labels() = JSONObject().put("labels", JSONArray((1..57).map {
        JSONObject().put("id", "Label_${it.toString().padStart(3, '0')}").put("name", "Label $it").put("type", "user")
    }))

    @Test fun labelInventoryReturnsEveryIdAcrossStableContinuationPages() {
        val api = Api().apply { repeat(3) { responses += labels() } }; val connector = runtime(api)
        val all = mutableListOf<String>(); var cursor = ""
        repeat(3) { page ->
            val result = invoke(connector, GmailConnector.LIST_LABELS, JSONObject().put("max_results", 25).put("page_token", cursor))
            val items = result.getJSONArray("items"); repeat(items.length()) { all += items.getJSONObject(it).getString("id") }
            assertEquals(page < 2, result.getBoolean("has_more")); assertEquals(57, result.getInt("label_count"))
            cursor = result.optString("next_page_token")
        }
        assertEquals(57, all.size); assertEquals(57, all.distinct().size)
    }
    @Test fun changedLabelInventoryRejectsOldContinuationInsteadOfSkippingTargets() {
        val api = Api().apply { responses += labels(); responses += labels().apply { getJSONArray("labels").getJSONObject(0).put("name", "Changed") } }
        val connector = runtime(api)
        val first = invoke(connector, GmailConnector.LIST_LABELS, JSONObject().put("max_results", 25))
        val failure = runCatching { invoke(connector, GmailConnector.LIST_LABELS, JSONObject().put("page_token", first.getString("next_page_token"))) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("changed"))
    }
    @Test fun threadContinuationReadsPreviouslyTruncatedMembersAndExposesState() {
        val api = Api().apply { repeat(2) { responses += thread(message("m1"), message("m2")) } }; val connector = runtime(api)
        val first = invoke(connector, GmailConnector.GET_THREAD, JSONObject().put("id", "t1").put("max_results", 1))
        val second = invoke(connector, GmailConnector.GET_THREAD, JSONObject().put("id", "t1").put("max_results", 1).put("page_token", first.getString("next_page_token")))
        val row = second.getJSONArray("items").getJSONObject(0)
        assertEquals("m2", row.getString("id")); assertEquals("10", row.getString("history_id")); assertEquals(2, row.getInt("label_count"))
        assertEquals("UNREAD", row.getJSONArray("label_ids").getString(1)); assertFalse(second.getBoolean("has_more"))
    }
    @Test fun threadContinuationRejectsChangedMembershipAndHistory() {
        for (changed in listOf(thread(message("m1"), message("m2"), message("m3")), thread(message("m1", "11"), message("m2")))) {
            val api = Api().apply { responses += thread(message("m1"), message("m2")); responses += changed }; val connector = runtime(api)
            val first = invoke(connector, GmailConnector.GET_THREAD, JSONObject().put("id", "t1").put("max_results", 1))
            assertTrue(runCatching { invoke(connector, GmailConnector.GET_THREAD, JSONObject().put("id", "t1").put("page_token", first.getString("next_page_token"))) }.isFailure)
        }
    }
    @Test fun changedAccountDuringInventoryReadFailsWithoutReturningMixedData() {
        val api = Api().apply { responses += labels(); afterRead = { epoch++ } }
        val error = runCatching { invoke(runtime(api), GmailConnector.LIST_LABELS, JSONObject()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("account changed"))
    }
    @Test fun providerCannotSubstituteAnotherMessageOrThreadIdentity() {
        for ((operation, response) in listOf(GmailConnector.GET_MESSAGE to message("other"), GmailConnector.GET_THREAD to thread(message("m1")).put("id", "other"))) {
            val api = Api().apply { responses += response }
            val error = runCatching { invoke(runtime(api), operation, JSONObject().put("id", "wanted")) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("different"))
        }
    }
    @Test fun malformedLocalInventoryCursorsAndOversizedPagesAreRejected() {
        for (args in listOf(JSONObject().put("page_token", "garbage"), JSONObject().put("max_results", 51))) {
            val api = Api().apply { responses += labels() }
            assertTrue(runCatching { invoke(runtime(api), GmailConnector.LIST_LABELS, args) }.isFailure)
        }
    }
    @Test fun localInventoryCursorCannotCrossAnAuthorizationEpochEvenWithIdenticalData() {
        for (operation in listOf(GmailConnector.LIST_LABELS, GmailConnector.GET_THREAD)) {
            val api = Api().apply { repeat(2) { responses += if (operation == GmailConnector.LIST_LABELS) labels() else thread(message("m1"), message("m2")) } }
            val connector = runtime(api); val args = JSONObject().put("max_results", 1)
            if (operation == GmailConnector.GET_THREAD) args.put("id", "t1")
            val first = invoke(connector, operation, args); api.epoch++
            args.put("page_token", first.getString("next_page_token"))
            assertTrue(runCatching { invoke(connector, operation, args) }.isFailure)
        }
    }
    @Test fun multibyteThreadBudgetNeverSkipsIdsEvenWhenRowsAreRemovedForBytes() {
        val messages = (0 until 25).map { i -> message("m$i").apply {
            put("snippet", "界".repeat(256))
            getJSONObject("payload").put("headers", JSONArray(listOf("From", "To", "Subject").map { name -> JSONObject().put("name", name).put("value", "界".repeat(512)) }))
                .getJSONObject("body").put("data", GoogleOAuthProtocol.base64Url("界".repeat(491).toByteArray()))
        } }
        val api = Api().apply { repeat(30) { responses += thread(*messages.toTypedArray()) } }; val connector = runtime(api)
        val seen = mutableListOf<String>(); var cursor = ""; var complete = false
        repeat(30) {
            if (!complete) {
                val result = invoke(connector, GmailConnector.GET_THREAD, JSONObject().put("id", "t1").put("max_results", 25).put("page_token", cursor))
                val items = result.getJSONArray("items"); assertTrue(items.length() > 0)
                repeat(items.length()) { seen += items.getJSONObject(it).getString("id") }
                complete = !result.getBoolean("has_more"); cursor = result.optString("next_page_token")
            }
        }
        assertTrue(complete); assertEquals((0 until 25).map { "m$it" }, seen); assertTrue(api.urls.size > 1)
    }
    @Test fun oversizedSingleThreadRowStillReturnsItsIdWithHonestPreviewTruncation() {
        val oversized = message("m1").apply {
            val parts = JSONArray().put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject()
                .put("data", GoogleOAuthProtocol.base64Url("界".repeat(12288).toByteArray()))))
            repeat(10) { parts.put(JSONObject().put("mimeType", "application/octet-stream").put("filename", "界".repeat(256))
                .put("partId", "p$it").put("body", JSONObject().put("attachmentId", "A".repeat(2048)).put("size", 1))) }
            put("payload", JSONObject().put("mimeType", "multipart/mixed").put("parts", parts))
        }
        val api = Api().apply { responses += thread(oversized) }
        val result = invoke(runtime(api), GmailConnector.GET_THREAD, JSONObject().put("id", "t1"))
        val row = result.getJSONArray("items").getJSONObject(0)
        assertEquals("m1", row.getString("id")); assertTrue(result.getBoolean("truncated")); assertFalse(result.getBoolean("has_more"))
        assertTrue(row.getBoolean("attachments_truncated"))
    }

}

package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException

/** Stateful REST and registry tests. No real account, OAuth grant, HTTP server or mailbox. */
class GmailDraftDeletionTest {
    private data class Request(val scope: String, val method: String, val url: String, val body: String?)
    private class Api : GoogleRestAuthorization {
        var epoch = 7L
        var account = "owner@example.test"
        val grants = mutableSetOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_COMPOSE)
        val calls = mutableListOf<Request>()
        val expectedEpochs = mutableListOf<Long?>()
        val bounds = mutableListOf<Int>()
        val proofs = mutableListOf<Pair<String, String?>>()
        var full = message()
        var revision = "m1"
        var raw = GoogleOAuthProtocol.base64Url("To: recipient@example.test\r\nSubject: Draft subject\r\n\r\nDraft body".toByteArray())
        var exists = true
        var deleteResponse = GoogleHttpResponse(204, "")
        var mutate = true
        var deleteFailure: Exception? = null
        var preDispatch: (() -> Unit)? = null
        var afterRawRead: (() -> Unit)? = null
        var afterMutation: (() -> Unit)? = null
        var responseOverride: ((Request) -> GoogleHttpResponse?)? = null
        val writes get() = calls.filter { it.method != "GET" }
        override fun isScopeGranted(scope: String) = scope in grants
        override fun currentAuthorizationEpoch() = epoch
        override fun verifyGmailAccount(capability: String, expectedAccount: String?, token: CancellationToken, epoch: Long): String {
            token.throwIfCancelled()
            check(epoch == this.epoch) { "Account epoch changed" }
            check(capability in grants) { "Scope missing" }
            proofs += capability to expectedAccount
            check(expectedAccount == null || expectedAccount == account) { "Account changed" }
            return account
        }
        override fun requestCancellable(scope: String, method: String, url: String, body: String?, contentType: String,
            token: CancellationToken, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleHttpResponse {
            expectedEpochs += expectedAuthorizationEpoch
            if (method == "DELETE") preDispatch?.invoke()
            return super<GoogleRestAuthorization>.requestCancellable(scope, method, url, body, contentType, token, requestHeaders, expectedAuthorizationEpoch)
        }
        override fun requestBytes(scope: String, method: String, url: String, body: ByteArray?, contentType: String,
            token: CancellationToken, maxResponseBytes: Int, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleBinaryResponse {
            expectedEpochs += expectedAuthorizationEpoch
            bounds += maxResponseBytes
            return super<GoogleRestAuthorization>.requestBytes(scope, method, url, body, contentType, token, maxResponseBytes, requestHeaders, expectedAuthorizationEpoch)
        }
        override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
            check(scope in grants)
            GoogleHttpPolicy.validateEndpoint(method, url, scope)
            val request = Request(scope, method, url, body)
            calls += request
            responseOverride?.invoke(request)?.let { return it }
            check(URI(url).path == "/gmail/v1/users/me/drafts/d1") { "Unexpected route $url" }
            if (method == "GET") {
                if (!exists) return GoogleHttpResponse(404, "{}")
                val format = URI(url).rawQuery.substringAfter("format=")
                val content = when (format) {
                    "full" -> JSONObject(full.toString())
                    "raw" -> JSONObject().put("id", revision).put("raw", raw)
                    "metadata" -> JSONObject().put("id", revision)
                    else -> error("Unexpected format $format")
                }
                if (format == "raw") afterRawRead?.also { afterRawRead = null }?.invoke()
                return GoogleHttpResponse(200, JSONObject().put("id", "d1").put("message", content).toString())
            }
            check(method == "DELETE" && URI(url).rawQuery == null && body == null)
            if (mutate && deleteResponse.status in 200..299) exists = false
            afterMutation?.invoke()
            deleteFailure?.let { throw it }
            return deleteResponse
        }
    }
    private class Journal : GoogleWorkspaceWriteJournal {
        val intents = linkedSetOf<String>()
        var reserveFailure = false
        var resolveFailure = false
        var reserveHook: (() -> Unit)? = null
        var reserves = 0
        override fun isUncertain(intentHash: String) = intentHash in intents
        override fun reserve(intentHash: String) {
            reserves++
            check(intents.add(intentHash)) { "duplicate intent" }
            if (reserveFailure) error("journal persistence failed")
            reserveHook?.invoke()
        }
        override fun resolve(intentHash: String): Boolean {
            if (resolveFailure) return false
            intents.remove(intentHash)
            return true
        }
    }
    private val token get() = CancellationToken.uncancellable()
    private fun args() = JSONObject().put("id", "d1").put("expected_revision", "m1")
    private fun prepare(component: GmailDraftDeletion) = component.prepare(args(), token)
    private fun perform(component: GmailDraftDeletion) = component.execute(prepare(component), token)
    private fun item(result: JSONObject) = result.getJSONArray("items").getJSONObject(0)
    private fun failure(fragment: String = "", block: () -> Unit): Throwable {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Expected failure containing $fragment", failure)
        assertTrue("Unexpected failure: ${failure?.message}", failure!!.message.orEmpty().contains(fragment, true))
        return failure
    }
    private fun runtime(api: Api, journal: GoogleWorkspaceWriteJournal = Journal()) = GmailConnector(api,
        object : ContactsGateway {
            override fun search(query: String, limit: Int) = emptyList<ContactRecord>()
            override fun find(contactId: Long): ContactRecord? = null
        }, { false }, { false }, writeJournal = journal)

    @Test fun realConnectorCatalogRoutesComposeOnlyIrreversibleDeleteWithoutAllow() {
        val api = Api()
        val connector = runtime(api)
        val definition = GmailConnector.definition(connector)
        val operation = definition.operations.single { it.name == GmailDraftDeletion.OPERATION }
        assertTrue(operation.write)
        assertFalse(operation.autonomyAllowed)
        assertTrue(operation.displayLabelResourceId != 0)
        assertTrue(operation.descriptionResourceId != 0)
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, GmailConnector.requiredScope(operation.name))
        assertEquals(setOf("id", "expected_revision"), (0 until operation.inputSchema.getJSONArray("required").length())
            .map { operation.inputSchema.getJSONArray("required").getString(it) }.toSet())
        assertFalse(operation.inputSchema.getBoolean("additionalProperties"))
        failure("approval") { connector.invoke(operation.name, args(), token) }
        val review = connector.prepareWrite(operation.name, args(), token)
        assertFalse(review.approval.allowAlwaysAvailable)
        assertEquals("delete_accepted", item(connector.invokePrepared(operation.name, args(), review, token)).getString("status"))
        val write = api.writes.single()
        assertEquals("DELETE", write.method)
        assertEquals("${GoogleRestEndpoints.GMAIL}/users/me/drafts/d1", write.url)
        assertNull(write.body)
        assertTrue(api.calls.all { it.scope == GoogleOAuthProtocol.GMAIL_COMPOSE })
    }

    @Test fun exactRoutesAndTransportBoundsAreUsedForReviewRecheckAndReadback() {
        val api = Api(); val journal = Journal()
        val result = item(perform(GmailDraftDeletion(api, journal)))
        assertEquals(listOf("GET", "GET", "GET", "DELETE", "GET"), api.calls.map { it.method })
        assertEquals(listOf("full", "raw", "raw", "metadata"), api.calls.filter { it.method == "GET" }.map { URI(it.url).rawQuery.substringAfter("format=") })
        assertTrue(api.bounds.all { it == 8 * 1024 * 1024 })
        assertTrue(api.expectedEpochs.all { it == 7L })
        assertTrue(api.proofs.all { it.first == GoogleOAuthProtocol.GMAIL_COMPOSE })
        assertEquals(null, api.proofs.first().second)
        assertTrue(api.proofs.drop(1).all { it.second == "owner@example.test" })
        assertEquals("observed_absent", result.getString("verification"))
        assertFalse(result.getBoolean("deletion_causation_verified"))
        assertFalse(result.getBoolean("retry_blocked"))
        assertEquals(1, journal.reserves)
        assertTrue(journal.intents.isEmpty())
    }

    @Test fun composePermissionIsRequiredAndIsRecheckedWithoutExpandingGrants() {
        val api = Api().apply { grants.remove(GoogleOAuthProtocol.GMAIL_COMPOSE) }
        failure("draft permission") { prepare(GmailDraftDeletion(api, Journal())) }
        assertTrue(api.calls.isEmpty()); assertTrue(api.proofs.isEmpty())
        api.grants += GoogleOAuthProtocol.GMAIL_COMPOSE
        val component = GmailDraftDeletion(api, Journal()); val review = prepare(component)
        api.grants -= GoogleOAuthProtocol.GMAIL_COMPOSE
        failure("permission") { component.execute(review, token) }
        assertTrue(api.writes.isEmpty())
        val definition = GmailConnector.definition(runtime(api))
        assertFalse(definition.operationAccessGranted!!.invoke(GmailDraftDeletion.OPERATION))
    }

    @Test fun registryDenialHasNoEffectAndNoAllowPolicyCanBeEnabled() {
        val api = Api(); val connector = runtime(api)
        val definition = GmailConnector.definition(connector)
        val operation = definition.operations.single { it.name == GmailDraftDeletion.OPERATION }
        var summary: ApprovalSummary? = null
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { true }, rejectingApprovalGate { summary = it })
        registry.register(definition); registry.connect(definition.id)
        failure("Allow") { registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW) }
        failure { registry.invoke(definition, operation.copy(write = false, autonomyAllowed = true), args(), token) }
        assertNotNull(summary); assertFalse(summary!!.allowAlwaysAvailable)
        assertTrue(api.writes.isEmpty()); assertTrue(api.exists)
    }

    @Test fun exactStringIdAndRevisionAreMandatoryAndExtraArgumentsAreRejected() {
        val invalid = listOf(JSONObject(), JSONObject().put("id", "d1"), args().put("id", "d1/other"),
            args().put("expected_revision", ""), args().put("expected_revision", 1), args().put("force", true))
        for (input in invalid) {
            val api = Api()
            failure { GmailDraftDeletion(api, Journal()).prepare(input, token) }
            assertTrue(api.calls.isEmpty()); assertTrue(api.proofs.isEmpty())
        }
    }

    @Test fun editedArgumentsNeverChangeTheReviewedTarget() {
        val api = Api(); val component = GmailDraftDeletion(api, Journal()); val input = args()
        val review = component.prepare(input, token)
        input.put("id", "other"); review.executionArguments.put("id", "other").put("expected_revision", "other")
        assertEquals("d1", item(component.execute(review, token)).getString("id"))
        assertTrue(api.writes.single().url.endsWith("/d1"))
    }

    @Test fun approvalIsOwnerBoundAndSingleUseEvenAfterSuccessfulDeletion() {
        val api = Api(); val component = GmailDraftDeletion(api, Journal()); val review = prepare(component)
        failure("another action") { GmailDraftDeletion(api, Journal()).execute(review, token) }
        component.execute(review, token)
        val callCount = api.calls.size
        failure("already attempted") { component.execute(review, token) }
        assertEquals(callCount, api.calls.size); assertEquals(1, api.writes.size)
    }

    @Test fun changedAccountOrEpochBlocksBeforeAnyMutation() {
        for (changeAccount in listOf(true, false)) {
            val api = Api(); val component = GmailDraftDeletion(api, Journal()); val review = prepare(component)
            if (changeAccount) api.account = "other@example.test" else api.epoch++
            failure(if (changeAccount) "account" else "authorization") { component.execute(review, token) }
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun accountAndEpochAreCheckedAgainAfterFinalRawRead() {
        for (changeAccount in listOf(true, false)) {
            val api = Api(); val component = GmailDraftDeletion(api, Journal()); val review = prepare(component)
            api.afterRawRead = { if (changeAccount) api.account = "other@example.test" else api.epoch++ }
            failure { component.execute(review, token) }
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun changedRevisionOrRawContentBlocksMutation() {
        for (changeRevision in listOf(true, false)) {
            val api = Api(); val journal = Journal(); val component = GmailDraftDeletion(api, journal); val review = prepare(component)
            if (changeRevision) api.revision = "m2" else api.raw = GoogleOAuthProtocol.base64Url("Changed content".toByteArray())
            failure("changed") { component.execute(review, token) }
            assertTrue(api.writes.isEmpty()); assertEquals(0, journal.reserves)
        }
    }

    @Test fun changedRevisionDuringPreparationDoesNotCreateAReview() {
        val api = Api().apply { revision = "m2" }
        failure("revision") { prepare(GmailDraftDeletion(api, Journal())) }
        assertTrue(api.writes.isEmpty())
    }

    @Test fun changedDraftIdMalformedMessageAndMissingRawAreRejected() {
        for (bad in listOf(JSONObject().put("id", "other").put("message", message()),
            JSONObject().put("id", "d1"), JSONObject().put("id", "d1").put("message", JSONObject().put("id", "m1")))) {
            val api = Api().apply { responseOverride = { request ->
                if (request.url.endsWith("format=raw")) GoogleHttpResponse(200, bad.toString()) else null
            } }
            failure { prepare(GmailDraftDeletion(api, Journal())) }
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun invalidOrOversizedRawCannotBeReviewedOrDeleted() {
        for (raw in listOf("not+base64", "a", "A".repeat(8 * 1024 * 1024 + 10))) {
            val api = Api().apply { this.raw = raw }
            failure { prepare(GmailDraftDeletion(api, Journal())) }
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun validEmptyDraftNeedsNoRecipientSubjectOrBody() {
        val api = Api().apply {
            full = JSONObject().put("id", "m1").put("payload", JSONObject().put("mimeType", "text/plain")
                .put("headers", JSONArray()).put("body", JSONObject().put("size", 0).put("data", "")))
            raw = ""
        }
        val component = GmailDraftDeletion(api, Journal()); val review = prepare(component)
        assertTrue(review.approval.lines.any { it == "To preview: (empty)" })
        assertTrue(review.approval.lines.any { it == "Subject preview: (empty)" })
        assertEquals("observed_absent", item(component.execute(review, token)).getString("verification"))
    }

    @Test fun localizedReviewIncludesIdentityAttachmentAndPermanentConsequenceWithTruncation() {
        val api = Api().apply {
            full = message("z".repeat(2_000))
            full.getJSONObject("payload").put("parts", JSONArray().put(JSONObject().put("mimeType", "application/pdf")
                .put("filename", "review.pdf").put("body", JSONObject().put("size", 128).put("attachmentId", "a1"))))
            full.getJSONObject("payload").getJSONArray("headers").put(header("Subject", "s".repeat(900)))
        }
        val review = prepare(GmailDraftDeletion(api, Journal()))
        val lines = review.approval.lines.joinToString("\n")
        assertTrue(lines.contains("owner@example.test")); assertTrue(lines.contains("d1 · revision: m1"))
        assertTrue(lines.contains("review.pdf")); assertTrue(lines.contains("128 bytes"))
        assertTrue(lines.contains("truncated")); assertTrue(lines.contains("cannot be recovered from Trash"))
        assertTrue(lines.contains("atomic revision")); assertFalse(review.approval.allowAlwaysAvailable)
        assertTrue(review.approval.localizedLines!!.all { it.resourceId != 0 })
        assertTrue(lines.length < 10_000)
        assertFalse(review.executionArguments.has("raw")); assertFalse(review.executionArguments.has("body"))
    }

    @Test fun largeBodyPreviewExplicitlySaysItIsTruncated() {
        val api = Api().apply { full = message("x".repeat(2_000)) }
        val review = prepare(GmailDraftDeletion(api, Journal()))
        assertTrue(review.approval.lines.any { it.contains("truncated") })
        assertTrue(review.approval.lines.first { it.startsWith("Body preview:") }.length < 1_020)
    }

    @Test fun empty2xxAndEmptyJsonObjectAreAcceptedWithoutCausationClaims() {
        for ((status, body) in listOf(200 to "", 204 to "", 200 to "{}", 200 to "  \n")) {
            val api = Api().apply { deleteResponse = GoogleHttpResponse(status, body) }
            val result = item(perform(GmailDraftDeletion(api, Journal())))
            assertEquals("delete_accepted", result.getString("status"))
            assertEquals("observed_absent", result.getString("verification"))
            assertFalse(result.getBoolean("deletion_causation_verified"))
        }
    }

    @Test fun unknownSuccessMarkerMalformedOrNonObjectResponseRemainsUncertain() {
        for (body in listOf("{\"success\":true}", "not json", "[]", "{} trailing")) {
            val api = Api().apply { deleteResponse = GoogleHttpResponse(200, body) }
            val journal = Journal(); val component = GmailDraftDeletion(api, journal)
            failure("unknown") { perform(component) }
            assertEquals(1, api.writes.size); assertEquals(1, journal.intents.size)
            failure("unknown") { prepare(component) }
            assertEquals(1, api.writes.size)
        }
    }

    @Test fun serverErrorTimeoutAndNetworkFailureRemainUncertainWithoutRetry() {
        for (status in listOf(500, 503, 408, 302)) {
            val api = Api().apply { deleteResponse = GoogleHttpResponse(status, "{}") }
            val journal = Journal(); val component = GmailDraftDeletion(api, journal)
            failure("unknown") { perform(component) }
            assertEquals(1, journal.intents.size); assertEquals(1, api.writes.size)
            failure("unknown") { prepare(GmailDraftDeletion(api, journal)) }
            assertEquals(1, api.writes.size)
        }
        val api = Api().apply { deleteFailure = IOException("connection interrupted") }
        val journal = Journal()
        failure("unknown") { perform(GmailDraftDeletion(api, journal)) }
        assertEquals(1, journal.intents.size); assertEquals(1, api.writes.size)
    }

    @Test fun explicitServerRejectionClearsMarkerButCannotReuseApproval() {
        for (status in listOf(400, 403, 404, 409, 429)) {
            val api = Api().apply { deleteResponse = GoogleHttpResponse(status, "{}") }
            val journal = Journal(); val component = GmailDraftDeletion(api, journal); val review = prepare(component)
            failure("HTTP $status") { component.execute(review, token) }
            assertTrue(journal.intents.isEmpty()); assertTrue(api.exists)
            failure("already attempted") { component.execute(review, token) }
            assertEquals(1, api.writes.size)
        }
    }

    @Test fun observedPresentOrMalformedReadbackRetainsMarkerAfterAcceptance() {
        for (malformed in listOf(true, false)) {
            val api = Api().apply {
                mutate = false
                if (malformed) responseOverride = { request -> if (request.url.endsWith("format=metadata")) GoogleHttpResponse(200, "not json") else null }
            }
            val journal = Journal(); val result = perform(GmailDraftDeletion(api, journal))
            assertEquals("delete_accepted", item(result).getString("status"))
            assertEquals(if (malformed) "unknown" else "observed_present", item(result).getString("verification"))
            assertTrue(item(result).getBoolean("retry_blocked")); assertTrue(result.has("safety_warning"))
            assertEquals(1, journal.intents.size); assertEquals(1, api.writes.size)
        }
    }

    @Test fun accountChangeAfterAcceptedDeleteCannotVerifyAnotherAccountsAbsence() {
        val api = Api().apply { afterMutation = { account = "other@example.test" } }
        val journal = Journal(); val result = item(perform(GmailDraftDeletion(api, journal)))
        assertEquals("unknown", result.getString("verification")); assertTrue(result.getBoolean("retry_blocked"))
        assertEquals(1, journal.intents.size)
        assertFalse(api.calls.any { it.url.endsWith("format=metadata") })
    }

    @Test fun failedPersistentReservationNeverDispatches() {
        val api = Api(); val journal = Journal().apply { reserveFailure = true }
        val component = GmailDraftDeletion(api, journal); val review = prepare(component)
        failure("persistence") { component.execute(review, token) }
        assertTrue(api.writes.isEmpty()); assertTrue(api.exists); assertEquals(1, journal.intents.size)
        failure("unknown") { prepare(component) }
    }

    @Test fun preDispatchProofFailureMayClearMarkerButPostDispatchProofFailureCannot() {
        val api = Api().apply { preDispatch = { throw GooglePreDispatchAuthorizationException(IllegalStateException("account proof failed")) } }
        val journal = Journal()
        failure("proof") { perform(GmailDraftDeletion(api, journal)) }
        assertTrue(api.writes.isEmpty()); assertTrue(journal.intents.isEmpty())
        val after = Api().apply { deleteFailure = IllegalStateException("refresh proof failed") }
        val retained = Journal()
        failure("unknown") { perform(GmailDraftDeletion(after, retained)) }
        assertEquals(1, after.writes.size); assertEquals(1, retained.intents.size)
    }

    @Test fun cancelledBeforeReservationDoesNotDispatchOrReserve() {
        val api = Api(); val journal = Journal(); val component = GmailDraftDeletion(api, journal); val review = prepare(component)
        val cancel = CancellationToken.cancellable().apply { cancel() }
        assertTrue(failure { component.execute(review, cancel) } is CancellationException)
        assertTrue(api.writes.isEmpty()); assertEquals(0, journal.reserves)
    }

    @Test fun cancelledAfterDispatchRetainsMarkerAndBlocksRetry() {
        val cancel = CancellationToken.cancellable()
        val api = Api().apply { afterMutation = { cancel.cancel() } }
        val journal = Journal(); val component = GmailDraftDeletion(api, journal); val review = prepare(component)
        assertTrue(failure { component.execute(review, cancel) } is CancellationException)
        assertEquals(1, journal.intents.size); assertEquals(1, api.writes.size)
        failure("unknown") { prepare(component) }
    }

    @Test fun journalCannotBeBypassedByChangedRevisionAfterUnknownDispatch() {
        val api = Api().apply { mutate = false; deleteFailure = IOException("lost response") }
        val journal = Journal()
        failure("unknown") { perform(GmailDraftDeletion(api, journal)) }
        api.full.put("id", "m2"); api.revision = "m2"; api.raw = GoogleOAuthProtocol.base64Url("new".toByteArray())
        val input = args().put("expected_revision", "m2")
        failure("unknown") { GmailDraftDeletion(api, journal).prepare(input, token) }
        assertEquals(1, api.writes.size)
    }

    @Test fun failedMarkerResolutionWarnsAfterObservedAbsence() {
        val api = Api(); val journal = Journal().apply { resolveFailure = true }
        val result = perform(GmailDraftDeletion(api, journal))
        assertEquals("observed_absent", item(result).getString("verification"))
        assertTrue(item(result).getBoolean("retry_blocked")); assertTrue(result.has("safety_warning"))
        assertEquals(1, journal.intents.size)
    }

    @Test fun epochChangeInsideReservationIsProvablyPreDispatchAndClearsMarker() {
        val api = Api(); val journal = Journal().apply { reserveHook = { api.epoch++ } }
        failure { perform(GmailDraftDeletion(api, journal)) }
        assertTrue(api.writes.isEmpty()); assertTrue(journal.intents.isEmpty())
    }

    companion object {
        private fun header(name: String, value: String) = JSONObject().put("name", name).put("value", value)
        private fun message(body: String = "Draft body") = JSONObject().put("id", "m1")
            .put("payload", JSONObject().put("mimeType", "text/plain").put("headers", JSONArray()
                .put(header("To", "recipient@example.test")).put(header("Subject", "Draft subject")))
                .put("body", JSONObject().put("size", body.toByteArray().size).put("data", GoogleOAuthProtocol.base64Url(body.toByteArray()))))
    }
}

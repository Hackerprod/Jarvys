package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CancellationException

class GitHubNativeBridgeTest {
    private class FakeTransport : GitHubNativeTransport {
        val requests = mutableListOf<GitHubNativeRequest>()
        val headers = mutableListOf<String>()
        val responses = ArrayDeque<GitHubNativeResponse>()
        var onRequest: (GitHubNativeRequest) -> Unit = {}
        override fun execute(request: GitHubNativeRequest, authorizationHeader: String, token: CancellationToken): GitHubNativeResponse {
            requests += request
            headers += authorizationHeader
            onRequest(request)
            return responses.removeFirst()
        }
    }

    private val token get() = CancellationToken.cancellable()
    private val authorization = GitHubNativeAuthorization { "Bearer unit-test-credential" }
    private val base = "a".repeat(40)
    private val newHead = "b".repeat(40)
    private val timestamp = "2026-10-08T10:00:00Z"
    private fun repo(name: String = "octo/repo", id: String = "R_1") = JSONObject().put("id", id)
        .put("nameWithOwner", name).put("url", "https://github.com/$name")
    private fun response(data: JSONObject) = GitHubNativeResponse(200, JSONObject().put("data", data).toString())
    private fun createArgs() = JSONObject().put("owner", "octo").put("repo", "repo").put("categoryId", "DIC_1")
        .put("title", "A title").put("body", "The body")
    private fun updateArgs() = createArgs().apply { remove("categoryId"); put("discussionNumber", 7); put("expectedUpdatedAt", timestamp) }
    private fun commitArgs(content: String = "hello\n") = JSONObject().put("owner", "octo").put("repo", "repo")
        .put("branch", "feature/example").put("expectedHeadOid", base).put("message", "Change files\nDetails")
        .put("additions", JSONArray().put(JSONObject().put("path", "src/example.txt").put("content", content)))
        .put("deletions", JSONArray().put("old.txt"))
    private fun categoryPreflight(categoryRepo: JSONObject = repo()) = response(JSONObject()
        .put("repository", repo().put("hasDiscussionsEnabled", true))
        .put("node", JSONObject().put("__typename", "DiscussionCategory").put("id", "DIC_1").put("repository", categoryRepo)))
    private fun discussion() = JSONObject().put("id", "D_7").put("number", 7).put("url", "https://github.com/octo/repo/discussions/7")
        .put("updatedAt", timestamp).put("repository", repo()).put("category", JSONObject().put("id", "DIC_1"))
    private fun discussionPreflight(updatedAt: String = timestamp) = response(JSONObject().put("repository", repo()
        .put("discussion", discussion().put("viewerCanUpdate", true).put("updatedAt", updatedAt))))
    private fun discussionMutation(operation: String = "createDiscussion") = response(JSONObject().put(operation,
        JSONObject().put("discussion", discussion())))
    private fun branchPreflight(oid: String = base) = response(JSONObject().put("repository", repo().put("ref", ref()
        .put("target", JSONObject().put("__typename", "Commit").put("oid", oid)))))
    private fun ref() = JSONObject().put("id", "REF_1").put("name", "feature/example").put("prefix", "refs/heads/")
    private fun commitMutation() = response(JSONObject().put("createCommitOnBranch", JSONObject().put("ref", ref())
        .put("commit", JSONObject().put("id", "C_1").put("oid", newHead).put("url", "https://github.com/octo/repo/commit/$newHead")
            .put("repository", repo()).put("parents", JSONObject().put("nodes", JSONArray().put(JSONObject().put("oid", base)))))))
    private fun error(block: () -> Unit): GitHubNativeException {
        val exception = runCatching(block).exceptionOrNull()
        assertTrue("Expected sanitized native failure, got ${exception?.javaClass}", exception is GitHubNativeException)
        return exception as GitHubNativeException
    }

    @Test fun syntheticToolsStayDisabledAndOnlyExactCommitChecksAreReadOnly() {
        val bridge = GitHubNativeBridge(FakeTransport())
        assertEquals(setOf(GitHubNativeBridge.CREATE_DISCUSSION, GitHubNativeBridge.UPDATE_DISCUSSION, GitHubNativeBridge.COMMIT_FILES, GitHubNativeBridge.COMMIT_CHECKS),
            bridge.tools().map { it.wireName }.toSet())
        bridge.tools().forEach {
            assertFalse(it.enabled)
            assertEquals(it.wireName == GitHubNativeBridge.COMMIT_CHECKS, it.annotations?.readOnlyHint)
            assertEquals(it.wireName == GitHubNativeBridge.COMMIT_CHECKS, it.annotations?.idempotentHint)
            assertEquals(false, JSONObject(it.inputSchemaJson).getBoolean("additionalProperties"))
        }
        assertFalse(bridge.handles("push_files"))
        assertFalse(bridge.handles("graphql"))
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error { bridge.prepare("graphql", JSONObject()) }.reason)
    }

    @Test fun createsDiscussionUsingVerifiedIdsFixedQueryAndFrozenVariables() {
        val fake = FakeTransport().apply { responses += categoryPreflight(); responses += discussionMutation() }
        val bridge = GitHubNativeBridge(fake)
        val args = createArgs().put("title", "Exact \"title\"").put("body", "  Hola 👋\r\n東京\n")
        val prepared = bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, args)
        args.put("title", "Changed after approval").put("body", "Changed")
        var gateCalls = 0
        val result = bridge.execute(prepared, authorization, token) { gateCalls++; assertEquals(1, fake.requests.size) }
        assertEquals(1, gateCalls)
        assertEquals(2, fake.requests.size)
        fake.requests.forEach { assertEquals("POST", it.method); assertEquals("https://api.github.com/graphql", it.url) }
        val preflight = JSONObject(fake.requests[0].bodyJson)
        assertEquals(GitHubNativeBridge.CATEGORY_QUERY, preflight.getString("query"))
        assertEquals(setOf("owner", "repo", "categoryId"), preflight.getJSONObject("variables").keys().asSequence().toSet())
        val mutation = JSONObject(fake.requests[1].bodyJson)
        assertEquals(GitHubNativeBridge.CREATE_MUTATION, mutation.getString("query"))
        val input = mutation.getJSONObject("variables").getJSONObject("input")
        assertEquals("R_1", input.getString("repositoryId"))
        assertEquals("DIC_1", input.getString("categoryId"))
        assertEquals("Exact \"title\"", input.getString("title"))
        assertEquals("  Hola 👋\r\n東京\n", input.getString("body"))
        val mcp = result.getJSONObject("result")
        assertFalse(mcp.getBoolean("isError"))
        assertEquals("D_7", mcp.getJSONObject("structuredContent").getString("id"))
        assertEquals("text", mcp.getJSONArray("content").getJSONObject(0).getString("type"))
        assertFalse(result.toString().contains("unit-test-credential"))
        assertFalse(prepared.toString().contains("Hola"))
    }

    @Test fun categoryMustBelongToTheReviewedRepository() {
        val fake = FakeTransport().apply { responses += categoryPreflight(repo("other/repo", "R_2")) }
        val bridge = GitHubNativeBridge(fake)
        var gate = false
        val failure = error { bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, token) { gate = true } }
        assertEquals(GitHubNativeFailure.INVALID_RESPONSE, failure.reason)
        assertFalse(gate)
        assertEquals(1, fake.requests.size)
        assertFalse(fake.requests.single().mutation)
    }

    @Test fun discussionUpdateRequiresExpectedVersionAndReportsNonAtomicPrecondition() {
        val fake = FakeTransport().apply { responses += discussionPreflight(); responses += discussionMutation("updateDiscussion") }
        val bridge = GitHubNativeBridge(fake)
        val result = bridge.execute(bridge.prepare(GitHubNativeBridge.UPDATE_DISCUSSION, updateArgs()), authorization, token) {}
        val input = JSONObject(fake.requests.last().bodyJson).getJSONObject("variables").getJSONObject("input")
        assertEquals(GitHubNativeBridge.UPDATE_MUTATION, JSONObject(fake.requests.last().bodyJson).getString("query"))
        assertEquals(setOf("discussionId", "title", "body"), input.keys().asSequence().toSet())
        assertEquals("D_7", input.getString("discussionId"))
        val receipt = result.getJSONObject("result").getJSONObject("structuredContent")
        assertFalse(receipt.getBoolean("atomicPrecondition"))
        assertTrue(receipt.getString("concurrencyNotice").contains("no atomic"))

        val stale = FakeTransport().apply { responses += discussionPreflight("2026-10-08T10:01:00Z") }
        val staleBridge = GitHubNativeBridge(stale)
        var gate = false
        assertEquals(GitHubNativeFailure.CONFLICT, error {
            staleBridge.execute(staleBridge.prepare(GitHubNativeBridge.UPDATE_DISCUSSION, updateArgs()), authorization, token) { gate = true }
        }.reason)
        assertFalse(gate)
        assertEquals(1, stale.requests.size)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.UPDATE_DISCUSSION, updateArgs().apply { remove("expectedUpdatedAt") })
        }.reason)
    }

    @Test fun atomicCommitPreservesExactUtf8AndUsesExpectedHeadAndProperFileChanges() {
        val content = "\uFEFF  café e\u0301 中文 👩‍💻\r\n\tlast line\n"
        val fake = FakeTransport().apply { responses += branchPreflight(); responses += commitMutation() }
        val bridge = GitHubNativeBridge(fake)
        val result = bridge.execute(bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs(content)), authorization, token) {}
        val preflight = JSONObject(fake.requests[0].bodyJson)
        assertEquals(GitHubNativeBridge.BRANCH_QUERY, preflight.getString("query"))
        assertEquals("refs/heads/feature/example", preflight.getJSONObject("variables").getString("refName"))
        val mutation = JSONObject(fake.requests[1].bodyJson)
        assertEquals(GitHubNativeBridge.COMMIT_MUTATION, mutation.getString("query"))
        val input = mutation.getJSONObject("variables").getJSONObject("input")
        assertEquals(base, input.getString("expectedHeadOid"))
        assertEquals("octo/repo", input.getJSONObject("branch").getString("repositoryNameWithOwner"))
        assertEquals("feature/example", input.getJSONObject("branch").getString("branchName"))
        assertFalse(input.getJSONObject("branch").has("refName"))
        assertEquals("Change files", input.getJSONObject("message").getString("headline"))
        assertEquals("Details", input.getJSONObject("message").getString("body"))
        val files = input.getJSONObject("fileChanges")
        val addition = files.getJSONArray("additions").getJSONObject(0)
        assertEquals("src/example.txt", addition.getString("path"))
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), Base64.getDecoder().decode(addition.getString("contents")))
        assertFalse(addition.has("content"))
        assertEquals("old.txt", files.getJSONArray("deletions").getJSONObject(0).getString("path"))
        assertFalse(input.has("force"))
        val receipt = result.getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(newHead, receipt.getString("sha"))
        assertEquals(base, receipt.getString("previousHeadOid"))
        assertTrue(receipt.getBoolean("atomicPrecondition"))
    }

    @Test fun branchMovedOrMissingExpectedHeadNeverDispatchesWrite() {
        val fake = FakeTransport().apply { responses += branchPreflight(newHead) }
        val bridge = GitHubNativeBridge(fake)
        var gate = false
        assertEquals(GitHubNativeFailure.CONFLICT, error {
            bridge.execute(bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs()), authorization, token) { gate = true }
        }.reason)
        assertFalse(gate)
        assertEquals(1, fake.requests.size)
        listOf("", "main", "a".repeat(39), "z".repeat(40)).forEach { invalid ->
            assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
                bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("expectedHeadOid", invalid))
            }.reason)
        }
    }

    @Test fun rejectsUnknownFieldsWrongTypesTraversalDuplicateAndDangerousPaths() {
        val bridge = GitHubNativeBridge(FakeTransport())
        listOf("url", "query", "authorization", "force", "access_token").forEach { field ->
            assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
                bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put(field, "unexpected"))
            }.reason)
        }
        listOf("../file", "a/../file", "/abs", "a//b", "a\\b", "a/%2e%2e/b", ".git/config", "a/./b", "a/", "C:thing", "a\nfile").forEach { path ->
            assertEquals("Path $path", GitHubNativeFailure.INVALID_ARGUMENT, error {
                bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("deletions", JSONArray().put(path)))
            }.reason)
        }
        listOf(".env", "a/.env.production", "id_rsa", "credentials.json", "release.jks").forEach { path ->
            assertEquals(GitHubNativeFailure.SECRET_DETECTED, error {
                bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("deletions", JSONArray().put(path)))
            }.reason)
        }
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("deletions", JSONArray().put("src/example.txt")))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("deletions", JSONArray().put("src")))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.UPDATE_DISCUSSION, updateArgs().put("discussionNumber", "7"))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("additions", "not an array"))
        }.reason)
    }

    @Test fun payloadAndPathCountsAreBoundedAndMalformedUnicodeCannotBeSilentlyReplaced() {
        val bridge = GitHubNativeBridge(FakeTransport())
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs("x".repeat(GitHubNativeBridge.MAX_FILE_BYTES + 1)))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs("😀".repeat(GitHubNativeBridge.MAX_FILE_BYTES / 4 + 1)))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs("unpaired \uD800"))
        }.reason)
        val tooMany = JSONArray((1..GitHubNativeBridge.MAX_PATHS).map { "old$it.txt" })
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("deletions", tooMany))
        }.reason)
        val tooLarge = JSONArray((1..3).map { JSONObject().put("path", "$it.txt").put("content", "x".repeat(GitHubNativeBridge.MAX_FILE_BYTES)) })
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("additions", tooLarge))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs().put("additions", JSONArray()).put("deletions", JSONArray()))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs().put("title", "x".repeat(257)))
        }.reason)
    }

    @Test fun secretsAreRejectedBeforeTransportAndNeverAppearInExceptionText() {
        val fake = FakeTransport()
        val bridge = GitHubNativeBridge(fake)
        listOf("ghp_" + "a".repeat(36), "github_pat_" + "b".repeat(60), "-----BEGIN PRIVATE KEY-----", "api_key = \"long-secret-value\"")
            .forEach { secret ->
                val failure = error { bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs(secret)) }
                assertEquals(GitHubNativeFailure.SECRET_DETECTED, failure.reason)
                assertFalse(failure.toString().contains(secret))
            }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun cancelledDeniedAndExpiredAuthorizationCannotReachMutation() {
        val fake = FakeTransport()
        val bridge = GitHubNativeBridge(fake)
        val cancelled = token.apply { cancel() }
        assertTrue(runCatching {
            bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, cancelled) { fail() }
        }.exceptionOrNull() is CancellationException)
        assertTrue(fake.requests.isEmpty())
        val expired = GitHubNativeAuthorization { throw GitHubNativeException(GitHubNativeFailure.UNAUTHORIZED) }
        assertEquals(GitHubNativeFailure.UNAUTHORIZED, error {
            bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), expired, token) { fail() }
        }.reason)
        assertTrue(fake.requests.isEmpty())

        fake.responses += categoryPreflight()
        val stoppedAtGate = token
        assertTrue(runCatching {
            bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, stoppedAtGate) { stoppedAtGate.cancel() }
        }.exceptionOrNull() is CancellationException)
        assertEquals(1, fake.requests.size)

        fake.responses += categoryPreflight()
        assertEquals(GitHubNativeFailure.FORBIDDEN, error {
            bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, token) {
                throw GitHubNativeException(GitHubNativeFailure.FORBIDDEN)
            }
        }.reason)
        assertEquals(2, fake.requests.size)
    }

    @Test fun checksCredentialEpochAgainAfterPreflightAndGate() {
        val fake = FakeTransport().apply { responses += categoryPreflight() }
        val bridge = GitHubNativeBridge(fake)
        var epoch = 1
        val bound = GitHubNativeAuthorization {
            if (epoch != 1) throw GitHubNativeException(GitHubNativeFailure.UNAUTHORIZED)
            "Bearer unit-test-credential"
        }
        val failure = error { bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), bound, token) { epoch++ } }
        assertEquals(GitHubNativeFailure.UNAUTHORIZED, failure.reason)
        assertFalse(failure.ambiguousOutcome)
        assertEquals(1, fake.requests.size)
    }

    @Test fun readRetriesHonorRetryAfterBoundAndNeverRepeatGateOrMutation() {
        val fake = FakeTransport().apply {
            responses += GitHubNativeResponse(429, "", mapOf("Retry-After" to "1"))
            responses += GitHubNativeResponse(503, "")
            responses += categoryPreflight()
            responses += discussionMutation()
        }
        val delays = mutableListOf<Long>()
        val bridge = GitHubNativeBridge(fake, waitMillis = { delays += it })
        var gateCalls = 0
        bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, token) { gateCalls++ }
        assertEquals(1, gateCalls)
        assertEquals(1500L, delays.sum())
        assertEquals(3, fake.requests.count { !it.mutation })
        assertEquals(1, fake.requests.count { it.mutation })
        val limited = FakeTransport().apply { responses += GitHubNativeResponse(429, "", mapOf("Retry-After" to "60")) }
        val limitedBridge = GitHubNativeBridge(limited, waitMillis = { fail("Must not violate Retry-After") })
        assertEquals(GitHubNativeFailure.RATE_LIMITED, error {
            limitedBridge.execute(limitedBridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, token) { fail() }
        }.reason)
        assertEquals(1, limited.requests.size)
    }

    @Test fun typedHttpErrorsAreSanitizedAndDefinitiveWhileMutation503IsAmbiguous() {
        mapOf(401 to GitHubNativeFailure.UNAUTHORIZED, 403 to GitHubNativeFailure.FORBIDDEN, 404 to GitHubNativeFailure.NOT_FOUND,
            409 to GitHubNativeFailure.CONFLICT, 422 to GitHubNativeFailure.VALIDATION, 429 to GitHubNativeFailure.RATE_LIMITED,
            302 to GitHubNativeFailure.REDIRECT_REJECTED, 503 to GitHubNativeFailure.AMBIGUOUS_OUTCOME).forEach { (status, expected) ->
            val fake = FakeTransport().apply { responses += categoryPreflight(); responses += GitHubNativeResponse(status, "private credential in error") }
            val bridge = GitHubNativeBridge(fake, waitMillis = { fail("Mutation cannot retry") })
            val failure = error { bridge.execute(bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs()), authorization, token) {} }
            assertEquals(expected, failure.reason)
            assertEquals(status == 503, failure.ambiguousOutcome)
            assertFalse(failure.toString().contains("credential in error"))
            assertEquals(1, fake.requests.count { it.mutation })
        }
    }

    @Test fun mutationIoCancellationInvalidResponseAndPartialGraphQlBlockReplay() {
        for (scenario in listOf("io", "cancel", "partial", "malformed", "missing", "wrong-id", "unsafe-url", "oversized")) {
            val fake = FakeTransport().apply { responses += categoryPreflight() }
            val active = token
            when (scenario) {
                "io" -> fake.onRequest = { if (it.mutation) throw IOException("private secret") }
                "cancel" -> { fake.responses += discussionMutation(); fake.onRequest = { if (it.mutation) active.cancel() } }
                "partial" -> fake.responses += GitHubNativeResponse(200, JSONObject(discussionMutation().body)
                    .put("errors", JSONArray().put(JSONObject().put("type", "FORBIDDEN"))).toString())
                "malformed" -> fake.responses += GitHubNativeResponse(200, "not json")
                "missing" -> fake.responses += response(JSONObject())
                "wrong-id" -> fake.responses += response(JSONObject().put("createDiscussion", JSONObject().put("discussion", discussion().put("repository", repo("octo/repo", "R_WRONG")))))
                "unsafe-url" -> fake.responses += response(JSONObject().put("createDiscussion", JSONObject().put("discussion", discussion().put("url", "https://evil.example/7"))))
                "oversized" -> fake.responses += GitHubNativeResponse(200, "x".repeat(GitHubNativeBridge.MAX_RESPONSE_BYTES + 1))
            }
            val bridge = GitHubNativeBridge(fake)
            val prepared = bridge.prepare(GitHubNativeBridge.CREATE_DISCUSSION, createArgs())
            val failure = error { bridge.execute(prepared, authorization, active) {} }
            assertTrue("Scenario $scenario", failure.ambiguousOutcome)
            assertNull(failure.cause)
            assertEquals(1, fake.requests.count { it.mutation })
            assertEquals(GitHubNativeFailure.ALREADY_USED, error { bridge.execute(prepared, authorization, token) {} }.reason)
            assertEquals(1, fake.requests.count { it.mutation })
        }
    }

    @Test fun everyMutationGraphQlErrorIsAmbiguousEvenWithNullBubblingAndAuthorizationErrorTypes() {
        for (type in listOf("UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "STALE_DATA", "UNPROCESSABLE", "UNKNOWN")) {
            for (data in listOf(JSONObject.NULL, JSONObject().put("createCommitOnBranch", JSONObject.NULL))) {
                val fake = FakeTransport().apply {
                    responses += branchPreflight()
                    responses += GitHubNativeResponse(200, JSONObject().put("data", data)
                        .put("errors", JSONArray().put(JSONObject().put("type", type).put("message", "do not expose this")
                            .put("path", JSONArray().put("createCommitOnBranch").put("commit").put("repository")))).toString())
                }
                val bridge = GitHubNativeBridge(fake)
                val prepared = bridge.prepare(GitHubNativeBridge.COMMIT_FILES, commitArgs())
                val failure = error { bridge.execute(prepared, authorization, token) {} }
                assertEquals(GitHubNativeFailure.AMBIGUOUS_OUTCOME, failure.reason)
                assertTrue(failure.ambiguousOutcome)
                assertFalse(failure.toString().contains("do not expose"))
                assertEquals(1, fake.requests.count { it.mutation })
                assertEquals(GitHubNativeFailure.ALREADY_USED, error { bridge.execute(prepared, authorization, token) {} }.reason)
                assertEquals(2, fake.requests.size)
            }
        }
    }

    @Test fun nodeOnlyOfficialMcpCommentTargetsAreBoundToReviewedRepository() {
        val fake = FakeTransport().apply { responses += response(JSONObject().put("node", JSONObject()
            .put("__typename", "DiscussionComment").put("id", "DC_1").put("discussion", JSONObject().put("number", 7).put("repository", repo())))) }
        val args = JSONObject().put("owner", "octo").put("repo", "repo").put("method", "update")
            .put("commentNodeID", "DC_1").put("discussionNumber", 7)
        GitHubNativeBridge(fake).validateMcpTarget("discussion_comment_write", args, authorization, token)
        assertFalse(fake.requests.single().mutation)
        assertEquals(GitHubNativeBridge.COMMENT_TARGET_QUERY, JSONObject(fake.requests.single().bodyJson).getString("query"))
        val wrong = FakeTransport().apply { responses += response(JSONObject().put("node", JSONObject()
            .put("__typename", "DiscussionComment").put("id", "DC_1").put("discussion", JSONObject().put("number", 7).put("repository", repo("wrong/repo"))))) }
        assertEquals(GitHubNativeFailure.INVALID_RESPONSE, error {
            GitHubNativeBridge(wrong).validateMcpTarget("discussion_comment_write", args, authorization, token)
        }.reason)
    }

    @Test fun nodeOnlyOfficialMcpReviewThreadsMustMatchRepositoryAndPullNumber() {
        fun fixture() = response(JSONObject().put("node", JSONObject().put("__typename", "PullRequestReviewThread").put("id", "PRRT_1")
            .put("pullRequest", JSONObject().put("number", 12).put("repository", repo()))))
        val fake = FakeTransport().apply { responses += fixture(); responses += fixture() }
        val args = JSONObject().put("owner", "octo").put("repo", "repo").put("method", "resolve_thread")
            .put("threadId", "PRRT_1").put("pullNumber", 12)
        val bridge = GitHubNativeBridge(fake)
        bridge.validateMcpTarget("pull_request_review_write", args, authorization, token)
        assertEquals(GitHubNativeBridge.REVIEW_THREAD_TARGET_QUERY, JSONObject(fake.requests.single().bodyJson).getString("query"))
        args.put("pullNumber", 13)
        assertEquals(GitHubNativeFailure.INVALID_RESPONSE, error {
            bridge.validateMcpTarget("pull_request_review_write", args, authorization, token)
        }.reason)
        assertEquals(2, fake.requests.count { !it.mutation })
    }
    @Test fun nodeBindingHelperIgnoresOtherOfficialOperationsWithoutRequiringNodeFields() {
        val fake = FakeTransport()
        val bridge = GitHubNativeBridge(fake)
        val unusedAuthorization = GitHubNativeAuthorization { fail("Unrelated MCP writes must not use native preflight"); "" }
        bridge.validateMcpTarget("create_issue", JSONObject(), unusedAuthorization, token)
        bridge.validateMcpTarget("create_pull_request", JSONObject(), unusedAuthorization, token)
        bridge.validateMcpTarget("discussion_comment_write", JSONObject().put("method", "add"), unusedAuthorization, token)
        bridge.validateMcpTarget("pull_request_review_write", JSONObject().put("method", "submit"), unusedAuthorization, token)
        assertTrue(fake.requests.isEmpty())
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            bridge.validateMcpTarget("discussion_comment_write", JSONObject().put("method", "update"), unusedAuthorization, token)
        }.reason)
    }

    private fun checksArgs() = JSONObject().put("owner", "octo").put("repo", "repo").put("sha", base)
    private fun checksCommit(rollup: Any = JSONObject.NULL, sha: String = base) = JSONObject()
        .put("__typename", "Commit").put("id", "C_1").put("oid", sha)
        .put("url", "https://github.com/octo/repo/commit/$sha").put("repository", repo()).put("statusCheckRollup", rollup)
    private fun checksFixture(commit: JSONObject) = response(JSONObject().put("repository", repo().put("object", commit)))
    private fun checksRollup(hasNext: Boolean = true, cursor: Any = "Y3Vyc29yOjE=") = JSONObject().put("state", "PENDING")
        .put("contexts", JSONObject().put("totalCount", 3)
            .put("pageInfo", JSONObject().put("hasNextPage", hasNext).put("endCursor", cursor))
            .put("nodes", JSONArray()
                .put(JSONObject().put("__typename", "CheckRun").put("id", "CR_1").put("name", "Unit tests")
                    .put("status", "IN_PROGRESS").put("conclusion", JSONObject.NULL))
                .put(JSONObject().put("__typename", "StatusContext").put("id", "SC_1").put("context", "lint")
                    .put("state", "SUCCESS"))))

    @Test fun commitChecksQueryUsesExactOidAndReturnsBoundedPaginationWithoutMutationGate() {
        val fake = FakeTransport().apply { responses += checksFixture(checksCommit(checksRollup())) }
        val bridge = GitHubNativeBridge(fake)
        val args = checksArgs().put("perPage", 2).put("after", "Y3Vyc29yOjA=")
        val prepared = bridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, args)
        args.put("sha", newHead)
        val result = bridge.execute(prepared, authorization, token) { fail("Read must not reserve mutation journal") }
            .getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(1, fake.requests.size)
        assertFalse(fake.requests.single().mutation)
        val query = JSONObject(fake.requests.single().bodyJson)
        assertEquals(GitHubNativeBridge.COMMIT_CHECKS_QUERY, query.getString("query"))
        assertTrue(query.getString("query").contains("object(oid:"))
        assertFalse(query.getString("query").contains("pullRequest"))
        assertFalse(query.getString("query").contains("ref("))
        val variables = query.getJSONObject("variables")
        assertEquals(setOf("owner", "repo", "sha", "first", "after"), variables.keys().asSequence().toSet())
        assertEquals(base, variables.getString("sha"))
        assertEquals(2, variables.getInt("first"))
        assertEquals("Y3Vyc29yOjA=", variables.getString("after"))
        assertEquals(base, result.getString("sha"))
        assertEquals("PENDING", result.getString("rollupState"))
        assertEquals(2, result.getJSONArray("checks").length())
        assertEquals("IN_PROGRESS", result.getJSONArray("checks").getJSONObject(0).getString("status"))
        assertTrue(result.getJSONArray("checks").getJSONObject(0).isNull("conclusion"))
        assertEquals("SUCCESS", result.getJSONArray("checks").getJSONObject(1).getString("state"))
        assertEquals(3, result.getInt("totalCount"))
        assertTrue(result.getJSONObject("pageInfo").getBoolean("hasNextPage"))
        assertEquals("Y3Vyc29yOjE=", result.getJSONObject("pageInfo").getString("endCursor"))
    }

    @Test fun commitChecksWithoutRollupDoesNotClaimSuccessAndDefaultsToBoundedPage() {
        val fake = FakeTransport().apply { responses += checksFixture(checksCommit()) }
        val bridge = GitHubNativeBridge(fake)
        val result = bridge.execute(bridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, checksArgs()), authorization, token) { fail() }
            .getJSONObject("result").getJSONObject("structuredContent")
        assertTrue(result.isNull("rollupState"))
        assertEquals(0, result.getJSONArray("checks").length())
        assertFalse(result.getJSONObject("pageInfo").getBoolean("hasNextPage"))
        assertTrue(result.getString("notice").contains("not evidence of success"))
        val variables = JSONObject(fake.requests.single().bodyJson).getJSONObject("variables")
        assertTrue(variables.isNull("after"))
        assertEquals(GitHubNativeBridge.DEFAULT_CHECKS_PAGE, variables.getInt("first"))
    }

    @Test fun commitChecksRejectMovedShaWrongRepositoryUnboundedPagesAndInvalidCursors() {
        listOf(checksCommit(sha = newHead), checksCommit().put("__typename", "Blob"),
            checksCommit().put("repository", repo("other/repo")),
            checksCommit(checksRollup()).apply { getJSONObject("statusCheckRollup").getJSONObject("contexts")
                .getJSONObject("pageInfo").put("endCursor", JSONObject.NULL) }).forEach { commit ->
            val fake = FakeTransport().apply { responses += checksFixture(commit) }
            val bridge = GitHubNativeBridge(fake)
            val failure = error { bridge.execute(bridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, checksArgs()), authorization, token) { fail() } }
            assertEquals(GitHubNativeFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.ambiguousOutcome)
            assertFalse(fake.requests.single().mutation)
        }
        val bridge = GitHubNativeBridge(FakeTransport())
        listOf(checksArgs().put("sha", "main"), checksArgs().put("perPage", 0), checksArgs().put("perPage", 51),
            checksArgs().put("perPage", "2"), checksArgs().put("after", "cursor\nother"),
            checksArgs().put("after", "a".repeat(GitHubNativeBridge.MAX_CURSOR_CHARS + 1)),
            checksArgs().put("branch", "main")).forEach { args ->
            assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error { bridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, args) }.reason)
        }
        val overflow = FakeTransport().apply { responses += checksFixture(checksCommit(checksRollup())) }
        val overflowBridge = GitHubNativeBridge(overflow)
        assertEquals(GitHubNativeFailure.INVALID_RESPONSE, error {
            overflowBridge.execute(overflowBridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, checksArgs().put("perPage", 1)), authorization, token) { fail() }
        }.reason)
    }

    @Test fun checkMetadataCannotEchoCredentialsAndExternalLogUrlsAreNotRequestedOrReturned() {
        val secret = "ghp_" + "a".repeat(36)
        val rollup = checksRollup(false, JSONObject.NULL)
        rollup.getJSONObject("contexts").getJSONArray("nodes").getJSONObject(0)
            .put("name", secret).put("detailsUrl", "https://ci.example/?token=$secret")
        val fake = FakeTransport().apply { responses += checksFixture(checksCommit(rollup)) }
        val bridge = GitHubNativeBridge(fake)
        val result = bridge.execute(bridge.prepare(GitHubNativeBridge.COMMIT_CHECKS, checksArgs()), authorization, token) { fail() }
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains("detailsUrl"))
        assertFalse(fake.requests.single().bodyJson.contains("detailsUrl"))
        assertEquals("[redacted]", result.getJSONObject("result").getJSONObject("structuredContent")
            .getJSONArray("checks").getJSONObject(0).getString("name"))
    }

    @Test fun sharedSingleFileWriteGuardMatchesNativePathsSecretsBoundsAndUnicodeValidation() {
        GitHubNativeBridge.validateFileWrite("src/example.txt", "  exact UTF-8 👩‍💻\r\n")
        for (path in listOf("../escape", "/absolute", "a/../b", "a/%2e/b", ".git/config")) {
            assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error { GitHubNativeBridge.validateFileWrite(path, "safe") }.reason)
        }
        for (path in listOf(".env", "a/.env.production", "credentials.json", "release.jks")) {
            assertEquals(GitHubNativeFailure.SECRET_DETECTED, error { GitHubNativeBridge.validateFileWrite(path, "safe") }.reason)
        }
        for (secret in listOf("sk-proj-" + "a".repeat(30), "ghp_" + "a".repeat(36), "access_token=long-secret-value")) {
            assertEquals(GitHubNativeFailure.SECRET_DETECTED, error { GitHubNativeBridge.validateFileWrite("file.txt", secret) }.reason)
        }
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error {
            GitHubNativeBridge.validateFileWrite("file.txt", "x".repeat(GitHubNativeBridge.MAX_FILE_BYTES + 1))
        }.reason)
        assertEquals(GitHubNativeFailure.INVALID_ARGUMENT, error { GitHubNativeBridge.validateFileWrite("file.txt", "bad \uD800") }.reason)
    }

}

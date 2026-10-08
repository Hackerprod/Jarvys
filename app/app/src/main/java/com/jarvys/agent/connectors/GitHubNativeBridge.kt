package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.mcp.McpToolAnnotations
import com.jarvys.agent.mcp.McpToolConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.encoding.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** No credentials, mutable arguments, or server-supplied GraphQL are retained in a prepared call. */
class GitHubNativePreparedCall internal constructor(val wireName: String, val argumentsJson: String) {
    val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest((wireName + "\n" + argumentsJson).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    internal val used = AtomicBoolean(false)
    override fun toString() = "GitHubNativePreparedCall(tool=$wireName)"
}

/** The coordinator must check the captured account/authorization epoch on EVERY invocation. */
fun interface GitHubNativeAuthorization {
    fun authorizationHeader(token: CancellationToken): String
}

class GitHubNativeRequest internal constructor(val operation: String, val bodyJson: String, val mutation: Boolean) {
    val method: String get() = "POST"
    val url: String get() = GitHubNativeBridge.ENDPOINT
    override fun toString() = "GitHubNativeRequest(operation=$operation, mutation=$mutation)"
}

class GitHubNativeResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    override fun toString() = "GitHubNativeResponse(status=$status)"
}

fun interface GitHubNativeTransport {
    /** Must send exactly once, without redirects or automatic POST replay. */
    fun execute(request: GitHubNativeRequest, authorizationHeader: String, token: CancellationToken): GitHubNativeResponse
}

enum class GitHubNativeFailure {
    INVALID_ARGUMENT, SECRET_DETECTED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, VALIDATION,
    RATE_LIMITED, TRANSIENT, REDIRECT_REJECTED, RESPONSE_TOO_LARGE, INVALID_RESPONSE, NETWORK,
    ALREADY_USED, AMBIGUOUS_OUTCOME,
}

/** Deliberately excludes upstream response text, request arguments, headers and exception causes. */
class GitHubNativeException(
    val reason: GitHubNativeFailure,
    val status: Int? = null,
    val ambiguousOutcome: Boolean = false,
) : IllegalStateException("GitHub native API: " + when (reason) {
    GitHubNativeFailure.INVALID_ARGUMENT -> "invalid or unsupported arguments"
    GitHubNativeFailure.SECRET_DETECTED -> "possible credential detected; remove it before preparing this operation"
    GitHubNativeFailure.UNAUTHORIZED -> "authorization expired; reconnect GitHub"
    GitHubNativeFailure.FORBIDDEN -> "permission denied; check account, repository access and required scopes"
    GitHubNativeFailure.NOT_FOUND -> "target not found or not accessible to this account"
    GitHubNativeFailure.CONFLICT -> "target changed; read the latest state and prepare a new operation"
    GitHubNativeFailure.VALIDATION -> "GitHub rejected the operation; check its parameters and repository rules"
    GitHubNativeFailure.RATE_LIMITED -> "rate limited; retry later"
    GitHubNativeFailure.TRANSIENT -> "service temporarily unavailable"
    GitHubNativeFailure.REDIRECT_REJECTED -> "redirect refused to protect authorization"
    GitHubNativeFailure.RESPONSE_TOO_LARGE -> "response exceeded the allowed size"
    GitHubNativeFailure.INVALID_RESPONSE -> "unexpected response from GitHub"
    GitHubNativeFailure.NETWORK -> "connection failed"
    GitHubNativeFailure.ALREADY_USED -> "prepared operation already attempted; do not replay it"
    GitHubNativeFailure.AMBIGUOUS_OUTCOME -> "write may have completed; verify remote state before any new attempt"
})

/**
 * Narrow GitHub.com-only complement to the official MCP server. Original implementation against:
 * https://docs.github.com/en/graphql/reference/commits
 * https://docs.github.com/en/graphql/reference/discussions
 * No generic URL/query endpoint, OAuth flow, approval UI, force push, or mutation retry exists here.
 * Discussion updatedAt is only a preflight check: GitHub provides no atomic update precondition.
 */
class GitHubNativeBridge(
    private val transport: GitHubNativeTransport = GitHubNativeUrlConnectionTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val waitMillis: (Long) -> Unit = Thread::sleep,
) {
    fun handles(wireName: String) = wireName in TOOL_NAMES

    fun tools(): List<McpToolConfig> = listOf(
        definition(CREATE_DISCUSSION, "Create a GitHub Discussion after verifying its repository and category.",
            schema(common().put("categoryId", stringSchema(256)).put("title", stringSchema(MAX_TITLE_CHARS))
                .put("body", stringSchema(MAX_DISCUSSION_BYTES)), listOf("owner", "repo", "categoryId", "title", "body")), false),
        definition(UPDATE_DISCUSSION, "Replace a Discussion title and body. Requires expectedUpdatedAt. The timestamp check is preflight only, not atomic; a concurrent edit can still be overwritten.",
            schema(common().put("discussionNumber", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", Int.MAX_VALUE))
                .put("expectedUpdatedAt", stringSchema(64)).put("title", stringSchema(MAX_TITLE_CHARS))
                .put("body", stringSchema(MAX_DISCUSSION_BYTES)),
                listOf("owner", "repo", "discussionNumber", "expectedUpdatedAt", "title", "body")), true),
        definition(COMMIT_FILES, "Atomically commit UTF-8 text additions and deletions on an existing branch. Requires its exact expectedHeadOid; never force-pushes. Up to 50 paths and 1 MiB text total; credentials are rejected.",
            schema(common().put("branch", stringSchema(255)).put("expectedHeadOid", stringSchema(40))
                .put("message", stringSchema(MAX_MESSAGE_BYTES))
                .put("additions", JSONObject().put("type", "array").put("maxItems", MAX_PATHS)
                    .put("items", schema(JSONObject().put("path", stringSchema(MAX_PATH_BYTES))
                        .put("content", stringSchema(MAX_FILE_BYTES)), listOf("path", "content"))))
                .put("deletions", JSONObject().put("type", "array").put("maxItems", MAX_PATHS)
                    .put("items", stringSchema(MAX_PATH_BYTES))),
                listOf("owner", "repo", "branch", "expectedHeadOid", "message")), true),
        definition(COMMIT_CHECKS, "Read paginated check runs and status contexts for one exact commit SHA, never a mutable branch or pull-request head. A null rollup means no checks were reported, not success. This does not determine branch-protection requirements.",
            schema(common().put("sha", stringSchema(40)).put("after", stringSchema(MAX_CURSOR_CHARS))
                .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_CHECKS_PAGE)),
                listOf("owner", "repo", "sha")), false, readOnly = true),
    )

    /** Binds node-only official MCP mutations to the repository/number reviewed by the user. */
    fun validateMcpTarget(wireName: String, arguments: JSONObject, authorization: GitHubNativeAuthorization,
                          token: CancellationToken) {
        // This helper is called for every official MCP write. Only these node-only operations
        // need an additional repository binding; all other authorization stays with the caller.
        val selectedMethod = arguments.opt("method") as? String
        val needsCommentBinding = wireName == "discussion_comment_write" &&
            selectedMethod in setOf("update", "delete", "mark_answer", "unmark_answer")
        val needsThreadBinding = wireName == "pull_request_review_write" &&
            selectedMethod in setOf("resolve_thread", "unresolve_thread")
        if (!needsCommentBinding && !needsThreadBinding) return
        token.throwIfCancelled()
        val owner = string(arguments, "owner", 39)
        val repo = string(arguments, "repo", 100)
        requireArgument(OWNER.matches(owner) && REPO.matches(repo) && repo !in setOf(".", ".."))
        val fullName = "$owner/$repo"
        val method = string(arguments, "method", 40)
        val query: String
        val nodeKey: String
        val operation: String
        when {
            wireName == "discussion_comment_write" && method in setOf("update", "delete", "mark_answer", "unmark_answer") -> {
                query = COMMENT_TARGET_QUERY
                nodeKey = "commentNodeID"
                operation = "JarvysDiscussionCommentTarget"
            }
            wireName == "pull_request_review_write" && method in setOf("resolve_thread", "unresolve_thread") -> {
                query = REVIEW_THREAD_TARGET_QUERY
                nodeKey = "threadId"
                operation = "JarvysReviewThreadTarget"
            }
            else -> invalidArgument()
        }
        val id = nodeId(string(arguments, nodeKey, 256))
        val data = request(operation, query, JSONObject().put("id", id), false, authorization, token)
        val node = data.optJSONObject("node") ?: throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
        requireResponse(node.optString("id") == id)
        if (nodeKey == "commentNodeID") {
            requireResponse(node.optString("__typename") == "DiscussionComment")
            val discussion = node.optJSONObject("discussion") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
            repository(discussion.optJSONObject("repository"), fullName)
            if (arguments.has("discussionNumber")) requireResponse(positiveNumber(arguments, "discussionNumber") == discussion.optInt("number"))
        } else {
            requireResponse(node.optString("__typename") == "PullRequestReviewThread")
            val pullRequest = node.optJSONObject("pullRequest") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
            repository(pullRequest.optJSONObject("repository"), fullName)
            requireResponse(positiveNumber(arguments, "pullNumber") == pullRequest.optInt("number"))
        }
    }

    /** Local-only validation and immutable snapshot. Preparation does not authorize a write. */
    fun prepare(wireName: String, arguments: JSONObject): GitHubNativePreparedCall {
        requireArgument(handles(wireName))
        val allowed = when (wireName) {
            CREATE_DISCUSSION -> setOf("owner", "repo", "categoryId", "title", "body")
            UPDATE_DISCUSSION -> setOf("owner", "repo", "discussionNumber", "expectedUpdatedAt", "title", "body")
            COMMIT_CHECKS -> setOf("owner", "repo", "sha", "after", "perPage")
            else -> setOf("owner", "repo", "branch", "expectedHeadOid", "message", "additions", "deletions")
        }
        exactKeys(arguments, allowed)
        val owner = string(arguments, "owner", 39)
        val repo = string(arguments, "repo", 100)
        requireArgument(OWNER.matches(owner) && REPO.matches(repo) && repo !in setOf(".", ".."))
        val frozen = JSONObject().put("owner", owner).put("repo", repo)
        when (wireName) {
            COMMIT_CHECKS -> {
                val sha = string(arguments, "sha", 40)
                requireArgument(SHA.matches(sha))
                frozen.put("sha", sha.lowercase(Locale.ROOT))
                val perPage = if (arguments.has("perPage")) positiveNumber(arguments, "perPage") else DEFAULT_CHECKS_PAGE
                requireArgument(perPage in 1..MAX_CHECKS_PAGE)
                frozen.put("perPage", perPage)
                if (arguments.has("after")) {
                    val after = string(arguments, "after", MAX_CURSOR_CHARS)
                    requireArgument(CURSOR.matches(after))
                    frozen.put("after", after)
                }
            }
            CREATE_DISCUSSION -> {
                frozen.put("categoryId", nodeId(string(arguments, "categoryId", 256)))
                discussionText(arguments, frozen)
            }
            UPDATE_DISCUSSION -> {
                val number = arguments.opt("discussionNumber")
                requireArgument(number is Int || number is Long)
                val value = (number as Number).toLong()
                requireArgument(value in 1..Int.MAX_VALUE.toLong())
                frozen.put("discussionNumber", value.toInt())
                val timestamp = string(arguments, "expectedUpdatedAt", 64)
                requireArgument(runCatching { Instant.parse(timestamp) }.isSuccess)
                frozen.put("expectedUpdatedAt", timestamp)
                discussionText(arguments, frozen)
            }
            COMMIT_FILES -> {
                val branch = string(arguments, "branch", 255)
                requireArgument(validBranch(branch))
                frozen.put("branch", branch)
                val sha = string(arguments, "expectedHeadOid", 40)
                requireArgument(SHA.matches(sha))
                frozen.put("expectedHeadOid", sha.lowercase(Locale.ROOT))
                val message = string(arguments, "message", MAX_MESSAGE_BYTES)
                requireArgument(message.isNotBlank() && message.substringBefore('\n').isNotBlank())
                frozen.put("message", message)
                val additions = array(arguments, "additions")
                val deletions = array(arguments, "deletions")
                requireArgument(additions.length() + deletions.length() in 1..MAX_PATHS)
                val paths = HashSet<String>()
                val cleanAdditions = JSONArray()
                val cleanDeletions = JSONArray()
                var total = 0
                for (i in 0 until additions.length()) {
                    val addition = additions.opt(i) as? JSONObject ?: invalidArgument()
                    exactKeys(addition, setOf("path", "content"))
                    val path = filePath(string(addition, "path", MAX_PATH_BYTES))
                    requireArgument(paths.add(path))
                    val content = string(addition, "content", MAX_FILE_BYTES, allowEmpty = true)
                    total += utf8(content).size
                    requireArgument(total <= MAX_TOTAL_FILE_BYTES)
                    cleanAdditions.put(JSONObject().put("path", path).put("content", content))
                }
                for (i in 0 until deletions.length()) {
                    val path = deletions.opt(i) as? String ?: invalidArgument()
                    requireArgument(utf8(path).size <= MAX_PATH_BYTES)
                    filePath(path)
                    requireArgument(paths.add(path))
                    cleanDeletions.put(path)
                }
                // File/directory collisions cannot express a valid atomic FileChanges operation.
                requireArgument(paths.none { path -> paths.any { it != path && it.startsWith("$path/") } })
                frozen.put("additions", cleanAdditions).put("deletions", cleanDeletions)
            }
        }
        val serialized = frozen.toString()
        requireArgument(utf8(serialized).size <= MAX_REQUEST_BYTES)
        return GitHubNativePreparedCall(wireName, serialized)
    }

    /**
     * Parent coordinator owns approval, policy, epoch and the durable replay journal. beforeMutation
     * is mandatory and called exactly once only after every read-only preflight passes. It must
     * recheck authority and reserve the journal before returning. Any exception aborts dispatch.
     */
    fun execute(
        prepared: GitHubNativePreparedCall,
        authorization: GitHubNativeAuthorization,
        token: CancellationToken,
        beforeMutation: () -> Unit,
    ): JSONObject {
        token.throwIfCancelled()
        if (!prepared.used.compareAndSet(false, true)) throw GitHubNativeException(GitHubNativeFailure.ALREADY_USED)
        // Revalidation also prevents internally constructed unsupported prepared calls.
        val call = prepare(prepared.wireName, JSONObject(prepared.argumentsJson))
        val args = JSONObject(call.argumentsJson)
        val fullName = args.getString("owner") + "/" + args.getString("repo")
        if (call.wireName == COMMIT_CHECKS) return mcpResult(commitChecks(args, fullName, authorization, token))
        val variables = JSONObject().put("owner", args.getString("owner")).put("repo", args.getString("repo"))
        val input = JSONObject()
        val operation: String
        val query: String
        var repositoryId: String
        var discussionId: String? = null
        when (call.wireName) {
            CREATE_DISCUSSION -> {
                variables.put("categoryId", args.getString("categoryId"))
                val data = request("JarvysDiscussionCategory", CATEGORY_QUERY, variables, false, authorization, token)
                val repository = repository(data.optJSONObject("repository"), fullName)
                repositoryId = repository.getString("id")
                if (!repository.optBoolean("hasDiscussionsEnabled")) throw GitHubNativeException(GitHubNativeFailure.VALIDATION)
                val category = data.optJSONObject("node") ?: throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
                requireResponse(category.optString("__typename") == "DiscussionCategory" && category.optString("id") == args.getString("categoryId"))
                requireResponse(repository(category.optJSONObject("repository"), fullName).getString("id") == repositoryId)
                input.put("repositoryId", repositoryId).put("categoryId", category.getString("id"))
                    .put("title", args.getString("title")).put("body", args.getString("body"))
                operation = "createDiscussion"
                query = CREATE_MUTATION
            }
            UPDATE_DISCUSSION -> {
                variables.put("number", args.getInt("discussionNumber"))
                val data = request("JarvysDiscussionIdentity", DISCUSSION_QUERY, variables, false, authorization, token)
                val repository = repository(data.optJSONObject("repository"), fullName)
                repositoryId = repository.getString("id")
                val discussion = repository.optJSONObject("discussion") ?: throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
                requireResponse(discussion.optInt("number") == args.getInt("discussionNumber"))
                discussionId = responseNodeId(discussion.opt("id"))
                requireResponse(repository(discussion.optJSONObject("repository"), fullName).getString("id") == repositoryId)
                verifiedUrl(discussion.optString("url"), "$fullName/discussions/${args.getInt("discussionNumber")}")
                val updatedAt = responseInstant(discussion.optString("updatedAt"))
                if (updatedAt != Instant.parse(args.getString("expectedUpdatedAt"))) throw GitHubNativeException(GitHubNativeFailure.CONFLICT)
                if (!discussion.optBoolean("viewerCanUpdate")) throw GitHubNativeException(GitHubNativeFailure.FORBIDDEN)
                input.put("discussionId", discussionId).put("title", args.getString("title")).put("body", args.getString("body"))
                operation = "updateDiscussion"
                query = UPDATE_MUTATION
            }
            COMMIT_FILES -> {
                variables.put("refName", "refs/heads/" + args.getString("branch"))
                val data = request("JarvysBranchIdentity", BRANCH_QUERY, variables, false, authorization, token)
                val repository = repository(data.optJSONObject("repository"), fullName)
                repositoryId = repository.getString("id")
                val ref = repository.optJSONObject("ref") ?: throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
                requireResponse(ref.optString("name") == args.getString("branch") && ref.optString("prefix") == "refs/heads/")
                responseNodeId(ref.opt("id"))
                val target = ref.optJSONObject("target") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
                requireResponse(target.optString("__typename") == "Commit" && SHA.matches(target.optString("oid")))
                if (!target.getString("oid").equals(args.getString("expectedHeadOid"), true)) throw GitHubNativeException(GitHubNativeFailure.CONFLICT)
                val additions = JSONArray()
                val deletions = JSONArray()
                val sourceAdditions = args.getJSONArray("additions")
                for (i in 0 until sourceAdditions.length()) {
                    val file = sourceAdditions.getJSONObject(i)
                    additions.put(JSONObject().put("path", file.getString("path"))
                        .put("contents", Base64.Default.encode(utf8(file.getString("content")))))
                }
                val sourceDeletions = args.getJSONArray("deletions")
                for (i in 0 until sourceDeletions.length()) deletions.put(JSONObject().put("path", sourceDeletions.getString(i)))
                val message = args.getString("message")
                val messageInput = JSONObject().put("headline", message.substringBefore('\n'))
                if ('\n' in message) messageInput.put("body", message.substringAfter('\n'))
                input.put("branch", JSONObject().put("repositoryNameWithOwner", repository.getString("nameWithOwner"))
                    .put("branchName", args.getString("branch")))
                    .put("expectedHeadOid", args.getString("expectedHeadOid"))
                    .put("message", messageInput)
                    .put("fileChanges", JSONObject().put("additions", additions).put("deletions", deletions))
                operation = "createCommitOnBranch"
                query = COMMIT_MUTATION
            }
            else -> invalidArgument()
        }
        val mutationVariables = JSONObject().put("input", input)
        requireArgument(utf8(JSONObject().put("query", query).put("variables", mutationVariables).toString()).size <= MAX_REQUEST_BYTES)
        token.throwIfCancelled()
        // The gate is outside request(), so it can never run as part of a read retry.
        beforeMutation()
        token.throwIfCancelled()
        val data = request(operation, query, mutationVariables, true, authorization, token)
        val result = try {
            val payload = data.optJSONObject(operation) ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
            if (call.wireName == COMMIT_FILES) commitResult(payload, args, repositoryId, fullName)
            else discussionResult(payload, args, repositoryId, fullName, discussionId)
        } catch (_: Exception) { throw ambiguous() }
        return mcpResult(result)
    }

    private fun mcpResult(result: JSONObject): JSONObject = JSONObject().put("result", JSONObject().put("isError", false)
        .put("structuredContent", result).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString()))))

    private fun commitChecks(args: JSONObject, fullName: String, authorization: GitHubNativeAuthorization,
                             token: CancellationToken): JSONObject {
        val variables = JSONObject().put("owner", args.getString("owner")).put("repo", args.getString("repo"))
            .put("sha", args.getString("sha")).put("first", args.getInt("perPage"))
            .put("after", args.opt("after") ?: JSONObject.NULL)
        val data = request("JarvysCommitChecks", COMMIT_CHECKS_QUERY, variables, false, authorization, token)
        val repository = repository(data.optJSONObject("repository"), fullName)
        val commit = repository.optJSONObject("object") ?: throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
        requireResponse(commit.optString("__typename") == "Commit" && commit.optString("oid").equals(args.getString("sha"), true))
        val id = responseNodeId(commit.opt("id"))
        requireResponse(repository(commit.optJSONObject("repository"), fullName).getString("id") == repository.getString("id"))
        val url = verifiedUrl(commit.optString("url"), "$fullName/commit/${args.getString("sha")}")
        val result = JSONObject().put("sha", args.getString("sha")).put("id", id).put("url", url)
            .put("repository", fullName).put("repositoryId", repository.getString("id"))
        requireResponse(commit.has("statusCheckRollup"))
        val rollup = commit.optJSONObject("statusCheckRollup")
        if (rollup == null) {
            requireResponse(commit.isNull("statusCheckRollup"))
            return result.put("rollupState", JSONObject.NULL).put("checks", JSONArray()).put("totalCount", 0)
                .put("pageInfo", JSONObject().put("hasNextPage", false).put("endCursor", JSONObject.NULL))
                .put("notice", "No checks were reported for this SHA; this is not evidence of success.")
        }
        val state = responseEnum(rollup, "state", STATUS_STATES)
        val contexts = rollup.optJSONObject("contexts") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        val nodes = contexts.optJSONArray("nodes") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        requireResponse(nodes.length() <= args.getInt("perPage"))
        val totalCount = contexts.opt("totalCount")
        requireResponse(totalCount is Int && totalCount >= nodes.length())
        val checks = JSONArray()
        val ids = HashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
            val nodeId = responseNodeId(node.opt("id"))
            requireResponse(ids.add(nodeId))
            val type = node.optString("__typename")
            val check = JSONObject().put("id", nodeId).put("type", type)
            when (type) {
                "CheckRun" -> {
                    check.put("name", checkLabel(node.opt("name")))
                        .put("status", responseEnum(node, "status", CHECK_STATUSES))
                    requireResponse(node.has("conclusion"))
                    check.put("conclusion", if (node.isNull("conclusion")) JSONObject.NULL else responseEnum(node, "conclusion", CHECK_CONCLUSIONS))
                }
                "StatusContext" -> check.put("name", checkLabel(node.opt("context")))
                    .put("state", responseEnum(node, "state", STATUS_STATES))
                else -> throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
            }
            checks.put(check)
        }
        val page = contexts.optJSONObject("pageInfo") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        val hasNext = page.opt("hasNextPage")
        requireResponse(hasNext is Boolean && page.has("endCursor"))
        val cursor = page.opt("endCursor")
        requireResponse(cursor == JSONObject.NULL || cursor is String && CURSOR.matches(cursor) && !SECRET.containsMatchIn(cursor))
        requireResponse(hasNext != true || cursor is String && cursor != args.optString("after") && nodes.length() > 0)
        return result.put("rollupState", state).put("checks", checks).put("totalCount", totalCount)
            .put("pageInfo", JSONObject().put("hasNextPage", hasNext).put("endCursor", cursor))
    }

    private fun discussionResult(payload: JSONObject, args: JSONObject, repoId: String, fullName: String, expectedId: String?): JSONObject {
        val discussion = payload.optJSONObject("discussion") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        val id = responseNodeId(discussion.opt("id"))
        requireResponse(expectedId == null || id == expectedId)
        val number = discussion.optInt("number")
        requireResponse(number > 0 && (expectedId == null || number == args.getInt("discussionNumber")))
        requireResponse(repository(discussion.optJSONObject("repository"), fullName).getString("id") == repoId)
        if (expectedId == null) requireResponse(discussion.optJSONObject("category")?.optString("id") == args.getString("categoryId"))
        val url = verifiedUrl(discussion.optString("url"), "$fullName/discussions/$number")
        responseInstant(discussion.optString("updatedAt"))
        val result = JSONObject().put("id", id).put("number", number).put("url", url)
            .put("repositoryId", repoId).put("repository", fullName).put("updatedAt", discussion.getString("updatedAt"))
        if (expectedId != null) result.put("atomicPrecondition", false).put("concurrencyNotice", DISCUSSION_CONCURRENCY_NOTICE)
        return result
    }

    private fun commitResult(payload: JSONObject, args: JSONObject, repoId: String, fullName: String): JSONObject {
        val commit = payload.optJSONObject("commit") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        val id = responseNodeId(commit.opt("id"))
        val oid = commit.optString("oid")
        requireResponse(SHA.matches(oid) && !oid.equals(args.getString("expectedHeadOid"), true))
        requireResponse(repository(commit.optJSONObject("repository"), fullName).getString("id") == repoId)
        val parents = commit.optJSONObject("parents")?.optJSONArray("nodes")
        requireResponse(parents != null && parents.length() == 1 && parents.optJSONObject(0)?.optString("oid")?.equals(args.getString("expectedHeadOid"), true) == true)
        val ref = payload.optJSONObject("ref") ?: throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        responseNodeId(ref.opt("id"))
        requireResponse(ref.optString("name") == args.getString("branch") && ref.optString("prefix") == "refs/heads/")
        val url = verifiedUrl(commit.optString("url"), "$fullName/commit/$oid")
        return JSONObject().put("id", id).put("sha", oid).put("url", url).put("repositoryId", repoId)
            .put("repository", fullName).put("branch", args.getString("branch"))
            .put("previousHeadOid", args.getString("expectedHeadOid")).put("atomicPrecondition", true)
    }

    private fun request(operation: String, query: String, variables: JSONObject, mutation: Boolean,
                        authorization: GitHubNativeAuthorization, token: CancellationToken): JSONObject {
        val request = GitHubNativeRequest(operation, JSONObject().put("query", query).put("variables", variables).toString(), mutation)
        var attempt = 0
        while (true) {
            token.throwIfCancelled()
            val header = authorization.authorizationHeader(token)
            if (!validAuthorization(header)) throw GitHubNativeException(GitHubNativeFailure.UNAUTHORIZED)
            token.throwIfCancelled()
            val response = try { transport.execute(request, header, token) } catch (error: Exception) {
                if (mutation) throw ambiguous()
                token.throwIfCancelled()
                if (error is GitHubNativeException) throw error
                throw GitHubNativeException(GitHubNativeFailure.NETWORK)
            }
            if (mutation && (token.isCancelled || response.status in 500..599 || response.status == 408)) throw ambiguous(response.status)
            token.throwIfCancelled()
            if (!mutation && (response.status == 429 || response.status in 500..599)) {
                val delay = retryDelay(response, attempt)
                if (attempt < MAX_READ_RETRIES && delay != null) { attempt++; pause(delay, token); continue }
            }
            if (response.status !in 200..299) throw httpFailure(response.status)
            if (response.body.length > MAX_RESPONSE_BYTES || utf8ResponseSize(response.body) > MAX_RESPONSE_BYTES) {
                if (mutation) throw ambiguous(response.status)
                throw GitHubNativeException(GitHubNativeFailure.RESPONSE_TOO_LARGE, response.status)
            }
            val json = try { JSONObject(response.body) } catch (_: Exception) {
                if (mutation) throw ambiguous(response.status)
                throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE, response.status)
            }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                // Resolver errors can null-bubble the entire data tree after a mutation executed.
                // Even UNAUTHORIZED/FORBIDDEN cannot prove non-execution at HTTP 200.
                if (mutation) throw ambiguous(response.status)
                val failure = graphqlFailure(errors)
                if (!mutation && failure == GitHubNativeFailure.RATE_LIMITED && attempt < MAX_READ_RETRIES) {
                    val delay = retryDelay(response, attempt)
                    if (delay != null) { attempt++; pause(delay, token); continue }
                }
                throw GitHubNativeException(failure, response.status)
            }
            if (json.has("errors") && errors == null) {
                if (mutation) throw ambiguous(response.status)
                throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE, response.status)
            }
            return json.optJSONObject("data") ?: if (mutation) throw ambiguous(response.status)
                else throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE, response.status)
        }
    }

    private fun retryDelay(response: GitHubNativeResponse, attempt: Int): Long? {
        val raw = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
        val delay = if (raw == null) 250L * (attempt + 1) else raw.toLongOrNull()?.let { seconds ->
            if (seconds !in 0..MAX_RETRY_DELAY_MILLIS / 1000) return null
            seconds * 1000
        } ?: runCatching { (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis()).coerceAtLeast(0) }.getOrNull() ?: return null
        return delay.takeIf { it in 0..MAX_RETRY_DELAY_MILLIS }
    }

    private fun pause(millis: Long, token: CancellationToken) {
        var remaining = millis
        while (remaining > 0) {
            token.throwIfCancelled()
            val step = minOf(remaining, 100)
            try { waitMillis(step) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                token.throwIfCancelled()
                throw GitHubNativeException(GitHubNativeFailure.NETWORK)
            }
            remaining -= step
        }
        token.throwIfCancelled()
    }

    companion object {
        const val ENDPOINT = "https://api.github.com/graphql"
        const val CREATE_DISCUSSION = "jarvys_create_discussion"
        const val UPDATE_DISCUSSION = "jarvys_update_discussion"
        const val COMMIT_FILES = "jarvys_commit_files"
        const val COMMIT_CHECKS = "jarvys_commit_checks"
        const val MAX_CHECKS_PAGE = 50
        const val DEFAULT_CHECKS_PAGE = 25
        const val MAX_CURSOR_CHARS = 512
        const val MAX_PATHS = 50
        const val MAX_PATH_BYTES = 1024
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_TOTAL_FILE_BYTES = 1024 * 1024
        const val MAX_DISCUSSION_BYTES = 64 * 1024
        const val MAX_TITLE_CHARS = 256
        const val MAX_MESSAGE_BYTES = 4096
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val DISCUSSION_CONCURRENCY_NOTICE = "expectedUpdatedAt was checked before the write; GitHub Discussion updates have no atomic compare-and-swap and may race with another edit."
        private const val MAX_READ_RETRIES = 2
        private const val MAX_RETRY_DELAY_MILLIS = 5_000L
        private val TOOL_NAMES = setOf(CREATE_DISCUSSION, UPDATE_DISCUSSION, COMMIT_FILES, COMMIT_CHECKS)
        private val CURSOR = Regex("[A-Za-z0-9_+=/:-]{1,$MAX_CURSOR_CHARS}")
        private val STATUS_STATES = setOf("ERROR", "EXPECTED", "FAILURE", "PENDING", "SUCCESS")
        private val CHECK_STATUSES = setOf("COMPLETED", "IN_PROGRESS", "PENDING", "QUEUED", "REQUESTED", "WAITING")
        private val CHECK_CONCLUSIONS = setOf("ACTION_REQUIRED", "CANCELLED", "FAILURE", "NEUTRAL", "SKIPPED", "STALE", "STARTUP_FAILURE", "SUCCESS", "TIMED_OUT")
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val REPO = Regex("[A-Za-z0-9_.-]{1,100}")
        private val NODE_ID = Regex("[A-Za-z0-9_+=/-]{1,256}")
        private val SHA = Regex("[0-9a-fA-F]{40}")
        private val SECRET = Regex("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|(?:sk-(?:proj-)?)[A-Za-z0-9_-]{20,}|(?i:Bearer)[ \\t]+[A-Za-z0-9._~+/-]{16,}|(?i:(?:password|passwd|client_secret|access_token|refresh_token|api[_-]?key|aws_secret_access_key))[\\\"']?\\s*[:=]\\s*[\\\"'][^\\\"'\\r\\n]{8,}[\\\"'])")
        private val SECRET_ASSIGNMENT = Regex("(?im)(?:^|[\\s{;,])(?:password|passwd|client_secret|access_token|refresh_token|api[_-]?key|aws_secret_access_key)\\s*[:=]\\s*[A-Za-z0-9_+/=.-]{8,}(?:$|[\\s;,}])")
        internal const val COMMIT_CHECKS_QUERY = "query JarvysCommitChecks(\$owner: String!, \$repo: String!, \$sha: GitObjectID!, \$first: Int!, \$after: String) { repository(owner: \$owner, name: \$repo) { id nameWithOwner url object(oid: \$sha) { __typename oid ... on Commit { id url repository { id nameWithOwner url } statusCheckRollup { state contexts(first: \$first, after: \$after) { nodes { __typename ... on CheckRun { id name status conclusion } ... on StatusContext { id context state } } totalCount pageInfo { hasNextPage endCursor } } } } } } }"
        internal const val COMMENT_TARGET_QUERY = "query JarvysDiscussionCommentTarget(\$id: ID!) { node(id: \$id) { __typename ... on DiscussionComment { id discussion { number repository { id nameWithOwner url } } } } }"
        internal const val REVIEW_THREAD_TARGET_QUERY = "query JarvysReviewThreadTarget(\$id: ID!) { node(id: \$id) { __typename ... on PullRequestReviewThread { id pullRequest { number repository { id nameWithOwner url } } } } }"
        internal const val CATEGORY_QUERY = "query JarvysDiscussionCategory(\$owner: String!, \$repo: String!, \$categoryId: ID!) { repository(owner: \$owner, name: \$repo) { id nameWithOwner url hasDiscussionsEnabled } node(id: \$categoryId) { __typename ... on DiscussionCategory { id repository { id nameWithOwner url } } } }"
        internal const val DISCUSSION_QUERY = "query JarvysDiscussionIdentity(\$owner: String!, \$repo: String!, \$number: Int!) { repository(owner: \$owner, name: \$repo) { id nameWithOwner url discussion(number: \$number) { id number url updatedAt viewerCanUpdate repository { id nameWithOwner url } } } }"
        internal const val BRANCH_QUERY = "query JarvysBranchIdentity(\$owner: String!, \$repo: String!, \$refName: String!) { repository(owner: \$owner, name: \$repo) { id nameWithOwner url ref(qualifiedName: \$refName) { id name prefix target { __typename oid } } } }"
        internal const val CREATE_MUTATION = "mutation JarvysCreateDiscussion(\$input: CreateDiscussionInput!) { createDiscussion(input: \$input) { discussion { id number url updatedAt category { id } repository { id nameWithOwner url } } } }"
        internal const val UPDATE_MUTATION = "mutation JarvysUpdateDiscussion(\$input: UpdateDiscussionInput!) { updateDiscussion(input: \$input) { discussion { id number url updatedAt repository { id nameWithOwner url } } } }"
        internal const val COMMIT_MUTATION = "mutation JarvysCommitFiles(\$input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: \$input) { commit { id oid url repository { id nameWithOwner url } parents(first: 2) { nodes { oid } } } ref { id name prefix } } }"

        /** Shared guard for equivalent official MCP single-file writes, before approval/dispatch. */
        fun validateFileWrite(path: String, content: String) {
            val source = JSONObject().put("path", path).put("content", content)
            filePath(string(source, "path", MAX_PATH_BYTES))
            string(source, "content", MAX_FILE_BYTES, allowEmpty = true)
        }

        private fun definition(name: String, description: String, schema: JSONObject, destructive: Boolean, readOnly: Boolean = false) =
            McpToolConfig(name, name, description, schema.toString(), enabled = false,
                annotations = McpToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, idempotentHint = readOnly, openWorldHint = true))
        private fun common() = JSONObject().put("owner", stringSchema(39)).put("repo", stringSchema(100))
        private fun stringSchema(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        private fun schema(properties: JSONObject, required: List<String>) = JSONObject().put("type", "object")
            .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
        private fun invalidArgument(): Nothing = throw GitHubNativeException(GitHubNativeFailure.INVALID_ARGUMENT)
        private fun requireArgument(condition: Boolean) { if (!condition) invalidArgument() }
        private fun requireResponse(condition: Boolean) { if (!condition) throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE) }
        private fun exactKeys(json: JSONObject, allowed: Set<String>) { requireArgument(json.keys().asSequence().all { it in allowed }) }
        private fun positiveNumber(json: JSONObject, key: String): Int {
            val value = json.opt(key)
            requireArgument(value is Int || value is Long)
            val number = (value as Number).toLong()
            requireArgument(number in 1..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        private fun array(json: JSONObject, key: String): JSONArray = if (!json.has(key)) JSONArray() else json.optJSONArray(key) ?: invalidArgument()
        private fun string(json: JSONObject, key: String, maxBytes: Int, allowEmpty: Boolean = false): String {
            val value = json.opt(key) as? String ?: invalidArgument()
            requireArgument(value.length <= maxBytes && (allowEmpty || value.isNotEmpty()))
            requireArgument(utf8(value).size <= maxBytes && '\u0000' !in value)
            if (SECRET.containsMatchIn(value) || SECRET_ASSIGNMENT.containsMatchIn(value)) throw GitHubNativeException(GitHubNativeFailure.SECRET_DETECTED)
            return value
        }
        private fun discussionText(source: JSONObject, target: JSONObject) {
            val title = string(source, "title", MAX_TITLE_CHARS)
            requireArgument(title.isNotBlank() && title.none(Char::isISOControl))
            target.put("title", title).put("body", string(source, "body", MAX_DISCUSSION_BYTES, allowEmpty = true))
        }
        private fun nodeId(value: String): String { requireArgument(NODE_ID.matches(value)); return value }
        private fun responseNodeId(value: Any?): String {
            requireResponse(value is String && NODE_ID.matches(value))
            return value as String
        }
        private fun responseEnum(json: JSONObject, key: String, allowed: Set<String>): String {
            val value = json.opt(key)
            requireResponse(value is String && value in allowed)
            return value as String
        }
        private fun checkLabel(value: Any?): String {
            requireResponse(value is String && value.length <= 4096)
            val label = (value as String).filter { !it.isISOControl() }.take(256)
            return if (SECRET.containsMatchIn(value) || SECRET_ASSIGNMENT.containsMatchIn(value)) "[redacted]" else label
        }
        private fun responseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
            throw GitHubNativeException(GitHubNativeFailure.INVALID_RESPONSE)
        }
        private fun utf8(value: String): ByteArray = try {
            val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: Exception) { invalidArgument() }
        private fun utf8ResponseSize(value: String) = value.toByteArray(Charsets.UTF_8).size
        private fun filePath(path: String): String {
            requireArgument(path.isNotEmpty() && !path.startsWith('/') && !path.endsWith('/') && !path.contains('\\')
                && !path.contains('%') && !path.contains(':') && path.none(Char::isISOControl))
            val parts = path.split('/')
            requireArgument(parts.none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) || it.endsWith('.') || it.endsWith(' ') })
            val name = parts.last().lowercase(Locale.ROOT)
            if (name == ".env" || name.startsWith(".env.") && !name.endsWith(".example") && !name.endsWith(".sample")
                || name in setOf("id_rsa", "id_ed25519", "credentials.json", "credentials", "secrets.json")
                || name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".keystore") || name.endsWith(".jks")) {
                throw GitHubNativeException(GitHubNativeFailure.SECRET_DETECTED)
            }
            if (SECRET.containsMatchIn(path)) throw GitHubNativeException(GitHubNativeFailure.SECRET_DETECTED)
            return path
        }
        private fun validBranch(branch: String): Boolean = branch.isNotEmpty() && branch != "@" && !branch.startsWith("refs/")
            && !branch.startsWith('-') && !branch.endsWith('.') && !branch.contains("..") && !branch.contains("@{")
            && branch.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" }
            && branch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }
        private fun repository(json: JSONObject?, fullName: String): JSONObject {
            if (json == null) throw GitHubNativeException(GitHubNativeFailure.NOT_FOUND)
            responseNodeId(json.opt("id"))
            requireResponse(json.optString("nameWithOwner").equals(fullName, true))
            verifiedUrl(json.optString("url"), fullName)
            return json
        }
        private fun verifiedUrl(value: String, expectedPath: String): String {
            val uri = runCatching { URI(value) }.getOrNull()
            requireResponse(uri != null && uri.scheme == "https" && uri.host == "github.com" && uri.port == -1
                && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
                && uri.rawPath.equals("/$expectedPath", true))
            return value
        }
        internal fun validAuthorization(value: String) = value.startsWith("Bearer ") && value.length in 8..4103
            && value.substring(7).all { it.isLetterOrDigit() && it.code < 128 || it in "_-." }
        private fun ambiguous(status: Int? = null) = GitHubNativeException(GitHubNativeFailure.AMBIGUOUS_OUTCOME, status, true)
        private fun httpFailure(status: Int) = GitHubNativeException(when (status) {
            401 -> GitHubNativeFailure.UNAUTHORIZED
            403 -> GitHubNativeFailure.FORBIDDEN
            404 -> GitHubNativeFailure.NOT_FOUND
            409, 412 -> GitHubNativeFailure.CONFLICT
            400, 422 -> GitHubNativeFailure.VALIDATION
            429 -> GitHubNativeFailure.RATE_LIMITED
            in 300..399 -> GitHubNativeFailure.REDIRECT_REJECTED
            in 500..599 -> GitHubNativeFailure.TRANSIENT
            else -> GitHubNativeFailure.INVALID_RESPONSE
        }, status)
        private fun graphqlFailure(errors: JSONArray): GitHubNativeFailure {
            val reasons = (0 until errors.length()).map { index ->
                val error = errors.optJSONObject(index) ?: return GitHubNativeFailure.INVALID_RESPONSE
                val type = error.optString("type").ifEmpty { error.optJSONObject("extensions")?.optString("code").orEmpty() }.uppercase(Locale.ROOT)
                when (type) {
                    "UNAUTHORIZED", "UNAUTHENTICATED", "BAD_CREDENTIALS" -> GitHubNativeFailure.UNAUTHORIZED
                    "FORBIDDEN", "INSUFFICIENT_SCOPES" -> GitHubNativeFailure.FORBIDDEN
                    "NOT_FOUND" -> GitHubNativeFailure.NOT_FOUND
                    "CONFLICT", "STALE_DATA", "STALE_HEAD", "GIT_REF_UPDATE_REJECTED" -> GitHubNativeFailure.CONFLICT
                    "UNPROCESSABLE", "VALIDATION", "GRAPHQL_VALIDATION_FAILED", "BAD_USER_INPUT" -> GitHubNativeFailure.VALIDATION
                    "RATE_LIMITED" -> GitHubNativeFailure.RATE_LIMITED
                    "INTERNAL", "SERVICE_UNAVAILABLE" -> GitHubNativeFailure.TRANSIENT
                    else -> GitHubNativeFailure.INVALID_RESPONSE
                }
            }
            return if (reasons.distinct().size == 1) reasons.first() else GitHubNativeFailure.INVALID_RESPONSE
        }
    }
}

/** Fixed-endpoint HTTPS transport. Streaming mode, no redirects, no auth replay, bounded responses. */
class GitHubNativeUrlConnectionTransport : GitHubNativeTransport {
    override fun execute(request: GitHubNativeRequest, authorizationHeader: String, token: CancellationToken): GitHubNativeResponse {
        token.throwIfCancelled()
        if (!GitHubNativeBridge.validAuthorization(authorizationHeader)) throw GitHubNativeException(GitHubNativeFailure.UNAUTHORIZED)
        val bytes = request.bodyJson.toByteArray(Charsets.UTF_8)
        if (bytes.size > GitHubNativeBridge.MAX_REQUEST_BYTES) throw GitHubNativeException(GitHubNativeFailure.INVALID_ARGUMENT)
        val connection = URL(GitHubNativeBridge.ENDPOINT).openConnection() as HttpURLConnection
        val unregister = token.registerCancelAction { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Authorization", authorizationHeader)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Jarvys-GitHub-Native")
            token.throwIfCancelled()
            connection.outputStream.use { output -> token.throwIfCancelled(); output.write(bytes) }
            val status = connection.responseCode
            val headers = connection.getHeaderField("Retry-After")?.let { mapOf("Retry-After" to it) } ?: emptyMap()
            if (status !in 200..299) return GitHubNativeResponse(status, "", headers)
            if (connection.contentLengthLong > GitHubNativeBridge.MAX_RESPONSE_BYTES) throw GitHubNativeException(GitHubNativeFailure.RESPONSE_TOO_LARGE)
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    token.throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > GitHubNativeBridge.MAX_RESPONSE_BYTES) throw GitHubNativeException(GitHubNativeFailure.RESPONSE_TOO_LARGE)
                    result.write(buffer, 0, count)
                }
            }
            token.throwIfCancelled()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val body = decoder.decode(java.nio.ByteBuffer.wrap(result.toByteArray())).toString()
            return GitHubNativeResponse(status, body, headers)
        } finally { unregister.run(); connection.disconnect() }
    }
}

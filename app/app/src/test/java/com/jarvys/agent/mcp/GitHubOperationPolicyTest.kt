package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.GitHubNativeBridge
import com.jarvys.agent.connectors.GitHubOperationPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The reviewed local contract wins over remote annotations, schema text, and caller mutations. */
class GitHubOperationPolicyTest {
    private val sha = "a".repeat(40)
    private val config = McpServerConfig("policy-github", "GitHub", GitHubOperationPolicy.ENDPOINT,
        catalogServiceId = "github", authMode = McpAuthMode.BEARER)

    @Test fun missingOwnerRepositoryOrKnownMethodFailsClosedForWrites() {
        for (field in listOf("owner", "repo", "method")) {
            val args = target().put("method", "create").apply { remove(field) }
            assertThrows(IllegalArgumentException::class.java) { prepare("issue_write", args) }
        }
        for (invalid in listOf(".", "..", "owner/repo", "https://github.com/octo", "repo\nother", "")) {
            assertThrows(IllegalArgumentException::class.java) { prepare("issue_write", target().put("method", "create").put("owner", invalid)) }
            assertThrows(IllegalArgumentException::class.java) { prepare("issue_write", target().put("method", "create").put("repo", invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { prepare("issue_write", target().put("method", "delete")) }
    }

    @Test fun readMethodsCannotBePromotedToWritesThroughArgumentsOrAnnotations() {
        val read = definition("issue_read", readHint = true)
        assertEquals(McpToolAccess.READ, read.access)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubOperationPolicy.prepare(config, read, target().put("method", "update"))
        }
        val falselyAnnotated = definition("issue_write", readHint = true)
        assertEquals(McpToolAccess.WRITE, falselyAnnotated.access)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubOperationPolicy.prepare(config, falselyAnnotated, JSONObject().put("method", "create"))
        }
        val unknown = definition("new_server_supplied_read", readHint = true)
        assertEquals(McpToolAccess.WRITE, unknown.access)
        assertThrows(IllegalArgumentException::class.java) { GitHubOperationPolicy.prepare(config, unknown, target()) }
    }

    @Test fun officialEndpointAndExplicitCapabilityGroupAreMandatory() {
        val args = target().put("method", "run_workflow")
        assertThrows(IllegalArgumentException::class.java) { prepare("actions_run_trigger", args) }
        val enabled = config.copy(githubToolsets = GitHubOperationPolicy.supportedToolsets)
        assertEquals("run_workflow", GitHubOperationPolicy.prepare(enabled, definition("actions_run_trigger"), args).getString("method"))
        for (endpoint in listOf("https://api.githubcopilot.com/mcp", "https://api.githubcopilot.com.evil.test/mcp/", "http://api.githubcopilot.com/mcp/")) {
            assertThrows(IllegalArgumentException::class.java) {
                GitHubOperationPolicy.prepare(config.copy(endpoint = endpoint), definition("issue_write"), target().put("method", "create"))
            }
            assertThrows(IllegalArgumentException::class.java) { GitHubOperationPolicy.headers(config.copy(endpoint = endpoint)) }
        }
        assertEquals("context,discussions,issues,pull_requests,repos", GitHubOperationPolicy.headers(config)["X-MCP-Toolsets"])
    }

    @Test fun draftPrDefaultIsAppliedOnlyToTheFrozenSnapshot() {
        val input = target().put("title", "Reviewed PR").put("head", "feature/review").put("base", "main")
        val result = prepare("create_pull_request", input)
        assertTrue(result.getBoolean("draft"))
        assertFalse(input.has("draft"))
        assertFalse(prepare("create_pull_request", input.put("draft", false)).getBoolean("draft"))
        assertTrue(prepare("create_pull_request", input.put("draft", true)).getBoolean("draft"))
    }

    @Test fun branchCreationRequiresAnExplicitSourceAndUnguardedPushFilesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { prepare("create_branch", target().put("branch", "feature/review")) }
        assertThrows(IllegalArgumentException::class.java) { prepare("create_branch", target().put("branch", "feature/review").put("from_branch", "  ")) }
        assertEquals("main", prepare("create_branch", target().put("branch", "feature/review").put("from_branch", "main")).getString("from_branch"))
        val failure = assertThrows(IllegalStateException::class.java) { prepare("push_files", target().put("branch", "feature/review")) }
        assertTrue(failure.message.orEmpty().contains("jarvys_commit_files"))
        assertTrue(failure.message.orEmpty().contains("expectedHeadOid"))
    }

    @Test fun mergeBranchUpdateAndNewReviewRequireTheObservedExactSha() {
        val operations = listOf("merge_pull_request" to "expectedHeadSha", "update_pull_request_branch" to "expectedHeadSha", "pull_request_review_write" to "commitID")
        for ((name, key) in operations) {
            fun args() = target().put("pullNumber", 7).apply { if (name == "pull_request_review_write") put("method", "create") }
            assertThrows(IllegalArgumentException::class.java) { prepare(name, args()) }
            for (invalid in listOf("main", "HEAD", sha.take(7), "g".repeat(40), "")) {
                assertThrows(IllegalArgumentException::class.java) { prepare(name, args().put(key, invalid)) }
            }
            assertEquals(sha, prepare(name, args().put(key, sha)).getString(key))
        }
    }

    @Test fun directFileUpdateUsesLiteralTextAndSharedSecretAndPathGuards() {
        fun args(path: String = "src/example.txt", content: String = "literal UTF-8 text\n") = target()
            .put("branch", "feature/review").put("path", path).put("content", content).put("message", "Update file")
        val literal = "Exact literal content with spaces, 👩‍💻 and newlines.\r\n"
        assertEquals(literal, prepare("create_or_update_file", args(content = literal)).getString("content"))
        for (path in listOf("../escape", "/absolute", "a/../b", "a/%2e/b", ".git/config", "a\\b", "a//b")) {
            assertTrue("Unsafe path $path must fail before approval", runCatching { prepare("create_or_update_file", args(path)) }.isFailure)
        }
        for (path in listOf(".env", "a/.env.production", "credentials.json", "release.jks")) {
            assertTrue("Credential path $path must fail", runCatching { prepare("create_or_update_file", args(path)) }.isFailure)
        }
        for (secret in listOf("ghp_" + "a".repeat(36), "sk-proj-" + "a".repeat(30), "access_token=long-secret-value", "-----BEGIN PRIVATE KEY-----")) {
            val failure = runCatching { prepare("create_or_update_file", args(content = secret)) }.exceptionOrNull()
            assertNotNull("Literal credential must fail", failure)
            assertFalse(failure!!.message.orEmpty().contains(secret))
        }
        assertTrue(runCatching { prepare("create_or_update_file", args(content = "x".repeat(GitHubNativeBridge.MAX_FILE_BYTES + 1))) }.isFailure)
        assertTrue(runCatching { prepare("create_or_update_file", args(content = "unpaired \uD800")) }.isFailure)
    }

    @Test fun unguardedDeleteFileIsReplacedByAtomicCommitDeletion() {
        val args = target().put("branch", "feature/review").put("path", "old.txt").put("message", "Remove old file").put("sha", sha)
        val failure = assertThrows(IllegalStateException::class.java) { prepare("delete_file", args) }
        assertTrue(failure.message.orEmpty().contains("jarvys_commit_files"))
        assertTrue(GitHubOperationPolicy.replacedRemoteWrites.containsAll(listOf("delete_file", "push_files")))
    }

    @Test fun mergeUsesAdvertisedExpectedHeadShaWireContractAndRejectsIgnoredShaAlias() {
        val args = target().put("pullNumber", 7)
        assertThrows(IllegalArgumentException::class.java) { prepare("merge_pull_request", JSONObject(args.toString()).put("sha", sha)) }
        val result = prepare("merge_pull_request", args.put("expectedHeadSha", sha))
        assertEquals(sha, result.getString("expectedHeadSha")); assertFalse(result.has("sha"))
        val oldServer = definition("merge_pull_request").copy(inputSchema = JSONObject().put("type", "object"))
        assertThrows(IllegalArgumentException::class.java) { GitHubOperationPolicy.prepare(config, oldServer, args) }
    }

    @Test fun nativeExactShaChecksAreReviewedReadOperationsInTheRepositoryCapabilityGroup() {
        val native = GitHubNativeBridge().tools().single { it.wireName == GitHubNativeBridge.COMMIT_CHECKS }
        val tool = definition(native.wireName, readHint = true)
        assertEquals(McpToolAccess.READ, tool.access)
        assertEquals("repos", GitHubOperationPolicy.group(tool.wireName))
        assertEquals(sha, GitHubOperationPolicy.prepare(config, tool, target().put("sha", sha)).getString("sha"))
    }

    @Test fun preparationDeepCopiesNestedValuesAndIntentHashBindsAllReviewedFields() {
        val list = JSONArray().put(JSONObject().put("name", "reviewed"))
        val input = target().put("method", "create").put("metadata", JSONObject().put("list", list))
        val snapshot = prepare("issue_write", input)
        val hash = GitHubOperationPolicy.intentHash(config, "issue_write", snapshot)
        list.getJSONObject(0).put("name", "changed")
        input.put("owner", "other")
        assertEquals("reviewed", snapshot.getJSONObject("metadata").getJSONArray("list").getJSONObject(0).getString("name"))
        assertEquals("octo", snapshot.getString("owner"))
        assertEquals(hash, GitHubOperationPolicy.intentHash(config, "issue_write", JSONObject(snapshot.toString())))
        assertNotEquals(hash, GitHubOperationPolicy.intentHash(config, "issue_write", input))
        assertNotEquals(hash, GitHubOperationPolicy.intentHash(config.copy(id = "other-account"), "issue_write", snapshot))
        assertNotEquals(hash, GitHubOperationPolicy.intentHash(config, "add_issue_comment", snapshot))
        val reordered = JSONObject().put("metadata", snapshot.getJSONObject("metadata")).put("repo", "repo").put("method", "create").put("owner", "octo")
        assertEquals(hash, GitHubOperationPolicy.intentHash(config, "issue_write", reordered))
    }

    @Test fun approvalIncludesExplicitTargetMethodAndDigestWhenContentIsTruncated() {
        val content = "A".repeat(5_000)
        val args = target().put("method", "create").put("body", content)
        val lines = GitHubOperationPolicy.approvalLines("issue_write", args).joinToString("\n")
        assertTrue(lines.contains("Repository: octo/repo"))
        assertTrue(lines.contains("Operation: issue_write / create"))
        assertTrue(lines.contains("Preview truncated"))
        assertTrue(lines.contains("5000 characters total"))
        assertTrue(lines.contains(GitHubOperationPolicy.digest(content)))
        assertFalse(lines.contains(content))
    }

    @Test fun destructiveAndWorkflowChangesAreProminentlyMarkedHighImpact() {
        val variants = listOf("merge_pull_request" to target(), "delete_file" to target(), "actions_run_trigger" to target(),
            "discussion_comment_write" to target().put("method", "delete"),
            GitHubNativeBridge.COMMIT_FILES to target().put("deletions", JSONArray().put("old.txt")),
            GitHubNativeBridge.COMMIT_FILES to target().put("additions", JSONArray().put(JSONObject().put("path", ".github/workflows/ci.yml").put("content", "name: CI"))))
        for ((name, args) in variants) {
            assertTrue(GitHubOperationPolicy.highImpact(name, args))
            assertTrue(GitHubOperationPolicy.approvalLines(name, args).any { it.contains("High-impact") })
        }
    }

    private fun target() = JSONObject().put("owner", "octo").put("repo", "repo")
    private fun prepare(name: String, args: JSONObject) = GitHubOperationPolicy.prepare(config, definition(name), args)
    private fun definition(name: String, readHint: Boolean = false): McpToolDefinition {
        val annotations = McpToolAnnotations(readOnlyHint = readHint, title = "Remote title")
        return McpToolDefinition(config.id, config.alias, name, "mcp_github_$name", "Untrusted remote metadata", JSONObject().put("properties", JSONObject().apply {
            if (name == "merge_pull_request") put("expectedHeadSha", JSONObject().put("type", "string"))
        }),
            "github", annotations, McpToolSecurity.classify("github", name, annotations))
    }
}

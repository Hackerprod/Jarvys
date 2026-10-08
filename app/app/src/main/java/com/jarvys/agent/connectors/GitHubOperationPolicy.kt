package com.jarvys.agent.connectors

import com.jarvys.agent.mcp.McpServerConfig
import com.jarvys.agent.mcp.McpToolAccess
import com.jarvys.agent.mcp.McpToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Reviewed local contract; remote tool names and annotations alone do not authorize an operation. */
internal object GitHubOperationPolicy {
    const val ENDPOINT = "https://api.githubcopilot.com/mcp/"
    val defaultToolsets = linkedSetOf("context", "repos", "issues", "pull_requests", "discussions")
    val supportedToolsets = defaultToolsets + "actions"
    val readNames = setOf("jarvys_commit_checks", "get_me", "search_repositories", "search_code", "get_file_contents", "get_commit",
        "list_commits", "search_commits", "list_branches", "list_tags", "get_tag", "get_latest_release",
        "list_releases", "get_release_by_tag", "issue_read", "search_issues", "list_issues", "pull_request_read",
        "list_pull_requests", "search_pull_requests", "list_discussions", "get_discussion", "get_discussion_comments",
        "list_discussion_categories", "actions_get", "actions_list", "get_job_logs")
    private val readMethods = mapOf(
        "issue_read" to setOf("get", "get_comments", "get_sub_issues", "get_labels", "get_parent"),
        "pull_request_read" to setOf("get", "get_diff", "get_status", "get_files", "get_commits", "get_review_comments", "get_reviews", "get_comments", "get_check_runs"),
        "actions_get" to setOf("get_workflow", "get_workflow_run", "get_workflow_job", "get_workflow_run_usage", "get_workflow_run_logs_url", "download_workflow_run_artifact"),
        "actions_list" to setOf("list_workflows", "list_workflow_runs", "list_workflow_jobs", "list_workflow_run_artifacts"),
    )
    private val writeMethods = mapOf(
        "issue_write" to setOf("create", "update"),
        "sub_issue_write" to setOf("add", "remove", "reprioritize"),
        "discussion_comment_write" to setOf("add", "reply", "update", "delete", "mark_answer", "unmark_answer"),
        "pull_request_review_write" to setOf("create", "submit_pending", "delete_pending", "resolve_thread", "unresolve_thread"),
        "actions_run_trigger" to setOf("run_workflow", "rerun_workflow_run", "rerun_failed_jobs", "cancel_workflow_run", "delete_workflow_run_logs"),
    )
    private val writeNames = setOf("create_branch", "create_or_update_file", "push_files", "delete_file",
        "issue_write", "add_issue_comment", "sub_issue_write", "create_pull_request", "update_pull_request",
        "pull_request_review_write", "add_comment_to_pending_review", "update_pull_request_branch", "merge_pull_request",
        "discussion_comment_write", "actions_run_trigger", "jarvys_create_discussion", "jarvys_update_discussion", "jarvys_commit_files")
    private val repoPart = Regex("[A-Za-z0-9_.-]{1,100}")
    private val sha = Regex("[0-9a-fA-F]{40,64}")
    private val secret = Regex("(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")

    fun validateToolsets(groups: Set<String>) {
        require(groups.isNotEmpty() && groups.all { it in supportedToolsets }) { "Choose supported GitHub capability groups" }
        require("context" in groups) { "GitHub identity context must remain enabled" }
    }
    fun headers(config: McpServerConfig): Map<String, String> {
        if (config.catalogServiceId != "github") return emptyMap()
        require(config.endpoint == ENDPOINT) { "GitHub capabilities require the official remote endpoint" }
        validateToolsets(config.githubToolsets)
        return mapOf("X-MCP-Toolsets" to config.githubToolsets.sorted().joinToString(","))
    }
    fun group(name: String): String = when {
        name == "get_me" -> "context"
        name.contains("discussion") -> "discussions"
        name.contains("pull_request") || name == "add_comment_to_pending_review" -> "pull_requests"
        name.contains("issue") -> "issues"
        name.startsWith("actions_") || name == "get_job_logs" -> "actions"
        else -> "repos"
    }
    fun prepare(config: McpServerConfig, tool: McpToolDefinition, supplied: JSONObject): JSONObject {
        require(config.endpoint == ENDPOINT && config.catalogServiceId == "github") { "Unverified GitHub endpoint" }
        require(group(tool.wireName) in config.githubToolsets) { "Enable this GitHub capability group and reconnect first" }
        require(supplied.toString().toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "GitHub arguments exceed 1 MiB" }
        val args = JSONObject(supplied.toString())
        val name = tool.wireName
        require(name in readNames || name in writeNames) { "This newly discovered GitHub operation has not been reviewed by Jarvys" }
        val methods = readMethods[name] ?: writeMethods[name]
        if (methods != null) require(args.optString("method") in methods) { "Unsupported GitHub method; inspect this tool's available methods" }
        if (tool.access == McpToolAccess.WRITE) {
            target(args)
            require(!secret.containsMatchIn(args.toString())) { "GitHub write contains a credential-like value; remove secrets before publishing" }
            if (name == "create_pull_request" && !args.has("draft")) args.put("draft", true)
            when (name) {
                "push_files" -> error("Use jarvys_commit_files with the observed expectedHeadOid for guarded multi-file commits")
                "create_branch" -> require(args.optString("from_branch").isNotBlank()) { "Choose an explicit source branch before creating a branch" }
                "create_or_update_file", "delete_file" -> {
                    path(args.getString("path")); branch(args.getString("branch"))
                    if (name == "create_or_update_file") GitHubNativeBridge.validateFileWrite(args.getString("path"), args.getString("content"))
                    if (name == "delete_file") require(sha.matches(args.optString("sha"))) { "Read the current blob SHA before deleting a file" }
                    if (args.has("sha")) require(sha.matches(args.optString("sha"))) { "Invalid observed file SHA" }
                }
                "merge_pull_request" -> require(sha.matches(args.optString("sha"))) { "Merge requires the exact reviewed head SHA" }
                "update_pull_request_branch" -> require(sha.matches(args.optString("expectedHeadSha"))) { "Updating a PR branch requires its exact observed head SHA" }
                "pull_request_review_write" -> if (args.optString("method") == "create") {
                    require(sha.matches(args.optString("commitID"))) { "Review creation requires the exact reviewed commitID" }
                }
            }
        }
        for (key in listOf("perPage", "per_page")) if (args.has(key)) require(args.optInt(key) in 1..100) { "GitHub page size must be 1–100" }
        if (args.has("page")) require(args.optInt("page") in 1..10000) { "Invalid GitHub page number" }
        return args
    }
    fun target(args: JSONObject): String {
        val owner = args.optString("owner"); val repo = args.optString("repo")
        require(repoPart.matches(owner) && owner !in setOf(".", "..") && repoPart.matches(repo) && repo !in setOf(".", "..")) {
            "GitHub writes require an explicit owner and repository"
        }
        return "$owner/$repo"
    }
    fun branch(value: String) {
        require(value.isNotBlank() && value.length <= 255 && value.none(Char::isISOControl) && !value.startsWith('/') &&
            !value.endsWith('/') && !value.contains("..") && !value.contains("@{") && value.none { it in " ~^:?*[\\" }) { "Invalid GitHub branch" }
    }
    fun path(value: String) {
        require(value.isNotBlank() && value.length <= 1024 && !value.startsWith('/') && !value.contains('\\') &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) } && value.none(Char::isISOControl)) { "Invalid repository-relative path" }
    }
    fun highImpact(name: String, args: JSONObject): Boolean = name in setOf("merge_pull_request", "delete_file", "actions_run_trigger") ||
        args.optString("method") in setOf("delete", "delete_pending", "remove", "mark_answer", "unmark_answer") ||
        args.optBoolean("force", false) || args.optJSONArray("deletions")?.length()?.let { it > 0 } == true ||
        args.toString().contains(".github/workflows/")

    /** All target fields remain visible; content previews explicitly state any truncation and digest. */
    fun approvalLines(name: String, args: JSONObject): List<String> = buildList {
        add("Repository: ${target(args)}")
        add("Operation: $name${args.optString("method").takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}")
        if (highImpact(name, args)) add("High-impact action: review deletion, workflow, merge, or history consequences.")
        val keys = args.keys().asSequence().toList().sorted()
        keys.filter { it !in setOf("owner", "repo", "method") }.forEach { key ->
            val value = args.opt(key)
            when {
                Regex("token|secret|password|authorization|credential|api[_-]?key", RegexOption.IGNORE_CASE).containsMatchIn(key) -> add("$key: [redacted]")
                value is JSONArray -> {
                    add("$key: ${value.length()} entries")
                    for (index in 0 until minOf(value.length(), 30)) {
                        val item = value.opt(index)
                        if (item is JSONObject && item.has("path")) {
                            val content = item.optString("content")
                            add("  ${item.optString("path")}: ${content.toByteArray(Charsets.UTF_8).size} bytes; SHA-256 ${digest(content)}")
                            if (content.isNotEmpty()) add(preview(content, 700))
                        } else add(preview(item.toString(), 400))
                    }
                    if (value.length() > 30) add("Additional ${value.length() - 30} entries omitted; inspect the complete change before approving.")
                }
                value is JSONObject -> add("$key: ${preview(redact(value).toString(), 1500)}")
                else -> add("$key: ${preview(value.toString(), if (key in setOf("body", "content", "message")) 4000 else 800)}")
            }
        }
    }
    fun intentHash(config: McpServerConfig, name: String, args: JSONObject): String =
        digest(config.id + "\n" + config.endpoint + "\n" + name + "\n" + canonical(args))
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun redact(value: JSONObject): JSONObject = JSONObject().apply {
        value.keys().forEach { key -> put(key, if (Regex("token|secret|password|credential|authorization",RegexOption.IGNORE_CASE).containsMatchIn(key)) "[redacted]" else when(val child=value.opt(key)) {
            is JSONObject -> redact(child)
            is JSONArray -> JSONArray().apply { for(i in 0 until child.length()) put(if(child.opt(i) is JSONObject) redact(child.getJSONObject(i)) else child.opt(i)) }
            else -> child
        }) }
    }
    private fun preview(value: String, limit: Int): String {
        val safe = secret.replace(value, "[redacted credential]").filter { it=='\n' || it=='\t' || !it.isISOControl() }
        return if(safe.length<=limit) safe else safe.take(limit)+"\n[Preview truncated; ${safe.length} characters total; SHA-256 ${digest(value)}]"
    }
}

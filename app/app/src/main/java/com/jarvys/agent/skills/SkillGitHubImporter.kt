package com.jarvys.agent.skills

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Fetches the SKILL.md from a GitHub repository or skill-folder URL. */
object SkillGitHubImporter {
    private data class Reference(
        val owner: String,
        val repository: String,
        val branch: String?,
        val markdownPath: List<String>,
    )

    fun downloadMarkdown(input: String): String {
        val reference = parseReference(input)
        val branch = reference.branch ?: resolveDefaultBranch(reference.owner, reference.repository)
        val url = rawUrl(reference, branch)
        return try {
            getText(url, SkillMarkdownParser.MAX_DOCUMENT_BYTES)
        } catch (error: HttpStatusException) {
            if (reference.branch == null && branch == "main" && error.statusCode == 404) {
                getText(rawUrl(reference, "master"), SkillMarkdownParser.MAX_DOCUMENT_BYTES)
            } else {
                throw error
            }
        }
    }

    private fun parseReference(input: String): Reference {
        var value = input.trim()
        require(value.isNotEmpty()) { "Enter a GitHub repository or skill-folder URL" }
        if (!value.contains("://")) value = "https://$value"
        val uri = Uri.parse(value)
        require(uri.scheme == "http" || uri.scheme == "https") { "Use an HTTP(S) GitHub URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "GitHub URL must not contain credentials or query parameters" }
        val segments = uri.pathSegments.filter(String::isNotEmpty)
        val host = uri.host?.lowercase().orEmpty()
        if (host == "raw.githubusercontent.com") {
            require(segments.size >= 4) { "GitHub raw URL must include owner, repository, branch and SKILL.md path" }
            return Reference(
                owner = segments[0],
                repository = segments[1].removeSuffix(".git"),
                branch = segments[2],
                markdownPath = skillMarkdownPath(segments.drop(3)),
            )
        }
        require(host == "github.com" || host == "www.github.com") { "Enter a github.com or raw.githubusercontent.com URL" }
        require(segments.size >= 2) { "GitHub URL must include an owner and repository" }
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) {
            val branch = segments[3]
            val suffix = segments.drop(4)
            val file = segments[2] == "blob"
            return Reference(owner, repository, branch, skillMarkdownPath(suffix, file))
        }
        return Reference(owner, repository, null, listOf("SKILL.md"))
    }

    private fun skillMarkdownPath(path: List<String>, isFile: Boolean = false): List<String> {
        if (path.lastOrNull().equals("SKILL.md", ignoreCase = true)) return path
        val folder = if (isFile) path.dropLast(1) else path
        return folder + "SKILL.md"
    }

    private fun resolveDefaultBranch(owner: String, repository: String): String {
        val url = Uri.Builder().scheme("https").authority("api.github.com")
            .appendPath("repos").appendPath(owner).appendPath(repository).build().toString()
        return runCatching {
            JSONObject(getText(url, 64 * 1024)).optString("default_branch").takeIf(String::isNotBlank)
        }.getOrNull() ?: "main"
    }

    private fun rawUrl(reference: Reference, branch: String): String {
        val builder = Uri.Builder().scheme("https").authority("raw.githubusercontent.com")
            .appendPath(reference.owner).appendPath(reference.repository).appendPath(branch)
        reference.markdownPath.forEach(builder::appendPath)
        return builder.build().toString()
    }

    private fun getText(url: String, maxBytes: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Jarvys Android Agent")
            connection.setRequestProperty("Accept", "application/vnd.github+json, text/plain")
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= maxBytes) { "GitHub SKILL.md exceeds 256 KiB" }
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name()).also {
                require(it.isNotBlank()) { "GitHub returned an empty SKILL.md" }
            }
        } finally {
            connection.disconnect()
        }
    }

    private class HttpStatusException(val statusCode: Int) : IllegalArgumentException("GitHub returned HTTP $statusCode")
}

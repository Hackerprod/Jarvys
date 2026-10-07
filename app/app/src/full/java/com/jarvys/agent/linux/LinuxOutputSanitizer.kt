package com.jarvys.agent.linux

import android.content.Context
import com.jarvys.agent.SecretStore
import com.jarvys.agent.mcp.McpServerRepository
import java.io.File

/** Shared redaction for guest output, persisted failure diagnostics and tool results. */
internal class LinuxOutputSanitizer(
    private val hostPaths: List<String>,
    private val knownSecrets: List<String>,
) {
    fun scrub(raw: String?): String {
        var safe = raw.orEmpty()
        if (safe.isEmpty()) return ""
        knownSecrets.filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
            .forEach { safe = safe.replace(it, "[secret redacted]") }
        hostPaths.filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
            .forEach { safe = safe.replace(it, "[app-private path]") }
        safe = safe.replace(BEARER_PATTERN, "$1[secret redacted]")
            .replace(JWT_PATTERN, "[secret redacted]")
            .replace(OPENAI_KEY_PATTERN, "[secret redacted]")
            .replace(SECRET_ASSIGNMENT_PATTERN, "$1$2[secret redacted]")
        return safe
    }

    companion object {
        private val BEARER_PATTERN = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*")
        private val JWT_PATTERN = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
        private val OPENAI_KEY_PATTERN = Regex("\\bsk-[A-Za-z0-9_-]{16,}")
        private val SECRET_ASSIGNMENT_PATTERN = Regex("(?i)(api[_-]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s,;]+")

        fun from(context: Context): LinuxOutputSanitizer {
            val app = context.applicationContext
            val paths = listOfNotNull(app.filesDir, app.cacheDir, app.codeCacheDir,
                app.applicationInfo.nativeLibraryDir?.let(::File), app.applicationInfo.dataDir?.let(::File))
                .flatMap { file -> listOf(file.absolutePath, runCatching { file.canonicalPath }.getOrNull().orEmpty()) }
            val secrets = runCatching {
                val store = SecretStore.get(app)
                val found = listOfNotNull(store.openRouterKey, store.getOpenAiApiKey(), store.getCustomEndpointKey()).toMutableList()
                store.getCodexCredentials()?.let { found += listOf(it.accessToken, it.refreshToken, it.accountId) }
                runCatching { store.getConnectorSecret("web_search", "exa_api_key") }.getOrNull()?.let(found::add)
                runCatching {
                    McpServerRepository.get(app).servers.value.forEach { server ->
                        listOf("bearer_token", "access_token", "refresh_token", "client_secret", "api_key")
                            .forEach { name -> store.getMcpSecret(server.id, name)?.let(found::add) }
                    }
                }
                found
            }.getOrDefault(emptyList())
            return LinuxOutputSanitizer(paths, secrets)
        }

        internal fun fromFilesDir(filesDir: File): LinuxOutputSanitizer {
            val paths = listOf(filesDir, File(filesDir, ".."), File(filesDir, "../cache"))
                .flatMap { listOf(it.absolutePath, runCatching { it.canonicalPath }.getOrNull().orEmpty()) }
            return LinuxOutputSanitizer(paths, emptyList())
        }
    }
}

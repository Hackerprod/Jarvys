package com.jarvys.agent

import android.net.Uri

/** Bounded, thread-safe WebView diagnostics that drop raw console text and sensitive URL components. */
internal class WebViewDiagnosticTrace(
    private val localResourceLabel: (Uri) -> String?,
    private val logMessage: (String) -> Unit = {},
) {
    private val lock = Any()
    private val events = ArrayList<Event>()

    fun recordDenied(uri: Uri?, reason: String, mainFrame: Boolean? = null) =
        record(Event.Denied(resource(uri), safeReason(reason), mainFrame, isDeclaredLocalResource(uri)))

    fun recordScriptLoadFailure(scriptPath: String) =
        record(Event.ScriptLoadFailure(safeScriptLabel(scriptPath)))

    fun recordExportMissing(scriptPath: String) =
        record(Event.ExportMissing(safeScriptLabel(scriptPath)))

    fun recordHandlerException(scriptPath: String, errorName: String) =
        record(Event.HandlerException(safeScriptLabel(scriptPath), safeErrorName(errorName)))

    fun recordAssetHandlerMiss(uri: Uri?) = record(Event.AssetHandlerMiss(resource(uri)))

    fun recordAssetHandlerError(uri: Uri?) = record(Event.AssetHandlerError(resource(uri)))

    fun recordResourceError(uri: Uri?, errorCode: Int, mainFrame: Boolean? = null) =
        record(Event.ResourceError(resource(uri), errorCode, mainFrame, isDeclaredLocalResource(uri)))

    fun recordHttpError(uri: Uri?, statusCode: Int, mainFrame: Boolean? = null) =
        record(Event.HttpError(resource(uri), statusCode, mainFrame, isDeclaredLocalResource(uri)))

    fun recordRenderProcessGone(crashed: Boolean) = record(Event.RenderProcessGone(crashed))

    /** Stores only a message category and a vetted local source label; raw Chromium text is discarded. */
    fun recordConsoleMessage(sourceId: String, lineNumber: Int, level: String, message: String) {
        val source = runCatching { Uri.parse(sourceId) }.getOrNull()
        record(Event.Console(
            safeLocalResourceLabel(source) ?: "external resource", safeConsoleLevel(level),
            classifyConsoleMessage(message), lineNumber,
            isDeclaredLocalResource(source),
        ))
    }

    /** Prefer concrete local failures over policy denials and unrelated browser requests. */
    fun failureMessage(fallback: String): String = synchronized(lock) {
        events.maxByOrNull { it.priority() }?.format() ?: fallback
    }

    private fun record(event: Event) {
        val message = event.format()
        synchronized(lock) {
            if (events.size == MAX_EVENTS) events.removeAt(0)
            events.add(event)
        }
        runCatching { logMessage(message) }
    }

    private fun resource(uri: Uri?): String {
        return safeLocalResourceLabel(uri) ?: uri?.let(::safeUrlLabel) ?: "unknown resource"
    }

    private fun safeLocalResourceLabel(uri: Uri?): String? = uri?.let {
        runCatching { localResourceLabel(it) }.getOrNull()?.let(::sanitizeLabel)
    }

    private fun isDeclaredLocalResource(uri: Uri?): Boolean = uri?.let {
        runCatching { localResourceLabel(it) }.getOrNull()?.let(::sanitizeLabel) != null
    } == true

    private fun safeScriptLabel(value: String): String {
        val parts = value.split('#', limit = 2)
        val path = sanitizeLabel(parts[0]) ?: return "module handler"
        val export = parts.getOrNull(1)?.takeIf { name ->
            name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '$' }
        }
        return if (export == null) path else "$path#$export"
    }

    /** Keep enough of a rejected local URL to diagnose routing, while dropping query/fragment data. */
    private fun safeUrlLabel(uri: Uri): String {
        val scheme = uri.scheme?.lowercase()?.takeIf { value ->
            value.isNotEmpty() && value.all { it in 'a'..'z' || it in '0'..'9' || it in "+-." }
        } ?: "no-scheme"
        val host = uri.host?.lowercase()?.takeIf { value ->
            value.isNotEmpty() && value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
        } ?: uri.encodedAuthority?.takeIf { value ->
            value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-:" }
        } ?: "no-host"
        if (scheme != "http" && scheme != "https") return "$scheme://$host"
        val origin = "$scheme://$host"
        if (host != "appassets.androidplatform.net") return origin

        val path = uri.encodedPath.orEmpty()
        if (path.startsWith("/module-artifacts/")) return "$origin/module-artifacts/<artifact>"
        if (path.length > MAX_LABEL_LENGTH || path.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "/._-" }) {
            return "$origin/<encoded-resource>"
        }
        return origin + path
    }

    private fun sanitizeLabel(value: String?): String? {
        if (value.isNullOrBlank() || value.length > MAX_LABEL_LENGTH || value.startsWith('/') ||
            value.contains("..") || value.contains('%') || value.contains('?') || value.contains('#')) return null
        if (value.split('/').any { it.isEmpty() }) return null
        if (!value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "/._-" }) return null
        return value
    }

    private fun safeReason(reason: String): String = when (reason) {
        REASON_METHOD_NOT_ALLOWED,
        REASON_OUTSIDE_ALLOWED_SCOPE,
        REASON_RESOURCE_UNAVAILABLE,
        REASON_ARTIFACT_UNAVAILABLE -> reason
        else -> "request rejected by local policy"
    }

    private fun safeConsoleLevel(level: String): String = when (level.uppercase()) {
        "ERROR" -> "error"
        "WARNING" -> "warning"
        "LOG", "DEBUG", "TIP" -> "info"
        else -> "console"
    }

    private fun safeErrorName(name: String): String = when (name) {
        "TypeError", "SyntaxError", "ReferenceError", "RangeError", "URIError", "EvalError", "AggregateError" -> name
        else -> "JavaScript error"
    }

    private fun classifyConsoleMessage(message: String): String = when {
        message.contains("Content Security Policy", ignoreCase = true) || message.contains("violates", ignoreCase = true) ->
            "Content Security Policy blocked content"
        message.contains("SyntaxError", ignoreCase = true) -> "JavaScript syntax error"
        message.contains("ReferenceError", ignoreCase = true) -> "JavaScript reference error"
        message.contains("TypeError", ignoreCase = true) -> "JavaScript type error"
        message.contains("Failed to load resource", ignoreCase = true) || message.contains("ERR_", ignoreCase = true) ->
            "browser resource load failure"
        message.contains("Uncaught", ignoreCase = true) -> "uncaught JavaScript error"
        else -> "browser console message"
    }

    private sealed interface Event {
        fun priority(): Int
        fun format(): String

        data class Denied(val resource: String, val reason: String, val mainFrame: Boolean?, val local: Boolean) : Event {
            override fun priority() = if (local) 80 else 55
            override fun format() = "Resource denied: $resource ($reason)${frameSuffix(mainFrame)}"
        }

        data class ScriptLoadFailure(val script: String) : Event {
            override fun priority() = 100
            override fun format() = "Module handler script failed to load: $script"
        }

        data class ExportMissing(val script: String) : Event {
            override fun priority() = 75
            override fun format() = "Module handler export is missing: $script"
        }

        data class HandlerException(val script: String, val errorName: String) : Event {
            override fun priority() = if (errorName == "JavaScript error") 82 else 98
            override fun format() = "Module handler raised $errorName: $script"
        }

        data class AssetHandlerMiss(val resource: String) : Event {
            override fun priority() = 95
            override fun format() = "Asset handler returned no response: $resource"
        }

        data class AssetHandlerError(val resource: String) : Event {
            override fun priority() = 94
            override fun format() = "Asset handler failed: $resource"
        }

        data class HttpError(val resource: String, val statusCode: Int, val mainFrame: Boolean?, val local: Boolean) : Event {
            override fun priority() = if (local) 95 else 50
            override fun format() = "HTTP $statusCode while loading $resource${frameSuffix(mainFrame)}"
        }

        data class ResourceError(val resource: String, val errorCode: Int, val mainFrame: Boolean?, val local: Boolean) : Event {
            override fun priority() = if (local) 90 else 45
            override fun format() = "WebView error $errorCode while loading $resource${frameSuffix(mainFrame)}"
        }

        data class RenderProcessGone(val crashed: Boolean) : Event {
            override fun priority() = 100
            override fun format() = if (crashed) "WebView renderer process crashed" else "WebView renderer process was killed"
        }

        data class Console(val resource: String, val level: String, val category: String, val line: Int, val local: Boolean) : Event {
            override fun priority() = if (level == "error" && local) 80 else 10
            override fun format(): String {
                val location = if (line > 0) "$resource:$line" else resource
                return "WebView $level: $category ($location)"
            }
        }

        companion object {
            fun frameSuffix(mainFrame: Boolean?): String = when (mainFrame) {
                true -> " (main frame)"
                false -> " (subresource)"
                null -> ""
            }
        }
    }

    companion object {
        const val REASON_METHOD_NOT_ALLOWED = "unsupported method"
        const val REASON_OUTSIDE_ALLOWED_SCOPE = "outside allowed local scope"
        const val REASON_RESOURCE_UNAVAILABLE = "local resource unavailable"
        const val REASON_ARTIFACT_UNAVAILABLE = "delivered artifact unavailable"

        private const val MAX_EVENTS = 32
        private const val MAX_LABEL_LENGTH = 160
    }
}

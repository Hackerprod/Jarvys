package com.jarvys.agent

import org.json.JSONObject

enum class WebSearchFailureKind { BLOCKED, NO_RESULTS, NETWORK, TIMEOUT, HTTP, PARSE, OTHER }

data class WebSearchFailurePresentation(val kind: WebSearchFailureKind, val httpStatus: String? = null) {
    fun messageResourceId(): Int = when (kind) {
        WebSearchFailureKind.BLOCKED -> R.string.web_search_failure_blocked
        WebSearchFailureKind.NO_RESULTS -> R.string.web_search_failure_no_results
        WebSearchFailureKind.NETWORK -> R.string.web_search_failure_network
        WebSearchFailureKind.TIMEOUT -> R.string.web_search_failure_timeout
        WebSearchFailureKind.HTTP -> R.string.web_search_failure_http
        WebSearchFailureKind.PARSE -> R.string.web_search_failure_parse
        WebSearchFailureKind.OTHER -> R.string.web_search_failure_other
    }

    companion object {
        fun parse(detail: String?): WebSearchFailurePresentation? {
            val raw = detail.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: run {
                    val start = raw.indexOf('{')
                    val end = raw.lastIndexOf('}')
                    if (start >= 0 && end > start) runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
                    else null
                }
                ?: return null
            if (!json.has("error")) return null
            val reason = json.optString("reason")
            val kind = when {
                reason == "blocked_or_captcha" || reason == "consent_required" -> WebSearchFailureKind.BLOCKED
                reason == "no_results" -> WebSearchFailureKind.NO_RESULTS
                reason == "network" -> WebSearchFailureKind.NETWORK
                reason == "timeout" -> WebSearchFailureKind.TIMEOUT
                reason.startsWith("http_") -> WebSearchFailureKind.HTTP
                reason == "parse_failed" -> WebSearchFailureKind.PARSE
                else -> WebSearchFailureKind.OTHER
            }
            return WebSearchFailurePresentation(kind, reason.removePrefix("http_").takeIf { kind == WebSearchFailureKind.HTTP })
        }

        fun serialize(reason: String, message: String, source: String = "bing"): String = JSONObject()
            .put("error", message.take(500))
            .put("reason", reason.take(80))
            .put("source", source.take(40))
            .toString()
    }
}

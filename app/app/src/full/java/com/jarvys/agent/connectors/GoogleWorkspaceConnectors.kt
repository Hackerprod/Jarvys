package com.jarvys.agent.connectors

internal object GoogleApiLimits {
    const val MAX_RESPONSE_BYTES = 1024 * 1024 + 16 * 1024
    const val MAX_TRANSFER_BYTES = 8 * 1024 * 1024
    const val MAX_TEXT_CHARS = 12 * 1024
    const val MAX_QUERY_CHARS = 512
    const val MAX_RESULTS = 25
    const val MAX_RECIPIENTS = 20
}

internal object GoogleRestEndpoints {
    const val GMAIL = "https://gmail.googleapis.com/gmail/v1"
    const val DRIVE = "https://www.googleapis.com/drive/v3"
    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun path(value: String): String = encode(value)
    fun requireSuccess(response: GoogleHttpResponse) {
        if (response.status !in 200..299) throw GoogleHttpPolicy.failure(response.status, response.body)
    }
    fun requireSuccess(response: GoogleBinaryResponse) {
        if (response.status !in 200..299) throw GoogleHttpPolicy.failure(response.status,
            response.body.take(16 * 1024).toByteArray().toString(Charsets.UTF_8))
    }
    fun safeError(response: GoogleHttpResponse): String = GoogleHttpPolicy.failure(response.status, response.body).message.orEmpty()
    fun safeError(response: GoogleBinaryResponse): String = GoogleHttpPolicy.failure(response.status,
        response.body.take(16 * 1024).toByteArray().toString(Charsets.UTF_8)).message.orEmpty()
}

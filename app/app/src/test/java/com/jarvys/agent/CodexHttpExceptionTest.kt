package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHttpExceptionTest {
    @Test
    fun keepsTheCompleteProviderBodyAndModelWithoutReformatting() {
        val body = "{\"error\":{\"message\":\"unknown model\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"
        val failure = CodexHttpException(400, body, "gpt-5.6-luna")

        assertEquals(400, failure.statusCode)
        assertEquals(body, failure.responseBody)
        assertEquals("gpt-5.6-luna", failure.model)
        assertEquals("OpenAI Codex Responses request failed with HTTP 400: $body", failure.message)
    }

    @Test
    fun codex429RetainsItsLegacyTypeAndExposesRetryAfterToCrew() {
        val failure = CodexHttpException(429, "rate limited", "model", null, 4000L)

        assertTrue(failure is CodexHttpException)
        assertTrue(failure is ProviderHttpException)
        assertTrue(failure.isRateLimit())
        assertEquals(429, failure.statusCode)
        assertEquals(4000L, failure.retryAfterMillis)
    }
}

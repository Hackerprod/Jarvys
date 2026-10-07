package com.jarvys.agent

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAuthDiagnosticTest {
    @Test fun includesOnlyAllowlistedMetadataAndDropsCredentialBearingMessages() {
        val secret = "authorization-code-refresh-token-secret"
        val body = JSONObject().put("error", JSONObject().put("code", "invalid_grant").put("message", secret))
            .put("access_token", secret).put("refresh_token", secret).put("error_description", secret)
        val diagnostic = CodexAuthDiagnostic.http(CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE, 400, body)
        val text = diagnostic.toDisplayText("browser")
        assertTrue(text.contains("stage=token_exchange"))
        assertTrue(text.contains("target_host=auth.openai.com"))
        assertTrue(text.contains("http=400"))
        assertTrue(text.contains("oauth=invalid_grant"))
        assertFalse(text.contains(secret))
        assertEquals("", diagnostic.exceptionType)
    }

    @Test fun unknownProviderReasonMethodAndStatusCannotBeInjectedIntoDisplay() {
        val secret = "secret\naccess_token=injected"
        val diagnostic = CodexAuthDiagnostic.http(CodexAuthDiagnostic.Stage.CALLBACK, 999,
            JSONObject().put("error", secret).put("error_code", secret))
        val text = diagnostic.toDisplayText(secret)
        assertTrue(text.contains("method=unknown"))
        assertFalse(text.contains("http="))
        assertFalse(text.contains("oauth="))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("target_host="))
        assertEquals("", CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.SAVE_SESSION, secret).reason)
    }

    @Test fun recognizedErrorCodeFallbackIsPreserved() {
        val diagnostic = CodexAuthDiagnostic.http(CodexAuthDiagnostic.Stage.POLL_APPROVAL, 403,
            JSONObject().put("error", "unrecognized server text").put("error_code", "authorization_pending"))
        assertEquals("authorization_pending", diagnostic.providerCode)
    }

    @Test fun transportClassificationDoesNotExposeExceptionMessagesOrCauses() {
        listOf(SSLException("secret") to "tls", UnknownHostException("secret") to "dns",
            SocketTimeoutException("secret") to "timeout", ConnectException("secret") to "connection",
            SecurityException("secret") to "permission", IOException("secret") to "io").forEach { (error, category) ->
            val diagnostic = CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.REFRESH,
                IllegalStateException("secret-wrapper", error))
            assertEquals(category, diagnostic.category)
            assertFalse(diagnostic.toDisplayText("browser").contains("secret"))
            val failure = CodexAuthDiagnostic.Failure(diagnostic)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("secret"))
            assertSame(diagnostic, CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.UNKNOWN, failure))
        }
    }

    @Test fun cyclicCauseGraphTerminatesAndStorageKeepsOnlyType() {
        val first = IOException("secret-one")
        val second = IllegalStateException("secret-two", first)
        first.initCause(second)
        assertEquals("io", CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.REQUEST_CODE, first).category)
        val storage = CodexAuthDiagnostic.storage(IllegalStateException("access_token=secret"))
        assertEquals(CodexAuthDiagnostic.Stage.SAVE_SESSION, storage.stage)
        assertEquals("storage", storage.category)
        assertFalse(storage.toDisplayText("device_code").contains("secret"))
    }
}

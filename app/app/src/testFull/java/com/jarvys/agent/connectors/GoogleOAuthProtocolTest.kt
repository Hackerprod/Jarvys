package com.jarvys.agent.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class GoogleOAuthProtocolTest {
    private data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
    private class FakeGoogleServer : GoogleHttpTransport {
        val requests = mutableListOf<Recorded>()
        var response = GoogleHttpResponse(200, """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"scope":"scope"}""")
        override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse {
            requests += Recorded(method, url, headers, body)
            return response
        }
    }

    @Test fun unsupportedAndroidRedirectAndConsentErrorsHaveExplicitUserFacingClassification() {
        assertEquals(GoogleOAuthError.REDIRECT_MISMATCH, GoogleOAuthProtocol.mapError("redirect_uri_mismatch"))
        assertEquals(GoogleOAuthError.ACCESS_BLOCKED, GoogleOAuthProtocol.mapError("access_blocked"))
        assertEquals(GoogleOAuthError.ACCESS_BLOCKED, GoogleOAuthProtocol.mapError("access_denied"))
        val server = FakeGoogleServer().apply {
            response = GoogleHttpResponse(400, """{"error":"access_blocked"}""")
        }
        val error = runCatching { GoogleOAuthProtocol.exchangeToken(server,
            GoogleOAuthProtocol.authorizationCodeForm("id", "", "code", "verifier", "redirect")) }.exceptionOrNull()
        assertEquals("access_blocked", (error as GoogleOAuthException).providerError)
        assertFalse(server.requests.single().body.orEmpty().contains("client_secret"))
    }

    @Test fun pkceStateAndOneFeatureScopeAreEncodedInAuthorizationUrl() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuthProtocol.pkceS256(verifier))
        val scope = GoogleOAuthProtocol.GMAIL_READ
        val url = GoogleOAuthProtocol.authorizationUrl("client-id", "http://127.0.0.1:49152/oauth2/callback",
            scope, "random-state", GoogleOAuthProtocol.pkceS256(verifier))
        val query = URI(url).rawQuery.split('&').associate { pair ->
            val parts = pair.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("code", query["response_type"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("random-state", query["state"])
        assertEquals(scope, query["scope"])
        assertFalse(query.containsKey("include_granted_scopes")) // Google says installed clients do not support incremental auth.
        assertFalse(query.containsKey("client_secret"))
        assertFalse(url.contains("refresh"))
    }

    @Test fun installedClientCodeExchangeAcceptsOptionalSecretOnlyInPostBody() {
        val server = FakeGoogleServer()
        val withoutSecret = GoogleOAuthProtocol.authorizationCodeForm("id", "", "code-value", "verifier-value", "http://127.0.0.1:1234/callback")
        GoogleOAuthProtocol.exchangeToken(server, withoutSecret)
        assertEquals(GoogleOAuthProtocol.TOKEN_ENDPOINT, server.requests.single().url)
        assertFalse(server.requests.single().body.orEmpty().contains("client_secret"))
        assertTrue(server.requests.single().body.orEmpty().contains("code_verifier=verifier-value"))

        server.requests.clear()
        val withSecret = GoogleOAuthProtocol.authorizationCodeForm("id", "user-owned-secret", "code-value", "verifier-value", "http://127.0.0.1:1234/callback")
        GoogleOAuthProtocol.exchangeToken(server, withSecret)
        val request = server.requests.single()
        assertTrue(request.body.orEmpty().contains("client_secret=user-owned-secret"))
        assertFalse(request.url.contains("user-owned-secret"))
    }

    @Test fun rotatingRefreshFormAndRevocationKeepCredentialsOutOfUrls() {
        val form = GoogleOAuthProtocol.refreshForm("client", "desktop-secret", "refresh-token")
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("desktop-secret", form["client_secret"])
        val server = FakeGoogleServer()
        GoogleOAuthProtocol.exchangeToken(server, form)
        assertFalse(server.requests.single().url.contains("refresh-token"))
        server.requests.clear()
        GoogleOAuthProtocol.revokeToken(server, "refresh-token")
        assertEquals(GoogleOAuthProtocol.REVOKE_ENDPOINT, server.requests.single().url)
        assertEquals("token=refresh-token", server.requests.single().body)
    }

    @Test fun bearerRestRequestRetriesOnceOnlyFor401AndNeverFor403() {
        var requests = 0
        var refreshes = 0
        val result = GoogleOAuthProtocol.retryOnceOn401(
            request = { requests++; GoogleHttpResponse(if (requests == 1) 401 else 200, "ok") },
            refresh = { refreshes++ },
        )
        assertEquals(200, result.status)
        assertEquals(2, requests)
        assertEquals(1, refreshes)

        requests = 0
        refreshes = 0
        val forbidden = GoogleOAuthProtocol.retryOnceOn401(
            request = { requests++; GoogleHttpResponse(403, "forbidden") },
            refresh = { refreshes++ },
        )
        assertEquals(403, forbidden.status)
        assertEquals(1, requests)
        assertEquals(0, refreshes)

        requests = 0
        refreshes = 0
        val cleared = AtomicReference(false)
        assertTrue(runCatching {
            GoogleOAuthProtocol.retryOnceOn401(
                request = { requests++; GoogleHttpResponse(401, "unauthorized") },
                refresh = { refreshes++ },
                onRepeatedUnauthorized = { cleared.set(true) },
            )
        }.isFailure)
        assertEquals(2, requests)
        assertEquals(1, refreshes)
        assertTrue(cleared.get())
    }

    @Test fun sharedLoopbackCallbackValidatesStateAndReturnsAuthorizationCode() {
        val server = LoopbackOAuthCallbackServer.random("/oauth2/callback")
        val codeResult = AtomicReference<String>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching { codeResult.set(server.awaitCode("nonce-state", 5_000)) }
                .onFailure(failure::set)
        }.apply { start() }
        try {
            Socket("127.0.0.1", URI(server.redirectUri).port).use { socket ->
                val path = URI(server.redirectUri).rawPath
                socket.getOutputStream().write("GET $path?state=nonce-state&code=oauth-code HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes()
            }
            worker.join(2_000)
            assertEquals(null, failure.get())
            assertEquals("oauth-code", codeResult.get())
            assertTrue(server.redirectUri.startsWith("http://127.0.0.1:"))
        } finally { server.close() }
    }

    @Test fun sharedLoopbackCallbackRejectsMismatchedStateAndMapsGoogleProviderErrors() {
        val server = LoopbackOAuthCallbackServer.random("/oauth2/callback")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching { server.awaitCode("expected-state", 5_000) }.onFailure(failure::set)
        }.apply { start() }
        try {
            Socket("127.0.0.1", URI(server.redirectUri).port).use { socket ->
                socket.getOutputStream().write("GET /oauth2/callback?state=attacker-state&code=wrong HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes()
            }
            worker.join(2_000)
            assertTrue(failure.get() is IllegalStateException)
        } finally { server.close() }
        assertEquals(GoogleOAuthError.REDIRECT_MISMATCH, GoogleOAuthProtocol.mapError("redirect_uri_mismatch"))
        assertEquals(GoogleOAuthError.ACCESS_BLOCKED, GoogleOAuthProtocol.mapError("access_blocked"))
    }
}

package com.jarvys.agent.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class GitHubDeviceFlowTest {
    private class FakeTransport : GitHubOAuthTransport {
        val urls = mutableListOf<String>()
        val forms = mutableListOf<Map<String, String>>()
        val replies = ArrayDeque<GitHubOAuthResponse>()
        override fun post(url: String, form: Map<String, String>): GitHubOAuthResponse {
            urls += url
            forms += form.toMap()
            return replies.removeFirst()
        }
    }

    @Test fun deviceRequestShowsOnlyMinimalScopesUnlessPrivateRepositoriesAreOptedIn() {
        val minimal = GitHubDeviceFlowProtocol.requestForm(GitHubDeviceFlowProtocol.CLIENT_ID, false)
        assertEquals(GitHubDeviceFlowProtocol.CLIENT_ID, minimal["client_id"])
        assertEquals("read:user offline_access", minimal["scope"])
        assertFalse(minimal["scope"].orEmpty().contains("repo"))
        assertEquals("read:user repo offline_access", GitHubDeviceFlowProtocol.requestForm(
            GitHubDeviceFlowProtocol.CLIENT_ID, true)["scope"])

        val fake = FakeTransport().apply { replies += GitHubOAuthResponse(200,
            """{"device_code":"device-secret","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""") }
        val code = GitHubDeviceFlowProtocol.deviceCode(fake, includePrivateRepositories = false)
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("https://github.com/login/device", code.verificationUri)
        assertEquals(GitHubDeviceFlowProtocol.DEVICE_CODE_ENDPOINT, fake.urls.single())
        assertFalse(code.userCode.contains(code.deviceCode))
        assertTrue(runCatching {
            GitHubDeviceFlowProtocol.parseDeviceCode(200,
                """{"device_code":"x","user_code":"A","verification_uri":"https://evil.example/login/device","expires_in":60,"interval":5}""")
        }.isFailure)
    }

    @Test fun pollHonorsPendingAndSlowDownIntervalsThenStoresRotatingExpiringTokens() {
        val fake = FakeTransport().apply {
            replies += GitHubOAuthResponse(200, """{"error":"authorization_pending"}""")
            replies += GitHubOAuthResponse(200, """{"error":"slow_down"}""")
            replies += GitHubOAuthResponse(200, """{"access_token":"access","refresh_token":"refresh","expires_in":28800,"refresh_token_expires_in":15897600,"scope":"read:user,repo"}""")
        }
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val token = GitHubDeviceFlowProtocol.pollForToken(fake,
            GitHubDeviceCode("hidden-device-code", "WDJB-MJHT", GitHubDeviceFlowProtocol.VERIFICATION_URI, 900, 5),
            nowMillis = { now }, waitMillis = { millis -> waits += millis; now += millis })
        assertEquals(listOf(5_000L, 5_000L, 10_000L), waits)
        assertEquals(3, fake.urls.size)
        assertTrue(fake.urls.all { it == GitHubDeviceFlowProtocol.TOKEN_ENDPOINT })
        assertEquals(GitHubDeviceFlowProtocol.DEVICE_GRANT, fake.forms.last()["grant_type"])
        assertEquals("access", token.accessToken)
        assertEquals("refresh", token.refreshToken)
        assertEquals(setOf("read:user", "repo"), token.scopes)
        assertEquals(now + 28_800_000L, token.expiresAtMillis)
        assertEquals(now + 15_897_600_000L, token.refreshTokenExpiresAtMillis)
        assertFalse(fake.forms.any { it.values.any { value -> value == token.accessToken || value == token.refreshToken } })
    }

    @Test fun pollMapsExpiredDeniedAndCancelledWithoutRetryingThem() {
        listOf("expired_token", "access_denied", "device_flow_disabled").forEach { code ->
            val fake = FakeTransport().apply { replies += GitHubOAuthResponse(200, """{"error":"$code"}""") }
            val failure = runCatching {
                GitHubDeviceFlowProtocol.pollForToken(fake,
                    GitHubDeviceCode("device", "WDJB-MJHT", GitHubDeviceFlowProtocol.VERIFICATION_URI, 900, 1),
                    waitMillis = {})
            }.exceptionOrNull()
            assertEquals(code, (failure as GitHubDeviceFlowException).code)
            assertEquals(1, fake.urls.size)
        }
        assertEquals(GitHubDeviceFlowError.EXPIRED, GitHubDeviceFlowProtocol.mapError(GitHubDeviceFlowException("expired_token")))
        assertEquals(GitHubDeviceFlowError.DENIED, GitHubDeviceFlowProtocol.mapError(GitHubDeviceFlowException("access_denied")))
        assertEquals(GitHubDeviceFlowError.DISABLED, GitHubDeviceFlowProtocol.mapError(GitHubDeviceFlowException("device_flow_disabled")))
        val cancelled = GitHubDeviceFlowException("cancelled")
        assertEquals(GitHubDeviceFlowError.CANCELLED, GitHubDeviceFlowProtocol.mapError(cancelled))
        assertNull(GitHubOAuthTokens("x", null, 0, 0, emptySet()).refreshToken)

        val expiring = FakeTransport().apply { replies += GitHubOAuthResponse(200, """{"error":"authorization_pending"}""") }
        var now = 0L
        val expiryFailure = runCatching {
            GitHubDeviceFlowProtocol.pollForToken(expiring,
                GitHubDeviceCode("device", "WDJB-MJHT", GitHubDeviceFlowProtocol.VERIFICATION_URI, 2, 1),
                nowMillis = { now }, waitMillis = { now += it })
        }.exceptionOrNull()
        assertEquals("expired_token", (expiryFailure as GitHubDeviceFlowException).code)
        assertEquals(1, expiring.urls.size)
    }

    @Test fun refreshUsesPublicClientAndKeepsCredentialOutOfTheRequestUrl() {
        val form = GitHubDeviceFlowProtocol.refreshForm(GitHubDeviceFlowProtocol.CLIENT_ID, "refresh-secret")
        assertEquals(GitHubDeviceFlowProtocol.CLIENT_ID, form["client_id"])
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("refresh-secret", form["refresh_token"])
        assertFalse(form.containsKey("client_secret"))
        assertFalse(GitHubDeviceFlowProtocol.TOKEN_ENDPOINT.contains("refresh-secret"))
    }
}

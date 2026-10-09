package com.jarvys.agent.connectors

import org.junit.Assert.*
import org.junit.Test

/** Deterministic Google-documented endpoint scope sets; no account or provider calls. */
class GmailScopeCapabilitiesTest {
    private val read = GoogleOAuthProtocol.GMAIL_READ
    private val compose = GoogleOAuthProtocol.GMAIL_COMPOSE
    private val send = GoogleOAuthProtocol.GMAIL_SEND
    private val modify = GoogleOAuthProtocol.GMAIL_MODIFY
    private val full = GoogleOAuthProtocol.GMAIL_FULL
    private val accepted = linkedMapOf(
        read to listOf(read, modify, full),
        compose to listOf(compose, modify, full),
        send to listOf(send, compose, modify, full),
        modify to listOf(modify, full),
        full to listOf(full),
    )

    @Test fun everyMixedGrantSetSelectsOnlyTheLeastActuallyGrantedAcceptedScope() {
        val scopes = accepted.keys.toList()
        for (bits in 0 until (1 shl scopes.size)) {
            val actual = scopes.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet()
            for ((capability, ordered) in accepted) {
                assertEquals(ordered, GoogleOAuthProtocol.acceptedScopes(capability))
                assertEquals("capability=$capability actual=$actual", ordered.firstOrNull(actual::contains),
                    GoogleOAuthProtocol.effectiveGrantedScope(capability, actual))
            }
        }
    }

    @Test fun defaultFeaturesNeverRequestManageFullSendOrComposeAndUnknownScopesAreRejected() {
        assertEquals(setOf(read), GoogleIdentityPolicy.scopesForFeature(GmailConnector.ID))
        assertEquals(setOf(GoogleOAuthProtocol.DRIVE_READ), GoogleIdentityPolicy.scopesForFeature(DriveConnector.ID))
        assertEquals(setOf(full, modify), GoogleIdentityPolicy.normalizeScopes(setOf(full, modify,
            "https://mail.google.com", "https://www.googleapis.com/auth/drive", "unknown")))
        assertNull(GoogleOAuthProtocol.effectiveGrantedScope(full, setOf(read, compose, send, modify)))
        assertNull(GoogleOAuthProtocol.effectiveGrantedScope(read, setOf(compose, send)))
        assertNull(GoogleOAuthProtocol.effectiveGrantedScope("unknown", setOf(full)))
    }

    @Test fun fullMailScopeMayReachGmailButNeverDriveOrOtherEndpoints() {
        GoogleHttpPolicy.validateEndpoint("DELETE", "https://gmail.googleapis.com/gmail/v1/users/me/messages/fake-id", full)
        assertTrue(runCatching { GoogleHttpPolicy.validateEndpoint("GET", "https://www.googleapis.com/drive/v3/files", full) }.isFailure)
        assertTrue(runCatching { GoogleHttpPolicy.validateEndpoint("POST", GoogleOAuthProtocol.TOKEN_ENDPOINT, full) }.isFailure)
        assertTrue(runCatching { GoogleHttpPolicy.validateEndpoint("GET", "https://gmail.googleapis.com/other/path", full) }.isFailure)
    }

    @Test fun driveScopesDoNotAcquireGmailEquivalencesOrFullDriveAccess() {
        for (scope in listOf(GoogleOAuthProtocol.DRIVE_READ, GoogleOAuthProtocol.DRIVE_FILE)) {
            assertEquals(listOf(scope), GoogleOAuthProtocol.acceptedScopes(scope))
            assertNull(GoogleOAuthProtocol.effectiveGrantedScope(scope, setOf(full, modify)))
        }
    }
}

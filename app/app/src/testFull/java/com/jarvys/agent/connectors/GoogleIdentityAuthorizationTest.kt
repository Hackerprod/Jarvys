package com.jarvys.agent.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleIdentityAuthorizationTest {
    @Test fun featureScopesAreMinimalAndExcludeBroadOrProhibitedScopes() {
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_READ), GoogleIdentityPolicy.scopesForFeature(GmailConnector.ID))
        assertEquals(setOf(GoogleOAuthProtocol.DRIVE_READ), GoogleIdentityPolicy.scopesForFeature(DriveConnector.ID))
        assertEquals(GoogleOAuthProtocol.GMAIL_READ, GoogleIdentityPolicy.scopeForFeature(GmailConnector.ID, "read"))
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleIdentityPolicy.scopeForFeature(GmailConnector.ID, "compose"))
        assertEquals(GoogleOAuthProtocol.GMAIL_SEND, GoogleIdentityPolicy.scopeForFeature(GmailConnector.ID, "send"))
        assertEquals(GoogleOAuthProtocol.DRIVE_FILE, GoogleIdentityPolicy.scopeForFeature(DriveConnector.ID, "file"))
        assertNull(GoogleIdentityPolicy.scopeForFeature("gmail", "modify"))
        assertFalse(GoogleIdentityPolicy.normalizeScopes(listOf("mail.google.com", "https://www.googleapis.com/auth/drive")).isNotEmpty())
        assertFalse(GoogleOAuthProtocol.GMAIL_SEND in GoogleIdentityPolicy.scopesForFeature(GmailConnector.ID))
    }

    @Test fun identityStateTracksOnlyConnectionScopesAndOptionalAccountNotTokens() {
        assertEquals(GoogleIdentityConnectionState.DISCONNECTED, GoogleIdentityPolicy.connectionState(emptySet()))
        assertEquals(GoogleIdentityConnectionState.CONNECTED,
            GoogleIdentityPolicy.connectionState(setOf(GoogleOAuthProtocol.DRIVE_READ)))
        assertEquals(GoogleIdentityConnectionState.REAUTHORIZE,
            GoogleIdentityPolicy.connectionState(setOf(GoogleOAuthProtocol.DRIVE_READ), reauthorize = true))
        val state = GoogleIdentityPolicy.session(setOf(GoogleOAuthProtocol.GMAIL_READ), "owner@example.com")
        assertTrue(state.connected)
        assertEquals("owner@example.com", state.accountEmail)
        assertEquals(setOf("connected", "scopes", "accountEmail"), GoogleIdentitySessionState::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }.map { it.name }.toSet())
    }

    @Test fun authorizationResolutionDenialCancellationAndTokenOutcomesAreExplicit() {
        val scope = setOf(GoogleOAuthProtocol.GMAIL_READ)
        assertEquals(GoogleIdentityDecision.RESOLUTION_PENDING,
            GoogleIdentityPolicy.decision(true, null, emptySet(), scope))
        assertEquals(GoogleIdentityDecision.USER_CANCELLED,
            GoogleIdentityPolicy.decision(false, null, emptySet(), scope, cancelled = true))
        assertEquals(GoogleIdentityDecision.SCOPE_DENIED,
            GoogleIdentityPolicy.decision(false, "access", emptySet(), scope))
        assertEquals(GoogleIdentityDecision.TOKEN_MISSING,
            GoogleIdentityPolicy.decision(false, null, scope, scope))
        assertEquals(GoogleIdentityDecision.TOKEN_READY,
            GoogleIdentityPolicy.decision(false, "access", scope, scope))
        assertTrue(GoogleIdentityPolicy.shouldReauthorizeAfter401(0))
        assertFalse(GoogleIdentityPolicy.shouldReauthorizeAfter401(1))
        assertEquals(GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE, GoogleIdentityPolicy.playServicesFailure(false))
        assertNull(GoogleIdentityPolicy.playServicesFailure(true))
    }

    @Test fun integratedManagerPersistsOnlyScopesAndEmailNeverAnAccessToken() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val manager = File(root, "src/full/java/com/jarvys/agent/connectors/GoogleOAuthManager.kt").readText()
        val persist = manager.substringAfter("private fun authorizeIdentity").substringBefore("private fun identityGrantedScopes")
        assertTrue(persist.contains("KEY_IDENTITY_SCOPES"))
        assertTrue(persist.contains("KEY_IDENTITY_EMAIL"))
        assertFalse(persist.contains("saveConnectorSecret"))
        assertFalse(persist.contains("KEY_IDENTITY_ACCESS_TOKEN"))
        assertFalse(persist.contains("putString(\"access_token\""))
    }
}

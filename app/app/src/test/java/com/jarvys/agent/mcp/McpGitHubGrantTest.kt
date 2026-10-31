package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.GitHubDeviceFlowProtocol
import com.jarvys.agent.connectors.GitHubOAuthTokens
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpGitHubGrantTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE)
    private lateinit var vault: FakeEncryptedVault
    private lateinit var repository: McpServerRepository
    private lateinit var oauth: McpOAuthManager
    private var now = 1_700_000_000_000L
    private var requests = 0
    private var response: () -> JSONObject = { error("Unexpected token request") }
    private val config = McpServerConfig(
        id = "github-grant", alias = "GitHub", endpoint = "https://api.githubcopilot.com/mcp/",
        catalogServiceId = "github", authMode = McpAuthMode.OAUTH,
        oauthClientId = GitHubDeviceFlowProtocol.CLIENT_ID,
    )

    @Before fun setUp() {
        preferences.edit().clear().commit()
        vault = FakeEncryptedVault()
        repository = McpServerRepository(context, vault)
        repository.upsert(config)
        oauth = newManager()
    }

    @After fun cleanUp() { preferences.edit().clear().commit() }

    @Test fun completeGrantRoundTripsThroughEncryptedVaultAndNeverPlainSettings() {
        val tokens = grant()
        oauth.saveGitHubDeviceGrant(config, tokens)
        val epoch = oauth.githubAuthorizationEpoch(config)
        val reloaded = newManager()

        assertEquals("Bearer ${tokens.accessToken}", reloaded.authorizationHeader(config))
        assertEquals(tokens.refreshToken, repository.oauthValue(config.id, "oauth_refresh_token"))
        assertEquals(tokens.expiresAtMillis.toString(), repository.oauthValue(config.id, "oauth_expires_at"))
        assertEquals(tokens.refreshTokenExpiresAtMillis, reloaded.githubRefreshTokenExpiresAtMillis(config))
        assertEquals(tokens.scopes, reloaded.githubGrantedScopes(config))
        assertEquals(epoch, reloaded.githubAuthorizationEpoch(config))
        val settings = preferences.all.toString()
        listOf(tokens.accessToken, tokens.refreshToken!!, epoch, "offline_access", "oauth_github_").forEach {
            assertFalse("Grant metadata leaked into plain server settings", settings.contains(it))
        }
        assertEquals(0, requests)
    }

    @Test fun refreshRotatesCredentialsAndDeadlinesButKeepsAuthorizationEpoch() {
        oauth.saveGitHubDeviceGrant(config, grant())
        val epoch = oauth.githubAuthorizationEpoch(config)
        response = {
            JSONObject().put("access_token", "rotated-access").put("refresh_token", "rotated-refresh")
                .put("expires_in", 3600).put("refresh_token_expires_in", 86400)
                .put("scope", "read:user,repo offline_access").put("token_type", "bearer")
        }
        assertEquals("Bearer rotated-access", oauth.refreshAccessToken(config))
        assertEquals("rotated-refresh", repository.oauthValue(config.id, "oauth_refresh_token"))
        assertEquals((now + 3_600_000).toString(), repository.oauthValue(config.id, "oauth_expires_at"))
        assertEquals(now + 86_400_000, oauth.githubRefreshTokenExpiresAtMillis(config))
        assertEquals(setOf("read:user", "repo", "offline_access"), oauth.githubGrantedScopes(config))
        assertEquals(epoch, oauth.githubAuthorizationEpoch(config))
        assertEquals(1, requests)
    }

    @Test fun omittedRefreshTokenScopeAndRefreshDeadlinePreserveExistingMetadata() {
        val original = grant()
        oauth.saveGitHubDeviceGrant(config, original)
        response = { JSONObject().put("access_token", "fresh-access").put("expires_in", 7200) }

        assertEquals("Bearer fresh-access", oauth.refreshAccessToken(config))
        assertEquals(original.refreshToken, repository.oauthValue(config.id, "oauth_refresh_token"))
        assertEquals(original.scopes, oauth.githubGrantedScopes(config))
        assertEquals(original.refreshTokenExpiresAtMillis, oauth.githubRefreshTokenExpiresAtMillis(config))
    }

    @Test fun rotatedRefreshWithOmittedDeadlinePreservesConservativeKnownDeadline() {
        val original = grant()
        oauth.saveGitHubDeviceGrant(config, original)
        response = { JSONObject().put("access_token", "fresh-access").put("refresh_token", "new-refresh") }
        oauth.refreshAccessToken(config)

        assertEquals(original.refreshTokenExpiresAtMillis, oauth.githubRefreshTokenExpiresAtMillis(config))
        assertEquals(original.scopes, oauth.githubGrantedScopes(config))
        // The new access token has no announced lifetime; the old access deadline must not leak.
        assertTrue(repository.oauthValue(config.id, "oauth_expires_at").isNullOrEmpty())
    }

    @Test fun explicitlyEmptyScopesAndNonExpiringRefreshReplacePreviousMetadata() {
        oauth.saveGitHubDeviceGrant(config, grant())
        response = {
            JSONObject().put("access_token", "fresh-access").put("scope", "")
                .put("refresh_token_expires_in", 0)
        }
        oauth.refreshAccessToken(config)

        assertEquals(emptySet<String>(), oauth.githubGrantedScopes(config))
        assertEquals(0L, oauth.githubRefreshTokenExpiresAtMillis(config))
    }

    @Test fun refreshExpiryIsRejectedBeforeAnyHttpEvenWhenAccessTokenIsMissing() {
        oauth.saveGitHubDeviceGrant(config, grant().copy(refreshTokenExpiresAtMillis = now))
        oauth.invalidateAccessToken(config)
        assertThrows(McpReauthRequiredException::class.java) { oauth.authorizationHeader(config) }
        assertEquals(0, requests)
    }

    @Test fun nearingAccessExpiryRefreshesOnceButNonExpiringGrantNeverRefreshes() {
        oauth.saveGitHubDeviceGrant(config, grant().copy(expiresAtMillis = now + 60_000))
        response = { JSONObject().put("access_token", "fresh-access").put("expires_in", 3600) }
        assertEquals("Bearer fresh-access", oauth.authorizationHeader(config))
        assertEquals(1, requests)
        oauth.saveGitHubDeviceGrant(config, grant().copy(refreshToken = null,
            expiresAtMillis = 0L, refreshTokenExpiresAtMillis = 0L))
        now += 365L * 24 * 60 * 60 * 1_000
        assertEquals("Bearer access-secret", oauth.authorizationHeader(config))
        assertEquals(1, requests)
    }

    @Test fun replacementGrantAndCompatibilityOverloadClearPreviousAccountMetadata() {
        oauth.saveGitHubDeviceGrant(config, grant())
        val firstEpoch = oauth.githubAuthorizationEpoch(config)
        oauth.saveGitHubDeviceGrant(config, "replacement-access", null, 0L)
        val replacementEpoch = oauth.githubAuthorizationEpoch(config)

        assertNotEquals(firstEpoch, replacementEpoch)
        assertEquals("Bearer replacement-access", oauth.authorizationHeader(config))
        assertEquals(0L, oauth.githubRefreshTokenExpiresAtMillis(config))
        assertEquals(emptySet<String>(), oauth.githubGrantedScopes(config))
        assertTrue(repository.oauthValue(config.id, "oauth_refresh_token").isNullOrEmpty())
        assertTrue(repository.oauthValue(config.id, "oauth_expires_at").isNullOrEmpty())
        assertThrows(McpReauthRequiredException::class.java) { oauth.refreshAccessToken(config) }
        assertEquals(0, requests)
    }

    @Test fun malformedExplicitGrantMetadataIsRejectedWithoutReplacingExistingGrant() {
        val original = grant()
        oauth.saveGitHubDeviceGrant(config, original)
        val epoch = oauth.githubAuthorizationEpoch(config)
        listOf(
            original.copy(accessToken = "bad\r\nAuthorization: secret"),
            original.copy(accessToken = " "),
            original.copy(accessToken = "a".repeat(4097)),
            original.copy(refreshToken = "bad\trefresh"),
            original.copy(refreshToken = ""),
            original.copy(refreshToken = "r".repeat(4097)),
            original.copy(expiresAtMillis = -1),
            original.copy(refreshTokenExpiresAtMillis = -1),
            original.copy(refreshToken = null),
            original.copy(scopes = setOf("repo\nsecret")),
            original.copy(scopes = setOf("s".repeat(257))),
            original.copy(scopes = (1..129).map { "scope$it" }.toSet()),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { oauth.saveGitHubDeviceGrant(config, invalid) }
            assertEquals(epoch, oauth.githubAuthorizationEpoch(config))
            assertEquals("Bearer ${original.accessToken}", oauth.authorizationHeader(config))
        }
    }

    @Test fun malformedRefreshResponseNeverChangesCredentialsOrLeaksBody() {
        val invalidResponses = listOf(
            JSONObject().put("access_token", "access-secret\r\n"),
            JSONObject().put("access_token", 123),
            JSONObject().put("access_token", "a".repeat(4097)),
            JSONObject().put("access_token", "fresh-access").put("refresh_token", ""),
            JSONObject().put("access_token", "fresh-access").put("refresh_token", JSONObject.NULL),
            JSONObject().put("access_token", "fresh-access").put("refresh_token", "refresh-secret\t"),
            JSONObject().put("access_token", "fresh-access").put("expires_in", -1),
            JSONObject().put("access_token", "fresh-access").put("expires_in", "garbage-secret"),
            JSONObject().put("access_token", "fresh-access").put("expires_in", 1.5),
            JSONObject().put("access_token", "fresh-access").put("expires_in", Long.MAX_VALUE),
            JSONObject().put("access_token", "fresh-access").put("refresh_token_expires_in", JSONObject.NULL),
            JSONObject().put("access_token", "fresh-access").put("refresh_token_expires_in", -1),
            JSONObject().put("access_token", "fresh-access").put("scope", "repo\nsecret"),
            JSONObject().put("access_token", "fresh-access").put("scope", JSONObject.NULL),
            JSONObject().put("access_token", "fresh-access").put("scope", "s".repeat(4097)),
            JSONObject().put("access_token", "fresh-access").put("token_type", "secret-type"),
            JSONObject().put("access_token", "fresh-access").put("error", "access-secret refresh-secret"),
        )
        oauth.saveGitHubDeviceGrant(config, grant())
        val snapshot = vault.values.toMap()
        invalidResponses.forEach { invalid ->
            response = { invalid }
            val failure = assertThrows(McpReauthRequiredException::class.java) { oauth.refreshAccessToken(config) }
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertNull(failure.cause)
            assertEquals(snapshot, vault.values)
        }
    }

    @Test fun networkExceptionMessagesAreSanitizedAndNeverChained() {
        oauth.saveGitHubDeviceGrant(config, grant())
        response = { throw IOException("provider response: access-secret refresh-secret") }
        val failure = assertThrows(McpReauthRequiredException::class.java) { oauth.refreshAccessToken(config) }
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertNull(failure.cause)
    }

    @Test fun staleEndpointClientAuthModeCatalogOrDeletedConfigurationCannotUseGrant() {
        val changedConfigs = listOf(
            config.copy(endpoint = "https://attacker.example/mcp"),
            config.copy(oauthClientId = "another-client"),
            config.copy(authMode = McpAuthMode.BEARER),
            config.copy(catalogServiceId = null),
        )
        oauth.saveGitHubDeviceGrant(config, grant())
        changedConfigs.forEach { changed ->
            repository.upsert(changed)
            assertRejected { oauth.authorizationHeader(config) }
            assertRejected { oauth.refreshAccessToken(config) }
            assertRejected { oauth.githubAuthorizationEpoch(config) }
            assertRejected { oauth.saveGitHubDeviceGrant(config, grant()) }
            assertRejected { oauth.authorizationHeader(changed) }
            assertRejected { oauth.refreshAccessToken(changed) }
            repository.upsert(config)
        }
        repository.delete(config.id)
        assertRejected { oauth.authorizationHeader(config) }
        assertRejected { oauth.refreshAccessToken(config) }
        assertRejected { oauth.saveGitHubDeviceGrant(config, grant()) }
        assertEquals(0, requests)
    }

    @Test fun alteredCredentialBindingsNeverSendRefreshTokenToOtherEndpoint() {
        listOf("oauth_endpoint_binding", "oauth_token_endpoint", "oauth_github_client_binding").forEach { key ->
            oauth.saveGitHubDeviceGrant(config, grant())
            repository.saveOAuthValue(config.id, key, "https://attacker.example/")
            assertRejected { oauth.authorizationHeader(config) }
            assertRejected { oauth.refreshAccessToken(config) }
            assertRejected { oauth.githubAuthorizationEpoch(config) }
        }
        assertEquals(0, requests)
    }

    @Test fun configurationRemovalDuringHttpPreventsCredentialResurrection() {
        oauth.saveGitHubDeviceGrant(config, grant())
        response = {
            repository.delete(config.id)
            JSONObject().put("access_token", "late-access").put("refresh_token", "late-refresh")
        }
        assertRejected { oauth.refreshAccessToken(config) }
        assertTrue(vault.values.isEmpty())
        assertNull(repository.get(config.id))
    }

    @Test fun configurationChangeDuringHttpPreventsStaleRefreshSave() {
        oauth.saveGitHubDeviceGrant(config, grant())
        val original = vault.values.toMap()
        response = {
            repository.upsert(config.copy(oauthClientId = "another-client"))
            JSONObject().put("access_token", "late-access")
        }
        assertRejected { oauth.refreshAccessToken(config) }
        assertEquals(original, vault.values)
    }

    @Test fun replacementGrantDuringHttpWinsOverStaleRefresh() {
        oauth.saveGitHubDeviceGrant(config, grant())
        val firstEpoch = oauth.githubAuthorizationEpoch(config)
        response = {
            oauth.saveGitHubDeviceGrant(config, "new-account-access", null, 0L)
            JSONObject().put("access_token", "late-access").put("refresh_token", "late-refresh")
        }
        assertRejected { oauth.refreshAccessToken(config) }
        assertEquals("Bearer new-account-access", oauth.authorizationHeader(config))
        assertNotEquals(firstEpoch, oauth.githubAuthorizationEpoch(config))
    }

    @Test fun clearAndDisconnectRemoveEveryGrantFieldAndEpochWithoutHttp() {
        oauth.saveGitHubDeviceGrant(config, grant())
        val epoch = oauth.githubAuthorizationEpoch(config)
        oauth.clear(config.id)
        assertTrue(vault.values.values.all(String::isEmpty))
        assertRejected { oauth.githubAuthorizationEpoch(config) }
        oauth.saveGitHubDeviceGrant(config, grant())
        assertNotEquals(epoch, oauth.githubAuthorizationEpoch(config))
        // A stale/untrusted revocation URL must never receive GitHub credentials.
        repository.saveOAuthValue(config.id, "oauth_revocation_endpoint", "https://attacker.example/revoke")
        oauth.revokeAndClear(config)
        assertTrue(vault.values.values.all(String::isEmpty))
        assertRejected { oauth.authorizationHeader(config) }
        assertEquals(0, requests)
    }

    @Test fun legacyGrantMigratesEpochBeforeLeasingAndPreservesItAcrossRefresh() {
        repository.saveOAuthValue(config.id, "oauth_access_token", "legacy-access")
        repository.saveOAuthValue(config.id, "oauth_refresh_token", "legacy-refresh")
        repository.saveOAuthValue(config.id, "oauth_endpoint_binding", config.endpoint)
        repository.saveOAuthValue(config.id, "oauth_token_endpoint", GitHubDeviceFlowProtocol.TOKEN_ENDPOINT)
        val epoch = oauth.githubAuthorizationEpoch(config)
        assertTrue(epoch.isNotBlank())
        assertEquals(epoch, newManager().githubAuthorizationEpoch(config))
        assertEquals(GitHubDeviceFlowProtocol.CLIENT_ID,
            repository.oauthValue(config.id, "oauth_github_client_binding"))
        response = { JSONObject().put("access_token", "fresh-access") }
        oauth.refreshAccessToken(config)
        assertEquals(epoch, oauth.githubAuthorizationEpoch(config))
        assertEquals("legacy-refresh", repository.oauthValue(config.id, "oauth_refresh_token"))
    }

    @Test fun corruptedStoredMetadataFailsClosedWithoutLeakingItsContent() {
        listOf(
            "oauth_github_granted_scopes" to "[\"scope-secret\", 1]",
            "oauth_github_refresh_expires_at" to "refresh-secret",
            "oauth_github_authorization_epoch" to "access-secret",
        ).forEach { (key, value) ->
            oauth.saveGitHubDeviceGrant(config, grant())
            repository.saveOAuthValue(config.id, key, value)
            val failure = runCatching { oauth.refreshAccessToken(config) }.exceptionOrNull()
            assertTrue(failure != null)
            assertFalse(failure?.message.orEmpty().contains("secret"))
            assertNull(failure?.cause)
        }
        assertEquals(0, requests)
    }

    @Test fun nonGitHubRefreshRetainsDcrClientResourceAndOptionalRotationBehavior() {
        val other = config.copy(id = "dcr-service", alias = "DCR", endpoint = "https://example.test/mcp",
            catalogServiceId = null, oauthClientId = "dynamically-registered-client")
        repository.upsert(other)
        repository.saveOAuthValue(other.id, "oauth_access_token", "old-generic-access")
        repository.saveOAuthValue(other.id, "oauth_refresh_token", "old-generic-refresh")
        repository.saveOAuthValue(other.id, "oauth_endpoint_binding", other.endpoint)
        repository.saveOAuthValue(other.id, "oauth_token_endpoint", "https://example.test/oauth/token")
        val generic = McpOAuthManager(repository, { now }) { endpoint, fields ->
            assertEquals("https://example.test/oauth/token", endpoint)
            assertEquals("dynamically-registered-client", fields["client_id"])
            assertEquals(other.endpoint, fields["resource"])
            JSONObject().put("access_token", "new-generic-access").put("expires_in", 3600)
        }
        assertEquals("Bearer new-generic-access", generic.refreshAccessToken(other))
        assertEquals("old-generic-refresh", repository.oauthValue(other.id, "oauth_refresh_token"))
    }

    private fun newManager() = McpOAuthManager(repository, { now }) { endpoint, fields ->
        requests++
        assertEquals(GitHubDeviceFlowProtocol.TOKEN_ENDPOINT, endpoint)
        assertEquals(GitHubDeviceFlowProtocol.CLIENT_ID, fields["client_id"])
        assertEquals("refresh_token", fields["grant_type"])
        assertEquals(setOf("client_id", "grant_type", "refresh_token"), fields.keys)
        response()
    }

    private fun grant() = GitHubOAuthTokens("access-secret", "refresh-secret", now + 600_000L,
        now + 3_600_000L, setOf("read:user", "offline_access"))

    private fun assertRejected(block: () -> Unit) {
        assertTrue("Stale or invalid grant should have been rejected", runCatching(block).isFailure)
    }

    /** Replaces the encrypted SecretStore boundary, never SharedPreferences credential storage. */
    private class FakeEncryptedVault : McpCredentialVault {
        val values = mutableMapOf<Pair<String, String>, String>()
        override fun get(serverId: String, key: String) = values[serverId to key]
        override fun save(serverId: String, key: String, value: String) { values[serverId to key] = value }
        override fun clear(serverId: String) { values.keys.removeAll { it.first == serverId } }
    }
}

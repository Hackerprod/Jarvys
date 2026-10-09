package com.jarvys.agent

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import com.jarvys.agent.connectors.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoogleAuthorizationManagerTest {
    private lateinit var app: Application
    private val singletonField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var oldStore: Any? = null
    private val scope = GoogleOAuthProtocol.GMAIL_READ
    private class Identity : GoogleIdentityAuthorization {
        var email: String? = "owner@example.test"
        var authorizeCalls = 0
        var allowsResolution: Boolean? = null
        var grantedOverride: Set<String>? = null
        val requestedScopes = mutableListOf<Set<String>>()
        val requestedAccounts = mutableListOf<String?>()
        var beforeGrant: () -> Unit = {}
        var revokedAccount: String? = null
        var revokedScopes: Set<String> = emptySet()
        var revokeAction: () -> Unit = {}
        override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
            authorizeCalls++
            requestedScopes += scopes
            requestedAccounts += accountEmail
            beforeGrant()
            return GoogleIdentityGrant("fake-memory-only-token", grantedOverride ?: scopes, email)
        }
        override fun authorizeCancellable(scopes: Set<String>, accountEmail: String?, token: CancellationToken,
                                          allowResolution: Boolean): GoogleIdentityGrant {
            allowsResolution = allowResolution
            return authorize(scopes, accountEmail)
        }
        override fun clearToken(accessToken: String) = Unit
        override fun revoke(scopes: Set<String>, accountEmail: String?) {
            revokedScopes = scopes
            revokedAccount = accountEmail
            revokeAction()
        }
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        oldStore = singletonField.get(null)
        app.getSharedPreferences("fake_google_secrets", Context.MODE_PRIVATE).edit().clear().commit()
        singletonField.set(null, SecretStore(app.getSharedPreferences("fake_google_secrets", Context.MODE_PRIVATE)))
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { singletonField.set(null, oldStore) }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Expected fake Google operation to finish", condition())
    }
    private fun manager(identity: Identity): GoogleOAuthManager = GoogleOAuthManager(app,
        GoogleHttpTransport { _, _, _, _ -> GoogleHttpResponse(200, "{}") }, identity)
    private fun seedGrant() {
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit()
            .putString("google_identity_granted_scopes", scope)
            .putString("google_identity_account_email", "owner@example.test").commit()
    }

    @Test fun normalRequestsCannotExpandScopeOrLaunchConsentAndNativeTokenIsNotPersisted() {
        val identity = Identity()
        val manager = manager(identity)
        assertTrue(runCatching { manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages") }.isFailure)
        assertEquals(0, identity.authorizeCalls)
        seedGrant()
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(false, identity.allowsResolution)
        assertFalse(app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).all.values.toString().contains("fake-memory-only-token"))
        assertFalse(app.getSharedPreferences("fake_google_secrets", Context.MODE_PRIVATE).all.values.toString().contains("fake-memory-only-token"))
    }

    @Test fun disconnectWinsAgainstLateAuthorizationAndNoSuccessCallbackReconnects() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identity = Identity().apply { beforeGrant = { started.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val manager = manager(identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var callbacks = 0
        manager.authorize(activity, scope) { callbacks++ }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(manager.isAuthorizationInProgress())
        manager.disconnectLocal()
        val revision = manager.stateRevision.value
        release.countDown()
        waitUntil { manager.stateRevision.value > revision }
        assertFalse(manager.isScopeGranted(scope))
        assertFalse(manager.isAuthorizationInProgress())
        assertEquals(0, callbacks)
    }

    @Test fun destroyedActivityInvalidatesAuthorizationBeforeGrantPersistence() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identity = Identity().apply { beforeGrant = { started.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val manager = manager(identity)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var callbacks = 0
        manager.authorize(controller.get(), scope) { callbacks++ }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        controller.pause().stop().destroy()
        val revision = manager.stateRevision.value
        release.countDown()
        waitUntil { manager.stateRevision.value > revision }
        assertFalse(manager.isScopeGranted(scope))
        assertEquals(0, callbacks)
    }

    @Test fun remoteRevocationIsPendingUntilAcknowledgedAndFailureIsNeverReportedVerified() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identity = Identity().apply { revokeAction = { started.countDown(); release.await(2, TimeUnit.SECONDS); error("private provider text") } }
        val manager = manager(identity)
        seedGrant()
        var callback: Result<Unit>? = null
        manager.revokeAndClear { callback = it }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(GoogleRevocationState.PENDING, manager.revocationState())
        assertFalse(manager.isScopeGranted(scope))
        release.countDown()
        waitUntil { callback != null }
        assertEquals(GoogleRevocationState.FAILED, manager.revocationState())
        assertTrue(callback!!.isFailure)
        assertFalse(callback!!.exceptionOrNull()!!.message.orEmpty().contains("private"))
        identity.revokeAction = {}
        callback = null
        manager.revokeAndClear { callback = it }
        waitUntil { callback != null }
        assertEquals(GoogleRevocationState.VERIFIED, manager.revocationState())
    }

    @Test fun unknownAccountDoesNotCarryPriorFeaturePermissions() {
        val identity = Identity().apply { email = null; grantedOverride = setOf(GoogleOAuthProtocol.DRIVE_READ) }
        val manager = manager(identity)
        seedGrant()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var completed = false
        manager.authorize(activity, GoogleOAuthProtocol.DRIVE_READ) { assertTrue(it.isSuccess); completed = true }
        waitUntil { completed }
        assertFalse(manager.isScopeGranted(scope))
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.DRIVE_READ))
        assertNull(manager.identityAccountEmail())
    }

    @Test fun nullAccountIncrementalScopesStayCoherentAndPartialDenialClearsMissingPermissions() {
        val identity = Identity().apply { email = null }
        val manager = manager(identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scopes = listOf(scope, GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleOAuthProtocol.GMAIL_SEND, GoogleOAuthProtocol.DRIVE_READ)
        val expected = linkedSetOf<String>()
        scopes.forEach { requested ->
            var result: Result<Unit>? = null
            manager.authorize(activity, requested) { result = it }
            waitUntil { result != null && !manager.isAuthorizationInProgress() }
            assertTrue(result!!.isSuccess)
            expected += GoogleOAuthProtocol.effectiveGrantedScope(requested, expected) ?: requested
            assertEquals(expected, identity.requestedScopes.last())
            assertEquals(expected, manager.grantedScopes())
        }
        identity.grantedOverride = setOf(scope)
        var result: Result<Unit>? = null
        manager.authorize(activity, GoogleOAuthProtocol.DRIVE_FILE) { result = it }
        waitUntil { result != null && !manager.isAuthorizationInProgress() }
        assertTrue(result!!.isFailure)
        assertEquals(setOf(scope), manager.grantedScopes())
    }

    @Test fun localDisconnectRetainsPinnedRevocationContextAndEncryptedLegacyGrants() {
        val identity = Identity()
        val manager = manager(identity)
        seedGrant()
        manager.disconnectLocal()
        assertEquals(GoogleRevocationState.NOT_REQUESTED, manager.revocationState())
        assertNull(manager.identityAccountEmail())
        var complete = false
        manager.revokeAndClear { assertTrue(it.isSuccess); complete = true }
        waitUntil { complete }
        assertEquals("owner@example.test", identity.revokedAccount)
        assertEquals(setOf(scope), identity.revokedScopes)
        val client = "fake-desktop-client"
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|account-one").take(48)
        manager.configure(client, "fake-client-secret", "account-one")
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-legacy-grant")
        manager.disconnectLocal()
        assertEquals("fake-legacy-grant", SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_refresh"))
        manager.configure("different-fake-desktop-client", "", "account-two")
        assertEquals("fake-legacy-grant", SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_refresh"))
    }

    @Test fun normalRequestRefusesSilentAccountChangeBeforeAnyApiWrite() {
        val identity = Identity().apply { email = "different@example.test" }
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> apiCalls++; GoogleHttpResponse(200, "{}") }, identity)
        seedGrant()
        val error = runCatching { manager.request(scope, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}") }.exceptionOrNull()
        assertTrue(error is GoogleIdentityAuthorizationException)
        assertEquals(0, apiCalls)
        assertFalse(manager.isScopeGranted(scope))
    }
    @Test fun legacyProjectWideRevocationStopsAfterFirstAcknowledgedToken() {
        val requests = mutableListOf<String?>()
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, _, body ->
            assertEquals("POST", method)
            assertEquals(GoogleOAuthProtocol.REVOKE_ENDPOINT, url)
            requests += body
            GoogleHttpResponse(if (requests.size == 1) 200 else 400, "")
        }, Identity())
        val client = "fake-revocation-client"
        manager.configure(client, "", "fake-account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|fake-account").take(48)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-token-one")
        SecretStore.get(app).saveConnectorSecret(owner, "grant_drive_read_refresh", "fake-token-two")
        var result: Result<Unit>? = null
        manager.revokeAndClear { result = it }
        waitUntil { result != null }
        assertTrue(result!!.isSuccess)
        assertEquals(1, requests.size)
        assertEquals(GoogleRevocationState.VERIFIED, manager.revocationState())
    }

    @Test fun legacyRevocationTriesAnotherSavedTokenWhenFirstIsNotAcknowledged() {
        var calls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ ->
            calls++
            GoogleHttpResponse(if (calls == 1) 400 else 200, "")
        }, Identity())
        val client = "fake-revocation-fallback"
        manager.configure(client, "", "fake-account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|fake-account").take(48)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-token-one")
        SecretStore.get(app).saveConnectorSecret(owner, "grant_drive_read_refresh", "fake-token-two")
        var result: Result<Unit>? = null
        manager.revokeAndClear { result = it }
        waitUntil { result != null }
        assertTrue(result!!.isSuccess)
        assertEquals(2, calls)
    }


    @Test fun staleApprovalEpochCannotAcquireNewAccountOrSendPostAfterReconnect() {
        val identity = Identity()
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> apiCalls++; GoogleHttpResponse(200, "{}") }, identity)
        seedGrant()
        val epochA = manager.currentAuthorizationEpoch()
        manager.disconnectLocal()
        identity.email = "new-account@example.test"
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var connected = false
        manager.authorize(activity, scope) { assertTrue(it.isSuccess); connected = true }
        waitUntil { connected && !manager.isAuthorizationInProgress() }
        val tokenCallsAfterReconnect = identity.authorizeCalls
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
        val error = runCatching { manager.requestCancellable(scope, "POST", url, "{}", token = CancellationToken.cancellable(),
            expectedAuthorizationEpoch = epochA) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(tokenCallsAfterReconnect, identity.authorizeCalls)
        assertEquals(0, apiCalls)
        assertNotEquals(epochA, manager.currentAuthorizationEpoch())
        assertEquals(200, manager.requestCancellable(scope, "POST", url, "{}", token = CancellationToken.cancellable(),
            expectedAuthorizationEpoch = manager.currentAuthorizationEpoch()).status)
        assertEquals(1, apiCalls)
    }

    @Test fun failedLegacyRevocationSurvivesManagerRecreationAndRetriesSameEncryptedGrant() {
        val bodies = mutableListOf<String?>()
        val transport = GoogleHttpTransport { _, _, _, body ->
            bodies += body
            GoogleHttpResponse(if (bodies.size == 1) 503 else 200, "")
        }
        val manager = GoogleOAuthManager(app, transport, Identity())
        val client = "fake-persist-revocation"
        manager.configure(client, "fake-secret", "account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|account").take(48)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-pending-token")
        var first: Result<Unit>? = null
        manager.revokeAndClear { first = it }
        waitUntil { first != null }
        assertTrue(first!!.isFailure)
        assertTrue(manager.hasPendingLegacyRevocation())
        assertEquals("fake-pending-token", SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_refresh"))
        assertFalse(app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).all.values.toString().contains("fake-pending-token"))
        val recreated = GoogleOAuthManager(app, transport, Identity())
        assertTrue(recreated.hasPendingLegacyRevocation())
        assertTrue(runCatching { recreated.configure("another-client", "", "another-account") }.isFailure)
        var retried: Result<Unit>? = null
        recreated.revokeAndClear { retried = it }
        waitUntil { retried != null }
        assertTrue(retried!!.isSuccess)
        assertEquals(listOf("token=fake-pending-token", "token=fake-pending-token"), bodies)
        assertEquals(GoogleRevocationState.VERIFIED, recreated.revocationState())
        assertFalse(recreated.hasPendingLegacyRevocation())
        assertNull(SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_refresh"))
        assertEquals("", recreated.configuredClientId())
    }

    @Test fun explicitLocalForgetDoesNotClaimRemoteRevocationAndAllowsRecovery() {
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> GoogleHttpResponse(400, "") }, Identity())
        val client = "fake-forget-revocation"
        manager.configure(client, "", "account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|account").take(48)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-already-revoked-token")
        SecretStore.get(app).saveConnectorSecret("unrelated-owner", "refresh", "unrelated-fake-token")
        var result: Result<Unit>? = null
        manager.revokeAndClear { result = it }
        waitUntil { result != null }
        assertTrue(manager.hasPendingLegacyRevocation())
        val epoch = manager.currentAuthorizationEpoch()
        manager.forgetPendingRevocationLocally()
        assertFalse(manager.hasPendingLegacyRevocation())
        assertEquals(GoogleRevocationState.NOT_REQUESTED, manager.revocationState())
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        assertNull(SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_refresh"))
        assertEquals("unrelated-fake-token", SecretStore.get(app).getConnectorSecret("unrelated-owner", "refresh"))
        manager.configure("recovered-client", "", "new-account")
        assertEquals("recovered-client", manager.configuredClientId())
    }

    @Test fun nativeUnknownAccountReconnectCannotRevokePreviousDisconnectedAccount() {
        val identity = Identity()
        val manager = manager(identity)
        seedGrant()
        manager.disconnectLocal()
        identity.email = null
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var connected = false
        manager.authorize(activity, GoogleOAuthProtocol.DRIVE_READ) { assertTrue(it.isSuccess); connected = true }
        waitUntil { connected && !manager.isAuthorizationInProgress() }
        var revoked = false
        manager.revokeAndClear { assertTrue(it.isSuccess); revoked = true }
        waitUntil { revoked }
        assertNull(identity.revokedAccount)
        assertEquals(setOf(GoogleOAuthProtocol.DRIVE_READ), identity.revokedScopes)
    }
    @Test fun encryptedPendingRevocationBlocksIoEvenIfLocalPreferenceWasNotPersisted() {
        var calls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> calls++; GoogleHttpResponse(200, "{}") }, Identity())
        manager.configure("fake-crash-client", "", "fake-crash-account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("fake-crash-client|fake-crash-account").take(48)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-retained-token")
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_read_scopes", scope)
        // Simulate the durable marker surviving before the local-disabled preference reaches disk.
        SecretStore.get(app).saveConnectorSecret("full_pending_revocation", "pending_owner", owner)
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().putBoolean("google_locally_disabled", false).commit()
        assertTrue(manager.hasPendingLegacyRevocation())
        assertFalse(manager.isScopeGranted(scope))
        assertTrue(runCatching { manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages") }.isFailure)
        assertEquals(0, calls)
    }


    private fun seedScopes(vararg scopes: String) {
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit()
            .putString("google_identity_granted_scopes", scopes.joinToString(" "))
            .putString("google_identity_account_email", "owner@example.test").commit()
    }

    @Test fun modifyAndFullGrantsExposeCapabilitiesWithoutSynthesizingExactScopeEntries() {
        val manager = manager(Identity())
        for (actual in listOf(GoogleOAuthProtocol.GMAIL_MODIFY, GoogleOAuthProtocol.GMAIL_FULL)) {
            seedScopes(actual)
            assertEquals(setOf(actual), manager.grantedScopes())
            for (capability in listOf(scope, GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleOAuthProtocol.GMAIL_SEND,
                GoogleOAuthProtocol.GMAIL_LABELS, GoogleOAuthProtocol.GMAIL_MODIFY)) assertTrue(manager.isScopeGranted(capability))
            assertEquals(actual == GoogleOAuthProtocol.GMAIL_FULL, manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
        }
        manager.disableScope(scope)
        assertTrue(manager.grantedScopes().isEmpty())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
        assertFalse(manager.isScopeGranted(scope))
    }

    @Test fun nativeRequestsSelectLeastActualAcceptedGrantAndNeverRequestUnneededSiblingScopes() {
        val identity = Identity()
        val manager = manager(identity)
        seedScopes(scope, GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleOAuthProtocol.GMAIL_MODIFY,
            GoogleOAuthProtocol.GMAIL_FULL, GoogleOAuthProtocol.DRIVE_READ)
        val before = manager.grantedScopes()
        val epoch = manager.currentAuthorizationEpoch()
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(setOf(scope), identity.requestedScopes.last())
        manager.request(GoogleOAuthProtocol.GMAIL_SEND, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}")
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_COMPOSE), identity.requestedScopes.last())
        manager.request(GoogleOAuthProtocol.GMAIL_MODIFY, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/fixture/modify", "{}")
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), identity.requestedScopes.last())
        assertEquals(false, identity.allowsResolution)
        assertEquals(before, manager.grantedScopes())
        assertEquals(epoch, manager.currentAuthorizationEpoch())
    }

    @Test fun modifyOnlyNativeReadRequestsActualModifyWithoutAddingReadonlyOrFull() {
        val identity = Identity()
        val manager = manager(identity)
        seedScopes(GoogleOAuthProtocol.GMAIL_MODIFY)
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_MODIFY)), identity.requestedScopes)
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), manager.grantedScopes())
        assertEquals(false, identity.allowsResolution)
        val calls = identity.authorizeCalls
        assertTrue(runCatching { manager.request(GoogleOAuthProtocol.GMAIL_FULL, "DELETE",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/fixture") }.isFailure)
        assertEquals(calls, identity.authorizeCalls)
    }

    @Test fun explicitExpansionDeniedBeforeGrantKeepsWorkingReadAccountAndLegacyCredentials() {
        val identity = Identity().apply { beforeGrant = {
            throw GoogleIdentityAuthorizationException(GoogleIdentityFailure.SCOPE_NOT_GRANTED, "denied fixture")
        } }
        val manager = manager(identity)
        seedGrant()
        SecretStore.get(app).saveConnectorSecret("unrelated-owner", "refresh", "fake-retained-credential")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: Result<Unit>? = null
        manager.authorize(activity, GoogleOAuthProtocol.GMAIL_MODIFY) { result = it }
        waitUntil { result != null && !manager.isAuthorizationInProgress() }
        assertTrue(result!!.isFailure)
        assertEquals(setOf(scope), manager.grantedScopes())
        assertEquals("owner@example.test", manager.identityAccountEmail())
        assertEquals("fake-retained-credential", SecretStore.get(app).getConnectorSecret("unrelated-owner", "refresh"))
        assertEquals(listOf(setOf(scope, GoogleOAuthProtocol.GMAIL_MODIFY)), identity.requestedScopes)
        assertFalse(manager.isIdentityReauthorizationRequired())
    }

    @Test fun interactiveSupersetResponsePersistsOnlyActualFullGrantAndInvalidatesOldApprovalEpoch() {
        val identity = Identity().apply { grantedOverride = setOf(GoogleOAuthProtocol.GMAIL_FULL) }
        val manager = manager(identity)
        seedGrant()
        val epoch = manager.currentAuthorizationEpoch()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: Result<Unit>? = null
        manager.authorize(activity, GoogleOAuthProtocol.GMAIL_MODIFY) { result = it }
        waitUntil { result != null && !manager.isAuthorizationInProgress() }
        assertTrue(result!!.isSuccess)
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_FULL), manager.grantedScopes())
        assertTrue(manager.isScopeGranted(scope))
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        val calls = identity.authorizeCalls
        assertTrue(runCatching { manager.requestCancellable(scope, "GET",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages", token = CancellationToken.cancellable(),
            expectedAuthorizationEpoch = epoch) }.isFailure)
        assertEquals(calls, identity.authorizeCalls)
    }

    @Test fun lostSelectedNativeScopeStopsBeforeApiAndPreservesUnrequestedSiblingGrant() {
        val identity = Identity().apply { grantedOverride = setOf(GoogleOAuthProtocol.DRIVE_READ) }
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> apiCalls++; GoogleHttpResponse(200, "{}") }, identity)
        seedScopes(GoogleOAuthProtocol.GMAIL_MODIFY, GoogleOAuthProtocol.DRIVE_READ)
        val epoch = manager.currentAuthorizationEpoch()
        assertTrue(runCatching { manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages") }.isFailure)
        assertEquals(0, apiCalls)
        assertEquals(setOf(GoogleOAuthProtocol.DRIVE_READ), manager.grantedScopes())
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        assertTrue(manager.isIdentityReauthorizationRequired())
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), identity.requestedScopes.single())
    }

    private fun seedLegacy(manager: GoogleOAuthManager, key: String, scopes: Set<String>, expiresAt: Long = System.currentTimeMillis() + 3_600_000): String {
        val client = "ux31-fake-legacy-client"
        val account = "ux31-fake-account"
        manager.configure(client, "fake-client-secret", account)
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("$client|$account").take(48)
        val secrets = SecretStore.get(app)
        secrets.saveConnectorSecret(owner, "grant_${key}_refresh", "fake-legacy-refresh")
        secrets.saveConnectorSecret(owner, "grant_${key}_access", "fake-legacy-access")
        secrets.saveConnectorSecret(owner, "grant_${key}_expiry", expiresAt.toString())
        secrets.saveConnectorSecret(owner, "grant_${key}_scopes", scopes.joinToString(" "))
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().putBoolean("google_locally_disabled", false).commit()
        return owner
    }

    @Test fun legacySupersetGrantKeepsItsStorageKeyAndSatisfiesReadWithoutNewAuthorization() {
        val identity = Identity()
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, headers, _ ->
            assertTrue(url.startsWith("https://gmail.googleapis.com/"))
            assertEquals("Bearer fake-legacy-access", headers["Authorization"])
            apiCalls++; GoogleHttpResponse(200, "{}")
        }, identity)
        seedLegacy(manager, "gmail_read", setOf(GoogleOAuthProtocol.GMAIL_MODIFY))
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), manager.grantedScopes())
        assertTrue(manager.isScopeGranted(scope))
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(1, apiCalls)
        assertEquals(0, identity.authorizeCalls)
    }

    @Test fun legacyRefreshWithoutScopeRetainsExactGrantAndNeverSynthesizesLogicalReadonly() {
        var refreshCalls = 0
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, _, _ ->
            if (url == GoogleOAuthProtocol.TOKEN_ENDPOINT) {
                refreshCalls++
                GoogleHttpResponse(200, """{"access_token":"fake-refreshed-access","expires_in":3600}""")
            } else { apiCalls++; GoogleHttpResponse(200, "{}") }
        }, Identity())
        val owner = seedLegacy(manager, "gmail_read", setOf(GoogleOAuthProtocol.GMAIL_MODIFY), 0)
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(1, refreshCalls); assertEquals(1, apiCalls)
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), manager.grantedScopes())
        assertEquals(GoogleOAuthProtocol.GMAIL_MODIFY, SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_scopes"))
        assertEquals("fake-refreshed-access", SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_read_access"))
        assertNull(SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_modify_access"))
    }

    @Test fun legacyRefreshLosingSelectedScopeInvalidatesEpochBeforeMailApiCall() {
        var apiCalls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, _, _ ->
            if (url == GoogleOAuthProtocol.TOKEN_ENDPOINT) GoogleHttpResponse(200,
                """{"access_token":"fake-refreshed-access","expires_in":3600,"scope":"${GoogleOAuthProtocol.GMAIL_READ}"}""")
            else { apiCalls++; GoogleHttpResponse(200, "{}") }
        }, Identity())
        seedLegacy(manager, "gmail_modify", setOf(GoogleOAuthProtocol.GMAIL_MODIFY), 0)
        val epoch = manager.currentAuthorizationEpoch()
        assertTrue(runCatching { manager.request(GoogleOAuthProtocol.GMAIL_MODIFY, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/fixture/modify", "{}") }.isFailure)
        assertEquals(0, apiCalls)
        assertEquals(setOf(scope), manager.grantedScopes())
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
    }

    @Test fun simultaneousExpansionClicksLaunchOnlyOneConsentAndCancelKeepsOriginalGrant() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identity = Identity().apply { beforeGrant = { started.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val manager = manager(identity)
        seedGrant()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var firstCallbacks = 0
        var second: Result<Unit>? = null
        manager.authorize(activity, GoogleOAuthProtocol.GMAIL_MODIFY) { firstCallbacks++ }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        manager.authorize(activity, GoogleOAuthProtocol.GMAIL_FULL) { second = it }
        assertTrue(requireNotNull(second).isFailure)
        assertEquals(1, identity.authorizeCalls)
        manager.cancelAuthorization()
        val revision = manager.stateRevision.value
        release.countDown()
        waitUntil { manager.stateRevision.value > revision }
        assertEquals(0, firstCallbacks)
        assertEquals(setOf(scope), manager.grantedScopes())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
    }

    @Test fun nativeScopeReductionRetainsConfirmedNarrowerReadButRequiresNewApproval() {
        val identity = Identity().apply { grantedOverride = setOf(scope) }
        val manager = manager(identity)
        seedScopes(GoogleOAuthProtocol.GMAIL_MODIFY)
        val epoch = manager.currentAuthorizationEpoch()
        assertTrue(runCatching { manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages") }.isFailure)
        assertEquals(setOf(scope), manager.grantedScopes())
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
    }

    @Test fun unsolicitedNativeFullResponseCannotExpandPersistedGrantDuringRead() {
        val identity = Identity().apply { grantedOverride = setOf(scope, GoogleOAuthProtocol.GMAIL_FULL) }
        val manager = manager(identity)
        seedGrant()
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(setOf(scope), identity.requestedScopes.single())
        assertEquals(setOf(scope), manager.grantedScopes())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
    }

    @Test fun unsolicitedLegacyFullRefreshScopeCannotExpandPersistedGrantDuringRead() {
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, _, _ ->
            if (url == GoogleOAuthProtocol.TOKEN_ENDPOINT) GoogleHttpResponse(200,
                """{"access_token":"fake-refreshed-access","expires_in":3600,"scope":"$scope ${GoogleOAuthProtocol.GMAIL_FULL}"}""")
            else GoogleHttpResponse(200, "{}")
        }, Identity())
        seedLegacy(manager, "gmail_read", setOf(scope), 0)
        manager.request(scope, "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages")
        assertEquals(setOf(scope), manager.grantedScopes())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
    }

    private fun extraLegacy(owner: String, key: String, scopes: Set<String>, access: String) {
        val store = SecretStore.get(app)
        store.saveConnectorSecret(owner, "grant_${key}_refresh", "fake-${key}-refresh")
        store.saveConnectorSecret(owner, "grant_${key}_access", access)
        store.saveConnectorSecret(owner, "grant_${key}_expiry", (System.currentTimeMillis() + 3_600_000).toString())
        store.saveConnectorSecret(owner, "grant_${key}_scopes", scopes.joinToString(" "))
    }

    @Test fun accountVerificationDefaultIsExplicitlyUnsupported() {
        val unsupported = object : GoogleRestAuthorization {
            override fun isScopeGranted(scope: String) = true
            override fun request(scope: String, method: String, url: String, body: String?, contentType: String) =
                error("An unsupported proof must not dispatch")
        }
        assertTrue(runCatching { unsupported.verifyGmailAccount(scope, null, CancellationToken.uncancellable(), 0) }.isFailure)
    }

    @Test fun legacyReadAndComposeTokensForDifferentAccountsCannotShareOneApprovalEpoch() {
        var mutations = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, headers, _ ->
            if (method != "GET") mutations++
            assertTrue(url.endsWith("/profile"))
            val email = if (headers["Authorization"] == "Bearer fake-compose-access") "other@example.test" else "owner@example.test"
            GoogleHttpResponse(200, """{"emailAddress":"$email"}""")
        }, Identity())
        val owner = seedLegacy(manager, "gmail_read", setOf(scope))
        extraLegacy(owner, "gmail_compose", setOf(GoogleOAuthProtocol.GMAIL_COMPOSE), "fake-compose-access")
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("owner@example.test", manager.verifyGmailAccount(scope, null, CancellationToken.uncancellable(), epoch))
        assertTrue(runCatching { manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_COMPOSE,
            "owner@example.test", CancellationToken.uncancellable(), epoch) }.isFailure)
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
        assertEquals(0, mutations)
        assertEquals("fake-compose-access", SecretStore.get(app).getConnectorSecret(owner, "grant_gmail_compose_access"))
        assertEquals("ux31-fake-account", manager.configuredAccountLabel())
    }

    @Test fun legacySendOnlyCannotProveAccountAndNeverDispatchesEvenWhenAccountLabelLooksLikeEmail() {
        var requests = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> requests++; GoogleHttpResponse(200, "{}") }, Identity())
        seedLegacy(manager, "gmail_send", setOf(GoogleOAuthProtocol.GMAIL_SEND))
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_SEND), manager.grantedScopes())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND))
        val failure = runCatching { manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND, null,
            CancellationToken.uncancellable(), manager.currentAuthorizationEpoch()) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(app.getString(R.string.full_google_legacy_send_unverified), failure!!.message)
        assertTrue(runCatching { manager.request(GoogleOAuthProtocol.GMAIL_SEND, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}") }.isFailure)
        assertEquals(0, requests)
    }

    @Test fun legacySendUsesAlreadyGrantedComposeAlternativeAndVerifiesExactTokenWithoutNewScopes() {
        val seenTokens = mutableListOf<String?>()
        val identity = Identity()
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, headers, _ ->
            seenTokens += headers["Authorization"]
            GoogleHttpResponse(200, if (url.endsWith("/profile")) """{"emailAddress":"owner@example.test"}""" else "{}")
        }, identity)
        val owner = seedLegacy(manager, "gmail_send", setOf(GoogleOAuthProtocol.GMAIL_SEND))
        extraLegacy(owner, "gmail_compose", setOf(GoogleOAuthProtocol.GMAIL_COMPOSE), "fake-compose-access")
        assertEquals(GoogleOAuthProtocol.GMAIL_COMPOSE, manager.effectiveGrantedScope(GoogleOAuthProtocol.GMAIL_SEND))
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("owner@example.test", manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND,
            null, CancellationToken.uncancellable(), epoch))
        manager.request(GoogleOAuthProtocol.GMAIL_SEND, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}")
        assertTrue(seenTokens.isNotEmpty()); assertTrue(seenTokens.all { it == "Bearer fake-compose-access" })
        assertEquals(0, identity.authorizeCalls)
        manager.disconnectLocal()
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND))
    }

    @Test fun legacyDispatchRechecksExactTokenAfterSeparateVerificationAndBlocksAccountReplacement() {
        var mutations = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, headers, _ ->
            if (method != "GET") mutations++
            val email = if (headers["Authorization"] == "Bearer fake-replaced-access") "other@example.test" else "owner@example.test"
            GoogleHttpResponse(200, if (url.endsWith("/profile")) """{"emailAddress":"$email"}""" else "{}")
        }, Identity())
        val owner = seedLegacy(manager, "gmail_compose", setOf(GoogleOAuthProtocol.GMAIL_COMPOSE))
        val epoch = manager.currentAuthorizationEpoch()
        manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_COMPOSE, null, CancellationToken.uncancellable(), epoch)
        SecretStore.get(app).saveConnectorSecret(owner, "grant_gmail_compose_access", "fake-replaced-access")
        assertTrue(runCatching { manager.requestCancellable(GoogleOAuthProtocol.GMAIL_COMPOSE, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/drafts", "{}", token = CancellationToken.uncancellable(),
            expectedAuthorizationEpoch = epoch) }.isFailure)
        assertEquals(0, mutations)
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
    }

    @Test fun legacy401RefreshCannotRetryMutationWithADifferentVerifiedAccount() {
        var mutations = 0
        var refreshes = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, headers, _ ->
            when {
                url == GoogleOAuthProtocol.TOKEN_ENDPOINT -> {
                    refreshes++; GoogleHttpResponse(200, """{"access_token":"fake-other-account-access","expires_in":3600}""")
                }
                url.endsWith("/profile") -> {
                    val email = if (headers["Authorization"] == "Bearer fake-other-account-access") "other@example.test" else "owner@example.test"
                    GoogleHttpResponse(200, """{"emailAddress":"$email"}""")
                }
                else -> { assertEquals("POST", method); mutations++; GoogleHttpResponse(401, "{}") }
            }
        }, Identity())
        seedLegacy(manager, "gmail_compose", setOf(GoogleOAuthProtocol.GMAIL_COMPOSE))
        val epoch = manager.currentAuthorizationEpoch()
        manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_COMPOSE, null, CancellationToken.uncancellable(), epoch)
        val failure = runCatching { manager.requestCancellable(GoogleOAuthProtocol.GMAIL_COMPOSE, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/drafts", "{}", token = CancellationToken.uncancellable(),
            expectedAuthorizationEpoch = epoch) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure is GooglePreDispatchAuthorizationException)
        assertEquals(1, mutations); assertEquals(1, refreshes)
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
    }

    @Test fun nativeUnknownAccountIsProvenByProfileThenPinnedForSendOnlyGrant() {
        val identity = Identity().apply { email = null }
        var profileReads = 0
        var mutations = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, _, _ ->
            if (url.endsWith("/profile")) { profileReads++; GoogleHttpResponse(200, """{"emailAddress":"verified@example.test"}""") }
            else { assertEquals("POST", method); mutations++; GoogleHttpResponse(200, "{}") }
        }, identity)
        seedScopes(scope, GoogleOAuthProtocol.GMAIL_SEND)
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().remove("google_identity_account_email").commit()
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("verified@example.test", manager.verifyGmailAccount(scope, null, CancellationToken.uncancellable(), epoch))
        assertEquals("verified@example.test", manager.identityAccountEmail())
        assertEquals("verified@example.test", manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND,
            "verified@example.test", CancellationToken.uncancellable(), epoch))
        manager.request(GoogleOAuthProtocol.GMAIL_SEND, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}")
        assertEquals(1, profileReads); assertEquals(1, mutations)
        assertNull(identity.requestedAccounts.first())
        assertTrue(identity.requestedAccounts.drop(1).all { it == "verified@example.test" })
    }

    @Test fun nativeUnknownSendOnlyGrantCannotBeInferredFromAccountLabelOrExpectedAccount() {
        val identity = Identity().apply { email = null }
        var calls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> calls++; GoogleHttpResponse(200, "{}") }, identity)
        seedScopes(GoogleOAuthProtocol.GMAIL_SEND)
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().remove("google_identity_account_email").commit()
        assertTrue(runCatching { manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND, "expected@example.test",
            CancellationToken.uncancellable(), manager.currentAuthorizationEpoch()) }.isFailure)
        assertEquals(0, calls)
        assertNull(manager.identityAccountEmail())
    }

    @Test fun nativeKnownAccountChangeBetweenVerificationAndDispatchCannotSend() {
        val identity = Identity()
        var mutations = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> mutations++; GoogleHttpResponse(200, "{}") }, identity)
        seedScopes(GoogleOAuthProtocol.GMAIL_SEND)
        val epoch = manager.currentAuthorizationEpoch()
        manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND, null, CancellationToken.uncancellable(), epoch)
        identity.email = "other@example.test"
        assertTrue(runCatching { manager.requestCancellable(GoogleOAuthProtocol.GMAIL_SEND, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}", token = CancellationToken.uncancellable(),
            expectedAuthorizationEpoch = epoch) }.isFailure)
        assertEquals(0, mutations)
        assertNotEquals(epoch, manager.currentAuthorizationEpoch())
    }

    @Test fun nativeUnknownReadAndSendGrantPinsReadAccountBeforeIssuingBoundSendToken() {
        val identity = Identity().apply { email = null }
        var profiles = 0
        var effects = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, _, _ ->
            if (url.endsWith("/profile")) { profiles++; GoogleHttpResponse(200, """{"emailAddress":"verified@example.test"}""") }
            else { assertEquals("POST", method); effects++; GoogleHttpResponse(200, "{}") }
        }, identity)
        seedScopes(scope, GoogleOAuthProtocol.GMAIL_SEND)
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().remove("google_identity_account_email").commit()
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("verified@example.test", manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_SEND,
            null, CancellationToken.uncancellable(), epoch))
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_SEND), setOf(scope), setOf(GoogleOAuthProtocol.GMAIL_SEND)), identity.requestedScopes)
        assertEquals(listOf(null, null, "verified@example.test"), identity.requestedAccounts)
        manager.request(GoogleOAuthProtocol.GMAIL_SEND, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "{}")
        assertEquals(1, profiles); assertEquals(1, effects)
        assertEquals(setOf(scope, GoogleOAuthProtocol.GMAIL_SEND), manager.grantedScopes())
    }

    @Test fun centralProofFailureIsMarkedPreDispatchBeforeAnyMutation() {
        var effects = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, _, _ ->
            if (url.endsWith("/profile")) GoogleHttpResponse(200, "{}")
            else { effects++; GoogleHttpResponse(200, "{}") }
        }, Identity())
        seedLegacy(manager, "gmail_compose", setOf(GoogleOAuthProtocol.GMAIL_COMPOSE))
        val error = runCatching { manager.request(GoogleOAuthProtocol.GMAIL_COMPOSE, "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/drafts", "{}") }.exceptionOrNull()
        assertTrue(error is GooglePreDispatchAuthorizationException)
        assertEquals(0, effects)
    }

    @Test fun labelsGrantDoesNotGrantMailReadOrganizationCompositionOrSending() {
        val manager = manager(Identity())
        seedScopes(GoogleOAuthProtocol.GMAIL_LABELS)
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_LABELS), manager.grantedScopes())
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_LABELS))
        for (capability in listOf(scope, GoogleOAuthProtocol.GMAIL_MODIFY, GoogleOAuthProtocol.GMAIL_FULL,
            GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleOAuthProtocol.GMAIL_SEND)) assertFalse(manager.isScopeGranted(capability))
    }

    @Test fun nativePinnedLabelsRequestsOnlyLabelsAndDoesNotRequestModifyOrFull() {
        val identity = Identity()
        var writes = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { method, url, _, _ ->
            assertEquals("POST", method); assertTrue(url.endsWith("/labels")); writes++; GoogleHttpResponse(200, "{}")
        }, identity)
        seedScopes(scope, GoogleOAuthProtocol.GMAIL_LABELS)
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("owner@example.test", manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_LABELS,
            "owner@example.test", CancellationToken.uncancellable(), epoch))
        manager.request(GoogleOAuthProtocol.GMAIL_LABELS, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/labels", "{}")
        assertEquals(1, writes)
        assertTrue(identity.requestedScopes.all { it == setOf(GoogleOAuthProtocol.GMAIL_LABELS) })
        assertEquals(setOf(scope, GoogleOAuthProtocol.GMAIL_LABELS), manager.grantedScopes())
    }

    @Test fun nativeUnknownLabelsAccountIsPinnedUsingExistingReadBeforeReacquiringLabels() {
        val identity = Identity().apply { email = null }
        var profileReads = 0
        var writes = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, _, _ ->
            if (url.endsWith("/profile")) { profileReads++; GoogleHttpResponse(200, """{"emailAddress":"labels-owner@example.test"}""") }
            else { writes++; GoogleHttpResponse(200, "{}") }
        }, identity)
        seedScopes(scope, GoogleOAuthProtocol.GMAIL_LABELS)
        app.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().remove("google_identity_account_email").commit()
        val epoch = manager.currentAuthorizationEpoch()
        assertEquals("labels-owner@example.test", manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_LABELS,
            null, CancellationToken.uncancellable(), epoch))
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_LABELS), setOf(scope), setOf(GoogleOAuthProtocol.GMAIL_LABELS)), identity.requestedScopes)
        assertEquals(listOf(null, null, "labels-owner@example.test"), identity.requestedAccounts)
        manager.request(GoogleOAuthProtocol.GMAIL_LABELS, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/labels", "{}")
        assertEquals(1, profileReads); assertEquals(1, writes)
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
    }

    @Test fun legacyLabelsOnlyCannotUseAnUnrelatedReadonlyTokenAsIdentityProof() {
        var calls = 0
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, _, _, _ -> calls++; GoogleHttpResponse(200, "{}") }, Identity())
        val owner = seedLegacy(manager, "gmail_labels", setOf(GoogleOAuthProtocol.GMAIL_LABELS))
        extraLegacy(owner, "gmail_read", setOf(scope), "fake-read-other-token")
        assertTrue(manager.isScopeGranted(scope))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_LABELS))
        assertEquals(setOf(scope, GoogleOAuthProtocol.GMAIL_LABELS), manager.grantedScopes())
        val failure = runCatching { manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_LABELS,
            "owner@example.test", CancellationToken.uncancellable(), manager.currentAuthorizationEpoch()) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(app.getString(R.string.full_google_legacy_labels_unverified), failure!!.message)
        assertEquals(0, calls)
    }

    @Test fun legacyLabelsUsesExistingModifyAlternativeAndShowsActualSelectedScope() {
        val seenTokens = mutableListOf<String?>()
        val identity = Identity()
        val manager = GoogleOAuthManager(app, GoogleHttpTransport { _, url, headers, _ ->
            seenTokens += headers["Authorization"]
            GoogleHttpResponse(200, if (url.endsWith("/profile")) """{"emailAddress":"owner@example.test"}""" else "{}")
        }, identity)
        val owner = seedLegacy(manager, "gmail_labels", setOf(GoogleOAuthProtocol.GMAIL_LABELS))
        extraLegacy(owner, "gmail_modify", setOf(GoogleOAuthProtocol.GMAIL_MODIFY), "fake-modify-access")
        assertEquals(GoogleOAuthProtocol.GMAIL_MODIFY, manager.effectiveGrantedScope(GoogleOAuthProtocol.GMAIL_LABELS))
        val epoch = manager.currentAuthorizationEpoch()
        manager.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_LABELS, "owner@example.test", CancellationToken.uncancellable(), epoch)
        manager.request(GoogleOAuthProtocol.GMAIL_LABELS, "POST", "https://gmail.googleapis.com/gmail/v1/users/me/labels", "{}")
        assertTrue(seenTokens.isNotEmpty()); assertTrue(seenTokens.all { it == "Bearer fake-modify-access" })
        assertEquals(0, identity.authorizeCalls)
        manager.disconnectLocal()
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_LABELS))
    }

    @Test fun deniedOptionalLabelsExpansionPreservesExistingReadGrant() {
        val identity = Identity().apply { grantedOverride = setOf(scope) }
        val manager = manager(identity)
        seedGrant()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: Result<Unit>? = null
        manager.authorize(activity, GoogleOAuthProtocol.GMAIL_LABELS) { result = it }
        waitUntil { result != null && !manager.isAuthorizationInProgress() }
        assertTrue(result!!.isFailure)
        assertEquals(setOf(scope), manager.grantedScopes())
        assertEquals(listOf(setOf(scope, GoogleOAuthProtocol.GMAIL_LABELS)), identity.requestedScopes)
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_LABELS))
    }
}

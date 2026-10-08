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
        var beforeGrant: () -> Unit = {}
        var revokedAccount: String? = null
        var revokedScopes: Set<String> = emptySet()
        var revokeAction: () -> Unit = {}
        override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
            authorizeCalls++
            requestedScopes += scopes
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
            expected += requested
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

}

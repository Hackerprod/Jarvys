package com.jarvys.agent.connectors

import android.accounts.Account
import android.content.Context
import com.jarvys.agent.CancellationToken
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

data class GoogleIdentityGrant(val accessToken: String, val grantedScopes: Set<String>, val accountEmail: String?) {
    override fun toString(): String = "GoogleIdentityGrant(scopes=${grantedScopes.size}, accountKnown=${accountEmail != null})"
}

enum class GoogleIdentityFailure { PLAY_SERVICES_UNAVAILABLE, USER_CANCELLED, SCOPE_NOT_GRANTED, ACCESS_BLOCKED, NETWORK, OTHER }

class GoogleIdentityAuthorizationException(
    val reason: GoogleIdentityFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class GoogleIdentitySessionState(val connected: Boolean, val scopes: Set<String>, val accountEmail: String?)
enum class GoogleIdentityDecision { TOKEN_READY, RESOLUTION_PENDING, USER_CANCELLED, SCOPE_DENIED, TOKEN_MISSING }
enum class GoogleIdentityConnectionState { DISCONNECTED, CONNECTED, REAUTHORIZE }

/** Pure feature-scope and persisted-state policy. Access tokens deliberately are not part of session state. */
object GoogleIdentityPolicy {
    fun scopesForFeature(connectorId: String): Set<String> = when (connectorId) {
        GmailConnector.ID -> setOf(GoogleOAuthProtocol.GMAIL_READ)
        DriveConnector.ID -> setOf(GoogleOAuthProtocol.DRIVE_READ)
        else -> emptySet()
    }

    fun playServicesFailure(available: Boolean): GoogleIdentityFailure? =
        if (available) null else GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE

    fun scopeForFeature(connectorId: String, feature: String): String? = when (connectorId to feature) {
        GmailConnector.ID to "read" -> GoogleOAuthProtocol.GMAIL_READ
        GmailConnector.ID to "compose" -> GoogleOAuthProtocol.GMAIL_COMPOSE
        GmailConnector.ID to "send" -> GoogleOAuthProtocol.GMAIL_SEND
        DriveConnector.ID to "read" -> GoogleOAuthProtocol.DRIVE_READ
        DriveConnector.ID to "file" -> GoogleOAuthProtocol.DRIVE_FILE
        else -> null
    }

    fun normalizeScopes(scopes: Collection<String>): Set<String> =
        scopes.filterTo(linkedSetOf()) { it in GoogleOAuthProtocol.ALLOWED_SCOPES }

    fun storedScopeState(requested: Set<String>, granted: Set<String>, previous: Set<String>): Set<String> =
        (previous + normalizeScopes(granted)).filterTo(linkedSetOf()) {
            it in GoogleOAuthProtocol.ALLOWED_SCOPES && (it in requested || it in previous)
        }

    fun session(scopes: Collection<String>, accountEmail: String?): GoogleIdentitySessionState {
        val granted = normalizeScopes(scopes)
        return GoogleIdentitySessionState(granted.isNotEmpty(), granted, accountEmail?.takeIf(String::isNotBlank))
    }

    fun connectionState(scopes: Collection<String>, reauthorize: Boolean = false): GoogleIdentityConnectionState = when {
        reauthorize -> GoogleIdentityConnectionState.REAUTHORIZE
        normalizeScopes(scopes).isNotEmpty() -> GoogleIdentityConnectionState.CONNECTED
        else -> GoogleIdentityConnectionState.DISCONNECTED
    }

    fun decision(hasResolution: Boolean, accessToken: String?, grantedScopes: Set<String>,
                 requestedScopes: Set<String>, cancelled: Boolean = false): GoogleIdentityDecision = when {
        cancelled -> GoogleIdentityDecision.USER_CANCELLED
        hasResolution -> GoogleIdentityDecision.RESOLUTION_PENDING
        accessToken.isNullOrBlank() -> GoogleIdentityDecision.TOKEN_MISSING
        !requestedScopes.all(grantedScopes::contains) -> GoogleIdentityDecision.SCOPE_DENIED
        else -> GoogleIdentityDecision.TOKEN_READY
    }

    fun shouldReauthorizeAfter401(attempt: Int): Boolean = attempt == 0

    /** ApiException codes belong to CommonStatusCodes, never ConnectionResult (16 means different things). */
    fun apiStatusFailure(statusCode: Int?): GoogleIdentityFailure = when (statusCode) {
        CommonStatusCodes.CANCELED -> GoogleIdentityFailure.USER_CANCELLED
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> GoogleIdentityFailure.NETWORK
        CommonStatusCodes.DEVELOPER_ERROR -> GoogleIdentityFailure.ACCESS_BLOCKED
        CommonStatusCodes.API_NOT_CONNECTED -> GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE
        else -> GoogleIdentityFailure.OTHER
    }
}

interface GoogleIdentityAuthorization {
    fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant
    fun authorizeCancellable(scopes: Set<String>, accountEmail: String?, token: CancellationToken,
                             allowResolution: Boolean): GoogleIdentityGrant {
        token.throwIfCancelled()
        return authorize(scopes, accountEmail).also { token.throwIfCancelled() }
    }
    fun clearToken(accessToken: String)
    fun clearTokenCancellable(accessToken: String, token: CancellationToken) {
        token.throwIfCancelled()
        clearToken(accessToken)
        token.throwIfCancelled()
    }
    fun revoke(scopes: Set<String>, accountEmail: String?)
}

/** Google Play services AuthorizationClient adapter. It returns access tokens only to the caller in memory. */
class GooglePlayServicesAuthorizationClient(
    context: Context,
    private val resolutionBroker: ActivityIntentSenderBroker = ActivityIntentSenderBroker.get(),
) : GoogleIdentityAuthorization {
    private val appContext = context.applicationContext

    override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant =
        authorizeCancellable(scopes, accountEmail, CancellationToken.uncancellable(), true)

    override fun authorizeCancellable(scopes: Set<String>, accountEmail: String?, token: CancellationToken,
                                      allowResolution: Boolean): GoogleIdentityGrant {
        token.throwIfCancelled()
        require(scopes.isNotEmpty() && scopes.all { it in GoogleOAuthProtocol.ALLOWED_SCOPES }) {
            "Only explicitly enabled Gmail and Drive scopes may be requested"
        }
        requirePlayServices()
        val client = Identity.getAuthorizationClient(appContext)
        val requestBuilder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
        accountEmail?.takeIf(String::isNotBlank)?.let { requestBuilder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        val first = await(token) { client.authorize(requestBuilder.build()) }
        val firstDecision = GoogleIdentityPolicy.decision(first.hasResolution(), first.accessToken,
            GoogleIdentityPolicy.normalizeScopes(first.grantedScopes), scopes)
        val result = if (firstDecision == GoogleIdentityDecision.RESOLUTION_PENDING) {
            if (!allowResolution) throw GoogleIdentityAuthorizationException(GoogleIdentityFailure.SCOPE_NOT_GRANTED,
                "Google needs authorization; reconnect this feature in Settings")
            val pending = first.pendingIntent ?: throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.OTHER, "Google returned a resolution without a PendingIntent")
            val responseIntent = try { resolutionBroker.launch(pending, AUTH_TIMEOUT_MS, token) }
                catch (error: Exception) { throw classify(error) }
            try { client.getAuthorizationResultFromIntent(responseIntent) }
            catch (error: Exception) { throw classify(error) }
        } else first
        token.throwIfCancelled()
        return toGrant(result, scopes)
    }

    override fun clearToken(accessToken: String) = clearTokenCancellable(accessToken, CancellationToken.uncancellable())
    override fun clearTokenCancellable(accessToken: String, token: CancellationToken) {
        token.throwIfCancelled()
        if (accessToken.isBlank()) return
        requirePlayServices()
        val request = ClearTokenRequest.builder().setToken(accessToken).build()
        await(token) { Identity.getAuthorizationClient(appContext).clearToken(request) }
    }

    override fun revoke(scopes: Set<String>, accountEmail: String?) {
        requirePlayServices()
        val builder = RevokeAccessRequest.builder()
            .setScopes(scopes.filter { it in GoogleOAuthProtocol.ALLOWED_SCOPES }.map(::Scope))
        accountEmail?.takeIf(String::isNotBlank)?.let { builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        await { Identity.getAuthorizationClient(appContext).revokeAccess(builder.build()) }
    }

    private fun toGrant(result: AuthorizationResult, requested: Set<String>): GoogleIdentityGrant {
        val token = result.accessToken.orEmpty()
        val granted = GoogleIdentityPolicy.normalizeScopes(result.grantedScopes)
        // Return partial grants to the manager, which replaces its snapshot and verifies the clicked feature.
        val returnedRequested = granted.intersect(requested)
        when (GoogleIdentityPolicy.decision(result.hasResolution(), token, granted, returnedRequested)) {
            GoogleIdentityDecision.RESOLUTION_PENDING -> throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.OTHER, "Google authorization still needs resolution")
            GoogleIdentityDecision.SCOPE_DENIED -> throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.SCOPE_NOT_GRANTED, "Google did not grant the enabled feature scope")
            GoogleIdentityDecision.TOKEN_MISSING -> throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.SCOPE_NOT_GRANTED, "Google did not return an access token for this feature")
            GoogleIdentityDecision.USER_CANCELLED -> throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.USER_CANCELLED, "Google authorization was cancelled")
            GoogleIdentityDecision.TOKEN_READY -> Unit
        }
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()?.takeIf(String::isNotBlank)
        return GoogleIdentityGrant(token, granted, email)
    }

    private fun requirePlayServices() {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        val failure = GoogleIdentityPolicy.playServicesFailure(status == ConnectionResult.SUCCESS)
        if (failure != null) throw GoogleIdentityAuthorizationException(
            failure,
            "Google Play services is unavailable (status $status)",
        )
    }

    private fun <T> await(token: CancellationToken = CancellationToken.uncancellable(), task: () -> com.google.android.gms.tasks.Task<T>): T {
        val pending = try { task() } catch (error: Exception) { throw classify(error) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AUTH_TIMEOUT_MS)
        while (true) {
            token.throwIfCancelled()
            try { return Tasks.await(pending, 200, TimeUnit.MILLISECONDS).also { token.throwIfCancelled() } }
            catch (error: java.util.concurrent.TimeoutException) {
                if (System.nanoTime() >= deadline) throw classify(error)
            } catch (error: Exception) { throw classify(error) }
        }
    }

    internal fun classify(error: Exception): GoogleIdentityAuthorizationException {
        if (error is GoogleIdentityAuthorizationException) return error
        val chain = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        val reason = when {
            chain.any { it is ActivityAuthorizationCancelledException || it is java.util.concurrent.CancellationException } -> GoogleIdentityFailure.USER_CANCELLED
            chain.any { it is java.util.concurrent.TimeoutException || it is InterruptedException } -> GoogleIdentityFailure.NETWORK
            else -> GoogleIdentityPolicy.apiStatusFailure(chain.filterIsInstance<com.google.android.gms.common.api.ApiException>().firstOrNull()?.statusCode)
        }
        // Do not retain a provider exception as a cause: its message may contain sensitive data.
        return GoogleIdentityAuthorizationException(reason, "Google authorization could not be completed")
    }

    companion object {
        private const val AUTH_TIMEOUT_MS = 180_000L
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

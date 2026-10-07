package com.jarvys.agent.connectors

import android.accounts.Account
import android.content.Context
import androidx.activity.result.IntentSenderRequest
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

data class GoogleIdentityGrant(val accessToken: String, val grantedScopes: Set<String>, val accountEmail: String?)

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
}

interface GoogleIdentityAuthorization {
    fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant
    fun clearToken(accessToken: String)
    fun revoke(scopes: Set<String>, accountEmail: String?)
}

/** Google Play services AuthorizationClient adapter. It returns access tokens only to the caller in memory. */
class GooglePlayServicesAuthorizationClient(
    context: Context,
    private val resolutionBroker: ActivityIntentSenderBroker = ActivityIntentSenderBroker.get(),
) : GoogleIdentityAuthorization {
    private val appContext = context.applicationContext

    override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
        require(scopes.isNotEmpty() && scopes.all { it in GoogleOAuthProtocol.ALLOWED_SCOPES }) {
            "Only explicitly enabled Gmail and Drive scopes may be requested"
        }
        requirePlayServices()
        val client = Identity.getAuthorizationClient(appContext)
        val requestBuilder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
        accountEmail?.takeIf(String::isNotBlank)?.let { requestBuilder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        val first = await { client.authorize(requestBuilder.build()) }
        val firstDecision = GoogleIdentityPolicy.decision(first.hasResolution(), first.accessToken,
            GoogleIdentityPolicy.normalizeScopes(first.grantedScopes), scopes)
        val result = if (firstDecision == GoogleIdentityDecision.RESOLUTION_PENDING) {
            val pending = first.pendingIntent ?: throw GoogleIdentityAuthorizationException(
                GoogleIdentityFailure.OTHER, "Google returned a resolution without a PendingIntent")
            val responseIntent = try { resolutionBroker.launch(pending, AUTH_TIMEOUT_MS) }
                catch (error: Exception) { throw classify(error) }
            try { client.getAuthorizationResultFromIntent(responseIntent) }
            catch (error: Exception) { throw classify(error) }
        } else first
        return toGrant(result, scopes)
    }

    override fun clearToken(accessToken: String) {
        if (accessToken.isBlank()) return
        requirePlayServices()
        val request = ClearTokenRequest.builder().setToken(accessToken).build()
        await { Identity.getAuthorizationClient(appContext).clearToken(request) }
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
        when (GoogleIdentityPolicy.decision(result.hasResolution(), token, granted, requested)) {
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

    private fun <T> await(task: () -> com.google.android.gms.tasks.Task<T>): T = try {
        Tasks.await(task(), AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (error: Exception) {
        throw classify(error)
    }

    private fun classify(error: Exception): GoogleIdentityAuthorizationException {
        if (error is GoogleIdentityAuthorizationException) return error
        if (error is ActivityAuthorizationCancelledException) return GoogleIdentityAuthorizationException(
            GoogleIdentityFailure.USER_CANCELLED, "Google authorization was cancelled", error)
        val apiError = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<com.google.android.gms.common.api.ApiException>().firstOrNull()
        val reason = when (apiError?.statusCode) {
            ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_DISABLED, ConnectionResult.API_UNAVAILABLE -> GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE
            com.google.android.gms.common.api.CommonStatusCodes.CANCELED -> GoogleIdentityFailure.USER_CANCELLED
            com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR -> GoogleIdentityFailure.NETWORK
            com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR -> GoogleIdentityFailure.ACCESS_BLOCKED
            else -> if (error is java.util.concurrent.TimeoutException) GoogleIdentityFailure.NETWORK else GoogleIdentityFailure.OTHER
        }
        return GoogleIdentityAuthorizationException(reason, "Google authorization could not be completed", error)
    }

    companion object {
        private const val AUTH_TIMEOUT_MS = 180_000L
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

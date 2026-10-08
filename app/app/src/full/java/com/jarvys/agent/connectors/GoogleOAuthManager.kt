package com.jarvys.agent.connectors

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Google access tokens from AuthorizationClient exist only in call-local memory. */
// Explicit editors preserve commit() acknowledgements at authorization/security boundaries; KTX edit hides that result.
@android.annotation.SuppressLint("UseKtx")
class GoogleOAuthManager internal constructor(
    context: Context,
    private val transport: GoogleHttpTransport = GoogleUrlConnectionTransport,
    identityClient: GoogleIdentityAuthorization? = null,
) : GoogleRestAuthorization {
    private val appContext = context.applicationContext
    private val secrets = SecretStore.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val identityAuthorization = identityClient ?: GooglePlayServicesAuthorizationClient(appContext)
    private val session = GoogleAuthorizationSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    private val _stateRevision = MutableStateFlow(0L)
    val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()
    @Volatile private var activeActivity: WeakReference<Activity>? = null
    @Volatile private var revokeState = runCatching {
        GoogleRevocationState.valueOf(preferences.getString(KEY_REVOCATION_STATE, "NOT_REQUESTED").orEmpty())
    }.getOrDefault(GoogleRevocationState.NOT_REQUESTED).let {
        // A process interruption cannot certify the remote result.
        if (it == GoogleRevocationState.PENDING) GoogleRevocationState.FAILED else it
    }

    init {
        (appContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (activeActivity?.get() === activity) cancelAuthorization()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun changed() = synchronized(_stateRevision) { _stateRevision.value = revision.incrementAndGet() }
    override fun currentAuthorizationEpoch(): Long = session.currentEpoch()
    fun isAuthorizationInProgress(): Boolean = session.hasInteractive()
    fun revocationState(): GoogleRevocationState = revokeState
    fun configuredClientId(): String = preferences.getString(CLIENT_ID, "").orEmpty()
    fun configuredAccountLabel(): String = preferences.getString(ACCOUNT_LABEL, "").orEmpty()
    fun isIdentityMode(): Boolean = preferences.getBoolean(KEY_IDENTITY_MODE, true)
    fun isConfigured(): Boolean = isIdentityMode() || configuredClientId().isNotBlank()
    fun isIdentityReauthorizationRequired(): Boolean = preferences.getBoolean(KEY_IDENTITY_REAUTHORIZE, false)
    fun hasClientSecret(): Boolean = currentOwnerId()?.let { secrets.getConnectorSecret(it, CLIENT_SECRET) }.orEmpty().isNotBlank()

    @Synchronized fun useIdentityMode() {
        require(revokeState != GoogleRevocationState.PENDING && pendingLegacyOwner() == null) {
            "Retry the pending Google revocation before connecting or changing accounts"
        }
        invalidateSession()
        check(preferences.edit().putBoolean(KEY_IDENTITY_MODE, true).commit()) { "Could not select Google Android authorization" }
        changed()
    }

    @Synchronized fun configure(clientId: String, clientSecret: String, accountLabel: String = configuredAccountLabel()) {
        require(revokeState != GoogleRevocationState.PENDING && pendingLegacyOwner() == null) {
            "Retry the pending Google revocation before connecting or changing accounts"
        }
        val id = clientId.trim()
        require(id.length in 8..512 && id.none(Char::isISOControl)) { "Enter a valid OAuth client ID from your own Google Cloud project" }
        val account = accountLabel.trim()
        require(account.length in 1..256 && account.none(Char::isISOControl)) { "Enter a label for the Google account that owns this grant" }
        invalidateSession()
        // Keep legacy encrypted owner grants during a mode/account switch. Never migrate them into plaintext.
        check(preferences.edit().putString(CLIENT_ID, id).putString(ACCOUNT_LABEL, account)
            .putBoolean(KEY_IDENTITY_MODE, false).putBoolean(KEY_LOCALLY_DISABLED, true).commit()) {
            "Could not save Google OAuth client settings"
        }
        secrets.saveConnectorSecret(ownerId(id, account), CLIENT_SECRET, clientSecret.trim())
        changed()
    }

    override fun isScopeGranted(scope: String): Boolean {
        validateScope(scope)
        // The encrypted pending marker itself is authoritative across a crash before the local preference write.
        if (hasPendingLegacyRevocation() || preferences.getBoolean(KEY_LOCALLY_DISABLED, false)) return false
        if (isIdentityMode()) return scope in identityGrantedScopes()
        val owner = currentOwnerId() ?: return false
        val encoded = scopeKey(scope)
        val storedScopes = secrets.getConnectorSecret(owner, "grant_${encoded}_scopes").orEmpty().split(' ')
        return scope in storedScopes && !secrets.getConnectorSecret(owner, "grant_${encoded}_refresh").isNullOrBlank()
    }
    fun grantedScopes(): Set<String> = GoogleOAuthProtocol.ALLOWED_SCOPES.filterTo(linkedSetOf(), ::isScopeGranted)

    fun authorize(activity: Activity, scope: String, onComplete: (Result<Unit>) -> Unit) {
        validateScope(scope)
        val lease = synchronized(this) {
            if (revokeState == GoogleRevocationState.PENDING || pendingLegacyOwner() != null || isAuthorizationInProgress()) {
                onComplete(Result.failure(GoogleIdentityAuthorizationException(GoogleIdentityFailure.OTHER,
                    "Finish or retry the pending Google connection operation before reconnecting")))
                return
            }
            session.acquire(interactive = true).also { activeActivity = WeakReference(activity); changed() }
        }
        OAUTH_EXECUTOR.execute {
            val result = runCatching {
                if (isIdentityMode()) authorizeIdentity(scope, lease, allowResolution = true)
                else authorizeBlocking(activity, scope, lease)
                session.withCurrent(lease) {
                    check(preferences.edit().putBoolean(KEY_LOCALLY_DISABLED, false)
                        .remove(KEY_REVOKE_SCOPES).remove(KEY_REVOKE_EMAIL).commit()) { "Could not enable Google connection" }
                    setRevocationState(GoogleRevocationState.NOT_REQUESTED)
                }
                Unit
            }
            mainHandler.post {
                // The check and UI callback share the generation lock with disconnect/account changes.
                try {
                    if (!activity.isDestroyed && !activity.isFinishing && session.isCurrent(lease)) {
                        session.withCurrent(lease) { onComplete(result) }
                    }
                } finally {
                    session.release(lease)
                    if (activeActivity?.get() === activity && !isAuthorizationInProgress()) activeActivity = null
                    changed()
                }
            }
        }
    }

    private fun authorizeIdentity(scope: String, lease: GoogleAuthorizationSession.Lease, allowResolution: Boolean): GoogleIdentityGrant {
        val previousEmail = identityAccountEmail()
        // Re-request the coherent set the user has already enabled, plus only the clicked feature.
        // AuthorizationResult often has no email. The actual returned grant, not inferred identity, is authoritative.
        val previousScopes = identityGrantedScopes()
        val requested = previousScopes + scope
        val grant = try { identityAuthorization.authorizeCancellable(requested, previousEmail, lease.token, allowResolution) }
        catch (error: GoogleIdentityAuthorizationException) {
            if (error.reason == GoogleIdentityFailure.SCOPE_NOT_GRANTED) session.withCurrent(lease) {
                check(preferences.edit().remove(KEY_IDENTITY_SCOPES).putBoolean(KEY_IDENTITY_REAUTHORIZE, true).commit()) {
                    "Could not clear Google permissions"
                }
                if (!allowResolution) session.invalidate()
                changed()
            }
            throw error
        }
        session.withCurrent(lease) {
            val email = grant.accountEmail?.takeIf(String::isNotBlank)
            if (!allowResolution && email != null && previousEmail != null && !email.equals(previousEmail, ignoreCase = true)) {
                session.invalidate()
                clearIdentityState()
                check(preferences.edit().putBoolean(KEY_LOCALLY_DISABLED, true).commit()) { "Could not disable the changed Google account" }
                changed()
                throw GoogleIdentityAuthorizationException(GoogleIdentityFailure.SCOPE_NOT_GRANTED,
                    "Google account changed; reconnect and review this operation with the selected account")
            }
            // Never merge permissions absent from this response, even for the same or an unknown account.
            val scopes = GoogleIdentityPolicy.normalizeScopes(grant.grantedScopes).intersect(requested)
            check(preferences.edit().putString(KEY_IDENTITY_SCOPES, scopes.joinToString(" "))
                .putString(KEY_IDENTITY_EMAIL, email.orEmpty()).putBoolean(KEY_IDENTITY_REAUTHORIZE, false).commit()) {
                "Could not save Google connection status"
            }
            changed()
            if (!allowResolution && scopes != previousScopes) {
                session.invalidate()
                throw GoogleIdentityAuthorizationException(GoogleIdentityFailure.SCOPE_NOT_GRANTED,
                    "Google permissions changed; prepare and approve this operation again")
            }
        }
        if (scope !in grant.grantedScopes) throw GoogleIdentityAuthorizationException(GoogleIdentityFailure.SCOPE_NOT_GRANTED,
            "Google did not grant the enabled feature permission")
        return grant
    }

    private fun identityGrantedScopes(): Set<String> = GoogleIdentityPolicy.normalizeScopes(
        preferences.getString(KEY_IDENTITY_SCOPES, "").orEmpty().split(' ').filter(String::isNotBlank))
    fun identityAccountEmail(): String? = preferences.getString(KEY_IDENTITY_EMAIL, null)?.takeIf(String::isNotBlank)

    private fun clearIdentityState() {
        check(preferences.edit().remove(KEY_IDENTITY_SCOPES).remove(KEY_IDENTITY_EMAIL)
            .putBoolean(KEY_IDENTITY_REAUTHORIZE, false).commit()) { "Could not clear Google connection status" }
    }
    private fun invalidateSession() {
        session.invalidate()
        ActivityIntentSenderBroker.get().invalidate()
        activeActivity = null
    }
    @Synchronized fun cancelAuthorization() { invalidateSession(); changed() }

    /** Local disconnect is immediate; it makes no claim about Google's remote project-wide grant. */
    @Synchronized fun disconnectLocal() {
        invalidateSession()
        val scopes = identityGrantedScopes()
        if (scopes.isNotEmpty()) {
            check(preferences.edit().putString(KEY_REVOKE_SCOPES, scopes.joinToString(" "))
                .putString(KEY_REVOKE_EMAIL, identityAccountEmail().orEmpty()).commit()) { "Could not retain Google revocation status" }
        }
        clearIdentityState()
        check(preferences.edit().putBoolean(KEY_LOCALLY_DISABLED, true).commit()) { "Could not disconnect Google locally" }
        changed()
    }

    private fun authorizeBlocking(activity: Activity, scope: String, lease: GoogleAuthorizationSession.Lease) {
        val owner = currentOwnerId() ?: error("Configure your own Google OAuth client first")
        val clientId = configuredClientId()
        val secret = secrets.getConnectorSecret(owner, CLIENT_SECRET).orEmpty()
        LoopbackOAuthCallbackServer.random(CALLBACK_PATH).use { callback ->
            val unregister = lease.token.registerCancelAction { callback.close() }
            try {
                val verifier = GoogleOAuthProtocol.randomVerifier()
                val state = GoogleOAuthProtocol.randomState()
                val uri = Uri.parse(GoogleOAuthProtocol.authorizationUrl(clientId, callback.redirectUri, scope,
                    state, GoogleOAuthProtocol.pkceS256(verifier)))
                activity.runOnUiThread {
                    if (session.isCurrent(lease) && !activity.isDestroyed && !activity.isFinishing) {
                        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri) }
                            .onFailure { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                }
                val code = try { callback.awaitCode(state, OAUTH_TIMEOUT_MS) } catch (error: IllegalStateException) {
                    lease.token.throwIfCancelled()
                    val providerError = Regex("OAuth authorization failed: ([A-Za-z0-9_-]+)").find(error.message.orEmpty())?.groupValues?.get(1)
                    if (providerError != null) throw GoogleOAuthException(providerError)
                    throw GoogleOAuthException("callback_timeout")
                }
                lease.token.throwIfCancelled()
                val response = tokenRequest(GoogleOAuthProtocol.authorizationCodeForm(clientId, secret, code, verifier, callback.redirectUri), lease.token)
                val access = response.optString("access_token")
                val refresh = response.optString("refresh_token")
                require(access.isNotBlank() && refresh.isNotBlank()) { "Google OAuth did not return access and refresh tokens" }
                val granted = response.optString("scope").split(' ').filter(String::isNotBlank).toSet().ifEmpty { setOf(scope) }
                require(scope in granted) { "Google did not grant the requested feature scope" }
                session.withCurrent(lease) { saveGrant(owner, scope, access, refresh, expiry(response), granted) }
            } finally { unregister.run() }
        }
    }

    override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse =
        requestCancellable(scope, method, url, body, contentType, CancellationToken.uncancellable())

    override fun requestCancellable(scope: String, method: String, url: String, body: String?, contentType: String,
                                    token: CancellationToken, requestHeaders: Map<String, String>,
                                    expectedAuthorizationEpoch: Long?): GoogleHttpResponse {
        body?.let { GoogleHttpPolicy.requireSize(it.toByteArray(Charsets.UTF_8).size, GoogleApiLimits.MAX_TRANSFER_BYTES) }
        val response = requestBytes(scope, method, url, body?.toByteArray(Charsets.UTF_8), contentType, token,
            GoogleApiLimits.MAX_RESPONSE_BYTES, requestHeaders, expectedAuthorizationEpoch)
        return GoogleHttpResponse(response.status, response.body.toString(Charsets.UTF_8), response.headers)
    }

    override fun requestBytes(scope: String, method: String, url: String, body: ByteArray?, contentType: String,
                              token: CancellationToken, maxResponseBytes: Int, requestHeaders: Map<String, String>,
                              expectedAuthorizationEpoch: Long?): GoogleBinaryResponse {
        validateScope(scope)
        GoogleHttpPolicy.validateEndpoint(method, url, scope)
        token.throwIfCancelled()
        val lease = synchronized(this) {
            check(revokeState != GoogleRevocationState.PENDING && !isAuthorizationInProgress() && isScopeGranted(scope)) {
                "Enable this Google feature and authorize its permission in Settings first"
            }
            session.acquire(expectedEpoch = expectedAuthorizationEpoch)
        }
        val unlink = token.registerCancelAction { lease.token.cancel() }
        try {
            var access: String
            val native = isIdentityMode()
            val owner = if (native) null else currentOwnerId() ?: error("Configure your Google OAuth client")
            val key = scopeKey(scope)
            access = if (native) authorizeIdentity(scope, lease, allowResolution = false).accessToken
                else validAccess(requireNotNull(owner), key, scope, lease)
            return GoogleRequestExecutor(transport).execute(scope, method, url, body, contentType, lease.token,
                maxResponseBytes, requestHeaders, accessToken = { session.withCurrent(lease) { access } },
                refresh = {
                    lease.token.throwIfCancelled()
                    if (native) {
                        identityAuthorization.clearTokenCancellable(access, lease.token)
                        access = authorizeIdentity(scope, lease, allowResolution = false).accessToken
                    } else access = refresh(requireNotNull(owner), key, scope, lease)
                }, repeatedUnauthorized = {
                    session.withCurrent(lease) {
                        if (native) removeIdentityScope(scope) else clearGrant(requireNotNull(owner), key)
                        session.invalidate()
                        changed()
                    }
                }).also { session.withCurrent(lease) { Unit } }
        } finally { unlink.run(); session.release(lease) }
    }

    private fun removeIdentityScope(scope: String) {
        check(preferences.edit().putString(KEY_IDENTITY_SCOPES, (identityGrantedScopes() - scope).joinToString(" "))
            .putBoolean(KEY_IDENTITY_REAUTHORIZE, true).commit()) { "Could not update Google connection status" }
    }

    /** Revocation succeeds only after Google's asynchronous task/HTTP acknowledgement. No main-thread networking. */
    // Revocation cleanup must finish before another connection is admitted; synchronous commits are intentional.
    @android.annotation.SuppressLint("ApplySharedPref")
    @Synchronized fun revokeAndClear(onComplete: ((Result<Unit>) -> Unit)? = null) {
        if (revokeState == GoogleRevocationState.PENDING) return
        val pendingOwner = pendingLegacyOwner()
        val native = pendingOwner == null && isIdentityMode()
        // Account and scopes are an inseparable tuple. An unknown active account must not inherit an old email.
        val activeScopes = identityGrantedScopes()
        val scopes = activeScopes.ifEmpty {
            GoogleIdentityPolicy.normalizeScopes(preferences.getString(KEY_REVOKE_SCOPES, "").orEmpty().split(' '))
        }
        val email = if (activeScopes.isNotEmpty()) identityAccountEmail()
            else preferences.getString(KEY_REVOKE_EMAIL, null)?.takeIf(String::isNotBlank)
        val owner = pendingOwner ?: currentOwnerId()
        val refreshTokens = if (native || owner == null) emptyList() else GoogleOAuthProtocol.ALLOWED_SCOPES.mapNotNull {
            secrets.getConnectorSecret(owner, "grant_${scopeKey(it)}_refresh")?.takeIf(String::isNotBlank)
        }.distinct()
        if (!native && owner != null && refreshTokens.isNotEmpty()) {
            // Persist only the encrypted owner reference. Tokens remain in their original encrypted owner store.
            // This survives a failed request/process restart without making the disconnected grant usable locally.
            secrets.saveConnectorSecret(PENDING_REVOCATION_STORE, PENDING_REVOCATION_OWNER, owner)
        }
        disconnectLocal()
        setRevocationState(GoogleRevocationState.PENDING)
        OAUTH_EXECUTOR.execute {
            val result = runCatching {
                if (native) identityAuthorization.revoke(scopes.ifEmpty { GoogleOAuthProtocol.ALLOWED_SCOPES }, email)
                else {
                    check(refreshTokens.isNotEmpty()) { "No saved Google grant was available to verify remote revocation" }
                    // Google's revocation is project-wide: a first success invalidates sibling tokens.
                    var acknowledged = false
                    for (token in refreshTokens) {
                        if (runCatching { GoogleRestEndpoints.requireSuccess(GoogleOAuthProtocol.revokeToken(transport, token)) }.isSuccess) {
                            acknowledged = true
                            break
                        }
                    }
                    check(acknowledged) { "Google revocation was not acknowledged" }
                }
                Unit
            }.fold(onSuccess = { Result.success(Unit) }, onFailure = {
                Result.failure(IllegalStateException("Disconnected locally; remote Google revocation could not be verified"))
            })
            synchronized(this) {
                if (result.isSuccess) {
                    if (!native && owner != null) {
                        secrets.clearConnectorSecrets(owner)
                        secrets.clearConnectorSecrets(PENDING_REVOCATION_STORE)
                        if (currentOwnerId() == owner) preferences.edit().remove(CLIENT_ID).remove(ACCOUNT_LABEL).commit()
                    }
                    preferences.edit().remove(KEY_REVOKE_SCOPES).remove(KEY_REVOKE_EMAIL).apply()
                }
                setRevocationState(if (result.isSuccess) GoogleRevocationState.VERIFIED else GoogleRevocationState.FAILED)
            }
            mainHandler.post { onComplete?.invoke(result) }
        }
    }
    fun hasPendingLegacyRevocation(): Boolean = pendingLegacyOwner() != null

    /** Explicit user-confirmed recovery only; this does not claim or perform remote revocation. */
    @Synchronized fun forgetPendingRevocationLocally() {
        check(revokeState != GoogleRevocationState.PENDING) { "Wait for the in-flight Google revocation request to finish" }
        val owner = pendingLegacyOwner() ?: return
        invalidateSession()
        secrets.clearConnectorSecrets(owner)
        secrets.clearConnectorSecrets(PENDING_REVOCATION_STORE)
        val edit = preferences.edit().putBoolean(KEY_LOCALLY_DISABLED, true)
        if (currentOwnerId() == owner) edit.remove(CLIENT_ID).remove(ACCOUNT_LABEL)
        check(edit.commit()) { "Could not clear the local pending Google connection" }
        setRevocationState(GoogleRevocationState.NOT_REQUESTED)
    }

    private fun pendingLegacyOwner(): String? = secrets.getConnectorSecret(PENDING_REVOCATION_STORE,
        PENDING_REVOCATION_OWNER)?.takeIf(String::isNotBlank)

    private fun setRevocationState(state: GoogleRevocationState) {
        revokeState = state
        preferences.edit().putString(KEY_REVOCATION_STATE, state.name).apply()
        changed()
    }
    fun disableScope(scope: String) { validateScope(scope); disconnectLocal() }

    private fun validAccess(owner: String, grant: String, scope: String, lease: GoogleAuthorizationSession.Lease): String {
        lease.token.throwIfCancelled()
        val until = secrets.getConnectorSecret(owner, "grant_${grant}_expiry")?.toLongOrNull() ?: 0L
        val access = secrets.getConnectorSecret(owner, "grant_${grant}_access").orEmpty()
        if (access.isNotBlank() && until > System.currentTimeMillis() + REFRESH_LEEWAY_MS) return access
        return refresh(owner, grant, scope, lease)
    }
    private fun refresh(owner: String, grant: String, scope: String, lease: GoogleAuthorizationSession.Lease): String {
        val refresh = secrets.getConnectorSecret(owner, "grant_${grant}_refresh")
            ?: error("Google refresh credential is missing; authorize this feature again")
        val response = try { tokenRequest(GoogleOAuthProtocol.refreshForm(configuredClientId(),
            secrets.getConnectorSecret(owner, CLIENT_SECRET).orEmpty(), refresh), lease.token) }
        catch (error: GoogleOAuthException) {
            if (error.providerError in setOf("invalid_grant", "invalid_client")) session.withCurrent(lease) { clearGrant(owner, grant); session.invalidate(); changed() }
            throw error
        }
        val access = response.optString("access_token")
        check(access.isNotBlank()) { "Google OAuth refresh returned no access token" }
        val rotated = response.optString("refresh_token").takeIf(String::isNotBlank) ?: refresh
        val scopes = response.optString("scope").split(' ').filter(String::isNotBlank).toSet().ifEmpty { setOf(scope) }
        session.withCurrent(lease) { saveGrant(owner, scope, access, rotated, expiry(response), scopes) }
        return access
    }
    private fun tokenRequest(fields: Map<String, String>, token: CancellationToken): JSONObject =
        GoogleOAuthProtocol.exchangeToken(transport, fields, token)
    private fun saveGrant(owner: String, scope: String, access: String, refresh: String, expiresAt: Long, scopes: Set<String>) {
        val key = scopeKey(scope)
        secrets.saveConnectorSecret(owner, "grant_${key}_access", access)
        secrets.saveConnectorSecret(owner, "grant_${key}_refresh", refresh)
        secrets.saveConnectorSecret(owner, "grant_${key}_expiry", expiresAt.toString())
        secrets.saveConnectorSecret(owner, "grant_${key}_scopes", scopes.joinToString(" "))
    }
    private fun clearGrant(owner: String, grant: String) {
        listOf("access", "refresh", "expiry", "scopes").forEach { secrets.saveConnectorSecret(owner, "grant_${grant}_$it", "") }
    }
    private fun expiry(response: JSONObject): Long {
        val seconds = response.optLong("expires_in", 0).coerceIn(0, 31_536_000)
        return if (seconds == 0L) 0 else System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
    }
    private fun currentOwnerId(): String? {
        val clientId = configuredClientId().takeIf(String::isNotBlank) ?: return null
        val account = configuredAccountLabel().takeIf(String::isNotBlank) ?: return null
        return ownerId(clientId, account)
    }
    private fun ownerId(clientId: String, account: String): String = "full_" + GoogleOAuthProtocol.hexSha256("$clientId|$account").take(48)
    private fun scopeKey(scope: String): String = when (scope) {
        GoogleOAuthProtocol.GMAIL_READ -> "gmail_read"
        GoogleOAuthProtocol.GMAIL_COMPOSE -> "gmail_compose"
        GoogleOAuthProtocol.GMAIL_SEND -> "gmail_send"
        GoogleOAuthProtocol.DRIVE_READ -> "drive_read"
        GoogleOAuthProtocol.DRIVE_FILE -> "drive_file"
        else -> error("Unsupported Google feature scope")
    }
    private fun validateScope(scope: String) { require(scope in GoogleOAuthProtocol.ALLOWED_SCOPES) { "Unsupported Google scope" } }
    companion object {
        private const val PREFERENCES = "jarvys_full_oauth_config"
        private const val CLIENT_ID = "installed_client_id"
        private const val ACCOUNT_LABEL = "installed_account_label"
        private const val CLIENT_SECRET = "installed_client_secret"
        private const val KEY_IDENTITY_MODE = "google_identity_authorization_enabled"
        private const val KEY_IDENTITY_SCOPES = "google_identity_granted_scopes"
        private const val KEY_IDENTITY_EMAIL = "google_identity_account_email"
        private const val KEY_IDENTITY_REAUTHORIZE = "google_identity_reauthorize_required"
        private const val KEY_LOCALLY_DISABLED = "google_locally_disabled"
        private const val KEY_REVOCATION_STATE = "google_revocation_state"
        private const val KEY_REVOKE_SCOPES = "google_disconnected_revocation_scopes"
        private const val KEY_REVOKE_EMAIL = "google_disconnected_revocation_email"
        private const val PENDING_REVOCATION_STORE = "full_pending_revocation"
        private const val PENDING_REVOCATION_OWNER = "pending_owner"
        private const val CALLBACK_PATH = "/oauth2/callback"
        private const val OAUTH_TIMEOUT_MS = 180_000
        private const val REFRESH_LEEWAY_MS = 60_000L
        private val OAUTH_EXECUTOR = Executors.newCachedThreadPool { Thread(it, "JarvysFullOAuth").apply { isDaemon = true } }
        @Volatile private var instance: GoogleOAuthManager? = null
        fun get(context: Context): GoogleOAuthManager = instance ?: synchronized(this) {
            instance ?: GoogleOAuthManager(context).also { instance = it }
        }
    }
}

class GoogleOAuthException(providerError: String) : IllegalStateException("Google OAuth authorization failed") {
    val providerError: String = providerError.takeIf { it in setOf("redirect_uri_mismatch", "access_blocked", "access_denied",
        "callback_timeout", "invalid_grant", "invalid_client", "invalid_request", "invalid_scope", "unauthorized_client") } ?: "other"
}

object GoogleOAuthProtocol {
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
    const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    const val DRIVE_READ = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    val ALLOWED_SCOPES = setOf(GMAIL_READ, GMAIL_COMPOSE, GMAIL_SEND, DRIVE_READ, DRIVE_FILE)
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun authorizationUrl(clientId: String, redirectUri: String, scope: String, state: String, challenge: String): String {
        require(scope in ALLOWED_SCOPES && state.isNotBlank() && challenge.isNotBlank())
        val values = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scope,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
        )
        return AUTH_ENDPOINT + "?" + values.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }
    }

    fun mapError(providerError: String?): GoogleOAuthError = when (providerError) {
        "redirect_uri_mismatch" -> GoogleOAuthError.REDIRECT_MISMATCH
        "access_blocked", "access_denied" -> GoogleOAuthError.ACCESS_BLOCKED
        "callback_timeout" -> GoogleOAuthError.CALLBACK_TIMEOUT
        else -> GoogleOAuthError.OTHER
    }

    fun authorizationCodeForm(clientId: String, clientSecret: String, code: String,
                              verifier: String, redirectUri: String): Map<String, String> = buildMap {
        put("client_id", clientId)
        if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
        put("code", code)
        put("code_verifier", verifier)
        put("redirect_uri", redirectUri)
        put("grant_type", "authorization_code")
    }

    fun refreshForm(clientId: String, clientSecret: String, refreshToken: String): Map<String, String> = buildMap {
        put("client_id", clientId)
        if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
        put("refresh_token", refreshToken)
        put("grant_type", "refresh_token")
    }

    fun revocationForm(token: String) = "token=${formEncode(token)}"

    internal fun exchangeToken(transport: GoogleHttpTransport, form: Map<String, String>,
                               token: CancellationToken = CancellationToken.uncancellable()): JSONObject {
        val body = form.entries.joinToString("&") { (key, value) -> "${formEncode(key)}=${formEncode(value)}" }
        val response = transport.executeBytes("POST", TOKEN_ENDPOINT,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            body.toByteArray(Charsets.UTF_8), token, GoogleApiLimits.MAX_RESPONSE_BYTES)
        val json = runCatching { JSONObject(response.body.toString(Charsets.UTF_8)) }.getOrElse {
            throw GoogleOAuthException("invalid_response")
        }
        if (response.status !in 200..299) throw GoogleOAuthException(json.optString("error"))
        return json
    }

    internal fun revokeToken(transport: GoogleHttpTransport, token: String): GoogleHttpResponse = transport.execute(
        "POST", REVOKE_ENDPOINT, mapOf("Content-Type" to "application/x-www-form-urlencoded"), revocationForm(token),
    )

    fun retryOnceOn401(
        request: () -> GoogleHttpResponse,
        refresh: () -> Unit,
        onRepeatedUnauthorized: () -> Unit = {},
    ): GoogleHttpResponse {
        val first = request()
        if (first.status != 401) return first
        refresh()
        val retried = request()
        if (retried.status == 401) {
            onRepeatedUnauthorized()
            error("Google authorization expired; reconnect this feature")
        }
        return retried
    }

    fun pkceS256(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    fun randomVerifier(): String = randomUrlSafe(32)
    fun randomState(): String = randomUrlSafe(24)
    private fun randomUrlSafe(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes).let(::base64Url)

    fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    fun hexSha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun base64Url(bytes: ByteArray): String {
        val output = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val a = bytes[index++].toInt() and 0xff
            val hasB = index < bytes.size
            val b = if (hasB) bytes[index++].toInt() and 0xff else 0
            val hasC = index < bytes.size
            val c = if (hasC) bytes[index++].toInt() and 0xff else 0
            output.append(URL_SAFE[a ushr 2])
            output.append(URL_SAFE[((a and 0x03) shl 4) or (b ushr 4)])
            if (hasB) output.append(URL_SAFE[((b and 0x0f) shl 2) or (c ushr 6)])
            if (hasC) output.append(URL_SAFE[c and 0x3f])
        }
        return output.toString()
    }

    fun base64UrlDecode(value: String): ByteArray {
        val cleaned = value.trimEnd('=')
        val output = ByteArrayOutputStream()
        var accumulator = 0
        var bits = 0
        for (character in cleaned) {
            val digit = URL_SAFE.indexOf(character)
            require(digit >= 0) { "Invalid base64url data" }
            accumulator = (accumulator shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((accumulator ushr bits) and 0xff)
            }
        }
        return output.toByteArray()
    }
}

enum class GoogleOAuthError { REDIRECT_MISMATCH, ACCESS_BLOCKED, CALLBACK_TIMEOUT, OTHER }

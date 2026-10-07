package com.jarvys.agent.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.Activity
import androidx.browser.customtabs.CustomTabsIntent
import com.jarvys.agent.SecretStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class GoogleHttpResponse(val status: Int, val body: String)

internal fun interface GoogleHttpTransport {
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse
}

/** Narrow REST seam enables Full-flavor JVM tests to use a fake API transport. */
interface GoogleRestAuthorization {
    fun isScopeGranted(scope: String): Boolean
    fun request(scope: String, method: String, url: String, body: String? = null,
                contentType: String = "application/json"): GoogleHttpResponse
}

/** Full-flavor OAuth facade: Google Identity AuthorizationClient by default, legacy BYO installed/Desktop OAuth in Advanced. */
class GoogleOAuthManager internal constructor(
    context: Context,
    private val transport: GoogleHttpTransport = GoogleUrlConnectionTransport,
) : GoogleRestAuthorization {
    private val appContext = context.applicationContext
    private val secrets = SecretStore.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val identityAuthorization: GoogleIdentityAuthorization = GooglePlayServicesAuthorizationClient(appContext)

    fun configuredClientId(): String = preferences.getString(CLIENT_ID, "").orEmpty()
    fun configuredAccountLabel(): String = preferences.getString(ACCOUNT_LABEL, "").orEmpty()
    fun isIdentityMode(): Boolean = preferences.getBoolean(KEY_IDENTITY_MODE, true)
    fun isConfigured(): Boolean = isIdentityMode() || configuredClientId().isNotBlank()
    fun isIdentityReauthorizationRequired(): Boolean = preferences.getBoolean(KEY_IDENTITY_REAUTHORIZE, false)
    fun hasClientSecret(): Boolean = currentOwnerId()?.let { secrets.getConnectorSecret(it, CLIENT_SECRET) }.orEmpty().isNotBlank()

    fun useIdentityMode() {
        check(preferences.edit().putBoolean(KEY_IDENTITY_MODE, true).commit()) { "Could not select Google Android authorization" }
    }

    @Synchronized
    fun configure(clientId: String, clientSecret: String, accountLabel: String = configuredAccountLabel()) {
        val id = clientId.trim()
        require(id.length in 8..512 && id.none(Char::isISOControl)) { "Enter a valid OAuth client ID from your own Google Cloud project" }
        val account = accountLabel.trim()
        require(account.length in 1..256 && account.none(Char::isISOControl)) { "Enter a label for the Google account that owns this grant" }
        val oldOwner = currentOwnerId()
        val newOwner = ownerId(id, account)
        if (oldOwner != null && oldOwner != newOwner) secrets.clearConnectorSecrets(oldOwner)
        check(preferences.edit().putString(CLIENT_ID, id).putString(ACCOUNT_LABEL, account).commit()) {
            "Could not save Google OAuth client settings"
        }
        check(preferences.edit().putBoolean(KEY_IDENTITY_MODE, false).commit()) { "Could not select the BYO Google OAuth client" }
        secrets.saveConnectorSecret(newOwner, CLIENT_SECRET, clientSecret.trim())
    }

    override fun isScopeGranted(scope: String): Boolean {
        validateScope(scope)
        if (isIdentityMode()) return scope in identityGrantedScopes()
        val owner = currentOwnerId() ?: return false
        val encoded = scopeKey(scope)
        val storedScopes = secrets.getConnectorSecret(owner, "grant_${encoded}_scopes").orEmpty().split(' ')
        return scope in storedScopes && !secrets.getConnectorSecret(owner, "grant_${encoded}_refresh").isNullOrBlank()
    }

    fun grantedScopes(): Set<String> = GoogleOAuthProtocol.ALLOWED_SCOPES.filterTo(linkedSetOf(), ::isScopeGranted)

    fun authorize(activity: Activity, scope: String, onComplete: (Result<Unit>) -> Unit) {
        validateScope(scope)
        OAUTH_EXECUTOR.execute {
            val result = runCatching {
                if (isIdentityMode()) { authorizeIdentity(scope); Unit } else authorizeBlocking(activity, scope)
            }
            activity.runOnUiThread { onComplete(result) }
        }
    }

    private fun authorizeIdentity(scope: String): GoogleIdentityGrant {
        val grant = identityAuthorization.authorize(setOf(scope), identityAccountEmail())
        require(scope in grant.grantedScopes) { "Google did not grant the enabled feature scope" }
        val scopes = GoogleIdentityPolicy.storedScopeState(setOf(scope), grant.grantedScopes, identityGrantedScopes())
        val email = grant.accountEmail?.takeIf(String::isNotBlank) ?: identityAccountEmail()
        check(preferences.edit().putString(KEY_IDENTITY_SCOPES, scopes.joinToString(" "))
            .putString(KEY_IDENTITY_EMAIL, email.orEmpty())
            .putBoolean(KEY_IDENTITY_REAUTHORIZE, false).commit()) {
            "Could not save Google connection status"
        }
        return grant
    }

    private fun identityGrantedScopes(): Set<String> = GoogleIdentityPolicy.normalizeScopes(
        preferences.getString(KEY_IDENTITY_SCOPES, "").orEmpty().split(' ').filter(String::isNotBlank))

    fun identityAccountEmail(): String? = preferences.getString(KEY_IDENTITY_EMAIL, null)?.takeIf(String::isNotBlank)

    private fun clearIdentityState() {
        check(preferences.edit().remove(KEY_IDENTITY_SCOPES).remove(KEY_IDENTITY_EMAIL)
            .putBoolean(KEY_IDENTITY_REAUTHORIZE, false).commit()) {
            "Could not clear Google connection status"
        }
    }

    private fun revokeIdentityState() {
        val scopes = identityGrantedScopes()
        val email = identityAccountEmail()
        clearIdentityState()
        OAUTH_EXECUTOR.execute { runCatching { identityAuthorization.revoke(scopes.ifEmpty { GoogleOAuthProtocol.ALLOWED_SCOPES }, email) } }
    }

    private fun authorizeBlocking(activity: Activity, scope: String) {
        val owner = currentOwnerId() ?: error("Configure your own Google OAuth client first")
        val clientId = configuredClientId()
        val secret = secrets.getConnectorSecret(owner, CLIENT_SECRET).orEmpty()
        LoopbackOAuthCallbackServer.random(CALLBACK_PATH).use { callback ->
            val verifier = GoogleOAuthProtocol.randomVerifier()
            val state = GoogleOAuthProtocol.randomState()
            val authorizationUrl = GoogleOAuthProtocol.authorizationUrl(
                clientId, callback.redirectUri, scope, state, GoogleOAuthProtocol.pkceS256(verifier),
            )
            val uri = Uri.parse(authorizationUrl)
            activity.runOnUiThread {
                runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri) }
                    .onFailure { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            val code = try {
                callback.awaitCode(state, OAUTH_TIMEOUT_MS)
            } catch (error: IllegalStateException) {
                val providerError = Regex("OAuth authorization failed: ([A-Za-z0-9_-]+)")
                    .find(error.message.orEmpty())?.groupValues?.get(1)
                if (providerError != null) throw GoogleOAuthException(providerError)
                throw error
            }
            val form = GoogleOAuthProtocol.authorizationCodeForm(clientId, secret, code, verifier, callback.redirectUri)
            val response = tokenRequest(form)
            val access = response.optString("access_token")
            val refresh = response.optString("refresh_token")
            require(access.isNotBlank() && refresh.isNotBlank()) { "Google OAuth did not return access and refresh tokens" }
            val granted = response.optString("scope").split(' ').filter(String::isNotBlank).toSet()
                .ifEmpty { setOf(scope) }
            require(scope in granted) { "Google did not grant the requested feature scope" }
            saveGrant(owner, scope, access, refresh, expiry(response), granted)
        }
    }

    /** A REST call refreshes once on 401 only. 403 is surfaced as a scope/permission failure. */
    override fun request(scope: String, method: String, url: String, body: String?,
                         contentType: String): GoogleHttpResponse {
        validateScope(scope)
        val approvedHost = if (scope.startsWith("https://www.googleapis.com/auth/gmail.")) "gmail.googleapis.com" else "www.googleapis.com"
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        require(parsed?.scheme == "https" && parsed.host == approvedHost && parsed.userInfo == null && parsed.fragment == null) {
            "Google API endpoint did not match the requested service"
        }
        if (isIdentityMode()) return requestWithIdentityToken(scope, method, url, body, contentType)
        val owner = currentOwnerId() ?: error("Configure your Google OAuth client")
        val grant = scopeKey(scope)
        if (!isScopeGranted(scope)) error("Enable this feature and authorize its $scope permission first")
        var access = validAccess(owner, grant, scope)
        return GoogleOAuthProtocol.retryOnceOn401(
            request = { apiRequest(method, url, access, body, contentType) },
            refresh = { access = refresh(owner, grant, scope) },
            onRepeatedUnauthorized = { clearGrant(owner, grant) },
        )
    }

    private fun requestWithIdentityToken(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse {
        var accessToken = authorizeIdentity(scope).accessToken
        return GoogleOAuthProtocol.retryOnceOn401(
            request = { apiRequest(method, url, accessToken, body, contentType) },
            refresh = {
                runCatching { identityAuthorization.clearToken(accessToken) }
                accessToken = authorizeIdentity(scope).accessToken
            },
            onRepeatedUnauthorized = {
                removeIdentityScope(scope)
                ConnectorRegistry.get(appContext).refreshStates()
            },
        )
    }

    private fun removeIdentityScope(scope: String) {
        val scopes = identityGrantedScopes() - scope
        check(preferences.edit().putString(KEY_IDENTITY_SCOPES, scopes.joinToString(" "))
            .putBoolean(KEY_IDENTITY_REAUTHORIZE, true).commit()) {
            "Could not update Google connection status"
        }
    }

    fun revokeAndClear() {
        if (isIdentityMode()) {
            revokeIdentityState()
            return
        }
        revokeScopes(GoogleOAuthProtocol.ALLOWED_SCOPES, clearConfiguration = true)
    }

    private fun revokeScopes(scopes: Set<String>, clearConfiguration: Boolean) {
        scopes.forEach(::validateScope)
        val owner = currentOwnerId() ?: return
        val refreshTokens = scopes.mapNotNull { scope ->
            secrets.getConnectorSecret(owner, "grant_${scopeKey(scope)}_refresh")
        }.distinct()
        refreshTokens.forEach { token ->
            runCatching { GoogleOAuthProtocol.revokeToken(transport, token) }
        }
        // Google documents that revoking a token removes every scope previously granted to the project.
        GoogleOAuthProtocol.ALLOWED_SCOPES.forEach { scope -> clearGrant(owner, scopeKey(scope)) }
        if (clearConfiguration) {
            secrets.clearConnectorSecrets(owner)
            check(preferences.edit().remove(CLIENT_ID).remove(ACCOUNT_LABEL).commit()) {
                "Could not remove the Google OAuth client setting"
            }
        }
    }

    fun disableScope(scope: String) {
        validateScope(scope)
        if (isIdentityMode()) {
            // AuthorizationClient revocation clears this application's Google grant as a whole.
            revokeIdentityState()
            return
        }
        revokeScopes(setOf(scope), clearConfiguration = false)
    }

    private fun validAccess(owner: String, grant: String, scope: String): String {
        val until = secrets.getConnectorSecret(owner, "grant_${grant}_expiry")?.toLongOrNull() ?: 0L
        val access = secrets.getConnectorSecret(owner, "grant_${grant}_access").orEmpty()
        if (access.isNotBlank() && until > System.currentTimeMillis() + REFRESH_LEEWAY_MS) return access
        return refresh(owner, grant, scope)
    }

    @Synchronized
    private fun refresh(owner: String, grant: String, scope: String): String {
        val refresh = secrets.getConnectorSecret(owner, "grant_${grant}_refresh")
            ?: error("Google refresh credential is missing; authorize this feature again")
        val form = GoogleOAuthProtocol.refreshForm(configuredClientId(),
            secrets.getConnectorSecret(owner, CLIENT_SECRET).orEmpty(), refresh)
        val response = tokenRequest(form)
        val access = response.optString("access_token")
        if (access.isBlank()) error("Google OAuth refresh returned no access token")
        val rotated = response.optString("refresh_token").takeIf(String::isNotBlank) ?: refresh
        val scopes = response.optString("scope").split(' ').filter(String::isNotBlank).toSet().ifEmpty { setOf(scope) }
        saveGrant(owner, scope, access, rotated, expiry(response), scopes)
        return access
    }

    private fun apiRequest(method: String, url: String, access: String, body: String?, contentType: String) =
        transport.execute(method, url, mapOf("Authorization" to "Bearer $access", "Content-Type" to contentType,
            "Accept" to "application/json"), body)

    private fun tokenRequest(fields: Map<String, String>): JSONObject = GoogleOAuthProtocol.exchangeToken(transport, fields)

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

    private fun validateScope(scope: String) {
        require(scope in GoogleOAuthProtocol.ALLOWED_SCOPES) { "Unsupported Google scope" }
    }

    companion object {
        private const val PREFERENCES = "jarvys_full_oauth_config"
        private const val CLIENT_ID = "installed_client_id"
        private const val ACCOUNT_LABEL = "installed_account_label"
        private const val CLIENT_SECRET = "installed_client_secret"
        private const val KEY_IDENTITY_MODE = "google_identity_authorization_enabled"
        private const val KEY_IDENTITY_SCOPES = "google_identity_granted_scopes"
        private const val KEY_IDENTITY_EMAIL = "google_identity_account_email"
        private const val KEY_IDENTITY_REAUTHORIZE = "google_identity_reauthorize_required"
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

class GoogleOAuthException(val providerError: String) : IllegalStateException(
    "Google OAuth failed (${providerError.take(80)})",
)

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

    internal fun exchangeToken(transport: GoogleHttpTransport, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (key, value) -> "${formEncode(key)}=${formEncode(value)}" }
        val response = transport.execute("POST", TOKEN_ENDPOINT,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), body)
        if (response.status !in 200..299) {
            val providerError = runCatching { JSONObject(response.body).optString("error") }.getOrNull().orEmpty()
            if (providerError.isNotBlank()) throw GoogleOAuthException(providerError)
            error("Google OAuth token request failed with HTTP ${response.status}")
        }
        return JSONObject(response.body)
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

internal object GoogleUrlConnectionTransport : GoogleHttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse {
        require(url.startsWith("https://")) { "Google API requests require HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= GoogleApiLimits.MAX_RESPONSE_BYTES) { "Google API response exceeded the byte limit" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            return GoogleHttpResponse(status, text)
        } finally { connection.disconnect() }
    }
}

package com.jarvys.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.SecretStore
import com.jarvys.agent.connectors.*
import com.jarvys.agent.flavor.GoogleServiceCard
import com.jarvys.agent.flavor.googleOAuthDiagnosticMessage
import com.jarvys.agent.ui.JarvysOwnTheme
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class GoogleAuthorizationFailureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var priorSecretStore: Any? = null
    @Before fun setup() {
        priorSecretStore = singleton.get(null)
        singleton.set(null, SecretStore(compose.activity.getSharedPreferences("fake-google-failure-ui", Context.MODE_PRIVATE)))
        compose.activity.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { singleton.set(null, priorSecretStore) }

    @Test fun disconnectedErrorSurvivesRecreationAndRetryUsesOnlyTheClickedScope() = checkFailure(1f, false)

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun spanishLargeTextErrorHasFullAccessibleTextAndNoEllipsis() = checkFailure(2f, true)

    @Test fun connectedComposeRetryKeepsReadAndComposeWithoutRequestingSend() {
        compose.activity.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit()
            .putString("google_identity_granted_scopes", GoogleOAuthProtocol.GMAIL_READ).commit()
        val calls = AtomicInteger()
        val requested = mutableListOf<Set<String>>()
        val failure = GoogleIdentityAuthorizationException(GoogleIdentityFailure.OTHER, "private provider text",
            diagnostic = GoogleAuthorizationDiagnostic(GoogleAuthorizationPhase.REQUEST,
                GoogleAuthorizationFailureCategory.PROVIDER, 8))
        val identity = object : GoogleIdentityAuthorization {
            override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
                synchronized(requested) { requested += scopes.toSet() }
                if (calls.incrementAndGet() == 1) throw failure
                return GoogleIdentityGrant("fake-token", scopes, null)
            }
            override fun clearToken(accessToken: String) = Unit
            override fun revoke(scopes: Set<String>, accountEmail: String?) = error("Revocation was not requested")
        }
        val manager = GoogleOAuthManager(compose.activity, identityClient = identity)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, ApprovalGate.INSTANCE)
        registry.register(ConnectorDefinition(GmailConnector.ID, "Gmail", "test", "Google fixture",
            connectionAccessGranted = { manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ) }))
        registry.connect(GmailConnector.ID)
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                GoogleServiceCard("Gmail", "Google connector", "gmail", GmailConnector.ID, manager, registry,
                    listOf(GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read,
                        GoogleOAuthProtocol.GMAIL_COMPOSE to R.string.full_google_scope_gmail_compose,
                        GoogleOAuthProtocol.GMAIL_SEND to R.string.full_google_scope_gmail_send), true, {})
            }
        }
        val addPermission = compose.activity.getString(R.string.connector_add_permission)
        // COMPOSE is the first ungranted feature; SEND remains a separate untouched choice.
        compose.onAllNodesWithText(addPermission)[0].performScrollTo().performClick()
        compose.onNode(hasText(addPermission) and hasAnyAncestor(isPopup())).performClick()
        val errorText = googleOAuthDiagnosticMessage(compose.activity, failure)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(errorText).fetchSemanticsNodes().size == 1 }
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_COMPOSE))
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_retry_authorization)).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            // Querying semantics synchronizes the paused Robolectric main looper with the posted completion callback.
            compose.onAllNodesWithText(errorText).fetchSemanticsNodes().isEmpty() &&
                calls.get() == 2 && !manager.isAuthorizationInProgress()
        }
        compose.onNodeWithText(errorText).assertDoesNotExist()
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_COMPOSE),
            setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_COMPOSE)), requested)
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_COMPOSE))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND))
    }

    private fun checkFailure(fontScale: Float, dark: Boolean) {
        val attempts = AtomicInteger()
        val scopesSeen = mutableListOf<Set<String>>()
        val safeFailure = GooglePlayServicesAuthorizationClient(compose.activity).classify(
            ExecutionException(ApiException(Status(12345, "access_token=private-provider-secret private@example.com"))),
            GoogleAuthorizationPhase.RESULT)
        val expectedText = googleOAuthDiagnosticMessage(compose.activity, safeFailure)
        val fakeIdentity = object : GoogleIdentityAuthorization {
            override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
                synchronized(scopesSeen) { scopesSeen += scopes.toSet() }
                if (attempts.incrementAndGet() == 1) throw safeFailure
                return GoogleIdentityGrant("fake-token", scopes, null)
            }
            override fun clearToken(accessToken: String) = Unit
            override fun revoke(scopes: Set<String>, accountEmail: String?) = error("Revocation was not requested")
        }
        val manager = GoogleOAuthManager(compose.activity, identityClient = fakeIdentity)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, ApprovalGate.INSTANCE)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        GoogleServiceCard("Gmail", "Google connector", "gmail", GmailConnector.ID, manager, registry,
                            listOf(GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read), true, {})
                    }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_connect_integrated)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(expectedText).fetchSemanticsNodes().size == 1 }
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(expectedText).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
        for (line in 0 until layouts.single().lineCount) assertFalse(layouts.single().isLineEllipsized(line))
        assertFalse(expectedText.contains("private-provider-secret"))
        assertFalse(expectedText.contains("private@example.com"))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(expectedText).assertExists()
        val retry = compose.onNodeWithText(compose.activity.getString(R.string.full_google_retry_authorization))
        retry.performScrollTo()
        capture(if (dark) "google-failure-dark-es-large" else "google-failure-light-en")
        retry.performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(expectedText).fetchSemanticsNodes().isEmpty() &&
                attempts.get() == 2 && !manager.isAuthorizationInProgress()
        }
        compose.onNodeWithText(expectedText).assertDoesNotExist()
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ))
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_READ), setOf(GoogleOAuthProtocol.GMAIL_READ)), scopesSeen)
    }

    private fun capture(name: String) {
        val directory = System.getProperty("jarvys.google.captureDir")?.let(::File) ?: return
        directory.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

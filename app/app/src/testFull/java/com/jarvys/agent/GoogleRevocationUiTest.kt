package com.jarvys.agent

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.connectors.*
import com.jarvys.agent.flavor.GoogleServiceCard
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class GoogleRevocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var priorSecretStore: Any? = null
    @Before fun setup() {
        priorSecretStore = singleton.get(null)
        singleton.set(null, SecretStore(compose.activity.getSharedPreferences("fake-revoke-ui", Context.MODE_PRIVATE)))
        compose.activity.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { singleton.set(null, priorSecretStore) }
    @Test fun pendingLegacyRevocationDisablesModeAndConfigurationUntilAcknowledged() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val manager = GoogleOAuthManager(compose.activity, GoogleHttpTransport { _, _, _, _ ->
            started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); GoogleHttpResponse(200, "")
        })
        manager.configure("fake-ui-client", "", "fake-ui-account")
        val owner = "full_" + GoogleOAuthProtocol.hexSha256("fake-ui-client|fake-ui-account").take(48)
        SecretStore.get(compose.activity).saveConnectorSecret(owner, "grant_gmail_read_refresh", "fake-token")
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, ApprovalGate.INSTANCE)
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                GoogleServiceCard("Gmail", "Google connector", "gmail", GmailConnector.ID, manager, registry,
                    listOf(GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read), true, {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.connector_advanced)).performScrollTo().performClick()
        manager.revokeAndClear()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val switchLabel = compose.activity.getString(R.string.full_google_use_integrated_authorization)
        val setupLabel = compose.activity.getString(R.string.full_google_configure)
        compose.onNodeWithText(switchLabel).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(setupLabel).performScrollTo().assertIsNotEnabled()
        finish.countDown()
        compose.waitUntil(5_000) { manager.revocationState() == GoogleRevocationState.VERIFIED }
        compose.onNodeWithText(switchLabel).performScrollTo().assertIsEnabled().performClick()
        assertTrue(manager.isIdentityMode())
    }
}

package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real OAuth manager + real retry executor, fake identity and HTTP; no credentials or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoogleMutationAdmissionTest {
    private lateinit var context: Context
    private val secretField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var priorSecrets: Any? = null
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        priorSecrets = secretField.get(null)
        secretField.set(null, SecretStore(context.getSharedPreferences("fake_google_secrets", Context.MODE_PRIVATE)))
        context.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("google_identity_authorization_enabled", true)
            .putString("google_identity_granted_scopes", GoogleOAuthProtocol.GMAIL_MODIFY).commit()
    }
    @After fun cleanup() { secretField.set(null, priorSecrets) }

    private fun scenario(retry: Boolean, reject: Boolean) {
        var admissions = 0
        var proofs = 0
        val proofAdmissions = mutableListOf<Int>()
        var mutations = 0
        var refreshes = 0
        val identity = object : GoogleIdentityAuthorization {
            override fun authorize(scopes: Set<String>, accountEmail: String?) = GoogleIdentityGrant("synthetic-access", scopes, null)
            override fun clearToken(accessToken: String) {
                refreshes++; assertEquals(1, admissions)
                // Exercise the manager's profile-proof branch again, rather than the native-account shortcut.
                context.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit()
                    .remove("google_identity_account_email").commit()
            }
            override fun revoke(scopes: Set<String>, accountEmail: String?) = Unit
        }
        val transport = GoogleHttpTransport { method, url, _, _ ->
            if (url.endsWith("/profile")) {
                assertEquals("GET", method); proofAdmissions += admissions; proofs++
                GoogleHttpResponse(200, """{"emailAddress":"fixture@example.test"}""")
            } else {
                assertEquals("POST", method)
                mutations++; assertEquals(mutations, admissions)
                GoogleHttpResponse(if (retry && mutations == 1) 401 else 200, "{}")
            }
        }
        val manager = GoogleOAuthManager(context, transport, identity)
        val execute = {
            manager.requestCancellable(GoogleOAuthProtocol.GMAIL_MODIFY, "POST",
                "${GoogleRestEndpoints.GMAIL}/users/me/messages/m1/trash", token = CancellationToken.cancellable(),
                beforeDispatch = { /* generic authorization deliberately does not admit WRITE */ },
                beforeMutation = { if (reject) error("durable admission failed"); admissions++ })
        }
        if (reject) assertTrue(runCatching { execute() }.exceptionOrNull() is GooglePreDispatchAuthorizationException)
        else assertEquals(200, execute().status)
        assertEquals(if (retry) 2 else 1, proofs)
        assertEquals(if (retry) listOf(0, 1) else listOf(0), proofAdmissions)
        assertEquals(if (reject) 0 else if (retry) 2 else 1, mutations)
        assertEquals(if (retry) 1 else 0, refreshes)
    }
    @Test fun profileReadNeverInvokesMutationAdmission() = scenario(retry = false, reject = false)
    @Test fun rejectedDurableMutationAdmissionAllowsProofButBlocksTarget() = scenario(retry = false, reject = true)
    @Test fun definitive401RetryRevalidatesMutationAfterRefreshWithoutAdmittingProof() = scenario(retry = true, reject = false)
}

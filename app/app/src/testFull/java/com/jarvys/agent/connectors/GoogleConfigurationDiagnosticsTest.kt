package com.jarvys.agent.connectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.flavor.googleOAuthErrorMessage
import com.jarvys.agent.flavor.googleOAuthDiagnosticMessage
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import java.util.concurrent.ExecutionException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleConfigurationDiagnosticsTest {
    @Test fun fingerprintUsesPublicCertificateBytesInExpectedAndroidClientFormat() {
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            GoogleConfigurationDiagnostics.fingerprint("abc".toByteArray()))
    }
    @Test fun installedClientDiagnosticExposesOnlyPackageAndCertificate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = GoogleConfigurationDiagnostics.identity(context)
        assertEquals(context.packageName, identity.packageName)
        assertTrue(identity.signingSha1.all { it.matches(Regex("[A-F0-9]{2}(:[A-F0-9]{2}){19}")) })
        assertEquals(setOf("packageName", "signingSha1"), GoogleConfigurationDiagnostics.ClientIdentity::class.java
            .declaredFields.filterNot { it.isSynthetic || it.name.startsWith("$") }.map { it.name }.toSet())
    }
    @Test fun blockedConfigurationAdviceDoesNotInventTestingConsoleState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = googleOAuthErrorMessage(context, GoogleIdentityAuthorizationException(GoogleIdentityFailure.ACCESS_BLOCKED,
            "secret token private developer details"))
        assertTrue(text.contains("SHA-1"))
        assertFalse(text.contains("still in Testing"))
        assertFalse(text.contains("secret token"))
        assertTrue(text.contains("not been verified"))
    }
    @Test fun knownAuthFailuresNeverEchoProviderSecrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (reason in GoogleIdentityFailure.entries) {
            val text = googleOAuthErrorMessage(context, GoogleIdentityAuthorizationException(reason, "access_token=private-secret"))
            assertFalse("Unsafe error for $reason: $text", text.contains("private-secret"))
        }
    }
    @Test fun wrappedUnmappedProviderCodeAndPhaseSurviveWithoutProviderTextOrCause() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = GooglePlayServicesAuthorizationClient(context)
        val secret = "access_token=private-token account=private@example.com https://example.test/?code=private-code"
        for (status in listOf(CommonStatusCodes.INTERNAL_ERROR, 12345)) {
            val error = client.classify(ExecutionException(IllegalStateException(secret, ApiException(Status(status, secret)))),
                GoogleAuthorizationPhase.RESULT)
            assertEquals(GoogleIdentityFailure.OTHER, error.reason)
            assertEquals(GoogleAuthorizationDiagnostic(GoogleAuthorizationPhase.RESULT,
                GoogleAuthorizationFailureCategory.PROVIDER, status), error.diagnostic)
            assertNull(error.cause)
            val display = googleOAuthDiagnosticMessage(context, error)
            assertTrue(display.contains(status.toString()))
            assertTrue(display.contains("Reading the Google authorization result"))
            assertFalse(display.contains("private"))
            assertFalse(error.toString().contains("private"))
            assertFalse(error.diagnostic.toString().contains("private"))
        }
    }
    @Test fun cancelledAndMisconfiguredCodesRetainTheirStatusDomainAndExistingGuidance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = GooglePlayServicesAuthorizationClient(context)
        for ((code, reason) in listOf(CommonStatusCodes.CANCELED to GoogleIdentityFailure.USER_CANCELLED,
            CommonStatusCodes.DEVELOPER_ERROR to GoogleIdentityFailure.ACCESS_BLOCKED)) {
            val error = client.classify(ExecutionException(ApiException(Status(code, "private-provider-message"))),
                GoogleAuthorizationPhase.REQUEST)
            assertEquals(reason, error.reason)
            assertEquals(code, error.diagnostic?.apiStatusCode)
            assertEquals(GoogleAuthorizationPhase.REQUEST, error.diagnostic?.phase)
            assertFalse(googleOAuthDiagnosticMessage(context, error).contains("private-provider-message"))
        }
    }
    @Test fun localFailureHasOnlyAnAllowlistedCategoryAndNoInventedProviderCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val error = IllegalStateException("private-account private-token private-client-secret")
        assertEquals(GoogleAuthorizationDiagnostic(GoogleAuthorizationPhase.UNKNOWN,
            GoogleAuthorizationFailureCategory.LOCAL_STATE), GoogleAuthorizationDiagnostic.from(error))
        val display = googleOAuthDiagnosticMessage(context, error)
        assertFalse(display.contains("private"))
        assertFalse(display.contains("Google API status code"))
        val timeout = GooglePlayServicesAuthorizationClient(context).classify(java.util.concurrent.TimeoutException("private"),
            GoogleAuthorizationPhase.RESOLUTION)
        assertEquals(GoogleIdentityFailure.NETWORK, timeout.reason)
        assertEquals(GoogleAuthorizationFailureCategory.TIMEOUT, timeout.diagnostic?.category)
        assertNull(timeout.diagnostic?.apiStatusCode)
        val unclassified = GooglePlayServicesAuthorizationClient(context).classify(
            GoogleIdentityAuthorizationException(GoogleIdentityFailure.OTHER, "private unclassified response"),
            GoogleAuthorizationPhase.RESULT)
        assertEquals(GoogleAuthorizationFailureCategory.UNKNOWN, unclassified.diagnostic?.category)
        assertEquals(GoogleAuthorizationPhase.RESULT, unclassified.diagnostic?.phase)
        assertFalse(googleOAuthDiagnosticMessage(context, unclassified).contains("private"))
    }
}

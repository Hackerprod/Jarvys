package com.jarvys.agent.connectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.flavor.googleOAuthErrorMessage
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
}

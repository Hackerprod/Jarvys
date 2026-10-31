package com.jarvys.agent

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewDiagnosticTraceTest {
    @Test
    fun failurePrefersConcreteLocalResourceStatusAndDropsRawConsoleAndUrlText() {
        val logs = mutableListOf<String>()
        val trace = WebViewDiagnosticTrace(::moduleAssetLabel, logs::add)
        trace.recordConsoleMessage(
            "https://outside.example/private.js?token=secret", 12, "ERROR",
            "Uncaught TypeError: private argument secret",
        )
        trace.recordHttpError(
            Uri.parse("https://appassets.androidplatform.net/assets/modules/chart/logic/tools.js?key=private"),
            403,
        )

        val failure = trace.failureMessage("generic fallback")
        assertEquals(
            "HTTP 403 while loading https://appassets.androidplatform.net/assets/modules/chart/logic/tools.js",
            failure,
        )
        val allOutput = (logs + failure).joinToString("\n")
        assertFalse(allOutput.contains("outside.example"))
        assertFalse(allOutput.contains("private.js"))
        assertFalse(allOutput.contains("token"))
        assertFalse(allOutput.contains("secret"))
        assertFalse(allOutput.contains("private argument"))
    }

    @Test
    fun deniedRequestUsesOnlySaneLocalLabelAndAllowlistedReason() {
        val trace = WebViewDiagnosticTrace(::moduleAssetLabel)
        trace.recordDenied(
            Uri.parse("https://appassets.androidplatform.net/assets/modules/chart/logic/tools.js"),
            "outside allowed local scope",
            mainFrame = true,
        )
        assertEquals(
            "Resource denied: logic/tools.js (outside allowed local scope) (main frame)",
            trace.failureMessage("generic fallback"),
        )

        val external = WebViewDiagnosticTrace(::moduleAssetLabel)
        external.recordDenied(Uri.parse("https://outside.example/private?token=secret"), "untrusted raw reason")
        val message = external.failureMessage("generic fallback")
        assertEquals("Resource denied: https://outside.example (request rejected by local policy)", message)
        assertTrue(!message.contains("/private") && !message.contains("secret"))
    }

    @Test
    fun handlerExceptionOutranksIncidentalFaviconAndOnlyKeepsErrorType() {
        val trace = WebViewDiagnosticTrace(::moduleAssetLabel)
        trace.recordDenied(
            Uri.parse("https://appassets.androidplatform.net/favicon.ico"),
            WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE,
            mainFrame = false,
        )
        trace.recordHttpError(
            Uri.parse("https://appassets.androidplatform.net/favicon.ico"), 403, mainFrame = false,
        )
        trace.recordHandlerException("logic/tools.js", "TypeError")

        assertEquals("Module handler raised TypeError: logic/tools.js", trace.failureMessage("fallback"))

        val unsafe = WebViewDiagnosticTrace(::moduleAssetLabel)
        unsafe.recordHandlerException("logic/tools.js", "TypeError with user input secret")
        assertEquals("Module handler raised JavaScript error: logic/tools.js", unsafe.failureMessage("fallback"))
    }

    private fun moduleAssetLabel(uri: Uri): String? =
        com.jarvys.agent.modules.ModuleWebViewSecurity.moduleAssetPath(uri, "modules/chart")
}

package com.jarvys.agent.flavor

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.connectors.GoogleConfigurationDiagnostics
import com.jarvys.agent.connectors.GoogleRevocationState
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class GoogleConfigurationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val identity = GoogleConfigurationDiagnostics.ClientIdentity("com.jarvys.agent",
        listOf("A6:ED:C6:0E:3A:0B:5E:31:B2:B8:2C:C3:FF:B1:BE:BF:E2:A8:CF:37"))
    @Test fun englishNarrowDiagnosticsAndUnconfirmedRevocationAreReadable() = render(false, 1f, "google-config-light-en")
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun spanishNarrowLargeTextDiagnosticsAndRevocationAreReadable() = render(true, 2f, "google-config-dark-es-large")

    private fun render(dark: Boolean, fontScale: Float, capture: String) {
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        GoogleConfigurationHelp(identity)
                        GoogleRevocationStatus(GoogleRevocationState.FAILED)
                    }
                }
            }
        }
        val packageLabel = compose.activity.getString(R.string.full_google_diagnostics_package, identity.packageName)
        compose.onNodeWithText(packageLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_diagnostics_sha1, identity.signingSha1.single()))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_revoke_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_revoke_verified)).assertDoesNotExist()
        capture(capture)
    }

    @Test fun pendingFailedAndVerifiedStatesNeverClaimEarlySuccess() {
        var state by mutableStateOf(GoogleRevocationState.NOT_REQUESTED)
        compose.setContent { MaterialTheme { GoogleRevocationStatus(state) } }
        val verified = compose.activity.getString(R.string.full_google_revoke_verified)
        compose.onNodeWithText(verified).assertDoesNotExist()
        compose.runOnIdle { state = GoogleRevocationState.PENDING }
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_revoke_pending)).assertIsDisplayed()
        compose.onNodeWithText(verified).assertDoesNotExist()
        compose.runOnIdle { state = GoogleRevocationState.FAILED }
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_revoke_failed)).assertIsDisplayed()
        compose.onNodeWithText(verified).assertDoesNotExist()
        compose.runOnIdle { state = GoogleRevocationState.VERIFIED }
        compose.onNodeWithText(verified).assertIsDisplayed()
    }
    @Test fun unsignedHostDoesNotInventARegisteredCertificate() {
        compose.setContent { MaterialTheme { Column { GoogleConfigurationHelp(identity.copy(signingSha1 = emptyList())) } } }
        compose.onNodeWithText(compose.activity.getString(R.string.full_google_diagnostics_no_cert)).assertIsDisplayed()
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

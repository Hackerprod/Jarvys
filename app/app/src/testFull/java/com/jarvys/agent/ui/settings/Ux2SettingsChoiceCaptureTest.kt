package com.jarvys.agent.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppLanguagePolicy
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.JarvysChoiceOption
import com.jarvys.agent.JarvysChoiceSheetContent
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.proactive.ProactiveStatus
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Ux2SettingsChoiceCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureColorAndLanguageSelectorsInLightAndDark() {
        val stage = System.getenv("UX2_CAPTURE_STAGE") ?: "before"
        require(stage == "before" || stage == "after")
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (dark in listOf(false, true)) {
            val mode = if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT
            capture(stage, "color_${if (dark) "dark" else "light"}", mode, R.string.settings_color_mode,
                listOf(
                    JarvysChoiceOption(JarvysThemeMode.SYSTEM, base.getString(R.string.settings_system)),
                    JarvysChoiceOption(JarvysThemeMode.LIGHT, base.getString(R.string.settings_light)),
                    JarvysChoiceOption(JarvysThemeMode.DARK, base.getString(R.string.settings_dark)),
                ), JarvysThemeMode.DARK)
            capture(stage, "language_${if (dark) "dark" else "light"}", mode, R.string.language_dialog_title,
                listOf(
                    JarvysChoiceOption(AppLanguageChoice.ENGLISH, base.getString(R.string.language_option_english)),
                    JarvysChoiceOption(AppLanguageChoice.SPANISH, base.getString(R.string.language_option_spanish)),
                    JarvysChoiceOption(AppLanguageChoice.SYSTEM, base.getString(R.string.language_option_system)),
                ), AppLanguageChoice.SPANISH)
        }
    }

    private fun <T> capture(
        stage: String,
        name: String,
        mode: JarvysThemeMode,
        titleResource: Int,
        choices: List<JarvysChoiceOption<T>>,
        selected: T,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                JarvysOwnTheme(mode) {
                    val localized = LocalContext.current
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsWorkspace(
                            page = SettingsWorkspacePage.HOME,
                            themeMode = mode, showAgentEvents = true, proactiveEnabled = false,
                            proactiveStatus = ProactiveStatus(enabled = false), agentTimeoutSeconds = 0,
                            memoryEnabled = false, memoryUsedCharacters = 0,
                            languageChoice = AppLanguagePolicy.fromStoredValue("es"),
                            onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
                            onProactiveEnabledChange = {}, onRefreshProactiveStatus = {}, onAgentTimeoutChange = {},
                            onNavigateRoute = {}, onMcp = {}, onSkills = {}, onConnectors = {}, onMemory = {},
                            scheduledTasksAvailable = false, onScheduledTasks = {}, onAccessibilitySettings = {}, onNavigate = {},
                        )
                        if (stage == "after") {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                            Surface(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .testTag("ux2-choice-capture-surface"),
                                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                JarvysChoiceSheetContent(
                                    title = localized.getString(titleResource),
                                    choices = choices,
                                    selected = selected,
                                    onSelect = {},
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                            Surface(
                                modifier = Modifier.align(Alignment.Center).widthIn(max = 320.dp),
                                shape = AlertDialogDefaults.shape,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = AlertDialogDefaults.TonalElevation,
                            ) {
                                LegacyChoiceDialogScene(title = localized.getString(titleResource),
                                    choices = choices, selected = selected)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = captureActivityBitmap()
        try {
            val directory = TestCaptureDirectories.named("ux2-shots")
            val output = File(directory, "${stage}_settings_$name.png")
            TestCaptureDirectories.assertOwned(directory, output)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Composable
    private fun <T> LegacyChoiceDialogScene(title: String, choices: List<JarvysChoiceOption<T>>, selected: T) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp))
            choices.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(choice.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected = choice.value == selected, onClick = {})
                }
            }
            TextButton(onClick = {}, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text(stringResource(R.string.connector_close))
            }
        }
    }

    private fun captureActivityBitmap(): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val width = (411f * density).toInt()
            val height = (891f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            captured = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        return requireNotNull(captured)
    }
}

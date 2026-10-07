package com.jarvys.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvysUiKitComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listAndEditorChromeMarginsAndStickySaveRemainAlignedAtFontScaleOneAndTwo() {
        val editor = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    JarvysScreen(
                        title = if (editor.value) "Add MCP server" else "MCP Servers",
                        onBack = {},
                        bottomAction = if (editor.value) ({ JarvysPrimaryButton("Save", {}, Modifier.testTag("save-button")) }) else null,
                    ) { insets ->
                        if (editor.value) {
                            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState()).testTag("editor-scroll")) {
                                JarvysTextField("", {}, label = { androidx.compose.material3.Text("Alias") },
                                    modifier = Modifier.testTag("first-field"))
                                Spacer(Modifier.height(720.dp))
                                JarvysSwitchRow("Trust insecure MCP server", "Traffic is unencrypted. Use trusted hosts only.",
                                    false, {}, modifier = Modifier.testTag("risk-row"), warning = true)
                            }
                        } else {
                            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                                JarvysGroup(Modifier.testTag("first-card")) {
                                    JarvysListRow("Jarvys local tools", "On device · 17 tools", icon = LucideIcons.Bot)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            fontScale.value = scale
            editor.value = false
            compose.waitForIdle()
            val titleBefore = compose.onNodeWithTag("jarvys-topbar-title").fetchSemanticsNode().boundsInRoot.top
            val backBefore = compose.onNodeWithTag("jarvys-back").fetchSemanticsNode().boundsInRoot
            val cardLeft = compose.onNodeWithTag("first-card").fetchSemanticsNode().boundsInRoot.left
            editor.value = true
            compose.waitForIdle()
            val titleAfter = compose.onNodeWithTag("jarvys-topbar-title").fetchSemanticsNode().boundsInRoot.top
            val backAfter = compose.onNodeWithTag("jarvys-back").fetchSemanticsNode().boundsInRoot
            val fieldLeft = compose.onNodeWithTag("first-field").fetchSemanticsNode().boundsInRoot.left
            assertTrue("title vertical offset differs at fontScale=$scale", kotlin.math.abs(titleBefore - titleAfter) <= 1f)
            assertEquals(backBefore.width, backAfter.width, 1f)
            assertEquals(backBefore.height, backAfter.height, 1f)
            assertEquals(cardLeft, fieldLeft, 1f)
            compose.onNodeWithTag("editor-scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val riskBottom = compose.onNodeWithTag("risk-row").fetchSemanticsNode().boundsInRoot.bottom
            val saveTop = compose.onNodeWithTag("save-button").fetchSemanticsNode().boundsInRoot.top
            assertTrue("risk row overlaps Save at fontScale=$scale: $riskBottom > $saveTop", riskBottom <= saveTop)
        }
        assertTrue(compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty())
        assertEquals(21, JarvysUiTokens.ToolbarTitleSize.value.toInt())
    }

    @Test fun topBarsAndOutlinedFieldsAreCentralizedInTheKit() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val source = sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not find app/src/main/java from ${working.path}")
        val offenders = mutableListOf<String>()
        Files.walk(source.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "JarvysUiKit.kt" }
                .forEach { path ->
                    val text = String(Files.readAllBytes(path), Charsets.UTF_8)
                    Regex("\\b(?:TopAppBar|OutlinedTextField)\\s*\\(").findAll(text).forEach { match ->
                        offenders += "${path.fileName}:${text.take(match.range.first).count { c -> c == '\n' } + 1}"
                    }
                }
        }
        assertTrue("Ad-hoc Material controls found outside JarvysUiKit: $offenders", offenders.isEmpty())
    }
}

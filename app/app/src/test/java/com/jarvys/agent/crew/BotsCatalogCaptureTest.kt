package com.jarvys.agent.crew

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.jarvysColorScheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native-pixel matrix: EN/ES x light/dark x normal/2x, with real layout and editor interactions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class BotsCatalogCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun failFastOnLayoutLoop() { setBotsTestIdleTimeout(10) }
    @After fun restoreIdleTimeout() { setBotsTestIdleTimeout(60) }
    private val output = TestCaptureDirectories.named("bots-ux15-${BuildConfig.FLAVOR}")

    @Test fun englishLightNormal() = captureMatrix(false, 1f, "en")
    @Test fun englishDarkNormal() = captureMatrix(true, 1f, "en")
    @Test fun englishLightLarge() = captureMatrix(false, 2f, "en")
    @Test fun englishDarkLarge() = captureMatrix(true, 2f, "en")
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi") fun spanishLightNormal() = captureMatrix(false, 1f, "es")
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi") fun spanishDarkNormal() = captureMatrix(true, 1f, "es")
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi") fun spanishLightLarge() = captureMatrix(false, 2f, "es")
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi") fun spanishDarkLarge() = captureMatrix(true, 2f, "es")

    @Test fun lightWorkingSweepMovesAndIdleReducedRemainStatic() = captureWorkingFrames(false)
    @Test fun darkWorkingSweepMovesAndIdleReducedRemainStatic() = captureWorkingFrames(true)

    private fun captureWorkingFrames(dark: Boolean) {
        val coding = BotDefinition(CrewProfile.codingDefault(), 1, true, true, "")
        var working by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            MaterialTheme(colorScheme = jarvysColorScheme(dark)) {
                BotsCatalogGrid(listOf(coding), if (working) mapOf("coding" to 1) else emptyMap(), {}, {}, {})
            }
        } }
        val prefix = "${if (dark) "dark" else "light"}-motion"
        fun pair(mode: String, expectMotion: Boolean) {
            compose.mainClock.advanceTimeBy(160)
            capture("$prefix-$mode-a")
            compose.mainClock.advanceTimeBy(1200)
            capture("$prefix-$mode-b")
            val equal = File(output, "$prefix-$mode-a.png").readBytes()
                .contentEquals(File(output, "$prefix-$mode-b.png").readBytes())
            assertEquals("Only working, visible, non-reduced names may animate ($mode)", !expectMotion, equal)
        }
        pair("working", true)
        compose.runOnIdle { working = false }
        pair("idle", false)
        compose.runOnIdle { working = true; reduced = true }
        pair("reduced", false)
    }

    private fun captureMatrix(dark: Boolean, fontScale: Float, language: String) {
        val research = BotDefinition(CrewProfile("custom-research", 1,
            if (language == "es") "Investigación" else "Research", "Compare evidence and sources", "Compare sources and explain uncertainty.",
            emptyList(), emptyList()), 1, true, false, "")
        val paused = BotDefinition(CrewProfile("custom-writer", 1, if (language == "es") "Escritura" else "Writing",
            "Write clearly", "Write clear summaries.", emptyList(), emptyList()), 2, false, false, "")
        val coding = BotDefinition(CrewProfile.codingDefault(), 1, true, true, "")
        val android = BotDefinition(CrewProfile("android-use", 1, "Android-use", "Native Android connectors", "Use declared tools.", emptyList(), emptyList()), 1, true, true, "")
        var route by mutableStateOf("grid")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalReducedMotion provides true) {
                MaterialTheme(colorScheme = jarvysColorScheme(dark)) {
                    if (route == "grid") BotsCatalogGrid(listOf(coding, android, research, paused), mapOf("coding" to 2),
                        onOpen = { route = "editor" }, onCreate = { route = "prompt" }, onClose = {})
                    else if (route == "prompt") BotCreationPrompt(false, null, {}, { route = "grid" })
                    else BotDefinitionEditor(research, false, emptyList(), emptyList(), {}, { route = "grid" }, {}, {}, {}, iconAvailable = true)
                }
            }
        }
        val prefix = "$language-${if (dark) "dark" else "light"}-${fontScale}x"
        compose.onNodeWithTag("bots-grid").assertIsDisplayed()
        val first = compose.onNodeWithTag("bot-tile-coding").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("bot-tile-android-use").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithTag("bots-grid").fetchSemanticsNode().config.contains(SemanticsActions.ScrollToIndex))
        assertEquals("Two columns must remain at large font sizes", first.top, second.top, 0.1f)
        assertTrue(first.right < second.left)
        compose.onNodeWithText("Built-in").assertDoesNotExist()
        compose.onNodeWithText("Integrado").assertDoesNotExist()
        compose.onNodeWithText("Your reusable assistants").assertDoesNotExist()
        compose.onNodeWithText("Tus asistentes reutilizables").assertDoesNotExist()
        val add = compose.onNodeWithTag("bots-create").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("bots-back").fetchSemanticsNode().boundsInRoot
        assertEquals(back.center.y, add.center.y, 0.1f)
        assertTrue(add.bottom <= first.top)
        listOf("coding", "android-use").forEach { id ->
            val icon = compose.onNodeWithTag("bot-icon-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val name = compose.onNodeWithTag("bot-name-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("icon must sit above the complete name at ${fontScale}x", icon.bottom < name.top)
            assertTrue(name.left >= first.left || id == "android-use")
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Android-use", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Bot name must wrap rather than clip", layouts.single().hasVisualOverflow)
        capture("$prefix-grid")
        compose.onNodeWithTag("bots-grid").performScrollToNode(hasTestTag("bot-open-custom-writer"))
        compose.onNodeWithTag("bot-open-custom-writer").assertIsDisplayed()
        compose.onNodeWithTag("bot-enabled-custom-writer").assertDoesNotExist()
        capture("$prefix-grid-custom")
        compose.runOnIdle { route = "editor" }
        compose.onNodeWithTag("bot-editor-name").assertExists()
        capture("$prefix-editor-top")
        compose.onNodeWithTag("bot-editor-instructions").performScrollTo().performTextReplacement("Review these reusable instructions.")
        capture("$prefix-editor-instructions")
        compose.onNodeWithTag("bot-editor-icon").performScrollTo().performClick()
        compose.onNodeWithTag("bot-icon-prompt").performScrollTo().performTextReplacement("A blue observatory on a moon")
        capture("$prefix-icon-prompt")
        compose.onNodeWithTag("bot-icon-cancel").performClick()
        compose.onNodeWithTag("bot-editor-instructions").performScrollTo().assertTextContains("Review these reusable instructions.")
        compose.onNodeWithTag("bot-editor-save").performScrollTo().assertIsDisplayed()
        val save = compose.onNodeWithTag("bot-editor-save").fetchSemanticsNode().boundsInRoot
        assertTrue(save.height >= 48f)
        capture("$prefix-editor-actions")
        compose.runOnIdle { route = "prompt" }
        compose.onNodeWithTag("bot-creation-text").assertExists()
        capture("$prefix-create")
    }

    private fun capture(name: String) {
        awaitReactionDrawIdle(compose)
        val back = compose.onNodeWithTag("bots-back").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("bots-header-title").fetchSemanticsNode().boundsInRoot
        assertTrue("Header title must start after the 48dp back target", title.left >= back.right)
        compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(output, "$name.png")
            TestCaptureDirectories.assertOwned(output, file)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            println("BOTS_UX15_CAPTURE=${file.absolutePath}")
        }
    }
}

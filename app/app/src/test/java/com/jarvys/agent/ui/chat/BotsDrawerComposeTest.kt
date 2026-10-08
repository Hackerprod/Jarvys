package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class BotsDrawerComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetScale() { RuntimeEnvironment.setFontScale(1f) }

    private val pinned = RunHistoryItem("pinned", "old source request", "CHAT", 0, 0, 1.0, title = "Pinned project", pinned = true)
    private val current = RunHistoryItem("current", "hidden source request", "CHAT", 0, 0, 2.0, title = "Current project")
    private val archived = RunHistoryItem("archive", "archived source", "CHAT", 0, 0, 3.0, title = "Archived project", archived = true)

    @Test fun botsIsFirstMenuThenPinnedAndCurrentSectionsWithoutOldHeadingOrSubtitles() {
        var opened = 0
        content(onBots = { opened++ })
        compose.onNodeWithTag("drawer-open-bots").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        val botBounds = compose.onNodeWithTag("drawer-open-bots").fetchSemanticsNode().boundsInRoot
        val newBounds = compose.onNodeWithTag("drawer-new-chat").fetchSemanticsNode().boundsInRoot
        assertTrue(botBounds.bottom <= newBounds.top)
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_chats_heading)).assertDoesNotExist()
        compose.onNodeWithText("old source request").assertDoesNotExist()
        val pinnedHeading = compose.onNodeWithTag("drawer-pinned-heading").fetchSemanticsNode().boundsInRoot
        val currentHeading = compose.onNodeWithTag("drawer-current-heading").fetchSemanticsNode().boundsInRoot
        assertTrue(pinnedHeading.bottom < currentHeading.top)
        compose.onNodeWithText("Pinned project").assertIsDisplayed()
        compose.onNodeWithText("Current project").assertIsDisplayed()
    }

    @Test fun activePinnedConversationIsUniqueAndResumeUsesTheActiveCallback() {
        var resumed = 0
        content(active = true, onResume = { resumed++ })
        compose.onAllNodesWithText("Pinned project").assertCountEquals(1)
        compose.onNodeWithText("Pinned project").performClick()
        assertEquals(1, resumed)
    }

    @Test fun searchAndArchiveActionsKeepTheirTargets() {
        var action: Pair<String, ConversationAction>? = null
        content(onAction = { id, kind, _ -> action = id to kind })
        compose.onNodeWithTag("drawer-search").performTextInput("Pinned")
        compose.onNodeWithText("Pinned project").assertIsDisplayed()
        compose.onNodeWithText("Current project").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_chat_actions, "Pinned project")).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_unpin)).performClick()
        assertEquals("pinned" to ConversationAction.UNPIN, action)
        compose.onNodeWithTag("drawer-archive-toggle").performClick()
        compose.onNodeWithText("Archived project").assertIsDisplayed()
        compose.onNodeWithText("Pinned project").assertDoesNotExist()
        compose.onNodeWithTag("drawer-archive-toggle").performClick()
        compose.onNodeWithText("Current project").assertIsDisplayed()
    }

    @Test fun lightEnglishDrawerCapture() = capture(false, 1f)
    @Test fun darkEnglishDoubleFontDrawerCapture() = capture(true, 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun darkSpanishDrawerCapture() = capture(true, 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun lightSpanishDoubleFontDrawerCapture() = capture(false, 2f)

    private fun capture(dark: Boolean, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            val config = android.content.res.Configuration(resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
        }
        content(dark = dark)
        compose.onNodeWithTag("drawer-open-bots").assertIsDisplayed()
        val output = TestCaptureDirectories.named("ux15-drawer-${BuildConfig.FLAVOR}")
        saveCapture(output, "${if (dark) "dark" else "light"}_${compose.activity.resources.configuration.locales[0].language}_font${scale.toInt()}_menus.png")
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText("Current project"))
        compose.onNodeWithText("Current project").assertIsDisplayed()
        val row = compose.onNodeWithText("Current project").fetchSemanticsNode().boundsInRoot
        assertTrue(row.width > 30f && row.height > 10f)
        saveCapture(output, "${if (dark) "dark" else "light"}_${compose.activity.resources.configuration.locales[0].language}_font${scale.toInt()}_chats.png")
    }

    private fun saveCapture(output: File, name: String) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val file = File(output, name); TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    private fun content(dark: Boolean = false, active: Boolean = false, onBots: () -> Unit = {},
        onResume: () -> Unit = {}, onAction: (String, ConversationAction, String?) -> Unit = { _, _, _ -> }) {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    ConversationDrawer(listOf(pinned, current, archived), if (active) "pinned" else "new",
                        if (active) "Pinned project" else null, "", active, null, true,
                        {}, onResume, {}, {}, onOpenBots = onBots, onConversationAction = onAction)
                }
            }
        }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.jarvys.agent.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AssistantMarkdown
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the production user bubble; all text, links and clipboard data are local fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi", shadows = [ReactionMagnifierShadow::class])
class UserMarkdownRecoveryComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val menu = MarkdownSelectionMenu()
    private val openedUris = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { openedUris += uri }
    }

    @Test fun boldInlineCodeAndListsRenderWithoutChangingStoredText() {
        val source = "**Bold** and `inline_code`\n\n- first item\n- second item"
        val event = user(source)
        install { IntentMessage(event) }
        val node = awaitText("Bold and inline_code")
        val richText = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(richText.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(richText.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        awaitText("first item").assertIsDisplayed()
        awaitText("second item").assertIsDisplayed()
        compose.onAllNodesWithText("**Bold**", substring = true, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(source, event.text)
    }

    @Test fun shortPlainTextBubbleWrapsAndPreservesSingleNewlines() {
        val source = "First line\nsnake_case_name"
        install { IntentMessage(user(source)) }
        val node = awaitText(source)
        assertEquals(2, layout(node).lineCount)
        val bubble = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("markdown-scene").fetchSemanticsNode().boundsInRoot
        assertTrue("Short text must not stretch the user bubble to the timeline width", bubble.width < parent.width * 0.8f)
        assertEquals(parent.right - 18f, bubble.right, 1f)
    }

    @Test fun completeCodeFenceScrollsWithinTheBubbleAndUsesCodeTypography() {
        val code = "val aVeryLongLocalVariable = \"" + "abcdef".repeat(25) + "\""
        install { IntentMessage(user("```kotlin\n$code\n```")) }
        val node = awaitText(code, fontSize = 12f)
        assertEquals(FontFamily.Monospace, layout(node).layoutInput.style.fontFamily)
        val bubble = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("markdown-scene").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.width <= parent.width - 36f)
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
        assertTrue(scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
        scroller.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
    }

    @Test fun malformedFenceAndHtmlRemainVisibleLiteralText() {
        val malformed = "```kotlin\nval unfinished = 1"
        val html = "<div>literal HTML</div>"
        install { Column { IntentMessage(user(malformed)); IntentMessage(user(html, 2L)) } }
        awaitText(malformed).assertIsDisplayed()
        awaitText(html).assertIsDisplayed()
    }

    @Test fun ordinaryLinkOpensThroughPlatformHandler() {
        install { IntentMessage(user("Read [Example](https://example.test/docs)")) }
        val node = awaitText("Read Example")
        val glyph = layout(node).getBoundingBox("Read ".length + 2).center
        node.performTouchInput { click(glyph) }
        compose.runOnIdle { assertEquals(listOf("https://example.test/docs"), openedUris) }
    }

    @Test fun formattedUserTextStillSupportsPointerSelectionAndCopy() {
        val source = "Alpha **bravo** charlie"
        install { IntentMessage(user(source).copy(reactionEmoji = "👍")) }
        val rendered = "Alpha bravo charlie"
        val node = awaitText(rendered)
        val glyph = layout(node).getBoundingBox(rendered.indexOf("bravo") + 2).center
        node.performTouchInput { longClick(glyph) }
        compose.waitUntil(5_000) { menu.provider != null }
        compose.runOnIdle { menu.click(TextContextMenuKeys.CopyKey) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("bravo", (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun assistantDefaultStillFoldsSingleNewlinesAndUsesBodySpacing() {
        install {
            AssistantMarkdown("First line\nSecond line", onOpenSkillFile = {},
                modifier = Modifier.testTag("assistant-markdown"))
        }
        val node = awaitText("First line Second line")
        assertEquals(1, layout(node).lineCount)
        assertEquals(14f, layout(node).layoutInput.style.fontSize.value, 0f)
        assertEquals(20f, layout(node).layoutInput.style.lineHeight.value, 0f)
    }

    private fun user(text: String, id: Long = 1L) = AgentRunUiEvent.messageEvent(id, "user", text, id)

    private fun install(content: @Composable () -> Unit) {
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true,
                    LocalTextContextMenuToolbarProvider provides menu, LocalUriHandler provides uriHandler) {
                    Box(Modifier.fillMaxSize().testTag("markdown-scene").padding(18.dp)) { content() }
                }
            }
        }
    }

    private fun awaitText(text: String, fontSize: Float = 14f): SemanticsNodeInteraction {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The plain fallback may have identical text. Wait for the rendered Markdown typography.
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        compose.waitUntil(5_000) { layout(node).layoutInput.style.fontSize.value == fontSize }
        compose.waitForIdle()
        return node
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private class MarkdownSelectionMenu : TextContextMenuProvider {
        var provider: TextContextMenuDataProvider? = null
        private var continuation: CancellableContinuation<Unit>? = null
        private val session = object : TextContextMenuSession {
            override fun close() { continuation?.takeIf { it.isActive }?.resume(Unit) }
        }
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            try {
                provider = dataProvider
                suspendCancellableCoroutine<Unit> { continuation = it }
            } finally { provider = null; continuation = null }
        }
        fun click(key: Any) {
            checkNotNull(provider).data().components.filterIsInstance<TextContextMenuItem>()
                .single { it.key == key }.onClick(session)
        }
    }
}

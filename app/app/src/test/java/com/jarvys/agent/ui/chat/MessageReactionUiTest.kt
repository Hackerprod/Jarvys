@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.jarvys.agent.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionHandleInfoKey
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Product composables, real pointer selection and local attachment fixtures; no account or network. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi", shadows = [ReactionMagnifierShadow::class])
class MessageReactionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val menu = ReactionSelectionMenu()
    private val body = "Alpha bravo charlie delta"

    @Test fun emptyAndMalformedReactionsHaveNoNodeAndNoLayoutFootprint() {
        var event by mutableStateOf(user())
        install { Column(Modifier.testTag("message-layout")) { IntentMessage(event) } }
        awaitText(body)
        val emptyBounds = compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot
        val bubbleBounds = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode().boundsInRoot
        assertEquals(bubbleBounds.height, emptyBounds.height, 0.01f)
        for (value in listOf("", "ordinary text", "👍🎉", "<b>👍</b>", "👍\n", "\uD83D")) {
            compose.runOnIdle { event = event.copy(reactionEmoji = value) }
            compose.onNodeWithTag("user-message-reaction-1").assertDoesNotExist()
            assertEquals(emptyBounds, compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun badgeIsOneReadOnlyEnglishAnnouncementCenteredOnTheBottomEdge() {
        install { Column(Modifier.testTag("message-layout")) { IntentMessage(user().copyReaction("👍")) } }
        awaitText(body)
        assertBadge(1L, "Jarvys reacted 👍")
        val badge = compose.onNodeWithTag("user-message-reaction-1").fetchSemanticsNode()
        val bubble = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode()
        assertEquals("Exactly half the badge crosses the bubble edge", bubble.boundsInRoot.bottom, badge.boundsInRoot.center.y, 0.5f)
        assertEquals("Only the outside half is reserved below", badge.boundsInRoot.bottom,
            compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot.bottom, 0.01f)
        assertTextClearance(body, 1L)
        assertEquals("Badge stays aligned to the bubble's end", bubble.boundsInRoot.right, badge.boundsInRoot.right, 0.01f)
        assertTrue("The static status must not acquire a button-sized touch target", badge.boundsInRoot.height < 48f)
        compose.onNodeWithTag("user-message-reaction-1").performTouchInput { longClick(Offset(center.x, height * 0.25f)) }
        compose.runOnIdle { assertEquals(null, menu.provider) }
    }

    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi", shadows = [ReactionMagnifierShadow::class])
    fun badgeHasTheSpanishAnnouncement() {
        install { IntentMessage(user().copyReaction("🎉")) }
        assertBadge(1L, "Jarvys reaccionó con 🎉")
    }

    @Test fun syntheticAndNonUserMessagesNeverDisplayStoredReactionMetadata() {
        var event by mutableStateOf(user().copy(reactionEmoji = "👍", proactiveThreadKey = "proactive-thread"))
        install { IntentMessage(event) }
        compose.onNodeWithTag("user-message-reaction-1").assertDoesNotExist()
        compose.runOnIdle { event = user().copy(reactionEmoji = "👍", proactiveNotice = true) }
        compose.onNodeWithTag("user-message-reaction-1").assertDoesNotExist()
        compose.runOnIdle { event = user().copy(reactionEmoji = "👍", kind = "assistant") }
        compose.onNodeWithTag("user-message-reaction-1").assertDoesNotExist()
    }

    @Test fun liveAddReplaceRepeatAndRemoveKeepsOneBadgeAndRestoresOriginalHeight() {
        var event by mutableStateOf(user())
        install { Column(Modifier.testTag("message-layout")) { IntentMessage(event) } }
        awaitText(body)
        val emptyBounds = compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot
        val bubbleBounds = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode().boundsInRoot
        val textBounds = compose.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (emoji in listOf("👀", "🎉", "🎉", "👍")) {
            compose.runOnIdle { event = event.copyReaction(emoji) }
            assertBadge(1L, "Jarvys reacted $emoji")
            val bubble = compose.onNodeWithTag("user-message-bubble-1").fetchSemanticsNode().boundsInRoot
            assertEquals(bubbleBounds.top, bubble.top, 0.01f)
            assertEquals(bubbleBounds.width, bubble.width, 0.01f)
            assertEquals("Clearance stays outside the text-selection region", textBounds,
                compose.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
            assertTextClearance(body, 1L)
            assertEquals(compose.onNodeWithTag("user-message-reaction-1").fetchSemanticsNode().boundsInRoot.bottom,
                compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot.bottom, 0.01f)
            assertTrue(compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot.height > emptyBounds.height)
        }
        compose.runOnIdle { event = event.copyReaction("") }
        compose.onNodeWithTag("user-message-reaction-1").assertDoesNotExist()
        assertEquals(emptyBounds, compose.onNodeWithTag("message-layout").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun selectingUserTextSurvivesReactionUpdateAndCopyExcludesBadgeAndNeighbors() {
        var event by mutableStateOf(user())
        install {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IntentMessage(event)
                IntentMessage(user(2L, "Separate user message").copyReaction("🎉"))
                Text("Adjacent control")
            }
        }
        select(body, "bravo")
        handles().assertCountEquals(2)
        compose.runOnIdle { event = event.copyReaction("👀") }
        handles().assertCountEquals(2)
        compose.runOnIdle { event = event.copyReaction("👍") }
        handles().assertCountEquals(2)
        compose.runOnIdle { event = event.copyReaction("") }
        handles().assertCountEquals(2)
        compose.runOnIdle { event = event.copyReaction("👍") }
        handles().assertCountEquals(2)
        assertEquals("bravo", copy())
        select(body, "charlie")
        compose.runOnIdle { menu.click(TextContextMenuKeys.SelectAllKey) }
        compose.waitForIdle()
        assertEquals(body, copy())
        assertBadge(1L, "Jarvys reacted 👍")
        assertBadge(2L, "Jarvys reacted 🎉")
    }

    @Test fun attachmentOnlyAndTextMessagesKeepTheirPreviewTouchTargetsAndSelection() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("reaction-photo.png")
        var event by mutableStateOf(user(text = "").copy(attachments = listOf(image), reactionEmoji = "👍"))
        install {
            CompositionLocalProvider(LocalContext provides fixtures.context) { IntentMessage(event, fixtures.session) }
        }
        val thumbnail = "chat-attachment-image-${image.id}"
        awaitTag(thumbnail)
        compose.waitUntil(5_000) {
            compose.onNodeWithTag(thumbnail).fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
        }
        assertBadge(1L, "Jarvys reacted 👍")
        val imageBounds = compose.onNodeWithTag(thumbnail).fetchSemanticsNode().boundsInRoot
        val reactionBounds = compose.onNodeWithTag("user-message-reaction-1").fetchSemanticsNode().boundsInRoot
        assertTrue("Reaction must never cover the image", reactionBounds.top > imageBounds.bottom)
        repeat(2) {
            compose.onNodeWithTag(thumbnail).performTouchInput { click() }
            val close = fixtures.context.getString(R.string.chat_attachment_close_preview)
            compose.onNodeWithContentDescription(close).assertIsDisplayed()
            compose.onNodeWithContentDescription(close).performTouchInput { click() }
            compose.onNodeWithContentDescription(close).assertDoesNotExist()
        }
        compose.runOnIdle { event = event.copy(text = body) }
        select(body, "bravo")
        compose.runOnIdle { menu.click(TextContextMenuKeys.SelectAllKey) }
        compose.waitForIdle()
        assertEquals(body, copy())
        assertTrue(fixtures.store.resolve(fixtures.session, image).isFile)
    }

    @Test fun doubleFontBadgeKeepsFinalLineSelectableAndItsInsideHalfDoesNotSelectText() {
        val multiline = "Alpha bravo\ncharlie delta"
        var event by mutableStateOf(user(text = multiline).copyReaction("👍"))
        install(fontScale = 2f) { IntentMessage(event) }
        awaitText(multiline)
        assertBadge(1L, "Jarvys reacted 👍")
        assertTextClearance(multiline, 1L)
        compose.onNodeWithTag("user-message-reaction-1").performTouchInput {
            longClick(Offset(center.x, height * 0.25f))
        }
        compose.runOnIdle { assertEquals(null, menu.provider) }
        handles().assertCountEquals(0)
        select(multiline, "delta")
        compose.runOnIdle { event = event.copyReaction("🎉") }
        handles().assertCountEquals(2)
        assertEquals("delta", copy())
    }

    @Test fun doubleFontAttachmentOnlyBadgeLeavesImageAndFileTargetsClear() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("reaction-photo-large.png")
        val document = fixtures.document("notes.txt")
        install(fontScale = 2f) {
            CompositionLocalProvider(LocalContext provides fixtures.context) {
                IntentMessage(user(text = "").copy(attachments = listOf(image, document), reactionEmoji = "👍"), fixtures.session)
            }
        }
        val thumbnail = "chat-attachment-image-${image.id}"
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(thumbnail).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsActions.OnClick) == true
        }
        assertBadge(1L, "Jarvys reacted 👍")
        val badge = compose.onNodeWithTag("user-message-reaction-1").fetchSemanticsNode().boundsInRoot
        for (tag in listOf(thumbnail, "chat-attachment-file-${document.id}")) {
            val attachment = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Large-font inside half never covers attachment content", badge.top >= attachment.bottom + 3.5f)
        }
        compose.onNodeWithTag("user-message-reaction-1").performTouchInput { click(Offset(center.x, height * 0.25f)) }
        compose.onNodeWithContentDescription(fixtures.context.getString(R.string.chat_attachment_close_preview)).assertDoesNotExist()
        compose.onNodeWithTag(thumbnail).performTouchInput { click() }
        compose.onNodeWithContentDescription(fixtures.context.getString(R.string.chat_attachment_close_preview)).assertIsDisplayed()
    }

    @Test fun repeatedDoubleFontRowsReserveTheirOutsideHalfDuringAddReplaceAndRemove() {
        var first by mutableStateOf(user(text = "One\nTwo"))
        var clicked by mutableStateOf(false)
        install(fontScale = 2f) {
            Column {
                IntentMessage(first)
                IntentMessage(user(2L, "Three\nFour").copyReaction("🎉"))
                IntentMessage(user(3L, "Five").copyReaction("👍"))
                Button(onClick = { clicked = true }, modifier = Modifier.testTag("next-message-control")) { Text("Next") }
            }
        }
        awaitText("One\nTwo")
        awaitText("Three\nFour")
        awaitText("Five")
        for (emoji in listOf("👀", "🎉", "")) {
            compose.runOnIdle { first = first.copyReaction(emoji) }
            for (id in 1L..2L) {
                val bottomTag = if (id == 1L && emoji.isEmpty()) "user-message-bubble-$id" else "user-message-reaction-$id"
                val bottom = compose.onNodeWithTag(bottomTag).fetchSemanticsNode().boundsInRoot.bottom
                val next = compose.onNodeWithTag("user-message-bubble-${id + 1}").fetchSemanticsNode().boundsInRoot
                assertTrue("A full badge fits before the next row even without row spacing", bottom <= next.top)
            }
            val lastBadge = compose.onNodeWithTag("user-message-reaction-3").fetchSemanticsNode().boundsInRoot
            val control = compose.onNodeWithTag("next-message-control").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(lastBadge.bottom <= control.top)
        }
        compose.onNodeWithTag("next-message-control").performTouchInput { click() }
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun assistantStreamingAndOtherUserMessagesDoNotGainOrMutateTheReaction() {
        var reply by mutableStateOf("An answer is arriving")
        var running by mutableStateOf(true)
        var event by mutableStateOf(user().copyReaction("👀"))
        install {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IntentMessage(event)
                IntentMessage(user(2L, "A different user message"))
                AssistantReplyView(AgentRunUiEvent.messageEvent(3L, "assistant", reply, 3L)
                    .copy(reactionEmoji = "🎉"), {}, false, streamActive = running)
            }
        }
        awaitText(reply)
        assertBadge(1L, "Jarvys reacted 👀")
        compose.onNodeWithTag("user-message-reaction-2").assertDoesNotExist()
        compose.onNodeWithTag("user-message-reaction-3").assertDoesNotExist()
        val copyDescription = compose.activity.getString(R.string.chat_footer_copy_description)
        compose.onNodeWithContentDescription(copyDescription).assertDoesNotExist()
        compose.runOnIdle { reply = "An answer is arriving with more content"; event = event.copyReaction("👍") }
        awaitText(reply)
        assertBadge(1L, "Jarvys reacted 👍")
        compose.runOnIdle { running = false }
        compose.onNodeWithContentDescription(copyDescription).assertIsDisplayed()
        compose.onNodeWithContentDescription(copyDescription).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(reply, (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.text?.toString())
        }
        assertBadge(1L, "Jarvys reacted 👍")
    }

    private fun user(id: Long = 1L, text: String = body) = AgentRunUiEvent.messageEvent(id, "user", text, id)

    private fun install(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalTextContextMenuToolbarProvider provides menu,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().padding(18.dp)) { content() }
                }
            }
        }
    }

    private fun assertBadge(id: Long, description: String) {
        compose.onAllNodesWithTag("user-message-reaction-$id").assertCountEquals(1)
        val node = compose.onNodeWithTag("user-message-reaction-$id").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(listOf(description), node.config[SemanticsProperties.ContentDescription])
        assertFalse("Read-only status has no click action", node.config.contains(SemanticsActions.OnClick))
        assertFalse("Read-only status has no long-click action", node.config.contains(SemanticsActions.OnLongClick))
        assertFalse("Read-only status has no control role", node.config.contains(SemanticsProperties.Role))
        val bubble = compose.onNodeWithTag("user-message-bubble-$id").fetchSemanticsNode().boundsInRoot
        assertEquals("Badge straddles the bottom edge", bubble.bottom, node.boundsInRoot.center.y, 0.5f)
        assertEquals("Badge keeps end alignment", bubble.right, node.boundsInRoot.right, 0.01f)
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    private fun assertTextClearance(text: String, id: Long) {
        val content = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("user-message-reaction-$id").fetchSemanticsNode().boundsInRoot
        assertTrue("The inside half clears the final line and selection hit area", badge.top >= content.bottom + 3.5f)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitText(text: String): SemanticsNodeInteraction {
        return awaitReactionMarkdownText(compose, text)
    }

    private fun select(text: String, word: String) {
        val node = awaitText(text)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val glyph = results.single().getBoundingBox(text.indexOf(word) + word.length / 2).center
        node.performTouchInput { longClick(glyph) }
        compose.waitUntil(5_000) { menu.provider != null }
        compose.waitForIdle()
    }

    private fun handles() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SelectionHandleInfoKey), useUnmergedTree = true)

    private fun copy(): String {
        compose.runOnIdle { menu.click(TextContextMenuKeys.CopyKey) }
        compose.waitForIdle()
        return (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }

    /** Records real Foundation toolbar callbacks; only toolbar presentation and magnifier are replaced. */
    private class ReactionSelectionMenu : TextContextMenuProvider {
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
            val item = checkNotNull(provider).data().components.filterIsInstance<TextContextMenuItem>().single { it.key == key }
            item.onClick(session)
        }
    }
}

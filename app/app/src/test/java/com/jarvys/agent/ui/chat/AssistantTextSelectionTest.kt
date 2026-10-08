@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.jarvys.agent.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.text.contextmenu.data.*
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.foundation.text.selection.SelectionHandleInfoKey
import androidx.compose.foundation.text.Handle
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.CHAT_COMPOSER_OVERLAY_TAG
import com.jarvys.agent.ui.shell.JarvysShellFrame
import java.io.File
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

/** Production message/timeline/shell, physical pointers and clipboard; no account or network.
 * Only platform menu presentation and magnifier are substituted on the host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi", shadows = [ReactionMagnifierShadow::class])
class AssistantTextSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val menu = SelectionMenu()
    private val openedUris = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { openedUris += uri }
    }
    private val body = "Alpha bravo charlie delta"
    private val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
        override fun isConnected(id: String) = false
        override fun setConnected(id: String, connected: Boolean) = Unit
    }, { true })

    @Test fun lightMessageSelection() = checkMessageSelection(false, 1f)
    @Test fun darkMessageSelection() = checkMessageSelection(true, 1f)
    @Test fun lightLargeMessageSelection() = checkMessageSelection(false, 1.5f)
    @Test fun darkLargeMessageSelection() = checkMessageSelection(true, 1.5f)

    private fun checkMessageSelection(dark: Boolean, fontScale: Float) {
        var reaction by mutableStateOf("👀")
        install(dark, fontScale) {
            Column {
                IntentMessage(event(1L, "user", "User adjacent message").copy(reactionEmoji = reaction))
                AssistantReplyView(event(2L, "assistant", "Alpha **bravo** charlie delta"), {}, false)
                AssistantReplyView(event(3L, "assistant", "Neighbor response excluded"), {}, false)
            }
        }
        select(body, "bravo")
        handles().assertCountEquals(2)
        compose.runOnIdle { reaction = "👍" }
        handles().assertCountEquals(2)
        capture("${if (dark) "dark" else "light"}-font$fontScale-selection")
        assertEquals("bravo", copy())
        select(body, "charlie")
        selectAll()
        assertEquals("Select all stays in one assistant body, excluding footer, user and neighbor", body, copy())
        select("User adjacent message", "adjacent")
        assertEquals("adjacent", copy())
        compose.onNodeWithTag("user-message-reaction-1").assertIsDisplayed()
    }

    @Test fun draggingASelectionHandleExtendsTheCopiedRange() {
        install { AssistantReplyView(event(1L, "assistant", body), {}, false) }
        val text = awaitText(body)
        select(body, "bravo")
        val handle = handles()[1]
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        val textBounds = text.fetchSemanticsNode().boundsInRoot
        val end = layout(text).getBoundingBox(body.indexOf("delta") + 3).center + textBounds.topLeft
        handle.performTouchInput {
            down(center)
            moveTo(end - handleBounds.topLeft, delayMillis = 200)
            up()
        }
        compose.waitUntil(5_000) { menu.provider != null }
        val selected = copy()
        assertTrue("Dragged selection includes the next word: $selected", selected.startsWith("bravo charlie"))
        assertFalse(selected.contains("Alpha"))
    }

    @Test fun codeAndMultipleMarkdownBlocksShareOnlyTheirMessageSelection() {
        val code = "val localAnswer = 42"
        val markdown = "# Heading\n\nBefore **bold** paragraph\n\n```kotlin\n$code\n```\n\nAfter paragraph"
        install {
            Column {
                AssistantReplyView(event(1L, "assistant", markdown), {}, false)
                Text("Outside footer sentinel")
                IntentMessage(event(2L, "user", "Outside neighbor sentinel"))
            }
        }
        select(code, "localAnswer", fontSize = 12f)
        handles().assertCountEquals(2)
        assertEquals("localAnswer", copy())
        select("Before bold paragraph", "bold")
        selectAll()
        val copied = copy()
        for (expected in listOf("Heading", "Before bold paragraph", code, "After paragraph")) {
            assertTrue("Missing rendered block $expected in $copied", copied.contains(expected))
        }
        assertFalse(copied.contains("Outside"))
        assertFalse(copied.contains("```"))
        // The existing whole-message copy action still copies the exact source Markdown.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_footer_copy_description))
            .performTouchInput { click() }
        assertEquals(markdown, clipboard())
    }

    @Test fun horizontalCodeScrollAndPointerCopyBothWork() {
        val code = "val localAnswer = \"" + "abcdef".repeat(35) + "\""
        install { AssistantReplyView(event(1L, "assistant", "```kotlin\n$code\n```"), {}, false) }
        awaitText(code, fontSize = 12f)
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
        scroller.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        assertNull("A short scroll must not open selection", menu.provider)
        scroller.performTouchInput { swipeRight() }
        compose.waitForIdle()
        select(code, "localAnswer", fontSize = 12f)
        assertEquals("localAnswer", copy())
    }

    @Test fun linkTapAndLongPressTextRemainSeparate() {
        val rendered = "Read Example then select bravo"
        install { AssistantReplyView(event(1L, "assistant", "Read [Example](https://example.test/docs) then select **bravo**"), {}, false) }
        val node = awaitText(rendered)
        val linkGlyph = layout(node).getBoundingBox(rendered.indexOf("Example") + 2).center
        node.performTouchInput { click(linkGlyph) }
        assertEquals(listOf("https://example.test/docs"), openedUris)
        select(rendered, "bravo")
        assertEquals("bravo", copy())
        assertEquals("Selecting ordinary text must not activate a link", 1, openedUris.size)
        select(rendered, "select")
        selectAll()
        assertEquals("Select all also includes the rendered link label", rendered, copy())
        assertEquals(1, openedUris.size)
    }

    @Test fun translationSelectionExcludesItsHeadingHideActionAndOriginalResponse() {
        val translated = "Traducción clara para seleccionar"
        var hidden = ""
        install {
            ConversationTimeline("translation-selection", events = listOf(
                event(1L, "assistant", "Original response excluded"),
                event(2L, "assistant_translation", translated).copy(stage = "Spanish translation", detail = "original-id")),
                isRunning = false, connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                chatWithoutMemory = false, onHideTranslation = { hidden = it })
        }
        select(translated, "clara")
        handles().assertCountEquals(2)
        selectAll()
        assertEquals(translated, copy())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_footer_hide_translation))
            .performTouchInput { click() }
        assertEquals("original-id", hidden)
    }

    @Test fun suggestedReplyRemainsOutsideSelectionAndKeepsItsTapAction() {
        val reply = event(1L, "assistant", body).copy(proactiveReplies = listOf(
            com.jarvys.agent.proactive.ProactiveSuggestedReply("Suggestion excluded", "Suggested action")))
        var selected: Pair<String, Int>? = null
        install {
            AssistantReplyView(reply, {}, false, onProactiveSuggestedReply = { id, index -> selected = id to index })
        }
        select(body, "bravo")
        selectAll()
        assertEquals(body, copy())
        compose.onNodeWithText("Suggestion excluded").performTouchInput { click() }
        assertEquals(reply.messageId to 0, selected)
    }

    @Test fun userAttachmentsAndReactionAreOutsideAssistantSelectAll() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val file = fixtures.document("excluded-file.txt")
        install {
            CompositionLocalProvider(LocalContext provides fixtures.context) {
                Column {
                    IntentMessage(event(1L, "user", "Attached user message").copy(attachments = listOf(file), reactionEmoji = "👍"), fixtures.session)
                    AssistantReplyView(event(2L, "assistant", body), {}, false)
                }
            }
        }
        select(body, "bravo")
        selectAll()
        assertEquals(body, copy())
        compose.onNodeWithTag("chat-attachment-file-${file.id}").assertIsDisplayed()
        select("Attached user message", "user")
        selectAll()
        assertEquals("Attached user message", copy())
    }

    @Test fun errorAndStreamingBodiesRemainSelectableWithNoActionFooter() {
        var running by mutableStateOf(true)
        var answer by mutableStateOf(body)
        install {
            Column {
                AssistantReplyView(event(1L, "assistant", answer), {}, false, streamActive = running)
                AssistantReplyView(event(2L, "assistant", "Failure detail remains selectable"), {}, true)
            }
        }
        select(body, "bravo")
        assertEquals("bravo", copy())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_footer_copy_description)).assertDoesNotExist()
        compose.runOnIdle { answer = "$body complete"; running = false }
        select("$body complete", "complete")
        assertEquals("complete", copy())
        select("Failure detail remains selectable", "detail")
        assertEquals("detail", copy())
    }

    @Test fun longAnswerScrollSelectionAndComposerFocusInLight() = checkShell(false, 1f)
    @Test fun longAnswerScrollSelectionAndComposerFocusInDarkLargeText() = checkShell(true, 1.5f)

    private fun checkShell(dark: Boolean, fontScale: Float) {
        val tail = "Final bravo paragraph stays selectable"
        val longAnswer = (1..18).joinToString("\n\n") { "Paragraph $it contains a long local response for vertical scrolling and gesture arbitration." } + "\n\n$tail"
        var goal by mutableStateOf("Preserved draft")
        var sends = 0
        var target: View? = null
        install(dark, fontScale, padded = false) {
            target = LocalView.current
            JarvysShellFrame(drawerState = rememberDrawerState(DrawerValue.Closed), drawerContent = {},
                route = AppRouteMeta("Selection regression", true, action = AppRouteAction.CHAT),
                agentSnapshot = AgentRunUiSnapshot(), onBack = {}, onOpenDrawer = {}, onOpenSettings = {},
                onNewChat = {}, onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                canCompact = false, compacting = false, canReflect = false, onToggleMemory = {}, onCompact = {}, onReflect = {},
                bottomBar = {
                    ChatComposer(goal = goal, onGoalChange = { goal = it }, onSend = { sends++ }, onStop = {},
                        onSelectModel = {}, modelLabel = "Local model", running = false, captureContextRequested = false,
                        onCaptureContext = {}, onImportSkill = {}, availableSkills = emptyList(), selectedSkillIds = emptySet(),
                        onToggleRunSkill = {}, onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                        crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {})
                }) {
                ConversationTimeline("long-selection", events = listOf(event(1L, "assistant", longAnswer)),
                    isRunning = false, connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false)
            }
        }
        val node = awaitText(tail)
        node.assertIsDisplayed()
        val scroller = compose.onNodeWithTag("chat-message-list")
        scroller.performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.65f, durationMillis = 180) }
        compose.waitForIdle()
        assertNull("Scrolling a long answer does not start selection", menu.provider)
        compose.onNodeWithTag("chat-jump-to-end").performTouchInput { click() }
        select(tail, "bravo")
        handles().assertCountEquals(2)
        compose.onNode(hasSetTextAction()).performTouchInput { down(center); moveBy(Offset(1f, 0f)); up() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("Preserved draft", goal)
        assertEquals("Focusing the composer must not send", 0, sends)
        handles().assertCountEquals(0)
        assertNull("Focusing the composer dismisses message selection", menu.provider)
        val beforeIme = compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(target), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 180))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle()
        val withIme = compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(beforeIme.bottom - 180f, withIme.bottom, 1f)
        compose.onNodeWithTag("chat-send-stop-button").performTouchInput { down(center); moveBy(Offset(1f, 0f)); up() }
        assertEquals(1, sends)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        capture("${if (dark) "dark" else "light"}-font$fontScale-shell-ime")
    }

    private fun event(id: Long, kind: String, text: String) = AgentRunUiEvent.messageEvent(id, kind, text, id)

    private fun install(dark: Boolean = false, fontScale: Float = 1f, padded: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalTextContextMenuToolbarProvider provides menu,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalUriHandler provides uriHandler) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (padded) Modifier.padding(18.dp) else Modifier)) { content() }
                }
            }
        }
    }

    private fun awaitText(text: String, fontSize: Float = 14f): SemanticsNodeInteraction {
        compose.waitUntil(5_000) {
            val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
            nodes.size == 1 && layout(compose.onNodeWithText(text, useUnmergedTree = true)).layoutInput.style.fontSize.value == fontSize
        }
        compose.waitForIdle()
        return compose.onNodeWithText(text, useUnmergedTree = true)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun select(text: String, word: String, fontSize: Float = 14f) {
        val node = awaitText(text, fontSize)
        val glyph = layout(node).getBoundingBox(text.indexOf(word) + word.length / 2).center
        node.performTouchInput { longClick(glyph) }
        compose.waitUntil(5_000) { menu.provider != null }
        compose.waitForIdle()
    }
    private fun handles() = compose.onAllNodes(SemanticsMatcher("range selection handle") {
        it.config.getOrNull(SelectionHandleInfoKey)?.let { info -> info.handle != Handle.Cursor } == true
    }, useUnmergedTree = true)
    private fun selectAll() { compose.runOnIdle { menu.click(TextContextMenuKeys.SelectAllKey) }; compose.waitForIdle() }
    private fun clipboard() = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    private fun copy(): String {
        compose.runOnIdle { menu.click(TextContextMenuKeys.CopyKey) }
        compose.waitForIdle()
        return clipboard()
    }
    private fun capture(name: String) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val directory = TestCaptureDirectories.named("ux23-selection-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX23_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }

    private class SelectionMenu : TextContextMenuProvider {
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

package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class DrawerDensityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetScale() { RuntimeEnvironment.setFontScale(1f) }
    private val pinned = RunHistoryItem("pinned-record", "Original pinned request", "CHAT", 0, 0, 1.0,
        "pinned-session", "Pinned project", pinned = true)
    private val current = pinned.copy(id = "current-record", sessionId = "current-session",
        title = "Current project", goal = "Other current request", pinned = false)
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun emptyDrawerOmitsBothHeadingsAndNormalEmptyDetails() {
        compose.setContent { Content(emptyList()) }
        assertNoSections()
        compose.onNodeWithText(text(R.string.drawer_no_chats)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.drawer_no_pinned_chats)).assertDoesNotExist()
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks", "drawer-open-settings")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed().assertHasClickAction() }
    }

    @Test fun sectionsFollowEmptyPinnedCurrentMixedAndArchivedTransitions() {
        val history = mutableStateOf(emptyList<RunHistoryItem>())
        compose.setContent { Content(history.value) }
        assertNoSections()
        compose.runOnIdle { history.value = listOf(pinned) }
        compose.onNodeWithTag("drawer-pinned-heading").assertIsDisplayed()
        compose.onNodeWithTag("drawer-current-heading").assertDoesNotExist()
        compose.onAllNodesWithText("Pinned project").assertCountEquals(1)
        compose.onNodeWithTag("drawer-pinned-marker", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { history.value = listOf(current) }
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        compose.onNodeWithTag("drawer-current-heading").assertIsDisplayed()
        compose.runOnIdle { history.value = listOf(pinned, current) }
        compose.onNodeWithTag("drawer-pinned-heading").assertIsDisplayed()
        compose.onNodeWithTag("drawer-current-heading").assertIsDisplayed()
        compose.onAllNodesWithText("Pinned project").assertCountEquals(1)
        compose.onAllNodesWithText("Current project").assertCountEquals(1)
        compose.runOnIdle { history.value = listOf(pinned.copy(archived = true), current.copy(archived = true)) }
        assertNoSections()
        compose.onNodeWithText(text(R.string.drawer_no_chats)).assertDoesNotExist()
    }

    @Test fun pinnedOnlyTitleAndGoalSearchesNeverProduceFalseEmptyFeedback() {
        compose.setContent { Content(listOf(pinned, current)) }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        for (query in listOf("Pinned project", "ORIGINAL PINNED")) {
            compose.onNodeWithTag("drawer-search").performTextReplacement(query)
            compose.onNode(hasText("Pinned project") and !hasSetTextAction()).assertIsDisplayed()
            compose.onNodeWithTag("drawer-current-heading").assertDoesNotExist()
            compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        }
        compose.onNodeWithTag("drawer-search").performTextReplacement("Current")
        compose.onNodeWithText("Current project").assertIsDisplayed()
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        compose.onNodeWithTag("drawer-search").performTextReplacement("missing")
        assertNoSections()
        compose.onNodeWithTag("drawer-search-empty").assertTextEquals(text(R.string.drawer_search_no_results))
        compose.onNodeWithTag("drawer-search").performTextReplacement("   ")
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        compose.onNodeWithText("Pinned project").assertIsDisplayed()
    }

    @Test fun emptySearchFeedbackRequiresANonblankQueryAndDisappearsOnClear() {
        compose.setContent { Content(emptyList()) }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.onNodeWithTag("drawer-search-empty").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.drawer_clear_search)).performClick()
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        assertNoSections()
    }

    @Test fun activePinnedSearchMatchRemainsUniqueAndKeepsResumeCallback() {
        var resumed = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.DARK) {
            ConversationDrawer(listOf(pinned), pinned.sessionId, pinned.title, pinned.goal, true, null, true,
                {}, { resumed++ }, {}, {})
        } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("Pinned")
        compose.onAllNodesWithText("Pinned project").assertCountEquals(1)
        compose.onNodeWithTag("drawer-search-empty").assertDoesNotExist()
        compose.onNodeWithText("Pinned project").performClick()
        assertEquals(1, resumed)
    }

    @Test fun compactNavigationHasContiguous48dpTargetsAndIndependentCallbacks() {
        val calls = mutableListOf<String>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ConversationDrawer(emptyList(), "new", null, "", false, null, true,
                { calls += "new" }, {}, {}, { calls += "settings" }, onOpenBots = { calls += "bots" },
                onOpenScheduledTasks = { calls += "scheduled" })
        } }
        val tags = listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks")
        val bounds = tags.map { compose.onNodeWithTag(it).assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .assertHasClickAction().fetchSemanticsNode().boundsInRoot }
        bounds.forEach { assertEquals(48f, it.height, 1f) }
        assertEquals(bounds[0].bottom, bounds[1].top, 0.01f)
        assertEquals(bounds[1].bottom, bounds[2].top, 0.01f)
        (tags + "drawer-open-settings").forEach { compose.onNodeWithTag(it).performTouchInput { click() } }
        assertEquals(listOf("new", "bots", "scheduled", "settings"), calls)
    }

    @Test fun rowOverflowIsVerticalAndLongPressAndBackDoNotMutateOrNavigate() {
        var mutations = 0
        var opens = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ConversationDrawer(listOf(pinned), "new", null, "", false, null, true, {}, {}, { opens++ }, {},
                onConversationAction = { _, _, _ -> mutations++ })
        } }
        compose.onNodeWithTag("conversation-overflow-vertical", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation-overflow-horizontal", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Pinned project").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.drawer_unpin)).assertIsDisplayed()
        dismissPopupWithBack()
        compose.onNodeWithText(text(R.string.drawer_unpin)).assertDoesNotExist()
        repeat(2) {
            openActions("Pinned project")
            compose.onNodeWithText(text(R.string.drawer_rename_chat)).performClick()
            compose.onNodeWithText(text(R.string.drawer_cancel_action)).performClick()
        }
        assertEquals(0, mutations)
        assertEquals(0, opens)
    }

    @Test fun archivedRowKeepsPinnedMarkerHorizontalOverflowAndRestoreAction() {
        val actions = mutableListOf<ConversationAction>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(listOf(pinned.copy(archived = true)), true, {}, { _, action, _ -> actions += action })
        } }
        compose.onNodeWithTag("drawer-pinned-marker", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation-overflow-horizontal", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation-overflow-vertical", useUnmergedTree = true).assertDoesNotExist()
        openActions("Pinned project")
        compose.onNodeWithText(text(R.string.drawer_unarchive)).performClick()
        assertEquals(listOf(ConversationAction.RESTORE), actions)
    }

    @Test fun populatedSectionHeadingsKeepHeadingSemantics() {
        compose.setContent { Content(listOf(pinned, current)) }
        listOf("drawer-pinned-heading", "drawer-current-heading").forEach {
            assertTrue(compose.onNodeWithTag(it).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        }
    }

    @Test fun backFirstCancelsSearchThenClosesDrawerWithoutRestoringEmptySections() {
        var closed = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ConversationDrawer(emptyList(), "new", null, "", false, null, true, {}, {}, {}, {},
                onCloseDrawer = { closed++ })
        } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("drawer-search").assertDoesNotExist()
        assertNoSections()
        assertEquals(0, closed)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closed)
    }

    @Test fun lightEnglishNormalFontCapture() = capture(false, 1f)
    @Test fun darkEnglishNormalFontCapture() = capture(true, 1f)
    @Test fun lightEnglishDoubleFontCapture() = capture(false, 2f)
    @Test fun darkEnglishDoubleFontCapture() = capture(true, 2f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun lightSpanishNormalFontCapture() = capture(false, 1f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun darkSpanishNormalFontCapture() = capture(true, 1f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun lightSpanishDoubleFontCapture() = capture(false, 2f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun darkSpanishDoubleFontCapture() = capture(true, 2f)

    private fun capture(dark: Boolean, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            val configuration = android.content.res.Configuration(resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION") resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        val titles = listOf("Pruebas de funcionalidad", "Preparación del entorno Linux", "Web para negocio de jardinería")
        val history = mutableStateOf(titles.mapIndexed { i, title -> pinned.copy(id = "record-$i", sessionId = "session-$i", title = title) })
        var density = 0f
        var renderedFontScale = 0f
        compose.setContent {
            density = LocalDensity.current.density
            renderedFontScale = LocalDensity.current.fontScale
            CompositionLocalProvider(LocalReducedMotion provides true) { Content(history.value, dark) }
        }
        compose.waitForIdle()
        assertEquals(scale, renderedFontScale, 0.001f)
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks").forEach { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48f * density - 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text(when (tag) {
                "drawer-new-chat" -> R.string.drawer_new_chat
                "drawer-open-bots" -> R.string.drawer_bots
                else -> R.string.drawer_scheduled_tasks
            })), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // The paragraph can retain the 190px measure constraint while Text wraps to its
            // intrinsic width. Check actual line bounds, not that unused paragraph width.
            assertFalse("Navigation label $tag must not clip vertically", layout.didOverflowHeight)
            repeat(layout.lineCount) { line ->
                assertFalse("Navigation label $tag must not ellipsize", layout.isLineEllipsized(line))
                assertTrue("Navigation label $tag left edge", layout.getLineLeft(line) >= -1f)
                assertTrue("Navigation label $tag right edge", layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
        saveCapture(dark, scale, "navigation")
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(titles.last()))
        compose.onNodeWithText(titles.last()).assertIsDisplayed()
        compose.onNodeWithTag("drawer-current-heading").assertDoesNotExist()
        compose.onAllNodesWithTag("drawer-pinned-marker", useUnmergedTree = true).assertCountEquals(0)
        val action = compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_chat_actions, titles.last()))
        val actionBounds = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(actionBounds.width >= 48f * density - 1f && actionBounds.height >= 48f * density - 1f)
        saveCapture(dark, scale, "pinned")
        action.performClick()
        compose.onNodeWithText(text(R.string.drawer_unpin)).assertIsDisplayed()
        saveCapture(dark, scale, "menu", popup = true)
        dismissPopupWithBack()
        compose.runOnIdle { history.value = emptyList() }
        assertNoSections()
        saveCapture(dark, scale, "empty")
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.onNodeWithTag("drawer-search-empty").assertIsDisplayed()
        saveCapture(dark, scale, "search-empty")
    }

    private fun dismissPopupWithBack() {
        compose.runOnIdle {
            val popup = WindowInspector.getGlobalWindowViews().last { it.javaClass.simpleName.contains("Popup") }
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    private fun saveCapture(dark: Boolean, scale: Float, state: String, popup: Boolean = false) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val output = TestCaptureDirectories.named("ux30-drawer-${BuildConfig.FLAVOR}")
            val root = if (popup) WindowInspector.getGlobalWindowViews().last { it.javaClass.simpleName.contains("Popup") }
                else compose.activity.window.decorView
            check(root.width > 0 && root.height > 0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val language = compose.activity.resources.configuration.locales[0].language
                val file = File(output, "${BuildConfig.FLAVOR}_${if (dark) "dark" else "light"}_${language}_320dp_font${scale.toInt()}_$state.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX30_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }

    private fun openActions(title: String) = compose.onNodeWithContentDescription(
        compose.activity.getString(R.string.drawer_chat_actions, title)).performClick()

    private fun assertNoSections() {
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        compose.onNodeWithTag("drawer-current-heading").assertDoesNotExist()
    }

    @Composable private fun Content(history: List<RunHistoryItem>, dark: Boolean = false) {
        JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
            ConversationDrawer(history, "new", null, "", false, null, true, {}, {}, {}, {})
        }
    }
}

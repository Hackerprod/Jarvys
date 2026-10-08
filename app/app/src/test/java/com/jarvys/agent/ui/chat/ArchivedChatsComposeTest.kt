package com.jarvys.agent.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import org.junit.Assert.*
import org.junit.After
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
class ArchivedChatsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetScale() { RuntimeEnvironment.setFontScale(1f) }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val saved = RunHistoryItem("archived-record", "Original archived goal", "UNIQUE_RESULT", 7, 9,
        1.0, "archived-session", "Archived chat", archived = true)

    @Test fun archiveShowsOnlyDistinctArchivedTitlesAndOpeningUsesRecordId() {
        val opened = mutableListOf<String>()
        val actions = mutableListOf<ConversationAction>()
        val normal = saved.copy(id = "normal-record", sessionId = "normal-session", title = "Current chat", archived = false)
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(listOf(saved, normal, saved.copy(id = "duplicate")), true,
                onOpenHistory = { opened += it }, onConversationAction = { _, action, _ -> actions += action })
        } }
        compose.onNodeWithTag("archived-chats-list").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_archive_help)).assertIsDisplayed()
        compose.onAllNodesWithText("Archived chat").assertCountEquals(1)
        compose.onNodeWithText("Current chat").assertDoesNotExist()
        compose.onNodeWithText(saved.goal).assertDoesNotExist()
        compose.onNodeWithText(saved.outcome, substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_history_summary, saved.outcome, saved.steps, saved.turns))
            .assertDoesNotExist()
        showTitle("Archived chat").performClick()
        assertEquals(listOf(saved.id), opened)
        assertTrue("Opening an archived record does not implicitly restore it", actions.isEmpty())
    }

    @Test fun restoreUsesSessionIdAndLastArchivedChatBecomesAnHonestEmptyState() {
        val history = mutableStateOf(listOf(saved))
        val actions = mutableListOf<Triple<String, ConversationAction, String?>>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(history.value, true, {}, { session, action, name ->
                actions += Triple(session, action, name)
                if (action == ConversationAction.RESTORE) {
                    history.value = history.value.map { if (it.sessionId == session) it.copy(archived = false) else it }
                }
            })
        } }
        menuAction(R.string.drawer_unarchive)
        assertEquals(listOf(Triple(saved.sessionId, ConversationAction.RESTORE, null)), actions)
        compose.onNodeWithText("Archived chat").assertDoesNotExist()
        compose.onNodeWithTag("archived-chats-empty").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.drawer_no_archived_chats))
        // The source may receive a newly archived chat while this destination remains open.
        compose.runOnIdle { history.value = listOf(saved.copy(title = "Newly archived")) }
        compose.onNodeWithTag("archived-chats-empty").assertDoesNotExist()
        showTitle("Newly archived").assertIsDisplayed()
    }

    @Test fun renameValidatesTrimsAndUpdatesTheSameArchivedConversation() {
        val history = mutableStateOf(listOf(saved))
        val actions = mutableListOf<Triple<String, ConversationAction, String?>>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(history.value, true, {}, { session, action, name ->
                actions += Triple(session, action, name)
                if (action == ConversationAction.RENAME) history.value = history.value.map {
                    if (it.sessionId == session) it.copy(title = name) else it
                }
            })
        } }
        menuAction(R.string.drawer_rename_chat)
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextReplacement("  ")
        compose.onNodeWithText(context.getString(R.string.drawer_name_required)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        input.performTextReplacement("  Updated title  ")
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).performClick()
        assertEquals(listOf(Triple(saved.sessionId, ConversationAction.RENAME, "Updated title")), actions)
        showTitle("Updated title").assertIsDisplayed()
        assertTrue(history.value.single().archived)
    }

    @Test fun cancellingAndConfirmingDeletePreserveTheConfirmationBoundary() {
        val history = mutableStateOf(listOf(saved))
        val actions = mutableListOf<Triple<String, ConversationAction, String?>>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(history.value, true, {}, { session, action, name ->
                actions += Triple(session, action, name)
                if (action == ConversationAction.DELETE) history.value = history.value.filterNot { it.sessionId == session }
            })
        } }
        menuAction(R.string.drawer_rename_chat)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Uncommitted title")
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        assertTrue(actions.isEmpty())
        menuAction(R.string.drawer_delete_action)
        compose.onNodeWithText(context.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        showTitle("Archived chat").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        menuAction(R.string.drawer_delete_action)
        compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).performClick()
        assertEquals(listOf(Triple(saved.sessionId, ConversationAction.DELETE, null)), actions)
        compose.onNodeWithTag("archived-chats-empty").assertIsDisplayed()
    }

    @Test fun managedArchivedChatsRetainDeleteProtection() {
        val record = mutableStateOf(saved.copy(sessionId = ProactiveConversation.SESSION_ID))
        var mutations = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.DARK) {
            ArchivedChatsScreen(listOf(record.value), true, {}, { _, _, _ -> mutations++ })
        } }
        for (managedSession in listOf(ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID)) {
            compose.runOnIdle { record.value = saved.copy(sessionId = managedSession) }
            openMenu()
            compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.drawer_unarchive)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.drawer_archive)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).performClick()
            compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        }
        assertEquals(0, mutations)
    }

    @Test fun disabledActionsCannotMutateButArchivedRecordsRemainReadable() {
        val enabled = mutableStateOf(true)
        val opened = mutableListOf<String>()
        var mutations = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            ArchivedChatsScreen(listOf(saved), enabled.value, { opened += it }, { _, _, _ -> mutations++ })
        } }
        menuAction(R.string.drawer_rename_chat)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Archived chat"))
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).assertDoesNotExist()
        showTitle("Archived chat").performClick()
        assertEquals(listOf(saved.id), opened)
        assertEquals(0, mutations)
    }

    @Test fun lightArchiveRemainsScrollableAndReachableAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.LIGHT)
    @Test fun darkArchiveRemainsScrollableAndReachableAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.DARK)

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun lightSpanishArchiveRemainsReachableAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.LIGHT)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun darkSpanishArchiveRemainsReachableAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.DARK)

    private fun checkLargeText(theme: JarvysThemeMode) {
        RuntimeEnvironment.setFontScale(2f)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            val config = android.content.res.Configuration(resources.configuration).apply { fontScale = 2f }
            @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
        }
        var opened: String? = null
        val title = if (compose.activity.resources.configuration.locales[0].language == "es")
            "Conversación archivada número" else "Archived conversation number"
        val history = (1..24).map { saved.copy(id = "record-$it", sessionId = "session-$it", title = "$title $it",
            timestampSeconds = (25 - it).toDouble()) }
        val visibleHistory = mutableStateOf(history)
        var density = 1f
        var fontScale = 1f
        compose.setContent {
            val originalDensity = LocalDensity.current
            density = originalDensity.density
            fontScale = originalDensity.fontScale
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysOwnTheme(theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ArchivedChatsScreen(visibleHistory.value, true, { opened = it }, { _, _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("The rendered Android content uses 200% font scaling", 2f, fontScale, 0.001f)
        val helperLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.drawer_archive_help))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(helperLayouts) }
        saveCapture(theme, "list")
        assertFalse("Archive instructions wrap without clipping at 200%", helperLayouts.single().hasVisualOverflow)
        val lastTitle = history.last().title!!
        val rowBounds = showTitle(lastTitle).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val listBounds = compose.onNodeWithTag("archived-chats-list").fetchSemanticsNode().boundsInRoot
        assertTrue(rowBounds.left >= listBounds.left && rowBounds.right <= listBounds.right)
        val actions = compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, lastTitle))
        val actionBounds = actions.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Actions remain at least 48dp wide", actionBounds.width >= 48f * density - 1f)
        assertTrue("Actions remain at least 48dp tall", actionBounds.height >= 48f * density - 1f)
        saveCapture(theme, "scrolled")
        showTitle(lastTitle).performClick()
        assertEquals(history.last().id, opened)
        actions.performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_unarchive)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        compose.runOnIdle { visibleHistory.value = emptyList() }
        compose.onNodeWithTag("archived-chats-empty").assertIsDisplayed()
        saveCapture(theme, "empty")
    }

    private fun saveCapture(theme: JarvysThemeMode, state: String) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val output = TestCaptureDirectories.named("ux18-archives-${BuildConfig.FLAVOR}")
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val language = compose.activity.resources.configuration.locales[0].language
                val file = File(output, "${theme.name.lowercase()}_${language}_320dp_font2_$state.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX18_ARCHIVES_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }

    private fun showTitle(title: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("archived-chats-list").performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }

    private fun openMenu() {
        showTitle("Archived chat")
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Archived chat")).performClick()
    }

    private fun menuAction(resource: Int) {
        openMenu()
        compose.onNodeWithText(context.getString(resource)).performClick()
    }
}

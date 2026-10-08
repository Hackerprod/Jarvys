@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.jarvys.agent.crew

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.WorkspaceStore
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ReactionMagnifierShadow
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UX27 presentation coverage. These are real mdpi Activity windows, not fixed-width children
 * inside one larger screenshot. All mission, message, board and clipboard data are local fixtures.
 * Optional captures: JARVYS_UX27_CAPTURE_DIR/<flavor>/<case>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi", shadows = [ReactionMagnifierShadow::class])
class CrewTaskPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val selectionMenu = InstructionsSelectionMenu()
    private var expectedFontScale = 1f

    @Test @Config(qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
    fun width320EnglishLight() = matrixScene(320, "en", false, 1f)
    @Test @Config(qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
    fun width320EnglishDark() = matrixScene(320, "en", true, 1f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun width320SpanishLight() = matrixScene(320, "es", false, 1f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun width320SpanishDark() = matrixScene(320, "es", true, 1f)
    @Test @Config(qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
    fun width320EnglishLargeLight() = matrixScene(320, "en", false, 2f)
    @Test @Config(qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
    fun width320EnglishLargeDark() = matrixScene(320, "en", true, 2f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun width320SpanishLargeLight() = matrixScene(320, "es", false, 2f)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun width320SpanishLargeDark() = matrixScene(320, "es", true, 2f)

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
    fun width360EnglishLight() = matrixScene(360, "en", false, 1f)
    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
    fun width360EnglishDark() = matrixScene(360, "en", true, 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun width360SpanishLight() = matrixScene(360, "es", false, 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun width360SpanishDark() = matrixScene(360, "es", true, 1f)
    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
    fun width360EnglishLargeLight() = matrixScene(360, "en", false, 2f)
    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
    fun width360EnglishLargeDark() = matrixScene(360, "en", true, 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun width360SpanishLargeLight() = matrixScene(360, "es", false, 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun width360SpanishLargeDark() = matrixScene(360, "es", true, 2f)

    @Test @Config(qualifiers = "en-rUS-w412dp-h800dp-port-mdpi")
    fun width412EnglishLight() = matrixScene(412, "en", false, 1f)
    @Test @Config(qualifiers = "en-rUS-w412dp-h800dp-port-mdpi")
    fun width412EnglishDark() = matrixScene(412, "en", true, 1f)
    @Test @Config(qualifiers = "es-rES-w412dp-h800dp-port-mdpi")
    fun width412SpanishLight() = matrixScene(412, "es", false, 1f)
    @Test @Config(qualifiers = "es-rES-w412dp-h800dp-port-mdpi")
    fun width412SpanishDark() = matrixScene(412, "es", true, 1f)
    @Test @Config(qualifiers = "en-rUS-w412dp-h800dp-port-mdpi")
    fun width412EnglishLargeLight() = matrixScene(412, "en", false, 2f)
    @Test @Config(qualifiers = "en-rUS-w412dp-h800dp-port-mdpi")
    fun width412EnglishLargeDark() = matrixScene(412, "en", true, 2f)
    @Test @Config(qualifiers = "es-rES-w412dp-h800dp-port-mdpi")
    fun width412SpanishLargeLight() = matrixScene(412, "es", false, 2f)
    @Test @Config(qualifiers = "es-rES-w412dp-h800dp-port-mdpi")
    fun width412SpanishLargeDark() = matrixScene(412, "es", true, 2f)

    @Test @Config(qualifiers = "es-rES-w320dp-h568dp-port-mdpi")
    fun short320SpanishLargeDarkKeepsUsefulConversationAndFooter() =
        matrixScene(320, "es", true, 2f, height = 568)

    @Test @Config(qualifiers = "es-rES-w320dp-h400dp-port-mdpi")
    fun contentHeight320AtLargeFontRetainsCompactHeaderTabsAndActions() {
        val mission = fixture("es")
        val board = board()
        install(language = "es", dark = true, fontScale = 2f) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(320.dp).testTag("ux27-short-viewport")) {
                    Mission(mission, board)
                }
            }
        }
        assertEquals(320, compose.activity.resources.configuration.screenWidthDp)
        assertEquals(400, compose.activity.resources.configuration.screenHeightDp)
        val viewport = bounds("ux27-short-viewport")
        assertEquals(320f, viewport.width, 1f)
        assertEquals("Exercise actual 320 dp of mission content", 320f, viewport.height, 1f)
        assertEquals("Short-height ellipsis must never mutate stored title", mission.title, text("crew-detail-title"))
        assertEquals(1, layout(node("crew-detail-title")).lineCount)
        val header = bounds("crew-mission-header")
        assertTrue("Short header must leave space for content and controls", header.height <= 160f)
        listOf("crew-detail-title", "crew-mission-status", "crew-orb-captain", "crew-mission-settings").forEach {
            node(it).assertIsDisplayed()
            assertInside(it, bounds(it), header)
        }
        listOf("crew-tab-debate", "crew-tab-board", "crew-tab-bots", "crew-mission-settings",
            "crew-ask-bot", "crew-stop-all").forEach {
            assertTouchTarget(it)
            assertInside(it, bounds(it), viewport)
        }
        assertCompleteTabLabels()
        val conversation = bounds("crew-debate-list")
        assertTrue("A scrollable conversation must remain usable at 320 dp content height", conversation.height >= 48f)
        assertTrue(conversation.bottom <= bounds("crew-ask-bot").top + 1f)
        node("crew-ref-status").assertIsDisplayed()
        assertPromptIsHidden(mission.originalInstructions)
        capture("w320-h400-content320-es-dark-font2-detail")
    }

    @Test fun settingsStackModesAndKeepCodingDetailsFullWidth() {
        val mission = fixture()
        val board = board()
        var mode by mutableStateOf(CrewMode.AUTO)
        val changes = mutableListOf<CrewMode>()
        install(fontScale = 2f) {
            Mission(mission, board, mode = mode, onMode = { mode = it; changes += it })
        }
        node("crew-mode-off").assertDoesNotExist()
        node("crew-mission-settings").performClick()
        assertEquals(2f, layout(compose.onNodeWithText(localized("en", R.string.crew_mode_off),
            useUnmergedTree = true)).layoutInput.density.fontScale, 0.01f)
        val off = bounds("crew-mode-off")
        val auto = bounds("crew-mode-auto")
        val always = bounds("crew-mode-always")
        assertEquals(off.left, auto.left, 1f)
        assertEquals(off.right, auto.right, 1f)
        assertEquals(auto.left, always.left, 1f)
        assertTrue("Mode choices must be stacked", off.bottom <= auto.top && auto.bottom <= always.top)
        listOf("off", "auto", "always").forEach { name ->
            node("crew-mode-$name").performScrollTo()
            assertTouchTarget("crew-mode-$name")
            node("crew-mode-$name").performClick()
        }
        assertEquals(listOf(CrewMode.OFF, CrewMode.AUTO, CrewMode.ALWAYS), changes)
        node("crew-mode-always").assertIsSelected()
        node("crew-view-instructions").performScrollTo().assertIsDisplayed()
        val instructions = bounds("crew-view-instructions")
        node("crew-configure-coding").performScrollTo().assertIsDisplayed()
        val coding = bounds("crew-configure-coding")
        assertEquals("Settings actions share a full-width column", instructions.width, coding.width, 1f)
        assertTrue(coding.width >= 180f)
        capture("settings-en-light-font2")
        node("crew-configure-coding").performClick()
        awaitTag("crew-profile-editor")
        node("crew-profile-read-only").assertIsDisplayed()
        node("crew-profile-save").assertDoesNotExist()
        node("crew-profile-close").performClick()
        node("crew-mission-screen").assertIsDisplayed()
        node("crew-mode-off").assertDoesNotExist()
    }

    @Test fun originalInstructionsAreUnchangedScrollableSelectableAndCopyable() {
        val mission = fixture()
        val original = mission.originalInstructions
        val board = board()
        install { Mission(mission, board) }
        assertPromptIsHidden(original)
        node("crew-mission-settings").performClick()
        node("crew-view-instructions").performScrollTo().performClick()
        assertFullInstructionsAndCopy(original)
        capture("instructions-en-light-font1")
        compose.onNodeWithText(localized("en", R.string.crew_close_panel)).performClick()
        node("crew-instructions-dialog").assertDoesNotExist()
        assertPromptIsHidden(original)
        assertEquals(original, mission.originalInstructions)
    }

    @Test fun botInstructionsUseTheWholeOriginalMissionAndSupportCopy() {
        val mission = fixture()
        val bot = mission.bots.first()
        val board = board()
        install(dark = true) {
            CrewBotDetailScreen(mission, bot.id, board, readOnly = true, onSendMessage = { _, _ -> },
                onRedirect = { _, _ -> }, onStopBot = {}, onBoardReference = {})
        }
        assertPromptIsHidden(bot.mission)
        node("crew-bot-view-instructions").assertIsDisplayed().performClick()
        assertFullInstructionsAndCopy(bot.mission)
        capture("bot-instructions-en-dark-font1")
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h400dp-port-mdpi")
    fun shortSpanishLargeDialogsKeepCloseAndEntireInstructionsAvailable() {
        val mission = fixture("es")
        val board = board()
        install(language = "es", dark = true, fontScale = 2f) { Mission(mission, board) }
        node("crew-mission-settings").performClick()
        val closeLabel = localized("es", R.string.crew_close_panel)
        compose.onNodeWithText(closeLabel).assertIsDisplayed()
        assertEquals(2f, layout(compose.onNodeWithText(closeLabel, useUnmergedTree = true)).layoutInput.density.fontScale, 0.01f)
        node("crew-view-instructions").performScrollTo().assertIsDisplayed().performClick()
        assertFullInstructionsAndCopy(mission.originalInstructions)
        compose.onNodeWithText(closeLabel).assertIsDisplayed()
        assertEquals(2f, layout(compose.onNodeWithText(closeLabel, useUnmergedTree = true)).layoutInput.density.fontScale, 0.01f)
        capture("w320-h400-instructions-es-dark-font2")
        compose.onNodeWithText(closeLabel).performClick()
        node("crew-instructions-dialog").assertDoesNotExist()
        node("crew-mission-settings").assertIsDisplayed()
    }

    @Test fun messagePickerRoutesToTheSelectedBotAndStopRoutesOnce() {
        val mission = fixture(bots = listOf(bot("mara", "Mara"), bot("leo", "Leo")))
        val requests = mutableListOf<Pair<String, String>>()
        var stops = 0
        val board = board()
        install { Mission(mission, board, onAsk = { id, message -> requests += id to message }, onStop = { stops++ }) }
        node("crew-ask-bot").performClick()
        node("crew-pick-bot-mara").assertIsDisplayed()
        node("crew-pick-bot-leo").assertIsDisplayed().performClick()
        assertEquals(listOf("leo" to ""), requests)
        node("crew-pick-bot-leo").assertDoesNotExist()
        node("crew-stop-all").performClick()
        assertEquals(1, stops)
        node("crew-ask-bot").performClick()
        node("crew-pick-bot-mara").performClick()
        assertEquals(listOf("leo" to "", "mara" to ""), requests)
    }

    @Test fun tabsAndStatusReferencesOpenBoardAndBotCallbacks() {
        val mission = fixture()
        val board = board().apply { post(REFERENCE, BOARD_TEXT) }
        val opened = mutableListOf<String>()
        install { Mission(mission, board, onOpen = { opened += it }) }
        node("crew-tab-debate").assertIsSelected()
        node("crew-ref-status").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(BOARD_TEXT, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("crew-tab-board").assertIsSelected()
        node("crew-board-content").assertIsDisplayed()
        compose.onNodeWithText(BOARD_TEXT).assertIsDisplayed()
        node("crew-tab-bots").performClick().assertIsSelected()
        node("crew-bot-row-mara").assertIsDisplayed().performClick()
        assertEquals(listOf("mara"), opened)
        node("crew-tab-debate").performClick().assertIsSelected()
        compose.onNodeWithText(STATUS_TEXT, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun compactStatusRetainsTextReferenceAndOneSenderLabel() {
        val text = "Fixture evidence retained in full, including café, Δ and issue #27."
        val status = message("status", CrewMessage.Type.STATUS, text)
        val finding = message("finding", CrewMessage.Type.FINDING, text)
        val mission = fixture(messages = listOf(finding, status))
        val board = board()
        install { Mission(mission, board) }
        listOf("status", "finding").forEach { id ->
            node("crew-message-$id").assertIsDisplayed()
            compose.onAllNodes(hasText("Mara") and hasAnyAncestor(hasTestTag("crew-message-$id")),
                useUnmergedTree = true).assertCountEquals(1)
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("crew-message-$id")),
                useUnmergedTree = true).assertIsDisplayed()
            node("crew-ref-$id").assertIsDisplayed()
        }
        assertTrue("STATUS must remain more compact than a normal message with identical content",
            bounds("crew-message-status").height < bounds("crew-message-finding").height)
        val statusAvatar = bounds("crew-message-orb-status")
        assertEquals(22f, statusAvatar.width, 1f)
        capture("compact-status-en-light-font1")
    }

    @Test fun denseDebateKeepsRealisticFindingsStatusesAndReferencesReadable() {
        val bots = listOf(bot("mara", "Mara", "DONE"), bot("leo", "Leo", "DONE"), bot("iris", "Iris", "WAITING"))
        fun row(id: String, sender: String, type: CrewMessage.Type, text: String) =
            CrewMessage(id, SESSION, sender, "chief", type, text, listOf(REFERENCE), 200L)
        val messages = listOf(
            row("source", "mara", CrewMessage.Type.FINDING,
                "The primary record lists 14 April. The summary uses 18 April, so those dates must stay distinct."),
            row("comparison", "leo", CrewMessage.Type.FINDING,
                "Both documents describe the same migration. Their dates refer to the review and release respectively."),
            row("review", "iris", CrewMessage.Type.STATUS,
                "Cross-check complete. One dated mismatch remains for the captain to review."),
            row("result", "mara", CrewMessage.Type.RESULT,
                "Keep both dates with their source labels. The evidence supports the release date; the earlier review date is retained as context."),
        )
        val mission = fixture(title = "Review conflicting source evidence", bots = bots, messages = messages)
        val board = board().apply { post(REFERENCE, BOARD_TEXT) }
        install(dark = true) { Mission(mission, board) }
        node("crew-message-result").assertIsDisplayed()
        node("crew-message-review").assertIsDisplayed()
        node("crew-ref-result").assertIsDisplayed()
        assertEquals(mission.title, text("crew-detail-title"))
        assertTouchTarget("crew-ask-bot")
        assertTouchTarget("crew-stop-all")
        capture("w360-h800-en-dark-font1-dense-debate")
    }

    @Test fun completedPillAndDetailShareTitleAndHaveNoStopAction() {
        val mission = fixture(status = "SYNTHESIZED", bots = listOf(bot(status = "DONE")))
        val board = board()
        var detail by mutableStateOf(false)
        install {
            if (detail) Mission(mission, board)
            else CrewMissionCard(mission, { detail = true }, reducedMotionOverride = true)
        }
        node("crew-mission-pill").assertIsDisplayed()
        assertTrue("Completed timeline pill must wrap its content", bounds("crew-mission-pill").height < 200f)
        compose.onNodeWithText("1:33", substring = true, useUnmergedTree = true).assertIsDisplayed()
        val title = text("crew-card-title")
        assertEquals(mission.title, title)
        capture("completed-pill-en-light-font1")
        node("crew-mission-pill").performClick()
        assertEquals(title, text("crew-detail-title"))
        node("crew-stop-all").assertDoesNotExist()
        assertTouchTarget("crew-ask-bot")
        capture("completed-detail-en-light-font1")
    }

    @Test fun anActiveBotKeepsStopAvailableEvenAfterMissionSynthesis() {
        val mission = fixture(status = "SYNTHESIZED", bots = listOf(bot(status = "WAITING")))
        val board = board()
        install { Mission(mission, board) }
        assertTouchTarget("crew-stop-all")
    }

    @Test fun readOnlyMissionKeepsInformationWithoutSendingOrStopping() {
        val mission = fixture()
        val board = board()
        install { Mission(mission, board, readOnly = true) }
        node("crew-mission-settings").assertIsDisplayed()
        node("crew-ask-bot").assertDoesNotExist()
        node("crew-stop-all").assertDoesNotExist()
        node("crew-tab-bots").performClick()
        node("crew-bot-row-mara").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun legacyLongPromptUsesLocalizedFallbackWithoutLosingOriginalText() {
        val original = instructions("es")
        val now = System.currentTimeMillis()
        val mission = CrewMissionSnapshot("legacy", SESSION, "fixture", original, "DONE", "", now - 93_000L, now,
            listOf(bot(status = "DONE")), listOf(message()))
        val board = board()
        var detail by mutableStateOf(false)
        install(language = "es", dark = true, fontScale = 2f) {
            if (detail) Mission(mission, board)
            else CrewMissionCard(mission, { detail = true }, reducedMotionOverride = true)
        }
        val fallback = localized("es", R.string.crew_task_untitled)
        assertEquals(fallback, text("crew-card-title"))
        assertEquals("1:33", text("crew-elapsed"))
        assertPromptIsHidden(original)
        node("crew-mission-card").performClick()
        assertEquals(fallback, text("crew-detail-title"))
        assertPromptIsHidden(original)
        node("crew-stop-all").assertDoesNotExist()
        capture("legacy-fallback-es-dark-font2")
        node("crew-mission-settings").performClick()
        node("crew-view-instructions").performScrollTo().performClick()
        assertEquals(original, text("crew-full-instructions"))
    }

    @Test fun blankAgentTitleUsesEnglishFallbackAndNeverTheInstructions() {
        val mission = fixture(title = "")
        val board = board()
        install { Mission(mission, board) }
        assertEquals(localized("en", R.string.crew_task_untitled), text("crew-detail-title"))
        assertPromptIsHidden(mission.originalInstructions)
    }

    private fun matrixScene(width: Int, language: String, dark: Boolean, fontScale: Float, height: Int = 800) {
        val mission = fixture(language)
        val board = board()
        var detail by mutableStateOf(false)
        install(language, dark, fontScale) {
            if (detail) Mission(mission, board)
            else CrewMissionCard(mission, { detail = true }, reducedMotionOverride = true)
        }
        assertEquals("Robolectric must configure the real window width", width,
            compose.activity.resources.configuration.screenWidthDp)
        assertEquals("Robolectric must configure the real window height", height,
            compose.activity.resources.configuration.screenHeightDp)
        assertEquals(1f, compose.activity.resources.displayMetrics.density, 0.01f)
        assertEquals(width.toFloat(), bounds("ux27-viewport").width, 1f)
        node("crew-card-title").assertIsDisplayed()
        val cardTitle = text("crew-card-title")
        assertEquals(mission.title, cardTitle)
        assertTrue(cardTitle.codePointCount(0, cardTitle.length) <= CrewMissionTitle.MAX_CODE_POINTS)
        val elapsed = text("crew-elapsed").split(':')
        assertEquals("Running fixture should show minutes, not an epoch-sized duration", "1", elapsed.first())
        assertTrue("Allow native-render setup time while retaining a realistic 1:33-ish fixture", elapsed.last().toInt() in 33..59)
        assertPromptIsHidden(mission.originalInstructions)
        capture("w${width}-h${height}-${language}-${if (dark) "dark" else "light"}-font${fontScale.toInt()}-card")
        node("crew-mission-card").performClick()
        assertEquals("Card and detail must use the same display metadata", cardTitle, text("crew-detail-title"))
        val viewport = bounds("ux27-viewport")
        val header = bounds("crew-mission-header")
        assertInside("mission header", header, viewport)
        listOf("crew-detail-title", "crew-mission-status", "crew-orb-captain", "crew-mission-settings").forEach { tag ->
            node(tag).assertIsDisplayed()
            assertInside(tag, bounds(tag), header)
        }
        val title = bounds("crew-detail-title")
        val status = bounds("crew-mission-status")
        val avatar = bounds("crew-orb-captain")
        val settings = bounds("crew-mission-settings")
        assertTrue("Title and status must not overlap", title.bottom <= status.top + 1f)
        assertTrue("Captain avatar must not cover title", avatar.right <= title.left)
        assertTrue("Settings must not cover title", title.right <= settings.left)
        assertTrue("The compact header must not consume the conversation", header.height <= viewport.height * 0.45f)
        assertTrue(layout(node("crew-detail-title")).lineCount <= 2)
        listOf("crew-tab-debate", "crew-tab-board", "crew-tab-bots", "crew-mission-settings",
            "crew-ask-bot", "crew-stop-all").forEach { tag ->
            assertTouchTarget(tag)
            assertInside(tag, bounds(tag), viewport)
        }
        assertCompleteTabLabels()
        val tabs = bounds("crew-tab-debate")
        val conversation = bounds("crew-debate-list")
        val ask = bounds("crew-ask-bot")
        assertTrue(header.bottom <= tabs.top + 1f)
        assertTrue(tabs.bottom <= conversation.top + 1f)
        assertTrue("Useful conversation area must remain visible", conversation.height >= if (height < 600) 96f else 180f)
        assertTrue("Footer must follow the conversation", conversation.bottom <= ask.top + 1f)
        compose.onNodeWithText(STATUS_TEXT, useUnmergedTree = true).assertIsDisplayed()
        node("crew-mode-off").assertDoesNotExist()
        node("crew-configure-coding").assertDoesNotExist()
        assertPromptIsHidden(mission.originalInstructions)
        capture("w${width}-h${height}-${language}-${if (dark) "dark" else "light"}-font${fontScale.toInt()}-detail")
    }

    private fun install(language: String = "en", dark: Boolean = false, fontScale: Float = 1f,
        content: @Composable () -> Unit) {
        // Android Dialog creates another Compose root; LocalDensity alone does not configure it.
        expectedFontScale = fontScale
        RuntimeEnvironment.setFontScale(fontScale)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            val actual = Configuration(resources.configuration).apply {
                setLocales(LocaleList(Locale(language)))
                this.fontScale = fontScale
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(actual, resources.displayMetrics)
        }
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocales(LocaleList(Locale(language)))
            this.fontScale = fontScale
        }
        val context = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
                LocalTextContextMenuToolbarProvider provides selectionMenu) {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        Surface(Modifier.fillMaxSize().testTag("ux27-viewport"), color = MaterialTheme.colorScheme.background) {
                            // Like a timeline item, a card receives zero minimum height and wraps.
                            // Mission screens still fill the available Activity viewport themselves.
                            Box(Modifier.fillMaxSize()) { content() }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Mission(mission: CrewMissionSnapshot, board: CrewBoard, readOnly: Boolean = false,
        mode: CrewMode = CrewMode.AUTO, onMode: (CrewMode) -> Unit = {}, onOpen: (String) -> Unit = {},
        onAsk: (String, String) -> Unit = { _, _ -> }, onStop: () -> Unit = {}) {
        CrewMissionScreen(mission, board, readOnly, onOpen, onAsk, onStop,
            crewMode = mode, onCrewModeChange = onMode)
    }

    private fun fixture(language: String = "en", status: String = "RUNNING",
        title: String = if (language == "es") "Comparar pruebas de la migración cobalto" else "Compare evidence for the cobalt migration",
        bots: List<CrewBotSnapshot> = listOf(bot()), messages: List<CrewMessage> = listOf(message())): CrewMissionSnapshot {
        val now = System.currentTimeMillis()
        return CrewMissionSnapshot("ux27-fixture", SESSION, "fixture", title, instructions(language), CrewMissionTitle.AGENT,
            status, if (status == "SYNTHESIZED") "The fixture evidence agrees." else "", now - 93_000L,
            if (status == "RUNNING") 0L else now, bots, messages)
    }

    private fun bot(id: String = "mara", name: String = "Mara", status: String = "RUNNING") =
        CrewBotSnapshot(id, CrewRoleTemplates.EXPLORER, "Explorer", name, "teal",
            "Archive every original detail exactly.\n\n" + instructions("en"), status, "", "", "",
            listOf("board_read", "msg_send"), 100L, if (status == "DONE") 500L else 0L)

    private fun message(id: String = "status", type: CrewMessage.Type = CrewMessage.Type.STATUS,
        text: String = STATUS_TEXT) = CrewMessage(id, SESSION, "mara", "chief", type, text, listOf(REFERENCE), 200L)

    private fun instructions(language: String): String {
        val first = if (language == "es") "Conserva íntegramente estas instrucciones: café, Δ y 🧭."
            else "Preserve these original instructions exactly: café, Δ and 🧭."
        val paragraph = if (language == "es")
            "Examina las pruebas de cada documento sin inventar conclusiones. Conserva los nombres, las fechas y todas las referencias; explica cualquier discrepancia."
        else "Inspect the evidence in each document without inventing conclusions. Keep every name, date and reference, and explain each discrepancy."
        return first + "\n\n" + (1..8).joinToString("\n\n") { "$it. $paragraph" } + "\n\nEND-OF-ORIGINAL-INSTRUCTIONS"
    }

    private fun board(): CrewBoard {
        // Use the same isolated file-backed fixture as CrewC1ComposeTest without initializing
        // AndroidKeyStore/SkillRepository through the production Context constructor.
        val constructor = WorkspaceStore::class.java.getDeclaredConstructor(File::class.java, String::class.java)
        constructor.isAccessible = true
        return CrewBoard(constructor.newInstance(temporary.newFolder(), WorkspaceStore.projectIdForSession(SESSION)))
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot
    private fun text(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    private fun localized(language: String, resource: Int): String {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList(Locale(language))) }
        return compose.activity.createConfigurationContext(configuration).getString(resource)
    }

    private fun assertPromptIsHidden(original: String) {
        compose.onAllNodesWithText(original, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("END-OF-ORIGINAL-INSTRUCTIONS", substring = true, useUnmergedTree = true).assertCountEquals(0)
        node("crew-full-instructions").assertDoesNotExist()
    }

    private fun assertTouchTarget(tag: String) {
        node(tag).assertIsDisplayed()
        val rect = bounds(tag)
        assertTrue("$tag must be at least 48 dp high, was ${rect.height}", rect.height >= 47.5f)
        assertTrue("$tag must be at least 48 dp wide, was ${rect.width}", rect.width >= 47.5f)
    }

    private fun assertCompleteTabLabels() {
        listOf("debate", "board", "bots").forEach { tab ->
            val label = node("crew-tab-label-$tab").assertIsDisplayed()
            val result = layout(label)
            assertEquals("The tab must use the requested font scale", expectedFontScale, result.layoutInput.density.fontScale, 0.01f)
            assertEquals("The tab label should occupy one line", 1, result.lineCount)
            assertFalse("The $tab tab label must remain fully readable", result.isLineEllipsized(0))
        }
    }

    private fun assertInside(label: String, inner: Rect, outer: Rect) {
        assertTrue("$label is outside its container: $inner / $outer", inner.left >= outer.left - 1f &&
            inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
        assertTrue("$label must have visible content", inner.width > 0f && inner.height > 0f)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertFullInstructionsAndCopy(original: String) {
        node("crew-instructions-dialog").assertIsDisplayed()
        val instructions = node("crew-full-instructions").assertIsDisplayed()
        assertEquals(original, text("crew-full-instructions"))
        assertEquals("Dialog text must really use the requested font scale", expectedFontScale,
            layout(instructions).layoutInput.density.fontScale, 0.01f)
        val scroll = instructions.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long original instructions must be independently scrollable", scroll.maxValue() > 0f)
        val glyph = layout(instructions).getBoundingBox(2).center
        instructions.performTouchInput { longClick(glyph) }
        compose.waitUntil(5_000) { selectionMenu.provider != null }
        compose.runOnIdle { selectionMenu.click(TextContextMenuKeys.SelectAllKey) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { selectionMenu.provider != null }
        compose.runOnIdle { selectionMenu.click(TextContextMenuKeys.CopyKey) }
        compose.waitForIdle()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Selection/copy must preserve the complete original text, including the off-screen end", original,
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    /** Validate native pixels even when capture persistence is disabled. */
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            val root = dialog?.window?.decorView ?: compose.activity.window.decorView
            assertTrue("Native window must be measured", root.width > 0 && root.height > 0)
            assertEquals("Native capture must use the actual requested font scale", expectedFontScale,
                root.resources.configuration.fontScale, 0.01f)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Native output must contain opaque visible pixels", pixels.count { (it ushr 24) > 0 } > 1000)
                assertTrue("Native output must contain drawn text and controls, not a blank surface", pixels.toSet().size > 24)
                System.getenv("JARVYS_UX27_CAPTURE_DIR")?.let { base ->
                    val directory = File(base, BuildConfig.FLAVOR)
                    assertTrue(directory.isDirectory || directory.mkdirs())
                    val file = File(directory, "$name.png")
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    assertTrue("Capture must be a nonempty PNG", file.length() > 1000L)
                }
            } finally { bitmap.recycle() }
        }
    }

    /** Replace toolbar presentation only; pointer selection and Foundation copy remain real. */
    private class InstructionsSelectionMenu : TextContextMenuProvider {
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

    companion object {
        private const val SESSION = "ux27-local-fixture"
        private const val REFERENCE = "/board/evidence.md"
        private const val BOARD_TEXT = "Local fixture evidence with a preserved source reference."
        private const val STATUS_TEXT = "Checking the fixture evidence."
    }
}

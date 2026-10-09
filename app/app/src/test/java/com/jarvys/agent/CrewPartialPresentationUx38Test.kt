package com.jarvys.agent

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewBotDetailScreen
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewMissionNotifier
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.time.Duration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

/**
 * UX38 native Compose coverage using only local mission fixtures. These host-rendered Android
 * windows are not Chromium screenshots or evidence of a run on a physical device.
 * Optional captures: JARVYS_UX38_CAPTURE_DIR/<flavor>/<case>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class CrewPartialPresentationUx38Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun englishPartialAndTimedOutAtNormalFont() = presentationMatrix("en", 1f)
    @Test fun englishPartialAndTimedOutAtLargeFont() = presentationMatrix("en", 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun spanishPartialAndTimedOutAtNormalFont() = presentationMatrix("es", 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun spanishPartialAndTimedOutAtLargeFont() = presentationMatrix("es", 2f)

    private fun presentationMatrix(language: String, fontScale: Float) {
        val context = localized(language, fontScale)
        val board = CrewBoard(WorkspaceStore(temporary.newFolder(), WorkspaceStore.projectIdForSession(SESSION)))
        var status by mutableStateOf("PARTIAL")
        var showDetail by mutableStateOf(false)
        val startedAt = System.currentTimeMillis() - 93_000L
        fun currentMission(): CrewMissionSnapshot {
            val text = context.getString(if (status == "PARTIAL") R.string.agent_provider_partial else R.string.agent_timeout_result)
            return mission(status, listOf(bot("mara", "Mara", status, text, startedAt)), listOf(
                message("completed-list", "mara", CrewMessage.Type.STATUS, "Completed List"),
                message("partial-result", "mara", CrewMessage.Type.RESULT, text),
            ), startedAt, startedAt + 93_000L)
        }
        install(context, fontScale) {
            if (showDetail) CrewBotDetailScreen(currentMission(), "mara", board, readOnly = true,
                onSendMessage = { _, _ -> }, onRedirect = { _, _ -> }, onStopBot = {}, onBoardReference = {})
            else CrewMissionCard(currentMission(), onOpen = { showDetail = true }, reducedMotionOverride = true)
        }

        for (outcome in listOf("PARTIAL", "TIMED_OUT")) {
            compose.runOnIdle { status = outcome; showDetail = false }
            val statusText = context.getString(if (outcome == "PARTIAL") R.string.crew_status_partial else R.string.crew_status_timed_out)
            val expectedBody = context.getString(if (outcome == "PARTIAL") R.string.agent_provider_partial else R.string.agent_timeout_result)
            val prefix = "$language-font${fontScale.toInt()}-${outcome.lowercase(Locale.ROOT)}"

            node("crew-mission-card").assertIsDisplayed()
            node("crew-mission-pill").assertDoesNotExist()
            // The mission header and terminal bot row must agree; old tool activity is history,
            // not the current terminal status presented by the summary card.
            compose.onAllNodesWithText(statusText, useUnmergedTree = true).assertCountEquals(2)
            compose.onNode(hasText(statusText) and hasAnyAncestor(hasTestTag("crew-status-mara")),
                useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodesWithText(statusText, useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { index, _ ->
                assertReadable(compose.onAllNodesWithText(statusText, useUnmergedTree = true)[index], fontScale)
            }
            compose.onNodeWithText("Completed List", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.crew_status_completed_tool, "List"), useUnmergedTree = true)
                .assertDoesNotExist()
            assertNoSuccessOrFailureLabels(context)
            capture("$prefix-card", fontScale)

            node("crew-mission-card").performClick()
            node("crew-bot-detail").assertIsDisplayed()
            compose.onNodeWithText(statusText, useUnmergedTree = true).assertIsDisplayed()
            assertReadable(compose.onNodeWithText(statusText, useUnmergedTree = true), fontScale)
            assertNoSuccessOrFailureLabels(context)
            val result = compose.onNodeWithText(expectedBody, useUnmergedTree = true)
            result.performScrollTo().assertIsDisplayed()
            assertEquals("The accessibility tree must expose the complete localized result", expectedBody,
                result.fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
            val layout = assertReadable(result, fontScale)
            assertTrue("Provider/timeout explanations must wrap at a phone width", layout.lineCount > 1)
            assertEquals("No suffix may disappear from the rendered result", expectedBody.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
            val opposite = localized(if (language == "en") "es" else "en", fontScale)
                .getString(if (outcome == "PARTIAL") R.string.agent_provider_partial else R.string.agent_timeout_result)
            compose.onNodeWithText(opposite, useUnmergedTree = true).assertDoesNotExist()
            capture("$prefix-detail", fontScale)
        }
    }

    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun partialMissionKeepsRunningSiblingActivityAndTimerUntilItActuallyFinishes() {
        val context = localized("es", 2f)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val startedAt = System.currentTimeMillis() - 93_000L
        var running by mutableStateOf(true)
        var finishedAt by mutableStateOf(0L)
        fun snapshot() = mission("PARTIAL", listOf(
            bot("mara", "Mara", "PARTIAL", context.getString(R.string.agent_provider_partial), startedAt),
            bot("nico", "Nico", if (running) "RUNNING" else "DONE", "", startedAt),
        ), listOf(
            message("mara-stale", "mara", CrewMessage.Type.STATUS, "Completed List"),
            message("nico-live", "nico", CrewMessage.Type.STATUS, "Using List"),
        ), startedAt, finishedAt)
        assertTrue("A partial mission is still active while another bot is working", snapshot().active())
        compose.mainClock.autoAdvance = false
        install(context, 2f) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CrewMissionCard(snapshot(), {}, reducedMotionOverride = true)
            }
        }
        settle()
        compose.onNode(hasText(context.getString(R.string.crew_status_partial)) and
            hasAnyAncestor(hasTestTag("crew-status-mara")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.crew_status_using_tool, "List")) and
            hasAnyAncestor(hasTestTag("crew-status-nico")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.crew_status_completed_tool, "List"), useUnmergedTree = true)
            .assertDoesNotExist()
        assertEquals("Ongoing work must retain indeterminate progress", androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
            node("crew-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        val before = elapsedSeconds()
        advance(5_000L)
        assertTrue("Partial mission timer must advance while its sibling is RUNNING", elapsedSeconds() > before)
        capture("es-font2-partial-running-sibling", 2f)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        settle()
        val background = elapsedSeconds()
        advance(5_000L)
        assertEquals("Stopped lifecycle must stop elapsed UI updates", background, elapsedSeconds())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        settle()
        assertTrue("Foregrounding must refresh the still-active timer", elapsedSeconds() > background)

        compose.runOnIdle { running = false; finishedAt = System.currentTimeMillis() }
        settle()
        assertFalse(snapshot().active())
        val terminal = elapsedSeconds()
        advance(5_000L)
        assertEquals("A fully terminal partial mission must retain its final elapsed time", terminal, elapsedSeconds())
        compose.onNode(hasText(context.getString(R.string.crew_status_done)) and
            hasAnyAncestor(hasTestTag("crew-status-nico")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.crew_status_using_tool, "List"), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.crew_status_partial), useUnmergedTree = true).assertCountEquals(2)
        node("crew-mission-pill").assertDoesNotExist()
    }

    @Test fun partialAndTimedOutMissionsNeverPublishSuccessfulCompletionNotification() {
        val context = localized("es", 1f)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.cancelAll()
        val id = "ux38-notification-${System.nanoTime()}"
        val startedAt = System.currentTimeMillis() - 93_000L
        fun snapshot(status: String, botStatus: String = status) = mission(status,
            listOf(bot("mara", "Mara", botStatus, context.getString(R.string.agent_provider_partial), startedAt)),
            emptyList(), startedAt, System.currentTimeMillis(), id)
        try {
            listOf(snapshot("PARTIAL"), snapshot("TIMED_OUT"), snapshot("PARTIAL", "RUNNING")).forEach { partial ->
                assertFalse("${partial.status}/${partial.bots.single().status} must not announce successful completion",
                    CrewMissionNotifier.publishForState(context, partial, false, true))
                assertEquals(0, shadowOf(notifications).allNotifications.size)
            }
            // The same mission must still be eligible for a later genuine synthesis. Returning
            // false for a partial result cannot poison the successful-notification dedupe key.
            assertTrue(CrewMissionNotifier.publishForState(context, snapshot("SYNTHESIZED", "DONE"), false, true))
            val published = shadowOf(notifications).allNotifications.single()
            assertEquals(context.getString(R.string.crew_result_notification_title),
                published.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertFalse(CrewMissionNotifier.publishForState(context, snapshot("SYNTHESIZED", "DONE"), false, true))
        } finally { notifications.cancelAll() }
    }

    private fun localized(language: String, fontScale: Float): Context {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocales(LocaleList(Locale(language)))
            this.fontScale = fontScale
        }
        return compose.activity.createConfigurationContext(configuration)
    }

    private fun install(context: Context, fontScale: Float, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            @Suppress("DEPRECATION")
            resources.updateConfiguration(Configuration(context.resources.configuration), resources.displayMetrics)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
                LocalReducedMotion provides true) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().testTag("ux38-viewport"), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        settle()
    }

    private fun mission(status: String, bots: List<CrewBotSnapshot>, messages: List<CrewMessage>,
        startedAt: Long, finishedAt: Long, id: String = "ux38-presentation") =
        CrewMissionSnapshot(id, SESSION, "fixture", "Review sources", status, "", startedAt, finishedAt, bots, messages)

    private fun bot(id: String, name: String, status: String, result: String, startedAt: Long) =
        CrewBotSnapshot(id, "analista", "Analyst", name, "amber", "Review sources", status, "", result, "",
            emptyList(), startedAt, if (status == "RUNNING") 0L else startedAt + 93_000L)

    private fun message(id: String, from: String, type: CrewMessage.Type, text: String) =
        CrewMessage(id, SESSION, from, "chief", type, text, emptyList(), 100L)

    private fun assertNoSuccessOrFailureLabels(context: Context) {
        listOf(R.string.crew_status_failed, R.string.crew_status_done, R.string.crew_status_synthesized).forEach { resource ->
            compose.onNodeWithText(context.getString(resource), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun assertReadable(node: SemanticsNodeInteraction, fontScale: Float): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().also { layout ->
            assertEquals("The actual text must use the requested font scale", fontScale, layout.layoutInput.density.fontScale, 0.01f)
            assertFalse("Localized text must not overflow its layout", layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) assertFalse("Localized text must not be ellipsized", layout.isLineEllipsized(line))
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun elapsedSeconds(): Long {
        val text = node("crew-elapsed").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val parts = text.split(':')
        return parts[0].toLong() * 60L + parts[1].toLong()
    }
    private fun settle() { compose.mainClock.advanceTimeBy(64L); compose.waitForIdle() }
    private fun advance(millis: Long) {
        // Advance Robolectric's wall clock and Compose's coroutine clock explicitly; no sleeps
        // or real provider waits are needed to exercise lifecycle and elapsed-time behavior.
        compose.runOnIdle { ShadowSystemClock.advanceBy(Duration.ofMillis(millis)) }
        compose.mainClock.advanceTimeBy(millis)
        settle()
    }

    private fun capture(name: String, fontScale: Float) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            assertTrue("Native Activity must have a measured viewport", root.width > 0 && root.height > 0)
            assertEquals(fontScale, root.resources.configuration.fontScale, 0.01f)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Native capture must contain visible text and controls", pixels.toSet().size > 24)
                System.getenv("JARVYS_UX38_CAPTURE_DIR")?.let { base ->
                    val directory = File(base, BuildConfig.FLAVOR)
                    assertTrue(directory.isDirectory || directory.mkdirs())
                    val file = File(directory, "$name.png")
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    assertTrue(file.length() > 1000L)
                }
            } finally { bitmap.recycle() }
        }
    }

    private companion object { const val SESSION = "ux38-local-presentation" }
}

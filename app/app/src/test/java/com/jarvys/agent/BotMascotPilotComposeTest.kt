package com.jarvys.agent

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.jarvys.agent.crew.BotDefinition
import com.jarvys.agent.crew.BotMascotPilotEntry
import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.crew.setBotsTestIdleTimeout
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Actual entry/dialog + private product package verification on Robolectric.
 * No test taps Start, imports a Rive file, creates a worker, or claims Android playback acceptance.
 * Host-only directory fsync adapter prepares fixtures; the UI reads through production store/load.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
class BotMascotPilotComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        effectContext = StandardTestDispatcher(),
    )

    private val context: Context get() = compose.activity.applicationContext
    private var expectedFontScale = 1f

    @Before fun failFastOnHostIdleness() { setBotsTestIdleTimeout(10) }
    @After fun restoreHostPolicy() {
        setBotsTestIdleTimeout(60)
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun source(design: String = "nimbo"): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/bot-mascot-scenes/$design.json"),
    ).use { it.readBytes() }

    private fun profile(id: String = "custom-pilot-${UUID.randomUUID()}") = CrewProfile(
        id, 1, "Private test bot name", "Private test description", "Private instructions must never enter diagnostics.",
        emptyList(), emptyList(), CrewProfile.WorkspaceMode.LEGACY_CHAT,
    )

    private fun savedFixture(): BotDefinition {
        val repository = CrewProfileRepository(context.filesDir)
        val bot = repository.create(profile(), emptyList(), emptyList())
        return assignFixture(bot)
    }

    private fun assignFixture(bot: BotDefinition, design: String = "nimbo"): BotDefinition {
        val store = BotMascotStoreTestSupport.store(context.filesDir)
        val bytes = source(design)
        val prepared = store.prepare(bot.id, "Original cloud visual test fixture", bytes,
            BotMascotStore.CompilerGate { input, token ->
                token.throwIfCancelled()
                BotMascotSceneCompiler.compileProduct(input).bytes
            }, CancellationToken.uncancellable())
        return CrewProfileRepository(context.filesDir).setMascot(bot.id, bot.revision, prepared,
            "pilot-ui-${UUID.randomUUID()}", BotMascotStore.sha256(bytes)).definition
    }

    private fun install(fontScale: Float = 1f, content: @Composable () -> Unit) {
        expectedFontScale = fontScale
        // Dialog creates a separate Android root: actual resources must carry the font scale too.
        RuntimeEnvironment.setFontScale(fontScale)
        listOf(context.resources, compose.activity.resources).distinct().forEach { resources ->
            val configuration = Configuration(resources.configuration).apply { this.fontScale = fontScale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                    LocalReducedMotion provides false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().padding(12.dp).testTag("pilot-test-host")) { content() }
                    }
                }
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun open() {
        awaitTag("mascot-pilot-open")
        compose.onNodeWithTag("mascot-pilot-open").assertIsEnabled().performClick()
        awaitTag("mascot-pilot-screen")
        assertStatic()
    }

    private fun assertNoNativeViews() {
        compose.onNodeWithTag("mascot-pilot-native-0").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-native-1").assertDoesNotExist()
    }

    private fun assertStatic() {
        compose.onNodeWithTag("mascot-pilot-phase")
            .assertTextEquals(compose.activity.getString(R.string.mascot_pilot_phase, "STATIC"))
        compose.onNodeWithTag("mascot-pilot-stop").assertIsNotEnabled()
        assertNoNativeViews()
    }

    @Test fun builtInNewAndMissingDescriptorDoNotExposeEntry() {
        val saved = savedFixture()
        val builtIn = BotDefinition(CrewProfile.codingDefault(), 1, true, true, "")
        val noDescriptor = BotDefinition(profile(), 1, true, false, "")
        install {
            BotMascotPilotEntry(builtIn, isNew = false)
            BotMascotPilotEntry(saved, isNew = true)
            BotMascotPilotEntry(noDescriptor, isNew = false)
        }
        compose.onNodeWithTag("pilot-test-host").assertExists()
        compose.onAllNodesWithTag("mascot-pilot-open").assertCountEquals(0)
        compose.onAllNodesWithTag("mascot-pilot-unavailable").assertCountEquals(0)
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        assertNoNativeViews()
    }

    @Test fun tamperedPackageKeepsGenericFallbackAndNeverOffersOpen() {
        val bot = savedFixture()
        val asset = File(context.filesDir, "bot_mascots/${bot.id}/${bot.mascot.packageRef}/asset.riv")
        val bytes = asset.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        asset.writeBytes(bytes)
        install { BotMascotPilotEntry(bot, isNew = false) }
        awaitTag("mascot-pilot-unavailable")
        compose.onNodeWithTag("mascot-pilot-unavailable")
            .assertTextEquals(compose.activity.getString(R.string.mascot_pilot_unavailable))
        compose.onNodeWithTag("mascot-pilot-open").assertDoesNotExist()
        compose.onNodeWithText(bot.profile.name, substring = true).assertDoesNotExist()
        compose.onNodeWithText("asset.riv", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        assertNoNativeViews()
    }

    @Test fun validSavedCustomOpensStaticAndCloseReopenResetsAllTransientControls() {
        val bot = savedFixture()
        install { BotMascotPilotEntry(bot, isNew = false) }
        open()
        compose.onNodeWithTag("mascot-pilot-start").assertIsEnabled()
        compose.onNodeWithTag("mascot-pilot-reduced").assertIsOn()
        scrollControlIntoView("mascot-pilot-mode-0-7").performClick()
        compose.onNodeWithTag("mascot-pilot-mode-0-7").assertIsSelected()
        compose.onNodeWithTag("mascot-pilot-close").performClick()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        open()
        compose.onNodeWithTag("mascot-pilot-mode-0-0").assertIsSelected()
        compose.onNodeWithTag("mascot-pilot-mode-1-2").assertIsSelected()
        compose.onNodeWithTag("mascot-pilot-reduced").assertIsOn()
        assertStatic()
    }

    @Test fun replacingBotIdentityClosesOldDialogAndRequiresFreshOpen() {
        val first = savedFixture()
        val second = savedFixture()
        val selected = mutableStateOf(first)
        install { BotMascotPilotEntry(selected.value, isNew = false) }
        open()
        compose.runOnIdle { selected.value = second }
        awaitTag("mascot-pilot-open")
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        open()
        assertStatic()
    }

    @Test fun replacingSameBotsMascotDescriptorClosesOldDialogAndResetsTransientMode() {
        val first = savedFixture()
        val replacement = assignFixture(first, design = "folio")
        assertEquals(first.id, replacement.id)
        assertNotEquals(first.mascot.packageRef, replacement.mascot.packageRef)
        val selected = mutableStateOf(first)
        install { BotMascotPilotEntry(selected.value, isNew = false) }
        open()
        scrollControlIntoView("mascot-pilot-mode-0-8").performClick()
        compose.runOnIdle { selected.value = replacement }
        awaitTag("mascot-pilot-open")
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        open()
        compose.onNodeWithTag("mascot-pilot-mode-0-0").assertIsSelected()
        assertStatic()
    }

    @Test fun returningFromBackgroundDoesNotStartDefaultStaticDialog() {
        val bot = savedFixture()
        install { BotMascotPilotEntry(bot, isNew = false) }
        open()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertStatic()
        compose.onNodeWithTag("mascot-pilot-start").assertIsEnabled()
    }

    @Test fun disabledParentActionKeepsVerifiedEntryDisabled() {
        val bot = savedFixture()
        install { BotMascotPilotEntry(bot, isNew = false, enabled = false) }
        awaitTag("mascot-pilot-open")
        compose.onNodeWithTag("mascot-pilot-open").assertIsNotEnabled()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        assertNoNativeViews()
    }

    @Test fun defaultStaticAt320dpAndLargeFontKeepsPreviewPinnedWhileBControlsScroll() {
        val bot = savedFixture()
        install(fontScale = 2f) { BotMascotPilotEntry(bot, isNew = false) }
        open()
        captureStaticDialog("en-light-320dp-font200-static")
        val original = compose.onNodeWithTag("mascot-pilot-previews").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        scrollControlIntoView("mascot-pilot-mode-1-8").performClick()
        val afterScroll = compose.onNodeWithTag("mascot-pilot-previews").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling B controls must not hide or move the pinned previews", original, afterScroll)
        compose.onNodeWithTag("mascot-pilot-mode-1-8").assertIsSelected()
        compose.onNodeWithTag("mascot-pilot-mode-0-0").assertIsSelected()
        assertNoNativeViews()
        captureStaticDialog("en-light-320dp-font200-b-controls")
        compose.onNodeWithTag("mascot-pilot-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
    }

    /**
     * Compose 1.9 performScrollTo loops without a progress bound and launches asynchronous
     * ScrollBy repeatedly. In a separate Dialog root it can restart work before native layout
     * catches up. Issue one measured scroll, explicitly advance its test clock, then verify
     * actual axis progress and full control visibility. Three corrections bound remeasure/
     * clamping/rounding; a stuck UI fails with geometry instead of scheduling until heap OOM.
     */
    private fun scrollControlIntoView(tag: String): SemanticsNodeInteraction {
        val target = compose.onNodeWithTag(tag)
        repeat(3) { correction ->
            val node = target.fetchSemanticsNode()
            val scroller = generateSequence(node.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsActions.ScrollBy) &&
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            } ?: error("$tag has no vertical semantic scroll parent")
            val viewport = scroller.boundsInRoot
            val top = node.positionInRoot.y
            val bottom = top + node.size.height
            val left = node.positionInRoot.x
            val right = left + node.size.width
            val fits = top >= viewport.top - 1f && bottom <= viewport.bottom + 1f &&
                left >= viewport.left - 1f && right <= viewport.right + 1f
            if (fits) return target.assertIsDisplayed()
            assertTrue("$tag is taller than its scroll viewport: ${node.size} / $viewport",
                node.size.height <= viewport.height + 1f)
            val axis = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            val maximum = axis.maxValue()
            assertFalse("Pilot test expects the normal vertical scroll direction", axis.reverseScrolling)
            val wanted = (top + node.size.height / 2f) - viewport.center.y
            val delta = wanted.coerceIn(-before, maximum - before)
            assertTrue("$tag cannot move into view: pass=$correction axis=$before/$maximum " +
                "target=($left,$top,$right,$bottom) viewport=$viewport", abs(delta) >= 0.5f)
            val action = requireNotNull(scroller.config[SemanticsActions.ScrollBy].action)
            println("UX40_PILOT_SCROLL tag=$tag pass=$correction before=$before max=$maximum delta=$delta")
            compose.runOnUiThread { assertTrue("Semantic scroll must accept the request", action(0f, delta)) }
            // Test-clock time, not wall-clock sleep. This is a single finite spring scroll.
            compose.mainClock.advanceTimeBy(2_000)
            settleDialogLayout()
            val updated = target.fetchSemanticsNode()
            val updatedScroller = generateSequence(updated.parent) { it.parent }.first {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }
            val after = updatedScroller.config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("$tag scroll made no progress: pass=$correction before=$before after=$after " +
                "delta=$delta targetTop=${updated.positionInRoot.y}", abs(after - before) >= 0.5f)
        }
        val node = target.fetchSemanticsNode()
        val scroller = generateSequence(node.parent) { it.parent }.first {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        val viewport = scroller.boundsInRoot
        assertTrue("$tag remained clipped after three measured corrections: " +
            "position=${node.positionInRoot} size=${node.size} bounds=${node.boundsInRoot} viewport=$viewport",
            node.positionInRoot.y >= viewport.top - 1f &&
                node.positionInRoot.y + node.size.height <= viewport.bottom + 1f &&
                node.positionInRoot.x >= viewport.left - 1f &&
                node.positionInRoot.x + node.size.width <= viewport.right + 1f)
        return target.assertIsDisplayed()
    }

    /** Two finite Android layout passes, shared by scrolling and host capture. */
    private fun settleDialogLayout() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = requireNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView)
                val density = root.resources.displayMetrics.density
                val width = (root.resources.configuration.screenWidthDp * density).toInt()
                val height = (root.resources.configuration.screenHeightDp * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            compose.waitForIdle()
        }
    }

    /** A real host-window Compose capture; it is explicitly NOT a Rive rendering screenshot. */
    private fun captureStaticDialog(name: String) {
        compose.waitForIdle()
        settleDialogLayout()
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView)
            assertTrue(root.width > 0 && root.height > 0)
            assertEquals(320f, root.width / root.resources.displayMetrics.density, 1f)
            assertEquals(expectedFontScale, root.resources.configuration.fontScale, 0.01f)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Static host window must contain visible text and controls", pixels.toSet().size > 24)
                val directory = TestCaptureDirectories.named("ux40-pilot-static-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(file.length() > 1000L)
                println("UX40_PILOT_STATIC_HOST_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }
}

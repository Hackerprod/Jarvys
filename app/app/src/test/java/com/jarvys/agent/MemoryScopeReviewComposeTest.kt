package com.jarvys.agent

import android.os.Looper
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp")
class MemoryScopeReviewComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeReviewShowsExactContentAndRequiresExplicitConsentBeforeSharing() {
        val stores = stores()
        show(stores)
        review("local.md")
        compose.onNodeWithTag("memory-scope-exact-content").assertTextEquals(LOCAL)
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        consent()
        compose.onNodeWithTag("memory-scope-approve").assertIsEnabled().performClick()
        waitFor { stores.root.listSharedPersonalForUser().count { it.active } == 1 }
        val grant = stores.root.listSharedPersonalForUser().single()
        assertEquals(LOCAL, stores.other.read(grant.path))
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
        compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
    }

    @Test fun cancelAfterConsentClearsApprovalAndReopenRequiresFreshConsent() {
        val stores = stores()
        show(stores)
        review("local.md")
        consent()
        compose.onNodeWithTag("memory-scope-cancel").performClick()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }

    @Test fun backAfterConsentDismissesReviewWithoutSharing() {
        val stores = stores()
        val harness = show(stores)
        review("local.md")
        consent()
        compose.runOnIdle { harness.dispatcher.onBackPressed() }
        compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }

    @Test fun outsideDismissAfterConsentDoesNotShareOrRetainConsent() {
        val stores = stores()
        show(stores)
        review("local.md")
        consent()
        compose.runOnIdle {
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            // Compose 1.9 tracks a complete outside press. ACTION_OUTSIDE alone does not call onDismissRequest.
            val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, -100f, -100f, 0)
            val up = android.view.MotionEvent.obtain(0L, 1L, android.view.MotionEvent.ACTION_UP, -100f, -100f, 0)
            try {
                assertTrue(dialog.dispatchTouchEvent(down))
                assertTrue(dialog.dispatchTouchEvent(up))
            } finally { down.recycle(); up.recycle() }
        }
        compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
    }

    @Test fun closeAfterConsentAndReopenNeverRestoresApproval() {
        val stores = stores()
        val harness = show(stores)
        review("local.md")
        consent()
        compose.onNodeWithTag("memory-scope-close").performClick()
        compose.onNodeWithTag("memory-scope-dialog").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { harness.showing.value = true }
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
    }

    @Test fun lifecycleInterruptionAfterConsentDiscardsReviewWithoutSharing() {
        val stores = stores()
        val harness = show(stores)
        review("local.md")
        consent()
        compose.runOnIdle { harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.onNodeWithTag("memory-scope-dialog").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle {
            harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            harness.showing.value = true
        }
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
    }

    @Test fun navigationAwayAndBackDropsReviewInsteadOfReplayingConsent() {
        val stores = stores()
        val harness = show(stores)
        review("local.md")
        consent()
        compose.runOnIdle { harness.showing.value = false }
        compose.onNodeWithTag("memory-scope-away").assertIsDisplayed()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { harness.showing.value = true }
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
    }

    @Test fun changedSourceCannotBeApprovedFromStaleNativeReview() {
        val stores = stores()
        show(stores)
        review("local.md")
        consent()
        stores.local.writeUserFile("local.md", document("Changed", "Unreviewed replacement"),
            stores.local.latestRevisionId("local.md"), false, SESSION)
        compose.onNodeWithTag("memory-scope-approve").performClick()
        waitForNode("memory-scope-error")
        assertTrue(stores.root.listSharedPersonalForUser().none { it.active })
        compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
        review("local.md")
        compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
    }

    @Test fun repeatedReviewAndApprovalDoNotCreateDuplicateGrants() {
        val stores = stores()
        show(stores)
        review("local.md")
        consent()
        compose.onNodeWithTag("memory-scope-approve").performClick()
        waitFor { stores.root.listSharedPersonalForUser().size == 1 }
        tab("CURRENT_CHAT")
        review("local.md")
        consent()
        compose.onNodeWithTag("memory-scope-approve").performClick()
        waitForNode("memory-scope-status")
        assertEquals(1, stores.root.listSharedPersonalForUser().size)
    }

    @Test fun legacyReadOnlyInspectionAndExplicitSharingKeepOriginalBytesUnchanged() {
        val stores = stores()
        show(stores)
        tab("LEGACY")
        scrollListTo("memory-scope-read-legacy-legacy.md")
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").performClick()
        waitForNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(LEGACY)
        compose.onNodeWithTag("memory-scope-approve").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertArrayEquals(stores.legacyBytes, stores.legacyFile.readBytes())
        compose.onNodeWithTag("memory-scope-close").performClick()
    }

    @Test fun legacyRequiresNativeApprovalAndRemainsByteIdenticalAfterGrantAndRevoke() {
        val stores = stores()
        show(stores)
        tab("LEGACY")
        review("legacy.md")
        compose.onNodeWithTag("memory-scope-exact-content").assertTextEquals(LEGACY)
        consent()
        compose.onNodeWithTag("memory-scope-approve").performClick()
        waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
        val grant = stores.root.listSharedPersonalForUser().single()
        assertTrue(grant.legacy)
        assertNull(grant.sourceConversationId)
        assertEquals(LEGACY, stores.other.read(grant.path))
        scrollListTo("memory-scope-revoke-${grant.id}")
        compose.onNodeWithTag("memory-scope-revoke-${grant.id}").performClick()
        waitFor { stores.root.listSharedPersonalForUser().none { it.active } }
        assertTrue(runCatching { stores.other.read(grant.path) }.isFailure)
        assertArrayEquals(stores.legacyBytes, stores.legacyFile.readBytes())
        scrollListTo("memory-scope-view-${grant.id}")
        compose.onNodeWithTag("memory-scope-view-${grant.id}").performClick()
        waitForNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(LEGACY)
    }

    @Test fun legacyOriginalCanBeReadEvenWhenSharingValidationRejectsItsContent() {
        val stores = stores()
        val sensitiveFixture = document("Unshareable fixture", "password: fixture-credential-value")
        stores.legacyFile.writeText(sensitiveFixture)
        assertTrue(runCatching { stores.root.reviewForSharing("legacy.md") }.isFailure)
        show(stores)
        tab("LEGACY")
        scrollListTo("memory-scope-read-legacy-legacy.md")
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").performClick()
        waitForNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(sensitiveFixture)
        compose.onNodeWithTag("memory-scope-approve").assertDoesNotExist()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }

    @Test fun openingLegacyListDoesNotMigrateOrPublishAnything() {
        val stores = stores()
        show(stores)
        tab("LEGACY")
        scrollListTo("memory-scope-read-legacy-legacy.md")
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").assertIsDisplayed()
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertFalse(File(stores.local.rootDirectory(), "legacy.md").exists())
        assertFalse(File(stores.other.rootDirectory(), "legacy.md").exists())
        assertArrayEquals(stores.legacyBytes, stores.legacyFile.readBytes())
        assertTrue(runCatching { stores.local.read("legacy.md") }.isFailure)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
    fun captureLongNativeReviewAt320DpInEnglish() = captureLongReview("en", 1f)

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun captureLongNativeReviewAt320DpInSpanishWithLargeFont() = captureLongReview("es", 2f)

    private fun captureLongReview(language: String, scale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(scale)
        try {
            val stores = stores()
            val longContent = document("Exact long note", (1..60).joinToString("\n") {
                "Line $it: synthetic personal preference, café y té; review every line."
            } + "\nFINAL LINE: no hidden continuation.")
            stores.local.writeUserFile("local.md", longContent, stores.local.latestRevisionId("local.md"), false, SESSION)
            show(stores)
            review("local.md")
            compose.onNodeWithTag("memory-scope-exact-content").assertTextEquals(longContent)
            compose.onNodeWithTag("memory-scope-approve").assertIsNotEnabled()
            captureNativeDialog("${language}_${scale}_review_top")
            consent()
            compose.onNodeWithTag("memory-scope-consent").assertIsDisplayed()
            compose.onNodeWithTag("memory-scope-approve").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("memory-scope-cancel").assertIsDisplayed()
            captureNativeDialog("${language}_${scale}_review_consent")
            compose.onNodeWithTag("memory-scope-cancel").performClick()
            assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
            compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
        } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }

    /** Synthetic host Android/Compose window rendering; not physical-device acceptance. */
    private fun captureNativeDialog(name: String) {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView)
                val density = root.resources.displayMetrics.density
                val width = (root.resources.configuration.screenWidthDp * density).toInt()
                val height = (root.resources.configuration.screenHeightDp * density).toInt()
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                root.draw(android.graphics.Canvas(bitmap))
                val colors = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Host dialog contains rendered text and controls", colors.toSet().size > 24)
                val directory = TestCaptureDirectories.named("memory-scope-native-${BuildConfig.FLAVOR}")
                val output = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, output)
                output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(output.length() > 1000)
                println("MEMORYSCOPE_NATIVE_HOST_CAPTURE=${output.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Harness {
        val showing: MutableState<Boolean> = mutableStateOf(true)
        val owner = Owner()
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
    }
    private fun show(stores: Stores): Harness {
        val harness = Harness()
        harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        harness.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides harness.owner) {
                MaterialTheme {
                    harness.dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher)
                    if (harness.showing.value) MemoryScopeReviewDialog(
                        store = stores.local, legacyStore = stores.root,
                        onDismiss = { harness.showing.value = false }, onMemoryChanged = {},
                    ) else Text("Another screen", modifier = Modifier.testTag("memory-scope-away"))
                }
            }
        }
        waitForNode("memory-scope-dialog")
        return harness
    }
    private fun review(path: String) {
        scrollListTo("memory-scope-review-$path")
        compose.onNodeWithTag("memory-scope-review-$path").performClick()
        waitForNode("memory-scope-exact-content")
    }
    private fun consent() {
        compose.onNodeWithTag("memory-scope-consent").performScrollTo().performClick()
    }
    private fun tab(value: String) {
        compose.onNodeWithTag("memory-scope-tab-$value").performClick()
    }
    private fun scrollListTo(tag: String) {
        waitFor {
            runCatching {
                compose.onNodeWithTag("memory-scope-list").performScrollToNode(hasTestTag(tag))
                true
            }.getOrDefault(false)
        }
    }
    private fun waitForNode(tag: String) {
        waitFor { runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false) }
    }
    private fun waitFor(block: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            block()
        }
        compose.waitForIdle()
    }
    private data class Stores(val root: MemoryStore, val local: MemoryStore, val other: MemoryStore,
                              val legacyFile: File, val legacyBytes: ByteArray)
    private fun stores(): Stores {
        val dir = Files.createTempDirectory("memory-scope-native-review").toFile()
        val legacy = File(dir, "legacy.md").apply { writeBytes(LEGACY.toByteArray(Charsets.UTF_8)) }
        val root = MemoryStore(dir, true, testMemorySeedProvider(AppLanguageChoice.ENGLISH))
        val local = root.forConversation(SESSION).also {
            it.ensureInitialized()
            it.writeUserFile("local.md", LOCAL, 0L, false, SESSION)
        }
        val other = root.forConversation("unrelated-future-chat").also { it.ensureInitialized() }
        return Stores(root, local, other, legacy, legacy.readBytes())
    }
    companion object {
        private const val SESSION = "review-current-chat"
        private fun document(name: String, body: String) = "---\nname: $name\ndescription: Native review test\n---\n$body\n"
        private val LOCAL = document("Local note", "Use tea with breakfast.\nThis exact second line must also be reviewed.")
        private val LEGACY = document("Legacy original", "An older fact with accents: café y té.\r\nKeep original bytes.  ")
    }
}

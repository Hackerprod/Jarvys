package com.jarvys.agent

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorStateStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.nio.file.Files

/** Real native route: selected-view authority never grants PERSONAL sharing by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
class MemoryScopeReviewComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolate() {
        com.jarvys.agent.apkfactory.FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        compose.activity.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0).edit().clear().commit()
    }
    private fun policy(mode: AutonomyPolicy) = NativeActionPolicy(compose.activity).set(NativeActionPolicy.Operation.MEMORY_SHARE_PERSONAL, mode)
    @Test fun defaultAllowSharesOnlyTheSelectedImmutableVersionWithoutCheckbox() {
        val stores = stores(); show(stores)
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        share("local.md")
        waitFor { stores.root.listSharedPersonalForUser().count { it.active } == 1 }
        val grant = stores.root.listSharedPersonalForUser().single()
        assertEquals(LOCAL, stores.other.read(grant.path))
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
        compose.onNodeWithTag("memory-scope-consent").assertDoesNotExist()
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }
    @Test fun shareDenyBlocksEvenWhenSelectedViewIsAllowed() {
        val stores = stores(); policy(AutonomyPolicy.DENY); show(stores)
        share("local.md")
        waitFor { compose.onAllNodesWithText(compose.activity.getString(R.string.memory_scope_approval_failed)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertFalse(stores.other.compileSystemPromptProjection().contains(LOCAL))
    }
    @Test fun askDisplaysExactScopeRevisionHashAndBytesAndOnlyThenShares() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores)
        share("local.md")
        val dialog = awaitReview()
        val text = viewText(dialog.window!!.decorView)
        assertTrue(text.contains(LOCAL)); assertTrue(text.contains("Revision:")); assertTrue(text.contains("SHA-256:"))
        assertTrue(text.contains(SESSION)); assertTrue(text.contains("PERSONAL"))
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { stores.root.listSharedPersonalForUser().count { it.active } == 1 }
        assertEquals(AutonomyPolicy.ASK, NativeActionPolicy(compose.activity).snapshot(NativeActionPolicy.Operation.MEMORY_SHARE_PERSONAL).policy)
    }
    @Test fun cancelledAskCannotShareAndReopeningRequiresFreshReview() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores)
        share("local.md"); val first = awaitReview()
        compose.runOnIdle { first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        waitFor { !first.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        share("local.md"); val second = awaitReview()
        assertNotSame(first, second)
        compose.runOnIdle { second.cancel() }
        waitFor { !second.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }
    @Test fun changingSourceWhileAskIsPendingNeverSharesNewOrReviewedOldBytes() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores)
        share("local.md"); val dialog = awaitReview()
        stores.local.writeUserFile("local.md", document("Changed", "New unreviewed bytes"), stores.local.latestRevisionId("local.md"), false, SESSION)
        compose.runOnIdle { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { !dialog.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }
    @Test fun liveDenyDuringAskPreventsGrantAndLifecycleDismissalDoesNotApprove() {
        val stores = stores(); policy(AutonomyPolicy.ASK); val showing = show(stores)
        share("local.md"); val dialog = awaitReview()
        policy(AutonomyPolicy.DENY)
        compose.runOnIdle { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { !dialog.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        policy(AutonomyPolicy.ASK)
        share("local.md"); val next = awaitReview()
        compose.runOnIdle { showing.value = false }
        waitNode("away") // Flush the owner composition/disposal before observing its independent Android window.
        waitFor { !next.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }
    @Test fun legacyReadOnlyPreviewAndExplicitGrantPreserveOriginalBytes() {
        val stores = stores(); show(stores)
        tab("LEGACY")
        scrollTo("memory-scope-read-legacy-legacy.md")
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").performClick()
        waitNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(LEGACY)
        assertArrayEquals(stores.legacyBytes, stores.legacyFile.readBytes())
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }
    @Test fun revocationRemovesProjectionAndKeepsSourceIntact() {
        val stores = stores(); show(stores); share("local.md")
        waitFor { stores.root.listSharedPersonalForUser().count { it.active } == 1 }
        val grant = stores.root.listSharedPersonalForUser().single()
        waitNode("memory-scope-dialog"); tab("PERSONAL_SHARED")
        scrollTo("memory-scope-revoke-${grant.id}")
        compose.onNodeWithTag("memory-scope-revoke-${grant.id}").performClick()
        waitFor { !stores.root.listSharedPersonalForUser().single().active }
        assertFalse(stores.other.compileSystemPromptProjection().contains(LOCAL))
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
    }
    @Test fun closeWinsOverShareWaitingForItsFinalStoreAdmission() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores)
        share("local.md"); val review = awaitReview()
        val lock = MemoryStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stores.local) as java.util.concurrent.locks.ReentrantLock
        lock.lock()
        try {
            compose.runOnIdle { review.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            waitFor { !review.isShowing }
            // The worker cannot enter its family-locked final admission until close has cancelled it.
            compose.onNodeWithTag("memory-scope-close").performClick()
        } finally { lock.unlock() }
        waitNode("away")
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
    }
    @Test fun repeatedExplicitAskGrantIsIdempotent() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores)
        repeat(2) {
            share("local.md"); val dialog = awaitReview()
            compose.runOnIdle { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            waitFor { !dialog.isShowing && stores.root.listSharedPersonalForUser().size == 1 }
            waitNode("memory-scope-dialog")
        }
        assertEquals(1, stores.root.listSharedPersonalForUser().size)
    }
    @Test fun legacyGrantRevokeAndHistoricalPreviewPreserveEveryOriginalByte() {
        val stores = stores(); policy(AutonomyPolicy.ASK); show(stores); tab("LEGACY")
        share("legacy.md"); val review = awaitReview()
        assertTrue(viewText(review.window!!.decorView).contains(LEGACY))
        compose.runOnIdle { review.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
        val grant = stores.root.listSharedPersonalForUser().single()
        assertTrue(grant.legacy); assertNull(grant.sourceConversationId)
        assertEquals(LEGACY, stores.other.read(grant.path))
        waitNode("memory-scope-dialog"); tab("PERSONAL_SHARED")
        scrollTo("memory-scope-revoke-${grant.id}")
        compose.onNodeWithTag("memory-scope-revoke-${grant.id}").performClick()
        val revoke = awaitReview()
        compose.runOnIdle { revoke.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { stores.root.listSharedPersonalForUser().none { it.active } }
        assertTrue(runCatching { stores.other.read(grant.path) }.isFailure)
        assertArrayEquals(stores.legacyBytes, stores.legacyFile.readBytes())
        waitNode("memory-scope-dialog"); tab("PERSONAL_SHARED")
        scrollTo("memory-scope-view-${grant.id}")
        compose.onNodeWithTag("memory-scope-view-${grant.id}").performClick()
        waitNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(LEGACY)
    }
    @Test fun unshareableLegacyCanBeReadButNeitherListingNorPreviewMigratesOrPublishes() {
        val stores = stores()
        val sensitive = document("Unshareable fixture", "password: fixture-credential-value")
        stores.legacyFile.writeText(sensitive)
        val before = stores.legacyFile.readBytes()
        assertTrue(runCatching { stores.root.reviewForSharing("legacy.md") }.isFailure)
        show(stores); tab("LEGACY")
        scrollTo("memory-scope-read-legacy-legacy.md")
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertFalse(File(stores.local.rootDirectory(), "legacy.md").exists())
        assertFalse(File(stores.other.rootDirectory(), "legacy.md").exists())
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").performClick()
        waitNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(sensitive)
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertArrayEquals(before, stores.legacyFile.readBytes())
        assertTrue(runCatching { stores.local.read("legacy.md") }.isFailure)
    }
    @Test fun backOutsideCancelAndNavigationReentryRequireANewAsk() {
        val stores = stores(); policy(AutonomyPolicy.ASK); val showing = show(stores)
        share("local.md"); val first = awaitReview()
        compose.runOnIdle {
            first.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            first.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        waitFor { !first.isShowing }
        share("local.md"); val second = awaitReview()
        compose.runOnIdle {
            second.setCanceledOnTouchOutside(true)
            val down = android.view.MotionEvent.obtain(0L, 1L, android.view.MotionEvent.ACTION_DOWN, -100f, -100f, 0)
            val up = android.view.MotionEvent.obtain(0L, 2L, android.view.MotionEvent.ACTION_UP, -100f, -100f, 0)
            try { second.dispatchTouchEvent(down); second.dispatchTouchEvent(up) }
            finally { down.recycle(); up.recycle() }
        }
        waitFor { !second.isShowing }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { showing.value = false }; waitNode("away")
        compose.runOnIdle { showing.value = true }; waitNode("memory-scope-dialog")
        share("local.md"); val third = awaitReview()
        assertNotSame(second, third)
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { third.cancel() }
    }
    @Test fun realPauseCancelsAskAndResumeDoesNotReplayIt() {
        val stores = stores(); policy(AutonomyPolicy.ASK); val showing = show(stores)
        share("local.md"); val old = awaitReview()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(old.isShowing); assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.runOnIdle { showing.value = true }; waitNode("memory-scope-dialog")
        share("local.md"); val fresh = awaitReview()
        assertNotSame(old, fresh); assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        compose.runOnIdle { fresh.cancel() }
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
            val longContent = document("Exact long note", (1..60).joinToString("\n") { "Line $it: synthetic preference, café y té; inspect every line." } + "\nFINAL LINE: no hidden continuation.")
            stores.local.writeUserFile("local.md", longContent, stores.local.latestRevisionId("local.md"), false, SESSION)
            policy(AutonomyPolicy.ASK); show(stores); share("local.md")
            val dialog = awaitReview()
            assertTrue(viewText(dialog.window!!.decorView).contains(longContent))
            captureNativeDialog("${language}_${scale}_review_top")
            compose.runOnIdle {
                fun findScroll(view: View): android.widget.ScrollView? = when (view) {
                    is android.widget.ScrollView -> view
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findScroll(view.getChildAt(it)) }
                    else -> null
                }
                requireNotNull(findScroll(dialog.window!!.decorView)).fullScroll(View.FOCUS_DOWN)
            }
            captureNativeDialog("${language}_${scale}_review_end")
            compose.runOnIdle {
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                assertTrue(positive.isShown && positive.isEnabled && positive.getGlobalVisibleRect(android.graphics.Rect()))
                assertTrue(negative.isShown && negative.getGlobalVisibleRect(android.graphics.Rect()))
                negative.performClick()
            }
            waitFor { !dialog.isShowing }
            assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
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

    private fun show(stores: Stores): androidx.compose.runtime.MutableState<Boolean> {
        val showing = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (showing.value) MemoryScopeReviewDialog(stores.local, stores.root, { showing.value = false }, {})
            else Text("Away", Modifier.testTag("away"))
        } }
        waitNode("memory-scope-dialog"); return showing
    }
    private fun share(path: String) { scrollTo("memory-scope-review-$path"); compose.onNodeWithTag("memory-scope-review-$path").performClick() }
    private fun tab(value: String) { compose.onNodeWithTag("memory-scope-tab-$value").performClick(); waitNode("memory-scope-dialog") }
    private fun scrollTo(tag: String) = waitFor { runCatching { compose.onNodeWithTag("memory-scope-list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
    private fun waitNode(tag: String) = waitFor { runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false) }
    private fun waitFor(block: () -> Boolean) { compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); block() }; compose.waitForIdle() }
    private fun awaitReview(): AlertDialog { waitFor { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }; return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()) }
    private fun viewText(view: View): String = (if (view is TextView) view.text.toString() else "") +
        (if (view is ViewGroup) (0 until view.childCount).joinToString("\n") { viewText(view.getChildAt(it)) } else "")
    private data class Stores(val root: MemoryStore, val local: MemoryStore, val other: MemoryStore, val legacyFile: File, val legacyBytes: ByteArray)
    private fun stores(): Stores {
        val dir = Files.createTempDirectory("memory-native-sharing").toFile()
        val legacy = File(dir, "legacy.md").apply { writeBytes(LEGACY.toByteArray()) }
        val root = MemoryStore(dir, true, testMemorySeedProvider(AppLanguageChoice.ENGLISH))
        val local = root.forConversation(SESSION).also { it.ensureInitialized(); it.writeUserFile("local.md", LOCAL, 0L, false, SESSION) }
        return Stores(root, local, root.forConversation("other").also { it.ensureInitialized() }, legacy, legacy.readBytes())
    }
    companion object {
        private const val SESSION = "review-current-chat"
        private fun document(name: String, body: String) = "---\nname: $name\ndescription: Native review test\n---\n$body\n"
        private val LOCAL = document("Local note", "Use tea with breakfast.\nThis exact second line must also be reviewed.")
        private val LEGACY = document("Legacy original", "An older fact with accents: café y té.\r\nKeep original bytes.  ")
    }
}

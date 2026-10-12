package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import org.robolectric.shadows.ShadowDialog
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryUiProtectionComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolate() {
        com.jarvys.agent.apkfactory.FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        compose.activity.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0).edit().clear().commit()
    }
    private fun policy() = NativeActionPolicy(compose.activity)
    private fun store() = MemoryStore(Files.createTempDirectory("native-view-test").toFile(), true,
        testMemorySeedProvider(AppLanguageChoice.ENGLISH)).forConversation("session")

    @Test fun firstUseDefaultAllowsSelectedSettingsAfterItsAuthorizedFrame() {
        val store = store()
        compose.setContent { MaterialTheme { MemorySettingsScreen(store, true, {}, "session", {}, {}, false, "", 0, false, {}, {}, {}, { _, _ -> }) } }
        waitFor("memory-home-list")
        drawAll()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(MemoryUiAutomationGuard.isAutomationEpochValid(MemoryUiAutomationGuard.captureAutomationEpoch()))
        assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(store.listSharedPersonalForUser().isEmpty())
    }
    @Test fun askProtectsBeforeContentAndDenyNeverInitializesSelectedScope() {
        val store = store()
        policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.DENY)
        compose.setContent { MaterialTheme { MemoryEditorDestination(store, "session", "human.md", false, {}, {}, {}) } }
        compose.waitForIdle()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.onNodeWithTag("memory-editor-name").assertDoesNotExist()
        assertFalse(java.io.File(store.rootDirectory(), "human.md").exists())
    }
    @Test fun askAllowsOnlyReviewedDraftAndNewDraftImmediatelyProtectsCapture() {
        val store = store()
        policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.ASK)
        compose.setContent { MaterialTheme { MemoryEditorDestination(store, "session", "human.md", false, {}, {}, {}) } }
        waitText("Allow once")
        compose.onNodeWithTag("memory-editor-name").assertDoesNotExist()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.onNodeWithText("Allow once").performClick()
        waitFor("memory-editor-name"); drawAll()
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        compose.onNodeWithTag("memory-editor-name").performTextInput(" draft")
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
        assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.onNodeWithTag("memory-editor-name").assertIsDisplayed()
        compose.onNodeWithText("Allow capture of this updated draft once").performClick()
        drawAll()
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun approvedDialogReleasesItsOwnWindowAndPreservesPreexistingActivitySecurity() {
        val store = store()
        compose.runOnIdle { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
        waitFor("memory-scope-dialog"); drawAll()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun revocationAndSelectionTransitionRejectQueuedCaptureBeforeNewContent() {
        val store = store()
        val key = mutableStateOf("first")
        var access: MemoryViewAccess? = null
        compose.setContent { MaterialTheme {
            val selected = memoryViewProtection(store, key.value)
            access = selected
            if (selected != null) Text(key.value, Modifier.testTag("selected"))
        } }
        waitFor("selected"); drawAll()
        val old = MemoryUiAutomationGuard.captureAutomationEpoch()
        compose.runOnIdle { access!!.navigate { key.value = "second" } }
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(old))
        waitFor("selected"); drawAll()
        val current = MemoryUiAutomationGuard.captureAutomationEpoch()
        policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.DENY)
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(current))
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun contentRefreshDoesNotCancelCompoundEffectTokenButNavigationDoes() {
        val store = store()
        var access: MemoryViewAccess? = null
        var token: CancellationToken? = null
        val showing = mutableStateOf(true)
        val recomposeCount = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent { MaterialTheme {
            if (showing.value) {
                token = rememberMemoryEffectToken(store, "home")
                access = memoryViewProtection(store, "home")
                SideEffect { recomposeCount.incrementAndGet() }
                if (access != null) Text("selected", Modifier.testTag("selected"))
            } else Text("away", Modifier.testTag("away"))
        } }
        waitFor("selected"); drawAll()
        val exactAccess = requireNotNull(access); val exactToken = requireNotNull(token)
        val firstCommit = CountDownLatch(1); val continueCommit = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val listener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) {
                if (revision.path == "two/MEMORY.md") { firstCommit.countDown(); check(continueCommit.await(10, TimeUnit.SECONDS)) }
            }
            override fun onMemoryCleared() = Unit
        }
        store.addChangeListener(listener)
        val initialCompositions = recomposeCount.get()
        val activity = compose.activity // ActivityScenario lookup must not wait for the blocked test/UI thread.
        val thread = Thread {
            try { MemoryNativeActions(activity).run(NativeActionPolicy.Operation.MEMORY_MODIFY, store, listOf("two/levels"), exactToken,
                { exactAccess.requireEffectSelection() }) { store.createDirectoryForUser("two/levels", "session") } }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            thread.start(); assertTrue("First commit did not arrive; worker failure=${failure.get()}", firstCommit.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); recomposeCount.get() > initialCompositions }
            assertFalse(exactToken.isCancellationRequested)
            continueCommit.countDown(); thread.join(5_000)
            assertNull(failure.get())
            assertTrue(java.io.File(store.rootDirectory(), "two/levels/MEMORY.md").exists())
            compose.runOnIdle { showing.value = false }
            waitFor("away"); drawAll()
            assertTrue(exactToken.isCancellationRequested)
        } finally { continueCommit.countDown(); thread.join(5_000); store.removeChangeListener(listener) }
    }
    @Test fun detachedDestroyedWindowReleasesGuard() {
        policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.ASK)
        val store = store()
        compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
        waitText("Allow once"); assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.activityRule.scenario.close(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun simultaneousCaptureAndDurablePreferenceEditsDoNotDeadlock() {
        val store = store()
        compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
        waitFor("memory-scope-dialog"); drawAll()
        val finished = CountDownLatch(1)
        val thread = Thread {
            try { repeat(100) { runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() } } }
            finally { finished.countDown() }
        }
        thread.start()
        compose.runOnIdle {
            repeat(20) { policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, if (it % 2 == 0) AutonomyPolicy.DENY else AutonomyPolicy.ALLOW) }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS)); thread.join(5_000)
        assertFalse(thread.isAlive)
    }
    @Test fun taintedAskNeverBecomesHumanConsentAfterGestureEndsAndRequiresReentry() {
        policy().set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.ASK)
        val store = store(); val showing = mutableStateOf(true)
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var gesture: MemoryUiAutomationGuard.Action
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { gesture = MemoryUiAutomationGuard.beginAsyncAction(epoch) })
        try {
            compose.setContent { MaterialTheme {
                if (showing.value) MemoryScopeReviewDialog(store, null, { showing.value = false }, {})
                else Text("away", Modifier.testTag("away"))
            } }
            waitText("Finish the interaction and reopen this review manually")
            gesture.close()
            compose.onNodeWithTag("memory-scope-dialog").assertDoesNotExist()
            assertTrue(MemoryUiAutomationGuard.isProtected())
            compose.onNodeWithText("Close").performClick(); waitFor("away"); drawAll()
            compose.runOnIdle { showing.value = true }
            waitText("Allow once")
            compose.onNodeWithText("Allow once").performClick()
            waitFor("memory-scope-dialog"); drawAll()
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertTrue(store.listSharedPersonalForUser().isEmpty())
        } finally { gesture.close() }
    }
    private fun waitText(text: String) = compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); runCatching { compose.onNodeWithText(text).fetchSemanticsNode(); true }.getOrDefault(false) }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false) }; compose.waitForIdle() }
    private fun drawAll() {
        compose.runOnIdle {
            val roots = listOfNotNull(compose.activity.window.decorView, ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView)
            roots.forEach { root ->
                val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try { root.viewTreeObserver.dispatchOnDraw(); root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
    }
}

package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryUiProtectionComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun standaloneSettingsProtectsBeforeDisplayingAnyMemory() {
        val store = store()
        compose.setContent {
            MaterialTheme {
                MemorySettingsScreen(store, true, {}, SESSION, {}, {}, false, "", 0, false,
                    {}, {}, {}, { _, _ -> })
            }
        }
        waitFor("memory-home-list")
        assertProtected()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun standaloneEditorProtectsWithoutNavigationHost() {
        val store = store()
        compose.setContent {
            MaterialTheme { MemoryEditorDestination(store, SESSION, "human.md", false, {}, {}, {}) }
        }
        waitFor("memory-editor-name")
        assertProtected()
    }

    @Test fun standaloneHistoryProtectsWithoutNavigationHost() {
        val store = store()
        compose.setContent { MaterialTheme { MemoryHistoryDestination(store, SESSION, {}, {}) } }
        compose.waitForIdle()
        assertProtected()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun standaloneReviewProtectsAndSecuresItsOwnDialogWindow() {
        val store = store()
        compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
        waitFor("memory-scope-dialog")
        assertProtected()
        assertTrue(ShadowDialog.getLatestDialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun automationInFlightCannotExposeReviewOrBecomeHumanConsentAfterCompletion() {
        val store = store()
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        var pendingAction: MemoryUiAutomationGuard.Action? = null
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            pendingAction = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        })
        try {
            compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
            waitFor("memory-ui-protected")
            assertProtected()
            compose.onNodeWithTag("memory-scope-dialog").assertDoesNotExist()
            compose.onNodeWithTag("memory-scope-exact-content").assertDoesNotExist()
            compose.onNodeWithTag("memory-scope-consent").assertDoesNotExist()
            compose.onNodeWithTag("memory-scope-approve").assertDoesNotExist()
            pendingAction!!.close()
            compose.waitForIdle()
            compose.onNodeWithTag("memory-ui-protected").assertIsDisplayed()
            compose.onNodeWithTag("memory-scope-approve").assertDoesNotExist()
            assertTrue(store.listSharedPersonalForUser().isEmpty())
        } finally { pendingAction?.close() }
    }

    @Test fun closingTaintedSurfaceRequiresSafeDrawAndManualReentry() {
        val store = store()
        val showing = mutableStateOf(true)
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        var pendingAction: MemoryUiAutomationGuard.Action? = null
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            pendingAction = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        })
        try {
            compose.setContent {
                MaterialTheme {
                    if (showing.value) MemoryScopeReviewDialog(store, null, { showing.value = false }, {})
                    else Text("Safe screen", modifier = Modifier.testTag("memory-safe-replacement"))
                }
            }
            waitFor("memory-ui-protected")
            pendingAction!!.close()
            compose.onNodeWithTag("memory-ui-protected-close").performClick()
            waitFor("memory-safe-replacement")
            safeReplacementDraw()
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            compose.runOnIdle { showing.value = true }
            waitFor("memory-scope-dialog")
            assertProtected()
            assertTrue(store.listSharedPersonalForUser().isEmpty())
        } finally { pendingAction?.close() }
    }

    @Test fun preexistingSecureWindowFlagIsPreservedAfterMemoryCloses() {
        val store = store()
        val showing = mutableStateOf(true)
        compose.runOnIdle { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent {
            MaterialTheme {
                if (showing.value) MemoryScopeReviewDialog(store, null, { showing.value = false }, {})
                else Text("Safe screen", modifier = Modifier.testTag("memory-safe-replacement"))
            }
        }
        waitFor("memory-scope-dialog")
        compose.onNodeWithTag("memory-scope-close").performClick()
        waitFor("memory-safe-replacement")
        safeReplacementDraw()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun detachedDestroyedWindowReleasesGuardWithoutWaitingForImpossibleDraw() {
        val store = store()
        compose.setContent { MaterialTheme { MemoryScopeReviewDialog(store, null, {}, {}) } }
        waitFor("memory-scope-dialog")
        assertProtected()
        compose.activityRule.scenario.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    private fun assertProtected() {
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
    }

    /** Drive the real window draw lifecycle, then run the posted release after replacement pixels. */
    private fun safeReplacementDraw() {
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                root.viewTreeObserver.dispatchOnDraw()
                root.draw(Canvas(bitmap))
            } finally { bitmap.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private fun store(): MemoryStore = MemoryStore(Files.createTempDirectory("native-memory-guard").toFile(), true,
        testMemorySeedProvider(AppLanguageChoice.ENGLISH)).forConversation(SESSION).also { it.ensureInitialized() }

    companion object { private const val SESSION = "native-memory-guard-session" }
}

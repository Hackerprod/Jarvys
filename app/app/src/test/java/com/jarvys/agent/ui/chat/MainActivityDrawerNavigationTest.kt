package com.jarvys.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native pointer-input coverage of the real Activity: the drawer is a profile entry plus Settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MainActivityDrawerNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val secretSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }

    private val fixtures = object : ExternalResource() {
        override fun before() {
            // Robolectric supplies a new Application for each test; the Android factory is static.
            ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
                .apply { isAccessible = true }.set(null, null)
            secretSingleton.set(null, SecretStore(context.getSharedPreferences("s2-navigation-secrets", 0)))
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            chatTestStore.onDatabaseWorker { FreshStorageBootstrap.awaitReadyBlocking(context.filesDir) }
            // Retain the current reflection disclosure fixture; legacy file-memory disclosure no longer exists.

            MemoryReflectionPreferences(context).markDisclosureShown()
        }

        override fun after() {
            secretSingleton.set(null, null)
            WorkManagerTestCleanup.close(context)
        }
    }

    private val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(com.jarvys.agent.ExistingUserSetupWizardRule())
        .around(chatTestStore).around(fixtures).around(compose)

    @Test fun drawerOffersOnlyTheProfileHeaderAndSettings() {
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
        openDrawer()
        compose.onNodeWithTag("drawer-profile-header").assertIsDisplayed()
        compose.onNodeWithTag("drawer-profile-name", useUnmergedTree = true).assertTextEquals(context.getString(R.string.app_name))
        compose.onNodeWithTag("drawer-open-settings").assertIsDisplayed()
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks", "drawer-open-central",
            "drawer-open-workspaces", "drawer-search-toggle", "drawer-archive-toggle", "conversation-drawer-scroll")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText(context.getString(R.string.drawer_archived_chats)).assertDoesNotExist()
    }

    @Test fun profileHeaderOpensMemoryAndBackReturnsToTheConversation() {
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
        openDrawer()
        tapTag("drawer-profile-header")
        awaitDrawerClosed()
        awaitRoute(AppNavigationBackPolicy.MEMORY)
        systemBack()
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
    }

    @Test fun settingsReachesScheduledTasksAndMemory() {
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
        openDrawer()
        tapTag("drawer-open-settings")
        awaitDrawerClosed()
        awaitRoute(AppNavigationBackPolicy.SETTINGS)
        compose.onNodeWithTag("settings-bots-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-scheduled-tasks-row").performScrollTo().performTouchInput { click() }
        awaitRoute(AppNavigationBackPolicy.TASKS)
        systemBack()
        awaitRoute(AppNavigationBackPolicy.SETTINGS)
        compose.onNodeWithText(context.getString(R.string.memory_title)).performScrollTo().performTouchInput { click() }
        awaitRoute(AppNavigationBackPolicy.MEMORY)
    }

    private fun navigation(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(compose.activity) as NavHostController

    private fun awaitRoute(route: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            runCatching { navigation().currentDestination?.route == route }.getOrDefault(false)
        }
        settleNativeFrame()
    }

    private fun tapTag(tag: String) {
        settleNativeFrame()
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun awaitDrawerClosed() {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            runCatching { compose.onNodeWithTag("drawer-profile-header").assertIsNotDisplayed() }.isSuccess
        }
        settleNativeFrame()
    }

    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openDrawer() {
        settleNativeFrame()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-profile-header").assertIsDisplayed()
    }

    /** Compose idleness alone can precede the Android window's layout/draw and pointer hit targets. */
    private fun settleNativeFrame() {
        compose.waitForIdle()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = compose.activity.window.decorView
                val metrics = root.resources.displayMetrics
                val config = root.resources.configuration
                val width = (config.screenWidthDp * metrics.density).toInt()
                val height = (config.screenHeightDp * metrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
            compose.waitForIdle()
        }
    }
}

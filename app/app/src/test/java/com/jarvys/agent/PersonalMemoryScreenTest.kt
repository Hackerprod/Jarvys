package com.jarvys.agent.memory

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalMemoryScreenTest {
    private val chatStore = ChatTestStore(runTestOnWorker = false)
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(chatStore).around(compose)

    @Test fun blankDatabaseShowsEmptyProfileAndUserCanSaveItFromTheNewScreen() {
        val files = chatStore.newFilesDirectory("personal-memory-screen")
        chatStore.localRunStore(files)
        val context = TestFilesContext(ApplicationProvider.getApplicationContext(), files)
        chatStore.onDatabaseWorker {
            check(ChatDatabaseBridge.getInstance(context).ensureConversationSync("memory-ui-session") is ChatDbResult.Success)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { PersonalMemoryScreen("memory-ui-session") }
            }
        }

        waitFor("memory-empty")
        compose.onNodeWithTag("memory-empty").assertIsDisplayed()
        compose.onNodeWithTag("memory-add-profile").performClick()
        compose.onNodeWithTag("memory-profile-value").performTextInput("The user's nickname is Hackerprod.")
        compose.onNodeWithTag("memory-save").performClick()
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithText("The user's nickname is Hackerprod.").assertIsDisplayed(); true }.getOrDefault(false)
        }
        waitFor("memory-notice")
        val persisted = chatStore.onDatabaseWorker {
            ChatDatabaseBridge.getInstance(context).personalAgentBrowse()
        }
        assertEquals("The user's nickname is Hackerprod.", persisted.single().text)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithTag(tag).assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private class TestFilesContext(base: Context, private val files: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
    }
}

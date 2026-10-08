package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfers
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** End-to-end native gesture path: persisted chat image -> viewer -> Save -> Downloads -> explicit Open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MainActivityImageDownloadGestureTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val session = "image-gesture-${UUID.randomUUID()}"
    private lateinit var event: AgentRunUiEvent
    private lateinit var bytes: ByteArray
    private lateinit var provider: FakeDownloadsProvider
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val secrets = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private val fixtures = object : ExternalResource() {
        override fun before() {
            ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
                .apply { isAccessible = true }.set(null, null)
            secrets.set(null, SecretStore(context.getSharedPreferences("ux16-gesture-secrets", 0)))
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            provider = FakeDownloadsProvider.install()
            val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            image.recycle()
            val path = GeneratedImageStore(context).save(session, UUID.randomUUID().toString(), bytes)
            val store = LocalRunStore(context)
            store.appendConversationMessage(session, "user", "Create a blue image for my notes")
            store.appendGeneratedImageEvent(session, path, "Blue image", null, "64x48", "image/png")
            event = store.readConversationTimeline(session).single { it.kind == "generated_image" }
            context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).edit().putString("active_session_id", session).commit()
        }
        override fun after() {
            secrets.set(null, null)
            WorkManagerTestCleanup.close(context)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun realImageViewerSaveGestureExportsAndOpenIsOnlyLaunchedAfterTheOpenGesture() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("generated-image-open-${event.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("generated-image-open-${event.id}").performTouchInput { click() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("generated-image-viewer-image").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("generated-image-save").assertIsDisplayed().performTouchInput { click() }
        val vm = ViewModelProvider(compose.activity)[ChatFileTransfers::class.java]
        val request = requireNotNull(ChatFileRequest.generated(session, event))
        assertEquals(context.filesDir, vm.getApplication<android.app.Application>().filesDir)
        compose.runOnIdle { println("UX16_SAVE_GESTURE transfers=${vm.transfers.value}; request=$request; state=${AgentRunUiState.state.value.sessionId}") }
        try { compose.waitUntil(10_000) { vm.transfers.value[request.key]?.saved == true } }
        catch (failure: Throwable) {
            throw AssertionError("Gesture export did not finish: ${vm.transfers.value}; notice=${vm.notice.value}; state=${AgentRunUiState.state.value.sessionId}", failure)
        }
        compose.onNodeWithTag("chat-download-result").assertIsDisplayed()
        assertEquals(1, provider.inserts)
        assertEquals(1, provider.publishes)
        assertArrayEquals(bytes, provider.file(provider.rows.keys.single()).readBytes())
        assertNull("The Save gesture must not launch SAF or another application", Shadows.shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("chat-download-open").performTouchInput { click() }
        val opened = Shadows.shadowOf(compose.activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(vm.transfers.value[request.key]!!.result!!.uri, opened.data)
        assertEquals(0, opened.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(session, context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).getString("active_session_id", null))
    }
}

package com.jarvys.agent.ui.chat

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.View
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.TestCaptureDirectories
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Private, local fixtures consumed by the production attachment store and preview decoder. */
internal class ReactionAttachmentFixtures(base: Context) {
    private val directory = TestCaptureDirectories.create("ux14-attachment-fixtures")
    val session: String = "reaction-${UUID.randomUUID()}"
    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
    val store = AttachmentStore(context)

    fun image(name: String): ChatAttachment {
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val bytes = try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb(40 + x, 70 + y, 200))
            }
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        return store.copyFromStream(session, name, "image/png", ChatAttachment.Kind.IMAGE, ByteArrayInputStream(bytes))
    }

    fun document(name: String): ChatAttachment = store.copyFromStream(session, name, "text/plain",
        ChatAttachment.Kind.FILE, ByteArrayInputStream("Local review notes".toByteArray())).also { attachment ->
        // File controls require persisted conversation ownership, not just copied bytes.
        val history = LocalRunStore(context)
        history.appendConversationMessage(session, "user", "Attached review notes", listOf(attachment))
        check(history.findChatFile(session, "attachment", attachment.id) == attachment)
    }
}

/** Plain paragraphs also appear in the async parser's 16 sp fallback. Wait for the real 14 sp body. */
internal fun awaitReactionMarkdownText(
    compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    text: String,
): SemanticsNodeInteraction {
    compose.waitUntil(5_000) {
        val matches = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        if (matches.size != 1) false else {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.singleOrNull()?.layoutInput?.style?.fontSize?.value == 14f
        }
    }
    compose.waitForIdle()
    return compose.onNodeWithText(text, useUnmergedTree = true)
}

/** Ownership resolves on IO before file action buttons change the card's geometry.
 * Wait for that real readiness signal before comparing a badge snapshot with its parent row.
 * The geometry assertions remain unchanged and still fail on overlap or extra padding.
 */
internal fun awaitReactionFileReady(
    compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    attachmentId: String,
) {
    compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("chat-file-download-$attachmentId").fetchSemanticsNodes()
            .singleOrNull()?.config?.contains(SemanticsActions.OnClick) == true
    }
    awaitReactionDrawIdle(compose)
}

/** Flushes actual View layout/draw after Compose settles; captures remain host-rendered native pixels. */
internal fun awaitReactionDrawIdle(compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    compose.waitForIdle()
    repeat(2) {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val metrics = root.resources.displayMetrics
            val configuration = root.resources.configuration
            val width = (configuration.screenWidthDp * metrics.density).toInt()
            val height = (configuration.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        compose.waitForIdle()
    }
}

/** Only magnifier presentation is replaced: real Compose pointer selection, handles and copy remain. */
@Implements(value = Magnifier::class, callThroughByDefault = false)
class ReactionMagnifierShadow {
    @Implementation fun getWidth(): Int = 100
    @Implementation fun getHeight(): Int = 40
    @Implementation fun getSourceWidth(): Int = 100
    @Implementation fun getSourceHeight(): Int = 40
    @Implementation fun getZoom(): Float = 1f
    @Implementation fun getPosition(): Point = Point()
    @Implementation fun getSourcePosition(): Point = Point()
    @Implementation fun show(x: Float, y: Float) = Unit
    @Implementation fun show(x: Float, y: Float, magnifierX: Float, magnifierY: Float) = Unit
    @Implementation fun setZoom(zoom: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

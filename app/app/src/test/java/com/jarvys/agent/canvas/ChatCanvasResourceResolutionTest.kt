package com.jarvys.agent.canvas

import android.os.Looper
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.PresentationArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatCanvasResourceResolutionTest {
    @Test
    fun resourceResolverDeniesOutOfScopeReferencesAndBridgeRevalidatesOnMainThread() {
        val sessionId = "active-conversation"
        val attachment = imageAttachment()
        val artifact = PresentationArtifact(
            id = "artifact-21",
            artifactType = "image/png",
            moduleId = "chart",
            name = "chart.png",
            title = null,
            mimeType = "image/png",
            sizeBytes = 1,
            relativePath = null,
            sha256 = null,
            manifestJson = null,
            thumbnailRef = "expected-thumb",
            storageKind = "DELIVERED",
        )
        val event = AgentRunUiEvent.messageEvent(21, "user", "photo", 1)
            .copy(attachments = listOf(attachment), presentationArtifact = artifact)
        val events = listOf(event)
        val ownedSessions = mutableListOf<String>()
        fun resolve(
            path: String,
            currentEvents: List<AgentRunUiEvent> = events,
            thumbnailRef: String? = "expected-thumb",
            attachmentOwnerSession: String = sessionId,
        ) = resolveCanvasConversationResourceRoute(
            path = path,
            sessionId = sessionId,
            events = currentEvents,
            attachmentIsOwned = { session, candidate ->
                ownedSessions += session
                session == attachmentOwnerSession && candidate.id == attachment.id
            },
            generatedImageIsOwned = { _, _ -> true },
            findThumbnailReference = { _, _ -> thumbnailRef },
            thumbnailReferenceId = { it },
        )

        assertTrue(resolve("/chat-assets/message/21/0") is CanvasConversationResourceRoute.MessageAttachment)
        assertEquals(listOf(sessionId), ownedSessions)
        assertNull(resolve("/chat-assets/../message/21/0"))
        assertFalse(isCanvasRequestInOrigin("https", "other.invalid", -1, null))
        assertFalse(isCanvasRequestInOrigin("https", "jarvys-chat-canvas.invalid", -1, "download=1"))
        assertNull(resolve("/chat-assets/message/22/0"))
        assertNull(resolve("/chat-assets/message/21/0", attachmentOwnerSession = "another-conversation"))
        assertNull(resolve("/chat-assets/artifact/21/thumbnail", thumbnailRef = "another-thumb"))

        var currentEvents = listOf(
            AgentRunUiEvent.messageEvent(31, "assistant", "latest", 2).copy(messageId = "message-31"),
        )
        var running = false
        var callbackCount = 0
        var callbackOnMain = false
        val bridge = ChatCanvasNativeBridge(
            sessionId = sessionId,
            currentEvents = { currentEvents },
            running = { running },
            allowRetry = { true },
            hasOlder = { false },
            loadingOlder = { false },
            benchmarkStartedAtNanos = null,
            callbacks = ChatCanvasCallbacks(action = { _, _, _ ->
                callbackCount++
                callbackOnMain = Looper.myLooper() == Looper.getMainLooper()
            }),
        )
        bridge.dispatch("retry", 31, "")
        running = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The main-thread recheck must reject a now-running turn", 0, callbackCount)

        running = false
        bridge.dispatch("retry", 31, "")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbackCount)
        assertTrue("Dispatch callback runs on the main thread", callbackOnMain)
    }

    private fun imageAttachment(): ChatAttachment {
        val id = UUID.randomUUID().toString()
        return ChatAttachment(id, "image.png", "image/png", 1, ChatAttachment.Kind.IMAGE, "$id-image.png")
    }
}

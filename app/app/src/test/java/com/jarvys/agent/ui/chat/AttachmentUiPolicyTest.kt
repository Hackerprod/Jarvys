package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ChatAttachment
import org.junit.Assert.*
import org.junit.Test

class AttachmentUiPolicyTest {
    private val attachmentId = "01234567-89ab-cdef-0123-456789abcdef"
    private val attachment = ChatAttachment(attachmentId, "photo.png", "image/png", 10, ChatAttachment.Kind.IMAGE, "$attachmentId-photo.png")
    private fun pending() = PendingChatAttachment("draft", "photo.png", attachment = attachment)

    @Test fun attachmentOnlyMessageCanSendButIncompleteCopiesCannot() {
        assertFalse(canSendWithAttachments(" ", emptyList()))
        assertTrue(canSendWithAttachments("hello", emptyList()))
        assertTrue(canSendWithAttachments("", listOf(pending())))
        assertFalse(canSendWithAttachments("hello", listOf(pending().copy(copying = true))))
        assertFalse(canSendWithAttachments("hello", listOf(pending().copy(error = "failed"))))
        assertFalse(canSendWithAttachments("hello", listOf(pending().copy(attachment = null))))
    }
    @Test fun attachmentsAreShownOnlyForOrdinaryUserMessages() {
        assertEquals(1, AgentRunUiEvent(1, "user", text = "").copyAttachments(listOf(attachment)).attachments.size)
        assertTrue(AgentRunUiEvent(1, "assistant", text = "").copyAttachments(listOf(attachment)).attachments.isEmpty())
        assertTrue(AgentRunUiEvent(1, "user", text = "", proactiveThreadKey = "system").copyAttachments(listOf(attachment)).attachments.isEmpty())
    }
    @Test fun panIsClampedToZoomedContentAndResetAtFit() {
        val geometry = GeneratedImageGeometry(400f, 400f, 800f, 400f)
        assertEquals(GeneratedImageTransform(), geometry.clamp(GeneratedImageTransform(0.5f, 100f, 100f)))
        assertEquals(GeneratedImageTransform(2f, 200f, 0f), geometry.clamp(GeneratedImageTransform(2f, 999f, 999f)))
        assertEquals(5f, geometry.clamp(GeneratedImageTransform(9f)).scale, 0f)
    }
    @Test fun zoomKeepsTheFingerAnchorAndResizeReclamps() {
        val geometry = GeneratedImageGeometry(400f, 400f, 400f, 400f)
        val zoomed = geometry.gesture(GeneratedImageTransform(), 2f, 0f, 0f, 100f, 100f)
        assertEquals(GeneratedImageTransform(2f, 100f, 100f), zoomed)
        assertEquals(GeneratedImageTransform(), geometry.gesture(zoomed, 0.5f, 500f, 500f, 100f, 100f))
    }
}

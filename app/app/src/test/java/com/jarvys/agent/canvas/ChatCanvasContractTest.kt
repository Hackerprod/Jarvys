package com.jarvys.agent.canvas

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.PreviewResponsePolicy
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context as RhinoContext
import java.nio.file.Files
import java.nio.file.Paths

class ChatCanvasContractTest {
    @Test
    fun hostActionAllowlistRejectsUnknownStaleAndOutOfScopeActions() {
        val user = AgentRunUiEvent.messageEvent(10, "user", "hola", 1)
        val latest = AgentRunUiEvent.messageEvent(11, "assistant", "respuesta", 2).copy(messageId = "m-11")
        val tool = AgentRunUiEvent.toolEvent(12, "tool_result", "abrir", "hecho", "call", null, 3)
        val pending = AgentRunUiEvent.userDecisionEvent(
            13, "decision-13", "Confirmar", "", listOf(UserDecisionOption("yes", "Sí")), true,
            "PENDING", null, null, 4,
        )
        val previewTool = tool.copy(previewId = "preview-token")
        val attachmentId = java.util.UUID.randomUUID().toString()
        val transferable = user.copy(attachments = listOf(ChatAttachment(
            attachmentId, "report.txt", "text/plain", 12, ChatAttachment.Kind.FILE, "$attachmentId-report.txt",
        )))
        val suggested = user.copy(proactiveReplies = listOf(ProactiveSuggestedReply("Sí", "sí")))

        assertTrue(allowed("copy", user))
        assertFalse(allowed("copy", tool))
        assertTrue(allowed("open_viewer", previewTool))
        assertTrue(allowed("download", transferable))
        assertTrue(allowed("share", transferable))
        assertTrue(allowed("respond", suggested, value = "0"))
        assertFalse(allowed("respond", suggested, value = "1"))
        assertFalse(allowed("respond", suggested.copy(proactiveRepliesUsed = true), value = "0"))
        assertTrue(allowed("retry", latest, latestAssistant = "m-11"))
        assertFalse(allowed("retry", latest, latestAssistant = "m-11", allowRetry = false))
        assertFalse(allowed("retry", latest, running = true, latestAssistant = "m-11"))
        assertFalse(allowed("retry", event = null, latestAssistant = "m-11"))
        assertTrue(allowed("resolve_decision", pending, value = "yes"))
        assertTrue(allowed("resolve_decision", pending, value = "dismiss"))
        assertFalse(allowed("resolve_decision", pending, value = "delete-all"))
        assertFalse(allowed("resolve_decision", pending.copy(decisionStatus = "RESOLVED"), value = "yes"))
        assertTrue(allowed("request_older", event = null, hasOlder = true))
        assertFalse(allowed("request_older", event = null, hasOlder = false))
        assertFalse(allowed("read_file", user))
    }

    @Test
    fun serializerPreservesReaderOrderAndUsesSeqAsStableUiIdentity() {
        val events = listOf(
            AgentRunUiEvent.messageEvent(80, "user", "primero", 1),
            AgentRunUiEvent.messageEvent(17, "assistant", "segundo", 2).copy(messageId = "m-17"),
        )

        val page = ChatCanvasEventSerializer.page(events, running = false)
        assertEquals("[80,17]", page.getJSONArray("order").toString())
        assertEquals(80L, page.getJSONArray("events").getJSONObject(0).getLong("seq"))
        assertEquals(17L, page.getJSONArray("events").getJSONObject(1).getLong("seq"))
        assertTrue(page.getJSONArray("events").getJSONObject(1).getBoolean("canRetry"))
    }

    @Test
    fun transientAssistantStreamUsesCanvasAssistantPresentationAndCopyPolicy() {
        val stream = AgentRunUiEvent(
            id = -1L, kind = "assistant_stream", stage = "STREAMING", text = "partial",
            messageId = "loop-run",
        )

        val serialized = ChatCanvasEventSerializer.page(listOf(stream), running = true)
            .getJSONArray("events").getJSONObject(0)

        assertEquals("assistant", serialized.getString("kind"))
        assertEquals("STREAMING", serialized.getString("stage"))
        assertTrue(serialized.getBoolean("canCopy"))
        assertFalse(serialized.getBoolean("canRetry"))
        assertTrue(allowed("copy", stream, running = true))
        assertFalse(allowed("retry", stream, latestAssistant = "loop-run"))
    }

    @Test
    fun canvasResponsePolicyDisablesNetworkAndActiveEmbedding() {
        val csp = PreviewResponsePolicy.interactiveHeaders().getValue("Content-Security-Policy")
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("frame-src 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
    }

    @Test
    fun javascriptPatchModelUpdatesBySeqAndEscapesModelHtml() {
        val source = String(Files.readAllBytes(Paths.get("src/main/assets/chat_canvas/canvas.js")), Charsets.UTF_8)
        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, source, "chat-canvas.js", 1, null)
            val result = cx.evaluateString(scope, """
                ChatCanvas.bootstrap({
                  events: [{seq: 3, text: "bootstrap"}], order: [3], loadingOlder: true
                });
                var bootCount = ChatCanvas.metrics().loadedMessages;
                var model = ChatCanvas.createModel();
                ChatCanvas.applyDeltas(model, [
                  {seq: 80, text: "uno"}, {seq: 17, text: "dos"}
                ], [80, 17]);
                ChatCanvas.applyDeltas(model, [{seq: 17, text: "dos actualizado"}]);
                bootCount + "|" + model.seqs.join(",") + "|" + model.bySeq["17"].text + "|" +
                  ChatCanvas.safeMarkdown("<img src=x onerror=alert(1)>");
            """.trimIndent(), "chat-canvas-test.js", 1, null).toString()
            assertEquals("1|80,17|dos actualizado|<p>&lt;img src=x onerror=alert(1)&gt;</p>", result)
        } finally {
            RhinoContext.exit()
        }
    }

    private fun allowed(
        action: String,
        event: AgentRunUiEvent? = null,
        value: String = "",
        running: Boolean = false,
        latestAssistant: String? = null,
        hasOlder: Boolean = false,
        allowRetry: Boolean = true,
    ) = ChatCanvasActionPolicy.check(
        action, event, value, running, latestAssistant, hasOlder, allowRetry,
    ).allowed
}

package com.jarvys.agent.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.LoadSkillTool
import com.jarvys.agent.PreviewResponsePolicy
import com.jarvys.agent.R
import com.jarvys.agent.TestSecretStoreRule
import com.jarvys.agent.ToolActivity
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context as RhinoContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCanvasContractTest {
    @get:Rule val testSecrets = TestSecretStoreRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
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

        val page = ChatCanvasEventSerializer.page(context, events, running = false)
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

        val serialized = ChatCanvasEventSerializer.page(context, listOf(stream), running = true)
            .getJSONArray("events").getJSONObject(0)

        assertEquals("assistant", serialized.getString("kind"))
        assertEquals("STREAMING", serialized.getString("stage"))
        assertTrue(serialized.getBoolean("canCopy"))
        assertFalse(serialized.getBoolean("canRetry"))
        assertTrue(allowed("copy", stream, running = true))
        assertFalse(allowed("retry", stream, latestAssistant = "loop-run"))
    }

    @Test
    fun internalSkillActivityDetailsAreScrubbedForLiveAndReopenedEventsWhilePublicSkillDetailsRemain() {
        val repository = SkillRepository.get(context).also { it.refresh() }
        val internal = repository.skills.value.first { SkillRepository.isInternalBundledSkill(it) }
        val public = repository.enabledForRun().first { !SkillRepository.isInternalBundledSkill(it) }

        fun load(skill: com.jarvys.agent.skills.SkillEntry) = LoadSkillTool(
            listOf(skill), CorePromptBudget.standard().loadedSkillChars,
        ).execute(mapOf("skill_id" to skill.metadata.id), CancellationToken.uncancellable()).also {
            assertTrue("A real bundled skill load should succeed: ${it.content}", it.success)
        }

        val internalLoad = load(internal)
        val bodyLine = internal.body.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("> Source:") }
            .maxByOrNull(String::length) ?: error("The internal skill must contain a non-heading body line")
        assertTrue(internalLoad.content.contains(internal.metadata.id))
        assertTrue(internalLoad.content.contains(internal.metadata.name))
        assertTrue(internalLoad.content.contains(bodyLine))

        val liveActivity = ToolActivity("internal-live", "internal-execution", "internal-call", "read_skill",
            internal.metadata.name, internal.metadata.id, internal.metadata.name, "tool_result",
            internalLoad.content, "", "", "workspace", 1)
        val reopenedActivity = ToolActivity.fromJson(liveActivity.toJson())
            ?: error("A serialized tool activity should reopen")
        listOf(liveActivity, reopenedActivity).forEachIndexed { index, activity ->
            val event = AgentRunUiEvent.activityEvent(index.toLong() + 1, activity)
            val serialized = ChatCanvasEventSerializer.page(context, listOf(event), running = false)
            val json = serialized.toString()
            listOf(internal.metadata.id, internal.metadata.name, bodyLine).forEach { value ->
                assertFalse("Internal skill data appeared in canvas JSON: $value", json.contains(value))
            }
            val projected = serialized.getJSONArray("events").getJSONObject(0)
            assertEquals(context.getString(R.string.activity_skill_loaded_generic), projected.getString("text"))
            assertEquals(context.getString(R.string.activity_skill_name_generic), projected.getString("label"))
            assertFalse(projected.has("detail"))
        }

        val publicLoad = load(public)
        val publicActivity = ToolActivity("public", "public-execution", "public-call", "read_skill",
            public.metadata.name, public.metadata.id, public.metadata.name, "tool_result",
            publicLoad.content, "", "", "workspace", 1)
        val publicEvent = ChatCanvasEventSerializer.page(context,
            listOf(AgentRunUiEvent.activityEvent(3, publicActivity)), running = false)
            .getJSONArray("events").getJSONObject(0)
        assertTrue(publicEvent.getString("detail").contains(public.metadata.id))
        assertTrue(publicEvent.getString("detail").contains(public.metadata.name))
        assertEquals(publicLoad.content, publicEvent.getString("detail"))
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

package com.jarvys.agent

import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.device.ScreenData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotProfileDraftServiceTest {
    private val options = listOf("board_read", "report_done", "read_skill", "coding_grep", "project_exec", "project_jobs")
    private fun draft() = JSONObject().put("name", "Research partner").put("description", "Reviews source evidence")
        .put("prompt", "Read the user's sources and identify uncertainty.").put("capabilities", JSONArray(listOf("board_read", "report_done")))
        .put("skillIds", JSONArray()).put("workspaceMode", "legacy_chat")
    private fun decode(value: JSONObject = draft()) = BotProfileDraftService.decode(value.toString(), options, listOf("skill.test"))
    private fun invalid(block: () -> Unit) { assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException) }

    @Test fun generatesUniqueStableDraftIdsAndPreservesReusableInstructions() {
        val first = decode(); val second = decode()
        assertTrue(first.id.startsWith("custom-")); assertNotEquals(first.id, second.id)
        assertEquals(1, first.version)
        assertEquals("Read the user's sources and identify uncertainty.", first.prompt)
        assertEquals(CrewProfile.WorkspaceMode.LEGACY_CHAT, first.workspaceMode)
    }
    @Test fun noCapabilitiesIsAValidNonExecutingDraft() {
        assertTrue(decode(draft().put("capabilities", JSONArray())).capabilities.isEmpty())
    }
    @Test fun unavailableCapabilitiesAndSkillsAreRejected() {
        invalid { decode(draft().put("capabilities", JSONArray(listOf("adb_shell")))) }
        invalid { decode(draft().put("skillIds", JSONArray(listOf("private.missing")))) }
    }
    @Test fun duplicateCapabilitiesAndNonStringOptionsAreRejected() {
        invalid { decode(draft().put("capabilities", JSONArray(listOf("board_read", "board_read")))) }
        invalid { decode(draft().put("capabilities", JSONArray(listOf(7)))) }
    }
    @Test fun outputCannotChooseIdsOrGrantHiddenFields() {
        invalid { decode(draft().put("id", "coding")) }
        invalid { decode(draft().put("enabled", true)) }
        invalid { decode(draft().put("approval", "always")) }
    }
    @Test fun malformedMissingOrTrailingContentFailsClosed() {
        val missing = draft().apply { remove("prompt") }; invalid { decode(missing) }
        invalid { BotProfileDraftService.decode("explanation " + draft(), options, emptyList()) }
        invalid { BotProfileDraftService.decode(draft().toString() + " another object", options, emptyList()) }
        invalid { BotProfileDraftService.decode("[]", options, emptyList()) }
    }
    @Test fun optionalJsonFenceStillProducesOnlyOneConfiguration() {
        assertEquals("Research partner", BotProfileDraftService.decode("```json\n${draft()}\n```", options, emptyList()).name)
    }
    @Test fun workspaceCompatibilityAndExecutionObservabilityAreRequired() {
        invalid { decode(draft().put("capabilities", JSONArray(listOf("coding_grep")))) }
        invalid { decode(draft().put("workspaceMode", "conversation_project").put("capabilities", JSONArray(listOf("project_exec")))) }
        assertEquals(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT,
            decode(draft().put("workspaceMode", "conversation_project").put("capabilities", JSONArray(listOf("project_exec", "project_jobs")))).workspaceMode)
    }
    @Test fun selectedSkillsRequireExplicitReadSkillCapability() {
        invalid { decode(draft().put("skillIds", JSONArray(listOf("skill.test")))) }
        assertEquals(listOf("skill.test"), decode(draft().put("capabilities", JSONArray(listOf("read_skill")))
            .put("skillIds", JSONArray(listOf("skill.test")))).skillIds)
    }
    @Test fun generatedConfigurationCannotExceedRuntimeSkillLimit() {
        val skills = (1..9).map { "skill.$it" }
        invalid { BotProfileDraftService.decode(draft().put("capabilities", JSONArray(listOf("read_skill")))
            .put("skillIds", JSONArray(skills)).toString(), options, skills) }
    }
    @Test fun boundedTextAndSingleLineNamesAreEnforced() {
        invalid { decode(draft().put("name", "x".repeat(81))) }
        invalid { decode(draft().put("name", "First\nSecond")) }
        invalid { decode(draft().put("prompt", "x".repeat(16001))) }
        invalid { decode(draft().put("workspaceMode", "global")) }
    }
    @Test fun isolatedModelRequestHasNoHistoryImagesOrToolsAndPreservesFreeformPrompt() {
        var observed = false
        val request = "Quiero un experto en orquídeas: revisa evidencia y pregunta cuando falte contexto."
        val service = service { instructions, history, prompt, images, tools, _ ->
            observed = true
            assertEquals(request, prompt); assertTrue(history.isEmpty()); assertTrue(images.isEmpty()); assertTrue(tools.isEmpty())
            assertTrue(instructions.contains("UNSAVED")); assertTrue(instructions.contains("user review"))
            assertTrue(instructions.contains("written in English")); assertTrue(instructions.contains("1-3 words"))
            ModelReply(draft().toString(), emptyList())
        }
        assertEquals("Research partner", service.generate(request, options, emptyList(), CancellationToken.cancellable()).name)
        assertTrue(observed)
    }
    @Test fun cancelBeforeOrAfterModelDoesNotReturnACandidate() {
        val cancelled = CancellationToken.cancellable().apply { cancel() }
        val service = service { _, _, _, _, _, _ -> error("Must not be invoked") }
        assertTrue(runCatching { service.generate("Create", options, emptyList(), cancelled) }.exceptionOrNull() is CancellationException)
        val token = CancellationToken.cancellable()
        val second = service { _, _, _, _, _, _ -> token.cancel(); ModelReply(draft().toString(), emptyList()) }
        assertTrue(runCatching { second.generate("Create", options, emptyList(), token) }.exceptionOrNull() is CancellationException)
    }
    @Test fun toolCallsAndOversizedInputNeverBecomeConfiguration() {
        val service = service { _, _, _, _, _, _ -> ModelReply(draft().toString(), listOf(ModelReply.Call("1", "execute", emptyMap()))) }
        invalid { service.generate("Create", options, emptyList(), CancellationToken.cancellable()) }
        invalid { service.generate("x".repeat(8001), options, emptyList(), CancellationToken.cancellable()) }
    }

    private fun service(block: (String, List<ConversationTurn>, String, List<ScreenData>, List<ToolSpec>, CancellationToken) -> ModelReply): BotProfileDraftService {
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken) =
                block(systemPrompt, emptyList(), userPrompt, images, tools, token)
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken) =
                block(systemPrompt, history, userPrompt, images, tools, token)
        }
        return BotProfileDraftService(CoreAgentModel(provider, "bot-draft-unit-test"))
    }
}

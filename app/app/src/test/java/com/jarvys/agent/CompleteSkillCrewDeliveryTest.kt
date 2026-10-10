package com.jarvys.agent

import com.jarvys.agent.skills.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteSkillCrewDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun run(payload: String, complete: Boolean = true, calls: Int = 1, skill: Boolean = false,
                    window: Int = 4096, unbounded: Boolean = true): Pair<List<ToolActivity>, List<ConversationTurn>> {
        val entry = SkillEntry(SkillMetadata("skill.test", "Test", "Test", 1, emptyList(), emptyList()), payload, SkillSource.IMPORTED, true, 0)
        val tool: CoreTool = if (skill) LoadSkillTool(listOf(entry), 16384) else object : CoreTool {
            override fun declaration() = ToolSpec("read_skill", "test", "Test", "core", ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun execute(args: Map<String, Any>, token: CancellationToken) = if (complete) CoreToolResult.complete(payload) else CoreToolResult.success(payload)
        }
        var turns = 0
        val model = object : CoreAgentLoop.Model {
            override fun contextWindow(token: CancellationToken) = window
            override fun complete(transcript: List<ConversationTurn>, prompt: String, declarations: List<ToolSpec>, token: CancellationToken): ModelReply =
                if (turns++ == 0) ModelReply("", (1..calls).map { ModelReply.Call("load-$it", "read_skill", mapOf("skill_id" to "skill.test")) })
                else ModelReply("Done", emptyList())
        }
        val artifacts = CrewContextArtifacts(temporary.newFolder(), "test-bot")
        val compactor = ConversationCompactor.forCrew("test-bot", { _, _, _ -> error("Unexpected summary") }, artifacts)
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(tool)), "", "test", CorePromptBudget.standard(), compactor,
            if (unbounded) CoreAgentLoop.Limits.UNBOUNDED else CoreAgentLoop.Limits(3, 12), null, null)
        val events = mutableListOf<ToolActivity>()
        val result = loop.run("Read", emptyList(), CancellationToken.uncancellable(), object : CoreAgentLoop.ProgressListener {
            override fun onProgress(stage: String, message: String) {}
            override fun onToolActivity(activity: ToolActivity) { events += activity }
        })
        assertEquals("Done", result.text)
        return events to loop.transcriptSnapshot()
    }
    @Test fun exactAsciiAndUtf8BoundariesAreCompleteWithoutArchival() {
        for (text in listOf("x".repeat(1308), "é".repeat(654), "😀".repeat(327))) {
            val (events, transcript) = run(text)
            assertEquals(listOf("tool_call", "tool_result"), events.map { it.stage })
            assertTrue(transcript.any { it.content == text })
            assertFalse(transcript.any { it.content.contains("preview is incomplete") })
        }
    }
    @Test fun overBudgetCompleteOutputsFailWithoutPartialPayloadOrLoadedActivity() {
        for (text in listOf("PRIVATE_SENTINEL" + "x".repeat(1309), "é".repeat(655))) {
            val (events, transcript) = run(text)
            assertEquals(listOf("tool_call", "tool_error"), events.map { it.stage })
            assertTrue(events.last().detail.contains("No partial output"))
            assertFalse(transcript.any { it.content.contains("PRIVATE_SENTINEL") || it.content.contains("preview is incomplete") })
        }
    }
    @Test fun ordinaryLargeResultsStillUseRecoverableArtifacts() {
        val (events, transcript) = run("x".repeat(1309), complete = false)
        assertEquals("tool_result", events.last().stage)
        assertTrue(transcript.any { it.content.contains("preview is incomplete") })
    }
    @Test fun realSkillIncludesHeaderInUtf8Boundary() {
        val header = "Skill skill.test — Test\n".toByteArray().size
        assertEquals("tool_result", run("x".repeat(1308 - header), skill = true).first.last().stage)
        assertEquals("tool_error", run("x".repeat(1309 - header), skill = true).first.last().stage)
    }
    @Test fun completeCrewResultsCannotExceedRemainingTurnBudget() {
        val (events, _) = run("x".repeat(16000), calls = 2, window = 262144, unbounded = false)
        assertEquals(listOf("tool_call", "tool_result", "tool_call", "tool_error"), events.map { it.stage })
        assertTrue(events.last().detail.contains("remaining per-turn"))
    }
}

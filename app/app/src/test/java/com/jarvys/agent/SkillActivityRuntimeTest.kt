package com.jarvys.agent

import com.jarvys.agent.skills.*
import org.junit.Assert.*
import org.junit.Test

class SkillActivityRuntimeTest {
    private fun skill(body:String="Follow the gardening instructions.")=SkillEntry(SkillMetadata("skill.garden","Gardening","Fixture",1,emptyList(),emptyList()),body,SkillSource.IMPORTED,true,0)
    private fun run(skill:SkillEntry=skill(),max:Int=16000,enabled:Boolean=true,id:String="skill.garden",cancel:Boolean=false,calls:Int=1):List<ToolActivity> {
        val tool=LoadSkillTool(listOf(skill),max,{enabled})
        val tools=CoreToolRegistry(listOf(tool));var turn=0
        val model=object:CoreAgentLoop.Model {
            override fun complete(transcript:List<ConversationTurn>,prompt:String,declarations:List<ToolSpec>,token:CancellationToken)=
                if(turn++==0) ModelReply("",(1..calls).map { ModelReply.Call("skill-call-$it","read_skill",mapOf("skill_id" to id)) }) else ModelReply("Done",emptyList())
        }
        val loop=CoreAgentLoop(model,tools,"","skill-chat",CorePromptBudget.standard(),null,3)
        val events=mutableListOf<ToolActivity>();val token=CancellationToken.cancellable()
        try { loop.run("Read the selected skill",emptyList(),token,object:CoreAgentLoop.ProgressListener {
            override fun onProgress(stage:String,message:String)=Unit
            override fun onToolActivity(activity:ToolActivity) { events+=activity;if(cancel && activity.stage=="tool_call") token.cancel() }
        }) } catch(expected:java.util.concurrent.CancellationException) { assertTrue(cancel) }
        return events
    }
    @Test fun successfulActualSkillReadIdentifiesSkillAndCompleteLoad() {
        val events=run();assertEquals(listOf("tool_call","tool_result"),events.map { it.stage })
        assertTrue(events.all { it.skill() && it.skillId=="skill.garden" && it.skillName=="Gardening" })
        assertEquals(1,events.map { it.executionId }.toSet().size);assertTrue(events.last().detail.contains("gardening instructions"))
    }
    @Test fun unavailableSkillFailsWithoutLoadedClaim() { assertEquals("tool_error",run(id="missing.skill").last().stage) }
    @Test fun disabledSkillFailsWithoutLoadedClaim() { assertEquals("tool_error",run(enabled=false).last().stage) }
    @Test fun oversizedSkillFailsWithoutPartialLoadedClaim() { assertEquals("tool_error",run(max=4).last().stage) }
    @Test fun cancelledSkillShowsInterruptionAndNeverLoaded() {
        val events=run(cancel=true);assertEquals("tool_interrupted",events.last().stage);assertFalse(events.any { it.stage=="tool_result" })
    }
    @Test fun secondCompleteSkillExceedingRemainingContextBudgetIsNotLoaded() {
        val events=run(skill=skill("x".repeat(16000)),calls=2)
        assertEquals(listOf("tool_call","tool_result","tool_call","tool_error"),events.map { it.stage })
        assertTrue(events.last().detail.contains("remaining per-turn context budget"))
    }
    @Test fun separateRunsNeverReuseInvocationIdentityEvenIfProviderIdRepeats() {
        assertNotEquals(run().first().executionId,run().first().executionId)
    }
}

package com.jarvys.agent

import com.jarvys.agent.skills.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask

class SkillActivityRuntimeTest {
    private fun skill(body:String="Follow the gardening instructions.")=SkillEntry(SkillMetadata("skill.garden","Gardening","Fixture",1,emptyList(),emptyList()),body,SkillSource.IMPORTED,true,0)
    private fun run(skill:SkillEntry=skill(),max:Int=16000,enabled:Boolean=true,id:String="skill.garden",cancel:Boolean=false,calls:Int=1):List<ToolActivity> {
        val tool=LoadSkillTool(listOf(skill),max,{enabled})
        val tools=CoreToolRegistry(listOf(tool));var turn=0
        val model=object:CoreAgentLoop.Model {
            override fun complete(transcript:List<ConversationTurn>,prompt:String,declarations:List<ToolSpec>,token:CancellationToken)=
                if(turn++==0) ModelReply("",(1..calls).map { ModelReply.Call("skill-call-$it","read_skill",mapOf("skill_id" to id)) }) else ModelReply("Done",emptyList())
        }
        val loop=CoreAgentLoop(model,tools,"","skill-chat",CorePromptBudget.standard(),null)
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
        assertTrue("skill activity retains the registry's visible tool label", events.all { it.displayName=="Read Skill" })
        assertEquals(1,events.map { it.executionId }.toSet().size);assertTrue(events.last().detail.contains("gardening instructions"))
    }
    @Test fun unavailableSkillFailsWithoutLoadedClaim() { assertEquals("tool_error",run(id="missing.skill").last().stage) }
    @Test fun disabledSkillFailsWithoutLoadedClaim() { assertEquals("tool_error",run(enabled=false).last().stage) }
    @Test fun oversizedSkillFailsWithoutPartialLoadedClaim() { assertEquals("tool_error",run(max=4).last().stage) }
    @Test fun cancelledSkillShowsInterruptionAndNeverLoaded() {
        val events=run(cancel=true);assertEquals("tool_interrupted",events.last().stage);assertFalse(events.any { it.stage=="tool_result" })
    }
    @Test fun secondCompleteSkillExceedingRemainingContextBudgetIsNotLoaded() {
        val events=run(skill=skill("x".repeat(16000)),max=CorePromptBudget.standard().loadedSkillChars,calls=2)
        assertEquals(listOf("tool_call","tool_result","tool_call","tool_error"),events.map { it.stage })
        assertTrue(events.last().detail.contains("remaining per-turn context budget"))
    }
    @Test fun completeSkillBudgetIsAllocatedInCallOrderAlongsideParallelTools() {
        val budget=CorePromptBudget.standard()
        val skillTool=LoadSkillTool(listOf(skill("x".repeat(16000))),budget.loadedSkillChars)
        val parallelEntered=CountDownLatch(1)
        val releaseParallel=CountDownLatch(1)
        val firstSkillResultRecorded=CountDownLatch(1)
        val independentRead=object:CoreTool {
            override fun executionMode()=CoreTool.ExecutionMode.PARALLEL
            override fun declaration()=ToolSpec("independent_read","test","Independent read","test",
                ToolSpec.Status.IMPLEMENTED,emptyMap(),emptyList())
            override fun execute(arguments:Map<String,Any>?,token:CancellationToken):CoreToolResult {
                parallelEntered.countDown()
                try {
                    releaseParallel.await()
                } catch(interrupted:InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.util.concurrent.CancellationException("parallel fixture interrupted")
                }
                return CoreToolResult.success("independent read")
            }
        }
        var turn=0
        val model=object:CoreAgentLoop.Model {
            override fun complete(transcript:List<ConversationTurn>,prompt:String,declarations:List<ToolSpec>,token:CancellationToken)=
                if(turn++==0) ModelReply("",listOf(
                    ModelReply.Call("skill-call-1","read_skill",mapOf("skill_id" to "skill.garden")),
                    ModelReply.Call("parallel-call","independent_read",emptyMap()),
                    ModelReply.Call("skill-call-2","read_skill",mapOf("skill_id" to "skill.garden"))))
                else ModelReply("Done",emptyList())
        }
        val events=mutableListOf<ToolActivity>()
        val loop=CoreAgentLoop(model,CoreToolRegistry(listOf(skillTool,independentRead)),"","skill-chat",budget,null)
        val task=FutureTask {
            loop.run("Read the selected skill",emptyList(),CancellationToken.cancellable(),object:CoreAgentLoop.ProgressListener {
                override fun onProgress(stage:String,message:String)=Unit
                override fun onToolActivity(activity:ToolActivity) {
                    events+=activity
                    if(activity.skill() && activity.stage=="tool_result") firstSkillResultRecorded.countDown()
                }
            })
        }
        val loopThread=Thread(task,"skill-budget-test-loop").apply { isDaemon=true; start() }
        try {
            parallelEntered.await()
            assertEquals("the first skill result must spend budget before the parallel call is released",
                0L,firstSkillResultRecorded.count)
        } finally {
            releaseParallel.countDown()
        }
        task.get()
        loopThread.join()
        assertFalse("loop worker should finish after the test barrier opens",loopThread.isAlive)
        assertEquals(listOf("tool_call","tool_result","tool_call","tool_error"),
            events.filter { it.skill() }.map { it.stage })
        assertTrue(events.first { it.skill() && it.stage=="tool_error" }.detail
            .contains("remaining per-turn context budget"))
    }
    @Test fun separateRunsNeverReuseInvocationIdentityEvenIfProviderIdRepeats() {
        assertNotEquals(run().first().executionId,run().first().executionId)
    }
}

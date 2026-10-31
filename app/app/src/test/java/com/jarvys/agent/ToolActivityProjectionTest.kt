package com.jarvys.agent

import com.jarvys.agent.crew.*
import org.junit.Assert.*
import org.junit.Test

class ToolActivityProjectionTest {
    private fun activity(key: String="run:1:0", stage: String="tool_call", detail: String="", event: String=stage,
        tool: String="read", skill: String="", preview: String="") = ToolActivity(event,key,"provider-id",tool,"Read",skill,
        if(skill.isBlank()) "" else "Gardening",stage,detail,preview,"literal audit","workspace",100)
    private fun bot(id:String="bot",state:String="RUNNING") = CrewBotSnapshot(id,"coding","Coding","Coding","coding","Inspect",state,"","","",emptyList(),0,0)
    private fun message(a:ToolActivity?,id:String="m",from:String="bot",text:String="",type:CrewMessage.Type=CrewMessage.Type.STATUS)=
        CrewMessage(id,"chat",from,"chief",type,text,emptyList(),100,a)

    @Test fun startProgressAndSuccessKeepOneRowAndEveryCanonicalEvent() {
        val a=activity();val p=activity(stage="tool_progress",detail="Scanning");val done=activity(stage="tool_result",detail="Found",preview="preview")
        val input=listOf(message(a,"a"),message(p,"p"),message(done,"d"))
        val row=CrewActivityTimeline.project(input,listOf(bot())).single()
        assertEquals(3,input.size);assertEquals("a",row.id);assertEquals("tool_result",row.activity.stage)
        assertEquals("Found",row.activity.detail);assertTrue(row.activity.allDetail().contains("Scanning"));assertEquals("preview",row.activity.previewId)
    }
    @Test fun lateStartAndProgressCannotDowngradeResult() {
        val result=activity(stage="tool_result",detail="Final",preview="preview")
        val projected=ToolActivity.project(listOf(result,activity(),activity(stage="tool_progress",detail="Late")),true)
        assertEquals("tool_result",projected.stage);assertEquals("Final",projected.detail);assertEquals("preview",projected.previewId)
    }
    @Test fun duplicateDeliveryIsIdempotent() {
        val p=activity(stage="tool_progress",detail="Once")
        assertEquals("Once",ToolActivity.project(listOf(p,p),true).allDetail())
    }
    @Test fun sameProviderIdDifferentRunAndRetryRemainSeparate() {
        val rows=CrewActivityTimeline.project(listOf(message(activity("run-1"),"1"),message(activity("run-2"),"2")),listOf(bot()))
        assertEquals(2,rows.size)
    }
    @Test fun sameExecutionIdDifferentBotsRemainSeparate() {
        assertEquals(2,CrewActivityTimeline.project(listOf(message(activity(),"1","a"),message(activity(),"2","b")),listOf(bot("a"),bot("b"))).size)
    }
    @Test fun realStatusAndLegacyActivityRemainUnmergedAndDirectional() {
        val rows=CrewActivityTimeline.project(listOf(message(null,"1",text="Using Read"),message(null,"2",text="Completed Read"),
            message(null,"3",text="I sent a real status")),listOf(bot()))
        assertEquals(3,rows.size);assertTrue(rows.all { it.to=="chief" && it.activity==null })
    }
    @Test fun stoppedStartedCallIsUnconfirmedButSuccessfulCallStaysSuccessful() {
        val rows=CrewActivityTimeline.project(listOf(message(activity("pending"),"1"),message(activity("done","tool_result"),"2")),listOf(bot(state="STOPPED")))
        assertEquals(listOf("tool_interrupted","tool_result"),rows.map { it.activity.stage })
    }
    @Test fun failureAndNeverStartedAndUnknownStayHonest() {
        for(stage in listOf("tool_error","tool_not_started","future_stage")) {
            val a=ToolActivity.project(listOf(activity(stage=stage)),false)
            assertNotEquals("tool_result",a.stage)
        }
    }
    @Test fun interruptionCanBeResolvedByAnAuthoritativeLateResult() {
        assertEquals("tool_result",ToolActivity.project(listOf(activity(stage="tool_interrupted"),activity(stage="tool_result")),false).stage)
    }
    @Test fun bothSnapshotAndMessageCodecPreserveTypedEvidence() {
        val a=activity(tool="read_skill",skill="skill.garden",stage="tool_result",detail="Instructions",preview="preview")
        val m=message(a)
        val direct=CrewMessage.fromJson(m.toJson())
        assertEquals(a.executionId,direct.activity.executionId);assertEquals(a.skillId,direct.activity.skillId)
        val snapshot=CrewMissionSnapshot("mission","chat","proc","Task","DONE","",0,100,listOf(bot(state="DONE")),listOf(m))
        val reopened=CrewMissionSnapshot.fromJson(snapshot.toJson())!!
        assertEquals("Instructions",reopened.messages.single().activity.detail);assertEquals("preview",reopened.messages.single().activity.previewId)
    }
    @Test fun legacySnapshotVersionsRemainReadableWithoutInventedActivity() {
        val snapshot=CrewMissionSnapshot("mission","chat","proc","Task","DONE","",0,100,listOf(bot(state="DONE")),listOf(message(null)))
        for(v in listOf(1,2)) assertNull(CrewMissionSnapshot.fromJson(snapshot.toJson().put("crewSchemaVersion",v))!!.messages.single().activity)
    }
    @Test fun loadingOnlyClassifiesActualReadSkillInvocation() {
        assertTrue(activity(tool="read_skill",skill="skill.garden").skill())
        assertFalse(activity(tool="read",skill="skill.garden").skill());assertFalse(activity(tool="read_skill").skill())
    }
    @Test fun eventCodecAndSanitizationKeepSecretsOutOfPresentation() {
        val a=activity(detail="Authorization: Bearer secret-value")
        assertFalse(a.detail.contains("secret-value"))
        val decoded=ToolActivity.fromJson(a.toJson())!!;assertEquals(a.eventId,decoded.eventId)
        assertEquals(a.executionId,decoded.executionId)
    }
    @Test fun liveTypedProjectionKeepsKeyDetailAndPreviewAndStopsPending() {
        AgentRunUiState.resetSession("typed");AgentRunUiState.beginRun("typed","Inspect")
        val a=activity();AgentRunUiState.onToolActivity(a)
        val id=AgentRunUiState.state.value.events.last().id
        AgentRunUiState.onToolActivity(activity(stage="tool_result",detail="Final",preview="preview"))
        AgentRunUiState.onToolActivity(a);AgentRunUiState.onToolActivity(activity(stage="tool_progress",detail="Late"))
        val row=AgentRunUiState.state.value.events.single { it.kind=="tool" }
        assertEquals(id,row.id);assertEquals("tool_result",row.stage);assertEquals("preview",row.previewId)
        AgentRunUiState.onToolActivity(activity("pending"))
        AgentRunUiState.complete("run","COMPLETED","Done")
        assertEquals("tool_interrupted",AgentRunUiState.state.value.events.single { it.toolCallId=="pending" }.stage)
    }
    @Test fun legacyLiveBlankCallIdsAreNeverMerged() {
        AgentRunUiState.resetSession("blank");AgentRunUiState.beginRun("blank","Inspect")
        AgentRunUiState.onToolProgress("tool_call","","Read",null)
        AgentRunUiState.onToolProgress("tool_result","","Read","Result")
        assertEquals(2,AgentRunUiState.state.value.events.count { it.kind=="tool" })
    }
    @Test fun legacyLiveDuplicateStartAndLateProgressKeepTerminalRow() {
        AgentRunUiState.resetSession("legacy");AgentRunUiState.beginRun("legacy","Inspect")
        AgentRunUiState.onToolProgress("tool_call","call","Read",null)
        AgentRunUiState.onToolProgress("tool_call","call","Read",null)
        AgentRunUiState.onToolProgress("tool_result","call","Read","Result")
        AgentRunUiState.onToolProgress("tool_progress","call","Late",null)
        assertEquals("Result",AgentRunUiState.state.value.events.single { it.kind=="tool" }.detail)
    }
    @Test fun malformedFutureLocalPayloadStaysLocalAndUnconfirmed() {
        val json=message(activity()).toJson().put("activity",org.json.JSONObject().put("schemaVersion",99).put("detail","Evidence"))
        val restored=CrewMessage.fromJson(json)
        assertNotNull(restored.activity);assertEquals("tool_interrupted",restored.activity.stage)
        assertTrue(restored.activity.detail.contains("Evidence"))
    }
    @Test fun projectedHistorySurvivesStopPreviewLossAndLateEvent() {
        val projected=ToolActivity.project(listOf(activity(),activity(stage="tool_progress",detail="Partial evidence"),
            activity(stage="tool_interrupted",event="interrupted")),false)
        assertTrue(projected.interrupted().allDetail().contains("Partial evidence"))
        assertTrue(projected.unavailablePreview().allDetail().contains("Partial evidence"))
        assertTrue(ToolActivity.project(listOf(projected,activity(stage="tool_progress",detail="Late")),true).allDetail().contains("Partial evidence"))
    }
    @Test fun restoredOldCallDoesNotBecomeRunningWhenSameBotResumes() {
        val old=message(activity("old"),"old")
        val restored=CrewActivityTimeline.interruptUnfinished(listOf(old),"bot")
        val rows=CrewActivityTimeline.project(restored+message(activity("new"),"new"),listOf(bot()))
        assertEquals(listOf("tool_interrupted","tool_call"),rows.map { it.activity.stage })
        assertEquals(2,CrewActivityTimeline.interruptUnfinished(restored,"bot").size)
    }
    @Test fun localActivityCannotEnterMailboxOrCaptainDescription() {
        val bus=CrewMessageBus("chat");val a=activity(stage="tool_result",detail="Retained detail")
        bus.recordActivity("bot",a);bus.recordActivity("bot",a)
        assertEquals(1,bus.snapshot().size);assertTrue(bus.pending("chief").isEmpty());assertTrue(bus.pending("bot").isEmpty())
        assertFalse(CrewManager.formatMessages(bus.snapshot()).contains("Retained detail"))
    }
}

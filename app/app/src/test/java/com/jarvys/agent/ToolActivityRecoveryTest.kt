package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolActivityRecoveryTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun activity(key:String,stage:String="tool_call",detail:String="",skill:Boolean=false)=ToolActivity("$key/$stage",key,"same-call",
        if(skill) "read_skill" else "read",if(skill) "Read Skill" else "Read",if(skill) "skill.garden" else "",
        if(skill) "Gardening" else "",stage,detail,"","audit","workspace",100)
    private fun calls(store:LocalRunStore,user:String,batch:String,tool:String="read") {
        store.appendModelTranscriptRow("chat",JSONObject().put("type","model_tool_calls").put("batchId",batch).put("userMessageId",user)
            .put("calls",JSONArray().put(JSONObject().put("id","same-call").put("name",tool).put("arguments",JSONObject()))))
    }
    private fun result(store:LocalRunStore,batch:String,output:String) {
        store.appendModelTranscriptRow("chat",JSONObject().put("type","model_tool_result").put("batchId",batch)
            .put("callId","same-call").put("toolName","read").put("output",output))
    }
    @Test fun everyTypedStageIsDurableButReopenHasOneRowWithFullEvidence() {
        val root=temporary.newFolder();val store=LocalRunStore(root);val user=store.appendConversationMessage("chat","user","Inspect")
        calls(store,user,"batch");store.appendToolActivity("chat",user,activity("run"))
        store.appendToolActivity("chat",user,activity("run","tool_progress","Partial result"))
        result(store,"batch","Full result");store.appendToolActivity("chat",user,activity("run","tool_result","Full result"))
        val row=LocalRunStore(root).readConversationTimeline("chat").single { it.kind=="tool" }
        assertEquals("run",row.toolCallId);assertEquals("tool_result",row.stage);assertEquals("Full result",row.detail)
        assertTrue(row.toolActivity!!.allDetail().contains("Partial result"))
    }
    @Test fun sameRawCallAcrossTwoUsersAndTwoBatchesNeverMerges() {
        val store=LocalRunStore(temporary.newFolder())
        for(i in 1..2) {
            val user=store.appendConversationMessage("chat","user","Question $i");calls(store,user,"b$i")
            store.appendToolActivity("chat",user,activity("run$i"));result(store,"b$i","Result $i")
            store.appendToolActivity("chat",user,activity("run$i","tool_result","Result $i"))
        }
        assertEquals(listOf("Result 1","Result 2"),store.readConversationTimeline("chat").filter { it.kind=="tool" }.map { it.detail })
    }
    @Test fun repeatedCallWithinOneUserDistinctBatchesRetainsBothExecutions() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Retry")
        for(i in 1..2) {
            calls(store,user,"b$i");store.appendToolActivity("chat",user,activity("run$i"));result(store,"b$i","Result $i")
            store.appendToolActivity("chat",user,activity("run$i","tool_result","Result $i"))
        }
        assertEquals(listOf("run1","run2"),store.readConversationTimeline("chat").filter { it.kind=="tool" }.map { it.toolCallId })
    }
    @Test fun legacyDistinctBatchesWithRepeatedCallAndSameUserStaySeparate() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Inspect")
        calls(store,user,"a");result(store,"a","First");calls(store,user,"b");result(store,"b","Second")
        assertEquals(listOf("First","Second"),store.readConversationTimeline("chat").filter { it.kind=="tool" }.map { it.detail })
    }
    @Test fun interruptedRowRecoversActualDurableResultRatherThanHidingIt() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Inspect")
        calls(store,user,"a");store.appendToolActivity("chat",user,activity("run"));result(store,"a","Actual success")
        store.appendToolActivity("chat",user,activity("run","tool_interrupted"))
        val row=store.readConversationTimeline("chat").single { it.kind=="tool" }
        assertEquals("tool_result",row.stage);assertEquals("Actual success",row.detail)
    }
    @Test fun skillInterruptedBeforeAcceptedContentRetainsEvidenceWithoutClaimingLoaded() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Skill")
        calls(store,user,"a","read_skill");store.appendToolActivity("chat",user,activity("run",skill=true));result(store,"a","Instructions")
        val row=store.readConversationTimeline("chat").single { it.kind=="tool" }
        assertEquals("tool_interrupted",row.stage);assertEquals("Instructions",row.detail);assertTrue(row.toolActivity!!.skill())
    }
    @Test fun missingResultStaysUnconfirmedAfterReopenAndDoesNotReplay() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Inspect")
        calls(store,user,"a");store.appendToolActivity("chat",user,activity("run"))
        repeat(2) { assertEquals("tool_interrupted",store.readConversationTimeline("chat").single { it.kind=="tool" }.stage) }
    }
    @Test fun regenerationInvalidatesOldTypedRowsButPreservesReplacement() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Inspect")
        store.appendToolActivity("chat",user,activity("old","tool_result","Old"))
        val assistant=store.appendConversationMessage("chat","assistant","Old",0L,"run",user,"COMPLETED")
        store.appendAssistantRegenerated("chat",assistant);store.appendToolActivity("chat",user,activity("new","tool_result","New"))
        assertEquals("new",store.readConversationTimeline("chat").single { it.kind=="tool" }.toolCallId)
    }
    @Test fun typedEvidenceDoesNotEnterModelTranscriptOrConversationMessages() {
        val store=LocalRunStore(temporary.newFolder());val user=store.appendConversationMessage("chat","user","Inspect")
        store.appendToolActivity("chat",user,activity("run","tool_result","UI_ONLY_MARKER"))
        assertFalse(store.readModelTranscriptRows("chat").any { it.toString().contains("UI_ONLY_MARKER") })
        assertFalse(store.readConversationMessages("chat").any { it.toString().contains("UI_ONLY_MARKER") })
    }
    @Test fun malformedFutureActivityDoesNotBecomeSuccessful() {
        assertNull(ToolActivity.fromJson(JSONObject().put("schemaVersion",42)))
        assertEquals("tool_interrupted",activity("run","unknown-stage").stage)
    }
}

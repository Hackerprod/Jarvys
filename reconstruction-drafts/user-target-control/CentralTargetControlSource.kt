package com.jarvys.agent

import com.jarvys.agent.chatdb.CentralUserRootCheckpoint
import com.jarvys.agent.workspaces.WorkspaceTreeEvidenceStore
import org.json.JSONObject

/** Room reads before Registry. No DTO returned here is dispatch authority. */
internal object CentralTargetControlSource {
    fun capture(store:LocalRunStore,source:CentralInvocationSource,args:String):CentralUserRootCheckpoint {
        check(store.latestUserMessageId(source.ownerCentralSessionId)==source.sourceMessageId) { "A fresh latest USER is required" }
        val user=store.captureCentralUserRoot(source.ownerCentralSessionId,source.sourceMessageId)
        check(user.storageGeneration==source.storageGeneration)
        val call=requireNotNull(store.readEpisodeToolSource(source.ownerCentralSessionId,source.sourceBatchId,source.sourceCallId))
        check(call.conversationId==source.ownerCentralSessionId && call.batchId==source.sourceBatchId && call.callId==source.sourceCallId &&
            call.toolName=="workspaces" && call.lifecycle=="STARTED" && call.output==null &&
            WorkspaceTreeEvidenceStore.canonical(JSONObject(call.argumentsJson))==args) { "Committed exact control arguments changed" }
        return user
    }
}

package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.chatdb.CentralSubmissionSnapshot
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Immutable display selection, not an executable permission. Native tap + exact CAS are still required. */
data class CentralAvailabilityDisplay(
    val submission:CentralSubmissionSnapshot?,
    val suspension:CentralTreeAdmission.SuspensionRecoveryDescriptor?,
    val state:String,
    val canRetry:Boolean,
) {
    /** Exact displayed request fence, never authority. Canonical field order supports stale-tap rejection. */
    fun recoveryFence():String {
        val fields=org.json.JSONArray().put("central_recovery_v1")
        val parked=suspension
        if(parked!=null) fields.put("SUSPENSION").put(parked.sessionId).put(parked.originalUserMessageId)
            .put(parked.resumeId).put(parked.revision).put(parked.sourceDigest).put(parked.rootCycle).put(parked.stopEpoch)
        else submission?.let {fields.put("NEW_TURN").put(it.sessionId).put(it.userMessageId).put(it.submissionId)
            .put(it.revision).put(it.sourceSha256).put(it.storageGeneration).put(it.state)} ?: return ""
        return fields.toString()
    }
    fun label(context:Context):String=context.getString(when(state) {
        "PREPARED"->R.string.central_request_queued
        "RUNNING"->R.string.central_request_running
        "BACKGROUND"->R.string.central_request_background
        "CANCELLED"->R.string.central_request_cancelled
        "FAILED"->R.string.central_request_failed
        else->R.string.central_request_uncertain
    })
}

/** Immutable cross-store display evidence, never an admission/claim or a replacement for tap-time CAS. */
class CentralAvailabilityUiEvidence internal constructor(
    submissions:List<CentralSubmissionSnapshot>,
    suspensions:List<CentralTreeAdmission.SuspensionRecoveryDescriptor>,
    assistantOwners:Map<String,String>,
) {
    val submissions:List<CentralSubmissionSnapshot> = java.util.Collections.unmodifiableList(ArrayList(submissions))
    val suspensions:List<CentralTreeAdmission.SuspensionRecoveryDescriptor> = java.util.Collections.unmodifiableList(ArrayList(suspensions))
    val assistantOwners:Map<String,String> = java.util.Collections.unmodifiableMap(LinkedHashMap(assistantOwners))
}

/** Applied only after native Room UI materialization. No migration, dispatch, or permit creation. */
object CentralAvailabilityUiProjection {
    @JvmStatic fun project(context:Context?,session:String,events:List<AgentRunUiEvent>):List<AgentRunUiEvent> {
        if(context==null || !CentralAvailabilityPolicy.admissionEnabled() || !WorkspaceIdentity.isCentral(session))return events
        val evidence=com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context)
            .readCentralAvailabilityUiEvidenceSync(session,events.filter {it.kind=="assistant"}.map {it.messageId}) ?: return events
        val rows=evidence.submissions
        val byUser=rows.associateBy {it.userMessageId}
        val resumes=evidence.suspensions.groupBy {it.originalUserMessageId}
        val terminalIds=rows.mapNotNull {row -> row.terminalEvidence?.let {runCatching {org.json.JSONObject(it).optString("assistantId")}.getOrNull()}}.toSet()
        return events.map {event ->
            if(event.kind=="assistant" && (event.messageId in terminalIds
                || evidence.assistantOwners[event.messageId] in byUser))
                return@map event.copy(canRegenerate=false)
            if(event.kind!="user" || event.messageId.isBlank())return@map event
            val row=byUser[event.messageId]
            val resume=resumes[event.messageId]?.singleOrNull()
            if(row==null && resume==null)return@map event
            if(row?.state=="COMPLETED" && resume==null)return@map event.copy(centralAvailability=null)
            val active=AgentForegroundService.isCentralSourceActive(session,event.messageId)
            val liveNew=row?.takeIf {it.state=="PREPARED"}?.let {
                val live=CentralSubmissionAdmission.find(session,it.submissionId)
                live!=null && live.revision==it.revision && runCatching {live.requirePrepared()}.isSuccess
            } ?: false
            val liveResume=resume?.let {AgentForegroundService.hasLiveCentralSuspension(it)} ?: false
            val state=when {active->"RUNNING";resume!=null->"BACKGROUND";row?.state=="STARTED"->"UNCERTAIN";else->row?.state?:"UNCERTAIN"}
            val retry=when {resume!=null->!liveResume;row?.state=="PREPARED"->!liveNew;else->false}
            event.copy(centralAvailability=CentralAvailabilityDisplay(row,resume,state,retry))
        }
    }
}

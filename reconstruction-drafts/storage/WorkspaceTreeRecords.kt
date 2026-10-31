package com.jarvys.agent.workspaces

/** Immutable durable identities. These values and serialized source strings confer no execution authority. */
internal data class TreeOrigin(val storageGeneration:String,val ownerCentralSessionId:String,
    val originUserMessageId:String,val originDigest:String)
internal data class TreeRoot(val rootId:String,val origin:TreeOrigin,val sourceReceipt:String,val policyVersion:Int,
    val revision:Long,val windowEpoch:Long,val requests:Long,val executions:Long,val wakes:Long,
    val admissionsClosed:Boolean,val wakesClosed:Boolean,val closeReason:String?)
internal enum class TreeMode { CENTRAL, WORK, CONSULT, NOTIFY }
internal enum class TreeReply { AWAIT, NONE }
internal enum class TreeNodeState { QUEUED, STARTED, RUNNING, WAITING, NEEDS_INPUT, TERMINAL, UNCERTAIN }
internal data class TreeNode(val rootId:String,val nodeId:String,val parentNodeId:String?,val depth:Int,
    val mode:TreeMode,val reply:TreeReply,val workspaceId:String?,val assignmentId:String?,val assignmentRevision:Long,
    val botId:String?,val cycleId:String,val revision:Long,val state:TreeNodeState,val sourceReceipt:String,
    val executionEventId:String?,val terminalRef:String?,val terminalHash:String?,val terminalOutcome:String?,
    val reportReceipt:String?,val controlIntent:String?,val admissionsClosed:Boolean,val wakesClosed:Boolean,val stopEpoch:Long)
internal data class TreeNodeFence(val rootId:String,val nodeId:String,val cycleId:String,val revision:Long,val assignmentRevision:Long) {
    companion object { fun of(node:TreeNode)=TreeNodeFence(node.rootId,node.nodeId,node.cycleId,node.revision,node.assignmentRevision) }
}
internal data class TreeChildRequest(val workspaceId:String,val assignmentKey:String,val requestId:String,
    val originalUserRequest:String,val role:String,val mission:String,val tools:List<String>?,val name:String?,
    val mode:TreeMode,val reply:TreeReply,val sourceReceipt:String,
    val centralSource:com.jarvys.agent.CentralInvocationSource?=null)
internal data class TreeExecutionReservation(val rootId:String,val nodeId:String,val assignmentId:String,
    val processId:String,val eventId:String,val revision:Long,val assignmentRevision:Long)
internal enum class TreeJoin { ALL, ANY }
internal enum class TreeWaitState { ARMING, WAITING, READY, STARTED, ACKNOWLEDGED, CANCELLED }
internal data class TreeCause(val eventKey:String,val nodeId:String,val cycleId:String,val kind:String,
    val evidenceRef:String,val evidenceHash:String)
internal data class TreeWait(val rootId:String,val waitId:String,val parentNodeId:String,val parentCycleId:String,
    val parentRevision:Long,val revision:Long,val children:List<String>,val join:TreeJoin,val implicit:Boolean,
    val state:TreeWaitState,val sourceReceipt:String,val checkpointCertificate:String?,val checkpointSha256:String?,
    val selectedCauses:List<TreeCause>?,val successorIntent:String?,val ownerProcess:String?,val ownerGeneration:String?,
    val ownerToken:String?,val incorporationReceipt:String?)
internal data class TreeWaitClaim(val wait:TreeWait,val causes:List<TreeCause>,val executionEventId:String,
    val sourceAssignmentRevision:Long,val admittedAssignmentRevision:Long)
internal data class TreeControlClaim(val node:TreeNode,val control:WorkspaceUserControl,val executionEventId:String?)
internal class TreeIntegrityException(message:String):IllegalStateException(message)
internal class TreeIneligibleException(message:String):IllegalStateException(message)

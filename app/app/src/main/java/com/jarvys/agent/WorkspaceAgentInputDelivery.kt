package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceRegistry
import java.util.UUID

/** Writes every worker-to-chief message into Central's durable conversation input pipeline. */
internal object WorkspaceAgentInputDelivery {
    @JvmStatic
    @Synchronized
    fun deliver(context: Context, bot: CrewManager.Bot, message: CrewMessage) {
        require(message.from == bot.id && message.to == "chief" && message.activity == null)
        require(WorkspaceIdentity.isExecutionSession(message.conversationId))
        require(WorkspaceCoordinationDelivery.isCanonicalWorkspace(context, message.conversationId))
        val workspaceId = WorkspaceIdentity.workspaceId(message.conversationId)
        val kind = try { ConversationInput.Kind.valueOf(message.type.name) }
        catch (_: IllegalArgumentException) { ConversationInput.Kind.OTHER }
        val content = message.conversationContent()
        if (content.isBlank()) {
            nonDeliverable(context, "empty worker message")
            return
        }
        val store = LocalRunStore(context)
        val assignmentId = bot.assignmentId()
        if (!isCanonicalUuid(assignmentId)) {
            nonDeliverable(context, "worker assignment identity is missing or invalid")
            return
        }
        val assignment = WorkspaceRegistry(context).use { registry ->
            registry.getAssignment(workspaceId, assignmentId)
        }
        if (assignment == null) {
            nonDeliverable(context, "worker assignment no longer exists")
            return
        }
        if (assignment.missionId != bot.missionId || assignment.botId != bot.id) {
            nonDeliverable(context, "worker message does not match its bound assignment")
            return
        }
        val evidenceReference = message.coordination
        val reference = evidenceReference?.let {
            WorkspaceCoordinationDelivery(context).registerForIncomingInput(workspaceId, it.evidenceId)
        }
        if (evidenceReference != null) {
            val evidence = store.readCrewCoordinationEvidence(message.conversationId, evidenceReference.evidenceId)
            if (reference == null || evidence == null || !evidenceReference.matches(evidence)
                || !reference.matchesEvidence(evidence) || reference.workspaceId != workspaceId
                || reference.assignmentId != assignment.assignmentId || reference.botId != bot.id) {
                nonDeliverable(context, "worker coordination reference could not be registered")
                return
            }
        }
        val input = input(message, workspaceId, assignment.assignmentId, kind, content, assignment, reference)
        // The stable source identity is checked against the fully resolved, typed provenance.
        // This makes a stable-ID retry idempotent without silently accepting changed assignment,
        // coordination reference, hash or revision fields.
        val existing = store.readConversationMessageByStableId(
            WorkspaceIdentity.CENTRAL_SESSION, input.stableId())
        if (existing != null) {
            val same = runCatching {
                val prior = store.readConversationInput(
                    WorkspaceIdentity.CENTRAL_SESSION, existing.getString("messageId"))
                prior != null && sameSourceInput(input, prior)
            }.getOrDefault(false)
            if (!same) nonDeliverable(context, "stable worker message identity conflicts with stored input")
            return
        }
        val storedId = store.appendConversationInput(input)
        AgentForegroundService.startRealAgentFromStoredChatMessage(
            context.applicationContext, WorkspaceIdentity.CENTRAL_SESSION, storedId,
        )
    }

    private fun input(message: CrewMessage, workspaceId: String, assignmentId: String,
                      kind: ConversationInput.Kind, content: String,
                      assignment: com.jarvys.agent.workspaces.WorkspaceAssignment? = null,
                      reference: WorkspaceCoordinationReference? = null) = ConversationInput(
        WorkspaceIdentity.CENTRAL_SESSION,
        message.id,
        content,
        ConversationInput.Origin.AGENT,
        ConversationInput.Provenance(message.conversationId, workspaceId, assignmentId,
            message.from, message.id, kind, ConversationInput.Provenance.PrincipalRole.SUBORDINATE,
            message.id, reference?.referenceId ?: "", reference?.contentSha256 ?: "",
            reference?.observedAssignmentRevision ?: assignment?.revision ?: 0L,
            reference?.requestId ?: assignment?.requestId.orEmpty(),
            reference?.missionId ?: assignment?.missionId.orEmpty(),
            reference?.cycleId ?: botCycleId(message)),
    )

    private fun botCycleId(message: CrewMessage): String = message.coordination?.cycleId.orEmpty()

    private fun sameSourceInput(expected: ConversationInput, actual: ConversationInput): Boolean {
        val left = expected.provenance ?: return false
        val right = actual.provenance ?: return false
        return expected.conversationId == actual.conversationId
            && expected.origin == actual.origin && expected.content == actual.content
            && left.sessionId == right.sessionId && left.workspaceId == right.workspaceId
            && left.assignmentId == right.assignmentId && left.botId == right.botId
            && left.messageId == right.messageId && left.kind == right.kind
            && left.principalRole == right.principalRole
            && left.correlationMessageId == right.correlationMessageId
            && left.referenceId == right.referenceId && left.contentSha256 == right.contentSha256
            && left.assignmentRevision == right.assignmentRevision && left.requestId == right.requestId
            && left.missionId == right.missionId && left.cycleId == right.cycleId
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        value.isNotBlank() && UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun nonDeliverable(context: Context, reason: String) {
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.RUNTIME,
            IllegalStateException("Worker conversation message was terminally classified: $reason"))
    }
}

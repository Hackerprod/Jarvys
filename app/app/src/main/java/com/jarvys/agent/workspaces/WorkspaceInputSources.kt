package com.jarvys.agent.workspaces

import com.jarvys.agent.crew.CrewInputBinding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Exact source fingerprints, separate from mutable dispatch state and checkpoint observation. */
internal object WorkspaceInputSources {
    fun reply(reply: WorkspaceCoordinationReply): CrewInputBinding {
        val ref = reply.reference
        check(reply.state != WorkspaceCoordinationReply.State.PREPARED)
        return CrewInputBinding.create(CrewInputBinding.SourceKind.WORKSPACE_REPLY, reply.replyId,
            ref.ownerCentralSessionId, ref.workspaceId, ref.executionSessionId, ref.assignmentId,
            ref.missionId, ref.botId, reply.mailboxMessageId, reply.payloadSha256, ref.cycleId, reply.text)
    }

    fun correction(scope: WorkspaceRecord, assignment: WorkspaceAssignment, control: WorkspaceUserControl,
                   admissionCycleId: String): CrewInputBinding {
        validateCorrection(scope, assignment, control)
        return CrewInputBinding.create(CrewInputBinding.SourceKind.NATIVE_CORRECT, control.requestId,
            scope.ownerCentralSessionId, scope.workspaceId, scope.executionSessionId, assignment.assignmentId,
            assignment.missionId, control.botId, correctionMailbox(control.requestId),
            correctionPayload(scope, assignment, control), admissionCycleId, control.userText)
    }

    fun correctionMailbox(controlId: String): String = UUID.nameUUIDFromBytes(
        ("jarvys-workspace-correction-mailbox-v1\u0000" + controlId).toByteArray(StandardCharsets.UTF_8)).toString()

    fun validateCorrection(scope: WorkspaceRecord, assignment: WorkspaceAssignment, control: WorkspaceUserControl) {
        check(scope.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION &&
            scope.executionSessionId == WorkspaceIdentity.executionSessionId(scope.workspaceId))
        check(assignment.workspaceId == scope.workspaceId && assignment.state == WorkspaceAssignmentState.BOUND &&
            assignment.assignmentId == control.assignmentId && assignment.botId == control.botId &&
            control.workspaceId == scope.workspaceId)
        check(control.type == WorkspaceControlType.CORRECT && control.origin == WorkspaceRegistry.NATIVE_UI_ORIGIN &&
            control.state != WorkspaceControlState.PENDING && control.admittedAssignmentRevision != null)
        // Later revisions do not erase an exact historical incorporation.
        check(assignment.revision >= control.admittedAssignmentRevision)
    }

    fun correctionPayload(scope: WorkspaceRecord, assignment: WorkspaceAssignment, control: WorkspaceUserControl): String {
        validateCorrection(scope, assignment, control)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun value(text: String) { out.writeInt(text.length); text.forEach { out.writeChar(it.code) } }
            value("workspace-native-correction-payload-v1")
            listOf(scope.ownerCentralSessionId, scope.workspaceId, scope.executionSessionId,
                assignment.assignmentId, assignment.requestId, assignment.missionId, control.botId,
                control.requestId, control.type.name, control.origin, control.userText).forEach(::value)
            out.writeLong(control.assignmentRevision)
            out.writeLong(requireNotNull(control.admittedAssignmentRevision))
            out.writeLong(control.createdAt)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.workspaces.WorkspaceAssignment
import com.jarvys.agent.workspaces.WorkspaceChanges
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Durable conversation evidence and exact incorporation receipt for workspace worker inputs. */
internal object WorkspaceConversationInputStore {
    @JvmStatic
    fun append(context: Context, executionSessionId: String, bot: CrewManager.Bot,
               message: CrewMessage): String {
        if (message.activity != null || "user" == message.from) return ""
        val input = toConversationInput(context, executionSessionId, bot, message)
        val store = LocalRunStore(context)
        // A supervisor outbox row is committed before Crew admission. Reuse that exact row so
        // the rich typed provenance survives the worker checkpoint/Room acknowledgement path.
        val existing = store.readConversationMessageByStableId(executionSessionId, input.stableId())
        if (existing != null) {
            val prior = store.readConversationInput(executionSessionId, existing.getString("messageId"))
                ?: throw IllegalStateException("Stored worker input lost its provenance")
            val priorProvenance = requireNotNull(prior.provenance)
            val expectedProvenance = requireNotNull(input.provenance)
            val sameIdentity = priorProvenance.toJson().toString() == expectedProvenance.toJson().toString()
            check(prior.content == input.content && prior.origin == input.origin && sameIdentity) {
                "Crew input conflicts with its durable conversation outbox"
            }
            return existing.getString("messageId")
        }
        val storedId = store.appendConversationInput(input)
        WorkspaceChanges.changed()
        return storedId
    }

    /** Resolves the exact durable metadata used by this admitted worker input. */
    @JvmStatic
    fun resolve(context: Context, executionSessionId: String, bot: CrewManager.Bot,
                message: CrewMessage): ConversationInput? {
        if (message.activity != null || "user" == message.from) return null
        val expected = toConversationInput(context, executionSessionId, bot, message)
        val store = LocalRunStore(context)
        val row = store.readConversationMessageByStableId(executionSessionId, expected.stableId()) ?: return null
        val saved = store.readConversationInput(executionSessionId, row.getString("messageId")) ?: return null
        val left = requireNotNull(expected.provenance)
        val right = requireNotNull(saved.provenance)
        check(saved.content == expected.content && saved.origin == expected.origin
            && left.toJson().toString() == right.toJson().toString()) {
            "Admitted Crew message does not match its durable worker input"
        }
        return ConversationInput(executionSessionId, message.id, saved.content, saved.origin, saved.provenance)
    }

    /**
     * Persists the supervisor side of a message as an idempotent existing-schema input row
     * before the Crew mailbox is touched. The worker checkpoint later acknowledges this same
     * row through the normal conversation-input pipeline.
     */
    @JvmStatic
    fun appendSupervisorIntent(context: Context, executionSessionId: String,
                               assignment: WorkspaceAssignment, botId: String, key: String,
                               text: String, correlation: WorkspaceCoordinationReference?): ConversationInput =
        appendSupervisorIntentOfKind(context, executionSessionId, assignment, botId, key, text, correlation,
            ConversationInput.Kind.MESSAGE)

    /** Retained answers never enter generic MESSAGE replay. The continuation coordinator owns dispatch. */
    fun appendSupervisorAnswerIntent(context: Context, executionSessionId: String,
                                    assignment: WorkspaceAssignment, botId: String, key: String,
                                    text: String, correlation: WorkspaceCoordinationReference): ConversationInput =
        appendSupervisorIntentOfKind(context, executionSessionId, assignment, botId, key, text, correlation,
            ConversationInput.Kind.ANSWER)

    private fun appendSupervisorIntentOfKind(context: Context, executionSessionId: String,
                               assignment: WorkspaceAssignment, botId: String, key: String,
                               text: String, correlation: WorkspaceCoordinationReference?,
                               kind: ConversationInput.Kind): ConversationInput {
        require(WorkspaceIdentity.isExecutionSession(executionSessionId)
            && executionSessionId == WorkspaceIdentity.executionSessionId(assignment.workspaceId)
            && assignment.state == com.jarvys.agent.workspaces.WorkspaceAssignmentState.BOUND
            && assignment.botId == botId)
        val messageId = supervisorMessageId(assignment.workspaceId, assignment.assignmentId, key)
        val input = ConversationInput(executionSessionId, messageId, text, ConversationInput.Origin.AGENT,
            ConversationInput.Provenance(WorkspaceIdentity.CENTRAL_SESSION, assignment.workspaceId,
                assignment.assignmentId, botId, messageId, kind,
                ConversationInput.Provenance.PrincipalRole.SUPERVISOR, correlation?.messageId ?: "",
                correlation?.referenceId ?: "", correlation?.contentSha256 ?: "",
                correlation?.observedAssignmentRevision ?: assignment.revision,
                assignment.requestId, assignment.missionId, correlation?.cycleId ?: ""))
        val store = LocalRunStore(context)
        val existing = store.readConversationMessageByStableId(executionSessionId, input.stableId())
        if (existing != null) {
            val prior = store.readConversationInput(executionSessionId, existing.getString("messageId"))
                ?: throw IllegalStateException("Supervisor outbox row lost its typed provenance")
            check(sameOutboxPayload(input, prior)) {
                "Supervisor message key conflicts with its durable outbox payload"
            }
            return input
        }
        store.appendConversationInput(input)
        WorkspaceChanges.changed()
        return input
    }

    /** True when a previous attempt already committed this stable supervisor key to the outbox. */
    @JvmStatic
    fun hasSupervisorIntent(context: Context, executionSessionId: String, workspaceId: String,
                            assignmentId: String, key: String): Boolean {
        val messageId = supervisorMessageId(workspaceId, assignmentId, key)
        val row = LocalRunStore(context).readConversationMessageByStableId(
            executionSessionId, ConversationInput.stableId(ConversationInput.Origin.AGENT,
                WorkspaceIdentity.CENTRAL_SESSION, messageId)) ?: return false
        if (row.optString("role") != "agent") return false
        val provenance = row.optJSONObject("provenance") ?: return false
        return provenance.optString("messageId") == messageId
            && provenance.optString("workspaceId") == workspaceId
            && provenance.optString("assignmentId") == assignmentId
            && provenance.optString("principalRole") == ConversationInput.Provenance.PrincipalRole.SUPERVISOR.name
    }

    /** Pending supervisor outbox entries are delivered only when the caller owns a runnable worker state. */
    @JvmStatic
    fun pendingSupervisorIntents(context: Context, executionSessionId: String,
                                 assignmentId: String? = null): List<ConversationInput> {
        val store = LocalRunStore(context)
        val staleRows = ArrayList<String>()
        val pending = ArrayList<ConversationInput>()
        val workspaceId = WorkspaceIdentity.workspaceId(executionSessionId)
        val assignments = HashMap<String, com.jarvys.agent.workspaces.WorkspaceAssignment?>()
        val latestCorrections = HashMap<String, com.jarvys.agent.workspaces.WorkspaceUserControl?>()
        com.jarvys.agent.workspaces.WorkspaceRegistry(context).use { registry ->
            for (row in store.readPendingInternalConversationInputs(executionSessionId)) {
                if (row.optString("role") != "agent" || row.has("inputState")) continue
                val provenanceJson = row.optJSONObject("provenance") ?: continue
                val provenance = runCatching { ConversationInput.Provenance.fromJson(provenanceJson) }.getOrNull()
                    ?: continue
                // This recovery path owns only central supervisor intents. Worker-to-worker
                // messages share the recipient's execution session, but provenance identifies
                // the sending bot and must not be judged against the recipient assignment.
                if (provenance.principalRole != ConversationInput.Provenance.PrincipalRole.SUPERVISOR
                    || provenance.sessionId != WorkspaceIdentity.CENTRAL_SESSION) continue
                if (provenance.workspaceId != workspaceId || provenance.assignmentId.isBlank()) continue

                val assigned = if (assignments.containsKey(provenance.assignmentId)) assignments[provenance.assignmentId]
                    else registry.getAssignment(workspaceId, provenance.assignmentId).also {
                        assignments[provenance.assignmentId] = it
                    }
                if (assigned == null || assigned.state != com.jarvys.agent.workspaces.WorkspaceAssignmentState.BOUND
                    || assigned.botId != provenance.botId || assigned.missionId != provenance.missionId) {
                    // Inputs from an assignment that no longer owns this worker cannot be delivered,
                    // regardless of whether their original type was TASK, ANSWER, or MESSAGE.
                    row.optString("messageId").takeIf(String::isNotBlank)?.let(staleRows::add)
                    continue
                }

                if (provenance.principalRole == ConversationInput.Provenance.PrincipalRole.SUPERVISOR) {
                    val correction = if (latestCorrections.containsKey(provenance.assignmentId))
                        latestCorrections[provenance.assignmentId]
                    else registry.getLatestCorrection(workspaceId, provenance.assignmentId).also {
                        latestCorrections[provenance.assignmentId] = it
                    }
                    val correctionRevision = correction?.admittedAssignmentRevision
                    if (correctionRevision != null && provenance.assignmentRevision < correctionRevision) {
                        // A later native CORRECT supersedes older supervisor instructions. RESUME
                        // alone may advance the assignment revision without replacing their authority.
                        row.optString("messageId").takeIf(String::isNotBlank)?.let(staleRows::add)
                        continue
                    }
                }

                if ((assignmentId != null && provenance.assignmentId != assignmentId)
                    || provenance.kind != ConversationInput.Kind.MESSAGE) continue
                pending += ConversationInput(executionSessionId, provenance.messageId, row.optString("content"),
                    ConversationInput.Origin.AGENT, provenance)
            }
        }
        if (staleRows.isNotEmpty()
            && store.markConversationMessagesResolved(executionSessionId, staleRows).isNotEmpty())
            WorkspaceChanges.changed()
        return pending
    }

    /** Recovers only completed assignments; interrupted workers need native RESUME first. */
    @JvmStatic
    fun recoverCompletedSupervisorIntents(context: Context, executionSessionId: String,
                                          manager: CrewManager) {
        val pending = pendingSupervisorIntents(context, executionSessionId)
        com.jarvys.agent.workspaces.WorkspaceRegistry(context).use { registry ->
            for (input in pending) {
                val provenance = requireNotNull(input.provenance)
                val assignment = registry.getAssignment(provenance.workspaceId, provenance.assignmentId) ?: continue
                val bot = manager.bot(provenance.botId) ?: continue
                if (assignment.state != com.jarvys.agent.workspaces.WorkspaceAssignmentState.BOUND
                    || assignment.botId != bot.id || assignment.missionId != bot.missionId
                    || bot.status() != CrewManager.Status.DONE
                    || bot.requiresExplicitResume() || !bot.hasDurableCheckpoint()) continue
                val controlPending = registry.getUnsettledControl(provenance.workspaceId, provenance.assignmentId) != null
                    || registry.getPendingControl(provenance.workspaceId, provenance.assignmentId,
                        assignment.revision) != null
                if (controlPending || com.jarvys.agent.workspaces.WorkspaceControlOwners
                        .hasNativeControlOwner(provenance.assignmentId)) continue
                manager.sendSupervisorMessageExact(bot.id, bot.token, input.messageId, input.content,
                    emptyList(), provenance, null)
            }
        }
    }

    /** Called only after a native RESUME/CORRECT has explicitly opened this worker cycle. */
    @JvmStatic
    fun deliverPendingSupervisorIntentsAfterExplicitResume(context: Context, executionSessionId: String,
                                                           manager: CrewManager, botId: String,
                                                           nativeOwner: CancellationToken? = null,
                                                           admission: Runnable = Runnable {}) {
        val bot = manager.bot(botId) ?: return
        if (bot.requiresExplicitResume() || bot.status() !in setOf(CrewManager.Status.QUEUED,
                CrewManager.Status.RUNNING, CrewManager.Status.WAITING, CrewManager.Status.DONE)) return
        val pending = pendingSupervisorIntents(context, executionSessionId, bot.assignmentId())
        com.jarvys.agent.workspaces.WorkspaceRegistry(context).use { registry ->
            for (input in pending) {
                val provenance = requireNotNull(input.provenance)
                val assignment = registry.getAssignment(provenance.workspaceId, provenance.assignmentId) ?: continue
                if (assignment.state != com.jarvys.agent.workspaces.WorkspaceAssignmentState.BOUND
                    || assignment.botId != bot.id || assignment.missionId != bot.missionId) continue
                val validate = Runnable {
                    admission.run()
                    check(!com.jarvys.agent.workspaces.WorkspaceControlOwners.hasNativeControlOwner(
                        assignment.assignmentId, nativeOwner)) {
                        "A newer native control takes precedence over the pending supervisor input"
                    }
                }
                runCatching {
                    manager.sendSupervisorMessageExact(bot.id, bot.token, input.messageId, input.content,
                        emptyList(), provenance, validate)
                }.onFailure { failure ->
                    LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.RUNTIME,
                        IllegalStateException("Pending supervisor input remains in the durable worker outbox", failure))
                }
            }
        }
    }

    private fun sameOutboxPayload(expected: ConversationInput, actual: ConversationInput): Boolean =
        expected.conversationId == actual.conversationId && expected.content == actual.content
            && expected.origin == actual.origin
            && expected.provenance?.toJson()?.toString() == actual.provenance?.toJson()?.toString()

    internal fun supervisorMessageId(workspaceId: String, assignmentId: String, key: String): String =
        UUID.nameUUIDFromBytes(("jarvys-workspace-supervisor-message-v1\u0000"
            + workspaceId + "\u0000" + assignmentId + "\u0000" + key)
            .toByteArray(StandardCharsets.UTF_8)).toString()

    private fun toConversationInput(context: Context, executionSessionId: String, bot: CrewManager.Bot,
                                    message: CrewMessage): ConversationInput {
        require(message.activity == null && "user" != message.from)
        message.inputProvenance?.let { return ConversationInput(
            executionSessionId, message.id, message.conversationContent(), ConversationInput.Origin.AGENT, it)
        }
        val workspaceId = WorkspaceIdentity.workspaceId(executionSessionId)
        val origin = if ("recovery" == message.from) ConversationInput.Origin.SYSTEM
            else ConversationInput.Origin.AGENT
        val sourceSession = if ("chief" == message.from || "recovery" == message.from)
            WorkspaceIdentity.CENTRAL_SESSION else executionSessionId
        val assignmentId = message.inputBinding?.assignmentId ?: bot.assignmentId()
        // For a supervisor message this field identifies its worker destination; for a worker
        // report it identifies the worker principal. The source session remains Central for the
        // former and the worker execution session for the latter.
        val sourceBotId = if ("chief" == message.from) message.to
            else if ("recovery" == message.from) "" else message.from
        val principalRole = if ("chief" == message.from)
            ConversationInput.Provenance.PrincipalRole.SUPERVISOR
        else ConversationInput.Provenance.PrincipalRole.PEER
        val kind = try { ConversationInput.Kind.valueOf(message.type.name) }
            catch (_: IllegalArgumentException) { ConversationInput.Kind.OTHER }
        val boundAssignment = if (assignmentId.isNotBlank()) com.jarvys.agent.workspaces.WorkspaceRegistry(context).use {
            it.getAssignment(workspaceId, assignmentId)
        } else null
        val replyReference = message.inputBinding?.takeIf {
            it.sourceKind == com.jarvys.agent.crew.CrewInputBinding.SourceKind.WORKSPACE_REPLY
        }?.let { binding ->
            LocalRunStore(context).readWorkspaceCoordinationReplyById(
                WorkspaceIdentity.CENTRAL_SESSION, binding.sourceId)?.reference
        }
        val reference = replyReference
        return ConversationInput(
            executionSessionId, message.id, message.conversationContent(), origin,
            ConversationInput.Provenance(sourceSession, workspaceId, assignmentId, sourceBotId,
                message.id, kind, principalRole,
                reference?.messageId ?: "", reference?.referenceId ?: "", reference?.contentSha256 ?: "",
                reference?.observedAssignmentRevision ?: boundAssignment?.revision ?: 0L,
                reference?.requestId ?: boundAssignment?.requestId.orEmpty(),
                reference?.missionId ?: boundAssignment?.missionId.orEmpty(),
                reference?.cycleId ?: message.inputBinding?.admissionCycleId.orEmpty()),
        )
    }

    @JvmStatic
    fun acknowledge(context: Context, executionSessionId: String, messageIds: List<String>) {
        val changed = LocalRunStore(context).markConversationMessagesIncorporated(executionSessionId, messageIds)
        if (changed.isNotEmpty()) WorkspaceChanges.changed()
    }

    /** Repairs the only crash window: a worker checkpoint committed its input before the Room ack. */
    @JvmStatic
    fun acknowledgeApplied(context: Context, executionSessionId: String, appliedSourceIds: Set<String>) {
        if (appliedSourceIds.isEmpty()) return
        val store = LocalRunStore(context)
        val candidates = store.readPendingInternalConversationInputsBySourceIds(executionSessionId, appliedSourceIds).filter { row ->
            if (row.optString("role") != "agent" && row.optString("role") != "system") return@filter false
            val provenance = row.optJSONObject("provenance") ?: return@filter false
            provenance.optString("messageId") in appliedSourceIds
        }
        val rows = candidates.mapNotNull { row -> row.optString("messageId").takeIf(String::isNotBlank) }
        val workspaceAgentIds = candidates.filter { row ->
            row.optString("role") == "agent" &&
                row.optJSONObject("provenance")?.optString("workspaceId").orEmpty().isNotBlank()
        }.mapNotNull { row -> row.optString("messageId").takeIf(String::isNotBlank) }.toSet()
        val changed = store.markConversationMessagesIncorporated(executionSessionId, rows)
        if (changed.any(workspaceAgentIds::contains)) WorkspaceChanges.changed()
    }
}

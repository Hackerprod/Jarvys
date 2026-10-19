package com.jarvys.agent

import android.app.Application
import com.jarvys.agent.crew.*
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.workspaces.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Integration seams use real local Crew/ledger/registry; the parked model never calls a provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceCoordinationDeliveryTest {
    @Rule @JvmField val chatTestStore = ChatTestStore(registerApplicationFilesDirectory = true)

    private lateinit var app: Application
    private lateinit var registry: WorkspaceRegistry
    private lateinit var ledger: LocalRunStore
    private lateinit var delivery: WorkspaceCoordinationDelivery
    private lateinit var scope: WorkspaceRecord
    private lateinit var target: WorkspaceAssignment
    private var parked: Parked? = null
    private val noAdmission = Runnable {}
    private val freshToken get() = CancellationToken.uncancellable()
    private val central get() = WorkspaceIdentity.CENTRAL_SESSION

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        File(app.filesDir, "jarvys").deleteRecursively()
        registry = WorkspaceRegistry(app)
        ledger = LocalRunStore(app)
        delivery = WorkspaceCoordinationDelivery(app, ledger) { session ->
            assertEquals(scope.executionSessionId, session)
            parked?.manager
        }
        scope = registry.createWorkspace("coordination-scope", "Synthetic coordination")
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment", "r".repeat(160),
            "Synthetic original request", "coding", "Synthetic mission", emptyList(), null, CrewMissionAccess.STANDARD)
        target = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "synthetic-process")
        assertTrue(ChatDatabaseBridge.getInstance(app).ensureConversationSync(central, "chat") is ChatDbResult.Success)
        ledger.appendConversationMessage(scope.executionSessionId, "user", "Synthetic source initialization")
    }

    @After fun close() {
        try { parked?.close() } finally {
            StopController.getInstance().stopRun()
            registry.close()
            app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        }
    }

    private fun bind(bot: String = "b".repeat(160)) {
        target = registry.bindBot(scope.workspaceId, target.assignmentId, target.revision, bot)
    }
    private fun evidence(text: String = "UNTRUSTED synthetic question", refs: List<String> = emptyList(),
                         kind: String = "QUESTION", message: String = UUID.randomUUID().toString(),
                         bot: String = target.botId ?: "b".repeat(160)): CrewCoordinationEvidence =
        CrewCoordinationEvidence.create(scope.executionSessionId, target.missionId, bot, UUID.randomUUID().toString(),
            message, kind, text, refs, 1234L).also { ledger.appendCrewCoordinationEvidence(it, noAdmission) }
    private fun references() = ledger.listWorkspaceCoordinationReferences(scope.executionSessionId, 50, null)
    private fun notices() = ledger.listWorkspaceCoordinationNotices(central, 50, null)
    private fun reconcile() = delivery.reconcile(scope.workspaceId, freshToken)
    private fun awaitScheduledReceipts() {
        val executor = WorkspaceResultDelivery::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(null) as ExecutorService
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }
    private fun read(r: WorkspaceCoordinationReference, token: CancellationToken = freshToken,
                     admission: Runnable = noAdmission) =
        delivery.read(scope.workspaceId, r.referenceId, r.contentSha256, 0, token, admission)
    private fun reply(r: WorkspaceCoordinationReference, key: String = "reply-key", text: String = "Untrusted chief answer",
                      token: CancellationToken = freshToken, admission: Runnable = noAdmission,
                      backend: WorkspaceCoordinationDelivery = delivery) =
        backend.reply(scope.workspaceId, r.referenceId, r.contentSha256, key, text, r.observedAssignmentRevision,
            "central-request", "Synthetic user task", token, admission)
    private fun prepared(r: WorkspaceCoordinationReference) = ledger.prepareWorkspaceCoordinationReply(
        WorkspaceCoordinationReply.create(r, "reply-key", "Untrusted chief answer", "central-request", "Synthetic user task", 1234L), noAdmission)
    private fun savedReply(r: WorkspaceCoordinationReference) = ledger.readWorkspaceCoordinationReply(central, r.referenceId)
    private fun failure(block: () -> Unit): Throwable = try { block(); throw AssertionError("Expected fail-closed rejection") }
        catch (error: Exception) { error }
    private fun liveQuestion(): Pair<Parked, WorkspaceCoordinationReference> {
        val worker = Parked().also { parked = it }
        bind(worker.bot.id)
        worker.manager.bindWorkspaceAssignmentId(worker.bot.id, target.assignmentId)
        worker.question()
        reconcile()
        return worker to references().single()
    }
    private fun liveQuestionWithoutNotice(): Pair<Parked, WorkspaceCoordinationReference> {
        val worker = Parked().also { parked = it }
        bind(worker.bot.id)
        val evidence = worker.question()
        val proposed = WorkspaceCoordinationReference.create(evidence, scope.ownerCentralSessionId,
            scope.workspaceId, target.assignmentId, target.requestId, target.revision)
        return worker to ledger.appendWorkspaceCoordinationReferenceIfAbsent(proposed, noAdmission)
    }

    @Test fun producerBeforeBindCatchesUpOnceAndNeverLeaksInternalBody() {
        val worker = Parked().also { parked = it }
        val source = worker.question("Internal body must not become an ordinary user instruction", listOf("private-ref"))
        assertEquals(source.text, ledger.readCrewCoordinationEvidence(scope.executionSessionId, source.evidenceId).text)
        assertTrue(worker.manager.canReplyToCoordination(source))
        reconcile(); assertTrue(references().isEmpty()); assertTrue(notices().isEmpty())
        bind(worker.bot.id); reconcile()
        WorkspaceCoordinationDelivery(app, LocalRunStore(app)) { worker.manager }.reconcile(scope.workspaceId, freshToken)
        val r = references().single()
        assertTrue(r.matchesEvidence(source)); assertEquals(target.revision, r.observedAssignmentRevision)
        assertEquals(r.referenceId, notices().single().referenceId)
        assertEquals("LIVE_REPLYABLE", read(r).getString("reply_state"))
        assertTrue(ledger.readConversationMessages(central).isEmpty())
        assertTrue(ledger.readActiveConversationMessages(central).isEmpty())
        assertTrue(ledger.readConversationTimeline(central).isEmpty())
        for (session in listOf(central, scope.executionSessionId)) {
            assertFalse(ledger.readConversationContext(session).contains(source.text))
            assertFalse(ledger.buildReflectionPayload(session).text.contains(source.text))
        }
        assertFalse(notices().single().toNoticeJson().toString().contains(source.text))
        assertFalse(delivery.inbox(scope.workspaceId, 50, null, noAdmission).toString().contains(source.text))
    }

    @Test fun reconciliationAutomaticallyRecordsNoticeAndForegroundReadIsExact() {
        bind(); evidence(); reconcile()
        assertEquals(1, references().size); assertEquals(1, notices().size)
        val r = references().single()
        val response = read(r)
        assertEquals(r.contentSha256, response.getString("expected_sha256"))
    }

    @Test fun messageCorrelationResolvesBothIdsAndRejectsAnotherAssignmentsSourceId() {
        val (worker, reference) = liveQuestion()
        val byCentralReference = delivery.resolveMessageCorrelation(scope.workspaceId, target.assignmentId,
            worker.bot.id, target.revision, reference.referenceId)
        val bySourceMessage = delivery.resolveMessageCorrelation(scope.workspaceId, target.assignmentId,
            worker.bot.id, target.revision, reference.messageId)
        assertEquals(reference.referenceId, byCentralReference.referenceId)
        assertTrue(byCentralReference.samePayload(bySourceMessage))

        val otherPrepared = registry.prepareAssignment(scope.workspaceId, "other-correlation-assignment",
            "other-correlation-request", "Synthetic source initialization", "coding", "Other assignment",
            emptyList(), null, CrewMissionAccess.STANDARD)
        val otherStarted = registry.markStarted(scope.workspaceId, otherPrepared.assignmentId,
            otherPrepared.revision, "other-correlation-process")
        val other = registry.bindBot(scope.workspaceId, otherPrepared.assignmentId,
            otherStarted.revision, "other-correlation-bot")
        val otherEvidence = CrewCoordinationEvidence.create(scope.executionSessionId, other.missionId,
            requireNotNull(other.botId), UUID.randomUUID().toString(), "source-message-from-another-assignment",
            "QUESTION", "Other assignment question", emptyList(), 5678L)
        ledger.appendCrewCoordinationEvidence(otherEvidence, noAdmission)
        val otherReference = WorkspaceCoordinationReference.create(otherEvidence, scope.ownerCentralSessionId,
            scope.workspaceId, other.assignmentId, other.requestId, other.revision)
        ledger.appendWorkspaceCoordinationReferenceIfAbsent(otherReference, noAdmission)

        assertFalse(otherReference.assignmentId == target.assignmentId)
        assertEquals(otherReference.referenceId, requireNotNull(
            ledger.findWorkspaceCoordinationReferenceBySourceMessageId(scope.executionSessionId,
                otherEvidence.messageId)).referenceId)
        failure {
            delivery.resolveMessageCorrelation(scope.workspaceId, target.assignmentId, worker.bot.id,
                target.revision, otherEvidence.messageId)
        }
    }

    @Test fun failedCrewAdmissionReportsTheCommittedOutboxAsPendingDelivery() {
        val worker = Parked().also { parked = it }
        bind(worker.bot.id)
        worker.manager.bindWorkspaceAssignmentId(worker.bot.id, target.assignmentId)
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) { }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                throw AssertionError("A message attempt must not resume a worker")
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.STATUS,
            "Checkpoint is available", emptyList())
        assertTrue(worker.bot.hasDurableCheckpoint())
        val checkpointWrites = AtomicInteger()
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                checkpointWrites.incrementAndGet()
                throw IllegalStateException("Synthetic post-outbox admission failure")
            }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                throw AssertionError("A message attempt must not resume a worker")
        })
        try {
            val receipt = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
                "message-key", "Synthetic supervisor message", null, "central-request",
                "Synthetic original request", freshToken, noAdmission)
            assertEquals("PENDING_DELIVERY", receipt.getString("state"))
            assertTrue(receipt.getBoolean("durable_intent_committed"))
            assertFalse(receipt.getBoolean("delivery_confirmed"))
            assertFalse(receipt.getBoolean("admission_committed"))
            assertTrue(WorkspaceConversationInputStore.hasSupervisorIntent(app, scope.executionSessionId,
                scope.workspaceId, target.assignmentId, "message-key"))
            val pending = ledger.readPendingInternalConversationInputs(scope.executionSessionId)
            assertTrue(pending.any { row ->
                row.optJSONObject("provenance")?.optString("messageId") == receipt.getString("worker_input_id")
            })

            worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
                override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                    checkpointWrites.incrementAndGet()
                    throw IllegalStateException("Synthetic retry checkpoint failure")
                }
                override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                    throw AssertionError("A message retry must not resume a worker")
            })
            val stillPending = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
                "message-key", "Synthetic supervisor message", null, "central-request",
                "Synthetic original request", freshToken, noAdmission)
            assertEquals("PENDING_DELIVERY", stillPending.getString("state"))
            assertFalse(stillPending.getBoolean("admission_committed"))
            assertFalse(stillPending.getBoolean("delivery_confirmed"))
            assertEquals(2, checkpointWrites.get())

            worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
                override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                    checkpointWrites.incrementAndGet()
                    assertTrue("retry must persist the exact queued receipt", pending.any { it.id == receipt.getString("worker_input_id") })
                }
                override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                    throw AssertionError("A message retry must not resume a worker")
            })
            val retried = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
                "message-key", "Synthetic supervisor message", null, "central-request",
                "Synthetic original request", freshToken, noAdmission)
            assertEquals("PENDING_INCORPORATION", retried.getString("state"))
            assertTrue(retried.getBoolean("admission_committed"))
            assertTrue(retried.getBoolean("delivery_confirmed"))
            assertEquals(3, checkpointWrites.get())
            assertEquals("exact retry must not duplicate the mailbox message", 1,
                worker.manager.messageBus().pending(worker.bot.id).count { it.id == retried.getString("worker_input_id") })
        } finally { worker.manager.configureCheckpoints(null) }
    }

    @Test fun transientPreAdmissionFailureLeavesSupervisorIntentPendingForExactRetry() {
        val worker = Parked(profileGuarded = true).also { parked = it }
        bind(worker.bot.id)
        worker.manager.bindWorkspaceAssignmentId(worker.bot.id, target.assignmentId)
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) { }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                throw AssertionError("A message attempt must not resume a worker")
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.STATUS,
            "Checkpoint is available", emptyList())
        assertTrue(worker.bot.hasDurableCheckpoint())
        val role = worker.bot.role
        val roomUnavailable = AtomicBoolean(true)
        worker.manager.configureProfiles({ id ->
            if (roomUnavailable.get()) throw android.database.sqlite.SQLiteException("Synthetic Room read failure")
            if (id == role.id) role else null
        }, emptyList())

        val first = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
            "transient-message-key", "Synthetic supervisor message", null, "central-request",
            "Synthetic original request", freshToken, noAdmission)
        assertEquals("PENDING_DELIVERY", first.getString("state"))
        assertTrue(first.getBoolean("durable_intent_committed"))
        assertFalse(first.getBoolean("admission_committed"))
        val inputId = first.getString("worker_input_id")
        val inputRowId = requireNotNull(ledger.readConversationMessageByStableId(scope.executionSessionId,
            ConversationInput.stableId(ConversationInput.Origin.AGENT, central, inputId))).getString("messageId")
        assertNull("transient pre-admission failures must not settle the durable row",
            ledger.readConversationInputState(scope.executionSessionId, inputRowId))
        assertTrue(worker.manager.messageBus().snapshot().none { it.id == inputId })

        roomUnavailable.set(false)
        val retried = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
            "transient-message-key", "Synthetic supervisor message", null, "central-request",
            "Synthetic original request", freshToken, noAdmission)
        assertEquals("PENDING_INCORPORATION", retried.getString("state"))
        assertEquals(inputId, retried.getString("worker_input_id"))
        assertTrue(retried.getBoolean("admission_committed"))
        assertTrue(retried.getBoolean("delivery_confirmed"))
        assertEquals(1, worker.manager.messageBus().pending(worker.bot.id).count { it.id == inputId })

        val checkpointAvailable = worker.bot.javaClass.getDeclaredField("checkpointAvailable").apply { isAccessible = true }
        val admissions = AtomicInteger()
        val loseCheckpointAfterPreflight = Runnable {
            if (admissions.incrementAndGet() == 2) checkpointAvailable.setBoolean(worker.bot, false)
        }
        val noCheckpoint = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
            "no-checkpoint-message-key", "Message waits for a durable checkpoint", null, "central-request",
            "Synthetic original request", freshToken, loseCheckpointAfterPreflight)
        assertEquals("PENDING_DELIVERY", noCheckpoint.getString("state"))
        assertFalse(noCheckpoint.getBoolean("admission_committed"))
        val noCheckpointId = noCheckpoint.getString("worker_input_id")
        val noCheckpointRowId = requireNotNull(ledger.readConversationMessageByStableId(scope.executionSessionId,
            ConversationInput.stableId(ConversationInput.Origin.AGENT, central, noCheckpointId)))
            .getString("messageId")
        assertNull("a missing checkpoint is retryable, not a final rejection",
            ledger.readConversationInputState(scope.executionSessionId, noCheckpointRowId))

        checkpointAvailable.setBoolean(worker.bot, true)
        val resumed = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
            "no-checkpoint-message-key", "Message waits for a durable checkpoint", null, "central-request",
            "Synthetic original request", freshToken, noAdmission)
        assertEquals("PENDING_INCORPORATION", resumed.getString("state"))
        assertEquals(noCheckpointId, resumed.getString("worker_input_id"))
        assertTrue(resumed.getBoolean("admission_committed"))
    }

    @Test fun deterministicCrewRejectionSettlesTheOutboxAndReturnsFinalReceipt() {
        val worker = Parked(profileGuarded = true).also { parked = it }
        bind(worker.bot.id)
        worker.manager.bindWorkspaceAssignmentId(worker.bot.id, target.assignmentId)
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) { }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                throw AssertionError("A message attempt must not resume a worker")
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.STATUS,
            "Checkpoint is available", emptyList())
        assertTrue(worker.bot.hasDurableCheckpoint())
        worker.profileEnabled.set(false)

        val receipt = delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
            "rejected-message-key", "Synthetic supervisor message", null, "central-request",
            "Synthetic original request", freshToken, noAdmission)
        assertEquals("REJECTED", receipt.getString("state"))
        assertFalse(receipt.getBoolean("admission_committed"))
        assertFalse(receipt.getBoolean("delivery_confirmed"))
        val inputId = receipt.getString("worker_input_id")
        assertEquals("resolved", ledger.readConversationInputState(scope.executionSessionId,
            requireNotNull(ledger.readConversationMessageByStableId(scope.executionSessionId,
                ConversationInput.stableId(ConversationInput.Origin.AGENT, central, inputId)).getString("messageId"))))
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(app, scope.executionSessionId,
            target.assignmentId).none { it.messageId == inputId })
        assertEquals("REJECTED", delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id,
            target.revision, "rejected-message-key", "Synthetic supervisor message", null,
            "central-request", "Synthetic original request", freshToken, noAdmission).getString("state"))
        assertTrue(worker.manager.messageBus().snapshot().none { it.id == inputId })
    }

    @Test fun cancellationAfterOutboxCommitPropagatesInsteadOfBecomingPendingReceipt() {
        val worker = Parked().also { parked = it }
        bind(worker.bot.id)
        worker.manager.bindWorkspaceAssignmentId(worker.bot.id, target.assignmentId)
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) { }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                throw AssertionError("A message attempt must not resume a worker")
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.STATUS,
            "Checkpoint is available", emptyList())
        val token = CancellationToken.cancellable()
        val priorRevision = WorkspaceChanges.revision.value
        val cancelAfterOutbox = CoroutineScope(Dispatchers.Unconfined).launch {
            WorkspaceChanges.revision.collect { revision -> if (revision > priorRevision) token.cancel() }
        }
        try {
            val thrown = failure {
                delivery.message(scope.workspaceId, target.assignmentId, worker.bot.id, target.revision,
                    "cancelled-message-key", "Synthetic supervisor message", null, "central-request",
                    "Synthetic original request", token, noAdmission)
            }
            assertTrue(thrown is CancellationException)
            assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(app, scope.executionSessionId,
                target.assignmentId).any { it.content == "Synthetic supervisor message" })
        } finally { cancelAfterOutbox.cancel() }
    }

    @Test fun reconciliationInvalidatesOnceForNewVisibleEvidenceAndNeverForAnIdempotentScan() {
        bind(); evidence()
        val detailBefore = WorkspaceChanges.revision.value
        val catalogBefore = WorkspaceChanges.catalogRevision.value

        reconcile()
        assertEquals(detailBefore + 1, WorkspaceChanges.revision.value)
        assertEquals(catalogBefore + 1, WorkspaceChanges.catalogRevision.value)
        val detailAfterInsert = WorkspaceChanges.revision.value
        val catalogAfterInsert = WorkspaceChanges.catalogRevision.value

        reconcile()

        assertEquals("replaying the same evidence writes no invalidation", detailAfterInsert,
            WorkspaceChanges.revision.value)
        assertEquals("replaying the same evidence does not reload the catalog", catalogAfterInsert,
            WorkspaceChanges.catalogRevision.value)
    }

    @Test fun checkpointScheduleWithNoVisibleEvidenceDoesNotInvalidateCatalogOrDetail() {
        bind()
        val detailBefore = WorkspaceChanges.revision.value
        val catalogBefore = WorkspaceChanges.catalogRevision.value

        // CoreAgentRuntime's committed-checkpoint listener enters this same scheduling route.
        WorkspaceCoordinationDelivery.schedule(app, scope.executionSessionId)
        awaitScheduledReceipts()

        assertEquals("a checkpoint-triggered no-op scan has no detail change", detailBefore,
            WorkspaceChanges.revision.value)
        assertEquals("a checkpoint-triggered no-op scan does not reload catalog rows", catalogBefore,
            WorkspaceChanges.catalogRevision.value)
    }

    @Test fun asciiFragmentsReconstructAllUtf16TextAndRefsAcrossEscapeBoundaries() {
        bind()
        val exactText = "a".repeat(7988) + "\u0000\u0001\n\t\"\\中文🚀\uD800x\uDC00" + "尾".repeat(3500)
        val exactRefs = listOf("ref\n\"\\\u0000\uD800", "🚀".repeat(1600), "", "\uDC00")
        evidence(exactText, exactRefs); reconcile(); val r = references().single()
        var offset = 0
        val content = StringBuilder()
        var pages = 0
        var digest: String? = null
        do {
            val page = delivery.read(scope.workspaceId, r.referenceId, r.contentSha256, offset, freshToken, noAdmission)
            assertEquals("coordination-content-json-ascii-v1", page.getString("content_format"))
            val fragment = page.getString("content_json_fragment")
            assertTrue(fragment.all { it.code in 0x20..0x7e })
            assertEquals(offset, page.getInt("offset"))
            assertTrue(page.toString().length < CentralWorkspaceTool.MAX_OUTPUT_CHARS - 1024)
            assertEquals(r.contentSha256, page.getString("expected_sha256"))
            assertFalse(page.getBoolean("refs_verified")); assertFalse(page.getBoolean("user_or_model_consumption_verified"))
            if (digest == null) digest = page.getString("content_sha256") else assertEquals(digest, page.getString("content_sha256"))
            content.append(fragment); pages++
            if (page.isNull("next_offset")) { assertEquals(content.length, page.getInt("total_chars")); break }
            assertEquals(offset + 8000, page.getInt("next_offset"))
            offset = page.getInt("next_offset")
        } while (pages < 20)
        assertTrue(pages > 2)
        val reconstructed = JSONObject(content.toString())
        assertEquals(exactText, reconstructed.getString("text"))
        assertEquals(exactRefs, (0 until reconstructed.getJSONArray("refs").length()).map { reconstructed.getJSONArray("refs").getString(it) })
        assertEquals(digest, MessageDigest.getInstance("SHA-256").digest(content.toString().toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) })
        assertEquals(1, notices().size)
    }

    @Test fun maximumIdentifierInboxFitsWireBudgetAndCursorNeverSkipsRows() {
        bind()
        repeat(53) { index -> evidence(kind = "FINDING", message = index.toString().padStart(3, '0') + "m".repeat(157)) }
        reconcile()
        val all = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = delivery.inbox(scope.workspaceId, 50, cursor, noAdmission)
            val messages = page.getJSONArray("messages")
            val ids = (0 until messages.length()).map { messages.getJSONObject(it).getString("message_id") }
            assertEquals(ids.sorted(), ids)
            assertTrue(page.toString().length < CentralWorkspaceTool.MAX_OUTPUT_CHARS - 1024)
            assertTrue(ids.none { it in all })
            all += ids; pages++
            if (page.isNull("next_after_message_id")) break
            assertTrue(ids.isNotEmpty())
            cursor = page.getString("next_after_message_id")
            assertEquals(ids.last(), cursor)
        } while (pages < 10)
        assertEquals(53, all.size); assertTrue(pages >= 2)
    }

    @Test fun wrongHashOwnerAndDeletedSourceCannotDiscloseCachedEvidence() {
        bind(); evidence(); reconcile(); val r = references().single()
        failure { delivery.read(scope.workspaceId, r.referenceId, "0".repeat(64), 0, freshToken, noAdmission) }
        val other = registry.createWorkspace("other", "Other synthetic owner")
        failure { delivery.read(other.workspaceId, r.referenceId, r.contentSha256, 0, freshToken, noAdmission) }
        var checks = 0
        val deleteAtFinalDisclosure = Runnable { if (++checks == 3) ledger.deleteConversation(scope.executionSessionId) }
        failure { read(r, admission = deleteAtFinalDisclosure) }
        assertEquals(3, checks)
        failure { delivery.inbox(scope.workspaceId, 50, null, noAdmission) }
    }

    @Test fun stoppedForegroundReadDoesNotReturnEvidence() {
        bind(); evidence(); reconcile()
        val r = references().single()
        val stopped = CancellationToken.cancellable().also { it.cancel() }
        failure { read(r, stopped) }; assertEquals(1, notices().size)
    }

    @Test fun replyRequiresExactNoticeThenAcceptsOnceAndHistoricalRetryNeverFindsRuntime() {
        val (worker, r) = liveQuestionWithoutNotice()
        ledger.appendConversationMessage(central, "user", "An unrelated Central row is not a coordination read")
        failure { reply(r) }; assertNull(savedReply(r)); assertTrue(worker.answers().isEmpty())
        assertTrue(notices().none { it.referenceId == r.referenceId })
        read(r)
        assertTrue(notices().any { it.referenceId == r.referenceId })
        val accepted = reply(r)
        assertEquals("PENDING_INCORPORATION", accepted.getString("state"))
        assertTrue(accepted.getBoolean("admission_committed"))
        assertFalse(accepted.getBoolean("user_or_model_consumption_verified")); assertFalse(accepted.getBoolean("question_resolved"))
        val answer = worker.answers().single()
        assertEquals("chief", answer.from); assertEquals(CrewMessage.Type.ANSWER, answer.type)
        assertEquals(r.messageId, answer.coordination.messageId)
        assertEquals("Untrusted chief answer", answer.text)
        worker.manager.stop(worker.bot.id)
        val reopened = WorkspaceCoordinationDelivery(app, LocalRunStore(app)) { throw AssertionError("A saved receipt must not look up, prepare, or resume a runtime") }
        assertEquals(accepted.toString(), reply(r, backend = reopened).toString())
        failure { reply(r, key = "different-key", backend = reopened) }
        failure { reply(r, text = "Changed payload", backend = reopened) }
        assertEquals(1, worker.answers().size)
        assertFalse(ledger.readConversationContext(central).contains("Untrusted chief answer"))
        assertFalse(ledger.buildReflectionPayload(central).text.contains("Untrusted chief answer"))
        assertEquals(listOf("An unrelated Central row is not a coordination read"),
            ledger.readConversationMessages(central).map { it.getString("content") })
    }

    @Test fun explicitBlockerIsReplyableFindingIsReadOnlyAndStatusIsNeverInferred() {
        val worker = Parked().also { parked = it }; bind(worker.bot.id)
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.STATUS,
            "blocked: please answer this question", emptyList())
        reconcile(); assertTrue(references().isEmpty())
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.FINDING,
            "An unverified observation", listOf("unverified-path"))
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.BLOCKER,
            "Which synthetic dependency should I use?", emptyList())
        reconcile()
        val finding = references().single { it.kind == "FINDING" }
        assertEquals("READ_ONLY_FINDING", read(finding).getString("reply_state"))
        failure { reply(finding) }; assertNull(savedReply(finding))
        val blocker = references().single { it.kind == "BLOCKER" }
        assertEquals("LIVE_REPLYABLE", read(blocker).getString("reply_state"))
        assertEquals("PENDING_INCORPORATION", reply(blocker).getString("state"))
        assertEquals(blocker.messageId, worker.answers().single().coordination.messageId)
    }

    @Test fun corruptRegistryOwnerAndDeletedCentralReceiptFailClosed() {
        bind(); evidence(); reconcile(); val r = references().single()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE workspace SET owner_central_session_id=? WHERE workspace_id=?", arrayOf("foreign-owner", scope.workspaceId))
            failure { read(r) }; failure { delivery.inbox(scope.workspaceId, 50, null, noAdmission) }
            db.execSQL("UPDATE workspace SET owner_central_session_id=? WHERE workspace_id=?", arrayOf(central, scope.workspaceId))
        }
        var checks = 0
        failure { read(r, admission = Runnable { if (++checks == 3) ledger.deleteConversation(central) }) }
        assertEquals(3, checks)
    }

    @Test fun startedCrashGapIsUncertainAndNewKeyCannotRedispatch() {
        val (worker, r) = liveQuestion()
        ledger.markWorkspaceCoordinationReplyStarted(prepared(r), noAdmission)
        val reopened = WorkspaceCoordinationDelivery(app, LocalRunStore(app)) { throw AssertionError("STARTED must not look up a manager") }
        val receipt = reply(r, backend = reopened)
        assertEquals("UNCERTAIN_NO_REPLAY", receipt.getString("state"))
        assertFalse(receipt.getBoolean("admission_committed")); assertTrue(worker.answers().isEmpty())
        failure { reply(r, key = "bypass-key", backend = reopened) }
        assertEquals("UNCERTAIN_NO_REPLAY", read(r).getString("reply_state"))
    }

    @Test fun postEnqueuePersistenceFailureRetainsUncertaintyAndNeverReplays() {
        val (worker, r) = liveQuestion()
        worker.manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
            override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                throw IllegalStateException("Synthetic post-enqueue checkpoint failure")
            }
            override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan = throw AssertionError("Never resume")
        })
        try {
            failure { reply(r) }
            assertEquals(1, worker.answers().size)
            assertEquals(WorkspaceCoordinationReply.State.STARTED, savedReply(r).state)
            assertEquals("UNCERTAIN_NO_REPLAY", reply(r).getString("state"))
            failure { reply(r, key = "retry-new-key") }
            assertEquals(1, worker.answers().size)
        } finally { worker.manager.configureCheckpoints(null) }
    }

    @Test fun preparedReplyRemainsUnsentAndStopOrReopenNeverDispatchesIt() {
        val (worker, r) = liveQuestion(); prepared(r)
        assertEquals("PREPARED_NOT_DISPATCHED", read(r).getString("reply_state"))
        worker.manager.stop(worker.bot.id)
        assertEquals("HISTORICAL_OR_INTERRUPTED", read(r).getString("reply_state"))
        val reopened = WorkspaceCoordinationDelivery(app, LocalRunStore(app)) { null }
        assertEquals("HISTORICAL_OR_INTERRUPTED", reopened.read(scope.workspaceId, r.referenceId,
            r.contentSha256, 0, CancellationToken.uncancellable(), Runnable {}).getString("reply_state"))
        failure { reply(r, backend = reopened) }
        assertEquals(WorkspaceCoordinationReply.State.PREPARED, savedReply(r).state); assertTrue(worker.answers().isEmpty())
    }

    @Test fun pendingNativeControlAndSameCycleCorrectionInvalidatePreparedRequest() {
        val (worker, r) = liveQuestion(); prepared(r)
        val control = registry.nativeControls().submit(UUID.randomUUID().toString(), scope.workspaceId,
            target.assignmentId, worker.bot.id, target.revision, WorkspaceControlType.CORRECT, "Synthetic native correction")
        assertEquals("NATIVE_CONTROL_PENDING", read(r).getString("reply_state"))
        failure { reply(r) }; assertTrue(worker.answers().isEmpty())
        val started = registry.markControlStarted(control.requestId, control.revision, "synthetic-process")
        val cycle = worker.bot.cycleId()
        synchronized(WorkspaceCoordinator.controlDispatchLock(target.assignmentId)) {
            worker.manager.sendUserMessage(worker.bot.id, "Synthetic native correction")
            registry.completeControl(started.requestId, started.revision)
        }
        assertEquals(cycle, worker.bot.cycleId())
        assertEquals("HISTORICAL_OR_SUPERSEDED", read(r).getString("reply_state"))
        failure { reply(r) }; assertTrue(worker.answers().isEmpty())
        assertEquals(WorkspaceCoordinationReply.State.PREPARED, savedReply(r).state)
    }

    @Test fun concurrentReadAndReplySharingStopControllerComplete() {
        val (worker, question) = liveQuestion()
        worker.manager.send(worker.bot.id, "chief", CrewMessage.Type.FINDING, "Separate synthetic notice", emptyList())
        reconcile()
        val finding = references().single { it.kind == "FINDING" }
        val stop = StopController.getInstance(); stop.stopRun(); val token = requireNotNull(stop.beginRun())
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val readThread = thread(failure) {
            await(start)
            read(finding, token, noAdmission)
        }
        val replyThread = thread(failure) {
            await(start)
            reply(question, token = token)
        }
        try {
            start.countDown()
            join(readThread); join(replyThread)
            failure.get()?.let { throw AssertionError("Concurrent integration operation failed", it) }
            assertEquals(2, notices().size); assertEquals(1, worker.answers().size)
        } finally {
            start.countDown()
            join(readThread); join(replyThread)
        }
    }

    private inner class Parked(profileGuarded: Boolean = false) : AutoCloseable {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val observed = AtomicReference<CrewCoordinationEvidence>()
        private val modelCalls = AtomicInteger()
        val profileEnabled = AtomicBoolean(true)
        val manager: CrewManager
        val bot: CrewManager.Bot
        init {
            val empty = CoreToolRegistry(emptyList())
            manager = CrewManager(scope.executionSessionId, empty, { _, _ -> empty }, { _, tools, inbox ->
                CoreAgentLoop({ _, _, _, token ->
                    modelCalls.incrementAndGet(); started.countDown()
                    try { assertTrue("Synthetic worker was not released", release.await(30, TimeUnit.SECONDS)) }
                    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled(); throw interrupted }
                    token.throwIfCancelled(); ModelReply("Synthetic worker completed", emptyList())
                }, tools, "", scope.executionSessionId, CorePromptBudget.standard(), null,
                    CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null)
            }, null)
            manager.configureCoordinationEvidenceListener { source ->
                ledger.appendCrewCoordinationEvidence(source, noAdmission)
                observed.set(source)
            }
            bot = if (profileGuarded) {
                val role = CrewRole("coordination-profile", "Synthetic worker", "custom", "Synthetic task",
                    emptyList(), null, "Synthetic profile", 1, emptyList())
                manager.configureProfiles({ id -> if (profileEnabled.get() && id == role.id) role else null }, emptyList())
                manager.spawnForMission(target.missionId, target.originalUserRequest, role.id, "Synthetic task",
                    null, "Synthetic worker", CrewMissionAccess.STANDARD, "Synthetic coordination")
            } else manager.spawnForMission(target.missionId, target.originalUserRequest, "custom", "Synthetic task",
                emptyList(), "Synthetic worker", CrewMissionAccess.STANDARD, "Synthetic coordination")
            await(started)
        }
        fun question(text: String = "Synthetic live question", refs: List<String> = emptyList()): CrewCoordinationEvidence {
            manager.sendFromWorker(bot, bot.token, "chief", CrewMessage.Type.QUESTION, text, refs)
            return requireNotNull(observed.get())
        }
        fun answers() = manager.messageBus().snapshot().filter { it.type == CrewMessage.Type.ANSWER }
        override fun close() {
            release.countDown(); manager.close()
            assertEquals("Reads/replies must never spawn another model cycle", 1, modelCalls.get())
        }
    }
    private fun await(latch: CountDownLatch) = assertTrue("Bounded latch timed out", latch.await(5, TimeUnit.SECONDS))
    private fun join(thread: Thread) { thread.join(5000); assertFalse("Thread did not finish: ${thread.name}", thread.isAlive) }
    private fun thread(failure: AtomicReference<Throwable>, work: () -> Unit) = Thread {
        try { work() } catch (error: Throwable) { failure.compareAndSet(null, error) }
    }.apply { isDaemon = true; start() }
}

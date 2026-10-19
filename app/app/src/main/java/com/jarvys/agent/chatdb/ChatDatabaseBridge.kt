package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Looper
import com.jarvys.agent.AgentErrorReporter
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.proactive.ProactiveReplyClaim
import com.jarvys.agent.crew.CrewCoordinationEvidence as CrewCoordinationJournalEvidence
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewResultEvidence as CrewResultJournalEvidence
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply as WorkspaceCoordinationReplyJournal
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation as WorkspaceInputIncorporationJournal
import com.jarvys.agent.workspaces.WorkspaceResultReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Java callback used for cancellation/admission checks immediately before a strict journal write. */
fun interface BeforeChatWrite {
    fun check()
}

/** The caller stopped waiting; a submitted Room operation is allowed to finish atomically. */
class ChatDatabaseWaitInterruptedException(cause: InterruptedException) :
    CancellationException("Interrupted while waiting for the chat database") {
    init { initCause(cause) }
}

/** Public timeline projection that does not expose the app-internal ConversationReader type. */
data class ChatTimelineEventPage(
    val events: List<AgentRunUiEvent>,
    val olderCursorSeq: Long?,
    val invalidatedToolTurnMessageIds: Set<String>,
) {
    val hasOlder: Boolean get() = olderCursorSeq != null
}

/**
 * Blocking Java facade over the normalized chat database. Instances are shared by canonical
 * application files directory and keep one daemon executor for their process lifetime. Database
 * opening is lazy: the provider is first called from that executor on the first `*Sync` method.
 *
 * Every entry rejects the main looper, active Room/read leases, and strict-journal callback
 * re-entry before submitting work. Results retain their typed [ChatDbResult] state; exceptions
 * from database initialization and unexpected repository failures are propagated to Java.
 */
class ChatDatabaseBridge private constructor(context: Context, injectedExecutor: ExecutorService? = null) {
    @Volatile
    private var executorThread: Thread? = null

    private val applicationContext = context.applicationContext
    private val executor: ExecutorService = injectedExecutor ?: Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-java-bridge").apply {
            isDaemon = true
            this@ChatDatabaseBridge.executorThread = this
        }
    }
    private val lifecycleLock = Any()
    private var closingForTests = false

    @Volatile
    private var repositories: Repositories? = null

    @Volatile
    private var initializationFailure: Exception? = null

    private data class Repositories(
        val database: JarvysChatDatabase,
        val conversations: ConversationRepository,
        val transcript: ModelTranscriptRepository,
        val memory: MemoryTimelineRepository,
        val artifacts: ConversationArtifactsRepository,
        val strictJournals: StrictJournalRepository,
        val reader: ConversationReader,
        val modelTurnWriter: ConversationModelTurnWriter,
        val agentMemory: PersonalAgentRepository,
    )

    companion object {
        private val instances = mutableMapOf<String, ChatDatabaseBridge>()

        /** Test-only: Robolectric runs UI tests ON the main looper; production keeps the guard on. */
        @Volatile @JvmField internal var mainThreadGuardDisabledForTests = false

        /** Set only by Gradle for JVM unit tests (system property); never present in the app process. */
        private val UNIT_TEST_MAIN_THREAD_GUARD_OFF: Boolean =
            System.getProperty("jarvys.unitTests.mainThreadGuardOff") == "true"

        /** Returns the per-filesDir facade. Creating it does not open SQLite. */
        @JvmStatic
        fun getInstance(context: Context): ChatDatabaseBridge {
            val appContext = context.applicationContext
            val key = File(appContext.filesDir, "jarvys").canonicalPath
            return synchronized(instances) {
                instances.getOrPut(key) { ChatDatabaseBridge(appContext) }
            }
        }

        /** Constructs the same bridge with a caller-owned executor for isolated database tests. */
        @JvmStatic
        fun getInstance(context: Context, executor: ExecutorService): ChatDatabaseBridge {
            val appContext = context.applicationContext
            val key = File(appContext.filesDir, "jarvys").canonicalPath
            return synchronized(instances) {
                instances[key] ?: ChatDatabaseBridge(appContext, executor).also { instances[key] = it }
            }
        }

        /** Returns an already registered bridge without constructing a Context or opening SQLite. */
        @JvmStatic
        fun findInstanceForFilesDirectory(filesDirectory: File): ChatDatabaseBridge? {
            val key = File(filesDirectory, "jarvys").canonicalPath
            return synchronized(instances) { instances[key] }
        }

        /** Closes one isolated test bridge and its Room/provider resources by application filesDir. */
        @JvmStatic
        fun closeInstanceForTests(filesDirectory: File) {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "ChatDatabaseBridge test cleanup must not block the main thread"
            }
            val key = File(filesDirectory, "jarvys").canonicalPath
            val instance = synchronized(instances) { instances.remove(key) }
            instance?.closeForTests()
        }

        /** Converts typed repository failures to an exception for legacy Java-facing facades. */
        @JvmStatic
        fun <T> requireSuccessSync(result: ChatDbResult<T>, operation: String): T = when (result) {
            is ChatDbResult.Success -> result.value
            ChatDbResult.NotFound -> throw IllegalStateException("$operation: record not found")
            ChatDbResult.Tombstoned -> throw IllegalStateException("$operation: conversation was deleted")
            is ChatDbResult.Conflict -> throw IllegalStateException("$operation: ${result.reason}")
            is ChatDbResult.Invalid -> throw IllegalArgumentException("$operation: ${result.reason}")
        }

        /** Java-friendly factory alias for [getInstance]. */
        @JvmStatic
        fun create(context: Context): ChatDatabaseBridge = getInstance(context)
    }

    private fun repositories(): Repositories {
        repositories?.let { return it }
        return synchronized(this) {
            repositories?.let { return@synchronized it }
            initializationFailure?.let {
                throw IllegalStateException("Chat database is unavailable after a failed open", it)
            }
            try {
                // This method only runs from execute()'s background task. Provider initialization
                // and its schema preflight therefore cannot block the caller's main thread.
                val database = JarvysChatDatabaseProvider.getInstance(applicationContext)
                val conversations = ConversationRepository(database)
                val transcript = ModelTranscriptRepository(database)
                val strictJournals = StrictJournalRepository(database)
                Repositories(
                    database,
                    conversations,
                    transcript,
                    MemoryTimelineRepository(database),
                    ConversationArtifactsRepository(database),
                    strictJournals,
                    ConversationReader(database),
                    ConversationModelTurnWriter(database, conversations, transcript),
                    PersonalAgentRepository(database),
                ).also { repositories = it }
            } catch (failure: Exception) {
                // A corrupt or inaccessible database must not trigger an open/retry loop. Keep the
                // original files intact, record one sanitized diagnostic, and fail later requests
                // quickly with the cached cause until the process is restarted.
                initializationFailure = failure
                AgentErrorReporter.reportChatDatabaseFailure(applicationContext, failure)
                throw IllegalStateException("Could not open the chat database", failure)
            }
        }
    }

    /** Agent memory is an ordinary Room repository. These methods retain the bridge's background and error-reporting contract. */
    private fun <T> withAgentMemory(operation: String, block: suspend (PersonalAgentRepository) -> T): T = try {
        execute { repositories -> block(repositories.agentMemory) }
    } catch (failure: Exception) {
        AgentErrorReporter.reportChatDatabaseFailure(applicationContext, failure)
        throw IllegalStateException("Could not $operation in the personal agent database", failure)
    }

    fun personalAgentPromptContext(conversationId: String, privateMode: Boolean): PersonalAgentPromptContext =
        personalAgentPromptContext(conversationId, privateMode, "principal")

    fun personalAgentPromptContext(conversationId: String, privateMode: Boolean,
                                   sessionKind: String): PersonalAgentPromptContext =
        withAgentMemory("read personal context") { it.promptContext(conversationId, privateMode, sessionKind) }

    fun personalAgentSetDirective(section: String, directive: String, sourceMessageId: String?, evidence: String,
                                  supersedesId: String? = null, conversationId: String? = null): PersonalMemoryReceipt =
        withAgentMemory("save user directive") { it.setDirective(section, directive, sourceMessageId, evidence, supersedesId, conversationId) }

    fun personalAgentSaveMemory(text: String, layer: String, category: String?, importance: Double,
                                sourceRef: String?): PersonalMemoryReceipt =
        withAgentMemory("save memory") { it.saveMemory(text, layer, category, importance, sourceRef) }

    fun personalAgentDailyConsolidationCandidates(): List<PersonalMemoryCandidate> =
        withAgentMemory("read daily consolidation candidates") { it.dailyConsolidationCandidates() }

    fun personalAgentConsolidateDailyNotes(sourceIds: List<String>, summary: String): PersonalMemoryReceipt =
        withAgentMemory("consolidate daily notes") { it.consolidateDailyNotes(sourceIds, summary) }

    fun personalAgentSearch(query: String, limit: Int = 8): List<PersonalMemoryHit> =
        withAgentMemory("search memory") { it.search(query, limit) }

    fun personalAgentSearchCapability(): String =
        withAgentMemory("read memory search capability") { it.searchCapability() }

    fun personalAgentGet(ids: List<String>): List<PersonalMemoryHit> =
        withAgentMemory("read memory") { it.get(ids) }

    fun personalAgentBrowse(): List<PersonalMemoryItem> =
        withAgentMemory("browse memory") { it.browse() }

    fun personalAgentRevisions(targetId: String): List<PersonalMemoryRevision> =
        withAgentMemory("read directive revision history") { it.revisions(targetId) }

    fun personalAgentForget(id: String): PersonalMemoryReceipt =
        withAgentMemory("forget memory") { it.forget(id) }

    fun personalAgentCorrect(id: String, text: String): PersonalMemoryReceipt =
        withAgentMemory("correct memory") { it.correct(id, text) }

    fun personalAgentSetProfile(name: String, vibe: String, emoji: String, bootstrapCompleted: Boolean = true): PersonalMemoryReceipt =
        withAgentMemory("save assistant profile") { it.saveAgentProfile(name, vibe, emoji, bootstrapCompleted) }

    fun personalAgentCompleteBootstrap(conversationId: String): Boolean =
        withAgentMemory("complete bootstrap") { it.setBootstrapComplete(conversationId) }

    fun personalAgentMarkCompacted(conversationId: String, kind: String, flushCompleted: Boolean): Long =
        withAgentMemory("record compaction state") { it.markCompacted(conversationId, kind, flushCompleted) }

    fun personalAgentNeedsMemoryFlush(conversationId: String): Boolean =
        withAgentMemory("read memory flush state") { it.needsMemoryFlush(conversationId) }

    fun personalAgentMarkMemoryFlushCompleted(conversationId: String, kind: String): Boolean =
        withAgentMemory("record memory flush") { it.markMemoryFlushCompleted(conversationId, kind) }

    fun personalAgentEnqueueJob(conversationId: String?, type: String = "extract"): String =
        withAgentMemory("queue memory maintenance") { it.enqueueMemoryJob(conversationId, type) }

    fun personalAgentClaimJob(id: String, owner: String, now: Long, leaseUntil: Long): PersonalMemoryJob? =
        withAgentMemory("claim memory maintenance") { it.claimExtractionJob(id, owner, now, leaseUntil) }

    fun personalAgentCompleteJob(id: String, owner: String): Boolean =
        withAgentMemory("complete memory maintenance") { it.completeJob(id, owner) }

    fun personalAgentRetryJob(id: String, owner: String, error: String, availableAt: Long): Boolean =
        withAgentMemory("retry memory maintenance") { it.retryJob(id, owner, error, availableAt) }

    private fun <T> execute(block: suspend (Repositories) -> T): T {
        ChatSqlBeforeWriteGuard.checkRepositoryAccess()
        check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null) {
            "ChatDatabaseBridge cannot be called inside an active chat database lease"
        }
        check(Thread.currentThread() !== executorThread) {
            "ChatDatabaseBridge cannot be re-entered from its own executor thread"
        }
        check(mainThreadGuardDisabledForTests || UNIT_TEST_MAIN_THREAD_GUARD_OFF || Looper.myLooper() != Looper.getMainLooper()) {
            "ChatDatabaseBridge blocking methods must not be called from the main thread"
        }
        return try {
            val pending = synchronized(lifecycleLock) {
                check(!closingForTests) { "ChatDatabaseBridge test instance is closed" }
                executor.submit(Callable {
                    val repos = repositories()
                    runBlocking { block(repos) }
                })
            }
            pending.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatDatabaseWaitInterruptedException(interrupted)
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    private fun closeForTests() {
        check(Thread.currentThread() !== executorThread) {
            "ChatDatabaseBridge test cleanup cannot run on its executor thread"
        }
        var closeFailure: Throwable? = null
        var interrupted = false
        try {
            val closeTask: Future<*> = synchronized(lifecycleLock) {
                closingForTests = true
                executor.submit(Callable {
                    val opened = repositories
                    repositories = null
                    opened?.let { JarvysChatDatabaseProvider.closeDatabaseFileForTests(it.database) }
                })
            }
            var finished = false
            while (!finished) {
                try {
                    closeTask.get()
                    finished = true
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (failure: ExecutionException) {
                    closeFailure = failure.cause ?: failure
                    finished = true
                }
            }
        } catch (failure: Throwable) {
            closeFailure = failure
        } finally {
            synchronized(lifecycleLock) {
                closingForTests = true
                executor.shutdown()
            }
            val thread = executorThread
            if (thread != null && thread !== Thread.currentThread()) {
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        closeFailure?.let { throw IllegalStateException("Could not close the chat database test instance", it) }
    }

    // Conversation metadata, messages, and immutable deletion state.
    @JvmOverloads
    fun ensureConversationSync(id: String, kind: String = "chat"): ChatDbResult<Conversation> =
        execute { it.conversations.ensureConversation(id, kind) }

    fun updateMetadataSync(
        conversationId: String,
        title: String?,
        generatedTitle: String?,
        pinned: Boolean?,
        archived: Boolean?,
        privateCode: Boolean?,
    ): ChatDbResult<Conversation> = execute {
        it.conversations.updateMetadata(conversationId, title, generatedTitle, pinned, archived, privateCode)
    }

    fun appendConversationTitleIfAbsentSync(conversationId: String, title: String?): ChatDbResult<Boolean> =
        execute { it.conversations.appendConversationTitleIfAbsent(conversationId, title) }

    fun renameConversationSync(conversationId: String, title: String?): ChatDbResult<Conversation> =
        execute { it.conversations.renameConversation(conversationId, title) }

    @JvmOverloads
    fun deleteConversationSync(
        conversationId: String,
        deletedAtMillis: Long = System.currentTimeMillis(),
    ): ChatDbResult<Boolean> =
        execute { it.conversations.deleteConversation(conversationId, deletedAtMillis) }

    fun isTombstonedSync(conversationId: String): Boolean = execute { it.conversations.isTombstoned(conversationId) }

    fun readConversationSync(conversationId: String): Conversation? =
        execute { it.conversations.readConversation(conversationId) }

    fun readConversationMetadataSync(conversationId: String): ConversationMetadataValue? =
        execute { it.conversations.readConversationMetadata(conversationId) }

    fun appendMessageSync(input: AppendMessageInput): ChatDbResult<StoredMessage> =
        execute { it.conversations.appendMessage(input) }

    fun updateMessageSnapshotSync(
        conversationId: String,
        messageId: String,
        parts: List<MessagePartInput>,
        status: String?,
        runId: String?,
        ownerUserMessageId: String?,
        durationMillis: Long?,
        updatedAtSeconds: Double,
    ): ChatDbResult<StoredMessage> = execute {
        it.conversations.updateMessageSnapshot(
            conversationId, messageId, parts, status, runId, ownerUserMessageId, durationMillis, updatedAtSeconds,
        )
    }

    fun finalizeMessageSync(
        conversationId: String,
        messageId: String,
        parts: List<MessagePartInput>,
        finalStatus: String,
        durationMillis: Long?,
        updatedAtSeconds: Double,
    ): ChatDbResult<StoredMessage> = execute {
        it.conversations.finalizeMessage(conversationId, messageId, parts, finalStatus, durationMillis, updatedAtSeconds)
    }

    fun appendProactiveAssistantMessageIfAbsentSync(
        conversationId: String,
        decisionId: String,
        content: String,
        threadKey: String?,
        replies: List<SuggestedReplyInput>,
    ): ChatDbResult<StoredMessage> = execute {
        it.conversations.appendProactiveAssistantMessageIfAbsent(conversationId, decisionId, content, threadKey, replies)
    }

    fun setTranslationSync(conversationId: String, messageId: String, language: String, content: String): ChatDbResult<Long> =
        execute { it.conversations.setTranslation(conversationId, messageId, language, content) }

    fun hideAssistantSync(conversationId: String, messageId: String, kind: String): ChatDbResult<Long> =
        execute { it.conversations.hideAssistant(conversationId, messageId, kind) }

    fun hideAssistantWithOwnerSync(
        conversationId: String,
        messageId: String,
        kind: String,
    ): ChatDbResult<AssistantMarkerValue> =
        execute { it.conversations.hideAssistantWithOwner(conversationId, messageId, kind) }

    fun regenerateIfLatestAssistantSync(
        conversationId: String,
        messageId: String,
    ): ChatDbResult<RegenerationPromptValue?> =
        execute { it.conversations.regenerateIfLatestAssistant(conversationId, messageId) }

    fun hideTranslationSync(conversationId: String, messageId: String): ChatDbResult<Long> =
        execute { it.conversations.hideTranslation(conversationId, messageId) }

    fun setReactionSync(conversationId: String, stableMessageId: String, emoji: String): ChatDbResult<ReactionUpdate> =
        execute { it.conversations.setReaction(conversationId, stableMessageId, emoji) }

    fun consumeSuggestedRepliesSync(conversationId: String, messageId: String): ChatDbResult<Long> =
        execute { it.conversations.consumeSuggestedReplies(conversationId, messageId) }

    fun claimProactiveSuggestedReplySync(
        conversationId: String,
        messageId: String,
        index: Int,
    ): ChatDbResult<ProactiveReplyClaim?> =
        execute { it.conversations.claimProactiveSuggestedReply(conversationId, messageId, index) }

    // Input, interrupt, and decision rows are transactionally appended by the conversation repository.
    @JvmOverloads
    fun appendInterruptRequestSync(
        conversationId: String,
        content: String,
        skills: List<String> = emptyList(),
        memoryDisabled: Boolean = false,
    ): ChatDbResult<InterruptRequestValue> = execute {
        it.conversations.appendInterruptRequest(conversationId, content, skills, memoryDisabled)
    }

    fun dispatchInterruptRequestSync(conversationId: String, requestId: String): ChatDbResult<DispatchedInterrupt> =
        execute { it.conversations.dispatchInterruptRequest(conversationId, requestId) }

    fun appendAndDispatchInterruptRequestSync(
        conversationId: String,
        content: String,
        skills: List<String>,
        memoryDisabled: Boolean,
    ): ChatDbResult<DispatchedInterrupt> = execute {
        it.modelTurnWriter.appendAndDispatchInterruptRequest(conversationId, content, skills, memoryDisabled)
    }

    @JvmOverloads
    fun pendingInterruptRequestsSync(conversationId: String? = null): List<InterruptRequestValue> =
        execute { it.conversations.pendingInterruptRequests(conversationId) }

    fun markInputStateSync(conversationId: String, messageIds: List<String>, state: String): ChatDbResult<List<String>> =
        execute { it.conversations.markInputState(conversationId, messageIds, state) }

    fun appendDecisionRequestSync(
        conversationId: String,
        decisionId: String,
        title: String,
        body: String,
        allowDismiss: Boolean,
        options: List<DecisionOptionInput>,
    ): ChatDbResult<Long> = execute {
        it.conversations.appendDecisionRequest(conversationId, decisionId, title, body, allowDismiss, options)
    }

    fun resolveDecisionSync(
        conversationId: String,
        decisionId: String,
        status: String,
        optionId: String?,
        optionLabel: String?,
    ): ChatDbResult<Long> = execute {
        it.conversations.resolveDecision(conversationId, decisionId, status, optionId, optionLabel)
    }

    // UI and model readers keep projections under ConversationReader's shared read fence.
    fun readConversationMessagesSync(conversationId: String): List<StoredMessage> =
        execute { it.reader.readConversationMessages(conversationId) }

    fun readPendingAgentInputsSync(conversationId: String): List<StoredMessage> =
        execute { it.reader.readPendingAgentInputs(conversationId) }

    fun countPendingAgentInputsByWorkspaceSync(conversationWorkspaceIds: Map<String, String>): Map<String, Int> =
        execute { it.reader.countPendingAgentInputsByWorkspace(conversationWorkspaceIds) }

    fun readActiveConversationMessagesSync(conversationId: String): List<StoredMessage> =
        execute { it.reader.readActiveConversationMessages(conversationId) }

    fun readMessageSync(conversationId: String, messageId: String): StoredMessage? =
        execute { it.reader.readMessage(conversationId, messageId) }

    fun readMessageByStableIdSync(conversationId: String, stableId: String): StoredMessage? =
        execute { it.reader.readMessageByStableId(conversationId, stableId) }

    fun readConversationMessageSync(conversationId: String, messageId: String): String? =
        execute { it.reader.readConversationMessage(conversationId, messageId) }

    fun readMessageAttachmentsSync(conversationId: String, messageId: String): List<com.jarvys.agent.ChatAttachment> =
        execute { it.reader.readMessageAttachments(conversationId, messageId) }

    fun readTailWindowSync(conversationId: String, turns: Int): MessageWindow =
        execute { it.reader.readTailWindow(conversationId, turns) }

    fun readWindowBeforeSync(conversationId: String, cursorSeq: Long, turns: Int): MessageWindow =
        execute { it.reader.readWindowBefore(conversationId, cursorSeq, turns) }

    fun readRecentConversationTimelineSync(conversationId: String, turns: Int): ChatTimelineEventPage =
        execute { it.reader.readRecentConversationTimeline(conversationId, turns).toPublicPage() }

    fun readConversationTimelineBeforeSync(
        conversationId: String,
        cursorSeq: Long,
        turns: Int,
    ): ChatTimelineEventPage = execute {
        it.reader.readConversationTimelineBefore(conversationId, cursorSeq, turns).toPublicPage()
    }

    /** Reads a complete timeline under one read fence, loading its side tables once. */
    fun readConversationTimelineSync(conversationId: String, turns: Int): List<AgentRunUiEvent> =
        execute { it.reader.readConversationTimeline(conversationId, turns) }

    fun persistStoppedSubmissionSync(
        conversationId: String,
        followUpRequestId: String?,
        ownerUserMessageId: String?,
        userMessageContentIfMissing: String?,
        assistantMessage: String,
        conversationKind: String,
    ): ChatDbResult<StoppedSubmissionResult> = execute {
        it.modelTurnWriter.persistStoppedSubmission(StoppedSubmissionInput(
            conversationId = conversationId,
            followUpRequestId = followUpRequestId,
            ownerUserMessageId = ownerUserMessageId,
            userMessageContentIfMissing = userMessageContentIfMissing,
            assistantMessage = assistantMessage,
            conversationKind = conversationKind,
        ))
    }

    fun loadConversationContextSync(conversationId: String): List<ConversationTurn> =
        execute { it.reader.loadConversationContext(conversationId) }

    fun readConversationContextSync(conversationId: String): String =
        execute { it.reader.readConversationContext(conversationId) }

    fun latestUserMessageIdSync(conversationId: String): String? = execute { it.reader.latestUserMessageId(conversationId) }

    @JvmOverloads
    fun listConversationsSync(
        limit: Int,
        excludedKinds: Set<String> = setOf("proactive", "tasks"),
    ): List<ConversationSummary> =
        execute { it.reader.listConversations(limit, excludedKinds) }

    @JvmOverloads
    fun listAllConversationsSync(
        excludedKinds: Set<String> = setOf("proactive", "tasks"),
    ): List<ConversationSummary> = execute { it.conversations.listAllConversations(excludedKinds) }

    fun listConversationsWithPendingAgentInputsSync(): List<String> =
        execute { it.conversations.listConversationsWithPendingAgentInputs() }

    fun messageCountsSync(conversationId: String): MessageCounts = execute { it.reader.messageCounts(conversationId) }

    fun readDecisionsSync(conversationId: String): List<ConversationDecisionValue> =
        execute { it.reader.readDecisions(conversationId) }

    fun readModelTranscriptSync(conversationId: String): ModelTranscriptSnapshot =
        execute { it.reader.readModelTranscript(conversationId) }

    /** Model ledger rows in legacy restore() shape, projected from normalized transcript entries. */
    fun readModelTranscriptRowsSync(conversationId: String): List<JSONObject> = execute { repos ->
        repos.reader.readModelTranscript(conversationId).entries.mapNotNull { it.toRestoreRow() }
    }

    fun readConversationImageReferencesSync(conversationId: String): List<ConversationImageReferenceValue> =
        execute { it.artifacts.readImageReferences(conversationId) }

    fun readCrewMissionSnapshotsSync(conversationId: String): List<CrewMissionSnapshot> =
        execute { it.reader.readCrewMissionSnapshots(conversationId) }

    fun recoverCrewMissionsSync(conversationId: String, processIdForComparison: String?): List<CrewMissionSnapshot> =
        execute { it.reader.recoverCrewMissions(conversationId, processIdForComparison) }

    fun pendingCompactionIdsSync(conversationId: String): List<String> =
        execute { it.reader.pendingCompactionIds(conversationId) }

    fun buildReflectionPayloadSync(
        conversationId: String,
        throughMessageId: String?,
        replayAll: Boolean,
    ): ReflectionPayloadSnapshot = execute {
        it.reader.buildReflectionPayload(conversationId, throughMessageId, replayAll)
    }

    // Model transcript persistence and restart restoration.
    fun appendToolBatchSync(input: ModelToolBatchInput): ChatDbResult<ModelToolBatchSnapshot> =
        execute { it.transcript.appendToolBatch(input) }

    /** Commits tool-call, result, provider-output, error, and optional incorporated-input rows atomically. */
    fun appendModelTurnSync(input: ConversationModelTurnInput): ChatDbResult<ConversationModelTurnResult> =
        execute { it.modelTurnWriter.append(input) }

    fun setCallLifecycleSync(conversationId: String, batchId: String, callId: String, lifecycle: String): ChatDbResult<Long> =
        execute { it.transcript.setCallLifecycle(conversationId, batchId, callId, lifecycle) }

    fun appendToolResultSync(input: ModelToolResultInput): ChatDbResult<ToolResult?> =
        execute { it.transcript.appendToolResult(input) }

    fun appendModelErrorSync(
        conversationId: String,
        ownerUserMessageId: String?,
        messageIndex: Long,
        errorCode: String,
    ): ChatDbResult<Long> = execute {
        it.transcript.appendModelError(conversationId, ownerUserMessageId, messageIndex, errorCode)
    }

    fun appendProviderOutputItemsSync(
        conversationId: String,
        ownerUserMessageId: String,
        messageIndex: Long,
        itemsJson: String,
    ): ChatDbResult<Long> = execute {
        it.transcript.appendProviderOutputItems(conversationId, ownerUserMessageId, messageIndex, itemsJson)
    }

    @JvmOverloads
    fun appendModelArtifactSync(
        conversationId: String,
        artifactId: String,
        relativePath: String,
        sha256: String,
        chars: Long,
        bytes: Long,
        mimeType: String = "text/plain",
    ): ChatDbResult<ModelArtifact> = execute {
        it.transcript.appendModelArtifact(conversationId, artifactId, relativePath, sha256, chars, bytes, mimeType)
    }

    fun appendToolActivitySync(input: ToolActivityInput): ChatDbResult<ToolActivity?> =
        execute { it.transcript.appendToolActivity(input) }

    fun appendToolPresentationSync(input: ToolPresentationInput): ChatDbResult<ToolPresentation> =
        execute { it.transcript.appendToolPresentation(input) }

    fun appendReflectionToolSync(input: ReflectionToolInput): ChatDbResult<ReflectionToolMarker> =
        execute { it.transcript.appendReflectionTool(input) }

    fun readToolActivitiesSync(conversationId: String, ownerUserMessageId: String, executionId: String): List<ToolActivity> =
        execute { it.transcript.readToolActivities(conversationId, ownerUserMessageId, executionId) }

    fun resolveActivitySourceBatchSync(
        conversationId: String,
        ownerUserMessageId: String?,
        executionId: String?,
        callId: String?,
    ): ToolBatchResolution = execute {
        it.transcript.resolveActivitySourceBatch(conversationId, ownerUserMessageId, executionId, callId)
    }

    // Attachments, generated images, and delivered artifacts remain file-backed; the DB owns their metadata.
    fun appendDeliveredArtifactSync(input: DeliveredArtifactInput): ChatDbResult<ConversationArtifact> =
        execute { it.artifacts.appendDeliveredArtifact(input) }

    fun appendGeneratedImageSync(input: GeneratedImageInput): ChatDbResult<GeneratedImage> =
        execute { it.artifacts.appendGeneratedImage(input) }

    fun findChatFileSync(conversationId: String, kind: String, id: String): ChatFileOwnership? =
        execute { it.reader.findChatFile(conversationId, kind, id) }

    fun artifactsForConversationSync(conversationId: String): List<ConversationArtifact> =
        execute { it.reader.artifactsForConversation(conversationId) }

    fun findArtifactThumbnailSync(conversationId: String, artifactId: String): FileRef? =
        execute { it.artifacts.artifactThumbnailRef(conversationId, artifactId) }

    fun ownsGeneratedFileSync(conversationId: String, relativePath: String): Boolean =
        execute { it.reader.ownsGeneratedFile(conversationId, relativePath) }

    fun conversationHasAttachmentsSync(conversationId: String): Boolean =
        execute { it.reader.conversationHasAttachments(conversationId) }

    fun conversationHasPrivateImagesOrAttachmentsSync(conversationId: String): Boolean =
        execute { it.reader.conversationHasPrivateImagesOrAttachments(conversationId) }

    fun unreferencedFileRefsSync(): List<FileRef> = execute { it.reader.unreferencedFileRefs() }

    // Compaction, reflection, and crew snapshot writes.
    fun appendCompactionSync(input: CompactionInput): ChatDbResult<Compaction> =
        execute { it.memory.appendCompaction(input) }

    fun acknowledgeCompactionsSync(
        conversationId: String,
        compactionIds: List<String>?,
        clearAll: Boolean,
    ): ChatDbResult<Long> = execute { it.memory.acknowledgeCompactions(conversationId, compactionIds, clearAll) }

    fun appendReflectionCheckpointSync(input: ReflectionInput): ChatDbResult<Long> =
        execute { it.memory.appendReflectionCheckpoint(input) }

    fun appendReflectionCommitSync(input: ReflectionInput): ChatDbResult<Long> =
        execute { it.memory.appendReflectionCommit(input) }

    fun appendReflectionEventSync(input: ReflectionInput): ChatDbResult<Long> =
        execute { it.memory.appendReflectionEvent(input) }

    fun appendReflectionUndoSync(conversationId: String, reflectionId: String, createdAtSeconds: Double?): ChatDbResult<Long> =
        execute { it.memory.appendReflectionUndo(conversationId, reflectionId, createdAtSeconds) }

    fun hasReflectionCommitSync(conversationId: String, reflectionId: String): Boolean =
        execute { it.reader.hasReflectionCommit(conversationId, reflectionId) }

    fun appendCrewSnapshotSync(conversationId: String, row: JSONObject): ChatDbResult<CrewMissionSnapshot> =
        execute { it.memory.appendCrewSnapshot(conversationId, row) }

    // Typed strict journals preserve their conflict/invalid/not-found outcomes for Java callers.
    @JvmOverloads
    fun appendCrewResultEvidenceSync(
        evidence: CrewResultJournalEvidence,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<CrewResultJournalEvidence> = execute {
        it.strictJournals.appendCrewResultEvidence(evidence) { beforeWrite?.check() }
    }

    fun readCrewResultEvidenceSync(executionSessionId: String, evidenceId: String): ChatDbResult<CrewResultJournalEvidence?> =
        execute { it.strictJournals.readCrewResultEvidence(executionSessionId, evidenceId) }

    @JvmOverloads
    fun listCrewResultEvidenceIdsSync(
        executionSessionId: String,
        limit: Int,
        afterEvidenceId: String? = null,
    ): ChatDbResult<List<String>> = execute {
        it.strictJournals.listCrewResultEvidenceIds(executionSessionId, limit, afterEvidenceId)
    }

    @JvmOverloads
    fun appendWorkspaceResultReferenceSync(
        reference: WorkspaceResultReference,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceResultReference> = execute {
        it.strictJournals.appendWorkspaceResultReference(reference) { beforeWrite?.check() }
    }

    @JvmOverloads
    fun appendWorkspaceResultNoticeSync(
        reference: WorkspaceResultReference,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceResultReference> = execute {
        it.strictJournals.appendWorkspaceResultNotice(reference) { beforeWrite?.check() }
    }

    fun readWorkspaceResultReferenceSync(
        conversationId: String,
        referenceId: String,
        isNotice: Boolean,
    ): ChatDbResult<WorkspaceResultReference?> = execute {
        it.strictJournals.readWorkspaceResultReference(conversationId, referenceId, isNotice)
    }

    @JvmOverloads
    fun listWorkspaceResultReferencesSync(
        conversationId: String,
        isNotice: Boolean,
        limit: Int,
        afterReferenceId: String? = null,
    ): ChatDbResult<List<WorkspaceResultReference>> = execute {
        it.strictJournals.listWorkspaceResultReferences(conversationId, isNotice, limit, afterReferenceId)
    }

    fun requireWorkspaceResultDisclosureLiveSync(
        reference: WorkspaceResultReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = execute {
        it.strictJournals.requireWorkspaceResultDisclosureLive(reference, noticeRequired)
    }

    @JvmOverloads
    fun appendCrewCoordinationEvidenceSync(
        evidence: CrewCoordinationJournalEvidence,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<CrewCoordinationJournalEvidence> = execute {
        it.strictJournals.appendCrewCoordinationEvidence(evidence) { beforeWrite?.check() }
    }

    fun readCrewCoordinationEvidenceSync(
        executionSessionId: String,
        evidenceId: String,
    ): ChatDbResult<CrewCoordinationJournalEvidence?> = execute {
        it.strictJournals.readCrewCoordinationEvidence(executionSessionId, evidenceId)
    }

    @JvmOverloads
    fun listCrewCoordinationEvidenceIdsSync(
        executionSessionId: String,
        limit: Int,
        afterEvidenceId: String? = null,
    ): ChatDbResult<List<String>> = execute {
        it.strictJournals.listCrewCoordinationEvidenceIds(executionSessionId, limit, afterEvidenceId)
    }

    @JvmOverloads
    fun appendWorkspaceCoordinationReferenceSync(
        reference: WorkspaceCoordinationReference,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceCoordinationReference> = execute {
        it.strictJournals.appendWorkspaceCoordinationReference(reference) { beforeWrite?.check() }
    }

    @JvmOverloads
    fun appendWorkspaceCoordinationNoticeSync(
        reference: WorkspaceCoordinationReference,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceCoordinationReference> = execute {
        it.strictJournals.appendWorkspaceCoordinationNotice(reference) { beforeWrite?.check() }
    }

    fun readWorkspaceCoordinationReferenceSync(
        conversationId: String,
        referenceId: String,
        isNotice: Boolean,
    ): ChatDbResult<WorkspaceCoordinationReference?> = execute {
        it.strictJournals.readWorkspaceCoordinationReference(conversationId, referenceId, isNotice)
    }

    fun findWorkspaceCoordinationReferenceBySourceMessageIdSync(
        conversationId: String,
        sourceMessageId: String,
    ): ChatDbResult<WorkspaceCoordinationReference?> = execute {
        it.strictJournals.findWorkspaceCoordinationReferenceBySourceMessageId(conversationId, sourceMessageId)
    }

    @JvmOverloads
    fun listWorkspaceCoordinationReferencesSync(
        conversationId: String,
        isNotice: Boolean,
        limit: Int,
        afterReferenceId: String? = null,
    ): ChatDbResult<List<WorkspaceCoordinationReference>> = execute {
        it.strictJournals.listWorkspaceCoordinationReferences(conversationId, isNotice, limit, afterReferenceId)
    }

    fun requireWorkspaceCoordinationDisclosureLiveSync(
        reference: WorkspaceCoordinationReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = execute {
        it.strictJournals.requireWorkspaceCoordinationDisclosureLive(reference, noticeRequired)
    }

    @JvmOverloads
    fun prepareWorkspaceCoordinationReplySync(
        reply: WorkspaceCoordinationReplyJournal,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceCoordinationReplyJournal> = execute {
        it.strictJournals.prepareWorkspaceCoordinationReply(reply) { beforeWrite?.check() }
    }

    @JvmOverloads
    fun markWorkspaceCoordinationReplyStartedSync(
        reply: WorkspaceCoordinationReplyJournal,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceCoordinationReplyJournal> = execute {
        it.strictJournals.markWorkspaceCoordinationReplyStarted(reply) { beforeWrite?.check() }
    }

    @JvmOverloads
    fun markWorkspaceCoordinationReplyAcceptedSync(
        reply: WorkspaceCoordinationReplyJournal,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceCoordinationReplyJournal> = execute {
        it.strictJournals.markWorkspaceCoordinationReplyAccepted(reply) { beforeWrite?.check() }
    }

    fun readWorkspaceCoordinationReplySync(
        centralSessionId: String,
        referenceId: String,
        replyKey: String?,
    ): ChatDbResult<WorkspaceCoordinationReplyJournal?> = execute {
        it.strictJournals.readWorkspaceCoordinationReply(centralSessionId, referenceId, replyKey)
    }

    fun readWorkspaceCoordinationReplyByIdSync(
        centralSessionId: String,
        replyId: String,
    ): ChatDbResult<WorkspaceCoordinationReplyJournal?> = execute {
        it.strictJournals.readWorkspaceCoordinationReplyById(centralSessionId, replyId)
    }

    fun countStartedQuestionBlockerRepliesByWorkspaceSync(
        centralSessionId: String,
        workspaceIds: Set<String>,
    ): ChatDbResult<Map<String, Int>> = execute {
        it.strictJournals.countStartedQuestionBlockerRepliesByWorkspace(centralSessionId, workspaceIds)
    }

    @JvmOverloads
    fun appendWorkspaceInputIncorporationSync(
        value: WorkspaceInputIncorporationJournal,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<WorkspaceInputIncorporationJournal> = execute {
        it.strictJournals.appendWorkspaceInputIncorporation(value) { beforeWrite?.check() }
    }

    fun readWorkspaceInputIncorporationSync(
        centralSessionId: String,
        sourceKind: WorkspaceInputIncorporationJournal.SourceKind,
        sourceId: String,
    ): ChatDbResult<WorkspaceInputIncorporationJournal?> = execute {
        it.strictJournals.readWorkspaceInputIncorporation(centralSessionId, sourceKind, sourceId)
    }

    fun requireWorkspaceInputIncorporationDisclosureLiveSync(
        value: WorkspaceInputIncorporationJournal,
    ): ChatDbResult<Unit> = execute {
        it.strictJournals.requireWorkspaceInputIncorporationDisclosureLive(value)
    }

    @JvmOverloads
    fun listWorkspaceInputIncorporationsSync(
        centralSessionId: String,
        limit: Int,
        afterReceiptId: String? = null,
    ): ChatDbResult<List<WorkspaceInputIncorporationJournal>> = execute {
        it.strictJournals.listWorkspaceInputIncorporations(centralSessionId, limit, afterReceiptId)
    }

    private fun ConversationReader.TimelineEventPage.toPublicPage() = ChatTimelineEventPage(
        events, olderCursorSeq, invalidatedToolTurnMessageIds,
    )

    private fun ModelTranscriptEntry.toRestoreRow(): JSONObject? = when (this) {
        is ModelTranscriptEntry.Batch -> {
            val batch = value.batch
            val calls = if (batch.raw_calls != null) {
                runCatching { JSONTokener(batch.raw_calls).nextValue() }.getOrNull() ?: JSONObject.NULL
            } else JSONArray().also { encoded -> value.calls.forEach { callState ->
                val call = callState.call
                encoded.put(JSONObject().put("id", call.call_id).put("name", call.name)
                    .put("arguments", runCatching { JSONTokener(call.arguments_json).nextValue() }.getOrNull() ?: JSONObject.NULL))
            } }
            val row = JSONObject()
                .put("type", "model_tool_calls")
                .put("batchId", batch.batch_id)
                .put("userMessageId", batch.owner_user_message_id.orEmpty())
                .put("messageIndex", batch.message_index)
                .put("assistantText", batch.assistant_text)
                .put("assistantProgress", batch.assistant_progress)
                .put("calls", calls)
            batch.provider_output_items_json?.let { encoded -> runCatching { JSONArray(encoded) }.getOrNull() }?.let {
                row.put("providerOutputItems", it)
            }
            row
        }
        is ModelTranscriptEntry.Lifecycle -> JSONObject()
            .put("type", if (state == "STARTED") "model_tool_started" else "model_tool_not_started")
            .put("batchId", batchId).put("callId", callId)
        is ModelTranscriptEntry.ResultActivity -> firstResult?.let { result -> JSONObject()
            .put("type", "model_tool_result")
            .put("batchId", batchId).put("callId", callId).put("toolName", result.tool_name)
            .put("lifecycle", result.lifecycle).put("output", result.output)
        }
        is ModelTranscriptEntry.Error -> JSONObject()
            .put("type", "model_error")
            .put("userMessageId", value.owner_user_message_id.orEmpty())
            .put("messageIndex", value.message_index).put("errorCode", value.error_code)
        is ModelTranscriptEntry.ProviderOutput -> JSONObject()
            .put("type", "model_provider_output_items")
            .put("userMessageId", value.owner_user_message_id)
            .put("messageIndex", value.message_index)
            .put("items", runCatching { JSONArray(value.items_json) }.getOrElse { JSONArray() })
        is ModelTranscriptEntry.Artifact -> JSONObject()
            .put("type", "model_artifact")
            .put("artifactId", value.artifact_id)
            .put("artifact", JSONObject()
                .put("path", value.path)
                .put("sha256", value.sha256)
                .put("chars", value.chars)
                .put("bytes", value.bytes))
    }
}

package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Looper
import com.jarvys.agent.AgentErrorReporter
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.MemoryWriteGate
import com.jarvys.agent.MemoryWritePolicy
import com.jarvys.agent.proactive.ProactiveReplyClaim
import com.jarvys.agent.crew.CrewCoordinationEvidence as CrewCoordinationJournalEvidence
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewResultEvidence as CrewResultJournalEvidence
import com.jarvys.agent.connectors.ConnectionStateCoordinator
import com.jarvys.agent.connectors.AutonomyAuditRecord
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
        val history: MessageHistoryRepository,
        val retention: ArtifactRetentionRepository,
    )

    companion object {
        private val instances = mutableMapOf<String, ChatDatabaseBridge>()

        /** Stable destination issuer/fence; no database opening or reset wait. Worker-only. */
        @JvmStatic
        fun connectionProjectionAnchor(context: Context): ConnectionProjectionAnchor =
            JarvysChatDatabaseProvider.connectionProjectionAnchor(context)

        /** Test-only: Robolectric runs UI tests ON the main looper; production keeps the guard on. */
        @Volatile @JvmField internal var mainThreadGuardDisabledForTests = false

        /** Set only by Gradle for JVM unit tests (system property); never present in the app process. */
        private fun unitTestMainThreadGuardDisabled(): Boolean =
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
        internal fun getInstance(context: Context, executor: ExecutorService): ChatDatabaseBridge {
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
                com.jarvys.agent.FreshStorageBootstrap.ensure(applicationContext)
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
                    MessageHistoryRepository(database),
                    ArtifactRetentionRepository(database),
                ).also { opened ->
                    // Before admitting this process's writers, close only effects owned by an earlier process.
                    runBlocking { opened.agentMemory.reconcileForeignEpisodeProcesses(
                        com.jarvys.agent.crew.CrewProcessIdentity.ID) }
                    // Recovery must succeed before the fast path can expose this repository set.
                    // It uses repositories directly (never bridge callbacks), so cannot re-enter open.
                    val wakeMissions = runBlocking { MissionEpisodeRepository(database).recoverPending() }
                    repositories = opened
                    // Wake outside the recovery lease, after publication; enqueue never calls this bridge.
                    if (wakeMissions) wakeMissionNarration()
                }
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

    // Runtime-only automatic entrypoints: tickets never originate in model arguments. Human UI
    // editing uses the existing explicit methods; causal evidence is intentionally not gated.
    @JvmOverloads
    fun personalAgentSetDirectiveAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        section: String, directive: String, sourceMessageId: String?, evidence: String,
        supersedesId: String?, conversationId: String?, witness: com.jarvys.agent.CentralInvocationWitness? = null): PersonalMemoryReceipt =
        executeBoundMemoryMutation(ticket,token,conversationId,null,false,"user_profile_set",witness) { repos,_ ->
            repos.agentMemory.setDirective(section,directive,sourceMessageId,evidence,supersedesId,conversationId)
        }

    fun personalAgentSaveMemoryAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        text: String, layer: String, category: String?, importance: Double, sourceRef: String?,
        options: MemorySaveOptions): PersonalMemoryReceipt =
        executeAutomatic(ticket, token) { it.agentMemory.saveMemory(text, layer, category, importance, sourceRef, options) }

    fun personalAgentSaveMemoryAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        text: String, layer: String, category: String?, importance: Double, sourceRef: String?,
        options: MemorySaveOptions, callerConversation: String?, project: String?, scope: String?): PersonalMemoryReceipt =
        executeAutomaticNamespace(ticket, token, callerConversation, project, scope, false) { repos, namespace ->
            repos.agentMemory.saveMemory(text, layer, category, importance, sourceRef, options, namespace)
        }

    /** Native save is keyed by its actual Central checkpoint, not an argument supplied by the model. */
    fun personalAgentSaveMemoryOperationAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        request: MemorySaveRequest, witness: com.jarvys.agent.CentralInvocationWitness): PersonalMemoryReceipt = executeOnWorker { repos ->
        token.throwIfCancelled()
        witness.requireInvocationCurrent()
        val source = witness.source()
        check(request.callerConversation == source.ownerCentralSessionId) { "memory_save_source_invalid" }
        val namespace = MemoryNamespace.resolve(applicationContext, request.callerConversation, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, {
            token.throwIfCancelled()
            witness.requireInvocationCurrent()
            true
        }, Callable { runBlocking {
            witness.requireInvocationCurrent()
            repos.agentMemory.saveMemory(request.text, request.layer, request.category, request.importance, request.sourceRef,
                request.options, namespace, MemorySaveOperationInput(request, witness))
        } })
    }

    /** Separate post-commit operation: a rejected optional detection never rolls back the acknowledged save. */
    fun personalAgentFindRelationCandidatesAutomatic(ticket:MemoryWriteGate.Ticket,token:CancellationToken,
        request:MemorySaveRequest,witness:com.jarvys.agent.CentralInvocationWitness,savedId:String):MemoryCandidateResult = executeOnWorker { repos ->
        val input=MemorySaveOperationInput(request,witness)
        val gate=MemoryWritePolicy.forContext(applicationContext).gate()
        fun owners()=MemoryNamespace.resolve(applicationContext,request.callerConversation,null,null,false,true,true)
        fun check() { token.throwIfCancelled(); witness.requireInvocationCurrent() }
        val repository=MemoryRelationRepository(repos.database)
        detectRelationCandidates({
            check(); val namespace=owners()
            gate.commit(ticket,{ check(); true },Callable { runBlocking {
                repos.database.withChatReadTransaction {
                    check(); MemorySaveOperations.requireCommittedCandidateSource(repos.database,input,savedId,namespace)
                    repository.lookup(savedId,namespace)
                }
            } })
        }) { lookup,match ->
            check(); val namespace=owners()
            gate.commit(ticket,{ check(); true },Callable { runBlocking {
                var prepared:MemorySaveOperations.Prepared?=null
                repository.insertPending(lookup,match,namespace,beforeEffect={
                    check(); prepared=MemorySaveOperations.requireCommittedCandidateSource(repos.database,input,savedId,namespace)
                },afterEffect={
                    check(); MemorySaveOperations.requireSessionForWrite(repos.database,input,requireNotNull(prepared)); check()
                })
            } })
        }
    }

    fun personalAgentRelationDetails(judgmentId:String):MemoryRelationDetails = executeOnWorker { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,true,true)
        runBlocking { MemoryRelationRepository(repos.database).details(judgmentId,namespace) }
    }
    fun personalAgentJudgeRelation(details:MemoryRelationDetails,relation:String,reason:String?,evidence:String?,confidence:Double?):MemoryRelationView = executeOnWorker { repos ->
        val args=linkedMapOf<String,Any?>("judgment_id" to details.relation.judgmentId,"relation" to relation)
        reason?.let { args["reason"]=it };evidence?.let { args["evidence"]=it };confidence?.let { args["confidence"]=it }
        val item=MemoryJudgmentArguments.prepare("memory_judge",args).items.single()
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,true,true)
        runBlocking { MemoryRelationRepository(repos.database).judge(item,namespace,"user","human",null,displayed=details) }
    }
    /** One actual item submission. The selected operation comes only from the whole certified request. */
    fun memoryJudgmentAutomatic(ticket:MemoryWriteGate.Ticket,token:CancellationToken,callerConversation:String?,
        request:MemoryJudgmentRequest,index:Int,witness:com.jarvys.agent.CentralInvocationWitness):MemoryRelationView = executeOnWorker { repos ->
        token.throwIfCancelled();witness.requireInvocationCurrent()
        val namespace=try { MemoryNamespace.resolve(applicationContext,callerConversation,null,null,true,true) }
            catch(failure:CancellationException) { throw failure }
            catch(failure:RuntimeException) { throw MemoryJudgmentSourceResolutionException(failure) }
        check(callerConversation==witness.source().ownerCentralSessionId) { "memory_judgment_source_invalid" }
        check(index in request.items.indices && request.items[index].index==index && request.items[index].error==null) { "memory_judgment_source_invalid" }
        val item=request.items[index]
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{
            token.throwIfCancelled();witness.requireInvocationCurrent();true
        },Callable { runBlocking {
            val sessions=MemorySessionRepository(repos.database);var binding:MemorySessionBinding?=null
            var sourceGuardPassed=false
            val before={ token.throwIfCancelled();witness.requireInvocationCurrent();binding=sessions.requireJudgmentSource(request,witness);sourceGuardPassed=true;Unit }
            val after={ token.throwIfCancelled();witness.requireInvocationCurrent();sessions.renewCurrent(requireNotNull(binding));token.throwIfCancelled();witness.requireInvocationCurrent() }
            try {
                // session provenance is obtained inside the writer guard, never from an argument or latest row.
                repos.database.withChatWriteTransaction {
                    before()
                    val repository=MemoryRelationRepository(repos.database)
                    if(request.toolName=="memory_compare") repository.compare(item,namespace,requireNotNull(binding).id,afterEffect=after)
                    else repository.judge(item,namespace,"agent","agent",requireNotNull(binding).id,afterEffect=after)
                }
            } catch(failure:android.database.SQLException) {
                // Corruption is not an ordinary recoverable item error. Preserve its original category.
                if(isChatSqlCorruption(failure)) throw MemoryJudgmentStorageCorruptionException(failure)
                if(!sourceGuardPassed) throw MemoryJudgmentSourceResolutionException(failure)
                // This exception crossed the owned transaction rollback, not an unknown executor ACK.
                throw MemoryJudgmentSqlItemException(failure)
            }
        } })
    }

    /** Human-requested detection is independent of automatic capture, never a model-supplied grant. */
    fun personalAgentFindRelationCandidates(id:String,expectedProject:String):MemoryCandidateResult = executeOnWorker { repos ->
        fun owners()=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,expectedProject,null,false,true)
        val repository=MemoryRelationRepository(repos.database)
        detectRelationCandidates({
            val namespace=owners()
            runBlocking { repository.lookup(id,namespace).also {
                check(it.source.project==MemoryNamespace.normalizeProject(expectedProject)) { "memory_project_mismatch" }
            } }
        }) { lookup,match ->
            val namespace=owners()
            runBlocking { repository.insertPending(lookup,match,namespace) }
        }
    }

    @JvmOverloads
    fun personalAgentPendingRelations(limit:Int=20,offset:Int=0):MemoryRelationPage = executeOnWorker { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,true,true)
        runBlocking { MemoryRelationRepository(repos.database).pendingRelations(namespace,limit,offset) }
    }

    private fun detectRelationCandidates(lookup:()->MemoryRelationLookup,
        insert:(MemoryRelationLookup,MemoryRelationMatch)->MemoryRelationCandidate?):MemoryCandidateResult {
        val confirmed=ArrayList<MemoryRelationCandidate>(); var problem:String?=null
        try {
            val selected=lookup()
            for(match in selected.candidates) {
                try { insert(selected,match)?.let(confirmed::add) }
                catch(failure:android.database.SQLException) { problem="memory_relation_sql_incomplete" }
            }
        } catch(failure:CancellationException) { throw failure }
        catch(failure:MemoryWriteGate.RejectedException) { problem="memory_relation_policy_revoked" }
        catch(failure:MemoryRelationSourceChanged) { problem="memory_relation_source_changed" }
        catch(failure:MemoryRelationUnknownOutcome) { problem="memory_relation_unknown_outcome" }
        catch(failure:android.database.SQLException) { problem="memory_relation_sql_incomplete" }
        // Unexpected registry/proof/storage invariants propagate; they are not a benign policy denial.
        return MemoryCandidateResult(if(problem==null) "complete" else "incomplete",confirmed,problem)
    }

    @JvmOverloads
    fun personalAgentSetPinnedAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String, pinned: Boolean, callerConversation: String?, witness: com.jarvys.agent.CentralInvocationWitness? = null): PersonalMemoryReceipt =
        executeBoundMemoryMutation(ticket, token, callerConversation, null, true,if(pinned) "memory_pin" else "memory_unpin",witness) { repos, namespace ->
            repos.agentMemory.setPinned(id, pinned, namespace)
        }

    @JvmOverloads
    fun personalAgentForgetAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String, callerConversation: String?, witness: com.jarvys.agent.CentralInvocationWitness? = null): PersonalMemoryReceipt = executeBoundMemoryMutation(ticket, token, callerConversation, null, true,"memory_forget",witness) { repos, namespace ->
            repos.agentMemory.forget(id, namespace)
        }

    @JvmOverloads
    fun personalAgentUpdateAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String, expectedProject: String, options: MemoryUpdateOptions, callerConversation: String?, witness: com.jarvys.agent.CentralInvocationWitness? = null): PersonalMemoryReceipt =
        executeBoundMemoryMutation(ticket, token, callerConversation, expectedProject, true,"memory_update",witness) { repos, namespace ->
            repos.agentMemory.update(id, expectedProject, options, namespace, "agent")
        }

    fun personalAgentForgetAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String): PersonalMemoryReceipt = executeAutomatic(ticket, token) { it.agentMemory.forget(id) }

    fun personalAgentConsolidateAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        sourceIds: List<String>, summary: String): PersonalMemoryReceipt =
        executeAutomatic(ticket, token) { it.agentMemory.consolidateDailyNotes(sourceIds, summary) }

    fun personalAgentSetEpisodeNarrationAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String, state: String, title: String?, text: String?) =
        executeAutomatic(ticket, token) { it.agentMemory.setEpisodeNarration(id, state, title, text) }

    fun personalAgentCommitEpisodeNarrationAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        id: String, expected: EpisodeNarrationInput, state: String, title: String?, text: String?): Boolean =
        executeAutomatic(ticket, token) { it.agentMemory.commitEpisodeNarration(id, expected, state, title, text) }

    fun personalAgentAdvanceExtractionCursorAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        conversationId: String, throughSeq: Long) =
        executeAutomatic(ticket, token) { it.agentMemory.advanceExtractionCursor(conversationId, throughSeq) }

    fun personalAgentNameSync(): String? = withAgentMemory("read agent name") { it.agentName() }

    fun appendConnectorAuditSync(record: AutonomyAuditRecord) = appendConnectorAuditsSync(listOf(record))

    /** Atomic batch append with idempotent IDs. Conflicting reuse of an ID is not a replay. */
    fun appendConnectorAuditsSync(records: List<AutonomyAuditRecord>) = execute { repositories ->
        repositories.database.withChatWriteTransaction {
            val sql = repositories.database.chatSql
            records.forEach { record ->
                require(record.id.isNotBlank()) { "Audit ID must be nonempty" }
                val existing = sql.query(
                    "SELECT timestamp_millis,connector_id,connector_name,operation_name,operation_label,summary FROM connector_autonomy_audit WHERE id=?",
                    arrayOf(record.id),
                ).use { cursor -> if (cursor.moveToFirst()) AutonomyAuditRecord(record.id,
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5)) else null }
                check(existing == null || existing == record) { "Audit identity conflict; original source preserved" }
                if (existing == null) sql.execSQL(
                    "INSERT INTO connector_autonomy_audit(id,timestamp_millis,connector_id,connector_name,operation_name,operation_label,summary) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(record.id, record.timestampMillis, record.connectorId, record.connectorName,
                        record.operationName, record.operationLabel, record.summary),
                )
            }
        }
    }

    fun recordConnectorStateEventSync(connectorId: String, connected: Boolean, atMs: Long = System.currentTimeMillis()) = execute { repositories ->
        repositories.database.withChatWriteTransaction {
            repositories.database.chatSql.execSQL(
                "INSERT INTO connector_state_events(id,connector_id,connected,connected_at_ms) VALUES(?,?,?,?)",
                arrayOf(java.util.UUID.randomUUID().toString(), connectorId, if (connected) 1 else 0, atMs),
            )
        }
    }

    fun recentConnectorAuditSync(connectorId: String, limit: Int): List<AutonomyAuditRecord> = execute { repositories ->
        require(limit >= 0)
        repositories.database.withChatReadTransaction {
            repositories.database.chatSql.query(
                "SELECT id,timestamp_millis,connector_id,connector_name,operation_name,operation_label,summary FROM connector_autonomy_audit WHERE connector_id=? ORDER BY timestamp_millis DESC,id DESC LIMIT ?",
                arrayOf(connectorId, limit),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add(AutonomyAuditRecord(
                    id = cursor.getString(0), timestampMillis = cursor.getLong(1), connectorId = cursor.getString(2),
                    connectorName = cursor.getString(3), operationName = cursor.getString(4),
                    operationLabel = cursor.getString(5), summary = cursor.getString(6),
                ))
            } }
        }
    }

    fun readEpisodeToolSourceSync(conversationId: String, batchId: String, callId: String): EpisodeToolSource? =
        execute { it.transcript.readEpisodeToolSource(conversationId, batchId, callId) }

    fun personalAgentRecordEpisodeToolStartedSync(conversationId: String, sourceMessageId: String, callId: String,
        toolName: String, connector: String?, authorization: String?, factsJson: String, outcome: String,
        actionId: String? = null, attemptId: String? = null, sourceBatchId: String? = null,
        runId: String? = null, processId: String? = null): String =
        withAgentMemory("record episode evidence") {
            it.recordEpisodeToolStarted(conversationId, sourceMessageId, callId, toolName, connector, authorization,
                factsJson, outcome, actionId, attemptId, sourceBatchId, runId, processId)
        }

    @JvmOverloads
    fun personalAgentFinishEpisodeToolEvidenceSync(conversationId: String, callId: String, outcome: String,
        factsJson: String, attemptId: String? = null, sourceBatchId: String? = null, runId: String? = null,
        authorization: String? = null) =
        withAgentMemory("finish episode evidence") {
            it.finishEpisodeToolEvidence(conversationId, callId, outcome, factsJson, attemptId, sourceBatchId, runId, authorization)
        }

    @JvmOverloads
    fun personalAgentAdmitEpisodeToolEvidenceSync(conversationId: String, callId: String, authorization: String,
        factsJson: String, attemptId: String? = null, sourceBatchId: String? = null, runId: String? = null,
        connectionObservation: com.jarvys.agent.connectors.ConnectionStateCoordinator.Observation? = null) =
        withAgentMemory("admit exact effect evidence") {
            it.admitEpisodeToolEvidence(conversationId, callId, authorization, factsJson, attemptId, sourceBatchId, runId, connectionObservation)
        }

    fun personalAgentEnsureTaskAttemptEvidenceSync(conversationId: String, sourceMessageId: String, actionId: String,
        attemptId: String, toolName: String, authorization: String?, outcome: String, factsJson: String) =
        withAgentMemory("reconcile task action evidence") {
            it.ensureTaskAttemptEvidence(conversationId, sourceMessageId, actionId, attemptId, toolName,
                authorization, outcome, factsJson)
        }

    fun personalAgentReconcileEpisodeRunSync(runId: String) =
        withAgentMemory("reconcile episode run") { it.reconcileEpisodeRun(runId) }

    fun personalAgentReconcileForeignEpisodeProcessesSync(processId: String) =
        withAgentMemory("reconcile abandoned episode runs") { it.reconcileForeignEpisodeProcesses(processId) }

    fun personalAgentPendingEpisodeNarrationsSync(conversationId: String): List<PersonalMemoryHit> =
        personalAgentPendingEpisodeNarrationsSync(conversationId, null)

    fun personalAgentPendingEpisodeNarrationsSync(conversationId: String, episodeIds: Collection<String>?): List<PersonalMemoryHit> {
        val selected = episodeIds?.distinct()
        if (selected != null && selected.isEmpty()) return emptyList()
        return withAgentMemory("read episode narration queue") { it.pendingEpisodeNarrations(conversationId, selected) }
    }

    fun personalAgentEpisodeNarrationInputSync(id: String): EpisodeNarrationInput? =
        withAgentMemory("read episode narration evidence") { it.episodeNarrationInput(id) }

    fun personalAgentCommitEpisodeNarrationSync(id: String, expected: EpisodeNarrationInput,
        state: String, title: String?, text: String?): Boolean =
        withAgentMemory("commit episode narration") { it.commitEpisodeNarration(id, expected, state, title, text) }

    fun personalAgentEpisodesForSourceMessagesSync(conversationId: String, messageIds: List<String>): List<EpisodeReference> =
        withAgentMemory("read compacted episode references") { it.episodesForSourceMessages(conversationId, messageIds) }

    fun personalAgentSetEpisodeNarrationSync(id: String, state: String, title: String?, text: String?) =
        withAgentMemory("update episode narration") { it.setEpisodeNarration(id, state, title, text) }

    fun personalAgentExtractionCursorSync(conversationId: String): Long =
        withAgentMemory("read extraction cursor") { it.extractionCursor(conversationId) }

    fun personalAgentAdvanceExtractionCursorSync(conversationId: String, throughSeq: Long) =
        withAgentMemory("advance extraction cursor") { it.advanceExtractionCursor(conversationId, throughSeq) }

    fun personalAgentPromptContext(conversationId: String, memoryEnabled: Boolean): PersonalAgentPromptContext =
        personalAgentPromptContext(conversationId, memoryEnabled, "principal")

    /** [runKind] is the wire name of the run's `RunKind`; unattended runs pass an empty [conversationId]. */
    fun personalAgentPromptContext(conversationId: String, memoryEnabled: Boolean,
                                   runKind: String): PersonalAgentPromptContext =
        withAgentMemory("read personal context") {
            // Existing profile/docs projection is not a canonical memory-context grant.
            val canonical = conversationId == MemoryNamespace.CENTRAL && runKind == "principal"
            val namespace = if (memoryEnabled && canonical)
                MemoryNamespace.resolve(applicationContext, conversationId, null, null, false, true)
            else MemoryNamespace.centralRead()
            it.promptContext(conversationId, memoryEnabled, runKind, namespace, canonical)
        }

    /** Trusted request-local namespace already resolved outside Room; it is not a model argument. */
    @JvmOverloads
    fun personalAgentPromptContextWithNamespace(conversationId: String, memoryEnabled: Boolean,
        runKind: String, namespace: MemoryNamespace, reminder: String = "",
        compactionSession: MemorySessionBinding? = null): PersonalAgentPromptContext =
        withAgentMemory("read personal context") {
            require(conversationId == MemoryNamespace.CENTRAL && runKind == "principal") { "memory_caller_denied" }
            it.promptContext(conversationId, memoryEnabled, runKind, namespace, true, reminder, compactionSession)
        }

    fun personalAgentSetDirective(section: String, directive: String, sourceMessageId: String?, evidence: String,
                                  supersedesId: String? = null, conversationId: String? = null): PersonalMemoryReceipt =
        withAgentMemory("save user directive") { it.setDirective(section, directive, sourceMessageId, evidence, supersedesId, conversationId) }

    fun personalAgentSaveMemory(text: String, layer: String, category: String?, importance: Double,
                                sourceRef: String?, options: MemorySaveOptions): PersonalMemoryReceipt =
        withAgentMemory("save memory") { it.saveMemory(text, layer, category, importance, sourceRef, options) }

    fun personalAgentDailyConsolidationCandidates(): List<PersonalMemoryCandidate> =
        withAgentMemory("read daily consolidation candidates") { it.dailyConsolidationCandidates() }

    fun personalAgentConsolidateDailyNotes(sourceIds: List<String>, summary: String): PersonalMemoryReceipt =
        withAgentMemory("consolidate daily notes") { it.consolidateDailyNotes(sourceIds, summary) }

    fun personalAgentTimeline(id: String, before: Int, after: Int,
        callerConversation: String?, project: String?): PersonalMemoryTimeline =
        withAgentMemory("read memory timeline") { repository ->
            val namespace = MemoryNamespace.resolve(applicationContext, callerConversation, project, null, false, true)
            repository.timeline(id, before, after, namespace)
        }

    @JvmOverloads
    fun personalAgentList(limit: Int = 8, filter: MemorySearchFilter = MemorySearchFilter()): List<PersonalMemoryHit> =
        personalAgentList(limit, filter, MemoryNamespace.CENTRAL, null, null, false)

    fun personalAgentList(limit: Int, filter: MemorySearchFilter, callerConversation: String?,
        project: String?, scope: String?, allProjects: Boolean): List<PersonalMemoryHit> =
        withAgentMemory("list memory") { repository ->
            val namespace = MemoryNamespace.resolve(applicationContext, callerConversation, project, scope, allProjects, true)
            repository.listMemories(limit, filter, namespace)
        }

    fun personalAgentSearch(query: String, limit: Int = 10): List<PersonalMemoryHit> =
        personalAgentSearch(query, limit, MemorySearchFilter(), MemoryNamespace.CENTRAL, null, null, false)

    fun personalAgentSearch(query: String, limit: Int, filter: MemorySearchFilter): List<PersonalMemoryHit> =
        personalAgentSearch(query, limit, filter, MemoryNamespace.CENTRAL, null, null, false)

    @JvmOverloads
    fun personalAgentSearch(query: String, limit: Int, filter: MemorySearchFilter,
        callerConversation: String?, project: String?, scope: String?, allProjects: Boolean,
        activity: com.jarvys.agent.MemorySessionActivity.Handle? = null): List<PersonalMemoryHit> =
        withAgentMemory("search memory") { repository ->
            CanonicalMemorySearch.normalizeMatchMode(filter.matchMode)
            val namespace = MemoryNamespace.resolve(applicationContext, callerConversation, project, scope, allProjects, true)
            repository.search(query, limit, filter, namespace, activity)
        }

    internal var compactionClock:java.time.Clock = java.time.Clock.systemUTC()
    @JvmOverloads
    fun beginCompactionMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,parent:MemorySessionBinding,coverage:CompactionCoverage?=null):CompactionMemoryBinding = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,false,true,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();true},Callable {
            runBlocking { repos.database.withChatWriteTransaction {
                token.throwIfCancelled();CompactionExecutionRepository(repos.database,compactionClock).begin(parent,coverage)
            } }
        })
    }
    fun finishCompactionMemory(ticket:MemoryWriteGate.Ticket,binding:CompactionMemoryBinding,outcome:String):Boolean = executeOnWorker { repos ->
        require(outcome in setOf("incomplete","cancelled")) { "memory_compaction_cleanup_outcome_invalid" }
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{true},Callable {
            runBlocking { CompactionExecutionRepository(repos.database,compactionClock).finish(binding,outcome) }
        })
    }
    fun saveCompactionMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:CompactionMemoryBinding,request:MemorySaveRequest):PersonalMemoryReceipt =
        executeCompactionMemory(ticket,token,binding) { repos,namespace ->
            check(request.callerConversation==MemoryNamespace.CENTRAL && (request.requestedProject==null || request.requestedProject==MemoryNamespace.CENTRAL)) { "memory_compaction_scope_invalid" }
            repos.agentMemory.saveMemory(request.text,request.layer,request.category,request.importance,request.sourceRef,request.options,namespace.selectWrite(request.requestedProject,request.rawScope),compaction=binding)
        }
    fun setCompactionDirective(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:CompactionMemoryBinding,
        section:String,directive:String,source:String?,evidence:String,supersedes:String?,conversation:String?):PersonalMemoryReceipt =
        executeCompactionMemory(ticket,token,binding) { repos,_ ->
            check(conversation==MemoryNamespace.CENTRAL) { "memory_compaction_scope_invalid" }
            repos.agentMemory.setDirective(section,directive,source,evidence,supersedes,conversation)
        }
    fun setCompactionNarration(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:CompactionMemoryBinding,id:String,state:String,title:String?,text:String?) =
        executeCompactionMemory(ticket,token,binding,true) { repos,_ ->
            check(binding.acknowledgedCoverage?.episodes?.any { it.id==id }==true) { "memory_compaction_narration_scope_invalid" }
            repos.agentMemory.setEpisodeNarration(id,state,title,text)
        }
    fun commitCompactionNarration(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:CompactionMemoryBinding,id:String,expected:EpisodeNarrationInput,state:String,title:String?,text:String?):Boolean =
        executeCompactionMemory(ticket,token,binding,true) { repos,_ ->
            check(binding.acknowledgedCoverage?.episodes?.any { it.id==id }==true) { "memory_compaction_narration_scope_invalid" }
            repos.agentMemory.commitEpisodeNarration(id,expected,state,title,text)
        }
    private fun <T> executeCompactionMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:CompactionMemoryBinding,
        narration:Boolean=false,block:suspend(Repositories,MemoryNamespace)->T):T {
        val previous=if(narration) binding.acknowledgedCoverage else null
        try {
            val acknowledged=executeOnWorker { repos ->
                binding.check(token)
                val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,false,true,true)
                MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{binding.check(token);true},Callable {
                    runBlocking { repos.database.withChatWriteTransaction {
                        binding.check(token)
                        val execution=CompactionExecutionRepository(repos.database,compactionClock)
                        execution.requireCurrent(binding)
                        if(narration) check(previous!=null && CompactionMemoryRepository(repos.database).prepare(previous.conversationId,previous.sources)==previous) { "memory_compaction_source_changed" }
                        // Only these narrow closures: no provider or swallowed savepoint rollback.
                        val before=execution.changes()
                        val result=block(repos,namespace)
                        binding.check(token)
                        if(execution.changes()>before) execution.renew(binding)
                        val after=if(narration) CompactionMemoryRepository(repos.database).prepare(requireNotNull(previous).conversationId,previous.sources) else null
                        if(narration) check(after!=null && after.sources==previous?.sources && after.leaves==previous?.leaves && after.coverageHash==previous?.coverageHash) { "memory_compaction_source_changed" }
                        Pair(result,after)
                    } }
                })
            }
            // Only a returned transport ACK can advance the exact own-mutation snapshot.
            if(narration) binding.acknowledge(previous,acknowledged.second)
            return acknowledged.first
        } catch(failure:RuntimeException) { binding.recordFailure(failure);throw failure }
    }

    internal var maintenanceClock:java.time.Clock = java.time.Clock.systemUTC()
    internal fun maintenanceNow():Long = maintenanceClock.millis()
    fun claimMaintenanceJob(ticket:MemoryWriteGate.Ticket,token:CancellationToken,id:String,owner:String,now:Long,leaseUntil:Long,
        workerActive:java.util.function.BooleanSupplier=java.util.function.BooleanSupplier { true }):MaintenanceClaimResult = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,false,true,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{if(!workerActive.asBoolean) token.cancel();token.throwIfCancelled();true},Callable {
            runBlocking { MaintenanceMemoryRepository(repos.database,maintenanceClock).claim(id,owner,now,leaseUntil) { if(!workerActive.asBoolean) token.cancel();token.throwIfCancelled() } }.let { result ->
                result.copy(binding=result.binding?.withWorkerCheck { workerActive.asBoolean })
            }
        })
    }
    fun completeMaintenanceJob(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding):Boolean =
        executeMaintenance(ticket,token,binding,setOf("extract","snapshot","narrate","daily_consolidate")) { repos,_ -> MaintenanceMemoryRepository(repos.database,maintenanceClock).complete(binding) }
    fun retryMaintenanceJob(ticket:MemoryWriteGate.Ticket,binding:MaintenanceMemoryBinding,reason:String,availableAt:Long):Boolean = executeOnWorker { repos ->
        try { MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{true},Callable {
            runBlocking { MaintenanceMemoryRepository(repos.database,maintenanceClock).retry(binding,reason,availableAt,true) }
        }) } catch(rejected:MemoryWriteGate.RejectedException) {
            runBlocking { MaintenanceMemoryRepository(repos.database,maintenanceClock).retry(binding,reason,availableAt,false) }
        }
    }
    fun abortDeletedMaintenanceJob(binding:MaintenanceMemoryBinding):Boolean = executeOnWorker { repos ->
        runBlocking { MaintenanceMemoryRepository(repos.database,maintenanceClock).deleted(binding) }
    }
    fun saveMaintenanceMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,request:MemorySaveRequest):PersonalMemoryReceipt =
        executeMaintenance(ticket,token,binding,setOf("extract","snapshot")) { repos,namespace ->
            check(request.callerConversation==binding.sourceConversation && (request.requestedProject==null || request.requestedProject==MemoryNamespace.CENTRAL)) { "memory_job_scope_invalid" }
            val selected=namespace.selectWrite(request.requestedProject,request.rawScope)
            repos.agentMemory.saveMemory(request.text,request.layer,request.category,request.importance,request.sourceRef,request.options,selected,maintenance=binding)
        }
    fun setMaintenanceDirective(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,
        section:String,directive:String,source:String?,evidence:String,supersedes:String?,conversation:String?):PersonalMemoryReceipt =
        executeMaintenance(ticket,token,binding,setOf("extract","snapshot")) { repos,_ ->
            check(conversation==binding.sourceConversation) { "memory_job_scope_invalid" }
            repos.agentMemory.setDirective(section,directive,source,evidence,supersedes,conversation)
        }
    fun consolidateMaintenanceMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,ids:List<String>,summary:String):PersonalMemoryReceipt =
        executeMaintenance(ticket,token,binding,setOf("daily_consolidate")) { repos,_ -> repos.agentMemory.consolidateDailyNotes(ids,summary,binding) }
    fun setMaintenanceNarration(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,id:String,state:String,title:String?,text:String?) =
        executeMaintenance(ticket,token,binding,setOf("narrate")) { repos,_ -> repos.agentMemory.setEpisodeNarration(id,state,title,text) }
    fun commitMaintenanceNarration(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,id:String,expected:EpisodeNarrationInput,state:String,title:String?,text:String?):Boolean =
        executeMaintenance(ticket,token,binding,setOf("narrate")) { repos,_ -> repos.agentMemory.commitEpisodeNarration(id,expected,state,title,text) }
    fun advanceMaintenanceCursor(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,conversation:String,throughSeq:Long) =
        executeMaintenance(ticket,token,binding,setOf("extract","snapshot")) { repos,_ ->
            check(conversation==binding.sourceConversation) { "memory_job_scope_invalid" };repos.agentMemory.advanceExtractionCursor(conversation,throughSeq)
        }
    private fun <T> executeMaintenance(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MaintenanceMemoryBinding,
        allowed:Set<String>,block:suspend(Repositories,MemoryNamespace)->T):T = executeOnWorker { repos ->
        binding.checkCancellation(token)
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,false,true,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{binding.checkCancellation(token);true},Callable {
            runBlocking { repos.database.withChatWriteTransaction {
                binding.checkCancellation(token)
                try { MaintenanceMemoryRepository(repos.database,maintenanceClock).requireCurrent(binding,allowed) }
                catch(deleted:MaintenanceSessionDeletedException) { binding.recordDeletion(deleted);throw deleted }
                block(repos,namespace)
            } }
        })
    }

    fun personalAgentGet(ids: List<String>, withEvidence: Boolean, callerConversation: String?): List<PersonalMemoryHit> =
        personalAgentGet(ids, withEvidence, callerConversation, false, 0L)

    fun personalAgentGet(ids: List<String>, withEvidence: Boolean, callerConversation: String?, includeHistory: Boolean, historyCursor: Long): List<PersonalMemoryHit> =
        withAgentMemory("read memory") { repository ->
            val namespace = MemoryNamespace.resolve(applicationContext, callerConversation, null, null, false, true, true)
            repository.get(ids, withEvidence, namespace, includeHistory, historyCursor, 50)
        }

    fun personalAgentPromptContext(conversationId: String, memoryEnabled: Boolean, runKind: String,
        project: String?, scope: String?): PersonalAgentPromptContext = withAgentMemory("memory context") {
            val namespace = MemoryNamespace.resolve(applicationContext, conversationId, project, scope, false, true)
            it.promptContext(conversationId, memoryEnabled, runKind, namespace)
        }

    fun personalAgentSearchCapability(): String =
        withAgentMemory("read memory search capability") { it.searchCapability() }

    fun personalAgentGet(ids: List<String>): List<PersonalMemoryHit> =
        personalAgentGet(ids, false, MemoryNamespace.CENTRAL)

    fun personalAgentGet(ids: List<String>, withEvidence: Boolean): List<PersonalMemoryHit> =
        personalAgentGet(ids, withEvidence, MemoryNamespace.CENTRAL)

    @JvmOverloads
    fun personalAgentBrowse(includeDeleted: Boolean = false): List<PersonalMemoryItem> =
        withAgentMemory("browse memory") { it.browse(MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true), includeDeleted) }

    /** Trusted runtime producer only; session IDs are allocated once before this call, never from model input. */
    fun beginMemorySession(ticket: MemoryWriteGate.Ticket, token: CancellationToken, canonicalId: String,
        conversation: String, ownerMessage: String, runId: String, processId: String): MemorySessionBinding = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext, conversation, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { token.throwIfCancelled(); true }, Callable {
            runBlocking { MemorySessionRepository(repos.database).begin(canonicalId, conversation, ownerMessage, runId, processId) }
        })
    }
    fun memorySessionCommandAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        action: String, args: Map<String, Any?>, witness: com.jarvys.agent.CentralInvocationWitness): Any = executeOnWorker { repos ->
        val claimed=witness.source()
        val content=MemorySessionCommands.content(action,args)
        val request=MemorySessionCommands.request(content,claimed.ownerCentralSessionId,claimed.sourceMessageId)
        val namespace=MemoryNamespace.resolve(applicationContext,claimed.ownerCentralSessionId,null,null,false,true,true)
        val input=MemorySaveOperationInput(request,witness,action)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { token.throwIfCancelled(); witness.requireInvocationCurrent(); true }, Callable {
            runBlocking {
                if(action==MemorySessionCommands.FINALIZE) MemorySessionRepository(repos.database).finalize(input,namespace)
                else repos.agentMemory.saveMemory(request.text,request.layer,request.category,request.importance,request.sourceRef,request.options,namespace,input)
            }
        })
    }
    /** Internal completed-answer producer. No transcript scan, retry, fallback session, or model tool. */
    @JvmOverloads
    fun capturePassiveMemory(ticket:MemoryWriteGate.Ticket,token:CancellationToken,binding:MemorySessionBinding,
        answer:String,activity:com.jarvys.agent.MemorySessionActivity.Handle?=null):MemoryPassiveCaptureResult = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext,binding.ownerConversation,null,null,false,true,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();true},Callable {
            runBlocking { repos.database.withChatReadTransaction { MemorySessionRepository(repos.database).requirePassiveCurrent(binding) } }
        })
        activity?.recordToolCall()
        val learnings=MemoryPassiveCapture.extractLearnings(answer)
        var saved=0;var duplicates=0
        try {
            learnings.forEachIndexed { ordinal,learning ->
                val namespace=MemoryNamespace.resolve(applicationContext,binding.ownerConversation,null,null,false,true,true)
                val didSave=MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();true},Callable {
                    runBlocking { repos.agentMemory.capturePassiveItem(learning,ordinal,binding,namespace) }
                })
                if(didSave) saved++ else duplicates++
            }
        } catch(failure:Exception) {
            throw MemoryPassiveCaptureException(MemoryPassiveCaptureResult(learnings.size,saved,duplicates),failure)
        }
        MemoryPassiveCaptureResult(learnings.size,saved,duplicates)
    }
    fun validateMemoryResumeReceipt(receipt: MemorySessionResumeReceipt): Boolean = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext, receipt.conversationId, null, null, false, true, true)
        runBlocking { repos.database.withChatReadTransaction { MemorySessionRepository(repos.database).validResumeReceipt(receipt) } }
    }
    fun beginResumedMemorySession(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        admission: com.jarvys.agent.AgentForegroundService.MemoryResumeAdmission,
        runId: String, processId: String): MemorySessionBinding = executeOnWorker { repos ->
        admission.requireCurrent(token)
        val receipt=admission.receipt
        MemoryNamespace.resolve(applicationContext, receipt.conversationId, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { admission.requireCurrent(token); token.throwIfCancelled(); true }, Callable {
            runBlocking { MemorySessionRepository(repos.database).resume(receipt,runId,processId) }
        })
    }
    fun renewMemorySession(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        binding: MemorySessionBinding): Boolean = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext, binding.ownerConversation, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { token.throwIfCancelled(); true }, Callable {
            runBlocking { MemorySessionRepository(repos.database).renew(binding) }
        })
    }
    fun captureMemoryPrompt(ticket: MemoryWriteGate.PromptCaptureTicket, token: CancellationToken,
        binding: MemorySessionBinding): String = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext, binding.ownerConversation, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commitPrompt(ticket, { token.throwIfCancelled(); true }, Callable {
            runBlocking { MemorySessionRepository(repos.database).capturePrompt(binding) }
        })
    }
    /** Cancellation is the outcome here, not permission to create content. OFF/ABA still denies this commit. */
    fun finishMemorySession(ticket: MemoryWriteGate.Ticket, binding: MemorySessionBinding, outcome: String) = executeOnWorker { repos ->
        MemoryNamespace.resolve(applicationContext, binding.ownerConversation, null, null, false, true, true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { true }, Callable {
            runBlocking { MemorySessionRepository(repos.database).finish(binding, outcome) }
        })
    }
    fun memoryReviewList(ticket:MemoryWriteGate.Ticket,token:CancellationToken,caller:String?,project:String?,limit:Long):List<PersonalMemoryHit> = executeOnWorker { repos ->
        token.throwIfCancelled()
        val all=MemoryObservationContent.trimSpace(project.orEmpty()).isEmpty()
        val namespace=MemoryNamespace.resolve(applicationContext,caller,project,null,all,true)
        val reviewNow=System.currentTimeMillis()
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();true},Callable {
            runBlocking { repos.database.withChatReadTransaction {
                token.throwIfCancelled()
                repos.agentMemory.memoriesNeedingReview(namespace,limit,reviewNow).also { token.throwIfCancelled() }
            } }
        })
    }

    fun memoryMarkReviewedAutomatic(ticket:MemoryWriteGate.Ticket,token:CancellationToken,caller:String?,project:String?,id:String,
        witness:com.jarvys.agent.CentralInvocationWitness):PersonalMemoryReviewReceipt =
        executeBoundMemoryMutation(ticket,token,caller,project?.takeIf { MemoryObservationContent.trimSpace(it).isNotEmpty() },false,"memory_review",witness,reviewTargetId=id) { repos,namespace ->
            repos.agentMemory.markReviewed(id,namespace.project,namespace)
        }

    @JvmOverloads
    fun personalAgentMemoriesNeedingReview(project:String?=null,limit:Long=20):List<PersonalMemoryItem> = withAgentMemory("read memories needing review") {
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,project,null,
            MemoryObservationContent.trimSpace(project.orEmpty()).isEmpty(),true)
        it.reviewItems(namespace,limit)
    }
    /** Explicit human metadata editing remains independent of the automatic-memory switch. */
    fun personalAgentMarkReviewed(id:String,expectedProject:String):PersonalMemoryReviewReceipt = withAgentMemory("mark memory reviewed") {
        val namespace=MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,expectedProject,null,false,true)
        it.markReviewed(id,expectedProject,namespace)
    }

    @JvmOverloads
    fun memoryContext(ticket:MemoryWriteGate.Ticket, token:CancellationToken, callerConversation:String?,
        project:String?, scope:String?, compact:Boolean, olderHistory:Boolean, maxBytes:Int,
        activity:com.jarvys.agent.MemorySessionActivity.Handle?=null):String = executeOnWorker { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext,callerConversation,project,scope,false,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();true},Callable {
            token.throwIfCancelled()
            // One reached handler event, outside the replayable read snapshot. This handle grants nothing.
            activity?.recordToolCall()
            val reminder=activity?.reminder(namespace.project).orEmpty()
            runBlocking { repos.database.withChatReadTransaction {
                token.throwIfCancelled()
                repos.agentMemory.canonicalContext(namespace,compact,olderHistory,
                    CanonicalMemoryContextArguments.maxBytes(maxBytes),reminder).also { token.throwIfCancelled() }
            } }
        })
    }

    fun searchMemoryPrompts(ticket:MemoryWriteGate.Ticket, token:CancellationToken, callerConversation:String?,
        query:String, limit:Long, project:String?, allProjects:Boolean):List<PersonalMemoryPromptView> = execute { repos ->
        require(query.isNotEmpty()) { "memory_search_prompts_query_required" }
        require(project==null || MemoryObservationContent.trimSpace(project).isNotEmpty()) { "memory_search_prompts_project_invalid" }
        require(!allProjects || project==null) { "memory_search_prompts_project_conflict" }
        val namespace=MemoryNamespace.resolve(applicationContext,callerConversation,project,null,allProjects,true)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, { token.throwIfCancelled(); true }, Callable {
            runBlocking { repos.database.withChatReadTransaction {
                token.throwIfCancelled()
                MemorySessionRepository(repos.database).searchPrompts(query,limit,namespace,token)
            } }
        })
    }

    fun personalAgentMemorySessions(): List<PersonalMemorySessionView> = execute { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)
        MemorySessionRepository(repos.database).sessions(namespace)
    }
    fun personalAgentMemoryPrompts(sessionId: String): List<PersonalMemoryPromptView> = execute { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)
        MemorySessionRepository(repos.database).prompts(sessionId,namespace)
    }
    fun personalAgentDeleteMemoryPrompt(id: String, expectedProject: String): PersonalMemoryDomainDeletionReceipt = execute { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)
        MemorySessionRepository(repos.database).deletePrompt(id,expectedProject,namespace)
    }
    fun personalAgentDeleteMemorySession(id: String, expectedProject: String): PersonalMemoryDomainDeletionReceipt = execute { repos ->
        val namespace=MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)
        MemorySessionRepository(repos.database).deleteSession(id,expectedProject,namespace)
    }

    /** Manual memory-domain project management; no autonomous tool, transcript, or workspace deletion. */
    fun personalAgentMemoryProjects(): List<PersonalMemoryProjectSummary> =
        withAgentMemory("list projects with memories") { it.memoryProjects(
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    fun personalAgentPreviewProjectDeletion(project: String, hardDelete: Boolean): PersonalMemoryProjectDeletionPreview =
        withAgentMemory("preview project memory deletion") { it.previewProjectDeletion(project, hardDelete,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    fun personalAgentDeleteProjectMemories(preview: PersonalMemoryProjectDeletionPreview): PersonalMemoryProjectDeletionReceipt =
        withAgentMemory("delete project memories") { it.deleteProjectMemories(preview,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    fun personalAgentRevisions(targetId: String): List<PersonalMemoryRevision> =
        withAgentMemory("read directive revision history") { it.revisions(targetId) }

    fun personalAgentSetPinned(id: String, pinned: Boolean): PersonalMemoryReceipt =
        withAgentMemory("update memory pin") { it.setPinned(id, pinned,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    /** Explicit user UI operation; not an automatic memory tool entry point. */
    fun personalAgentDeleteObservation(id: String, expectedProject: String): PersonalMemoryReceipt =
        withAgentMemory("delete memory observation") { it.deleteObservation(id, expectedProject,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    /** Explicit irreversible manual action; never registered as an autonomous tool. */
    fun personalAgentHardDeleteObservation(id: String, expectedProject: String): PersonalMemoryDeletionReceipt =
        withAgentMemory("permanently delete memory observation") { it.hardDeleteObservation(id, expectedProject,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    fun personalAgentForgetDirective(id: String): PersonalMemoryReceipt =
        withAgentMemory("forget profile directive") { it.forgetDirective(id) }

    fun personalAgentForget(id: String): PersonalMemoryReceipt =
        withAgentMemory("forget memory") { it.forget(id, MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, null, null, false, true, true)) }

    fun personalAgentUpdate(id: String, expectedProject: String, options: MemoryUpdateOptions): PersonalMemoryReceipt =
        withAgentMemory("update memory") { it.update(id, expectedProject, options,
            MemoryNamespace.resolve(applicationContext, MemoryNamespace.CENTRAL, expectedProject, null, false, true, true)) }

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
        withAgentMemory("queue memory maintenance") { it.enqueueMemoryJob(conversationId, type, maintenanceClock.millis()) }

    fun personalAgentClaimJob(id: String, owner: String, now: Long, leaseUntil: Long): PersonalMemoryJob? =
        withAgentMemory("claim memory maintenance") { it.claimExtractionJob(id, owner, now, leaseUntil) }

    fun personalAgentCompleteJob(id: String, owner: String): Boolean =
        withAgentMemory("complete memory maintenance") { it.completeJob(id, owner) }

    fun personalAgentRetryJob(id: String, owner: String, error: String, availableAt: Long): Boolean =
        withAgentMemory("retry memory maintenance") { it.retryJob(id, owner, error, availableAt) }

    /** Technical reservation is independent of semantic-memory preference and does not enqueue work. */
    fun reserveCodingJobSync(submission: com.jarvys.agent.coding.CodingJobSubmission): CodingJobReservation =
        executeCodingOnWorker(submission) { repos, operation ->
            submission.requireCaptureCurrent()
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            check(submission.journal() === journal) { "Foreign coding journal store" }
            // Workspace SQLite must never be opened while holding the Room lifecycle fence.
            val source = resolveCodingJobSource(applicationContext, submission)
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            val reserved = anchor.fenced {
                val binding = runBlocking { CodingJobRepository(repos.database).reserve(submission, source) }
                val reservation = CodingJobReservation.confirmed(binding, submission, journal,
                    anchor.attest(repos.database, binding.technicalStoreGeneration))
                submission.captureConfirmed()
                reservation
            }
            operation.completeReservation(reserved)
            reserved
        }

    /** A post-journal administrative ACK, not process execution or a semantic-memory receipt. */
    fun acceptCodingJobSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit): CodingJobAcceptance =
        executeCodingOnWorker(commit.reservation().submission(), acceptance = true) { repos, operation ->
            recordCodingCommitInWorker(repos, commit, CodingJobRepository.Kind.SUBMISSION_ACCEPTED)
            // Publish the original post-TX ACK even if the waiting caller was interrupted. The
            // anchor and SQL lease are released; listeners may schedule, never re-enter this bridge.
            val accepted = CodingJobAcceptance.confirmed(commit.reservation(), commit)
            operation.completeAcceptance(accepted)
            accepted
        }

    /** Exact durable preparation admission; return before calling the entire lazy backend. */
    fun admitCodingPreparationSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit) {
        recordCodingCommit(commit, CodingJobRepository.Kind.PREPARATION_ADMITTED)
    }

    /** Technical terminal/history projection. Never repairs source attribution or replays a command. */
    fun projectCodingJobSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit) {
        recordCodingCommit(commit, CodingJobRepository.Kind.JOURNAL_PROJECTION)
    }

    /** Durable exact-source cancellation intent. A late ACK never resumes the cancellation caller. */
    /** Optional attribution lookup only; always validate the live cancellation witness around IO. */
    fun resolveCodingCancellationSource(invocation: EffectInvocation): com.jarvys.agent.coding.CodingJobEvidence.Source? {
        check(LeasedConnectionElement.current()==null && RawReadConnectionElement.current()==null)
        val witness=checkNotNull(invocation.crewSource()) { "Cancellation requires its actual Crew source" }
        witness.requireInvocationCurrent()
        val source=witness.source()
        val resolved=try {resolveCodingJobSource(applicationContext,source)}
            // Attribution is optional, never cancellation authority. A current store rejected by
            // fresh-schema preflight stays unavailable; do not adopt it or invent semantic roles.
            catch(_:com.jarvys.agent.CleanInstallRequiredException){null}
            catch(_:android.database.sqlite.SQLiteException){null}
            catch(_:java.io.IOException){null}
        witness.requireInvocationCurrent()
        check(invocation.crewSource()===witness && witness.source()===source) { "Cancellation source changed during attribution lookup" }
        return resolved
    }

    fun linkCodingCancelSemanticSync(checkpoint: CodingCancellationCheckpoint) {
        val result=checkNotNull(checkpoint.result()) { "Semantic cancellation requires RESULT, never INTENT" }
        checkpoint.requireResult(result)
        val request=result.request()
        executeCancellationOnWorker(request) {repos,_ ->
            linkCodingCancellationInWorker(repos,request.reservation(),request.semanticTicket(),request.requestId()) {
                CodingCancellationRepository(repos.database).requireCompleted(result)
            }
        }
    }
    fun linkCodingCancelSemanticSync(checkpoint: CodingCancellationObservationCheckpoint) {
        val observation=checkpoint.observation();checkpoint.requireFor(observation)
        val operation=observation.beginOperation()
        executeOwnedOnWorker(operation::fail,operation::close,null) {repos ->
            linkCodingCancellationInWorker(repos,observation.reservation(),observation.semanticTicket(),observation.observationId()) {
                CodingCancellationRepository(repos.database).requireObserved(observation)
            }
        }
    }
    private fun linkCodingCancellationInWorker(repos:Repositories,reservation:CodingJobReservation,
        ticket:MemoryWriteGate.Ticket,id:String,verify:()->Unit) {
        reservation.requireJournalOwner(com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir))
        val anchor=JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
        fun current(){check(anchor.accepts(reservation.lifecycle(),repos.database));reservation.requireCurrentProjection()}
        try {
            MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{true},Callable {
                anchor.fenced {current();runBlocking {repos.database.withChatWriteTransaction {
                    verify();CodingCancellationSemanticRepository(repos.database).link(id)
                }}}
            })
        } catch(_:MemoryWriteGate.RejectedException) {
            anchor.fenced {current();runBlocking {repos.database.withChatWriteTransaction {
                verify();CodingCancellationSemanticRepository(repos.database).suppress(id)
            }}}
        }
    }
    fun personalAgentCodingCancellationSourceSync(entryId:String,requestId:String,maxChars:Int):JSONObject =
        execute {repos ->repos.database.withChatReadTransaction {
            CodingCancellationSemanticRepository(repos.database).source(entryId,requestId,maxChars)
        }}

    /** Records a real known no-op; this checkpoint cannot authorize a cancellation request. */
    fun observeCodingCancellationSync(observation: com.jarvys.agent.coding.CodingCancellation.Observation): CodingCancellationObservationCheckpoint {
        val operation=observation.beginOperation()
        return executeOwnedOnWorker(operation::fail,operation::close,observation::abandonDispatch) { repos ->
            val reservation=observation.reservation()
            val journal=com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            reservation.requireJournalOwner(journal)
            observation.requireIssuer()
            val anchor=JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            val checkpoint=anchor.fenced {
                check(anchor.accepts(reservation.lifecycle(),repos.database)) { "Stale cancellation observation destination" }
                reservation.requireCurrentProjection()
                runBlocking { CodingCancellationRepository(repos.database).observe(observation) }
                CodingCancellationObservationCheckpoint.confirmed(observation)
            }
            observation.captureConfirmed()
            operation.complete(checkpoint) // Historical publication outside the Room/anchor locks.
            checkpoint
        }
    }

    fun admitCodingCancelSync(request: com.jarvys.agent.coding.CodingCancellation.Request,
        admission: EffectInvocation.Admission): CodingCancellationCheckpoint = executeCancellationOnWorker(request) { repos, operation ->
        val reservation = request.reservation()
        val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
        reservation.requireJournalOwner(journal)
        val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
        val checkpoint = anchor.fenced {
            check(anchor.accepts(reservation.lifecycle(), repos.database)) { "Stale cancellation target destination" }
            reservation.requireCurrentProjection()
            runBlocking { CodingCancellationRepository(repos.database).admit(request, admission) }
            CodingCancellationCheckpoint.confirmed(request, null, admission, reservation.lifecycle())
        }
        request.captureConfirmed()
        operation.complete(checkpoint) // Outside Room/anchor, historical observation only.
        checkpoint
    }

    /** Persists the exact manager result; never signals a token or derives a result from stored JSON. */
    fun finishCodingCancelSync(result: com.jarvys.agent.coding.CodingCancellation.Result): CodingCancellationCheckpoint =
        executeCancellationOnWorker(result.request()) { repos, operation ->
            val request = result.request()
            val intent = result.intentCheckpoint()
            intent.requireIntent(request)
            result.requireIssuer()
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            request.reservation().requireJournalOwner(journal)
            result.cancellingCommit()?.let { commit ->
                commit.requireMintedBy(journal)
                check(commit.reservation() === request.reservation() &&
                    commit.snapshot().state == com.jarvys.agent.coding.CodingJobSnapshot.State.CANCELLING) {
                    "Cancellation result requires its exact CANCELLING journal"
                }
            }
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            val checkpoint = anchor.fenced {
                check(anchor.accepts(intent.lifecycle(), repos.database)) { "Stale cancellation result destination" }
                intent.requireCurrentProjection()
                runBlocking { CodingCancellationRepository(repos.database).finish(result) }
                CodingCancellationCheckpoint.confirmed(request, result, intent.admission(), intent.lifecycle())
            }
            operation.complete(checkpoint)
            checkpoint
        }

    /** Existing-only verification: never creates a generation, binding, source or reservation. */
    fun verifyExistingCodingJobSync(candidate: com.jarvys.agent.coding.CodingJobJournalStore.RecoveryCandidate): CodingJobRecoveryBinding =
        executeOnWorker { repos ->
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            check(candidate.journal() === journal) { "Foreign Coding recovery journal" }
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            anchor.fenced {
                runBlocking { CodingJobRepository(repos.database).verifyExisting(candidate.record().binding) }
                CodingJobRecoveryBinding.verified(candidate, journal,
                    anchor.attest(repos.database, candidate.record().binding.technicalStoreGeneration))
            }
        }

    /** Recertified journal facts only; no invocation, ticket, acceptance or command is recreated. */
    fun projectRecoveredCodingJobSync(verified: com.jarvys.agent.coding.CodingJobJournalStore.RecoveryVerified) =
        executeOnWorker { repos ->
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            verified.requireMintedBy(journal)
            val binding = verified.recoveryBinding()
            binding.requireFor(binding.candidate(), journal)
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            anchor.fenced {
                check(anchor.accepts(binding.lifecycle(), repos.database)) { "Stale Coding recovery destination" }
                binding.requireCurrentProjection()
                runBlocking { CodingJobRepository(repos.database).recordRecovered(verified) }
            }
        }

    /** Live-owner terminal repair only. This never admits or dispatches a command. */
    fun repairCodingJobTerminalSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit,
        pin: com.jarvys.agent.coding.CodingJobOwnerLease.Pin) {
        val submission = commit.reservation().submission()
        val operation = submission.beginRepairOperation(pin)
        executeTrackedOnWorker(operation, null) { repos ->
            val reservation = commit.reservation()
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            reservation.requireJournalOwner(journal)
            commit.requireMintedBy(journal)
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            anchor.fenced {
                check(anchor.accepts(reservation.lifecycle(), repos.database)) { "Stale coding technical reservation" }
                reservation.requireCurrentProjection()
                submission.requireRepairReady()
                runBlocking { CodingJobRepository(repos.database).repairTerminal(commit) }
            }
        }
    }

    private fun recordCodingCommit(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit,
                                   kind: CodingJobRepository.Kind) = executeCodingOnWorker(commit.reservation().submission()) { repos, _ ->
        recordCodingCommitInWorker(repos, commit, kind)
    }

    private fun recordCodingCommitInWorker(repos: Repositories,
        commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit, kind: CodingJobRepository.Kind) {
        val reservation = commit.reservation()
        val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
        reservation.requireJournalOwner(journal)
        commit.requireMintedBy(journal)
        val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
        anchor.fenced {
            check(anchor.accepts(reservation.lifecycle(), repos.database)) { "Stale coding technical reservation" }
            reservation.requireCurrentProjection()
            runBlocking { CodingJobRepository(repos.database).record(commit, kind) }
        }
    }

    /** Optional semantic roles; technical terminal commit is a separate, already-completed operation. */
    fun linkCodingJobSemanticSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit) =
        executeCodingOnWorker(commit.reservation().submission()) { repos, _ -> linkCodingJobSemanticInWorker(repos, commit) }

    /** Optional semantic phase of live repair; the original runtime ticket is never reconstructed. */
    fun linkCodingJobSemanticRepairSync(commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit,
        pin: com.jarvys.agent.coding.CodingJobOwnerLease.Pin) {
        val submission = commit.reservation().submission()
        val operation = submission.beginRepairOperation(pin)
        executeTrackedOnWorker(operation, null) { repos ->
            submission.requireRepairReady()
            linkCodingJobSemanticInWorker(repos, commit)
        }
    }

    private fun linkCodingJobSemanticInWorker(repos: Repositories,
        commit: com.jarvys.agent.coding.CodingJobJournalStore.Commit) {
            val reservation = commit.reservation()
            val journal = com.jarvys.agent.coding.CodingJobJournalStore.forFilesRoot(applicationContext.filesDir)
            reservation.requireJournalOwner(journal); commit.requireMintedBy(journal)
            check(commit.record().terminal != null) { "Semantic Coding roles require terminal facts" }
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            fun current() {
                check(anchor.accepts(reservation.lifecycle(), repos.database)) { "Stale Coding semantic destination" }
                reservation.requireCurrentProjection()
            }
            try {
                MemoryWritePolicy.forContext(applicationContext).gate().commit(reservation.submission().semanticTicket(), { true }, Callable {
                    anchor.fenced { current(); runBlocking { repos.database.withChatWriteTransaction {
                        CodingJobSemanticRepository(repos.database).linkTerminal(commit)
                    } } }
                })
            } catch (_: MemoryWriteGate.RejectedException) {
                // This is technical eligibility metadata, not an automatic semantic write.
                anchor.fenced { current(); runBlocking { repos.database.withChatWriteTransaction {
                    CodingJobSemanticRepository(repos.database).suppress(commit.record().binding.jobId)
                } } }
            }
        }

    fun personalAgentCodingJobSourceSync(entryId: String, jobId: String, maxChars: Int): JSONObject =
        execute { repos -> repos.database.withChatReadTransaction {
            CodingJobSemanticRepository(repos.database).source(entryId, jobId, maxChars)
        } }

    /** Capture exact Central-call provenance and the SAME database lifecycle; release before returning. */
    fun captureCentralInvocationSync(conversationId: String, ownerMessageId: String, batchId: String,
                                    callId: String, toolName: String, runId: String, processId: String): CentralInvocationCheckpoint =
        executeOnWorker { repos ->
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            anchor.fenced {
                val source = runBlocking {
                    val generation = ConnectionProjectionRepository(repos.database).generation()
                    captureCentralInvocationSource(repos.database, generation, conversationId,
                        ownerMessageId, batchId, callId, toolName, runId, processId)
                }
                CentralInvocationCheckpoint(source, anchor.attest(repos.database, source.storageGeneration))
            }
        }

    /** Read real durable identity, release the Room lease/fence, then let the caller bind purely. */
    fun connectionProjectionEvidenceSync(): ConnectionStateCoordinator.DestinationEvidence = executeOnWorker { repos ->
        val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
        anchor.fenced {
            try {
                val generation = runBlocking { ConnectionProjectionRepository(repos.database).generation() }
                anchor.attest(repos.database, generation)
            } catch (failure: Throwable) {
                anchor.invalidate(repos.database)
                throw failure
            }
        }
    }

    /** ACK is returned only after the exact immutable batch transaction commits. No Prefs callback. */
    fun projectConnectionStateSync(batch: ConnectionStateCoordinator.ProjectionBatch): ConnectionStateCoordinator.Ack =
        executeOnWorker { repos ->
            val anchor = JarvysChatDatabaseProvider.connectionProjectionAnchor(repos.database)
            anchor.fenced {
                check(anchor.accepts(batch.binding.evidence(), repos.database)) { "Stale connection projection binding" }
                try {
                    runBlocking { ConnectionProjectionRepository(repos.database).project(batch) }
                } catch (failure: Throwable) {
                    // Outcome may be uncertain. Preserve the source outbox and require fresh evidence.
                    anchor.invalidate(repos.database)
                    throw failure
                }
            }
        }

    private fun <T> execute(block: suspend (Repositories) -> T): T =
        executeOnWorker { repos -> runBlocking { block(repos) } }

    /** Gate ownership stays on this callable's thread across the structured Room transaction. */
    private fun <T> executeAutomatic(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        block: suspend (Repositories) -> T): T = executeOnWorker { repos ->
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, {
            token.throwIfCancelled()
            true
        }, Callable { runBlocking { block(repos) } })
    }

    private fun <T> executeBoundMemoryMutation(ticket:MemoryWriteGate.Ticket,token:CancellationToken,
        caller:String?,project:String?,byId:Boolean,action:String,witness:com.jarvys.agent.CentralInvocationWitness?,reviewTargetId:String?=null,
        block:suspend (Repositories,MemoryNamespace)->T):T {
        if(witness==null && action=="user_profile_set") return executeAutomatic(ticket,token) { block(it,MemoryNamespace.central()) }
        if(witness==null) return executeAutomaticNamespace(ticket,token,caller,project,null,byId,block)
        return executeOnWorker { repos ->
            witness.requireInvocationCurrent()
            val namespace=MemoryNamespace.resolve(applicationContext,caller,project,null,false,byId,byId)
            check(caller==witness.source().ownerCentralSessionId) { "memory_save_source_invalid" }
            MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket,{token.throwIfCancelled();witness.requireInvocationCurrent();true},Callable {
                runBlocking { repos.database.withChatWriteTransaction {
                    token.throwIfCancelled()
                    MemorySessionRepository(repos.database).requireInvocationForWrite(action,witness,reviewTargetId,namespace.project)
                    block(repos,namespace).also { token.throwIfCancelled(); witness.requireInvocationCurrent() }
                } }
            })
        }
    }

    /** Registry resolution is worker-local and finishes before taking the memory gate or Room transaction. */
    private fun <T> executeAutomaticNamespace(ticket: MemoryWriteGate.Ticket, token: CancellationToken,
        caller: String?, project: String?, scope: String?, byId: Boolean,
        block: suspend (Repositories, MemoryNamespace) -> T): T = executeOnWorker { repos ->
        token.throwIfCancelled()
        val namespace = MemoryNamespace.resolve(applicationContext, caller, project, scope, false, byId, byId)
        MemoryWritePolicy.forContext(applicationContext).gate().commit(ticket, {
            token.throwIfCancelled()
            true
        }, Callable { runBlocking { block(repos, namespace) } })
    }

    private fun <T> executeOnWorker(block: (Repositories) -> T): T = executeTrackedOnWorker(null, null, block)

    private fun <T> executeCodingOnWorker(submission: com.jarvys.agent.coding.CodingJobSubmission,
        acceptance: Boolean = false,
        block: (Repositories, com.jarvys.agent.coding.CodingJobSubmission.RoomOperation) -> T): T {
        val operation = when {
            acceptance -> submission.beginAcceptanceOperation()
            else -> submission.beginRoomOperation()
        }
        return executeTrackedOnWorker(operation, { submission.abandonDispatch() }) { repos -> block(repos, operation) }
    }

    private fun <T> executeCancellationOnWorker(request: com.jarvys.agent.coding.CodingCancellation.Request,
        block: (Repositories, com.jarvys.agent.coding.CodingCancellation.Request.RoomOperation) -> T): T {
        val operation = request.beginOperation()
        return executeOwnedOnWorker(operation::fail, operation::close, request::abandonDispatch) { repos -> block(repos, operation) }
    }

    private fun <T> executeTrackedOnWorker(operation: com.jarvys.agent.coding.CodingJobSubmission.RoomOperation?,
        interruptedObserver: (() -> Unit)?, block: (Repositories) -> T): T =
        executeOwnedOnWorker(operation?.let { it::fail }, operation?.let { it::close }, interruptedObserver, block)

    private fun <T> executeOwnedOnWorker(onFailure: ((Throwable) -> Unit)?, onFinished: (() -> Unit)?,
        interruptedObserver: (() -> Unit)?, block: (Repositories) -> T): T {
        val ownership = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = try {
            ChatSqlBeforeWriteGuard.checkRepositoryAccess()
            check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null) {
                "ChatDatabaseBridge cannot be called inside an active chat database lease"
            }
            check(Thread.currentThread() !== executorThread) { "ChatDatabaseBridge cannot be re-entered from its own executor thread" }
            check(mainThreadGuardDisabledForTests || unitTestMainThreadGuardDisabled() || Looper.myLooper() != Looper.getMainLooper()) {
                "ChatDatabaseBridge blocking methods must not be called from the main thread"
            }
            synchronized(lifecycleLock) {
                check(!closingForTests) { "ChatDatabaseBridge test instance is closed" }
                executor.submit(Callable {
                    check(ownership.compareAndSet(0, 1)) { "Bridge operation was rejected before worker claim" }
                    try { block(repositories()) }
                    catch (failure: Throwable) { onFailure?.invoke(failure); throw failure }
                    finally { onFinished?.invoke() } // Block has released all Room leases/anchor fences.
                })
            }
        } catch (failure: Throwable) {
            if (ownership.compareAndSet(0, 2)) {
                try { onFailure?.invoke(failure) } finally { onFinished?.invoke() }
            }
            throw failure
        }
        return try { pending.get() }
        catch (interrupted: InterruptedException) {
            interruptedObserver?.invoke() // Never cancel the process-owned callable or release its pin.
            Thread.currentThread().interrupt()
            throw ChatDatabaseWaitInterruptedException(interrupted)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
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
    ): ChatDbResult<Conversation> = execute {
        it.conversations.updateMetadata(conversationId, title, generatedTitle, pinned, archived)
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
    ): ChatDbResult<InterruptRequestValue> = execute {
        it.conversations.appendInterruptRequest(conversationId, content, skills)
    }

    fun dispatchInterruptRequestSync(conversationId: String, requestId: String): ChatDbResult<DispatchedInterrupt> =
        execute { it.conversations.dispatchInterruptRequest(conversationId, requestId) }

    fun appendAndDispatchInterruptRequestSync(
        conversationId: String,
        content: String,
        skills: List<String>,
    ): ChatDbResult<DispatchedInterrupt> = execute {
        it.modelTurnWriter.appendAndDispatchInterruptRequest(conversationId, content, skills)
    }

    @JvmOverloads
    fun pendingInterruptRequestsSync(conversationId: String? = null): List<InterruptRequestValue> =
        execute { it.conversations.pendingInterruptRequests(conversationId) }

    fun markInputStateSync(conversationId: String, messageIds: List<String>, state: String): ChatDbResult<List<String>> =
        executeMissionWrite { repos,authorization -> repos.conversations.markInputStateForMission(conversationId, messageIds, state,authorization) }

    internal fun bindMissionEpisodeInputSync(input: MissionEpisodeInput) = executeMissionWrite { repos,authorization ->
        MissionEpisodeRepository(repos.database).bind(input,authorization)
    }

    /** Publish the wake only after the receipt and episode transaction commits. */
    private fun <T> executeMissionWrite(block:suspend(Repositories,MissionReceiptAuthorization)->T):T {
        val policy=MemoryWritePolicy.forContext(applicationContext)
        // Capture before submission: an OFF/ON change while queued never upgrades this source operation.
        val ticket=policy.captureTicket()
        val (result,materialized)=executeOnWorker { repositories ->
            // Known absent workspace membership is source-only; malformed registry/SQL still throws.
            val ownership=if(ticket.enabledAtCapture()) MissionReceiptAuthorization.currentOwnership(applicationContext) else null
            var entered=false
            fun source(authorization:MissionReceiptAuthorization)=runBlocking {
                val missions=MissionEpisodeRepository(repositories.database)
                val before=missions.pendingIds()
                val value=block(repositories,authorization)
                value to (missions.pendingIds()-before).isNotEmpty()
            }
            try {
                policy.gate().commit(ticket,{true},Callable {
                    entered=true
                    source(requireNotNull(ownership))
                })
            } catch(rejected:MemoryWriteGate.RejectedException) {
                if(entered) throw rejected
                // Only pre-entry denial: no source operation or ambiguous ACK is ever replayed here.
                source(MissionReceiptAuthorization.policyDenied())
            }
        }
        if(materialized) wakeMissionNarration()
        return result
    }

    private fun wakeMissionNarration() {
        // A failure never rolls back a successful source write. Startup replays the durable receipt.
        runCatching { com.jarvys.agent.PersonalMemoryExtractionWorker.enqueueNarration(
            applicationContext, com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION) }
            .onFailure { AgentErrorReporter.reportBackgroundFailure(applicationContext, it) }
    }

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

    fun readInputsAfterSync(conversationId: String, afterSeq: Long): List<StoredMessage> =
        execute { it.conversations.readInputsAfter(conversationId, afterSeq) }

    fun inputStateMessageIdsSync(conversationId: String, state: String?, ids: List<String>?): Set<String> =
        execute { it.conversations.inputStateMessageIds(conversationId, state, ids) }

    fun markIncorporatedThroughSync(conversationId: String, messageId: String): ChatDbResult<List<String>> =
        execute { it.conversations.markIncorporatedThrough(conversationId, messageId) }

    fun latestUserMessageIndexSync(conversationId: String): Long =
        execute { it.conversations.latestUserMessageIndex(conversationId) }

    fun readMessageAtIndexSync(conversationId: String, index: Long): StoredMessage? =
        execute { it.conversations.getMessageAtIndex(conversationId, index) }

    fun readModelArtifactRowsSync(conversationId: String): List<JSONObject> =
        execute { it.transcript.readArtifactRows(conversationId) }

    fun readModelArtifactRowsSync(conversationId: String, artifactIds: List<String>): List<JSONObject> =
        execute { it.transcript.readArtifactRows(conversationId, artifactIds) }

    fun readToolResultSourceSync(conversationId: String, callId: String): JSONObject? =
        execute { it.transcript.readToolResultSource(conversationId, callId) }

    // Artifact retention (SINGLE_SESSION 7.10): states, the protected closure of episode evidence, storage pressure.
    fun nextExpellableArtifactSync(conversationId: String): ExpellableArtifact? =
        execute { it.retention.nextExpellable(conversationId) }

    fun expelArtifactSync(conversationId: String, artifactId: String, reason: String): Boolean =
        execute { it.retention.expel(conversationId, artifactId, reason) }

    fun protectedArtifactIdsSync(conversationId: String): Set<String> =
        execute { it.retention.protectedArtifactIds(conversationId) }

    fun setStorageWarningSync(active: Boolean) = execute { it.retention.setStorageWarning(active) }

    fun retentionSummarySync(conversationId: String): RetentionSummary =
        execute { it.retention.summary(conversationId) }

    // Layer 3 of the memory model: the stored messages, searchable and readable exactly.
    fun historySearchSync(
        conversationId: String, query: String, role: String?, fromMs: Long?, toMsExclusive: Long?,
        cursor: String?, limit: Int, excerptChars: Int,
    ): HistoryPage = execute { it.history.search(conversationId, query, role, fromMs, toMsExclusive, cursor, limit, excerptChars) }

    fun historyReadSync(conversationId: String, messageId: String, offset: Int, limitChars: Int): HistoryMessage? =
        execute { it.history.read(conversationId, messageId, offset, limitChars) }

    fun readPendingAgentInputsSync(conversationId: String): List<StoredMessage> =
        execute { it.reader.readPendingAgentInputs(conversationId) }

    fun readPendingInternalMessagesByStableIdsSync(
        conversationId: String,
        stableIds: List<String>,
    ): List<StoredMessage> = execute {
        it.conversations.getPendingInternalMessagesByStableIds(conversationId, stableIds)
    }

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

    /** Rows of one proactive or task thread through the thread index, never the whole conversation. */
    fun readThreadMessagesSync(conversationId: String, threadKey: String): List<StoredMessage> =
        execute { it.conversations.readThreadMessages(conversationId, threadKey) }

    /** The compacted notice a thread reply answers plus its exchange, or null while the notice is still live. */
    fun readCompactedProactiveReferentSync(conversationId: String, replyMessageId: String): ProactiveReferent? =
        execute { it.reader.readCompactedProactiveReferent(conversationId, replyMessageId) }

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

    fun readInteractionProtectionSync(conversationId: String): InteractionProtectionSnapshot =
        execute { it.reader.readInteractionProtection(conversationId) }

    fun validateProtectedContextSync(conversationId: String, turns: List<ConversationTurn>) =
        execute { it.reader.validateProtectedContext(conversationId, turns) }

    fun loadConversationContextSync(conversationId: String): List<ConversationTurn> = executeOnWorker { repos ->
        // Only optional recovery enrichment degrades; Room, lifecycle and clean-install failures still propagate.
        val namespace = if (conversationId == MemoryNamespace.CENTRAL) try {
            MemoryNamespace.resolve(applicationContext, conversationId, null, null, false, true, true)
        } catch (_: com.jarvys.agent.workspaces.WorkspaceRegistryCorruptException) { null
        } catch (_: android.database.sqlite.SQLiteException) { null
        } catch (failure: IllegalArgumentException) {
            if (failure.message == "memory_owner_denied") null else throw failure
        } else null
        runBlocking { repos.reader.loadConversationContext(conversationId, namespace) }
    }

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
        executeMissionWrite { repos,authorization -> repos.modelTurnWriter.appendForMission(input,authorization) }

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
        state: String = ModelArtifactState.RETAINED,
        stateReason: String? = null,
    ): ChatDbResult<ModelArtifact> = execute {
        it.transcript.appendModelArtifact(conversationId, artifactId, relativePath, sha256, chars, bytes, mimeType, state, stateReason)
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

    fun appendDeliveredArtifactWithReceiptSync(input: DeliveredArtifactInput): ChatDbResult<DeliveredArtifactCommit> =
        execute { it.artifacts.appendDeliveredArtifactWithReceipt(input) }

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

    fun unreferencedFileRefsSync(): List<FileRef> = execute { it.reader.unreferencedFileRefs() }

    // Compaction, reflection, and crew snapshot writes.
    fun prepareCompactionCoverageSync(conversationId: String, sources: List<ConversationTurn.Source>): CompactionCoverage? =
        execute { CompactionMemoryRepository(it.database).prepare(conversationId, sources) }

    @JvmOverloads
    fun appendCompactionPreparedSync(input:CompactionInput,coverage:CompactionCoverage?,prepared:PreparedCompactionFlush?,sessionKind:String,
        protection:InteractionProtectionSnapshot?=null,attempt:CompactionMemoryAttempt?=null):ChatDbResult<Compaction> = executeOnWorker { repos ->
        fun structural()=runBlocking { repos.memory.appendCompactionPrepared(input,coverage,null,sessionKind,protection,attempt,false,compactionClock) }
        if(attempt==null) structural()
        else {
            try {
                MemoryNamespace.resolve(applicationContext,MemoryNamespace.CENTRAL,null,null,false,true,true)
                MemoryWritePolicy.forContext(applicationContext).gate().commit(attempt.memoryTicket,{attempt.binding.check(attempt.token);true},Callable {
                    runBlocking { repos.memory.appendCompactionPrepared(input,coverage,prepared,sessionKind,protection,attempt,true,compactionClock) }
                })
            } catch(unavailable:MemorySessionUnavailableException) { structural() }
              catch(rejected:MemoryWriteGate.RejectedException) { structural() }
        }
    }

    fun readCompactionFlushStatusSync(conversationId: String, compactionId: String): String? =
        execute { CompactionMemoryRepository(it.database).status(conversationId, compactionId) }

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
    private class MissionEvidenceAbort(val result:ChatDbResult<CrewResultJournalEvidence>):RuntimeException()
    @JvmOverloads
    fun appendCrewResultEvidenceSync(evidence:CrewResultJournalEvidence,beforeWrite:BeforeChatWrite?=null):ChatDbResult<CrewResultJournalEvidence> =
        executeMissionWrite { repositories,authorization ->
            try {
                repositories.database.withChatWriteTransaction {
                    var inserted=false
                    val result=repositories.strictJournals.appendCrewResultEvidence(evidence) { beforeWrite?.check();inserted=true }
                    // A normalized nested Conflict must escape the outer TX before being returned.
                    if(result !is ChatDbResult.Success) throw MissionEvidenceAbort(result)
                    MissionEpisodeRepository(repositories.database).reconcileEvidence(result.value,authorization,inserted)
                    result
                }
            } catch(aborted:MissionEvidenceAbort) { aborted.result }
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

    fun appendWorkspaceInputIncorporationIfAbsentSync(
        value: WorkspaceInputIncorporationJournal,
        beforeWrite: BeforeChatWrite? = null,
    ): ChatDbResult<Boolean> = execute {
        it.strictJournals.appendWorkspaceInputIncorporationAndReport(value) { beforeWrite?.check() }
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

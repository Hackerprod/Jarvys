package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import android.os.Looper
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.RoomDatabase.JournalMode
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import java.util.ArrayDeque
import kotlin.coroutines.ContinuationInterceptor

/**
 * Normalized Room store for the conversation ledger (M0 schema version). Room entities do not emit
 * SQL CHECK constraints; triggers install the invariants SQLite can enforce. Remaining payload,
 * enum, bounds, optional sequence-marker, and first-wins checks are M1 repository responsibilities.
 * Do not call [clearAllTables]: permanent tombstones are immutable and intentionally reject deletion.
 */
@Database(
    entities = [
        Conversation::class,
        Tombstone::class,
        ConversationItem::class,
        Message::class,
        MessagePart::class,
        MessageAttachment::class,
        MessageTranslation::class,
        MessageReaction::class,
        ProactiveSuggestedReply::class,
        UserDecision::class,
        UserDecisionOption::class,
        InterruptRequest::class,
        InterruptRequestSkill::class,
        ToolBatch::class,
        ToolCall::class,
        ToolResult::class,
        ToolActivity::class,
        ToolPresentation::class,
        ReflectionToolMarker::class,
        ModelError::class,
        ModelProviderOutput::class,
        ModelArtifact::class,
        ConversationArtifact::class,
        GeneratedImage::class,
        Compaction::class,
        CompactionReflectionAck::class,
        Reflection::class,
        ReflectionRevision::class,
        CrewMission::class,
        CrewBot::class,
        CrewBotTool::class,
        CrewMessage::class,
        CrewMessageActivity::class,
        CrewMessageInputBinding::class,
        CrewMessageRef::class,
        CrewResultEvidence::class,
        WorkspaceResultRef::class,
        CrewCoordinationEvidence::class,
        CrewCoordinationEvidenceRef::class,
        WorkspaceCoordinationRef::class,
        WorkspaceCoordinationReply::class,
        WorkspaceCoordinationReplyTransition::class,
        WorkspaceInputIncorporation::class,
        FileRef::class,
        GenerationRun::class,
        StorageMeta::class,
        AgentProfile::class,
        AgentDoc::class,
        UserDirective::class,
        MemoryEntry::class,
        MemoryProjectAliasEntity::class,
        MemoryReviewClearEventEntity::class,
        MemoryRelationEntity::class,
        MemorySession::class,
        MemoryPrompt::class,
        MemoryPromptTombstone::class,
        MemorySessionTombstone::class,
        MemorySessionFinalization::class,
        MemoryDeletedObservation::class,
        MemorySaveOperation::class,
        MemoryEvidence::class,
        MissionEpisodeInput::class,
        CompactionCoverageSource::class,
        CompactionFlushBinding::class,
        MemoryLink::class,
        TranscriptArtifactRef::class,
        MemoryRevision::class,
        AgentSessionState::class,
        MemoryJob::class,
        ConnectorAutonomyAudit::class,
        ConnectorStateEvent::class,
        CodingJobBindingRow::class,
        CodingJobObservationRow::class,
        CodingJobSemanticLinkRow::class,
        CodingCancelRequestRow::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class JarvysChatDatabase : RoomDatabase() {
    internal fun expectedFreshIdentity(): String =
        (createOpenDelegate() as androidx.room.RoomOpenDelegate).identityHash

    internal abstract fun conversationDao(): ConversationDao
    internal abstract fun conversationItemDao(): ConversationItemDao
    internal abstract fun messageDao(): MessageDao
    companion object {
        /** Entity table names used by M0 schema inspection; keep aligned with [Database.entities]. */
        @JvmField
        val ENTITY_TABLE_NAMES: Set<String> = setOf(
            "conversations",
            "tombstones",
            "conversation_items",
            "messages",
            "message_parts",
            "message_attachments",
            "message_translations",
            "message_reactions",
            "proactive_suggested_replies",
            "user_decisions",
            "user_decision_options",
            "interrupt_requests",
            "interrupt_request_skills",
            "tool_batches",
            "tool_calls",
            "tool_results",
            "tool_activities",
            "tool_presentations",
            "reflection_tool_markers",
            "model_errors",
            "model_provider_outputs",
            "model_artifacts",
            "artifacts",
            "generated_images",
            "compactions",
            "compaction_reflection_acks",
            "reflections",
            "reflection_revisions",
            "crew_missions",
            "crew_bots",
            "crew_bot_tools",
            "crew_messages",
            "crew_message_activities",
            "crew_message_input_bindings",
            "crew_message_refs",
            "crew_result_evidence",
            "workspace_result_refs",
            "crew_coordination_evidence",
            "crew_coordination_evidence_refs",
            "workspace_coordination_refs",
            "workspace_coordination_replies",
            "workspace_coordination_reply_transitions",
            "workspace_input_incorporations",
            "file_refs",
            "generation_run",
            "storage_meta",
            "agent_profiles",
            "agent_docs",
            "user_directives",
            "memory_entries",
            "memory_project_aliases",
            "memory_review_clear_events",
            "memory_relations",
            "memory_sessions", "memory_prompts", "memory_prompt_tombstones", "memory_session_tombstones", "memory_session_finalizations",
            "memory_deleted_observations",
            "memory_save_operations",
            "memory_evidence",
            "mission_episode_inputs",
            "compaction_coverage_sources",
            "compaction_flush",
            "memory_links",
            "transcript_artifact_refs",
            "memory_revisions",
            "agent_session_state",
            "memory_jobs",
            "connector_autonomy_audit",
            "connector_state_events",
            "coding_job_bindings",
            "coding_job_observations",
            "coding_job_semantic_links",
            "coding_cancel_requests",
        )
    }
}

/** One lazy app-process database instance per current-generation agent.db path. */
internal object JarvysChatDatabaseProvider {
    private const val DATABASE_VERSION = 1
    private const val DATABASE_NAME = "agent.db"
    private const val BUSY_TIMEOUT_MILLIS = 5_000
    private const val WAL_AUTOCHECKPOINT_PAGES = 1_000
    private const val JOURNAL_SIZE_LIMIT_BYTES = 16_777_216

    private val instances = mutableMapOf<String, JarvysChatDatabase>()
    private val projectionAnchors = mutableMapOf<String, ConnectionProjectionAnchor>()

    private fun projectionAnchor(file: File): ConnectionProjectionAnchor {
        val key = file.canonicalPath
        return synchronized(projectionAnchors) {
            projectionAnchors.getOrPut(key) { ConnectionProjectionAnchor() }
        }
    }

    internal fun connectionProjectionAnchor(context: Context): ConnectionProjectionAnchor {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Connection destination identity must be obtained off the main thread"
        }
        return projectionAnchor(File(com.jarvys.agent.StorageGeneration(context).chatRoot(), DATABASE_NAME))
    }

    internal fun connectionProjectionAnchor(database: JarvysChatDatabase): ConnectionProjectionAnchor =
        projectionAnchor(databaseFileForRawQuery(database))

    private val databaseExecutors = IdentityHashMap<JarvysChatDatabase, List<ExecutorService>>()
    private val queryDispatchers = IdentityHashMap<JarvysChatDatabase, ExecutorCoroutineDispatcher>()
    private val writerDispatchers = IdentityHashMap<JarvysChatDatabase, ExecutorCoroutineDispatcher>()
    private val databaseFiles = IdentityHashMap<JarvysChatDatabase, File>()
    private val applicationFiles = IdentityHashMap<JarvysChatDatabase, File>()

    internal fun filesDirectory(database: JarvysChatDatabase): File =
        synchronized(applicationFiles) { requireNotNull(applicationFiles[database]) }
    private val readPools = IdentityHashMap<JarvysChatDatabase, ReadDatabasePool>()

    /** Call off the main thread. Room opens on first use; version preflight is dispatched to the read pool. */
    @JvmStatic
    fun getInstance(context: Context): JarvysChatDatabase {
        check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null) {
            "Database lifecycle cannot be entered from an active database lease"
        }
        com.jarvys.agent.FreshStorageBootstrap.requireFreshInstall(context.applicationContext)
        com.jarvys.agent.FreshStorageBootstrap.awaitReadyBlocking(context.applicationContext.filesDir)
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "JarvysChatDatabaseProvider.getInstance must be called off the main thread"
        }
        val databaseFile = File(com.jarvys.agent.StorageGeneration(context).chatRoot(), DATABASE_NAME)
        val key = databaseFile.canonicalPath
        val anchor = projectionAnchor(databaseFile)
        return anchor.fenced { synchronized(this) {
            instances[key] ?: run {
            anchor.opening()
            databaseFile.parentFile?.mkdirs()
            check(databaseFile.parentFile?.isDirectory == true) {
                "Could not create Jarvys chat database directory: ${databaseFile.parentFile}"
            }
            val executorSet = createExecutors()
            var openedDatabase: JarvysChatDatabase? = null
            var readPool: ReadDatabasePool? = null
            val opened = try {
                buildDatabase(context.applicationContext, databaseFile, executorSet)
                    .also {
                        openedDatabase = it
                        kotlinx.coroutines.runBlocking { it.useWriterConnection { } }
                        readPool = ReadDatabasePool(databaseFile)
                    }
            } catch (error: Throwable) {
                runCatching { readPool?.close() }
                runCatching { openedDatabase?.close() }
                executorSet.forEach(ExecutorService::shutdown)
                throw error
            }
            opened.also {
                synchronized(applicationFiles) { applicationFiles[it] = context.applicationContext.filesDir }
                synchronized(databaseExecutors) { databaseExecutors[it] = executorSet }
                synchronized(databaseFiles) { databaseFiles[it] = databaseFile }
                synchronized(queryDispatchers) { queryDispatchers[it] = executorSet[0].asCoroutineDispatcher() }
                synchronized(writerDispatchers) { writerDispatchers[it] = executorSet[1].asCoroutineDispatcher() }
                synchronized(readPools) { readPools[it] = checkNotNull(readPool) }
                anchor.opened(it)
                instances[key] = it
            }
            }
        } }
    }

    /**
     * Opens an isolated file for schema tests while applying the production future-version gate and
     * connection PRAGMAs. It is internal so production callers use only the process singleton.
     */
    internal fun openDatabaseFileForTests(context: Context, databaseFile: File): JarvysChatDatabase {
        val resources = ExplicitFileResources(context, databaseFile, reportFailures = true)
        return try { resources.open() } catch (failure: Throwable) {
            runCatching { resources.close(awaitTermination = false) }
            throw failure
        }
    }

    /** Only a selftest-owned path/context reaches this handle; it never joins the user singleton. */
    internal class ScratchDatabase internal constructor(context: Context, file: File) {
        private val resources = ExplicitFileResources(context, file, reportFailures = false)
        fun open(): JarvysChatDatabase = resources.open()
        /** Must be called after the operation's workers have physically returned, including setup failure. */
        fun closeAndAwait() = resources.close(awaitTermination = true)
    }

    /** Shared explicit-file implementation, also used by schema tests; no alternate Room engine. */
    private class ExplicitFileResources(val context: Context, val file: File, val reportFailures: Boolean) {
        private var executors: List<ExecutorService> = emptyList()
        private var database: JarvysChatDatabase? = null
        private var pool: ReadDatabasePool? = null
        private var registered = false
        private var opened = false
        private var partialCloseUnconfirmed = false
        fun open(): JarvysChatDatabase = projectionAnchor(file).fenced {
            check(!opened) { "scratch_database_already_opened" }
            opened = true
            projectionAnchor(file).opening()
            file.parentFile?.mkdirs()
            executors = createExecutors()
            val db = buildDatabase(context.applicationContext, file, executors, reportFailures)
            database = db // Retain each partial resource before the next potentially failing step.
            kotlinx.coroutines.runBlocking { db.useWriterConnection { } }
            pool = ReadDatabasePool(file, if (!reportFailures) ({ partialCloseUnconfirmed = true }) else null)
            synchronized(databaseExecutors) { databaseExecutors[db] = executors }
            synchronized(queryDispatchers) { queryDispatchers[db] = executors[0].asCoroutineDispatcher() }
            synchronized(writerDispatchers) { writerDispatchers[db] = executors[1].asCoroutineDispatcher() }
            synchronized(applicationFiles) { applicationFiles[db] = context.applicationContext.filesDir }
            synchronized(databaseFiles) { databaseFiles[db] = file }
            synchronized(readPools) { readPools[db] = checkNotNull(pool) }
            registered = true
            projectionAnchor(file).opened(db)
            db
        }
        fun close(awaitTermination: Boolean) {
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) { try { block() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            } }
            val db = database
            if (registered && db != null) attempt { closeDatabaseOwned(db, strictClose = awaitTermination) }
            else {
                attempt { pool?.close(strict = awaitTermination) }
                attempt { db?.close() }
            }
            executors.forEach { executor -> attempt { executor.shutdown() } }
            // A cancelled caller must not abandon cleanup or mistake shutdown-requested for terminated.
            var interrupted = Thread.interrupted()
            if (awaitTermination) executors.forEach { executor ->
                while (!executor.isTerminated) {
                    try { executor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (awaitTermination && partialCloseUnconfirmed) attempt { error("scratch_partial_close_unconfirmed") }
            if (awaitTermination && failure == null) {
                val anchor = projectionAnchor(file)
                anchor.fenced { synchronized(projectionAnchors) {
                    if (projectionAnchors[file.canonicalPath] === anchor) projectionAnchors.remove(file.canonicalPath)
                } }
            }
            failure?.let { throw it }
        }
    }

    /** Closes provider-owned handles and executors together with their Room database. */
    internal fun closeDatabase(database: JarvysChatDatabase) = closeDatabaseOwned(database, strictClose = false)

    private fun closeDatabaseOwned(database: JarvysChatDatabase, strictClose: Boolean) {
        val file = synchronized(databaseFiles) { databaseFiles[database] } ?: return
        val anchor = projectionAnchor(file)
        anchor.fenced {
            anchor.invalidate(database)
            try { closeDatabaseUnderFence(database, strictClose) } finally { anchor.closed(database) }
        }
    }

    private fun closeDatabaseUnderFence(database: JarvysChatDatabase, strictClose: Boolean) {
        val readPool = synchronized(readPools) { readPools[database] }
        try {
            // Owners must drain active Room transactions before shutdown; Room does not await them.
            // Wake blocked raw acquisitions; borrowed raw handles close when returned.
            readPool?.close(strict = strictClose)
        } finally {
            synchronized(readPools) {
                if (readPools[database] === readPool) readPools.remove(database)
            }
            synchronized(applicationFiles) { applicationFiles.remove(database) }
            val file = synchronized(databaseFiles) { databaseFiles.remove(database) }
            try {
                database.close()
            } finally {
                val executors = synchronized(databaseExecutors) { databaseExecutors.remove(database) }.orEmpty()
                synchronized(queryDispatchers) { queryDispatchers.remove(database) }?.close()
                synchronized(writerDispatchers) { writerDispatchers.remove(database) }?.close()
                executors.forEach(ExecutorService::shutdown)
                if (file != null) synchronized(this) {
                    val key = file.canonicalPath
                    if (instances[key] === database) instances.remove(key)
                }
            }
        }
    }

    /** Closes every database opened by this process when the owning application is shutting down. */
    internal fun closeInstance() {
        // Never wait for a file fence while holding the provider monitor (open uses fence -> monitor).
        val opened = synchronized(this) { instances.values.toList() }
        opened.forEach(::closeDatabase)
    }

    /** Closes an isolated database opened for tests, with a bounded and diagnostic Room close. */
    internal fun closeDatabaseFileForTests(database: JarvysChatDatabase) {
        val finished = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val closer = Thread({
            try {
                closeDatabase(database)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }, "jarvys-chat-test-database-close").apply { isDaemon = true }
        closer.start()

        if (!finished.await(TEST_DATABASE_CLOSE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            closer.interrupt()
            throw AssertionError(
                "Timed out after ${TEST_DATABASE_CLOSE_TIMEOUT_SECONDS}s closing the chat database. " +
                    "Closer interrupted; all-thread dump follows:\n${dumpAllThreadStacks()}",
            )
        }
        failure.get()?.let { error ->
            when (error) {
                is Error -> throw error
                is RuntimeException -> throw error
                else -> throw IllegalStateException("Could not close the chat database in the test helper", error)
            }
        }
    }

    private fun dumpAllThreadStacks(): String = buildString {
        Thread.getAllStackTraces().entries
            .sortedBy { it.key.name }
            .forEach { (thread, stack) ->
                append("\n\"${thread.name}\" ")
                append("state=${thread.state}, daemon=${thread.isDaemon}, alive=${thread.isAlive}\n")
                stack.forEach { frame -> append("    at ").append(frame).append('\n') }
            }
    }

    /** Physical file for raw, read-only projection handles owned by this provider. */
    internal fun databaseFileForRawQuery(database: JarvysChatDatabase): File =
        synchronized(databaseFiles) { databaseFiles[database] }
            ?: throw IllegalStateException("No database file is registered for the chat database")

    /** Validate the already-open pool against its recorded main-file identity, without opening/adopting. */
    internal fun requireOpenFileIdentity(database: JarvysChatDatabase, context: Context): () -> Unit {
        val file = synchronized(databaseFiles) { databaseFiles[database] }
            ?: throw IllegalStateException("memory_export_source_changed")
        val pool = synchronized(readPools) { readPools[database] }
            ?: throw IllegalStateException("memory_export_source_changed")
        val directories = com.jarvys.agent.MemoryDoctorPaths.memoryDirectories(context)
        check(file.absoluteFile == File(directories.last(), DATABASE_NAME).absoluteFile) { "memory_export_source_changed" }
        pool.requireDoctorIdentity(file)
        val identity = com.jarvys.agent.MemoryDoctorFileIdentity.capture(file, directories, "source_changed")
        val validate: () -> Unit = {
            identity.requireCurrent()
            pool.requireDoctorIdentity(file)
            check(synchronized(databaseFiles) { databaseFiles[database] == file } &&
                synchronized(readPools) { readPools[database] === pool }) { "memory_export_source_changed" }
        }
        validate()
        return validate
    }

    /** Observation only: cold providers stay cold, and retries never acquire a writer lease. */
    internal fun <T> observeOpenForDoctor(context: Context, token: com.jarvys.agent.CancellationToken,
        block: (ChatSqlDatabase, () -> Unit) -> T): T {
        check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null)
        check(Looper.myLooper() != Looper.getMainLooper()) { "doctor_worker_required" }
        token.throwIfCancelled()
        val directories = com.jarvys.agent.MemoryDoctorPaths.memoryDirectories(context)
        val file = File(directories.last(), DATABASE_NAME)
        val key = file.canonicalPath
        val database = synchronized(this) { instances[key] }
            ?: throw com.jarvys.agent.MemoryDoctorUnavailable("store_not_open")
        val anchor = synchronized(projectionAnchors) { projectionAnchors[key] }
            ?: throw com.jarvys.agent.MemoryDoctorUnavailable("store_not_open")
        val observedPool = synchronized(readPools) { readPools[database] }
            ?: throw com.jarvys.agent.MemoryDoctorUnavailable("store_not_open")
        val thread = Thread.currentThread()
        val unregister = token.registerCancelAction { thread.interrupt() }
        var locked = false
        var handle: SQLiteConnection? = null
        try {
            while (!locked) {
                token.throwIfCancelled()
                locked = anchor.fence.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            fun requireCurrent() {
                token.throwIfCancelled()
                check(anchor.fence.isHeldByCurrentThread)
                if (synchronized(this) { instances[key] !== database } ||
                    synchronized(databaseFiles) { databaseFiles[database]?.canonicalPath != key } ||
                    synchronized(readPools) { readPools[database] !== observedPool } ||
                    synchronized(projectionAnchors) { projectionAnchors[key] !== anchor })
                    throw com.jarvys.agent.MemoryDoctorUnavailable("source_changed")
                observedPool.requireDoctorIdentity(file)
            }
            requireCurrent()
            val identity = com.jarvys.agent.MemoryDoctorFileIdentity.capture(file, directories, "store_missing")
            handle = acquireReadDatabaseBlocking(database)
            token.throwIfCancelled()
            return block(DriverChatSqlDatabase(checkNotNull(handle))) { requireCurrent(); identity.requireCurrent() }
                .also { requireCurrent(); identity.requireCurrent() }
        } catch (interrupted: InterruptedException) {
            if (token.isCancellationRequested) throw java.util.concurrent.CancellationException("doctor_cancelled")
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            try { handle?.let { releaseReadDatabase(database, it) } }
            finally {
                if (locked) anchor.fence.unlock()
                unregister.run()
                if (token.isCancellationRequested) Thread.interrupted()
            }
        }
    }

    /** Runs a read projection on the query pool belonging to this exact Room database instance. */
    internal suspend fun <T> onQueryExecutor(database: JarvysChatDatabase, operation: suspend () -> T): T {
        ChatSqlBeforeWriteGuard.checkRepositoryAccess()
        val activeLease = LeasedConnectionElement.current()
        if (activeLease != null) {
            // A writer lease is thread-bound by Android SQLiteSession. Reject an inherited lease
            // on a dispatcher hop before any query path can acquire, use, or release a connection.
            activeLease.assertCanUseFromCurrentCoroutine(currentCoroutineContext()[ContinuationInterceptor])
            return operation()
        }
        if (RawReadConnectionElement.current() != null) return operation()
        return withContext(synchronized(queryDispatchers) { queryDispatchers[database] }
            ?: throw IllegalStateException("No Jarvys chat query executor is registered for this database")) {
            operation()
        }
    }

    internal suspend fun <T> onWriterExecutor(database: JarvysChatDatabase, operation: suspend () -> T): T {
        val dispatcher = synchronized(writerDispatchers) { writerDispatchers[database] }
            ?: throw IllegalStateException("No Jarvys chat writer executor is registered for this database")
        return withContext(dispatcher) { operation() }
    }

    internal suspend fun acquireReadDatabase(database: JarvysChatDatabase): SQLiteConnection {
        // runInterruptible discards its result when the job is cancelled after the blocking call already
        // returned; the borrowed handle must then go back to the pool or it is lost forever.
        val acquired = java.util.concurrent.atomic.AtomicReference<SQLiteConnection?>()
        try {
            return kotlinx.coroutines.runInterruptible {
                acquireReadDatabaseBlocking(database).also { acquired.set(it) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            acquired.getAndSet(null)?.let { releaseReadDatabase(database, it) }
            throw cancelled
        }
    }

    /** Synchronous SQL facade fallback; its monitor wait responds to thread interruption. */
    internal fun acquireReadDatabaseBlocking(database: JarvysChatDatabase): SQLiteConnection =
        synchronized(readPools) { readPools[database] }
            ?.acquireBlocking()
            ?: throw IllegalStateException("No Jarvys chat read pool is registered for this database")

    internal fun releaseReadDatabase(database: JarvysChatDatabase, handle: SQLiteConnection) {
        val pool = synchronized(readPools) { readPools[database] }
        if (pool != null) pool.release(handle) else runCatching { handle.close() }
    }

    /** Per-connection SQLite commit generation used to validate a multi-statement read projection. */
    internal fun readDataVersion(handle: SQLiteConnection): Long = DriverChatSqlDatabase(handle).query("PRAGMA data_version").use { cursor ->
        check(cursor.moveToFirst()) { "PRAGMA data_version returned no value" }
        cursor.getLong(0)
    }

    /** Startup worker preflight only: never construct Room or create a directory/database. */
    internal fun preflightFreshStorage(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Storage preflight must run off the main thread" }
        val files = context.applicationContext.filesDir.absoluteFile
        val root = File(File(files, "jarvys"), com.jarvys.agent.StorageGeneration.NAME)
        val expectedRoot = File(File(files.canonicalFile, "jarvys"), com.jarvys.agent.StorageGeneration.NAME)
        if (root.canonicalFile != expectedRoot) throw com.jarvys.agent.CleanInstallRequiredException()
        val file = File(root, DATABASE_NAME)
        rejectNewerDatabaseWithoutMutation(file)
        requireFreshIdentity(file, JarvysChatDatabase_Impl().expectedFreshIdentity())
    }

    private fun buildDatabase(
        context: Context,
        databaseFile: File,
        executors: List<ExecutorService>,
        reportFailures: Boolean = true,
    ): JarvysChatDatabase {
        runVersionPreflightOnQueryExecutor(databaseFile, executors[0])
        // Even closing an initialized Room object may open its lazy Android connection. Reject an
        // incompatible file before constructing that object; this generated delegate has no context or path.
        runIdentityPreflight(databaseFile, JarvysChatDatabase_Impl().expectedFreshIdentity(), executors[0])
        return Room.databaseBuilder(context, JarvysChatDatabase::class.java, databaseFile.absolutePath)
            // Room and the independent read-only pool use the same bundled SQLite engine/file.
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // Preserve the existing single writer executor and Room lease ordering. Independent
            // committed reads use the bounded read-only pool and never share a writer lease.
            .setQueryExecutor(executors[1])
            .setTransactionExecutor(executors[1])
            // Fresh-only schema: no imports, historical migration chain or destructive fallback.
            .addCallback(connectionPragmasCallback(context.applicationContext, reportFailures))
            .build()
    }

    /** Runs the read-only schema probe on the named query worker, never on the caller/UI thread. */
    private fun runVersionPreflightOnQueryExecutor(databaseFile: File, queryExecutor: ExecutorService) {
        try {
            queryExecutor.submit<Unit> { rejectNewerDatabaseWithoutMutation(databaseFile) }.get()
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is RuntimeException) throw cause
            throw IllegalStateException("Could not inspect chat database schema version at ${databaseFile.absolutePath}", cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while inspecting chat database schema version", error)
        }
    }

    /** Read only: Room must never see a schema newer than the one this binary understands. */
    private fun rejectNewerDatabaseWithoutMutation(databaseFile: File) {
        val members = listOf("", "-wal", "-shm", "-journal").map { File(databaseFile.path + it) }
        val present = members.map { member ->
            val stat = try {
                android.system.Os.lstat(member.absolutePath)
            } catch (error: android.system.ErrnoException) {
                if (error.errno == android.system.OsConstants.ENOENT) null else throw error
            }
            if (stat != null && !android.system.OsConstants.S_ISREG(stat.st_mode)) {
                throw com.jarvys.agent.CleanInstallRequiredException()
            }
            stat != null
        }
        if (!present[0] || databaseFile.length() == 0L) {
            if (present.drop(1).any { it }) throw com.jarvys.agent.CleanInstallRequiredException()
            return
        }
        val foundVersion = try {
            val sqlite = openReadOnlyConnection(databaseFile)
            sqlite.use { db ->
                DriverChatSqlDatabase(db).query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst()) { "Could not read SQLite user_version from ${databaseFile.name}" }
                    cursor.getInt(0)
                }
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException("Could not inspect chat database schema version at ${databaseFile.absolutePath}", error)
        }
        if (foundVersion != DATABASE_VERSION) {
            throw com.jarvys.agent.CleanInstallRequiredException()
        }
    }

    /** Require the current generated Room identity before any writer can adopt an existing file. */
    private fun runIdentityPreflight(file: File, expected: String, executor: ExecutorService) {
        try {
            executor.submit<Unit> {
                requireFreshIdentity(file, expected)
            }.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while inspecting database identity", error)
        }
    }

    private fun requireFreshIdentity(file: File, expected: String) {
        if (!file.exists() || file.length() == 0L) return
        openReadOnlyConnection(file).use { db ->
            val hasMaster = DriverChatSqlDatabase(db).query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='room_master_table'",
            ).use { it.moveToFirst() }
            if (!hasMaster) throw com.jarvys.agent.CleanInstallRequiredException()
            val identity = DriverChatSqlDatabase(db).query("SELECT identity_hash FROM room_master_table WHERE id=42")
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (identity != expected) throw com.jarvys.agent.CleanInstallRequiredException()
        }
    }

    internal fun connectionPragmasCallback(context: Context, reportFailures: Boolean = true): Callback = object : Callback() {
        private val driverTriggersInstalled = AtomicBoolean(false)
        private val driverTriggerInstallLock = Any()

        private fun createDriverTriggersOnce(connection: SQLiteConnection) {
            if (driverTriggersInstalled.get()) return
            synchronized(driverTriggerInstallLock) {
                if (driverTriggersInstalled.get()) return
                DatabaseTriggers.create(connection, context, reportFailures)
                driverTriggersInstalled.set(true)
            }
        }

        override fun onCreate(connection: SQLiteConnection) {
            super.onCreate(connection)
            // PRAGMA synchronous cannot change inside the creation transaction; onOpen applies the PRAGMAs.
            createDriverTriggersOnce(connection)
        }

        override fun onOpen(connection: SQLiteConnection) {
            super.onOpen(connection)
            createDriverTriggersOnce(connection)
            configureOpenedConnection(connection)
        }

        // Kept for the test-only SupportSQLiteOpenHelper accessor; Room itself is built with
        // BundledSQLiteDriver and calls the SQLiteConnection overloads above.
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            DatabaseTriggers.create(db, context, reportFailures)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            configureOpenedConnection(db, reportFailures)
        }
    }

    /** Shared Room/test-helper connection setup; each opened connection gets triggers and PRAGMAs. */
    internal fun configureOpenedConnection(db: SupportSQLiteDatabase, reportFailures: Boolean = true) {
        DatabaseTriggers.create(db, reportFailures = reportFailures)
        // Applies to each connection (including readers opened later), unlike a one-time PRAGMA.
        db.setForeignKeyConstraintsEnabled(true)
        setPragma(db, "foreign_keys=ON")
        setPragma(db, "busy_timeout=$BUSY_TIMEOUT_MILLIS")
        setPragma(db, "synchronous=NORMAL")
        setPragma(db, "wal_autocheckpoint=$WAL_AUTOCHECKPOINT_PAGES")
        setPragma(db, "journal_size_limit=$JOURNAL_SIZE_LIMIT_BYTES")
        verifyPragma(db, "journal_mode", "wal")
        verifyPragma(db, "foreign_keys", "1")
        verifyPragma(db, "busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
        verifyPragma(db, "synchronous", "1")
        verifyPragma(db, "wal_autocheckpoint", WAL_AUTOCHECKPOINT_PAGES.toString())
        verifyPragma(db, "journal_size_limit", JOURNAL_SIZE_LIMIT_BYTES.toString())
    }

    /** Configure the actual driver connection; foreign_keys is per SQLite connection. */
    internal fun configureOpenedConnection(connection: SQLiteConnection) {
        val sql = DriverChatSqlDatabase(connection)
        sql.execSQL("PRAGMA foreign_keys=ON")
        sql.execSQL("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS")
        sql.execSQL("PRAGMA synchronous=NORMAL")
        sql.execSQL("PRAGMA wal_autocheckpoint=$WAL_AUTOCHECKPOINT_PAGES")
        sql.execSQL("PRAGMA journal_size_limit=$JOURNAL_SIZE_LIMIT_BYTES")
        verifyPragma(connection, "journal_mode", "wal")
        verifyPragma(connection, "foreign_keys", "1")
        verifyPragma(connection, "busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
        verifyPragma(connection, "synchronous", "1")
        verifyPragma(connection, "wal_autocheckpoint", WAL_AUTOCHECKPOINT_PAGES.toString())
        verifyPragma(connection, "journal_size_limit", JOURNAL_SIZE_LIMIT_BYTES.toString())
    }

/** Invariants Room cannot encode, installed when creating the current fresh schema. */
internal object DatabaseTriggers {
    fun create(connection: SQLiteConnection, context: Context? = null, reportFailures: Boolean = true) {
        val sql = DriverChatSqlDatabase(connection)
        create(sql, context, reportFailures)
    }

    fun create(db: SupportSQLiteDatabase, context: Context? = null, reportFailures: Boolean = true) = create(SupportChatSqlDatabase(db), context, reportFailures)

    fun create(db: ChatSqlDatabase, context: Context? = null, reportFailures: Boolean = true) {
        // Room cannot emit CHECK: these two fresh-schema triggers enforce the same alias kind invariant.
        for (event in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_memory_project_alias_kind_${event.lowercase()} BEFORE $event ON memory_project_aliases WHEN NEW.kind NOT IN ('key','cwd') BEGIN SELECT RAISE(ABORT,'memory_alias_invalid_kind'); END")
        }
        MemoryPortableMetadata.install(db)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_entries_topic_active ON memory_entries(project,scope,topic_key) WHERE topic_key IS NOT NULL AND deleted_at IS NULL")
        PersonalMemorySearchIndex.install(db, context, reportFailures)
        MemoryPromptSearchIndex.install(db)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_transcript_artifact_owner ON transcript_artifact_refs(conversation_id,owner_kind,batch_id,IFNULL(call_id,''),artifact_id)")
        MessageSearchIndex.install(db)
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_conversation_no_replace " +
                "BEFORE INSERT ON conversations " +
                "WHEN EXISTS (SELECT 1 FROM conversations WHERE id=NEW.id) " +
                "BEGIN SELECT RAISE(ABORT,'conversation_exists'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_conversation_id_immutable " +
                "BEFORE UPDATE OF id ON conversations " +
                "BEGIN SELECT RAISE(ABORT,'conversation_id_immutable'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_tombstones_no_duplicate " +
                "BEFORE INSERT ON tombstones " +
                "WHEN EXISTS (SELECT 1 FROM tombstones WHERE scope=NEW.scope AND entity_id=NEW.entity_id) " +
                "BEGIN SELECT RAISE(ABORT,'tombstone_exists'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_conversation_no_tombstone_resurrection " +
                "BEFORE INSERT ON conversations " +
                "WHEN EXISTS (SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=NEW.id) " +
                "BEGIN SELECT RAISE(ABORT,'conversation_tombstoned'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_conversation_tombstone_after_delete " +
                "AFTER DELETE ON conversations " +
                "BEGIN DELETE FROM agent_session_state WHERE conversation_id=OLD.id; " +
                "INSERT INTO tombstones(scope,entity_id,deleted_at) " +
                "SELECT 'conversation',OLD.id,CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) " +
                "WHERE NOT EXISTS (SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=OLD.id); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_tombstones_no_update " +
                "BEFORE UPDATE ON tombstones BEGIN SELECT RAISE(ABORT,'tombstones_are_immutable'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_tombstones_no_delete " +
                "BEFORE DELETE ON tombstones BEGIN SELECT RAISE(ABORT,'tombstones_are_immutable'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_workspace_reply_transition_monotonic " +
                "BEFORE INSERT ON workspace_coordination_reply_transitions " +
                "WHEN NOT EXISTS (SELECT 1 FROM workspace_coordination_replies r " +
                "WHERE r.conversation_id=NEW.conversation_id AND r.reply_id=NEW.reply_id AND " +
                "((r.state='PREPARED' AND NEW.state='STARTED' AND NEW.state_at_ms>=r.state_at_ms) OR " +
                "(r.state='STARTED' AND NEW.state='ACCEPTED_LOCAL' AND NEW.state_at_ms>=r.state_at_ms))) " +
                "BEGIN SELECT RAISE(ABORT,'invalid_workspace_reply_transition'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_workspace_reply_update_monotonic " +
                "BEFORE UPDATE OF state,state_at_ms ON workspace_coordination_replies " +
                "WHEN NOT ((OLD.state='PREPARED' AND NEW.state='STARTED' AND NEW.state_at_ms>=OLD.state_at_ms) OR " +
                "(OLD.state='STARTED' AND NEW.state='ACCEPTED_LOCAL' AND NEW.state_at_ms>=OLD.state_at_ms)) " +
                "BEGIN SELECT RAISE(ABORT,'invalid_workspace_reply_transition'); END",
        )
        val activeStates = "'preparing','requesting','streaming','waiting_tool'"
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_generation_run_target_insert " +
                "BEFORE INSERT ON generation_run " +
                "WHEN (NEW.active_target_key IS NULL) != (NEW.state NOT IN ($activeStates)) " +
                "BEGIN SELECT RAISE(ABORT,'generation_run_target_state_mismatch'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_generation_run_target_update " +
                "BEFORE UPDATE OF state,active_target_key ON generation_run " +
                "WHEN (NEW.active_target_key IS NULL) != (NEW.state NOT IN ($activeStates)) " +
                "BEGIN SELECT RAISE(ABORT,'generation_run_target_state_mismatch'); END",
        )
    }
}

    /** Some PRAGMA assignments return a row; Android rejects those through execSQL, so they go through query. */
    private fun setPragma(db: SupportSQLiteDatabase, assignment: String) {
        db.query("PRAGMA $assignment").use { cursor -> cursor.moveToFirst() }
    }

    private fun verifyPragma(db: SupportSQLiteDatabase, pragma: String, expected: String) {
        val actual = db.query("PRAGMA $pragma").useAndReadFirstCell()
        check(actual.equals(expected, ignoreCase = true)) {
            "Jarvys chat database PRAGMA contract failed: $pragma expected=$expected actual=$actual"
        }
    }

    private fun verifyPragma(connection: SQLiteConnection, pragma: String, expected: String) {
        val actual = connection.prepare("PRAGMA $pragma").use { statement ->
            check(statement.step()) { "PRAGMA $pragma returned no value" }
            when (statement.getColumnType(0)) {
                1 -> statement.getLong(0).toString()
                2 -> statement.getDouble(0).toString()
                3 -> statement.getText(0)
                else -> ""
            }
        }
        check(actual.equals(expected, ignoreCase = true)) {
            "Jarvys chat database PRAGMA contract failed: $pragma expected=$expected actual=$actual"
        }
    }

    private fun createExecutors(): List<ExecutorService> = listOf(
        Executors.newFixedThreadPool(4, NamedDaemonThreadFactory("jarvys-chat-read")),
        Executors.newSingleThreadExecutor(NamedDaemonThreadFactory("jarvys-chat-transaction")),
    )

    /** Read-only handles are never created through the framework or allowed to create a store. */
    private fun openReadOnlyConnection(file: File): SQLiteConnection =
        BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX)

    /** Independent committed-read handles; retain the established bounded pool and snapshot fence. */
    private class ReadDatabasePool(databaseFile: File, private val onPartialCloseFailure: (() -> Unit)? = null) {
        private data class OpenFileIdentity(val device: Long, val inode: Long)
        private fun fileIdentity(file: File): OpenFileIdentity {
            val value = android.system.Os.lstat(file.absolutePath)
            check(android.system.OsConstants.S_ISREG(value.st_mode)) { "read_pool_file_invalid" }
            return OpenFileIdentity(value.st_dev, value.st_ino)
        }
        // Runtime metadata only. A detached old pool must never attest a newly replaced file.
        private val openedIdentity = fileIdentity(databaseFile)
        fun requireDoctorIdentity(file: File) {
            val current = try { fileIdentity(file) }
            catch (failure: android.system.ErrnoException) {
                if (failure.errno == android.system.OsConstants.ENOENT)
                    throw com.jarvys.agent.MemoryDoctorUnavailable("source_changed")
                throw failure
            }
            if (current != openedIdentity) throw com.jarvys.agent.MemoryDoctorUnavailable("source_changed")
        }
        private val all = mutableListOf<SQLiteConnection>()
        private val lock = java.lang.Object()
        private val available = ArrayDeque<SQLiteConnection>()
        private val borrowed = Collections.newSetFromMap(IdentityHashMap<SQLiteConnection, Boolean>())
        private var closed = false

        init {
            try {
                repeat(READ_CONNECTION_POOL_SIZE) {
                    val database = openReadOnlyConnection(databaseFile)
                    try {
                        DriverChatSqlDatabase(database).execSQL("PRAGMA query_only=ON")
                        DriverChatSqlDatabase(database).execSQL("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS")
                        verifyPragma(database, "journal_mode", "wal")
                        verifyPragma(database, "query_only", "1")
                        verifyPragma(database, "busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
                        check(fileIdentity(databaseFile) == openedIdentity) { "read_pool_file_changed" }
                        all += database
                        available.addLast(database)
                    } catch (error: Throwable) {
                        runCatching { database.close() }.onFailure { onPartialCloseFailure?.invoke() }
                        throw error
                    }
                }
            } catch (error: Throwable) {
                all.forEach { runCatching { it.close() }.onFailure { onPartialCloseFailure?.invoke() } }
                throw error
            }
        }

        fun acquireBlocking(): SQLiteConnection {
            synchronized(lock) {
                while (true) {
                    check(!closed) { "Jarvys chat read pool is closed" }
                    val database = available.pollFirst()
                    if (database != null) {
                        borrowed.add(database)
                        return database
                    }
                    // Let InterruptedException escape. The suspend acquisition wraps this wait
                    // in runInterruptible, which translates a coroutine cancellation into a
                    // CancellationException once the blocked worker is interrupted.
                    lock.wait()
                }
            }
        }

        fun release(database: SQLiteConnection) {
            val closeOnReturn = synchronized(lock) {
                if (!borrowed.remove(database)) return
                if (closed) {
                    all.removeAll { it === database }
                    true
                } else {
                    available.addLast(database)
                    lock.notify()
                    false
                }
            }
            if (closeOnReturn) runCatching { database.close() }
        }

        fun close(strict: Boolean = false) {
            var failure: Throwable? = null
            val availableToClose = synchronized(lock) {
                // Preserve uncertainty about borrowed handles, but never strand the idle handles.
                if (strict && borrowed.isNotEmpty()) failure = IllegalStateException("scratch_read_handles_not_drained")
                if (closed) emptyList() else {
                    closed = true
                    val handles = available.toList()
                    available.clear()
                    all.removeAll { handle -> handles.any { it === handle } }
                    lock.notifyAll()
                    handles
                }
            }
            availableToClose.forEach { handle ->
                try { handle.close() } catch (caught: Throwable) {
                    if (strict) {
                        if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                    }
                }
            }
            failure?.let { throw it }
        }

        private fun verifyPragma(database: SQLiteConnection, pragma: String, expected: String) {
            val actual = DriverChatSqlDatabase(database).query("PRAGMA $pragma").use { cursor ->
                check(cursor.moveToFirst()) { "PRAGMA $pragma returned no value" }
                cursor.getString(0)
            }
            check(actual.equals(expected, ignoreCase = true)) {
                "Jarvys chat read pool PRAGMA contract failed: $pragma expected=$expected actual=$actual"
            }
        }
    }

    private const val READ_CONNECTION_POOL_SIZE = 4
    private const val TEST_DATABASE_CLOSE_TIMEOUT_SECONDS = 30L

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val sequence = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private fun Cursor.useAndReadFirstCell(): String {
        use {
            check(it.moveToFirst()) { "PRAGMA query returned no value" }
            return it.getString(0)
        }
    }
}

/** Test SQL inspections use leases from this database's actual Room driver pool. */
internal val JarvysChatDatabase.testChatSql: ChatSqlDatabase
    get() = chatSql


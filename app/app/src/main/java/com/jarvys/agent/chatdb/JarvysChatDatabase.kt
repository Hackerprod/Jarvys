package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.RoomDatabase.JournalMode
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
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
 * enum, bounds, optional sequence-marker, and first-wins import checks are M1 repository responsibilities.
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
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class JarvysChatDatabase : RoomDatabase() {
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
        )
    }
}

/** One app-process database instance at filesDir/jarvys/jarvys-chat.db. */
internal object JarvysChatDatabaseProvider {
    private const val DATABASE_VERSION = 1
    private const val DATABASE_NAME = "jarvys-chat.db"
    private const val DATABASE_DIRECTORY = "jarvys"
    private const val BUSY_TIMEOUT_MILLIS = 5_000
    private const val WAL_AUTOCHECKPOINT_PAGES = 1_000
    private const val JOURNAL_SIZE_LIMIT_BYTES = 16_777_216

    @Volatile
    private var instance: JarvysChatDatabase? = null

    private val databaseExecutors = IdentityHashMap<JarvysChatDatabase, List<ExecutorService>>()
    private val queryDispatchers = IdentityHashMap<JarvysChatDatabase, ExecutorCoroutineDispatcher>()
    private val writerDispatchers = IdentityHashMap<JarvysChatDatabase, ExecutorCoroutineDispatcher>()
    private val databaseFiles = IdentityHashMap<JarvysChatDatabase, File>()
    private val readPools = IdentityHashMap<JarvysChatDatabase, ReadDatabasePool>()

    /** Call off the main thread. Room opens on first use; version preflight is dispatched to the read pool. */
    @JvmStatic
    fun getInstance(context: Context): JarvysChatDatabase {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "JarvysChatDatabaseProvider.getInstance must be called off the main thread"
        }
        return instance ?: synchronized(this) {
            instance ?: run {
            val databaseFile = File(File(context.applicationContext.filesDir, DATABASE_DIRECTORY), DATABASE_NAME)
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
                synchronized(databaseExecutors) { databaseExecutors[it] = executorSet }
                synchronized(databaseFiles) { databaseFiles[it] = databaseFile }
                synchronized(queryDispatchers) { queryDispatchers[it] = executorSet[0].asCoroutineDispatcher() }
                synchronized(writerDispatchers) { writerDispatchers[it] = executorSet[1].asCoroutineDispatcher() }
                synchronized(readPools) { readPools[it] = checkNotNull(readPool) }
                instance = it
            }
            }
        }
    }

    /**
     * Opens an isolated file for schema tests while applying the production future-version gate and
     * connection PRAGMAs. It is internal so production callers use only the process singleton.
     */
    internal fun openDatabaseFileForTests(context: Context, databaseFile: File): JarvysChatDatabase {
        databaseFile.parentFile?.mkdirs()
        val executorSet = createExecutors()
        var openedDatabase: JarvysChatDatabase? = null
        var readPool: ReadDatabasePool? = null
        return try {
            buildDatabase(context.applicationContext, databaseFile, executorSet).also { database ->
                openedDatabase = database
                kotlinx.coroutines.runBlocking { database.useWriterConnection { } }
                readPool = ReadDatabasePool(databaseFile)
                synchronized(databaseExecutors) { databaseExecutors[database] = executorSet }
                synchronized(queryDispatchers) { queryDispatchers[database] = executorSet[0].asCoroutineDispatcher() }
                synchronized(writerDispatchers) { writerDispatchers[database] = executorSet[1].asCoroutineDispatcher() }
                synchronized(databaseFiles) { databaseFiles[database] = databaseFile }
                synchronized(readPools) { readPools[database] = checkNotNull(readPool) }
            }
        } catch (error: Throwable) {
            runCatching { readPool?.close() }
            runCatching { openedDatabase?.close() }
            executorSet.forEach(ExecutorService::shutdown)
            throw error
        }
    }

    /** Closes provider-owned handles and executors together with their Room database. */
    internal fun closeDatabase(database: JarvysChatDatabase) {
        val readPool = synchronized(readPools) { readPools[database] }
        readPool?.close()
        synchronized(readPools) {
            if (readPools[database] === readPool) readPools.remove(database)
        }
        database.close()
        val executors = synchronized(databaseExecutors) { databaseExecutors.remove(database) }.orEmpty()
        synchronized(queryDispatchers) { queryDispatchers.remove(database) }?.close()
        synchronized(writerDispatchers) { writerDispatchers.remove(database) }?.close()
        synchronized(databaseFiles) { databaseFiles.remove(database) }
        executors.forEach(ExecutorService::shutdown)
        synchronized(this) { if (instance === database) instance = null }
    }

    /** Closes the process singleton and its read pool when the owning application is shutting down. */
    internal fun closeInstance() = synchronized(this) { instance?.let(::closeDatabase) }

    /** Closes an isolated database opened for tests. */
    internal fun closeDatabaseFileForTests(database: JarvysChatDatabase) = closeDatabase(database)

    /** Physical file for raw, read-only projection handles owned by this provider. */
    internal fun databaseFileForRawQuery(database: JarvysChatDatabase): File =
        synchronized(databaseFiles) { databaseFiles[database] }
            ?: throw IllegalStateException("No database file is registered for the chat database")

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

    internal suspend fun acquireReadDatabase(database: JarvysChatDatabase): SQLiteDatabase =
        kotlinx.coroutines.runInterruptible { acquireReadDatabaseBlocking(database) }

    /** Synchronous SQL facade fallback; its monitor wait responds to thread interruption. */
    internal fun acquireReadDatabaseBlocking(database: JarvysChatDatabase): SQLiteDatabase =
        synchronized(readPools) { readPools[database] }
            ?.acquireBlocking()
            ?: throw IllegalStateException("No Jarvys chat read pool is registered for this database")

    internal fun releaseReadDatabase(database: JarvysChatDatabase, handle: SQLiteDatabase) {
        val pool = synchronized(readPools) { readPools[database] }
        if (pool != null) pool.release(handle) else runCatching { handle.close() }
    }

    /** Per-connection SQLite commit generation used to validate a multi-statement read projection. */
    internal fun readDataVersion(handle: SQLiteDatabase): Long = handle.rawQuery("PRAGMA data_version", null).use { cursor ->
        check(cursor.moveToFirst()) { "PRAGMA data_version returned no value" }
        cursor.getLong(0)
    }

    private fun buildDatabase(
        context: Context,
        databaseFile: File,
        executors: List<ExecutorService>,
    ): JarvysChatDatabase {
        runVersionPreflightOnQueryExecutor(databaseFile, executors[0])
        return Room.databaseBuilder(context, JarvysChatDatabase::class.java, databaseFile.absolutePath)
            // Room 2.7's Android driver uses one connection for writer and reader leases. The
            // provider's separate read-only pool serves concurrent projections from WAL.
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(executors[0])
            .setTransactionExecutor(executors[1])
            .addMigrations(*Migrations.ALL)
            .addCallback(connectionPragmasCallback())
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
        if (!databaseFile.exists() || databaseFile.length() == 0L) return
        val foundVersion = try {
            val sqlite = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            sqlite.use { db ->
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    check(cursor.moveToFirst()) { "Could not read SQLite user_version from ${databaseFile.name}" }
                    cursor.getInt(0)
                }
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException("Could not inspect chat database schema version at ${databaseFile.absolutePath}", error)
        }
        if (foundVersion > DATABASE_VERSION) {
            throw IllegalStateException(
                "database_schema_too_new: found user_version=$foundVersion, supported=$DATABASE_VERSION; file was left untouched",
            )
        }
    }

    internal fun connectionPragmasCallback(): Callback = object : Callback() {
        private val driverTriggersInstalled = AtomicBoolean(false)
        private val driverTriggerInstallLock = Any()

        private fun createDriverTriggersOnce(connection: SQLiteConnection) {
            if (driverTriggersInstalled.get()) return
            synchronized(driverTriggerInstallLock) {
                if (driverTriggersInstalled.get()) return
                DatabaseTriggers.create(connection)
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
        // AndroidSQLiteDriver and calls the SQLiteConnection overloads above.
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            DatabaseTriggers.create(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            configureOpenedConnection(db)
        }
    }

    /** Shared Room/test-helper connection setup; each opened connection gets triggers and PRAGMAs. */
    internal fun configureOpenedConnection(db: SupportSQLiteDatabase) {
        DatabaseTriggers.create(db)
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

/** Invariants Room cannot encode. Every future migration that rebuilds these tables must call [create]. */
internal object DatabaseTriggers {
    fun create(connection: SQLiteConnection) {
        val sql = DriverChatSqlDatabase(connection)
        create(sql)
    }

    fun create(db: SupportSQLiteDatabase) = create(SupportChatSqlDatabase(db))

    fun create(db: ChatSqlDatabase) {
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
                "BEGIN INSERT INTO tombstones(scope,entity_id,deleted_at) " +
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

    /** Independent committed-read handles; Room's Android driver reader lease aliases the writer. */
    private class ReadDatabasePool(databaseFile: File) {
        private val all = mutableListOf<SQLiteDatabase>()
        private val lock = java.lang.Object()
        private val available = ArrayDeque<SQLiteDatabase>()
        private val borrowed = Collections.newSetFromMap(IdentityHashMap<SQLiteDatabase, Boolean>())
        private var closed = false

        init {
            try {
                repeat(READ_CONNECTION_POOL_SIZE) {
                    val database = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    try {
                        database.rawQuery("PRAGMA query_only=ON", null).use { while (it.moveToNext()) Unit }
                        database.rawQuery("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS", null).use { while (it.moveToNext()) Unit }
                        verifyPragma(database, "journal_mode", "wal")
                        verifyPragma(database, "query_only", "1")
                        verifyPragma(database, "busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
                        all += database
                        available.addLast(database)
                    } catch (error: Throwable) {
                        runCatching { database.close() }
                        throw error
                    }
                }
            } catch (error: Throwable) {
                all.forEach { runCatching { it.close() } }
                throw error
            }
        }

        fun acquireBlocking(): SQLiteDatabase {
            synchronized(lock) {
                while (true) {
                    check(!closed) { "Jarvys chat read pool is closed" }
                    val database = available.pollFirst()
                    if (database != null) {
                        borrowed.add(database)
                        return database
                    }
                    try {
                        lock.wait()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("Interrupted while waiting for a Jarvys chat read handle", interrupted)
                    }
                }
            }
        }

        fun release(database: SQLiteDatabase) {
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

        fun close() {
            val availableToClose = synchronized(lock) {
                if (closed) return
                closed = true
                val handles = available.toList()
                available.clear()
                all.removeAll { handle -> handles.any { it === handle } }
                lock.notifyAll()
                handles
            }
            availableToClose.forEach { runCatching { it.close() } }
        }

        private fun verifyPragma(database: SQLiteDatabase, pragma: String, expected: String) {
            val actual = database.rawQuery("PRAGMA $pragma", null).use { cursor ->
                check(cursor.moveToFirst()) { "PRAGMA $pragma returned no value" }
                cursor.getString(0)
            }
            check(actual.equals(expected, ignoreCase = true)) {
                "Jarvys chat read pool PRAGMA contract failed: $pragma expected=$expected actual=$actual"
            }
        }
    }

    private const val READ_CONNECTION_POOL_SIZE = 4

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


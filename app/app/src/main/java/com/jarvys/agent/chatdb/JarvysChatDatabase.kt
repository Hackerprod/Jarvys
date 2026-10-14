package com.jarvys.agent.chatdb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.RoomDatabase.JournalMode
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Normalized Room store for the conversation ledger (M0 schema version). Room entities do not emit
 * SQL CHECK constraints; equivalent state, enum, bounds, and transition validation is an M1
 * repository responsibility and will be covered by M1 repository-level tests.
 */
@Database(
    entities = [
        Conversation::class,
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
        DeliveredFile::class,
        GeneratedImage::class,
        Compaction::class,
        CompactionReflectionAck::class,
        Reflection::class,
        ReflectionRevision::class,
        CrewMission::class,
        CrewBot::class,
        CrewBotTool::class,
        CrewMessage::class,
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
abstract class JarvysChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun conversationItemDao(): ConversationItemDao
    abstract fun messageDao(): MessageDao
    companion object {
        /** Entity table names used by M0 schema inspection; keep aligned with [Database.entities]. */
        @JvmField
        val ENTITY_TABLE_NAMES: Set<String> = setOf(
            "conversations",
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
            "delivered_files",
            "generated_images",
            "compactions",
            "compaction_reflection_acks",
            "reflections",
            "reflection_revisions",
            "crew_missions",
            "crew_bots",
            "crew_bot_tools",
            "crew_messages",
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
object JarvysChatDatabaseProvider {
    private const val DATABASE_VERSION = 1
    private const val DATABASE_NAME = "jarvys-chat.db"
    private const val DATABASE_DIRECTORY = "jarvys"
    private const val BUSY_TIMEOUT_MILLIS = 5_000
    private const val WAL_AUTOCHECKPOINT_PAGES = 1_000
    private const val JOURNAL_SIZE_LIMIT_BYTES = 16_777_216

    @Volatile
    private var instance: JarvysChatDatabase? = null

    private val isolatedExecutors = IdentityHashMap<JarvysChatDatabase, List<ExecutorService>>()

    @JvmStatic
    fun getInstance(context: Context): JarvysChatDatabase = instance ?: synchronized(this) {
        instance ?: run {
            val databaseFile = File(File(context.applicationContext.filesDir, DATABASE_DIRECTORY), DATABASE_NAME)
            databaseFile.parentFile?.mkdirs()
            check(databaseFile.parentFile?.isDirectory == true) {
                "Could not create Jarvys chat database directory: ${databaseFile.parentFile}"
            }
            val executorSet = createExecutors()
            val opened = try {
                buildDatabase(context.applicationContext, databaseFile, executorSet)
            } catch (error: Throwable) {
                executorSet.forEach(ExecutorService::shutdown)
                throw error
            }
            opened.also { instance = it }
        }
    }

    /**
     * Opens an isolated file for schema tests while applying the production future-version gate and
     * connection PRAGMAs. It is internal so production callers use only the process singleton.
     */
    internal fun openDatabaseFileForTests(context: Context, databaseFile: File): JarvysChatDatabase {
        databaseFile.parentFile?.mkdirs()
        val executorSet = createExecutors()
        return try {
            buildDatabase(context.applicationContext, databaseFile, executorSet).also { database ->
                synchronized(isolatedExecutors) { isolatedExecutors[database] = executorSet }
            }
        } catch (error: Throwable) {
            executorSet.forEach(ExecutorService::shutdown)
            throw error
        }
    }

    /** Closes a database opened by [openDatabaseFileForTests] and releases its private executors. */
    internal fun closeDatabaseFileForTests(database: JarvysChatDatabase) {
        database.close()
        val executors = synchronized(isolatedExecutors) { isolatedExecutors.remove(database) }.orEmpty()
        executors.forEach(ExecutorService::shutdown)
    }

    private fun buildDatabase(
        context: Context,
        databaseFile: File,
        executors: List<ExecutorService>,
    ): JarvysChatDatabase {
        runVersionPreflightOnQueryExecutor(databaseFile, executors[0])
        return Room.databaseBuilder(context, JarvysChatDatabase::class.java, databaseFile.absolutePath)
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

    private fun connectionPragmasCallback(): Callback = object : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS")
            db.execSQL("PRAGMA synchronous=NORMAL")
            db.execSQL("PRAGMA wal_autocheckpoint=$WAL_AUTOCHECKPOINT_PAGES")
            db.execSQL("PRAGMA journal_size_limit=$JOURNAL_SIZE_LIMIT_BYTES")
            verifyPragma(db, "journal_mode", "wal")
            verifyPragma(db, "foreign_keys", "1")
            verifyPragma(db, "busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
            verifyPragma(db, "synchronous", "1")
            verifyPragma(db, "wal_autocheckpoint", WAL_AUTOCHECKPOINT_PAGES.toString())
            verifyPragma(db, "journal_size_limit", JOURNAL_SIZE_LIMIT_BYTES.toString())
        }
    }

    private fun verifyPragma(db: SupportSQLiteDatabase, pragma: String, expected: String) {
        val actual = db.query("PRAGMA $pragma").useAndReadFirstCell()
        check(actual.equals(expected, ignoreCase = true)) {
            "Jarvys chat database PRAGMA contract failed: $pragma expected=$expected actual=$actual"
        }
    }

    private fun createExecutors(): List<ExecutorService> = listOf(
        Executors.newSingleThreadExecutor(NamedDaemonThreadFactory("jarvys-chat-query")),
        Executors.newSingleThreadExecutor(NamedDaemonThreadFactory("jarvys-chat-transaction")),
    )

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


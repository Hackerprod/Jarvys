package com.jarvys.agent.chatdb

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations are additive and registered here. Future columns must be appended to the
 * end of the entity/table declaration and added with an explicit version-to-version migration.
 * Every migration that recreates any triggered table must call
 * [JarvysChatDatabaseProvider.DatabaseTriggers.create]; Room also reapplies the idempotent triggers
 * on every connection open because it does not export or validate them.
 */
internal object Migrations {
    /** Room's driver and compatibility entrypoints must execute the identical migration program. */
    private abstract class SqlMigration(from: Int, to: Int) : Migration(from, to) {
        final override fun migrate(database: SupportSQLiteDatabase) = migrateSql { database.execSQL(it) }
        final override fun migrate(connection: SQLiteConnection) = migrateSql { sql ->
            connection.prepare(sql).use { statement -> statement.step() }
        }
        protected abstract fun migrateSql(execute: (String) -> Unit)
    }

    private val MIGRATION_3_4 = object : SqlMigration(3, 4) {
        override fun migrateSql(execute: (String) -> Unit) {
            execute("CREATE TABLE IF NOT EXISTS connector_autonomy_audit (id TEXT NOT NULL, timestamp_millis INTEGER NOT NULL, connector_id TEXT NOT NULL, connector_name TEXT NOT NULL, operation_name TEXT NOT NULL, operation_label TEXT NOT NULL, summary TEXT NOT NULL, PRIMARY KEY(id))")
            execute("CREATE INDEX IF NOT EXISTS index_connector_autonomy_audit_connector_id_timestamp_millis_id ON connector_autonomy_audit(connector_id, timestamp_millis, id)")
            execute("CREATE TABLE IF NOT EXISTS connector_state_events (id TEXT NOT NULL, connector_id TEXT NOT NULL, connected INTEGER NOT NULL, connected_at_ms INTEGER NOT NULL, PRIMARY KEY(id))")
            execute("CREATE INDEX IF NOT EXISTS index_connector_state_events_connector_id_connected_at_ms ON connector_state_events(connector_id, connected_at_ms)")
        }
    }
    private val MIGRATION_4_5 = object : SqlMigration(4, 5) {
        override fun migrateSql(execute: (String) -> Unit) {
            execute("CREATE INDEX IF NOT EXISTS idx_memory_entries_topic_key ON memory_entries(topic_key)")
            execute("ALTER TABLE memory_evidence ADD COLUMN source_batch_id TEXT")
            execute("ALTER TABLE memory_evidence ADD COLUMN run_id TEXT")
            execute("ALTER TABLE memory_evidence ADD COLUMN process_id TEXT")
            execute("CREATE INDEX IF NOT EXISTS idx_memory_evidence_batch ON memory_evidence(ref_conversation_id,source_batch_id,ref_id)")
            execute("CREATE INDEX IF NOT EXISTS idx_memory_evidence_run ON memory_evidence(run_id,outcome)")
            execute("CREATE INDEX IF NOT EXISTS idx_memory_evidence_pending_process ON memory_evidence(outcome,process_id)")
            // Pre-ownership pending evidence cannot establish a live owner or success after restart.
            execute("UPDATE memory_entries SET narration='none' WHERE kind='episode' AND id IN (SELECT entry_id FROM memory_evidence WHERE outcome IN ('intent','started'))")
            execute("UPDATE memory_evidence SET outcome='uncertain' WHERE outcome IN ('intent','started')")
            execute("CREATE TABLE IF NOT EXISTS mission_episode_inputs (conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, execution_session_id TEXT NOT NULL, workspace_id TEXT NOT NULL, assignment_id TEXT NOT NULL, mission_id TEXT NOT NULL, bot_id TEXT NOT NULL, cycle_id TEXT NOT NULL, result_text_sha256 TEXT NOT NULL, PRIMARY KEY(conversation_id,message_id), FOREIGN KEY(conversation_id,message_id) REFERENCES messages(conversation_id,message_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            execute("CREATE INDEX IF NOT EXISTS idx_mission_episode_inputs_source ON mission_episode_inputs(execution_session_id,mission_id,bot_id,cycle_id)")
            execute("CREATE TABLE IF NOT EXISTS compaction_coverage_sources (conversation_id TEXT NOT NULL, compaction_id TEXT NOT NULL, source_kind TEXT NOT NULL, source_id TEXT NOT NULL, call_id TEXT NOT NULL, source_fingerprint TEXT NOT NULL, source_seq INTEGER NOT NULL, PRIMARY KEY(conversation_id,compaction_id,source_kind,source_id,call_id), FOREIGN KEY(conversation_id,compaction_id) REFERENCES compactions(conversation_id,compaction_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            execute("CREATE TABLE IF NOT EXISTS compaction_flush (conversation_id TEXT NOT NULL, compaction_id TEXT NOT NULL, coverage_hash TEXT, digest_id TEXT, digest_revision INTEGER, status TEXT NOT NULL, PRIMARY KEY(conversation_id,compaction_id), FOREIGN KEY(conversation_id,compaction_id) REFERENCES compactions(conversation_id,compaction_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }
    private val MIGRATION_5_6 = object : SqlMigration(5, 6) {
        override fun migrateSql(execute: (String) -> Unit) {
            execute("ALTER TABLE connector_state_events ADD COLUMN store_epoch TEXT")
            execute("ALTER TABLE connector_state_events ADD COLUMN sequence INTEGER")
            execute("ALTER TABLE connector_state_events ADD COLUMN previous_choice INTEGER")
            execute("ALTER TABLE connector_state_events ADD COLUMN reason TEXT")
            execute("ALTER TABLE connector_state_events ADD COLUMN operation_id TEXT")
            execute("ALTER TABLE connector_state_events ADD COLUMN parent_operation_id TEXT")
            execute("ALTER TABLE connector_state_events ADD COLUMN payload_version INTEGER")
            execute("CREATE UNIQUE INDEX IF NOT EXISTS index_connector_state_events_store_epoch_sequence ON connector_state_events(store_epoch,sequence)")
        }
    }
    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}


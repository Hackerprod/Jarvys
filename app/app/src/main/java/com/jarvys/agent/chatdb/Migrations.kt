package com.jarvys.agent.chatdb

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations are additive and registered here. Future columns must be appended to the
 * end of the entity/table declaration and added with an explicit version-to-version migration.
 * Every migration that recreates any triggered table must call
 * [JarvysChatDatabaseProvider.DatabaseTriggers.create]; Room also reapplies the idempotent triggers
 * on every connection open because it does not export or validate them.
 */
internal object Migrations {
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS connector_autonomy_audit (id TEXT NOT NULL, timestamp_millis INTEGER NOT NULL, connector_id TEXT NOT NULL, connector_name TEXT NOT NULL, operation_name TEXT NOT NULL, operation_label TEXT NOT NULL, summary TEXT NOT NULL, PRIMARY KEY(id))")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_connector_autonomy_audit_connector_id_timestamp_millis_id ON connector_autonomy_audit(connector_id, timestamp_millis, id)")
            database.execSQL("CREATE TABLE IF NOT EXISTS connector_state_events (id TEXT NOT NULL, connector_id TEXT NOT NULL, connected INTEGER NOT NULL, connected_at_ms INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_connector_state_events_connector_id_connected_at_ms ON connector_state_events(connector_id, connected_at_ms)")
        }
    }
    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4)
}


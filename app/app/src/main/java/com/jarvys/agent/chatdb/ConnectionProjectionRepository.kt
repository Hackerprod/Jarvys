package com.jarvys.agent.chatdb

import com.jarvys.agent.connectors.ConnectionStateCoordinator
import java.util.UUID

/** Immutable projection only: a local selection is never an effect authorization. */
internal class ConnectionProjectionRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    suspend fun generation(): String = database.withChatWriteTransaction {
        readGeneration() ?: UUID.randomUUID().toString().let { candidate ->
            sql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)", arrayOf(GENERATION_KEY, candidate))
            checkNotNull(readGeneration())
        }
    }

    suspend fun project(batch: ConnectionStateCoordinator.ProjectionBatch): ConnectionStateCoordinator.Ack =
        database.withChatWriteTransaction {
            check(readGeneration() == batch.targetGeneration) { "Connection projection destination changed" }
            batch.events.forEach { event ->
                require(event.storeEpoch == batch.sourceEpoch && event.eventId == "${event.storeEpoch}:${event.sequence}")
                sql.execSQL(
                    "INSERT OR IGNORE INTO connector_state_events " +
                        "(id,connector_id,connected,connected_at_ms,store_epoch,sequence,previous_choice,reason,operation_id,parent_operation_id,payload_version) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(event.eventId, event.connectorId, if (event.selected) 1 else 0, event.observedAtMs,
                        event.storeEpoch, event.sequence, event.previous?.let { if (it) 1 else 0 }, event.reason,
                        event.operationId, event.parentOperationId, PAYLOAD_VERSION),
                )
                val matches = sql.query(
                    "SELECT id,connector_id,connected,connected_at_ms,store_epoch,sequence,previous_choice,reason,operation_id,parent_operation_id,payload_version " +
                        "FROM connector_state_events WHERE id=?", arrayOf(event.eventId),
                ).use { c ->
                    c.moveToFirst() && c.getString(0) == event.eventId && c.getString(1) == event.connectorId &&
                        c.getInt(2) == (if (event.selected) 1 else 0) && c.getLong(3) == event.observedAtMs &&
                        !c.isNull(4) && c.getString(4) == event.storeEpoch && !c.isNull(5) && c.getLong(5) == event.sequence &&
                        (if (c.isNull(6)) null else c.getInt(6)) == event.previous?.let { if (it) 1 else 0 } &&
                        c.getString(7) == event.reason && c.getString(8) == event.operationId &&
                        (if (c.isNull(9)) null else c.getString(9)) == event.parentOperationId &&
                        !c.isNull(10) && c.getInt(10) == PAYLOAD_VERSION
                }
                check(matches) { "Immutable connection event payload conflict" }
            }
            ConnectionStateCoordinator.Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)
        }

    private fun readGeneration(): String? = sql.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(GENERATION_KEY)).use { c ->
        if (!c.moveToFirst()) null else c.getString(0).also { value ->
            check(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
                "Invalid connection projection generation"
            }
        }
    }

    companion object {
        const val GENERATION_KEY = "connection_projection_generation_v1"
        const val PAYLOAD_VERSION = 1
    }
}

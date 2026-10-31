package com.jarvys.agent.chatdb

import android.content.Context
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.io.File

/** Explicit impossible-through-current-UI states for old/corrupt ledger no-resurrection tests. */
object LegacyCentralStateTestFixture {
    @JvmStatic fun databaseFile(context: Context): File =
        JarvysChatDatabaseProvider.databaseFileForRawQuery(JarvysChatDatabaseProvider.getInstance(context))

    @JvmStatic fun assertCentralRowExists(context: Context) {
        val database = JarvysChatDatabaseProvider.getInstance(context)
        runBlocking { database.withChatReadTransaction {
            assertEquals(1L, database.chatSql.query("SELECT COUNT(*) FROM conversations WHERE id=?",
                arrayOf(WorkspaceIdentity.CENTRAL_SESSION)).use { it.moveToFirst(); it.getLong(0) })
        } }
    }

    @JvmStatic fun tombstoneAndRemove(context: Context) = remove(context, true)
    @JvmStatic fun removeWithoutTombstone(context: Context) = remove(context, false)

    private fun remove(context: Context, tombstone: Boolean) {
        val database = JarvysChatDatabaseProvider.getInstance(context)
        val central = WorkspaceIdentity.CENTRAL_SESSION
        runBlocking {
            database.withChatWriteTransaction {
                val sql = database.chatSql
                assertEquals(1L, sql.query("SELECT COUNT(*) FROM conversations WHERE id=?", arrayOf(central))
                    .use { it.moveToFirst(); it.getLong(0) })
                if (tombstone) sql.execSQL(
                    "INSERT INTO tombstones(scope,entity_id,deleted_at,payload) VALUES('conversation',?,123,NULL)",
                    arrayOf(central))
                else assertEquals(0L, sql.query(
                    "SELECT COUNT(*) FROM tombstones WHERE scope='conversation' AND entity_id=?", arrayOf(central))
                    .use { it.moveToFirst(); it.getLong(0) })
                // Only this missing-history fixture bypasses the automatic tombstone trigger.
                // DDL and delete share one transaction; restore exact SQL before committing.
                val triggerName = "trg_conversation_tombstone_after_delete"
                val triggerSql = if (tombstone) null else sql.query(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(triggerName))
                    .use { check(it.moveToFirst()); it.getString(0) }
                if (triggerSql != null) sql.execSQL("DROP TRIGGER $triggerName")
                sql.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(central))
                if (triggerSql != null) {
                    sql.execSQL(triggerSql)
                    assertEquals(triggerSql, sql.query(
                        "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(triggerName))
                        .use { check(it.moveToFirst()); it.getString(0) })
                    assertEquals(0L, sql.query(
                        "SELECT COUNT(*) FROM tombstones WHERE scope='conversation' AND entity_id=?", arrayOf(central))
                        .use { it.moveToFirst(); it.getLong(0) })
                }
                assertEquals(0L, sql.query("SELECT COUNT(*) FROM conversations WHERE id=?", arrayOf(central))
                    .use { it.moveToFirst(); it.getLong(0) })
            }
        }
    }
}

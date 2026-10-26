package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionStateEventMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun v5(): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE connector_state_events (id TEXT NOT NULL,connector_id TEXT NOT NULL,connected INTEGER NOT NULL,connected_at_ms INTEGER NOT NULL,PRIMARY KEY(id))")
                    db.execSQL("CREATE INDEX index_connector_state_events_connector_id_connected_at_ms ON connector_state_events(connector_id,connected_at_ms)")
                    db.execSQL("CREATE TABLE storage_meta(key TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO storage_meta VALUES('synthetic-existing-key','preserved')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Explicit migration is required by this fixture")
            }).build())

    private fun migrate(db: SupportSQLiteDatabase) =
        Migrations.ALL.single { it.startVersion == 5 && it.endVersion == 6 }.migrate(db)

    @Test fun legacyRowsRemainIntactWithoutFabricatedOrderingOrProof() {
        val helper = v5()
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO connector_state_events VALUES('legacy-on','calendar',1,42)")
            db.execSQL("INSERT INTO connector_state_events VALUES('legacy-off','calendar',0,42)")
            migrate(db)
            db.query("SELECT id,connector_id,connected,connected_at_ms,store_epoch,sequence,previous_choice,reason,operation_id,parent_operation_id,payload_version FROM connector_state_events ORDER BY id").use { c ->
                for ((id, selected) in listOf("legacy-off" to 0, "legacy-on" to 1)) {
                    assertTrue(c.moveToNext())
                    assertEquals(id, c.getString(0))
                    assertEquals("calendar", c.getString(1))
                    assertEquals(selected, c.getInt(2))
                    assertEquals(42L, c.getLong(3))
                    for (column in 4..10) assertTrue("Legacy metadata column $column must remain NULL", c.isNull(column))
                }
                assertFalse(c.moveToNext())
            }
            db.query("SELECT key,value FROM storage_meta").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("synthetic-existing-key", c.getString(0))
                assertEquals("preserved", c.getString(1))
                assertFalse(c.moveToNext())
            }
        } finally { helper.close() }
    }

    @Test fun migratedTableMatchesFreshRoomColumnsKeysAndIndexes() {
        val helper = v5()
        val fresh = Room.inMemoryDatabaseBuilder(context, JarvysChatDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val migrated = helper.writableDatabase
            migrate(migrated)
            val freshSql = fresh.openHelper.writableDatabase
            assertEquals(6, freshSql.version)
            val migratedInfo = TableInfo.read(migrated, "connector_state_events")
            val freshInfo = TableInfo.read(freshSql, "connector_state_events")
            assertEquals(freshInfo, migratedInfo)
            assertEquals(setOf("store_epoch", "sequence", "previous_choice", "reason", "operation_id",
                "parent_operation_id", "payload_version"), migratedInfo.columns.filterValues { !it.notNull }.keys)
            assertTrue(migratedInfo.foreignKeys.isEmpty())
            val indexes = requireNotNull(migratedInfo.indices)
            assertTrue(indexes.any { it.unique && it.columns == listOf("store_epoch", "sequence") })
            assertTrue(indexes.any { !it.unique && it.columns == listOf("connector_id", "connected_at_ms") })
        } finally { fresh.close(); helper.close() }
    }

    @Test fun epochSequenceIsUniqueWithoutCollapsingLegacyNullMetadata() {
        val helper = v5()
        try {
            val db = helper.writableDatabase
            migrate(db)
            fun insert(id: String, epoch: String?, sequence: Long?) {
                db.execSQL("INSERT INTO connector_state_events(id,connector_id,connected,connected_at_ms,store_epoch,sequence) VALUES(?,'calendar',1,42,?,?)",
                    arrayOf(id, epoch, sequence))
            }
            insert("old-one", null, null)
            insert("old-two", null, null)
            insert("epoch-a:1", "epoch-a", 1L)
            assertThrows(SQLiteConstraintException::class.java) { insert("conflicting-id", "epoch-a", 1L) }
            insert("epoch-b:1", "epoch-b", 1L)
            insert("epoch-a:2", "epoch-a", 2L)
            db.query("SELECT COUNT(*) FROM connector_state_events").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(5, c.getInt(0))
            }
            db.query("SELECT id FROM connector_state_events WHERE store_epoch='epoch-a' AND sequence=1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("epoch-a:1", c.getString(0)); assertFalse(c.moveToNext())
            }
        } finally { helper.close() }
    }
}

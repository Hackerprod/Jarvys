package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationDriverParityTest {
    @Test fun everyRegisteredMigrationHasEquivalentDriverAndSupportEffects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Migration must be explicitly exercised")
                }).build())
        val driver = AndroidSQLiteDriver().open(":memory:")
        try {
            val support = helper.writableDatabase
            fun both(sql: String) { support.execSQL(sql); driver.prepare(sql).use { it.step() } }
            // The pre-v4 migration dependencies, matching the existing ownership-migration fixture.
            both("CREATE TABLE memory_entries(id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,narration TEXT NOT NULL,text TEXT NOT NULL,title TEXT,topic_key TEXT)")
            both("CREATE TABLE memory_evidence(entry_id TEXT NOT NULL,ordinal INTEGER NOT NULL,ref_kind TEXT NOT NULL,ref_conversation_id TEXT,ref_id TEXT NOT NULL,action_id TEXT,attempt_id TEXT,at_ms INTEGER NOT NULL,tool_name TEXT,outcome TEXT,authorization TEXT,facts_json TEXT NOT NULL,connector TEXT,PRIMARY KEY(entry_id,ordinal))")
            both("CREATE TABLE messages(conversation_id TEXT NOT NULL,message_id TEXT NOT NULL,PRIMARY KEY(conversation_id,message_id))")
            both("CREATE TABLE compactions(conversation_id TEXT NOT NULL,compaction_id TEXT NOT NULL,PRIMARY KEY(conversation_id,compaction_id))")
            both("INSERT INTO memory_entries VALUES('entry','episode','done','Synthetic preserved text',NULL,NULL)")
            both("INSERT INTO memory_evidence VALUES('entry',0,'tool_call','central','call',NULL,NULL,42,'tool','started',NULL,'{}',NULL)")
            assertEquals(listOf(3 to 4, 4 to 5, 5 to 6), Migrations.ALL.map { it.startVersion to it.endVersion })
            for (migration in Migrations.ALL) {
                migration.migrate(support)
                migration.migrate(driver)
                assertEquals("Schema differs after ${migration.startVersion}→${migration.endVersion}",
                    supportRows(support, schemaQuery), driverRows(driver, schemaQuery))
                assertEquals(supportRows(support, "SELECT id||':'||narration||':'||text FROM memory_entries"),
                    driverRows(driver, "SELECT id||':'||narration||':'||text FROM memory_entries"))
                assertEquals(supportRows(support, "SELECT ref_id||':'||outcome FROM memory_evidence"),
                    driverRows(driver, "SELECT ref_id||':'||outcome FROM memory_evidence"))
                if (migration.endVersion == 4) {
                    both("INSERT INTO connector_state_events VALUES('legacy','calendar',0,42)")
                }
            }
            assertEquals(listOf("call:uncertain"), driverRows(driver, "SELECT ref_id||':'||outcome FROM memory_evidence"))
            assertEquals(listOf("entry:none:Synthetic preserved text"),
                driverRows(driver, "SELECT id||':'||narration||':'||text FROM memory_entries"))
            val retained = "SELECT id||':'||connector_id||':'||connected||':'||connected_at_ms FROM connector_state_events WHERE store_epoch IS NULL AND sequence IS NULL AND previous_choice IS NULL AND reason IS NULL AND operation_id IS NULL AND parent_operation_id IS NULL AND payload_version IS NULL"
            assertEquals(listOf("legacy:calendar:0:42"), driverRows(driver, retained))
            assertEquals(supportRows(support, retained), driverRows(driver, retained))
        } finally { driver.close(); helper.close() }
    }

    private val schemaQuery = "SELECT type||':'||name||':'||tbl_name||':'||COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name!='android_metadata' ORDER BY type,name"
    private fun supportRows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun driverRows(db: SQLiteConnection, sql: String): List<String> = db.prepare(sql).use { statement ->
        buildList { while (statement.step()) add(statement.getText(0)) }
    }
}

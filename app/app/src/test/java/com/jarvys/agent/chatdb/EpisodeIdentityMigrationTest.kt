package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
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
class EpisodeIdentityMigrationTest {
    @Test fun v5PreservesEvidenceAndDoesNotInventOwnershipForOldPendingRows() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()).callback(object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE memory_entries(id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,narration TEXT NOT NULL,text TEXT NOT NULL,title TEXT,topic_key TEXT)")
                db.execSQL("CREATE TABLE memory_evidence(entry_id TEXT NOT NULL,ordinal INTEGER NOT NULL,ref_kind TEXT NOT NULL,ref_conversation_id TEXT,ref_id TEXT NOT NULL,action_id TEXT,attempt_id TEXT,at_ms INTEGER NOT NULL,tool_name TEXT,outcome TEXT,authorization TEXT,facts_json TEXT NOT NULL,connector TEXT,PRIMARY KEY(entry_id,ordinal))")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
        }).build())
        try {
            val db = helper.writableDatabase
            val outcomes = listOf("intent", "started", "ok", "error", "uncertain", "not_executed", "deferred")
            outcomes.forEachIndexed { index, outcome ->
                db.execSQL("INSERT INTO memory_entries(id,kind,narration,text,title) VALUES(?,'episode','done','User corrected text','User corrected title')", arrayOf("entry-$index"))
                db.execSQL("INSERT INTO memory_evidence VALUES(?,0,'tool_call','central',?,NULL,NULL,123,'tool',?,'allow','{}',NULL)",
                    arrayOf("entry-$index", "call-$index", outcome))
            }
            Migrations.ALL.single { it.startVersion == 4 && it.endVersion == 5 }.migrate(db)
            db.query("SELECT ref_id,outcome,at_ms,source_batch_id,run_id,process_id FROM memory_evidence ORDER BY entry_id").use { c ->
                outcomes.forEachIndexed { index, outcome ->
                    assertTrue(c.moveToNext())
                    assertEquals("call-$index", c.getString(0))
                    assertEquals(if (outcome in listOf("intent", "started")) "uncertain" else outcome, c.getString(1))
                    assertEquals(123L, c.getLong(2))
                    assertTrue(c.isNull(3) && c.isNull(4) && c.isNull(5))
                }
                assertFalse(c.moveToNext())
            }
            db.query("SELECT narration,text,title FROM memory_entries ORDER BY id").use { c ->
                outcomes.forEach { outcome ->
                    assertTrue(c.moveToNext())
                    assertEquals(if (outcome in listOf("intent", "started")) "none" else "done", c.getString(0))
                    assertEquals("User corrected text", c.getString(1))
                    assertEquals("User corrected title", c.getString(2))
                }
                assertFalse(c.moveToNext())
            }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_memory_entries_topic_key'").use { assertTrue(it.moveToFirst()) }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_memory_evidence_batch'").use { assertTrue(it.moveToFirst()) }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='mission_episode_inputs'").use { assertTrue(it.moveToFirst()) }
        } finally { helper.close() }
    }
}

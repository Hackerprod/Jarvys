package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
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
class CodingJobMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun oldDatabase(): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).callback(object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE storage_meta(key TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
                db.execSQL("INSERT INTO storage_meta VALUES('synthetic-preserved','original')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Explicit fixture migration required")
        }).build())

    @Test fun additiveMigrationMatchesFreshRoomAndDoesNotFabricateJobsOrGeneration() {
        val helper = oldDatabase()
        val fresh = Room.inMemoryDatabaseBuilder(context, JarvysChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val db = helper.writableDatabase
            Migrations.ALL.single { it.startVersion == 6 && it.endVersion == 7 }.migrate(db)
            val freshSql = fresh.openHelper.writableDatabase
            assertEquals(7, freshSql.version)
            for (table in listOf("coding_job_bindings", "coding_job_observations")) {
                assertEquals(TableInfo.read(freshSql, table), TableInfo.read(db, table))
                db.query("SELECT count(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            db.query("SELECT key,value FROM storage_meta").use {
                assertTrue(it.moveToFirst()); assertEquals("synthetic-preserved", it.getString(0)); assertEquals("original", it.getString(1)); assertFalse(it.moveToNext())
            }
            assertTrue(TableInfo.read(db, "coding_job_bindings").foreignKeys.isEmpty())
            val dependencies = TableInfo.read(db, "coding_job_observations").foreignKeys
            assertEquals(setOf("coding_job_bindings"), dependencies.map { it.referenceTable }.toSet())
        } finally { fresh.close(); helper.close() }
    }

    @Test fun submissionAndRevisionIdentityConstraintsAreEnforcedWithoutCentralRows() {
        val helper = oldDatabase()
        try {
            val db = helper.writableDatabase
            Migrations.ALL.single { it.startVersion == 6 && it.endVersion == 7 }.migrate(db)
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO coding_job_bindings VALUES('job','submission','generation','synthetic-binding')")
            assertThrows(Exception::class.java) { db.execSQL("INSERT INTO coding_job_bindings VALUES('other','submission','generation','synthetic-binding')") }
            db.execSQL("INSERT INTO coding_job_observations VALUES('job',1,'SUBMISSION_ACCEPTED','synthetic-evidence','hash',?)", arrayOf(byteArrayOf(1, 2, 3)))
            assertThrows(Exception::class.java) {
                db.execSQL("INSERT INTO coding_job_observations VALUES('job',1,'SUBMISSION_ACCEPTED','other','hash',?)", arrayOf(byteArrayOf(4)))
            }
            assertThrows(Exception::class.java) { db.execSQL("DELETE FROM coding_job_bindings WHERE job_id='job'") }
            db.query("SELECT payload_bytes FROM coding_job_observations WHERE job_id='job'").use {
                assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0))
            }
        } finally { helper.close() }
    }
}

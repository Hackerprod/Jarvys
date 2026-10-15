package com.jarvys.agent.chatdb

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolates Android/Robolectric SQLite WAL concurrency from Room and chatdb's connection adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WalConcurrencyProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun independentAndroidConnectionsReadDuringImmediateWriterTransaction() {
        val file = temporary.newFile("wal-probe.db")
        val writerDatabase = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        assertTrue("the probe requires WAL mode", writerDatabase.enableWriteAheadLogging())
        writerDatabase.execSQL("CREATE TABLE wal_probe (value INTEGER NOT NULL)")
        writerDatabase.execSQL("INSERT INTO wal_probe(value) VALUES (1)")
        val readerDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val journalMode = readerDatabase.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            assertTrue("journal_mode returns a row", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("wal", journalMode.lowercase())

        val writerExecutor = Executors.newSingleThreadExecutor()
        val readerExecutor = Executors.newSingleThreadExecutor()
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        var writeFuture: java.util.concurrent.Future<*>? = null
        try {
            writeFuture = writerExecutor.submit {
                // SQLiteDatabase.beginTransactionNonExclusive() uses SQLite's BEGIN IMMEDIATE mode.
                writerDatabase.beginTransactionNonExclusive()
                try {
                    writerDatabase.execSQL("UPDATE wal_probe SET value=2")
                    writerEntered.countDown()
                    check(releaseWriter.await(10, TimeUnit.SECONDS)) { "timed out waiting to release the WAL writer" }
                    writerDatabase.setTransactionSuccessful()
                } finally {
                    writerDatabase.endTransaction()
                }
            }
            assertTrue("writer has entered BEGIN IMMEDIATE and updated the row", writerEntered.await(10, TimeUnit.SECONDS))

            val concurrentRead = readerExecutor.submit(Callable {
                readerDatabase.rawQuery("SELECT value FROM wal_probe", null).use { cursor ->
                    assertTrue("SELECT returns the previously committed row", cursor.moveToFirst())
                    cursor.getInt(0)
                }
            })
            assertEquals("WAL reader sees the last committed value while the writer is open", 1, concurrentRead.get(3, TimeUnit.SECONDS))

            releaseWriter.countDown()
            writeFuture.get(10, TimeUnit.SECONDS)
            val committedValue = readerDatabase.rawQuery("SELECT value FROM wal_probe", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(2, committedValue)
        } finally {
            releaseWriter.countDown()
            runCatching { writeFuture?.get(10, TimeUnit.SECONDS) }
            writerExecutor.shutdownNow()
            readerExecutor.shutdownNow()
            writerExecutor.awaitTermination(10, TimeUnit.SECONDS)
            readerExecutor.awaitTermination(10, TimeUnit.SECONDS)
            readerDatabase.close()
            writerDatabase.close()
        }
    }
}

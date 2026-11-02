package com.jarvys.agent.chatdb

import android.app.Application
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.ChatTestStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
class CentralSubmissionSchemaCompatibilityTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context get()=RuntimeEnvironment.getApplication()
    private val v3Identity get()=JarvysChatDatabaseV3_Impl().expectedFreshIdentity()
    private fun fixture(version:Int)=JSONObject(javaClass.getResourceAsStream(if(version==1) "/room_v98_v1.json" else "/room_v99_v2.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
    private fun file(name:String)=File(chat.newFilesDirectory(name),"current.db")
    private fun open(file:File)=BundledSQLiteDriver().open(file.absolutePath)
    private fun scalar(db:SQLiteConnection,sql:String)=DriverChatSqlDatabase(db).query(sql).use { assertTrue(it.moveToFirst());it.getLong(0) }
    private fun create(file:File,version:Int,populate:Boolean=true) {
        open(file).use { db ->
            val f=fixture(version);val entities=f.getJSONArray("entities")
            db.execSQL("PRAGMA foreign_keys=OFF")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val name=e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",name))
                e.optJSONArray("indices")?.let { indexes ->for(j in 0 until indexes.length())db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",name)) }
                if(populate) {
                    val fields=e.getJSONArray("fields")
                    val names=(0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                    val values=(0 until fields.length()).map { when(fields.getJSONObject(it).getString("affinity")) { "INTEGER"->1L;"REAL"->1.25;"BLOB"->byteArrayOf(0,(-40).toByte(),65,0);else->"retained" } }.toTypedArray()
                    DriverChatSqlDatabase(db).execSQL("INSERT INTO `$name` ("+names.joinToString(",") {"`$it`"}+") VALUES ("+names.joinToString(","){"?"}+")",values)
                }
            }
            val setup=f.getJSONArray("setupQueries");for(i in 0 until setup.length())db.execSQL(setup.getString(i))
            db.execSQL("PRAGMA user_version=$version")
        }
    }
    private fun rows(db:SQLiteConnection,version:Int):Map<String,List<List<String>>> {
        val entities=fixture(version).getJSONArray("entities");val sql=DriverChatSqlDatabase(db)
        return (0 until entities.length()).associate { i ->
            val e=entities.getJSONObject(i);val fields=e.getJSONArray("fields")
            val names=(0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            e.getString("tableName") to sql.query("SELECT "+names.joinToString(","){"typeof(`$it`),hex(`$it`)"}+" FROM `"+e.getString("tableName")+"`").use { c -> buildList {while(c.moveToNext())add((0 until c.columnCount).map {c.getString(it)})} }
        }
    }
    @Test fun pinnedV1AndV2RowsSurviveAtomicV3UpgradeAndReopen() {
        assertEquals(RoomCentralSubmissionSchema.V2_IDENTITY,fixture(2).getString("identityHash"))
        for(version in 1..2) {
            val file=file("submission-populated-$version");create(file,version)
            val before=open(file).use {rows(it,version)}
            repeat(2) {open(file).use { db ->
                RoomCentralSubmissionUpgrade.upgrade(db,v3Identity)
                assertEquals(3,RoomTreeProvenanceSchema.validate(db,v3Identity,false))
                assertEquals(before,rows(db,version));assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM central_turn_submissions"))
            }}
        }
    }
    @Test fun offProviderKeepsGenuineV2UsableAndExistingV1Behavior() {
        for(version in 1..2) {
            val file=file("submission-off-$version");create(file,version,false)
            val room=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,false)
            try {assertFalse(room is JarvysChatDatabaseV3)} finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(room)}
            open(file).use { db ->assertEquals(2,RoomTreeProvenanceSchema.validate(db,v3Identity,false));assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE name='central_turn_submissions'"))}
        }
    }
    @Test fun ownedV3UpgradeThenOffReopenUsesSameNativeDaosWithoutSchemaWrites() {
        val file=file("submission-provider-v3");create(file,2,false)
        var room=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true)
        try {assertTrue(room is JarvysChatDatabaseV3)} finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(room)}
        val schema=open(file).use {scalar(it,"PRAGMA schema_version")}
        room=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,false,false)
        try {assertTrue(room is JarvysChatDatabaseV3)} finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(room)}
        open(file).use {assertEquals(schema,scalar(it,"PRAGMA schema_version"));assertEquals(3,RoomTreeProvenanceSchema.validate(it,v3Identity,false))}
    }
    @Test fun interruptedDdlRollsBackOriginalIdentityVersionAndEveryRow() {
        for(version in 1..2) {
            val file=file("submission-interrupt-$version");create(file,version)
            open(file).use { db ->
                val before=rows(db,version)
                val fault=object:SQLiteConnection by db {
                    override fun prepare(sql:String):SQLiteStatement {
                        val statement=db.prepare(sql)
                        if(sql!=RoomCentralSubmissionSchema.CREATE_SQL)return statement
                        return object:SQLiteStatement by statement {override fun step():Boolean {statement.step();error("after submission DDL")}}
                    }
                }
                assertEquals("after submission DDL",assertThrows(IllegalStateException::class.java){RoomCentralSubmissionUpgrade.upgrade(fault,v3Identity)}.message)
                assertEquals(version,RoomTreeProvenanceSchema.validate(db,v3Identity,true));assertEquals(before,rows(db,version))
            }
        }
    }
    @Test fun nativeFullPreservesV2ThenAllowsExplicitRetry() {
        val file=file("submission-full");create(file,2)
        open(file).use {db ->
            val before=rows(db,2);val pages=scalar(db,"PRAGMA page_count")
            assertEquals(pages,scalar(db,"PRAGMA max_page_count=$pages"))
            val error=runCatching {RoomCentralSubmissionUpgrade.upgrade(db,v3Identity)}.exceptionOrNull()
            assertTrue("Expected SQLITE_FULL: $error",generateSequence(error){it.cause}.any {it is android.database.SQLException && Regex("Error code: 13(?:, message: [\\s\\S]*)?").matches(it.message.orEmpty())})
            assertEquals(2,RoomTreeProvenanceSchema.validate(db,v3Identity,true));assertEquals(before,rows(db,2))
            scalar(db,"PRAGMA max_page_count=${pages+100}");RoomCentralSubmissionUpgrade.upgrade(db,v3Identity);assertEquals(before,rows(db,2))
        }
    }
    @Test fun prospectiveV3ShapeFailureCannotPublishIdentityOrVersion() {
        val file=file("submission-prospective");create(file,2)
        open(file).use {db ->
            val before=rows(db,2)
            val fault=object:SQLiteConnection by db {
                override fun prepare(sql:String):SQLiteStatement {
                    assertFalse("Identity publication before full prospective validation",sql.startsWith("UPDATE room_master_table"))
                    return db.prepare(if(sql==RoomCentralSubmissionSchema.CREATE_SQL) sql.replace("`revision` INTEGER","`revision` TEXT") else sql)
                }
            }
            assertThrows(RoomCurrentSchemaUnavailable::class.java){RoomCentralSubmissionUpgrade.upgrade(fault,v3Identity)}
            assertEquals(2,RoomTreeProvenanceSchema.validate(db,v3Identity,true));assertEquals(before,rows(db,2))
        }
    }
    @Test fun registeredV2ReaderPreventsUpgradeUntilPhysicalClose() {
        val file=file("submission-registered-reader");create(file,2,false)
        val old=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,false)
        try {
            assertThrows(IllegalStateException::class.java){JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true)}
            open(file).use {assertEquals(2,RoomTreeProvenanceSchema.validate(it,v3Identity,false))}
        } finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(old)}
        val upgraded=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true)
        try {assertTrue(upgraded is JarvysChatDatabaseV3)} finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(upgraded)}
    }
    @Test fun concurrentExplicitV3OpenersPublishOnlyOneAdditiveSchema() {
        val file=file("submission-concurrent");create(file,2,false)
        open(file).use {JarvysChatDatabaseProvider.DatabaseTriggers.create(it,context,false)}
        val before=open(file).use {scalar(it,"PRAGMA schema_version")}
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        val handles=java.util.Collections.synchronizedList(mutableListOf<JarvysChatDatabase>())
        try {
            val start=java.util.concurrent.CountDownLatch(1)
            val calls=(1..2).map {pool.submit<JarvysChatDatabase>{start.await();JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true).also {handles.add(it)}}}
            start.countDown();calls.forEach {it.get(30,java.util.concurrent.TimeUnit.SECONDS)}
            open(file).use {assertEquals(3,RoomTreeProvenanceSchema.validate(it,v3Identity,false));assertEquals(before+3,scalar(it,"PRAGMA schema_version"))}
        } finally {handles.forEach {JarvysChatDatabaseProvider.closeDatabaseFileForTests(it)};pool.shutdownNow()}
    }
    @Test fun hiddenExecutableObjectsAndFutureVersionsRejectWithoutMutation() {
        for(mutation in listOf("CREATE TRIGGER android_metadata AFTER UPDATE ON room_master_table BEGIN DELETE FROM storage_meta; END","CREATE TRIGGER sqliteXreview AFTER UPDATE ON room_master_table BEGIN DELETE FROM storage_meta; END","PRAGMA user_version=4")) {
            val file=file("submission-reject-${mutation.hashCode()}");create(file,2)
            open(file).use {it.execSQL(mutation)};val before=file.readBytes()
            open(file).use {db ->assertThrows(RoomCurrentSchemaUnavailable::class.java){RoomCentralSubmissionUpgrade.upgrade(db,v3Identity)}}
            assertArrayEquals(before,file.readBytes())
        }
    }
}

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
    @Test fun cachedNativeWalReaderRetainsItsV2SnapshotThenObservesExactV3WithoutLostRows() {
        val file=file("submission-cached-wal");create(file,2)
        open(file).use { writer ->
            writer.execSQL("PRAGMA journal_mode=WAL")
            val before=rows(writer,2)
            open(file).use { reader ->
                reader.execSQL("BEGIN")
                assertEquals(2L,scalar(reader,"PRAGMA user_version"))
                reader.prepare("SELECT COUNT(*) FROM messages").use { cached ->
                    assertTrue(cached.step());val count=cached.getLong(0);cached.reset()
                    RoomCentralSubmissionUpgrade.upgrade(writer,v3Identity)
                    assertEquals(2L,scalar(reader,"PRAGMA user_version"));assertEquals(before,rows(reader,2))
                    assertTrue(cached.step());assertEquals(count,cached.getLong(0));cached.reset()
                    reader.execSQL("COMMIT")
                    assertTrue(cached.step());assertEquals(count,cached.getLong(0))
                    assertEquals(3,RoomTreeProvenanceSchema.validate(reader,v3Identity,false));assertEquals(before,rows(reader,2))
                }
            }
        }
    }
    private fun isolatedContext(name:String):android.content.Context {
        val files=File(chat.newFilesDirectory(name),"files");check(files.mkdirs());chat.localRunStore(files)
        return object:android.content.ContextWrapper(context) {
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir():File=files
            override fun getApplicationInfo():android.content.pm.ApplicationInfo=android.content.pm.ApplicationInfo(super.getApplicationInfo()).also {it.dataDir=files.parentFile!!.absolutePath}
        }
    }
    private fun seedValidNativeV2(ownerContext:android.content.Context):Pair<File,String> {
        val file=File(com.jarvys.agent.StorageGeneration(ownerContext).chatRoot(),"agent.db")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs());create(file,2,false)
        val room=JarvysChatDatabaseProvider.openDatabaseFileForTests(ownerContext,file,true,false)
        val message=try {
            kotlinx.coroutines.runBlocking {
                val result=ConversationRepository(room).appendMessage(AppendMessageInput(com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION,"user",listOf(MessagePartInput("text","Valid native retained USER \u0000 Ω"))))
                (result as ChatDbResult.Success).value
            }
        } finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(room)}
        return file to message.messageId
    }
    @Test fun populatedNativeV2ProviderWithAllAuxiliaryObjectsUpgradesAndReopensExactV3() {
        val owner=isolatedContext("native-v2-to-v3");val (file,user)=seedValidNativeV2(owner)
        val before=open(file).use { db ->
            assertFalse(DriverChatSqlDatabase(db).query("PRAGMA foreign_key_check").use {it.moveToFirst()})
            rows(db,2)
        }
        fun objects()=open(file).use {db ->DriverChatSqlDatabase(db).query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use {c ->buildList {while(c.moveToNext())add((0 until c.columnCount).map {if(c.isNull(it))null else c.getString(it)})}}}
        val oldObjects=objects();assertTrue(oldObjects.any {it[1]=="idx_memory_entries_topic_active"})
        val upgraded=JarvysChatDatabaseProvider.openDatabaseFileForTests(owner,file,true,true)
        try {
            assertTrue(upgraded is JarvysChatDatabaseV3)
            assertEquals("user",kotlinx.coroutines.runBlocking {upgraded.messageDao().getById(com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION,user)}!!.role)
        } finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(upgraded)}
        assertEquals(oldObjects,objects().filterNot {it[2]=="central_turn_submissions"})
        open(file).use {db ->assertEquals(before,rows(db,2));assertFalse(DriverChatSqlDatabase(db).query("PRAGMA foreign_key_check").use {it.moveToFirst()});assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM central_turn_submissions"))}
        val reopened=JarvysChatDatabaseProvider.getInstance(owner)
        try {assertTrue(reopened is JarvysChatDatabaseV3)} finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(reopened)}
    }
    @Test fun actualDoctorObservationAndMemoryExportReadUpgradedV3WithoutQueueOrMemoryWrites() {
        val owner=isolatedContext("v3-doctor-export");val (file,user)=seedValidNativeV2(owner)
        val upgraded=JarvysChatDatabaseProvider.openDatabaseFileForTests(owner,file,true,true)
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(upgraded)
        val db=JarvysChatDatabaseProvider.getInstance(owner)
        try {
            val before=open(file).use {rows(it,2)}
            val count=JarvysChatDatabaseProvider.observeOpenForDoctor(owner,com.jarvys.agent.CancellationToken.cancellable()) { sql,requireCurrent ->
                val n=sql.query("SELECT COUNT(*) FROM messages WHERE message_id=?",arrayOf(user)).use {check(it.moveToFirst());it.getLong(0)}
                assertEquals(0L,sql.query("SELECT COUNT(*) FROM central_turn_submissions").use {check(it.moveToFirst());it.getLong(0)})
                requireCurrent();n
            }
            assertEquals(1L,count)
            com.jarvys.agent.workspaces.WorkspaceRegistry(owner).use {it.createWorkspace("export-owner","Export owner")}
            val namespace=MemoryNamespace.resolve(owner,MemoryNamespace.CENTRAL,null,null,true,true)
            val output=java.io.ByteArrayOutputStream()
            val exported=kotlinx.coroutines.runBlocking {MemoryExportRepository(db,owner).render(namespace,com.jarvys.agent.MemoryExportOptions(),"2026-11-02T00:00:00.000Z",com.jarvys.agent.CancellationToken.cancellable()){output}}
            assertTrue(output.size()>0);assertEquals(output.size().toLong(),exported.sizeBytes)
            assertFalse(output.toString("UTF-8").contains("central_turn_submissions"))
            open(file).use {assertEquals(before,rows(it,2));assertEquals(0L,scalar(it,"SELECT COUNT(*) FROM central_turn_submissions"));assertEquals(3,RoomTreeProvenanceSchema.validate(it,v3Identity,false))}
        } finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(db)}
    }
    @Test fun independentlyUpgradedMixedCapabilitiesRemainReadOnlyAndAdmissionOff() {
        for(roomReady in listOf(false,true)) {
            context.deleteDatabase(com.jarvys.agent.workspaces.WorkspaceRegistry.DATABASE_NAME)
            val owner=isolatedContext("mixed-capability-$roomReady");val (file,_)=seedValidNativeV2(owner)
            if(roomReady) {val db=JarvysChatDatabaseProvider.openDatabaseFileForTests(owner,file,true,true);JarvysChatDatabaseProvider.closeDatabaseFileForTests(db)}
            com.jarvys.agent.workspaces.WorkspaceRegistry(owner).use {registry ->
                registry.upgradeTreeSchema();if(!roomReady)registry.upgradeCentralResumeSchema()
                val registryBefore=registry.schemaVersion()
                val roomBefore=open(file).use {rows(it,2)}
                assertEquals(roomReady,JarvysChatDatabaseProvider.currentSubmissionSchemaReady(owner))
                assertEquals(!roomReady,registry.schemaVersion()==3)
                if(!com.jarvys.agent.CentralAvailabilityPolicy.admissionEnabled() || roomReady)
                    assertThrows(IllegalStateException::class.java) {com.jarvys.agent.CentralAvailabilityPolicy.requireRegistrySchemaReady(owner)}
                if(!roomReady) {
                    val room=JarvysChatDatabaseProvider.openDatabaseFileForTests(owner,file,true,false)
                    try {
                        val writer=ConversationModelTurnWriter(room,ConversationRepository(room),ModelTranscriptRepository(room))
                        val input=AppendMessageInput(com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION,"user",listOf(MessagePartInput("text","must not append"),MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(emptyList()))),stableId="missing-room-capability")
                        assertThrows(IllegalStateException::class.java) {kotlinx.coroutines.runBlocking {writer.appendPreparedCentralSubmission(input,"missing-room-capability","process")}}
                    } finally {JarvysChatDatabaseProvider.closeDatabaseFileForTests(room)}
                }
                assertEquals(registryBefore,registry.schemaVersion())
                open(file).use {assertEquals(if(roomReady)3L else 2L,scalar(it,"PRAGMA user_version"));assertEquals(roomBefore,rows(it,2))}
            }
            context.deleteDatabase(com.jarvys.agent.workspaces.WorkspaceRegistry.DATABASE_NAME)
        }
    }
}

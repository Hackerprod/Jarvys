package com.jarvys.agent.chatdb

import android.app.Application
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.ChatTestStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** New current-data migration proof. Does not enroll old data or restore legacy storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
class RoomTreeProvenanceCompatibilityTest {
    @get:Rule val chat = ChatTestStore(registerApplicationFilesDirectory=true)
    private val context get()=RuntimeEnvironment.getApplication()
    private val fixture get()=JSONObject(javaClass.getResourceAsStream("/room_v98_v1.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
    private val v2Identity get()=JarvysChatDatabase_Impl().expectedFreshIdentity()
    private fun file(name:String)=File(chat.newFilesDirectory(name),"current.db")
    private fun open(file:File)=BundledSQLiteDriver().open(file.absolutePath)
    private fun raw(connection:SQLiteConnection)=DriverChatSqlDatabase(connection)
    private fun scalar(connection:SQLiteConnection,sql:String):Long=raw(connection).query(sql).use { assertTrue(it.moveToFirst());it.getLong(0) }
    private fun createV1(file:File,populate:Boolean=true) {
        open(file).use { db ->
            val f=fixture;val entities=f.getJSONArray("entities")
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL("BEGIN IMMEDIATE")
            try {
                for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i);val name=e.getString("tableName")
                    db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",name))
                    val indexes=e.optJSONArray("indices")
                    if(indexes!=null)for(k in 0 until indexes.length())db.execSQL(indexes.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}",name))
                }
                if(populate)for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i);val fields=e.getJSONArray("fields")
                    val names=(0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                    val values=(0 until fields.length()).map { n ->
                        val field=fields.getJSONObject(n)
                        // One row in every v98 entity; legal storage classes and opaque retained values.
                        when(field.getString("affinity")) {
                            "INTEGER" -> 1L
                            "REAL" -> 1.25
                            "BLOB" -> byteArrayOf(0,0,0,(-40).toByte(),65,0)
                            else -> "row"
                        }
                    }.toTypedArray()
                    raw(db).execSQL("INSERT INTO `"+e.getString("tableName")+"` ("+names.joinToString(",") { "`$it`" }+") VALUES ("+names.joinToString(",") { "?" }+")",values)
                }
                val setup=f.getJSONArray("setupQueries")
                for(i in 0 until setup.length())db.execSQL(setup.getString(i))
                db.execSQL("PRAGMA user_version=1")
                db.execSQL("COMMIT")
            }catch(failure:Throwable){runCatching { db.execSQL("ROLLBACK") }.exceptionOrNull()?.let(failure::addSuppressed);throw failure}
        }
    }
    private fun rows(db:SQLiteConnection):Map<String,List<List<String>>> {
        val entities=fixture.getJSONArray("entities")
        return (0 until entities.length()).associate { index ->
            val e=entities.getJSONObject(index);val table=e.getString("tableName");val fields=e.getJSONArray("fields")
            val names=(0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            table to raw(db).query("SELECT "+names.joinToString(",") { "typeof(`$it`),hex(`$it`)" }+" FROM `$table`").use { c ->
                buildList { while(c.moveToNext())add((0 until c.columnCount).map { c.getString(it) }) }.sortedBy { it.joinToString("|") }
            }
        }
    }
    private fun upgrade(connection:SQLiteConnection,failAfterDdl:Boolean=false) {
        val actual = if(!failAfterDdl)connection else object : SQLiteConnection by connection {
            override fun prepare(sql:String):androidx.sqlite.SQLiteStatement {
                val statement=connection.prepare(sql)
                if(sql!=RoomTreeProvenanceSchema.CREATE_SQL)return statement
                return object : androidx.sqlite.SQLiteStatement by statement {
                    override fun step():Boolean {
                        val value=statement.step()
                        assertEquals(1L,scalar(connection,"PRAGMA user_version"))
                        assertEquals(RoomTreeProvenanceSchema.V1_IDENTITY,raw(connection).query("SELECT identity_hash FROM room_master_table WHERE id=42").use { assertTrue(it.moveToFirst());it.getString(0) })
                        error("injected interruption after additive DDL before identity/version change")
                    }
                }
            }
        }
        RoomTreeProvenanceUpgrade.upgrade(actual,v2Identity)
    }
    @Test fun pinnedV1AllRowsConservedAcrossAtomicUpgradeAndRepeatedReopen() {
        val file=file("room-v1-populated");createV1(file)
        val before=open(file).use { db ->
            assertEquals(RoomTreeProvenanceSchema.V1_IDENTITY,fixture.getString("identityHash"))
            assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true));rows(db)
        }
        open(file).use { db ->upgrade(db);assertEquals(before,rows(db));assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM crew_message_input_provenance")) }
        repeat(2) { open(file).use { db ->upgrade(db);assertEquals(2,RoomTreeProvenanceSchema.validate(db,v2Identity,false));assertEquals(before,rows(db)) } }
    }
    @Test fun interruptionRollsBackDdlIdentityVersionAndEveryOldRow() {
        val file=file("room-v1-interrupt");createV1(file)
        val before=open(file).use(::rows)
        open(file).use { db ->assertThrows(IllegalStateException::class.java){upgrade(db,true)};assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true));assertEquals(before,rows(db)) }
        open(file).use { db ->assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true));upgrade(db);assertEquals(before,rows(db)) }
    }
    @Test fun prospectiveStructureIsValidatedBeforePublishingAnyV2Identity() {
        val file=file("room-v1-prospective");createV1(file)
        open(file).use { db ->
            val before=rows(db)
            val altered=object:SQLiteConnection by db {
                override fun prepare(sql:String):androidx.sqlite.SQLiteStatement {
                    assertFalse("No identity update may precede prospective structure validation",sql.startsWith("UPDATE room_master_table"))
                    return db.prepare(if(sql==RoomTreeProvenanceSchema.CREATE_SQL)
                        sql.replace("`assignment_revision` INTEGER","`assignment_revision` TEXT") else sql)
                }
            }
            assertThrows(RoomCurrentSchemaUnavailable::class.java){RoomTreeProvenanceUpgrade.upgrade(altered,v2Identity)}
            assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true));assertEquals(before,rows(db))
        }
    }

    @Test fun identityAndVersionPublicationFailuresRollbackTheSameNativeTransaction() {
        for(fault in listOf("UPDATE room_master_table","PRAGMA user_version=2")) {
            val file=file("room-v1-publication-"+fault.hashCode());createV1(file)
            open(file).use { db ->
                val before=rows(db)
                val interrupted=object:SQLiteConnection by db {
                    override fun prepare(sql:String):androidx.sqlite.SQLiteStatement {
                        val statement=db.prepare(sql)
                        if(!sql.startsWith(fault))return statement
                        return object:androidx.sqlite.SQLiteStatement by statement {
                            override fun step():Boolean {statement.step();error("injected publication failure")}
                        }
                    }
                }
                val failure=assertThrows(IllegalStateException::class.java){RoomTreeProvenanceUpgrade.upgrade(interrupted,v2Identity)}
                assertEquals("injected publication failure",failure.message)
                assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true));assertEquals(before,rows(db))
            }
        }
    }

    @Test fun nativeSqliteFullCannotPublishPartialSchemaAndCanRetryAfterCapacityRestored() {
        val file=file("room-v1-full");createV1(file)
        val before=open(file).use(::rows)
        open(file).use { db ->
            val pages=scalar(db,"PRAGMA page_count")
            assertEquals(pages,scalar(db,"PRAGMA max_page_count=$pages"))
            val failure=runCatching { upgrade(db) }.exceptionOrNull()
            val chain=generateSequence(failure) { it.cause }.toList()
            assertTrue("Expected native SQLITE_FULL, got " + chain.map { it.javaClass.name+":"+it.message },
                chain.any { it is android.database.SQLException && Regex("Error code: 13(?:, message: [\\s\\S]*)?").matches(it.message.orEmpty()) })
            assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
            assertEquals(before,rows(db))
            assertTrue(scalar(db,"PRAGMA max_page_count="+(pages+100))>pages)
            upgrade(db)
            assertEquals(before,rows(db))
        }
        open(file).use { assertEquals(2,RoomTreeProvenanceSchema.validate(it,v2Identity,false)) }
    }
    @Test fun nativeV98AuxiliaryObjectsAndDataAreConservedWithoutRebuild() {
        val file=file("room-v1-native-auxiliary");createV1(file,false)
        open(file).use { db ->
            JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false)
            assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
            fun objects()=raw(db).query("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name<>'room_master_table' ORDER BY type,name").use { c ->
                buildList { while(c.moveToNext())add(listOf(c.getString(0),c.getString(1),c.getString(2))) }
            }
            fun allRows():Map<String,List<List<String>>> {
                val names=raw(db).query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'room_master_table'").use { c ->
                    buildList { while(c.moveToNext())add(c.getString(0)) }
                }
                return names.associateWith { table ->
                    val columns=raw(db).query("PRAGMA table_info(`$table`)").use { c ->buildList { while(c.moveToNext())add(c.getString(1)) } }
                    raw(db).query("SELECT "+columns.joinToString(",") { "typeof(`$it`),hex(`$it`)" }+" FROM `$table`").use { c ->
                        buildList { while(c.moveToNext())add((0 until c.columnCount).map { c.getString(it) }) }.sortedBy { it.joinToString("|") }
                    }
                }
            }
            val beforeObjects=objects();val beforeRows=allRows()
            assertTrue(beforeObjects.any { it[1]=="idx_memory_entries_topic_active" })
            upgrade(db)
            assertEquals(2,RoomTreeProvenanceSchema.validate(db,v2Identity,false))
            assertEquals(beforeObjects,objects().filter { it[1]!="crew_message_input_provenance" })
            assertEquals(beforeRows,allRows().filterKeys { it!="crew_message_input_provenance" })
        }
    }
    @Test fun gateOffProviderRejectsV1WithoutChangingAnyFile() {
        val file=file("room-v1-gated");createV1(file)
        val bytes=file.readBytes();val members=file.parentFile!!.listFiles()!!.map { it.name }.sorted()
        val failure=assertThrows(RoomCurrentSchemaUnavailable::class.java) { JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,false) }
        assertTrue(failure.message!!.contains("data preserved"));assertArrayEquals(bytes,file.readBytes())
        assertEquals(members,file.parentFile!!.listFiles()!!.map { it.name }.sorted())
    }
    @Test fun realProviderUpgradePreservesValidNativeV1AndMatchesFreshV2IdentityAndDdl() {
        val migrated=file("room-v1-real-room");createV1(migrated,false)
        val owner="00000000-0000-0000-0000-000000000001"
        val session=com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION
        val before=open(migrated).use { connection ->
            JarvysChatDatabaseProvider.DatabaseTriggers.create(connection,context,false)
            connection.execSQL("PRAGMA foreign_keys=ON")
            val sql=raw(connection)
            sql.execSQL("INSERT INTO conversations(id,kind,pinned,archived,created_at,updated_at,last_message_at,message_count,next_seq,history_revision) VALUES(?,'chat',0,0,1000,1000,1000,1,2,1)",arrayOf(session))
            sql.execSQL("INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,sort_sub,turn_anchor_seq,created_at) VALUES(?,1,'message',?,1,0,1,1.25)",arrayOf(session,owner))
            sql.execSQL("INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,ack_required,created_at,updated_at) VALUES(?,?,?,1,1,'user',0,1.25,1.25)",arrayOf(session,owner,owner))
            sql.execSQL("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,0,'text',?)",arrayOf(session,owner,"Retained real USER text \u0000 Ω"))
            assertFalse(sql.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            rows(connection)
        }
        val room=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,migrated,true)
        try {
            runBlocking { room.useWriterConnection { } }
            val retained=runBlocking { room.messageDao().getById(session,owner) }
            assertNotNull(retained);assertEquals("user",retained!!.role);assertEquals(owner,retained.stable_id)
        } finally { JarvysChatDatabaseProvider.closeDatabaseFileForTests(room) }
        open(migrated).use { connection ->
            assertEquals(before,rows(connection))
            assertFalse(raw(connection).query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            assertEquals(0L,scalar(connection,"SELECT COUNT(*) FROM crew_message_input_provenance"))
        }
        val fresh=file("room-v2-fresh")
        val db=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,fresh)
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db)
        for(file in listOf(migrated,fresh))open(file).use { assertEquals(2,RoomTreeProvenanceSchema.validate(it,v2Identity,false)) }
        val reopened=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,migrated)
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(reopened)
    }
    @Test fun newerUnknownPartialAndSpoofedIdentityNeverReceiveMigrationWrites() {
        val mutations=listOf<(SQLiteConnection)->Unit>(
            {it.execSQL("PRAGMA user_version=3")},
            {it.execSQL("PRAGMA user_version=0")},
            {it.execSQL("UPDATE room_master_table SET identity_hash='wrong' WHERE id=42")},
            {it.execSQL("UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB) WHERE id=42")},
            {it.execSQL(RoomTreeProvenanceSchema.CREATE_SQL)},
            {it.execSQL("ALTER TABLE crew_messages ADD COLUMN unexpected TEXT")},
            {it.execSQL("DROP INDEX idx_crew_messages_id")},
            {it.execSQL("CREATE TABLE unknown_side_record(id TEXT)")}
        )
        mutations.forEachIndexed { index,mutate ->
            val file=file("room-v1-invalid-$index");createV1(file,false);open(file).use(mutate)
            val before=file.readBytes()
            BundledSQLiteDriver().open(file.absolutePath,SQLITE_OPEN_READONLY).use { db ->
                assertThrows(RoomCurrentSchemaUnavailable::class.java){RoomTreeProvenanceSchema.validate(db,v2Identity,true)}
            }
            assertArrayEquals(before,file.readBytes())
            open(file).use { db ->assertThrows(RoomCurrentSchemaUnavailable::class.java){upgrade(db)} }
            assertArrayEquals(before,file.readBytes())
        }
    }
    @Test fun unknownAndChangedNativeTriggersCannotMutateOldRowsDuringMigration() {
        val mutations=listOf<(SQLiteConnection)->Unit>(
            { it.execSQL("CREATE TRIGGER rogue_identity_update AFTER UPDATE ON room_master_table BEGIN DELETE FROM storage_meta; END") },
            { it.execSQL("CREATE VIEW rogue_view AS SELECT * FROM storage_meta") },
            { it.execSQL("DROP TRIGGER trg_tombstones_no_update");it.execSQL("CREATE TRIGGER trg_tombstones_no_update BEFORE UPDATE ON tombstones BEGIN SELECT 1; END") },
            { it.execSQL("DROP TRIGGER trg_memory_prompts_fts_insert");it.execSQL("CREATE TRIGGER trg_memory_prompts_fts_insert AFTER INSERT ON memory_prompts BEGIN SELECT 1; END") }
        )
        mutations.forEachIndexed { index,mutate ->
            val file=file("room-v1-trigger-$index");createV1(file,false)
            open(file).use { db ->JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false);raw(db).execSQL("INSERT INTO storage_meta(key,value) VALUES('retained','unchanged')");mutate(db) }
            val before=file.readBytes()
            open(file).use { db ->
                assertThrows(RoomCurrentSchemaUnavailable::class.java){upgrade(db)}
                assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM storage_meta WHERE key='retained' AND value='unchanged'"))
            }
            assertArrayEquals(before,file.readBytes())
        }
    }
    /** Rejection is not enough: none of the old typed bytes or schema may be repaired/deleted. */
    private fun rejectUpgradeWithoutMutation(file:File) {
        val bytes=file.readBytes()
        open(file).use { db ->
            val before=rows(db)
            fun schema()=raw(db).query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use { c ->
                buildList { while(c.moveToNext())add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) }
            }
            val beforeSchema=schema()
            assertThrows(RoomCurrentSchemaUnavailable::class.java){upgrade(db)}
            assertEquals(before,rows(db));assertEquals(beforeSchema,schema())
            assertEquals(1L,scalar(db,"PRAGMA user_version"))
            raw(db).query("SELECT typeof(identity_hash),identity_hash FROM room_master_table WHERE id=42").use {
                assertTrue(it.moveToFirst());assertEquals("text",it.getString(0))
                assertEquals(RoomTreeProvenanceSchema.V1_IDENTITY,it.getString(1));assertFalse(it.moveToNext())
            }
        }
        assertArrayEquals(bytes,file.readBytes())
    }

    @Test fun hiddenTriggerNamesAndLiteralPrefixCaseLookalikesCannotDeleteRetainedRows() {
        // '_' in LIKE is a wildcard. None of these legal names is SQLite's reserved literal prefix.
        val names=listOf("sqliteXreview","SQLITEXreview","SqliteXReview","sqlite-review",
            "sqliteReview","android_metadata","ANDROID_METADATA","Android_Metadata")
        names.forEachIndexed { index,name ->
            val file=file("room-hidden-trigger-$index");createV1(file)
            open(file).use { db ->
                db.execSQL("CREATE TRIGGER `$name` AFTER UPDATE ON room_master_table BEGIN DELETE FROM storage_meta; END")
                assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM storage_meta"))
            }
            rejectUpgradeWithoutMutation(file)
        }
    }

    @Test fun ignoredNameLookalikesInEveryOtherObjectTypeAreRejectedWithoutRepair() {
        var index=0
        for(name in listOf("sqliteXreview","SQLITEXreview","android_metadata","ANDROID_METADATA")) {
            for(ddl in listOf("CREATE VIEW `$name` AS SELECT * FROM storage_meta",
                "CREATE INDEX `$name` ON storage_meta(value)",
                "CREATE TABLE `$name`(unexpected BLOB)")) {
                val file=file("room-hidden-object-${index++}");createV1(file)
                open(file).use { it.execSQL(ddl) }
                rejectUpgradeWithoutMutation(file)
            }
        }
    }

    @Test fun malformedAndroidMetadataTableAndNonTextLocaleAreNotExemptFromValidation() {
        val mutations=listOf(
            "CREATE TABLE android_metadata(locale BLOB)",
            "CREATE TABLE android_metadata(locale TEXT,unexpected INTEGER)",
            "CREATE TABLE android_metadata(other TEXT)",
            "CREATE TABLE android_metadata(locale TEXT);INSERT INTO android_metadata VALUES(X'656E5F5553')",
            "CREATE TABLE android_metadata(locale TEXT);INSERT INTO android_metadata VALUES(NULL)")
        mutations.forEachIndexed { index,mutation ->
            val file=file("room-malformed-android-metadata-$index");createV1(file)
            open(file).use { db ->mutation.split(';').forEach { db.execSQL(it) } }
            rejectUpgradeWithoutMutation(file)
        }
    }

    @Test fun exactAndroidLocaleMetadataRemainsOptionalAndConserved() {
        for(populated in listOf(false,true)) {
            val file=file("room-android-metadata-valid-$populated");createV1(file)
            open(file).use { db ->
                db.execSQL("CREATE TABLE android_metadata (locale TEXT)")
                if(populated)db.execSQL("INSERT INTO android_metadata(locale) VALUES('es_MX')")
                fun metadata()=raw(db).query("SELECT typeof(locale),hex(locale) FROM android_metadata").use { c ->
                    buildList { while(c.moveToNext())add(listOf(c.getString(0),c.getString(1))) }
                }
                val beforeRows=rows(db);val beforeMetadata=metadata()
                assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
                upgrade(db)
                assertEquals(2,RoomTreeProvenanceSchema.validate(db,v2Identity,false))
                assertEquals(beforeRows,rows(db));assertEquals(beforeMetadata,metadata())
                assertEquals("CREATE TABLE android_metadata (locale TEXT)",raw(db).query("SELECT sql FROM sqlite_master WHERE type='table' AND name='android_metadata'").use { assertTrue(it.moveToFirst());it.getString(0) })
            }
        }
    }

    @Test fun eachMissingMessageSearchFamilyMemberRejectsBeforeAnyUpgradeWrite() {
        val members=listOf("VIEW message_search_content","TABLE message_fts",
            "TRIGGER trg_message_fts_insert","TRIGGER trg_message_fts_delete","TRIGGER trg_message_fts_update",
            "TABLE message_fts_config","TABLE message_fts_data","TABLE message_fts_docsize","TABLE message_fts_idx")
        members.forEachIndexed { index,member ->
            val file=file("room-message-fts-missing-$index");createV1(file,false)
            open(file).use { db ->
                JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false)
                assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
                db.execSQL("INSERT INTO storage_meta(key,value) VALUES('retained','unchanged')")
                db.execSQL("DROP $member")
            }
            rejectUpgradeWithoutMutation(file)
        }
    }

    @Test fun mixedAndIncompatibleMessageSearchVariantsCannotBeRebuiltByUpgrade() {
        val mutations=listOf<(SQLiteConnection)->Unit>(
            { db ->db.execSQL("DROP VIEW message_search_content");db.execSQL("CREATE VIEW message_search_content AS SELECT rowid,payload AS text,kind AS role,conversation_id FROM message_parts") },
            { db ->db.execSQL("DROP TRIGGER trg_message_fts_insert");db.execSQL("CREATE TRIGGER trg_message_fts_insert AFTER INSERT ON message_parts BEGIN SELECT 1; END") },
            { db ->db.execSQL("DROP TABLE message_fts");db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts4(text,role,conversation_id)") },
            { db ->db.execSQL("DROP TABLE message_fts");db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts5(text,role,conversation_id,content='message_search_content',content_rowid='rowid',tokenize='porter')") },
            { db ->db.execSQL("DROP TABLE message_fts");db.execSQL("DROP TRIGGER trg_message_fts_delete");db.execSQL("DROP TRIGGER trg_message_fts_update") }
        )
        mutations.forEachIndexed { index,mutate ->
            val file=file("room-message-fts-mixed-$index");createV1(file,false)
            open(file).use { db ->
                JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false)
                db.execSQL("INSERT INTO storage_meta(key,value) VALUES('retained','unchanged')")
                mutate(db)
            }
            rejectUpgradeWithoutMutation(file)
        }
    }

    @Test fun nativeMessageSearchCompleteAbsentAndFailedInstallViewOnlyStatesConserveTheirExactObjects() {
        // MessageSearchIndex.install creates its view before try; its catch/drop removes only
        // the three triggers and virtual table. Thus view-only is a real native failure state.
        for(state in listOf("complete","absent","view-only")) {
            val file=file("room-message-fts-supported-$state");createV1(file,false)
            open(file).use { db ->
                JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false)
                if(state!="complete") {
                    for(name in listOf("insert","delete","update"))db.execSQL("DROP TRIGGER trg_message_fts_$name")
                    db.execSQL("DROP TABLE message_fts")
                    if(state=="absent")db.execSQL("DROP VIEW message_search_content")
                }
                db.execSQL("INSERT INTO storage_meta(key,value) VALUES('retained','unchanged')")
                fun objects()=raw(db).query("SELECT type,name,sql FROM sqlite_master WHERE name IN ('message_search_content','message_fts','trg_message_fts_insert','trg_message_fts_delete','trg_message_fts_update','message_fts_config','message_fts_data','message_fts_docsize','message_fts_idx') ORDER BY type,name").use { c ->
                    buildList { while(c.moveToNext())add(listOf(c.getString(0),c.getString(1),c.getString(2))) }
                }
                val before=objects();val beforeRows=rows(db)
                assertEquals(if(state=="complete")9 else if(state=="view-only")1 else 0,before.size)
                assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
                upgrade(db)
                assertEquals(2,RoomTreeProvenanceSchema.validate(db,v2Identity,false))
                assertEquals(before,objects());assertEquals(beforeRows,rows(db))
            }
        }
    }

    @Test fun moduleShadowClassificationDoesNotCertifyReplacedTableDdl() {
        for(parent in listOf("memory_fts","memory_prompts_fts","message_fts")) {
            val file=file("room-shadow-replaced-$parent");createV1(file,false)
            val shadow=parent+"_config"
            open(file).use { db ->
                JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false)
                assertEquals(1,RoomTreeProvenanceSchema.validate(db,v2Identity,true))
                db.execSQL("CREATE TEMP TABLE retained_config AS SELECT * FROM `$shadow`")
                db.execSQL("DROP TABLE `$shadow`")
                db.execSQL("CREATE TABLE `$shadow`(k PRIMARY KEY,v,unexpected BLOB) WITHOUT ROWID")
                db.execSQL("INSERT INTO `$shadow`(k,v) SELECT k,v FROM retained_config")
                val classified=raw(db).query("PRAGMA table_list").use { c ->
                    var found=false
                    while(c.moveToNext())if(c.getString(0)=="main" && c.getString(1)==shadow) {
                        assertEquals("Module suffix classification alone is not DDL validation","shadow",c.getString(2));found=true
                    }
                    found
                }
                assertTrue(classified)
            }
            val before=file.readBytes()
            open(file).use { db ->assertThrows(RoomCurrentSchemaUnavailable::class.java){upgrade(db)} }
            assertArrayEquals(before,file.readBytes())
        }
    }

    @Test fun concurrentExplicitProviderUpgradesSerializeOnNativeLifecycleAnchor() {
        val file=file("room-v1-concurrent");createV1(file,false)
        val before=open(file).use { db ->JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false);scalar(db,"PRAGMA schema_version") }
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        val start=java.util.concurrent.CountDownLatch(1)
        val opened=java.util.Collections.synchronizedList(mutableListOf<JarvysChatDatabase>())
        try {
            val calls=(1..2).map { pool.submit<JarvysChatDatabase> {
                start.await();JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true).also { opened.add(it) }
            } }
            start.countDown();calls.forEach { it.get(30,java.util.concurrent.TimeUnit.SECONDS) }
            open(file).use { db ->
                assertEquals(2,RoomTreeProvenanceSchema.validate(db,v2Identity,false))
                assertEquals(before+1,scalar(db,"PRAGMA schema_version"))
            }
        } finally {
            opened.forEach { JarvysChatDatabaseProvider.closeDatabaseFileForTests(it) }
            pool.shutdownNow()
        }
    }

    @Test fun retainedNativeReadSnapshotSeesV1UntilReleaseThenReopensExactV2() {
        val file=file("room-v1-reader");createV1(file,false)
        open(file).use { db ->JarvysChatDatabaseProvider.DatabaseTriggers.create(db,context,false);raw(db).query("PRAGMA journal_mode=WAL").use { assertTrue(it.moveToFirst());assertEquals("wal",it.getString(0)) } }
        val reader=BundledSQLiteDriver().open(file.absolutePath,SQLITE_OPEN_READONLY)
        try {
            reader.execSQL("BEGIN")
            assertEquals(1,RoomTreeProvenanceSchema.validate(reader,v2Identity,true))
            val before=rows(reader)
            val opened=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true)
            try {
                assertEquals(before,rows(reader))
                assertEquals(1,RoomTreeProvenanceSchema.validate(reader,v2Identity,true))
                reader.execSQL("COMMIT")
                assertEquals(2,RoomTreeProvenanceSchema.validate(reader,v2Identity,false))
                assertEquals(before,rows(reader))
            } finally { JarvysChatDatabaseProvider.closeDatabaseFileForTests(opened) }
        } finally { reader.close() }
    }

    @Test fun provenanceForeignKeyRejectsOrphansAndCascadesOnlyWithActualParent() {
        val file=file("room-v1-fk");createV1(file);open(file).use { db ->
            upgrade(db);db.execSQL("PRAGMA foreign_keys=ON")
            val columns=listOf("conversation_id","mission_id","message_id","source_session_id","workspace_id","assignment_id","bot_id","source_message_id","kind","principal_role","correlation_message_id","reference_id","content_sha256","assignment_revision","request_id","input_mission_id","cycle_id")
            val bytes=ExactUtf16.encode("retained\u0000\ud800")
            val values=arrayOf<Any?>("row","row","row",bytes,bytes,bytes,bytes,bytes,"MESSAGE","PEER",bytes,bytes,bytes,1L,bytes,bytes,bytes)
            val insert="INSERT INTO crew_message_input_provenance("+columns.joinToString(",")+") VALUES("+columns.joinToString(","){"?"}+")"
            raw(db).execSQL(insert,values)
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM crew_message_input_provenance"))
            val invalid=values.copyOf();invalid[2]="missing"
            val failure=runCatching {raw(db).execSQL(insert,invalid)}.exceptionOrNull()
            assertTrue(failure is android.database.SQLException && isChatSqlConstraint(failure))
            raw(db).execSQL("DELETE FROM crew_messages WHERE conversation_id=? AND mission_id=? AND message_id=?",arrayOf("row","row","row"))
            assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM crew_message_input_provenance"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM conversations"))
        }
    }
}

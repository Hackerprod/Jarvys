package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.FreshStorageBootstrap
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Actual bootstrap boundary; no live reply owner, permit, provider, or acceptance is synthesized. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
class ProactiveReplyBootstrapGenerationTest {
    @get:Rule val storage=ChatTestStore()
    private val fixtures=mutableListOf<Fixture>()
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private val thread="proactive:first-notice:😀"
    private val key=ConnectionProjectionRepository.GENERATION_KEY

    private inner class Fixture {
        val files=storage.newFilesDirectory()
        val context:Context=object:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=files
            override fun getDatabasePath(name:String):File=if(File(name).isAbsolute)File(name)
                else File(files,"databases/$name").also {it.parentFile!!.mkdirs()}
        }
        private var bridgeOpened=false
        private var database:JarvysChatDatabase?=null
        val db get()=checkNotNull(database)
        val sql get()=db.testChatSql
        init {
            ChatDatabaseBridge.getInstance(context) // Lazy facade, not bootstrap or generation creation.
            storage.localRunStore(files) // Register normal fixture cleanup without touching Room rows.
            database=JarvysChatDatabaseProvider.getInstance(context)
            fixtures.add(this)
        }
        fun ensure()=FreshStorageBootstrap.ensure(context)
        fun generation():String?=sql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(key)).use {c ->if(c.moveToFirst())c.getString(0) else null}
        fun count(table:String)=sql.query("SELECT COUNT(*) FROM $table").use {check(it.moveToFirst());it.getLong(0)}
        fun notice(decision:String="first-notice"):StoredMessage=runBlocking {
            (ConversationRepository(db).appendProactiveAssistantMessageIfAbsent(central,decision,"First notice 😀",thread,
                listOf(SuggestedReplyInput("Reply","Reply 😀"))) as ChatDbResult.Success).value
        }
        fun latest():ProactiveReplySourceSnapshot? {
            val value=ChatDatabaseBridge.getInstance(context).captureLatestProactiveReplySourceSync(thread)
            bridgeOpened=true
            return value
        }
        fun schema():List<List<String?>> = sql.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use {c ->
            buildList {while(c.moveToNext())add((0 until c.columnCount).map {if(c.isNull(it))null else c.getString(it)})}
        }
        fun rows(excludeGeneration:Boolean=false):Map<String,List<List<String>>> {
            val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {c ->buildList {while(c.moveToNext())add(c.getString(0))}}
            fun quoted(name:String)="\""+name.replace("\"","\"\"")+"\""
            return tables.associateWith {table ->
                val columns=sql.query("PRAGMA table_info("+quoted(table)+")").use {c ->buildList {while(c.moveToNext())add(c.getString(1))}}
                val suffix=if(excludeGeneration && table=="storage_meta")" WHERE key<>?" else ""
                val args=if(suffix.isEmpty())emptyArray<Any?>() else arrayOf<Any?>(key)
                sql.query("SELECT "+columns.joinToString(","){"typeof("+quoted(it)+"),hex("+quoted(it)+")"}+" FROM "+quoted(table)+suffix,args).use {c ->
                    buildList {while(c.moveToNext())add((0 until c.columnCount).map {c.getString(it) ?: "NULL"})}.sortedBy {it.joinToString("|")}
                }
            }
        }
        fun close() {
            val opened=database ?: return
            database=null
            if(bridgeOpened)ChatDatabaseBridge.closeInstanceForTests(files)
            else {
                JarvysChatDatabaseProvider.closeDatabaseFileForTests(opened)
                ChatDatabaseBridge.closeInstanceForTests(files)
            }
            bridgeOpened=false
        }
        fun reopen() {
            val old=db;close()
            ChatDatabaseBridge.getInstance(context)
            database=JarvysChatDatabaseProvider.getInstance(context)
            assertNotSame(old,db)
        }
    }
    @After fun close() {fixtures.forEach {it.close()}}

    @Test fun actualFreshBootstrapMakesFirstNoticeReadableBeforeAnyUserOrConnector() {
        val f=Fixture();assertNull(f.generation());assertEquals(0L,f.count("conversations"))
        f.ensure();val generation=checkNotNull(f.generation());assertEquals(generation,UUID.fromString(generation).toString())
        val notice=f.notice();val before=f.rows();val source=checkNotNull(f.latest())
        assertEquals(notice.messageId,source.sourceMessageId);assertEquals(generation,source.physicalGeneration)
        assertTrue(source.currentEligible);assertEquals(before,f.rows())
        assertEquals(0L,f.sql.query("SELECT COUNT(*) FROM messages WHERE role='user'").use {it.moveToFirst();it.getLong(0)})
        assertEquals(0L,f.count("central_turn_submissions"));assertEquals(0L,f.count("connector_state_events"))
    }

    @Test fun retainedCurrentRowsAndSchemaStayExactExceptSingleMissingGeneration() {
        val f=Fixture()
        runBlocking {
            val repo=ConversationRepository(f.db)
            repo.ensureConversation(central,"central")
            repo.appendMessage(AppendMessageInput(central,"user",listOf(MessagePartInput("text","retained ordinary 😀")),conversationKind="central"))
            repo.appendMessage(AppendMessageInput(central,"user",listOf(MessagePartInput("text","retained thread 😀")),proactiveThreadKey=thread,conversationKind="central"))
            repo.consumeSuggestedReplies(central,f.notice().messageId)
        }
        f.sql.execSQL("INSERT INTO storage_meta(key,value) VALUES('unrelated-current-metadata','retain-exactly')")
        assertNull(f.generation());val rows=f.rows(true);val schema=f.schema()
        f.ensure();assertNotNull(f.generation());assertEquals(rows,f.rows(true));assertEquals(schema,f.schema())
        assertEquals(2L,f.count("storage_meta"));val allRows=f.rows()
        f.ensure();assertEquals(allRows,f.rows());assertEquals(0L,f.count("central_turn_submissions"))
    }

    @Test fun existingPhysicalGenerationAndRowsSurviveRepeatedBootstrapAndRealReopen() {
        val f=Fixture();f.notice()
        val generation=runBlocking {ConnectionProjectionRepository(f.db).generation()}
        val rows=f.rows();val schema=f.schema()
        f.ensure();f.ensure();assertEquals(generation,f.generation());assertEquals(rows,f.rows());assertEquals(schema,f.schema())
        f.reopen();f.ensure();assertEquals(generation,f.generation());assertEquals(rows,f.rows());assertEquals(schema,f.schema())
    }

    @Test fun malformedExistingGenerationFailsAndRollsBackCentralWithoutRewritingRetainedRows() {
        for(malformed in listOf<Any>("not-a-generation","",42L,UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))) {
            val f=Fixture()
            runBlocking {ConversationRepository(f.db).appendMessage(AppendMessageInput("retained-chat","user",listOf(MessagePartInput("text","keep this"))))}
            f.sql.execSQL("INSERT INTO storage_meta(key,value) VALUES(?,?)",arrayOf(key,malformed))
            val rows=f.rows();val schema=f.schema()
            assertThrows(IllegalStateException::class.java) {f.ensure()}
            assertEquals(rows,f.rows());assertEquals(schema,f.schema())
            assertThrows(IllegalStateException::class.java) {f.ensure()}
            assertEquals(rows,f.rows())
            assertEquals(0L,f.sql.query("SELECT COUNT(*) FROM conversations WHERE id=?",arrayOf(central)).use {it.moveToFirst();it.getLong(0)})
        }
    }

    @Test fun failedGenerationInsertRollsBackCentralAndMetadataTogetherAndCanRetry() {
        val f=Fixture();val rows=f.rows();val schema=f.schema()
        // The existing writer owns this TEMP trigger; persisted-schema preflight must stay exact.
        f.sql.execSQL("CREATE TEMP TRIGGER reject_bootstrap_generation BEFORE INSERT ON storage_meta WHEN NEW.key='$key' BEGIN SELECT RAISE(ABORT,'injected_generation_insert'); END")
        try {
            val failure=runCatching {f.ensure()}.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(generateSequence(failure) {it.cause}.any {it.message.orEmpty().contains("injected_generation_insert")})
        } finally {f.sql.execSQL("DROP TRIGGER reject_bootstrap_generation")}
        assertEquals(rows,f.rows());assertEquals(schema,f.schema());assertNull(f.generation())
        assertEquals(0L,f.count("conversations"))
        f.ensure();assertNotNull(f.generation());assertEquals(1L,f.count("conversations"));assertEquals(0L,f.count("central_turn_submissions"))
    }

    @Test fun separatePhysicalStoresWithSameBootstrapFormatHaveDifferentGeneration() {
        val first=Fixture();val second=Fixture();first.ensure();second.ensure()
        val firstIntent=FreshStorageBootstrap.stamp(Intent());val secondIntent=FreshStorageBootstrap.stamp(Intent())
        assertEquals(firstIntent.getStringExtra(FreshStorageBootstrap.GENERATION_EXTRA),secondIntent.getStringExtra(FreshStorageBootstrap.GENERATION_EXTRA))
        assertTrue(FreshStorageBootstrap.isCurrent(firstIntent));assertTrue(FreshStorageBootstrap.isCurrent(secondIntent))
        assertNotEquals(first.generation(),second.generation())
        first.notice();second.notice()
        assertNotEquals(first.latest()!!.physicalGeneration,second.latest()!!.physicalGeneration)
        assertEquals(0L,first.count("central_turn_submissions"));assertEquals(0L,second.count("central_turn_submissions"))
    }

    @Test fun sourceDescriptorWithoutGenerationFailsReadOnlyRatherThanBootstrapping() {
        val f=Fixture();val notice=f.notice();assertNull(f.generation());val rows=f.rows()
        assertThrows(IllegalStateException::class.java) {runBlocking {f.db.withChatReadTransaction {
            ProactiveReplySourceValidation.read(f.db,notice.messageId,thread)
        }}}
        assertEquals(rows,f.rows());assertNull(f.generation())
        f.ensure();assertEquals(notice.messageId,f.latest()!!.sourceMessageId)
    }
}

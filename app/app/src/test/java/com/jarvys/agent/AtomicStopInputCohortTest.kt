package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AtomicStopInputCohortTest {
    @get:Rule val storage=ChatTestStore()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private inner class Fixture {
        val files=storage.newFilesDirectory()
        val context=object:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=files
        }
        var ledger=storage.localRunStore(files)
        fun input(text:String)=ledger.appendAndDispatchInterruptRequest(session,text,emptyList())
        fun sql(statement:String)=runBlocking {
            val db=JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction {db.chatSql.execSQL(statement)}
        }
        fun reopen(){ChatDatabaseBridge.closeInstanceForTests(files);ChatDatabaseBridge.getInstance(context);ledger=LocalRunStore(context)}
        fun assistants()=ledger.readConversationMessages(session).filter {it.optString("role")=="assistant"}
    }
    @Test fun exactCapturedIdsOnlyAndRepeatedCommitIsIdempotentAfterReopen() {
        val f=Fixture();val first=f.input("PreSTOP one");val second=f.input("PreSTOP two")
        val cohort=listOf(first,second);val later=f.input("Later fresh USER")
        assertEquals(cohort,f.ledger.persistStoppedInputCohort(session,cohort,"Stopped captured request"))
        assertEquals(setOf(first,second),f.assistants().map {it.getString("userMessageId")}.toSet())
        assertTrue(f.assistants().all {it.getString("status")=="FAILED"})
        assertNull(f.ledger.readConversationInputState(session,later))
        assertTrue(f.ledger.persistStoppedInputCohort(session,cohort,"Stopped captured request").isEmpty())
        assertEquals(2,f.assistants().size)
        f.reopen()
        assertEquals("resolved",f.ledger.readConversationInputState(session,first))
        assertEquals("resolved",f.ledger.readConversationInputState(session,second))
        assertNull(f.ledger.readConversationInputState(session,later))
        assertTrue(f.ledger.persistStoppedInputCohort(session,cohort,"Stopped captured request").isEmpty())
        assertEquals(2,f.assistants().size)
    }
    @Test fun failureOnSecondExactIdRollsBackFirstFailedBubbleAndResolutionAcrossReopen() {
        val f=Fixture();val first=f.input("PreSTOP one");val second=f.input("PreSTOP two");val fresh=f.input("Fresh retained")
        f.sql("CREATE TRIGGER fixture_stop_abort BEFORE INSERT ON messages WHEN NEW.role='assistant' AND NEW.owner_user_message_id='$second' BEGIN SELECT RAISE(ABORT,'fixture_exact_stop_second_write'); END")
        val error=assertThrows(RuntimeException::class.java){f.ledger.persistStoppedInputCohort(session,listOf(first,second),"Stopped captured request")}
        assertTrue(generateSequence<Throwable>(error){it.cause}.any {it.message?.contains("fixture_exact_stop_second_write")==true})
        assertTrue(f.assistants().isEmpty())
        for(id in listOf(first,second,fresh))assertNull(f.ledger.readConversationInputState(session,id))
        f.sql("DROP TRIGGER fixture_stop_abort");f.reopen()
        assertTrue(f.assistants().isEmpty())
        for(id in listOf(first,second,fresh))assertNull(f.ledger.readConversationInputState(session,id))
        assertEquals(listOf(first,second),f.ledger.persistStoppedInputCohort(session,listOf(first,second),"Stopped captured request"))
        assertNull(f.ledger.readConversationInputState(session,fresh));assertEquals(2,f.assistants().size)
    }
}

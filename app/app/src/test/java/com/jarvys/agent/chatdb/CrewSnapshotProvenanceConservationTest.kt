package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.crew.CrewInputBinding
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewTreeMessageRoutingTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Normalized snapshot conservation, not a synthetic native admission/capability fixture. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],shadows=[com.jarvys.agent.ArtifactOsShadow::class])
class CrewSnapshotProvenanceConservationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var database:JarvysChatDatabase
    private lateinit var memory:MemoryTimelineRepository
    private val file get()=File(temporary.root,"snapshot-provenance.db")
    @Before fun open() { database=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file);memory=MemoryTimelineRepository(database) }
    @After fun close() {if(::database.isInitialized)JarvysChatDatabaseProvider.closeDatabaseFileForTests(database);worker.shutdownNow()}
    private fun <T> onWorker(body:suspend ()->T):T=worker.submit<T>{runBlocking {body()}}.get(60,TimeUnit.SECONDS)
    private fun fixture(kind:String):CrewMessage= CrewTreeMessageRoutingTest::class.java.getDeclaredMethod(kind).apply {isAccessible=true}
        .invoke(CrewTreeMessageRoutingTest()) as CrewMessage
    private fun snapshot(message:CrewMessage,title:String="Snapshot"):JSONObject=CrewMissionSnapshot("mission",message.conversationId,
        "local-test",title,"RUNNING","",1,0,emptyList(),listOf(message)).toJson()
    private fun <T> success(value:ChatDbResult<T>):T=when(value){is ChatDbResult.Success->value.value;else->throw AssertionError("Snapshot write failed: $value")}
    private suspend fun read(message:CrewMessage):CrewMessage=memory.readCrewMissionSnapshots(message.conversationId).single().messages.single()
    private fun same(expected:CrewMessage,actual:CrewMessage) {
        assertEquals(expected.toJson().toString(),actual.toJson().toString())
        assertEquals(expected.inputProvenance?.toJson()?.toString(),actual.inputProvenance?.toJson()?.toString())
    }
    private suspend fun denied(body:suspend ()->Unit) {
        var failure:Throwable?=null;try {body()}catch(t:Throwable){failure=t}
        assertNotNull("Malformed persisted proof must not decode",failure)
    }
    @Test fun taskReportAnswerRoundTripReopenAndReplacementConserveExactProof()=onWorker {
        val messages=listOf("task","report","answer").map(::fixture)
        for(message in messages) {same(message,success(memory.appendCrewSnapshot(message.conversationId,snapshot(message))).messages.single());same(message,read(message))}
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(database);open()
        for(message in messages) {
            same(message,read(message));success(memory.appendCrewSnapshot(message.conversationId,snapshot(message,"Replacement")));same(message,read(message))
            val count=database.testChatSql.query("SELECT count(*) FROM crew_message_input_provenance WHERE conversation_id=?",arrayOf(message.conversationId)).use{check(it.moveToFirst());it.getInt(0)}
            assertEquals(1,count)
        }
    }
    @Test fun acceptedEmptyAndUnicodeScalarsAndContentAreLosslessNotInferred()=onWorker {
        val original=fixture("report")
        val p=ConversationInput.Provenance.fromJson(original.inputProvenance.toJson().put("requestId","request\u0000\uD800 café 🚀"))
        val bare=CrewMessage(original.id,original.conversationId,original.from,original.to,original.type,"content\u0000\uD800 café 🚀",original.refs,original.timestampMillis)
        val binding=CrewInputBinding.fromJsonStrict(original.inputBinding.toJson().put("deliverySha256",CrewInputBinding.deliverySha256(bare)))
        val message=bare.withInputBinding(binding).withInputProvenance(p)
        success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)));same(message,read(message))
        database.testChatSql.query("SELECT typeof(request_id),hex(request_id),typeof(reference_id),hex(reference_id) FROM crew_message_input_provenance WHERE conversation_id=?",arrayOf(message.conversationId)).use {
            check(it.moveToFirst());assertEquals("blob",it.getString(0));assertEquals(ExactUtf16.encode(p.requestId).joinToString(""){b->"%02X".format(b)},it.getString(1));assertEquals("blob",it.getString(2));assertEquals("",it.getString(3))
        }
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(database);open();same(message,read(message))
    }
    @Test fun missingTypedProofFailsClosedForEveryTreeKindButOrdinaryLegacyRemainsReadable()=onWorker {
        for(kind in listOf("task","report","answer")) {
            val message=fixture(kind);success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)))
            database.testChatSql.execSQL("DELETE FROM crew_message_input_provenance WHERE conversation_id=?",arrayOf(message.conversationId))
            denied {read(message)}
            // The binding and raw message remain retained, never stripped to make decoding succeed.
            database.testChatSql.query("SELECT count(*) FROM crew_message_input_bindings WHERE conversation_id=?",arrayOf(message.conversationId)).use{check(it.moveToFirst());assertEquals(1,it.getInt(0))}
        }
        val legacy=CrewMessage("legacy","legacy-snapshot","worker","chief",CrewMessage.Type.MESSAGE,"Old unbound message",emptyList(),1)
        success(memory.appendCrewSnapshot(legacy.conversationId,snapshot(legacy)));same(legacy,read(legacy));assertNull(read(legacy).inputProvenance)
    }
    @Test fun malformedStoredScalarRoleAndSourceNeverAcquireFallbackAuthority()=onWorker {
        for(kind in listOf("task","report","answer")) for(change in listOf("principal_role='UNKNOWN'","assignment_revision=0.5","request_id='coerced-text'","kind='UNKNOWN'","source_session_id=X'00'")) {
            val message=fixture(kind);success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)))
            database.testChatSql.execSQL("UPDATE crew_message_input_provenance SET $change WHERE conversation_id=?",arrayOf(message.conversationId))
            denied {read(message)}
        }
    }
    @Test fun failedProofReplacementRollsBackLedgerAndPriorCanonicalSnapshot()=onWorker {
        val message=fixture("answer");success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)))
        val before=database.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(message.conversationId)).use{check(it.moveToFirst());it.getLong(0)}
        database.testChatSql.execSQL("CREATE TRIGGER reject_provenance_insert BEFORE INSERT ON crew_message_input_provenance BEGIN SELECT RAISE(ABORT,'test_provenance_write_failed'); END")
        try {
            var rejected=false
            try {val result=memory.appendCrewSnapshot(message.conversationId,snapshot(message,"Must roll back"));rejected=result !is ChatDbResult.Success}
            catch(expected:Exception){rejected=true}
            assertTrue(rejected)
        } finally {database.testChatSql.execSQL("DROP TRIGGER reject_provenance_insert")}
        same(message,read(message))
        database.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(message.conversationId)).use{check(it.moveToFirst());assertEquals(before,it.getLong(0))}
        assertEquals("Snapshot",memory.readCrewMissionSnapshots(message.conversationId).single().title)
    }
    @Test fun nullProofScalarsAreRejectedWithoutConvertingAbsenceToEmpty()=onWorker {
        val message=fixture("report");success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)))
        for(column in listOf("request_id","reference_id","principal_role","assignment_revision")) {
            denied {database.testChatSql.execSQL("UPDATE crew_message_input_provenance SET $column=NULL WHERE conversation_id=?",arrayOf(message.conversationId))}
            same(message,read(message))
        }
    }
    @Test fun legitimateReplacementCascadesOnlyRemovedMessagesProof()=onWorker {
        val message=fixture("task");success(memory.appendCrewSnapshot(message.conversationId,snapshot(message)))
        val legacy=CrewMessage("legacy",message.conversationId,"worker","chief",CrewMessage.Type.MESSAGE,"Retained ordinary message",emptyList(),2)
        success(memory.appendCrewSnapshot(legacy.conversationId,snapshot(legacy,"Updated")));same(legacy,read(legacy))
        database.testChatSql.query("SELECT count(*) FROM crew_message_input_provenance WHERE conversation_id=?",arrayOf(message.conversationId)).use{check(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }

}

package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.proactive.ProactiveThreadKey
import com.jarvys.agent.workspaces.MissionRef
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Read/accounting proof only. The classifier never supplies a USER, live action, or permit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class NativeStandaloneNoticeProtectionTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private val bridge get()=ChatDatabaseBridge.getInstance(context)
    private lateinit var database:JarvysChatDatabase
    private val db get()=database
    private val sql get()=db.testChatSql
    private val repo get()=ConversationRepository(db)
    @Before fun ready() {FreshStorageBootstrap.ensure(context);database=JarvysChatDatabaseProvider.getInstance(context);bridge.centralSubmissionSchemaReadySync()}
    private fun reopen() {ChatDatabaseBridge.closeInstanceForTests(context.filesDir);database=JarvysChatDatabaseProvider.getInstance(context)}
    private fun notice(thread:String?="proactive:test",decision:String=java.util.UUID.randomUUID().toString(),text:String="Native notice 😀",
        replies:List<SuggestedReplyInput> = listOf(SuggestedReplyInput("Reply","Reply 😀"))):StoredMessage=runBlocking {
        (repo.appendProactiveAssistantMessageIfAbsent(session,decision,text,thread,replies) as ChatDbResult.Success).value
    }
    private fun source(id:String)=ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,session,id,null)
    private fun projection()=bridge.readInteractionProtectionSync(session)
    private fun contextTurns()=bridge.loadConversationContextSync(session)
    private fun scalar(query:String)=sql.query(query).use {check(it.moveToFirst());it.getLong(0)}
    private fun rows():Map<String,List<List<String>>> {
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {c ->buildList {while(c.moveToNext())add(c.getString(0))}}
        fun quoted(name:String)="\""+name.replace("\"","\"\"")+"\""
        return tables.associateWith {table ->
            val columns=sql.query("PRAGMA table_info("+quoted(table)+")").use {c ->buildList {while(c.moveToNext())add(c.getString(1))}}
            sql.query("SELECT "+columns.joinToString(","){"typeof("+quoted(it)+"),hex("+quoted(it)+")"}+" FROM "+quoted(table)).use {c ->
                buildList {while(c.moveToNext())add((0 until c.columnCount).map {c.getString(it) ?: "NULL"})}.sortedBy {it.joinToString("|")}
            }
        }
    }
    private class RollbackProbe:RuntimeException()
    /** Each negative starts from the same valid source; an earlier bad row cannot mask later cases. */
    private fun malformed(change:()->Unit) {
        val before=projection();assertFalse(before.unsafeAnchor)
        try {runBlocking {db.withChatWriteTransaction {
            change()
            val changed=InteractionProtection(db).read(session)
            assertTrue("Malformed declared notice must not acquire safe accounting",changed.unsafeAnchor)
            assertNotEquals(before.fingerprint,changed.fingerprint)
            throw RollbackProbe()
        }}} catch(expected:RollbackProbe) {}
        assertEquals(before.fingerprint,projection().fingerprint);assertFalse(projection().unsafeAnchor)
    }
    private fun validMutationChangesFingerprint(change:()->Unit) {
        val before=projection();assertFalse(before.unsafeAnchor)
        try {runBlocking {db.withChatWriteTransaction {
            change();val changed=InteractionProtection(db).read(session)
            assertFalse(changed.unsafeAnchor);assertNotEquals(before.fingerprint,changed.fingerprint)
            throw RollbackProbe()
        }}} catch(expected:RollbackProbe) {}
        assertEquals(before.fingerprint,projection().fingerprint)
    }

    @Test fun currentProactiveAndTaskNoticesKeepOwnProtectedPositionsAndContextAcrossReopen() {
        val notices=listOf(notice("proactive:current"),notice("task:scheduled",text="",replies=emptyList()))
        val before=rows();val protection=projection();assertFalse(protection.unsafeAnchor)
        for(notice in notices) {
            val source=source(notice.messageId)
            assertTrue(source in protection.protectedSources)
            assertEquals(InteractionSourcePosition(notice.seq,0,notice.seq),protection.sourcePositions[source])
            assertNull(bridge.readMessageSync(session,notice.messageId)!!.ownerUserMessageId)
        }
        val turns=contextTurns();bridge.validateProtectedContextSync(session,turns)
        assertEquals(0,protection.protectedPrefixLimit(turns,emptySet()))
        val visible=notices.first();assertThrows(IllegalStateException::class.java) {
            bridge.validateProtectedContextSync(session,turns.filterNot {it.source()==source(visible.messageId)})
        }
        assertEquals(before,rows());assertEquals(0L,scalar("SELECT COUNT(*) FROM messages WHERE role='user'"))
        assertEquals(0L,scalar("SELECT COUNT(*) FROM central_turn_submissions"))
        reopen()
        assertFalse(projection().unsafeAnchor);bridge.validateProtectedContextSync(session,contextTurns())
        assertEquals(before,rows())
    }

    @Test fun legacyNullEmptyAndWhitespaceThreadNoticesStayReadableWithoutAnyP4ReplyAuthorityOrWrites() {
        val nullThread=notice(null)
        val emptyId=LocalRunStore(context).appendProactiveAssistantMessageIfAbsent(session,"legacy-empty","Legacy three-argument native notice")
        val emptyThread=bridge.readMessageSync(session,emptyId)!!;assertEquals("",emptyThread.proactiveThreadKey)
        val whitespaceThread=notice(" \t\n ")
        val legacyNotices=listOf(nullThread,emptyThread,whitespaceThread)
        // Blank-thread authority is denied even while a valid physical generation exists.
        for(notice in legacyNotices) {
            assertNull(bridge.readProactiveReplySourceSync(notice.messageId,null))
            assertNull(bridge.captureProactiveReplySourceSync(notice.messageId,ProactiveReplyAcceptance.OPTION,0))
            assertNull(ProactiveInteractionDispatcher.prepareSuggestedReply(context,notice.messageId,0))
        }
        // Classification cannot silently create the otherwise lazy physical generation either.
        sql.execSQL("DELETE FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY))
        val before=rows();assertFalse(projection().unsafeAnchor)
        bridge.validateProtectedContextSync(session,contextTurns())
        for(notice in legacyNotices) {
            assertTrue(source(notice.messageId) in projection().protectedSources)
            assertNull(bridge.readProactiveReplySourceSync(notice.messageId,null))
            assertNull(bridge.captureProactiveReplySourceSync(notice.messageId,ProactiveReplyAcceptance.OPTION,0))
            assertNull(ProactiveInteractionDispatcher.prepareSuggestedReply(context,notice.messageId,0))
            assertNull(runCatching {ProactiveInteractionDispatcher.prepareInlineReply(context,notice.messageId,
                ProactiveThreadKey.fingerprint(notice.proactiveThreadKey.orEmpty()),"No authority")}.getOrNull())
        }
        assertEquals(before,rows());assertEquals(0L,scalar("SELECT COUNT(*) FROM central_turn_submissions"))
        assertEquals(0L,scalar("SELECT COUNT(*) FROM messages WHERE role='user'"))
        reopen()
        assertFalse(projection().unsafeAnchor);bridge.validateProtectedContextSync(session,contextTurns());assertEquals(before,rows())
    }

    @Test fun aValidNoticeNeverMasksAnOrdinaryOwnerlessAssistant() {
        notice()
        runBlocking {repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Unproven assistant")),status="COMPLETED"))}
        assertTrue(projection().unsafeAnchor)
        assertThrows(IllegalStateException::class.java) {bridge.validateProtectedContextSync(session,contextTurns())}
    }

    @Test fun nonCentralStandaloneAndMissionCardAccountingAndFingerprintStayUnchanged() {
        val other="non-central-notice"
        val notice=runBlocking {(repo.appendProactiveAssistantMessageIfAbsent(other,"other-decision","Other workspace notice","other-thread",
            listOf(SuggestedReplyInput("Reply","Other reply"))) as ChatDbResult.Success).value}
        fun protection()=bridge.readInteractionProtectionSync(other)
        val source=ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,other,notice.messageId,null)
        val before=rows();val original=protection()
        assertTrue(original.unsafeAnchor);assertTrue(source in original.protectedSources);assertEquals(before,rows())
        // These fields were absent from the original non-Central projection evidence.
        sql.execSQL("UPDATE messages SET stable_id='changed',proactive_decision_id='changed',proactive_thread_key='   ' WHERE conversation_id=?",arrayOf(other))
        sql.execSQL("UPDATE conversation_items SET turn_anchor_seq=seq WHERE conversation_id=?",arrayOf(other))
        sql.execSQL("UPDATE message_parts SET payload='changed body' WHERE conversation_id=?",arrayOf(other))
        sql.execSQL("UPDATE proactive_suggested_replies SET text='changed option' WHERE conversation_id=?",arrayOf(other))
        val changedRows=rows();val changed=protection()
        assertTrue(changed.unsafeAnchor);assertEquals(original.fingerprint,changed.fingerprint);assertEquals(changedRows,rows())
        // Original mission-card exclusion remains intact even with a declared decision outside Central.
        sql.execSQL("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,1,?,'{}')",arrayOf(other,notice.messageId,MissionRef.PART_KIND))
        val cardRows=rows();val card=protection()
        assertFalse(card.unsafeAnchor);assertFalse(source in card.protectedSources);assertFalse(source in card.sourcePositions)
        assertEquals(cardRows,rows())
        reopen();assertEquals(card.fingerprint,protection().fingerprint);assertEquals(cardRows,rows())
    }

    @Test fun malformedDiscriminatorOwnershipAndSpineNeverBecomeSafeHumanOrNoticeGroups() {
        val owner=runBlocking {(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","A real earlier USER")))) as ChatDbResult.Success).value}
        val notice=notice();val id=notice.messageId
        fun message(change:String)=sql.execSQL("UPDATE messages SET $change WHERE conversation_id=? AND message_id=?",arrayOf(session,id))
        for(change in listOf("proactive_decision_id=NULL","proactive_decision_id=''","stable_id='changed-stable'","status=NULL","status='FAILED'","role='user'",
            "owner_user_message_id='${owner.messageId}'","run_id='unowned-run'","input_state='resolved'","input_state_seq=1",
            "proactive_reply_to_message_id='${owner.messageId}'","interrupt_request_id='interrupt'","ack_required=1","duration_ms=1",
            "before_message_id='${owner.messageId}'")) malformed {message(change)}
        for(change in listOf("item_kind='model_tool_calls'","item_key='another-message'","sort_seq=sort_seq+1","sort_sub=-1","turn_anchor_seq=seq")) malformed {
            sql.execSQL("UPDATE conversation_items SET $change WHERE conversation_id=? AND seq=?",arrayOf(session,notice.seq))
        }
        malformed {sql.execSQL("UPDATE messages SET proactive_decision_id=? WHERE conversation_id=? AND message_id=?",arrayOf(byteArrayOf(65,66),session,id))}
        malformed {sql.execSQL("UPDATE messages SET proactive_thread_key=? WHERE conversation_id=? AND message_id=?",arrayOf(byteArrayOf(65,66),session,id))}
    }

    @Test fun extraProtocolPartsAttachmentsAndMalformedOptionsRemainUnsafeIncludingMissionCards() {
        val notice=notice();val id=notice.messageId
        for(kind in listOf("unknown-protocol",ConversationInput.PROVENANCE_PART_KIND,ProactiveReplyAcceptance.PART_KIND,CentralSubmissionRequest.PART_KIND,MissionRef.PART_KIND)) malformed {
            sql.execSQL("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,1,?,'{}')",arrayOf(session,id,kind))
        }
        malformed {sql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=?",arrayOf(byteArrayOf(65),session,id))}
        malformed {sql.execSQL("UPDATE message_parts SET kind='reasoning' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        malformed {sql.execSQL("DELETE FROM message_parts WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        malformed {sql.execSQL("UPDATE message_parts SET ordinal=1 WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        malformed {sql.execSQL("INSERT INTO message_attachments(conversation_id,message_id,ordinal,attachment_id,name,size_bytes,kind,relative_path) VALUES(?,?,0,'untrusted','a.txt',1,'document','a.txt')",arrayOf(session,id))}
        malformed {sql.execSQL("UPDATE proactive_suggested_replies SET ordinal=7 WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        malformed {sql.execSQL("UPDATE proactive_suggested_replies SET ordinal='not-an-integer' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        malformed {sql.execSQL("UPDATE proactive_suggested_replies SET text=? WHERE conversation_id=? AND message_id=?",arrayOf(byteArrayOf(65),session,id))}
    }

    @Test fun exactHiddenConsumedAndTranslationMarkersRemainProtectedAndBadMarkersFail() {
        val notice=notice();val id=notice.messageId
        runBlocking {repo.consumeSuggestedReplies(session,id);repo.hideAssistant(session,id,"assistant_deleted");repo.hideTranslation(session,id)}
        val stored=bridge.readMessageSync(session,id)!!
        assertFalse(projection().unsafeAnchor);assertTrue(source(id) in projection().protectedSources)
        assertTrue(contextTurns().none {it.source()==source(id)});bridge.validateProtectedContextSync(session,contextTurns())
        for(change in listOf("hidden_seq=NULL","hidden_kind=NULL","hidden_kind='invented'","hidden_seq=seq","suggested_replies_consumed_seq=seq","translation_hidden_seq=seq")) malformed {
            sql.execSQL("UPDATE messages SET $change WHERE conversation_id=? AND message_id=?",arrayOf(session,id))
        }
        for(marker in listOf(stored.hiddenSeq!!,stored.suggestedRepliesConsumedSeq!!,stored.translationHiddenSeq!!)) {
            malformed {sql.execSQL("UPDATE conversation_items SET item_key='different-notice' WHERE conversation_id=? AND seq=?",arrayOf(session,marker))}
            malformed {sql.execSQL("UPDATE conversation_items SET item_kind='message' WHERE conversation_id=? AND seq=?",arrayOf(session,marker))}
        }
        val second=notice("task:regenerated")
        runBlocking {repo.hideAssistant(session,second.messageId,"assistant_regenerated")}
        assertFalse(projection().unsafeAnchor);assertTrue(source(second.messageId) in projection().protectedSources)
        bridge.validateProtectedContextSync(session,contextTurns())
    }

    @Test fun laterBeforeInsertionKeepsNativeNoticeAtItsActualShiftedSortPosition() {
        val owner=runBlocking {(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Earlier USER")))) as ChatDbResult.Success).value}
        val notice=notice()
        val inserted=runBlocking {repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Inserted before notice")),
            status="COMPLETED",ownerUserMessageId=owner.messageId,beforeMessageId=notice.messageId))}
        assertTrue(inserted is ChatDbResult.Success)
        val protection=projection();assertFalse(protection.unsafeAnchor)
        assertEquals(InteractionSourcePosition(notice.seq,1,notice.seq),protection.sourcePositions[source(notice.messageId)])
        bridge.validateProtectedContextSync(session,contextTurns())
        assertTrue(source(notice.messageId) in protection.protectedSources)
    }

    @Test fun discriminatorBodyAndOptionChangesInvalidateTheExactProtectionFingerprint() {
        val notice=notice();val id=notice.messageId
        validMutationChangesFingerprint {sql.execSQL("UPDATE messages SET proactive_decision_id='another-valid-decision' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        validMutationChangesFingerprint {sql.execSQL("UPDATE messages SET proactive_thread_key='another-valid-thread' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))}
        validMutationChangesFingerprint {sql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=?",arrayOf("Changed\u0000body 😀",session,id))}
        validMutationChangesFingerprint {sql.execSQL("UPDATE proactive_suggested_replies SET label=? WHERE conversation_id=? AND message_id=?",arrayOf("Changed\u0000label 😀",session,id))}
        validMutationChangesFingerprint {sql.execSQL("UPDATE proactive_suggested_replies SET text=? WHERE conversation_id=? AND message_id=?",arrayOf("Changed\u0000option 😀",session,id))}
        val held=projection();val oldTurns=contextTurns()
        sql.execSQL("UPDATE message_parts SET payload='Changed notice body' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))
        assertThrows(IllegalStateException::class.java) {bridge.validateProtectedContextSync(session,oldTurns)}
        val result=runBlocking {MemoryTimelineRepository(db).appendCompactionPrepared(CompactionInput(session,"must not commit",Long.MAX_VALUE,summarizedMessages=1),null,null,"central",held)}
        assertEquals(ChatDbResult.Conflict("context_protection_changed"),result)
        assertEquals(0L,scalar("SELECT COUNT(*) FROM compactions"))
    }

    @Test fun exactNoticeComponentKeepsNulAndUnpairedUtf16Distinct() {
        fun component(value:String)=ProactiveReplyAcceptance.digestFields("jarvys-native-standalone-notice-protection-v1","part","0","text",value,"option","0",value,value)
        assertNotEquals(component("x\uD800"),component("x\uD801"))
        assertNotEquals(component("x\uDC00"),component("x\uDC01"))
        assertNotEquals(component("x\u0000y"),component("xy"))
        assertNotEquals(component("😀"),component("�"))
        assertEquals(component("a\u0000\uD800😀"),component("a\u0000\uD800😀"))
    }

    @Test fun acceptedReplyStaysContextValidAfterNativeHideAndExactSourceMutationStillFailsRoot() {
        val notice=notice();val captured=bridge.readProactiveReplySourceSync(notice.messageId,null)!!
        val kind=ProactiveReplyAcceptance.OPTION
        val actionId=ProactiveReplyAcceptance.actionId(captured.physicalGeneration,session,captured.threadKey,captured.sourceMessageId,kind)
        val accepted=runBlocking {ProactiveReplyWriter(db,repo).appendInTransaction(captured.physicalGeneration,captured.sourceMessageId,captured.threadKey,
            captured.sourceFingerprint,kind,0,captured.suggestedReplies[0].text.trim(),actionId,ProactiveReplyAcceptance.submissionId(actionId),"protection-test",{})!!}
        assertFalse(projection().unsafeAnchor);bridge.validateProtectedContextSync(session,contextTurns())
        runBlocking {repo.hideAssistant(session,notice.messageId,"assistant_deleted")}
        assertFalse(projection().unsafeAnchor);bridge.validateProtectedContextSync(session,contextTurns())
        bridge.validateProactiveReplySourceSync(session,accepted.snapshot.userMessageId)
        val before=projection().fingerprint
        sql.execSQL("UPDATE message_parts SET payload='Changed accepted source' WHERE conversation_id=? AND message_id=?",arrayOf(session,notice.messageId))
        assertNotEquals(before,projection().fingerprint)
        assertThrows(IllegalStateException::class.java) {bridge.validateProactiveReplySourceSync(session,accepted.snapshot.userMessageId)}
        assertThrows(IllegalStateException::class.java) {runBlocking {captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId)}}
        assertEquals(1L,scalar("SELECT COUNT(*) FROM central_turn_submissions"))
    }
}

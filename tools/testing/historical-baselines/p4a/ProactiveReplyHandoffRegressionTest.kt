package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.testChatSql
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.proactive.ProactiveReplyClaim
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadKey
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P4A baseline regressions at the actual native prepare boundary, before any service launch.
 * These assert the desired conservation/recovery contract, so the unfixed implementation is RED.
 * Room close/reopen is deterministic host evidence, not a physical-process or provider test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
class ProactiveReplyHandoffRegressionTest {
    @get:Rule val storage = ChatTestStore()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "p4a-native-reply-thread"
    private val selectedText = "Review this notice"

    private inner class Fixture {
        val files = storage.newFilesDirectory()
        val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(files, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        init {
            // Register the real dispatcher context first, preserving its isolated Registry location.
            ChatDatabaseBridge.getInstance(context)
            storage.localRunStore(files)
            FreshStorageBootstrap.ensure(context)
            AgentRunUiState.resetSession(session)
        }
        val store get() = LocalRunStore(context)
        val database get() = JarvysChatDatabaseProvider.getInstance(context)
        val sql get() = database.testChatSql

        fun notice(): String = store.appendProactiveAssistantMessageIfAbsent(
            session, "p4a-baseline-decision", "A synthetic notice to answer",
            listOf(ProactiveSuggestedReply("Review", selectedText)), thread,
        )

        fun reopen() {
            val previous = database
            ChatDatabaseBridge.closeInstanceForTests(files)
            ChatDatabaseBridge.getInstance(context)
            store.readCentralSubmissions(session)
            assertNotSame("Must inspect a genuinely reopened native Room instance", previous, database)
        }

        fun rejectUserInsert() {
            sql.execSQL("""
                CREATE TRIGGER p4a_reject_user_insert BEFORE INSERT ON messages
                WHEN NEW.role='user'
                BEGIN SELECT RAISE(ABORT,'p4a_user_insert_rejected'); END
            """.trimIndent())
        }

        fun removeUserInsertRejection() = sql.execSQL("DROP TRIGGER p4a_reject_user_insert")

        fun userCount(): Long = sql.query("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='user'",
            arrayOf(session)).use { check(it.moveToFirst()); it.getLong(0) }

        fun nextSequence(): Long = sql.query("SELECT next_seq FROM conversations WHERE id=?",
            arrayOf(session)).use { check(it.moveToFirst()); it.getLong(0) }

        /** All rows/columns in the acceptance's existing write set, including counters and spine. */
        fun durableState(): Map<String, List<List<Pair<String, String?>>>> = linkedMapOf(
            "conversations" to "id",
            "messages" to "conversation_id,seq",
            "message_parts" to "conversation_id,message_id,ordinal",
            "proactive_suggested_replies" to "conversation_id,message_id,ordinal",
            "conversation_items" to "conversation_id,seq",
            "central_turn_submissions" to "conversation_id,submission_id",
        ).mapValues { (table, order) ->
            sql.query("SELECT * FROM $table ORDER BY $order").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                        val value = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }
                            else -> cursor.getString(index)
                        }
                        cursor.getColumnName(index) to value
                    })
                }
            }
        }
    }

    @Test fun suggestedReplyUserInsertFailureRollsBackConsumptionAndAllDurableRowsAfterReopen() {
        val fixture = Fixture()
        val noticeId = fixture.notice()
        assertNull(fixture.store.readStoredMessage(session, noticeId)!!.suggestedRepliesConsumedSeq)
        val before = fixture.durableState()
        val beforeNextSequence = fixture.nextSequence()

        fixture.rejectUserInsert()
        val first = try {
            runCatching { ProactiveInteractionDispatcher.prepareSuggestedReply(fixture.context, noticeId, 0) }
        } finally {
            // A test trigger must not become a schema/provenance failure on reopen.
            fixture.removeUserInsertRejection()
        }
        fixture.reopen()
        val reopenedNotice = fixture.store.readStoredMessage(session, noticeId)!!
        val after = fixture.durableState()
        val afterNextSequence = fixture.nextSequence()
        val reopenedUsers = fixture.userCount()
        val reopenedSubmissions = fixture.store.readCentralSubmissions(session)

        // On baseline this exact repeat returns null: the first failed acceptance consumed it.
        // On a conserved implementation it reaches the same deterministic USER failure again.
        fixture.rejectUserInsert()
        val repeated = try {
            runCatching { ProactiveInteractionDispatcher.prepareSuggestedReply(fixture.context, noticeId, 0) }
        } finally {
            fixture.removeUserInsertRejection()
        }
        val evidence = "P4A_OPTION_REOPEN consumedSeq=${reopenedNotice.suggestedRepliesConsumedSeq} " +
            "users=$reopenedUsers submissions=${reopenedSubmissions.size} " +
            "nextSeqBefore=$beforeNextSequence nextSeqAfter=$afterNextSequence " +
            "repeatReturnedNull=${repeated.isSuccess && repeated.getOrNull() == null} " +
            "repeatHitUserInsert=${hitUserInsertRejection(repeated)}"
        println(evidence)

        assertTrue("Must fail at the actual USER INSERT, not fixture setup: $first", hitUserInsertRejection(first))
        assertEquals("USER INSERT failure must conserve consumption, counters, parts and spine. $evidence", before, after)
        assertEquals("The failed repeat must conserve that same state", before, fixture.durableState())
        assertNull("No accepted USER means this option remains unconsumed", reopenedNotice.suggestedRepliesConsumedSeq)
        assertEquals(0L, reopenedUsers)
        assertTrue(reopenedSubmissions.isEmpty())
        assertTrue("A fresh selection remains eligible after rollback. $evidence", hitUserInsertRejection(repeated))
    }

    @Test fun inlinePrepareWithoutLaunchRetainsPreparedRecoveryForExactUserAfterReopen() {
        val fixture = Fixture()
        val noticeId = fixture.notice()
        assertTrue("The existing Central schema/admission must be active", CentralAvailabilityPolicy.admissionEnabled())
        assertTrue(fixture.store.centralSubmissionSchemaReady())

        // Invoke production validation and USER preparation only. Never invoke submit/launch/service.
        val prepared = requireNotNull(ProactiveInteractionDispatcher.prepareInlineReply(
            fixture.context, noticeId, ProactiveThreadKey.fingerprint(thread), "  Reply from the notification  ",
        ))
        val accepted = fixture.store.readStoredMessage(session, prepared.userMessageId)!!
        assertEquals("user", accepted.role)
        assertEquals("Reply from the notification", accepted.text)
        assertEquals(thread, accepted.proactiveThreadKey)
        assertNull("Inline preserves the native latest-prior-notice referent", accepted.proactiveReplyToMessageId)
        val beforeReopen = fixture.durableState()

        fixture.reopen()
        assertTrue("A missing display must not be explained by an absent schema", fixture.store.centralSubmissionSchemaReady())
        val submissions = fixture.store.readCentralSubmissions(session)
        val events = fixture.store.readRecentConversationTimeline(session)
        val event = events.single { it.kind == "user" && it.messageId == prepared.userMessageId }
        val display = event.centralAvailability
        val evidence = "P4A_INLINE_REOPEN users=${fixture.userCount()} submissions=${submissions.size} " +
            "matchingSubmissions=${submissions.count { it.userMessageId == prepared.userMessageId }} " +
            "displayState=${display?.state} canRetry=${display?.canRetry}"
        println(evidence)

        assertEquals("Reopen and UI reads must not mutate or create acceptance", beforeReopen, fixture.durableState())
        assertEquals(accepted, fixture.store.readStoredMessage(session, prepared.userMessageId))
        assertEquals(1L, fixture.userCount())
        assertEquals("Accepted inline USER must retain one durable Central handoff. $evidence", 1,
            submissions.count { it.userMessageId == prepared.userMessageId })
        val submission = submissions.single { it.userMessageId == prepared.userMessageId }
        assertEquals("PREPARED", submission.state)
        assertEquals("Reopen cannot claim or run an accepted reply", null, submission.claimRunId)
        assertNotNull("Existing exact USER recovery projection must survive. $evidence", display)
        assertEquals("PREPARED", display!!.state)
        assertTrue("No launch happened, so recovery needs an explicit native action", display.canRetry)
        assertEquals(submission, display.submission)
        assertTrue("Exact retry must carry its existing display fence", display.recoveryFence().isNotBlank())
    }

    private fun hitUserInsertRejection(result: Result<ProactiveReplyClaim?>): Boolean =
        generateSequence(result.exceptionOrNull()) { it.cause }
            .any { it.message.orEmpty().contains("p4a_user_insert_rejected") }
}

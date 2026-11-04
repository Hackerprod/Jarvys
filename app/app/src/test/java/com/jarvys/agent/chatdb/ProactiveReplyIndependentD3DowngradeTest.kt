package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Storage-only receipt conservation regression; no live owner, grant, policy override or provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveReplyIndependentD3DowngradeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var database: JarvysChatDatabase
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    @Before fun open() { database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "reviewer-d3.db"), true, true) }
    @After fun close() { if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database); worker.shutdownNow() }
    private fun <T> work(body: suspend () -> T): T = worker.submit<T> { runBlocking { body() } }.get(60, TimeUnit.SECONDS)

    @Test fun historicalMissingLookupStaysNullWhileRootCaptureRequiresActualUser() = work {
        val repository = ConversationRepository(database)
        assertNull(database.withChatReadTransaction { ProactiveReplySourceValidation.validateContext(database, central, "missing-message") })
        assertNull(repository.readCompactedProactiveReferent(central, "missing-message", Long.MAX_VALUE))
        assertNull(ConversationReader(database).readCompactedProactiveReferent(central, "missing-message"))
        assertTrue(runCatching { captureCentralUserRootDigest(database, central, "missing-message") }.isFailure)
    }

    @Test fun coherentProofRemovalAndUserMetadataStrippingCannotDowngradeTypedReceiptToOrdinaryContext() = work {
        val repository = ConversationRepository(database)
        ConnectionProjectionRepository(database).generation()
        for (removeProof in listOf(true, false)) {
            val thread = "reviewer-downgrade-$removeProof"
            val notice = (repository.appendProactiveAssistantMessageIfAbsent(central, "reviewer-$removeProof", "Pinned notice", thread,
                listOf(SuggestedReplyInput("Review", "Review this notice"))) as ChatDbResult.Success).value
            val source = database.withChatReadTransaction { requireNotNull(ProactiveReplySourceValidation.read(database, notice.messageId, thread)) }
            val action = ProactiveReplyAcceptance.actionId(source.physicalGeneration, central, thread, notice.messageId, ProactiveReplyAcceptance.OPTION)
            val accepted = requireNotNull(ProactiveReplyWriter(database, repository).appendInTransaction(source.physicalGeneration,
                notice.messageId, thread, source.sourceFingerprint, ProactiveReplyAcceptance.OPTION, 0, "Review this notice", action,
                ProactiveReplyAcceptance.submissionId(action), "reviewer-process") {})
            val user = accepted.snapshot.userMessageId
            assertEquals(accepted.snapshot.sourceSha256, captureCentralUserRootDigest(database, central, user))
            if (removeProof) database.testChatSql.execSQL("DELETE FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",
                arrayOf(central, user, ProactiveReplyAcceptance.PART_KIND))
            else database.testChatSql.execSQL("UPDATE message_parts SET kind='unknown_future_acceptance' WHERE conversation_id=? AND message_id=? AND kind=?",
                arrayOf(central, user, ProactiveReplyAcceptance.PART_KIND))
            database.testChatSql.execSQL("UPDATE messages SET proactive_thread_key=NULL,proactive_reply_to_message_id=NULL,stable_id=? WHERE conversation_id=? AND message_id=?",
                arrayOf("ordinary-looking-$removeProof", central, user))
            // Durable receipt remains untouched and identifies the original positive P4 source.
            assertEquals(accepted.snapshot, database.withChatReadTransaction { CentralTurnSubmissionRepository(database).find(central, accepted.snapshot.submissionId) })
            val rejected = linkedMapOf<String, Boolean>()
            rejected["validateUser"] = runCatching { database.withChatReadTransaction { ProactiveReplySourceValidation.validateUser(database, central, user) } }.isFailure
            rejected["validateContext"] = runCatching { database.withChatReadTransaction { ProactiveReplySourceValidation.validateContext(database, central, user) } }.isFailure
            rejected["rootCapture"] = runCatching { captureCentralUserRootDigest(database, central, user) }.isFailure
            rejected["repositoryReferent"] = runCatching { repository.readCompactedProactiveReferent(central, user, Long.MAX_VALUE) }.isFailure
            rejected["readerReferent"] = runCatching { ConversationReader(database).readCompactedProactiveReferent(central, user) }.isFailure
            rejected["claim"] = runCatching { database.withChatWriteTransaction { CentralTurnSubmissionRepository(database).claimPreparedInTransaction(
                accepted.snapshot, "reviewer-process", "reviewer-run", 7, setOf(accepted.snapshot.submissionId)) } }.isFailure
            assertTrue("All entrypoints must reject coherent downgrade (delete=$removeProof): $rejected", rejected.values.all { it })
        }
    }
}

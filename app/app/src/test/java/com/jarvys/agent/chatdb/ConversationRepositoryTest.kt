package com.jarvys.agent.chatdb

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "chatdb-repository-test").apply { isDaemon = true } }
    private lateinit var database: JarvysChatDatabase
    private lateinit var repository: ConversationRepository

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "repository.db"))
        repository = ConversationRepository(database)
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        testExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        testExecutor.submit<T> { runBlocking { block() } }.get(45, TimeUnit.SECONDS)

    @Test fun ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection() = onDatabaseThread {
        val first = success(repository.ensureConversation("lifecycle"))
        val again = success(repository.ensureConversation("lifecycle", "different-kind"))
        assertEquals("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <again.created_at> to equal <first.created_at>", first.created_at, again.created_at)
        assertEquals("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <again.kind> to equal <\"chat\">", "chat", again.kind)

        assertEquals("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <success(repository.deleteConversation(\"lifecycle\"))> to equal <true>", true, success(repository.deleteConversation("lifecycle")))
        assertEquals("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <success(repository.deleteConversation(\"lifecycle\"))> to equal <false>", false, success(repository.deleteConversation("lifecycle")))
        assertTrue("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <repository.isTombstoned(\"lifecycle\")> to be true", repository.isTombstoned("lifecycle"))
        assertTrue("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <repository.ensureConversation(\"lifecycle\") === ChatDbResult.Tombstoned> to be true", repository.ensureConversation("lifecycle") === ChatDbResult.Tombstoned)

        assertEquals("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <success(repository.deleteConversation(\"never-created\"))> to equal <false>", false, success(repository.deleteConversation("never-created")))
        assertTrue("ensureDeleteAndTombstoneAreIdempotentAndPreventResurrection: expected <repository.ensureConversation(\"never-created\") === ChatDbResult.Tombstoned> to be true", repository.ensureConversation("never-created") === ChatDbResult.Tombstoned)
    }

    @Test fun legacyStableMessageIdentityUsesSessionAndAbsoluteIndex() {
        val session = "legacy.session-7"
        val absoluteIndex = 42L
        val expected = "legacy-${UUID.nameUUIDFromBytes("$session\u0000$absoluteIndex".toByteArray(StandardCharsets.UTF_8))}"
        val first = ConversationRepository.legacyStableMessageId(session, absoluteIndex)
        assertEquals("legacyStableMessageIdentityUsesSessionAndAbsoluteIndex: expected <first> to equal <expected>", expected, first)
        assertEquals("legacyStableMessageIdentityUsesSessionAndAbsoluteIndex: expected <ConversationRepository.legacyStableMessageId(session, absoluteIndex)> to equal <first>", first, ConversationRepository.legacyStableMessageId(session, absoluteIndex))
        assertTrue("legacyStableMessageIdentityUsesSessionAndAbsoluteIndex: expected <first != ConversationRepository.legacyStableMessageId(session, absoluteIndex + 1)> to be true", first != ConversationRepository.legacyStableMessageId(session, absoluteIndex + 1))
    }

    @Test fun concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous() = onDatabaseThread {
        success(repository.ensureConversation("concurrent"))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 36).map { index -> pool.submit(Callable {
                runBlocking {
                    repository.appendMessage(AppendMessageInput(
                        conversationId = "concurrent",
                        role = if (index % 3 == 0) ConversationRepository.ROLE_USER else ConversationRepository.ROLE_ASSISTANT,
                        parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "message-$index")),
                    ))
                }
            }) }
            futures.forEach { assertTrue("concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous: expected <it.get(45, TimeUnit.SECONDS) is ChatDbResult.Success> to be true", it.get(45, TimeUnit.SECONDS) is ChatDbResult.Success) }
        } finally {
            pool.shutdownNow()
        }
        val rows = database.openHelper.writableDatabase.query(
            "SELECT seq,message_index FROM messages WHERE conversation_id=? ORDER BY seq", arrayOf("concurrent"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
        assertEquals("concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous: expected <rows.map { it.first }> to equal <(1L..36L).toList()>", (1L..36L).toList(), rows.map { it.first })
        assertEquals("concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous: expected <rows.map { it.second }.sorted()> to equal <(0L until 36L).toList()>", (0L until 36L).toList(), rows.map { it.second }.sorted())
        val conversation = success(repository.ensureConversation("concurrent"))
        assertEquals("concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous: expected <conversation.next_seq> to equal <37L>", 37L, conversation.next_seq)
        assertEquals("concurrentAppendsKeepSequenceAndAbsoluteMessageIndicesContiguous: expected <conversation.message_count> to equal <36L>", 36L, conversation.message_count)
    }

    @Test fun steeringDispatchReordersMessageIndexAndPagesRemainKeysetAligned() = onDatabaseThread {
        success(repository.ensureConversation("steering"))
        val u1 = append("steering", "user", "u1")
        val a1 = append("steering", "assistant", "a1")
        val u2 = append("steering", "user", "u2")
        val a2 = append("steering", "assistant", "a2")
        val interrupt = success(repository.appendInterruptRequest("steering", "follow-up", listOf("skill"), true))
        assertEquals("the steering request keeps its ordered skills", listOf("skill"), interrupt.skills)
        val dispatched = success(repository.dispatchInterruptRequest("steering", interrupt.requestId))
        assertTrue("the first dispatch inserts the steering message", dispatched.inserted)
        assertFalse("dispatching the same request again is idempotent", success(repository.dispatchInterruptRequest("steering", interrupt.requestId)).inserted)
        assertEquals("the dispatch message contains the steering text", "follow-up", dispatched.message.text)
        val inBetween = success(repository.appendMessage(AppendMessageInput(
            conversationId = "steering", role = "assistant", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "before follow-up")),
            beforeMessageId = interrupt.requestId, status = "COMPLETED",
        )))
        val u3 = append("steering", "user", "u3")
        val a3 = append("steering", "assistant", "a3")

        val ordered = database.openHelper.writableDatabase.query(
            "SELECT message_id FROM messages WHERE conversation_id=? ORDER BY message_index", arrayOf("steering"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals("message indices follow the logical order after inserting before steering", listOf(u1.messageId, a1.messageId, u2.messageId, a2.messageId, inBetween.messageId, interrupt.requestId, u3.messageId, a3.messageId), ordered)

        val tail = successWindow(repository.readTailWindow("steering", 1))
        assertEquals("the tail page cursor is the latest user message sequence", u3.seq, tail.olderCursorSeq)
        assertTrue("the tail page contains the latest user message", tail.slots.any { it.item.item_key == u3.messageId })
        val previous = repository.readWindowBefore("steering", u3.seq, 1)
        assertTrue("the previous page includes the assistant inserted before steering", previous.slots.any { it.item.item_key == inBetween.messageId })
        assertTrue("the previous page includes the steering request marker", previous.slots.any { it.item.item_key == interrupt.requestId })
        assertTrue("the previous page excludes its user-message cursor", previous.slots.none { it.item.item_key == u3.messageId })
        assertEquals("the previous page cursor is the preceding user message sequence", u2.seq, previous.olderCursorSeq)

        val previousAgain = repository.readWindowBefore("steering", u2.seq, 1)
        assertTrue("paging before u2 reaches the earlier u1 message", previousAgain.slots.any { it.item.item_key == u1.messageId })

        // Cursor is the dispatch message itself: the assistant has a later physical seq but sorts before it.
        val beforeDispatch = repository.readWindowBefore("steering", dispatched.message.seq, 1)
        assertTrue("a message sorted before dispatch is visible before the dispatch cursor", beforeDispatch.slots.any { it.item.item_key == inBetween.messageId })
        assertTrue("the strict cursor excludes the dispatch message row; the request marker may share its key", beforeDispatch.slots.none {
            it.item.item_kind == "message" && it.item.item_key == dispatched.message.messageId
        })

        val expectedSpine = database.openHelper.writableDatabase.query(
            "SELECT seq FROM conversation_items WHERE conversation_id=? ORDER BY sort_seq,sort_sub,seq", arrayOf("steering"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (turns in 1..3) {
            val pages = mutableListOf<MessageWindow>()
            var page = repository.readTailWindow("steering", turns)
            while (true) {
                pages += page
                val cursor = page.olderCursorSeq ?: break
                assertEquals("each older page starts at its cursor sequence", cursor, page.slots.first().item.seq)
                page = repository.readWindowBefore("steering", cursor, turns)
            }
            val pagedSequences = pages.asReversed().flatMap { window -> window.slots.map { it.item.seq } }
            assertEquals("all spine rows are paged once for turns=$turns", expectedSpine, pagedSequences)
            assertEquals("keyset paging does not repeat a spine sequence for turns=$turns", pagedSequences.size, pagedSequences.distinct().size)
        }
    }

    @Test fun oldestWindowRetainsUnanchoredPrefixBeforeFirstUserAnchor() = onDatabaseThread {
        success(repository.ensureConversation("leading-prefix"))
        val markerSeq = success(repository.appendDecisionRequest(
            "leading-prefix", "early-decision", "Early", "Before the first message", false,
            listOf(DecisionOptionInput("ok", "OK")),
        ))
        val firstUser = append("leading-prefix", "user", "first turn")
        val secondUser = append("leading-prefix", "user", "second turn")

        val oldestPage = repository.readWindowBefore("leading-prefix", secondUser.seq, turns = 1)
        assertEquals("oldestWindowRetainsUnanchoredPrefixBeforeFirstUserAnchor: expected <oldestPage.slots.map { it.item.seq }> to equal <listOf(markerSeq, firstUser.seq)>", listOf(markerSeq, firstUser.seq), oldestPage.slots.map { it.item.seq })
        assertEquals("oldestWindowRetainsUnanchoredPrefixBeforeFirstUserAnchor: expected <oldestPage.slots.first().item.item_kind> to equal <\"user_decision_request\">", "user_decision_request", oldestPage.slots.first().item.item_kind)
        assertNull("oldestWindowRetainsUnanchoredPrefixBeforeFirstUserAnchor: expected <oldestPage.olderCursorSeq> to be null", oldestPage.olderCursorSeq)
    }

    @Test fun proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules() = onDatabaseThread {
        success(repository.ensureConversation("markers"))
        val user = append("markers", "user", "prompt")
        val proactive = success(repository.appendProactiveAssistantMessageIfAbsent(
            "markers", "decision-1", "suggestions", "thread-1", listOf(SuggestedReplyInput("Yes", "yes")),
        ))
        val duplicate = success(repository.appendProactiveAssistantMessageIfAbsent(
            "markers", "decision-1", "different payload", "thread-2", listOf(SuggestedReplyInput("No", "no")),
        ))
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <duplicate.messageId> to equal <proactive.messageId>", proactive.messageId, duplicate.messageId)
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <duplicate.text> to equal <\"suggestions\">", "suggestions", duplicate.text)
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <duplicate.suggestedReplies.size> to equal <1>", 1, duplicate.suggestedReplies.size)

        success(repository.setTranslation("markers", proactive.messageId, "es", "primera"))
        success(repository.setTranslation("markers", proactive.messageId, "es", "ultima"))
        success(repository.hideTranslation("markers", proactive.messageId))
        assertNull("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.getMessage(\"markers\", proactive.messageId)?.translation> to be null", repository.getMessage("markers", proactive.messageId)?.translation)
        val thumbsUp = "\uD83D\uDC4D"
        val reactionAdded = success(repository.setReaction("markers", user.stableId, thumbsUp))
        assertTrue("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <reactionAdded.changed> to be true", reactionAdded.changed)
        val repeatedReaction = success(repository.setReaction("markers", user.stableId, thumbsUp))
        assertFalse("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repeatedReaction.changed> to be false", repeatedReaction.changed)
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repeatedReaction.seq> to equal <reactionAdded.seq>", reactionAdded.seq, repeatedReaction.seq)
        assertTrue("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.setReaction(\"markers\", user.stableId, \"not-an-emoji\") is ChatDbResult.Invalid> to be true", repository.setReaction("markers", user.stableId, "not-an-emoji") is ChatDbResult.Invalid)
        val reactionRemoved = success(repository.setReaction("markers", user.stableId, ""))
        assertTrue("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <reactionRemoved.changed> to be true", reactionRemoved.changed)
        assertFalse("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <success(repository.setReaction(\"markers\", user.stableId, \"\")).changed> to be false", success(repository.setReaction("markers", user.stableId, "")).changed)
        val reaction = database.openHelper.writableDatabase.query(
            "SELECT emoji FROM message_reactions WHERE conversation_id=? AND target_stable_id=?", arrayOf("markers", user.stableId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <reaction> to equal <\"\">", "", reaction)

        success(repository.hideAssistant("markers", proactive.messageId, "assistant_regenerated"))
        success(repository.hideAssistant("markers", proactive.messageId, "assistant_deleted"))
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.getMessage(\"markers\", proactive.messageId)?.hiddenKind> to equal <\"assistant_deleted\">", "assistant_deleted", repository.getMessage("markers", proactive.messageId)?.hiddenKind)
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.firstUserMessage(\"markers\")?.messageId> to equal <user.messageId>", user.messageId, repository.firstUserMessage("markers")?.messageId)
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.messageCounts(\"markers\").total> to equal <2L>", 2L, repository.messageCounts("markers").total)
        val consumedSeq = success(repository.consumeSuggestedReplies("markers", proactive.messageId))
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <success(repository.consumeSuggestedReplies(\"markers\", proactive.messageId))> to equal <consumedSeq>", consumedSeq, success(repository.consumeSuggestedReplies("markers", proactive.messageId)))
        assertEquals("proactiveDecisionIsIdempotentAndMarkersFollowTheirLedgerRules: expected <repository.getMessage(\"markers\", proactive.messageId)?.suggestedRepliesConsumedSeq> to equal <consumedSeq>", consumedSeq, repository.getMessage("markers", proactive.messageId)?.suggestedRepliesConsumedSeq)
    }

    @Test fun userDecisionRequestOptionsAndLatestResolutionAreProjected() = onDatabaseThread {
        success(repository.ensureConversation("decisions"))
        success(repository.appendDecisionRequest(
            conversationId = "decisions", decisionId = "choice", title = "Choose", body = "Pick one", allowDismiss = true,
            options = listOf(DecisionOptionInput("yes", "Yes", "Continue"), DecisionOptionInput("no", "No", role = "decline")),
        ))
        val options = database.openHelper.writableDatabase.query(
            "SELECT option_id,label FROM user_decision_options WHERE conversation_id=? AND decision_id=? ORDER BY ordinal",
            arrayOf("decisions", "choice"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        assertEquals("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <options> to equal <listOf(\"yes\" to \"Yes\", \"no\" to \"No\")>", listOf("yes" to "Yes", "no" to "No"), options)
        val firstResolution = success(repository.resolveDecision("decisions", "choice", "selected", "yes", "Yes"))
        val lastResolution = success(repository.resolveDecision("decisions", "choice", "dismissed", null, null))
        assertTrue("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <lastResolution > firstResolution> to be true", lastResolution > firstResolution)

        val reopenedSeq = success(repository.appendDecisionRequest(
            "decisions", "choice", "Choose again", "Retry", false, listOf(DecisionOptionInput("retry", "Retry")),
        ))
        assertTrue("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <reopenedSeq > lastResolution> to be true", reopenedSeq > lastResolution)
        val row = database.openHelper.writableDatabase.query(
            "SELECT seq,title,status,option_id,resolved_seq FROM user_decisions WHERE conversation_id=? AND decision_id=?",
            arrayOf("decisions", "choice"),
        ).use { c -> assertTrue("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <c.moveToFirst()> to be true", c.moveToFirst()); listOf(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getLong(4)) }
        assertEquals("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <row[0]> to equal <reopenedSeq>", reopenedSeq, row[0])
        assertEquals("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <row[1]> to equal <\"Choose again\">", "Choose again", row[1])
        assertNull("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <row[2]> to be null", row[2])
        assertNull("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <row[3]> to be null", row[3])
        assertNull("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <row[4]> to be null", row[4])
        assertTrue("userDecisionRequestOptionsAndLatestResolutionAreProjected: expected <repository.appendDecisionRequest(\"missing\", \"choice\", \"\", \"\", false, emptyList()) is Ch...> to be true", repository.appendDecisionRequest("missing", "choice", "", "", false, emptyList()) is ChatDbResult.NotFound)
    }

    @Test fun snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap() = onDatabaseThread {
        success(repository.ensureConversation("atomic"))
        val stream = success(repository.appendMessage(AppendMessageInput(
            conversationId = "atomic", role = "assistant", status = "STREAMING",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "partial")),
        )))
        success(repository.finalizeMessage("atomic", stream.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "final")), "COMPLETED"))
        val late = success(repository.updateMessageSnapshot("atomic", stream.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "late")), "STREAMING"))
        assertEquals("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <late.text> to equal <\"final\">", "final", late.text)
        assertEquals("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <late.status> to equal <\"COMPLETED\">", "COMPLETED", late.status)

        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_abort_part BEFORE INSERT ON message_parts " +
                "BEGIN SELECT RAISE(ABORT,'injected_repository_failure'); END",
        )
        try {
            repository.appendMessage(AppendMessageInput(
                conversationId = "atomic", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "must rollback")),
            ))
            throw AssertionError("append should fail at the injected point")
        } catch (failure: android.database.sqlite.SQLiteConstraintException) {
            assertTrue("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <failure.message.orEmpty().contains(\"injected_repository_failure\")> to be true", failure.message.orEmpty().contains("injected_repository_failure"))
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER test_abort_part")
        val finalMessage = append("atomic", "user", "after rollback")
        assertEquals("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <finalMessage.seq> to equal <stream.seq + 1L>", stream.seq + 1L, finalMessage.seq)
        assertEquals("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <finalMessage.messageIndex> to equal <1L>", 1L, finalMessage.messageIndex)
        assertEquals("snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap: expected <success(repository.ensureConversation(\"atomic\")).message_count> to equal <2L>", 2L, success(repository.ensureConversation("atomic")).message_count)
    }

    @Test fun messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics() = onDatabaseThread {
        success(repository.ensureConversation("assistant-only-updates"))
        val user = success(repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "user", listOf(MessagePartInput(MessagePartKinds.TEXT, "prompt")), durationMillis = -5,
        )))
        assertNull("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <user.durationMillis> to be null", user.durationMillis)
        assertTrue("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <repository.updateMessageSnapshot( \"assistant-only-updates\", user.messageId, listOf(Mess...> to be true", repository.updateMessageSnapshot(
            "assistant-only-updates", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "changed")),
        ) is ChatDbResult.Invalid)
        assertTrue("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <repository.finalizeMessage( \"assistant-only-updates\", user.messageId, listOf(MessagePar...> to be true", repository.finalizeMessage(
            "assistant-only-updates", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "changed")), "COMPLETED",
        ) is ChatDbResult.Invalid)
        assertTrue("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <repository.setTranslation(\"assistant-only-updates\", user.messageId, \"es\", \"traducción\"...> to be true", repository.setTranslation("assistant-only-updates", user.messageId, "es", "traducción") is ChatDbResult.Invalid)

        val assistant = success(repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "assistant", listOf(MessagePartInput(MessagePartKinds.TEXT, "draft")), status = "STREAMING",
        )))
        val finalized = success(repository.finalizeMessage(
            "assistant-only-updates", assistant.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "done")),
            "COMPLETED", durationMillis = -1,
        ))
        assertNull("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <finalized.durationMillis> to be null", finalized.durationMillis)
        assertEquals("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <finalized.text> to equal <\"done\">", "done", finalized.text)
        assertTrue("messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics: expected <repository.appendMessage(AppendMessageInput( \"assistant-only-updates\", \"assistant\", emp...> to be true", repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "assistant", emptyList(), proactiveDecisionId = "  ",
        )) is ChatDbResult.Invalid)
    }

    @Test fun attachmentReferencesStayOrderedAndSnapshotsRespectMessageRoles() = onDatabaseThread {
        success(repository.ensureConversation("attachments"))
        val firstAttachment = MessageAttachmentInput(
            attachmentId = "first-attachment-id", name = "first.png", mimeType = "image/png",
            sizeBytes = 12, kind = "image", relativePath = "first-attachment-picture.png", sha256 = "first-digest",
        )
        val secondAttachment = MessageAttachmentInput(
            attachmentId = "second-attachment-id", name = "second.png", mimeType = "image/png",
            sizeBytes = 24, kind = "image", relativePath = "second-attachment-picture.png", sha256 = "second-digest",
        )
        val user = success(repository.appendMessage(AppendMessageInput(
            conversationId = "attachments", role = "user",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "look")), attachments = listOf(firstAttachment, secondAttachment),
        )))
        assertEquals("user attachment rows retain their input order", listOf("first-attachment-id", "second-attachment-id"), user.attachments.map { it.attachmentId })
        assertEquals("the body has one text part and one ref per attachment", 3, user.parts.size)
        assertEquals("attachment reference parts retain attachment order",
            listOf("first-attachment-id", "second-attachment-id"),
            user.parts.filter { it.kind == MessagePartKinds.ATTACHMENT_REF }
                .map { org.json.JSONObject(it.payload).getString("attachmentId") },
        )

        // LocalRunStore has no public user-message edit operation; exercise the repository's
        // internal replacement path without inventing a new public operation.
        replacePartsPreservingAttachmentsInternally("attachments", user.messageId, "edited user body")
        val replacedUser = repository.getMessage("attachments", user.messageId)!!
        assertEquals("replacing a user body keeps its text", "edited user body", replacedUser.text)
        assertEquals("replacing a user body keeps attachment refs in order", listOf("first-attachment-id", "second-attachment-id"),
            replacedUser.parts.filter { it.kind == MessagePartKinds.ATTACHMENT_REF }
                .map { org.json.JSONObject(it.payload).getString("attachmentId") },
        )

        val assistant = success(repository.appendMessage(AppendMessageInput(
            conversationId = "attachments", role = "assistant", status = "STREAMING",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "draft")),
        )))
        val updatedAssistant = success(repository.updateMessageSnapshot(
            "attachments", assistant.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "replacement body")),
        ))
        assertEquals("an assistant snapshot replaces its body", "replacement body", updatedAssistant.text)
        assertTrue("a snapshot attempt on a user message is rejected", repository.updateMessageSnapshot(
            "attachments", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "user edit is unsupported")),
        ) is ChatDbResult.Invalid)

        val reloadedUser = repository.getMessage("attachments", user.messageId)!!
        assertEquals("rehydration keeps user attachments in their original order", listOf("first-attachment-id", "second-attachment-id"), reloadedUser.attachments.map { it.attachmentId })
        assertEquals("rejecting a user snapshot leaves the edited user body unchanged", "edited user body", reloadedUser.text)
        assertEquals("rehydration keeps user attachment reference parts in their original order",
            listOf("first-attachment-id", "second-attachment-id"),
            reloadedUser.parts.filter { it.kind == MessagePartKinds.ATTACHMENT_REF }
                .map { org.json.JSONObject(it.payload).getString("attachmentId") },
        )
        listOf(firstAttachment, secondAttachment).forEach { attachment ->
            val fileRefCount = database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM file_refs WHERE relative_path=?", arrayOf(attachment.relativePath),
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals("each attachment reuses its single file reference", 1L, fileRefCount)
        }
    }

    @Test fun rawRepositoryInsertsProvideEveryRequiredSchemaColumn() {
        val relativeSourcePath = "app/app/src/main/java/com/jarvys/agent/chatdb/ConversationRepository.kt"
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        var sourceFile: File? = null
        while (directory != null && sourceFile == null) {
            sourceFile = listOf(
                File(directory, relativeSourcePath),
                File(directory, "app/src/main/java/com/jarvys/agent/chatdb/ConversationRepository.kt"),
            ).firstOrNull { it.isFile }
            directory = directory.parentFile
        }
        assertTrue("ConversationRepository.kt source must be available to audit raw INSERT statements", sourceFile?.isFile == true)

        val source = sourceFile!!.readText()
        val insertPattern = Regex("""(?is)\bINSERT\s+(?:OR\s+\w+\s+)?INTO\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
        val insertIntoCount = Regex("""(?i)\bINSERT\s+(?:OR\s+\w+\s+)?INTO\b""").findAll(source).count()
        val concatBoundary = Regex("\"\\s*\\+\\s*\"")
        val inserts = insertPattern.findAll(source).map { match ->
            val table = match.groupValues[1]
            val columns = match.groupValues[2].replace(concatBoundary, "")
                .split(',').map { it.trim().trim('`', '\"', '[', ']') }.filter(String::isNotEmpty).toSet()
            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
            Triple(table, columns, line)
        }.toList()
        assertTrue("ConversationRepository.kt must contain raw INSERT statements to audit", inserts.isNotEmpty())
        assertEquals("every raw INSERT INTO statement must have an explicit column list", insertIntoCount, inserts.size)

        val db = database.openHelper.writableDatabase
        inserts.forEach { (table, insertedColumns, line) ->
            val tableInfo = db.query("PRAGMA table_info($table)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        Triple(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1 &&
                                cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        ),
                    )
                }
            }
            assertTrue("ConversationRepository.kt:$line INSERT INTO $table must target an existing table", tableInfo.isNotEmpty())
            val createSql = db.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table),
            ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else "" }
            val autoIncrementPrimaryKeys = Regex(
                """(?i)(?:^|[,(])\s*[`"\[]?([A-Za-z_][A-Za-z0-9_]*)[`"\]]?\s+INTEGER\s+PRIMARY\s+KEY\s+AUTOINCREMENT\b""",
            ).findAll(createSql).map { it.groupValues[1] }.toSet()
            val schemaColumns = tableInfo.map { it.first }.toSet()
            val unknownColumns = insertedColumns - schemaColumns
            assertTrue("ConversationRepository.kt:$line INSERT INTO $table has unknown columns $unknownColumns", unknownColumns.isEmpty())
            val requiredColumns = tableInfo.filter { (name, notNullWithoutDefault, primaryKeyPosition) ->
                notNullWithoutDefault && !(primaryKeyPosition > 0 && name in autoIncrementPrimaryKeys)
            }.map { it.first }.toSet()
            val missingColumns = requiredColumns - insertedColumns
            assertTrue("ConversationRepository.kt:$line INSERT INTO $table omits required columns $missingColumns", missingColumns.isEmpty())
        }
    }

    @Test fun multiMessageHydrationUsesOneChildUnionQuery() {
        val countedFile = File(temporary.root, "query-count.db")
        val queryExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "chatdb-counted-query").apply { isDaemon = true } }
        val transactionExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "chatdb-counted-transaction").apply { isDaemon = true } }
        val callbackExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "chatdb-query-callback").apply { isDaemon = true } }
        val childQueryCount = AtomicInteger()
        val countedDatabase = Room.databaseBuilder(context, JarvysChatDatabase::class.java, countedFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .setQueryCallback(RoomDatabase.QueryCallback { query, _ ->
                if (query.contains("SELECT 'part' AS type")) childQueryCount.incrementAndGet()
            }, callbackExecutor)
            .addMigrations(*Migrations.ALL)
            .addCallback(JarvysChatDatabaseProvider.connectionPragmasCallback())
            .build()
        try {
            val countedRepository = ConversationRepository(countedDatabase)
            onDatabaseThread {
                success(countedRepository.ensureConversation("query-count"))
                val rows = (0 until 4).map { index ->
                    success(countedRepository.appendMessage(AppendMessageInput(
                        conversationId = "query-count", role = "user",
                        parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "body-$index")),
                    )))
                }
                callbackExecutor.submit {}.get(10, TimeUnit.SECONDS)
                childQueryCount.set(0)
                val loaded = countedRepository.getMessagesByIds("query-count", rows.map { it.messageId })
                callbackExecutor.submit {}.get(10, TimeUnit.SECONDS)
                assertEquals("multiMessageHydrationUsesOneChildUnionQuery: expected <loaded.size> to equal <4>", 4, loaded.size)
                assertEquals("multiMessageHydrationUsesOneChildUnionQuery: expected <childQueryCount.get()> to equal <1>", 1, childQueryCount.get())
            }
        } finally {
            countedDatabase.close()
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
            callbackExecutor.shutdownNow()
        }
    }

    @Test fun inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive() = onDatabaseThread {
        success(repository.ensureConversation("input-state"))
        val first = success(repository.appendInterruptRequest("input-state", "first", listOf("skill-a")))
        val second = success(repository.appendInterruptRequest("input-state", "second", listOf("skill-b")))
        assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <repository.pendingInterruptRequests(\"input-state\").flatMap { it.skills }> to equal <listOf(\"skill-a\", \"skill-b\")>", listOf("skill-a", "skill-b"), repository.pendingInterruptRequests("input-state").flatMap { it.skills })

        val marked = success(repository.markInputState("input-state", listOf(first.requestId, second.requestId), ConversationRepository.INPUT_INCORPORATED))
        assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <marked> to equal <listOf(first.requestId, second.requestId)>", listOf(first.requestId, second.requestId), marked)
        val values = database.openHelper.writableDatabase.query(
            "SELECT request_id,input_state,input_state_seq FROM interrupt_requests WHERE conversation_id=? ORDER BY seq", arrayOf("input-state"),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }
        assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <values.map { it.third }.distinct().size> to equal <2>", 2, values.map { it.third }.distinct().size)
        values.forEach { (requestId, state, markerSeq) ->
            assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <state> to equal <ConversationRepository.INPUT_INCORPORATED>", ConversationRepository.INPUT_INCORPORATED, state)
            val marker = database.openHelper.writableDatabase.query(
                "SELECT item_kind FROM conversation_items WHERE conversation_id=? AND seq=?", arrayOf("input-state", markerSeq),
            ).use { c -> assertTrue("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <c.moveToFirst()> to be true", c.moveToFirst()); c.getString(0) }
            assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <marker> to equal <ConversationRepository.INPUT_INCORPORATED>", ConversationRepository.INPUT_INCORPORATED, marker)
            assertTrue("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <requestId == first.requestId || requestId == second.requestId> to be true", requestId == first.requestId || requestId == second.requestId)
        }
        assertTrue("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <success(repository.markInputState(\"input-state\", listOf(first.requestId, second.request...> to be true", success(repository.markInputState("input-state", listOf(first.requestId, second.requestId), ConversationRepository.INPUT_RESOLVED)).isEmpty())
        assertTrue("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <repository.dispatchInterruptRequest(\"input-state\", first.requestId) is ChatDbResult.Suc...> to be true", repository.dispatchInterruptRequest("input-state", first.requestId) is ChatDbResult.Success)
        val dispatched = success(repository.dispatchInterruptRequest("input-state", second.requestId)).message
        assertEquals("inputMarkersUseOneSpineSequencePerReceiptAndAreExclusive: expected <dispatched.inputState> to equal <ConversationRepository.INPUT_INCORPORATED>", ConversationRepository.INPUT_INCORPORATED, dispatched.inputState)
    }

    @Test fun ledgerProjectionParityCoversMessagesMarkersAndSteering() = onDatabaseThread {
        success(repository.ensureConversation("parity"))
        val user = append("parity", "user", "question")
        val hiddenAssistant = append("parity", "assistant", "superseded")
        val request = success(repository.appendInterruptRequest("parity", "clarify"))
        val dispatch = success(repository.dispatchInterruptRequest("parity", request.requestId))
        val insertedBeforeDispatch = success(repository.appendMessage(AppendMessageInput(
            conversationId = "parity", role = "assistant", status = "COMPLETED", beforeMessageId = dispatch.message.messageId,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "answer before steering")),
        )))
        success(repository.hideAssistant("parity", hiddenAssistant.messageId, "assistant_regenerated"))

        val actualSpine = database.openHelper.writableDatabase.query(
            "SELECT item_kind,item_key FROM conversation_items WHERE conversation_id=? ORDER BY sort_seq,sort_sub,seq", arrayOf("parity"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val expectedLedgerProjection = listOf(
            "message" to user.messageId,
            "message" to hiddenAssistant.messageId,
            "main_interrupt_request" to request.requestId,
            "message" to insertedBeforeDispatch.messageId,
            "message" to dispatch.message.messageId,
            "assistant_regenerated" to hiddenAssistant.messageId,
        )
        assertEquals("ledgerProjectionParityCoversMessagesMarkersAndSteering: expected <actualSpine> to equal <expectedLedgerProjection>", expectedLedgerProjection, actualSpine)
        val visibleMessageIds = database.openHelper.writableDatabase.query(
            "SELECT message_id FROM messages WHERE conversation_id=? ORDER BY message_index", arrayOf("parity"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals("ledgerProjectionParityCoversMessagesMarkersAndSteering: expected <visibleMessageIds> to equal <listOf(user.messageId, hiddenAssistant.messageId, insertedBeforeDispatch.messageId, dis...>", listOf(user.messageId, hiddenAssistant.messageId, insertedBeforeDispatch.messageId, dispatch.message.messageId), visibleMessageIds)
        assertEquals("ledgerProjectionParityCoversMessagesMarkersAndSteering: expected <repository.getMessage(\"parity\", hiddenAssistant.messageId)?.hiddenKind> to equal <\"assistant_regenerated\">", "assistant_regenerated", repository.getMessage("parity", hiddenAssistant.messageId)?.hiddenKind)
        assertEquals("ledgerProjectionParityCoversMessagesMarkersAndSteering: expected <repository.messageCounts(\"parity\").total> to equal <4L>", 4L, repository.messageCounts("parity").total)
    }

    @Test fun generationRunRevisionCheckpointAndStaleRecoveryAreAtomic() = onDatabaseThread {
        success(repository.ensureConversation("runs"))
        val message = success(repository.appendMessage(AppendMessageInput(
            conversationId = "runs", role = "assistant", status = "STREAMING",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "running")),
        )))
        val run = success(repository.createGenerationRun("run-1", "runs", message.messageId))
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <run.stateRevision> to equal <0L>", 0L, run.stateRevision)
        assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.createGenerationRun(\"run-duplicate-target\", \"runs\", message.messageId) is Ch...> to be true", repository.createGenerationRun("run-duplicate-target", "runs", message.messageId) is ChatDbResult.Conflict)
        assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.createGenerationRun(\"run-1\", \"runs\", message.messageId) is ChatDbResult.Conf...> to be true", repository.createGenerationRun("run-1", "runs", message.messageId) is ChatDbResult.Conflict)
        val streaming = success(repository.transitionGenerationRun("run-1", 0, ConversationRepository.RUN_STREAMING, checkpointSeq = 5))
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <streaming.stateRevision> to equal <1L>", 1L, streaming.stateRevision)
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <streaming.checkpointSeq> to equal <5L>", 5L, streaming.checkpointSeq)
        assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.transitionGenerationRun(\"run-1\", 0, ConversationRepository.RUN_COMPLETED) is...> to be true", repository.transitionGenerationRun("run-1", 0, ConversationRepository.RUN_COMPLETED) is ChatDbResult.Conflict)
        assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.transitionGenerationRun(\"run-1\", 1, ConversationRepository.RUN_STREAMING, ch...> to be true", repository.transitionGenerationRun("run-1", 1, ConversationRepository.RUN_STREAMING, checkpointSeq = 4) is ChatDbResult.Conflict)
        val completed = success(repository.transitionGenerationRun("run-1", 1, ConversationRepository.RUN_COMPLETED, checkpointSeq = 6))
        assertNull("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <completed.activeTargetKey> to be null", completed.activeTargetKey)
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <completed.stateRevision> to equal <2L>", 2L, completed.stateRevision)

        success(repository.createGenerationRun("run-2", "runs", message.messageId))
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.markStaleRunsInterrupted()> to equal <1>", 1, repository.markStaleRunsInterrupted())
        val stale = database.openHelper.writableDatabase.query("SELECT state,active_target_key FROM generation_run WHERE id='run-2'").use { c ->
            assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <c.moveToFirst()> to be true", c.moveToFirst()); c.getString(0) to if (c.isNull(1)) null else c.getString(1)
        }
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <stale.first> to equal <\"interrupted\">", "interrupted", stale.first)
        assertNull("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <stale.second> to be null", stale.second)
        assertEquals("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.getMessage(\"runs\", message.messageId)?.status> to equal <\"INTERRUPTED\">", "INTERRUPTED", repository.getMessage("runs", message.messageId)?.status)
        val user = append("runs", "user", "user message")
        assertTrue("generationRunRevisionCheckpointAndStaleRecoveryAreAtomic: expected <repository.createGenerationRun(\"run-user\", \"runs\", user.messageId) is ChatDbResult.Invalid> to be true", repository.createGenerationRun("run-user", "runs", user.messageId) is ChatDbResult.Invalid)
    }

    @Test fun drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds() = onDatabaseThread {
        success(repository.ensureConversation("first-user"))
        append("first-user", "user", "first prompt")
        append("first-user", "assistant", "preview text")
        success(repository.updateMetadata("first-user", generatedTitle = "generated"))
        success(repository.updateMetadata("first-user", generatedTitle = "later generated title"))
        success(repository.ensureConversation("metadata"))
        append("metadata", "user", "other prompt")
        success(repository.updateMetadata("metadata", generatedTitle = "ignored", pinned = true, archived = true, privateCode = true))
        success(repository.renameConversation("metadata", "  manual\n  title\t"))
        success(repository.ensureConversation("system", "proactive"))
        success(repository.ensureConversation("tasks", "tasks"))
        success(repository.ensureConversation("central", "central"))
        success(repository.ensureConversation("workspace", "workspace_exec"))
        val rows = repository.listConversations(10)
        val first = rows.first { it.id == "first-user" }
        val metadata = rows.first { it.id == "metadata" }
        assertEquals("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <first.title> to equal <\"generated\">", "generated", first.title)
        assertEquals("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <first.goal> to equal <\"first prompt\">", "first prompt", first.goal)
        assertEquals("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <first.preview> to equal <\"preview text\">", "preview text", first.preview)
        assertEquals("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <metadata.title> to equal <\"manual title\">", "manual title", metadata.title)
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <metadata.pinned> to be true", metadata.pinned)
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <metadata.archived> to be true", metadata.archived)
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <success(repository.ensureConversation(\"metadata\")).private_code> to be true", success(repository.ensureConversation("metadata")).private_code)
        success(repository.updateMetadata("metadata", privateCode = false))
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <success(repository.ensureConversation(\"metadata\")).private_code> to be true", success(repository.ensureConversation("metadata")).private_code)
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <repository.renameConversation(\"metadata\", \" \\t \") is ChatDbResult.Invalid> to be true", repository.renameConversation("metadata", " \t ") is ChatDbResult.Invalid)
        assertTrue("drawerSummaryUsesMetadataThenGeneratedThenFirstUserAndExcludesSpecialKinds: expected <rows.none { it.id in setOf(\"system\", \"tasks\", \"central\", \"workspace\") }> to be true", rows.none { it.id in setOf("system", "tasks", "central", "workspace") })
    }

    @Test fun drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp() = onDatabaseThread {
        success(repository.ensureConversation("drawer-active"))
        val first = success(repository.appendMessage(AppendMessageInput(
            "drawer-active", "user", listOf(
                MessagePartInput(MessagePartKinds.TEXT, "  Plan   now. "),
                MessagePartInput(MessagePartKinds.TEXT, "More detail follows."),
            ), createdAtSeconds = 10.0,
        )))
        val regenerated = success(repository.appendMessage(AppendMessageInput(
            "drawer-active", "assistant", listOf(MessagePartInput(MessagePartKinds.TEXT, "old answer")), status = "COMPLETED", createdAtSeconds = 20.0,
        )))
        val latestUser = success(repository.appendMessage(AppendMessageInput(
            "drawer-active", "user", listOf(
                MessagePartInput(MessagePartKinds.TEXT, "latest user "),
                MessagePartInput(MessagePartKinds.TEXT, "goal"),
            ), createdAtSeconds = 30.0,
        )))
        val deleted = success(repository.appendMessage(AppendMessageInput(
            "drawer-active", "assistant", listOf(MessagePartInput(MessagePartKinds.TEXT, "deleted answer")), status = "COMPLETED", createdAtSeconds = 40.0,
        )))
        success(repository.hideAssistant("drawer-active", regenerated.messageId, "assistant_regenerated"))
        success(repository.hideAssistant("drawer-active", deleted.messageId, "assistant_deleted"))

        val summary = repository.listConversations(10).single { it.id == "drawer-active" }
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.title> to equal <\"Plan now.\">", "Plan now.", summary.title)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.goal> to equal <\"latest user goal\">", "latest user goal", summary.goal)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.preview> to equal <\"latest user goal\">", "latest user goal", summary.preview)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.turnCount> to equal <1L>", 1L, summary.turnCount)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.messageCount> to equal <4L>", 4L, summary.messageCount)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.lastMessageAtMillis> to equal <30_000L>", 30_000L, summary.lastMessageAtMillis)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <summary.updatedAtMillis> to equal <30_000L>", 30_000L, summary.updatedAtMillis)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <repository.firstUserMessage(\"drawer-active\")?.messageId> to equal <first.messageId>", first.messageId, repository.firstUserMessage("drawer-active")?.messageId)
        assertEquals("drawerCountsOnlyActiveMessagesAndUsesLegacyTitleGoalPreviewAndTimestamp: expected <repository.latestUserMessageId(\"drawer-active\")> to equal <latestUser.messageId>", latestUser.messageId, repository.latestUserMessageId("drawer-active"))
    }

    @Test fun drawerOmitsConversationWhenEveryAssistantMessageIsHidden() = onDatabaseThread {
        success(repository.ensureConversation("all-hidden"))
        val onlyAssistant = append("all-hidden", "assistant", "removed")
        success(repository.hideAssistant("all-hidden", onlyAssistant.messageId, "assistant_deleted"))
        assertTrue("drawerOmitsConversationWhenEveryAssistantMessageIsHidden: expected <repository.listConversations(10).none { it.id == \"all-hidden\" }> to be true", repository.listConversations(10).none { it.id == "all-hidden" })
    }

    private suspend fun append(conversationId: String, role: String, text: String): StoredMessage = success(
        repository.appendMessage(AppendMessageInput(conversationId, role, listOf(MessagePartInput(MessagePartKinds.TEXT, text)), status = if (role == "assistant") "COMPLETED" else null)),
    )

    private fun replacePartsPreservingAttachmentsInternally(conversationId: String, messageId: String, newText: String) {
        val preserveReferences = ConversationRepository::class.java.getDeclaredMethod(
            "preserveAttachmentReferences", String::class.java, String::class.java, List::class.java,
        ).apply { isAccessible = true }
        val replaceParts = ConversationRepository::class.java.getDeclaredMethod(
            "replaceParts", String::class.java, String::class.java, List::class.java,
        ).apply { isAccessible = true }
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            val parts = preserveReferences.invoke(
                repository, conversationId, messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, newText)),
            ) as List<MessagePartInput>
            replaceParts.invoke(repository, conversationId, messageId, parts)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> success(result: ChatDbResult<T>): T {
        assertTrue("Expected success but received $result", result is ChatDbResult.Success)
        return (result as ChatDbResult.Success<T>).value
    }

    private fun successWindow(window: MessageWindow): MessageWindow = window
}

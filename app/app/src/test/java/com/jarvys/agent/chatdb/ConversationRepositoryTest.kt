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
        assertEquals(first.created_at, again.created_at)
        assertEquals("chat", again.kind)

        assertEquals(true, success(repository.deleteConversation("lifecycle")))
        assertEquals(false, success(repository.deleteConversation("lifecycle")))
        assertTrue(repository.isTombstoned("lifecycle"))
        assertTrue(repository.ensureConversation("lifecycle") === ChatDbResult.Tombstoned)

        assertEquals(false, success(repository.deleteConversation("never-created")))
        assertTrue(repository.ensureConversation("never-created") === ChatDbResult.Tombstoned)
    }

    @Test fun legacyStableMessageIdentityUsesSessionAndAbsoluteIndex() {
        val session = "legacy.session-7"
        val absoluteIndex = 42L
        val expected = "legacy-${UUID.nameUUIDFromBytes("$session\u0000$absoluteIndex".toByteArray(StandardCharsets.UTF_8))}"
        val first = ConversationRepository.legacyStableMessageId(session, absoluteIndex)
        assertEquals(expected, first)
        assertEquals(first, ConversationRepository.legacyStableMessageId(session, absoluteIndex))
        assertTrue(first != ConversationRepository.legacyStableMessageId(session, absoluteIndex + 1))
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
            futures.forEach { assertTrue(it.get(45, TimeUnit.SECONDS) is ChatDbResult.Success) }
        } finally {
            pool.shutdownNow()
        }
        val rows = database.openHelper.writableDatabase.query(
            "SELECT seq,message_index FROM messages WHERE conversation_id=? ORDER BY seq", arrayOf("concurrent"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
        assertEquals((1L..36L).toList(), rows.map { it.first })
        assertEquals((0L until 36L).toList(), rows.map { it.second }.sorted())
        val conversation = success(repository.ensureConversation("concurrent"))
        assertEquals(37L, conversation.next_seq)
        assertEquals(36L, conversation.message_count)
    }

    @Test fun steeringDispatchReordersMessageIndexAndPagesRemainKeysetAligned() = onDatabaseThread {
        success(repository.ensureConversation("steering"))
        val u1 = append("steering", "user", "u1")
        val a1 = append("steering", "assistant", "a1")
        val u2 = append("steering", "user", "u2")
        val a2 = append("steering", "assistant", "a2")
        val interrupt = success(repository.appendInterruptRequest("steering", "follow-up", listOf("skill"), true))
        assertEquals(listOf("skill"), interrupt.skills)
        val dispatched = success(repository.dispatchInterruptRequest("steering", interrupt.requestId))
        assertTrue(dispatched.inserted)
        assertFalse(success(repository.dispatchInterruptRequest("steering", interrupt.requestId)).inserted)
        assertEquals("follow-up", dispatched.message.text)
        val inBetween = success(repository.appendMessage(AppendMessageInput(
            conversationId = "steering", role = "assistant", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "before follow-up")),
            beforeMessageId = interrupt.requestId, status = "COMPLETED",
        )))
        val u3 = append("steering", "user", "u3")
        val a3 = append("steering", "assistant", "a3")

        val ordered = database.openHelper.writableDatabase.query(
            "SELECT message_id FROM messages WHERE conversation_id=? ORDER BY message_index", arrayOf("steering"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf(u1.messageId, a1.messageId, u2.messageId, a2.messageId, inBetween.messageId, interrupt.requestId, u3.messageId, a3.messageId), ordered)

        val tail = successWindow(repository.readTailWindow("steering", 1))
        assertEquals(u3.seq, tail.olderCursorSeq)
        assertTrue(tail.slots.any { it.item.item_key == u3.messageId })
        val previous = repository.readWindowBefore("steering", u3.seq, 1)
        assertTrue(previous.slots.any { it.item.item_key == inBetween.messageId })
        assertTrue(previous.slots.any { it.item.item_key == interrupt.requestId })
        assertTrue(previous.slots.none { it.item.item_key == u3.messageId })
        assertEquals(u2.seq, previous.olderCursorSeq)

        val previousAgain = repository.readWindowBefore("steering", u2.seq, 1)
        assertTrue(previousAgain.slots.any { it.item.item_key == u1.messageId })

        // Cursor is the dispatch message itself: the assistant has a later physical seq but sorts before it.
        val beforeDispatch = repository.readWindowBefore("steering", dispatched.message.seq, 1)
        assertTrue(beforeDispatch.slots.any { it.item.item_key == inBetween.messageId })
        assertTrue(beforeDispatch.slots.none { it.item.item_key == dispatched.message.messageId })

        val expectedSpine = database.openHelper.writableDatabase.query(
            "SELECT seq FROM conversation_items WHERE conversation_id=? ORDER BY sort_seq,sort_sub,seq", arrayOf("steering"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (turns in 1..3) {
            val pages = mutableListOf<MessageWindow>()
            var page = repository.readTailWindow("steering", turns)
            while (true) {
                pages += page
                val cursor = page.olderCursorSeq ?: break
                assertEquals(cursor, page.slots.first().item.seq)
                page = repository.readWindowBefore("steering", cursor, turns)
            }
            val pagedSequences = pages.asReversed().flatMap { window -> window.slots.map { it.item.seq } }
            assertEquals("all spine rows are paged once for turns=$turns", expectedSpine, pagedSequences)
            assertEquals(pagedSequences.size, pagedSequences.distinct().size)
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
        assertEquals(listOf(markerSeq, firstUser.seq), oldestPage.slots.map { it.item.seq })
        assertEquals("user_decision_request", oldestPage.slots.first().item.item_kind)
        assertNull(oldestPage.olderCursorSeq)
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
        assertEquals(proactive.messageId, duplicate.messageId)
        assertEquals("suggestions", duplicate.text)
        assertEquals(1, duplicate.suggestedReplies.size)

        success(repository.setTranslation("markers", proactive.messageId, "es", "primera"))
        success(repository.setTranslation("markers", proactive.messageId, "es", "ultima"))
        success(repository.hideTranslation("markers", proactive.messageId))
        assertNull(repository.getMessage("markers", proactive.messageId)?.translation)
        val thumbsUp = "\uD83D\uDC4D"
        val reactionAdded = success(repository.setReaction("markers", user.stableId, thumbsUp))
        assertTrue(reactionAdded.changed)
        val repeatedReaction = success(repository.setReaction("markers", user.stableId, thumbsUp))
        assertFalse(repeatedReaction.changed)
        assertEquals(reactionAdded.seq, repeatedReaction.seq)
        assertTrue(repository.setReaction("markers", user.stableId, "not-an-emoji") is ChatDbResult.Invalid)
        val reactionRemoved = success(repository.setReaction("markers", user.stableId, ""))
        assertTrue(reactionRemoved.changed)
        assertFalse(success(repository.setReaction("markers", user.stableId, "")).changed)
        val reaction = database.openHelper.writableDatabase.query(
            "SELECT emoji FROM message_reactions WHERE conversation_id=? AND target_stable_id=?", arrayOf("markers", user.stableId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals("", reaction)

        success(repository.hideAssistant("markers", proactive.messageId, "assistant_regenerated"))
        success(repository.hideAssistant("markers", proactive.messageId, "assistant_deleted"))
        assertEquals("assistant_deleted", repository.getMessage("markers", proactive.messageId)?.hiddenKind)
        assertEquals(user.messageId, repository.firstUserMessage("markers")?.messageId)
        assertEquals(2L, repository.messageCounts("markers").total)
        val consumedSeq = success(repository.consumeSuggestedReplies("markers", proactive.messageId))
        assertEquals(consumedSeq, success(repository.consumeSuggestedReplies("markers", proactive.messageId)))
        assertEquals(consumedSeq, repository.getMessage("markers", proactive.messageId)?.suggestedRepliesConsumedSeq)
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
        assertEquals(listOf("yes" to "Yes", "no" to "No"), options)
        val firstResolution = success(repository.resolveDecision("decisions", "choice", "selected", "yes", "Yes"))
        val lastResolution = success(repository.resolveDecision("decisions", "choice", "dismissed", null, null))
        assertTrue(lastResolution > firstResolution)

        val reopenedSeq = success(repository.appendDecisionRequest(
            "decisions", "choice", "Choose again", "Retry", false, listOf(DecisionOptionInput("retry", "Retry")),
        ))
        assertTrue(reopenedSeq > lastResolution)
        val row = database.openHelper.writableDatabase.query(
            "SELECT seq,title,status,option_id,resolved_seq FROM user_decisions WHERE conversation_id=? AND decision_id=?",
            arrayOf("decisions", "choice"),
        ).use { c -> assertTrue(c.moveToFirst()); listOf(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getLong(4)) }
        assertEquals(reopenedSeq, row[0])
        assertEquals("Choose again", row[1])
        assertNull(row[2])
        assertNull(row[3])
        assertNull(row[4])
        assertTrue(repository.appendDecisionRequest("missing", "choice", "", "", false, emptyList()) is ChatDbResult.NotFound)
    }

    @Test fun snapshotsDoNotResurrectFinishedMessageAndFailedTransactionLeavesNoSequenceGap() = onDatabaseThread {
        success(repository.ensureConversation("atomic"))
        val stream = success(repository.appendMessage(AppendMessageInput(
            conversationId = "atomic", role = "assistant", status = "STREAMING",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "partial")),
        )))
        success(repository.finalizeMessage("atomic", stream.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "final")), "COMPLETED"))
        val late = success(repository.updateMessageSnapshot("atomic", stream.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "late")), "STREAMING"))
        assertEquals("final", late.text)
        assertEquals("COMPLETED", late.status)

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
            assertTrue(failure.message.orEmpty().contains("injected_repository_failure"))
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER test_abort_part")
        val finalMessage = append("atomic", "user", "after rollback")
        assertEquals(stream.seq + 1L, finalMessage.seq)
        assertEquals(1L, finalMessage.messageIndex)
        assertEquals(2L, success(repository.ensureConversation("atomic")).message_count)
    }

    @Test fun messageSnapshotFinalizationAndTranslationEnforceAssistantAndPositiveDurationSemantics() = onDatabaseThread {
        success(repository.ensureConversation("assistant-only-updates"))
        val user = success(repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "user", listOf(MessagePartInput(MessagePartKinds.TEXT, "prompt")), durationMillis = -5,
        )))
        assertNull(user.durationMillis)
        assertTrue(repository.updateMessageSnapshot(
            "assistant-only-updates", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "changed")),
        ) is ChatDbResult.Invalid)
        assertTrue(repository.finalizeMessage(
            "assistant-only-updates", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "changed")), "COMPLETED",
        ) is ChatDbResult.Invalid)
        assertTrue(repository.setTranslation("assistant-only-updates", user.messageId, "es", "traducción") is ChatDbResult.Invalid)

        val assistant = success(repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "assistant", listOf(MessagePartInput(MessagePartKinds.TEXT, "draft")), status = "STREAMING",
        )))
        val finalized = success(repository.finalizeMessage(
            "assistant-only-updates", assistant.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "done")),
            "COMPLETED", durationMillis = -1,
        ))
        assertNull(finalized.durationMillis)
        assertEquals("done", finalized.text)
        assertTrue(repository.appendMessage(AppendMessageInput(
            "assistant-only-updates", "assistant", emptyList(), proactiveDecisionId = "  ",
        )) is ChatDbResult.Invalid)
    }

    @Test fun attachmentReferencesStayOrderedAndSurviveStreamingSnapshots() = onDatabaseThread {
        success(repository.ensureConversation("attachments"))
        val attachment = MessageAttachmentInput(
            attachmentId = "stable-attachment-id", name = "picture.png", mimeType = "image/png",
            sizeBytes = 12, kind = "image", relativePath = "stable-attachment-id-picture.png", sha256 = "digest",
        )
        val user = success(repository.appendMessage(AppendMessageInput(
            conversationId = "attachments", role = "user",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "look")), attachments = listOf(attachment),
        )))
        assertEquals(1, user.attachments.size)
        assertEquals(2, user.parts.size)
        assertEquals(MessagePartKinds.ATTACHMENT_REF, user.parts.last().kind)
        assertEquals("stable-attachment-id", org.json.JSONObject(user.parts.last().payload).getString("attachmentId"))

        val updated = success(repository.updateMessageSnapshot(
            "attachments", user.messageId, listOf(MessagePartInput(MessagePartKinds.TEXT, "new body")),
        ))
        assertEquals("new body", updated.text)
        assertEquals(1, updated.parts.count { it.kind == MessagePartKinds.ATTACHMENT_REF })
        assertEquals("stable-attachment-id", org.json.JSONObject(updated.parts.last().payload).getString("attachmentId"))
        val fileRefCount = database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM file_refs WHERE relative_path=?", arrayOf(attachment.relativePath),
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        assertEquals(1L, fileRefCount)
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
                assertEquals(4, loaded.size)
                assertEquals(1, childQueryCount.get())
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
        assertEquals(listOf("skill-a", "skill-b"), repository.pendingInterruptRequests("input-state").flatMap { it.skills })

        val marked = success(repository.markInputState("input-state", listOf(first.requestId, second.requestId), ConversationRepository.INPUT_INCORPORATED))
        assertEquals(listOf(first.requestId, second.requestId), marked)
        val values = database.openHelper.writableDatabase.query(
            "SELECT request_id,input_state,input_state_seq FROM interrupt_requests WHERE conversation_id=? ORDER BY seq", arrayOf("input-state"),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }
        assertEquals(2, values.map { it.third }.distinct().size)
        values.forEach { (requestId, state, markerSeq) ->
            assertEquals(ConversationRepository.INPUT_INCORPORATED, state)
            val marker = database.openHelper.writableDatabase.query(
                "SELECT item_kind FROM conversation_items WHERE conversation_id=? AND seq=?", arrayOf("input-state", markerSeq),
            ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
            assertEquals(ConversationRepository.INPUT_INCORPORATED, marker)
            assertTrue(requestId == first.requestId || requestId == second.requestId)
        }
        assertTrue(success(repository.markInputState("input-state", listOf(first.requestId, second.requestId), ConversationRepository.INPUT_RESOLVED)).isEmpty())
        assertTrue(repository.dispatchInterruptRequest("input-state", first.requestId) is ChatDbResult.Success)
        val dispatched = success(repository.dispatchInterruptRequest("input-state", second.requestId)).message
        assertEquals(ConversationRepository.INPUT_INCORPORATED, dispatched.inputState)
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
        assertEquals(expectedLedgerProjection, actualSpine)
        val visibleMessageIds = database.openHelper.writableDatabase.query(
            "SELECT message_id FROM messages WHERE conversation_id=? ORDER BY message_index", arrayOf("parity"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf(user.messageId, hiddenAssistant.messageId, insertedBeforeDispatch.messageId, dispatch.message.messageId), visibleMessageIds)
        assertEquals("assistant_regenerated", repository.getMessage("parity", hiddenAssistant.messageId)?.hiddenKind)
        assertEquals(4L, repository.messageCounts("parity").total)
    }

    @Test fun generationRunRevisionCheckpointAndStaleRecoveryAreAtomic() = onDatabaseThread {
        success(repository.ensureConversation("runs"))
        val message = success(repository.appendMessage(AppendMessageInput(
            conversationId = "runs", role = "assistant", status = "STREAMING",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "running")),
        )))
        val run = success(repository.createGenerationRun("run-1", "runs", message.messageId))
        assertEquals(0L, run.stateRevision)
        assertTrue(repository.createGenerationRun("run-duplicate-target", "runs", message.messageId) is ChatDbResult.Conflict)
        assertTrue(repository.createGenerationRun("run-1", "runs", message.messageId) is ChatDbResult.Conflict)
        val streaming = success(repository.transitionGenerationRun("run-1", 0, ConversationRepository.RUN_STREAMING, checkpointSeq = 5))
        assertEquals(1L, streaming.stateRevision)
        assertEquals(5L, streaming.checkpointSeq)
        assertTrue(repository.transitionGenerationRun("run-1", 0, ConversationRepository.RUN_COMPLETED) is ChatDbResult.Conflict)
        assertTrue(repository.transitionGenerationRun("run-1", 1, ConversationRepository.RUN_STREAMING, checkpointSeq = 4) is ChatDbResult.Conflict)
        val completed = success(repository.transitionGenerationRun("run-1", 1, ConversationRepository.RUN_COMPLETED, checkpointSeq = 6))
        assertNull(completed.activeTargetKey)
        assertEquals(2L, completed.stateRevision)

        success(repository.createGenerationRun("run-2", "runs", message.messageId))
        assertEquals(1, repository.markStaleRunsInterrupted())
        val stale = database.openHelper.writableDatabase.query("SELECT state,active_target_key FROM generation_run WHERE id='run-2'").use { c ->
            assertTrue(c.moveToFirst()); c.getString(0) to if (c.isNull(1)) null else c.getString(1)
        }
        assertEquals("interrupted", stale.first)
        assertNull(stale.second)
        assertEquals("INTERRUPTED", repository.getMessage("runs", message.messageId)?.status)
        val user = append("runs", "user", "user message")
        assertTrue(repository.createGenerationRun("run-user", "runs", user.messageId) is ChatDbResult.Invalid)
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
        assertEquals("generated", first.title)
        assertEquals("first prompt", first.goal)
        assertEquals("preview text", first.preview)
        assertEquals("manual title", metadata.title)
        assertTrue(metadata.pinned)
        assertTrue(metadata.archived)
        assertTrue(success(repository.ensureConversation("metadata")).private_code)
        success(repository.updateMetadata("metadata", privateCode = false))
        assertTrue(success(repository.ensureConversation("metadata")).private_code)
        assertTrue(repository.renameConversation("metadata", " \t ") is ChatDbResult.Invalid)
        assertTrue(rows.none { it.id in setOf("system", "tasks", "central", "workspace") })
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
        assertEquals("Plan now.", summary.title)
        assertEquals("latest user goal", summary.goal)
        assertEquals("latest user goal", summary.preview)
        assertEquals(1L, summary.turnCount)
        assertEquals(4L, summary.messageCount)
        assertEquals(30_000L, summary.lastMessageAtMillis)
        assertEquals(30_000L, summary.updatedAtMillis)
        assertEquals(first.messageId, repository.firstUserMessage("drawer-active")?.messageId)
        assertEquals(latestUser.messageId, repository.latestUserMessageId("drawer-active"))
    }

    @Test fun drawerOmitsConversationWhenEveryAssistantMessageIsHidden() = onDatabaseThread {
        success(repository.ensureConversation("all-hidden"))
        val onlyAssistant = append("all-hidden", "assistant", "removed")
        success(repository.hideAssistant("all-hidden", onlyAssistant.messageId, "assistant_deleted"))
        assertTrue(repository.listConversations(10).none { it.id == "all-hidden" })
    }

    private suspend fun append(conversationId: String, role: String, text: String): StoredMessage = success(
        repository.appendMessage(AppendMessageInput(conversationId, role, listOf(MessagePartInput(MessagePartKinds.TEXT, text)), status = if (role == "assistant") "COMPLETED" else null)),
    )

    private fun <T> success(result: ChatDbResult<T>): T {
        assertTrue("Expected success but received $result", result is ChatDbResult.Success)
        return (result as ChatDbResult.Success<T>).value
    }

    private fun successWindow(window: MessageWindow): MessageWindow = window
}

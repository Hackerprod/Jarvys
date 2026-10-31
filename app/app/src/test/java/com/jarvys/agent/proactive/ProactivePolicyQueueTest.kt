package com.jarvys.agent.proactive

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePolicyQueueTest {
    @Test fun prefilterDropsOnlyStructuralNoiseWithStableReasonsAndMarksOtherCandidates() {
        val cases = listOf(
            event(app = "com.jarvys.agent") to "own_app",
            event(app = "android") to "system_package",
            event(app = "com.android.systemui") to "system_package",
            event(app = "com.google.android.apps.screenshot") to "system_package",
            event(app = "com.android.providers.downloads") to "system_package",
            event(app = "com.android.providers.downloads.ui") to "system_package",
            event(app = "com.android.packageinstaller") to "system_package",
            event(ongoing = true) to "ongoing_notification",
            event(foregroundService = true) to "foreground_service",
            event(androidCategory = "progress") to "progress_notification",
            event(androidCategory = "transport") to "media_transport",
            event(androidCategory = "service") to "service_notification",
            event(androidCategory = "sys") to "system_notification",
            event(androidCategory = "status") to "system_notification",
            event(groupSummary = true) to "group_summary",
            event(title = "", body = "") to "empty_content",
        )
        cases.forEach { (candidate, reason) ->
            val result = ProactivePrefilter.evaluate(candidate, "com.jarvys.agent")
            assertEquals(ProactivePrefilterDecision.Discard(reason), result)
        }
        val blocked = ProactivePrefilter.evaluate(event(app = "com.blocked.app"), "com.jarvys.agent") {
            setOf("com.blocked.app")
        }
        assertEquals(ProactivePrefilterDecision.Discard("blocked_app"), blocked)

        listOf(
            event(app = "com.bank.app", title = "Ava paid you", body = "Zelle transfer complete", androidCategory = "msg", category = "msg"),
            event(app = "com.github.android", title = "Review requested", body = "Pull request #42", androidCategory = "social", category = "social"),
            event(app = "com.chat.app", title = "Maya", body = "Are we still on?", androidCategory = "msg", category = "msg"),
        ).forEach { message ->
            assertTrue(ProactivePrefilter.evaluate(message, "com.jarvys.agent") is ProactivePrefilterDecision.Candidate)
        }
        val promotion = ProactivePrefilter.evaluate(event(category = "other", androidCategory = null, body = "50% off today"), "com.jarvys.agent")
            as ProactivePrefilterDecision.Candidate
        assertEquals("needs_model_relevance", promotion.event.prefilterMark)
        assertTrue(EmptyProactiveAppBlocklist.blockedApps().isEmpty())
    }

    @Test fun watermarkDiscardsOlderPendingEventsAndKeepsNewMessagesAsCandidates() {
        val store = store()
        val delivered = event(body = "already delivered", received = 2_000)
        val old = event(body = "old system status", received = 1_000)
        val fresh = event(body = "new message", received = 2_001, category = "msg")
        listOf(delivered, old, fresh).forEach { assertTrue(store.append(it)) }
        assertTrue(store.markProcessed(delivered.id))

        val batches = ProactiveCandidateQueue(store).nextBatches()
        assertEquals(listOf(fresh.id), batches.flatMap(ProactiveEventBatch::events).map(ProactiveEvent::id))
        val discarded = store.readAll().single { it.id == old.id }
        assertEquals(ProactiveEvent.DISCARDED, discarded.state)
        assertEquals("older_than_watermark", discarded.discardReason)
        assertEquals(2_000L, store.watermarkMillis())
    }

    @Test fun optInCaptureBoundaryRejectsNotificationsAlreadyActiveAtEnableTime() {
        val store = store()
        store.initializeCaptureBoundary(1_500L)
        val alreadyActive = event(body = "pre-existing active notice", received = 1_499L)
        val justPosted = event(body = "new notice", received = 1_500L, category = "msg")
        listOf(alreadyActive, justPosted).forEach { assertTrue(store.append(it)) }

        val candidates = ProactiveCandidateQueue(store).nextBatches().flatMap(ProactiveEventBatch::events)
        assertEquals(listOf(justPosted.id), candidates.map(ProactiveEvent::id))
        assertEquals(1_499L, store.watermarkMillis())
        assertEquals(1_500L, store.candidatePostTimeBoundaryMillis())
        assertEquals("older_than_watermark", store.readAll().single { it.id == alreadyActive.id }.discardReason)
    }

    @Test fun discardsPersistForAuditAndNeverReturnAsCandidates() {
        val store = store()
        val ongoing = event(ongoing = true)
        assertEquals(ProactiveCaptureResult.DiscardedStored("ongoing_notification"),
            ProactiveIngestion.ingest(ongoing, store, "com.jarvys.agent"))
        val persisted = store.readAll().single()
        assertEquals(ProactiveEvent.DISCARDED, persisted.state)
        assertEquals("ongoing_notification", persisted.discardReason)
        assertTrue(persisted.ongoing)
        assertEquals("msg", persisted.androidCategory)
        assertFalse(ProactivePrefilter.evaluate(persisted, "com.jarvys.agent") is ProactivePrefilterDecision.Candidate)
        assertTrue(ProactiveCandidateQueue(store).nextBatches().isEmpty())
        assertFalse(store.markProcessed(persisted.id))
    }

    @Test fun notificationRefreshCoalescesByStableSourceKeyAndKeepsFirstSourceTime() {
        val store = store()
        val first = event(
            sender = "Nora", app = "com.chat.app", body = "First text", received = 100,
            notificationKey = "notification-key",
        )
        val updated = event(
            sender = "Nora", app = "com.chat.app", body = "Updated text", received = 250,
            observed = 300, notificationKey = "notification-key",
        )
        assertTrue(store.append(first))
        assertTrue(store.append(updated))
        val current = store.pending().single()
        assertEquals(first.id, current.id)
        assertEquals(100L, current.receivedAtMillis)
        assertEquals(300L, current.observedAtMillis)
        assertEquals("Updated text", current.body)
        assertNotEquals(first.dedupKey, current.dedupKey)
        assertEquals(
            ProactiveNormalizer.dedupKey("notifications", "com.chat.app\u001fNora\u001fA title\u001fUpdated text", 100),
            current.dedupKey,
        )
        assertEquals(1, ProactiveCandidateQueue(store).nextBatches().single().events.size)
    }

    @Test fun candidateQueueGroupsByConversationOrdersByOriginalTimeAndMarksProcessed() {
        val store = store()
        val late = event(sender = "Nora", app = "com.chat.app", body = "later", received = 300, sourceId = "notifications")
        val other = event(sender = "Milo", app = "com.chat.app", body = "other chat", received = 200)
        val early = event(sender = "Nora", app = "com.chat.app", body = "earlier", received = 100, sourceId = "sms")
        listOf(late, other, early).forEach { assertTrue(store.append(it)) }

        val queue = ProactiveCandidateQueue(store)
        val batches = queue.nextBatches()
        val nora = batches.single { it.conversationKey == "com.chat.app\u001fNora" }
        assertEquals(listOf("sms", "notifications"), nora.events.map { it.sourceId })
        assertEquals(listOf(100L, 300L), nora.events.map { it.receivedAtMillis })
        assertTrue(batches.any { it.events.singleOrNull()?.body == "other chat" })
        queue.markProcessed(nora.events.map { it.id })
        assertTrue(queue.nextBatches().none { it.conversationKey == nora.conversationKey })
        assertEquals(nora.events.map { it.id }.toSet(), store.readAll().filter { it.state == ProactiveEvent.PROCESSED }.map { it.id }.toSet())
    }

    @Test fun clearingPendingPayloadsRetainsDiscardedAuditAndProcessedRows() {
        val file = Files.createTempDirectory("proactive-clear").resolve("events.jsonl").toFile()
        val store = ProactiveEventStore(file)
        val pending = event(body = "private pending payload")
        val processed = event(received = 2_000).copy(
            appLabel = "PRIVATE APP LABEL",
            sender = "PRIVATE SENDER",
            title = "PRIVATE TITLE",
            body = "PRIVATE PROCESSED BODY",
            sourceInstanceKey = "PRIVATE SOURCE KEY",
            conversationThread = "PRIVATE THREAD",
        )
        val discarded = event(received = 3_000, ongoing = true).copy(
            appLabel = "PRIVATE DISCARDED LABEL",
            sender = "PRIVATE DISCARDED SENDER",
            title = "PRIVATE DISCARDED TITLE",
            body = "PRIVATE DISCARDED BODY",
            conversationThread = "PRIVATE DISCARDED THREAD",
        )
        store.append(pending)
        store.append(processed)
        store.markProcessed(processed.id)
        store.recordDiscard(discarded, "ongoing_notification")
        file.appendText("{malformed PRIVATE CORRUPT BODY\n")
        assertEquals(1, store.clearPending())
        val retained = store.readAll()
        assertEquals(setOf(processed.id, discarded.id), retained.map { it.id }.toSet())
        val savedProcessed = retained.single { it.id == processed.id }
        val savedDiscarded = retained.single { it.id == discarded.id }
        assertEquals(ProactiveEvent.PROCESSED, savedProcessed.state)
        assertEquals(processed.sourceId, savedProcessed.sourceId)
        assertEquals(processed.appPackage, savedProcessed.appPackage)
        assertEquals(processed.category, savedProcessed.category)
        assertEquals(processed.receivedAtMillis, savedProcessed.receivedAtMillis)
        assertEquals(processed.dedupKey, savedProcessed.dedupKey)
        assertEquals(ProactiveEvent.DISCARDED, savedDiscarded.state)
        assertEquals("ongoing_notification", savedDiscarded.discardReason)
        retained.forEach { event ->
            assertNull(event.appLabel)
            assertNull(event.sender)
            assertNull(event.title)
            assertNull(event.body)
            assertNull(event.sourceInstanceKey)
            assertNull(event.conversationThread)
        }
        val contents = file.readText()
        listOf("private pending payload", "PRIVATE APP LABEL", "PRIVATE SENDER", "PRIVATE TITLE",
            "PRIVATE PROCESSED BODY", "PRIVATE SOURCE KEY", "PRIVATE THREAD", "PRIVATE DISCARDED LABEL",
            "PRIVATE DISCARDED SENDER", "PRIVATE DISCARDED TITLE", "PRIVATE DISCARDED BODY",
            "PRIVATE DISCARDED THREAD", "PRIVATE CORRUPT BODY").forEach { assertFalse(contents.contains(it)) }
    }

    @Test fun p0JsonlRowsWithoutNewFieldsRemainReadable() {
        val path = Files.createTempDirectory("proactive-legacy").resolve("events.jsonl").toFile()
        path.writeText("""{"id":"legacy-id","sourceId":"notifications","dedupKey":"legacy-key","receivedAtMillis":123,"observedAtMillis":456,"appPackage":"com.chat","appLabel":"Chat","sender":"Kai","title":"Hi","body":"Hello","category":"msg","direction":null,"state":"pending"}""" + "\n")
        val old = ProactiveEventStore(path).pending().single()
        assertEquals("legacy-id", old.id)
        assertEquals(123L, old.receivedAtMillis)
        assertFalse(old.ongoing)
        assertFalse(old.foregroundService)
        assertFalse(old.groupSummary)
        assertNull(old.androidCategory)
        assertNull(old.discardReason)
    }

    private fun event(
        app: String = "com.example.chat",
        title: String = "A title",
        body: String = "A body",
        sender: String? = "Rae",
        androidCategory: String? = "msg",
        category: String = "msg",
        ongoing: Boolean = false,
        foregroundService: Boolean = false,
        groupSummary: Boolean = false,
        received: Long = 1_000,
        observed: Long = received + 1,
        notificationKey: String? = null,
        sourceId: String = "notifications",
    ): ProactiveEvent {
        val normalized = ProactiveNormalizer.notification(
            NotificationInput(
                sourceId = sourceId,
                receivedAtMillis = received,
                observedAtMillis = observed,
                appPackage = app,
                appLabel = app,
                title = title,
                body = body,
                androidCategory = androidCategory,
                messagingSender = sender,
                ongoing = ongoing,
                foregroundService = foregroundService,
                groupSummary = groupSummary,
                notificationKey = notificationKey,
            ),
        )
        return normalized.copy(category = category)
    }

    private fun store(): ProactiveEventStore = ProactiveEventStore(
        Files.createTempDirectory("proactive-p1").resolve("events.jsonl").toFile(),
    )
}

package com.jarvys.agent.proactive

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveEventTest {
    @Test fun notificationSourceSpecHasBilingualDescriptionAndListenerPermission() {
        assertTrue(ProactiveSources.notifications.descriptionForModel.en.isNotBlank())
        assertTrue(ProactiveSources.notifications.descriptionForModel.es.isNotBlank())
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", ProactiveSources.notifications.requiredPermission)
        assertEquals("notification_listener", ProactiveSources.notifications.trigger)
    }

    @Test fun notificationCategoriesAndMessagingSenderAreNormalizedFromSourceMetadata() {
        val fixtures = listOf(
            notification("bank", "Ava via Zelle", "You received \\$25", "msg", expected = "msg", sender = "Ava"),
            notification("github", "GitHub", "Review requested", "social", expected = "social"),
            notification("promo", "Store", "Sale today", null, expected = "other"),
            notification("mail", "Inbox", "Invoice", "email", expected = "email"),
            notification("phone", "Phone", "Incoming call", "call", expected = "call"),
            notification("phone", "Phone", "Missed call", "call", expected = "missed_call", missed = true),
        )
        fixtures.forEach { (input, expected, sender) ->
            val event = ProactiveNormalizer.notification(input)
            assertEquals(expected, event.category)
            if (sender != null) assertEquals(sender, event.sender)
        }
    }

    @Test fun dedupKeyIsStableForRepostsAndChangesForContentOrOriginalTime() {
        val base = notification("zelle", "Ava", "Payment received", "msg", expected = "msg").first
        val first = ProactiveNormalizer.notification(base)
        val republished = ProactiveNormalizer.notification(base.copy(observedAtMillis = base.observedAtMillis + 5_000))
        val changedBody = ProactiveNormalizer.notification(base.copy(body = "Payment reversed"))
        val changedOriginalTime = ProactiveNormalizer.notification(base.copy(receivedAtMillis = base.receivedAtMillis + 1))
        assertEquals(first.dedupKey, republished.dedupKey)
        assertNotEquals(first.dedupKey, changedBody.dedupKey)
        assertNotEquals(first.dedupKey, changedOriginalTime.dedupKey)
        assertEquals(base.receivedAtMillis, first.receivedAtMillis)
    }

    @Test fun redactorHidesContextualCodesAndLuhnCardsButPreservesOtherText() {
        assertEquals("Your verification code is [código oculto].", ProactiveRedactor.redactForModel("Your verification code is 839204."))
        assertEquals("Card [tarjeta oculta]", ProactiveRedactor.redactForModel("Card 4111 1111 1111 1111"))
        assertEquals("Reference 1234567", ProactiveRedactor.redactForModel("Reference 1234567"))
        assertEquals("Nothing sensitive here.", ProactiveRedactor.redactForModel("Nothing sensitive here."))
        val event = ProactiveNormalizer.notification(
            baseInput(body = "Use code 839204; card 4111111111111111"),
        )
        assertTrue(event.body.orEmpty().contains("839204"))
        assertTrue(ProactiveRedactor.redactForModel(event.body.orEmpty()).contains("[código oculto]"))
        assertFalse(ProactiveRedactor.redactForModel(event.body.orEmpty()).contains("4111111111111111"))
    }

    @Test fun storeDeduplicatesPersistsStateAndIgnoresCorruptRowsWithoutLosingLaterEvents() {
        val directory = Files.createTempDirectory("proactive-store").toFile()
        val file = File(directory, "events.jsonl")
        val store = ProactiveEventStore(file)
        val first = ProactiveNormalizer.notification(baseInput(body = "first"))
        val second = ProactiveNormalizer.notification(baseInput(body = "second"))
        assertTrue(store.append(first))
        assertFalse(store.append(first.copy(id = "new-event-id")))
        file.appendText("{not valid json}\n")
        assertTrue(store.append(second))
        assertEquals(listOf(first.id, second.id), store.pending().map { it.id })
        assertTrue(store.markProcessed(first.id))
        assertEquals(listOf(second.id), store.pending().map { it.id })

        val reopened = ProactiveEventStore(file)
        assertEquals(listOf(second.id), reopened.pending().map { it.id })
        assertTrue(reopened.markProcessed(second.id))
        assertTrue(ProactiveEventStore(file).pending().isEmpty())
    }

    @Test fun concurrentAppendsOfSameDedupKeyOnlyPersistOneEvent() {
        val directory = Files.createTempDirectory("proactive-concurrent").toFile()
        val event = ProactiveNormalizer.notification(baseInput())
        val stores = List(12) { ProactiveEventStore(File(directory, "events.jsonl")) }
        val executor = Executors.newFixedThreadPool(stores.size)
        try {
            val outcomes = executor.invokeAll(stores.map { store -> Callable { store.append(event.copy(id = "event-${Thread.currentThread().id}")) } })
                .map { it.get() }
            assertEquals(1, outcomes.count { it })
            assertEquals(1, ProactiveEventStore(File(directory, "events.jsonl")).pending().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun notificationCapturePathNormalizesAndAppendsImmediately() {
        val directory = Files.createTempDirectory("proactive-capture").toFile()
        val store = ProactiveEventStore(File(directory, "events.jsonl"))
        val input = baseInput(body = "  Zelle   received payment  ")
        assertEquals(ProactiveCaptureResult.CandidateStored("other"),
            ProactiveNotificationCapture.capture(input, store, "com.jarvys.agent"))
        assertEquals(ProactiveCaptureResult.Duplicate,
            ProactiveNotificationCapture.capture(input, store, "com.jarvys.agent"))
        assertEquals("Zelle received payment", store.pending().single().body)
    }

    private fun notification(
        app: String,
        title: String,
        body: String,
        category: String?,
        expected: String,
        sender: String? = null,
        missed: Boolean = false,
    ): Triple<NotificationInput, String, String?> = Triple(
        baseInput(appPackage = "com.example.$app", appLabel = app, title = title, body = body,
            androidCategory = category, messagingSender = sender, isMissedCall = missed),
        expected,
        sender,
    )

    private fun baseInput(
        appPackage: String = "com.example.chat",
        appLabel: String = "Chat",
        title: String = "Title",
        body: String = "Body",
        androidCategory: String? = null,
        messagingSender: String? = null,
        isMissedCall: Boolean = false,
    ) = NotificationInput(
        receivedAtMillis = 1_700_000_000_000,
        observedAtMillis = 1_700_000_000_500,
        appPackage = appPackage,
        appLabel = appLabel,
        title = title,
        body = body,
        androidCategory = androidCategory,
        messagingSender = messagingSender,
        isMissedCall = isMissedCall,
    )
}

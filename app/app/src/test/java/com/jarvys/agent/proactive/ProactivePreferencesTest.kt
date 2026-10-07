package com.jarvys.agent.proactive

import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.WorkManagerTestCleanup
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactivePreferencesTest {
    @Before fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
    }

    @After fun closeWorkManager() {
        WorkManagerTestCleanup.close(RuntimeEnvironment.getApplication())
    }

    @Test fun captureIsClosedByDefaultOpensOnOptInAndOptOutRemovesPendingPayloads() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jarvys_proactive_preferences", 0).edit().clear().commit()
        val file = File(Files.createTempDirectory("proactive-preferences").toFile(), "events.jsonl")
        val store = ProactiveEventStore(file)
        val preferences = ProactivePreferences(context, store)
        var captureRan = false
        assertFalse(preferences.enabled)
        assertFalse(preferences.captureIfEnabled {
            captureRan = true
            ProactiveNotificationCapture.capture(input("pending secret"), store, context.packageName)
        })
        assertFalse(captureRan)
        assertFalse(file.exists())

        preferences.enabled = true
        assertTrue(preferences.enabled)
        assertTrue(preferences.captureIfEnabled {
            ProactiveNotificationCapture.capture(input("pending secret"), store, context.packageName)
        })
        assertEquals(ProactiveCaptureResult.DiscardedStored("ongoing_notification"),
            ProactiveNotificationCapture.capture(input("ongoing audit", ongoing = true), store, context.packageName))
        assertEquals(1, store.pending().size)

        preferences.enabled = false
        assertFalse(preferences.enabled)
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf(ProactiveEvent.DISCARDED), store.readAll().map { it.state })
        assertTrue(store.readAll().single().discardReason == "ongoing_notification")
        assertFalse(file.readText().contains("pending secret"))
        assertFalse(preferences.captureIfEnabled { captureRan = true })
    }

    private fun input(body: String, ongoing: Boolean = false) = NotificationInput(
        receivedAtMillis = 100,
        observedAtMillis = 101,
        appPackage = "com.example.mail",
        appLabel = "Mail",
        title = "A message",
        body = body,
        androidCategory = "email",
        ongoing = ongoing,
        notificationKey = body,
    )
}

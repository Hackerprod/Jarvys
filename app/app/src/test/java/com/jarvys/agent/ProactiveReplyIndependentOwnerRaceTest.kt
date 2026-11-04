package com.jarvys.agent

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Native queued-owner and presentation-lifetime regression tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveReplyIndependentOwnerRaceTest {
    @get:Rule val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "reviewer-owner-race"
    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private val store get() = LocalRunStore(context)
    private fun receiverExecutor(): ExecutorService =
        Class.forName("com.jarvys.agent.proactive.ProactiveReplyReceiverExecutor")
            .getDeclaredField("executor").apply { isAccessible = true }.get(null) as ExecutorService
    private fun drainStarts(): List<Intent> = buildList {
        val app = Shadows.shadowOf(context as Application)
        while (true) add(app.nextStartedService ?: break)
    }
    @Before fun open() {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Run only after real D3 activation, never a gate override" }
        db {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get(30, TimeUnit.SECONDS)
        }
        AgentRunUiState.resetSession(session)
        AgentRunUiState.showSession(session)
        UserDecisionUiAvailability.setChatVisible(true)
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        drainStarts()
    }
    @After fun close() {
        receiverExecutor().submit {}.get(30, TimeUnit.SECONDS)
        ProactiveReplyAdmission.cancelCapturedInteractions(session)
        UserDecisionUiAvailability.setChatVisible(false)
        AgentRunUiState.resetSession(session)
        drainStarts()
    }
    private fun notice(): String = db {
        store.appendProactiveAssistantMessageIfAbsent(session, "reviewer-decision", "Reviewer exact notice",
            listOf(ProactiveSuggestedReply("Review", "Review this exact notice")), thread)
    }
    private fun inlineIntent(source: String): Intent {
        db { ProactiveInteractionDispatcher.refreshNotification(context, thread, source) }
        val notification = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val action = notification.actions.single { !it.remoteInputs.isNullOrEmpty() }
        return Intent(Shadows.shadowOf(action.actionIntent).savedIntent).also {
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(ProactiveActionReceiver.EXTRA_INLINE_REPLY).build()),
                it, Bundle().apply { putCharSequence(ProactiveActionReceiver.EXTRA_INLINE_REPLY, "Received before STOP") })
        }
    }

    @Test fun nativeStopRejectsAlreadyReceivedReplyStillQueuedBeforeCapture() {
        val source = notice()
        val intent = inlineIntent(source)
        val executor = receiverExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = executor.submit {
            blocked.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Reviewer executor latch timed out" }
        }
        assertTrue(blocked.await(30, TimeUnit.SECONDS))
        try {
            // Genuine onReceive has returned after owning and queueing the exact delivery.
            ProactiveActionReceiver().onReceive(context, intent)
            assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
            // Native deny-only ingress does not require a service or synthesize durable cancellation.
            AgentForegroundService.stopRun(session)
        } finally {
            release.countDown()
            blocker.get(30, TimeUnit.SECONDS)
            executor.submit {}.get(30, TimeUnit.SECONDS)
        }
        assertTrue("STOP must revoke the old received tap even before worker capture", db { store.readCentralSubmissions(session) }.isEmpty())
        assertTrue(db { store.readProactiveThreadMessages(session, thread) }.none { it.role == "user" })
        assertTrue("Old delivery must never send a Central service start", drainStarts().isEmpty())
        // A genuinely later receiver delivery is independent and may be accepted.
        ProactiveActionReceiver().onReceive(context, Intent(intent))
        executor.submit {}.get(30, TimeUnit.SECONDS)
        assertEquals(1, db { store.readCentralSubmissions(session) }.size)
        assertEquals(1, drainStarts().count { it.action == "com.jarvys.agent.CENTRAL_SUBMISSION" })
    }

    @Test fun visibleRouteRoundTripCannotRevivePreviouslyCapturedInteraction() {
        val source = notice()
        val event = db { store.readRecentConversationTimeline(session) }.single { it.messageId == source }
        AgentRunUiState.restoreSession(session, listOf(event))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val old = requireNotNull(ProactiveReplyAdmission.captureDisplayedOption(activity.get(), event, 0))
            try {
                // The activity remains STARTED throughout; the same event is retained when returning.
                UserDecisionUiAvailability.setChatVisible(false)
                AgentRunUiState.showSession("reviewer-other-session")
                AgentRunUiState.showSession(session)
                UserDecisionUiAvailability.setChatVisible(true)
                assertTrue(AgentRunUiState.containsExactProactiveSelection(session, event, 0))
                assertThrows("An intervening navigation must permanently expire this owner", IllegalStateException::class.java) {
                    old.requireCurrent()
                }
                assertThrows(IllegalStateException::class.java) { db { ProactiveReplyAdmission.prepare(context, old) } }
                assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
                val fresh = requireNotNull(ProactiveReplyAdmission.captureDisplayedOption(activity.get(), event, 0))
                fresh.use { assertNotNull(db { ProactiveReplyAdmission.prepare(context, it) }) }
                assertEquals(1, db { store.readCentralSubmissions(session) }.size)
            } finally { old.close() }
        } finally { activity.pause().stop().destroy() }
    }
}

package com.jarvys.agent

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentDraftViewModelTest {
    @Test fun defaultViewModelFactoryCanInstantiateItThroughTheApplicationConstructor() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        try {
            val model = androidx.lifecycle.ViewModelProvider(
                store,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[AttachmentDraftViewModel::class.java]
            assertNotNull(model)
        } finally { store.clear() }
    }

    @Test fun emptyAndStaleSessionSubmissionsDoNotStartWork() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val model = AttachmentDraftViewModel(application)
        val store = ViewModelStore().apply { put("attachments", model) }
        try {
            val session = UUID.randomUUID().toString()
            model.switchSession(session)
            assertFalse(model.submit(session, "  ", arrayListOf(), false))
            assertFalse(model.submit("old-session", "hello", arrayListOf(), false))
            assertFalse(model.sending.value)
            assertTrue(model.drafts.value.isEmpty())
        } finally { store.clear() }
    }

    @Test fun cancelledCameraCaptureIsRemovedAndClearedOnLifecycleEnd() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val model = AttachmentDraftViewModel(application)
        val store = ViewModelStore().apply { put("attachments", model) }
        val session = UUID.randomUUID().toString()
        model.switchSession(session)
        val first = model.createCameraFile(session)
        first.writeText("temporary capture")
        val second = model.createCameraFile(session)
        assertFalse("replacing a capture removes its old temporary file", first.exists())
        assertEquals(session, model.cameraSession)
        assertEquals(second, model.cameraFile)
        store.clear()
        assertFalse(second.exists())
        assertNull(model.cameraFile)
        assertNull(model.cameraSession)
        assertTrue(model.drafts.value.isEmpty())
    }

    @Test fun restoredCameraOnlyAcceptsItsPrivateCacheAndKeepsAnExistingCapture() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val model = AttachmentDraftViewModel(application)
        val store = ViewModelStore().apply { put("attachments", model) }
        val outside = File(application.cacheDir, "outside-camera.jpg").apply { writeText("outside") }
        val restored = File(application.cacheDir, "jarvys/camera/restored.jpg").apply {
            parentFile!!.mkdirs(); writeText("capture")
        }
        try {
            model.restoreCameraFile(outside.path, "central")
            assertNull(model.cameraFile)
            model.restoreCameraFile(restored.path, "central")
            assertEquals(restored, model.cameraFile)
            assertEquals("central", model.cameraSession)
            model.restoreCameraFile(outside.path, "other")
            assertEquals(restored, model.cameraFile)
        } finally { store.clear() }
        assertFalse(restored.exists())
        assertTrue(outside.exists())
    }

    @Test fun userRowIsVisibleBeforeMessagePersistenceStarts() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val executor = QueuedExecutor()
        val session = UUID.randomUUID().toString()
        var persistCalls = 0
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String {
                persistCalls++
                return "persisted-message-id"
            }
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) = Unit
        }, executor)
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(session, emptyList())
            model.switchSession(session)

            assertTrue(model.submit(session, "Visible immediately", arrayListOf(), false))

            val optimistic = AgentRunUiState.state.value.events.single { it.kind == "user" }
            assertEquals("Visible immediately", optimistic.text)
            assertTrue(optimistic.messageId.isBlank())
            assertTrue(AgentRunUiState.state.value.running)
            assertEquals(0, persistCalls)

            executor.runNext()

            assertEquals(1, persistCalls)
            assertEquals("persisted-message-id", AgentRunUiState.state.value.events.single { it.kind == "user" }.messageId)
        } finally {
            owner.clear()
            AgentRunUiState.restoreSession("attachment-optimistic-finished", emptyList())
        }
    }

    @Test fun stopBeforePersistenceCancelsOnlyThatSubmissionAndReportsCancellation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val executor = QueuedExecutor()
        val session = UUID.randomUUID().toString()
        var persistCalls = 0
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String {
                persistCalls++
                return "must-not-persist"
            }
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) = Unit
        }, executor)
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(session, emptyList())
            model.switchSession(session)
            assertTrue(model.submit(session, "Stop before saving", arrayListOf(), false))

            model.cancelSubmission(session)
            model.cancelSubmission(session)
            executor.runNext()
            val submission = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertEquals(0, persistCalls)
            assertFalse(submission.persisted)
            assertEquals(AppLanguageRuntime.localizedContext(application).getString(R.string.chat_attachment_send_cancelled), submission.error)
            assertFalse(AgentRunUiState.state.value.running)
            val failedRow = AgentRunUiState.state.value.events.single { it.kind == "user" }
            assertEquals("Stop before saving", failedRow.text)
            assertEquals(submission.error, failedRow.submissionError)
            assertFalse(AgentRunUiState.state.value.events.any { it.kind == "assistant" })
            model.cancelSubmission(session)
            assertFalse(model.sending.value)
        } finally {
            owner.clear()
            AgentRunUiState.restoreSession("attachment-pre-cancel-finished", emptyList())
        }
    }

    @Test fun stopAfterPersistenceDoesNotCancelTheStartedAgentOrChangeTheError() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val session = UUID.randomUUID().toString()
        val agentStarted = CountDownLatch(1)
        val finishAgentStart = CountDownLatch(1)
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>) = "already-persisted"
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) {
                agentStarted.countDown()
                check(finishAgentStart.await(10, TimeUnit.SECONDS))
            }
        })
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(session, emptyList())
            model.switchSession(session)
            assertTrue(model.submit(session, "Already saved", arrayListOf(), false))
            assertTrue("agent startup should be reached after persistence", agentStarted.await(10, TimeUnit.SECONDS))

            model.cancelSubmission(session)
            model.cancelSubmission(session)
            finishAgentStart.countDown()
            val submission = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertTrue(submission.persisted)
            assertNull(submission.error)
            assertEquals("already-persisted", AgentRunUiState.state.value.events.single { it.kind == "user" }.messageId)
        } finally {
            finishAgentStart.countDown()
            owner.clear()
            AgentRunUiState.restoreSession("attachment-post-persist-finished", emptyList())
        }
    }

    @Test fun anotherSessionCanPersistWhileThePreviousSessionIsDelayed() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val sessionA = "delayed-${UUID.randomUUID()}"
        val sessionB = "ready-${UUID.randomUUID()}"
        val persistenceAStarted = CountDownLatch(1)
        val releasePersistenceA = CountDownLatch(1)
        val agentAStarted = CountDownLatch(1)
        val agentBStarted = CountDownLatch(1)
        val persistedSessions = ConcurrentLinkedQueue<String>()
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String {
                if (session == sessionA) {
                    persistenceAStarted.countDown()
                    check(releasePersistenceA.await(10, TimeUnit.SECONDS))
                }
                persistedSessions += session
                return "message-$session"
            }
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) {
                if (session == sessionA) agentAStarted.countDown() else agentBStarted.countDown()
            }
        })
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(sessionA, emptyList())
            model.switchSession(sessionA)
            assertTrue(model.submit(sessionA, "Slow save", arrayListOf(), false))
            assertTrue("session A should reach the delayed persistence", persistenceAStarted.await(10, TimeUnit.SECONDS))

            AgentRunUiState.restoreSession(sessionB, emptyList())
            model.switchSession(sessionB)
            assertFalse("sending projects only the visible session", model.sending.value)
            assertTrue(model.isSending(sessionA))
            assertTrue("session A must not block a send from session B",
                model.submit(sessionB, "Save independently", arrayListOf(), false))
            assertTrue(model.isSending(sessionB))
            assertTrue("session B should reach agent startup while A is still held", agentBStarted.await(10, TimeUnit.SECONDS))
            val resultB = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertEquals(sessionB, resultB.sessionId)
            assertTrue(resultB.persisted)
            assertEquals(1L, releasePersistenceA.count)
            assertEquals(listOf(sessionB), persistedSessions.toList())
            assertFalse(model.sending.value)
            assertTrue(model.isSending(sessionA))
            assertFalse(model.isSending(sessionB))

            AgentRunUiState.restoreSession(sessionA, emptyList())
            model.switchSession(sessionA)
            assertTrue("returning to A projects its still-running submission", model.sending.value)
            releasePersistenceA.countDown()
            assertTrue(agentAStarted.await(10, TimeUnit.SECONDS))
            val resultA = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertEquals(sessionA, resultA.sessionId)
            assertTrue(resultA.persisted)
            assertEquals(listOf(sessionB, sessionA), persistedSessions.toList())
            assertFalse(model.sending.value)
            assertFalse(model.isSending(sessionA))
        } finally {
            releasePersistenceA.countDown()
            owner.clear()
            AgentRunUiState.restoreSession("attachment-multisession-finished", emptyList())
        }
    }

    @Test fun stoppingSessionBDoesNotCancelSessionA() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val executor = QueuedExecutor()
        val sessionA = "stop-a-${UUID.randomUUID()}"
        val sessionB = "stop-b-${UUID.randomUUID()}"
        val persistedSessions = mutableListOf<String>()
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String {
                persistedSessions += session
                return "message-$session"
            }
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) = Unit
        }, executor)
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(sessionA, emptyList())
            model.switchSession(sessionA)
            assertTrue(model.submit(sessionA, "A stays active", arrayListOf(), false))
            AgentRunUiState.restoreSession(sessionB, emptyList())
            model.switchSession(sessionB)
            assertFalse(model.sending.value)
            assertTrue(model.submit(sessionB, "Cancel B", arrayListOf(), false))

            model.cancelSubmission(sessionB)
            executor.runNext()
            executor.runNext()
            val resultA = runBlocking { withTimeout(10_000) { model.submissions.first() } }
            val resultB = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertEquals(sessionA, resultA.sessionId)
            assertTrue(resultA.persisted)
            assertNull(resultA.error)
            assertEquals(sessionB, resultB.sessionId)
            assertFalse(resultB.persisted)
            assertEquals(AppLanguageRuntime.localizedContext(application).getString(R.string.chat_attachment_send_cancelled), resultB.error)
            assertEquals(listOf(sessionA), persistedSessions)
        } finally {
            owner.clear()
            AgentRunUiState.restoreSession("attachment-stop-b-finished", emptyList())
        }
    }

    @Test fun stoppingSessionADoesNotCancelSessionB() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val executor = QueuedExecutor()
        val sessionA = "stop-a-${UUID.randomUUID()}"
        val sessionB = "stop-b-${UUID.randomUUID()}"
        val persistedSessions = mutableListOf<String>()
        val model = AttachmentDraftViewModel(application, object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String {
                persistedSessions += session
                return "message-$session"
            }
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>, memoryDisabled: Boolean) = Unit
        }, executor)
        val owner = ViewModelStore().apply { put("attachments", model) }
        try {
            AgentRunUiState.restoreSession(sessionA, emptyList())
            model.switchSession(sessionA)
            assertTrue(model.submit(sessionA, "Cancel A", arrayListOf(), false))
            AgentRunUiState.restoreSession(sessionB, emptyList())
            model.switchSession(sessionB)
            assertFalse(model.sending.value)
            assertTrue(model.submit(sessionB, "B stays active", arrayListOf(), false))

            model.cancelSubmission(sessionA)
            executor.runNext()
            executor.runNext()
            val resultA = runBlocking { withTimeout(10_000) { model.submissions.first() } }
            val resultB = runBlocking { withTimeout(10_000) { model.submissions.first() } }

            assertEquals(sessionA, resultA.sessionId)
            assertFalse(resultA.persisted)
            assertEquals(AppLanguageRuntime.localizedContext(application).getString(R.string.chat_attachment_send_cancelled), resultA.error)
            assertEquals(sessionB, resultB.sessionId)
            assertTrue(resultB.persisted)
            assertNull(resultB.error)
            assertEquals(listOf(sessionB), persistedSessions)
        } finally {
            owner.clear()
            AgentRunUiState.restoreSession("attachment-stop-a-finished", emptyList())
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) = synchronized(tasks) {
            check(!shutdown)
            tasks.addLast(command)
        }

        fun runNext() = synchronized(tasks) { tasks.removeFirst() }.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> = synchronized(tasks) {
            shutdown = true
            tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown && synchronized(tasks) { tasks.isEmpty() }
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}

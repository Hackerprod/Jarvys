package com.jarvys.agent

import android.app.Application
import android.content.ComponentName
import android.content.Intent
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
import java.util.UUID

/** A persisted row stays targetable even if the service never starts; no live service/provider is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ReactionFailingStartApplication::class)
class MessageReactionSubmissionTest {
    @Test fun failedServiceStartStillBindsPersistedMessageForALaterLiveReaction() {
        val app = ApplicationProvider.getApplicationContext<ReactionFailingStartApplication>()
        val session = "reaction-interrupted-${UUID.randomUUID()}"
        val model = AttachmentDraftViewModel(app)
        val owner = ViewModelStore().apply { put("draft", model) }
        try {
            AgentRunUiState.restoreSession(session, emptyList())
            model.switchSession(session)
            assertTrue(model.submit(session, "Keep this request", arrayListOf(), false))
            val submission = runBlocking { withTimeout(10_000) { model.submissions.first() } }
            assertTrue(submission.persisted)
            assertNotNull(submission.error)
            assertEquals(1, app.startAttempts)
            val store = LocalRunStore(app)
            val persisted = store.readConversationTimeline(session).single()
            val live = AgentRunUiState.state.value.events.single { it.kind == "user" }
            assertTrue(live.messageId.isNotBlank())
            assertEquals(persisted.messageId, live.messageId)
            assertFalse(AgentRunUiState.state.value.running)
            val reaction = MessageReactionTool(store, session)
            reaction.prepareModelMetadata(store.loadConversationContext(session), "")
            assertTrue(reaction.execute(mapOf("message_id" to persisted.messageId, "emoji" to "👀"),
                CancellationToken.uncancellable()).success)
            assertEquals("👀", AgentRunUiState.state.value.events.single { it.kind == "user" }.reactionEmoji)
            assertEquals("Keep this request", live.text)
        } finally {
            owner.clear()
            AgentRunUiState.restoreSession("reaction-submission-test-finished", emptyList())
        }
    }
}

class ReactionFailingStartApplication : Application() {
    @Volatile var startAttempts = 0
    override fun startForegroundService(service: Intent): ComponentName? {
        startAttempts++
        throw IllegalStateException("Hermetic test: service startup unavailable")
    }
}

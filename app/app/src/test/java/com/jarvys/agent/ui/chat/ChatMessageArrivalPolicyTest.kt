package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageArrivalPolicyTest {
    @Test fun restorationAndScrollDoNotAnimateExistingTranscriptRows() {
        val restored = listOf(
            AgentRunUiEvent(1, "user", text = "Original request"),
            AgentRunUiEvent(2, "assistant", text = "Saved response"),
        ).map(ChatMessageArrivalPolicy::key)
        assertNull(ChatMessageArrivalPolicy.newestUnseen(restored.toSet(), restored, liveChange = false))
        assertNull(ChatMessageArrivalPolicy.newestUnseen(restored.toSet(), restored, liveChange = true))
    }

    @Test fun onlyTheNewestUnseenRowGetsAnArrivalCue() {
        val previous = setOf("user:request-1")
        val current = listOf("user:request-1", "tool:4", "assistant:answer-2")
        assertEquals("assistant:answer-2",
            ChatMessageArrivalPolicy.newestUnseen(previous, current, liveChange = true))
    }
}

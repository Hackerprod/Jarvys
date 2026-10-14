package com.jarvys.agent.tasks

import com.jarvys.agent.CancellationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRespondToolTest {
    @Test fun optionalMetadataDoesNotFinishTheAgentRun() {
        val tool = TaskRespondTool()
        val result = tool.execute(mapOf(
            "title" to "Daily summary",
            "urgency" to "high",
            "suggested_replies" to listOf(mapOf("label" to "Open", "text" to "Show details")),
        ), CancellationToken.uncancellable())

        assertTrue(result.success)
        assertFalse(result.finishRun)
        assertEquals("Daily summary", tool.result()?.title)
        assertEquals("high", tool.result()?.urgency)
        assertEquals("Show details", tool.result()?.suggestedReplies?.single()?.text)
        assertFalse(tool.declaration().description.contains("Never ask the user"))
        assertFalse(tool.declaration().description.contains("Finish this unattended"))
    }

    @Test fun metadataIsOptionalAndStillDoesNotBecomeATerminalResponse() {
        val tool = TaskRespondTool()
        val result = tool.execute(emptyMap(), CancellationToken.uncancellable())

        assertTrue(result.success)
        assertFalse(result.finishRun)
        assertNull(tool.result()?.title)
        assertEquals("normal", tool.result()?.urgency)
        assertTrue(tool.result()?.suggestedReplies.orEmpty().isEmpty())
    }
}

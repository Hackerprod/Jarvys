package com.jarvys.agent.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCanvasMemoryPolicyTest {
    @Test
    fun composeBaselineCountsOnlyItsProcessBecauseItHasNoChatWebView() {
        assertEquals(42_000, ChatCanvasMemoryPolicy.composeTotalPssKb(42_000))
    }

    @Test
    fun canvasComparisonIncludesRendererWhenAndroidExposesItsPss() {
        assertEquals(47_000, ChatCanvasMemoryPolicy.canvasTotalPssKb(42_000, 5_000))
        assertEquals(true, ChatCanvasMemoryPolicy.canvasWithinCompose(48_000, 42_000, 5_000))
        assertEquals(false, ChatCanvasMemoryPolicy.canvasWithinCompose(46_000, 42_000, 5_000))
    }

    @Test
    fun canvasMemoryGateIsUnmeasuredWhenRendererPssIsUnavailable() {
        assertNull(ChatCanvasMemoryPolicy.canvasTotalPssKb(42_000, null))
        assertNull(ChatCanvasMemoryPolicy.canvasWithinCompose(48_000, 42_000, null))
    }
}

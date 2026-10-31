package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollPolicyTest {
    @Test fun openingConversationJumpsWithoutAnimationAndStartsFollowing() {
        assertEquals(ChatScrollDecision(true, false, true),
            ChatScrollPolicy.decide(ChatScrollTrigger.CONVERSATION_OPENED, false, false))
    }

    @Test fun newUserMessageAndExplicitJumpReturnToTail() {
        assertEquals(ChatScrollDecision(true, true, true),
            ChatScrollPolicy.decide(ChatScrollTrigger.USER_MESSAGE_SENT, false, false))
        assertEquals(ChatScrollDecision(true, true, true),
            ChatScrollPolicy.decide(ChatScrollTrigger.JUMP_TO_END, false, false))
    }

    @Test fun streamingUpdatesFollowOnlyWhenAlreadyFollowing() {
        assertEquals(ChatScrollDecision(true, false, true),
            ChatScrollPolicy.decide(ChatScrollTrigger.STREAM_UPDATE, true, true))
        assertEquals(ChatScrollDecision(false, false, false),
            ChatScrollPolicy.decide(ChatScrollTrigger.STREAM_UPDATE, false, false))
    }

    @Test fun userScrollAwayPausesFollowingAndReturningToTailReengagesIt() {
        assertEquals(ChatScrollDecision(false, false, false),
            ChatScrollPolicy.decide(ChatScrollTrigger.USER_SCROLLED, true, false))
        assertEquals(ChatScrollDecision(false, false, true),
            ChatScrollPolicy.decide(ChatScrollTrigger.USER_SCROLLED, false, true))
    }

}

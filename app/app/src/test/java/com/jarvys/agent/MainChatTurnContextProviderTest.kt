package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainChatTurnContextProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun activeRunReadsDurableUserMessagesOnceAndRestartCanRecoverThem() {
        val store = LocalRunStore(temporary.newFolder())
        val session = "main-chat-steering"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val activeProvider = MainChatTurnContextProvider(store, session, initialId)
        val steeringId = store.appendConversationMessage(session, "user", "follow-up while working")

        val incoming = activeProvider.takeDurableMessages()

        assertEquals(1, incoming.size)
        assertEquals(steeringId, incoming.single().id)
        assertEquals("follow-up while working", incoming.single().content)
        assertTrue(incoming.single().genuineUser)
        assertEquals("main_chat", incoming.single().sourceKind)
        assertTrue(activeProvider.takeDurableMessages().isEmpty())

        val recoveredProvider = MainChatTurnContextProvider(store, session, initialId)
        val recovered = recoveredProvider.takeDurableMessages()
        assertEquals(steeringId, recovered.single().id)
        assertEquals("follow-up while working", recovered.single().content)
    }

    @Test fun pickedSteeringRemainsUnconsumedUntilTheLoopAcknowledgesTranscriptInsertion() {
        val store = LocalRunStore(temporary.newFolder())
        val session = "main-chat-steering-ack"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val provider = MainChatTurnContextProvider(store, session, initialId)
        val steeringId = store.appendConversationMessage(session, "user", "follow-up while working")

        val picked = provider.takeDurableMessages()

        assertEquals(steeringId, provider.latestUnincorporatedSteeringMessageId())
        assertTrue(provider.hasUnconsumedMessages())
        provider.durableMessagesIncorporated(picked)
        assertEquals("", provider.latestUnincorporatedSteeringMessageId())
        assertFalse(provider.hasUnconsumedMessages())
    }

    @Test fun steeringArrivingAfterClosureStillRemainsEligibleForFollowUp() {
        val store = LocalRunStore(temporary.newFolder())
        val session = "main-chat-steering-late"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val provider = MainChatTurnContextProvider(store, session, initialId)
        provider.closeForRunExit()

        val lateId = store.appendConversationMessage(session, "user", "arrived after closure")

        assertEquals(lateId, provider.latestUnincorporatedSteeringMessageId())
        assertTrue(provider.hasUnconsumedMessages())
    }

    @Test fun restartDoesNotCollectAnAlreadyIncorporatedMessageAgainFromAnOlderCursor() {
        val store = LocalRunStore(temporary.newFolder())
        val session = "main-chat-steering-old-cursor"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val steeringId = store.appendConversationMessage(session, "user", "already incorporated")
        store.markConversationMessagesIncorporated(session, listOf(steeringId))

        val restartedProvider = MainChatTurnContextProvider(store, session, initialId)

        assertTrue(restartedProvider.takeDurableMessages().isEmpty())
        assertEquals("", restartedProvider.latestUnincorporatedSteeringMessageId())
    }

    @Test fun dispatchedButUnincorporatedSteeringIsRecoveredAcrossProviderRestart() {
        val store = LocalRunStore(temporary.newFolder())
        val session = "main-chat-steering-restart"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val requestId = store.appendInterruptRequest(session, "accepted steering", emptyList(), false)
        assertTrue(store.dispatchInterruptRequest(session, requestId) != null)

        val firstProvider = MainChatTurnContextProvider(store, session, initialId)
        val picked = firstProvider.takeDurableMessages()
        assertEquals(requestId, picked.single().id)
        firstProvider.closeForRunExit()
        assertEquals(requestId, store.readPendingInterruptRequests(session).single().optString("requestId"))

        val recoveredProvider = MainChatTurnContextProvider(store, session, initialId)
        assertEquals(requestId, recoveredProvider.latestUnincorporatedSteeringMessageId())
        val recovered = recoveredProvider.takeDurableMessages()
        recoveredProvider.durableMessagesIncorporated(recovered)
        assertTrue(store.readPendingInterruptRequests(session).isEmpty())

        val restartedProvider = MainChatTurnContextProvider(store, session, initialId)
        assertEquals("", restartedProvider.latestUnincorporatedSteeringMessageId())
    }
}

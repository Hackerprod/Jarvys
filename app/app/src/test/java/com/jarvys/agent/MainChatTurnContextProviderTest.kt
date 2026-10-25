package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class MainChatTurnContextProviderTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()

    @Test fun activeRunReadsDurableUserMessagesOnceAndRestartCanRecoverThem() {
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
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
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
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
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        val session = "main-chat-steering-late"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val provider = MainChatTurnContextProvider(store, session, initialId)
        provider.closeForRunExit()

        val lateId = store.appendConversationMessage(session, "user", "arrived after closure")

        assertEquals(lateId, provider.latestUnincorporatedSteeringMessageId())
        assertTrue(provider.hasUnconsumedMessages())
    }

    @Test fun restartDoesNotCollectAnAlreadyIncorporatedMessageAgainFromAnOlderCursor() {
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        val session = "main-chat-steering-old-cursor"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val steeringId = store.appendConversationMessage(session, "user", "already incorporated")
        store.markConversationMessagesIncorporated(session, listOf(steeringId))

        val restartedProvider = MainChatTurnContextProvider(store, session, initialId)

        assertTrue(restartedProvider.takeDurableMessages().isEmpty())
        assertEquals("", restartedProvider.latestUnincorporatedSteeringMessageId())
    }

    @Test fun dispatchedButUnincorporatedSteeringIsRecoveredAcrossProviderRestart() {
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        val session = "main-chat-steering-restart"
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val requestId = store.appendInterruptRequest(session, "accepted steering", emptyList())
        assertTrue(store.dispatchInterruptRequest(session, requestId) != null)
        assertTrue(store.readConversationMessages(session).any {
            it.optString("messageId") == requestId && it.optBoolean("mainChatAcknowledgementRequired")
        })

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

    @Test fun explicitResolutionDoesNotClaimTranscriptIncorporation() {
        val files = chatTestStore.newFilesDirectory()
        val store = chatTestStore.localRunStore(files)
        val session = "main-chat-steering-resolved"
        store.appendConversationMessage(session, "user", "initial request")
        val requestId = store.appendInterruptRequest(session, "stopped steering", emptyList())
        assertTrue(store.dispatchInterruptRequest(session, requestId) != null)

        store.markConversationMessagesResolved(session, listOf(requestId))
        val reopened = chatTestStore.localRunStore(files)
        assertTrue(reopened.readPendingInterruptRequests(session).isEmpty())
        assertFalse(store.incorporatedConversationMessageIds(session).contains(requestId))

        store.markConversationMessagesIncorporated(session, listOf(requestId))
        assertFalse(store.incorporatedConversationMessageIds(session).contains(requestId))
    }

    @Test fun terminalAssistantResponseResolvesOwnerForRecoveryAndProvider() {
        for (status in listOf("FAILED", "PARTIAL", "STOPPED")) {
            val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
            val session = "main-chat-steering-terminal-$status"
            val initialId = store.appendConversationMessage(session, "user", "initial request")
            val requestId = store.appendInterruptRequest(session, "terminal steering", emptyList())
            assertTrue(store.dispatchInterruptRequest(session, requestId) != null)
            val provider = MainChatTurnContextProvider(store, session, initialId)

            store.appendConversationMessage(session, "assistant", "terminal response", null, "", requestId, status)

            assertTrue("$status owner is not recoverable", store.readPendingInterruptRequests(session).isEmpty())
            assertTrue(store.resolvedConversationMessageIds(session).contains(requestId))
            assertTrue("$status owner is not redelivered", provider.takeDurableMessages().isEmpty())
            assertEquals("", provider.latestUnincorporatedSteeringMessageId())
        }
    }
}

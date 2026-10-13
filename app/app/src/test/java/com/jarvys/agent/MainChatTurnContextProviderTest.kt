package com.jarvys.agent

import org.junit.Assert.assertEquals
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
}

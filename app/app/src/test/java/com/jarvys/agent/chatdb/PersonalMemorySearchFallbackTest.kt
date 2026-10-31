package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatTestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalMemorySearchFallbackTest {
    @get:Rule val chatStore = ChatTestStore()

    @Test fun forcedFts4AndLikeOpenDatabaseAndReturnBasicRankedMatches() {
        assertFallbackSearch(PersonalMemorySearchMode.FTS4, "fts4-search")
        assertFallbackSearch(PersonalMemorySearchMode.LIKE, "like-search")
    }

    @Test fun likeSearchTreatsPercentAndUnderscoreAsLiteralTermCharacters() {
        val files = chatStore.newFilesDirectory("like-search-special-characters")
        PersonalMemorySearchIndex.capabilityOverrideForTests = PersonalMemorySearchMode.LIKE
        try {
            chatStore.withLocalRunStore(files) {
                val app = ApplicationProvider.getApplicationContext<Context>()
                val context = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir() = files
                }
                val bridge = ChatDatabaseBridge.getInstance(context)
                assertTrue(bridge.ensureConversationSync("like-special-session") is ChatDbResult.Success)
                val literalId = bridge.personalAgentSaveMemory("account_id is paired with a 100% discount", "daily", null, .4, "test").id
                bridge.personalAgentSaveMemory("accountXid is paired with a 1000 discount", "daily", null, .4, "test")

                assertEquals(listOf(literalId), bridge.personalAgentSearch("account_id").map { it.id })
                assertEquals(listOf(literalId), bridge.personalAgentSearch("100%").map { it.id })
            }
        } finally {
            PersonalMemorySearchIndex.capabilityOverrideForTests = null
        }
    }

    private fun assertFallbackSearch(mode: PersonalMemorySearchMode, directoryName: String) {
        val files = chatStore.newFilesDirectory(directoryName)
        PersonalMemorySearchIndex.capabilityOverrideForTests = mode
        try {
            chatStore.withLocalRunStore(files) {
                val app = ApplicationProvider.getApplicationContext<Context>()
                val context = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir() = files
                }
                val bridge = ChatDatabaseBridge.getInstance(context)
                assertTrue(bridge.ensureConversationSync("$directoryName-session") is ChatDbResult.Success)
                val weaker = bridge.personalAgentSaveMemory("Hackerprod enjoys gardening", "daily", null, .4, "test")
                val stronger = bridge.personalAgentSaveMemory("Hackerprod develops Android applications", "curated", null, .8, "test")

                assertEquals(mode.storedValue, bridge.personalAgentSearchCapability())
                val hits = bridge.personalAgentSearch("Hacker Andro")
                assertEquals(listOf(stronger.id, weaker.id), hits.map { it.id })
                assertTrue(hits.first().score > hits.last().score)
                PersonalMemorySearchIndex.capabilityOverrideForTests = null
                assertEquals(mode.storedValue, bridge.personalAgentSearchCapability())
            }
        } finally {
            PersonalMemorySearchIndex.capabilityOverrideForTests = null
        }
    }
}

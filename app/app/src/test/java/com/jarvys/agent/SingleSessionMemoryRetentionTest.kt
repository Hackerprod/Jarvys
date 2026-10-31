package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.chatdb.AgentDocDefaults
import com.jarvys.agent.chatdb.ChatSqlDatabase
import com.jarvys.agent.chatdb.JarvysChatDatabase
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.MemorySaveOptions
import com.jarvys.agent.chatdb.MemorySearchFilter
import com.jarvys.agent.chatdb.MessagePartInput
import com.jarvys.agent.chatdb.MessagePartKinds
import com.jarvys.agent.chatdb.PersonalAgentRepository
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatReadTransaction
import com.jarvys.agent.chatdb.withChatWriteTransaction
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * S5 contracts B1, B2, B5 and B14 against the real tools, repositories and transcript producer; no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SingleSessionMemoryRetentionTest {
    @get:Rule val fixture = ChatTestStore()
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    private fun contextFor(files: File): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
    }

    private class Fixture(val files: File, val store: LocalRunStore, val context: Context) {
        val bridge: ChatDatabaseBridge get() = ChatDatabaseBridge.getInstance(context)
        val database: JarvysChatDatabase get() = JarvysChatDatabaseProvider.getInstance(context)
        val repository: PersonalAgentRepository get() = PersonalAgentRepository(database)
    }

    private fun open(): Fixture {
        val files = fixture.newFilesDirectory()
        val store = fixture.localRunStore(files)
        return Fixture(files, store, contextFor(files))
    }

    private fun <T> Fixture.read(block: (ChatSqlDatabase) -> T): T = runBlocking {
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction { block(database.chatSql) } }
    }

    private fun Fixture.write(block: (ChatSqlDatabase) -> Unit) = runBlocking {
        database.withChatWriteTransaction { block(database.chatSql) }
    }

    private fun Fixture.tool(name: String): CoreTool =
        PersonalMemoryTools.create(context, "", central, CorePromptBudget.standard().toolResultsPerTurnChars)
            .first { it.declaration().name == name }

    private fun Fixture.call(name: String, vararg arguments: Pair<String, Any?>): CoreToolResult =
        tool(name).execute(linkedMapOf<String, Any>().apply { arguments.forEach { (k, v) -> if (v != null) put(k, v) } },
            CancellationToken.uncancellable())

    private fun summarizer(): ModelProviderClient = object : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
            tools: List<ToolSpec>, sessionId: String, token: CancellationToken) = ModelReply("A short summary of the work.", emptyList())

        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
            images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
            token: CancellationToken) = complete(systemPrompt, userPrompt, images, tools, sessionId, token)
    }

    private fun Fixture.compactEverything() {
        ConversationCompactor(central, CoreAgentModel(context, summarizer(), central), store).compact(
            store.loadConversationContext(central), 32_768, "manual", ConversationCompactionPolicy.Mode.ALL,
            CancellationToken.uncancellable(), null,
        ) ?: error("nothing was compacted")
    }

    // ---------------------------------------------------------------- B1

    @Test fun B1_topicKeyUpdatesTheSameEntryAndTheFactIsRecoverableAfterCompaction() {
        val f = open()
        f.store.appendConversationMessage(central, "user", "How do we authenticate?")
        f.store.appendConversationMessage(central, "assistant", "Server sessions for now.")
        f.store.appendConversationMessage(central, "user", "Keep going")
        val first = f.bridge.personalAgentSaveMemory("Authentication uses server sessions", "curated", null, 0.8, "m1",
            MemorySaveOptions("decision", "Architecture/Auth Model", "Auth model", null, null, emptyList(), "related", central, "m1", "agent"))
        assertEquals("saved", first.status)
        assertEquals(1L, first.revision)

        f.compactEverything()

        // Same key, different spelling: the existing entry is updated, with its revision history.
        val second = f.bridge.personalAgentSaveMemory("Authentication uses signed tokens", "curated", null, 0.8, "m2",
            MemorySaveOptions("decision", "architecture/auth-model", null, null, null, emptyList(), "related", central, "m2", "agent"))
        assertEquals(first.id, second.id)
        assertEquals("updated", second.status)
        assertEquals(2L, second.revision)
        val active = f.bridge.personalAgentBrowse().filter { it.kind == "memory" }
        assertEquals(1, active.size)
        assertEquals("Authentication uses signed tokens", active.single().text)
        assertEquals("Auth model", active.single().title)

        // After the compaction the next run still sees the fact: in the curated block and through search.
        assertTrue(f.bridge.personalAgentPromptContext(central, true).relevantMemory.contains("signed tokens"))
        val hits = f.bridge.personalAgentSearch("tokens", 8)
        assertEquals(listOf(first.id), hits.map { it.id })
        assertEquals("architecture/auth-model", hits.single().topicKey)
        assertEquals(2L, hits.single().revision)
        // The old wording is revision history, not a second active row.
        assertTrue(f.bridge.personalAgentSearch("sessions", 8).isEmpty())
        val revisions = f.read { sql ->
            sql.query("SELECT cause FROM memory_revisions WHERE target_type='memory' AND target_id=? ORDER BY rowid", arrayOf(first.id))
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }
        assertEquals(listOf("save", "topic_update"), revisions)

        // A different key is a different entry.
        val other = f.bridge.personalAgentSaveMemory("Deploys go through CI", "curated", null, 0.5, "m3",
            MemorySaveOptions("fact", "ops/deploys", null, null, null, emptyList(), "related", central, "m3", "agent"))
        assertNotEquals(first.id, other.id)
    }

    @Test fun B1_savingListsOverlappingEntriesAndSupersedesLinksTheReplacement() {
        val f = open()
        val old = f.bridge.personalAgentSaveMemory("The preferred editor is vim", "curated", null, 0.6, "m1", com.jarvys.agent.chatdb.MemorySaveOptions())
        val receipt = f.bridge.personalAgentSaveMemory("The preferred editor is helix", "curated", null, 0.6, "m2", com.jarvys.agent.chatdb.MemorySaveOptions())
        // Nothing replaced the old entry yet, but the receipt names it so the next call can.
        assertTrue(receipt.candidates.any { it.id == old.id })
        val replaced = f.bridge.personalAgentSaveMemory("The preferred editor is zed", "curated", null, 0.6, "m3",
            MemorySaveOptions(null, null, null, null, receipt.id, listOf(old.id), "compatible", null, null, "agent"))
        assertEquals("saved", replaced.status)
        val loaded = f.bridge.personalAgentGet(listOf(replaced.id), false).single()
        assertTrue(loaded.links.any { it.relation == "supersedes" && it.id == receipt.id && it.direction == "out" })
        assertTrue(loaded.links.any { it.relation == "compatible" && it.id == old.id })
        // The superseded entry no longer answers searches or reads, but its row and revisions remain.
        assertTrue(f.bridge.personalAgentSearch("helix", 8).isEmpty())
        assertTrue(f.bridge.personalAgentGet(listOf(receipt.id)).isEmpty())
        val kept = f.read { sql -> sql.query("SELECT COUNT(*) FROM memory_entries WHERE id=?", arrayOf(receipt.id)).use { it.moveToFirst(); it.getInt(0) } }
        assertEquals(1, kept)
        // Correcting creates the same supersedes link and moves nothing else.
        val corrected = f.bridge.personalAgentSaveMemory("The preferred editor is neovim", "curated", null, 0.6, "explicit-correction", MemorySaveOptions(supersedesId = replaced.id))
        assertTrue(f.bridge.personalAgentGet(listOf(corrected.id)).single().links.any { it.relation == "supersedes" && it.id == replaced.id })
    }

    @Test fun B1_repeatingDuplicateTextAppliesSupersedesAndRelatedLinks() {
        val f = open()
        val replaced = f.bridge.personalAgentSaveMemory("The old deployment target is staging", "curated", null, 0.6, "old", com.jarvys.agent.chatdb.MemorySaveOptions())
        val duplicate = f.bridge.personalAgentSaveMemory("The deployment target is production", "curated", null, 0.6, "first", com.jarvys.agent.chatdb.MemorySaveOptions())
        val related = f.bridge.personalAgentSaveMemory("Production deploys require approval", "curated", null, 0.6, "related", com.jarvys.agent.chatdb.MemorySaveOptions())
        val repeated = f.bridge.personalAgentSaveMemory("The deployment target is production", "curated", null, 0.6, "repeat",
            MemorySaveOptions(null, null, null, null, replaced.id, listOf(related.id), "related", null, null, "agent"))
        assertEquals(duplicate.id, repeated.id)
        assertEquals("already_saved", repeated.status)
        val links = f.bridge.personalAgentGet(listOf(duplicate.id)).single().links
        assertTrue(links.any { it.relation == "supersedes" && it.id == replaced.id && it.direction == "out" })
        assertTrue(links.any { it.relation == "related" && it.id == related.id && it.direction == "out" })
        assertTrue(f.bridge.personalAgentGet(listOf(replaced.id)).isEmpty())
    }

    @Test fun B1_unchangedTopicStillLinksTheSupersededEntry() {
        val f = open()
        val target = f.bridge.personalAgentSaveMemory("Old architecture decision", "curated", null, 0.6, "old", com.jarvys.agent.chatdb.MemorySaveOptions())
        val current = f.bridge.personalAgentSaveMemory("Current architecture decision", "curated", null, 0.6, "current",
            MemorySaveOptions("decision", "architecture/current", null, null, null, emptyList(), "related", null, null, "agent"))
        val same = f.bridge.personalAgentSaveMemory("Current architecture decision", "curated", null, 0.6, "current-2",
            MemorySaveOptions("decision", "architecture/current", null, null, target.id, emptyList(), "related", null, null, "agent"))
        assertEquals(current.id, same.id)
        assertTrue(f.bridge.personalAgentGet(listOf(current.id)).single().links.any {
            it.relation == "supersedes" && it.id == target.id
        })
    }

    @Test fun B1_topicKeyUpdatesMetadataEvenWhenTheTextIsUnchanged() {
        val f = open()
        val first = f.bridge.personalAgentSaveMemory("Keep the build reproducible", "curated", "engineering", 0.4, "first",
            MemorySaveOptions("decision", "engineering/build", "Build", null, null, emptyList(), "related", null, null, "agent"))

        val updated = f.bridge.personalAgentSaveMemory("Keep the build reproducible", "daily", "operations", 0.9, "second",
            MemorySaveOptions("decision", "engineering/build", "Build", null, null, emptyList(), "related", null, null, "agent"))

        assertEquals(first.id, updated.id)
        assertEquals("updated", updated.status)
        assertEquals(2L, updated.revision)
        val hit = f.bridge.personalAgentGet(listOf(first.id)).single()
        assertEquals("daily", hit.layer)
        assertEquals(2L, hit.revision)
        val fields = f.read { sql ->
            sql.query("SELECT category,importance FROM memory_entries WHERE id=?", arrayOf(first.id)).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0) to c.getDouble(1)
            }
        }
        assertEquals("operations", fields.first)
        assertEquals(0.9, fields.second, 0.0)
    }

    @Test fun B1_correctReceiptRevisionMatchesMemoryGetForTheReplacementId() {
        val f = open()
        val original = f.bridge.personalAgentSaveMemory("The preferred shell is bash", "curated", null, 0.6, "test", com.jarvys.agent.chatdb.MemorySaveOptions())

        val corrected = f.bridge.personalAgentSaveMemory("The preferred shell is fish", "curated", null, 0.6, "explicit-correction", MemorySaveOptions(supersedesId = original.id))
        val loaded = f.bridge.personalAgentGet(listOf(corrected.id)).single()

        assertEquals("saved", corrected.status)
        assertEquals(corrected.id, loaded.id)
        assertEquals(loaded.revision, corrected.revision)
        assertEquals(1L, corrected.revision)
    }

    @Test fun B1_longOverlapUsesNormalizedTermsWithoutExceedingSqliteBindLimit() {
        val f = open()
        val old = f.bridge.personalAgentSaveMemory("The established policy is term509", "curated", null, 0.6, "old", com.jarvys.agent.chatdb.MemorySaveOptions())
        val longText = (0..509).joinToString(" ") { "term$it" }
        val saved = f.bridge.personalAgentSaveMemory(longText, "curated", null, 0.6, "long", com.jarvys.agent.chatdb.MemorySaveOptions())
        assertTrue(saved.candidates.any { it.id == old.id })
    }

    @Test fun B1_bundledCoreRulesMigrationRefreshesThePreviousV3Text() {
        val f = open()
        f.bridge.personalAgentBrowse() // Install current bundled defaults first.
        f.write { sql ->
            sql.execSQL("UPDATE agent_docs SET content=?,content_hash='previous-v3',revision=7 WHERE agent_id='default' AND kind='core_rules'",
                arrayOf(AgentDocDefaults.CORE_RULES_V3_PRE_RECALL))
        }
        f.bridge.personalAgentBrowse() // Initialization recognizes the previous V3 content and migrates it.
        val migrated = f.read { sql ->
            sql.query("SELECT content,revision FROM agent_docs WHERE agent_id='default' AND kind='core_rules'").use {
                it.moveToFirst(); it.getString(0) to it.getLong(1)
            }
        }
        assertEquals(AgentDocDefaults.CORE_RULES_V4, migrated.first)
        assertEquals(8L, migrated.second)
        assertTrue(migrated.first.contains("raise the conflict with the user in your next response"))

        f.write { sql ->
            sql.execSQL("UPDATE agent_docs SET content=?,content_hash='head-v3',revision=11 WHERE agent_id='default' AND kind='core_rules'",
                arrayOf(AgentDocDefaults.CORE_RULES_V3_S4))
        }
        f.bridge.personalAgentBrowse()
        val fromHead = f.read { sql ->
            sql.query("SELECT content,revision FROM agent_docs WHERE agent_id='default' AND kind='core_rules'").use {
                it.moveToFirst(); it.getString(0) to it.getLong(1)
            }
        }
        assertEquals(AgentDocDefaults.CORE_RULES_V4, fromHead.first)
        assertEquals(12L, fromHead.second)
        f.bridge.personalAgentBrowse()
        assertEquals(fromHead.second, f.read { sql ->
            sql.query("SELECT revision FROM agent_docs WHERE agent_id='default' AND kind='core_rules'").use { it.moveToFirst(); it.getLong(0) }
        })
    }

    // ---------------------------------------------------------------- B2

    private fun parse(result: CoreToolResult): JSONObject {
        assertTrue(result.content, result.success)
        return JSONObject(result.content)
    }

    @Test fun B2_historySearchFindsTheExactMessageAfterCompactionAndReadsItBack() {
        val f = open()
        val exact = "The order reference is 4817-XK and it must be kept exactly."
        val user = f.store.appendConversationMessage(central, "user", exact)
        f.store.appendConversationMessage(central, "assistant", "Noted.", 10L, "run-1", user, "COMPLETED")
        repeat(3) { f.store.appendConversationMessage(central, "user", "filler message number $it about something else") }
        f.compactEverything()
        // The summary does not carry the number; the stored message does.
        assertFalse(f.store.loadConversationContext(central).any { it.content.contains("4817-XK") })

        val found = parse(f.call("history_search", "query" to "4817-XK"))
        val results = found.getJSONArray("results")
        assertEquals(1, results.length())
        val hit = results.getJSONObject(0)
        assertEquals(user, hit.getString("message_id"))
        assertTrue(hit.getString("excerpt").contains("4817-XK"))
        val read = parse(f.call("history_read", "message_id" to user))
        assertEquals(exact, read.getString("text"))
        assertTrue(read.isNull("next_offset"))

        // A page at a time: the pages concatenate to the original text.
        val pages = StringBuilder()
        var offset = 0
        do {
            val page = parse(f.call("history_read", "message_id" to user, "offset" to offset, "limit_chars" to 7))
            pages.append(page.getString("text"))
            offset = if (page.isNull("next_offset")) -1 else page.getInt("next_offset")
        } while (offset >= 0)
        assertEquals(exact, pages.toString())
    }

    @Test fun B2_historySearchPagesWithACursorAndHidesRegeneratedMessages() {
        val f = open()
        val ids = (1..5).map { f.store.appendConversationMessage(central, "user", "sapphire note $it") }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pagesRead = 0
        do {
            val page = parse(f.call("history_search", "query" to "sapphire", "limit" to 2, "cursor" to cursor))
            page.getJSONArray("results").let { for (i in 0 until it.length()) seen += it.getJSONObject(i).getString("message_id") }
            cursor = if (page.isNull("next_cursor")) null else page.getString("next_cursor")
            pagesRead++
        } while (cursor != null)
        assertEquals(3, pagesRead)
        assertEquals(ids.toSet(), seen.toSet())
        assertEquals(5, seen.size)

        // Appended messages stay out of an existing snapshot and do not invalidate its cursor.
        val duringIds = (1..5).map { f.store.appendConversationMessage(central, "assistant", "beryl note $it", 4L, "run-during-$it", null, "COMPLETED") }
        val beforeHide = parse(f.call("history_search", "query" to "beryl", "limit" to 2))
        val newMessage = f.store.appendConversationMessage(central, "assistant", "beryl note added later", 4L, "run-later", null, "COMPLETED")
        val afterWrites = parse(f.call("history_search", "query" to "beryl", "limit" to 2, "cursor" to beforeHide.getString("next_cursor")))
        val continued = mutableListOf<String>()
        afterWrites.getJSONArray("results").let { for (i in 0 until it.length()) continued += it.getJSONObject(i).getString("message_id") }
        var nextCursor = if (afterWrites.isNull("next_cursor")) null else afterWrites.getString("next_cursor")
        while (nextCursor != null) {
            val page = parse(f.call("history_search", "query" to "beryl", "limit" to 2, "cursor" to nextCursor))
            page.getJSONArray("results").let { for (i in 0 until it.length()) continued += it.getJSONObject(i).getString("message_id") }
            nextCursor = if (page.isNull("next_cursor")) null else page.getString("next_cursor")
        }
        assertEquals(listOf(duringIds[2], duringIds[1], duringIds[0]), continued)
        assertFalse(continued.contains(newMessage))
        assertEquals(newMessage, parse(f.call("history_search", "query" to "beryl", "limit" to 1)).getJSONArray("results").getJSONObject(0).getString("message_id"))

        // Source changes after page one invalidate the cursor instead of mixing old and new results.
        val beforeRegeneration = parse(f.call("history_search", "query" to "beryl", "limit" to 2))
        f.store.appendAssistantRegenerated(central, newMessage)
        val rejectedAfterRegeneration = f.call("history_search", "query" to "beryl", "limit" to 2,
            "cursor" to beforeRegeneration.getString("next_cursor"))
        assertFalse(rejectedAfterRegeneration.success)
        assertTrue(rejectedAfterRegeneration.content, rejectedAfterRegeneration.content.contains("history_cursor_invalid"))

        val beforeEdit = parse(f.call("history_search", "query" to "beryl", "limit" to 2))
        val edit = f.bridge.finalizeMessageSync(central, duringIds[2],
            listOf(MessagePartInput(MessagePartKinds.TEXT, "edited message without the term")), "COMPLETED", null,
            System.currentTimeMillis() / 1000.0)
        assertTrue(edit.toString(), edit is ChatDbResult.Success)
        val rejectedAfterEdit = f.call("history_search", "query" to "beryl", "limit" to 2,
            "cursor" to beforeEdit.getString("next_cursor"))
        assertFalse(rejectedAfterEdit.success)
        assertTrue(rejectedAfterEdit.content, rejectedAfterEdit.content.contains("history_cursor_invalid"))

        // A cursor belongs to its query: another query rejects it instead of mixing pages.
        val first = parse(f.call("history_search", "query" to "sapphire", "limit" to 2))
        val rejected = f.call("history_search", "query" to "different", "cursor" to first.getString("next_cursor"))
        assertFalse(rejected.success)
        assertTrue(rejected.content, rejected.content.contains("history_cursor_invalid"))

        // A regenerated answer is not found and cannot be read.
        val user = f.store.appendConversationMessage(central, "user", "write the report")
        val answer = f.store.appendConversationMessage(central, "assistant", "first draft with the word tangerine", 5L, "run-2", user, "COMPLETED")
        assertEquals(1, parse(f.call("history_search", "query" to "tangerine")).getJSONArray("results").length())
        f.store.appendAssistantRegenerated(central, answer)
        assertEquals(0, parse(f.call("history_search", "query" to "tangerine")).getJSONArray("results").length())
        assertFalse(f.call("history_read", "message_id" to answer).success)
    }

    // ---------------------------------------------------------------- B5

    private fun Fixture.episode(id: String, atMs: Long, narration: String, title: String?, text: String,
                                tool: String, connector: String?, outcome: String, calls: Int) = write { sql ->
        sql.execSQL(
            "INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,created_at,updated_at," +
                "kind,narration,event_at,title) VALUES(?,'curated',NULL,?,NULL,0.5,'','runtime',NULL,0,?,?,'episode',?,?,?)",
            arrayOf(id, text, atMs, atMs, narration, atMs, title),
        )
        for (n in 1..calls) sql.execSQL(
            "INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,action_id,attempt_id,at_ms,tool_name,outcome," +
                "authorization,facts_json,connector) VALUES(?,?,'tool_result',?,?,NULL,NULL,?,?,?,'allow','{}',?)",
            arrayOf(id, n, central, "$id-call-$n", atMs, tool, outcome, connector),
        )
    }

    private fun ms(date: String): Long = LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun B5_episodesWithoutNarrationAreFoundByToolConnectorAndDateWithoutAQuery() {
        val f = open()
        f.bridge.ensureConversationSync(central)
        f.episode("ep-gmail", ms("2026-09-29"), "pending", null, "", "gmail", "gmail", "ok", 3)
        f.episode("ep-sms", ms("2026-10-02"), "failed", null, "", "sms_cleanup", null, "ok", 1)
        f.bridge.personalAgentSaveMemory("The user likes short answers", "curated", null, 0.5, "m1", com.jarvys.agent.chatdb.MemorySaveOptions())

        val byTool = f.bridge.personalAgentSearch("", 8, MemorySearchFilter.parse(null, null, null, "gmail", null, null, null))
        assertEquals(listOf("ep-gmail"), byTool.map { it.id })
        assertTrue("the label comes from the evidence: ${byTool.single().label}", byTool.single().label.let {
            it.contains("gmail") && it.contains("x3") && it.contains("2026-09-29") })
        assertEquals("", byTool.single().text)
        assertEquals(listOf("ep-gmail"), f.bridge.personalAgentSearch("", 8, MemorySearchFilter.parse(null, null, null, null, "GMAIL", "ok", null)).map { it.id })
        assertEquals(listOf("ep-sms"), f.bridge.personalAgentSearch("", 8, MemorySearchFilter.parse("episode", "2026-10-01", "2026-10-31", null, null, null, null)).map { it.id })
        assertTrue(f.bridge.personalAgentSearch("", 8, MemorySearchFilter.parse(null, null, null, "gmail", null, "error", null)).isEmpty())
        // Without a query or a filter there is nothing to search for.
        assertTrue(runCatching { f.bridge.personalAgentSearch("", 8) }.isFailure)

        // The tool answers with excerpts and the episode label; memory_get adds the evidence.
        val searched = parse(f.call("memory_search", "tool" to "gmail", "from" to "2026-09-01", "to" to "2026-09-30"))
        val item = searched.getJSONArray("results").getJSONObject(0)
        assertEquals("ep-gmail", item.getString("id"))
        assertEquals(3, item.getInt("evidence_count"))
        assertTrue(item.getString("label").contains("gmail"))
        val got = JSONArray(f.call("memory_get", "ids" to "ep-gmail", "expand" to "evidence").content).getJSONObject(0)
        assertEquals(3, got.getJSONArray("evidence").length())
        assertEquals("gmail", got.getJSONArray("evidence").getJSONObject(0).getString("connector"))

        // none -> pending -> failed -> done: the episode stays findable at every step.
        for (state in listOf("none", "pending", "failed", "done")) {
            f.write { it.execSQL("UPDATE memory_entries SET narration=? WHERE id='ep-gmail'", arrayOf(state)) }
            assertEquals(state, f.bridge.personalAgentSearch("", 8, MemorySearchFilter.parse(null, null, null, "gmail", null, null, null)).single().narration)
        }
        // A finished narration is searchable text as well, in combination with the structured filters.
        f.write { it.execSQL("UPDATE memory_entries SET title='Gmail spam cleanup',text='Moved the spam folder to the trash' WHERE id='ep-gmail'") }
        val combined = f.bridge.personalAgentSearch("spam", 8, MemorySearchFilter.parse(null, null, null, "gmail", null, null, null))
        assertEquals(listOf("ep-gmail"), combined.map { it.id })
        assertTrue(f.bridge.personalAgentSearch("spam", 8, MemorySearchFilter.parse(null, null, null, "sms_cleanup", null, null, null)).isEmpty())
    }

    @Test fun B5_searchAnswersWithExcerptsAndGetReturnsTheCompleteText() {
        val f = open()
        val long = "First line about orchids.\nSecond line says the watering schedule is every nine days.\n" + "padding ".repeat(2_000)
        val saved = f.bridge.personalAgentSaveMemory(long, "curated", null, 0.5, "m1", com.jarvys.agent.chatdb.MemorySaveOptions())
        val searched = parse(f.call("memory_search", "query" to "watering"))
        val excerpt = searched.getJSONArray("results").getJSONObject(0).getString("excerpt")
        assertEquals("Second line says the watering schedule is every nine days.", excerpt)
        assertFalse(searched.toString().contains("padding padding"))
        val full = JSONArray(f.call("memory_get", "ids" to saved.id).content).getJSONObject(0).getString("text")
        assertEquals(long.trim(), full)
    }

    @Test fun B5_punctuationQueryUsesTheStructuredFilter() {
        val f = open()
        f.write { sql ->
            sql.execSQL("INSERT INTO memory_entries(id,layer,text,importance,triggers,provenance,usage_count,created_at,updated_at,kind,narration) " +
                "VALUES('ep-punctuation','curated','',0.5,'','runtime',0,1,1,'episode','none')")
            sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,facts_json) " +
                "VALUES('ep-punctuation',1,'tool_result',?, 'call-punctuation',1,'gmail','ok','{}')", arrayOf(central))
        }
        val found = f.bridge.personalAgentSearch("!!! ???", 8,
            MemorySearchFilter.parse(null, null, null, "gmail", null, null, null))
        assertEquals(listOf("ep-punctuation"), found.map { it.id })
        for (query in listOf("%", "_")) {
            val symbolQuery = f.bridge.personalAgentSearch(query, 8,
                MemorySearchFilter.parse(null, null, null, "gmail", null, null, null))
            assertEquals(listOf("ep-punctuation"), symbolQuery.map { it.id })
        }
    }

    // ---------------------------------------------------------------- B14

    private fun bigText(letter: Char) = letter.toString().repeat(MainChatTranscriptStore.INLINE_CHARS + 1_000)

    private fun Fixture.states(): Map<String, String> = read { sql ->
        sql.query("SELECT artifact_id,state FROM model_artifacts WHERE conversation_id=?", arrayOf(central))
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    }

    private fun Fixture.refs(): List<Triple<String, String?, String>> = read { sql ->
        sql.query("SELECT owner_kind,call_id,artifact_id FROM transcript_artifact_refs WHERE conversation_id=? ORDER BY id", arrayOf(central))
            .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getString(2))) } }
    }

    private fun Fixture.recover(id: String): CoreToolResult =
        MainChatTranscriptStore(files, central, store).recoveryTool()
            .execute(mutableMapOf<String, Any>("artifact_id" to id), CancellationToken.uncancellable())

    @Test fun B14_realProducerPopulatesTheRelationsAndPressureNeverExpelsTheProtectedClosure() {
        val f = open()
        val user = f.store.appendConversationMessage(central, "user", "Run both tools")
        val big = MainChatToolContinuityTest.testTool("big_tool") { args, _ ->
            CoreToolResult.success("RESULT-" + args["body"].toString().take(1).repeat(MainChatTranscriptStore.INLINE_CHARS + 500))
        }
        val calls = listOf(
            ModelReply.Call("c-protected", "big_tool", linkedMapOf<String, Any>("body" to bigText('A'))),
            ModelReply.Call("c-loose", "big_tool", linkedMapOf<String, Any>("body" to bigText('B'))),
        )
        var turn = 0
        val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
            if (turn++ == 0) ModelReply(bigText('T'), calls) else ModelReply("done", emptyList())
        }, CoreToolRegistry(listOf(big)), "system", central)
        MainChatTranscriptStore(f.files, central, f.store).attach(loop, emptyList())
        val result = loop.run("Run both tools", emptyList(), CancellationToken.uncancellable(), null)
        f.store.appendConversationMessage(central, "assistant", result.text, result.durationMs, result.runId, user, result.outcome)
        f.store.appendConversationMessage(central, "user", "next")

        // The producer itself wrote the relations: result, arguments and batch text, for both calls.
        val refs = f.refs()
        assertEquals(2, refs.count { it.first == "tool_result" })
        assertEquals(2, refs.count { it.first == "tool_call_args" })
        assertEquals(1, refs.count { it.first == "batch_text" && it.second == null })
        val resultOf = refs.filter { it.first == "tool_result" }.associate { it.second!! to it.third }
        val argsOf = refs.filter { it.first == "tool_call_args" }.associate { it.second!! to it.third }
        val batchText = refs.single { it.first == "batch_text" }.third
        val firstColumn = f.read { sql ->
            sql.query("SELECT call_id,file_artifact_id FROM tool_results WHERE conversation_id=?", arrayOf(central))
                .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
        }
        assertEquals(resultOf, firstColumn)
        assertTrue(f.states().values.all { it == "retained" })

        // A compaction that summarizes the tool exchange owns its source artifact too.
        f.compactEverything()
        val firstCompaction = f.refs().filter { it.first == "compaction" }.single().third
        f.store.appendConversationMessage(central, "user", "A later interaction")
        f.store.appendConversationMessage(central, "assistant", "The later interaction is complete.")
        f.store.appendConversationMessage(central, "user", "Another interaction")
        f.store.appendConversationMessage(central, "assistant", "The other interaction is complete.")
        f.compactEverything()
        val compactionRefs = f.refs().filter { it.first == "compaction" }
        assertEquals(2, compactionRefs.size)
        val secondCompaction = compactionRefs.map { it.third }.first { it != firstCompaction }

        // Episode evidence names the protected call only.
        f.write { sql ->
            sql.execSQL("INSERT INTO memory_entries(id,layer,text,importance,triggers,provenance,usage_count,created_at,updated_at,kind,narration) " +
                "VALUES('ep-1','curated','',0.5,'','runtime',0,1,1,'episode','pending')")
            sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,facts_json) " +
                "VALUES('ep-1',1,'tool_result',?,'c-protected',1,'big_tool','ok','{}')", arrayOf(central))
        }
        val protectedSet = setOf(resultOf["c-protected"]!!, argsOf["c-protected"]!!, batchText, firstCompaction)
        assertEquals(protectedSet, f.bridge.protectedArtifactIdsSync(central))

        // Pressure that eases once the unreferenced artifacts of the two calls and the loose summary are gone.
        val loose = setOf(resultOf["c-loose"]!!, argsOf["c-loose"]!!, secondCompaction)
        fun expelled() = f.states().count { it.value == "expelled" }
        f.store.setStoragePressureForTests { _, _ -> expelled() < loose.size }
        val admitted = MainChatTranscriptStore(f.files, central, f.store).archive(bigText('N'))
        assertTrue(admitted.text, admitted.text.contains("state=retained"))
        val afterExpulsion = f.states()
        assertEquals(loose, afterExpulsion.filter { it.value == "expelled" }.keys)
        assertTrue(protectedSet.all { afterExpulsion[it] == "retained" })
        assertEquals("retained", afterExpulsion[admitted.artifactIds.single()])
        // Expelling frees the disk: the files of the loose artifacts are gone and the protected ones are still there.
        val onDisk = java.nio.file.Files.walk(f.files.toPath()).use { paths ->
            paths.filter(java.nio.file.Files::isRegularFile).map { it.fileName.toString() }.toList().toSet()
        }
        assertTrue(loose.none { "$it.txt" in onDisk })
        assertTrue(protectedSet.all { "$it.txt" in onDisk })
        // The text the model sees for each state, and the metadata, agree.
        val expelledRead = f.recover(loose.first())
        assertFalse(expelledRead.success)
        assertTrue(expelledRead.content, expelledRead.content.contains("state=expelled"))
        val retainedRead = f.recover(resultOf["c-protected"]!!)
        assertTrue(retainedRead.content, retainedRead.success && retainedRead.content.startsWith("state=retained"))

        // Nothing left to free: only the protected closure remains, so new output is not kept.
        f.store.setStoragePressureForTests { _, _ -> true }
        val unavailable = MainChatTranscriptStore(f.files, central, f.store).archive(bigText('U'))
        assertTrue(unavailable.text, unavailable.text.contains("state=unavailable") && unavailable.text.contains("reason=storage"))
        val finalStates = f.states()
        assertEquals("unavailable", finalStates[unavailable.artifactIds.single()])
        assertTrue("the protected closure is never expelled", protectedSet.all { finalStates[it] == "retained" })
        assertEquals("the unreferenced new artifact was freed first", "expelled", finalStates[admitted.artifactIds.single()])
        val summary = f.bridge.retentionSummarySync(central)
        assertTrue(summary.storageWarning)
        assertEquals(protectedSet.size, summary.protectedCount)
        assertEquals(1, summary.unavailable)
        // Episodes, evidence, messages and the inline tool results are untouched.
        val episode = f.bridge.personalAgentGet(listOf("ep-1"), true).single()
        assertEquals(1, episode.evidence.size)
        assertEquals(2, f.read { sql -> sql.query("SELECT COUNT(*) FROM tool_results WHERE conversation_id=?", arrayOf(central)).use { it.moveToFirst(); it.getInt(0) } })
        assertTrue(f.store.loadConversationContext(central).isNotEmpty())
        val unreadable = f.recover(unavailable.artifactIds.single())
        assertTrue(unreadable.content, !unreadable.success && unreadable.content.contains("state=unavailable"))

        // expand=source shares its character budget across every evidence row.
        f.write { sql ->
            sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,facts_json) " +
                "VALUES('ep-1',2,'tool_result',?,'c-protected',2,'big_tool','ok','{}')", arrayOf(central))
        }
        val sourceRows = JSONArray(f.call("memory_get", "ids" to "ep-1", "expand" to "source").content).getJSONObject(0)
            .getJSONArray("evidence")
        assertEquals(2, sourceRows.length())
        val outputChars = (0 until sourceRows.length()).sumOf { index ->
            val source = sourceRows.getJSONObject(index).getJSONObject("source")
            source.getString("output").length + (0 until source.getJSONArray("artifacts").length()).sumOf { artifactIndex ->
                source.getJSONArray("artifacts").getJSONObject(artifactIndex).optString("text").length
            }
        }
        assertTrue(outputChars <= CorePromptBudget.standard().toolResultsPerTurnChars / 2)
        val source = sourceRows.getJSONObject(0).getJSONObject("source")
        assertEquals("big_tool", source.getString("tool"))
        assertEquals("retained", source.getJSONArray("artifacts").getJSONObject(0).getString("state"))

        // Storage recovers: the warning clears at the next write that needs no expulsion.
        f.store.setStoragePressureForTests(StoragePressure.NONE)
        MainChatTranscriptStore(f.files, central, f.store).archive(bigText('R'))
        assertFalse(f.bridge.retentionSummarySync(central).storageWarning)

        // Superseding the only episode that references this call must leave its evidence closure protected.
        f.bridge.personalAgentSaveMemory("A new decision replaces the episode", "curated", null, 0.5, "replace-episode",
            MemorySaveOptions("decision", "architecture/replacement", null, null, "ep-1", emptyList(), "related", null, null, "agent"))
        assertEquals(protectedSet, f.bridge.protectedArtifactIdsSync(central))

        f.store.setStoragePressureForTests { _, _ -> true }
        val afterSupersedesPressure = MainChatTranscriptStore(f.files, central, f.store).archive(bigText('V'))
        assertTrue(afterSupersedesPressure.text, afterSupersedesPressure.text.contains("state=unavailable"))
        assertTrue(protectedSet.all { f.states()[it] == "retained" })

        // Forgetting preserves evidence for a separate call whose only episode owner is forgotten.
        f.store.setStoragePressureForTests(StoragePressure.NONE)
        f.store.appendConversationMessage(central, "user", "run the forget retention tool")
        val forgetTool = MainChatToolContinuityTest.testTool("forget_tool") { _, _ -> CoreToolResult.success(bigText('F')) }
        val forgetModelCalls = java.util.concurrent.atomic.AtomicInteger()
        val forgetLoop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
            if (forgetModelCalls.getAndIncrement() == 0) ModelReply(bigText('A'), listOf(
                ModelReply.Call("forget-call", "forget_tool", linkedMapOf<String, Any>()),
            )) else ModelReply("done", emptyList())
        }, CoreToolRegistry(listOf(forgetTool)), "system", central)
        MainChatTranscriptStore(f.files, central, f.store).attach(forgetLoop, emptyList())
        forgetLoop.run("run the forget retention tool", emptyList(), CancellationToken.uncancellable(), null)
        val forgetCallId = f.read { sql ->
            sql.query("SELECT call_id FROM tool_calls WHERE conversation_id=? AND call_id='forget-call'", arrayOf(central))
                .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        }
        val forgetArtifact = f.read { sql ->
            sql.query("SELECT artifact_id FROM transcript_artifact_refs WHERE conversation_id=? AND call_id=? ORDER BY id LIMIT 1",
                arrayOf(central, forgetCallId)).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        }
        f.write { sql ->
            sql.execSQL("INSERT INTO memory_entries(id,layer,text,importance,triggers,provenance,usage_count,created_at,updated_at,kind,narration) " +
                "VALUES('ep-forget','curated','',0.5,'','runtime',0,1,1,'episode','pending')")
            sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,facts_json) " +
                "VALUES('ep-forget',1,'tool_result',?,?,1,'forget_tool','ok','{}')", arrayOf(central, forgetCallId))
        }
        assertTrue(forgetArtifact in f.bridge.protectedArtifactIdsSync(central))
        f.bridge.personalAgentForget("ep-forget")
        assertTrue(forgetArtifact in f.bridge.protectedArtifactIdsSync(central))
        f.store.setStoragePressureForTests { _, _ -> true }
        val afterForgetPressure = MainChatTranscriptStore(f.files, central, f.store).archive(bigText('W'))
        assertTrue(afterForgetPressure.text, afterForgetPressure.text.contains("state=unavailable"))
        assertEquals("retained", f.states()[forgetArtifact])
        assertTrue(protectedSet.all { f.states()[it] == "retained" })

        // When two compactions cover the same message index, protect only the one with the later sequence.
        val compactionIds = f.read { sql ->
            sql.query("SELECT r.artifact_id,c.compaction_id,c.first_kept FROM transcript_artifact_refs r " +
                "JOIN compactions c ON c.conversation_id=r.conversation_id AND c.compaction_id=r.batch_id " +
                "WHERE r.conversation_id=? AND r.owner_kind='compaction'", arrayOf(central)).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getLong(2)) }
            }
        }
        val firstBoundary = compactionIds.getValue(firstCompaction).second
        val laterCompactionId = compactionIds.getValue(secondCompaction).first
        val laterBoundary = compactionIds.getValue(secondCompaction).second
        f.write { sql ->
            sql.execSQL("UPDATE compactions SET first_kept=? WHERE conversation_id=? AND compaction_id=?",
                arrayOf(firstBoundary, central, laterCompactionId))
        }
        val tiedProtected = f.bridge.protectedArtifactIdsSync(central)
        assertTrue(secondCompaction in tiedProtected)
        assertFalse(firstCompaction in tiedProtected)
        f.write { sql ->
            sql.execSQL("UPDATE compactions SET first_kept=? WHERE conversation_id=? AND compaction_id=?",
                arrayOf(laterBoundary, central, laterCompactionId))
        }
    }

    @Test fun repeatingAnExpelledArtifactUnderPressurePreservesItsMetadataAndDoesNotAbort() {
        val f = open()
        val firstProducer = MainChatTranscriptStore(f.files, central, f.store)
        val original = firstProducer.archive(bigText('A'))
        val originalId = original.artifactIds.single()

        f.store.setStoragePressureForTests { _, _ -> true }
        firstProducer.archive(bigText('B')) // Evicts A; B is unavailable because pressure remains.
        assertEquals("expelled", f.states()[originalId])
        val before = f.read { sql ->
            sql.query("SELECT path,sha256,chars,bytes,state,state_reason FROM model_artifacts WHERE conversation_id=? AND artifact_id=?",
                arrayOf(central, originalId)).use { c ->
                assertTrue(c.moveToFirst())
                listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getLong(3).toString(), c.getString(4), c.getString(5))
            }
        }

        val repeated = firstProducer.archive(bigText('A'))
        assertTrue(repeated.text, repeated.text.contains("state=expelled"))
        assertEquals("expelled", f.states()[originalId])
        val after = f.read { sql ->
            sql.query("SELECT path,sha256,chars,bytes,state,state_reason FROM model_artifacts WHERE conversation_id=? AND artifact_id=?",
                arrayOf(central, originalId)).use { c ->
                assertTrue(c.moveToFirst())
                listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getLong(3).toString(), c.getString(4), c.getString(5))
            }
        }
        assertEquals(before, after)
    }

    @Test fun transcriptProducerCanSaveAfterAnotherInstanceExpelsAnOwnedFile() {
        val f = open()
        val originalProducer = MainChatTranscriptStore(f.files, central, f.store)
        val original = originalProducer.archive(bigText('A'))
        val expelledId = original.artifactIds.single()

        f.store.setStoragePressureForTests { _, _ -> f.states()[expelledId] != "expelled" }
        val otherProducer = MainChatTranscriptStore(f.files, central, f.store)
        val admitted = otherProducer.archive(bigText('B'))

        assertEquals("expelled", f.states()[expelledId])
        assertTrue(admitted.text, admitted.text.contains("state=retained"))
        val next = originalProducer.archive(bigText('C'))
        assertTrue(next.text, next.text.contains("state=retained"))
        assertEquals("retained", f.states()[next.artifactIds.single()])
    }

    @Test fun B14_checkpointArtifactLockSerializesAnotherProducerUntilItsRowsCommit() {
        val f = open()
        f.store.appendConversationMessage(central, "user", "write a large result")
        val checkpointProducer = MainChatTranscriptStore(f.files, central, f.store)
        val otherProducer = MainChatTranscriptStore(f.files, central, f.store)
        val loose = checkpointProducer.archive(bigText('P')).artifactIds.single()
        val pressureChecks = java.util.concurrent.atomic.AtomicInteger()
        f.store.setStoragePressureForTests { _, _ -> pressureChecks.getAndIncrement() == 0 }
        val tool = MainChatToolContinuityTest.testTool("lock_tool") { _, _ -> CoreToolResult.success("ok") }
        val modelCalls = java.util.concurrent.atomic.AtomicInteger()
        val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
            if (modelCalls.getAndIncrement() == 0) ModelReply(bigText('T'), listOf(
                ModelReply.Call("lock-call", "lock_tool", linkedMapOf<String, Any>("body" to bigText('L'))),
            )) else ModelReply("done", emptyList())
        }, CoreToolRegistry(listOf(tool)), "system", central)
        checkpointProducer.attach(loop, emptyList())

        val checkpointStaged = java.util.concurrent.CountDownLatch(1)
        val releaseCheckpoint = java.util.concurrent.CountDownLatch(1)
        val stagedTypes = java.util.concurrent.atomic.AtomicReference<List<String>>()
        val checkpointExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val evictionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val evictionThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        MainChatTranscriptStore.checkpointStagedHookForTests = java.util.function.Consumer { rows ->
            if (rows.none { it.optString("type") == "model_artifact" }) return@Consumer
            stagedTypes.set(rows.map { it.optString("type") })
            checkpointStaged.countDown()
            check(releaseCheckpoint.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            val pendingCheckpoint = checkpointExecutor.submit {
                loop.run("write a large result", emptyList(), CancellationToken.uncancellable(), null)
            }
            assertTrue("checkpoint must stage rows before committing them; done=${pendingCheckpoint.isDone}",
                checkpointStaged.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("checkpoint must stage an artifact row; staged=${stagedTypes.get()}",
                stagedTypes.get().orEmpty().contains("model_artifact"))
            f.store.setStoragePressureForTests { _, _ -> true }

            val pendingEviction = evictionExecutor.submit {
                evictionThread.set(Thread.currentThread())
                otherProducer.archive(bigText('E'))
            }
            val evictionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (evictionThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < evictionDeadline) Thread.yield()
            assertEquals("eviction must wait on the shared lock until checkpoint rows commit", Thread.State.BLOCKED, evictionThread.get().state)
            assertFalse(pendingCheckpoint.isDone)
            assertFalse(pendingEviction.isDone)

            releaseCheckpoint.countDown()
            pendingCheckpoint.get(5, java.util.concurrent.TimeUnit.SECONDS)
            pendingEviction.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("expelled", f.states()[loose])
            assertTrue(f.states().isNotEmpty())
        } finally {
            releaseCheckpoint.countDown()
            MainChatTranscriptStore.checkpointStagedHookForTests = null
            checkpointExecutor.shutdownNow()
            evictionExecutor.shutdownNow()
        }
    }

    @Test fun B14_aTruncatedArtifactIsMarkedBoundedInMetadataAndText() {
        val f = open()
        f.store.appendConversationMessage(central, "user", "load a huge file")
        val oversize = "z".repeat(MainChatTranscriptStore.MAX_ARTIFACT_CHARS + 100)
        val archived = MainChatTranscriptStore(f.files, central, f.store).archive(oversize)
        assertTrue(archived.text, archived.text.contains("state=bounded") && archived.text.contains("bounded"))
        assertEquals("bounded", f.states()[archived.artifactIds.single()])
        val read = f.recover(archived.artifactIds.single())
        assertTrue(read.content, read.success && read.content.startsWith("state=bounded"))
    }

    @Test fun B2_historyReadAdvancesAcrossASupplementaryCodePoint() {
        val f = open()
        val message = f.store.appendConversationMessage(central, "user", "😀x")
        val first = parse(f.call("history_read", "message_id" to message, "offset" to 0, "limit_chars" to 1))
        assertEquals("😀", first.getString("text"))
        assertEquals(2, first.getInt("next_offset"))
        val second = parse(f.call("history_read", "message_id" to message, "offset" to 2, "limit_chars" to 1))
        assertEquals("x", second.getString("text"))
        assertTrue(second.isNull("next_offset"))
    }
}

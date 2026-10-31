package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class SingleSessionReadBoundariesTest {
    @get:Rule val fixture = ChatTestStore()
    private fun success(result: ChatDbResult<*>): Any? = (result as ChatDbResult.Success).value

    @Test fun B11_compactedSelectionPreservesCompleteRetainedAndCrossingGroups() = runBlocking {
        val files = fixture.newFilesDirectory()
        val store = fixture.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val central = WorkspaceIdentity.CENTRAL_SESSION
        val old = store.appendConversationMessage(central, "user", "old request")
        val retained = store.appendConversationMessage(central, "user", "retained request")
        val database = JarvysChatDatabaseProvider.getInstance(context)
        val model = ModelTranscriptRepository(database)
        suspend fun batch(id: String, owner: String, index: Long) {
            success(model.appendToolBatch(ModelToolBatchInput(central, id, owner, index, id,
                calls = listOf(ModelCallInput("call-$id", "fixture-tool", "{}")))))
        }
        suspend fun result(id: String) {
            success(model.appendToolResult(ModelToolResultInput(central, id, "call-$id", "fixture-tool", "RESULT", "result-$id")))
        }
        batch("old-complete", old, 0); result("old-complete")
        batch("retained-complete", retained, 1); result("retained-complete")
        batch("crossing-🚀", old, 0)
        val compaction = success(MemoryTimelineRepository(database).appendCompaction(
            CompactionInput(central, "summary", 1, summarizedMessages = 1))) as Compaction
        result("crossing-🚀")
        val full = model.readModelTranscript(central, includeCompacted = true)
        val selected = model.readModelTranscript(central)
        assertEquals(setOf("retained-complete", "crossing-🚀"), selected.entries.filterIsInstance<ModelTranscriptEntry.Batch>().map { it.value.batch.batch_id }.toSet())
        suspend fun restore(snapshot: ModelTranscriptSnapshot): List<String> {
            val entries = snapshot.entries.filter { it is ModelTranscriptEntry.Batch || it is ModelTranscriptEntry.ResultActivity }
            val rows = entries.mapNotNull { entry -> when (entry) {
                is ModelTranscriptEntry.Batch -> JSONObject().put("type", "model_tool_calls").put("batchId", entry.value.batch.batch_id)
                    .put("messageIndex", entry.value.batch.message_index).put("assistantText", entry.value.batch.assistant_text)
                    .put("calls", JSONArray().apply { entry.value.calls.forEach { put(JSONObject().put("id", it.call.call_id)
                        .put("name", it.call.name).put("arguments", JSONObject(it.call.arguments_json))) } })
                is ModelTranscriptEntry.ResultActivity -> JSONObject().put("type", "model_tool_result").put("batchId", entry.batchId)
                    .put("callId", entry.callId).put("output", entry.firstResult!!.output).put("toolName", entry.firstResult.tool_name)
                else -> null
            } }
            val position = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
                database.chatSql.query("SELECT sort_seq,sort_sub FROM conversation_items WHERE conversation_id=? AND seq=?",
                    arrayOf(central, compaction.seq)).use { it.moveToFirst(); Triple(it.getLong(0), it.getInt(1), compaction.seq) }
            } }
            val boundary = entries.indexOfFirst { it.sortSeq > position.first ||
                (it.sortSeq == position.first && (it.sortSub > position.second ||
                    (it.sortSub == position.second && it.seq >= position.third))) }.let { if (it < 0) rows.size else it }
            return MainChatTranscriptStore.restore(rows, 1, boundary).values.flatten().map { turn ->
                "${turn.kind}:${turn.role}:${turn.content}:${turn.toolCalls.map { it.id }}"
            }
        }
        assertEquals(restore(full), restore(selected))
        val loaded = ConversationReader(database).loadConversationContext(central)
        assertEquals(setOf("call-retained-complete", "call-crossing-🚀"), loaded.flatMap { it.toolCalls }.map { it.id }.toSet())
    }

    @Test fun B11_runCheckpointsAndArtifactOwnershipNeverReadTheWholeMessageStream() = runBlocking {
        val files = fixture.newFilesDirectory()
        val store = fixture.localRunStore(files)
        val session = WorkspaceIdentity.CENTRAL_SESSION
        var owner = store.appendConversationMessage(session, "user", "initial")
        val fullReads = mutableListOf<Int>()
        for (additionalMessages in listOf(0, 40)) {
            repeat(additionalMessages) { owner = store.appendConversationMessage(session, "user", "history $it") }
            val provider = MainChatTurnContextProvider(store, session, owner)
            val steering = store.appendConversationMessage(session, "user", "steering")
            val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ -> error("No model call expected") },
                CoreToolRegistry(emptyList()), "", session, CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
            val checkpoint = CoreAgentLoop::class.java.getDeclaredMethod("collectTurnContext", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val statements = CopyOnWriteArrayList<String>()
            ChatSqlInstrumentation.observeStatements({ statements += it }) {
                val reactions = MessageReactionTool(store, session)
                reactions.prepareModelMetadata(emptyList(), "steering")
                assertTrue(checkpoint.invoke(loop, false) as Boolean)
                checkpoint.invoke(loop, false)
                checkpoint.invoke(loop, true)
                assertEquals(steering, provider.latestUnincorporatedSteeringMessageId())
                store.markConversationMessagesIncorporatedThrough(session, steering)
                assertEquals(store.conversationMessageIndex(session, steering), store.latestUserMessageIndex(session).toLong())
                MainChatTranscriptStore(files, session, store).archive("archived tool output")
            }
            fullReads += statements.count { it == "SELECT message_id FROM messages WHERE conversation_id=? AND seq<=? ORDER BY seq" }
            assertFalse(statements.any { it.contains("model_tool_calls") && it.startsWith("SELECT seq,item_kind") })
        }
        assertEquals(listOf(0, 0), fullReads)
    }
}

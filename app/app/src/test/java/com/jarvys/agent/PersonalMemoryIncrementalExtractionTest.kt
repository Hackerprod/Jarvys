package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalMemoryIncrementalExtractionTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var store: LocalRunStore
    private lateinit var worker: PersonalMemoryExtractionWorker
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private val seen = mutableListOf<List<String>>()
    private val seenIds = mutableListOf<List<String>>()
    private var response: (CancellationToken) -> ModelReply = { ModelReply("NO_REPLY", emptyList()) }
    private lateinit var model: CoreAgentModel

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        store = fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        bridge.ensureConversationSync(central)
        bridge.personalAgentPromptContext(central, true)
        enabled(true)
        worker = TestListenableWorkerBuilder.from(context, PersonalMemoryExtractionWorker::class.java).build()
        model = CoreAgentModel(object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = throw AssertionError()
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                seen += history.map { it.content }
                seenIds += history.map { it.durableMessageId }
                return response(token)
            }
        }, "incremental-test")
    }

    private fun enabled(value: Boolean) {
        context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(MemoryConstants.ENABLED_KEY, value).commit()
    }
    private fun extract(token: CancellationToken = CancellationToken.cancellable()) =
        worker.extractIncrementally(central, bridge, model, token, false)

    @Test fun nextPassReadsOnlyNewInputsAndEmptyPassDoesNotCallModel() {
        store.appendConversationMessage(central, "user", "first fact")
        assertTrue(extract())
        val firstCursor = bridge.personalAgentExtractionCursorSync(central)
        assertTrue(firstCursor > 0)
        assertTrue(extract())
        assertEquals(1, seen.size)
        store.appendConversationMessage(central, "user", "second fact")
        assertTrue(extract())
        assertEquals(listOf(listOf("first fact"), listOf("second fact")), seen)
        assertTrue(seenIds.flatten().all { it.isNotBlank() })
        assertNotEquals(seenIds[0].single(), seenIds[1].single())
        assertEquals(bridge.readInputsAfterSync(central, 0).filter { it.role == "user" }.map { it.messageId },
            seenIds.flatten())
        assertTrue(bridge.personalAgentExtractionCursorSync(central) > firstCursor)
    }

    @Test fun failedExtractionDoesNotConsumeCursorAndRetrySeesSameMessage() {
        store.appendConversationMessage(central, "user", "retry fact")
        response = { throw IllegalStateException("simulated model failure") }
        assertFalse(extract())
        assertEquals(0L, bridge.personalAgentExtractionCursorSync(central))
        response = { ModelReply("NO_REPLY", emptyList()) }
        assertTrue(extract())
        assertEquals(listOf(listOf("retry fact"), listOf("retry fact")), seen)
    }

    @Test fun cancellationAndDisablingMemoryDuringModelDoNotAdvanceCursor() {
        store.appendConversationMessage(central, "user", "keep for later")
        response = { token -> token.cancel(); ModelReply("NO_REPLY", emptyList()) }
        assertTrue(runCatching { extract() }.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(0L, bridge.personalAgentExtractionCursorSync(central))
        response = { enabled(false); ModelReply("NO_REPLY", emptyList()) }
        assertFalse(extract())
        assertEquals(0L, bridge.personalAgentExtractionCursorSync(central))
        enabled(true)
        response = { ModelReply("NO_REPLY", emptyList()) }
        assertTrue(extract())
        assertTrue(bridge.personalAgentExtractionCursorSync(central) > 0)
    }

    @Test fun narrationJobIsDurableAndDoesNotOwnTheExtractionCursor() {
        val id = bridge.personalAgentEnqueueJob(central, PersonalMemoryExtractionWorker.TYPE_NARRATE)
        val job = bridge.personalAgentClaimJob(id, "owner", System.currentTimeMillis(), Long.MAX_VALUE)
        assertEquals(PersonalMemoryExtractionWorker.TYPE_NARRATE, job?.type)
        assertEquals(0L, bridge.personalAgentExtractionCursorSync(central))
    }
}

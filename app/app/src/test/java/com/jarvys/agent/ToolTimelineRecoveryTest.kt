package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolTimelineRecoveryTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    // Compose actions such as scrollTo must run on the rule's test dispatcher without a second
    // queued dispatcher waiting for the main test coroutine to advance.
    @OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        effectContext = UnconfinedTestDispatcher(),
    )

    // The conversation store is gated by the single-session reset; await it off the main thread first.
    @org.junit.Before fun storageReady() {
        chatTestStore.onDatabaseWorker {
            FreshStorageBootstrap.awaitReadyBlocking(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir)
        }
    }

    @Test fun realWorkspaceToolsRecreateActivityRestoreAndExpandWithoutReplay() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = "reopen-tools-${System.nanoTime()}"
        val store = LocalRunStore(app)
        val projectId = WorkspaceStore.projectIdForSession(session)
        val workspace = WorkspaceStore(File(app.filesDir, "jarvys/workspaces"), projectId)
        val userId = store.appendConversationMessage(session, "user", "Create a gardening page")
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Create a gardening page")
        AgentRunUiState.bindCurrentUserMessage(session, userId)
        val writes = AtomicInteger()
        val handlers = WorkspaceTools.create(workspace).map { handler -> object : CoreTool {
            override fun declaration() = handler.declaration()
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                if (declaration().name == "write") writes.incrementAndGet()
                return handler.execute(arguments, token)
            }
        } }
        val calls = listOf(
            ModelReply.Call("reopen-list-failed", "ls", mapOf("path" to "missing")),
            ModelReply.Call("reopen-list", "ls", mapOf("path" to ".")),
            ModelReply.Call("reopen-write", "write", mapOf("path" to "index.html", "content" to "<h1>Gardening preview persisted</h1>")),
        )
        val turns = AtomicInteger()
        val loop = CoreAgentLoop({ _, _, _, _ ->
            if (turns.getAndIncrement() == 0) ModelReply("Creating page", calls) else ModelReply("Your page is ready.", emptyList())
        }, CoreToolRegistry(handlers), "Use tools", session)
        MainChatTranscriptStore(app.filesDir, session, store).attach(loop, emptyList())
        val result = loop.run("Create a gardening page", emptyList(), CancellationToken.uncancellable(),
            object : CoreAgentLoop.ProgressListener {
                override fun onProgress(stage: String, message: String) = Unit
                override fun onToolProgress(stage: String, callId: String, displayName: String, detail: String?,
                                            previewId: String?, reflectionSource: String?, auditDetail: String?) {
                    AgentForegroundService.persistAndShowToolProgress(store, session, userId, stage, callId,
                        displayName, detail, previewId, reflectionSource, auditDetail)
                }
            })
        val answerId = store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, userId, result.outcome)
        AgentRunUiState.complete(result.runId, result.outcome, result.text, answerId, result.durationMs)

        // Clear process-local projection, recreate the Activity, and read everything from a fresh store.
        AgentRunUiState.resetSession("discard-live-state")
        val originalActivity = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame("Test must use the recreated Activity", originalActivity, compose.activity)
        val reopened = LocalRunStore(app)
        val timeline = reopened.readConversationTimeline(session)
        AgentRunUiState.restoreSession(session, timeline)
        assertEquals(3, timeline.count { it.kind == "tool" })
        assertEquals(1, timeline.count { it.kind == "user" })
        assertTrue(timeline.single { it.toolCallId == "reopen-write" }.detail!!.contains("Wrote index.html"))
        MainChatToolContinuityTest.assertProviderRequests(reopened.loadConversationContext(session), "reopen-write", "Wrote index.html")
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AgentRunScreen(conversationKey = session, events = timeline, isRunning = false,
                        emptyReport = null, connectorRegistry = registry,
                        onOpenPreview = {}, onOpenSkillFile = {})
                }
            }
        }
        compose.onNodeWithTag("tool-group-${timeline.first { it.kind == "tool" }.id}").performScrollTo().performClick()
        compose.onNodeWithTag("tool-output-toggle-reopen-write").performScrollTo().performClick()
        // Markdown parsing runs on Default; Compose idleness alone does not await publication.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Wrote index.html").fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        compose.onNodeWithText("Wrote index.html").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("History restore must not re-run the write", 1, writes.get()) }
        AgentRunUiState.resetSession("reopen-cleanup")
        store.deleteConversation(session)
        File(app.filesDir, "jarvys/workspaces/$projectId").deleteRecursively()
    }
}

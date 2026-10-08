package com.jarvys.agent

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolTimelineRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realWorkspaceToolsRecreateActivityRestoreExpandAndOpenExistingPreviewWithoutReplay() {
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
            ModelReply.Call("reopen-preview", "preview_workspace", emptyMap()),
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
        assertEquals(projectId, AgentRunUiState.state.value.events.single { it.toolCallId == "reopen-preview" }.previewId)

        // Clear process-local projection, recreate the Activity, and read everything from a fresh store.
        AgentRunUiState.resetSession("discard-live-state")
        val originalActivity = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame("Test must use the recreated Activity", originalActivity, compose.activity)
        val reopened = LocalRunStore(app)
        val timeline = reopened.readConversationTimeline(session)
        AgentRunUiState.restoreSession(session, timeline)
        assertEquals(4, timeline.count { it.kind == "tool" })
        assertEquals(1, timeline.count { it.kind == "user" })
        assertTrue(timeline.single { it.toolCallId == "reopen-write" }.detail!!.contains("Wrote index.html"))
        assertEquals(projectId, timeline.single { it.toolCallId == "reopen-preview" }.previewId)
        MainChatToolContinuityTest.assertProviderRequests(reopened.loadConversationContext(session), "reopen-write", "Wrote index.html")
        val selectedPreview = mutableStateOf<String?>(null)
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val skillSingleton = com.jarvys.agent.skills.SkillRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val mcpSingleton = com.jarvys.agent.mcp.McpServerRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val priorSkills = skillSingleton.get(null)
        val priorMcp = mcpSingleton.get(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val preview = selectedPreview.value
                    if (preview != null) WorkspacePreviewScreen(preview)
                    else AgentRunScreen(conversationKey = session, events = timeline, isRunning = false,
                        emptyReport = null, connectorRegistry = registry,
                        onOpenPreview = { selectedPreview.value = it }, onOpenSkillFile = {}, chatWithoutMemory = false)
                }
            }
        }
        compose.onNodeWithTag("tool-output-toggle-reopen-write").performScrollTo().performClick()
        compose.onNodeWithText("Wrote index.html").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tool-preview-reopen-preview").performScrollTo().performClick()
        // Preview file verification runs on IO. Compose idleness alone does not await that work.
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            var ready = false
            compose.runOnUiThread { ready = findWebView(compose.activity.window.decorView) != null }
            ready
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(projectId, selectedPreview.value)
            val previewStore = readOnlyPreviewWorkspace(compose.activity, projectId)
            assertTrue("Read-only preview store must resolve the existing HTML", previewStore.hasIndexHtml())
            assertSame("Preview must not initialize the skill catalog", priorSkills, skillSingleton.get(null))
            assertSame("Preview must not initialize the MCP credential vault", priorMcp, mcpSingleton.get(null))
            val web = findWebView(compose.activity.window.decorView)
            assertNotNull("Preview route must create the actual WebView", web)
            val url = shadowOf(web!!).lastLoadedUrl
            assertTrue(url.contains("/workspaces/$projectId/index.html"))
            val request = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            val response = web.webViewClient.shouldInterceptRequest(web, request)
            assertEquals(200, response!!.statusCode)
            assertEquals("<h1>Gardening preview persisted</h1>", response.data.bufferedReader().use { it.readText() })
            assertEquals("History restore must not re-run the write", 1, writes.get())
        }
        AgentRunUiState.resetSession("reopen-cleanup")
        store.deleteConversation(session)
        File(app.filesDir, "jarvys/workspaces/$projectId").deleteRecursively()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

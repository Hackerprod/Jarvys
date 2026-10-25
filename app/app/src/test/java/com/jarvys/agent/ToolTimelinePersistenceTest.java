package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class ToolTimelinePersistenceTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore();

    @Test public void terminalDetailsAndAuditSurviveFreshStoreAndAWorkspaceIdIsNotAPreview() throws Exception {
        File files = chatTestStore.newFilesDirectory(); String session = "terminal-presentation";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String user = store.appendConversationMessage(session, "user", "Show preview");
        String project = WorkspaceStore.projectIdForSession(session);
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), project);
        workspace.write("index.html", "<h1>saved</h1>");
        // A recorded workspace id has no viewer: only a delivered snapshot can be opened from the thread.
        store.appendConversationToolPresentation(session, user, "Preview Workspace", "tool_result", "saved-preview",
                "Private page result password=hidden-sentinel", project, "read index.html");
        store.appendReflectionToolEvent(session, user, "Preview Workspace", "workspace", "tool_result", "saved-preview");
        store.appendConversationMessage(session, "assistant", "ready");
        List<AgentRunUiEvent> events = chatTestStore.localRunStore(files).readConversationTimeline(session);
        AgentRunUiEvent tool = events.stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertEquals(1, events.stream().filter(e -> "tool".equals(e.getKind())).count());
        assertTrue(tool.getDetail().contains("Private page result"));
        assertFalse(tool.getDetail().contains("hidden-sentinel"));
        assertTrue(tool.getDetail().contains("no longer available"));
        assertEquals("read index.html", tool.getToolAuditDetail());
        assertNull(tool.getPreviewId());
        assertFalse(tool.getPreviewIsCurrent());
        assertFalse(store.buildReflectionPayload(session).text.contains("Private page result"));
    }

    @Test public void legacyPreviewMarkerNeverReconstructsAWorkspacePreview() throws Exception {
        File files = chatTestStore.newFilesDirectory(); LocalRunStore store = chatTestStore.localRunStore(files);
        String own = "legacy-own", missing = "legacy-missing";
        for (String session : new String[] {own, missing}) {
            String user = store.appendConversationMessage(session, "user", "Show preview");
            store.appendReflectionToolEvent(session, user, "Preview Workspace", "workspace", "tool_result", "preview-" + session);
        }
        String ownProject = WorkspaceStore.projectIdForSession(own);
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), ownProject);
        workspace.write("index.html", "<h1>current only</h1>");
        AgentRunUiEvent current = store.readConversationTimeline(own).stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertNull("an existing project is never turned into a preview", current.getPreviewId());
        assertFalse(current.getPreviewIsCurrent());
        assertNull(store.readConversationTimeline(missing).stream().filter(e -> "tool".equals(e.getKind())).findFirst().get().getPreviewId());
        assertFalse(new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(missing)).exists());
    }
    @Test public void hydrationAndOptimisticRowsDeduplicateOnlySameMessageIdentity() throws Exception {
        LocalRunStore store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory()); String session = "identity-hydration";
        String first = store.appendConversationMessage(session, "user", "Same words");
        AgentRunUiState.resetSession(session);
        AgentRunUiState.restoreSession(session, store.readConversationTimeline(session));
        AgentRunUiState.beginRun(session, "Same words");
        AgentRunUiState.bindCurrentUserMessage(session, first);
        assertEquals(1, AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream().filter(e -> "user".equals(e.getKind())).count());
        AgentRunUiState.complete("first-run", "COMPLETED", "done");
        String second = store.appendConversationMessage(session, "user", "Same words");
        AgentRunUiState.beginRun(session, "Same words");
        AgentRunUiState.bindCurrentUserMessage(session, second);
        assertEquals(2, AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream().filter(e -> "user".equals(e.getKind())).count());
        assertNotEquals(first, second);
        AgentRunUiState.resetSession("identity-cleanup");
    }

    @Test public void repeatedProviderCallIdInAnotherTurnSurvivesPageRefresh() throws Exception {
        File files = chatTestStore.newFilesDirectory(); String session = "same-call-id-separate-turns";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String oldUser = store.appendConversationMessage(session, "user", "Old turn");
        store.appendReflectionToolEvent(session, oldUser, "Old Tool", "mcp", "tool_result", "call_0");
        List<AgentRunUiEvent> oldPage = store.readConversationTimelinePage(
                session, LocalRunStore.NO_TIMELINE_CURSOR, 1).events;
        assertEquals(1, oldPage.stream().filter(event -> "tool".equals(event.getKind())).count());
        AgentRunUiState.resetSession(session);
        AgentRunUiState.restoreSession(session, oldPage);

        String newUser = store.appendConversationMessage(session, "user", "New turn");
        store.appendReflectionToolEvent(session, newUser, "New Tool", "mcp", "tool_result", "call_0");
        List<AgentRunUiEvent> refreshedWindow = store.readConversationTimelinePage(
                session, LocalRunStore.NO_TIMELINE_CURSOR, 1).events;
        AgentRunUiState.refreshPersistedSession(session, refreshedWindow);

        List<AgentRunUiEvent> tools = AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream()
                .filter(event -> "tool".equals(event.getKind()) && "call_0".equals(event.getToolCallId()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, tools.size());
        assertEquals(java.util.Arrays.asList(oldUser + "/call_0", newUser + "/call_0"),
                tools.stream().map(AgentRunUiEvent::getToolIdentity).collect(java.util.stream.Collectors.toList()));
        AgentRunUiState.resetSession(session);
    }


    @Test public void repeatedProviderCallIdInDifferentBatchesKeepsBatchScopedIdentity() throws Exception {
        File files = chatTestStore.newFilesDirectory(); String session = "same-call-id-separate-batches";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String user = store.appendConversationMessage(session, "user", "Run two batches");
        for (String batchId : java.util.Arrays.asList("batch-old", "batch-new")) {
            org.json.JSONObject call = new org.json.JSONObject().put("id", "call_0")
                    .put("name", "search").put("arguments", new org.json.JSONObject());
            store.appendModelTranscriptRow(session, new org.json.JSONObject()
                    .put("type", "model_tool_calls").put("batchId", batchId)
                    .put("userMessageId", user).put("calls", new org.json.JSONArray().put(call)));
            store.appendReflectionToolEvent(session, user, "Search", "mcp", "tool_result", "call_0");
        }

        List<AgentRunUiEvent> tools = store.readConversationTimeline(session).stream()
                .filter(event -> "tool".equals(event.getKind()) && "call_0".equals(event.getToolCallId()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, tools.size());
        assertEquals(java.util.Arrays.asList("batch-old/call_0", "batch-new/call_0"),
                tools.stream().map(AgentRunUiEvent::getToolIdentity).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void interruptedStartedAndUnlaunchedToolsRestoreHonestCardsWithoutReplay() throws Exception {
        File files = chatTestStore.newFilesDirectory(); String session = "interrupted-timeline";
        LocalRunStore store = chatTestStore.localRunStore(files);
        store.appendConversationMessage(session, "user", "Write two files");
        java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
        CoreTool write = MainChatToolContinuityTest.testTool("write", (args, token) -> {
            effects.incrementAndGet(); token.cancelForTimeout(); token.throwIfCancelled();
            return CoreToolResult.success("unreachable");
        }, CoreTool.ExecutionMode.SEQUENTIAL);
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> new ModelReply("", java.util.Arrays.asList(
                new ModelReply.Call("started", "write", MainChatToolContinuityTest.arguments("path", "one.html")),
                new ModelReply.Call("pending", "write", MainChatToolContinuityTest.arguments("path", "two.html")))),
                new CoreToolRegistry(java.util.Collections.singletonList(write)), "system", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, java.util.Collections.emptyList());
        loop.run("Write two files", java.util.Collections.emptyList(), CancellationToken.uncancellable(), null);
        List<AgentRunUiEvent> restored = chatTestStore.localRunStore(files).readConversationTimeline(session);
        AgentRunUiEvent started = restored.stream().filter(e -> "started".equals(e.getToolCallId())).findFirst().get();
        AgentRunUiEvent pending = restored.stream().filter(e -> "pending".equals(e.getToolCallId())).findFirst().get();
        assertEquals("tool_interrupted", started.getStage());
        assertEquals(Integer.valueOf(R.string.connector_tool_unconfirmed), started.getToolStatusResourceId());
        assertTrue(started.getDetail().contains("effects may have occurred"));
        assertEquals("tool_not_started", pending.getStage());
        assertEquals(Integer.valueOf(R.string.connector_tool_not_started), pending.getToolStatusResourceId());
        assertNull(started.getPreviewId()); assertNull(pending.getPreviewId());
        assertTrue(store.readModelTranscriptRows(session).stream().anyMatch(row -> "model_tool_result".equals(row.optString("type"))
                && "started".equals(row.optString("callId"))
                && "INTERRUPTED_UNCERTAIN".equals(row.optString("lifecycle"))
                && row.optString("output").contains("effects may have occurred")));
        assertTrue(store.readModelTranscriptRows(session).stream().anyMatch(row -> "model_tool_result".equals(row.optString("type"))
                && "pending".equals(row.optString("callId"))
                && "NEVER_LAUNCHED".equals(row.optString("lifecycle"))
                && row.optString("output").contains("NEVER_LAUNCHED")));
        assertEquals(1, effects.get());
    }

    @Test public void durableResultWithoutPresentationRestoresDetailsButRegenerationHidesOnlyUiAttempt() throws Exception {
        File files = chatTestStore.newFilesDirectory(); String session = "result-presentation-gap";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String user = store.appendConversationMessage(session, "user", "Write one file");
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
                ? new ModelReply("", java.util.Collections.singletonList(new ModelReply.Call("gap-call", "write", MainChatToolContinuityTest.arguments("path", "index.html"))))
                : new ModelReply("done", java.util.Collections.emptyList()), new CoreToolRegistry(java.util.Collections.singletonList(
                        MainChatToolContinuityTest.testTool("write", (args, token) -> CoreToolResult.success("Wrote index.html")))), "system", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, java.util.Collections.emptyList());
        CoreAgentLoop.Result result = loop.run("Write one file", java.util.Collections.emptyList(), CancellationToken.uncancellable(), null);
        store.appendReflectionToolEvent(session, user, "Write", "workspace", "tool_result", "gap-call");
        String answer = store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
        List<AgentRunUiEvent> restored = chatTestStore.localRunStore(files).readConversationTimeline(session);
        assertEquals(1, restored.stream().filter(e -> "tool".equals(e.getKind())).count());
        AgentRunUiEvent tool = restored.stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertEquals("Wrote index.html", tool.getDetail()); assertEquals("tool_result", tool.getStage());
        store.appendAssistantRegenerated(session, answer);
        assertFalse(chatTestStore.localRunStore(files).readConversationTimeline(session).stream().anyMatch(e -> "gap-call".equals(e.getToolCallId())));
        assertTrue(chatTestStore.localRunStore(files).loadConversationContext(session).stream()
                .anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT && t.content.contains("Wrote index.html")));
    }

    @Test public void activeHydrationPreservesMatchingLiveToolButStillShowsOldUncertaintyAndRealResults() {
        String session = "active-hydration";
        AgentRunUiState.resetSession(session);
        AgentRunUiState.beginRun(session, "Keep working");
        AgentRunUiState.onToolProgress("tool_call", "live-call", "Write", null);
        AgentRunUiEvent uncertain = AgentRunUiEvent.toolEvent(200, "tool_interrupted", "Write", "effects may have occurred", "live-call", null, 0);
        AgentRunUiEvent old = AgentRunUiEvent.toolEvent(201, "tool_interrupted", "Read", "old interrupted call", "old-call", null, 0);
        AgentRunUiState.refreshPersistedSession(session, java.util.Arrays.asList(uncertain, old));
        List<AgentRunUiEvent> live = AgentRunUiState.INSTANCE.getState().getValue().getEvents();
        assertEquals("tool_call", live.stream().filter(e -> "live-call".equals(e.getToolCallId())).findFirst().get().getStage());
        assertEquals("tool_interrupted", live.stream().filter(e -> "old-call".equals(e.getToolCallId())).findFirst().get().getStage());
        AgentRunUiState.onToolProgress("tool_progress", "live-call", "Write", "working");
        AgentRunUiState.refreshPersistedSession(session, java.util.Arrays.asList(uncertain, old));
        assertEquals("tool_progress", AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream()
                .filter(e -> "live-call".equals(e.getToolCallId())).findFirst().get().getStage());
        AgentRunUiEvent complete = AgentRunUiEvent.toolEvent(202, "tool_result", "Write", "Wrote index.html", "live-call", null, 0);
        AgentRunUiState.refreshPersistedSession(session, java.util.Arrays.asList(complete, old));
        assertEquals("tool_result", AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream()
                .filter(e -> "live-call".equals(e.getToolCallId())).findFirst().get().getStage());
        AgentRunUiState.resetSession(session);
        AgentRunUiState.restoreSession(session, java.util.Collections.singletonList(uncertain));
        assertEquals("tool_interrupted", AgentRunUiState.INSTANCE.getState().getValue().getEvents().get(0).getStage());
        AgentRunUiState.resetSession("active-hydration-cleanup");
    }

}

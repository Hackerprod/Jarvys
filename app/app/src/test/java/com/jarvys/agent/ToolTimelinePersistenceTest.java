package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ToolTimelinePersistenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void terminalDetailsAuditAndExactPreviewSurviveFreshStoreWithoutReflectionLeak() throws Exception {
        File files = temporary.newFolder(); String session = "terminal-presentation";
        LocalRunStore store = new LocalRunStore(files);
        String user = store.appendConversationMessage(session, "user", "Show preview");
        String project = WorkspaceStore.projectIdForSession(session);
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), project);
        workspace.write("index.html", "<h1>saved</h1>");
        store.appendConversationToolPresentation(session, user, "Preview Workspace", "tool_result", "saved-preview",
                "Private page result password=hidden-sentinel", project, "read index.html");
        store.appendReflectionToolEvent(session, user, "Preview Workspace", "workspace", "tool_result", "saved-preview");
        store.appendConversationMessage(session, "assistant", "ready");
        List<AgentRunUiEvent> events = new LocalRunStore(files).readConversationTimeline(session);
        AgentRunUiEvent tool = events.stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertEquals(1, events.stream().filter(e -> "tool".equals(e.getKind())).count());
        assertTrue(tool.getDetail().contains("Private page result"));
        assertFalse(tool.getDetail().contains("hidden-sentinel"));
        assertEquals("read index.html", tool.getToolAuditDetail());
        assertEquals(project, tool.getPreviewId());
        assertFalse(tool.getPreviewIsCurrent());
        assertFalse(store.buildReflectionPayload(session).text.contains("Private page result"));
        assertTrue(workspace.resolvePreviewPath("index.html").delete());
        AgentRunUiEvent missing = new LocalRunStore(files).readConversationTimeline(session).stream()
                .filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertNull(missing.getPreviewId());
        assertTrue(missing.getDetail().contains("no longer available"));
    }

    @Test public void legacyPreviewRequiresOwnExistingCanonicalFileAndNeverReconstructsOutput() throws Exception {
        File files = temporary.newFolder(); LocalRunStore store = new LocalRunStore(files);
        String own = "legacy-own", other = "legacy-other", missing = "legacy-missing";
        for (String session : new String[] {own, other, missing}) {
            String user = store.appendConversationMessage(session, "user", "Show preview");
            store.appendReflectionToolEvent(session, user, "Preview Workspace", "workspace", "tool_result", "preview-" + session);
        }
        String ownProject = WorkspaceStore.projectIdForSession(own);
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), ownProject);
        workspace.write("index.html", "<h1>current only</h1>");
        AgentRunUiEvent current = store.readConversationTimeline(own).stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertEquals(ownProject, current.getPreviewId());
        assertTrue(current.getPreviewIsCurrent());
        assertTrue(current.getDetail().contains("unavailable"));
        assertFalse(current.getDetail().contains("current only"));
        assertNull(store.readConversationTimeline(missing).stream().filter(e -> "tool".equals(e.getKind())).findFirst().get().getPreviewId());
        File otherRoot = new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(other));
        Files.createSymbolicLink(otherRoot.toPath(), workspace.resolvePreviewPath("index.html").getParentFile().toPath());
        assertNull(store.readConversationTimeline(other).stream().filter(e -> "tool".equals(e.getKind())).findFirst().get().getPreviewId());
        assertFalse(new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(missing)).exists());
    }

    @Test public void hydrationAndOptimisticRowsDeduplicateOnlySameMessageIdentity() throws Exception {
        LocalRunStore store = new LocalRunStore(temporary.newFolder()); String session = "identity-hydration";
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
    @Test public void interruptedStartedAndUnlaunchedToolsRestoreHonestCardsWithoutReplay() throws Exception {
        File files = temporary.newFolder(); String session = "interrupted-timeline";
        LocalRunStore store = new LocalRunStore(files);
        store.appendConversationMessage(session, "user", "Write two files");
        java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
        CoreTool write = MainChatToolContinuityTest.testTool("write", (args, token) -> {
            effects.incrementAndGet(); token.cancelForTimeout(); token.throwIfCancelled();
            return CoreToolResult.success("unreachable");
        });
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> new ModelReply("", java.util.Arrays.asList(
                new ModelReply.Call("started", "write", MainChatToolContinuityTest.arguments("path", "one.html")),
                new ModelReply.Call("pending", "write", MainChatToolContinuityTest.arguments("path", "two.html")))),
                new CoreToolRegistry(java.util.Collections.singletonList(write)), "system", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, java.util.Collections.emptyList());
        loop.run("Write two files", java.util.Collections.emptyList(), CancellationToken.uncancellable(), null);
        List<AgentRunUiEvent> restored = new LocalRunStore(files).readConversationTimeline(session);
        AgentRunUiEvent started = restored.stream().filter(e -> "started".equals(e.getToolCallId())).findFirst().get();
        AgentRunUiEvent pending = restored.stream().filter(e -> "pending".equals(e.getToolCallId())).findFirst().get();
        assertEquals("tool_interrupted", started.getStage());
        assertEquals(Integer.valueOf(R.string.connector_tool_unconfirmed), started.getToolStatusResourceId());
        assertTrue(started.getDetail().contains("effects may have occurred"));
        assertEquals("tool_not_started", pending.getStage());
        assertEquals(Integer.valueOf(R.string.connector_tool_not_started), pending.getToolStatusResourceId());
        assertNull(started.getPreviewId()); assertNull(pending.getPreviewId());
        assertEquals(1, effects.get());
    }

    @Test public void durableResultWithoutPresentationRestoresDetailsButRegenerationHidesOnlyUiAttempt() throws Exception {
        File files = temporary.newFolder(); String session = "result-presentation-gap";
        LocalRunStore store = new LocalRunStore(files);
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
        List<AgentRunUiEvent> restored = new LocalRunStore(files).readConversationTimeline(session);
        assertEquals(1, restored.stream().filter(e -> "tool".equals(e.getKind())).count());
        AgentRunUiEvent tool = restored.stream().filter(e -> "tool".equals(e.getKind())).findFirst().get();
        assertEquals("Wrote index.html", tool.getDetail()); assertEquals("tool_result", tool.getStage());
        store.appendAssistantRegenerated(session, answer);
        assertFalse(new LocalRunStore(files).readConversationTimeline(session).stream().anyMatch(e -> "gap-call".equals(e.getToolCallId())));
        assertTrue(new LocalRunStore(files).loadConversationContext(session).stream()
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

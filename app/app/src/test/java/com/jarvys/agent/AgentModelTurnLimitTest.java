package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.jarvys.agent.device.ScreenData;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
    shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class AgentModelTurnLimitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @After public void reset() { ArtifactOsShadow.reset(); }
    @Test public void ordinaryRunStillStopsAfterExactly128RepliesWithoutRetryOrNoProgressConflation() {
        AtomicInteger requests = new AtomicInteger(), effects = new AtomicInteger();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("step", "test", "synthetic step", "test", ToolSpec.Status.IMPLEMENTED,
                    Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
                return CoreToolResult.success("step-" + effects.incrementAndGet());
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, declarations, token) -> {
            int request = requests.incrementAndGet();
            return new ModelReply("", Collections.singletonList(
                new ModelReply.Call("step-" + request, "step", Collections.singletonMap("index", request))));
        }, new CoreToolRegistry(Collections.singletonList(tool)), "", "synthetic-main-chat");
        CancellationToken token = CancellationToken.cancellable();
        AgentModelTurnLimitException failure = assertThrows(AgentModelTurnLimitException.class,
            () -> loop.run("Run synthetic steps", Collections.emptyList(), token, null));
        assertTrue(failure instanceof IllegalStateException);
        assertEquals("Agent reached its configured model-turn limit", failure.getMessage());
        assertEquals(128, requests.get()); assertEquals(128, effects.get());
        assertFalse(token.isCancellationRequested());
        assertEquals(128, loop.transcriptSnapshot().stream()
            .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT).count());
    }
    @Test public void reflectionRetainsTwelveTurnCapFailureWrappingAndNoAutomaticRetry() throws Exception {
        File files = temporary.newFolder("reflection");
        MemoryStore memory = new MemoryStore(new File(files, "memory"), true,
            MemorySeedTextProvider.fromAppLanguage(RuntimeEnvironment.getApplication())).forConversation("turn-limit");
        memory.ensureInitialized();
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "workspaces"), "cccccccccccccccccccccccc",
            null, null, memory, "turn-limit", true, MemoryStore.Actor.REFLECTION, "turn-limit-group", true);
        AtomicInteger requests = new AtomicInteger();
        ModelProviderClient provider = new ModelProviderClient() {
            @Override public ModelReply complete(String system, String prompt, List<ScreenData> images,
                    List<ToolSpec> declarations, String session, CancellationToken token) {
                throw new AssertionError("Only the mock conversation boundary is allowed");
            }
            @Override public ModelReply completeConversation(String system, List<ConversationTurn> history,
                    String prompt, List<ScreenData> images, List<ToolSpec> declarations, String session, CancellationToken token) {
                int step = requests.incrementAndGet();
                return new ModelReply("", Collections.singletonList(new ModelReply.Call("list-" + step,
                    "ls", Collections.singletonMap("path", "/memory/missing-" + step))));
            }
        };
        MemoryReflectionWorker worker = new MemoryReflectionWorker("turn-limit", "turn-limit-group", memory,
            WorkspaceTools.createReflectionMemoryOnly(workspace), new CoreAgentModel(provider, "turn-limit"));
        MemoryReflectionWorker.Failure failure = assertThrows(MemoryReflectionWorker.Failure.class,
            () -> worker.run("[{\"role\":\"user\",\"content\":\"Synthetic preference\"}]", CancellationToken.uncancellable()));
        assertTrue(failure.getCause() instanceof AgentModelTurnLimitException);
        assertEquals(12, MemoryReflectionWorker.MAX_MODEL_TURNS);
        assertEquals(MemoryReflectionWorker.MAX_MODEL_TURNS, requests.get());
        assertTrue(memory.reflectionGroupRevisions("turn-limit-group").isEmpty());
    }
    @Test public void turnLimitHasSafeStructuralDiagnosticIdentityWithoutMessageText() throws Exception {
        LocalDiagnostics store = new LocalDiagnostics(temporary.newFolder("no-backup"), () -> 1_800_000_000_000L);
        assertTrue(store.record(LocalDiagnostics.Origin.SERVICE, new AgentModelTurnLimitException(), false));
        JSONObject row = store.snapshot(null, 1, true).getJSONArray("records").getJSONObject(0);
        assertEquals(AgentModelTurnLimitException.class.getName(), row.getJSONArray("causes").getJSONObject(0).getString("type"));
        assertFalse(row.toString().contains("Agent reached its configured model-turn limit"));
    }
}

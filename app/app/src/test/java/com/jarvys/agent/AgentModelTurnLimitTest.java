package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
    shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class AgentModelTurnLimitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @After public void reset() { ArtifactOsShadow.reset(); }
    @Test public void ordinaryRunCompletesAfterMoreThan128ToolRounds() {
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
            if (request > 130) return new ModelReply("finished", Collections.emptyList());
            return new ModelReply("", Collections.singletonList(
                new ModelReply.Call("step-" + request, "step", Collections.singletonMap("index", request))));
        }, new CoreToolRegistry(Collections.singletonList(tool)), "", "synthetic-main-chat");
        CoreAgentLoop.Result result = loop.run("Run synthetic steps", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals("COMPLETED", result.outcome);
        assertEquals("finished", result.text);
        assertEquals(131, requests.get()); assertEquals(130, effects.get());
        assertEquals(130, loop.transcriptSnapshot().stream()
            .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT).count());
    }
    @Test public void turnLimitHasSafeStructuralDiagnosticIdentityWithoutMessageText() throws Exception {
        LocalDiagnostics store = new LocalDiagnostics(temporary.newFolder("no-backup"), () -> 1_800_000_000_000L);
        assertTrue(store.record(LocalDiagnostics.Origin.SERVICE, new AgentModelTurnLimitException(), false));
        JSONObject row = store.snapshot(null, 1, true).getJSONArray("records").getJSONObject(0);
        assertEquals(AgentModelTurnLimitException.class.getName(), row.getJSONArray("causes").getJSONObject(0).getString("type"));
        assertFalse(row.toString().contains("Agent reached its configured model-turn limit"));
    }
}

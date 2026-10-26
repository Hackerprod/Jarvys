package com.jarvys.agent;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.EpisodeToolSource;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class EpisodeRunIdentityTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();

    @Test public void recorderUsesCommittedBatchAndSharedRunIdentityAcrossRepeatedProviderIds() throws Exception {
        File files = fixture.newFilesDirectory();
        LocalRunStore store = fixture.localRunStore(files);
        Context context = context(files);
        ChatDatabaseBridge bridge = ChatDatabaseBridge.getInstance(context);
        String session = "episode-batch-session";
        String priorBatch = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String runId = "run-" + attempt;
            String owner = store.appendConversationMessage(session, "user", "Write " + attempt);
            MainChatTranscriptStore transcript = new MainChatTranscriptStore(files, session, store);
            AtomicInteger turns = new AtomicInteger();
            CoreTool write = new CoreTool() {
                @Override public ToolSpec declaration() {
                    return new ToolSpec("write_effect", "test", "write", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
                }
                @Override public EffectClass effectClass() { return EffectClass.WRITE; }
                @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token,
                        ProgressListener progress, EffectInvocation invocation) {
                    CoreToolResult result = CoreTool.super.execute(arguments, token, progress, invocation);
                    // This deterministic fake declares its own completion; registry never infers it from success.
                    return result.withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.OK));
                }
                @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                    MainChatTranscriptStore.CallBinding binding = transcript.committedCall("provider-reused");
                    EpisodeToolSource source = bridge.readEpisodeToolSourceSync(session, binding.batchId, binding.callId);
                    assertNotNull("Binding must reference a committed batch before dispatch", source);
                    assertNull("No tool result exists before dispatch", source.getOutput());
                    return CoreToolResult.success("write completed");
                }
            };
            CoreToolRegistry tools = new CoreToolRegistry(Collections.singletonList(write))
                .withInvocationObserver(new EpisodeRecorder(context, session, owner, runId, transcript));
            CoreAgentLoop loop = new CoreAgentLoop((history, prompt, available, token) ->
                turns.getAndIncrement() == 0 ? new ModelReply("", Collections.singletonList(
                    new ModelReply.Call("provider-reused", "write_effect", Collections.emptyMap())))
                    : new ModelReply("done", Collections.emptyList()), tools, "Use tools", session);
            transcript.attach(loop, Collections.emptyList(), owner);
            CoreAgentLoop.Result result = loop.run("write", Collections.emptyList(), Collections.emptyList(),
                CancellationToken.cancellable(), null, owner, runId);
            assertEquals(runId, result.runId);
            String entry = bridge.personalAgentEpisodesForSourceMessagesSync(session, Collections.singletonList(owner)).get(0).getId();
            com.jarvys.agent.chatdb.PersonalMemoryEvidenceView row = bridge.personalAgentEpisodeNarrationInputSync(entry).getEvidence().get(0);
            assertEquals(runId, row.getRunId());
            assertEquals("ok", row.getOutcome());
            assertEquals(transcript.committedCall("provider-reused").batchId, row.getSourceBatchId());
            if (priorBatch != null) assertNotEquals(priorBatch, row.getSourceBatchId());
            priorBatch = row.getSourceBatchId();
            assertEquals("write completed", bridge.readEpisodeToolSourceSync(session, priorBatch, "provider-reused").getOutput());
        }
    }

    @Test public void failedCheckpointDoesNotPublishBindingOrDispatchEffect() throws Exception {
        File files = fixture.newFilesDirectory();
        LocalRunStore store = fixture.localRunStore(files);
        String session = "failed-episode-checkpoint";
        String owner = store.appendConversationMessage(session, "user", "write");
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(files, session, store);
        AtomicInteger effects = new AtomicInteger();
        CoreTool write = new CoreTool() {
            @Override public ToolSpec declaration() { return new ToolSpec("write_effect", "test", "write", "test",
                ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList()); }
            @Override public EffectClass effectClass() { return EffectClass.WRITE; }
            @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
                effects.incrementAndGet(); return CoreToolResult.success("done");
            }
        };
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(write))
            .withInvocationObserver(new EpisodeRecorder(context(files), session, owner, "run", transcript));
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> new ModelReply("",
            Collections.singletonList(new ModelReply.Call("call", "write_effect", Collections.emptyMap()))), registry, "Use tools", session);
        transcript.attach(loop, Collections.emptyList(), owner);
        MainChatTranscriptStore.checkpointStagedHookForTests = rows -> { throw new IllegalStateException("test persistence failure"); };
        try {
            try { loop.run("write", Collections.emptyList(), CancellationToken.cancellable(), null); fail("Expected checkpoint failure"); }
            catch (CoreAgentLoop.CheckpointFailure expected) { }
            assertEquals(0, effects.get());
            try { transcript.committedCall("call"); fail("Uncommitted binding must be absent"); }
            catch (IllegalStateException expected) { }
        } finally { MainChatTranscriptStore.checkpointStagedHookForTests = null; }
    }

    private Context context(File files) {
        return new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
    }
}

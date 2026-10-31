package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Test-only real checkpoint/provider fixture. Callback runs inside the live owning invocation. */
public final class CentralInvocationTestSource {
    private CentralInvocationTestSource() { }
    public static CentralInvocationWitness runWithWitness(Context context,
            BiConsumer<EffectInvocation, CentralInvocationWitness> callback) {
        return runWithWitness(context, "Coordinate local workspace", "Coordinate", "probe-call", callback);
    }
    public static CentralInvocationWitness runWithWitness(Context context, String userText,
            BiConsumer<EffectInvocation, CentralInvocationWitness> callback) {
        return runWithWitness(context, userText, userText, "probe-" + UUID.randomUUID(), callback);
    }
    private static CentralInvocationWitness runWithWitness(Context context, String userText, String request,
            String callId, BiConsumer<EffectInvocation, CentralInvocationWitness> callback) {
        LocalRunStore store = new LocalRunStore(context);
        String owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", userText);
        String run = UUID.randomUUID().toString();
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(context.getFilesDir(), WorkspaceIdentity.CENTRAL_SESSION, store);
        AtomicReference<CentralInvocationWitness> captured = new AtomicReference<>();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("source_probe", "test", "source probe", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public EffectClass effectClass() { return EffectClass.WRITE; }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
                throw new AssertionError("Fixture must use the explicit invocation context");
            }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token,
                    ProgressListener listener, EffectInvocation invocation) {
                CentralInvocationWitness witness = invocation.centralSource();
                if (witness == null) throw new AssertionError("Committed Central witness required");
                captured.set(witness); callback.accept(invocation, witness);
                // Deliberately not an execution receipt: the callback owns any actual effect facts.
                return CoreToolResult.success("Local fixture observed");
            }
        };
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(tool))
                .withInvocationSourceProvider(transcript.centralSourceProvider(owner,run));
        AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((history,prompt,tools,token) -> turns.getAndIncrement() == 0
                ? new ModelReply("",Collections.singletonList(new ModelReply.Call(callId,"source_probe",Collections.emptyMap())))
                : new ModelReply("Done",Collections.emptyList()),registry,"Use the local fixture",WorkspaceIdentity.CENTRAL_SESSION);
        transcript.attach(loop,Collections.emptyList(),owner,run);
        loop.run(request,Collections.emptyList(),Collections.emptyList(),CancellationToken.cancellable(),null,owner,run);
        if (captured.get() == null) throw new AssertionError("Source callback was not reached");
        return captured.get();
    }
}

package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real loop preparation and fsynced checkpoint evidence; no Coding async opt-in or job receipt. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows = ArtifactOsShadow.class)
public class CrewInvocationSourceTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();

    private static Map<String,Object> args(Object value) {
        Map<String,Object> map = new LinkedHashMap<>(); map.put("value",value); return map;
    }
    private static JSONObject metadata() throws Exception {
        return new JSONObject().put("missionId","mission-one").put("cycleId","cycle-one");
    }
    private static final class Harness {
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicReference<CrewInvocationWitness> witness = new AtomicReference<>();
        final AtomicReference<CrewCheckpointStore.ConfirmedSnapshot> confirmed = new AtomicReference<>();
        CrewCheckpointStore.ConfirmedSnapshot latestOnDisk;
        final AtomicReference<CoreAgentLoop.PreparedInvocationSnapshot> prepared = new AtomicReference<>();
        final AtomicReference<Map<String,Object>> executed = new AtomicReference<>();
        final AtomicReference<CoreToolRegistry> registry = new AtomicReference<>();
    }
    private Harness run(Map<String,Object> arguments, String propertyType,
                        BiConsumer<CoreAgentLoop.PreparedInvocationSnapshot,CrewCheckpointStore.ConfirmedSnapshot> afterSave, boolean failSync) throws Exception {
        return run(arguments,propertyType,afterSave,failSync,false);
    }
    private Harness run(Map<String,Object> arguments, String propertyType,
                        BiConsumer<CoreAgentLoop.PreparedInvocationSnapshot,CrewCheckpointStore.ConfirmedSnapshot> afterSave,
                        boolean failSync, boolean cancelAtActivity) throws Exception {
        return run(arguments,propertyType,afterSave,failSync,cancelAtActivity,null);
    }
    private Harness run(Map<String,Object> arguments, String propertyType,
                        BiConsumer<CoreAgentLoop.PreparedInvocationSnapshot,CrewCheckpointStore.ConfirmedSnapshot> afterSave,
                        boolean failSync, boolean cancelAtActivity, Runnable duringValidation) throws Exception {
        return run(arguments,propertyType,afterSave,failSync,cancelAtActivity,duringValidation,java.util.function.Function.identity());
    }
    private Harness run(Map<String,Object> arguments, String propertyType,
                        BiConsumer<CoreAgentLoop.PreparedInvocationSnapshot,CrewCheckpointStore.ConfirmedSnapshot> afterSave,
                        boolean failSync, boolean cancelAtActivity, Runnable duringValidation,
                        java.util.function.Function<CoreAgentLoop.Checkpoint,CoreAgentLoop.Checkpoint> savedStarted) throws Exception {
        File files = fixture.newFilesDirectory(); fixture.localRunStore(files);
        CrewCheckpointStore store = new CrewCheckpointStore(files, directory -> {
            if (failSync) throw new IOException("Fixture directory sync failure");
        });
        Harness h = new Harness();
        Map<String,Object> schema = new LinkedHashMap<>();
        AtomicBoolean validationMutation = new AtomicBoolean();
        Map<String,Object> property = new LinkedHashMap<String,Object>() {
            @Override public Object get(Object key) {
                if (duringValidation != null && validationMutation.compareAndSet(false,true)) duringValidation.run();
                return super.get(key);
            }
        };
        property.put("type",propertyType);
        schema.put("type","object"); schema.put("properties",Collections.singletonMap("value",property));
        schema.put("required",Collections.singletonList("value"));
        ToolSpec spec = new ToolSpec("probe", "test", "Local probe", "test", ToolSpec.Status.IMPLEMENTED,
                Collections.singletonMap("value",propertyType), Collections.singletonList("value"),schema);
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String,Object> args, CancellationToken token) {
                throw new AssertionError("Invocation context must be transported");
            }
            @Override public CoreToolResult execute(Map<String,Object> args, CancellationToken token,
                    ProgressListener progress, EffectInvocation invocation) {
                CrewInvocationWitness witness = invocation.crewSource(); assertNotNull(witness);
                witness.requireInvocationCurrent(); assertNull(invocation.centralSource());
                h.witness.set(witness); h.executed.set(args); h.dispatches.incrementAndGet();
                assertThrows(UnsupportedOperationException.class, () -> args.put("extra",true));
                return CoreToolResult.success("Fixture observed");
            }
        };
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(tool)).withInvocationSourceProvider(
                new CoreToolRegistry.InvocationSourceProvider() {
                    @Override public void bind(CoreTool actual, String call, EffectInvocation invocation) {
                        throw new IllegalStateException("Prepared identity required");
                    }
                    @Override public void bindPrepared(CoreTool actual, String call, Map<String,Object> args,
                            CoreAgentLoop.PreparedInvocationSnapshot prepared, EffectInvocation invocation) {
                        if (prepared == null) throw new IllegalStateException("Prepared identity required");
                        assertSame(h.prepared.get(),prepared);
                        prepared.requireDispatch(actual,args); prepared.requireConfirmed(h.confirmed.get());
                        invocation.bindCrewSource(new CrewInvocationWitness(new CrewInvocationSource(h.confirmed.get(),prepared),
                                invocation, () -> {}));
                    }
                });
        h.registry.set(registry);
        AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((history,prompt,tools,token) -> turns.getAndIncrement()==0
                ? new ModelReply("",Collections.singletonList(new ModelReply.Call("call-one","probe",arguments)))
                : new ModelReply("Done",Collections.emptyList()),registry,"","crew-session");
        loop.setCheckpointListener(new CoreAgentLoop.CheckpointListener() {
            @Override public boolean requiresPreparedInvocationSnapshot() { return true; }
            @Override public void persist(CoreAgentLoop.Checkpoint checkpoint) {
                // Real later writes must not replace the already captured STARTED source.
                if (h.confirmed.get() != null) try {
                    store.saveConfirmed("crew-session","bot-one",metadata(),"scope-one",checkpoint,new JSONObject());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            @Override public void persistStarted(CoreAgentLoop.Checkpoint checkpoint, CoreAgentLoop.PreparedInvocationSnapshot prepared) {
                try {
                    h.confirmed.set(store.saveConfirmed("crew-session","bot-one",metadata(),"scope-one",savedStarted.apply(checkpoint),new JSONObject()));
                    h.prepared.set(prepared);
                    if (afterSave != null) afterSave.accept(prepared,h.confirmed.get());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        });
        CancellationToken token = CancellationToken.cancellable();
        try {
            loop.run("Probe",Collections.emptyList(),token,cancelAtActivity ? new CoreAgentLoop.ProgressListener() {
                @Override public void onProgress(String stage,String detail) { }
                @Override public void onToolActivity(ToolActivity activity) { if ("tool_call".equals(activity.stage)) token.cancel(); }
            } : null);
            if (failSync) fail("A failed checkpoint must abort the loop");
        } catch (RuntimeException expected) {
            if (!failSync && !cancelAtActivity) throw expected;
        } finally { Thread.interrupted(); }
        // Oracle only: production provider above never performs this latest-file lookup.
        if (!failSync && h.confirmed.get() != null)
            h.latestOnDisk = store.confirmDurable("crew-session","bot-one","scope-one");
        return h;
    }

    @Test public void exactSameSaveWitnessSurvivesFinalCheckpointAndSealing() throws Exception {
        Harness h = run(args("plain"),"string",null,false);
        assertEquals(1,h.dispatches.get());
        CrewInvocationSource source = h.witness.get().source();
        assertEquals("crew-session",source.executionSessionId); assertEquals("bot-one",source.botId);
        assertEquals("mission-one",source.missionId); assertEquals("cycle-one",source.cycleId);
        assertEquals("scope-one",source.scopeIdentity); assertEquals("call-one",source.callId);
        assertEquals(h.confirmed.get().payloadSha256,source.checkpointPayloadSha256);
        assertEquals("STARTED",h.confirmed.get().loopForEvidence().toolLifecycle.get("call-one"));
        assertNotNull(h.latestOnDisk);
        assertEquals("RESULT",h.latestOnDisk.loopForEvidence().toolLifecycle.get("call-one"));
        assertNotEquals("Later persisted RESULT must not rewrite the invocation's source digest",
                h.latestOnDisk.payloadSha256,source.checkpointPayloadSha256);
        assertThrows(IllegalStateException.class, () -> h.witness.get().requireInvocationCurrent());
        assertThrows(IllegalStateException.class, () -> h.prepared.get().arguments());
    }

    @Test public void intentAndResultCheckpointsCannotStandInForExactStartedProof() throws Exception {
        for (String state : Arrays.asList("INTENT","RESULT")) {
            Harness h = run(args("plain"),"string",null,false,false,null,checkpoint -> {
                Map<String,String> lifecycle = new LinkedHashMap<>(checkpoint.toolLifecycle);
                lifecycle.put("call-one",state);
                return new CoreAgentLoop.Checkpoint(checkpoint.transcript,checkpoint.appliedIncomingIds,lifecycle,
                        checkpoint.genuineUserIndex,checkpoint.incorporations);
            });
            assertEquals(state,h.confirmed.get().loopForEvidence().toolLifecycle.get("call-one"));
            assertEquals("Must not dispatch from " + state,0,h.dispatches.get());
            assertNull("No invocation witness from " + state,h.witness.get());
        }
    }

    @Test public void differentCallOrToolInConfirmedBytesCannotReplacePreparedInvocation() throws Exception {
        for (boolean wrongCall : Arrays.asList(true,false)) {
            Harness h = run(args("plain"),"string",null,false,false,null,checkpoint -> {
                List<ConversationTurn> transcript = new ArrayList<>();
                for (ConversationTurn turn : checkpoint.transcript) {
                    if (turn.kind != ConversationTurn.Kind.TOOL_CALLS) { transcript.add(turn); continue; }
                    List<ModelReply.Call> calls = new ArrayList<>();
                    for (ModelReply.Call call : turn.toolCalls) calls.add(new ModelReply.Call(
                            wrongCall ? "different-call" : call.id,wrongCall ? call.name : "different-tool",call.arguments));
                    transcript.add(ConversationTurn.toolCalls(turn.content,calls));
                }
                Map<String,String> lifecycle = new LinkedHashMap<>(checkpoint.toolLifecycle);
                if (wrongCall) { lifecycle.remove("call-one"); lifecycle.put("different-call","STARTED"); }
                return new CoreAgentLoop.Checkpoint(transcript,checkpoint.appliedIncomingIds,lifecycle,
                        checkpoint.genuineUserIndex,checkpoint.incorporations);
            });
            String mismatch = wrongCall ? "different call" : "different tool";
            assertNotNull(mismatch,h.confirmed.get());
            assertEquals(mismatch,0,h.dispatches.get()); assertNull(mismatch,h.witness.get());
        }
    }

    @Test public void legitimateCoercionLinksRawCheckpointToExactEffectiveArguments() throws Exception {
        Harness h = run(args("7"),"integer",null,false);
        assertEquals(1,h.dispatches.get()); assertTrue(h.executed.get().get("value") instanceof Number);
        assertEquals(7,((Number)h.executed.get().get("value")).intValue());
        ModelReply.Call stored = h.confirmed.get().loop.transcript.stream().flatMap(t -> t.toolCalls.stream()).findFirst().get();
        assertEquals("7",stored.arguments.get("value"));
    }

    @Test public void numericBoxingAndJsonNullRoundTripDoNotChangeEffectiveValues() throws Exception {
        Map<String,Object> nested = new LinkedHashMap<>(); nested.put("integerLookingDouble",1.0d); nested.put("nullable",null);
        Harness h = run(args(nested),"object",null,false);
        assertEquals(1,h.dispatches.get());
        Map<?,?> effective = (Map<?,?>)h.executed.get().get("value");
        assertTrue(effective.get("integerLookingDouble") instanceof Double); assertTrue(effective.containsKey("nullable"));
        assertNull(effective.get("nullable"));
    }

    @Test public void nestedProviderMutationAfterSaveCannotChangeExecutedArguments() throws Exception {
        List<Object> nested = new ArrayList<>(Collections.singletonList("original"));
        Harness h = run(args(nested),"array",(prepared,confirmed) -> nested.set(0,"later"),false);
        assertEquals(1,h.dispatches.get()); assertEquals(Collections.singletonList("original"),h.executed.get().get("value"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>)h.executed.get().get("value")).add("bad"));
    }

    @Test public void providerMutationDuringValidationCannotPairNewRawWithOldEffective() throws Exception {
        Map<String,Object> nested = args("original");
        AtomicBoolean mutated = new AtomicBoolean();
        Harness h = run(args(nested),"object",null,false,false,() -> {
            nested.put("value","changed during validation"); mutated.set(true);
        });
        assertTrue(mutated.get()); assertNotNull(h.confirmed.get());
        assertEquals("Changed raw checkpoint must not attest the pre-validation effective snapshot",0,h.dispatches.get());
        assertNull(h.witness.get());
    }

    @Test public void publicConfirmedLoopMutationCannotRewriteVerifiedPayload() throws Exception {
        Map<String,Object> nested = args("original");
        Harness h = run(args(nested),"object",(prepared,confirmed) -> {
            ModelReply.Call call = confirmed.loop.transcript.stream().flatMap(t -> t.toolCalls.stream()).findFirst().get();
            assertThrows(UnsupportedOperationException.class,
                    () -> ((Map<String,Object>)call.arguments.get("value")).put("value","tampered"));
        },false);
        assertEquals(1,h.dispatches.get());
        assertEquals("original",((Map<?,?>)h.executed.get().get("value")).get("value"));
        ModelReply.Call verified = h.confirmed.get().loopForEvidence().transcript.stream().flatMap(t -> t.toolCalls.stream()).findFirst().get();
        assertEquals("original",((Map<?,?>)verified.arguments.get("value")).get("value"));
    }

    @Test public void postRenameSyncFailureCannotSupplyAnInvocationWitness() throws Exception {
        Harness h = run(args("plain"),"string",null,true);
        assertEquals(0,h.dispatches.get()); assertNull(h.witness.get()); assertNull(h.confirmed.get());
    }

    @Test public void discardedSnapshotCannotBeUsedAtDispatch() throws Exception {
        Harness h = run(args("plain"),"string",(prepared,confirmed) -> prepared.discard(),false);
        assertEquals(0,h.dispatches.get()); assertNull(h.witness.get());
    }

    @Test public void cancellationAfterStartedBeforeInvokeDiscardsPreparedSecretReferences() throws Exception {
        Harness h = run(args("plain"),"string",null,false,true);
        assertNotNull(h.prepared.get()); assertEquals(0,h.dispatches.get());
        assertThrows(IllegalStateException.class, () -> h.prepared.get().arguments());
    }

    @Test public void normalRegistryCopiesPreserveProviderButDelegatedScopeDropsIt() {
        AtomicInteger attempts = new AtomicInteger();
        CoreTool tool = new CoreTool() {
            final ToolSpec spec = new ToolSpec("probe","test","Probe","test",ToolSpec.Status.IMPLEMENTED,Collections.emptyMap(),Collections.emptyList());
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) { return CoreToolResult.success("read"); }
        };
        CoreToolRegistry base = new CoreToolRegistry(Collections.singletonList(tool)).withInvocationSourceProvider((actual,call,owner) -> {
            attempts.incrementAndGet(); throw new IllegalStateException("fixture requires prepared source");
        });
        for (CoreToolRegistry copy : Arrays.asList(base.subset(Collections.singletonList("probe")),base.with(Collections.emptyList()),base.withInvocationGuard(() -> {}))) {
            assertFalse(copy.invoke("probe",Collections.emptyMap(),CancellationToken.cancellable(),null,"call").success);
        }
        assertEquals(3,attempts.get());
        assertTrue(base.forDelegatedAgent().invoke("probe",Collections.emptyMap(),CancellationToken.cancellable(),null,"child-call").success);
        assertEquals(3,attempts.get());
    }

    @Test public void noSnapshotDirectInvocationCannotBorrowTheLastConfirmedCall() throws Exception {
        Harness h = run(args("plain"),"string",null,false);
        CoreToolResult replay = h.registry.get().invoke("probe",args("plain"),CancellationToken.cancellable(),null,"call-one");
        assertFalse(replay.success); assertEquals(1,h.dispatches.get());
    }
}

package com.jarvys.agent;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CentralInvocationSourceTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();
    private Context context() throws Exception {
        File files = fixture.newFilesDirectory();
        fixture.localRunStore(files);
        return new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); fail("Expected provenance rejection"); }
        catch (IllegalStateException | IllegalArgumentException expected) { }
    }
    private static <T> T lease(CentralInvocationWitness witness, Callable<T> callback) {
        try { return witness.withCurrentWorkspaceTransaction(callback); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test public void exactCommittedSourceExistsWithMemoryOffAndMetadataSurvivesSeal() throws Exception {
        Context context = context();
        MemoryWriteGate gate = MemoryWritePolicy.forContext(context).gate();
        boolean prior = gate.isEnabled();
        MemoryWritePolicy.forContext(context).setEnabledBlocking(false);
        try {
            CentralInvocationWitness witness = CentralInvocationTestSource.runWithWitness(context,(invocation,source) -> {
                assertSame(source,invocation.centralSource()); source.requireInvocationCurrent();
                CentralInvocationSource value = source.source();
                assertEquals(WorkspaceIdentity.CENTRAL_SESSION,value.ownerCentralSessionId);
                assertEquals("probe-call",value.sourceCallId);
                assertEquals(com.jarvys.agent.crew.CrewProcessIdentity.ID,value.sourceProcessId);
                assertEquals(value.storageGeneration,UUID.fromString(value.storageGeneration).toString());
                assertNotEquals(StorageGeneration.NAME,value.storageGeneration);
                assertEquals(value.sourceBatchId,ChatDatabaseBridge.getInstance(context)
                        .readEpisodeToolSourceSync(value.ownerCentralSessionId,value.sourceBatchId,value.sourceCallId).getBatchId());
                assertFalse(gate.isEnabled());
                rejects(source::requireInsideCurrentLease);
                lease(source,() -> { source.requireInsideCurrentLease(); return null; });
            });
            assertNotNull(witness.source()); rejects(witness::requireInvocationCurrent);
            rejects(() -> lease(witness,() -> null));
        } finally { MemoryWritePolicy.forContext(context).setEnabledBlocking(prior); }
    }

    @Test public void repeatedProviderCallIdsAcrossRunsNeverAdoptPreviousBatchOrOwner() throws Exception {
        Context context = context();
        CentralInvocationSource first = CentralInvocationTestSource.runWithWitness(context,(i,w) -> {}).source();
        CentralInvocationSource second = CentralInvocationTestSource.runWithWitness(context,(i,w) -> {}).source();
        assertEquals(first.sourceCallId,second.sourceCallId);
        assertNotEquals(first.sourceBatchId,second.sourceBatchId);
        assertNotEquals(first.sourceMessageId,second.sourceMessageId);
        assertNotEquals(first.sourceRunId,second.sourceRunId);
        assertEquals(first.storageGeneration,second.storageGeneration);
    }

    @Test public void foreignOrDuplicateBindingCannotTransferAnOperationalSource() throws Exception {
        Context context = context();
        CentralInvocationTestSource.runWithWitness(context,(invocation,witness) -> {
            EffectInvocation foreign = new EffectInvocation(admission -> {});
            rejects(() -> foreign.bindCentralSource(witness));
            rejects(() -> invocation.bindCentralSource(witness));
            assertNull(foreign.centralSource()); assertSame(witness,invocation.centralSource());
            witness.requireInvocationCurrent();
        });
    }

    @Test public void failedCheckpointPublishesNeitherSourceNorDispatch() throws Exception {
        Context context = context(); AtomicInteger calls = new AtomicInteger();
        MainChatTranscriptStore.checkpointStagedHookForTests = rows -> { throw new IllegalStateException("fixture checkpoint failure"); };
        try {
            try { CentralInvocationTestSource.runWithWitness(context,(i,w) -> calls.incrementAndGet()); fail("Checkpoint must fail"); }
            catch (CoreAgentLoop.CheckpointFailure expected) { }
            assertEquals(0,calls.get());
        } finally { MainChatTranscriptStore.checkpointStagedHookForTests = null; }
    }

    @Test public void missingCallAndMismatchedOwnerOrRunCannotFallbackToLatest() throws Exception {
        Context context = context();
        LocalRunStore store = new LocalRunStore(context);
        String owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","Exact source");
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(context.getFilesDir(),WorkspaceIdentity.CENTRAL_SESSION,store);
        EffectInvocation invocation = new EffectInvocation(a -> {});
        CoreTool probe = probe(i -> fail("No checkpoint means no dispatch"));
        rejects(() -> transcript.centralSourceProvider(owner,"run").bind(probe,"missing",invocation));
        assertNull(invocation.centralSource());
        CentralInvocationTestSource.runWithWitness(context,(i,w) -> {
            CentralInvocationSource s = w.source();
            ChatDatabaseBridge bridge = ChatDatabaseBridge.getInstance(context);
            rejects(() -> bridge.captureCentralInvocationSync(s.ownerCentralSessionId,"wrong-owner",s.sourceBatchId,s.sourceCallId,"source_probe",s.sourceRunId,s.sourceProcessId));
            rejects(() -> bridge.captureCentralInvocationSync(s.ownerCentralSessionId,s.sourceMessageId,s.sourceBatchId,"missing-call","source_probe",s.sourceRunId,s.sourceProcessId));
            rejects(() -> bridge.captureCentralInvocationSync(s.ownerCentralSessionId,s.sourceMessageId,s.sourceBatchId,s.sourceCallId,"wrong-tool",s.sourceRunId,s.sourceProcessId));
        });
        runMismatch(context, false);
        runMismatch(context, true);
    }
    private void runMismatch(Context context, boolean ownerMismatch) {
        LocalRunStore store = new LocalRunStore(context);
        String owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","New source");
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(context.getFilesDir(),WorkspaceIdentity.CENTRAL_SESSION,store);
        AtomicInteger dispatched = new AtomicInteger();
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(probe(i -> dispatched.incrementAndGet())))
                .withInvocationSourceProvider(transcript.centralSourceProvider(ownerMismatch ? "wrong-owner" : owner,ownerMismatch ? "run" : "wrong-run"));
        AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop loop = loop(registry,turns);
        transcript.attach(loop,Collections.emptyList(),owner,"run");
        loop.run("Run",Collections.emptyList(),Collections.emptyList(),CancellationToken.cancellable(),null,owner,"run");
        assertEquals(0,dispatched.get());
    }
    private static CoreTool probe(java.util.function.Consumer<EffectInvocation> body) {
        return new CoreTool() {
            @Override public ToolSpec declaration() { return new ToolSpec("source_probe","test","probe","test",ToolSpec.Status.IMPLEMENTED,Collections.emptyMap(),Collections.emptyList()); }
            @Override public CoreToolResult execute(Map<String,Object> arguments,CancellationToken token) { throw new AssertionError("Context required"); }
            @Override public CoreToolResult execute(Map<String,Object> arguments,CancellationToken token,ProgressListener listener,EffectInvocation invocation) {
                body.accept(invocation); return CoreToolResult.success("Observed");
            }
        };
    }
    private static CoreAgentLoop loop(CoreToolRegistry registry, AtomicInteger turns) {
        return new CoreAgentLoop((history,prompt,tools,token) -> turns.getAndIncrement() == 0
                ? new ModelReply("",Collections.singletonList(new ModelReply.Call("probe-call","source_probe",Collections.emptyMap())))
                : new ModelReply("Done",Collections.emptyList()),registry,"Observe",WorkspaceIdentity.CENTRAL_SESSION);
    }

    @Test public void registryCopiesKeepProviderButDelegatedAgentNeverReceivesCentralSource() throws Exception {
        Context context = context(); LocalRunStore store = new LocalRunStore(context);
        String owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","Copy source");
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(context.getFilesDir(),WorkspaceIdentity.CENTRAL_SESSION,store);
        AtomicReference<CentralInvocationWitness> seen = new AtomicReference<>();
        CoreToolRegistry base = new CoreToolRegistry(Collections.singletonList(probe(i -> seen.set(i.centralSource()))))
                .withInvocationSourceProvider(transcript.centralSourceProvider(owner,"run"));
        CoreToolRegistry copy = base.with(Collections.emptyList()).subset(base.names()).forRequester("tester","blue").withoutAttachments()
                .withInvocationGuard(() -> {});
        CoreAgentLoop loop = loop(copy,new AtomicInteger()); transcript.attach(loop,Collections.emptyList(),owner,"run");
        loop.run("Run",Collections.emptyList(),Collections.emptyList(),CancellationToken.cancellable(),null,owner,"run");
        assertNotNull(seen.get()); seen.set(null);
        CoreToolRegistry delegated = base.forDelegatedAgent();
        assertTrue(delegated.names().contains("source_probe"));
        delegated.invoke("source_probe",Collections.emptyMap(),CancellationToken.uncancellable(),null,"independent-crew-call");
        assertNull(seen.get());
    }

    @Test public void newAttachmentInvalidatesLiveWitnessAndNeverAcceptsAnOldCheckpointCallback() throws Exception {
        Context context = context(); LocalRunStore store = new LocalRunStore(context);
        String owner = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","Original source");
        MainChatTranscriptStore transcript = new MainChatTranscriptStore(context.getFilesDir(),WorkspaceIdentity.CENTRAL_SESSION,store);
        AtomicBoolean verified = new AtomicBoolean();
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(probe(invocation -> {
            CentralInvocationWitness old = invocation.centralSource(); assertNotNull(old);
            CoreAgentLoop replacement = loop(new CoreToolRegistry(Collections.singletonList(probe(i -> {}))),new AtomicInteger());
            transcript.attach(replacement,Collections.emptyList(),owner,"replacement-run");
            rejects(old::requireInvocationCurrent);
            assertEquals("original-run",old.source().sourceRunId); verified.set(true);
        }))).withInvocationSourceProvider(transcript.centralSourceProvider(owner,"original-run"));
        CoreAgentLoop original = loop(registry,new AtomicInteger());
        transcript.attach(original,Collections.emptyList(),owner,"original-run");
        try {
            original.run("Run",Collections.emptyList(),Collections.emptyList(),CancellationToken.cancellable(),null,owner,"original-run");
            fail("Replaced attachment cannot checkpoint the old run");
        } catch (CoreAgentLoop.CheckpointFailure expected) { }
        assertTrue(verified.get());
        rejects(() -> transcript.committedCall("probe-call"));
    }

    @Test public void databaseCloseCannotCrossTheSourceTransactionLease() throws Exception {
        Context context = context();
        AtomicBoolean entered = new AtomicBoolean(), closed = new AtomicBoolean();
        AtomicReference<CentralInvocationWitness> retained = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        try {
            CentralInvocationTestSource.runWithWitness(context,(invocation,witness) -> {
                retained.set(witness);
                Runnable closeDatabase = com.jarvys.agent.chatdb.CentralSourceLifecycleTestSupport.currentDatabaseCloser(context);
                lease(witness,() -> {
                    entered.set(true);
                    ReentrantLock fence = ChatDatabaseBridge.connectionProjectionAnchor(context).getFence();
                    Thread closer = new Thread(() -> {
                        try { closeDatabase.run(); closed.set(true); }
                        catch (Throwable failure) { closeFailure.set(failure); }
                    },"source-close-fixture");
                    closer.setDaemon(true); closeThread.set(closer); closer.start();
                    long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while (!fence.hasQueuedThread(closer)) {
                        if (closed.get() || closeFailure.get()!=null || System.nanoTime()>=deadline)
                            throw new AssertionError("Database close did not wait behind exact source lease",closeFailure.get());
                        Thread.yield();
                    }
                    witness.requireInsideCurrentLease(); assertFalse(closed.get());
                    return null;
                });
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (!closed.get() && closeFailure.get()==null && System.nanoTime()<deadline) Thread.yield();
                assertNull(closeFailure.get()); assertTrue(closed.get());
                rejects(() -> lease(witness,() -> failTransaction()));
            });
        } catch (CoreAgentLoop.CheckpointFailure expectedAfterClosedDatabase) {
            // The tool result checkpoint uses the deliberately closed database; no recovery/replay is attempted.
        } finally {
            Thread closer = closeThread.get();
            if (closer != null) { closer.join(TimeUnit.SECONDS.toMillis(5)); assertFalse("Closer must terminate after lease release",closer.isAlive()); }
        }
        assertTrue(entered.get()); assertTrue(closed.get()); assertNotNull(retained.get().source());
    }
    private static Object failTransaction() { throw new AssertionError("Stale source cannot enter another workspace transaction"); }
}

package com.jarvys.agent;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.chatdb.*;
import com.jarvys.agent.coding.*;
import com.jarvys.agent.crew.*;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real prepared Crew dispatch + Room/journal acceptance; no backend/model/network effects. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CodingAsyncRegistryTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();
    private final Map<Context,MemoryWriteGate.Ticket> semanticTickets = Collections.synchronizedMap(new IdentityHashMap<>());
    interface Producer { CoreToolResult run(Context context, EffectInvocation invocation, CancellationToken token) throws Exception; }
    static final class Run {
        final AtomicReference<EffectInvocation> invocation = new AtomicReference<>();
        final AtomicReference<CoreToolRegistry> registry = new AtomicReference<>();
        final AtomicInteger dispatches = new AtomicInteger(), syncCallbacks = new AtomicInteger();
        CoreAgentLoop.Checkpoint checkpoint;
        boolean cancelled;
        String toolContent() {
            StringBuilder text = new StringBuilder();
            int results = 0;
            for (ConversationTurn turn : checkpoint.transcript) if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                results++; text.append(turn.content);
            }
            assertTrue("This oracle requires an actual tool-result turn", results > 0);
            assertTrue("This oracle requires nonempty tool-result content", text.length() > 0);
            return text.toString();
        }
    }
    private Run run(boolean asynchronous, boolean coverage, Producer producer) throws Exception {
        File files = fixture.newFilesDirectory(); fixture.localRunStore(files);
        Context context = new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        semanticTickets.put(context,MemoryWritePolicy.forContext(context).captureTicket());
        String session = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString());
        new LocalRunStore(context).appendConversationMessage(session,"user","Synthetic async registry fixture");
        Run result = new Run(); AtomicReference<Throwable> failure = new AtomicReference<>();
        CoreTool tool = new CoreTool() {
            final ToolSpec spec = new ToolSpec("project_exec","test","Local async probe","test",ToolSpec.Status.IMPLEMENTED,
                    Collections.emptyMap(),Collections.emptyList());
            @Override public ToolSpec declaration(){return spec;}
            @Override public AccessClassification accessClassification(){return AccessClassification.WRITE;}
            @Override public CompletionMode completionMode(Map<String,Object> args){return asynchronous?CompletionMode.ASYNC_JOB:CompletionMode.SYNCHRONOUS;}
            @Override public boolean hasCompleteAsyncSubmissionCoverage(){return coverage;}
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token){throw new AssertionError("Invocation required");}
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token,ProgressListener progress,EffectInvocation invocation){
                result.dispatches.incrementAndGet();result.invocation.set(invocation);
                try { return producer.run(context,invocation,token); }
                catch(CancellationException cancelled){throw cancelled;}
                catch(Throwable error){failure.set(error);throw new AssertionError(error);}
            }
        };
        CoreToolRegistry base = new CoreToolRegistry(Arrays.asList(tool,CodingSubmissionTestSource.projectJobsCompanion())).withInvocationObserver(new CoreToolRegistry.InvocationObserver(){
            public void beforeDispatch(CoreTool t,String id,Map<String,Object> args){result.syncCallbacks.incrementAndGet();}
            public void afterDispatch(CoreTool t,String id,CoreToolResult r,Throwable f){result.syncCallbacks.incrementAndGet();}
            public void onEffectAdmission(CoreTool t,String id,EffectInvocation.Admission a){result.syncCallbacks.incrementAndGet();}
        });
        CrewRole role = new CrewRole("async-registry-fixture","Fixture","coding","Observe local submission",
                Arrays.asList("project_exec","project_jobs"),null,"Fixture",1,Collections.emptyList(),CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        AtomicInteger turns = new AtomicInteger();
        try(CrewManager manager = new CrewManager(session,base,(bot,crew)->{
            CoreToolRegistry exact = CoreAgentRuntime.withCrewInvocationSource(base,bot,crew);result.registry.set(exact);return exact;
        },(bot,tools,incoming)->new CoreAgentLoop((history,prompt,specs,token)->turns.getAndIncrement()==0
                ?new ModelReply("",Collections.singletonList(new ModelReply.Call("async-call","project_exec",Collections.emptyMap())))
                :new ModelReply("Done",Collections.emptyList()),tools,"",session,CorePromptBudget.standard(),null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED,incoming,null),null)){
            manager.configureProfiles(id->role,role.tools);
            manager.configureCheckpoints(new CrewCheckpointCoordinator(context,session,bot->bot.role));
            CrewManager.Bot bot=manager.spawn(role.id,"Exercise local submission",role.tools,"Fixture");
            CountDownLatch done=new CountDownLatch(1);
            Thread waiter=new Thread(()->{try{bot.awaitTermination();done.countDown();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"async-registry-await");
            waiter.setDaemon(true);waiter.start();
            try {assertTrue("Worker must terminate",done.await(30,TimeUnit.SECONDS));}finally{waiter.interrupt();}
            if(failure.get()!=null)throw new AssertionError(failure.get());
            result.checkpoint=bot.checkpoint();result.cancelled=bot.token.isCancellationRequested();
        }
        return result;
    }
    private CodingJobAcceptance accept(Context context,EffectInvocation invocation) throws Exception {
        CrewInvocationSource source=invocation.crewSource().source();
        CodingJobJournalStore journal=CodingJobJournalStore.forFilesRoot(context.getFilesDir());
        CodingJobOwnerLease owner=CodingJobOwnerLease.create(context.getFilesDir());
        try(CodingJobOwnerLease.Pin pin=owner.pinSubmission()){
            String job=UUID.randomUUID().toString();
            CodingJobSubmission submission=CodingJobSubmission.capture(invocation,job,UUID.randomUUID().toString(),
                    source.executionSessionId+"/"+source.botId+"/fixture","scope",source.scopeIdentity,
                    pin,Objects.requireNonNull(semanticTickets.get(context)),journal);
            ChatDatabaseBridge bridge=ChatDatabaseBridge.getInstance(context);
            CodingJobReservation reservation=bridge.reserveCodingJobSync(submission);
            CodingJobEvidence.Record record=new CodingJobEvidence.Record(reservation.binding(),1,
                    CodingJobEvidence.Phase.SUBMISSION_INTENT,true,null,null);
            CodingJobSnapshot snapshot=new CodingJobSnapshot(job,reservation.binding().owner,"scope",".","synthetic",
                    context.getFilesDir().getAbsolutePath(),source.executionSessionId,CodingJobSnapshot.State.LAUNCH_INTENT,
                    1L,null,null,null,"coding-job:"+job+"/log",null,0L,false);
            CodingJobJournalStore.Commit commit=journal.write(reservation,0,record,snapshot);
            CodingJobAcceptance acceptance=bridge.acceptCodingJobSync(commit);
            invocation.bindAcceptedSubmission(acceptance);
            return acceptance;
        } finally {assertTrue("No test worker remains pinned",owner.requestQuiesce().isDone());}
    }
    @Test public void actualPreparedSubmissionOwnsReceiptAndSkipsSynchronousFinalizers() throws Exception {
        AtomicReference<CodingJobAcceptance> accepted=new AtomicReference<>();
        Run r=run(true,true,(context,invocation,token)->{
            CodingJobAcceptance receipt=accept(context,invocation);accepted.set(receipt);
            CoreToolResult original=CoreToolResult.complete("accepted-only").withJobSubmissionReceipt(receipt);
            CoreToolResult copied=original.withContent("accepted-only");
            assertSame(receipt,copied.jobSubmissionReceipt);assertTrue(copied.completeContentRequired);
            assertNull(copied.effectReceipt);assertNull(copied.administrativeReceipt);assertNull(copied.persistenceEvidence);
            return copied;
        });
        assertEquals(1,r.dispatches.get());assertEquals(0,r.syncCallbacks.get());
        assertSame(accepted.get(),r.invocation.get().acceptedSubmission());
        assertTrue(r.toolContent().contains("accepted-only"));
        assertNull(accepted.get().submissionCommit().record().terminal);
        assertEquals(CodingJobEvidence.Phase.SUBMISSION_INTENT,accepted.get().submissionCommit().record().highestEffectPhase);
    }
    @Test public void missingCoverageRejectsBeforeProducerDespiteRealPreparedSource() throws Exception {
        Run r=run(true,false,(c,i,t)->{throw new AssertionError("Must not dispatch");});
        assertEquals(0,r.dispatches.get());assertEquals(0,r.syncCallbacks.get());
        assertTrue(r.toolContent().contains("not started"));
    }
    @Test public void directInvocationWithoutPreparedSourceCannotDispatch() throws Exception {
        Run r=run(true,true,(c,i,t)->CoreToolResult.failure("no-acceptance"));
        int before=r.dispatches.get();
        CoreToolResult result=r.registry.get().invoke("project_exec",Collections.emptyMap(),CancellationToken.cancellable());
        assertFalse(result.success);assertNull(result.jobSubmissionReceipt);assertEquals(before,r.dispatches.get());
    }
    @Test public void presentationSuccessWithoutDurableAcceptanceIsNotAccepted() throws Exception {
        Run r=run(true,true,(c,i,t)->CoreToolResult.success("unproven-success"));
        assertNull(r.invocation.get().acceptedSubmission());assertEquals(0,r.syncCallbacks.get());
        assertTrue(r.toolContent().contains("No durable job acceptance"));
        assertFalse(r.toolContent().contains("unproven-success"));
    }
    @Test public void foreignReceiptCannotBeAdoptedByAnotherLiveInvocation() throws Exception {
        AtomicReference<CodingJobAcceptance> foreign=new AtomicReference<>();
        run(true,true,(c,i,t)->{foreign.set(accept(c,i));return CoreToolResult.success("accepted");});
        Run other=run(true,true,(c,i,t)->CoreToolResult.success("foreign").withJobSubmissionReceipt(foreign.get()));
        assertNull(other.invocation.get().acceptedSubmission());assertEquals(0,other.syncCallbacks.get());
        assertTrue(other.toolContent().contains("foreign acceptance receipt"));
    }
    @Test public void sealedAcceptedInvocationCannotLeakReceiptIntoAnotherCallOrPrecancelledCall() throws Exception {
        Run r=run(true,true,(c,i,t)->{accept(c,i);return CoreToolResult.success("accepted");});
        EffectInvocation original=r.invocation.get();assertNotNull(original.acceptedSubmission());
        int count=r.dispatches.get();
        CoreToolResult next=r.registry.get().invoke("project_exec",Collections.emptyMap(),CancellationToken.cancellable(),null,"new-call",original);
        assertFalse(next.success);assertNull(next.jobSubmissionReceipt);assertEquals(count,r.dispatches.get());
        CancellationToken cancelled=CancellationToken.cancellable();cancelled.cancel();
        assertThrows(CancellationException.class,()->r.registry.get().invoke("project_exec",Collections.emptyMap(),cancelled,null,"cancelled-call",original));
        assertEquals(count,r.dispatches.get());
    }
    @Test public void synchronousToolCannotReturnAnotherJobsAdministrativeReceipt() throws Exception {
        AtomicReference<CodingJobAcceptance> foreign=new AtomicReference<>();
        run(true,true,(c,i,t)->{foreign.set(accept(c,i));return CoreToolResult.success("accepted");});
        Run sync=run(false,false,(c,i,t)->CoreToolResult.success("wrong-sync-success").withJobSubmissionReceipt(foreign.get()));
        assertTrue(sync.toolContent().contains("Synchronous tool returned asynchronous acceptance metadata"));
        assertFalse(sync.toolContent().contains("wrong-sync-success"));
    }
    @Test public void cancellationAfterAcceptancePreservesDurableJobIdentityWithoutCommandSuccess() throws Exception {
        Run r=run(true,true,(c,i,t)->{accept(c,i);t.cancel();throw new CancellationException("Synthetic after-accept cancellation");});
        assertTrue(r.cancelled);assertNotNull(r.invocation.get().acceptedSubmission());assertEquals(0,r.syncCallbacks.get());
        assertNull(r.invocation.get().acceptedSubmission().submissionCommit().record().terminal);
        assertEquals(1,r.dispatches.get());
    }
}

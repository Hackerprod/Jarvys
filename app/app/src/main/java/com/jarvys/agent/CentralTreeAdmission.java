package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.CentralUserRootCheckpoint;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.crew.CrewProcessIdentity;
import com.jarvys.agent.workspaces.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Native service capability. Root DTOs/source strings alone cannot authorize dispatch. */
public final class CentralTreeAdmission implements AutoCloseable {
    private final LocalRunStore store;
    private final CentralUserRootCheckpoint origin;
    private final CancellationToken token;
    private final String processId=CrewProcessIdentity.ID;
    private final Thread ownerThread=Thread.currentThread();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final boolean freshDelegation;
    private String rootId;
    private MainChatTranscriptStore.TreeExitReceipt exitReceipt;
    private Runnable unregisterStop=()->{};
    private final List<IncomingOwner> incomingOwners=new ArrayList<>();
    private static final class IncomingOwner {
        final CoreAgentLoop.IncomingMessage message; final String digest;
        IncomingOwner(CoreAgentLoop.IncomingMessage message,String digest) { this.message=message;this.digest=digest; }
    }
    private CentralTreeAdmission(LocalRunStore store,CentralUserRootCheckpoint origin,CancellationToken token,boolean fresh) {
        this.store=Objects.requireNonNull(store);this.origin=Objects.requireNonNull(origin);
        this.token=Objects.requireNonNull(token);this.freshDelegation=fresh;requireCurrent(token);
    }
    static CentralTreeAdmission captureUser(LocalRunStore store,String session,String id,CancellationToken token) {
        return new CentralTreeAdmission(store,store.captureCentralUserRoot(session,id),token,true);
    }
    public String storageGeneration(){return origin.getStorageGeneration();}
    public String ownerCentralSessionId(){return origin.getOwnerCentralSessionId();}
    public String originUserMessageId(){return origin.getOriginUserMessageId();}
    public String originDigest(){return origin.getOriginDigest();}
    public String cycleId(){return processId+":"+token.generation();}
    public String rootId(){return Objects.requireNonNull(rootId,"Tree root is not bound");}
    public String sourceReceipt(){return "user-root:"+UUID.nameUUIDFromBytes((storageGeneration()+"\u0000"+originUserMessageId()+"\u0000"+cycleId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public void requireInvocationCurrent(CancellationToken expected) {
        if(expected!=token || closed.get() || !processId.equals(CrewProcessIdentity.ID))
            throw new IllegalStateException("Central source owner changed");
        token.throwIfCancelled();
    }
    private void requireInsideLease(CancellationToken expected) {
        requireInvocationCurrent(expected);
        if(!origin.getLifecycle().fenceIdentity().isHeldByCurrentThread() || !origin.getLifecycle().isCurrentPure())
            throw new IllegalStateException("Tree origin database lifecycle changed");
    }
    public void requireCurrent(CancellationToken expected) {
        ReentrantLock fence=origin.getLifecycle().fenceIdentity();fence.lock();
        try {requireInsideLease(expected);} finally {fence.unlock();}
    }
    public <T> T withCurrentWorkspaceTransaction(CancellationToken expected,Callable<T> work) {
        ReentrantLock fence=origin.getLifecycle().fenceIdentity();fence.lock();
        try {requireInsideLease(expected);return work.call();}
        catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}
        finally {fence.unlock();}
    }
    public void revalidateSource(CancellationToken expected) {
        requireCurrent(expected);
        CentralUserRootCheckpoint current=store.captureCentralUserRoot(ownerCentralSessionId(),originUserMessageId());
        if(!storageGeneration().equals(current.getStorageGeneration()) || !originDigest().equals(current.getOriginDigest()))
            throw new IllegalStateException("Original USER source changed");
        requireCurrent(expected);
    }
    public void requireFreshDelegation(CancellationToken expected) {
        requireCurrent(expected);
        if(!freshDelegation || Thread.currentThread()!=ownerThread)
            throw new IllegalStateException("Fresh native USER execution is required for delegation");
    }
    private void bindRoot(String id) {if(rootId!=null)throw new IllegalStateException("Root cannot be rebound");rootId=Objects.requireNonNull(id);}
    private void registerStop(Context context) {
        unregisterStop=token.registerCancelAction(()->{if(token.wasStoppedByUser())WorkspaceTreeStops.latchRoot(context,rootId(),cycleId());});
    }
    static CentralTreeAdmission beginUser(Context context,LocalRunStore store,String session,String id,CancellationToken token) {
        CentralTreeAdmission authority=captureUser(store,session,id,token);
        try(WorkspaceRegistry registry=WorkspaceTreeAdmissionGateway.openForNewAdmission(context,authority,token)) {
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
            TreeOrigin origin=new TreeOrigin(authority.storageGeneration(),authority.ownerCentralSessionId(),authority.originUserMessageId(),authority.originDigest());
            TreeRoot root=authority.withCurrentWorkspaceTransaction(token,()->trees.beginRoot(origin,authority.sourceReceipt(),authority.cycleId()));
            TreeNode node=trees.getNode(root.getRootId(),root.getRootId());
            if(root.getAdmissionsClosed() || node==null || !node.getCycleId().equals(authority.cycleId()))
                throw new IllegalStateException("Existing root requires an exact native continuation; closed roots never reopen");
            authority.bindRoot(root.getRootId());authority.registerStop(context);authority.requireCurrent(token);return authority;
        } catch(RuntimeException failure){authority.close();throw failure;}
    }
    static CentralTreeAdmission captureClaimed(Context context,LocalRunStore store,ConversationInput input,CancellationToken token) {
        String claim=WorkspaceContinuationDelivery.claimedRunId(context,input,token);
        ConversationInput.Provenance source=Objects.requireNonNull(input.provenance);
        TreeRoot root=WorkspaceTreeAdmissionGateway.findOriginalRoot(context,source.workspaceId,source.assignmentId);
        if(root==null)return null; // Historical P1 is not silently enrolled.
        if(root.getAdmissionsClosed())throw new IllegalStateException("Original root is closed");
        TreeOrigin saved=root.getOrigin();
        CentralTreeAdmission authority=new CentralTreeAdmission(store,store.captureCentralUserRoot(saved.getOwnerCentralSessionId(),saved.getOriginUserMessageId()),token,false);
        try(WorkspaceRegistry registry=new WorkspaceRegistry(context)) {
            if(!saved.getStorageGeneration().equals(authority.storageGeneration()) || !saved.getOriginDigest().equals(authority.originDigest()))
                throw new IllegalStateException("Claimed continuation original source changed");
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
            TreeNode node=Objects.requireNonNull(trees.getNode(root.getRootId(),root.getRootId()));
            if(!claim.equals(WorkspaceContinuationDelivery.claimedRunId(context,input,token)))throw new IllegalStateException("P1 claim changed");
            authority.withCurrentWorkspaceTransaction(token,()->trees.attachCentralExecution(TreeNodeFence.Companion.of(node),authority.cycleId(),authority.sourceReceipt(),claim,true,source.assignmentId));
            authority.bindRoot(root.getRootId());authority.registerStop(context);authority.requireCurrent(token);return authority;
        } catch(RuntimeException failure){authority.close();throw failure;}
    }
    void bindIncomingOwner(Context context,List<CoreAgentLoop.IncomingMessage> messages,CancellationToken expected) {
        requireCurrent(expected);
        if(Thread.currentThread()!=ownerThread || exitReceipt!=null)throw new IllegalStateException("Native owner callback is no longer current");
        List<IncomingOwner> confirmed=new ArrayList<>();
        for(CoreAgentLoop.IncomingMessage message:messages)if(message!=null && "main_chat".equals(message.sourceKind))
            confirmed.add(new IncomingOwner(message,ChatDatabaseBridge.getInstance(context).confirmCentralTreeIncomingSync(ownerCentralSessionId(),message)));
        requireCurrent(expected);incomingOwners.addAll(confirmed);
    }
    void confirmExit(MainChatTranscriptStore.TreeExitReceipt receipt,CancellationToken expected) {
        requireCurrent(expected);
        if(receipt==null || exitReceipt!=null)throw new IllegalStateException("Exit receipt cannot be replaced");
        for(IncomingOwner owner:incomingOwners)if(!receipt.committedIncomingIds.contains(owner.message.id))
            throw new IllegalStateException("Final owner absent from confirmed checkpoint");
        exitReceipt=receipt;
    }
    void finishSuccessful(Context context,String assistantId) {
        requireCurrent(token);
        MainChatTranscriptStore.TreeExitReceipt receipt=Objects.requireNonNull(exitReceipt,"Confirmed native exit required");
        String finalOwner=receipt.ownerMessageId;StringBuilder chain=new StringBuilder();
        for(IncomingOwner owner:incomingOwners){
            String current=ChatDatabaseBridge.getInstance(context).confirmCentralTreeIncomingSync(ownerCentralSessionId(),owner.message);
            if(!current.equals(owner.digest) || !receipt.committedIncomingIds.contains(owner.message.id))throw new IllegalStateException("Final owner source changed");
            finalOwner=owner.message.id;chain.append(':').append(finalOwner).append(':').append(current);
        }
        ChatDatabaseBridge.getInstance(context).confirmCentralTreeExitSync(ownerCentralSessionId(),assistantId,finalOwner,receipt.runId);
        revalidateSource(token);
        try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);TreeNode node=Objects.requireNonNull(trees.getNode(rootId(),rootId()));
            if(!cycleId().equals(node.getCycleId()))throw new IllegalStateException("Exit cycle changed");
            TreeNode finished=withCurrentWorkspaceTransaction(token,()->trees.finishCentral(TreeNodeFence.Companion.of(node),receipt.digest+":"+assistantId+chain,false));
            if(finished.getState()==TreeNodeState.WAITING) {
                java.util.LinkedHashSet<String> sessions=new java.util.LinkedHashSet<>();
                for(TreeNode child:trees.children(rootId(),rootId())) if(child.getWorkspaceId()!=null)
                    sessions.add(WorkspaceIdentity.executionSessionId(child.getWorkspaceId()));
                // Metadata nudge only: schedule retains exact process-local P1 permits, never creates one.
                for(String session:sessions) WorkspaceResultDelivery.schedule(context,session);
            }
        }
        terminalEvidence=new TerminalEvidence(this,assistantId,receipt.runId,finalOwner,sha256(receipt.digest+":"+assistantId+":"+finalOwner+":"+chain));
    }
    void acknowledgeClaimed(Context context,ConversationInput input) {
        requireCurrent(token);String claim=WorkspaceContinuationDelivery.claimedRunId(context,input,token);
        ConversationInput.Provenance source=Objects.requireNonNull(input.provenance);
        CrewCheckpointStore.ConfirmedSnapshot saved=Objects.requireNonNull(new CrewCheckpointStore(context.getFilesDir()).confirmDurable(
                WorkspaceIdentity.executionSessionId(source.workspaceId),source.botId,"native-workspace:"+source.workspaceId));
        if(!"ACKNOWLEDGED".equals(CrewCheckpointStore.continuation(saved).dispatchState()) || !"incorporated".equals(store.readConversationInputState(ownerCentralSessionId(),input.messageId)))
            throw new IllegalStateException("Exact P1 incorporation is not durable");
        if(!claim.equals(WorkspaceContinuationDelivery.claimedRunId(context,input,token)))throw new IllegalStateException("P1 claim changed");
        try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
            withCurrentWorkspaceTransaction(token,()->{trees.recordCentralIncorporated(rootId(),source.assignmentId,claim,saved.payloadSha256);return null;});
        }
    }
    /** Denial-only exit remains valid after deadline cancellation; never revives the token or acknowledges effects. */
    void failOwnedRoot(Context context,String reason) {
        if(rootId==null || closed.get())return;
        if(Thread.currentThread()!=ownerThread || !processId.equals(CrewProcessIdentity.ID))
            throw new IllegalStateException("Failed Central execution owner changed");
        ReentrantLock fence=origin.getLifecycle().fenceIdentity();fence.lock();
        try {
            if(!origin.getLifecycle().isCurrentPure())throw new IllegalStateException("Failed root lifecycle changed");
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)) {
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
                TreeRoot root=Objects.requireNonNull(trees.getRoot(rootId()));
                TreeNode node=Objects.requireNonNull(trees.getNode(rootId(),rootId()));
                if(!cycleId().equals(node.getCycleId()) || !storageGeneration().equals(root.getOrigin().getStorageGeneration())
                        || !originDigest().equals(root.getOrigin().getOriginDigest())
                        || !ownerCentralSessionId().equals(root.getOrigin().getOwnerCentralSessionId())
                        || !originUserMessageId().equals(root.getOrigin().getOriginUserMessageId()))
                    throw new IllegalStateException("Failed root canonical owner changed");
                // Exact owner first; close enqueue before durable mutation, including a failed transaction.
                WorkspaceTreeStops.latchRoot(context,rootId(),cycleId());
                trees.failCentral(TreeNodeFence.Companion.of(node),reason,"terminal:"+reason+":"+sourceReceipt());
            }
        } finally {fence.unlock();}
    }
    void stopOwnedRoot(Context context) {
        if(!token.wasStoppedByUser() || rootId==null || closed.get())return;
        ReentrantLock fence=origin.getLifecycle().fenceIdentity();fence.lock();
        try {
            if(!origin.getLifecycle().isCurrentPure())throw new IllegalStateException("Stopped root lifecycle changed");
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);TreeNode node=Objects.requireNonNull(trees.getNode(rootId(),rootId()));
                if(cycleId().equals(node.getCycleId()))trees.finishCentral(TreeNodeFence.Companion.of(node),"stop:"+sourceReceipt(),true);
            }
        } finally {fence.unlock();}
    }
    /** Only an exact native STARTED submission can create a fresh request root. */
    static CentralTreeAdmission beginSubmission(Context context,LocalRunStore store,
            CentralSubmissionAdmission.ClaimAuthority claim,CancellationToken token) {
        if(!CentralAvailabilityPolicy.admissionEnabled())throw new IllegalStateException("Central availability disabled");
        claim.requireCurrent(claim.getPermit());
        if(claim.getTokenGeneration()!=token.generation())throw new IllegalStateException("Submission token changed");
        ChatDatabaseBridge.getInstance(context).loadCentralSubmissionContextSync(claim);
        return beginUser(context,store,claim.getPermit().getSessionId(),claim.getPermit().getUserMessageId(),token);
    }

    public static final class TerminalEvidence {
        private final CentralTreeAdmission authority;
        private final String assistantId,runId,finalOwner,chainDigest;
        private TerminalEvidence(CentralTreeAdmission authority,String assistantId,String runId,String finalOwner,String chainDigest){
            this.authority=authority;this.assistantId=assistantId;this.runId=runId;this.finalOwner=finalOwner;this.chainDigest=chainDigest;
        }
        public String getAssistantId(){return assistantId;}
        public String getChainDigest(){return chainDigest;}
        public com.jarvys.agent.connectors.ConnectionStateCoordinator.DestinationEvidence getLifecycle(){return authority.origin.getLifecycle();}
        public String getRunId(){return runId;}
        public String getFinalOwnerMessageId(){return finalOwner;}
        public String getOriginalUserMessageId(){return authority.originUserMessageId();}
        public String getStorageGeneration(){return authority.storageGeneration();}
        public String getOriginDigest(){return authority.originDigest();}
        public void requireOwnerPure(CancellationToken token){
            authority.requireInvocationCurrent(token);
            if(authority.terminalEvidence!=this)throw new IllegalStateException("Terminal evidence changed");
        }
        public void requireCurrent(CancellationToken token){
            authority.requireCurrent(token);
            if(authority.terminalEvidence!=this)throw new IllegalStateException("Terminal evidence changed");
        }
    }
    private TerminalEvidence terminalEvidence;
    public TerminalEvidence getTerminalEvidence(){return Objects.requireNonNull(terminalEvidence,"Confirmed terminal evidence required");}

    /** Display-only snapshot. A row selection cannot dispatch without the fresh native action below. */
    public static final class SuspensionRecoveryDescriptor {
        private final CentralResumeRecord row;
        private final TreeOrigin origin;
        private SuspensionRecoveryDescriptor(CentralResumeRecord row,TreeOrigin origin){this.row=row;this.origin=origin;}
        public String getSessionId(){return origin.getOwnerCentralSessionId();}
        public String getOriginalUserMessageId(){return origin.getOriginUserMessageId();}
        public String getResumeId(){return row.getResumeId();}
        public long getRevision(){return row.getRevision();}
        public String getSourceDigest(){return row.getSourceDigest();}
        public String getRootCycle(){return row.getParentCycle();}
        public long getStopEpoch(){return row.getStopEpoch();}
    }
    public static List<SuspensionRecoveryDescriptor> listRecoverableSuspensions(Context context,String session){
        if(!WorkspaceIdentity.CENTRAL_SESSION.equals(session) || !CentralAvailabilityPolicy.admissionEnabled())return Collections.emptyList();
        List<SuspensionRecoveryDescriptor> result=new ArrayList<>();
        try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
            if(registry.schemaVersion()!=3)return Collections.emptyList();
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
            for(CentralResumeRecord row:trees.preparedCentralSuspensions()){
                TreeRoot root=Objects.requireNonNull(trees.getRoot(row.getRootId()));
                TreeNode node=Objects.requireNonNull(trees.getNode(row.getRootId(),row.getNodeId()));
                if(!root.getAdmissionsClosed() && !root.getWakesClosed() && !node.getAdmissionsClosed() && !node.getWakesClosed() &&
                    node.getState()==TreeNodeState.WAITING && node.getControlIntent()==null && node.getCycleId().equals(row.getParentCycle()) &&
                    node.getRevision()==row.getParentRevision() && node.getStopEpoch()==row.getStopEpoch())
                    result.add(new SuspensionRecoveryDescriptor(row,root.getOrigin()));
            }
        }
        return Collections.unmodifiableList(result);
    }
    public static SuspensionPermit recoverSuspension(Context context,AttachmentDraftViewModel.NativeSuspensionRecoveryAction action){
        if(!CentralAvailabilityPolicy.admissionEnabled())throw new IllegalStateException("Central recovery disabled");
        SuspensionRecoveryDescriptor selected=Objects.requireNonNull(action.getDescriptor());
        action.requireCurrent(selected.getSessionId(),selected.getResumeId(),selected.getRevision());
        LocalRunStore store=new LocalRunStore(context);
        CentralUserRootCheckpoint source=store.captureCentralUserRoot(selected.getSessionId(),selected.getOriginalUserMessageId());
        if(!source.getStorageGeneration().equals(selected.origin.getStorageGeneration()) || !source.getOriginDigest().equals(selected.origin.getOriginDigest()))
            throw new IllegalStateException("Recovery original USER source changed");
        com.jarvys.agent.chatdb.CentralSubmissionSnapshot submission=store.readCentralSubmissionForSource(source);
        if(submission!=null && !"STARTED".equals(submission.getState()) && !"COMPLETED".equals(submission.getState()))
            throw new IllegalStateException("Original submission cannot authorize suspension recovery");
        final MainChatTranscriptStore.ConfirmedCentralYield restored;
        try {
            org.json.JSONObject certificate=new org.json.JSONObject(selected.row.getCertificate());
            CrewCheckpointStore.ConfirmedCentralYieldSnapshot snapshot=new CrewCheckpointStore(context.getFilesDir()).readCentralYieldConfirmed(
                selected.getSessionId(),certificate.getString("checkpointReferenceId"),certificate.getString("checkpointDigest"),source.getStorageGeneration());
            restored=MainChatTranscriptStore.recoveredYield(Objects.requireNonNull(snapshot),selected.row.getCertificate());
        }catch(org.json.JSONException failure){throw new IllegalStateException("Recovery certificate malformed",failure);}
        if(!restored.rootId().equals(selected.row.getRootId()) || !restored.oldCycleId().equals(selected.getRootCycle()))
            throw new IllegalStateException("Recovery checkpoint ownership changed");
        ChatDatabaseBridge.getInstance(context).validateCentralYieldSourcesSync(source,restored.retainedCheckpoint().transcript);
        action.requireCurrent(selected.getSessionId(),selected.getResumeId(),selected.getRevision());
        java.util.concurrent.locks.ReentrantLock lock=source.getLifecycle().fenceIdentity();lock.lock();
        try {
            if(!source.getLifecycle().isCurrentPure())throw new IllegalStateException("Recovery lifetime changed");
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                CentralResumeRecord recovered=new WorkspaceTreeStore(registry).recoverCentralSuspension(selected.row,action.getActionId(),CrewProcessIdentity.ID,
                    ()->action.requireCurrent(selected.getSessionId(),selected.getResumeId(),selected.getRevision()));
                return new SuspensionPermit(context,store,source,restored,recovered);
            }
        } finally {lock.unlock();}
    }

    /** Positive live permission minted only after confirmed checkpoint plus atomic native park. */
    public static final class SuspensionPermit {
        private final Context context;
        private final LocalRunStore store;
        private final CentralUserRootCheckpoint source;
        private final MainChatTranscriptStore.ConfirmedCentralYield confirmed;
        private final CentralResumeRecord parked;
        private final String process=CrewProcessIdentity.ID;
        private boolean consumed;
        private SuspensionPermit(Context context,LocalRunStore store,CentralUserRootCheckpoint source,
                MainChatTranscriptStore.ConfirmedCentralYield confirmed,CentralResumeRecord parked){
            this.context=context.getApplicationContext();this.store=store;this.source=source;this.confirmed=confirmed;this.parked=parked;
        }
        public String getResumeId(){return parked.getResumeId();}
        public String getSourceDigest(){return parked.getSourceDigest();}
        public String getRootCycle(){return parked.getParentCycle();}
        public long getRevision(){return parked.getRevision();}
        public void requireCurrent(){
            if(consumed || !process.equals(CrewProcessIdentity.ID))throw new IllegalStateException("Suspension permission no longer live");
            java.util.concurrent.locks.ReentrantLock lock=source.getLifecycle().fenceIdentity();lock.lock();
            try {if(!source.getLifecycle().isCurrentPure())throw new IllegalStateException("Suspension lifetime changed");}
            finally {lock.unlock();}
            CentralUserRootCheckpoint current=store.captureCentralUserRoot(source.getOwnerCentralSessionId(),source.getOriginUserMessageId());
            if(!current.getOriginDigest().equals(source.getOriginDigest()) || !current.getStorageGeneration().equals(source.getStorageGeneration()))
                throw new IllegalStateException("Suspension source changed");
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);CentralResumeRecord actual=trees.getCentralResume(getResumeId());
                if(actual==null || !"PREPARED".equals(actual.getState()) || actual.getRevision()!=parked.getRevision() || !actual.getSourceDigest().equals(getSourceDigest()))
                    throw new IllegalStateException("Suspension is no longer unclaimed");
                TreeRoot root=Objects.requireNonNull(trees.getRoot(parked.getRootId()));
                if(root.getAdmissionsClosed() || root.getWakesClosed())throw new IllegalStateException("Suspension root closed");
                TreeNode node=Objects.requireNonNull(trees.getNode(parked.getRootId(),parked.getNodeId()));
                if(node.getStopEpoch()!=parked.getStopEpoch() || !node.getCycleId().equals(getRootCycle()))
                    throw new IllegalStateException("Suspension STOP fence changed");
            }
        }
        public synchronized SuspensionClaim reserve(CancellationToken token,String runId){
            requireCurrent();token.throwIfCancelled();
            com.jarvys.agent.chatdb.CentralSubmissionSnapshot original=store.readCentralSubmissionForSource(source);
            String originalRun=original==null?null:original.getClaimRunId();
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
                CentralResumeRecord reserved=trees.reserveCentralSuspension(parked,process,runId,Long.toString(token.generation()),process+":"+token.generation());
                if(reserved==null)return null;
                consumed=true;
                return new SuspensionClaim(this,reserved,token,originalRun);
            } catch(RuntimeException failure){
                // An unconfirmed transaction outcome must never reuse this process permission.
                consumed=true;throw failure;
            }
        }
        public synchronized void invalidate(){consumed=true;}
        public void stopFromNativeBoundary(){
            WorkspaceTreeStops.latchRoot(context,parked.getRootId(),getRootCycle());
            synchronized(this){consumed=true;}
            java.util.concurrent.locks.ReentrantLock lock=source.getLifecycle().fenceIdentity();lock.lock();
            try {
                if(!source.getLifecycle().isCurrentPure())throw new IllegalStateException("Stopped suspension lifetime changed");
                try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){new WorkspaceTreeStore(registry).stopCentralResume(parked);}
            } finally {lock.unlock();}
        }
    }
    public static final class SuspensionClaim {
        private final SuspensionPermit permit;
        private final CentralResumeRecord reservation;
        private final CancellationToken token;
        private final String originalRun;
        private CentralTreeAdmission successor;
        private volatile boolean bound;
        private volatile boolean invalid;
        private String successorCertificate;
        private volatile CentralResumeRecord boundRecord;
        private SuspensionClaim(SuspensionPermit permit,CentralResumeRecord reservation,CancellationToken token,String originalRun){
            this.permit=permit;this.reservation=reservation;this.token=token;this.originalRun=originalRun;
        }
        public String resumeId(){return reservation.getResumeId();}
        public String rootId(){return reservation.getRootId();}
        public String cycleId(){return reservation.getSuccessorCycle();}
        public String runId(){return reservation.getOwnerRun();}
        public String sourceCheckpointDigest(){return permit.confirmed.checkpointDigest();}
        public String sourceCertificate(){return permit.confirmed.certificate();}
        public MainChatTranscriptStore.ConfirmedCentralYield confirmedYield(){return permit.confirmed;}
        public String getResumeId(){return resumeId();}
        public String getRunId(){return runId();}
        public String getOriginalRunId(){return originalRun;}
        public String getOriginalUserMessageId(){return permit.source.getOriginUserMessageId();}
        public String getStorageGeneration(){return permit.source.getStorageGeneration();}
        public String getOriginDigest(){return permit.source.getOriginDigest();}
        public com.jarvys.agent.connectors.ConnectionStateCoordinator.DestinationEvidence getLifecycle(){return permit.source.getLifecycle();}
        public String getSuccessorCertificateSha256(){if(!bound)throw new IllegalStateException("Resume not bound");return successorCertificate;}
        public void requireCurrent(CancellationToken expected){
            if(expected!=token || invalid || !permit.process.equals(CrewProcessIdentity.ID))throw new IllegalStateException("Suspension claimant changed");
            token.throwIfCancelled();
            WorkspaceTreeStops.requireRootAllowed(permit.context,rootId(),reservation.getParentCycle());
            WorkspaceTreeStops.requireRootAllowed(permit.context,rootId(),cycleId());
            java.util.concurrent.locks.ReentrantLock lock=getLifecycle().fenceIdentity();lock.lock();
            try{if(!getLifecycle().isCurrentPure())throw new IllegalStateException("Suspension lifetime changed");}finally{lock.unlock();}
        }
        public void requireOwnerPure(CancellationToken expected){
            if(expected!=token || invalid || !bound || successor==null || !permit.process.equals(CrewProcessIdentity.ID))
                throw new IllegalStateException("Suspension owner changed");
            successor.requireInvocationCurrent(expected);
        }
        public void requireBound(CancellationToken expected){
            requireCurrent(expected);if(!bound || successor==null)throw new IllegalStateException("Suspension successor not bound");
            successor.requireCurrent(expected);
            try(WorkspaceRegistry registry=new WorkspaceRegistry(permit.context)){
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);CentralResumeRecord current=trees.getCentralResume(resumeId());
                TreeRoot root=Objects.requireNonNull(trees.getRoot(rootId()));TreeNode node=Objects.requireNonNull(trees.getNode(rootId(),rootId()));
                if(current==null || !current.equals(boundRecord) || !"INCORPORATED".equals(current.getState()) || !Objects.equals(current.getOwnerProcess(),permit.process) ||
                    !Objects.equals(current.getSuccessorCycle(),cycleId()) || !Objects.equals(current.getOwnerRun(),runId()) ||
                    !Objects.equals(current.getOwnerToken(),Long.toString(token.generation())) || !Objects.equals(current.getSourceDigest(),reservation.getSourceDigest()) ||
                    root.getAdmissionsClosed() || root.getWakesClosed() || node.getAdmissionsClosed() || node.getWakesClosed() ||
                    !cycleId().equals(node.getCycleId()) || node.getStopEpoch()!=reservation.getStopEpoch())
                    throw new IllegalStateException("Bound suspension source/owner revoked");
            }
        }
        public void requireTerminalEvidence(CancellationToken expected,TerminalEvidence evidence){
            requireCurrent(expected);
            if(!bound || successor==null || evidence==null || evidence.authority!=successor)
                throw new IllegalStateException("Terminal suspension owner changed");
            evidence.requireCurrent(expected);
            try(WorkspaceRegistry registry=new WorkspaceRegistry(permit.context)){
                CentralResumeRecord actual=new WorkspaceTreeStore(registry).getCentralResume(resumeId());
                if(!Objects.equals(actual,boundRecord))throw new IllegalStateException("Terminal resume proof changed");
            }
        }
        public boolean isBound(){return bound && !invalid;}
        public void markUncertain(Context context,String reason){
            if(reason==null || reason.isEmpty())throw new IllegalArgumentException("Uncertainty reason required");
            invalid=true;
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                new WorkspaceTreeStore(registry).uncertainCentralResume(reservation);
            }
        }
        public CentralTreeAdmission attachSuccessorOwner(Context context,CancellationToken expected){
            requireCurrent(expected);if(successor!=null)throw new IllegalStateException("Successor owner already attached");
            CentralTreeAdmission authority=new CentralTreeAdmission(permit.store,permit.source,token,false);
            authority.bindRoot(rootId());authority.registerStop(context);successor=authority;return authority;
        }
        public void bindAndIncorporate(Context context,MainChatTranscriptStore.ConfirmedCentralSuccessor receipt,CancellationToken expected){
            requireCurrent(expected);
            if(successor==null || receipt.original()!=permit.confirmed || !receipt.resumeId().equals(resumeId()) || !receipt.runId().equals(runId()))
                throw new IllegalStateException("Successor receipt changed");
            successor.revalidateSource(token);
            try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
                WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);
                String evidence="central-successor-v1:"+resumeId()+":"+runId()+":"+receipt.checkpointDigest();
                CentralResumeRecord attached=successor.withCurrentWorkspaceTransaction(token,()->trees.bindCentralResume(reservation,evidence));
                boundRecord=successor.withCurrentWorkspaceTransaction(token,()->trees.incorporateCentralResume(attached,evidence));
                successorCertificate=sha256(evidence);bound=true;
            }catch(RuntimeException failure){
                invalid=true;
                try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){new WorkspaceTreeStore(registry).uncertainCentralResume(reservation);}
                catch(RuntimeException cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
    }
    private static String sha256(String value){
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public SuspensionPermit park(Context context,MainChatTranscriptStore.ConfirmedCentralYield receipt){
        requireCurrent(token);
        if(!CentralAvailabilityPolicy.admissionEnabled() || Thread.currentThread()!=ownerThread || !cycleId().equals(receipt.oldCycleId()) || !rootId().equals(receipt.rootId()))
            throw new IllegalStateException("Yield owner changed");
        revalidateSource(token);receipt.request().requirePrepared();
        try(WorkspaceRegistry registry=new WorkspaceRegistry(context)){
            WorkspaceTreeStore trees=new WorkspaceTreeStore(registry);TreeNode parent=Objects.requireNonNull(trees.getNode(rootId(),rootId()));
            CentralResumeRecord parked=withCurrentWorkspaceTransaction(token,()->trees.parkCentral(TreeNodeFence.Companion.of(parent),receipt.runId(),receipt.certificate()));
            return new SuspensionPermit(context,store,origin,receipt,parked);
        }
    }

    @Override public void close(){if(closed.compareAndSet(false,true))unregisterStop.run();}
}

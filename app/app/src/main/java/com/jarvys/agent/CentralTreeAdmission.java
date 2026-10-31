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
    @Override public void close(){if(closed.compareAndSet(false,true))unregisterStop.run();}
}

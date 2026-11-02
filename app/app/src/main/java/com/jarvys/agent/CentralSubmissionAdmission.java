package com.jarvys.agent;

import com.jarvys.agent.chatdb.AppendMessageInput;
import com.jarvys.agent.chatdb.CentralSubmissionAppendReceipt;
import com.jarvys.agent.chatdb.CentralSubmissionSnapshot;
import com.jarvys.agent.chatdb.CentralUserRootCheckpoint;
import com.jarvys.agent.connectors.ConnectionStateCoordinator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local native submission authority. Stored rows and Intent extras cannot reconstruct it. */
public final class CentralSubmissionAdmission {
    private CentralSubmissionAdmission() {}
    private static final String PROCESS_ID = UUID.randomUUID().toString();
    private static final ConcurrentHashMap<String, LivePermit> LIVE = new ConcurrentHashMap<>();
    static String processId() { return PROCESS_ID; }
    private static String key(String session, String id) { return session.length()+":"+session+id; }

    public static final class AppendAuthority implements AutoCloseable {
        private final AttachmentDraftViewModel.NativeSendAction action;
        private final String submissionId;
        private volatile boolean open = true;
        private AppendMessageInput input;
        private AppendAuthority(AttachmentDraftViewModel.NativeSendAction action) {
            this.action = action;
            this.submissionId = action.getSubmissionId();
            action.requireCurrent(action.getSessionId(), submissionId);
        }
        public String getSubmissionId() { return submissionId; }
        public String getSessionId() { return action.getSessionId(); }
        public String getProcessId() { return PROCESS_ID; }
        public String getText() { return action.getText(); }
        public List<ChatAttachment> getAttachments() { return action.getAttachments(); }
        public void requireCurrent(String session, String id) {
            if (!open || !submissionId.equals(id)) throw new IllegalStateException("Native Send action expired");
            action.requireCurrent(session,id);
        }
        /** The native store adapter binds its exact immutable conversion once, before writer dispatch. */
        public synchronized void bindInput(AppendMessageInput value) {
            requireCurrent(value.getConversationId(),submissionId);
            if (input != null && input != value) throw new IllegalStateException("Native Send payload already bound");
            if (!"user".equals(value.getRole())) throw new IllegalArgumentException("Native Send must be genuine USER");
            input = value;
        }
        public synchronized void requireInput(AppendMessageInput value) {
            requireCurrent(value.getConversationId(),submissionId);
            if (input != value) throw new IllegalStateException("Native Send payload changed");
        }
        @Override public void close() { open=false; }
    }
    static AppendAuthority beginSend(AttachmentDraftViewModel.NativeSendAction action) {
        action.beginPersistence();
        return new AppendAuthority(action);
    }

    public static final class LivePermit {
        private final CentralSubmissionSnapshot snapshot;
        private final CentralUserRootCheckpoint source;
        private volatile boolean invalid;
        private volatile ClaimAuthority claim;
        private LivePermit(CentralSubmissionAppendReceipt receipt) {
            snapshot=receipt.getSnapshot(); source=receipt.getSource();
            if (!"PREPARED".equals(snapshot.getState()) || !PROCESS_ID.equals(snapshot.getCurrentAdmissionProcess())
                    || !snapshot.getSessionId().equals(source.getOwnerCentralSessionId())
                    || !snapshot.getUserMessageId().equals(source.getOriginUserMessageId())
                    || !snapshot.getStorageGeneration().equals(source.getStorageGeneration())
                    || !snapshot.getSourceSha256().equals(source.getOriginDigest()))
                throw new IllegalStateException("Committed submission source does not match native admission");
        }
        public String getSessionId() { return snapshot.getSessionId(); }
        public String getSubmissionId() { return snapshot.getSubmissionId(); }
        public String getUserMessageId() { return snapshot.getUserMessageId(); }
        public long getUserSeq() { return snapshot.getUserSeq(); }
        public String getSourceSha256() { return snapshot.getSourceSha256(); }
        public String getStorageGeneration() { return snapshot.getStorageGeneration(); }
        public String getProcessId() { return PROCESS_ID; }
        public long getRevision() { return snapshot.getRevision(); }
        public ConnectionStateCoordinator.DestinationEvidence getLifecycle() { return source.getLifecycle(); }
        public CentralUserRootCheckpoint getSource() { return source; }
        private void requireNativeIdentity() {
            if(invalid || LIVE.get(key(getSessionId(),getSubmissionId()))!=this)
                throw new IllegalStateException("Native submission permission is no longer current");
        }
        public void requireCurrent() {
            requireNativeIdentity();
            java.util.concurrent.locks.ReentrantLock fence=getLifecycle().fenceIdentity();fence.lock();
            try {if(!getLifecycle().isCurrentPure())throw new IllegalStateException("Native submission lifecycle changed");}
            finally {fence.unlock();}
        }
        public void requirePrepared() {
            requireCurrent();
            if (claim != null) throw new IllegalStateException("Submission already has a native claim");
        }
    }
    static LivePermit register(AppendAuthority action, CentralSubmissionAppendReceipt receipt) {
        action.requireCurrent(receipt.getSnapshot().getSessionId(),receipt.getSnapshot().getSubmissionId());
        return registerReceipt(receipt);
    }
    private static LivePermit registerReceipt(CentralSubmissionAppendReceipt receipt) {
        LivePermit fresh=new LivePermit(receipt);
        String key=key(fresh.getSessionId(),fresh.getSubmissionId());
        LivePermit old=LIVE.putIfAbsent(key,fresh);
        if (old!=null) {
            old.requirePrepared();
            if (old.getRevision()!=fresh.getRevision() || !old.getSourceSha256().equals(fresh.getSourceSha256()))
                throw new IllegalStateException("Submission permission identity changed");
            return old;
        }
        return fresh;
    }
    static LivePermit find(String session,String id) { return LIVE.get(key(session,id)); }
    static List<LivePermit> prepared(String session) {
        List<LivePermit> found=new ArrayList<>();
        for(LivePermit permit:LIVE.values()) if(session.equals(permit.getSessionId()) && !permit.invalid && permit.claim==null)
            found.add(permit);
        found.sort(java.util.Comparator.comparingLong(LivePermit::getUserSeq));
        return found;
    }
    static void invalidate(LivePermit permit) {
        permit.invalid=true;LIVE.remove(key(permit.getSessionId(),permit.getSubmissionId()),permit);
    }

    public static final class ClaimAuthority {
        private final LivePermit permit;
        private final AgentForegroundService.CentralSubmissionOwner owner;
        private final List<String> eligibleSubmissionIds;
        private volatile CentralTreeAdmission.TerminalEvidence terminalEvidence;
        private ClaimAuthority(LivePermit permit, AgentForegroundService.CentralSubmissionOwner owner,List<String> eligible) {
            this.permit=permit;this.owner=owner;this.eligibleSubmissionIds=Collections.unmodifiableList(new ArrayList<>(eligible));
        }
        public List<String> getEligibleSubmissionIds(){return eligibleSubmissionIds;}
        public CentralTreeAdmission.TerminalEvidence getTerminalEvidence(){return terminalEvidence;}
        void bindTerminalEvidence(CentralTreeAdmission.TerminalEvidence evidence) {
            requireCurrent(permit);owner.requireTerminalEvidence(evidence);
            if(!permit.getUserMessageId().equals(evidence.getOriginalUserMessageId())
                || !permit.getSourceSha256().equals(evidence.getOriginDigest()) || !getRunId().equals(evidence.getRunId()))
                throw new IllegalStateException("Submission terminal source changed");
            if(terminalEvidence!=null && terminalEvidence!=evidence)throw new IllegalStateException("Submission terminal proof already bound");
            terminalEvidence=evidence;
        }
        public void requireTerminalEvidence(CentralTreeAdmission.TerminalEvidence evidence) {
            requireCurrent(permit);
            if(evidence==null || terminalEvidence!=evidence)throw new IllegalStateException("Submission lacks its native terminal proof");
            owner.requireTerminalEvidence(evidence);
        }
        public LivePermit getPermit() { return permit; }
        public String getProcessId() { return PROCESS_ID; }
        public String getRunId() { return owner.getRunId(); }
        public long getTokenGeneration() { return owner.getTokenGeneration(); }
        /** Called only inside the bridge-held lifecycle transaction; no Room/Registry IO or lock acquisition. */
        public void requireTransactionOwner() {
            if(permit.claim!=this)throw new IllegalStateException("Submission claim identity changed");
            permit.requireNativeIdentity();owner.requireCurrent(permit.getSessionId());
        }
        public void requireCurrent(LivePermit expected) {
            if(expected!=permit || permit.claim!=this) throw new IllegalStateException("Submission claim identity changed");
            permit.requireCurrent();owner.requireCurrent(permit.getSessionId());
        }
    }
    static ClaimAuthority claim(LivePermit permit,AgentForegroundService.CentralSubmissionOwner owner) {
        synchronized(permit) {
            permit.requirePrepared();owner.requireCurrent(permit.getSessionId());
            List<String> eligible=new ArrayList<>();
            for(LivePermit live:prepared(permit.getSessionId())) {live.requirePrepared();eligible.add(live.getSubmissionId());}
            ClaimAuthority claim=new ClaimAuthority(permit,owner,eligible);permit.claim=claim;return claim;
        }
    }

    public static final class RecoveryAuthority implements AutoCloseable {
        private final AttachmentDraftViewModel.NativeRecoveryAction action;
        private final String receiptId=UUID.randomUUID().toString();
        private volatile boolean open=true;
        private RecoveryAuthority(AttachmentDraftViewModel.NativeRecoveryAction action) {this.action=action;}
        public String getProcessId() {return PROCESS_ID;}
        public String getReceiptId() {return receiptId;}
        public void requireCurrent(String session,String id,long revision) {
            if(!open)throw new IllegalStateException("Native recovery action expired");
            action.requireCurrent(session,id,revision);
        }
        @Override public void close(){open=false;}
    }
    static RecoveryAuthority beginRecovery(AttachmentDraftViewModel.NativeRecoveryAction action) {
        action.beginPersistence();return new RecoveryAuthority(action);
    }
    static LivePermit registerRecovered(RecoveryAuthority action,CentralSubmissionAppendReceipt receipt,long expectedRevision) {
        action.requireCurrent(receipt.getSnapshot().getSessionId(),receipt.getSnapshot().getSubmissionId(),expectedRevision);
        return registerReceipt(receipt);
    }

    public static final class CancelAuthority {
        private final AgentForegroundService.CentralSubmissionStopBoundary boundary;
        private final List<CentralSubmissionSnapshot> captured;
        private CancelAuthority(AgentForegroundService.CentralSubmissionStopBoundary boundary,List<CentralSubmissionSnapshot> captured) {
            this.boundary=boundary;this.captured=Collections.unmodifiableList(new ArrayList<>(captured));
        }
        public List<CentralSubmissionSnapshot> getCaptured(){return captured;}
        public String getProcessId(){return PROCESS_ID;}
        public void requireCurrent(){boundary.requireCurrent();}
    }
    static CancelAuthority cancel(AgentForegroundService.CentralSubmissionStopBoundary boundary,List<CentralSubmissionSnapshot> captured) {
        boundary.requireCurrent();return new CancelAuthority(boundary,captured);
    }
}

package com.jarvys.agent.coding;

import com.jarvys.agent.StorageGeneration;
import com.jarvys.agent.chatdb.CodingJobReservation;
import com.jarvys.agent.chatdb.CodingJobRecoveryBinding;
import com.jarvys.agent.linux.CodingJobJournalWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Main producer of exact schema3 bytes. A decoded record or the legacy raw IO token is not its Commit. */
public final class CodingJobJournalStore {
    private static final Map<String,CodingJobJournalStore> STORES = new ConcurrentHashMap<>();
    private final File root;
    private final File filesRoot;
    private final Object recoveryLock = new Object();
    private final CodingJobJournalWriter writer;
    private final Map<String,Object> directoryAnchors = new ConcurrentHashMap<>();
    private final Map<String,Head> heads = new ConcurrentHashMap<>();
    private CodingJobJournalStore(File files) throws IOException {
        filesRoot=files;
        root = new StorageGeneration(files).directory("crew-context").getAbsoluteFile();
        verify(root);
        writer = new CodingJobJournalWriter(root, CodingJobJournalWriter.RealBackend.INSTANCE);
    }
    /** Only this canonical-root registry chooses an issuer; no arbitrary register/replace entry point. */
    public static CodingJobJournalStore forFilesRoot(File files) throws IOException {
        File exact = files.getAbsoluteFile(); verify(exact);
        synchronized (STORES) {
            CodingJobJournalStore found = STORES.get(exact.getPath());
            if (found == null) { found = new CodingJobJournalStore(exact); STORES.put(exact.getPath(),found); }
            return found;
        }
    }
    public static final class Commit {
        private final CodingJobJournalStore issuer;
        private final CodingJobReservation reservation;
        private final CodingJobEvidence.Record record;
        private final CodingJobSnapshot snapshot;
        private final byte[] payload;
        private final File target;
        private Commit(CodingJobJournalStore issuer, Pending pending) {
            this.issuer=issuer; reservation=pending.reservation; record=pending.record;
            snapshot=pending.snapshot; payload=pending.payload.clone(); target=pending.target;
        }
        public CodingJobReservation reservation() { return reservation; }
        public CodingJobEvidence.Record record() { return record; }
        public CodingJobSnapshot snapshot() { return snapshot; }
        public String payloadSha256() { return ProjectScope.sha256(payload); }
        public byte[] copyPayload() { return payload.clone(); }
        public File target() { return target; }
        public void requireMintedBy(CodingJobJournalStore expected) {
            if (issuer != expected) throw new IllegalStateException("Journal commit belongs to a different issuer");
            reservation.requireJournalOwner(expected);
            snapshot.requireMatches(record);
            if (!Arrays.equals(payload,CodingJobJournalCodec.encode(snapshot,record)))
                throw new IllegalStateException("Journal commit payload changed");
        }
    }
    /** Verified syntax and exact existing file identity only, not durability or provenance authority. */
    public static final class RecoveryCandidate {
        private final CodingJobJournalStore issuer;
        private final CodingJobEvidence.Record record;
        private final CodingJobSnapshot snapshot;
        private final byte[] payload;
        private final File target;
        private final Object fileKey;
        private RecoveryCandidate(CodingJobJournalStore issuer,File target,byte[] bytes,
                CodingJobJournalCodec.Decoded decoded,Object fileKey) {
            this.issuer=issuer;this.target=target;payload=bytes.clone();record=decoded.record;
            snapshot=decoded.snapshot;this.fileKey=fileKey;
        }
        public CodingJobJournalStore journal(){return issuer;}
        public CodingJobEvidence.Record record(){return record;}
        public CodingJobSnapshot snapshot(){return snapshot;}
        public byte[] copyPayload(){return payload.clone();}
        public File target(){return target;}
    }
    /** Producer-issued recovery IO attestation, deliberately not a live submission Commit. */
    public static final class RecoveryVerified {
        private final CodingJobJournalStore issuer;
        private final CodingJobRecoveryBinding binding;
        private final CodingJobEvidence.Record record;
        private final CodingJobSnapshot snapshot;
        private final byte[] payload;
        private final File target;
        private RecoveryVerified(CodingJobJournalStore issuer,CodingJobRecoveryBinding binding,
                CodingJobEvidence.Record record,CodingJobSnapshot snapshot,byte[] payload,File target) {
            this.issuer=issuer;this.binding=binding;this.record=record;this.snapshot=snapshot;
            this.payload=payload.clone();this.target=target;
        }
        public CodingJobRecoveryBinding recoveryBinding(){return binding;}
        public CodingJobEvidence.Record record(){return record;}
        public CodingJobSnapshot snapshot(){return snapshot;}
        public byte[] copyPayload(){return payload.clone();}
        public String payloadSha256(){return ProjectScope.sha256(payload);}
        public File target(){return target;}
        public void requireMintedBy(CodingJobJournalStore expected) {
            if(issuer!=expected)throw new IllegalStateException("Foreign recovered journal issuer");
            binding.requireFor(binding.candidate(),expected);
            if(!record.binding.equals(binding.candidate().record.binding))throw new IllegalStateException("Recovered binding changed");
            snapshot.requireMatches(record);
            CodingJobJournalCodec.Decoded decoded=CodingJobJournalCodec.decode(payload,payload.length);
            if(!Arrays.equals(CodingJobJournalCodec.encode(snapshot,record),CodingJobJournalCodec.encode(decoded.snapshot,decoded.record)))
                throw new IllegalStateException("Recovered payload facts changed");
        }
    }
    /** Existing-only inspection. It neither creates directories nor changes a journal or Room row. */
    public RecoveryCandidate inspectExisting(File target,int maxBytes) throws IOException {
        verify(target);
        if(maxBytes<0)throw new IllegalArgumentException("Negative journal read budget");
        ProjectFileIO.Attributes before=ProjectFileIO.attributes(target);
        if(!before.isRegularFile() || before.isSymbolicLink() || before.fileKey()==null)
            throw new IOException("Recovery journal is not an identifiable regular file");
        byte[] bytes=readRecoveryBounded(target,maxBytes);
        CodingJobJournalCodec.Decoded decoded=CodingJobJournalCodec.decode(bytes,maxBytes);
        CodingJobSnapshot snapshot=decoded.snapshot;
        File expected=new File(new File(root,ProjectScope.sha256(snapshot.conversationId.getBytes(StandardCharsets.UTF_8))+"/coding-jobs"),snapshot.id+".json");
        if(!expected.equals(target.getAbsoluteFile()))throw new IOException("Recovered journal path identity mismatch");
        ProjectScope scope=new ProjectScopeStore(filesRoot).openExisting(snapshot.conversationId);
        if(!scope.id().equals(snapshot.scopeId) || !scope.rootDirectory().getCanonicalPath().equals(snapshot.projectRoot) ||
                !scope.durableIdentity().equals(decoded.record.binding.durableScopeIdentity))
            throw new IOException("Recovered project identity changed");
        requireExistingAnchor(root);requireExistingAnchor(expected.getParentFile().getParentFile());
        requireExistingAnchor(expected.getParentFile());requireExistingAnchor(scope.rootDirectory());
        ProjectFileIO.Attributes after=ProjectFileIO.attributes(target);
        if(!before.fileKey().equals(after.fileKey()) || !after.isRegularFile() || before.size()!=after.size())
            throw new IOException("Recovery journal changed while reading");
        RecoveryCandidate candidate=new RecoveryCandidate(this,expected,bytes,decoded,after.fileKey());
        requireCurrentCandidate(candidate);
        return candidate;
    }
    private void requireCurrentCandidate(RecoveryCandidate candidate) throws IOException {
        if(candidate.issuer!=this)throw new IllegalStateException("Foreign recovery candidate");
        verify(candidate.target);
        requireExistingAnchor(root);requireExistingAnchor(candidate.target.getParentFile().getParentFile());
        requireExistingAnchor(candidate.target.getParentFile());requireExistingAnchor(new File(candidate.snapshot.projectRoot));
        ProjectScope scope=new ProjectScopeStore(filesRoot).openExisting(candidate.snapshot.conversationId);
        if(!scope.durableIdentity().equals(candidate.record.binding.durableScopeIdentity))throw new IOException("Recovery scope changed");
        ProjectFileIO.Attributes before=ProjectFileIO.attributes(candidate.target);
        if(!before.isRegularFile() || before.isSymbolicLink() || !candidate.fileKey.equals(before.fileKey()) ||
                !Arrays.equals(candidate.payload,readRecoveryBounded(candidate.target,candidate.payload.length)))
            throw new IOException("Recovery candidate bytes or file identity changed");
        if(!candidate.fileKey.equals(ProjectFileIO.attributes(candidate.target).fileKey()))
            throw new IOException("Recovery journal was replaced during validation");
    }
    public RecoveryVerified recertifyTerminal(RecoveryCandidate candidate,CodingJobRecoveryBinding binding) throws IOException {
        binding.requireFor(candidate,this);
        if(candidate.record.terminal==null)throw new IllegalArgumentException("Only an immutable terminal can be recertified without owner death");
        synchronized(recoveryLock) {
            requireCurrentCandidate(candidate);
            // A new exact-byte durable write, not a claim about the original attempt's fsync.
            writer.writeEnvelope(candidate.target,candidate.record.binding.jobId,candidate.payload);
            RecoveryCandidate installed=inspectExisting(candidate.target,candidate.payload.length);
            if(!Arrays.equals(candidate.payload,installed.payload))throw new IOException("Recertified terminal bytes changed after commit");
            return new RecoveryVerified(this,binding,candidate.record,candidate.snapshot,candidate.payload,candidate.target);
        }
    }
    /** The caller retains this exact exclusive owner probe through the durable journal recovery commit. */
    public RecoveryVerified recoverDead(RecoveryCandidate candidate,CodingJobRecoveryBinding binding,
            CodingJobOwnerLease.Probe probe) throws IOException {
        binding.requireFor(candidate,this);
        if(candidate.record.terminal!=null)throw new IllegalArgumentException("Existing terminal facts are immutable");
        synchronized(recoveryLock) {
            CodingJobEvidence.Binding identity=candidate.record.binding;
            probe.requireDeadOwner(filesRoot,identity.ownerProcessId,identity.managerInstanceId);
            requireCurrentCandidate(candidate);
            CodingJobEvidence.Record next=new CodingJobEvidence.Record(identity,Math.addExact(candidate.record.revision,1),
                candidate.record.highestEffectPhase,candidate.record.submissionCommitted,candidate.record.authorization,
                new CodingJobEvidence.TerminalFacts(CodingJobEvidence.TerminalState.UNCERTAIN,null,false,false,true,
                    CodingJobEvidence.AbsenceCoverage.NOT_PROVEN));
            CodingJobSnapshot old=candidate.snapshot;
            CodingJobSnapshot snapshot=new CodingJobSnapshot(old.id,old.owner,old.scopeId,old.cwd,old.redactedCommand,
                old.projectRoot,old.conversationId,CodingJobSnapshot.State.UNCERTAIN,old.createdAt,old.startedAt,null,null,
                old.logArtifact,"Owner terminated; command outcome is unknown. No command was replayed or process signalled.",old.logBytes,false);
            byte[] bytes=CodingJobJournalCodec.encode(snapshot,next);
            probe.requireDeadOwner(filesRoot,identity.ownerProcessId,identity.managerInstanceId);
            requireCurrentCandidate(candidate);
            writer.writeEnvelope(candidate.target,identity.jobId,bytes);
            RecoveryCandidate installed=inspectExisting(candidate.target,bytes.length);
            if(!Arrays.equals(bytes,installed.payload))throw new IOException("Recovered terminal bytes changed after commit");
            probe.requireDeadOwner(filesRoot,identity.ownerProcessId,identity.managerInstanceId);
            return new RecoveryVerified(this,binding,next,snapshot,bytes,candidate.target);
        }
    }
    public static final class UnconfirmedWrite extends IOException {
        private final String jobId;
        private UnconfirmedWrite(String jobId, Exception cause) {
            super("Coding journal revision is unconfirmed; dispatch must not be resumed",cause); this.jobId=jobId;
        }
        public String jobId() { return jobId; }
    }
    private static final class Head {
        final CodingJobReservation reservation;
        Commit confirmed;
        Pending pending;
        Head(CodingJobReservation reservation) { this.reservation=reservation; }
    }
    private static final class Pending {
        final CodingJobReservation reservation;
        final CodingJobEvidence.Record record;
        final CodingJobSnapshot snapshot;
        final byte[] payload;
        final File target;
        Pending(CodingJobReservation reservation,CodingJobEvidence.Record record,CodingJobSnapshot snapshot,byte[] payload,File target) {
            this.reservation=reservation;this.record=record;this.snapshot=snapshot;this.payload=payload.clone();this.target=target;
        }
    }
    public Commit write(CodingJobReservation reservation,long expectedRevision,CodingJobEvidence.Record next,
            CodingJobSnapshot snapshot) throws IOException {
        return writeInternal(reservation,expectedRevision,next,snapshot,null,false);
    }
    /** No new submission acceptance: recertifies the private producer's abandoned initial metadata. */
    public Commit initializeAbandoned(CodingJobReservation reservation,CodingJobEvidence.Record record,
            CodingJobSnapshot snapshot,CodingJobOwnerLease.Pin pin) throws IOException {
        reservation.submission().requirePreDispatchAbandoned();
        if(record.revision!=1 || record.terminal!=null || record.highestEffectPhase!=CodingJobEvidence.Phase.SUBMISSION_INTENT)
            throw new IllegalArgumentException("Abandoned initialization must be original submission metadata");
        return writeInternal(reservation,0,record,snapshot,pin,true);
    }
    /** Audit-only append after exact head repair. No phase, authorization or execution advance. */
    public Commit writeTerminalRepair(CodingJobReservation reservation,long expectedRevision,CodingJobEvidence.Record record,
            CodingJobSnapshot snapshot,CodingJobOwnerLease.Pin pin) throws IOException {
        if(record.terminal==null)throw new IllegalArgumentException("Repair may append terminal facts only");
        return writeInternal(reservation,expectedRevision,record,snapshot,pin,false);
    }
    private Commit writeInternal(CodingJobReservation reservation,long expectedRevision,CodingJobEvidence.Record next,
            CodingJobSnapshot snapshot,CodingJobOwnerLease.Pin repairPin,boolean initialize) throws IOException {
        Objects.requireNonNull(reservation).requireJournalOwner(this);
        if (!reservation.binding().equals(next.binding)) throw new IllegalArgumentException("Journal binding changed");
        if(repairPin==null)reservation.submission().requireOwnerLive();
        else {reservation.submission().requireRepairPin(repairPin);reservation.submission().requireRepairReady();}
        snapshot.requireMatches(next);
        byte[] bytes = CodingJobJournalCodec.encode(snapshot,next);
        Head head = heads.computeIfAbsent(next.binding.jobId,key -> new Head(reservation));
        synchronized (head) {
            if (head.reservation != reservation) throw new IllegalStateException("Job reservation was replaced");
            if (head.pending != null) throw new IllegalStateException("An exact uncertain revision must be repaired before advancing");
            long current = head.confirmed == null ? 0 : head.confirmed.record.revision;
            if (expectedRevision != current) throw new IllegalStateException("Journal compare-and-set revision changed");
            if(repairPin!=null && !initialize) {
                if(head.confirmed==null || next.highestEffectPhase!=head.confirmed.record.highestEffectPhase ||
                        next.submissionCommitted!=head.confirmed.record.submissionCommitted ||
                        !Objects.equals(next.authorization,head.confirmed.record.authorization))
                    throw new IllegalArgumentException("Terminal repair cannot advance admission facts");
            }
            if (head.confirmed == null) {
                if (next.revision != 1 || next.highestEffectPhase != CodingJobEvidence.Phase.SUBMISSION_INTENT || next.terminal != null)
                    throw new IllegalArgumentException("First journal revision must be submission intent");
            } else {
                CodingJobEvidence.Advance advance = CodingJobEvidence.validateAdvance(head.confirmed.record,next);
                if (advance == CodingJobEvidence.Advance.IDEMPOTENT) {
                    if (!Arrays.equals(bytes,head.confirmed.payload)) throw new IllegalArgumentException("Operational snapshot changed at the same revision");
                    return head.confirmed;
                }
            }
            File directory = new File(root,ProjectScope.sha256(snapshot.conversationId.getBytes(StandardCharsets.UTF_8))+"/coding-jobs");
            File target = new File(directory,next.binding.jobId+".json"); verify(target);
            requireExistingAnchor(root); requireExistingAnchor(directory.getParentFile());
            requireExistingAnchor(new File(snapshot.projectRoot));
            if (head.confirmed != null) {
                requireExistingAnchor(directory);
                if (!Arrays.equals(readBounded(target,head.confirmed.payload.length),head.confirmed.payload))
                    throw new IllegalStateException("Confirmed journal bytes changed before revision advance");
            }
            if (head.confirmed == null && target.exists()) throw new IllegalStateException("An existing journal requires verified recovery, not overwrite");
            head.pending = new Pending(reservation,next,snapshot,bytes,target);
            return persistPending(head);
        }
    }
    /** Exact private-candidate durability repair only. It never resumes enqueue, preparation or execution. */
    public Commit repairPending(CodingJobReservation reservation) throws IOException {
        reservation.submission().requireOwnerLive();
        return repairHeadInternal(reservation,false);
    }
    /** Returns the exact confirmed head, or null before any local journal candidate was reserved. */
    public Commit repairHead(CodingJobReservation reservation,CodingJobOwnerLease.Pin pin) throws IOException {
        reservation.submission().requireRepairPin(pin);reservation.submission().requireRepairReady();
        return repairHeadInternal(reservation,true);
    }
    private Commit repairHeadInternal(CodingJobReservation reservation,boolean allowConfirmed) throws IOException {
        reservation.requireJournalOwner(this);
        Head head = heads.get(reservation.binding().jobId);
        if(head==null && allowConfirmed)return null;
        if (head == null || head.reservation != reservation) throw new IllegalStateException("No matching local journal head");
        synchronized (head) {
            Pending pending = head.pending;
            if(pending==null && allowConfirmed) {
                Commit confirmed=head.confirmed;
                if(confirmed!=null) {
                    verify(confirmed.target);
                    requireExistingAnchor(root);requireExistingAnchor(confirmed.target.getParentFile().getParentFile());
                    requireExistingAnchor(confirmed.target.getParentFile());requireExistingAnchor(new File(confirmed.snapshot.projectRoot));
                    if(!Arrays.equals(readBounded(confirmed.target,confirmed.payload.length),confirmed.payload))
                        throw new IllegalStateException("Confirmed repair head bytes changed");
                }
                return confirmed;
            }
            if (pending == null) throw new IllegalStateException("No exact pending candidate");
            verify(pending.target);
            requireExistingAnchor(root);requireExistingAnchor(pending.target.getParentFile().getParentFile());
            requireExistingAnchor(new File(pending.snapshot.projectRoot));
            if (directoryAnchors.containsKey(pending.target.getParentFile().getPath()))requireExistingAnchor(pending.target.getParentFile());
            if (pending.target.exists()) {
                byte[] disk = readBounded(pending.target,Math.max(pending.payload.length,head.confirmed==null?0:head.confirmed.payload.length));
                if (!Arrays.equals(disk,pending.payload) && (head.confirmed==null || !Arrays.equals(disk,head.confirmed.payload)))
                    throw new IllegalStateException("Journal changed outside the pending candidate");
            } else if (head.confirmed != null) {
                throw new IllegalStateException("Previously confirmed journal disappeared");
            }
            return persistPending(head);
        }
    }
    private Commit persistPending(Head head) throws IOException {
        Pending pending = head.pending;
        try {
            requireExistingAnchor(root);
            requireExistingAnchor(pending.target.getParentFile().getParentFile());
            ensureDirectory(pending.target.getParentFile());
            requireExistingAnchor(pending.target.getParentFile());
            writer.writeEnvelope(pending.target,pending.record.binding.jobId,pending.payload);
            Commit commit = new Commit(this,pending);
            head.confirmed=commit; head.pending=null;
            return commit;
        } catch (Exception failure) { throw new UnconfirmedWrite(pending.record.binding.jobId,failure); }
    }
    private void requireExistingAnchor(File directory) throws IOException {
        verify(directory);
        ProjectFileIO.Attributes attributes=ProjectFileIO.attributes(directory);
        if(!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey()==null)
            throw new IOException("Journal directory identity is unavailable");
        Object previous=directoryAnchors.putIfAbsent(directory.getPath(),attributes.fileKey());
        if(previous!=null && !previous.equals(attributes.fileKey()))throw new IOException("Journal directory identity was replaced");
    }
    private void ensureDirectory(File directory) throws IOException {
        verify(directory);
        if (!directory.exists()) {
            File parent=directory.getParentFile(); if(parent==null)throw new IOException("Journal directory has no parent");
            requireExistingAnchor(parent);
            if(!directory.mkdir()&&!directory.isDirectory())throw new IOException("Cannot create journal directory");
            CodingJobJournalWriter.RealBackend.INSTANCE.syncDirectory(parent);
        }
        if(!directory.isDirectory())throw new IOException("Journal parent is not a directory");
    }
    private static byte[] readBounded(File file,int maximum) throws IOException {
        if(!file.isFile()||file.length()>maximum)throw new IOException("Unexpected journal bytes");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=new java.io.FileInputStream(file)) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1) { if(out.size()+count>maximum)throw new IOException("Journal changed while reading");out.write(buffer,0,count); }
        }
        return out.toByteArray();
    }
    /** Recovery additionally requires an existing no-follow descriptor; live protocol is unchanged. */
    private static byte[] readRecoveryBounded(File file,int maximum) throws IOException {
        if(!file.isFile()||file.length()>maximum)throw new IOException("Unexpected journal bytes");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=ProjectFileIO.openNoFollow(file)) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1) { if(out.size()+count>maximum)throw new IOException("Journal changed while reading");out.write(buffer,0,count); }
        }
        return out.toByteArray();
    }
    private static void verify(File file)throws IOException {
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("Journal storage cannot use symlinks");
    }
}

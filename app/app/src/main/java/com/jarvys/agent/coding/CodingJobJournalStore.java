package com.jarvys.agent.coding;

import com.jarvys.agent.StorageGeneration;
import com.jarvys.agent.chatdb.CodingJobReservation;
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
    private final CodingJobJournalWriter writer;
    private final Map<String,Object> directoryAnchors = new ConcurrentHashMap<>();
    private final Map<String,Head> heads = new ConcurrentHashMap<>();
    private CodingJobJournalStore(File files) throws IOException {
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
        Objects.requireNonNull(reservation).requireJournalOwner(this);
        if (!reservation.binding().equals(next.binding)) throw new IllegalArgumentException("Journal binding changed");
        reservation.submission().requireOwnerLive();
        snapshot.requireMatches(next);
        byte[] bytes = CodingJobJournalCodec.encode(snapshot,next);
        Head head = heads.computeIfAbsent(next.binding.jobId,key -> new Head(reservation));
        synchronized (head) {
            if (head.reservation != reservation) throw new IllegalStateException("Job reservation was replaced");
            if (head.pending != null) throw new IllegalStateException("An exact uncertain revision must be repaired before advancing");
            long current = head.confirmed == null ? 0 : head.confirmed.record.revision;
            if (expectedRevision != current) throw new IllegalStateException("Journal compare-and-set revision changed");
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
        reservation.requireJournalOwner(this);
        reservation.submission().requireOwnerLive();
        Head head = heads.get(reservation.binding().jobId);
        if (head == null || head.reservation != reservation) throw new IllegalStateException("No matching local journal head");
        synchronized (head) {
            Pending pending = head.pending;
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
    private static void verify(File file)throws IOException {
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("Journal storage cannot use symlinks");
    }
}

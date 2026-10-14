package com.jarvys.agent.apkfactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One process-owned whole-archive operation, with conservative, advisory heap admission.
 * This does not reserve Android heap or guarantee that another subsystem cannot exhaust it.
 * An insufficient positive plan gets at most one collection hint and an actual heap recheck.
 * The runtime may ignore that hint or introduce latency; admission can still fail afterward.
 * Larger archives need a file-backed transformer, verifier and signing pipeline, not larger arrays.
 */
public final class FactoryPackagingResources {
    private FactoryPackagingResources() {}
    private static final AtomicBoolean OWNED = new AtomicBoolean();
    private static final long HEADROOM = 16L * 1024 * 1024;
    public static final class ResourceFailure extends IOException {
        ResourceFailure(String message) { super(message); }
    }
    public static final class Lease implements Closeable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease() {}
        @Override public void close() { if (closed.compareAndSet(false,true)) OWNED.set(false); }
    }
    /** Never waits when another APK operation owns its buffers. */
    public static Lease acquire() throws ResourceFailure {
        if (!OWNED.compareAndSet(false,true)) throw new ResourceFailure("Factory resources busy: another process-local APK operation still owns its buffers; retry after it finishes or is cancelled");
        return new Lease();
    }
    /** Package-private policy seam; public admission always reads the real process heap. */
    interface HeapProbe {
        long availableBytes();
        void requestCollection();
    }
    private static final HeapProbe RUNTIME_HEAP = new HeapProbe() {
        @Override public long availableBytes() {
            Runtime heap=Runtime.getRuntime();
            return heap.maxMemory()-(heap.totalMemory()-heap.freeMemory());
        }
        @Override public void requestCollection() { Runtime.getRuntime().gc(); }
    };
    public static void requireHeap(long required) throws ResourceFailure {
        requireHeap(required,RUNTIME_HEAP);
    }
    static void requireHeap(long required,HeapProbe heap) throws ResourceFailure {
        if(required<0) throw new ResourceFailure("Invalid negative factory resource plan");
        long available=heap.availableBytes();
        // Completed operations may have left unreachable buffers. A hint is not a reservation,
        // so remeasure once and keep the same strict refusal if memory remains insufficient.
        if(required>0 && available>=0 && required>available) {
            heap.requestCollection();
            available=heap.availableBytes();
        }
        requireHeap(required,available);
    }
    static void requireHeap(long required,long available) throws ResourceFailure {
        if(required<0 || available<0 || required>available)
            throw new ResourceFailure("Factory resource admission failed: estimated additional heap "+required+" bytes, currently available "+available+" bytes. Reduce source size or release other memory; declared budgets do not guarantee device resources");
    }
    /** Two retained input/output copies and growth during a bounded byte-array read. */
    public static long readEstimate(long bytes) throws ResourceFailure { return estimate(0,bytes,0,0,3,0); }
    /** Source snapshot/clones, two ZIP buffers and repeated expanded verification maps, plus metadata. */
    public static long buildEstimate(long source,long template,long expandedTemplate) throws ResourceFailure {
        return estimate(source,template,expandedTemplate,8,2,5);
    }
    /** Input, signed/output buffers and two independently expanded payload verification maps. */
    public static long artifactEstimate(long archive,long expanded) throws ResourceFailure {
        return estimate(0,archive,expanded,0,4,3);
    }
    private static long estimate(long source,long archive,long expanded,int sourceCopies,int archiveCopies,int expandedCopies) throws ResourceFailure {
        if(source<0 || archive<0 || expanded<0) throw new ResourceFailure("Invalid negative factory resource plan");
        try {
            return Math.addExact(HEADROOM, Math.addExact(Math.multiplyExact(source,sourceCopies),
                Math.addExact(Math.multiplyExact(archive,archiveCopies),Math.multiplyExact(expanded,expandedCopies))));
        } catch(ArithmeticException failure) { throw new ResourceFailure("Factory resource plan overflow"); }
    }
}

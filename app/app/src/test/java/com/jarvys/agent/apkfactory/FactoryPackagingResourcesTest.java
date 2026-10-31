package com.jarvys.agent.apkfactory;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class FactoryPackagingResourcesTest {
    private static final long MIB=1024L*1024;
    @Test public void actualSizedPlansFitTinyAndDefaultAppsWithoutReservingDeclaredMaxima() throws Exception {
        long tiny=FactoryPackagingResources.buildEstimate(1024,2*MIB,4*MIB);
        long normal=FactoryPackagingResources.buildEstimate(8*MIB,2*MIB,4*MIB);
        long large=FactoryPackagingResources.buildEstimate(24*MIB,2*MIB,4*MIB);
        assertTrue(tiny<48*MIB); assertTrue(normal<128*MIB); assertTrue(large>normal);
        FactoryPackagingResources.requireHeap(tiny,48*MIB);
        FactoryPackagingResources.requireHeap(normal,128*MIB);
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.requireHeap(large,128*MIB));
        FactoryPackagingResources.requireHeap(large,512*MIB);
    }
    @Test public void insufficientNegativeAndOverflowResourcePlansFailExplicitly() throws Exception {
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.readEstimate(Long.MAX_VALUE));
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.buildEstimate(-1,0,0));
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.requireHeap(1,0));
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.requireHeap(-1,1024));
    }
    private static final class HeapProbeFixture implements FactoryPackagingResources.HeapProbe {
        private final long[] readings;
        int measurements, hints;
        HeapProbeFixture(long... readings) { this.readings=readings; }
        @Override public long availableBytes() {
            assertTrue("Unexpected extra heap measurement",measurements<readings.length);
            return readings[measurements++];
        }
        @Override public void requestCollection() { hints++; assertEquals("Only one collection hint is allowed",1,hints); }
    }
    @Test public void sufficientZeroAndExactPlansNeverRequestCollection() throws Exception {
        for(long required:new long[]{0,1,48*MIB}) {
            HeapProbeFixture heap=new HeapProbeFixture(48*MIB);
            FactoryPackagingResources.requireHeap(required,heap);
            assertEquals(1,heap.measurements); assertEquals(0,heap.hints);
        }
    }
    @Test public void invalidPlanOrMeasurementNeverRequestsCollection() throws Exception {
        HeapProbeFixture negativePlan=new HeapProbeFixture();
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.requireHeap(-1,negativePlan));
        assertEquals(0,negativePlan.measurements); assertEquals(0,negativePlan.hints);
        HeapProbeFixture negativeAvailable=new HeapProbeFixture(-1);
        assertThrows(FactoryPackagingResources.ResourceFailure.class,()->FactoryPackagingResources.requireHeap(1,negativeAvailable));
        assertEquals(1,negativeAvailable.measurements); assertEquals(0,negativeAvailable.hints);
    }
    @Test public void insufficientPlanRequestsOneCollectionAndUsesTheFreshMeasurement() throws Exception {
        HeapProbeFixture heap=new HeapProbeFixture(16*MIB,32*MIB);
        FactoryPackagingResources.requireHeap(32*MIB,heap);
        assertEquals(2,heap.measurements); assertEquals(1,heap.hints);
    }
    @Test public void ignoredOrInsufficientCollectionStillRefusesAndReleasesOperationOwnership() throws Exception {
        for(long after:new long[]{16*MIB,24*MIB,8*MIB,-1}) {
            HeapProbeFixture heap=new HeapProbeFixture(16*MIB,after);
            FactoryPackagingResources.ResourceFailure failure=assertThrows(FactoryPackagingResources.ResourceFailure.class,()-> {
                try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {
                    FactoryPackagingResources.requireHeap(32*MIB,heap);
                }
            });
            assertTrue(failure.getMessage().contains("currently available "+after+" bytes"));
            assertEquals(2,heap.measurements); assertEquals(1,heap.hints);
            try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {}
        }
    }
    @Test public void anotherThreadFailsBusyAndExceptionsCancellationAndDoubleCloseReleaseSafely() throws Exception {
        FactoryPackagingResources.Lease first=FactoryPackagingResources.acquire();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            assertTrue(worker.submit(()-> { try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {return false;} catch(FactoryPackagingResources.ResourceFailure busy){return true;} }).get(2,TimeUnit.SECONDS));
        } finally {first.close();worker.shutdownNow();}
        try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {
            first.close(); assertThrows(FactoryPackagingResources.ResourceFailure.class,FactoryPackagingResources::acquire);
        }
        assertThrows(CancellationException.class,()-> { try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {throw new CancellationException();} });
        assertThrows(IllegalStateException.class,()-> { try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {throw new IllegalStateException();} });
        try(FactoryPackagingResources.Lease ignored=FactoryPackagingResources.acquire()) {}
    }
}

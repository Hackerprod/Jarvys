package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic bounded generators exercise long wire accounting without allocating their file sizes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 32}, manifest = Config.NONE)
public class StreamingFileShareTransferTest {
    private static final String NONCE = "a".repeat(64), HASH = "b".repeat(64);
    private final List<StreamingFileShareTransfer.Admission> owned = new ArrayList<>();
    private long now;
    private static final OutputStream DISCARD = new OutputStream() { public void write(int value) {} public void write(byte[] bytes,int offset,int count) {} };
    private static class Snapshot implements StreamingFileShareTransfer.Snapshot {
        final long length; String hash; long position;
        volatile boolean failClose, closed;
        volatile CountDownLatch reading, finishRead, closing, finishClose;
        Snapshot(long length) throws Exception {
            this.length = length;
            hash = length <= 9L * 1024 * 1024 ? hash(length) : HASH;
        }
        public void materialize(long maximum, StreamingFileShareTransfer.SnapshotWriter writer, Runnable active) throws Exception { active.run(); writer.write(DISCARD,active); active.run(); }
        public long size() { return length; }
        public String sha256() { return hash; }
        public int read(byte[] bytes,int offset,int count) throws Exception {
            if(reading!=null) { reading.countDown(); assertTrue(finishRead.await(5,TimeUnit.SECONDS)); }
            if(position==length) return -1;
            int result=(int)Math.min(count,length-position);
            for(int i=0;i<result;i++) bytes[offset+i]=pattern(position+i);
            position+=result; return result;
        }
        public void close() throws Exception {
            if(closing!=null) { closing.countDown(); assertTrue(finishClose.await(5,TimeUnit.SECONDS)); }
            if(failClose) throw new java.io.IOException("Synthetic close failure");
            closed=true;
        }
    }
    private static byte pattern(long at) { return (byte)(at*31+7); }
    private static String hash(long size) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[32768];
        for(long offset=0;offset<size;) {
            int count=(int)Math.min(buffer.length,size-offset);
            for(int i=0;i<count;i++) buffer[i]=pattern(offset+i);
            digest.update(buffer,0,count); offset+=count;
        }
        return StreamingFileShareTransfer.hex(digest.digest());
    }
    private static <T> FutureTask<T> async(Callable<T> action) {
        FutureTask<T> task=new FutureTask<>(action); Thread thread=new Thread(task,"synthetic-streaming-share"); thread.setDaemon(true);thread.start();return task;
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        try { return async(action).get(10,TimeUnit.SECONDS); }
        catch(ExecutionException failure) {
            if(failure.getCause() instanceof Exception) throw (Exception)failure.getCause();
            throw (Error)failure.getCause();
        }
    }
    private StreamingFileShareTransfer.Admission reserve(Snapshot snapshot) throws Exception {
        StreamingFileShareTransfer.Admission admission=StreamingFileShareTransfer.reserve(StreamingFileShareTransfer.MAX_BYTES,300000,()->now,()->snapshot);
        owned.add(admission); return admission;
    }
    private StreamingFileShareTransfer endpoint(Snapshot source) throws Exception { return endpoint(source,uid->true,FileShareTransfer.MAX_BYTES); }
    private StreamingFileShareTransfer endpoint(Snapshot source,FileShareTransfer.HostVerifier verifier,int legacyMaximum) throws Exception {
        StreamingFileShareTransfer.Admission admission=reserve(source);
        return worker(()->admission.prepare((out,active)->{},NONCE,verifier,()->{},legacyMaximum));
    }
    private static void busy() throws Exception {
        try { FileShareTransfer.Admission admission=FileShareTransfer.reserve(); admission.close(); fail("Released still-owned snapshot"); }
        catch(FactoryException expected) { assertEquals("SHARE_BUSY",expected.code); }
    }
    private static void waitClean(StreamingFileShareTransfer.Admission admission) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!admission.cleanedForTest() && System.nanoTime()<end) Thread.sleep(2);
        assertTrue("Cleanup did not finish",admission.cleanedForTest());
    }
    @After public void cleanup() throws Exception { for(StreamingFileShareTransfer.Admission admission:owned) { admission.close();waitClean(admission); } }
    private static Parcel request(int version,String nonce,long offset) {
        Parcel data=Parcel.obtain();
        data.writeByteArray((version==1?FileShareTransfer.DESCRIPTOR:StreamingFileShareTransfer.DESCRIPTOR_V2).getBytes(StandardCharsets.US_ASCII));
        data.writeByteArray(nonce.getBytes(StandardCharsets.US_ASCII));
        if(version==1) data.writeInt((int)offset); else data.writeLong(offset);
        data.setDataPosition(0); return data;
    }
    private static int read(StreamingFileShareTransfer endpoint,int version,String nonce,long offset) {
        Parcel data=request(version,nonce,offset),reply=Parcel.obtain();
        try {
            assertTrue(endpoint.onTransact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0)); reply.setDataPosition(0);
            int count=reply.readInt(); byte[] bytes=reply.createByteArray(); assertEquals(count,bytes.length);
            assertTrue(bytes.length<=32768);assertEquals(0,reply.dataAvail()); return count;
        } finally { data.recycle();reply.recycle(); }
    }
    private static void denied(Runnable action) { try { action.run();fail("Accepted invalid transfer"); } catch(SecurityException expected) {} }

    @Test public void oldV1ClientReadsDiskEndpointWithoutChangingItsEightMiBLimit() throws Exception {
        for(int size:new int[]{1,32769,FileShareTransfer.MAX_BYTES}) {
            Snapshot source=new Snapshot(size); StreamingFileShareTransfer endpoint=endpoint(source);
            assertEquals(1,endpoint.protocolVersion);
            long[] written={0};
            OutputStream output=new OutputStream() {
                public void write(int value) { fail(); }
                public void write(byte[] bytes) { assertTrue(bytes.length<=32768);for(byte value:bytes)assertEquals(pattern(written[0]++),value); }
            };
            worker(()->{FileShareTransfer.copy(endpoint,NONCE,size,endpoint.sha256,output,()->{});return null;});
            assertEquals(size,written[0]);waitClean(owned.get(owned.size()-1));assertTrue(source.closed);
        }
    }
    @Test public void v2StreamsBeyondEightMiBAndCanNegotiateForSmallerFamilyEnvelope() throws Exception {
        for(int size:new int[]{FileShareTransfer.MAX_BYTES+3,7*1024*1024}) {
            Snapshot source=new Snapshot(size);StreamingFileShareTransfer endpoint=endpoint(source,uid->true,6*1024*1024);
            assertEquals(2,endpoint.protocolVersion);long[] written={0};
            OutputStream output=new OutputStream(){public void write(int value){fail();}public void write(byte[] bytes){assertTrue(bytes.length<=32768);written[0]+=bytes.length;}};
            worker(()->{StreamingFileShareTransfer.copy(endpoint,NONCE,size,endpoint.sha256,2,output,()->{});return null;});
            assertEquals(size,written[0]);waitClean(owned.get(owned.size()-1));
        }
    }
    @Test public void longOffsetsRemainExactAboveSignedIntWithoutWholePayloadAllocation() throws Exception {
        long offset=(long)Integer.MAX_VALUE+1;
        Snapshot source=new Snapshot(offset+17);StreamingFileShareTransfer endpoint=endpoint(source);
        java.lang.reflect.Field field=StreamingFileShareTransfer.class.getDeclaredField("nextOffset");field.setAccessible(true);field.setLong(endpoint,offset);
        source.position=offset;
        assertEquals(17,read(endpoint,2,NONCE,offset));
        denied(()->read(endpoint,2,NONCE,offset));
        assertEquals(0,read(endpoint,2,NONCE,offset+17));
        waitClean(owned.get(0));
    }
    @Test public void cancelledBlockedMaterializationRetainsGlobalAdmissionThroughSourceClose() throws Exception {
        Snapshot source=new Snapshot(3);StreamingFileShareTransfer.Admission admission=reserve(source);
        CountDownLatch started=new CountDownLatch(1),resume=new CountDownLatch(1),closing=new CountDownLatch(1),closed=new CountDownLatch(1);
        FutureTask<?> task=async(()->admission.prepare(new StreamingFileShareTransfer.SnapshotWriter(){
            public void write(OutputStream out,Runnable active)throws Exception{started.countDown();assertTrue(resume.await(5,TimeUnit.SECONDS));active.run();}
            public void close()throws Exception{closing.countDown();assertTrue(closed.await(5,TimeUnit.SECONDS));}
        },NONCE,uid->true,()->{}));
        assertTrue(started.await(5,TimeUnit.SECONDS));admission.close();busy();resume.countDown();
        try{task.get(5,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertTrue(closing.await(5,TimeUnit.SECONDS));busy();closed.countDown();waitClean(admission);
    }
    @Test public void cleanupFailureHoldsAdmissionAndOnlyCleanupIsRetried() throws Exception {
        Snapshot source=new Snapshot(3);source.failClose=true;StreamingFileShareTransfer endpoint=endpoint(source);
        source.closing=new CountDownLatch(1);source.finishClose=new CountDownLatch(0);
        endpoint.close();assertTrue(source.closing.await(5,TimeUnit.SECONDS));busy();
        source.failClose=false;
        // A local retry is safe; no read/transaction is replayed.
        for(int i=0;i<50&&!owned.get(0).cleanedForTest();i++){endpoint.close();Thread.sleep(2);}
        waitClean(owned.get(0));assertEquals(0,source.position);
    }
    @Test public void cancellationDuringBlockedDiskReadDoesNotAdmitNewSnapshot() throws Exception {
        Snapshot source=new Snapshot(3);source.reading=new CountDownLatch(1);source.finishRead=new CountDownLatch(1);
        StreamingFileShareTransfer endpoint=endpoint(source);
        FutureTask<?> reading=async(()->read(endpoint,1,NONCE,0));assertTrue(source.reading.await(5,TimeUnit.SECONDS));
        endpoint.close();busy();source.finishRead.countDown();
        try{reading.get(5,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof SecurityException);}
        waitClean(owned.get(0));
    }
    @Test public void deadlineAndClockReversalFailClosedWithoutReadingAgain() throws Exception {
        for(long time:new long[]{300000,-1}){
            now=0;Snapshot source=new Snapshot(3);StreamingFileShareTransfer endpoint=endpoint(source);now=time;
            denied(()->read(endpoint,1,NONCE,0));waitClean(owned.get(owned.size()-1));assertEquals(0,source.position);
        }
    }
    @Test public void authenticationPrecedesEveryParseAndWrongProtocolNeverConsumes() throws Exception {
        AtomicInteger verified=new AtomicInteger();Snapshot source=new Snapshot(3);
        StreamingFileShareTransfer endpoint=endpoint(source,uid->{verified.incrementAndGet();assertEquals(Binder.getCallingUid(),uid);return true;},FileShareTransfer.MAX_BYTES);
        denied(()->read(endpoint,2,NONCE,0));denied(()->read(endpoint,1,HASH,0));denied(()->read(endpoint,1,NONCE,1));
        assertEquals(0,source.position);assertEquals(3,read(endpoint,1,NONCE,0));assertEquals(4,verified.get());
    }
    @Test public void v2MalformedReplyNeverAllocatesClaimedLengthOrRetries() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        Binder binder=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){calls.incrementAndGet();reply.writeInt(Integer.MAX_VALUE);reply.writeInt(Integer.MAX_VALUE);return true;}};
        try{worker(()->{StreamingFileShareTransfer.copy(binder,NONCE,9L*1024*1024,HASH,2,DISCARD,()->{});return null;});fail();}
        catch(FactoryException expected){assertEquals("INVALID_SHARE",expected.code);}
        assertEquals(1,calls.get());
    }
    @Test public void v2ReceiverStillRequiresHashAndExplicitEof() throws Exception {
        Snapshot source=new Snapshot(FileShareTransfer.MAX_BYTES+1);StreamingFileShareTransfer endpoint=endpoint(source);
        try{worker(()->{StreamingFileShareTransfer.copy(endpoint,NONCE,source.length,HASH,2,DISCARD,()->{});return null;});fail();}
        catch(FactoryException expected){assertEquals("INVALID_SHARE",expected.code);}
        waitClean(owned.get(0));denied(()->read(endpoint,2,NONCE,source.length));
    }
    @Test public void malformedBudgetIsRejectedBeforeTakingSharedSlot() throws Exception {
        for(long maximum:new long[]{-1,0,StreamingFileShareTransfer.MAX_BYTES+1,Long.MAX_VALUE}){
            try{StreamingFileShareTransfer.reserve(maximum,300000,()->0,()->null);fail();}
            catch(FactoryException expected){assertEquals("INVALID_SHARE",expected.code);}
        }
        FileShareTransfer.Admission guard=FileShareTransfer.reserve();guard.close();
    }
}

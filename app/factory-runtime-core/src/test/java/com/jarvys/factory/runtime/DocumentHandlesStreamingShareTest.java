package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** No real providers. Consumed source ownership and both cumulative quotas are exercised directly. */
public class DocumentHandlesStreamingShareTest {
    private static ResourceBudgets budget(long handle,long session,int slots) {
        Map<String,Object> docs=new LinkedHashMap<>();docs.put("handleBytes",handle);docs.put("sessionBytes",session);docs.put("openHandles",slots);
        Map<String,Object> root=new LinkedHashMap<>();root.put("version",1);root.put("documents",docs);return ResourceBudgets.parse(root);
    }
    private static class Source extends ByteArrayInputStream {
        final List<Integer> requested=new ArrayList<>();boolean closed,failClose;int calls;
        Source(int length){super(new byte[length]);}
        public synchronized int read(byte[] bytes,int offset,int count){calls++;requested.add(count);return super.read(bytes,offset,count);}
        public void close()throws IOException{if(failClose)throw new IOException("Synthetic close failure");closed=true;}
    }
    private static void failure(String code,Checked action)throws Exception{
        try{action.run();fail("Expected "+code);}catch(FactoryException expected){assertEquals(code,expected.code);}
    }
    interface Checked {void run()throws Exception;}
    @Test public void legacyShareAndBackupSnapshotsRespectSmallSignedReadQuota()throws Exception{
        for(boolean backup:new boolean[]{false,true}){
            List<Runnable> cleanup=new ArrayList<>();DocumentHandles handles=new DocumentHandles(()->0,cleanup::add,budget(8,8,1));
            Source source=new Source(7);String token=handles.grantRead(source);
            byte[] bytes=backup?handles.snapshotForBackup(token):handles.snapshotForShare(token);
            assertEquals(7,bytes.length);assertEquals(java.util.Arrays.asList(8,1),source.requested);
            assertEquals(1,cleanup.size());cleanup.get(0).run();assertTrue(source.closed);
        }
    }
    @Test public void legacyShareAndBackupSnapshotsDoNotProbeBeyondExhaustedQuota()throws Exception{
        for(boolean backup:new boolean[]{false,true}){
            List<Runnable> cleanup=new ArrayList<>();DocumentHandles handles=new DocumentHandles(()->0,cleanup::add,budget(8,8,1));
            Source source=new Source(8);String token=handles.grantRead(source);
            failure("DOCUMENT_QUOTA",()->{if(backup)handles.snapshotForBackup(token);else handles.snapshotForShare(token);});
            assertEquals(1,source.calls);assertEquals(java.util.Arrays.asList(8),source.requested);
            assertEquals(1,cleanup.size());cleanup.get(0).run();assertTrue(source.closed);
        }
    }
    @Test public void smallSignedQuotaDoesNotRequireAFullChunkReservation()throws Exception{
        List<Runnable> cleanup=new ArrayList<>();DocumentHandles handles=new DocumentHandles(()->0,cleanup::add,budget(8,8,1));
        Source source=new Source(7);DocumentHandles.ShareSource owner=handles.takeShareSource(handles.grantRead(source),100);
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();owner.copyTo(out,()->{});assertEquals(7,out.size());assertEquals(java.util.Arrays.asList(8,1),source.requested);assertTrue(cleanup.isEmpty());assertFalse(source.closed);}
        finally{owner.close();}assertTrue(source.closed);
    }
    @Test public void exactDocumentQuotaDoesNotReadAnUnchargedEofProbe()throws Exception{
        DocumentHandles handles=new DocumentHandles(()->0,r->{throw new AssertionError("Unexpected async close");},budget(8,8,1));
        Source source=new Source(8);DocumentHandles.ShareSource owner=handles.takeShareSource(handles.grantRead(source),100);
        try{failure("DOCUMENT_QUOTA",()->owner.copyTo(new ByteArrayOutputStream(),()->{}));assertEquals(1,source.calls);assertEquals(java.util.Arrays.asList(8),source.requested);}
        finally{owner.close();}
    }
    @Test public void shareLimitRequiresExactEofAndExtraByteIsCharged()throws Exception{
        DocumentHandles handles=new DocumentHandles(()->0,r->{throw new AssertionError();},budget(20,20,1));
        Source source=new Source(8);DocumentHandles.ShareSource owner=handles.takeShareSource(handles.grantRead(source),7);
        try{failure("SHARE_TOO_LARGE",()->owner.copyTo(new ByteArrayOutputStream(),()->{}));assertEquals(1,source.calls);assertEquals(8,(int)source.requested.get(0));}
        finally{owner.close();}
    }
    @Test public void sourceRevocationDoesNotRaceCloseAndFailedCleanupRetainsDocumentSlot()throws Exception{
        List<Runnable> cleanup=new ArrayList<>();DocumentHandles handles=new DocumentHandles(()->0,cleanup::add,budget(128,256,1));
        Source source=new Source(3);source.failClose=true;DocumentHandles.ShareSource owner=handles.takeShareSource(handles.grantRead(source),10);
        DocumentHandles replacement=handles.revokeAndRenew();assertTrue(cleanup.isEmpty());assertFalse(source.closed);
        failure("HANDLE_LIMIT",replacement::reserveRead);
        failure("INVALID_HANDLE",()->owner.copyTo(new ByteArrayOutputStream(),()->{}));
        try{owner.close();fail();}catch(IOException expected){}
        failure("HANDLE_LIMIT",replacement::reserveRead);
        source.failClose=false;owner.close();DocumentHandles.Reservation next=replacement.reserveRead();next.cancel();
    }
    @Test public void sourceCanBeConsumedOnlyOnceAndCannotBeReadByItsOldHandle()throws Exception{
        DocumentHandles handles=new DocumentHandles(()->0,r->{throw new AssertionError();},budget(128,256,1));
        Source source=new Source(3);String handle=handles.grantRead(source);DocumentHandles.ShareSource owner=handles.takeShareSource(handle,10);
        try{
            failure("HANDLE_BUSY",()->handles.read(handle,0,1));
            owner.copyTo(new ByteArrayOutputStream(),()->{});
            failure("INVALID_HANDLE",()->handles.read(handle,0,1));
            failure("INVALID_HANDLE",()->owner.copyTo(new ByteArrayOutputStream(),()->{}));
            failure("HANDLE_LIMIT",handles::reserveRead);
        }finally{owner.close();}handles.reserveRead().cancel();
    }
    @Test public void nineMiBSourceUsesOneBoundedBufferAndRetainsCloseOwnership()throws Exception{
        long length=9L*1024*1024+3;
        DocumentHandles handles=new DocumentHandles(()->0,r->{throw new AssertionError();},budget(length+1,length+1,1));
        final long[] position={0};final boolean[] closed={false};
        InputStream input=new InputStream(){
            public int read(){throw new AssertionError();}
            public int read(byte[] bytes,int offset,int count){assertTrue(count<=32768);if(position[0]==length)return -1;int n=(int)Math.min(count,length-position[0]);position[0]+=n;return n;}
            public void close(){closed[0]=true;}
        };
        DocumentHandles.ShareSource owner=handles.takeShareSource(handles.grantRead(input),length);
        long[] written={0};OutputStream output=new OutputStream(){public void write(int value){fail();}public void write(byte[] bytes,int offset,int count){assertTrue(bytes.length<=32768);written[0]+=count;}};
        try{owner.copyTo(output,()->{});assertEquals(length,written[0]);assertFalse(closed[0]);}
        finally{owner.close();}assertTrue(closed[0]);
    }
}

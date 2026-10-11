package com.jarvys.factory.runtime;
import com.jarvys.factory.contract.ResourceBudgets;
import java.io.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
/** Pure synthetic clock/stream checks. No provider, file target, or hardware. */
public class DocumentHandlesMetadataTest {
 private long now;
 private final Queue<Runnable> cleanup = new ArrayDeque<>();
 private final List<DocumentHandles> registries = new ArrayList<>();
 private ResourceBudgets budget(long bytes, long lease) {
  Map<String,Object> d=new LinkedHashMap<>();d.put("handleBytes",bytes);d.put("sessionBytes",bytes);d.put("leaseMs",lease);
  Map<String,Object> b=new LinkedHashMap<>();b.put("version",1);b.put("documents",d);return ResourceBudgets.parse(b);
 }
 private DocumentHandles handles(long bytes,long lease) { DocumentHandles h=new DocumentHandles(()->now,cleanup::add,budget(bytes,lease));registries.add(h);return h; }
 @After public void close(){for(DocumentHandles h:registries)h.revokeAll();Runnable r;while((r=cleanup.poll())!=null)r.run();}
 private void invalid(DocumentHandles h,String token)throws Exception{try{h.metadata(token);fail("Expected invalid handle");}catch(FactoryException expected){assertEquals("INVALID_HANDLE",expected.code);}}
 @Test public void signedLongCapacityAndLeaseAreDisclosedWithoutNarrowing()throws Exception{
  DocumentHandles h=handles(4294967296L,86400000L);String token=h.grantRead(new ByteArrayInputStream(new byte[0]));
  assertEquals(4294967296L,h.metadata(token).maximumBytes);assertEquals(86400000L,h.metadata(token).expiresAfterMs);
 }
 @Test public void signedReducedLimitsRemainReduced()throws Exception{
  DocumentHandles h=handles(7L,10L);String token=h.grantWrite(new ByteArrayOutputStream());
  assertEquals(7L,h.metadata(token).maximumBytes);assertEquals(10L,h.metadata(token).expiresAfterMs);
 }
 @Test public void leaseStartsAtReservationAndMetadataNeverRenewsIt()throws Exception{
  DocumentHandles h=handles(5L,100L);DocumentHandles.Reservation r=h.reserveRead();now=40;r.beginOpen();
  now=60;String token=r.grantRead(new ByteArrayInputStream(new byte[]{1,2}));assertEquals(40L,h.metadata(token).expiresAfterMs);
  now=99;h.read(token,0,1);assertEquals(1L,h.metadata(token).expiresAfterMs);assertEquals(1L,h.metadata(token).expiresAfterMs);
  now=100;invalid(h,token);
 }
 @Test public void foreignClosedAndRevokedMetadataAreRejected()throws Exception{
  DocumentHandles h=handles(5L,100L),other=handles(5L,100L);String token=h.grantRead(new ByteArrayInputStream(new byte[0]));
  invalid(other,token);h.close(token);invalid(h,token);String next=h.grantWrite(new ByteArrayOutputStream());h.revokeAll();invalid(h,next);
 }
 @Test public void metadataDoesNotConsumeBytesOrOpenOrReadProvider()throws Exception{
  DocumentHandles h=handles(1L,100L);String token=h.grantRead(new InputStream(){public int read(){throw new AssertionError();}public int read(byte[] b){b[0]=1;return 1;}});
  for(int i=0;i<100;i++)assertEquals(1L,h.metadata(token).maximumBytes);
  assertEquals(1L,h.read(token,0,1).nextOffset);
 }
 @Test public void ungrantedAndOpeningReservationsCannotPublishLiveMetadata()throws Exception{
  DocumentHandles h=handles(1L,100L);DocumentHandles.Reservation r=h.reserveRead();invalid(h,r.token());r.beginOpen();invalid(h,r.token());r.cancel();r.abortOpen();
 }
}

package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLHandshakeException;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

/** No network/provider targets. Sources and sinks count bytes without materializing whole transfers. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=33, manifest=org.robolectric.annotation.Config.NONE)
public class NativeNetworkTransferTest {
    static final NativeOperationAuthorization.Access ALLOW = () -> {};
    final Queue<Runnable> cleanup = new ConcurrentLinkedQueue<>();
    final List<DocumentHandles> registries = new ArrayList<>();
    final ResourceBudgets budgets = ResourceBudgets.legacy();
    DocumentHandles docs = documents(budgets);
    DocumentHandles documents(ResourceBudgets budget) {
        DocumentHandles value = new DocumentHandles(() -> 0, cleanup::add, budget); registries.add(value); return value;
    }
    @After public void close() { for (DocumentHandles handles : registries) handles.revokeAll(); drain(); }
    void drain() { Runnable task; while ((task=cleanup.poll()) != null) task.run(); }
    interface Checked { void run() throws Exception; }
    static void failure(String code, Checked operation) throws Exception {
        try { operation.run(); fail("Expected " + code); } catch (FactoryException e) { assertEquals(code, e.code); }
    }
    static class Source extends InputStream {
        long remaining, bytes; int calls, largest; Runnable after;
        Source(long remaining) { this.remaining=remaining; }
        public int read() { throw new AssertionError("Unbounded/single-byte path"); }
        public int read(byte[] data, int offset, int count) {
            calls++; largest=Math.max(largest,count);
            if (remaining == 0) return -1;
            int actual=(int)Math.min(count,remaining); remaining-=actual; bytes+=actual;
            if (after != null) after.run(); return actual;
        }
    }
    static class Sink extends OutputStream {
        long bytes; int calls, largest; Runnable after;
        public void write(int value) { throw new AssertionError("Single-byte path"); }
        public void write(byte[] data, int offset, int count) throws IOException {
            calls++; largest=Math.max(largest,count); bytes+=count; if (after != null) after.run();
        }
    }
    static class Fake extends HttpURLConnection {
        int code=200, disconnects, inputOpens, outputOpens;
        long length=-1; String location; InputStream input=new Source(0); OutputStream output=new Sink(); Runnable onHeaders;
        IOException headersFailure; final Map<String,String> requestHeaders=new LinkedHashMap<>();
        Fake() throws Exception { super(new URL("https://fake.invalid/a")); }
        public void connect() { throw new AssertionError("Not a real connection"); }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnects++; }
        public void setFixedLengthStreamingMode(long length) { this.length=length; }
        public void setRequestProperty(String key,String value) { requestHeaders.put(key,value); }
        public OutputStream getOutputStream() { outputOpens++; return output; }
        public InputStream getInputStream() { inputOpens++; return input; }
        public InputStream getErrorStream() { inputOpens++; return input; }
        public int getResponseCode() throws IOException { if (onHeaders!=null) onHeaders.run(); if(headersFailure!=null)throw headersFailure; return code; }
        public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        public Map<String,List<String>> getHeaderFields() { return Collections.singletonMap("x-result", Collections.singletonList("yes")); }
    }
    static class Transport {
        final Queue<Fake> queue=new ArrayDeque<>(); final AtomicInteger opens=new AtomicInteger(); final List<String> urls=new ArrayList<>();
        final AtomicLong clock=new AtomicLong(); boolean cleartext;
        NativeNetwork network=new NativeNetwork(url -> { opens.incrementAndGet(); urls.add(url.toExternalForm()); if(queue.isEmpty()) throw new AssertionError("Unexpected new connection"); return queue.remove(); }, host -> cleartext, clock::get);
        Transport(Fake... values) { queue.addAll(Arrays.asList(values)); }
    }
    JSONObject args(String handle,long max) throws Exception { return new JSONObject().put("url","https://fake.invalid/a").put("handle",handle).put("maxBytes",max); }
    NetworkTransferRequest down(String handle,long max) throws Exception { return NetworkTransferRequest.download(args(handle,max),budgets); }
    NetworkTransferRequest up(String handle,long length) throws Exception { return NetworkTransferRequest.upload(args(handle,Math.max(1,length)).put("length",length),budgets); }
    JSONObject run(NetworkTransferRequest request, Transport transport) throws Exception { return transport.network.executeTransfer(request,docs,ALLOW,new NativeNetwork.Cancellation()); }

    @Test public void oneMiBUploadIsChunkedAndDoesNotProbeOrReadResponse() throws Exception {
        Source source=new Source(1024*1024+1); String token=docs.grantRead(source); Fake fake=new Fake(); fake.input=new Source(Long.MAX_VALUE);
        JSONObject result=run(up(token,1024*1024),new Transport(fake));
        assertTrue(result.getBoolean("complete")); assertEquals(1024*1024,result.getLong("requestBytesWritten"));
        assertEquals(1,source.remaining); assertEquals(8192,source.largest); assertEquals(8192,((Sink)fake.output).largest);
        assertEquals(1024*1024,fake.length); assertEquals(0,fake.inputOpens); assertEquals(1024*1024,result.getLong("nextOffset"));
        assertFalse(result.getBoolean("remoteCommitConfirmed")); assertEquals(1024*1024+1,docs.read(token,1024*1024,1).nextOffset);
    }
    @Test public void oneMiBDownloadWritesBoundedChunksAndRetainsHandle() throws Exception {
        Sink sink=new Sink(); String token=docs.grantWrite(sink); Fake fake=new Fake(); Source response=new Source(1024*1024); fake.input=response;
        JSONObject result=run(down(token,1024*1024+1),new Transport(fake));
        assertTrue(result.getBoolean("complete")); assertTrue(result.getBoolean("eofConfirmed")); assertEquals(1024*1024,sink.bytes);
        assertEquals(8192,sink.largest); assertEquals(8192,response.largest); assertFalse(result.getBoolean("providerCommitConfirmed"));
        assertFalse(result.getBoolean("atomicReplacement")); docs.write(token,sink.bytes,"eA=="); assertEquals(1024*1024+1,sink.bytes);
    }
    @Test public void exactDownloadLimitDoesNotPerformExtraReadOrClaimEOF() throws Exception {
        Sink sink=new Sink(); String token=docs.grantWrite(sink); Fake fake=new Fake(); Source response=new Source(8); fake.input=response;
        JSONObject result=run(down(token,8),new Transport(fake));
        assertEquals("TRANSFER_LIMIT_REACHED",result.getString("error")); assertFalse(result.getBoolean("complete"));
        assertFalse(result.getBoolean("eofConfirmed")); assertEquals(1,response.calls); assertEquals(8,sink.bytes);
    }
    @Test public void denyHasNoTrafficAndNoProviderIOAndDoesNotConsumeHandle() throws Exception {
        Source source=new Source(2); String token=docs.grantRead(source); Transport transport=new Transport(new Fake());
        try { transport.network.executeTransfer(up(token,1),docs,()->{throw new SecurityException();},new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) {}
        assertEquals(0,transport.opens.get()); assertEquals(0,source.calls); assertEquals(1,docs.read(token,0,1).nextOffset);
    }
    @Test public void invalidOwnerModeOffsetAndInsufficientQuotaFailBeforeConnection() throws Exception {
        String token=docs.grantRead(new Source(5)); Transport transport=new Transport(new Fake());
        failure("WRONG_MODE",()->run(down(token,1),transport));
        failure("INVALID_OFFSET",()->run(NetworkTransferRequest.upload(args(token,1).put("length",1).put("offset",1),budgets),transport));
        DocumentHandles other=documents(budgets);
        failure("INVALID_HANDLE",()->transport.network.executeTransfer(up(token,1),other,ALLOW,new NativeNetwork.Cancellation()));
        assertEquals(0,transport.opens.get());
    }
    @Test public void transferClaimPreventsConcurrentChunkCallsAndReleasesAfterward() throws Exception {
        Source source=new Source(3); String token=docs.grantRead(source); Fake fake=new Fake();
        ((Sink)fake.output).after=()->{try{failure("HANDLE_BUSY",()->docs.read(token,1,1));}catch(Exception e){throw new AssertionError(e);}};
        run(up(token,1),new Transport(fake)); assertEquals(2,docs.read(token,1,1).nextOffset);
    }
    @Test public void prematureEOFReportsConsumedPositionWithoutReplay() throws Exception {
        Source source=new Source(3); String token=docs.grantRead(source); Fake fake=new Fake();
        JSONObject result=run(up(token,8),new Transport(fake));
        assertEquals("TRANSFER_SOURCE_EOF",result.getString("error")); assertEquals(3,result.getLong("documentBytesRead"));
        assertEquals(3,result.getLong("requestBytesWritten")); assertFalse(result.getBoolean("complete"));
    }
    @Test public void allUploadRedirectsRefuseReplayAndConsumeSourceOnlyOnce() throws Exception {
        for(int status:new int[]{301,302,303,307,308}) {
            Source source=new Source(1); String token=docs.grantRead(source); Fake fake=new Fake(); fake.code=status; fake.location="/second";
            Transport transport=new Transport(fake);
            JSONObject result=run(NetworkTransferRequest.upload(args(token,1).put("length",1).put("redirect","follow"),budgets),transport);
            assertEquals("UPLOAD_REDIRECT_NOT_REPLAYABLE",result.getString("error")); assertEquals(1,transport.opens.get()); assertEquals(1,source.calls);
            docs.close(token); drain();
        }
    }
    @Test public void uploadManualRedirectReturnsMetadataAndNoReplay() throws Exception {
        String token=docs.grantRead(new Source(1)); Fake fake=new Fake(); fake.code=307;
        JSONObject result=run(NetworkTransferRequest.upload(args(token,1).put("length",1).put("redirect","manual"),budgets),new Transport(fake));
        assertTrue(result.getBoolean("complete")); assertFalse(result.getBoolean("ok")); assertEquals(307,result.getInt("status"));
    }
    @Test public void downloadValidatesRedirectsBeforeFirstWriteAndStripsCrossOriginHeaders() throws Exception {
        Sink sink=new Sink(); String token=docs.grantWrite(sink); Fake a=new Fake(),b=new Fake(); a.code=302; a.location="https://another.invalid/final";
        b.input=new Source(3); a.onHeaders=()->assertEquals(0,sink.bytes); b.onHeaders=()->assertEquals(0,sink.bytes);
        JSONObject args=args(token,4).put("headers",new JSONObject().put("Authorization","Bearer inline").put("X-Custom","private"));
        Transport transport=new Transport(a,b); JSONObject result=run(NetworkTransferRequest.download(args,budgets),transport);
        assertEquals(2,transport.opens.get()); assertEquals(0,a.inputOpens); assertTrue(b.requestHeaders.isEmpty());
        assertEquals(3,sink.bytes); assertEquals(1,result.getInt("redirects")); assertTrue(result.getBoolean("complete"));
    }
    @Test public void sameOriginRedirectPreservesHeaders() throws Exception {
        String token=docs.grantWrite(new Sink()); Fake a=new Fake(),b=new Fake(); a.code=302;a.location="/next";
        run(NetworkTransferRequest.download(args(token,2).put("headers",new JSONObject().put("X-Private","same")),budgets),new Transport(a,b));
        assertEquals("same",b.requestHeaders.get("x-private"));
    }
    @Test public void invalidDowngradeAndExcessRedirectsNeverWrite() throws Exception {
        for(String destination:new String[]{"file:///private", "http://fake.invalid/next", "https://user:password@fake.invalid/"}) {
            Sink sink=new Sink(); String token=docs.grantWrite(sink); Fake a=new Fake(); a.code=307; a.location=destination;
            JSONObject result=run(down(token,8),new Transport(a)); assertFalse(result.getBoolean("complete")); assertEquals(0,sink.bytes);
            docs.close(token);drain();
        }
        String token=docs.grantWrite(new Sink());Fake a=new Fake();a.code=302;a.location="/next";
        JSONObject result=run(NetworkTransferRequest.download(args(token,8).put("maxRedirects",0),budgets),new Transport(a));
        assertEquals("TOO_MANY_REDIRECTS",result.getString("error"));
    }
    @Test public void optionalRequestBodyUsesGeneralHTTPMethodAndRemainsBounded() throws Exception {
        Sink sink=new Sink(); String token=docs.grantWrite(sink); Fake fake=new Fake();fake.input=new Source(2);
        JSONObject result=run(NetworkTransferRequest.download(args(token,4).put("method","PUT").put("body","abc"),budgets),new Transport(fake));
        assertEquals("PUT",fake.getRequestMethod());assertEquals(3,fake.length);assertEquals(3,result.getLong("requestBytesWritten"));assertEquals(2,sink.bytes);
        failure("INVALID_ARGUMENT",()->NetworkTransferRequest.download(args(token,4).put("method","GET").put("body","x"),budgets));
    }
    @Test public void redirect303DropsInlinePostBodyAndContentHeaders() throws Exception {
        String token=docs.grantWrite(new Sink());Fake a=new Fake(),b=new Fake();a.code=303;a.location="/after";
        run(NetworkTransferRequest.download(args(token,2).put("method","POST").put("body","abc")
                .put("headers",new JSONObject().put("Content-Type","text/plain")),budgets),new Transport(a,b));
        assertEquals("GET",b.getRequestMethod());assertEquals(0,b.outputOpens);assertFalse(b.requestHeaders.containsKey("content-type"));
    }
    @Test public void writeFailureReportsUnknownPartialAndRetiresTarget() throws Exception {
        Sink sink=new Sink(){public void write(byte[] bytes,int offset,int count)throws IOException{this.bytes+=2;throw new IOException("private");}};
        String token=docs.grantWrite(sink);Fake fake=new Fake();fake.input=new Source(4);
        JSONObject result=run(down(token,8),new Transport(fake));assertEquals("DOCUMENT_IO",result.getString("error"));
        assertTrue(result.getBoolean("documentPositionUncertain"));assertTrue(result.isNull("nextOffset"));assertEquals(0,result.getLong("documentBytesWritten"));
        assertEquals(2,sink.bytes);assertTrue(result.getBoolean("partialEffectsPossible"));assertFalse(result.toString().contains("private"));
        failure("INVALID_HANDLE",()->docs.write(token,0,"eA=="));
    }
    @Test public void outputFailureReportsUncertainSentBytesAndKnownConsumedSource() throws Exception {
        String token=docs.grantRead(new Source(4));Fake fake=new Fake();fake.output=new Sink(){public void write(byte[] b,int o,int c)throws IOException{throw new IOException();}};
        JSONObject result=run(up(token,4),new Transport(fake));assertEquals("NETWORK_ERROR",result.getString("error"));
        assertEquals(4,result.getLong("documentBytesRead"));assertEquals(0,result.getLong("requestBytesWritten"));
        assertTrue(result.getBoolean("requestWriteUncertain"));assertTrue(result.getBoolean("requestMayHaveReachedPeer"));
    }
    @Test public void revocationAfterReadPreventsBodyWriteAndPreservesNativeProgressOnly() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);Source source=new Source(4);source.after=()->allowed.set(false);
        String token=docs.grantRead(source);Fake fake=new Fake();Transport transport=new Transport(fake);NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();
        try{transport.network.executeTransfer(up(token,4),docs,()->{if(!allowed.get())throw new SecurityException();},cancel);fail();}catch(SecurityException expected){}
        assertEquals(0,((Sink)fake.output).bytes);assertEquals(4,cancel.transferProgress().getLong("documentBytesRead"));
        failure("INVALID_HANDLE",()->docs.read(token,4,1));
    }
    @Test public void revocationAfterWriteCannotEraseKnownEffects() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);Sink sink=new Sink();sink.after=()->allowed.set(false);
        String token=docs.grantWrite(sink);Fake fake=new Fake();fake.input=new Source(4);NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();
        try{new Transport(fake).network.executeTransfer(down(token,8),docs,()->{if(!allowed.get())throw new SecurityException();},cancel);fail();}catch(SecurityException expected){}
        assertEquals(4,sink.bytes);assertEquals(4,cancel.transferProgress().getLong("documentBytesWritten"));
        assertFalse(cancel.transferProgress().getBoolean("complete"));
    }
    @Test public void documentCancellationBetweenChunksPreventsFurtherTraffic() throws Exception {
        Source source=new Source(20000);String token=docs.grantRead(source);Fake fake=new Fake();((Sink)fake.output).after=docs::cancelAll;
        JSONObject result=run(up(token,20000),new Transport(fake));assertEquals("INVALID_HANDLE",result.getString("error"));
        assertEquals(8192,source.bytes);assertEquals(8192,result.getLong("requestBytesWritten"));
    }
    @Test public void cancellationOfBlockedProviderRetiresWithoutInlineCloseOrFalseZero() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closes=new AtomicInteger();
        InputStream source=new InputStream(){public int read(){throw new AssertionError();}public int read(byte[] b,int o,int n){entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return 1;}public void close(){closes.incrementAndGet();}};
        String token=docs.grantRead(source);Fake fake=new Fake();Transport transport=new Transport(fake);NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();
        AtomicReference<Throwable> failure=new AtomicReference<>();Thread worker=new Thread(()->{try{transport.network.executeTransfer(upUnchecked(token),docs,ALLOW,cancel);}catch(Throwable e){failure.set(e);}});
        worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));cancel.cancel();assertEquals(0,closes.get());
        assertTrue(cancel.transferProgress().getBoolean("documentPositionUncertain"));release.countDown();worker.join(2000);assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof FactoryException);assertEquals("CANCELLED",((FactoryException)failure.get()).code);
        assertEquals(1,cancel.transferProgress().getLong("documentBytesRead"));assertEquals(0,((Sink)fake.output).bytes);drain();assertEquals(1,closes.get());
    }
    NetworkTransferRequest upUnchecked(String token){try{return up(token,2);}catch(Exception e){throw new AssertionError(e);}}
    @Test public void elapsedDeadlineCancelsBeforeNextChunkAndRecordsPartialEffects() throws Exception {
        String token=docs.grantRead(new Source(10000));Fake fake=new Fake();Transport transport=new Transport(fake);
        ((Sink)fake.output).after=()->transport.clock.set(TimeUnit.MILLISECONDS.toNanos(11));NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();
        failure("TIMEOUT",()->transport.network.executeTransfer(NetworkTransferRequest.upload(args(token,10000).put("length",10000).put("timeoutMs",10),budgets),docs,ALLOW,cancel));
        assertEquals(8192,cancel.transferProgress().getLong("requestBytesWritten"));assertTrue(fake.disconnects>0);
    }
    @Test public void headerFailureAndTLSFailureDoNotTouchTarget() throws Exception {
        Sink sink=new Sink();String token=docs.grantWrite(sink);Fake fake=new Fake();fake.headersFailure=new SSLHandshakeException("private");
        JSONObject result=run(down(token,8),new Transport(fake));assertEquals("TLS_ERROR",result.getString("error"));assertEquals(0,sink.bytes);
        assertFalse(result.getBoolean("providerCommitConfirmed"));assertFalse(result.toString().contains("private"));
    }
    @Test public void cleartextUsesPlatformPolicyBeforeOpeningConnection() throws Exception {
        String token=docs.grantWrite(new Sink());Transport transport=new Transport(new Fake());
        JSONObject result=run(NetworkTransferRequest.download(args(token,8).put("url","http://fake.invalid/a"),budgets),transport);
        assertEquals("CLEARTEXT_NOT_PERMITTED",result.getString("error"));assertEquals(0,transport.opens.get());
    }
    @Test public void strictGrammarBudgetAndLongCountersAreNotBodyBufferSizes() throws Exception {
        String token=docs.grantRead(new Source(0));JSONObject args=args(token,1);
        for(Object value:new Object[]{1.5,"2",JSONObject.NULL,-1L,0L})
            failure("INVALID_ARGUMENT",()->NetworkTransferRequest.download(new JSONObject(args.toString()).put("maxBytes",value),budgets));
        failure("INVALID_ARGUMENT",()->NetworkTransferRequest.upload(args,budgets));
        failure("INVALID_ARGUMENT",()->NetworkTransferRequest.download(new JSONObject(args.toString()).put("path","/tmp/private"),budgets));
        failure("INVALID_ARGUMENT",()->NetworkTransferRequest.download(new JSONObject(args.toString()).put("handle","content://private"),budgets));
        failure("INVALID_ARGUMENT",()->NetworkTransferRequest.upload(new JSONObject(args.toString()).put("length",1).put("method","GET"),budgets));
        ResourceBudgets large=ResourceBudgets.maxima();long total=4L*1024*1024*1024;
        NetworkTransferRequest request=NetworkTransferRequest.upload(args(token,total).put("length",total).put("timeoutMs",3600000),large);
        assertEquals(total,request.length);assertEquals(3600000,request.timeoutMs);
        DocumentHandles largeDocs=documents(large);String largeToken=largeDocs.grantRead(new Source(0));Fake fake=new Fake();
        JSONObject result=new Transport(fake).network.executeTransfer(NetworkTransferRequest.upload(args(largeToken,total).put("length",total),large),largeDocs,ALLOW,new NativeNetwork.Cancellation());
        assertEquals(total,fake.length);assertEquals("TRANSFER_SOURCE_EOF",result.getString("error"));
    }
    @Test public void scopeBindsActualBodyHandleOffsetHeadersAndAllLimits() throws Exception {
        String token=docs.grantWrite(new Sink());JSONObject original=args(token,100).put("offset",5).put("method","PUT").put("body","private-body")
                .put("timeoutMs",180000).put("headers",new JSONObject().put("X-Scope","private"));
        NetworkTransferRequest request=NetworkTransferRequest.download(original,budgets);original.put("handle","a");
        JSONObject details=request.authorizationDetails();assertEquals(token,details.getString("handle"));assertEquals(5,details.getLong("offset"));
        assertEquals(100,details.getLong("maxBytes"));assertEquals(180000,details.getInt("timeoutMs"));assertEquals("PUT",details.getString("method"));
        assertEquals("private",details.getJSONObject("headers").getString("x-scope"));assertEquals(64,details.getString("bodySha256").length());
    }
    @Test public void quotaReservationRefundsUnusedButKeepsActualAndUnknownBytes() throws Exception {
        Map<String,Object> documentBudget=new HashMap<>();documentBudget.put("handleBytes",10L);documentBudget.put("sessionBytes",10L);
        Map<String,Object> root=new HashMap<>();root.put("version",1);root.put("documents",documentBudget);ResourceBudgets small=ResourceBudgets.parse(root);
        DocumentHandles handles=documents(small);String token=handles.grantWrite(new Sink());Fake fake=new Fake();fake.input=new Source(3);
        new Transport(fake).network.executeTransfer(NetworkTransferRequest.download(args(token,10),small),handles,ALLOW,new NativeNetwork.Cancellation());
        handles.write(token,3,"YWJjZGVmZw==");failure("DOCUMENT_QUOTA",()->handles.write(token,10,"eA=="));
    }
    @Test public void headResponseSkipsBodyAndZeroLengthUploadSkipsProviderRead() throws Exception {
        String target=docs.grantWrite(new Sink());Fake head=new Fake();head.input=new Source(100);
        JSONObject result=run(NetworkTransferRequest.download(args(target,1).put("method","HEAD"),budgets),new Transport(head));
        assertTrue(result.getBoolean("complete"));assertEquals(0,head.inputOpens);
        Source source=new Source(10);String token=docs.grantRead(source);Fake empty=new Fake();run(up(token,0),new Transport(empty));
        assertEquals(0,source.calls);assertEquals(0,empty.length);
    }
    @Test public void cancellationObjectCannotAuthorizeAnotherTransfer() throws Exception {
        String token=docs.grantWrite(new Sink());Transport transport=new Transport(new Fake());NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();
        transport.network.executeTransfer(down(token,1),docs,ALLOW,cancel);
        failure("INVALID_ARGUMENT",()->transport.network.executeTransfer(down(token,1),docs,ALLOW,cancel));assertEquals(1,transport.opens.get());
    }
    @Test public void missingResponseStreamAndFailedReadDoNotClaimEOF() throws Exception {
        String token=docs.grantWrite(new Sink());Fake missing=new Fake();missing.code=500;missing.input=null;
        JSONObject result=run(down(token,8),new Transport(missing));assertEquals("RESPONSE_BODY_UNAVAILABLE",result.getString("error"));assertFalse(result.getBoolean("eofConfirmed"));
        Fake broken=new Fake();broken.input=new InputStream(){public int read()throws IOException{throw new IOException();}};
        result=run(down(token,8),new Transport(broken));assertEquals("NETWORK_ERROR",result.getString("error"));
        assertTrue(result.getBoolean("responseReadUncertain"));assertEquals(0,result.getLong("documentBytesWritten"));
    }
    @Test public void originalSourceGuardIsNotUnwrappedOrBypassed() throws Exception {
        AtomicInteger calls=new AtomicInteger();InputStream guarded=new InputStream(){
            public int read(){throw new AssertionError();}
            public int read(byte[] bytes,int offset,int length)throws IOException{if(calls.incrementAndGet()>1)throw new IOException("Broker lease revoked");return length;}
        };
        String token=docs.grantRead(guarded);Fake fake=new Fake();JSONObject result=run(up(token,16384),new Transport(fake));
        assertEquals("DOCUMENT_IO",result.getString("error"));assertEquals(8192,result.getLong("requestBytesWritten"));
        assertTrue(result.getBoolean("documentPositionUncertain"));assertEquals(2,calls.get());
    }
    @Test public void realDeadlineDisconnectsBlockedNetworkWithoutWritingTarget() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Sink sink=new Sink();String token=docs.grantWrite(sink);
        Fake fake=new Fake(){public int getResponseCode()throws IOException{entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException();}return 200;}public void disconnect(){super.disconnect();release.countDown();}};
        NativeNetwork.Cancellation cancel=new NativeNetwork.Cancellation();Transport transport=new Transport(fake);
        failure("TIMEOUT",()->transport.network.executeTransfer(NetworkTransferRequest.download(args(token,8).put("timeoutMs",50),budgets),docs,ALLOW,cancel));
        assertEquals(0,entered.getCount());assertTrue(fake.disconnects>0);assertEquals(0,sink.bytes);
        assertEquals("TIMEOUT",cancel.transferProgress().getString("error"));
    }
    @Test public void sessionReservationPreventsCompetingHandleSpendThenRefunds() throws Exception {
        Map<String,Object> values=new HashMap<>();values.put("handleBytes",10L);values.put("sessionBytes",10L);
        Map<String,Object> root=new HashMap<>();root.put("version",1);root.put("documents",values);ResourceBudgets small=ResourceBudgets.parse(root);
        DocumentHandles handles=documents(small);String first=handles.grantWrite(new Sink()),second=handles.grantWrite(new Sink());Fake fake=new Fake();
        fake.onHeaders=()->{try{failure("DOCUMENT_QUOTA",()->handles.write(second,0,"eA=="));}catch(Exception e){throw new AssertionError(e);}};
        new Transport(fake).network.executeTransfer(NetworkTransferRequest.download(args(first,10),small),handles,ALLOW,new NativeNetwork.Cancellation());
        handles.write(second,0,"YWJjZGVmZ2hp"); // Nine bytes leave only one across the shared session.
        Transport transport=new Transport(new Fake());
        failure("DOCUMENT_QUOTA",()->transport.network.executeTransfer(NetworkTransferRequest.download(args(first,2),small),handles,ALLOW,new NativeNetwork.Cancellation()));
        assertEquals(0,transport.opens.get());
    }
    @Test public void responseHeaderLimitFailsBeforeAnyTargetWrite() throws Exception {
        Sink sink=new Sink();String token=docs.grantWrite(sink);Fake fake=new Fake(){public Map<String,List<String>> getHeaderFields(){return Collections.singletonMap("x-huge",Collections.singletonList(new String(new char[300]).replace('\0','x')));}};
        fake.input=new Source(2);JSONObject result=run(NetworkTransferRequest.download(args(token,8).put("maxHeaderBytes",256),budgets),new Transport(fake));
        assertEquals("RESPONSE_TOO_LARGE",result.getString("error"));assertEquals(0,sink.bytes);assertEquals(0,fake.inputOpens);
    }
    @Test public void transferDisclosureBindsLongDocumentBoundsAndCannotClaimContentSnapshot() throws Exception {
        final String NONCE = "a".repeat(64);
        com.jarvys.factory.contract.ResourceBudgets resources = com.jarvys.factory.contract.ResourceBudgets.parse(java.util.Map.of("version",1,"documents",java.util.Map.of("handleBytes",4294967296L,"sessionBytes",4294967296L)));
        JSONObject args = new JSONObject().put("url","https://example.com").put("handle",NONCE).put("length",4294967296L).put("maxBytes",4294967296L);
        JSONObject details=NetworkTransferRequest.upload(args,resources).authorizationDetails();
        NativeOperationRequest parsed=NativeOperationRequest.parse("network.upload",details);
        assertEquals(4294967296L,parsed.details().getLong("length"));
        assertThrows(FactoryException.class,()->NativeOperationRequest.parse("network.upload",parsed.details().put("contentSnapshotBound",true)));
        assertThrows(FactoryException.class,()->NativeOperationRequest.parse("network.download",details));
        JSONObject download=NetworkTransferRequest.download(new JSONObject().put("url","https://example.com").put("handle",NONCE),resources).authorizationDetails();
        assertEquals("network",NativeOperationRequest.parse("network.download",download).capability);
        assertThrows(FactoryException.class,()->NativeOperationRequest.parse("network.download",download.put("offset",1)));
    }
}

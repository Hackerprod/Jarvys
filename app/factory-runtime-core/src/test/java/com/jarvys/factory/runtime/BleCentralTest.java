package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Deterministic fake-platform tests. Never loads or invokes Android Bluetooth. */
public final class BleCentralTest {
    interface Task { void run() throws Exception; }
    static int tests, checks;
    static void test(String name, Task task) throws Exception { task.run(); tests++; System.out.println("PASS " + name); }
    static void yes(boolean result) { checks++; if (!result) throw new AssertionError("Check " + checks + " failed"); }
    static void eq(Object expected, Object actual) { checks++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    static void error(String code, Task task) throws Exception { try { task.run(); throw new AssertionError("Expected " + code); } catch (FactoryException e) { eq(code, e.code); } }
    static String field(Map<String,Object> value, String key) { return (String)value.get(key); }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value) { return (List<Map<String,Object>>)value; }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    static final UUID SERVICE = UUID.fromString("12345678-1234-5678-1234-567812345678"), CHAR = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final class Gate implements BleCentral.Access { boolean denied, closed; int closes; public void check() { if (denied || closed) throw new SecurityException(); } public void close() { closes++; closed = true; } }
    static final class Time implements BleCentral.Clock { long now; public long elapsedMs() { return now; } }
    static final class Fake implements BleCentral.Platform {
        FakeScan scan; FakeLink link; final List<FakeLink> links = new ArrayList<>(); boolean rejectScan, throwScan, rejectConnect, synchronousConnect, synchronousExecute;
        public BleCentral.Scan newScan(BleCentral.ScanEvents events) { return scan = new FakeScan(events); }
        public BleCentral.Link newLink(Object device, BleCentral.LinkEvents events) { link = new FakeLink(events); links.add(link); return link; }
        final class FakeScan implements BleCentral.Scan {
            final BleCentral.ScanEvents events; boolean stopped, failStop; int stopCalls;
            FakeScan(BleCentral.ScanEvents events) { this.events = events; }
            public boolean start(List<UUID> ids) { if (throwScan) throw new SecurityException(); return !rejectScan; }
            public boolean stop() { stopCalls++; if (failStop) return false; stopped = true; return true; }
            void found(String key, byte[] data) { events.found(this, key, key, "Peripheral", -42, data); }
        }
        final class FakeLink implements BleCentral.Link {
            final BleCentral.LinkEvents events; BleCentral.Command pending; final List<BleCentral.Command> executions = new ArrayList<>();
            boolean closed, failClose, rejectExecute, throwExecute, rejectNotifications; int closes; final List<Boolean> notificationRouting = new ArrayList<>();
            FakeLink(BleCentral.LinkEvents events) { this.events = events; }
            public boolean connect() { if (synchronousConnect) events.state(this,true,0); return !rejectConnect; }
            public boolean execute(BleCentral.Command command) {
                executions.add(command); if (throwExecute) throw new SecurityException(); if (rejectExecute) return false;
                yes(pending == null); pending = command;
                if (synchronousExecute) finish(0, new byte[]{7}, null);
                return true;
            }
            public boolean close() { closes++; if (failClose) return false; closed = true; pending = null; return true; }
            public boolean localNotifications(Object attr, boolean enabled) { notificationRouting.add(enabled); return !rejectNotifications; }
            void connected() { events.state(this,true,0); }
            void finish(int status, byte[] value, List<BleCentral.Service> services) {
                BleCentral.Command op = pending; pending = null; if (op == null) throw new AssertionError("No pending operation");
                if ("requestMtu".equals(op.kind)) events.mtu(this,op.token,status,value == null ? 23 : (value[0] & 255) | ((value[1] & 255) << 8));
                else events.completed(this,op.token,op.kind,op.attribute,status,value,services);
            }
        }
    }
    static final class Fixture implements AutoCloseable {
        final Gate page = new Gate(); Gate connectionGrant = new Gate(); final Fake fake = new Fake(); final Time time = new Time(); final BleCentral core;
        String connectionId, attributeId, descriptorId, secondAttributeId; Object attribute = new Object(), second = new Object(), descriptor = new Object();
        Fixture() { this(BleCentral.Limits.defaults()); }
        Fixture(BleCentral.Limits limits) { core = new BleCentral(fake,time,limits,page); }
        String scan() throws Exception { return field(core.scan(Collections.emptyList(),100,new Gate()),"scanId"); }
        String device() throws Exception { String scan = scan(); fake.scan.found("private-address",new byte[]{1}); String id = field(events(scan).get(0),"deviceId"); core.stopScan(scan); return id; }
        void connect() throws Exception { connectionGrant = new Gate(); connectionId = field(core.connect(device(),connectionGrant),"connectionId"); fake.link.connected(); events(connectionId); }
        void discover() throws Exception {
            connect(); core.services(connectionId,new Gate());
            BleCentral.Attribute d = new BleCentral.Attribute(CCCD,descriptor,0,Collections.emptyList());
            BleCentral.Attribute c = new BleCentral.Attribute(CHAR,attribute,2|4|8|16|32,Collections.singletonList(d));
            BleCentral.Attribute duplicate = new BleCentral.Attribute(CHAR,second,2|4|8|16|32,Collections.emptyList());
            fake.link.finish(0,null,Collections.singletonList(new BleCentral.Service(SERVICE,1,0,Arrays.asList(c,duplicate))));
            List<Map<String,Object>> entries = list(object(events(connectionId).get(0).get("catalog")).get("entries"));
            attributeId = field(entries.get(1),"attributeId"); descriptorId = field(entries.get(2),"attributeId"); secondAttributeId = field(entries.get(3),"attributeId");
        }
        List<Map<String,Object>> events(String id) throws Exception { BleCentral.Poll poll = core.poll(id); List<Map<String,Object>> events = list(poll.result.get("events")); poll.check(); poll.commit(); return events; }
        String read(Gate grant) throws Exception { return field(core.read(connectionId,attributeId,grant),"operationId"); }
        String subscribe(Gate grant) throws Exception { String id = field(core.subscribe(connectionId,attributeId,"notify",grant),"subscriptionId"); fake.link.finish(0,null,null); events(connectionId); return id; }
        public void close() { if (fake.scan != null) fake.scan.failStop = false; for (Fake.FakeLink link : fake.links) link.failClose = false; core.close(); }
    }
    static BleCentral.Limits limits(int queue, int events, int value) { return new BleCentral.Limits(4,128,queue,events,Math.min(events,32),64,512,16,value,30000,30000); }
    public static void main(String[] args) throws Exception {
        test("scan uses opaque page-owned handles and copies advertisement", () -> { try (Fixture f = new Fixture()) {
            Gate grant = new Gate(); String scan = field(f.core.scan(Collections.singletonList(SERVICE),100,grant),"scanId");
            byte[] bytes = {1,2,3}; f.fake.scan.found("AA:BB:CC:DD:EE:FF",bytes); bytes[0]=9;
            List<Map<String,Object>> events=f.events(scan); eq("AQID",events.get(0).get("advertisementBase64")); yes(!new JSONObject(events.get(0)).toString().contains("AA:BB")); yes(!grant.closed);
            f.core.stopScan(scan); yes(grant.closed); yes(f.fake.scan.stopped);
        }});
        test("scan bounded duration preserves terminal event without inventing readings", () -> { try (Fixture f=new Fixture()) {
            String scan=f.scan(); f.time.now=100; f.core.tick(); List<Map<String,Object>> events=f.events(scan); eq("scan_finished",events.get(0).get("type")); error("INVALID_HANDLE",()->f.core.poll(scan));
        }});
        test("scan ring drops oldest with counters and poll commits only once", () -> { try(Fixture f=new Fixture(limits(3,2,512))) {
            String scan=f.scan(); for(int i=0;i<5;i++)f.fake.scan.found("same",new byte[]{(byte)i});
            BleCentral.Poll p=f.core.poll(scan); eq("3",p.result.get("droppedEvents")); eq(2,list(p.result.get("events")).size()); eq(2,list(f.core.poll(scan).result.get("events")).size());
            p.commit(); p.commit(); eq(0,f.events(scan).size());
        }});
        test("stale poll does not remove subsequently received events", () -> { try(Fixture f=new Fixture()) {
            String scan=f.scan(); f.fake.scan.found("same",new byte[]{1}); BleCentral.Poll p=f.core.poll(scan); f.fake.scan.found("same",new byte[]{2}); p.commit(); eq(1,f.events(scan).size());
        }});
        test("scan cleanup failure retains handle and process slot for retry", () -> { try(Fixture f=new Fixture()) {
            String scan=f.scan(); f.fake.scan.failStop=true; eq(false,f.core.stopScan(scan).get("stopped")); yes(f.core.hasUnconfirmedCleanup()); error("BUSY",()->f.scan());
            f.fake.scan.failStop=false; eq(true,f.core.stopScan(scan).get("stopped")); yes(!f.core.hasUnconfirmedCleanup());
        }});
        test("synchronous scan failure and security exception release native slots", () -> { try(Fixture f=new Fixture()) {
            f.fake.rejectScan=true; error("UNAVAILABLE",()->f.scan()); yes(f.fake.scan.stopped); f.core.cancelAll(); f.fake.rejectScan=false; f.fake.throwScan=true; error("PERMISSION_DENIED",()->f.scan()); yes(f.fake.scan.stopped);
        }});
        test("scan stale callback after stop cannot recreate device authority", () -> { try(Fixture f=new Fixture()) {
            String scan=f.scan(); Fake.FakeScan old=f.fake.scan; f.core.stopScan(scan); old.found("late",new byte[]{1}); yes(!f.core.hasResources()); error("INVALID_HANDLE",()->f.core.poll(scan));
        }});
        test("page denial is enforced before any platform effect", () -> { try(Fixture f=new Fixture()) { f.page.denied=true; error("PERMISSION_DENIED",()->f.scan()); yes(f.fake.scan==null); }});
        test("connection authority survives initial acknowledgement", () -> { try(Fixture f=new Fixture()) { f.connect(); yes(!f.connectionGrant.closed); f.core.tick(); yes(!f.fake.link.closed); }});
        test("synchronous connect callback is correctly owned", () -> { try(Fixture f=new Fixture()) { f.fake.synchronousConnect=true; f.connect(); yes(!f.fake.link.closed); }});
        test("duplicate UUIDs get different native characteristic handles", () -> { try(Fixture f=new Fixture()) { f.discover(); yes(!f.attributeId.equals(f.secondAttributeId)); f.core.read(f.connectionId,f.secondAttributeId,new Gate()); yes(f.fake.link.pending.attribute==f.second); }});
        test("GATT operations are strictly serialized and read bytes copied", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate a=new Gate(),b=new Gate(); String id=f.read(a); f.core.read(f.connectionId,f.secondAttributeId,b); eq(2,f.fake.link.executions.size());
            byte[] bytes={3,4}; f.fake.link.finish(0,bytes,null); bytes[0]=9; eq(3,f.fake.link.executions.size()); yes(!a.closed); yes(!b.closed); Map<String,Object> event=f.events(f.connectionId).get(0); yes(a.closed); eq(id,event.get("operationId")); eq("AwQ=",event.get("valueBase64"));
            f.fake.link.finish(0,new byte[0],null); eq("",f.events(f.connectionId).get(0).get("valueBase64"));
        }});
        test("wrong token kind or native object cannot complete pending GATT operation", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.read(new Gate()); BleCentral.Command op=f.fake.link.pending;
            f.fake.link.events.completed(f.fake.link,op.token+1,op.kind,op.attribute,0,new byte[]{1},null);
            f.fake.link.events.completed(f.fake.link,op.token,"writeCharacteristic",op.attribute,0,null,null);
            f.fake.link.events.completed(f.fake.link,op.token,op.kind,new Object(),0,new byte[]{1},null);
            eq(0,f.events(f.connectionId).size()); f.fake.link.finish(0,new byte[]{4},null); eq(1,f.events(f.connectionId).size());
        }});
        test("queued cancellation never reaches platform and keeps connection", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.read(new Gate()); Gate queued=new Gate(); String id=field(f.core.write(f.connectionId,f.attributeId,new byte[]{42},"response",queued),"operationId");
            eq(false,f.core.cancel(id).get("writeMayHaveReachedDevice")); yes(!queued.closed); yes(!f.fake.link.closed); f.fake.link.finish(0,new byte[]{1},null); eq(2,f.fake.link.executions.size()); f.events(f.connectionId); yes(queued.closed);
        }});
        test("in-flight write cancellation closes link and honestly reports uncertainty", () -> { try(Fixture f=new Fixture()) {
            f.discover(); String id=field(f.core.write(f.connectionId,f.attributeId,new byte[]{42},"response",new Gate()),"operationId"); BleCentral.Command op=f.fake.link.pending;
            eq(true,f.core.cancel(id).get("writeMayHaveReachedDevice")); yes(f.fake.link.closed); List<Map<String,Object>> events=f.events(f.connectionId); eq("unknown",events.get(0).get("writeOutcome"));
            f.fake.link.events.completed(f.fake.link,op.token,op.kind,op.attribute,0,null,null); error("INVALID_HANDLE",()->f.core.poll(f.connectionId));
        }});
        test("write timeout closes connection and never retries", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.core.write(f.connectionId,f.attributeId,new byte[]{1},"response",new Gate()); f.time.now=30001; f.core.tick(); yes(f.fake.link.closed); eq(2,f.fake.link.executions.size()); eq("unknown",f.events(f.connectionId).get(0).get("writeOutcome"));
        }});
        test("write response confirms ATT only and no-response confirms stack only", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.core.write(f.connectionId,f.attributeId,new byte[]{1},"response",new Gate()); f.fake.link.finish(0,null,null); eq("att_acknowledged",f.events(f.connectionId).get(0).get("writeOutcome"));
            f.core.write(f.connectionId,f.attributeId,new byte[]{1},"noResponse",new Gate()); f.fake.link.finish(0,null,null); eq("sent_to_stack",f.events(f.connectionId).get(0).get("writeOutcome"));
        }});
        test("write rejection is not-sent and post-admission GATT error is unknown", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.fake.link.rejectExecute=true; f.core.write(f.connectionId,f.attributeId,new byte[]{1},"response",new Gate()); eq("not_sent",f.events(f.connectionId).get(0).get("writeOutcome"));
            f.fake.link.rejectExecute=false; f.core.write(f.connectionId,f.attributeId,new byte[]{1},"response",new Gate()); f.fake.link.finish(133,null,null); eq("unknown",f.events(f.connectionId).get(0).get("writeOutcome"));
        }});
        test("write throwing across native call is uncertain and link is retired", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.fake.link.throwExecute=true; f.core.write(f.connectionId,f.attributeId,new byte[]{1},"response",new Gate()); yes(f.fake.link.closed); eq("unknown",f.events(f.connectionId).get(0).get("writeOutcome"));
        }});
        test("negotiated MTU bounds no-response payload without truncation", () -> { try(Fixture f=new Fixture()) {
            f.discover(); error("TOO_LARGE",()->f.core.write(f.connectionId,f.attributeId,new byte[21],"noResponse",new Gate()));
            f.core.requestMtu(f.connectionId,100,new Gate()); f.fake.link.finish(0,new byte[]{(byte)247,0},null);
            Map<String,Object> event=f.events(f.connectionId).get(0); eq(100,event.get("requestedMtu")); eq(247,event.get("actualMtu"));
            f.core.write(f.connectionId,f.attributeId,new byte[244],"noResponse",new Gate()); eq(244,f.fake.link.pending.value().length); f.fake.link.finish(0,null,null); f.events(f.connectionId);
            error("TOO_LARGE",()->f.core.write(f.connectionId,f.attributeId,new byte[245],"noResponse",new Gate()));
        }});
        test("Android14-sized MTU callback reports actual517 and caps attribute at512", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.core.requestMtu(f.connectionId,64,new Gate()); f.fake.link.finish(0,new byte[]{5,2},null); eq(517,f.events(f.connectionId).get(0).get("actualMtu"));
            f.core.write(f.connectionId,f.attributeId,new byte[512],"noResponse",new Gate()); eq(512,f.fake.link.pending.value().length); error("INVALID_REQUEST",()->f.core.write(f.connectionId,f.attributeId,new byte[513],"response",new Gate()));
        }});
        test("unsolicited MTU cannot complete an unrelated pending operation", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.read(new Gate()); f.fake.link.events.mtu(f.fake.link,0,0,100); eq("readCharacteristic",f.fake.link.pending.kind); eq("mtu_changed",f.events(f.connectionId).get(0).get("type"));
        }});
        test("MTU operation timeout retires its callback epoch", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.core.requestMtu(f.connectionId,64,new Gate()); BleCentral.Command op=f.fake.link.pending; f.time.now=30000; f.core.tick(); yes(f.fake.link.closed); f.fake.link.events.mtu(f.fake.link,op.token,0,517); yes(!object(f.core.poll(f.connectionId).result).get("active").equals(true));
        }});
        test("subscription remains pending until CCCD write confirms", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); String sub=field(f.core.subscribe(f.connectionId,f.attributeId,"indicate",grant),"subscriptionId"); eq("writeDescriptor",f.fake.link.pending.kind); eq((byte)2,f.fake.link.pending.value()[0]);
            f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{1}); eq(0,f.events(f.connectionId).size()); f.fake.link.finish(0,null,null); yes(!grant.closed); f.events(f.connectionId);
            f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{1,2}); Map<String,Object> event=f.events(f.connectionId).get(0); eq(sub,event.get("subscriptionId")); eq("AQI=",event.get("valueBase64"));
        }});
        test("notification poll does not acquire or close another grant", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); f.subscribe(grant); for(int i=0;i<100;i++){f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{(byte)i});f.events(f.connectionId);} eq(0,grant.closes); yes(!grant.closed);
        }});
        test("unsubscribe disables CCCD and local routing before closing owner lease", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); String sub=f.subscribe(grant); f.core.unsubscribe(sub); eq((byte)0,f.fake.link.pending.value()[0]); yes(!grant.closed); f.fake.link.finish(0,null,null); yes(!grant.closed); eq(Arrays.asList(true,false),f.fake.link.notificationRouting);
            f.events(f.connectionId); yes(grant.closed); f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{1}); eq(0,f.events(f.connectionId).size()); error("INVALID_HANDLE",()->f.core.unsubscribe(sub));
        }});
        test("subscription revocation closes connection and scrubs queued notifications", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); f.subscribe(grant); f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{9}); grant.denied=true; f.core.tick(); yes(f.fake.link.closed); error("INVALID_HANDLE",()->f.core.poll(f.connectionId));
        }});
        test("CCCD is exclusively managed by subscription API", () -> { try(Fixture f=new Fixture()) {
            f.discover(); error("UNSUPPORTED",()->f.core.write(f.connectionId,f.descriptorId,new byte[]{1,0},"response",new Gate())); f.core.read(f.connectionId,f.descriptorId,new Gate()); eq("readDescriptor",f.fake.link.pending.kind);
        }});
        test("ordinary descriptor read and write are generic", () -> { try(Fixture f=new Fixture()) {
            f.connect(); f.core.services(f.connectionId,new Gate()); Object descriptor=new Object(); BleCentral.Attribute d=new BleCentral.Attribute(UUID.randomUUID(),descriptor,0,Collections.emptyList());
            BleCentral.Attribute c=new BleCentral.Attribute(CHAR,f.attribute,2,Collections.singletonList(d)); f.fake.link.finish(0,null,Collections.singletonList(new BleCentral.Service(SERVICE,0,0,Collections.singletonList(c))));
            String id=field(list(object(f.events(f.connectionId).get(0).get("catalog")).get("entries")).get(2),"attributeId");
            f.core.write(f.connectionId,id,new byte[]{3},"response",new Gate()); eq("writeDescriptor",f.fake.link.pending.kind); yes(f.fake.link.pending.attribute==descriptor);
        }});
        test("subscription native failure cannot fabricate active subscription", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); f.core.subscribe(f.connectionId,f.attributeId,"notify",grant); f.fake.link.finish(5,null,null); yes(f.fake.link.closed); yes(grant.closed);
        }});
        test("native service change invalidates every opaque attribute handle", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.fake.link.events.invalidated(f.fake.link); yes(f.fake.link.closed); error("UNAVAILABLE",()->f.read(new Gate()));
        }});
        test("cleanup failure blocks resource release until a real retry succeeds", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.fake.link.failClose=true; eq(true,f.core.disconnect(f.connectionId).get("cleanupPending")); yes(f.core.hasUnconfirmedCleanup()); f.fake.link.failClose=false; eq(true,f.core.disconnect(f.connectionId).get("localResourcesReleased")); yes(!f.core.hasUnconfirmedCleanup());
        }});
        test("connection denial and foreign handles fail closed", () -> { try(Fixture f=new Fixture()) {
            f.discover(); error("INVALID_HANDLE",()->f.core.read(UUID.randomUUID().toString(),f.attributeId,new Gate())); error("INVALID_HANDLE",()->f.core.read(f.connectionId,UUID.randomUUID().toString(),new Gate()));
            f.connectionGrant.denied=true; f.core.tick(); yes(f.fake.link.closed); error("INVALID_HANDLE",()->f.core.poll(f.connectionId));
        }});
        test("queue budget is enforced without additional native calls", () -> { try(Fixture f=new Fixture(limits(2,32,512))) {
            f.discover(); f.read(new Gate()); f.read(new Gate()); error("BUSY",()->f.read(new Gate())); eq(2,f.fake.link.executions.size());
        }});
        test("developer value budget applies to writes and received bytes", () -> { try(Fixture f=new Fixture(limits(4,32,2))) {
            f.discover(); error("INVALID_REQUEST",()->f.core.write(f.connectionId,f.attributeId,new byte[3],"response",new Gate())); f.read(new Gate()); f.fake.link.finish(0,new byte[3],null); yes(f.fake.link.closed);
        }});
        test("synchronous operation completion does not revoke connection after lease closes", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.fake.synchronousExecute=true; Gate grant=new Gate(); f.read(grant); yes(!grant.closed); yes(!f.fake.link.closed); eq("Bw==",f.events(f.connectionId).get(0).get("valueBase64")); yes(grant.closed);
        }});
        test("new connection ignores old native link callbacks", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Fake.FakeLink old=f.fake.link; String oldId=f.connectionId; f.core.disconnect(oldId); f.connect(); old.events.state(old,true,0); old.events.notification(old,f.attribute,new byte[]{1}); eq(0,f.events(f.connectionId).size()); yes(!f.fake.link.closed);
        }});
        test("page shutdown closes everything and withholds all data", () -> { try(Fixture f=new Fixture()) {
            f.discover(); f.read(new Gate()); f.page.denied=true; f.core.tick(); yes(f.fake.link.closed); error("PERMISSION_DENIED",()->f.core.poll(f.connectionId)); yes(!f.core.hasResources());
        }});
        test("long catalog uses stable bounded pages with no silent attribute loss", () -> { try(Fixture f=new Fixture(new BleCentral.Limits(4,128,32,128,32,64,4096,16,512,30000,30000))) {
            f.connect(); f.core.services(f.connectionId,0,256,new Gate()); List<BleCentral.Attribute> chars=new ArrayList<>(); for(int i=0;i<1200;i++)chars.add(new BleCentral.Attribute(CHAR,new Object(),2,Collections.emptyList()));
            f.fake.link.finish(0,null,Collections.singletonList(new BleCentral.Service(SERVICE,0,0,chars))); Map<String,Object> page=object(f.events(f.connectionId).get(0).get("catalog"));
            Set<String> ids=new HashSet<>(); int count=0;
            while(true){ yes(new JSONObject(page).toString().getBytes(StandardCharsets.UTF_8).length<BleCentral.MAX_POLL_BYTES); for(Map<String,Object> row:list(page.get("entries"))){count++; if(row.containsKey("attributeId"))yes(ids.add(field(row,"attributeId")));} if(page.get("nextOffset")==null)break; page=f.core.services(f.connectionId,((Number)page.get("nextOffset")).intValue(),256,new Gate()); }
            eq(1201,count); eq(1200,ids.size());
        }});
        test("failed scan start never strands an undisclosed handle", () -> { try(Fixture f=new Fixture()) {
            f.fake.rejectScan=true; error("UNAVAILABLE",()->f.scan()); f.fake.rejectScan=false; yes(f.scan()!=null);
        }});
        test("failed connection start never consumes a hidden connection slot", () -> { try(Fixture f=new Fixture()) {
            String device=f.device(); f.fake.rejectConnect=true; for(int i=0;i<20;i++)error("UNAVAILABLE",()->f.core.connect(device,new Gate())); f.fake.rejectConnect=false; yes(f.core.connect(device,new Gate()).containsKey("connectionId"));
        }});
        test("oversized declared-budget notification reports a dropped diagnostic", () -> { try(Fixture f=new Fixture(limits(4,32,2))) {
            f.discover(); f.subscribe(new Gate()); f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{1,2,3}); Map<String,Object> event=f.events(f.connectionId).get(0); eq("notification_dropped",event.get("type")); yes(!event.containsKey("valueBase64")); yes(!f.fake.link.closed);
        }});
        test("late connection success cannot bypass deadline between watchdog ticks", () -> { try(Fixture f=new Fixture()) {
            f.connectionId=field(f.core.connect(f.device(),new Gate()),"connectionId"); f.time.now=30000; f.fake.link.connected(); yes(f.fake.link.closed); eq("connection_timeout",f.events(f.connectionId).get(0).get("reason"));
        }});
        test("revoked scan then native failure cannot reveal buffered advertisements", () -> { try(Fixture f=new Fixture()) {
            Gate grant=new Gate(); String scan=field(f.core.scan(Collections.emptyList(),100,grant),"scanId"); f.fake.scan.found("peer",new byte[]{1}); grant.denied=true; f.fake.scan.events.failed(f.fake.scan,2); error("INVALID_HANDLE",()->f.core.poll(scan));
        }});
        test("revoked subscription then services change cannot reveal buffered data", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); f.subscribe(grant); f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{9}); grant.denied=true; f.fake.link.events.invalidated(f.fake.link); error("INVALID_HANDLE",()->f.core.poll(f.connectionId));
        }});
        test("completed read grant remains live until actual poll delivery", () -> { try(Fixture f=new Fixture()) {
            f.discover(); Gate grant=new Gate(); f.read(grant); f.fake.link.finish(0,new byte[]{42},null); yes(!grant.closed); BleCentral.Poll result=f.core.poll(f.connectionId); grant.denied=true; error("PERMISSION_DENIED",result::check); f.core.tick(); yes(f.fake.link.closed); yes(grant.closed);
        }});
        test("terminal scan lease stays live through last buffered result delivery", () -> { try(Fixture f=new Fixture()) {
            Gate grant=new Gate(); String scan=field(f.core.scan(Collections.emptyList(),100,grant),"scanId"); f.fake.scan.found("peer",new byte[]{1}); f.time.now=100; f.core.tick(); yes(!grant.closed); f.events(scan); yes(grant.closed);
        }});
        test("bounded-ring result drop closes exactly the retired one-shot grant", () -> { try(Fixture f=new Fixture(limits(4,2,512))) {
            f.discover(); Gate a=new Gate(),b=new Gate(),c=new Gate(); f.read(a);f.fake.link.finish(0,new byte[]{1},null);f.read(b);f.fake.link.finish(0,new byte[]{2},null);yes(!a.closed);f.read(c);f.fake.link.finish(0,new byte[]{3},null);yes(a.closed);yes(!b.closed);f.events(f.connectionId);yes(b.closed);yes(c.closed);
        }});
        test("poll byte guard accounts for Android slash escaping of base64", () -> { try(Fixture f=new Fixture()) {
            String scan=f.scan();byte[] bytes=new byte[1650];Arrays.fill(bytes,(byte)255);for(int i=0;i<128;i++)f.fake.scan.found("peer",bytes);
            BleCentral.Poll result=f.core.poll(scan);String json=new JSONObject(result.result).toString();int androidCompatibleBytes=json.replace("/","\\/").getBytes(StandardCharsets.UTF_8).length;yes(androidCompatibleBytes<=BleCentral.MAX_POLL_BYTES);yes(list(result.result.get("events")).size()>0);
        }});
        test("closing an undrained terminal owner releases every result grant", () -> { try(Fixture f=new Fixture()) {
            f.discover();Gate grant=new Gate();f.read(grant);f.fake.link.finish(0,new byte[]{1},null);f.fake.link.events.state(f.fake.link,false,0);yes(!grant.closed);f.core.cancelAll();yes(grant.closed);yes(f.connectionGrant.closed);yes(!f.core.hasResources());
        }});
        test("peer disconnect retains truthful terminal status after active subscription", () -> { try(Fixture f=new Fixture()) {
            f.discover();Gate grant=new Gate();f.core.subscribe(f.connectionId,f.attributeId,"notify",grant);f.fake.link.finish(0,null,null);f.fake.link.events.notification(f.fake.link,f.attribute,new byte[]{8});f.fake.link.events.state(f.fake.link,false,0);yes(grant.closed);List<Map<String,Object>> events=f.events(f.connectionId);yes(events.size()>0);eq("disconnected",events.get(events.size()-1).get("type"));for(Map<String,Object> event:events)yes(!"notification".equals(event.get("type")));
        }});
        test("process-wide scan capacity is retained across helpers", () -> {
            List<Fixture> fixtures=new ArrayList<>(); try{ for(int i=0;i<BleCentral.PROCESS_SCANS;i++){Fixture f=new Fixture();fixtures.add(f);f.scan();} Fixture extra=new Fixture();fixtures.add(extra);error("BUSY",()->extra.scan()); } finally{for(Fixture f:fixtures)f.close();}
        });
        test("Android24/30 permission matrix preserves genuine legacy location needs", () -> {
            for(int api:new int[]{24,28,30}){List<String> names=BlePermissions.required(api,35,true,false,BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION);yes(names.contains(BlePermissions.LEGACY));yes(names.contains(BlePermissions.ADMIN));yes(names.contains(BlePermissions.FINE));yes(!names.contains(BlePermissions.SCAN));}
        });
        test("Android31+ scan privacy is explicit and connect stays separate", () -> {
            for(int api:new int[]{31,33,34,35,36}){List<String> full=BlePermissions.required(api,35,true,false,BlePermissions.ScanPrivacy.FULL);eq(Arrays.asList(BlePermissions.SCAN,BlePermissions.COARSE,BlePermissions.FINE),full);eq(Collections.singletonList(BlePermissions.SCAN),BlePermissions.required(api,35,true,false,BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION));eq(Collections.singletonList(BlePermissions.CONNECT),BlePermissions.required(api,35,false,true,BlePermissions.ScanPrivacy.FULL));}
        });
        test("manifest full mode never silently declares neverForLocation", () -> {
            List<Map<String,Object>> full=BlePermissions.manifest(BlePermissions.ScanPrivacy.FULL), restricted=BlePermissions.manifest(BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION);
            yes(!full.get(2).containsKey("usesPermissionFlags"));eq("neverForLocation",restricted.get(2).get("usesPermissionFlags"));yes(!full.get(5).containsKey("maxSdkVersion"));eq(30,restricted.get(5).get("maxSdkVersion"));yes(!full.toString().contains("ADVERTISE"));
        });
        test("parser freezes write bytes and binds digest rather than raw payload", () -> {
            String id=UUID.randomUUID().toString(); JSONObject argsWrite=new JSONObject().put("connectionId",id).put("attributeId",id).put("valueBase64","AQID").put("writeType","response");
            BleRequest request=BleRequest.parse("bluetooth.write",argsWrite);argsWrite.put("valueBase64","CQkJ");eq("AQID",BleBytes.encode(request.bytes()));JSONObject details=request.authorizationDetails();yes(!details.has("valueBase64"));eq(3,details.getInt("byteLength"));eq(64,details.getString("valueSha256").length());request.clear();try{request.bytes();throw new AssertionError("Retired bytes reused");}catch(IllegalStateException expected){checks++;}
        });
        test("strict parsing rejects duplicate keys coercions foreign fields and bad base64", () -> {
            String id=UUID.randomUUID().toString();
            for(String json:new String[]{"{\"serviceUuids\":[],\"durationMs\":1,\"durationMs\":2}","{\"serviceUuids\":[],\"durationMs\":\"1\"}","{\"serviceUuids\":[],\"durationMs\":1.0}","{\"serviceUuids\":[],\"durationMs\":1,\"x\":2}","{\"serviceUuids\":[\"180d\"],\"durationMs\":1}"})error("INVALID_REQUEST",()->BleRequest.parse("bluetooth.scan",json));
            for(String b64:new String[]{"A","AB==","AQ","AQ==\n","_w=="})error("INVALID_REQUEST",()->BleRequest.parse("bluetooth.write",new JSONObject().put("connectionId",id).put("attributeId",id).put("valueBase64",b64).put("writeType","response")));
        });
        test("parser supports arbitrary canonical UUIDs and no policy on poll or teardown", () -> {
            BleRequest r=BleRequest.parse("bluetooth.scan","{\"serviceUuids\":[\"12345678-1234-5678-1234-567812345678\"],\"durationMs\":300000}");eq(SERVICE,r.serviceUuids.get(0));yes(r.needsPolicy());
            yes(!BleRequest.parse("bluetooth.poll",new JSONObject().put("handle",UUID.randomUUID().toString())).needsPolicy());yes(!BleRequest.parse("bluetooth.disconnect",new JSONObject().put("connectionId",UUID.randomUUID().toString())).needsPolicy());
        });
        test("pure codec round trips every bounded binary size on API24-compatible code", () -> { Random random=new Random(123);for(int n=0;n<=512;n++){byte[] data=new byte[n];random.nextBytes(data);yes(Arrays.equals(data,BleBytes.decode(BleBytes.encode(data))));eq(Base64.getEncoder().encodeToString(data),BleBytes.encode(data));} });
        test("signed configuration validates all declared limits and explicit privacy", () -> {
            JSONObject limits=new JSONObject("{\"connections\":4,\"discoveredDevices\":128,\"queuedOperations\":32,\"bufferedEvents\":128,\"pollEvents\":32,\"services\":64,\"attributes\":512,\"subscriptions\":16,\"valueBytes\":512,\"scanDurationMs\":30000,\"operationTimeoutMs\":30000}");
            BleConfiguration config=BleConfiguration.parse(new JSONObject("{\"version\":1,\"scanPrivacy\":\"full\"}"),limits);eq(512,config.limits.attributes);error("INVALID_CONFIG",()->BleConfiguration.parse(new JSONObject("{\"version\":1}"),limits));limits.put("pollEvents",129);error("INVALID_CONFIG",()->BleConfiguration.parse(new JSONObject("{\"version\":1,\"scanPrivacy\":\"full\"}"),limits));
        });
        System.out.println("RESULT " + tests + " tests, " + checks + " assertions passed; synthetic only, no Android Bluetooth execution.");
    }
}

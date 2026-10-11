package com.jarvys.factory.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;

/** Pure orchestration tests. FakeStore is synthetic and does NOT establish SQLite durability. */
public final class NotificationScheduleEngineTest {
    static final String PROCESS=hex('1');
    static String hex(char c) { char[] a=new char[64];java.util.Arrays.fill(a,c);return new String(a); }
    static final class Time implements Clock { long epoch=1000, elapsed=100; public long epochMs(){return epoch;}public long elapsedMs(){return elapsed;} }
    static Source source(char artifact) { return new Source("test.generated.app",hex('a'),hex(artifact),hex('c'),1,true,new Limits(128,256,1024,4194304)); }
    static NotificationScheduleRequest.Schedule request(String id) throws Exception { return request(id,7,false); }
    static NotificationScheduleRequest.Schedule request(String id,int notification,boolean exact) throws Exception {
        return NotificationScheduleRequest.parseSchedule("{\"scheduleId\":\""+id+"\",\"atEpochMs\":2000,\"expiresAtEpochMs\":4000,\"precision\":\""+(exact?"exact":"inexact")+"\",\"notification\":{\"id\":"+notification+",\"channelId\":\"main\",\"title\":\"Hello\",\"body\":\"Body\"}}");
    }
    static final class Memory implements Store {
        final Map<String,Row> rows=new LinkedHashMap<>();
        Runnable beforeChange;String cursor;
        public String recoveryCursor(){return cursor;}public void checkpointRecovery(String token){cursor=token;}
        String key(String a,String id){return a+":"+id;}
        public synchronized Row insert(Source s,NotificationScheduleRequest.Schedule r,long now)throws FactoryException{
            Row old=get(s.ownerFingerprint,r.scheduleId);if(old!=null){if(!old.fingerprint.equals(r.fingerprint()))throw new FactoryException("CONFLICT","");return old;}
            Row v=new Row(s,r,NotificationActionEvents.randomToken(),State.PENDING,0,"admitted","","",now,now);rows.put(key(s.ownerFingerprint,r.scheduleId),v);return v;
        }
        public synchronized Row insertImmediate(Source s,NotificationScheduleRequest.Notification n,String process,long now){
            Row row=new Row(s,n,NotificationActionEvents.randomToken(),State.PENDING,0,"admitted",process,"",now,now);rows.put(key(s.ownerFingerprint,row.recordId),row);return row;
        }
        public synchronized Row get(String a,String id){return rows.get(key(a,id));}
        public synchronized Row byAlarmToken(String token){for(Row r:rows.values())if(r.token.equals(token))return r;return null;}
        public synchronized Row change(Row expected,State next,String reason,String process,String attempt,long now){
            if(beforeChange!=null){Runnable r=beforeChange;beforeChange=null;r.run();}
            Row old=get(expected.source.ownerFingerprint,expected.recordId);
            if(old==null||old.revision!=expected.revision||!old.token.equals(expected.token)||old.state!=expected.state)return null;
            Row r=old.kind==Kind.SCHEDULED?new Row(old.source,old.request,old.token,next,old.revision+1,reason,process,attempt,old.createdAt,now):new Row(old.source,old.notification,old.token,next,old.revision+1,reason,process,attempt,old.createdAt,now);
            rows.put(key(old.source.ownerFingerprint,old.recordId),r);return r;
        }
        public List<ActionToken> actions(Row r){List<ActionToken> out=new ArrayList<>();for(NotificationScheduleRequest.Action a:r.notification.actions)out.add(new ActionToken(NotificationActionEvents.randomToken(),NotificationActionEvents.randomToken(),a));return out;}
        public synchronized List<Row> page(String after,int limit){List<Row> out=new ArrayList<>();for(Row r:rows.values())if(after==null||r.token.compareTo(after)>0)out.add(r);out.sort((a,b)->a.token.compareTo(b.token));return new ArrayList<>(out.subList(0,Math.min(limit,out.size())));}
        public synchronized boolean forget(Row r,boolean acknowledge){Row old=get(r.source.ownerFingerprint,r.recordId);if(old==null||old.revision!=r.revision||!old.token.equals(r.token))return false;rows.remove(key(r.source.ownerFingerprint,r.recordId));return true;}
    }
    static final class Host implements Authority {
        Source acquiredSource;Policy policy=Policy.ALLOWED;int acquisitions,checks,closed;Runnable duringAcquire,duringCheck;
        public Decision acquire(Row r,Source currentSource,Mode mode,Session session){acquiredSource=currentSource;acquisitions++;if(duringAcquire!=null)duringAcquire.run();return new Decision(policy,policy==Policy.ALLOWED?new Lease(){public void check(){checks++;if(duringCheck!=null)duringCheck.run();}public void close(){closed++;}}:null);}
    }
    static final class OS implements Platform {
        boolean exact=true,ready=true,failPost,failBeforePost;int arms,posts,cancels;Runnable beforeArm,afterArm,beforePost,afterCheckpoint;
        public boolean exactAvailable(){return exact;}public boolean notificationsReady(NotificationScheduleRequest.Notification n){return ready;}
        public void arm(Row r,Checkpoint c){if(beforeArm!=null)beforeArm.run();c.check();arms++;if(afterArm!=null)afterArm.run();}
        public void cancelAlarm(Row r){cancels++;}
        public void post(Row r,List<ActionToken> actions,Checkpoint c){if(beforePost!=null)beforePost.run();if(failBeforePost)throw new SecurityException();c.check();if(afterCheckpoint!=null)afterCheckpoint.run();posts++;if(failPost)throw new RuntimeException("uncertain");}
    }
    static final class Fixture {
        final Time time=new Time();final Memory store=new Memory();final Host host=new Host();final OS os=new OS();final Source source=source('b');
        final NotificationScheduleEngine engine=new NotificationScheduleEngine(store,host,os,time,source,PROCESS);
        Session session(){return new Session(time,8000);}
        Row schedule(String id)throws Exception{return engine.schedule(request(id),Mode.FOREGROUND_REVIEW,session());}
        Row due(String id)throws Exception{Row row=schedule(id);time.epoch=2000;return engine.fire(row.token,session());}
    }
    @Test public void defaultInexactArmsAndFirePostsOnce()throws Exception{Fixture f=new Fixture();Row row=f.schedule("n");assertEquals(State.SCHEDULED,row.state);assertEquals(1,f.os.arms);f.time.epoch=2000;Row posted=f.engine.fire(row.token,f.session());assertEquals(State.ACCEPTED,posted.state);assertEquals(1,f.os.posts);f.engine.fire(row.token,f.session());assertEquals(1,f.os.posts);assertEquals(2,f.host.closed);}
    @Test public void exactUnavailableNeverDowngrades()throws Exception{Fixture f=new Fixture();f.os.exact=false;assertEquals(State.QUEUED_EXACT_PERMISSION,f.engine.schedule(request("n",7,true),Mode.FOREGROUND_REVIEW,f.session()).state);assertEquals(0,f.os.arms);}
    @Test public void exactNeedsArtifactDeclaration()throws Exception{Fixture f=new Fixture();Source s=new Source("test.generated.app",hex('a'),hex('b'),hex('c'),1,false,new Limits(128,256,1024,4194304));NotificationScheduleEngine e=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,s,PROCESS);try{e.schedule(request("x",7,true),Mode.FOREGROUND_REVIEW,f.session());fail();}catch(FactoryException ex){assertEquals("CAPABILITY_REQUIRED",ex.code);}assertTrue(f.store.rows.isEmpty());}
    @Test public void askQueuesWithoutPostingOrPrompt()throws Exception{Fixture f=new Fixture();Row row=f.schedule("n");f.host.policy=Policy.ASK;f.time.epoch=2000;assertEquals(State.QUEUED_APPROVAL,f.engine.fire(row.token,f.session()).state);assertEquals(0,f.os.posts);}
    @Test public void unavailableQueuesAndRecoveryDoesNotRetryQueue()throws Exception{Fixture f=new Fixture();f.host.policy=Policy.UNAVAILABLE;Row row=f.schedule("n");assertEquals(State.QUEUED_POLICY_UNAVAILABLE,row.state);f.host.policy=Policy.ALLOWED;f.time.epoch=2000;f.engine.recoverPage(null,64,f.session());assertEquals(0,f.os.posts);assertEquals(1,f.host.acquisitions);assertEquals(State.ACCEPTED,f.engine.resume("n",Mode.FOREGROUND_REVIEW,f.session()).state);}
    @Test public void denyRevokesAndCannotResume()throws Exception{Fixture f=new Fixture();f.host.policy=Policy.DENIED;assertEquals(State.REVOKED,f.schedule("n").state);f.host.policy=Policy.ALLOWED;assertEquals(State.REVOKED,f.engine.resume("n",Mode.FOREGROUND_REVIEW,f.session()).state);assertEquals(0,f.os.arms);}
    @Test public void lateAcquisitionCannotArm()throws Exception{Fixture f=new Fixture();Session s=f.session();f.host.duringAcquire=()->{s.invalidate();};assertEquals(State.QUEUED_POLICY_UNAVAILABLE,f.engine.schedule(request("n"),Mode.BACKGROUND_NO_UI,s).state);assertEquals(0,f.os.arms);}
    @Test public void lateLiveCheckCannotPost()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.time.epoch=2000;Session s=f.session();f.os.beforePost=s::invalidate;assertEquals(State.QUEUED_POLICY_UNAVAILABLE,f.engine.fire(r.token,s).state);assertEquals(0,f.os.posts);}
    @Test public void lateOsArmPreflightCannotRegister()throws Exception{Fixture f=new Fixture();Session s=f.session();f.os.beforeArm=s::invalidate;assertEquals(State.SCHEDULE_UNKNOWN,f.engine.schedule(request("n"),Mode.BACKGROUND_NO_UI,s).state);assertEquals(0,f.os.arms);}
    @Test public void osBlockedQueues()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.time.epoch=2000;f.os.ready=false;assertEquals(State.QUEUED_OS_BLOCKED,f.engine.fire(r.token,f.session()).state);assertEquals(0,f.os.posts);}
    @Test public void notifyUncertaintyNeverAutoReplays()throws Exception{Fixture f=new Fixture();f.os.failPost=true;assertEquals(State.DELIVERY_UNKNOWN,f.due("n").state);f.os.failPost=false;f.engine.resume("n",Mode.FOREGROUND_REVIEW,f.session());f.engine.recoverPage(null,64,f.session());assertEquals(1,f.os.posts);}
    @Test public void preflightFailureCanResumeWithoutDuplicateAttempt()throws Exception{Fixture f=new Fixture();f.os.failBeforePost=true;assertEquals(State.QUEUED_POLICY_UNAVAILABLE,f.due("n").state);assertEquals(0,f.os.posts);f.os.failBeforePost=false;assertEquals(State.ACCEPTED,f.engine.resume("n",Mode.FOREGROUND_REVIEW,f.session()).state);}
    @Test public void cancelPersistsAndStaleAlarmCannotResurrect()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");assertEquals(State.CANCELLED,f.engine.cancel("n").state);f.time.epoch=2000;assertEquals(State.CANCELLED,f.engine.fire(r.token,f.session()).state);assertEquals(0,f.os.posts);assertEquals(1,f.os.cancels);}
    @Test public void cancellationTooLateDoesNotChangeReservationRevision()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.time.epoch=2000;f.os.beforePost=()->{try{Row before=f.engine.status("n");assertEquals(State.DISPATCH_RESERVED,before.state);Row cancel=f.engine.cancel("n");assertEquals(before.revision,cancel.revision);}catch(Exception e){throw new AssertionError(e);}};assertEquals(State.ACCEPTED,f.engine.fire(r.token,f.session()).state);}
    @Test public void cancelRetriesHarmlessCasContention()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.store.beforeChange=()->f.store.change(r,State.QUEUED_APPROVAL,"race",PROCESS,"",1000);assertEquals(State.CANCELLED,f.engine.cancel("n").state);}
    @Test public void cancellationDuringArmCleansStableIdentity()throws Exception{Fixture f=new Fixture();f.os.afterArm=()->{try{f.engine.cancel("n");}catch(Exception ex){throw new AssertionError(ex);}};assertEquals(State.CANCELLED,f.schedule("n").state);assertEquals(2,f.os.cancels);}
    @Test public void repeatedRequestIsIdempotentNotImplicitResume()throws Exception{Fixture f=new Fixture();f.host.policy=Policy.ASK;Row r=f.schedule("n");f.host.policy=Policy.ALLOWED;assertEquals(r.revision,f.schedule("n").revision);assertEquals(1,f.host.acquisitions);}
    @Test public void sameIdDifferentRequestConflicts()throws Exception{Fixture f=new Fixture();f.schedule("n");try{f.engine.schedule(request("n",8,false),Mode.FOREGROUND_REVIEW,f.session());fail();}catch(FactoryException ex){assertEquals("CONFLICT",ex.code);}}
    @Test public void crashReservationBecomesUnknownButSameProcessIsProtected()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");r=f.store.change(r,State.DISPATCH_RESERVED,"crash",PROCESS,"",1000);assertEquals(State.DISPATCH_RESERVED,f.engine.resume("n",Mode.FOREGROUND_REVIEW,f.session()).state);NotificationScheduleEngine restart=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,f.source,hex('2'));assertEquals(State.DELIVERY_UNKNOWN,restart.resume("n",Mode.FOREGROUND_REVIEW,f.session()).state);assertEquals(0,f.os.posts);}
    @Test public void oldArtifactCrashReservationIsRecovered()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.store.change(r,State.DISPATCH_RESERVED,"crash",PROCESS,"",1000);NotificationScheduleEngine updated=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,source('d'),hex('2'));updated.recoverPage(null,64,f.session());assertEquals(State.DELIVERY_UNKNOWN,f.engine.status("n").state);assertEquals(0,f.os.posts);}
    @Test public void expiredNeverPostsAndBoundaryInclusive()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.time.epoch=4001;assertEquals(State.EXPIRED,f.engine.fire(r.token,f.session()).state);Fixture other=new Fixture();r=other.schedule("n");other.time.epoch=4000;assertEquals(State.ACCEPTED,other.engine.fire(r.token,other.session()).state);}
    @Test public void stableRandomIdentityAvoidsHashCollision()throws Exception{Fixture f=new Fixture();assertEquals("Aa".hashCode(),"BB".hashCode());assertNotEquals(f.schedule("Aa").token,f.schedule("BB").token);}
    @Test public void forgetTerminalThenReuseHasFreshIdentity()throws Exception{Fixture f=new Fixture();Row old=f.schedule("n");f.engine.cancel("n");assertTrue(f.engine.forget("n",false));Row newer=f.schedule("n");assertNotEquals(old.token,newer.token);assertNull(f.engine.fire(old.token,f.session()));assertEquals(State.SCHEDULED,f.engine.status("n").state);}
    @Test public void staleCasCannotChangeReusedSchedule()throws Exception{Fixture f=new Fixture();Row old=f.schedule("n");f.engine.cancel("n");f.engine.forget("n",false);Row fresh=f.schedule("n");Row stale=new Row(old.source,old.request,old.token,fresh.state,fresh.revision,"","","",1000,1000);assertNull(f.store.change(stale,State.CANCELLED,"late",PROCESS,"",1000));assertEquals(State.SCHEDULED,f.engine.status("n").state);}
    @Test public void uncertainForgetRequiresExplicitAcknowledgment()throws Exception{Fixture f=new Fixture();f.os.failPost=true;f.due("n");try{f.engine.forget("n",false);fail();}catch(FactoryException ex){assertEquals("UNRESOLVED_NOTIFICATION",ex.code);}assertTrue(f.engine.forget("n",true));}
    @Test public void activeForgetIsRejected()throws Exception{Fixture f=new Fixture();f.schedule("n");try{f.engine.forget("n",true);fail();}catch(FactoryException ex){assertEquals("UNRESOLVED_NOTIFICATION",ex.code);}}
    @Test public void coldRecoveryRearamsSameTokenOnly()throws Exception{Fixture f=new Fixture();Row r=f.schedule("n");f.store.change(r,State.ARMING,"crash",hex('2'),"",1000);f.engine.recoverPage(null,64,f.session());assertEquals(r.token,f.engine.status("n").token);assertEquals(State.SCHEDULED,f.engine.status("n").state);assertEquals(2,f.os.arms);}
    @Test public void forgetGrammarRejectsUnknownAndNonBoolean()throws Exception{assertFalse(NotificationScheduleControl.parseForget("{\"scheduleId\":\"x\"}").acknowledgeDeliveryUnknown);assertTrue(NotificationScheduleControl.parseForget("{\"scheduleId\":\"x\",\"acknowledgeDeliveryUnknown\":true}").acknowledgeDeliveryUnknown);for(String s:new String[]{"{\"scheduleId\":\"x\",\"acknowledgeDeliveryUnknown\":1}","{\"scheduleId\":\"x\",\"payload\":{}}"})try{NotificationScheduleControl.parseForget(s);fail();}catch(FactoryException expected){}}
    @Test public void dueCallbackWaitsForArmCommit()throws Exception{
        Fixture f=new Fixture();CountDownLatch registered=new CountDownLatch(1),allowArm=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        f.os.afterArm=()->{registered.countDown();try{assertTrue(allowArm.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
        Thread arm=new Thread(()->{try{f.schedule("n");}catch(Throwable t){error.set(t);}});arm.start();assertTrue(registered.await(2,TimeUnit.SECONDS));
        Row r=f.engine.status("n");assertEquals(State.ARMING,r.state);f.time.epoch=2000;
        Thread fire=new Thread(()->{try{f.engine.fire(r.token,f.session());}catch(Throwable t){error.set(t);}});fire.start();allowArm.countDown();arm.join(3000);fire.join(3000);
        assertFalse(arm.isAlive());assertFalse(fire.isAlive());if(error.get()!=null)throw new AssertionError(error.get());assertEquals(State.ACCEPTED,f.engine.status("n").state);assertEquals(1,f.os.posts);
    }
    @Test public void sameVisibleIdSerializesReservationThroughPost()throws Exception {
        Fixture f=new Fixture();Row a=f.schedule("a"),b=f.schedule("b");f.time.epoch=2000;
        CountDownLatch inPost=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        f.os.beforePost=()->{if(Thread.currentThread().getName().equals("post-a")){inPost.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(Exception e){throw new AssertionError(e);}}};
        Thread first=new Thread(()->{try{f.engine.fire(a.token,f.session());}catch(Throwable t){error.set(t);}},"post-a");first.start();assertTrue(inPost.await(2,TimeUnit.SECONDS));
        Thread second=new Thread(()->{try{f.engine.fire(b.token,f.session());}catch(Throwable t){error.set(t);}},"post-b");second.start();
        // The second cannot enter DISPATCH_RESERVED while the first's visible-ID commit owns the gate.
        Thread.sleep(30);assertNotEquals(State.DISPATCH_RESERVED,f.engine.status("b").state);release.countDown();first.join(3000);second.join(3000);
        if(error.get()!=null)throw new AssertionError(error.get());assertFalse(first.isAlive());assertFalse(second.isAlive());assertEquals(2,f.os.posts);
        assertEquals(State.ACCEPTED,f.engine.status("a").state);assertEquals(State.ACCEPTED,f.engine.status("b").state);
    }

    @Test public void sameSignerUpdateRebindsStoredContentToCurrentSource()throws Exception {
        Fixture f=new Fixture();Row original=f.schedule("n");Source newer=source('d');assertNotEquals(f.source.fingerprint,newer.fingerprint);assertEquals(f.source.ownerFingerprint,newer.ownerFingerprint);
        NotificationScheduleEngine updated=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,newer,hex('2'));f.time.epoch=2000;
        assertEquals(State.ACCEPTED,updated.fire(original.token,f.session()).state);assertSame(newer,f.host.acquiredSource);
        assertEquals(f.source.fingerprint,updated.status("n").source.fingerprint);assertEquals(1,f.os.posts);
    }
    @Test public void updateRemovingCapabilityQueuesWithoutDiscardingData()throws Exception {
        Fixture f=new Fixture();Row original=f.schedule("n");Source newer=new Source("test.generated.app",hex('a'),hex('d'),hex('c'),2,false,false,new Limits(128,256,1024,4194304));
        NotificationScheduleEngine updated=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,newer,hex('2'));f.time.epoch=2000;
        Row queued=updated.fire(original.token,f.session());assertEquals(State.QUEUED_POLICY_UNAVAILABLE,queued.state);assertEquals("current_artifact_capability_unavailable",queued.reason);assertEquals(0,f.os.posts);assertEquals(1,f.host.acquisitions);
    }
    @Test public void differentSignerCannotInheritOwnerDataOrPost()throws Exception {
        Fixture f=new Fixture();Row original=f.schedule("n");Source other=new Source("test.generated.app",hex('e'),hex('d'),hex('c'),2,true,new Limits(128,256,1024,4194304));
        NotificationScheduleEngine updated=new NotificationScheduleEngine(f.store,f.host,f.os,f.time,other,hex('2'));f.time.epoch=2000;
        assertNull(updated.status("n"));assertEquals(State.REVOKED,updated.fire(original.token,f.session()).state);assertEquals(0,f.os.posts);
    }

    static NotificationScheduleRequest.Notification immediate()throws Exception{
        return NotificationScheduleRequest.parseNotification("{\"id\":7,\"channelId\":\"main\",\"title\":\"Hello\",\"body\":\"Body\",\"actions\":[{\"id\":\"open\",\"title\":\"Open\",\"data\":{\"values\":[null,1,true]}}]}");
    }
    @Test public void immediateUsesSameLedgerAndPostsWithoutAnyAlarmOrHostAcquisition()throws Exception{
        Fixture f=new Fixture();NotificationImmediateEngine immediate=new NotificationImmediateEngine(f.store,f.os,f.time,f.source,PROCESS);
        int[] checks={0};Row result=immediate.show(immediate(),()->checks[0]++);
        assertEquals(State.ACCEPTED,result.state);assertEquals(Kind.IMMEDIATE,result.kind);assertNull(result.request);assertNull(result.scheduleId);
        assertEquals(0,f.os.arms);assertEquals(1,f.os.posts);assertEquals(0,f.host.acquisitions);assertTrue(checks[0]>=2);
        assertEquals(result.token,result.details().getString("generationId"));assertFalse(result.details().has("atEpochMs"));
    }
    @Test public void immediatePostUncertaintyRetainsGenerationWithoutReplay()throws Exception{
        Fixture f=new Fixture();f.os.failPost=true;NotificationImmediateEngine immediate=new NotificationImmediateEngine(f.store,f.os,f.time,f.source,PROCESS);
        Row result=immediate.show(immediate(),()->{});assertEquals(State.DELIVERY_UNKNOWN,result.state);
        assertThrows(FactoryException.class,()->immediate.forget(result.token,false));assertTrue(immediate.forget(result.token,true));assertEquals(1,f.os.posts);
    }
    @Test public void immediatePreflightFailureReturnsTerminalNoPostReceipt()throws Exception{
        Fixture f=new Fixture();f.os.failBeforePost=true;NotificationImmediateEngine immediate=new NotificationImmediateEngine(f.store,f.os,f.time,f.source,PROCESS);
        Row result=immediate.show(immediate(),()->{});assertEquals(State.CANCELLED,result.state);assertEquals(0,f.os.posts);assertTrue(immediate.forget(result.token,false));
    }
    @Test public void alarmLookupCannotDispatchImmediateGeneration()throws Exception{
        Fixture f=new Fixture();Row row=f.store.insertImmediate(f.source,immediate(),PROCESS,1000);assertNull(f.engine.fire(row.token,f.session()));assertEquals(0,f.os.posts);
    }
    @Test public void abandonedImmediateAdmissionIsReclaimedOnLaterProcessRecovery()throws Exception{
        Fixture f=new Fixture();Row row=f.store.insertImmediate(f.source,immediate(),hex('2'),1000);f.engine.recoverPage(null,64,f.session());assertNull(f.store.byAlarmToken(row.token));assertEquals(0,f.os.posts);
    }
    @Test public void inFlightImmediateAdmissionIsNotReclaimedInItsOwnProcess()throws Exception{
        Fixture f=new Fixture();Row row=f.store.insertImmediate(f.source,immediate(),PROCESS,1000);f.engine.recoverPage(null,64,f.session());assertNotNull(f.store.byAlarmToken(row.token));
    }
    @Test public void generationControlHasNoIntentOrArbitraryPayloadEscape()throws Exception{
        String id=hex('3');assertEquals(id,NotificationScheduleControl.parseGenerationStatus("{\"generationId\":\""+id+"\"}"));
        assertFalse(NotificationScheduleControl.parseForgetGeneration("{\"generationId\":\""+id+"\"}").acknowledgeDeliveryUnknown);
        assertThrows(FactoryException.class,()->NotificationScheduleControl.parseForgetGeneration("{\"generationId\":\""+id+"\",\"intent\":{}}"));
    }

}

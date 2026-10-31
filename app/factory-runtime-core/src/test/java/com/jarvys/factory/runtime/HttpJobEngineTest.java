package com.jarvys.factory.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

public final class HttpJobEngineTest {
    static final String ID=HttpJobRequestTest.ID;
    static String hash(char c){char[] a=new char[64];java.util.Arrays.fill(a,c);return new String(a);}
    static Source source(char cert,char apk){return new Source("com.example.job",hash(cert),hash(apk),hash('c'),1,new Limits(32,8388608));}
    static final class Time implements Clock {long elapsed=1,epoch=1000;public long epochMs(){return epoch++;}public long elapsedMs(){return elapsed;}}
    static final class Memory implements Store {
        final Map<String,Row> rows=new LinkedHashMap<>();int next=100,budgetChecks;boolean budget=true;
        synchronized Row raw(){return rows.isEmpty()?null:rows.values().iterator().next();}
        private String key(String owner,String id){return owner+":"+id;}
        public synchronized Row insert(Source s,HttpJobRequest request,String admissionToken,long now)throws FactoryException{
            Row existing=get(s.owner,request.jobId);if(existing!=null)return existing;
            Row row=new Row(s,request,admissionToken,next++,State.ACCEPTED,0,"durably_admitted","","","",false,now,now);rows.put(key(s.owner,request.jobId),row);return row;
        }
        public synchronized Row get(String owner,String id){return rows.get(key(owner,id));}
        public synchronized Row byToken(String token){for(Row r:rows.values())if(r.token.equals(token))return r;return null;}
        public synchronized void checkBudget(Source current,Row row)throws FactoryException{budgetChecks++;if(!budget)throw new FactoryException("QUOTA_EXCEEDED","budget");}
        public synchronized Row change(Row e,State state,String reason,String process,String attempt,String result,boolean ack,long now){
            Row old=get(e.source.owner,e.request.jobId);if(old==null||old.revision!=e.revision||!old.token.equals(e.token)||old.state!=e.state)return null;
            Row r=new Row(old.source,old.request,old.token,old.androidId,state,old.revision+1,reason,process,attempt,result,ack,old.createdAt,now);rows.put(key(old.source.owner,old.request.jobId),r);return r;
        }
        public synchronized boolean forget(Row e){Row old=get(e.source.owner,e.request.jobId);if(old==null||old.revision!=e.revision)return false;rows.remove(key(e.source.owner,e.request.jobId));return true;}
    }
    static final class Scheduler implements Platform {int schedules,cancels;boolean accepted=true;public boolean schedule(Row r){schedules++;return accepted;}public void cancel(Row r){cancels++;}}
    static final class Auth implements Authority {
        SessionStatus sessionStatus=SessionStatus.ACTIVE;int calls,checks,closes;boolean alive=true;Mode mode;Runnable hook;
        public Decision acquire(Row row,Source source,Mode mode,Session session){calls++;this.mode=mode;if(hook!=null)hook.run();return new Decision(sessionStatus,sessionStatus==SessionStatus.ACTIVE?new Lease(){public void check(){checks++;if(!alive)throw new SecurityException();}public void close(){closes++;}}:null);}
    }
    static final class Fixture {
        final Time clock=new Time();final Memory memory=new Memory();final Scheduler scheduler=new Scheduler();final Auth auth=new Auth();int effects;Transport transport=(r,a,c)->{effects++;a.check();try{return new JSONObject().put("status",200).put("ok",true).put("body","response");}catch(Exception e){throw new AssertionError(e);}};
        Source current=source('a','b');String process="process-a";
        HttpJobEngine engine(){return new HttpJobEngine(memory,auth,scheduler,(r,a,c)->transport.execute(r,a,c),clock,current,process);}
        Session session(){return new Session(clock,125000);}
        Row enqueue()throws Exception{return engine().enqueue(HttpJobRequest.parse(HttpJobRequestTest.input()),session());}
        RunResult fire()throws Exception{return engine().fire(memory.raw().token,session());}
        Row unknown()throws Exception{enqueue();transport=(r,a,c)->{effects++;throw new FactoryException("NETWORK_ERROR","broken");};return fire().row;}
    }
    @Test public void admissionIsDurableAndNotHttpCompletion()throws Exception{Fixture f=new Fixture();Row r=f.enqueue();assertEquals(State.QUEUED,r.state);assertEquals(0,f.auth.calls);assertEquals(0,f.effects);assertEquals(1,f.scheduler.schedules);}
    @Test public void schedulingFailureIsAcceptedNotQueued()throws Exception{Fixture f=new Fixture();f.scheduler.accepted=false;assertEquals(State.ACCEPTED,f.enqueue().state);assertEquals(0,f.effects);}
    @Test public void idempotentEnqueueDoesNotRearmOrExecute()throws Exception{Fixture f=new Fixture();Row r=f.enqueue();assertEquals(r.token,f.enqueue().token);assertEquals(1,f.scheduler.schedules);assertEquals(0,f.effects);}
    @Test public void changedPayloadConflicts()throws Exception{Fixture f=new Fixture();f.enqueue();JSONObject p=HttpJobRequestTest.input();p.getJSONObject("request").put("body","different");try{f.engine().enqueue(HttpJobRequest.parse(p),f.session());fail();}catch(FactoryException e){assertEquals("CONFLICT",e.code);}}
    @Test public void completeResultCanBeReadAndAcknowledged()throws Exception{Fixture f=new Fixture();f.enqueue();RunResult result=f.fire();assertEquals(State.COMPLETED,result.row.state);assertFalse(result.retryBeforeEffect);assertEquals(1,f.effects);assertEquals(1,f.auth.closes);assertEquals("response",f.engine().readResult(ID,true).getJSONObject("result").getString("body"));assertTrue(f.engine().forget(ID,false));}
    @Test public void completionDoesNotRepeatOnSchedulerDuplicate()throws Exception{Fixture f=new Fixture();f.enqueue();f.fire();f.fire();assertEquals(1,f.effects);assertEquals(1,f.auth.calls);}
    @Test public void unavailableBeforeEffectMayRetry()throws Exception{Fixture f=new Fixture();f.enqueue();f.auth.sessionStatus=SessionStatus.UNAVAILABLE;RunResult r=f.fire();assertTrue(r.retryBeforeEffect);assertEquals(State.QUEUED,r.row.state);assertEquals(0,f.effects);}
    @Test public void transportFailureIsUnknownAndNeverAutomaticallyRetried()throws Exception{Fixture f=new Fixture();assertEquals(State.UNKNOWN,f.unknown().state);RunResult r=f.fire();assertFalse(r.retryBeforeEffect);assertEquals(State.UNKNOWN,r.row.state);assertEquals(1,f.effects);}
    @Test public void unknownRequiresExplicitAcknowledgmentAndNewForegroundAuthority()throws Exception{Fixture f=new Fixture();f.unknown();try{f.engine().resume(ID,"now",false,f.session());fail();}catch(FactoryException e){assertEquals("OUTCOME_UNKNOWN",e.code);}int calls=f.auth.calls;f.engine().resume(ID,"now",true,f.session());assertEquals(calls+1,f.auth.calls);assertEquals(Mode.FOREGROUND_REPLAY,f.auth.mode);assertEquals(2,f.effects);}
    @Test public void foregroundMustExplicitlyChooseNow()throws Exception{Fixture f=new Fixture();f.enqueue();try{f.engine().resume(ID,"scheduled",false,f.session());fail();}catch(FactoryException e){assertEquals("INVALID_ARGUMENT",e.code);}assertEquals(0,f.auth.calls);}
    @Test public void foregroundResumeUsesOneFreshHostSession()throws Exception{Fixture f=new Fixture();f.enqueue();f.auth.sessionStatus=SessionStatus.UNAVAILABLE;f.fire();f.auth.sessionStatus=SessionStatus.ACTIVE;assertEquals(State.COMPLETED,f.engine().resume(ID,"now",false,f.session()).row.state);assertEquals(Mode.FOREGROUND_NOW,f.auth.mode);assertEquals(2,f.auth.calls);assertEquals(1,f.effects);}
    @Test public void unknownRemainsUnknownWhenReplayCannotGetHostSession()throws Exception{Fixture f=new Fixture();f.unknown();f.auth.sessionStatus=SessionStatus.UNAVAILABLE;assertEquals(State.UNKNOWN,f.engine().resume(ID,"now",true,f.session()).row.state);assertEquals(1,f.effects);}
    @Test public void processDeathAfterReservationNeverReplaysMutation()throws Exception{
        Fixture f=new Fixture();f.enqueue();f.transport=(r,a,c)->{throw new AssertionError("simulated process death");};try{f.fire();fail();}catch(AssertionError expected){}
        assertEquals(State.RUNNING,f.memory.raw().state);f.process="process-b";assertEquals(State.UNKNOWN,f.engine().status(ID).state);f.fire();assertEquals(1,f.auth.calls);
    }
    @Test public void processDeathDuringPreparationMayRetryBeforeEffect()throws Exception{
        Fixture f=new Fixture();f.enqueue();f.auth.hook=()->{throw new AssertionError("simulated death");};try{f.fire();fail();}catch(AssertionError expected){}
        assertEquals(State.PREPARING,f.memory.raw().state);f.process="process-b";f.auth.hook=null;assertEquals(State.QUEUED,f.engine().status(ID).state);assertEquals(State.COMPLETED,f.fire().row.state);
    }
    @Test public void deathDuringReplaySessionAcquisitionRetainsUnknownRisk()throws Exception{
        Fixture f=new Fixture();f.unknown();f.auth.hook=()->{throw new AssertionError("simulated death");};try{f.engine().resume(ID,"now",true,f.session());fail();}catch(AssertionError expected){}
        f.process="process-b";assertEquals(State.UNKNOWN,f.engine().status(ID).state);assertEquals(1,f.effects);
    }
    @Test public void sameSignerUpdateRetainsRecordsAndRevalidates()throws Exception{Fixture f=new Fixture();Row before=f.enqueue();f.current=source('a','d');assertEquals(before.token,f.engine().status(ID).token);f.fire();assertEquals(2,f.memory.budgetChecks);}
    @Test public void differentSignerCannotReadOrExecuteAnotherOwner()throws Exception{Fixture f=new Fixture();f.enqueue();String token=f.memory.raw().token;f.current=source('d','b');assertNull(f.engine().status(ID));assertNull(f.engine().fire(token,f.session()).row);assertEquals(0,f.auth.calls);}
    @Test public void cancelQueuedNeverDispatches()throws Exception{Fixture f=new Fixture();f.enqueue();assertEquals(State.CANCELLED,f.engine().cancel(ID).state);f.fire();assertEquals(0,f.effects);}
    @Test public void cancellationDuringSessionAcquisitionFencesLateReply()throws Exception{Fixture f=new Fixture();f.enqueue();f.auth.hook=()->{try{f.engine().cancel(ID);}catch(Exception e){throw new AssertionError(e);}};assertEquals(State.CANCELLED,f.fire().row.state);assertEquals(0,f.effects);}
    @Test public void cancellationAfterReservationIsUnknownAndDropsLateResult()throws Exception{
        Fixture f=new Fixture();f.enqueue();f.transport=(r,a,c)->{try{f.effects++;assertEquals(State.UNKNOWN,f.engine().cancel(ID).state);return new JSONObject().put("status",200);}catch(Exception e){throw new AssertionError(e);}};
        assertEquals(State.UNKNOWN,f.fire().row.state);assertEquals("",f.memory.raw().result);
    }
    @Test public void cancelledReplayReviewKeepsPriorUnknown()throws Exception{Fixture f=new Fixture();f.unknown();f.auth.hook=()->{try{f.engine().cancel(ID);}catch(Exception e){throw new AssertionError(e);}};assertEquals(State.UNKNOWN,f.engine().resume(ID,"now",true,f.session()).row.state);assertEquals(1,f.effects);}
    @Test public void revokedLeaseAfterReservationNeverDeliversResult()throws Exception{Fixture f=new Fixture();f.enqueue();f.transport=(r,a,c)->{f.effects++;f.auth.alive=false;try{return new JSONObject().put("status",200);}catch(Exception e){throw new AssertionError(e);}};assertEquals(State.UNKNOWN,f.fire().row.state);}
    @Test public void staleSessionCannotDispatch()throws Exception{Fixture f=new Fixture();f.enqueue();Session s=f.session();s.stop();try{f.engine().fire(f.memory.raw().token,s);fail();}catch(SecurityException expected){}assertEquals(0,f.auth.calls);}
    @Test public void timeoutDuringSessionAcquisitionCannotDispatch()throws Exception{Fixture f=new Fixture();f.enqueue();f.auth.hook=()->f.clock.elapsed=200000;RunResult r=f.fire();assertEquals(State.QUEUED,r.row.state);assertTrue(r.retryBeforeEffect);assertEquals(0,f.effects);}
    @Test public void signedBudgetIsCheckedBeforeAuthorityAndAtDispatch()throws Exception{Fixture f=new Fixture();f.enqueue();f.auth.hook=()->f.memory.budget=false;RunResult r=f.fire();assertEquals(State.QUEUED,r.row.state);assertEquals(0,f.effects);assertEquals(2,f.memory.budgetChecks);}
    @Test public void forgetRequiresAcknowledgments()throws Exception{Fixture f=new Fixture();f.enqueue();f.fire();try{f.engine().forget(ID,false);fail();}catch(FactoryException e){assertEquals("UNRESOLVED_JOB",e.code);}Fixture u=new Fixture();u.unknown();try{u.engine().forget(ID,false);fail();}catch(FactoryException e){assertEquals("UNRESOLVED_JOB",e.code);}assertTrue(u.engine().forget(ID,true));}
    @Test public void unresolvedRowsCannotBeReadAsResults()throws Exception{Fixture f=new Fixture();f.enqueue();try{f.engine().readResult(ID,false);fail();}catch(FactoryException e){assertEquals("RESULT_UNAVAILABLE",e.code);}}
    @Test public void duplicateConcurrentDeliveryAcquiresOnlyOnce()throws Exception{
        Fixture f=new Fixture();f.enqueue();CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger failures=new AtomicInteger();
        f.auth.hook=()->{inside.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}};
        Thread first=new Thread(()->{try{f.fire();}catch(Exception e){failures.incrementAndGet();}});first.start();inside.await();RunResult second=f.fire();assertEquals(State.PREPARING,second.row.state);release.countDown();first.join(5000);assertFalse(first.isAlive());assertEquals(0,failures.get());assertEquals(1,f.effects);assertEquals(1,f.auth.calls);
    }
}

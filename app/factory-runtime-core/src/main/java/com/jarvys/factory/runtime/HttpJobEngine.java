package com.jarvys.factory.runtime;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Finite generic work, never a JavaScript lifetime. All effect boundaries are injected and fenced. */
public final class HttpJobEngine {
    public enum State { ACCEPTED, QUEUED, PREPARING, RUNNING, COMPLETED, UNKNOWN, CANCELLED }
    public enum Mode { BACKGROUND_NO_UI, FOREGROUND_NOW, FOREGROUND_REPLAY }
    public enum SessionStatus { ACTIVE, UNAVAILABLE }
    public interface Clock { long epochMs(); long elapsedMs(); }
    public interface Lease extends NativeOperationAuthorization.Access, AutoCloseable { @Override void close(); }
    public static final class Decision implements AutoCloseable {
        public final SessionStatus sessionStatus; public final Lease lease;
        public Decision(SessionStatus status,Lease lease){if(status==null||(status==SessionStatus.ACTIVE)!=(lease!=null))throw new IllegalArgumentException();this.sessionStatus=status;this.lease=lease;}
        public void close(){if(lease!=null)lease.close();}
    }
    public interface Authority {
        /** SAME jobs.enqueueHttp operation; foreground_now explicitly discloses constraints bypass. */
        Decision acquire(Row row,Source current,Mode mode,Session session) throws FactoryException;
    }
    public interface Store {
        Row insert(Source source,HttpJobRequest request,String admissionToken,long now) throws FactoryException;
        Row get(String owner,String id) throws FactoryException;
        Row byToken(String token) throws FactoryException;
        void checkBudget(Source current,Row row) throws FactoryException;
        Row change(Row expected,State state,String reason,String process,String attempt,String result,boolean acknowledged,long now) throws FactoryException;
        boolean forget(Row expected) throws FactoryException;
    }
    public interface Platform {
        /** Return true only for JobScheduler.RESULT_SUCCESS, which is not HTTP completion. */
        boolean schedule(Row row) throws FactoryException;
        void cancel(Row row) throws FactoryException;
    }
    public interface Transport { JSONObject execute(NetworkRequest request,NativeOperationAuthorization.Access access,NativeNetwork.Cancellation cancellation) throws FactoryException; }
    public static final class Limits {
        public final int records; public final long storageBytes;
        public Limits(int records,long bytes){if(records<1||records>1024||bytes<65536||bytes>67108864)throw new IllegalArgumentException("Invalid signed job budgets");this.records=records;storageBytes=bytes;}
    }
    public static final class Source {
        public final String appId,certificateSha256,apkSha256,recordSha256,owner,fingerprint;
        public final long versionCode; public final Limits limits;
        public Source(String app,String cert,String apk,String record,long version,Limits limits){
            if(app==null||!app.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||!hash(cert)||!hash(apk)||!hash(record)||version<1||limits==null)throw new IllegalArgumentException();
            appId=app;certificateSha256=cert;apkSha256=apk;recordSha256=record;versionCode=version;this.limits=limits;
            owner=HttpJobRequest.digest("factory-http-owner-v1",app,cert);fingerprint=HttpJobRequest.digest("factory-http-source-v1",app,cert,apk,record,String.valueOf(version));
        }
        private static boolean hash(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    }
    public static final class Row {
        public final Source source; public final HttpJobRequest request;
        public final String token,reason,process,attempt,result; public final State state;
        public final int androidId; public final long revision,createdAt,updatedAt; public final boolean acknowledged;
        public Row(Source source,HttpJobRequest request,String token,int androidId,State state,long revision,String reason,String process,String attempt,String result,boolean acknowledged,long createdAt,long updatedAt){
            if(source==null||request==null||token==null||state==null||revision<0||androidId<0)throw new IllegalArgumentException();
            this.source=source;this.request=request;this.token=token;this.androidId=androidId;this.state=state;this.revision=revision;this.reason=reason;this.process=process;this.attempt=attempt;this.result=result;this.acknowledged=acknowledged;this.createdAt=createdAt;this.updatedAt=updatedAt;
        }
        public JSONObject details(){
            try{return new JSONObject().put("jobId",request.jobId).put("status",state==State.PREPARING?"queued":state.name().toLowerCase(Locale.ROOT))
                .put("reason",reason).put("revision",revision).put("createdAtEpochMs",createdAt).put("updatedAtEpochMs",updatedAt)
                .put("resultAvailable",!result.isEmpty()).put("resultAcknowledged",acknowledged).put("outcomeMayHaveOccurred",state==State.RUNNING||state==State.UNKNOWN||(state==State.PREPARING&&"authorizing_replay".equals(reason)));}
            catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
    }
    /** Stop flips current immediately; connection teardown must be off the Android main thread. */
    public static final class Session {
        public static final long MAX_LIFETIME_MS=125000;
        public final String attempt=HttpJobRequest.token(); public final long deadlineElapsedMs;
        public final NativeNetwork.Cancellation cancellation=new NativeNetwork.Cancellation();
        private final Clock clock; private final AtomicBoolean ended=new AtomicBoolean(),reserved=new AtomicBoolean();
        public Session(Clock clock,long budgetMs){if(clock==null||budgetMs<1||budgetMs>MAX_LIFETIME_MS)throw new IllegalArgumentException();this.clock=clock;deadlineElapsedMs=Math.addExact(clock.elapsedMs(),budgetMs);}
        public boolean active(){return !ended.get()&&!Thread.currentThread().isInterrupted()&&clock.elapsedMs()<deadlineElapsedMs;}
        public void check(){if(!active())throw new SecurityException("Finite job lifetime ended");}
        /** Conservative reservation flag is raised BEFORE durable reservation. Never reset in this session. */
        void reserving(){reserved.set(true);check();}
        public boolean stop(){ended.set(true);return !reserved.get();}
        public boolean reserved(){return reserved.get();}
    }
    public static final class RunResult {
        public final Row row; public final boolean retryBeforeEffect;
        RunResult(Row row,boolean retry){this.row=row;retryBeforeEffect=retry;}
    }
    private final Store store;private final Authority authority;private final Platform platform;private final Transport transport;
    private final Clock clock;private final Source source;private final String process;
    public HttpJobEngine(Store store,Authority authority,Platform platform,Transport transport,Clock clock,Source source,String process){
        if(store==null||authority==null||platform==null||transport==null||clock==null||source==null||process==null)throw new IllegalArgumentException();
        this.store=store;this.authority=authority;this.platform=platform;this.transport=transport;this.clock=clock;this.source=source;this.process=process;
    }
    /** Admission is durable and inert. Page-local or bounded OS execution authority is acquired only before HTTP. */
    public Row enqueue(HttpJobRequest request,Session session) throws FactoryException {
        session.check();
        Row existing=store.get(source.owner,request.jobId);
        if(existing!=null){if(!existing.request.fingerprint.equals(request.fingerprint))throw new FactoryException("CONFLICT","Job ID already binds a different immutable payload.");return recover(existing);}
        session.check();
        String admissionToken=HttpJobRequest.token();
        Row row=store.insert(source,request,admissionToken,clock.epochMs());
        if(!row.token.equals(admissionToken)||row.revision!=0||row.state!=State.ACCEPTED)return recover(row);
        session.check();
        boolean scheduled;
        try{scheduled=platform.schedule(row);}catch(FactoryException|RuntimeException unavailable){return row;}
        if(!scheduled)return row;
        Row changed=change(row,State.QUEUED,"scheduler_accepted","","","",false);
        return changed==null?store.get(source.owner,request.jobId):changed;
    }
    public Row status(String id) throws FactoryException {return recover(store.get(source.owner,HttpJobRequest.id(id)));}
    public RunResult fire(String token,Session session) throws FactoryException {
        Row row=recover(store.byToken(token));
        if(row==null||!source.owner.equals(row.source.owner))return new RunResult(null,false);
        if(row.state!=State.ACCEPTED&&row.state!=State.QUEUED)return new RunResult(row,false);
        if("session_unavailable".equals(row.reason))return new RunResult(row,false);
        return run(row,Mode.BACKGROUND_NO_UI,session);
    }
    /** Explicit caller intent to run NOW; applies even when original scheduler constraints are unmet. */
    public RunResult resume(String id,String execution,boolean acknowledgeUnknown,Session session) throws FactoryException {
        if(!"now".equals(execution))throw HttpJobRequest.invalid();
        Row row=status(id);if(row==null)return new RunResult(null,false);
        if(row.state==State.RUNNING||row.state==State.PREPARING)throw new FactoryException("BUSY","This job still has an active attempt.");
        if(row.state==State.COMPLETED)return new RunResult(row,false);
        if(row.state==State.UNKNOWN&&!acknowledgeUnknown)throw new FactoryException("OUTCOME_UNKNOWN","The previous HTTP effect may have occurred; explicit acknowledgment is required before a fresh authorized attempt.");
        platform.cancel(row);
        return run(row,row.state==State.UNKNOWN?Mode.FOREGROUND_REPLAY:Mode.FOREGROUND_NOW,session);
    }
    private RunResult run(Row initial,Mode mode,Session session) throws FactoryException {
        session.check();store.checkBudget(source,initial);
        Row row=change(initial,State.PREPARING,mode==Mode.FOREGROUND_REPLAY?"authorizing_replay":"authorizing",process,session.attempt,"",false);
        if(row==null)return new RunResult(status(initial.request.jobId),false);
        try(Decision decision=authority.acquire(row,source,mode,session)){
            session.check();
            if(decision.sessionStatus!=SessionStatus.ACTIVE){
                State next=mode==Mode.FOREGROUND_REPLAY?State.UNKNOWN:State.QUEUED;
                String reason="session_unavailable";
                Row changed=change(row,next,reason,"","","",false);
                return new RunResult(changed,decision.sessionStatus==SessionStatus.UNAVAILABLE&&mode==Mode.BACKGROUND_NO_UI);
            }
            decision.lease.check();session.check();store.checkBudget(source,row);
            session.reserving();
            Row reservation=change(row,State.RUNNING,"dispatch_reserved",process,session.attempt,"",false);
            if(reservation==null)return new RunResult(status(initial.request.jobId),false);
            row=reservation;
            final Row reserved=row;
            NativeOperationAuthorization.Access access=()->{
                session.check();decision.lease.check();session.check();
                try{Row current=store.get(source.owner,reserved.request.jobId);if(current==null||current.revision!=reserved.revision||current.state!=State.RUNNING||!current.attempt.equals(session.attempt))throw new SecurityException("Job generation changed");}
                catch(FactoryException unavailable){throw new SecurityException("Job ledger unavailable");}
            };
            access.check();
            JSONObject result=transport.execute(row.request.request,access,session.cancellation);
            access.check();
            String encoded=result.toString();if(HttpJobRequest.bytes(encoded)>NetworkRequest.MAX_RESULT_BYTES)throw new FactoryException("RESPONSE_TOO_LARGE","Bounded job result exceeds its inbox allowance.");
            Row completed=change(row,State.COMPLETED,"http_response_received","","",encoded,false);
            return new RunResult(completed==null?status(initial.request.jobId):completed,false);
        }catch(FactoryException|RuntimeException failure){
            boolean effect=row.state==State.RUNNING||"authorizing_replay".equals(row.reason);
            String code=failure instanceof FactoryException?((FactoryException)failure).code:"CANCELLED_OR_REVOKED";
            // A code is stable metadata, never an exception message, URL or response body.
            if(code==null||!code.matches("[A-Z_]{1,64}"))code="UNAVAILABLE";
            Row changed=change(row,effect?State.UNKNOWN:State.QUEUED,effect?"outcome_unknown":"before_effect_"+code.toLowerCase(Locale.ROOT),"","","",false);
            return new RunResult(changed==null?status(initial.request.jobId):changed,!effect&&mode==Mode.BACKGROUND_NO_UI);
        }finally{session.cancellation.cancel();} // The caller owns the session through final reply/JobService completion.
    }
    public Row cancel(String id) throws FactoryException {
        for(int i=0;i<16;i++){
            Row row=status(id);if(row==null)return null;
            if(terminal(row.state)){platform.cancel(row);return row;}
            State next=row.state==State.RUNNING||"authorizing_replay".equals(row.reason)?State.UNKNOWN:State.CANCELLED;
            Row changed=change(row,next,next==State.UNKNOWN?"cancelled_outcome_unknown":"cancelled_before_effect","","","",false);
            if(changed!=null){platform.cancel(changed);return changed;}
        }throw new FactoryException("BUSY","Job cancellation was not confirmed.");
    }
    public JSONObject readResult(String id,boolean acknowledge) throws FactoryException {
        Row row=status(id);if(row==null)return null;
        if(row.state!=State.COMPLETED||row.result.isEmpty())throw new FactoryException("RESULT_UNAVAILABLE","No completed HTTP result is available.");
        if(acknowledge&&!row.acknowledged){Row changed=change(row,row.state,row.reason,row.process,row.attempt,row.result,true);if(changed==null)throw new FactoryException("BUSY","Result acknowledgment was not confirmed.");row=changed;}
        try{return new JSONObject().put("jobId",row.request.jobId).put("result",new JSONObject(row.result)).put("acknowledged",row.acknowledged);}
        catch(Exception unavailable){throw new FactoryException("STORAGE_ERROR","Stored job result is invalid.");}
    }
    public boolean forget(String id,boolean acknowledgeUnknown) throws FactoryException {
        Row row=status(id);if(row==null)return false;
        if(!terminal(row.state)||(row.state==State.UNKNOWN&&!acknowledgeUnknown)||(row.state==State.COMPLETED&&!row.acknowledged))
            throw new FactoryException("UNRESOLVED_JOB","Forget requires a terminal record, acknowledged result, or explicit unknown-outcome acknowledgment.");
        platform.cancel(row);return store.forget(row);
    }
    private Row recover(Row row) throws FactoryException {
        if(row==null||!source.owner.equals(row.source.owner))return null;
        if(!process.equals(row.process)&&(row.state==State.RUNNING||row.state==State.PREPARING)){
            boolean unknown=row.state==State.RUNNING||"authorizing_replay".equals(row.reason);
            Row changed=change(row,unknown?State.UNKNOWN:State.QUEUED,unknown?"process_died_after_reservation":"process_died_before_effect","","","",false);
            return changed==null?store.get(source.owner,row.request.jobId):changed;
        }return row;
    }
    private Row change(Row row,State state,String reason,String process,String attempt,String result,boolean acknowledged)throws FactoryException{
        return store.change(row,state,reason,process,attempt,result,acknowledged,clock.epochMs());
    }
    public static boolean terminal(State state){return state==State.COMPLETED||state==State.UNKNOWN||state==State.CANCELLED;}
}

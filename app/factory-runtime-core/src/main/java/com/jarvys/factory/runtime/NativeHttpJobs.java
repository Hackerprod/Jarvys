package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Foreground bridge adapter. Invoke off main; keep session tied to actual Activity/bridge lifetime. */
public final class NativeHttpJobs {
    /** Closed parser only: no Context, source/provider call, ledger, scheduling or HTTP effect. */
    public static void validate(String operation,JSONObject args)throws FactoryException {
        try {
            if(args==null)throw HttpJobRequest.invalid();
            switch(operation==null?"":operation){
                case "jobs.enqueueHttp":HttpJobRequest.parse(args);return;
                case "jobs.status":case "jobs.cancel":FactoryConfig.exactKeys(args,"jobId");id(args);return;
                case "jobs.resume":
                    FactoryConfig.exactKeys(args,"jobId","execution","acknowledgeUnknown");id(args);
                    if(!"now".equals(HttpJobRequest.text(args,"execution",null)))throw HttpJobRequest.invalid();
                    HttpJobRequest.bool(args,"acknowledgeUnknown",false);return;
                case "jobs.readResult":FactoryConfig.exactKeys(args,"jobId","acknowledge");id(args);HttpJobRequest.bool(args,"acknowledge",false);return;
                case "jobs.forget":FactoryConfig.exactKeys(args,"jobId","acknowledgeUnknown");id(args);HttpJobRequest.bool(args,"acknowledgeUnknown",false);return;
                default:throw HttpJobRequest.invalid();
            }
        }catch(FactoryException error){throw error;}catch(Exception invalid){throw HttpJobRequest.invalid();}
    }
    /** Foreground authority must bind jobs.enqueueHttp to row.authorizationDetails("foreground_now"). */
    public static JSONObject execute(Context context,FactoryConfig config,String operation,JSONObject args,Authority foreground,Session session)throws FactoryException{
        if(foreground==null||session==null)throw HttpJobRequest.invalid();
        validate(operation,args);
        session.check();
        if (!"jobs.enqueueHttp".equals(operation) && !"jobs.resume".equals(operation))
            return ownData(context,config,operation,args,session);
        Source source=FactoryHttpJobServices.source(context,config,session);
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            HttpJobEngine engine=new HttpJobEngine(ledger,foreground,new NativeHttpJobScheduler(context),new NativeNetwork()::execute,FactoryHttpJobServices.CLOCK,source,FactoryHttpJobServices.PROCESS);
            if("jobs.enqueueHttp".equals(operation))return engine.enqueue(HttpJobRequest.parse(args),session).details();
            switch(operation){
                case "jobs.status":FactoryConfig.exactKeys(args,"jobId");return details(engine.status(id(args)));
                case "jobs.cancel":FactoryConfig.exactKeys(args,"jobId");return details(engine.cancel(id(args)));
                case "jobs.resume":
                    FactoryConfig.exactKeys(args,"jobId","execution","acknowledgeUnknown");
                    return details(engine.resume(id(args),HttpJobRequest.text(args,"execution",null),HttpJobRequest.bool(args,"acknowledgeUnknown",false),session).row);
                case "jobs.readResult":
                    FactoryConfig.exactKeys(args,"jobId","acknowledge");JSONObject result=engine.readResult(id(args),HttpJobRequest.bool(args,"acknowledge",false));return result==null?notFound():result;
                case "jobs.forget":
                    FactoryConfig.exactKeys(args,"jobId","acknowledgeUnknown");return new JSONObject().put("jobId",id(args)).put("forgotten",engine.forget(id(args),HttpJobRequest.bool(args,"acknowledgeUnknown",false)));
                default:throw HttpJobRequest.invalid();
            }
        }catch(FactoryException error){throw error;}catch(Exception invalid){throw HttpJobRequest.invalid();}
    }
    private static JSONObject ownData(Context context,FactoryConfig config,String operation,JSONObject args,Session session)throws FactoryException {
        String owner=owner(context,config.appId);session.check();
        try(HttpJobLedger ledger=new HttpJobLedger(context)) {
            String id=id(args);Row original=ledger.get(owner,id);session.check();
            if(original==null)return "jobs.forget".equals(operation)?new JSONObject().put("jobId",id).put("forgotten",false):notFound();
            // Stored provenance can address only owned data/cancellation. It grants no fresh effect.
            Platform cleanup=new Platform(){
                public boolean schedule(Row row){throw new SecurityException("Own-data adapter cannot schedule HTTP");}
                public void cancel(Row row)throws FactoryException{session.check();new NativeHttpJobScheduler(context).cancel(row);}
            };
            HttpJobEngine engine=new HttpJobEngine(ledger,(row,current,mode,active)->{throw new SecurityException("Own-data adapter cannot acquire HTTP authority");},cleanup,
                (request,access,cancellation)->{throw new SecurityException("Own-data adapter cannot execute HTTP");},FactoryHttpJobServices.CLOCK,original.source,FactoryHttpJobServices.PROCESS);
            switch(operation){
                case "jobs.status":return details(engine.status(id));
                case "jobs.cancel":return details(engine.cancel(id));
                case "jobs.readResult":return engine.readResult(id,HttpJobRequest.bool(args,"acknowledge",false));
                case "jobs.forget":return new JSONObject().put("jobId",id).put("forgotten",engine.forget(id,HttpJobRequest.bool(args,"acknowledgeUnknown",false)));
                default:throw HttpJobRequest.invalid();
            }
        }catch(FactoryException failure){throw failure;}catch(Exception unavailable){throw new FactoryException("STORAGE_ERROR","Owned job inbox operation could not be confirmed.");}
    }
    /** Local signer proves ownership only, never capability, source receipt or HTTP permission. */
    @SuppressWarnings("deprecation") static String owner(Context context,String expectedAppId)throws FactoryException {
        try {
            if(context==null||!context.getPackageName().equals(expectedAppId))throw new SecurityException();
            PackageInfo own=context.getPackageManager().getPackageInfo(expectedAppId,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            Signature[] signers=Build.VERSION.SDK_INT>=28?own.signingInfo==null?null:own.signingInfo.getApkContentsSigners():own.signatures;
            if(!expectedAppId.equals(own.packageName)||signers==null||signers.length!=1)throw new SecurityException();
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());StringBuilder certificate=new StringBuilder();
            for(byte b:hash)certificate.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return HttpJobRequest.digest("factory-http-owner-v1",expectedAppId,certificate.toString());
        }catch(Exception unavailable){throw new FactoryException("SOURCE_UNAVAILABLE","Installed app/signer data ownership could not be verified locally.");}
    }
    private static String id(JSONObject args)throws Exception{return HttpJobRequest.id(HttpJobRequest.text(args,"jobId",null));}
    private static JSONObject details(Row row)throws Exception{return row==null?notFound():row.details();}
    private static JSONObject notFound()throws Exception{return new JSONObject().put("status","not_found");}
    private NativeHttpJobs(){}
}

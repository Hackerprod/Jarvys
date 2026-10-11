package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Testable native effect boundary. An explicit UNKNOWN state prevents automatic effect replay. */
public final class WidgetEngine {
    public static final class Source {
        public final String appId,certificate,apk,receipt,owner;public final long version;public final WidgetModel.Limits limits;
        public Source(String appId,String certificate,String apk,String receipt,long version,WidgetModel.Limits limits){
            if(appId==null||!appId.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||!WidgetLedger.token(certificate)||!WidgetLedger.token(apk)||!WidgetLedger.token(receipt)||version<1||limits==null)throw new IllegalArgumentException();
            this.appId=appId;this.certificate=certificate;this.apk=apk;this.receipt=receipt;this.version=version;this.limits=limits;this.owner=hash(appId+"\n"+certificate);
        }
    }
    public interface Platform {
        Set<Integer> ownInstances()throws FactoryException;
        boolean pinSupported();
        void validate(WidgetModel model)throws FactoryException;
        boolean requestPin(WidgetModel preview,NativeOperationAuthorization.Access live)throws FactoryException;
        void update(WidgetLedger.Record row,NativeOperationAuthorization.Access live)throws FactoryException;
    }
    private final Source source;private final WidgetLedger ledger;private final Platform platform;
    public WidgetEngine(Source source,WidgetLedger ledger,Platform platform){if(source==null||ledger==null||platform==null)throw new IllegalArgumentException();this.source=source;this.ledger=ledger;this.platform=platform;}
    public JSONObject execute(WidgetRequest request,NativeOperationAuthorization.Access live)throws FactoryException{
        if(request==null||live==null)throw new IllegalArgumentException();
        // Reparse against current signed limits, not limits used by an earlier foreground caller.
        request=WidgetRequest.parse(request.authorizationDetails(),source.limits);
        synchronized(ledger.lock()){
            live.check();if(request.action.equals("requestPin")){
                if(!platform.pinSupported())return result("unsupported",false);
                platform.validate(request.model);live.check();try{return result(platform.requestPin(request.model,live)?"requested":"unsupported",false);}catch(FactoryException|RuntimeException unknown){throw new FactoryException("DISPATCH_UNKNOWN","Launcher pin request completion is unknown; do not automatically repeat it.");}
            }
            Set<Integer> own=platform.ownInstances();requireOwn(request.widgetIds,own);ledger.reconcileOwnInstances(own);if(request.model!=null)platform.validate(request.model);live.check();
            List<WidgetLedger.Record> reserved=ledger.reserve(request,false);
            for(WidgetLedger.Record row:reserved){
                try{live.check();requireOwn(Collections.singletonList(row.widgetId),platform.ownInstances());platform.update(row,live);ledger.applied(row);}
                catch(FactoryException|RuntimeException unknown){throw new FactoryException("DISPATCH_UNKNOWN","Widget update may have reached Android; read each instance before an explicit acknowledged replacement.");}
            }
            JSONObject result=result(request.action.equals("remove")?"configuration_reset":"updated",false);try{JSONArray ids=new JSONArray();for(int id:request.widgetIds)ids.put(id);return result.put("widgetIds",ids);}catch(JSONException e){throw new AssertionError(e);}
        }
    }
    /** Fresh background lease must bind this exact request. APPLIED can restore; UNKNOWN never replays. */
    public WidgetRequest restorationRequest(int id)throws FactoryException{synchronized(ledger.lock()){WidgetLedger.Record row=ledger.read(id);if(row==null||!(row.state.equals("APPLIED")||row.state.equals("RESTORE_PENDING"))||row.model==null||!platform.ownInstances().contains(id))return null;return WidgetRequest.update(id,WidgetModel.parse(row.model.details(),source.limits),source.limits);}}
    public boolean restore(WidgetRequest expected,NativeOperationAuthorization.Access live)throws FactoryException{synchronized(ledger.lock()){
        if(expected==null||expected.widgetIds.size()!=1||!expected.action.equals("update"))throw new IllegalArgumentException();int id=expected.widgetIds.get(0);WidgetRequest current=restorationRequest(id);if(current==null||!current.canonicalJson().equals(expected.canonicalJson()))return false;
        live.check();platform.validate(expected.model);live.check();List<WidgetLedger.Record> rows=ledger.reserve(expected,true);WidgetLedger.Record row=rows.get(0);try{live.check();requireOwn(expected.widgetIds,platform.ownInstances());platform.update(row,live);ledger.applied(row);return true;}catch(FactoryException|RuntimeException failure){throw new FactoryException("DISPATCH_UNKNOWN","Widget restoration is unresolved and will not automatically repeat.");}
    }}
    public JSONObject read(int id)throws FactoryException{if(!platform.ownInstances().contains(id))throw new FactoryException("NOT_OWN_WIDGET","Widget instance does not belong to this app's provider.");WidgetLedger.Record row=ledger.read(id);if(row!=null)return row.details();try{return new JSONObject().put("widgetId",id).put("state","UNCONFIGURED").put("configuration",JSONObject.NULL).put("dispatchUnknown",false);}catch(JSONException e){throw new AssertionError(e);}}
    public JSONArray list()throws FactoryException{Set<Integer> own=platform.ownInstances();ledger.reconcileOwnInstances(own);JSONArray result=new JSONArray();for(int id:new TreeSet<>(own))result.put(id);return result;}
    private static void requireOwn(List<Integer> ids,Set<Integer> own)throws FactoryException{if(!own.containsAll(ids))throw new FactoryException("NOT_OWN_WIDGET","Exact widget IDs must all belong to this app's provider.");}
    private static JSONObject result(String state,boolean removed){try{return new JSONObject().put("state",state).put("launcherPlacementRemoved",removed);}catch(JSONException e){throw new AssertionError(e);}}
    private static String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}catch(Exception e){throw new AssertionError(e);}}
}

package com.jarvys.factory.runtime;

import android.content.Context;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Small bridge adapter; NativeEffects owns live operation sessions, job lifetime and replies. */
public final class NativeWidgetEffects {
    public static final class Prepared {
        public final String method;
        public final WidgetRequest effect;
        private final int widgetId;
        private final String eventId;
        private Prepared(String method,WidgetRequest effect,int widgetId,String eventId){this.method=method;this.effect=effect;this.widgetId=widgetId;this.eventId=eventId;}
        public boolean requiresSession(){return effect!=null;}
        public String operation(){return effect==null?null:WidgetRequest.OPERATION;}
        public JSONObject authorizationDetails(){if(effect==null)throw new IllegalStateException("Own-data reads do not carry effect authorization");return effect.authorizationDetails();}
        /** Run on the bounded IO worker; pass the lease for this exact Prepared effect, or null for own-data calls. */
        public Object execute(Context context,long deadlineElapsedMs,BooleanSupplier active,NativeOperationAuthorization.Access live)throws FactoryException{
            NativeWidgets widgets=new NativeWidgets(context,deadlineElapsedMs,active);
            if(effect!=null){if(live==null)throw new SecurityException("Exact widget operation lease required");return widgets.manage(effect,live);}
            switch(method){
                case "widgets.availability":return widgets.availability();
                case "widgets.list":return widgets.list();
                case "widgets.read":return widgets.read(widgetId);
                case "widgets.pollActions":return widgets.pollActions();
                case "widgets.acknowledgeAction":return widgets.acknowledgeAction(eventId);
                default:throw WidgetModel.invalid();
            }
        }
    }
    public static Prepared prepare(String method,JSONObject arguments)throws FactoryException{return prepare(method,arguments,WidgetModel.Limits.ceiling());}
    public static Prepared prepare(String method,JSONObject arguments,WidgetModel.Limits limits)throws FactoryException{
        if(limits==null)throw WidgetModel.invalid();
        if(method==null||arguments==null)throw WidgetModel.invalid();JSONObject args=StrictJson.object(arguments.toString(),limits.modelBytes+2048,24,8192);
        try{
            switch(method){
                case "widgets.availability":case "widgets.list":case "widgets.pollActions":WidgetModel.keys(args);return new Prepared(method,null,0,null);
                case "widgets.read":WidgetModel.keys(args,"widgetId");return new Prepared(method,null,WidgetModel.integer(args.get("widgetId"),1,Integer.MAX_VALUE),null);
                case "widgets.acknowledgeAction":WidgetModel.keys(args,"eventId");Object event=args.get("eventId");if(!(event instanceof String)||!WidgetLedger.token((String)event))throw WidgetModel.invalid();return new Prepared(method,null,0,(String)event);
                case "widgets.requestPin":WidgetModel.keys(args,"model");return prepared(method,"requestPin",new JSONArray(),args.get("model"),false,limits);
                case "widgets.update":if(args.has("acknowledgeUnknown"))WidgetModel.keys(args,"widgetIds","model","acknowledgeUnknown");else WidgetModel.keys(args,"widgetIds","model");return prepared(method,"update",args.get("widgetIds"),args.get("model"),unknown(args),limits);
                case "widgets.remove":if(args.has("acknowledgeUnknown"))WidgetModel.keys(args,"widgetIds","acknowledgeUnknown");else WidgetModel.keys(args,"widgetIds");return prepared(method,"remove",args.get("widgetIds"),JSONObject.NULL,unknown(args),limits);
                default:throw WidgetModel.invalid();
            }
        }catch(JSONException e){throw WidgetModel.invalid();}
    }
    private static Prepared prepared(String method,String action,Object ids,Object model,boolean acknowledge,WidgetModel.Limits limits)throws FactoryException,JSONException{return new Prepared(method,WidgetRequest.parse(new JSONObject().put("action",action).put("widgetIds",ids).put("model",model).put("acknowledgeUnknown",acknowledge),limits),0,null);}
    private static boolean unknown(JSONObject args)throws FactoryException,JSONException{if(!args.has("acknowledgeUnknown"))return false;Object value=args.get("acknowledgeUnknown");if(!(value instanceof Boolean))throw WidgetModel.invalid();return(Boolean)value;}
    private NativeWidgetEffects(){}
}

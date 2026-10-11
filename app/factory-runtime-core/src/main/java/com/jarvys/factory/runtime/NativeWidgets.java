package com.jarvys.factory.runtime;
import android.content.Context;
import java.util.function.BooleanSupplier;
import org.json.*;
/** Foreground bridge facade. manage must receive the lease bound to request.authorizationDetails(). */
public final class NativeWidgets {
    private final FactoryWidgetServices.Environment environment;private final long deadline;private final BooleanSupplier active;
    public NativeWidgets(Context context,long deadlineElapsedMs,BooleanSupplier active)throws FactoryException{this.deadline=deadlineElapsedMs;this.active=active;environment=FactoryWidgetServices.open(context,deadlineElapsedMs,active);}
    public WidgetModel.Limits limits(){current();return environment.source.limits;}
    public JSONObject availability()throws FactoryException{current();return environment.platform.availability();}
    public JSONArray list()throws FactoryException{current();return environment.engine.list();}
    public JSONObject read(int widgetId)throws FactoryException{current();return environment.engine.read(widgetId);}
    public JSONObject manage(WidgetRequest request,NativeOperationAuthorization.Access live)throws FactoryException{current();return environment.engine.execute(request,()->{current();live.check();current();});}
    public JSONObject pollActions()throws FactoryException{current();JSONObject event=environment.ledger.poll();try{return new JSONObject().put("event",event==null?JSONObject.NULL:event);}catch(JSONException e){throw new AssertionError(e);}}
    public JSONObject acknowledgeAction(String eventId)throws FactoryException{current();try{return new JSONObject().put("acknowledged",environment.ledger.acknowledge(eventId));}catch(JSONException e){throw new AssertionError(e);}}
    private void current(){if(!active.getAsBoolean()||android.os.SystemClock.elapsedRealtime()>=deadline)throw new SecurityException("Widget facade session ended");}
}

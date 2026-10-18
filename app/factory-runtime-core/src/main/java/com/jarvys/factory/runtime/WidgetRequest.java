package com.jarvys.factory.runtime;
import java.util.*;
import org.json.*;
/** All widget effects use one configurable operation, bound to exact action, own IDs and model. */
public final class WidgetRequest {
    public static final String OPERATION="widgets.manage";
    public final String action;public final List<Integer> widgetIds;public final WidgetModel model;public final boolean acknowledgeUnknown;
    private WidgetRequest(String action,List<Integer> ids,WidgetModel model,boolean acknowledgeUnknown){this.action=action;this.widgetIds=Collections.unmodifiableList(new ArrayList<>(ids));this.model=model;this.acknowledgeUnknown=acknowledgeUnknown;}
    public static WidgetRequest parse(String raw,WidgetModel.Limits limits)throws FactoryException{return parse(StrictJson.object(raw,limits.modelBytes+2048,24,8192),limits);}
    public static WidgetRequest parse(JSONObject raw,WidgetModel.Limits limits)throws FactoryException{
        if(raw==null||limits==null)throw invalid("request and signed limits are required.");StrictJson.object(raw.toString(),limits.modelBytes+2048,24,8192);
        WidgetModel.keys(raw,"action","widgetIds","model","acknowledgeUnknown");try{
            String action=WidgetModel.string(raw,"action",24,false);if(!Arrays.asList("requestPin","update","remove").contains(action))throw invalid("action must be requestPin, update, or remove.");
            if(!(raw.get("widgetIds") instanceof JSONArray))throw invalid("widgetIds must be an array of positive integer app-owned widget IDs.");
            if(!(raw.get("acknowledgeUnknown") instanceof Boolean))throw invalid("acknowledgeUnknown must be a boolean.");
            JSONArray a=raw.getJSONArray("widgetIds");if(a.length()>limits.instances)throw invalid("widgetIds exceeds the signed instance limit.");TreeSet<Integer> ids=new TreeSet<>();for(int i=0;i<a.length();i++){Object value=a.get(i);if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid("widgetIds items must be positive integers.");long id=((Number)value).longValue();if(id<1||id>Integer.MAX_VALUE)throw invalid("widgetIds items must be positive integers within Android's integer range.");if(!ids.add((int)id))throw invalid("widgetIds must contain unique positive integers.");}
            boolean pin=action.equals("requestPin"),remove=action.equals("remove"),unknown=raw.getBoolean("acknowledgeUnknown");if(pin?!ids.isEmpty():ids.isEmpty())throw invalid("requestPin requires an empty widgetIds array; update and remove require at least one ID.");
            Object m=raw.get("model");if(remove?m!=JSONObject.NULL:!(m instanceof JSONObject))throw invalid("model must be an object for requestPin/update and null for remove.");if(pin&&unknown)throw invalid("acknowledgeUnknown must be false for requestPin.");
            return new WidgetRequest(action,new ArrayList<>(ids),remove?null:WidgetModel.parse((JSONObject)m,limits),unknown);
        }catch(JSONException e){throw invalid("request fields must match the selected action's documented types.");}
    }
    public JSONObject authorizationDetails(){try{JSONArray ids=new JSONArray();for(int id:widgetIds)ids.put(id);return new JSONObject().put("action",action).put("widgetIds",ids).put("model",model==null?JSONObject.NULL:model.details()).put("acknowledgeUnknown",acknowledgeUnknown);}catch(JSONException e){throw new AssertionError(e);}}
    public String canonicalJson(){return WidgetModel.canonical(authorizationDetails());}
    static WidgetRequest update(int id,WidgetModel model,WidgetModel.Limits limits)throws FactoryException{try{return parse(new JSONObject().put("action","update").put("widgetIds",new JSONArray().put(id)).put("model",model.details()).put("acknowledgeUnknown",false),limits);}catch(JSONException e){throw new AssertionError(e);}}
    private static FactoryException invalid(String message){return new FactoryException("INVALID_ARGUMENT","widgets.manage: "+message);}
}

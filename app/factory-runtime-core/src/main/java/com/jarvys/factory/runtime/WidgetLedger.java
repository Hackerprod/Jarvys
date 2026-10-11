package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded atomic snapshots, separate from notifications. No event can carry policy authority. */
public final class WidgetLedger {
    public interface Persistence {
        Object lock(); byte[] read() throws Exception; void write(byte[] value) throws Exception;
    }
    public static final class Record {
        public final int widgetId; public final String generation,artifact,state;public final WidgetModel model;
        public final Map<String,String> tokens;
        private Record(JSONObject row)throws FactoryException {
            try{widgetId=row.getInt("widgetId");generation=row.getString("generation");artifact=row.getString("artifact");state=row.getString("state");model=row.isNull("model")?null:WidgetModel.parse(row.getJSONObject("model"),WidgetModel.Limits.ceiling());Map<String,String> t=new LinkedHashMap<>();JSONObject raw=row.getJSONObject("tokens");for(Iterator<String> i=raw.keys();i.hasNext();){String key=i.next();t.put(key,raw.getString(key));}tokens=Collections.unmodifiableMap(t);}catch(JSONException e){throw storage();}
        }
        public JSONObject details(){try{return new JSONObject().put("widgetId",widgetId).put("generation",generation).put("state",state).put("sourceApkSha256",artifact).put("configuration",model==null?JSONObject.NULL:model.details()).put("dispatchUnknown",state.equals("DISPATCH_UNKNOWN"));}catch(JSONException e){throw new AssertionError(e);}}
    }
    private final Persistence persistence;private final WidgetEngine.Source source;
    public WidgetLedger(Persistence persistence,WidgetEngine.Source source){if(persistence==null||source==null)throw new IllegalArgumentException();this.persistence=persistence;this.source=source;}
    Object lock(){return persistence.lock();}
    private JSONObject load()throws FactoryException {
        try{byte[] raw=persistence.read();if(raw==null)return new JSONObject().put("schema",1).put("owner",source.owner).put("rows",new JSONArray()).put("events",new JSONArray());
            if(raw.length>WidgetModel.Limits.ceiling().storageBytes/2)throw storage();JSONObject state=StrictJson.object(new String(raw,StandardCharsets.UTF_8),WidgetModel.Limits.ceiling().storageBytes/2,32,32768);
            WidgetModel.keys(state,"schema","owner","rows","events");if(state.getInt("schema")!=1||!source.owner.equals(state.getString("owner")))throw storage();
            JSONArray rows=state.getJSONArray("rows"),events=state.getJSONArray("events");if(rows.length()>128||events.length()>1024)throw storage();Set<Integer> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);Record r=new Record(row);if(r.widgetId<1||!ids.add(r.widgetId)||!token(r.generation)||!token(r.artifact)||!Arrays.asList("APPLIED","RESET","DISPATCH_UNKNOWN","RESTORE_PENDING").contains(r.state)||r.tokens.size()>64)throw storage();if(r.model==null?!r.tokens.isEmpty():r.tokens.size()!=r.model.buttons.size())throw storage();for(String t:r.tokens.values())if(!token(t))throw storage();row.getJSONArray("consumed");}
            return state;
        }catch(FactoryException e){throw e;}catch(Exception e){throw storage();}
    }
    private void save(JSONObject state)throws FactoryException {
        byte[] raw=state.toString().getBytes(StandardCharsets.UTF_8);
        // AtomicFile can hold old + new snapshots. Reserve bounded filesystem metadata headroom.
        long priorBytes;try{byte[] prior=persistence.read();priorBytes=prior==null?0:prior.length;}catch(Exception unavailable){throw storage();}
        if(Math.max(raw.length*2L,priorBytes+raw.length)+8192>source.limits.storageBytes)throw new FactoryException("RESOURCE_LIMIT","Widget storage budget is full; no event or configuration was silently evicted.");
        try{StrictJson.object(new String(raw,StandardCharsets.UTF_8),source.limits.storageBytes/2,32,32768);persistence.write(raw);}catch(Exception e){throw storage();}
    }
    public Record read(int id)throws FactoryException{synchronized(lock()){JSONObject row=find(load(),id);return row==null?null:new Record(row);}}
    private static JSONObject find(JSONObject s,int id)throws FactoryException{try{JSONArray rows=s.getJSONArray("rows");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("widgetId")==id)return rows.getJSONObject(i);return null;}catch(JSONException e){throw storage();}}
    List<Record> reserve(WidgetRequest request,boolean preserveCurrentTokens)throws FactoryException{synchronized(lock()){
        JSONObject state=load();List<Record> results=new ArrayList<>();try{JSONArray rows=state.getJSONArray("rows");int adds=0;for(int id:request.widgetIds){JSONObject old=find(state,id);if(old==null)adds++;else if(old.getString("state").equals("DISPATCH_UNKNOWN")&&!request.acknowledgeUnknown)throw new FactoryException("DISPATCH_UNKNOWN","Previous widget dispatch is unresolved; acknowledgeUnknown must explicitly permit replacement.");}if(rows.length()+adds>source.limits.instances)throw new FactoryException("RESOURCE_LIMIT","Widget instance budget is full.");
            for(int id:request.widgetIds){JSONObject row=find(state,id);boolean preserve=preserveCurrentTokens&&row!=null&&row.getString("artifact").equals(source.apk)&&request.model!=null&&WidgetModel.canonical(row.get("model")).equals(request.model.canonicalJson());
                if(row==null){row=new JSONObject().put("widgetId",id);rows.put(row);}if(!preserve){JSONObject tokens=new JSONObject();if(request.model!=null)for(WidgetModel.Node button:request.model.buttons)tokens.put(button.id,NotificationActionEvents.randomToken());row.put("generation",NotificationActionEvents.randomToken()).put("tokens",tokens).put("consumed",new JSONArray());}
                row.put("artifact",source.apk).put("model",request.model==null?JSONObject.NULL:request.model.details()).put("state","DISPATCH_UNKNOWN");results.add(new Record(row));
            }save(state);return results;
        }catch(JSONException e){throw storage();}
    }}
    void applied(Record expected)throws FactoryException{synchronized(lock()){JSONObject state=load(),row=find(state,expected.widgetId);try{if(row==null||!expected.generation.equals(row.getString("generation")))throw storage();row.put("state",expected.model==null?"RESET":"APPLIED");save(state);}catch(JSONException e){throw storage();}}}
    public void deleted(int[] ids)throws FactoryException{synchronized(lock()){JSONObject state=load();try{JSONArray old=state.getJSONArray("rows"),next=new JSONArray();Set<Integer> deleted=new HashSet<>();for(int id:ids)deleted.add(id);for(int i=0;i<old.length();i++)if(!deleted.contains(old.getJSONObject(i).getInt("widgetId")))next.put(old.get(i));state.put("rows",next);save(state);}catch(JSONException e){throw storage();}}}
    /** Recover missed host deletion callbacks without touching launcher placement or pending events. */
    public void reconcileOwnInstances(Set<Integer> own)throws FactoryException{synchronized(lock()){
        if(own==null)throw new IllegalArgumentException();JSONObject state=load();try{JSONArray rows=state.getJSONArray("rows"),kept=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(own.contains(row.getInt("widgetId")))kept.put(row);}if(kept.length()!=rows.length()){state.put("rows",kept);save(state);}}catch(JSONException e){throw storage();}
    }}
    /** System-supplied restore mapping is data migration only. Rendering still acquires current exact-ID policy. */
    public void remap(int[] oldIds,int[] newIds,Set<Integer> own)throws FactoryException{synchronized(lock()){
        if(oldIds==null||newIds==null||oldIds.length!=newIds.length||oldIds.length>source.limits.instances)throw WidgetModel.invalid();
        JSONObject state=load();try{Set<Integer> oldSet=new HashSet<>(),newSet=new HashSet<>();for(int i=0;i<oldIds.length;i++){if(oldIds[i]<1||newIds[i]<1||!oldSet.add(oldIds[i])||!newSet.add(newIds[i])||!own.contains(newIds[i]))throw WidgetModel.invalid();}
            for(int i=0;i<oldIds.length;i++){JSONObject old=find(state,oldIds[i]);if(old==null||oldIds[i]==newIds[i])continue;if(find(state,newIds[i])!=null)throw new FactoryException("CONFLICT","Restored widget IDs would overwrite existing configuration.");if(old.getString("state").equals("DISPATCH_UNKNOWN"))continue;old.put("widgetId",newIds[i]);if(!old.isNull("model"))old.put("state","RESTORE_PENDING");old.put("generation",NotificationActionEvents.randomToken());JSONObject tokens=new JSONObject();if(!old.isNull("model"))for(WidgetModel.Node button:WidgetModel.parse(old.getJSONObject("model"),source.limits).buttons)tokens.put(button.id,NotificationActionEvents.randomToken());old.put("tokens",tokens).put("consumed",new JSONArray());}
            save(state);
        }catch(JSONException e){throw storage();}
    }}
    /** Durable inbox insertion and consumption are one transaction. Raw intent extras are never accepted. */
    public JSONObject acceptTap(String opaque,long now,Set<Integer> ownIds)throws FactoryException{synchronized(lock()){
        if(!token(opaque)||now<1||ownIds==null)return null;JSONObject state=load();try{JSONArray rows=state.getJSONArray("rows"),events=state.getJSONArray("events");for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);Record r=new Record(row);if(r.model==null||!source.apk.equals(r.artifact)||!ownIds.contains(r.widgetId)||!(r.state.equals("APPLIED")||r.state.equals("DISPATCH_UNKNOWN")))continue;
            String actionId=null;for(Map.Entry<String,String> item:r.tokens.entrySet())if(item.getValue().equals(opaque)){actionId=item.getKey();break;}if(actionId==null)continue;WidgetModel.Node button=null;for(WidgetModel.Node item:r.model.buttons)if(item.id.equals(actionId))button=item;if(button==null)return null;boolean once=button.delivery.equals("oncePerGeneration");JSONArray consumed=row.getJSONArray("consumed");for(int i=0;i<consumed.length();i++)if(once&&opaque.equals(consumed.getString(i))){for(int j=0;j<events.length();j++)if(opaque.equals(events.getJSONObject(j).optString("token")))return publicEvent(events.getJSONObject(j));return null;}
            if(events.length()>=source.limits.inboxEvents)throw new FactoryException("RESOURCE_LIMIT","Widget action inbox is full; the action remains unconsumed.");
            JSONObject event=new JSONObject().put("type","widget_action").put("eventId",NotificationActionEvents.randomToken()).put("widgetId",r.widgetId).put("generation",r.generation).put("sourceApkSha256",r.artifact).put("actionId",actionId).put("receivedAtEpochMs",now).put("data",button.data()).put("deliverySemantics",once?"once_per_generation_delivery":"at_least_once_delivery").put("token",opaque);events.put(event);if(once)consumed.put(opaque);save(state);return publicEvent(event);
        }return null;}catch(JSONException e){throw storage();}
    }}
    public JSONObject poll()throws FactoryException{synchronized(lock()){try{JSONArray events=load().getJSONArray("events");return events.length()==0?null:publicEvent(events.getJSONObject(0));}catch(JSONException e){throw storage();}}}
    public boolean acknowledge(String id)throws FactoryException{synchronized(lock()){if(!token(id))throw WidgetModel.invalid();JSONObject state=load();try{JSONArray old=state.getJSONArray("events"),next=new JSONArray();boolean found=false;for(int i=0;i<old.length();i++){JSONObject event=old.getJSONObject(i);if(id.equals(event.getString("eventId")))found=true;else next.put(event);}if(found){state.put("events",next);save(state);}return found;}catch(JSONException e){throw storage();}}}
    private static JSONObject publicEvent(JSONObject event){JSONObject copy=WidgetModel.json(event.toString());copy.remove("token");return copy;}
    static boolean token(String s){return NotificationActionEvents.validToken(s);}
    static FactoryException storage(){return new FactoryException("STORAGE_UNAVAILABLE","Widget state is unavailable; no configuration or action authority was assumed.");}
}

package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Closed, bounded RemoteViews tree. Values are immutable snapshots, never HTML, code or intents. */
public final class WidgetModel {
    public static final class Limits {
        public final int instances,nodes,depth,actions,modelBytes,actionDataBytes,inboxEvents,storageBytes,imageBytes,imagePixels;
        public Limits(int instances,int nodes,int depth,int actions,int modelBytes,int actionDataBytes,int inboxEvents,int storageBytes,int imageBytes,int imagePixels) {
            if(instances<1||instances>128||nodes<1||nodes>256||depth<1||depth>8||actions<0||actions>64||modelBytes<256||modelBytes>131072||actionDataBytes<2||actionDataBytes>16384||inboxEvents<1||inboxEvents>1024||storageBytes<65536||storageBytes>16777216||imageBytes<1||imageBytes>2097152||imagePixels<1||imagePixels>1048576) throw new IllegalArgumentException("Widget budget outside safe envelope");
            this.instances=instances;this.nodes=nodes;this.depth=depth;this.actions=actions;this.modelBytes=modelBytes;this.actionDataBytes=actionDataBytes;this.inboxEvents=inboxEvents;this.storageBytes=storageBytes;this.imageBytes=imageBytes;this.imagePixels=imagePixels;
        }
        public static Limits fromJson(JSONObject json)throws FactoryException {
            keys(json,"instances","nodes","depth","actions","modelBytes","actionDataBytes","inboxEvents","storageBytes","imageBytes","imagePixels");
            try{return new Limits(integer(json.get("instances"),1,128),integer(json.get("nodes"),1,256),integer(json.get("depth"),1,8),integer(json.get("actions"),0,64),integer(json.get("modelBytes"),256,131072),integer(json.get("actionDataBytes"),2,16384),integer(json.get("inboxEvents"),1,1024),integer(json.get("storageBytes"),65536,16777216),integer(json.get("imageBytes"),1,2097152),integer(json.get("imagePixels"),1,1048576));}catch(JSONException|IllegalArgumentException e){throw invalid();}
        }
        public static Limits defaults(){return new Limits(16,64,6,16,16384,2048,64,1048576,262144,262144);}
        public static Limits ceiling(){return new Limits(128,256,8,64,131072,16384,1024,16777216,2097152,1048576);}
    }
    public static final class Node {
        public final String type,text,asset,description,id,label,delivery; public final int max,value; public final boolean indeterminate;
        public final List<Node> children; private final String data;
        Node(String type,String text,String asset,String description,String id,String label,int max,int value,boolean indeterminate,List<Node> children,String data,String delivery){this.delivery=delivery;this.type=type;this.text=text;this.asset=asset;this.description=description;this.id=id;this.label=label;this.max=max;this.value=value;this.indeterminate=indeterminate;this.children=Collections.unmodifiableList(new ArrayList<>(children));this.data=data;}
        public JSONObject data(){return json(data);}
        JSONObject details(){try{JSONObject v=new JSONObject().put("type",type);if(type.equals("row")||type.equals("column")){JSONArray a=new JSONArray();for(Node n:children)a.put(n.details());v.put("children",a);}else if(type.equals("text"))v.put("text",text);else if(type.equals("image"))v.put("asset",asset).put("description",description);else if(type.equals("progress"))v.put("max",max).put("value",value).put("indeterminate",indeterminate);else v.put("id",id).put("label",label).put("data",data()).put("delivery",delivery);return v;}catch(JSONException e){throw new AssertionError(e);}}
    }
    public final Node root; public final List<Node> buttons; private final String snapshot;
    private WidgetModel(Node root,List<Node> buttons){this.root=root;this.buttons=Collections.unmodifiableList(new ArrayList<>(buttons));try{snapshot=canonical(new JSONObject().put("root",root.details()));}catch(JSONException e){throw new AssertionError(e);}}
    public JSONObject details(){return json(snapshot);} public String canonicalJson(){return snapshot;}
    public static WidgetModel parse(String raw,Limits limits)throws FactoryException{return parse(StrictJson.object(raw,limits.modelBytes,24,8192),limits);}
    public static WidgetModel parse(JSONObject value,Limits limits)throws FactoryException{
        if(value==null||limits==null)throw invalid();StrictJson.object(value.toString(),limits.modelBytes,24,8192);keys(value,"root");
        try{if(!(value.get("root") instanceof JSONObject))throw invalid();ArrayList<Node> actions=new ArrayList<>();WidgetModel model=new WidgetModel(node(value.getJSONObject("root"),limits,1,new int[]{0},actions,new HashSet<>()),actions);if(model.snapshot.getBytes(StandardCharsets.UTF_8).length>limits.modelBytes)throw new FactoryException("TOO_LARGE","Canonical widget model exceeds signed budget.");return model;}catch(JSONException|IllegalArgumentException e){throw invalid();}
    }
    private static Node node(JSONObject o,Limits l,int depth,int[] count,List<Node> actions,Set<String> ids)throws FactoryException,JSONException{
        if(depth>l.depth||++count[0]>l.nodes)throw invalid();String type=string(o,"type",16,false);List<Node> children=new ArrayList<>();String text="",asset="",description="",id="",label="",data="{}",delivery="repeatable";int max=1,value=0;boolean indefinite=false;
        switch(type){
            case "row":case "column":keys(o,"type","children");Object raw=o.get("children");if(!(raw instanceof JSONArray))throw invalid();JSONArray a=(JSONArray)raw;if(a.length()<1||a.length()>l.nodes)throw invalid();for(int i=0;i<a.length();i++){if(!(a.get(i) instanceof JSONObject))throw invalid();children.add(node(a.getJSONObject(i),l,depth+1,count,actions,ids));}break;
            case "text":keys(o,"type","text");text=string(o,"text",4096,true);break;
            case "image":keys(o,"type","asset","description");asset=string(o,"asset",240,false);if(!asset.matches("www/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.(?:png|jpg|jpeg|webp)")||asset.contains(".."))throw invalid();description=string(o,"description",512,true);break;
            case "progress":keys(o,"type","max","value","indeterminate");max=integer(o.get("max"),1,1000000);value=integer(o.get("value"),0,max);if(!(o.get("indeterminate") instanceof Boolean))throw invalid();indefinite=o.getBoolean("indeterminate");break;
            case "button":if(o.has("delivery")){keys(o,"type","id","label","data","delivery");delivery=string(o,"delivery",24,false);if(!delivery.equals("repeatable")&&!delivery.equals("oncePerGeneration"))throw invalid();}else keys(o,"type","id","label","data");id=string(o,"id",64,false);if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")||!ids.add(id)||actions.size()>=l.actions)throw invalid();label=string(o,"label",256,false);if(!(o.get("data") instanceof JSONObject))throw invalid();JSONObject payload=o.getJSONObject("data");StrictJson.object(payload.toString(),l.actionDataBytes,24,8192);data=canonical(payload);break;
            default:throw invalid();
        }
        Node result=new Node(type,text,asset,description,id,label,max,value,indefinite,children,data,delivery);if(type.equals("button"))actions.add(result);return result;
    }
    static String canonical(Object value){return WidgetJson.canonical(value,0);}
    static JSONObject json(String raw){try{return new JSONObject(raw);}catch(JSONException e){throw new IllegalStateException(e);}}
    static void keys(JSONObject value,String... names)throws FactoryException{Set<String> allowed=new HashSet<>(Arrays.asList(names));Iterator<String> i=value.keys();while(i.hasNext())if(!allowed.contains(i.next()))throw invalid();if(value.length()!=names.length)throw invalid();}
    static int integer(Object value,int min,int max)throws FactoryException{if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid();long n=((Number)value).longValue();if(n<min||n>max)throw invalid();return(int)n;}
    static String string(JSONObject o,String key,int bytes,boolean empty)throws FactoryException,JSONException{Object v=o.get(key);if(!(v instanceof String))throw invalid();String s=(String)v;if((!empty&&s.trim().isEmpty())||s.getBytes(StandardCharsets.UTF_8).length>bytes)throw invalid();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isISOControl(c)&&c!='\n'&&c!='\t')throw invalid();if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}return s;}
    static FactoryException invalid(){return new FactoryException("INVALID_ARGUMENT","Invalid bounded widget model or own-instance request.");}
}

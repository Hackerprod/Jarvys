package com.jarvys.factory.runtime;
import org.json.*;
/** Deterministic widget JSON identity. Parsing uses the existing bounded StrictJson implementation. */
final class WidgetJson {
    static String canonical(Object item,int depth) {
        if(depth>24)throw new IllegalArgumentException("Widget JSON depth");
        try {
            if(item instanceof JSONObject){JSONObject v=(JSONObject)item;java.util.List<String> keys=new java.util.ArrayList<>();for(java.util.Iterator<String> i=v.keys();i.hasNext();)keys.add(i.next());java.util.Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String key:keys){if(b.length()>1)b.append(',');unicode(key);b.append(JSONObject.quote(key)).append(':').append(canonical(v.get(key),depth+1));}return b.append('}').toString();}
            if(item instanceof org.json.JSONArray){org.json.JSONArray a=(org.json.JSONArray)item;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i),depth+1));}return b.append(']').toString();}
            if(item==JSONObject.NULL)return "null";
            if(item instanceof String){unicode((String)item);return JSONObject.quote((String)item);}
            if(item instanceof Boolean)return item.toString();
            if(item instanceof Number){double n=((Number)item).doubleValue();if(Double.isNaN(n)||Double.isInfinite(n))throw new IllegalArgumentException("Widget JSON number");return JSONObject.numberToString((Number)item);}
        }catch(JSONException e){throw new IllegalArgumentException(e);}throw new IllegalArgumentException("Widget JSON type");
    }

    private static void unicode(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw new IllegalArgumentException("Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Unicode");}}
}

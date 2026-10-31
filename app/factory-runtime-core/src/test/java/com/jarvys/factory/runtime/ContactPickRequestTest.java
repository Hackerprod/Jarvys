package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContactPickRequestTest {
    private void reject(String raw) { assertThrows(FactoryException.class,()->ContactPickRequest.parse(raw)); }
    @Test public void exactRequiredKindAndStringTypeGrantNoCallerIntentAuthority() throws Exception {
        for (String kind : new String[]{"phone","email"}) {
            JSONObject valid = new JSONObject().put("kind",kind); assertEquals(kind,ContactPickRequest.parse(valid.toString()));
            for (String field : new String[]{"uri","url","mimeType","contactId","lookupKey","name","value","projection","selection","sortOrder","limit","flags","method","args","action","component","package","extras","control","nonce","protocolVersion","intent","selector","all","query"})
                reject(new JSONObject(valid.toString()).put(field,"x").toString());
        }
        reject("{}");
        for (Object kind : new Object[]{JSONObject.NULL,0,true,new JSONObject(),new org.json.JSONArray(),"","PHONE"," phone","email ","all"})
            reject(new JSONObject().put("kind",kind).toString());
    }
    @Test public void strictJsonRejectsDuplicateEscapedDuplicateTrailingAndNonJsonForms() throws Exception {
        assertEquals("phone",ContactPickRequest.parse("{\"kind\":\"ph\\u006fne\"}"));
        for (String bad : new String[]{null,"","[]","null","{'kind':'phone'}","{kind:\"phone\"}","{\"kind\":\"phone\",}","{\"kind\":\"phone\"} trailing","{\"kind\":\"phone\",\"kind\":\"email\"}","{\"kind\":\"phone\",\"ki\\u006ed\":\"email\"}","{\"kind\":\"\\ud800\"}","{\"kind\":\"\\udc00\"}"}) reject(bad);
    }
    @Test public void argumentBytesAreBounded() throws Exception {
        String valid="{\"kind\":\"phone\"}";
        StringBuilder padded=new StringBuilder(valid); while(padded.length()<ContactPickRequest.MAX_ARGS_BYTES)padded.append(' ');
        assertEquals("phone",ContactPickRequest.parse(padded.toString())); reject(padded+" ");
    }
}

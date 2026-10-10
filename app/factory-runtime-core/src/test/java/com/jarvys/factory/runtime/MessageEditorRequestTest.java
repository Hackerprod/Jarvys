package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessageEditorRequestTest {
    static JSONObject args(String method) throws Exception {
        return method.equals("email.compose") ? new JSONObject().put("to","fixture@example.invalid").put("subject","Synthetic").put("body","Fixture\n\tbody") : new JSONObject().put("number","+15550100").put("body","Fixture\n\tbody");
    }
    private void reject(String method,String raw) { assertThrows(FactoryException.class,()->ExternalLaunchRequest.parse(method,raw)); }
    @Test public void exactFieldsTypesAndNoCallerIntentAuthority() throws Exception {
        for(String method:new String[]{"email.compose","sms.compose"}) {
            JSONObject valid=args(method);
            assertEquals("Fixture\n\tbody",ExternalLaunchRequest.parse(method,valid.toString()).body);
            for(String field:method.equals("email.compose")?new String[]{"to","subject","body"}:new String[]{"number","body"}) {
                JSONObject absent=new JSONObject(valid.toString()); absent.remove(field); reject(method,absent.toString());
                for(Object bad:new Object[]{JSONObject.NULL,1,true,new org.json.JSONArray(),new JSONObject()}) {
                    JSONObject mutated=new JSONObject(valid.toString()).put(field,bad); reject(method,mutated.toString());
                }
            }
            for(String field:new String[]{"uri","url","component","package","extras","flags","action","intent","mimeType","selector","callback","cc","bcc","attachments","html","send","account","subscriptionId"})
                reject(method,new JSONObject(valid.toString()).put(field,"x").toString());
        }
    }
    @Test public void strictJsonRejectsDuplicatesAndAllNonJsonEnvelopeForms() throws Exception {
        for(String method:new String[]{"email.compose","sms.compose"}) {
            String valid=args(method).toString();
            for(String bad:new String[]{null,"","[]","null","{} trailing","{'body':'x'}",valid+" trailing",valid.substring(0,valid.length()-1)+",}",valid.substring(0,valid.length()-1)+",\"body\":\"other\"}",valid.substring(0,valid.length()-1)+",\"bo\\u0064y\":\"other\"}"})reject(method,bad);
            reject(method,valid.replace("Fixture\\n\\tbody","\\ud800"));
            reject(method,valid.replace("Fixture\\n\\tbody","\\udc00"));
        }
        for(String method:new String[]{null,"email.send","sms.send","EMAIL.COMPOSE","sms.open","email","intent.launch"})reject(method,args("email.compose").toString());
    }
    @Test public void editorEscapingAllowanceIsBoundedAndLegacyCeilingsStayUnchanged() throws Exception {
        String raw="{\"to\":\"fixture@example.invalid\",\"subject\":\"\",\"body\":\""+MessageEditorSpecTest.repeat("\\u000a",4096)+"\"}";
        assertTrue(raw.length()>8192); assertTrue(raw.length()<32768);
        assertEquals(MessageEditorSpecTest.repeat("\n",4096),ExternalLaunchRequest.parse("email.compose",raw).body);
        String sms="{\"number\":\"0\",\"body\":\""+MessageEditorSpecTest.repeat("\\u0009",4096)+"\"}";
        assertEquals(MessageEditorSpecTest.repeat("\t",4096),ExternalLaunchRequest.parse("sms.compose",sms).body);
        String valid=args("email.compose").toString();
        assertNotNull(ExternalLaunchRequest.parse("email.compose",valid+MessageEditorSpecTest.repeat(" ",32768-valid.length())));
        reject("email.compose",valid+MessageEditorSpecTest.repeat(" ",32769-valid.length()));
        for(String method:new String[]{"maps.open","phone.dial"}) {
            String legacy=method.equals("maps.open")?"{\"query\":\"fixture\"}":"{\"number\":\"0\"}";
            assertNotNull(ExternalLaunchRequest.parse(method,legacy+MessageEditorSpecTest.repeat(" ",8192-legacy.length())));
            reject(method,legacy+MessageEditorSpecTest.repeat(" ",8193-legacy.length()));
        }
    }
}

package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalLaunchRequestTest {
    private void reject(String method,String args) {
        assertThrows(FactoryException.class,()->ExternalLaunchRequest.parse(method,args));
    }
    @Test public void validRequestsHaveExactlyOneTypedInterpretation()throws Exception {
        assertEquals("geo:90,-180",ExternalLaunchRequest.parse("maps.open","{\"latitude\":90,\"longitude\":-180}").uri);
        assertEquals("geo:0,0.0000001",ExternalLaunchRequest.parse("maps.open","{\"longitude\":1e-7,\"latitude\":-0.0}").uri);
        assertEquals("geo:0,0?q=%2520%26%2B%23",ExternalLaunchRequest.parse("maps.open","{\"query\":\"%20&+#\"}").uri);
        assertEquals("tel:+00123",ExternalLaunchRequest.parse("phone.dial","{\"number\":\"+00123\"}").uri);
        assertEquals(ExternalLaunchSpec.Kind.MAPS_QUERY,ExternalLaunchRequest.parse("maps.open","{\"query\":\"\\ud83d\\ude00\"}").kind);
    }
    @Test public void exactKeysXorRequiredFieldsAndTypesCannotBeCoerced() {
        for(String args:new String[]{"{}","{\"query\":null}","{\"query\":1}","{\"latitude\":1}","{\"longitude\":2}","{\"latitude\":\"1\",\"longitude\":2}","{\"latitude\":1,\"longitude\":null}","{\"latitude\":true,\"longitude\":2}","{\"latitude\":[],\"longitude\":2}","{\"query\":\"q\",\"latitude\":1,\"longitude\":2}","{\"latitude\":91,\"longitude\":2}","{\"latitude\":0,\"longitude\":181}","{\"latitude\":1e999,\"longitude\":0}"})reject("maps.open",args);
        for(String args:new String[]{"{}","{\"number\":123}","{\"number\":null}","{\"number\":true}","{\"number\":[]}","{\"number\":{}}"})reject("phone.dial",args);
        for(String key:new String[]{"uri","url","component","package","extras","flags","action","intent","mimeType","selector","callback","number","query"}) {
            if(!key.equals("query"))reject("maps.open","{\"query\":\"q\",\""+key+"\":\"x\"}");
            if(!key.equals("number"))reject("phone.dial","{\"number\":\"123\",\""+key+"\":\"x\"}");
        }
    }
    @Test public void strictJsonRejectsDuplicatesMalformedNonJsonAndUnpairedUnicode() {
        for(String args:new String[]{null,"", "[]","null","{query:'q'}","{\"query\":\"a\",\"query\":\"b\"}","{\"query\":\"a\",\"qu\\u0065ry\":\"b\"}","{\"query\":\"q\",}","{\"query\":\"q\"} trailing","{\"query\":\"\\ud800\"}","{\"query\":\"\\udc00\"}","{\"latitude\":NaN,\"longitude\":0}","{\"latitude\":Infinity,\"longitude\":0}","{\"latitude\":01,\"longitude\":0}","{\"query\":\""+new String(new char[8192]).replace('\0','x')+"\"}"})reject("maps.open",args);
        for(String method:new String[]{null,"maps","maps.dial","maps.navigate","phone.call","phone.open","browser.open","intent.launch","MAPS.OPEN"})reject(method,"{\"number\":\"123\"}");
    }
    @Test public void bridgeUsesSameRulesAndIndependentCapabilities()throws Exception {
        for(String method:new String[]{"maps.open","phone.dial"}) {
            String capability=method.startsWith("maps")?"maps":"phone";
            JSONObject args=method.startsWith("maps")?new JSONObject().put("query","Café"):new JSONObject().put("number","+123");
            FactoryConfig own=FactoryConfig.parsePreview(FactoryDispatcherTest.configuration("[\""+capability+"\"]"));
            assertEquals(method,FactoryDispatcherTest.request(method,args,own).method);
            for(String caps:new String[]{"[]","[\"browser\"]","[\"documents\"]",capability.equals("maps")?"[\"phone\"]":"[\"maps\"]"}) {
                FactoryException error=assertThrows(FactoryException.class,()->FactoryDispatcherTest.request(method,args,FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(caps))));
                assertEquals("CAPABILITY_DENIED",error.code);
            }
            for(String rawArgs:new String[]{args.toString().replace("}",",\"flags\":0}"),"{}"}) {
                assertThrows(FactoryException.class,()->BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,"{\"v\":1,\"id\":\"test\",\"method\":\""+method+"\",\"args\":"+rawArgs+"}",own));
            }
        }
    }
}

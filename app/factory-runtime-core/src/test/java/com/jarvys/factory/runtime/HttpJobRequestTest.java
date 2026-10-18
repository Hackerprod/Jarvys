package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class HttpJobRequestTest {
    static final String ID="12345678-1234-4321-8123-123456789abc";
    static JSONObject input()throws Exception{return new JSONObject().put("jobId",ID).put("request",new JSONObject().put("url","https://example.invalid/job").put("method","POST").put("body","one"));}
    @Test public void immutablePayloadAndCanonicalConstraints()throws Exception{
        JSONObject input=input();HttpJobRequest r=HttpJobRequest.parse(input);input.getJSONObject("request").put("body","two");
        assertEquals("one",new JSONObject(r.payloadJson()).getJSONObject("request").getString("body"));assertEquals("any",r.constraints.network);
        assertEquals(r.fingerprint,HttpJobRequest.parse(r.payloadJson()).fingerprint);
    }
    @Test public void modesDiscloseReplayAndConstraintOverride()throws Exception{
        HttpJobRequest r=HttpJobRequest.parse(input());JSONObject d=r.authorizationDetails("foreground_now",true);
        assertEquals("foreground_now",d.getString("executionMode"));assertTrue(d.getBoolean("replayPossible"));
        assertTrue(d.getJSONObject("request").has("bodySha256"));assertFalse(r.authorizationDetails().getBoolean("replayPossible"));
        try{r.authorizationDetails("scheduled",true);fail();}catch(FactoryException e){assertEquals("INVALID_ARGUMENT",e.code);}
    }
    @Test public void schedulingConstraintsRoundTrip()throws Exception{
        JSONObject input=input().put("constraints",new JSONObject().put("network","unmetered").put("requiresCharging",true).put("minimumLatencyMs",1234).put("overrideDeadlineMs",2345));
        HttpJobRequest r=HttpJobRequest.parse(input);assertTrue(r.constraints.requiresCharging);assertEquals(1234,r.constraints.minimumLatencyMs);assertEquals(2345,r.constraints.overrideDeadlineMs);
    }
    @Test public void rejectsUnboundedAndWrongTypedConstraints()throws Exception{
        Object[] invalid={new JSONObject().put("network","none"),new JSONObject().put("requiresCharging","true"),new JSONObject().put("minimumLatencyMs",-1),new JSONObject().put("minimumLatencyMs",1.5),new JSONObject().put("overrideDeadlineMs",HttpJobRequest.MAX_DELAY_MS+1),new JSONObject().put("minimumLatencyMs",2).put("overrideDeadlineMs",1),new JSONObject().put("periodic",true)};
        for(Object c:invalid)reject(input().put("constraints",c));
        FactoryException field=assertThrows(FactoryException.class,()->HttpJobRequest.parse(input().put("constraints",new JSONObject().put("minimumLatencyMs","later"))));
        assertEquals("jobs.enqueueHttp: constraints.minimumLatencyMs must be an integer from 0 through 2592000000.",field.getMessage());
    }
    @Test public void rejectsExecutableAndHandlePayloads()throws Exception{
        reject(input().put("onComplete","alert(1)"));reject(input().put("request",new JSONObject().put("url","https://example.invalid").put("documentHandle","opaque")));
        reject(input().put("request",new JSONObject().put("url","file:///data/user/0/private")));
        reject(input().put("jobId","../payload"));reject(input().put("jobId",7));
    }
    @Test public void usesExistingNetworkLimitsAndGrammar()throws Exception{
        reject(input().put("request",new JSONObject().put("url","https://example.invalid").put("method","GET").put("body","x")));
        reject(input().put("request",new JSONObject().put("url","https://example.invalid").put("timeoutMs",120001)));
        JSONObject n=new JSONObject().put("url","https://example.invalid").put("method","POST").put("bodyBase64","AAEC/w==").put("responseType","base64");
        HttpJobRequest binary=HttpJobRequest.parse(input().put("request",n));
        assertEquals("base64",binary.request.responseType);
        assertArrayEquals(new byte[]{0,1,2,(byte)255},binary.request.bodyBytes());
        assertEquals(binary.fingerprint,HttpJobRequest.parse(binary.payloadJson()).fingerprint);
    }
    private void reject(JSONObject value)throws Exception{try{HttpJobRequest.parse(value);fail(value.toString());}catch(FactoryException expected){assertNotNull(expected.code);}}
}

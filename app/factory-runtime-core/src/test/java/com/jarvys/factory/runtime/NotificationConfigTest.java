package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationConfigTest {
    private static JSONObject config(boolean capability) throws Exception {
        return new JSONObject().put("schemaVersion",1).put("appId","org.example.notify").put("name","Notify")
                .put("entryPoint","www/index.html").put("capabilities",new JSONArray(capability?java.util.Collections.singletonList("notifications"):java.util.Collections.emptyList()));
    }
    private static JSONObject policy(boolean scheduling,boolean exact) throws Exception { return new JSONObject().put("scheduling",scheduling).put("exactAlarms",exact); }
    @Test public void absenceResolvesDisabledWithoutChangingHistoricalJson() throws Exception {
        for(boolean cap:new boolean[]{false,true}) {
            JSONObject json=config(cap);FactoryConfig parsed=FactoryConfig.parsePreview(json.toString());
            assertFalse(parsed.notifications.scheduling);assertFalse(parsed.notifications.exactAlarms);assertFalse(json.has("notifications"));
        }
    }
    @Test public void typedSchedulingPolicyRequiresCapabilityAndExactRequiresScheduling() throws Exception {
        for(boolean[] flags:new boolean[][]{{false,false},{true,false},{true,true}}) {
            JSONObject json=config(true).put("notifications",policy(flags[0],flags[1]));
            FactoryConfig parsed=FactoryConfig.parsePreview(json.toString());
            assertEquals(flags[0],parsed.notifications.scheduling);assertEquals(flags[1],parsed.notifications.exactAlarms);
            assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config(false).put("notifications",policy(flags[0],flags[1])).toString()));
        }
        for(JSONObject value:java.util.Arrays.asList(policy(false,true),policy(true,true).put("scheduling","true"),policy(true,true).put("exactAlarms",1),
                policy(true,true).put("receiver","other"),new JSONObject().put("scheduling",true)))
            assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config(true).put("notifications",value).toString()));
    }
}

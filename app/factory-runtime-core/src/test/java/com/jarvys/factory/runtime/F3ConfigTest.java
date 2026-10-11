package com.jarvys.factory.runtime;
import com.jarvys.factory.contract.BluetoothPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class F3ConfigTest {
    private JSONObject config(String... caps) throws Exception { return new JSONObject().put("schemaVersion",1).put("appId","org.example.f3").put("name","F3").put("entryPoint","www/index.html").put("capabilities",new JSONArray(caps)); }
    private JSONObject bluetooth(String privacy) throws Exception { return new JSONObject().put("version",1).put("scanPrivacy",privacy); }
    @Test public void explicitBluetoothPrivacyRoundTrips() throws Exception {
        for(String privacy:new String[]{"full","neverForLocation"}) {
            FactoryConfig result=FactoryConfig.parsePreview(config("bluetooth").put("bluetooth",bluetooth(privacy)).toString());
            assertEquals(privacy,result.bluetooth.scanPrivacy); assertEquals(privacy.equals("neverForLocation"),result.bluetooth.neverForLocation());
            assertEquals(result.bluetooth,BluetoothPolicyJson.parse(BluetoothPolicyJson.toJson(result.bluetooth)));
        }
    }
    @Test public void hiddenAndUntypedPrivacyIsRejected() throws Exception {
        for(JSONObject policy:new JSONObject[]{bluetooth("full").put("version","1"),bluetooth("full").put("version",2),bluetooth("full").put("extra",true),bluetooth("full").put("scanPrivacy",false),bluetooth("location")})
            assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config("bluetooth").put("bluetooth",policy).toString()));
        assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config("bluetooth").toString()));
        assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config().put("bluetooth",bluetooth("full")).toString()));
    }
    @Test public void absentHistoricalPolicyRemainsAbsent() throws Exception { assertNull(FactoryConfig.parsePreview(config("storage").toString()).bluetooth); }
    @Test public void jobsRequiresNetworkAndNoArbitraryConfigSection() throws Exception {
        assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config("jobs").toString()));
        assertTrue(FactoryConfig.parsePreview(config("jobs","network").toString()).capabilities.contains("jobs"));
        for(String field:new String[]{"jobs","widgets","nfc"}) assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config(field,"network").put(field,new JSONObject()).toString()));
    }
    @Test public void installedF3AppsRequirePinnedHost() throws Exception {
        for(String cap:new String[]{"nfc","widgets","jobs"}) assertThrows(FactoryException.class,()->FactoryConfig.parse(config(cap,"network").toString(),"org.example.f3"));
        JSONObject source=config("bluetooth").put("bluetooth",bluetooth("full"));
        assertThrows(FactoryException.class,()->FactoryConfig.parse(source.toString(),"org.example.f3"));
        source.put("documentBroker",new JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256",String.join("",java.util.Collections.nCopies(64,"a"))));
        assertNotNull(FactoryConfig.parse(source.toString(),"org.example.f3").bluetooth);
    }
}

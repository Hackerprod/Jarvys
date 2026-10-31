package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** No effects adapter here can open an Activity or perform external I/O. */
public class MessageEditorDispatcherTest {
    private FactoryConfig config(String caps) throws Exception { return FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(caps)); }
    private boolean contains(JSONArray array,String expected) throws Exception {
        for(int i=0;i<array.length();i++)if(expected.equals(array.getString(i)))return true;
        return false;
    }
    @Test public void capabilitiesAreIndependentAndGrantNoAndroidPermissions() throws Exception {
        for(String method:new String[]{"email.compose","sms.compose"}) {
            String capability=method.split("\\.")[0];
            assertEquals(capability,CapabilityCatalog.METHODS.get(method).capability);
            CapabilityCatalog.Capability entry=CapabilityCatalog.CAPABILITIES.get(capability);
            assertTrue(entry.permissions.isEmpty()); assertTrue(entry.features.isEmpty());
            assertTrue(entry.components.isEmpty()); assertTrue(entry.intentFilters.isEmpty());
            assertEquals(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES,entry.queries);
            assertEquals(1,entry.methods.size());
            JSONObject args=MessageEditorRequestTest.args(method);
            assertEquals(method,FactoryDispatcherTest.request(method,args,config("[\""+capability+"\"]")).method);
            for(String caps:new String[]{"[]","[\"phone\"]","[\"maps\"]","[\"browser\"]","[\"share\",\"documents\"]",capability.equals("email")?"[\"sms\"]":"[\"email\"]"}) {
                FactoryException denied=assertThrows(FactoryException.class,()->FactoryDispatcherTest.request(method,args,config(caps)));
                assertEquals("CAPABILITY_DENIED",denied.code);
            }
        }
    }
    @Test public void previewRejectsEditorsViaNonPerformingAdapter() throws Exception {
        FactoryConfig config=config("[\"email\",\"sms\"]");
        for(String method:new String[]{"email.compose","sms.compose"}) {
            BridgeProtocol.Request call=FactoryDispatcherTest.request(method,MessageEditorRequestTest.args(method),config);
            FactoryException error=assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),
                    FactoryDispatcher.previewMetadata("synthetic.host",35,35),FactoryDispatcher.simulatedEffects()));
            assertEquals("UNAVAILABLE",error.code);
        }
    }
    @Test public void installedDispatcherRevalidatesAndDelegatesOnceWithoutClaimingDelivery() throws Exception {
        FactoryConfig config=config("[\"email\",\"sms\"]");
        for(String method:new String[]{"email.compose","sms.compose"}) {
            BridgeProtocol.Request call=FactoryDispatcherTest.request(method,MessageEditorRequestTest.args(method),config);
            AtomicInteger calls=new AtomicInteger();
            Object result=FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","com.example.generated",35,35),
                    (operation,args)->{assertEquals(method,operation.wireName);calls.incrementAndGet();return new JSONObject().put("launchRequested",true).put("actionConfirmed",false);});
            assertEquals(1,calls.get()); assertFalse(((JSONObject)result).getBoolean("actionConfirmed"));
            assertEquals(2,((JSONObject)result).length());
            call.args.put("send",true);
            assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","com.example.generated",35,35),
                    (operation,args)->{throw new AssertionError("Mutated request dispatched");}));
            call.args.remove("send");
            FactoryException denied=assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config("[]"),new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","com.example.generated",35,35),
                    (operation,args)->{throw new AssertionError("Denied capability dispatched");}));
            assertEquals("CAPABILITY_DENIED",denied.code);
        }
    }
    @Test public void previewMetadataNamesUnavailableMethodsCapabilitiesAndLimitsHonestly() throws Exception {
        FactoryConfig config=config("[\"email\",\"sms\"]");
        JSONObject info=(JSONObject)FactoryDispatcher.dispatch(FactoryDispatcherTest.request("runtime.info",new JSONObject(),config),config,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("synthetic.host",35,35),(operation,args)->{throw new AssertionError("Metadata called effect");});
        for(String capability:new String[]{"email","sms"}) {
            assertTrue(contains(info.getJSONArray("implementedCapabilities"),capability));
            assertTrue(contains(info.getJSONArray("unavailableCapabilities"),capability));
            assertTrue(contains(info.getJSONArray("unavailableMethods"),capability+".compose"));
            assertFalse(contains(info.getJSONArray("simulatedCapabilities"),capability));
            assertEquals("[\""+capability+"\"]",info.getJSONArray(capability+"Requires").toString());
        }
        assertTrue(info.getBoolean("externalEditorsMaySyncDrafts"));
        assertTrue(info.getBoolean("externalRecipientsMayUseNetwork"));
        assertTrue(info.getBoolean("externalLaunchBrokerRequired"));
        assertFalse(info.getBoolean("externalActionConfirmed"));
        JSONObject limits=info.getJSONObject("limits");
        assertEquals(254,limits.getInt("emailAddressCharacters"));
        assertEquals(256,limits.getInt("editorSubjectCodePoints"));
        assertEquals(1024,limits.getInt("editorSubjectBytes"));
        assertEquals(4096,limits.getInt("editorBodyBytes"));
        assertEquals(32768,limits.getInt("editorArgumentsBytes"));
    }
    @Test public void installedConfigurationRequiresPinnedBrokerForEitherEditor() throws Exception {
        for(String capability:new String[]{"email","sms"}) {
            FactoryException denied=assertThrows(FactoryException.class,()->FactoryConfig.parse(FactoryDispatcherTest.configuration("[\""+capability+"\"]"),"com.example.generated"));
            assertEquals("INVALID_CONFIG",denied.code);
            assertNotNull(config("[\""+capability+"\"]"));
        }
    }
}

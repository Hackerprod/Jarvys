package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DatabaseDispatcherTest {
    FactoryConfig config(String caps)throws Exception{return FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(caps));}
    BridgeProtocol.Request request(String method,JSONObject args,FactoryConfig config)throws Exception{return FactoryDispatcherTest.request(method,args,config);}
    Object dispatch(BridgeProtocol.Request request,FactoryConfig config,FactoryDispatcher.Database adapter)throws Exception{
        return FactoryDispatcher.dispatch(request,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.previewMetadata("host",28,35),FactoryDispatcher.simulatedEffects(),adapter);
    }
    @Test public void databaseIsIndependentFromEveryOtherCapabilityAndHost()throws Exception{
        FactoryConfig database=config("[\"database\"]");assertNull(database.documentBroker);
        for(String capability:CapabilityCatalog.NAMES) if(!"database".equals(capability)) {
            FactoryException denied=assertThrows(FactoryException.class,()->request("database.info",new JSONObject(),config("[\""+capability+"\"]")));assertEquals("CAPABILITY_DENIED",denied.code);
        }
        assertTrue(CapabilityCatalog.CAPABILITIES.get("database").manifestNodes.isEmpty());
        assertEquals("fixture",dispatch(request("database.info",new JSONObject(),database),database,(method,args)->"fixture"));
    }
    @Test public void dispatchRevalidatesRevokedCapabilityAndUnknownArguments()throws Exception{
        FactoryConfig database=config("[\"database\"]");BridgeProtocol.Request request=request("database.info",new JSONObject(),database);
        assertEquals("CAPABILITY_DENIED",assertThrows(FactoryException.class,()->dispatch(request,config("[]"),(m,a)->{throw new AssertionError();})).code);
        request.args.put("sql","SELECT 1");assertEquals("INVALID_ARGUMENT",assertThrows(FactoryException.class,()->dispatch(request,database,(m,a)->{throw new AssertionError();})).code);
    }
    @Test public void missingBackendIsExplicitlyUnavailableNeverSimulatedSuccess()throws Exception{
        FactoryConfig database=config("[\"database\"]");assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->dispatch(request("database.info",new JSONObject(),database),database,null)).code);
    }
    @Test public void wrongFrameOrOriginCannotReachBackend()throws Exception{
        FactoryConfig database=config("[\"database\"]");String raw="{\"v\":1,\"id\":\"x\",\"method\":\"database.info\",\"args\":{}}";
        assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate(BridgeProtocol.ORIGIN,false,raw,database)).code);
        assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate("file://",true,raw,database)).code);
    }
    @Test public void metadataDistinguishesNativeEphemeralStorageFromExternalEffects()throws Exception{
        FactoryConfig database=config("[\"database\"]");JSONObject result=(JSONObject)dispatch(request("runtime.info",new JSONObject(),database),database,null);
        assertEquals(1,result.getInt("databaseProtocolVersion"));assertFalse(result.getBoolean("databaseBrokerRequired"));assertFalse(result.getBoolean("databasePersistent"));
        assertFalse(result.getJSONArray("simulatedCapabilities").toString().contains("database"));assertFalse(result.getJSONArray("unavailableCapabilities").toString().contains("database"));
    }
}

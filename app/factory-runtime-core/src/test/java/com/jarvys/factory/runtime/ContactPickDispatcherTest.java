package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Synthetic dispatch only. No provider query, contact or communication is used. */
public class ContactPickDispatcherTest {
    private FactoryConfig config(String caps) throws Exception { return FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(caps)); }
    private boolean contains(JSONArray array,String expected) throws Exception { for(int i=0;i<array.length();i++)if(expected.equals(array.getString(i)))return true; return false; }
    @Test public void contactCapabilityIsIndependentAndAddsOnlyExactHostQueries() throws Exception {
        CapabilityCatalog.Capability entry=CapabilityCatalog.CAPABILITIES.get("contacts");
        assertEquals("contacts",CapabilityCatalog.METHODS.get("contacts.pick").capability);
        assertEquals(1,entry.methods.size()); assertEquals(CapabilityCatalog.Method.CONTACTS_PICK,entry.methods.get(0));
        assertTrue(entry.permissions.isEmpty()); assertTrue(entry.features.isEmpty()); assertTrue(entry.components.isEmpty()); assertTrue(entry.intentFilters.isEmpty());
        assertTrue(entry.resources.isEmpty()); assertTrue(entry.dependencies.isEmpty()); assertEquals(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES,entry.queries);
        for (String kind:new String[]{"phone","email"}) {
            JSONObject args=new JSONObject().put("kind",kind);
            assertEquals("contacts.pick",FactoryDispatcherTest.request("contacts.pick",args,config("[\"contacts\"]")).method);
            for(String caps:new String[]{"[]","[\"phone\"]","[\"email\"]","[\"sms\"]","[\"maps\"]","[\"browser\"]","[\"share\",\"documents\"]"}) {
                FactoryException denied=assertThrows(FactoryException.class,()->FactoryDispatcherTest.request("contacts.pick",args,config(caps)));
                assertEquals("CAPABILITY_DENIED",denied.code);
            }
        }
    }
    @Test public void previewCannotSelectContactAndInstalledAdapterRunsOnlyAfterRevalidation() throws Exception {
        FactoryConfig config=config("[\"contacts\"]");
        for(String kind:new String[]{"phone","email"}) {
            BridgeProtocol.Request call=FactoryDispatcherTest.request("contacts.pick",new JSONObject().put("kind",kind),config);
            FactoryException denied=assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.previewMetadata("synthetic.host",35,35),FactoryDispatcher.simulatedEffects()));
            assertEquals("UNAVAILABLE",denied.code);
            AtomicInteger count=new AtomicInteger();
            JSONObject result=(JSONObject)FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","synthetic.app",35,35),(method,args)->{count.incrementAndGet();assertEquals(CapabilityCatalog.Method.CONTACTS_PICK,method);return new JSONObject().put("kind",kind).put("value","Synthetic fixture");});
            assertEquals(1,count.get()); assertEquals(2,result.length()); assertEquals(kind,result.getString("kind"));
            call.args.put("value","forged");
            assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","synthetic.app",35,35),(method,args)->{throw new AssertionError("Mutated request dispatched");}));
            call.args.remove("value");
            assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config("[]"),new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata("installed","synthetic.app",35,35),(method,args)->{throw new AssertionError("Undeclared contact capability dispatched");}));
        }
    }
    @Test public void metadataReportsMinimalDataSemanticsHostRequirementsAndBounds() throws Exception {
        for(String mode:new String[]{"preview","installed"}) {
            FactoryConfig config=config("[\"contacts\"]");
            JSONObject info=(JSONObject)FactoryDispatcher.dispatch(FactoryDispatcherTest.request("runtime.info",new JSONObject(),config),config,new BoundedStore(new MemoryBackend()),FactoryDispatcher.metadata(mode,"synthetic.host",35,35),FactoryDispatcher.simulatedEffects());
            assertTrue(contains(info.getJSONArray("implementedCapabilities"),"contacts"));
            assertEquals("preview".equals(mode),contains(info.getJSONArray("unavailableCapabilities"),"contacts"));
            assertEquals("preview".equals(mode),contains(info.getJSONArray("unavailableMethods"),"contacts.pick"));
            assertFalse(contains(info.getJSONArray("simulatedCapabilities"),"contacts"));
            assertEquals("[\"contacts\"]",info.getJSONArray("contactsRequires").toString());
            assertEquals("[\"phone\",\"email\"]",info.getJSONArray("contactsKinds").toString());
            assertEquals(1,info.getInt("contactPickProtocolVersion")); assertTrue(info.getBoolean("contactsBrokerRequired"));
            assertFalse(info.getBoolean("contactsValueNormalized")); assertFalse(info.getBoolean("contactsValueSyntaxValidated"));
            assertFalse(info.getBoolean("externalActionConfirmed"));
            JSONObject limits=info.getJSONObject("limits"); assertEquals(256,limits.getInt("contactValueCodePoints")); assertEquals(1024,limits.getInt("contactValueBytes")); assertEquals(300000,limits.getInt("contactPickLifetimeMs")); assertEquals(128,limits.getInt("contactArgumentsBytes"));
        }
    }
    @Test public void installedContactsRequirePinnedHostButNeverDocuments() throws Exception {
        String plain=FactoryDispatcherTest.configuration("[\"contacts\"]");
        assertEquals("INVALID_CONFIG",assertThrows(FactoryException.class,()->FactoryConfig.parse(plain,"com.example.generated")).code);
        assertNull(FactoryConfig.parsePreview(plain).documentBroker);
        for(String host:CapabilityCatalog.DOCUMENT_BROKER_PACKAGES) {
            String pinned=new JSONObject(plain).put("documentBroker",new JSONObject().put("packageName",host).put("certificateSha256",new String(new char[64]).replace('\0','a'))).toString();
            FactoryConfig valid=FactoryConfig.parse(pinned,"com.example.generated"); assertEquals(host,valid.documentBroker.packageName); assertEquals(java.util.Collections.singleton("contacts"),valid.capabilities);
        }
    }
}

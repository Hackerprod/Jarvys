package com.jarvys.factory.runtime;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class IncomingShareConfigTest {
    private static JSONObject policy() throws Exception { return new JSONObject().put("mimeTypes",new JSONArray(Arrays.asList("image/*","text/plain"))).put("multiple",true); }
    private static JSONObject config(String... caps) throws Exception {
        return new JSONObject().put("schemaVersion",1).put("appId","org.example.share").put("name","Share")
                .put("entryPoint","www/index.html").put("capabilities",new JSONArray(Arrays.asList(caps)));
    }
    @Test public void exactTypedPolicyRoundTripsAndRequiresExplicitDocuments() throws Exception {
        JSONObject json=config("incomingShare","documents").put("incomingShare",policy());
        FactoryConfig parsed=FactoryConfig.parsePreview(json.toString());
        assertEquals(Arrays.asList("image/*","text/plain"),parsed.incomingShare.mimeTypes); assertTrue(parsed.incomingShare.multiple);
        assertEquals(policy().toString(),IncomingSharePolicyJson.toJson(parsed.incomingShare).toString());
        for(String[] caps:new String[][]{{"incomingShare"},{"documents"},{}})
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(config(caps).put("incomingShare",policy()).toString()));
        assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(config("documents","incomingShare").toString()));
        assertNull(FactoryConfig.parsePreview(config("documents").toString()).incomingShare);
    }
    @Test public void stringsExtraFieldsMissingFieldsNullAndNoncanonicalMimesFail() throws Exception {
        for(JSONObject value:Arrays.asList(policy().put("multiple","true"), policy().put("multiple",1),policy().put("multiple",JSONObject.NULL),
                policy().put("scheme","content"),policy().put("mimeTypes",new JSONArray(Arrays.asList("text/plain","image/*"))),
                policy().put("mimeTypes",new JSONArray(Arrays.asList("text/plain","text/plain"))),policy().put("mimeTypes",new JSONArray(Collections.singletonList(7))),
                new JSONObject().put("multiple",true),new JSONObject().put("mimeTypes",new JSONArray(Collections.singletonList("text/plain")))))
            assertEquals("INVALID_CONFIG",assertThrows(FactoryException.class, () -> IncomingSharePolicyJson.parse(value)).code);
        assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(config("documents","incomingShare").put("incomingShare",JSONObject.NULL).toString()));
    }
}

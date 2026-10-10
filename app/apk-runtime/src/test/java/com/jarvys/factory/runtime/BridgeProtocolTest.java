package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeProtocolTest {
    private static final String HANDLE = new String(new char[64]).replace('\0', 'a');
    static FactoryConfig config(String capabilities) throws Exception {
        return FactoryConfig.parsePreview("{\"schemaVersion\":1,\"appId\":\"com.example.notes\",\"name\":\"Notes\",\"entryPoint\":\"www/index.html\",\"capabilities\":" + capabilities + "}");
    }
    static String request(String method, String args) {
        return "{\"v\":1,\"id\":\"r_1\",\"method\":\"" + method + "\",\"args\":" + args + "}";
    }
    private void rejected(String expected, String origin, boolean main, String raw, FactoryConfig config) throws Exception {
        try { BridgeProtocol.validate(origin, main, raw, config); fail("Expected " + expected); }
        catch (FactoryException e) { assertEquals(expected, e.code); }
    }
    @Test public void originAndFrameAreCheckedBeforeParsing() throws Exception {
        FactoryConfig config = config("[\"storage\"]");
        String[] origins = {"null", "file://", "content://app", "http://app.jarvys.invalid", "https://evil.example", "https://app.jarvys.invalid.evil.example", "https://app.jarvys.invalid/", "https://app.jarvys.invalid:443", "https://user@app.jarvys.invalid"};
        for (String origin : origins) rejected("UNTRUSTED_SOURCE", origin, true, "not json", config);
        rejected("UNTRUSTED_SOURCE", BridgeProtocol.ORIGIN, false, request("storage.get", "{\"key\":\"notes\"}"), config);
    }
    @Test public void storageRequiresDeclarationButRuntimeIntrospectionDoesNot() throws Exception {
        FactoryConfig empty = config("[]");
        rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request("storage.get", "{\"key\":\"notes\"}"), empty);
        assertEquals("runtime.info", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request("runtime.info", "{}"), empty).method);
        String[][] methods = {{"export.text", "{\"filename\":\"note.txt\",\"text\":\"hi\"}"}, {"share.text", "{\"text\":\"hi\"}"}, {"clipboard.write", "{\"text\":\"hi\"}"}, {"haptics.perform", "{}"}, {"device.info", "{}"},
            {"documents.open", "{\"mimeType\":\"*/*\"}"},
            {"documents.create", "{\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}"},
            {"documents.read", "{\"handle\":\""+HANDLE+"\",\"offset\":0,\"length\":1}"},
            {"documents.write", "{\"handle\":\""+HANDLE+"\",\"offset\":0,\"data\":\"AA==\"}"},
            {"documents.close", "{\"handle\":\""+HANDLE+"\"}"}, {"documents.cancel", "{}"},
            {"share.file", "{\"handle\":\""+HANDLE+"\",\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}"}};
        for (String[] call : methods) rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request(call[0], call[1]), empty);
    }
    @Test public void typedAllowlistRejectsDangerousOrUnknownRequests() throws Exception {
        FactoryConfig all = config("[\"storage\",\"export\",\"share\",\"clipboard\",\"haptics\",\"device\"]");
        for (String method : new String[]{"shell.exec", "intent.start", "location.get", "network.fetch", "java.invoke", "storage.clearAll", "permission.grant"})
            rejected("UNKNOWN_METHOD", BridgeProtocol.ORIGIN, true, request(method, "{}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("storage.set", "{\"key\":\"notes\",\"value\":123}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("storage.get", "{\"key\":\"../otherapp\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("device.info", "{\"serial\":true}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("haptics.perform", "{\"kind\":\"continuous\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("export.text", "{\"filename\":\"../x.txt\",\"text\":\"a\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("export.text", "{\"filename\":\"a.apk\",\"text\":\"a\",\"mimeType\":\"application/vnd.android.package-archive\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("share.text", "{\"text\":\"a\",\"targetPackage\":\"evil\"}"), all);
    }
    @Test public void strictJsonRejectsDuplicatesTrailingCommentsAndDeepNesting() throws Exception {
        FactoryConfig all = config("[\"storage\"]");
        String[] invalid = {"{'v':1}", "{v:1}", "{\"v\":1,}", "{\"v\":1,\"v\":1}", "{\"v\":1} /* comment */", "{\"a\":NaN}", "{\"a\":01}", "{\"a\":\"line\nline\"}", "{\"a\":[[[[[[[[[[1]]]]]]]]]]}"};
        for (String raw : invalid) rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, raw, all);
        rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, "{\"x\":\"" + '\\' + "uＦＦＦＦ\"}", all);
    }
    @Test public void boundedMessagesAndUtf8Values() throws Exception {
        FactoryConfig all = config("[\"storage\"]");
        String huge = new String(new char[BridgeProtocol.MAX_MESSAGE_BYTES + 1]).replace('\0', 'x');
        rejected("TOO_LARGE", BridgeProtocol.ORIGIN, true, huge, all);
        String unicode = new String(new char[22000]).replace('\0', '界');
        String raw = new JSONObject().put("v", 1).put("id", "utf8").put("method", "storage.set")
                .put("args", new JSONObject().put("key", "test").put("value", unicode)).toString();
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, raw, all);
        assertEquals("storage.set", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                request("storage.set", "{\"key\":\"notes.v1\",\"value\":\"hello\"}"), all).method);
    }
    @Test public void responsesAreEscapedAndPreserveTypedValues() throws Exception {
        JSONObject success = new JSONObject(BridgeProtocol.success("r_1", "\"quoted\"\ntext"));
        assertTrue(success.getBoolean("ok")); assertEquals("\"quoted\"\ntext", success.getString("result"));
        assertTrue(new JSONObject(BridgeProtocol.success("r_1", null)).isNull("result"));
        JSONObject failure = new JSONObject(BridgeProtocol.failure("r_1", "CANCELLED", "Cancelled"));
        assertFalse(failure.getBoolean("ok")); assertEquals("CANCELLED", failure.getJSONObject("error").getString("code"));
    }
    @Test public void all128CapabilitySelectionsMatchCatalogAndTypedValidators() throws Exception {
        String[][] calls = {{"runtime.info", "{}"}, {"storage.get", "{\"key\":\"x\"}"},
            {"storage.set", "{\"key\":\"x\",\"value\":\"v\"}"}, {"storage.remove", "{\"key\":\"x\"}"},
            {"storage.list", "{}"}, {"export.text", "{\"filename\":\"x.txt\",\"text\":\"x\"}"},
            {"share.text", "{\"text\":\"x\"}"}, {"clipboard.write", "{\"text\":\"x\"}"},
            {"haptics.perform", "{}"}, {"device.info", "{}"},
            {"documents.open", "{\"mimeType\":\"*/*\"}"},
            {"documents.create", "{\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}"},
            {"documents.read", "{\"handle\":\""+HANDLE+"\",\"offset\":0,\"length\":1}"},
            {"documents.write", "{\"handle\":\""+HANDLE+"\",\"offset\":0,\"data\":\"AA==\"}"},
            {"documents.close", "{\"handle\":\""+HANDLE+"\"}"}, {"documents.cancel", "{}"},
            {"share.file", "{\"handle\":\""+HANDLE+"\",\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}"}};
        assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.size(), calls.length);
        for (int mask = 0; mask < 128; mask++) {
            org.json.JSONArray declared = new org.json.JSONArray();
            for (int bit = 0; bit < 7; bit++) if ((mask & (1 << bit)) != 0)
                declared.put(com.jarvys.factory.contract.CapabilityCatalog.NAMES.get(bit));
            FactoryConfig selected = config(declared.toString());
            for (String[] call : calls) {
                com.jarvys.factory.contract.CapabilityCatalog.Method method =
                    com.jarvys.factory.contract.CapabilityCatalog.METHODS.get(call[0]);
                assertNotNull(method);
                if ((method.capability == null || selected.capabilities.contains(method.capability))
                        && (!call[0].equals("share.file") || selected.capabilities.contains("documents"))) {
                    assertSame(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                        request(call[0], call[1]), selected).operation);
                } else rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true,
                    request(call[0], call[1]), selected);
                org.json.JSONObject invalid = new org.json.JSONObject(call[1]).put("undeclared", true);
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true,
                    request(call[0], invalid.toString()), selected);
            }
        }
    }

    @Test public void sharingRejectsUrisPathsMimeWildcardsAndExtraAuthority() throws Exception {
        FactoryConfig both=config("[\"share\",\"documents\"]");
        JSONObject valid=new JSONObject().put("handle",HANDLE).put("filename","file.bin").put("mimeType","application/octet-stream");
        assertEquals("share.file",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("share.file",valid.toString()),both).method);
        for (String extra:new String[]{"uri","path","targetPackage","flags","bytes","offset","persist"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("share.file",new JSONObject(valid.toString()).put(extra,"ignored").toString()),both);
        for (String mime:new String[]{"*/*","image/*","text/plain; charset=utf-8","text/plain\n","content://private"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("share.file",new JSONObject(valid.toString()).put("mimeType",mime).toString()),both);
        for (String name:new String[]{"../secret","/secret","a\\b","a..b","name.",".hidden","a\n"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("share.file",new JSONObject(valid.toString()).put("filename",name).toString()),both);
    }

    @Test public void documentsRejectPathsNonCanonicalChunksAndNonIntegerOffsets() throws Exception {
        FactoryConfig docs = config("[\"documents\"]");
        String[] mimeInvalid = {"text/plain; charset=utf-8", "*/plain", "text/plain,application/json", "file:///tmp/a", "", "text/plain\n"};
        for (String mime : mimeInvalid) {
            JSONObject args = new JSONObject().put("mimeType", mime);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("documents.open", args.toString()), docs);
        }
        for (String mime : new String[]{"*/*","text/*","image/png","application/vnd.example+json"})
            BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("documents.open",new JSONObject().put("mimeType",mime).toString()),docs);
        for (String mime : new String[]{"*/*","text/*"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.create",new JSONObject().put("filename","file.txt").put("mimeType",mime).toString()),docs);
        for (String data : new String[]{"", "AA", "AB==", "AAA", "AAB=", "AA==\n", "AA--", "====", new String(new char[43696]).replace('\0','A')})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.write",new JSONObject().put("handle",HANDLE).put("offset",0).put("data",data).toString()),docs);
        for (String number : new String[]{"0.0","1e0","-1","16777217","2147483648","\"0\"","null"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.read","{\"handle\":\""+HANDLE+"\",\"offset\":"+number+",\"length\":1}"),docs);
        for (String extra : new String[]{"uri","path","url","persist","packageName"}) {
            JSONObject args = new JSONObject().put("handle",HANDLE).put(extra,"content://private");
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.close",args.toString()),docs);
        }
        for (String handle : new String[]{HANDLE.toUpperCase(),HANDLE.substring(1),"content://private"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.close",new JSONObject().put("handle",handle).toString()),docs);
        for (int length : new int[]{0,-1,32769})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.read",new JSONObject().put("handle",HANDLE).put("offset",0).put("length",length).toString()),docs);
        BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("documents.read",new JSONObject().put("handle",HANDLE).put("offset",16777216).put("length",1).toString()),docs);
        String maximum = java.util.Base64.getEncoder().encodeToString(new byte[32768]);
        BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("documents.write",new JSONObject().put("handle",HANDLE).put("offset",16744448).put("data",maximum).toString()),docs);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("documents.write",new JSONObject().put("handle",HANDLE).put("offset",16744449).put("data",maximum).toString()),docs);
    }

}

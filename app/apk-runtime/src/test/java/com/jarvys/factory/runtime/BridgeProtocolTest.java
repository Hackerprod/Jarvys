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
            {"photos.pick", "{}"}, {"photos.capture", "{}"},
            {"audio.play", "{\"handle\":\""+HANDLE+"\"}"},
            {"maps.open", "{\"query\":\"Synthetic map fixture\"}"},
            {"phone.dial", "{\"number\":\"+15550100\"}"},
            {"email.compose", "{\"to\":\"fixture@example.invalid\",\"subject\":\"Fixture\",\"body\":\"Body\"}"},
            {"sms.compose", "{\"number\":\"+15550100\",\"body\":\"Body\"}"},
            {"contacts.pick", "{\"kind\":\"phone\"}"},
            {"calendar.insert", "{\"title\":\"Synthetic event\",\"location\":\"\",\"description\":\"\",\"startTimeMillis\":0,\"endTimeMillis\":86400000,\"timeZone\":\"UTC\",\"allDay\":true}"},
            {"biometrics.availability", "{}"},
            {"biometrics.authenticate", "{\"reason\":\"Confirm this local action\"}"},
            {"biometrics.cancel", "{}"},
            {"sensors.list", "{}"},
            {"sensors.capture", "{\"kind\":\"light\",\"durationMs\":100,\"intervalMs\":100,\"maxSamples\":1}"},
            {"sensors.cancel", "{}"},
            {"presentation.get", "{}"},
            {"presentation.set", "{\"theme\":\"dark\",\"orientation\":\"portrait\"}"},
            {"presentation.reset", "{}"},
            {"database.info", "{}"},
            {"database.migrate", "{\"fromVersion\":0,\"toVersion\":1,\"steps\":[{\"kind\":\"createTable\",\"table\":\"notes\",\"columns\":{\"text\":{\"type\":\"text\",\"nullable\":false}}}]}"},
            {"database.transact", "{\"version\":1,\"operations\":[{\"kind\":\"insert\",\"table\":\"notes\",\"id\":\"one\",\"values\":{\"text\":\"fixture\"}}]}"},
            {"database.select", "{\"version\":1,\"table\":\"notes\"}"},
            {"storage.backupExport", "{\"version\":1,\"keys\":[\"settings\"],\"filename\":\"backup.json\"}"},
            {"storage.backupImport", "{\"version\":1,\"keys\":[\"settings\"],\"handle\":\""+HANDLE+"\",\"mode\":\"insertOnly\"}"},
            {"database.backupExport", "{\"version\":1,\"tables\":[\"notes\"],\"filename\":\"backup.json\"}"},
            {"database.backupImport", "{\"version\":1,\"tables\":[\"notes\"],\"handle\":\""+HANDLE+"\",\"mode\":\"insertOnly\"}"},
            {"database.close", "{}"},
            {"database.cancel", "{}"},
            {"browser.open", "{\"url\":\"https://example.com/review?fixture=1\"}"},
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
    @Test public void allCapabilitySelectionsMatchCatalogAndTypedValidators() throws Exception {
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
            {"photos.pick", "{}"}, {"photos.capture", "{}"},
            {"audio.play", "{\"handle\":\""+HANDLE+"\"}"},
            {"maps.open", "{\"query\":\"Synthetic map fixture\"}"},
            {"phone.dial", "{\"number\":\"+15550100\"}"},
            {"email.compose", "{\"to\":\"fixture@example.invalid\",\"subject\":\"Fixture\",\"body\":\"Body\"}"},
            {"sms.compose", "{\"number\":\"+15550100\",\"body\":\"Body\"}"},
            {"contacts.pick", "{\"kind\":\"phone\"}"},
            {"calendar.insert", "{\"title\":\"Synthetic event\",\"location\":\"\",\"description\":\"\",\"startTimeMillis\":0,\"endTimeMillis\":86400000,\"timeZone\":\"UTC\",\"allDay\":true}"},
            {"biometrics.availability", "{}"},
            {"biometrics.authenticate", "{\"reason\":\"Confirm this local action\"}"},
            {"biometrics.cancel", "{}"},
            {"sensors.list", "{}"},
            {"sensors.capture", "{\"kind\":\"light\",\"durationMs\":100,\"intervalMs\":100,\"maxSamples\":1}"},
            {"sensors.cancel", "{}"},
            {"presentation.get", "{}"},
            {"presentation.set", "{\"theme\":\"dark\",\"orientation\":\"portrait\"}"},
            {"presentation.reset", "{}"},
            {"database.info", "{}"},
            {"database.migrate", "{\"fromVersion\":0,\"toVersion\":1,\"steps\":[{\"kind\":\"createTable\",\"table\":\"notes\",\"columns\":{\"text\":{\"type\":\"text\",\"nullable\":false}}}]}"},
            {"database.transact", "{\"version\":1,\"operations\":[{\"kind\":\"insert\",\"table\":\"notes\",\"id\":\"one\",\"values\":{\"text\":\"fixture\"}}]}"},
            {"database.select", "{\"version\":1,\"table\":\"notes\"}"},
            {"storage.backupExport", "{\"version\":1,\"keys\":[\"settings\"],\"filename\":\"backup.json\"}"},
            {"storage.backupImport", "{\"version\":1,\"keys\":[\"settings\"],\"handle\":\""+HANDLE+"\",\"mode\":\"insertOnly\"}"},
            {"database.backupExport", "{\"version\":1,\"tables\":[\"notes\"],\"filename\":\"backup.json\"}"},
            {"database.backupImport", "{\"version\":1,\"tables\":[\"notes\"],\"handle\":\""+HANDLE+"\",\"mode\":\"insertOnly\"}"},
            {"database.close", "{}"},
            {"database.cancel", "{}"},
            {"browser.open", "{\"url\":\"https://example.com/review?fixture=1\"}"},
            {"share.file", "{\"handle\":\""+HANDLE+"\",\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}"}};
        assertEquals(46, calls.length);
        assertEquals(20, com.jarvys.factory.contract.CapabilityCatalog.NAMES.size());
        assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.size(), calls.length);
        CapabilityDecisionTree.requireReviewedProductionSources();
        java.util.List<CapabilityDecisionTree<Object>> validTrees = new java.util.ArrayList<>();
        java.util.List<CapabilityDecisionTree<Object>> invalidTrees = new java.util.ArrayList<>();
        int validLeaves = 0, invalidLeaves = 0;
        final int[] wireInvocations = {0};
        for (String[] call : calls) {
            final String valid = request(call[0], call[1]);
            final String invalid = request(call[0], new JSONObject(call[1]).put("undeclared", true).toString());
            for (String raw : new String[]{valid, invalid}) {
                CapabilityDecisionTree<Object> tree = CapabilityDecisionTree.explore(capabilities -> {
                    wireInvocations[0]++;
                    try {
                        return BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, raw,
                                CapabilityDecisionTree.config(capabilities)).operation;
                    } catch (FactoryException rejected) { return rejected.code; }
                });
                if (raw == valid) { validTrees.add(tree); validLeaves += tree.leaves(); }
                else { invalidTrees.add(tree); invalidLeaves += tree.leaves(); }
            }
        }
        // All reachable membership paths, not a hand-picked projection or cached test result.
        assertEquals(99, validLeaves);
        assertEquals(46, invalidLeaves);
        assertEquals(198, wireInvocations[0]);
        int checked = 0;
        long assertions = 0;
        for (int mask = 0; mask < (1 << com.jarvys.factory.contract.CapabilityCatalog.NAMES.size()); mask++) {
            org.json.JSONArray declared = new org.json.JSONArray();
            for (int bit = 0; bit < com.jarvys.factory.contract.CapabilityCatalog.NAMES.size(); bit++) if ((mask & (1 << bit)) != 0)
                declared.put(com.jarvys.factory.contract.CapabilityCatalog.NAMES.get(bit));
            FactoryConfig selected = config(declared.toString());
            // Independently bind the parsed set used for replay to every bit of this subset.
            assertEquals(Integer.bitCount(mask), selected.capabilities.size());
            for (int bit = 0; bit < com.jarvys.factory.contract.CapabilityCatalog.NAMES.size(); bit++)
                assertEquals((mask & (1 << bit)) != 0, selected.capabilities.contains(
                        com.jarvys.factory.contract.CapabilityCatalog.NAMES.get(bit)));
            for (int index = 0; index < calls.length; index++) {
                String[] call = calls[index];
                com.jarvys.factory.contract.CapabilityCatalog.Method method =
                    com.jarvys.factory.contract.CapabilityCatalog.METHODS.get(call[0]);
                assertNotNull(method);
                Object actual = validTrees.get(index).outcome(selected.capabilities);
                if ((method.capability == null || selected.capabilities.contains(method.capability))
                        && (!(call[0].equals("share.file") || call[0].startsWith("photos.") || call[0].equals("audio.play") || call[0].equals("database.backupExport") || call[0].equals("database.backupImport") || call[0].equals("storage.backupExport") || call[0].equals("storage.backupImport")) || selected.capabilities.contains("documents"))) {
                    assertSame(method, actual);
                } else assertEquals("CAPABILITY_DENIED", actual);
                assertEquals("INVALID_ARGUMENT", invalidTrees.get(index).outcome(selected.capabilities));
                assertions += 2;
            }
            checked++;
        }
        assertEquals(1048576, checked);
        assertEquals(96468992L, assertions);
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

    @Test public void photosRejectEveryCallerControlledOptionAndRequireBothDeclarations() throws Exception {
        FactoryConfig both = config("[\"photos\",\"documents\"]");
        for (String method : new String[]{"photos.pick", "photos.capture"}) {
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, "{}"), both).method);
            for (String caps : new String[]{"[]", "[\"photos\"]", "[\"documents\"]"})
                rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request(method, "{}"), config(caps));
            for (String extra : new String[]{"uri", "path", "mimeType", "output", "camera", "packageName", "flags", "persist", "multiple", "width", "quality"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true,
                        request(method, new JSONObject().put(extra, "forbidden").toString()), both);
            for (String invalid : new String[]{"null", "[]", "true", "1", "\"image/jpeg\""})
                rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, request(method, invalid), both);
        }
    }

    @Test public void audioAcceptsOnlyOpaqueHandleAndRequiresBothDeclarations() throws Exception {
        FactoryConfig both=config("[\"audio\",\"documents\"]");
        JSONObject args=new JSONObject().put("handle",HANDLE);
        assertEquals("audio.play",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("audio.play",args.toString()),both).method);
        for(String extra:new String[]{"uri","path","url","text","data","mimeType","volume","loop","autoplay","control"})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("audio.play",new JSONObject(args.toString()).put(extra,"x").toString()),both);
        for(String handle:new String[]{"content://private/sound","/private/file","https://remote/sound","AA==","",HANDLE.toUpperCase()})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("audio.play",new JSONObject().put("handle",handle).toString()),both);
        for(String caps:new String[]{"[]","[\"audio\"]","[\"documents\"]"})
            rejected("CAPABILITY_DENIED",BridgeProtocol.ORIGIN,true,request("audio.play",args.toString()),config(caps));
    }
    @Test public void browserRequiresOnlyBrowserAndRejectsExtraAuthorityOrUntypedUrl() throws Exception {
        FactoryConfig browser = config("[\"browser\"]");
        JSONObject args = new JSONObject().put("url", "https://example.com/path?q=1");
        assertEquals("browser.open", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request("browser.open", args.toString()), browser).method);
        for (String capability : new String[]{"documents", "photos", "audio", "share", "device"})
            rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request("browser.open", args.toString()), config("[\"" + capability + "\"]"));
        for (String extra : new String[]{"headers", "cookies", "packageName", "component", "flags", "intent", "body", "method", "referrer", "control", "nonce"})
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true,
                    request("browser.open", new JSONObject(args.toString()).put(extra, "forbidden").toString()), browser);
        for (Object value : new Object[]{true, 1, JSONObject.NULL, new JSONObject(), new org.json.JSONArray()})
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("browser.open", new JSONObject().put("url", value).toString()), browser);
        rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, request("browser.open", "{}"), browser);
        for (String url : new String[]{"http://example.com", "javascript:alert(1)", "https://user@example.com", "https://example.com/#private", "https://example.com/%0a", "https://EXAMPLE.com"})
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("browser.open", new JSONObject().put("url", url).toString()), browser);
    }


    @Test public void presentationRequiresExactEnumPairAndEmptyGetResetArguments() throws Exception {
        FactoryConfig enabled = config("[\"presentation\"]");
        for (String theme : new String[]{"system", "light", "dark"}) {
            for (String orientation : new String[]{"system", "portrait", "landscape"}) {
                JSONObject args = new JSONObject().put("theme", theme).put("orientation", orientation);
                assertEquals("presentation.set", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                        request("presentation.set", args.toString()), enabled).method);
            }
        }
        for (String args : new String[]{"{}", "{\"theme\":\"dark\"}", "{\"orientation\":\"portrait\"}",
                "{\"theme\":null,\"orientation\":\"portrait\"}", "{\"theme\":\"dark\",\"orientation\":null}",
                "{\"theme\":1,\"orientation\":\"portrait\"}", "{\"theme\":\"dark\",\"orientation\":true}",
                "{\"theme\":\"Dark\",\"orientation\":\"portrait\"}", "{\"theme\":\"dark\",\"orientation\":\"Portrait\"}",
                "{\"theme\":\"auto\",\"orientation\":\"portrait\"}", "{\"theme\":\"dark\",\"orientation\":\"sensor\"}",
                "{\"theme\":\" dark\",\"orientation\":\"portrait\"}", "{\"theme\":\"dark\",\"orientation\":\"portrait \"}",
                "{\"theme\":\"dark\",\"orientation\":\"portrait\",\"appId\":\"other.app\"}"}) {
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("presentation.set", args), enabled);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("presentation.set", args), config("[]"));
        }
        for (String method : new String[]{"presentation.get", "presentation.reset"}) {
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, "{}"), enabled).method);
            for (String args : new String[]{"{\"theme\":\"system\"}", "{\"orientation\":\"system\"}", "{\"appId\":\"other.app\"}"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, args), enabled);
        }
        for (String method : new String[]{"presentation.get", "presentation.set", "presentation.reset"})
            for (String args : new String[]{"null", "[]", "true", "1", "\"dark\""})
                rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, request(method, args), enabled);
    }

    @Test public void sensorWireArgumentsAreStrictBoundedAndNeverGrantBackgroundAuthority() throws Exception {
        FactoryConfig enabled=config("[\"sensors\"]");
        JSONObject valid=new JSONObject().put("kind","light").put("durationMs",100).put("intervalMs",100).put("maxSamples",1);
        for(String key:new String[]{"durationMs","intervalMs","maxSamples"}){
            for(String bad:new String[]{"null","true","\"100\"","100.0","1e2","-1","2147483648"}){
                String args=valid.toString().replace("\""+key+"\":"+valid.get(key),"\""+key+"\":"+bad);
                rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("sensors.capture",args),enabled);
            }
            JSONObject missing=new JSONObject(valid.toString());missing.remove(key);rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("sensors.capture",missing.toString()),enabled);
        }
        for(String kind:new String[]{"gyroscope_uncalibrated","magnetometer","step_counter","location","LIGHT"})rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("sensors.capture",new JSONObject(valid.toString()).put("kind",kind).toString()),enabled);
        for(String method:new String[]{"sensors.list","sensors.cancel"})for(String extra:new String[]{"background","persist","sensorId","vendor"})rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request(method,new JSONObject().put(extra,true).toString()),enabled);
    }

    @Test public void gyroscopeWireRetainsArgumentPrecedenceAndStandaloneGrant() throws Exception {
        String args="{\"kind\":\"gyroscope\",\"durationMs\":5000,\"intervalMs\":100,\"maxSamples\":50}";
        assertEquals("sensors.capture",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("sensors.capture",args),config("[\"sensors\"]")).method);
        rejected("CAPABILITY_DENIED",BridgeProtocol.ORIGIN,true,request("sensors.capture",args),config("[]"));
        assertEquals("sensors.capture",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("sensors.capture",args.replace("5000","300000")),config("[\"sensors\"]")).method);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("sensors.capture",args.replace("5000","300001")),config("[]"));
        for(String key:new String[]{"durationMs","intervalMs","maxSamples"}){
            JSONObject missing=new JSONObject(args);missing.remove(key);rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("sensors.capture",missing.toString()),config("[\"sensors\"]"));
        }
    }

    @Test public void sensorCancellationEnvelopeHasExactUtf8ControlBudget() throws Exception {
        FactoryConfig enabled=config("[\"sensors\"]");
        String escaped="{\"v\":1,\"id\":\"boundary\",\"method\":\"sensors."+"\\"+"u0063ancel\",\"args\":{}}";
        String padded=escaped+new String(new char[1024-escaped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length]).replace('\0',' ');
        assertEquals(1024,padded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("sensors.cancel",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,padded,enabled).method);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,padded+" ",enabled);
        String unicode=request("sensors.cancel","{\"界\":true}");
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,unicode,enabled);
        rejected("INVALID_REQUEST",BridgeProtocol.ORIGIN,true,escaped+"\u00a0",enabled);
    }


    @Test public void biometricsRequiresExactSafeReasonAndIndependentCapability() throws Exception {
        FactoryConfig enabled=config("[\"biometrics\"]");
        for(String method:new String[]{"biometrics.availability","biometrics.authenticate","biometrics.cancel"}) {
            String args=method.endsWith("authenticate")?"{\"reason\":\"Confirm this local action\"}":"{}";
            assertEquals(method,BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request(method,args),enabled).method);
            rejected("CAPABILITY_DENIED",BridgeProtocol.ORIGIN,true,request(method,args),config("[]"));
            rejected("UNTRUSTED_SOURCE","https://evil.invalid",true,request(method,args),enabled);
            rejected("UNTRUSTED_SOURCE",BridgeProtocol.ORIGIN,false,request(method,args),enabled);
            for(String extra:new String[]{"credential","fallback","cryptoObject","token","appId","title","background","timeoutMs","challenge","nonce"}) {
                JSONObject altered=new JSONObject(args).put(extra,true);
                rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request(method,altered.toString()),enabled);
            }
            for(String invalid:new String[]{"null","[]","true","1","\"reason\""})
                rejected("INVALID_REQUEST",BridgeProtocol.ORIGIN,true,request(method,invalid),enabled);
        }
        for(Object reason:new Object[]{JSONObject.NULL,true,1,new JSONObject(),new org.json.JSONArray(),"","   ","line\nline","tab\tvalue","bad\u0000value","bidi\u202evalue","line\u2028value","bad\ud800",new String(new char[161]).replace('\0','a')})
            rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("biometrics.authenticate",new JSONObject().put("reason",reason).toString()),enabled);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("biometrics.authenticate","{}"),enabled);
        for(String reason:new String[]{"Confirm this local action","Confirmación local 東京",new String(new char[160]).replace('\0','a')})
            assertEquals(reason,BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,request("biometrics.authenticate",new JSONObject().put("reason",reason).toString()),enabled).args.getString("reason"));
    }

    @Test public void biometricCancellationHasExactUtf8BudgetAndNoCallerOptions() throws Exception {
        FactoryConfig enabled=config("[\"biometrics\"]");
        String escaped="{\"v\":1,\"id\":\"boundary\",\"method\":\"biometrics."+"\\"+"u0063ancel\",\"args\":{}}";
        String padded=escaped+new String(new char[1024-escaped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length]).replace('\0',' ');
        assertEquals(1024,padded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("biometrics.cancel",BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,padded,enabled).method);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,padded+" ",enabled);
        rejected("INVALID_ARGUMENT",BridgeProtocol.ORIGIN,true,request("biometrics.cancel","{\"reason\":\"x\"}"),enabled);
        rejected("INVALID_REQUEST",BridgeProtocol.ORIGIN,true,escaped+"\u00a0",enabled);
    }
    @Test public void backupArgumentsArePureAndNeedDatabaseAndDocumentsButNotPolicyDuringValidation() throws Exception {
        for (String method : new String[]{"database.backupExport", "database.backupImport"}) {
            JSONObject args = new JSONObject().put("version", 1).put("tables", new org.json.JSONArray("[\"notes\"]"));
            if (method.equals("database.backupExport")) args.put("filename", "backup.json");
            else args.put("handle", HANDLE).put("mode", "insertOnly");
            FactoryConfig both = config("[\"database\",\"documents\"]");
            assertTrue(both.dataBackupTables.isEmpty());
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, args.toString()), both).method);
            for (String caps : new String[]{"[]", "[\"database\"]", "[\"documents\"]"})
                rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request(method, args.toString()), config(caps));
            for (String extra : new String[]{"sql", "uri", "path", "all", "certificate", "allowSecrets"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put(extra, true).toString()), both);
            for (String tables : new String[]{"[]", "[\"notes\",\"notes\"]", "[\"tasks\",\"notes\"]", "[1]", "[\"id\"]", "[\"sqlite_master\"]"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("tables", new org.json.JSONArray(tables)).toString()), both);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("version", "1").toString()), both);
            if (method.equals("database.backupImport")) {
                for (String mode : new String[]{"replace", "merge", "insert", ""})
                    rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("mode", mode).toString()), both);
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("handle", "content://private").toString()), both);
            } else rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("filename", "../backup.json").toString()), both);
        }
    }

    @Test public void storageBackupArgumentsArePureAndRequireStorageAndDocumentsWithoutPolicy() throws Exception {
        FactoryConfig both = config("[\"storage\",\"documents\"]");
        assertTrue(both.dataBackupKeys.isEmpty());
        for (String method : new String[]{"storage.backupExport", "storage.backupImport"}) {
            JSONObject args = new JSONObject().put("version", 1).put("keys", new org.json.JSONArray("[\"A.key:1-\",\"settings\"]"));
            if (method.endsWith("Export")) args.put("filename", "backup.json");
            else args.put("handle", HANDLE).put("mode", "insertOnly");
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, args.toString()), both).method);
            for (String caps : new String[]{"[]", "[\"storage\"]", "[\"documents\"]", "[\"database\",\"documents\"]"})
                rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request(method, args.toString()), config(caps));
            for (String extra : new String[]{"tables", "uri", "path", "all", "preferences", "certificate", "allowSecrets"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put(extra, true).toString()), both);
            for (String keys : new String[]{"[]", "[\"a\",\"a\"]", "[\"b\",\"a\"]", "[1]", "[null]", "[\"a/b\"]", "[\"a b\"]", "[\"\"]"})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("keys", new org.json.JSONArray(keys)).toString()), both);
            for (Object version : new Object[]{0, 2, "1", JSONObject.NULL, true})
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("version", version).toString()), both);
            org.json.JSONArray maximum = new org.json.JSONArray();
            for (int i = 0; i < 32; i++) maximum.put(String.format(java.util.Locale.ROOT, "key%02d", i));
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("keys", maximum).toString()), both).method);
            maximum.put("key32");
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("keys", maximum).toString()), both);
            if (method.endsWith("Import")) {
                assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("mode", "replaceSelectedKeys").toString()), both).method);
                for (String mode : new String[]{"replace", "merge", "replaceSelectedTables", ""})
                    rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("mode", mode).toString()), both);
                rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("handle", "content://private").toString()), both);
            } else rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, new JSONObject(args.toString()).put("filename", "../backup.json").toString()), both);
        }
    }

    @Test public void documentAndDatabaseCancellationEnvelopesShareStrictOneKiBBoundary() throws Exception {
        for (String method : new String[]{"documents.cancel", "database.cancel"}) {
            FactoryConfig enabled = config("[\"" + method.substring(0, method.indexOf('.')) + "\"]");
            String raw = request(method, "{}");
            String maximum = raw + new String(new char[1024 - raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length]).replace('\0', ' ');
            assertEquals(1024, maximum.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertEquals(method, BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, maximum, enabled).method);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, maximum + " ", enabled);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, maximum + " ", config("[]"));
            rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, maximum, config("[]"));
            rejected("UNTRUSTED_SOURCE", "https://example.invalid", true, maximum + " ", enabled);
            rejected("UNTRUSTED_SOURCE", BridgeProtocol.ORIGIN, false, maximum + " ", enabled);
            rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request(method, "{\"all\":true}"), enabled);
        }
    }

}

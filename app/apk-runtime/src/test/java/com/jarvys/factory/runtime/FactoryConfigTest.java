package com.jarvys.factory.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class FactoryConfigTest {
    private String text(String id, String entry, String capabilities) {
        return "{\"schemaVersion\":1,\"appId\":\"" + id + "\",\"name\":\"Notes\",\"entryPoint\":\"" + entry + "\",\"capabilities\":" + capabilities + "}";
    }
    @Test public void configurationMatchesDynamicInstalledIdentity() throws Exception {
        assertEquals("com.first.notes", FactoryConfig.parse(text("com.first.notes", "www/index.html", "[]"), "com.first.notes").appId);
        assertEquals("com.second.tasks", FactoryConfig.parse(text("com.second.tasks", "www/index.html", "[\"storage\"]"), "com.second.tasks").appId);
        reject(text("com.first.notes", "www/index.html", "[]"), "com.second.tasks");
    }
    @Test public void rejectUnknownDuplicateCapabilityAndTraversal() throws Exception {
        assertNull(FactoryConfig.parse(text("com.example.app", "www/index.html", "[\"location\"]"), "com.example.app").documentBroker);
        reject(text("com.example.app", "www/index.html", "[\"storage\",\"storage\"]"), "com.example.app");
        for (String path : new String[]{"www/../secret.html", "file:///x.html", "www/%2e%2e/x.html", "factory-app.json", "www//index.html", "www/index.html?next=evil", "www/script.js"})
            reject(text("com.example.app", path, "[]"), "com.example.app");
    }
    @Test public void declaredCapabilitiesAreImmutable() throws Exception {
        FactoryConfig config = FactoryConfig.parse(text("com.example.app", "www/index.html", "[\"storage\"]"), "com.example.app");
        try { config.capabilities.add("export"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void documentBrokerIsPinnedForHostEffectsAndPinsCannotRemainUnused() throws Exception {
        String base = text("com.example.app", "www/index.html", "[\"documents\"]");
        reject(base, "com.example.app");
        assertNull(FactoryConfig.parsePreview(base).documentBroker);
        String digest = new String(new char[64]).replace('\0', 'a');
        for (String host : new String[]{"com.jarvys.agent","com.jarvys.agent.recoverytest"}) {
            org.json.JSONObject config = new org.json.JSONObject(base).put("documentBroker",
                new org.json.JSONObject().put("packageName",host).put("certificateSha256",digest));
            FactoryConfig parsed = FactoryConfig.parse(config.toString(),"com.example.app");
            assertEquals(host,parsed.documentBroker.packageName); assertEquals(digest,parsed.documentBroker.certificateSha256);
            assertEquals(host,FactoryConfig.parsePreview(config.toString()).documentBroker.packageName);
            config.put("capabilities",new org.json.JSONArray());
            reject(config.toString(),"com.example.app");
            assertThrows(FactoryException.class,()->FactoryConfig.parsePreview(config.toString()));
        }
        for (String host : new String[]{"org.example.broker","com.jarvys.agent.fake","com.jarvys.agent.debug",""}) {
            org.json.JSONObject config = new org.json.JSONObject(base).put("documentBroker",
                new org.json.JSONObject().put("packageName",host).put("certificateSha256",digest));
            reject(config.toString(),"com.example.app");
        }
        for (String certificate : new String[]{digest.toUpperCase(), "aa", digest + "a", "", "sha256:" + digest}) {
            org.json.JSONObject config = new org.json.JSONObject(base).put("documentBroker",
                new org.json.JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256",certificate));
            reject(config.toString(),"com.example.app");
        }
        org.json.JSONObject metadata = new org.json.JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256",digest);
        reject(new org.json.JSONObject(base).put("documentBroker",metadata.put("uri","content://private")).toString(),"com.example.app");
        reject(new org.json.JSONObject(base).put("documentBroker",org.json.JSONObject.NULL).toString(),"com.example.app");
    }

    @Test public void externalCapabilitiesAloneRequireAndAcceptTheSameBuildOwnedBrokerWithoutDocuments() throws Exception {
        String digest = new String(new char[64]).replace('\0', 'a');
        for (String caps : new String[]{"[\"share\"]", "[\"clipboard\"]", "[\"browser\"]", "[\"maps\"]", "[\"phone\"]", "[\"email\"]", "[\"sms\"]", "[\"contacts\"]", "[\"calendar\"]", "[\"browser\",\"documents\",\"maps\",\"phone\"]"}) {
            String base = text("com.example.app", "www/index.html", caps);
            reject(base, "com.example.app");
            assertNull(FactoryConfig.parsePreview(base).documentBroker);
            for (String host : new String[]{"com.jarvys.agent", "com.jarvys.agent.recoverytest"}) {
                org.json.JSONObject json = new org.json.JSONObject(base).put("documentBroker",
                        new org.json.JSONObject().put("packageName", host).put("certificateSha256", digest));
                FactoryConfig parsed = FactoryConfig.parse(json.toString(), "com.example.app");
                assertEquals(host, parsed.documentBroker.packageName);
                assertEquals(caps.contains("documents"), parsed.capabilities.contains("documents"));
            }
        }
        for (String caps : new String[]{"[\"audio\"]", "[\"photos\"]"}) {
            org.json.JSONObject json = new org.json.JSONObject(text("com.example.app", "www/index.html", caps)).put("documentBroker",
                    new org.json.JSONObject().put("packageName", "com.jarvys.agent").put("certificateSha256", digest));
            assertEquals("com.jarvys.agent",FactoryConfig.parse(json.toString(),"com.example.app").documentBroker.packageName);
        }
        reject(new org.json.JSONObject(text("com.example.app", "www/index.html", "[]")).put("documentBroker",
                new org.json.JSONObject().put("packageName", "com.jarvys.agent").put("certificateSha256", digest)).toString(),"com.example.app");
    }
    private void reject(String text, String installed) throws Exception {
        try { FactoryConfig.parse(text, installed); fail("Invalid config accepted"); } catch (FactoryException expected) { }
    }

    @Test public void presentationIsStandaloneAndRejectsAnUnusedHostPin() throws Exception {
        String base = text("com.example.app", "www/index.html", "[\"presentation\"]");
        FactoryConfig installed = FactoryConfig.parse(base, "com.example.app");
        assertEquals(java.util.Collections.singleton("presentation"), installed.capabilities);
        assertNull(installed.documentBroker);
        assertNull(FactoryConfig.parsePreview(base).documentBroker);
        org.json.JSONObject broker = new org.json.JSONObject().put("packageName", "com.jarvys.agent")
                .put("certificateSha256", new String(new char[64]).replace('\0', 'a'));
        reject(new org.json.JSONObject(base).put("documentBroker", broker).toString(),"com.example.app");
        reject(text("com.example.app", "www/index.html", "[\"presentation\",\"presentation\"]"), "com.example.app");
    }

    @Test public void dataBackupPolicyDefaultsEmptyAndIsStrictImmutableAndBounded() throws Exception {
        String base = text("com.example.app", "www/index.html", "[\"database\",\"documents\"]");
        assertTrue(FactoryConfig.parsePreview(base).dataBackupTables.isEmpty());
        org.json.JSONObject json = new org.json.JSONObject(base).put("dataBackup", new org.json.JSONObject().put("tables", new org.json.JSONArray("[\"notes\",\"tasks\"]")));
        FactoryConfig parsed = FactoryConfig.parsePreview(json.toString());
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("notes", "tasks")), parsed.dataBackupTables);
        assertThrows(UnsupportedOperationException.class, () -> parsed.dataBackupTables.add("other"));
        for (String policy : new String[]{"null", "[]", "{}", "{\"tables\":null}", "{\"tables\":\"notes\"}",
                "{\"tables\":[],\"all\":true}", "{\"tables\":[\"notes\",\"notes\"]}", "{\"tables\":[\"tasks\",\"notes\"]}",
                "{\"tables\":[1]}", "{\"tables\":[\"id\"]}", "{\"tables\":[\"sqlite_master\"]}", "{\"tables\":[\"factory_schema\"]}",
                "{\"tables\":[\"Notes\"]}", "{\"tables\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}"}) {
            String invalid = base.substring(0, base.length() - 1) + ",\"dataBackup\":" + policy + "}";
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(invalid));
        }
        for (String caps : new String[]{"[]", "[\"database\"]", "[\"documents\"]"}) {
            json.put("capabilities", new org.json.JSONArray(caps));
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
            json.getJSONObject("dataBackup").put("tables", new org.json.JSONArray());
            assertTrue(FactoryConfig.parsePreview(json.toString()).dataBackupTables.isEmpty());
            json.getJSONObject("dataBackup").put("tables", new org.json.JSONArray("[\"notes\"]"));
        }
    }

    @Test public void storageBackupPolicyIsIndependentStrictBoundedAndImmutable() throws Exception {
        String base = text("com.example.app", "www/index.html", "[\"storage\",\"documents\"]");
        assertTrue(FactoryConfig.parsePreview(base).dataBackupKeys.isEmpty());
        org.json.JSONObject json = new org.json.JSONObject(base).put("dataBackup", new org.json.JSONObject()
                .put("keys", new org.json.JSONArray("[\"A.key:1-\",\"settings\"]")));
        FactoryConfig parsed = FactoryConfig.parsePreview(json.toString());
        assertTrue(parsed.dataBackupTables.isEmpty());
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("A.key:1-", "settings")), parsed.dataBackupKeys);
        assertThrows(UnsupportedOperationException.class, () -> parsed.dataBackupKeys.add("other"));
        for (String policy : new String[]{"{\"keys\":null}", "{\"keys\":\"settings\"}", "{\"keys\":[],\"all\":true}",
                "{\"keys\":[\"a\",\"a\"]}", "{\"keys\":[\"b\",\"a\"]}", "{\"keys\":[1]}", "{\"keys\":[null]}",
                "{\"keys\":[\"\"]}", "{\"keys\":[\"a/b\"]}", "{\"keys\":[\"a b\"]}", "{\"tables\":null,\"keys\":[]}"})
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(base.substring(0, base.length() - 1) + ",\"dataBackup\":" + policy + "}"));
        for (String caps : new String[]{"[]", "[\"storage\"]", "[\"documents\"]", "[\"database\",\"documents\"]"}) {
            json.put("capabilities", new org.json.JSONArray(caps));
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
            json.put("dataBackup", new org.json.JSONObject().put("keys", new org.json.JSONArray()));
            assertTrue(FactoryConfig.parsePreview(json.toString()).dataBackupKeys.isEmpty());
            json.put("dataBackup", new org.json.JSONObject().put("keys", new org.json.JSONArray("[\"settings\"]")));
        }
        json.put("capabilities", new org.json.JSONArray("[\"storage\",\"documents\"]"));
        org.json.JSONArray maximum = new org.json.JSONArray();
        for (int i = 0; i < 32; i++) maximum.put(String.format(java.util.Locale.ROOT, "key%02d", i));
        json.put("dataBackup", new org.json.JSONObject().put("keys", maximum));
        assertEquals(32, FactoryConfig.parsePreview(json.toString()).dataBackupKeys.size());
        maximum.put("key32");
        assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
        String longest = new String(new char[96]).replace('\0', 'a');
        json.put("dataBackup", new org.json.JSONObject().put("keys", new org.json.JSONArray().put(longest)));
        assertTrue(FactoryConfig.parsePreview(json.toString()).dataBackupKeys.contains(longest));
        json.getJSONObject("dataBackup").put("keys", new org.json.JSONArray().put(longest + "a"));
        assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
        json.put("dataBackup", new org.json.JSONObject().put("tables", new org.json.JSONArray("[\"notes\"]"))
                .put("keys", new org.json.JSONArray("[\"settings\"]")));
        assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
        json.put("capabilities", new org.json.JSONArray("[\"storage\",\"database\",\"documents\"]"));
        assertEquals(java.util.Collections.singleton("notes"), FactoryConfig.parsePreview(json.toString()).dataBackupTables);
        assertEquals(java.util.Collections.singleton("settings"), FactoryConfig.parsePreview(json.toString()).dataBackupKeys);
    }

}

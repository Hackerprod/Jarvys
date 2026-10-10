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
        reject(text("com.example.app", "www/index.html", "[\"location\"]"), "com.example.app");
        reject(text("com.example.app", "www/index.html", "[\"storage\",\"storage\"]"), "com.example.app");
        for (String path : new String[]{"www/../secret.html", "file:///x.html", "www/%2e%2e/x.html", "factory-app.json", "www//index.html", "www/index.html?next=evil", "www/script.js"})
            reject(text("com.example.app", path, "[]"), "com.example.app");
    }
    @Test public void declaredCapabilitiesAreImmutable() throws Exception {
        FactoryConfig config = FactoryConfig.parse(text("com.example.app", "www/index.html", "[\"storage\"]"), "com.example.app");
        try { config.capabilities.add("export"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void documentBrokerIsPinnedAndOnlyAcceptedForDocuments() throws Exception {
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
            config.put("capabilities",new org.json.JSONArray()); reject(config.toString(),"com.example.app");
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

    @Test public void browserAloneRequiresAndAcceptsTheSameBuildOwnedBrokerWithoutDocuments() throws Exception {
        String digest = new String(new char[64]).replace('\0', 'a');
        for (String caps : new String[]{"[\"browser\"]", "[\"browser\",\"documents\"]"}) {
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
        for (String caps : new String[]{"[]", "[\"audio\"]", "[\"photos\"]", "[\"share\"]"}) {
            org.json.JSONObject json = new org.json.JSONObject(text("com.example.app", "www/index.html", caps)).put("documentBroker",
                    new org.json.JSONObject().put("packageName", "com.jarvys.agent").put("certificateSha256", digest));
            reject(json.toString(), "com.example.app");
        }
    }
    private void reject(String text, String installed) throws Exception {
        try { FactoryConfig.parse(text, installed); fail("Invalid config accepted"); } catch (FactoryException expected) { }
    }
}

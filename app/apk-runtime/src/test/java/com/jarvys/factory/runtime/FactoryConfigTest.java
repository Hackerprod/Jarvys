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
    private void reject(String text, String installed) throws Exception {
        try { FactoryConfig.parse(text, installed); fail("Invalid config accepted"); } catch (FactoryException expected) { }
    }
}

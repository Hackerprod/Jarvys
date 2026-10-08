package com.jarvys.factory.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssetPolicyTest {
    @Test public void allowsOnlyBundledWebAssetsAndSdk() {
        assertEquals("factory-sdk.js", AssetPolicy.assetPath(BridgeProtocol.ORIGIN + "/factory-sdk.js"));
        assertEquals("www/app.js", AssetPolicy.assetPath(BridgeProtocol.ORIGIN + "/www/app.js"));
        assertTrue(AssetPolicy.navigationAllowed(BridgeProtocol.ORIGIN + "/www/index.html#note", true));
        assertFalse(AssetPolicy.navigationAllowed(BridgeProtocol.ORIGIN + "/www/index.html", false));
        assertFalse(AssetPolicy.navigationAllowed(BridgeProtocol.ORIGIN + "/www/app.js", true));
    }
    @Test public void deniesExternalAndPrivilegedSchemesEncodedTraversalAndConfiguration() {
        String[] rejected = {"file:///data/data/x", "content://provider/id", "https://example.com/a.js", "http://app.jarvys.invalid/www/index.html", "javascript:alert(1)", "intent://other", "data:text/html,hello", BridgeProtocol.ORIGIN + "/factory-app.json", BridgeProtocol.ORIGIN + "/www/../factory-app.json", BridgeProtocol.ORIGIN + "/www/%2e%2e/x.js", BridgeProtocol.ORIGIN + "/www/app.js?external=1", "https://user@app.jarvys.invalid/www/app.js", "https://app.jarvys.invalid:443/www/app.js", BridgeProtocol.ORIGIN + "/www//app.js", BridgeProtocol.ORIGIN + "/www/evil.apk"};
        for (String url : rejected) assertNull(url, AssetPolicy.assetPath(url));
    }
    @Test public void cspDisallowsInlineJavaScriptNetworkFramesAndWorkers() {
        assertTrue(AssetPolicy.CSP.contains("script-src 'self';"));
        for (String directive : new String[]{"connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "form-action 'none'", "frame-ancestors 'none'", "object-src 'none'"}) assertTrue(AssetPolicy.CSP.contains(directive));
    }
}

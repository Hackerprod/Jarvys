package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.BrowserUrl;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure parser tests; no URL is resolved, visited or sent to a browser. */
public class BrowserUrlTest {
    private static String repeat(char c, int count) { return new String(new char[count]).replace('\0', c); }
    private static void rejected(String url) {
        try { BrowserUrl.parse(url); fail("Accepted forbidden URL: " + url); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void originalBytesArePreservedWithoutDecodingOrNormalization() {
        for (String url : new String[]{"https://example.com", "https://example.com/", "https://example.com:443/",
                "https://www.example.com/a/../b?x=a%2fb&y=%C3%A9", "https://example.com/?", "https://xn--bcher-kva.example/a%23b",
                "https://example.com/path?account=user%40example.com", "https://example.com/%2520"}) {
            BrowserUrl parsed = BrowserUrl.parse(url);
            assertSame(url, parsed.url); assertEquals(java.net.URI.create(url).getHost(), parsed.host);
        }
    }
    @Test public void lengthAndDnsBoundariesAreExact() {
        String prefix = "https://example.com/";
        assertEquals(2048, BrowserUrl.parse(prefix + repeat('a', 2048 - prefix.length())).url.length());
        rejected(prefix + repeat('a', 2049 - prefix.length()));
        String host = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + "." + repeat('d', 61);
        assertEquals(253, BrowserUrl.parse("https://" + host).host.length());
        rejected("https://" + host + "e"); rejected("https://" + repeat('a', 64) + ".com");
        for (String url : new String[]{null, "", "https://localhost", "https://x", "https://.com", "https://example..com", "https://example.com.",
                "https://-example.com", "https://example-.com", "https://ex_ample.com", "https://EXAMPLE.com", "https://example.COM"}) rejected(url);
    }
    @Test public void alternateSchemesCredentialsFragmentsAndNoncanonicalPortsAreRejected() {
        for (String url : new String[]{"http://example.com", "HTTPS://example.com", "//example.com", "https:example.com", "https:///example.com",
                "javascript:alert(1)", "intent://example.com/#Intent;end", "file:///tmp/private", "content://private/file", "data:text/html,x",
                "https://user@example.com", "https://user:password@example.com", "https://@example.com", "https://example.com/#", "https://example.com/#private",
                "https://example.com:", "https://example.com:0443", "https://example.com:4430", "https://example.com:+443", "https://example.com:-1",
                "https://example.com:0", "https://example.com:65536", "https://%65xample.com/"}) rejected(url);
    }
    @Test public void numericIpAndReservedLocalHostsAreRejected() {
        for (String host : new String[]{"127.0.0.1", "127.1", "2130706433", "0177.0.0.1", "0x7f.0.0.1", "0x7f000001", "127.0x1",
                "1.2.3.0xffffffff", "example.123", "[::1]", "[2001:db8::1]", "localhost", "x.localhost", "a.local", "a.internal"})
            rejected("https://" + host + "/");
    }
    @Test public void literalAndEscapedControlsAndBackslashAreRejectedWithoutEcho() {
        for (int value = 0; value <= 0x20; value++) {
            rejected("https://example.com/" + (char)value);
            rejected("https://example.com/%" + String.format(java.util.Locale.ROOT, "%02x", value));
        }
        for (String tail : new String[]{"%7f", "%7F", "%5c", "%5C", "\\", "a\\b", "café", "\u007f", "\u0080", "\ud83d\ude00", "%", "%1", "%GG", "%ＦＦ"})
            rejected("https://example.com/" + tail);
    }
}

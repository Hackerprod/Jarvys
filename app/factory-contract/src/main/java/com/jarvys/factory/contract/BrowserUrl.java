package com.jarvys.factory.contract;

import java.net.URI;
import java.net.URISyntaxException;

/** Closed browser-launch grammar. Validation never resolves, decodes or normalizes the URL. */
public final class BrowserUrl {
    public static final int MAX_LENGTH = 2048;
    public final String url;
    public final String host;

    private BrowserUrl(String url, String host) { this.url = url; this.host = host; }

    public static BrowserUrl parse(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Invalid browser URL length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\')
                throw new IllegalArgumentException("Browser URL must be printable ASCII");
            if (c == '%') {
                if (i + 2 >= value.length()) throw new IllegalArgumentException("Malformed escape");
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Malformed escape");
                int decoded = high * 16 + low;
                if (decoded <= 0x20 || decoded == 0x7f || decoded == 0x5c)
                    throw new IllegalArgumentException("Forbidden escaped control or backslash");
                i += 2;
            }
        }
        final URI parsed;
        try { parsed = new URI(value).parseServerAuthority(); }
        catch (URISyntaxException invalid) { throw new IllegalArgumentException("Malformed browser URL", invalid); }
        if (!parsed.isAbsolute() || parsed.isOpaque() || !"https".equals(parsed.getScheme())
                || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null)
            throw new IllegalArgumentException("Only HTTPS without user information or fragment is supported");
        String host = parsed.getHost();
        if (host == null || host.length() > 253 || !host.equals(host.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Noncanonical DNS host");
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) throw new IllegalArgumentException("A complete DNS host is required");
        for (String label : labels) {
            if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
                throw new IllegalArgumentException("Noncanonical DNS label");
        }
        String suffix = labels[labels.length - 1];
        // WHATWG IPv4 forms include octal, hexadecimal and shortened numeric addresses.
        if (suffix.matches("[0-9]+") || suffix.matches("0x[0-9a-f]+")
                || suffix.equals("localhost") || suffix.equals("local") || suffix.equals("internal"))
            throw new IllegalArgumentException("IP spellings and reserved local names are unsupported");
        String authority = parsed.getRawAuthority();
        if (!host.equals(authority) && !(host + ":443").equals(authority))
            throw new IllegalArgumentException("Only canonical host and default HTTPS port are supported");
        return new BrowserUrl(value, host);
    }
}

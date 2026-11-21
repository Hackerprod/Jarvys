package com.jarvys.factory.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Optional "standalone" authority: the app runs without the Jarvys host. Only the network capability is
 * allowed (no host broker), and every request is limited to a short list of declared host:port pairs.
 * Absence of an authority keeps the historical host-bound behaviour.
 */
public final class StandalonePolicy {
    public static final String AUTHORITY = "standalone";
    public static final int MAX_HOSTS = 16;
    private static final Pattern HOST_PORT = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?:[0-9]{1,5}");
    private StandalonePolicy() { }

    /** Returns the lower-case canonical host:port list, or throws IllegalArgumentException. */
    public static List<String> validateHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty() || hosts.size() > MAX_HOSTS) throw new IllegalArgumentException("Standalone apps need 1-" + MAX_HOSTS + " allowed hosts");
        List<String> result = new ArrayList<>();
        for (String value : hosts) {
            String host = value == null ? "" : value.toLowerCase(Locale.ROOT);
            if (!HOST_PORT.matcher(host).matches()) throw new IllegalArgumentException("Allowed hosts must look like host:port");
            int port = Integer.parseInt(host.substring(host.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Allowed host port is out of range");
            if (result.contains(host)) throw new IllegalArgumentException("Duplicate allowed host");
            result.add(host);
        }
        return Collections.unmodifiableList(result);
    }

    /** Standalone apps may only use capabilities that need no host, plus network. */
    public static void validateCapabilities(Iterable<String> requested) {
        for (String name : CapabilityCatalog.select(requested))
            if (!name.equals("network") && CapabilityCatalog.requiresHostBroker(Collections.singleton(name)))
                throw new IllegalArgumentException("Capability " + name + " requires the Jarvys host and is not allowed in standalone mode");
    }
}

package guard.smoke;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Smoke-only tripwire: no non-localhost name can ever reach platform DNS. */
public final class NoExternalDns extends InetAddressResolverProvider {
    public static final AtomicInteger DENIED_LOOKUPS = new AtomicInteger();
    public String name() { return "offline-guard-smoke-localhost-only"; }
    public InetAddressResolver get(Configuration configuration) {
        return new InetAddressResolver() {
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if ("localhost".equalsIgnoreCase(host)) return configuration.builtinResolver().lookupByName(host, policy);
                DENIED_LOOKUPS.incrementAndGet();
                throw new UnknownHostException("Smoke DNS tripwire blocked lookup");
            }
            public String lookupByAddress(byte[] bytes) throws UnknownHostException {
                throw new UnknownHostException("Smoke DNS tripwire blocked reverse lookup");
            }
        };
    }
}

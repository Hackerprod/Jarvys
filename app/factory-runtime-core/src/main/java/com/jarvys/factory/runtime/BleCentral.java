package com.jarvys.factory.runtime;

import java.util.*;

/** Page-owned BLE central. No policy UI, background service, raw Android object or address bridge. */
public final class BleCentral implements AutoCloseable {
    public interface Access extends AutoCloseable { void check(); void close(); }
    public interface Clock { long elapsedMs(); }
    public interface Platform {
        /** Creation has no Bluetooth effect. Callbacks must identify their exact Scan/Link. */
        Scan newScan(ScanEvents events);
        Link newLink(Object device, LinkEvents events);
    }
    public interface Scan {
        boolean start(List<UUID> serviceUuids);
        /** True means the local stop call completed, not RF silence or peer disconnection. */
        boolean stop();
    }
    public interface Link {
        boolean connect();
        boolean execute(Command command);
        boolean close();
        /** Local notification routing only; never silently issue extra peripheral writes. */
        boolean localNotifications(Object characteristic, boolean enabled);
    }
    public interface ScanEvents {
        void found(Scan source, String privateKey, Object device, String advertisedName, int rssi, byte[] advertisement);
        void failed(Scan source, int platformStatus);
    }
    public interface LinkEvents {
        void state(Link source, boolean connected, int platformStatus);
        void completed(Link source, long token, String kind, Object nativeAttribute, int platformStatus, byte[] value, List<Service> services);
        void notification(Link source, Object nativeCharacteristic, byte[] value);
        void invalidated(Link source);
        void mtu(Link source, long token, int platformStatus, int actualMtu);
    }
    public static final class Limits {
        public final int connections, devices, queuedOperations, bufferedEvents, pollEvents, services, attributes, subscriptions, valueBytes;
        public final long scanDurationMs, operationTimeoutMs;
        public Limits(int connections, int devices, int queuedOperations, int bufferedEvents, int pollEvents,
                      int services, int attributes, int subscriptions, int valueBytes, long scanDurationMs, long operationTimeoutMs) {
            this.connections = range(connections, 1, 16); this.devices = range(devices, 1, 1024);
            this.queuedOperations = range(queuedOperations, 1, 256); this.bufferedEvents = range(bufferedEvents, 1, 2048);
            this.pollEvents = range(pollEvents, 1, 256); this.services = range(services, 1, 256);
            this.attributes = range(attributes, 1, 4096); this.subscriptions = range(subscriptions, 1, 256); this.valueBytes = range(valueBytes, 1, MAX_VALUE_BYTES);
            if (pollEvents > bufferedEvents || scanDurationMs < 1 || scanDurationMs > 300000 || operationTimeoutMs < 1 || operationTimeoutMs > 300000)
                throw new IllegalArgumentException("Invalid Bluetooth resource declaration");
            this.scanDurationMs = scanDurationMs; this.operationTimeoutMs = operationTimeoutMs;
        }
        public static Limits defaults() { return new Limits(4, 128, 32, 128, 32, 64, 512, 16, 512, 30000, 30000); }
        private static int range(int n, int low, int high) { if (n < low || n > high) throw new IllegalArgumentException("Invalid Bluetooth resource declaration"); return n; }
    }
    /** Fixed transport/working-set guards, not developer permissions or Bluetooth hardware limits. */
    public static final int MAX_VALUE_BYTES = 512, MAX_ADVERTISEMENT_BYTES = 1650, MAX_EVENT_BYTES = 49152, MAX_POLL_BYTES = 65536;
    public static final int MAX_BUFFER_BYTES = 1024 * 1024, MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    public static final int PROCESS_CONNECTIONS = 32, PROCESS_SCANS = 4;
    private static int processLinks, processScans;
    private static synchronized boolean acquire(boolean scan) {
        if (scan) { if (processScans == PROCESS_SCANS) return false; processScans++; }
        else { if (processLinks == PROCESS_CONNECTIONS) return false; processLinks++; }
        return true;
    }
    private static synchronized void release(boolean scan) { if (scan) processScans--; else processLinks--; }

    /** Immutable bounded snapshots made by the platform after discovery. References stay native. */
    public static final class Attribute {
        public final UUID uuid; public final Object nativeObject; public final int properties;
        public final List<Attribute> descriptors;
        public Attribute(UUID uuid, Object nativeObject, int properties, List<Attribute> descriptors) {
            this.uuid = Objects.requireNonNull(uuid); this.nativeObject = Objects.requireNonNull(nativeObject); this.properties = properties;
            this.descriptors = Collections.unmodifiableList(new ArrayList<>(descriptors));
        }
    }
    public static final class Service {
        public final UUID uuid; public final int instanceId, type; public final List<Attribute> characteristics;
        public Service(UUID uuid, int instanceId, int type, List<Attribute> characteristics) {
            this.uuid = Objects.requireNonNull(uuid); this.instanceId = instanceId; this.type = type;
            this.characteristics = Collections.unmodifiableList(new ArrayList<>(characteristics));
        }
    }
    /** Native command token never comes from JavaScript. Android correlates type + native object. */
    public static final class Command {
        public final long token; public final String kind, writeType; public final Object attribute;
        public final int requestedMtu; private final byte[] value;
        Command(long token, String kind, Object attribute, byte[] value, String writeType) { this(token, kind, attribute, value, writeType, 0); }
        Command(long token, String kind, Object attribute, byte[] value, String writeType, int requestedMtu) {
            this.token = token; this.kind = kind; this.attribute = attribute; this.value = value == null ? null : value.clone(); this.writeType = writeType; this.requestedMtu = requestedMtu;
        }
        public byte[] value() { return value == null ? null : value.clone(); }
        void clear() { if (value != null) Arrays.fill(value, (byte) 0); }
    }
    private static final class Device {
        final String id = id(); final Object nativeObject;
        Device(Object nativeObject) { this.nativeObject = nativeObject; }
    }
    private static final class Ref {
        final String id = id(), kind; final Attribute attribute; final Ref characteristic;
        Ref(Attribute a, String kind, Ref characteristic) { this.attribute = a; this.kind = kind; this.characteristic = characteristic; }
    }
    private static final class EventLease {
        final Access access; final boolean closeOnRemoval;
        EventLease(Access access, boolean closeOnRemoval) { this.access = access; this.closeOnRemoval = closeOnRemoval; }
    }
    private abstract static class Owner {
        final String id = id(); final Access access; final Deque<Map<String, Object>> events = new ArrayDeque<>();
        final IdentityHashMap<Map<String,Object>, EventLease> eventLeases = new IdentityHashMap<>();
        long sequence, dropped; int eventBytes; boolean active = true, cleanupPending, accessClosed;
        Owner(Access access) { this.access = access; }
        void closeAccess() { if (!accessClosed) { accessClosed = true; safeClose(access); } }
    }
    private static final class ScanOwner extends Owner {
        Scan scan; long deadline; boolean slot;
        ScanOwner(Access access) { super(access); }
    }
    private static final class Connection extends Owner {
        Link link; boolean slot, connected, discovered; long deadline; int actualMtu = 23;
        final Map<String, Ref> refs = new LinkedHashMap<>(); final IdentityHashMap<Object, Ref> nativeRefs = new IdentityHashMap<>();
        final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
        final Deque<Operation> queue = new ArrayDeque<>(); Operation pending; List<Map<String, Object>> catalog;
        Connection(Access access) { super(access); }
    }
    private static final class Subscription {
        final String id = id(); final Ref characteristic, cccd; final Access access; final String mode;
        boolean active;
        Subscription(Ref characteristic, Ref cccd, Access access, String mode) { this.characteristic = characteristic; this.cccd = cccd; this.access = access; this.mode = mode; }
    }
    private static final class Operation {
        final String id = id(), kind; final Ref ref; final Access access; final Command command;
        final Subscription subscription; boolean started; long deadline; int catalogOffset, catalogLimit = 32;
        Operation(String kind, Ref ref, Access access, Command command, Subscription subscription) {
            this.kind = kind; this.ref = ref; this.access = access; this.command = command; this.subscription = subscription;
        }
    }
    public final class Poll {
        public final Map<String, Object> result; private final Owner owner; private final long through; private final List<EventLease> grants = new ArrayList<>(); private boolean committed;
        private Poll(Owner owner, List<Map<String, Object>> events, long through) {
            this.owner = owner; this.through = through;
            for (Map<String,Object> event : events) { EventLease grant = owner.eventLeases.get(event); if (grant != null) grants.add(grant); }
            result = immutable(map("handle", owner.id, "active", owner.active, "cleanupPending", owner.cleanupPending,
                    "droppedEvents", Long.toString(owner.dropped), "events", events, "foregroundOnly", true));
        }
        /** Recheck this exact snapshot immediately before sending; a buffered result may have been revoked/dropped. */
        public void check() throws FactoryException {
            synchronized (BleCentral.this) {
                checkPage(); BleCentral.check(owner.access);
                for (EventLease grant : grants) BleCentral.check(grant.access);
            }
        }
        /** Call only after the bridge accepted this exact bounded response. */
        public void commit() {
            synchronized (BleCentral.this) {
                if (committed) return; committed = true;
                while (!owner.events.isEmpty() && Long.parseLong((String) owner.events.peekFirst().get("sequence")) <= through)
                    removeFirstEvent(owner);
                if (!owner.active && !owner.cleanupPending && owner.events.isEmpty()) removeOwner(owner);
            }
        }
    }

    private final Platform platform; private final Clock clock; private final Limits limits; private final Access page;
    private final LinkedHashMap<String, Device> devicesByKey = new LinkedHashMap<>(); private final Map<String, Device> devicesById = new HashMap<>();
    private final Map<String, Connection> connections = new LinkedHashMap<>(); private ScanOwner scanner;
    private long token; private boolean closed;
    public BleCentral(Platform platform, Clock clock, Limits limits, Access page) {
        this.platform = Objects.requireNonNull(platform); this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits); this.page = Objects.requireNonNull(page);
    }
    public synchronized Map<String, Object> scan(List<UUID> serviceUuids, long durationMs, Access access) throws FactoryException {
        checkPage(); check(access); Objects.requireNonNull(serviceUuids);
        if (serviceUuids.size() > 16 || serviceUuids.contains(null) || new HashSet<>(serviceUuids).size() != serviceUuids.size() || durationMs < 1 || durationMs > limits.scanDurationMs)
            throw error("INVALID_REQUEST", "Invalid or undeclared scan bounds.");
        if (scanner != null) throw error("BUSY", "Drain or stop the previous scan first.");
        if (!acquire(true)) throw error("BUSY", "Process scan capacity is full.");
        devicesByKey.clear(); devicesById.clear();
        ScanOwner s = new ScanOwner(access); s.slot = true; s.deadline = deadline(durationMs); scanner = s;
        try {
            s.scan = Objects.requireNonNull(platform.newScan(new ScanEvents() {
                public void found(Scan source, String key, Object device, String name, int rssi, byte[] raw) { scanFound(s, source, key, device, name, rssi, raw); }
                public void failed(Scan source, int status) { scanFailed(s, source, status); }
            }));
            checkPage(); check(access);
            if (!s.scan.start(Collections.unmodifiableList(new ArrayList<>(serviceUuids)))) throw error("UNAVAILABLE", "Android did not start the scan.");
            checkPage(); check(access);
            return map("scanId", s.id, "durationMs", durationMs, "foregroundOnly", true, "active", s.active);
        } catch (Exception e) { endScan(s, false, "scan_start_failed", null); if (!s.cleanupPending) removeOwner(s); throw sanitize(e); }
    }
    public synchronized Map<String, Object> stopScan(String id) throws FactoryException {
        ScanOwner s = scanOwner(id); endScan(s, false, "scan_stopped", null);
        Map<String, Object> result = map("scanId", id, "stopped", !s.cleanupPending, "cleanupPending", s.cleanupPending);
        if (!s.cleanupPending) removeOwner(s);
        return result;
    }
    private synchronized void scanFound(ScanOwner s, Scan source, String key, Object nativeDevice, String name, int rssi, byte[] raw) {
        if (scanner != s || !s.active || source != s.scan) return;
        if (!authorized(s)) { endScan(s, false, "permission_ended", null); return; }
        if (clock.elapsedMs() >= s.deadline) { endScan(s, true, "scan_finished", null); return; }
        if (key == null || key.length() > 128 || nativeDevice == null || raw == null || raw.length > MAX_ADVERTISEMENT_BYTES || rssi < -127 || rssi > 127) return;
        Device d = devicesByKey.get(key);
        if (d == null) {
            if (devicesByKey.size() == limits.devices) { s.dropped = increment(s.dropped); return; }
            d = new Device(nativeDevice); devicesByKey.put(key, d); devicesById.put(d.id, d);
        }
        String boundedName = name == null ? "" : name.substring(0, Math.min(name.length(), 128));
        emit(s, map("type", "advertisement", "deviceId", d.id, "name", boundedName, "rssi", rssi, "advertisementBase64", base64(raw)));
    }
    private synchronized void scanFailed(ScanOwner s, Scan source, int status) {
        if (scanner == s && s.scan == source && s.active) endScan(s, authorized(s), "scan_failed", status);
    }
    public synchronized Map<String, Object> connect(String deviceId, Access access) throws FactoryException {
        checkPage(); check(access);
        Device d = devicesById.get(deviceId); if (d == null) throw error("INVALID_HANDLE", "Unknown or expired native device handle.");
        if (connections.size() >= limits.connections || !acquire(false)) throw error("BUSY", "Connection capacity is full; drain closed handles first.");
        Connection c = new Connection(access); c.slot = true; c.deadline = deadline(limits.operationTimeoutMs); connections.put(c.id, c);
        try {
            c.link = Objects.requireNonNull(platform.newLink(d.nativeObject, new LinkEvents() {
                public void state(Link source, boolean connected, int status) { linkState(c, source, connected, status); }
                public void completed(Link source, long token, String kind, Object attr, int status, byte[] value, List<Service> services) { complete(c, source, token, kind, attr, status, value, services); }
                public void notification(Link source, Object attr, byte[] value) { changed(c, source, attr, value); }
                public void invalidated(Link source) { serviceChanged(c, source); }
                public void mtu(Link source, long token, int status, int actualMtu) { mtuChanged(c, source, token, status, actualMtu); }
            }));
            checkPage(); check(access);
            if (!c.link.connect()) throw error("UNAVAILABLE", "Android did not start a GATT connection.");
            checkPage(); check(access);
            return map("connectionId", c.id, "state", c.connected ? "connected" : "connecting", "foregroundOnly", true);
        } catch (Exception e) { endConnection(c, false, "connection_start_failed", null); if (!c.cleanupPending) removeOwner(c); throw sanitize(e); }
    }
    private synchronized void linkState(Connection c, Link source, boolean connected, int status) {
        if (!owns(c, source)) return;
        if (!authorized(c)) { endConnection(c, false, "permission_ended", null); return; }
        if (status == 0 && connected) {
            if (c.connected) return;
            if (clock.elapsedMs() >= c.deadline) { endConnection(c, true, "connection_timeout", null); return; }
            c.connected = true; emit(c, map("type", "connected", "connectionId", c.id)); startNext(c);
        } else endConnection(c, true, "disconnected", status);
    }
    public synchronized Map<String, Object> services(String connectionId, Access access) throws FactoryException { return services(connectionId, 0, 32, access); }
    public synchronized Map<String, Object> services(String connectionId, int offset, int limit, Access access) throws FactoryException {
        Connection c = ready(connectionId); check(access);
        if (offset < 0 || limit < 1 || limit > 256) throw error("INVALID_REQUEST", "Invalid service catalog page.");
        if (c.discovered) {
            Map<String, Object> result = catalogPage(c, offset, limit); check(access); return result;
        }
        if (offset != 0) throw error("INVALID_REQUEST", "Initial discovery must start at catalog offset zero.");
        for (Operation op : c.queue) if ("services".equals(op.kind)) throw error("BUSY", "Service discovery is already queued.");
        if (c.pending != null && "services".equals(c.pending.kind)) throw error("BUSY", "Service discovery is already pending.");
        Operation op = operation("services", null, access, "discover", null, null, null); op.catalogOffset = offset; op.catalogLimit = limit;
        return enqueue(c, op);
    }
    private Map<String, Object> catalogPage(Connection c, int offset, int limit) throws FactoryException {
        if (offset > c.catalog.size()) throw error("INVALID_REQUEST", "Catalog offset is past the end.");
        List<Map<String,Object>> entries = new ArrayList<>(); int bytes = 2048, next = offset;
        while (next < c.catalog.size() && entries.size() < limit) {
            Map<String,Object> entry = c.catalog.get(next); int size = estimate(entry);
            if (bytes + size > MAX_EVENT_BYTES - 2048) break;
            entries.add(entry); bytes += size; next++;
        }
        return map("connectionId", c.id, "entries", Collections.unmodifiableList(entries), "offset", offset,
                "nextOffset", next == c.catalog.size() ? null : next, "totalEntries", c.catalog.size(), "cached", true);
    }
    public synchronized Map<String, Object> read(String connectionId, String attributeId, Access access) throws FactoryException {
        Connection c = ready(connectionId); Ref r = ref(c, attributeId); check(access);
        if ("characteristic".equals(r.kind) && (r.attribute.properties & 2) == 0) throw error("UNSUPPORTED", "Characteristic is not readable.");
        return enqueue(c, operation("read", r, access, "characteristic".equals(r.kind) ? "readCharacteristic" : "readDescriptor", null, null, null));
    }
    public synchronized Map<String, Object> write(String connectionId, String attributeId, byte[] bytes, String writeType, Access access) throws FactoryException {
        Connection c = ready(connectionId); Ref r = ref(c, attributeId); check(access);
        if (bytes == null || bytes.length > limits.valueBytes || !("response".equals(writeType) || "noResponse".equals(writeType))) throw error("INVALID_REQUEST", "Invalid bounded GATT write.");
        if (isCccd(r)) throw error("UNSUPPORTED", "Use subscribe or unsubscribe for client configuration descriptors.");
        if ("descriptor".equals(r.kind) && !"response".equals(writeType)) throw error("UNSUPPORTED", "Descriptor writes require a response.");
        if ("characteristic".equals(r.kind)) {
            int flag = "response".equals(writeType) ? 8 : 4;
            if ((r.attribute.properties & flag) == 0) throw error("UNSUPPORTED", "Characteristic does not support this write type.");
            if ("noResponse".equals(writeType) && bytes.length > Math.min(c.actualMtu - 3, MAX_VALUE_BYTES)) throw error("TOO_LARGE", "No-response write exceeds the observed negotiated MTU payload; request MTU first or use explicit application framing.");
        }
        return enqueue(c, operation("write", r, access, "characteristic".equals(r.kind) ? "writeCharacteristic" : "writeDescriptor", bytes, writeType, null));
    }
    public synchronized Map<String, Object> requestMtu(String connectionId, int requestedMtu, Access access) throws FactoryException {
        Connection c = ready(connectionId); check(access);
        if (requestedMtu < 23 || requestedMtu > 517) throw error("INVALID_REQUEST", "MTU must be in the ATT range 23 through 517.");
        if (token == Long.MAX_VALUE) throw error("UNAVAILABLE", "Native operation sequence exhausted.");
        return enqueue(c, new Operation("requestMtu", null, access, new Command(++token, "requestMtu", null, null, null, requestedMtu), null));
    }
    private synchronized void mtuChanged(Connection c, Link source, long callbackToken, int status, int actualMtu) {
        if (!owns(c, source)) return;
        if (!authorized(c)) { endConnection(c, false, "permission_ended", null); return; }
        if (status == 0) {
            if (actualMtu < 23 || actualMtu > 517) { endConnection(c, true, "invalid_negotiated_mtu", status); return; }
            c.actualMtu = actualMtu;
        }
        Operation op = c.pending;
        if (op == null || !"requestMtu".equals(op.kind) || op.command.token != callbackToken) {
            if (status == 0) emit(c, map("type", "mtu_changed", "actualMtu", actualMtu, "noResponsePayloadBytes", Math.min(actualMtu - 3, MAX_VALUE_BYTES)));
            return;
        }
        if (!allowed(op.access)) { endConnection(c, false, "permission_ended", null); return; }
        if (clock.elapsedMs() >= op.deadline) { endConnection(c, true, "operation_timeout", null); return; }
        finishOperation(c, op, status == 0 ? "completed" : "gatt_error", status, null, null);
    }
    public synchronized Map<String, Object> subscribe(String connectionId, String characteristicId, String mode, Access access) throws FactoryException {
        Connection c = ready(connectionId); Ref r = ref(c, characteristicId); check(access);
        if (!"characteristic".equals(r.kind) || !("notify".equals(mode) || "indicate".equals(mode))) throw error("INVALID_REQUEST", "A characteristic and explicit notification mode are required.");
        if ((r.attribute.properties & ("notify".equals(mode) ? 16 : 32)) == 0) throw error("UNSUPPORTED", "Characteristic does not support the selected notification mode.");
        if (c.subscriptions.size() >= limits.subscriptions) throw error("BUSY", "Subscription capacity is full.");
        for (Subscription existing : c.subscriptions.values()) if (existing.characteristic == r) throw error("BUSY", "Characteristic already has a subscription or pending change.");
        Ref descriptor = null;
        for (Ref candidate : c.refs.values()) if (candidate.characteristic == r && isCccd(candidate)) {
            if (descriptor != null) throw error("UNSUPPORTED", "Ambiguous client configuration descriptor."); descriptor = candidate;
        }
        if (descriptor == null) throw error("UNSUPPORTED", "Characteristic has no client configuration descriptor.");
        Subscription s = new Subscription(r, descriptor, access, mode); c.subscriptions.put(s.id, s);
        try {
            Map<String, Object> result = enqueue(c, operation("subscribe", descriptor, access, "writeDescriptor", new byte[]{(byte)("notify".equals(mode) ? 1 : 2), 0}, "response", s));
            result.put("subscriptionId", s.id); return result;
        } catch (FactoryException e) { c.subscriptions.remove(s.id); throw e; }
    }
    public synchronized Map<String, Object> unsubscribe(String subscriptionId) throws FactoryException {
        for (Connection c : connections.values()) {
            Subscription s = c.subscriptions.get(subscriptionId); if (s == null) continue;
            ready(c.id); check(s.access);
            if (!s.active) throw error("BUSY", "Subscription is still pending.");
            for (Operation op : c.queue) if (op.subscription == s) throw error("BUSY", "Subscription teardown is already queued.");
            if (c.pending != null && c.pending.subscription == s) throw error("BUSY", "Subscription teardown is already pending.");
            return enqueue(c, operation("unsubscribe", s.cccd, s.access, "writeDescriptor", new byte[]{0, 0}, "response", s));
        }
        throw error("INVALID_HANDLE", "Unknown native subscription handle.");
    }
    private Operation operation(String kind, Ref r, Access access, String nativeKind, byte[] value, String writeType, Subscription sub) throws FactoryException {
        if (token == Long.MAX_VALUE) throw error("UNAVAILABLE", "Native operation sequence exhausted.");
        return new Operation(kind, r, access, new Command(++token, nativeKind, r == null ? null : r.attribute.nativeObject, value, writeType), sub);
    }
    private Map<String, Object> enqueue(Connection c, Operation op) throws FactoryException {
        if (c.queue.size() + (c.pending == null ? 0 : 1) >= limits.queuedOperations) { op.command.clear(); throw error("BUSY", "GATT queue capacity is full."); }
        c.queue.addLast(op); startNext(c);
        return map("connectionId", c.id, "operationId", op.id, "accepted", true);
    }
    private void startNext(Connection c) {
        if (!c.active || !c.connected || c.pending != null) return;
        Operation op = c.queue.pollFirst(); if (op == null) return;
        c.pending = op;
        if (!authorized(c) || !allowed(op.access)) { endConnection(c, false, "permission_ended", null); return; }
        try {
            if ("write".equals(op.kind) && "noResponse".equals(op.command.writeType) && op.command.value.length > Math.min(c.actualMtu - 3, MAX_VALUE_BYTES)) { finishOperation(c, op, "mtu_payload_exceeded", -1, null, null); return; }
            if ("subscribe".equals(op.kind) && !c.link.localNotifications(op.subscription.characteristic.attribute.nativeObject, true)) {
                // A half-started subscription is never represented as active.
                endConnection(c, true, "notification_routing_failed", null); return;
            }
            op.deadline = deadline(limits.operationTimeoutMs); op.started = true;
            if (!c.link.execute(op.command)) {
                // A false platform return means not admitted. There is no automatic retry.
                op.started = false;
                if (op.subscription != null) { endConnection(c, true, "subscription_not_admitted", null); return; }
                finishOperation(c, op, "not_started", -1, null, null); return;
            }
            if (c.pending == op && (!authorized(c) || !allowed(op.access))) endConnection(c, false, "permission_ended", null);
        } catch (RuntimeException failure) { endConnection(c, true, "platform_error", null); }
    }
    private synchronized void complete(Connection c, Link source, long callbackToken, String kind, Object attribute, int status, byte[] value, List<Service> discovered) {
        if (!owns(c, source)) return;
        Operation op = c.pending;
        if (op == null || callbackToken != op.command.token || !op.command.kind.equals(kind) || op.command.attribute != attribute) return;
        if (!authorized(c) || !allowed(op.access)) { endConnection(c, false, "permission_ended", null); return; }
        if (clock.elapsedMs() >= op.deadline) { endConnection(c, true, "operation_timeout", null); return; }
        if (value != null && value.length > limits.valueBytes) { endConnection(c, true, "value_too_large", null); return; }
        if (status != 0 && op.subscription != null) { endConnection(c, true, "subscription_failed", status); return; }
        if (status == 0 && "services".equals(op.kind)) {
            try { buildCatalog(c, discovered); }
            catch (FactoryException invalid) { endConnection(c, true, "services_too_large_or_invalid", null); return; }
        }
        if (status == 0 && "read".equals(op.kind) && value == null) { endConnection(c, true, "invalid_read_result", null); return; }
        if (status == 0 && "subscribe".equals(op.kind)) op.subscription.active = true;
        if (status == 0 && "unsubscribe".equals(op.kind)) {
            try { if (!c.link.localNotifications(op.subscription.characteristic.attribute.nativeObject, false)) { endConnection(c, true, "notification_routing_failed", null); return; } }
            catch (RuntimeException failed) { endConnection(c, true, "notification_routing_failed", null); return; }
            op.subscription.active = false; c.subscriptions.remove(op.subscription.id); purgeSubscription(c, op.subscription.id);
        }
        finishOperation(c, op, status == 0 ? "completed" : "gatt_error", status, value, c.catalog);
    }
    private void finishOperation(Connection c, Operation op, String outcome, int status, byte[] value, List<Map<String, Object>> catalog) {
        if (c.pending != op) return;
        Map<String, Object> event = operationEvent(op, outcome, status);
        if ("requestMtu".equals(op.kind)) { event.put("requestedMtu", op.command.requestedMtu); event.put("actualMtu", c.actualMtu); event.put("noResponsePayloadBytes", Math.min(c.actualMtu - 3, MAX_VALUE_BYTES)); }
        if (value != null && "completed".equals(outcome)) event.put("valueBase64", base64(value));
        if ("services".equals(op.kind) && "completed".equals(outcome)) {
            try { Map<String,Object> page = catalogPage(c, op.catalogOffset, op.catalogLimit); page.put("cached", false); event.put("catalog", page); }
            catch (FactoryException invalid) { endConnection(c, true, "invalid_catalog_page", null); return; }
        }
        emit(c, event, op.access, !"subscribe".equals(op.kind)); c.pending = null; op.command.clear();
        startNext(c);
    }
    private Map<String, Object> operationEvent(Operation op, String outcome, Integer status) {
        Map<String, Object> event = map("type", "operation", "operationId", op.id, "operation", op.kind, "outcome", outcome);
        if (status != null) event.put("gattStatus", status);
        if (op.ref != null) event.put("attributeId", op.ref.id);
        if (op.subscription != null) event.put("subscriptionId", op.subscription.id);
        if ("write".equals(op.kind)) event.put("writeOutcome", !op.started ? "not_sent" : !"completed".equals(outcome) ? "unknown" : "noResponse".equals(op.command.writeType) ? "sent_to_stack" : "att_acknowledged");
        return event;
    }
    private synchronized void changed(Connection c, Link source, Object nativeAttribute, byte[] value) {
        if (!owns(c, source) || value == null) return;
        if (!authorized(c)) { endConnection(c, false, "permission_ended", null); return; }
        Ref r = c.nativeRefs.get(nativeAttribute); if (r == null) return;
        for (Subscription s : c.subscriptions.values()) if (s.active && s.characteristic == r) {
            if (!allowed(s.access)) { endConnection(c, false, "subscription_permission_ended", null); return; }
            if (value.length > limits.valueBytes) {
                c.dropped = increment(c.dropped);
                emit(c, map("type", "notification_dropped", "subscriptionId", s.id, "reason", "value_exceeds_declared_budget", "receivedBytes", value.length)); return;
            }
            emit(c, map("type", "notification", "subscriptionId", s.id, "attributeId", r.id, "mode", s.mode, "valueBase64", base64(value)), s.access, false); return;
        }
    }
    private synchronized void serviceChanged(Connection c, Link source) { if (owns(c, source)) endConnection(c, terminalAllowed(c), "services_changed_reconnect_required", null); }
    private void buildCatalog(Connection c, List<Service> services) throws FactoryException {
        if (services == null || services.size() > limits.services) throw error("TOO_LARGE", "Invalid or oversized service catalog.");
        List<Map<String, Object>> output = new ArrayList<>();
        for (Service s : services) {
            if (s == null || s.characteristics.size() > limits.attributes) throw error("TOO_LARGE", "Invalid service catalog.");
            String serviceId = id();
            output.add(map("kind", "service", "serviceId", serviceId, "uuid", s.uuid.toString(), "instanceId", s.instanceId, "type", s.type));
            for (Attribute a : s.characteristics) {
                Ref r = addRef(c, a, "characteristic", null);
                output.add(map("kind", "characteristic", "attributeId", r.id, "serviceId", serviceId, "uuid", a.uuid.toString(), "properties", a.properties));
                if (a.descriptors.size() > limits.attributes) throw error("TOO_LARGE", "Invalid service catalog.");
                for (Attribute d : a.descriptors) {
                    Ref dr = addRef(c, d, "descriptor", r);
                    output.add(map("kind", "descriptor", "attributeId", dr.id, "characteristicId", r.id, "uuid", d.uuid.toString()));
                }
            }
        }
        if (estimate(output) > MAX_CATALOG_BYTES) throw error("TOO_LARGE", "Service catalog exceeds native working-set capacity.");
        c.catalog = immutable(output); c.discovered = true;
    }
    private Ref addRef(Connection c, Attribute a, String kind, Ref parent) throws FactoryException {
        if (a == null || c.refs.size() >= limits.attributes || c.nativeRefs.containsKey(a.nativeObject) || (!"characteristic".equals(kind) && !a.descriptors.isEmpty())) throw error("TOO_LARGE", "Invalid or oversized attribute catalog.");
        Ref r = new Ref(a, kind, parent); c.refs.put(r.id, r); c.nativeRefs.put(a.nativeObject, r); return r;
    }
    public synchronized Map<String, Object> cancel(String operationId) throws FactoryException {
        checkPage();
        for (Connection c : connections.values()) {
            if (c.pending != null && c.pending.id.equals(operationId)) {
                check(c.access); boolean uncertain = c.pending.started && "write".equals(c.pending.kind);
                endConnection(c, true, "cancelled_in_flight", null);
                return map("operationId", operationId, "cancelled", true, "connectionClosed", !c.cleanupPending, "cleanupPending", c.cleanupPending, "writeMayHaveReachedDevice", uncertain);
            }
            Iterator<Operation> it = c.queue.iterator();
            while (it.hasNext()) {
                Operation op = it.next(); if (!op.id.equals(operationId)) continue;
                check(c.access); it.remove(); emit(c, operationEvent(op, "cancelled_before_start", null), op.access, !"unsubscribe".equals(op.kind)); op.command.clear();
                if ("subscribe".equals(op.kind)) c.subscriptions.remove(op.subscription.id);
                return map("operationId", operationId, "cancelled", true, "connectionClosed", false, "writeMayHaveReachedDevice", false);
            }
        }
        throw error("INVALID_HANDLE", "Unknown or completed operation handle.");
    }
    public synchronized Map<String, Object> disconnect(String connectionId) throws FactoryException {
        Connection c = connection(connectionId); boolean uncertain = c.pending != null && c.pending.started && "write".equals(c.pending.kind);
        endConnection(c, false, "disconnected_by_app", null);
        Map<String, Object> result = map("connectionId", c.id, "localResourcesReleased", !c.cleanupPending, "cleanupPending", c.cleanupPending, "writeMayHaveReachedDevice", uncertain);
        if (!c.cleanupPending) removeOwner(c);
        return result;
    }
    public synchronized Poll poll(String handle) throws FactoryException {
        checkPage(); tick(); Owner owner = scanner != null && scanner.id.equals(handle) ? scanner : connection(handle);
        check(owner.access);
        List<Map<String, Object>> events = new ArrayList<>(); int bytes = 1024; long through = 0;
        for (Map<String, Object> event : owner.events) {
            int next = estimate(event); if (events.size() >= limits.pollEvents || bytes + next > MAX_POLL_BYTES) break;
            EventLease grant = owner.eventLeases.get(event); if (grant != null) check(grant.access);
            events.add(event); bytes += next; through = Long.parseLong((String) event.get("sequence"));
        }
        checkPage(); check(owner.access);
        return new Poll(owner, Collections.unmodifiableList(events), through);
    }
    /** Runtime calls at least every 100 ms while any resource exists, plus immediately on lifecycle/revocation. */
    public synchronized void tick() {
        if (closed || !allowed(page)) { cancelAll(); return; }
        if (scanner != null) {
            ScanOwner s = scanner;
            if (s.cleanupPending) cleanupScan(s);
            if (!s.active && !allowed(s.access)) { clearEvents(s); s.closeAccess(); }
            if (s.active && !authorized(s)) endScan(s, false, "permission_ended", null);
            else if (s.active && clock.elapsedMs() >= s.deadline) endScan(s, true, "scan_finished", null);
            if (!s.active && !s.cleanupPending && s.events.isEmpty()) removeOwner(s);
        }
        for (Connection c : new ArrayList<>(connections.values())) {
            if (c.cleanupPending) cleanupLink(c);
            if (!terminalAllowed(c)) { if (c.active) endConnection(c, false, "permission_ended", null); else { clearEvents(c); c.closeAccess(); } }
            if (!c.active) { if (!c.cleanupPending && c.events.isEmpty()) removeOwner(c); continue; }
            if (!authorized(c)) { endConnection(c, false, "permission_ended", null); continue; }
            boolean denied = false;
            for (Subscription s : c.subscriptions.values()) if (!allowed(s.access)) { denied = true; break; }
            if (denied) { endConnection(c, false, "subscription_permission_ended", null); continue; }
            if (!c.connected && clock.elapsedMs() >= c.deadline) { endConnection(c, true, "connection_timeout", null); continue; }
            if (c.pending != null) {
                boolean permitted = allowed(c.pending.access);
                if (!permitted || clock.elapsedMs() >= c.pending.deadline) endConnection(c, permitted, "operation_timeout_or_permission_ended", null);
            }
        }
    }
    public synchronized void cancelAll() {
        devicesByKey.clear(); devicesById.clear();
        if (scanner != null) { ScanOwner s = scanner; endScan(s, false, "page_ended", null); if (!s.cleanupPending) removeOwner(s); }
        for (Connection c : new ArrayList<>(connections.values())) { endConnection(c, false, "page_ended", null); if (!c.cleanupPending) removeOwner(c); }
    }
    @Override public synchronized void close() { closed = true; cancelAll(); }
    public synchronized boolean hasResources() { return scanner != null || !connections.isEmpty(); }
    public synchronized boolean hasUnconfirmedCleanup() {
        if (scanner != null && scanner.cleanupPending) return true;
        for (Connection c : connections.values()) if (c.cleanupPending) return true; return false;
    }
    private void endScan(ScanOwner s, boolean report, String reason, Integer status) {
        if (s.active) {
            s.active = false;
            if (!report) clearEvents(s); else emit(s, map("type", reason, "platformStatus", status == null ? 0 : status));
            if (!report) s.closeAccess();
        } else if (!report) { clearEvents(s); s.closeAccess(); }
        cleanupScan(s);
    }
    private void cleanupScan(ScanOwner s) {
        boolean done = s.scan == null;
        if (!done) try { done = s.scan.stop(); } catch (RuntimeException ignored) { }
        s.cleanupPending = !done;
        if (done && s.slot) { s.slot = false; release(true); }
    }
    private void endConnection(Connection c, boolean report, String reason, Integer status) {
        if (c.active) {
            report = report && terminalAllowed(c);
            c.active = false; c.connected = false;
            if (!report) clearEvents(c);
            if (c.pending != null) { retire(c, c.pending, report, reason); c.pending = null; }
            while (!c.queue.isEmpty()) retire(c, c.queue.removeFirst(), report, "cancelled_before_start");
            for (Subscription s : c.subscriptions.values()) {
                s.active = false; purgeSubscription(c, s.id);
                Iterator<EventLease> it = c.eventLeases.values().iterator();
                while (it.hasNext()) if (it.next().access == s.access) it.remove();
                safeClose(s.access);
            }
            c.subscriptions.clear(); c.refs.clear(); c.nativeRefs.clear(); c.catalog = null; if (!report) c.closeAccess();
            if (report) emit(c, map("type", "disconnected", "reason", reason, "platformStatus", status == null ? 0 : status));
        } else if (!report) { clearEvents(c); c.closeAccess(); }
        cleanupLink(c);
    }
    private void retire(Connection c, Operation op, boolean report, String reason) {
        if (report) emit(c, operationEvent(op, reason, null), op.subscription == null ? op.access : null, op.subscription == null); op.command.clear();
        if (!report && op.subscription == null) safeClose(op.access);
        else if ("subscribe".equals(op.kind) && !c.subscriptions.containsKey(op.subscription.id)) safeClose(op.access);
    }
    private void cleanupLink(Connection c) {
        boolean done = c.link == null;
        if (!done) try { done = c.link.close(); } catch (RuntimeException ignored) { }
        c.cleanupPending = !done;
        if (done && c.slot) { c.slot = false; release(false); }
    }
    private void emit(Owner owner, Map<String,Object> event) { emit(owner, event, null, false); }
    private void emit(Owner owner, Map<String, Object> event, Access grant, boolean closeOnRemoval) {
        if (owner.sequence == Long.MAX_VALUE) { owner.dropped = increment(owner.dropped); if (grant != null && closeOnRemoval) safeClose(grant); return; }
        event.put("sequence", Long.toString(++owner.sequence)); event.put("elapsedMs", Long.toString(clock.elapsedMs()));
        Map<String, Object> frozen = immutable(event); int bytes = estimate(frozen);
        if (bytes > MAX_EVENT_BYTES) { owner.dropped = increment(owner.dropped); if (grant != null && closeOnRemoval) safeClose(grant); return; }
        while (!owner.events.isEmpty() && (owner.events.size() >= limits.bufferedEvents || owner.eventBytes + bytes > MAX_BUFFER_BYTES)) {
            removeFirstEvent(owner); owner.dropped = increment(owner.dropped);
        }
        owner.events.addLast(frozen); owner.eventBytes += bytes;
        if (grant != null) owner.eventLeases.put(frozen, new EventLease(grant, closeOnRemoval));
    }
    private void purgeSubscription(Connection c, String id) {
        Iterator<Map<String, Object>> it = c.events.iterator();
        while (it.hasNext()) { Map<String, Object> e = it.next(); if ("notification".equals(e.get("type")) && id.equals(e.get("subscriptionId"))) { c.eventBytes -= estimate(e); c.eventLeases.remove(e); c.dropped = increment(c.dropped); it.remove(); } }
    }
    private void removeOwner(Owner owner) { if (owner == scanner) scanner = null; else connections.remove(owner.id, owner); clearEvents(owner); owner.closeAccess(); }
    private static void removeFirstEvent(Owner owner) {
        Map<String,Object> event = owner.events.removeFirst(); owner.eventBytes -= estimate(event);
        EventLease grant = owner.eventLeases.remove(event); if (grant != null && grant.closeOnRemoval) safeClose(grant.access);
    }
    private static void clearEvents(Owner owner) { while (!owner.events.isEmpty()) removeFirstEvent(owner); owner.eventBytes = 0; }
    private boolean terminalAllowed(Connection c) {
        if (!authorized(c)) return false;
        for (Subscription sub : c.subscriptions.values()) if (!allowed(sub.access)) return false;
        for (EventLease grant : c.eventLeases.values()) if (!allowed(grant.access)) return false;
        return c.pending == null || allowed(c.pending.access);
    }
    private boolean owns(Connection c, Link source) { return connections.get(c.id) == c && c.active && c.link == source; }
    private boolean authorized(Owner owner) { return !closed && allowed(page) && allowed(owner.access); }
    private void checkPage() throws FactoryException { if (closed) throw error("CANCELLED", "Native BLE owner is closed."); check(page); }
    private Connection ready(String id) throws FactoryException {
        checkPage(); Connection c = connection(id); check(c.access);
        if (!c.active || !c.connected) throw error("UNAVAILABLE", "GATT connection is not ready."); return c;
    }
    private Connection connection(String id) throws FactoryException { Connection c = connections.get(id); if (c == null) throw error("INVALID_HANDLE", "Unknown native connection handle."); return c; }
    private ScanOwner scanOwner(String id) throws FactoryException { if (scanner == null || !scanner.id.equals(id)) throw error("INVALID_HANDLE", "Unknown native scan handle."); return scanner; }
    private Ref ref(Connection c, String id) throws FactoryException { Ref r = c.refs.get(id); if (r == null) throw error("INVALID_HANDLE", "Discover services before using their native attribute handles."); return r; }
    private static boolean isCccd(Ref r) { return "descriptor".equals(r.kind) && UUID.fromString("00002902-0000-1000-8000-00805f9b34fb").equals(r.attribute.uuid); }
    private long deadline(long duration) { long now = clock.elapsedMs(); return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration; }
    private static String id() { return UUID.randomUUID().toString(); }
    private static void check(Access a) throws FactoryException { try { Objects.requireNonNull(a).check(); } catch (RuntimeException e) { throw error("PERMISSION_DENIED", "Native BLE page or operation authority ended."); } }
    private static boolean allowed(Access a) { try { a.check(); return true; } catch (RuntimeException denied) { return false; } }
    private static void safeClose(Access a) { try { a.close(); } catch (RuntimeException ignored) { } }
    private static long increment(long x) { return x == Long.MAX_VALUE ? x : x + 1; }
    static FactoryException error(String code, String text) { return new FactoryException(code, text); }
    private static FactoryException sanitize(Exception e) { if (e instanceof FactoryException) return (FactoryException)e; return error(e instanceof SecurityException ? "PERMISSION_DENIED" : "UNAVAILABLE", "BLE platform operation could not start."); }
    static Map<String, Object> map(Object... pairs) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) m.put((String)pairs[i], pairs[i + 1]); return m; }
    /** Conservative upper bound for JSON bytes (control characters and surrogate units allow six-byte escapes). */
    static int estimate(Object value) {
        long n = 8;
        if (value instanceof String) {
            String s = (String)value;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                n += ch < 32 || Character.isSurrogate(ch) ? 6 : ch == '\\' || ch == '"' || ch == '/' ? 2 : ch < 128 ? 1 : ch < 2048 ? 2 : 3;
            }
        }
        else if (value instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>)value).entrySet()) n += estimate(e.getKey()) + estimate(e.getValue()) + 2;
        else if (value instanceof List) for (Object v : (List<?>)value) n += estimate(v) + 1;
        else n += 32;
        return (int)Math.min(Integer.MAX_VALUE, n);
    }
    @SuppressWarnings("unchecked") private static <T> T immutable(T value) {
        if (value instanceof Map) { Map<String,Object> out = new LinkedHashMap<>(); for (Map.Entry<String,Object> e : ((Map<String,Object>)value).entrySet()) out.put(e.getKey(), immutable(e.getValue())); return (T)Collections.unmodifiableMap(out); }
        if (value instanceof List) { List<Object> out = new ArrayList<>(); for (Object item : (List<?>)value) out.add(immutable(item)); return (T)Collections.unmodifiableList(out); }
        return value;
    }
    static String base64(byte[] bytes) { return BleBytes.encode(bytes); }
}

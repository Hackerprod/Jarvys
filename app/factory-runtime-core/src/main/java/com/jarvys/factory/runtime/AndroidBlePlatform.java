package com.jarvys.factory.runtime;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.ParcelUuid;
import java.util.*;

/** Real framework central, API 24+. No sockets, JNI, reflection, pairing, enabling or GATT server. */
@SuppressWarnings("deprecation")
public final class AndroidBlePlatform implements BleCentral.Platform {
    private final Context context; private final BlePermissions.ScanPrivacy privacy; private final BleCentral.Limits limits;
    public AndroidBlePlatform(Context context, BlePermissions.ScanPrivacy privacy, BleCentral.Limits limits) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.privacy = Objects.requireNonNull(privacy); this.limits = Objects.requireNonNull(limits);
    }
    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }
    public List<String> requiredPermissions(boolean scan, boolean connect) {
        return BlePermissions.required(Build.VERSION.SDK_INT, context.getApplicationInfo().targetSdkVersion, scan, connect, privacy);
    }
    public Map<String, Object> status() {
        Map<String,Object> result = BleCentral.map("supported", context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                "deviceApi", Build.VERSION.SDK_INT, "targetApi", context.getApplicationInfo().targetSdkVersion,
                "scanPrivacy", privacy == BlePermissions.ScanPrivacy.FULL ? "full" : "neverForLocation",
                "beaconsMayBeFiltered", privacy == BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION,
                "foregroundOnly", true, "centralOnly", true, "automaticEnable", false, "explicitBondApi", false, "osAuthenticationPairingMayOccur", true,
                "mtuNegotiationBehavior", Build.VERSION.SDK_INT >= 34 ? "android_first_request_517_then_ignores_further_requests" : "requested_mtu_is_not_guaranteed");
        Set<String> declared = declared();
        List<Map<String,Object>> permissions = new ArrayList<>();
        for (String name : requiredPermissions(true, true)) permissions.add(BleCentral.map("name", name, "declared", declared.contains(name),
                "granted", context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED));
        result.put("permissions", permissions);
        try { BluetoothAdapter adapter = adapter(); result.put("adapterPresent", adapter != null); result.put("enabled", adapter != null && adapter.isEnabled()); }
        catch (SecurityException denied) { result.put("enabled", "unknown_permission_required"); }
        try {
            LocationManager lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = lm != null && (Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled() : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            result.put("locationServicesEnabled", enabled);
        } catch (RuntimeException unavailable) { result.put("locationServicesEnabled", "unknown"); }
        result.put("locationServicesMayBeRequiredForScan", Build.VERSION.SDK_INT <= 30 || context.getApplicationInfo().targetSdkVersion <= 30 || privacy == BlePermissions.ScanPrivacy.FULL);
        return result;
    }
    private Set<String> declared() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            return info.requestedPermissions == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(info.requestedPermissions));
        } catch (PackageManager.NameNotFoundException unavailable) { return Collections.emptySet(); }
    }
    /** Call after one unified policy decision, with the shared UI slot and trusted handoff held.
     * This only launches the genuine OS request. The runtime must correlate/recheck the result. */
    public Map<String,Object> requestPermissions(Activity activity, boolean scan, boolean connect, int requestCode, BleCentral.Access access) throws FactoryException {
        if (!scan && !connect) throw BleCentral.error("INVALID_REQUEST", "Select scan or connect permissions.");
        List<String> missing = missingDangerousPermissions(scan, connect); access.check();
        if (missing.isEmpty()) return BleCentral.map("permissionRequestStatus", "already_granted", "requestedPermissions", missing);
        activity.requestPermissions(missing.toArray(new String[0]), requestCode);
        return BleCentral.map("permissionRequestStatus", "permission_requested", "requestedPermissions", missing);
    }
    public List<String> missingDangerousPermissions(boolean scan, boolean connect) throws FactoryException {
        Set<String> declared = declared();
        for (String name : requiredPermissions(scan, connect)) if (!declared.contains(name))
            throw BleCentral.error("PERMISSION_NOT_DECLARED", "The installed artifact does not declare all required Bluetooth permissions.");
        List<String> missing = new ArrayList<>();
        for (String name : BlePermissions.dangerous(Build.VERSION.SDK_INT, context.getApplicationInfo().targetSdkVersion, scan, connect, privacy))
            if (context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) missing.add(name);
        // Android 12+ fine-location dialogs require coarse and fine in the same request, even if coarse was already granted.
        if (Build.VERSION.SDK_INT >= 31 && missing.contains(BlePermissions.FINE) && !missing.contains(BlePermissions.COARSE)) missing.add(0, BlePermissions.COARSE);
        return Collections.unmodifiableList(missing);
    }
    public void checkPermissions(boolean scan, boolean connect) { require(scan, connect); }
    private void require(boolean scan, boolean connect) {
        for (String name : requiredPermissions(scan, connect)) if (context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Required Android Bluetooth permission is absent");
    }
    @Override public BleCentral.Scan newScan(BleCentral.ScanEvents events) { return new NativeScan(events); }
    @Override public BleCentral.Link newLink(Object device, BleCentral.LinkEvents events) {
        if (!(device instanceof BluetoothDevice)) throw new IllegalArgumentException("Invalid native Bluetooth device");
        return new NativeLink((BluetoothDevice)device, events);
    }
    private final class NativeScan implements BleCentral.Scan {
        final BleCentral.ScanEvents events; BluetoothLeScanner scanner; volatile boolean ended; ScanCallback callback;
        NativeScan(BleCentral.ScanEvents events) { this.events = events; }
        @Override public boolean start(List<UUID> uuids) {
            require(true, false); BluetoothAdapter adapter = adapter(); if (adapter == null) return false;
            // getBluetoothLeScanner returns null when Bluetooth is disabled. Do not turn it on.
            scanner = adapter.getBluetoothLeScanner(); if (scanner == null) return false;
            List<ScanFilter> filters = new ArrayList<>();
            for (UUID uuid : uuids) filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(uuid)).build());
            callback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) { found(result); }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    if (results == null) return;
                    for (int i = 0; i < Math.min(results.size(), limits.devices); i++) found(results.get(i));
                }
                @Override public void onScanFailed(int errorCode) { if (!ended) events.failed(NativeScan.this, errorCode); }
            };
            scanner.startScan(filters, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), callback);
            return true;
        }
        private void found(ScanResult result) {
            if (ended || result == null) return;
            try {
                require(true, false);
                BluetoothDevice device = result.getDevice(); ScanRecord record = result.getScanRecord();
                if (device == null || record == null) return;
                byte[] bytes = record.getBytes(); if (bytes == null || bytes.length > BleCentral.MAX_ADVERTISEMENT_BYTES) return;
                // Address is used solely as a native deduplication key. Never put it in a bridge event.
                events.found(this, device.getAddress(), device, record.getDeviceName(), result.getRssi(), bytes);
            } catch (SecurityException denied) { events.failed(this, -1); }
        }
        @Override public boolean stop() {
            ended = true;
            if (scanner == null || callback == null) return true;
            try { scanner.stopScan(callback); scanner = null; callback = null; return true; }
            catch (RuntimeException unconfirmed) { return false; }
        }
    }
    private final class NativeLink implements BleCentral.Link {
        final BluetoothDevice device; final BleCentral.LinkEvents events;
        final Object lock = new Object(); BluetoothGatt gatt; BleCentral.Command pending; volatile boolean ended;
        NativeLink(BluetoothDevice device, BleCentral.LinkEvents events) { this.device = device; this.events = events; }
        private boolean matches(BluetoothGatt source) {
            synchronized (lock) {
                if (ended) return false;
                // An early Binder callback may arrive before connectGatt returns on API 24/25.
                if (gatt == null) gatt = source;
                return gatt == source;
            }
        }
        @Override public boolean connect() {
            require(false, true);
            BluetoothGatt created = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            synchronized (lock) {
                if (created == null) return false;
                if (ended || (gatt != null && gatt != created)) { created.close(); return false; }
                gatt = created; return true;
            }
        }
        @Override public boolean execute(BleCentral.Command command) {
            require(false, true); BluetoothGatt current;
            synchronized (lock) { if (ended || gatt == null || pending != null) return false; pending = command; current = gatt; }
            boolean accepted;
            switch (command.kind) {
                case "discover": accepted = current.discoverServices(); break;
                case "requestMtu": accepted = current.requestMtu(command.requestedMtu); break;
                case "readCharacteristic": accepted = current.readCharacteristic((BluetoothGattCharacteristic)command.attribute); break;
                case "readDescriptor": accepted = current.readDescriptor((BluetoothGattDescriptor)command.attribute); break;
                case "writeCharacteristic": {
                    BluetoothGattCharacteristic characteristic = (BluetoothGattCharacteristic)command.attribute;
                    byte[] bytes = command.value(); int type = "noResponse".equals(command.writeType) ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    if (Build.VERSION.SDK_INT >= 33) accepted = current.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS;
                    else { characteristic.setWriteType(type); accepted = characteristic.setValue(bytes) && current.writeCharacteristic(characteristic); }
                    break;
                }
                case "writeDescriptor": {
                    BluetoothGattDescriptor descriptor = (BluetoothGattDescriptor)command.attribute; byte[] bytes = command.value();
                    if (Build.VERSION.SDK_INT >= 33) accepted = current.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS;
                    else accepted = descriptor.setValue(bytes) && current.writeDescriptor(descriptor);
                    break;
                }
                default: throw new IllegalArgumentException("Unsupported native GATT operation");
            }
            if (!accepted) synchronized (lock) { if (pending == command) pending = null; }
            return accepted;
        }
        @Override public boolean localNotifications(Object characteristic, boolean enabled) {
            require(false, true); BluetoothGatt current;
            synchronized (lock) { if (ended || gatt == null) return false; current = gatt; }
            return current.setCharacteristicNotification((BluetoothGattCharacteristic)characteristic, enabled);
        }
        @Override public boolean close() {
            BluetoothGatt current;
            synchronized (lock) { ended = true; pending = null; current = gatt; }
            if (current == null) return true;
            // Closing the client is authoritative local cleanup even if disconnect requires a now-revoked permission.
            try { current.disconnect(); } catch (RuntimeException ignored) { }
            try { current.close(); synchronized (lock) { if (gatt == current) gatt = null; } return true; }
            catch (RuntimeException unconfirmed) { return false; }
        }
        private void done(BluetoothGatt source, String kind, Object attribute, int status, byte[] value, List<BleCentral.Service> services) {
            if (!matches(source)) return;
            BleCentral.Command op;
            synchronized (lock) {
                op = pending;
                if (op == null || !op.kind.equals(kind) || op.attribute != attribute) return;
                pending = null;
            }
            // Copy callback-time bytes before leaving the callback; never reread mutable characteristic fields later.
            byte[] snapshot = value == null || value.length > BleCentral.MAX_VALUE_BYTES ? value : value.clone();
            events.completed(this, op.token, kind, attribute, status, snapshot, services);
        }
        private void changed(BluetoothGatt source, BluetoothGattCharacteristic attribute, byte[] value) {
            if (!matches(source) || value == null || value.length > BleCentral.MAX_VALUE_BYTES) return;
            events.notification(this, attribute, value.clone());
        }
        private List<BleCentral.Service> snapshot(BluetoothGatt source) {
            List<BluetoothGattService> nativeServices = source.getServices();
            if (nativeServices == null || nativeServices.size() > limits.services) return null;
            List<BleCentral.Service> result = new ArrayList<>(); int attributes = 0;
            for (BluetoothGattService service : nativeServices) {
                List<BluetoothGattCharacteristic> nativeChars = service.getCharacteristics(); if (nativeChars.size() > limits.attributes) return null;
                List<BleCentral.Attribute> chars = new ArrayList<>();
                for (BluetoothGattCharacteristic characteristic : nativeChars) {
                    if (++attributes > limits.attributes) return null;
                    List<BluetoothGattDescriptor> nativeDescriptors = characteristic.getDescriptors(); if (nativeDescriptors.size() > limits.attributes) return null;
                    List<BleCentral.Attribute> descriptors = new ArrayList<>();
                    for (BluetoothGattDescriptor descriptor : nativeDescriptors) {
                        if (++attributes > limits.attributes) return null;
                        descriptors.add(new BleCentral.Attribute(descriptor.getUuid(), descriptor, 0, Collections.emptyList()));
                    }
                    chars.add(new BleCentral.Attribute(characteristic.getUuid(), characteristic, characteristic.getProperties(), descriptors));
                }
                result.add(new BleCentral.Service(service.getUuid(), service.getInstanceId(), service.getType(), chars));
            }
            return result;
        }
        final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
                if (matches(source)) events.state(NativeLink.this, state == BluetoothProfile.STATE_CONNECTED, status);
            }
            @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
                if (!matches(source)) return;
                List<BleCentral.Service> services = null;
                try { if (status == BluetoothGatt.GATT_SUCCESS) services = snapshot(source); }
                catch (RuntimeException invalid) { /* Null catalog is fail-closed in the core. */ }
                done(source, "discover", null, status, null, services);
            }
            @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic attribute, int status) {
                if (Build.VERSION.SDK_INT < 33) done(source, "readCharacteristic", attribute, status, attribute.getValue(), null);
            }
            @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic attribute, byte[] value, int status) { done(source, "readCharacteristic", attribute, status, value, null); }
            @Override public void onDescriptorRead(BluetoothGatt source, BluetoothGattDescriptor attribute, int status) {
                if (Build.VERSION.SDK_INT < 33) done(source, "readDescriptor", attribute, status, attribute.getValue(), null);
            }
            @Override public void onDescriptorRead(BluetoothGatt source, BluetoothGattDescriptor attribute, int status, byte[] value) { done(source, "readDescriptor", attribute, status, value, null); }
            @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic attribute, int status) { done(source, "writeCharacteristic", attribute, status, null, null); }
            @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor attribute, int status) { done(source, "writeDescriptor", attribute, status, null, null); }
            @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic attribute) {
                if (Build.VERSION.SDK_INT < 33) changed(source, attribute, attribute.getValue());
            }
            @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic attribute, byte[] value) { changed(source, attribute, value); }
            @Override public void onMtuChanged(BluetoothGatt source, int mtu, int status) {
                if (!matches(source)) return;
                long token = 0;
                synchronized (lock) {
                    if (pending != null && "requestMtu".equals(pending.kind)) { token = pending.token; pending = null; }
                }
                events.mtu(NativeLink.this, token, status, mtu);
            }
            @Override public void onServiceChanged(BluetoothGatt source) { if (matches(source)) events.invalidated(NativeLink.this); }
        };
    }
}

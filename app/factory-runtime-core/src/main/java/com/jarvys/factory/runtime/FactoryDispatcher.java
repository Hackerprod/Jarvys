package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single platform-neutral handler dispatch for installed runtime and isolated harness. */
public final class FactoryDispatcher {
    private FactoryDispatcher() { }
    public interface Metadata {
        JSONObject deviceInfo() throws Exception;
        String mode();
        String hostAppId();
    }
    public interface Effects { Object perform(CapabilityCatalog.Method method, JSONObject args) throws Exception; }
    public static Metadata metadata(String mode, String hostAppId, int apiLevel, int targetSdk) {
        if (!"preview".equals(mode) && !"installed".equals(mode)) throw new IllegalArgumentException("Unknown mode");
        return new Metadata() {
            public String mode() { return mode; }
            public String hostAppId() { return hostAppId; }
            public JSONObject deviceInfo() throws Exception {
                return new JSONObject().put("platform", "android").put("apiLevel", apiLevel)
                        .put("appId", hostAppId).put("hostAppId", hostAppId).put("targetSdk", targetSdk).put("mode", mode);
            }
        };
    }
    public static Metadata previewMetadata(String hostAppId, int apiLevel, int targetSdk) {
        return metadata("preview", hostAppId, apiLevel, targetSdk);
    }
    public static Effects simulatedEffects() {
        return (method, args) -> {
            switch (method) {
                case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
                    throw new FactoryException("UNAVAILABLE", "Documents require an installed generated app and the matching human-only Jarvys broker; preview never opens files.");
                case HAPTICS_PERFORM: case SHARE_TEXT: case CLIPBOARD_WRITE: case EXPORT_TEXT:
                    return new JSONObject().put("simulated", true).put("performed", false).put("mode", "preview")
                            .put("operation", method.wireName);
                default: throw new FactoryException("UNKNOWN_METHOD", "Not an effect operation.");
            }
        };
    }
    public static Object dispatch(BridgeProtocol.Request request, FactoryConfig config, BoundedStore store,
                                  Metadata metadata, Effects effects) throws Exception {
        // Defense in depth for callers outside the WebView bridge, using the exact same validator.
        BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                new JSONObject().put("v", 1).put("id", request.id).put("method", request.method).put("args", request.args).toString(), config);
        switch (request.operation) {
            case STORAGE_GET: return store.get(request.args.getString("key"));
            case STORAGE_SET: store.set(request.args.getString("key"), request.args.getString("value")); return null;
            case STORAGE_REMOVE: store.remove(request.args.getString("key")); return null;
            case STORAGE_LIST: return new JSONArray(store.list());
            case RUNTIME_INFO:
                return new JSONObject().put("sdkVersion", CapabilityCatalog.SDK_VERSION).put("offline", true)
                        .put("mode", metadata.mode()).put("declaredAppId", config.appId).put("hostAppId", metadata.hostAppId())
                        .put("simulatedCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("export", "share", "clipboard", "haptics") : java.util.Collections.emptyList()))
                        .put("unavailableCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Collections.singletonList("documents") : java.util.Collections.emptyList()))
                        .put("documentProtocolVersion", 1)
                        .put("documentBrokerRequired", true)
                        .put("implementedCapabilities", new JSONArray(FactoryConfig.SUPPORTED))
                        .put("declaredCapabilities", new JSONArray(config.capabilities))
                        .put("limits", new JSONObject().put("messageBytes", BridgeProtocol.MAX_MESSAGE_BYTES)
                                .put("textBytes", BridgeProtocol.MAX_TEXT_BYTES).put("storageValueBytes", BridgeProtocol.MAX_VALUE_BYTES)
                                .put("storageBytes", BoundedStore.MAX_TOTAL_BYTES).put("storageEntries", BoundedStore.MAX_ENTRIES)
                                .put("documentChunkBytes", 32768).put("documentBytes", 16 * 1024 * 1024)
                                .put("documentSessionBytes", 32 * 1024 * 1024).put("documentHandles", 4)
                                .put("documentHandleLifetimeMs", 300000));
            case DEVICE_INFO: return metadata.deviceInfo().put("declaredAppId", config.appId);
            case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
            case HAPTICS_PERFORM: case SHARE_TEXT: case CLIPBOARD_WRITE: case EXPORT_TEXT:
                return effects.perform(request.operation, request.args);
            default: throw new FactoryException("UNKNOWN_METHOD", "Unknown operation.");
        }
    }
}

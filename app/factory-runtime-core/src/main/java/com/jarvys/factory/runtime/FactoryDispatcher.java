package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import com.jarvys.factory.contract.ExternalLaunchSpec;
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
                case CONTACTS_PICK: case MAPS_OPEN: case PHONE_DIAL: case EMAIL_COMPOSE: case SMS_COMPOSE: case BROWSER_OPEN: case AUDIO_PLAY: case PHOTOS_PICK: case PHOTOS_CAPTURE: case SHARE_FILE: case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
                    throw new FactoryException("UNAVAILABLE", "Documents, photos, audio, browser, maps, phone, email, SMS and contacts require an installed generated app and the matching human-only Jarvys broker; preview never opens files, camera, audio, external URLs, maps, dialers, message editors or contact pickers.");
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
                        .put("offlineScope", "embedded_webview").put("browserMayUseExternalNetwork", true)
                        .put("externalRecipientsMayUseNetwork", true)
                        .put("externalActionConfirmed", false)
                        .put("mode", metadata.mode()).put("declaredAppId", config.appId).put("hostAppId", metadata.hostAppId())
                        .put("simulatedCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("export", "share", "clipboard", "haptics") : java.util.Collections.emptyList()))
                        .put("unavailableCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("documents", "photos", "audio", "browser", "maps", "phone", "email", "sms", "contacts") : java.util.Collections.emptyList()))
                        .put("unavailableMethods", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("share.file", "documents.open", "documents.create", "documents.read", "documents.write", "documents.close", "documents.cancel", "photos.pick", "photos.capture", "audio.play", "browser.open", "maps.open", "phone.dial", "email.compose", "sms.compose", "contacts.pick")
                                : java.util.Collections.emptyList()))
                        .put("contactPickProtocolVersion", 1)
                        .put("contactsRequires", new JSONArray(java.util.Collections.singletonList("contacts")))
                        .put("contactsBrokerRequired", true)
                        .put("contactsKinds", new JSONArray(java.util.Arrays.asList("phone", "email")))
                        .put("contactsValueNormalized", false)
                        .put("contactsValueSyntaxValidated", false)
                        .put("externalLaunchProtocolVersion", 1)
                        .put("mapsRequires", new JSONArray(java.util.Collections.singletonList("maps")))
                        .put("phoneRequires", new JSONArray(java.util.Collections.singletonList("phone")))
                        .put("emailRequires", new JSONArray(java.util.Collections.singletonList("email")))
                        .put("smsRequires", new JSONArray(java.util.Collections.singletonList("sms")))
                        .put("externalEditorsMaySyncDrafts", true)
                        .put("externalLaunchBrokerRequired", true)
                        .put("browserProtocolVersion", 1)
                        .put("browserRequires", new JSONArray(java.util.Collections.singletonList("browser")))
                        .put("browserBrokerRequired", true)
                        .put("fileShareProtocolVersion", 1)
                        .put("fileShareRequires", new JSONArray(java.util.Arrays.asList("documents", "share")))
                        .put("audioProtocolVersion", 1)
                        .put("audioRequires", new JSONArray(java.util.Arrays.asList("documents", "audio")))
                        .put("audioBrokerRequired", true)
                        .put("audioFormats", new JSONArray(java.util.Collections.singletonList("wav-pcm16")))
                        .put("photoProtocolVersion", 1)
                        .put("photoRequires", new JSONArray(java.util.Arrays.asList("documents", "photos")))
                        .put("photoBrokerRequired", true)
                        .put("documentProtocolVersion", 1)
                        .put("documentBrokerRequired", true)
                        .put("implementedCapabilities", new JSONArray(FactoryConfig.SUPPORTED))
                        .put("declaredCapabilities", new JSONArray(config.capabilities))
                        .put("limits", new JSONObject().put("messageBytes", BridgeProtocol.MAX_MESSAGE_BYTES)
                                .put("textBytes", BridgeProtocol.MAX_TEXT_BYTES).put("storageValueBytes", BridgeProtocol.MAX_VALUE_BYTES)
                                .put("storageBytes", BoundedStore.MAX_TOTAL_BYTES).put("storageEntries", BoundedStore.MAX_ENTRIES)
                                .put("browserUrlLength", com.jarvys.factory.contract.BrowserUrl.MAX_LENGTH)
                                .put("browserLifetimeMs", 300000)
                                .put("externalLaunchLifetimeMs", 300000)
                                .put("contactPickLifetimeMs", 300000)
                                .put("contactArgumentsBytes", ContactPickRequest.MAX_ARGS_BYTES)
                                .put("contactValueCodePoints", com.jarvys.factory.contract.ContactPickSpec.MAX_VALUE_CODE_POINTS)
                                .put("contactValueBytes", com.jarvys.factory.contract.ContactPickSpec.MAX_VALUE_BYTES)
                                .put("emailAddressCharacters", ExternalLaunchSpec.MAX_EMAIL_ADDRESS)
                                .put("editorSubjectCodePoints", ExternalLaunchSpec.MAX_SUBJECT_CODE_POINTS)
                                .put("editorSubjectBytes", ExternalLaunchSpec.MAX_SUBJECT_BYTES)
                                .put("editorBodyBytes", ExternalLaunchSpec.MAX_BODY_BYTES)
                                .put("editorArgumentsBytes", ExternalLaunchRequest.MAX_EDITOR_ARGS_BYTES)
                                .put("mapsQueryCodePoints", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_QUERY_CODE_POINTS)
                                .put("mapsQueryBytes", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_QUERY_BYTES)
                                .put("phoneDigits", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_PHONE_DIGITS)
                                .put("documentChunkBytes", 32768).put("documentBytes", 16 * 1024 * 1024)
                                .put("documentSessionBytes", 32 * 1024 * 1024).put("documentHandles", 4)
                                .put("fileShareBytes", 8 * 1024 * 1024).put("fileShareLifetimeMs", 300000)
                                .put("audioBytes", 6 * 1024 * 1024).put("audioDurationMs", 30000)
                                .put("audioChannelsMax", 2).put("audioBitsPerSample", 16)
                                .put("audioSampleRateMin", 8000).put("audioSampleRateMax", 48000)
                                .put("documentHandleLifetimeMs", 300000));
            case DEVICE_INFO: return metadata.deviceInfo().put("declaredAppId", config.appId);
            case CONTACTS_PICK: case MAPS_OPEN: case PHONE_DIAL: case EMAIL_COMPOSE: case SMS_COMPOSE: case BROWSER_OPEN: case AUDIO_PLAY: case PHOTOS_PICK: case PHOTOS_CAPTURE: case SHARE_FILE: case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
            case HAPTICS_PERFORM: case SHARE_TEXT: case CLIPBOARD_WRITE: case EXPORT_TEXT:
                return effects.perform(request.operation, request.args);
            default: throw new FactoryException("UNKNOWN_METHOD", "Unknown operation.");
        }
    }
}

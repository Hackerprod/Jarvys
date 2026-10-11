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
    public interface Database { Object perform(String method, JSONObject args) throws Exception; }
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
                case NETWORK_REQUEST: case NETWORK_PROBE: case NETWORK_PING: case NETWORK_CANCEL:
                case NOTIFICATIONS_STATUS: case NOTIFICATIONS_CHANNEL: case NOTIFICATIONS_SHOW: case NOTIFICATIONS_CANCEL: case NOTIFICATIONS_REQUEST_PERMISSION:
                case SPEECH_STATUS: case SPEECH_VOICES: case SPEECH_SPEAK: case SPEECH_STOP:
                    throw new FactoryException("UNAVAILABLE", "Native network, notifications and speech require an installed generated app and live Jarvys policy. ICMP also requires an available Android ping utility. Preview does not perform or simulate these effects.");
                case BIOMETRICS_AVAILABILITY: case BIOMETRICS_AUTHENTICATE: case BIOMETRICS_CANCEL:
                case SENSORS_LIST: case SENSORS_CAPTURE: case SENSORS_CANCEL:
                case PRESENTATION_GET: case PRESENTATION_SET: case PRESENTATION_RESET:
                case CALENDAR_INSERT: case CONTACTS_PICK: case MAPS_OPEN: case PHONE_DIAL: case EMAIL_COMPOSE: case SMS_COMPOSE: case BROWSER_OPEN: case AUDIO_PLAY: case PHOTOS_PICK: case PHOTOS_CAPTURE: case SHARE_FILE: case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
                    throw new FactoryException("UNAVAILABLE", "Biometrics requires an installed generated Activity and is never simulated. Presentation and sensors require an installed generated Activity; preview cannot change host theme/orientation or read hardware sensors. Documents, photos, audio, browser, maps, phone, email, SMS, contacts and calendar require an installed generated app and the matching authenticated Jarvys broker; preview never opens files, camera, audio, external URLs, maps, dialers, message editors, contact pickers or calendar editors.");
                case HAPTICS_PERFORM: case SHARE_TEXT: case CLIPBOARD_WRITE: case EXPORT_TEXT:
                    return new JSONObject().put("simulated", true).put("performed", false).put("mode", "preview")
                            .put("operation", method.wireName);
                default: throw new FactoryException("UNKNOWN_METHOD", "Not an effect operation.");
            }
        };
    }
    public static Object dispatch(BridgeProtocol.Request request, FactoryConfig config, BoundedStore store,
                                  Metadata metadata, Effects effects) throws Exception {
        return dispatch(request,config,store,metadata,effects,null);
    }
    public static Object dispatch(BridgeProtocol.Request request, FactoryConfig config, BoundedStore store,
                                  Metadata metadata, Effects effects, Database database) throws Exception {
        // Preserve sensor integer types before defensive JSON serialization.
        if(request.operation==CapabilityCatalog.Method.SENSORS_CAPTURE) SensorCapture.Request.parse(request.args);
        // Preserve database numeric types before the defensive envelope serialization.
        boolean backup = request.operation == CapabilityCatalog.Method.DATABASE_BACKUP_EXPORT ||
                request.operation == CapabilityCatalog.Method.DATABASE_BACKUP_IMPORT;
        if (backup) DatabaseBackupRequest.validate(request.method, request.args);
        else if (request.operation == CapabilityCatalog.Method.STORAGE_BACKUP_EXPORT ||
                request.operation == CapabilityCatalog.Method.STORAGE_BACKUP_IMPORT)
            StorageBackupRequest.validate(request.method, request.args);
        else if ("database".equals(request.operation.capability)) DatabaseRequest.validate(request.method,request.args);
        // Preserve integer types before the defensive envelope serialization.
        if (request.operation == CapabilityCatalog.Method.CALENDAR_INSERT) ExternalLaunchRequest.parseCalendar(request.args);
        // Defense in depth for callers outside the WebView bridge, using the exact same validator.
        BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                new JSONObject().put("v", 1).put("id", request.id).put("method", request.method).put("args", request.args).toString(), config);
        switch (request.operation) {
            case STORAGE_BACKUP_EXPORT: case STORAGE_BACKUP_IMPORT:
                if ("preview".equals(metadata.mode())) throw new FactoryException("UNAVAILABLE", "Storage backups require an installed generated app, current exact signed receipt and matching authenticated Jarvys broker.");
                JSONArray keys = request.args.getJSONArray("keys");
                for (int index = 0; index < keys.length(); index++)
                    if (!config.dataBackupKeys.contains(keys.getString(index)))
                        throw new FactoryException("BACKUP_DENIED", "Selected key is not declared for data backup.");
                if (database == null) throw new FactoryException("UNAVAILABLE", "A private native storage backup session is required.");
                return database.perform(request.method, request.args);
            case DATABASE_BACKUP_EXPORT: case DATABASE_BACKUP_IMPORT:
                if ("preview".equals(metadata.mode())) throw new FactoryException("UNAVAILABLE", "Database backups require an installed generated app, current exact signed receipt and matching authenticated Jarvys broker.");
                JSONArray tables = request.args.getJSONArray("tables");
                for (int index = 0; index < tables.length(); index++)
                    if (!config.dataBackupTables.contains(tables.getString(index)))
                        throw new FactoryException("BACKUP_DENIED", "Selected table is not declared for data backup.");
                if (database == null) throw new FactoryException("UNAVAILABLE", "A private native database backup session is required.");
                return database.perform(request.method, request.args);
            case DATABASE_INFO: case DATABASE_MIGRATE: case DATABASE_TRANSACT: case DATABASE_SELECT: case DATABASE_CLOSE: case DATABASE_CANCEL:
                if(database==null) throw new FactoryException("UNAVAILABLE","A private native database session is required.");
                return database.perform(request.method,request.args);
            case STORAGE_GET: return store.get(request.args.getString("key"));
            case STORAGE_SET: store.set(request.args.getString("key"), request.args.getString("value")); return null;
            case STORAGE_REMOVE: store.remove(request.args.getString("key")); return null;
            case STORAGE_LIST: return new JSONArray(store.list());
            case RUNTIME_INFO:
                return new JSONObject().put("sdkVersion", CapabilityCatalog.SDK_VERSION).put("offline", !config.capabilities.contains("network"))
                        .put("offlineScope", "embedded_webview_configuration_not_process_isolation")
                        .put("processInternetPermission", config.capabilities.contains("network"))
                        .put("nativeNetworkProtocol", "http_https").put("nativeProbeIsIcmp", false)
                        .put("nativePingProtocol", "icmp").put("platformPingUtilityRequired", true)
                        .put("nativeOperationsPolicy", "host_allow_ask_deny").put("speechEngineMayUseNetwork", true)
                        .put("notificationAcceptedMeansDelivered", false).put("browserMayUseExternalNetwork", true)
                        .put("externalRecipientsMayUseNetwork", true)
                        .put("externalActionConfirmed", false)
                        .put("mode", metadata.mode()).put("declaredAppId", config.appId).put("hostAppId", metadata.hostAppId())
                        .put("simulatedCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("export", "share", "clipboard", "haptics") : java.util.Collections.emptyList()))
                        .put("unavailableCapabilities", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("documents", "photos", "audio", "browser", "maps", "phone", "email", "sms", "contacts", "calendar", "presentation", "sensors", "biometrics", "network", "notifications", "speech") : java.util.Collections.emptyList()))
                        .put("unavailableMethods", new JSONArray("preview".equals(metadata.mode())
                                ? java.util.Arrays.asList("share.file", "documents.open", "documents.create", "documents.read", "documents.write", "documents.close", "documents.cancel", "photos.pick", "photos.capture", "audio.play", "browser.open", "maps.open", "phone.dial", "email.compose", "sms.compose", "contacts.pick", "calendar.insert", "presentation.get", "presentation.set", "presentation.reset", "sensors.list", "sensors.capture", "sensors.cancel", "biometrics.availability", "biometrics.authenticate", "biometrics.cancel", "database.backupExport", "database.backupImport", "storage.backupExport", "storage.backupImport", "network.request", "network.probe", "network.ping", "network.cancel", "notifications.status", "notifications.channel", "notifications.show", "notifications.cancel", "notifications.requestPermission", "speech.status", "speech.voices", "speech.speak", "speech.stop")
                                : java.util.Collections.emptyList()))
                        .put("biometricsProtocolVersion", 1).put("biometricsBrokerRequired", false)
                        .put("biometricsRequires", new JSONArray(java.util.Collections.singletonList("biometrics")))
                        .put("biometricsMinimumApi", 30).put("biometricsClass", "strong")
                        .put("biometricsDeviceCredentialAllowed", false).put("biometricsForegroundOnly", true)
                        .put("biometricsLocalOnly", true).put("biometricsAuthorizesOtherActions", false)
                        .put("sensorsProtocolVersion", 1).put("sensorsBrokerRequired", false)
                        .put("sensorsKinds",new JSONArray(java.util.Arrays.asList("light","accelerometer","gyroscope")))
                        .put("sensorsForegroundOnly",true).put("sensorsMaxRateHz",10)
                        .put("sensorsMaxDurationMs",5000).put("sensorsMaxSamples",50).put("sensorsResultBytes",16384)
                        .put("presentationProtocolVersion", 1).put("presentationBrokerRequired", false)
                        .put("presentationRequires", new JSONArray(java.util.Collections.singletonList("presentation")))
                        .put("presentationThemes", new JSONArray(java.util.Arrays.asList("system", "light", "dark")))
                        .put("presentationOrientations", new JSONArray(java.util.Arrays.asList("system", "portrait", "landscape")))
                        .put("presentationOrientationGuaranteed", false).put("presentationRecreatesActivity", true)
                        .put("databaseProtocolVersion", 1).put("databaseBrokerRequired", false)
                        .put("databasePersistent", !"preview".equals(metadata.mode()))
                        .put("databaseRequires", new JSONArray(java.util.Collections.singletonList("database")))
                        .put("signedArtifactHistoryRequired", true).put("databaseBackupProtocolVersion", 2).put("databaseBackupBrokerRequired", true)
                        .put("databaseBackupRequires", new JSONArray(java.util.Arrays.asList("database", "documents")))
                        .put("databaseBackupTables", new JSONArray(config.dataBackupTables))
                        .put("databaseBackupFormat", "jarvys-sqlite-data").put("databaseBackupPlaintext", true)
                        .put("databaseBackupCurrentSignedReceiptRequired", false)
                        .put("databaseBackupModes", new JSONArray(java.util.Arrays.asList("insertOnly", "replaceSelectedTables")))
                        .put("databaseBackupExactSchemaRequired", true).put("databaseBackupProviderDurabilityConfirmed", false)
                        .put("storageBackupProtocolVersion", 2).put("storageBackupBrokerRequired", true)
                        .put("storageBackupRequires", new JSONArray(java.util.Arrays.asList("storage", "documents")))
                        .put("storageBackupKeys", new JSONArray(config.dataBackupKeys))
                        .put("storageBackupFormat", "jarvys-preferences-data").put("storageBackupPlaintext", true)
                        .put("storageBackupCurrentSignedReceiptRequired", false)
                        .put("storageBackupModes", new JSONArray(java.util.Arrays.asList("insertOnly", "replaceSelectedKeys")))
                        .put("storageBackupScope", "string_key_value_store")
                        .put("storageBackupProviderDurabilityConfirmed", false)
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
                        .put("calendarRequires", new JSONArray(java.util.Collections.singletonList("calendar")))
                        .put("calendarBrokerRequired", true)
                        .put("calendarTimeZoneAdvisory", true)
                        .put("calendarTimeZoneIdsRuntimeSpecific", true)
                        .put("calendarAllDayEndExclusive", true)
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
                                .put("biometricsReasonCharacters", 160).put("biometricsReasonBytes", 512)
                                .put("biometricsAuthenticationMs", 60000).put("biometricsAdmissionCount", 3)
                                .put("biometricsAdmissionWindowMs", 60000).put("biometricsCancelEnvelopeBytes", 1024)
                                .put("biometricsNoopCancelCount", 8).put("biometricsNoopCancelWindowMs", 10000)
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
                                .put("calendarTitleCodePoints", ExternalLaunchSpec.CalendarInsertSpec.MAX_TITLE_CODE_POINTS)
                                .put("calendarTitleBytes", ExternalLaunchSpec.CalendarInsertSpec.MAX_TITLE_BYTES)
                                .put("calendarLocationCodePoints", ExternalLaunchSpec.CalendarInsertSpec.MAX_LOCATION_CODE_POINTS)
                                .put("calendarLocationBytes", ExternalLaunchSpec.CalendarInsertSpec.MAX_LOCATION_BYTES)
                                .put("calendarDescriptionBytes", ExternalLaunchSpec.CalendarInsertSpec.MAX_DESCRIPTION_BYTES)
                                .put("calendarMinTimeMillis", ExternalLaunchSpec.CalendarInsertSpec.MIN_TIME_MILLIS)
                                .put("calendarMaxTimeMillis", ExternalLaunchSpec.CalendarInsertSpec.MAX_TIME_MILLIS)
                                .put("calendarMaxDurationMillis", ExternalLaunchSpec.CalendarInsertSpec.MAX_DURATION_MILLIS)
                                .put("mapsQueryCodePoints", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_QUERY_CODE_POINTS)
                                .put("mapsQueryBytes", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_QUERY_BYTES)
                                .put("phoneDigits", com.jarvys.factory.contract.ExternalLaunchSpec.MAX_PHONE_DIGITS)
                                .put("databaseBackupArgumentBytes", DatabaseBackupRequest.MAX_ARGUMENT_BYTES)
                                .put("databaseBackupBytes", 1024 * 1024).put("databaseBackupTables", 8)
                                .put("databaseBackupLifetimeMs", 300000)
                                .put("storageBackupArgumentBytes", StorageBackupRequest.MAX_ARGUMENT_BYTES)
                                .put("storageBackupBytes", 1024 * 1024).put("storageBackupKeys", 32)
                                .put("storageBackupLifetimeMs", 300000)
                                .put("documentChunkBytes", 32768).put("documentBytes", 16 * 1024 * 1024)
                                .put("documentSessionBytes", 32 * 1024 * 1024).put("documentHandles", 4)
                                .put("fileShareBytes", 8 * 1024 * 1024).put("fileShareLifetimeMs", 300000)
                                .put("audioBytes", 6 * 1024 * 1024).put("audioDurationMs", 30000)
                                .put("audioChannelsMax", 2).put("audioBitsPerSample", 16)
                                .put("audioSampleRateMin", 8000).put("audioSampleRateMax", 48000)
                                .put("documentHandleLifetimeMs", 300000));
            case DEVICE_INFO: return metadata.deviceInfo().put("declaredAppId", config.appId);
                case NETWORK_REQUEST: case NETWORK_PROBE: case NETWORK_PING: case NETWORK_CANCEL:
                case NOTIFICATIONS_STATUS: case NOTIFICATIONS_CHANNEL: case NOTIFICATIONS_SHOW: case NOTIFICATIONS_CANCEL: case NOTIFICATIONS_REQUEST_PERMISSION:
                case SPEECH_STATUS: case SPEECH_VOICES: case SPEECH_SPEAK: case SPEECH_STOP:
            case BIOMETRICS_AVAILABILITY: case BIOMETRICS_AUTHENTICATE: case BIOMETRICS_CANCEL:
                case SENSORS_LIST: case SENSORS_CAPTURE: case SENSORS_CANCEL:
                case PRESENTATION_GET: case PRESENTATION_SET: case PRESENTATION_RESET:
                case CALENDAR_INSERT: case CONTACTS_PICK: case MAPS_OPEN: case PHONE_DIAL: case EMAIL_COMPOSE: case SMS_COMPOSE: case BROWSER_OPEN: case AUDIO_PLAY: case PHOTOS_PICK: case PHOTOS_CAPTURE: case SHARE_FILE: case DOCUMENTS_OPEN: case DOCUMENTS_CREATE: case DOCUMENTS_READ: case DOCUMENTS_WRITE: case DOCUMENTS_CLOSE: case DOCUMENTS_CANCEL:
            case HAPTICS_PERFORM: case SHARE_TEXT: case CLIPBOARD_WRITE: case EXPORT_TEXT:
                return effects.perform(request.operation, request.args);
            default: throw new FactoryException("UNKNOWN_METHOD", "Unknown operation.");
        }
    }
}

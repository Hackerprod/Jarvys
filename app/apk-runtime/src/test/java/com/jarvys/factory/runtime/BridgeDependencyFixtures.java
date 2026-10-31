package com.jarvys.factory.runtime;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit typed arguments and independently declared dependency expectations for all 134 methods. */
final class BridgeDependencyFixtures {
    private BridgeDependencyFixtures() { }
    static final class Fixture {
        final String method, arguments, primary;
        final String[] extra;
        Fixture(String method, String arguments, String primary, String... extra) {
            this.method = method; this.arguments = arguments; this.primary = primary; this.extra = extra;
        }
        Set<String> requiredCapabilities() {
            Set<String> required = new LinkedHashSet<>(Arrays.asList(extra));
            if (primary != null) required.add(primary);
            return required;
        }
        String invalidExtraCode() {
            // Bluetooth's closed request grammar deliberately uses INVALID_REQUEST.
            return "bluetooth".equals(primary) ? "INVALID_REQUEST" : "INVALID_ARGUMENT";
        }
        Set<String> preArgumentCapabilities() {
            Set<String> before = new LinkedHashSet<>();
            // These exact operations intentionally check a dependency before argument parsing.
            if (method.startsWith("jobs.") || method.equals("secrets.request")) before.add("network");
            if (method.equals("network.upload") || method.equals("network.download")) before.add("documents");
            return before;
        }
    }
    static final Fixture[] ALL = {
        new Fixture("runtime.info", "{}", null),
        new Fixture("storage.set", "{\"key\":\"factory-test\",\"value\":\"synthetic\"}", "storage"),
        new Fixture("storage.get", "{\"key\":\"factory-test\"}", "storage"),
        new Fixture("storage.list", "{}", "storage"),
        new Fixture("storage.listPage", "{\"limit\":1}", "storage"),
        new Fixture("storage.remove", "{\"key\":\"factory-test\"}", "storage"),
        new Fixture("storage.backupExport", "{\"version\":1,\"keys\":[\"settings\"],\"filename\":\"backup.json\"}", "storage", "documents"),
        new Fixture("storage.backupImport", "{\"version\":1,\"keys\":[\"settings\"],\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"mode\":\"insertOnly\"}", "storage", "documents"),
        new Fixture("export.text", "{\"filename\":\"factory-test.txt\",\"text\":\"synthetic\"}", "export"),
        new Fixture("share.text", "{\"text\":\"synthetic\"}", "share"),
        new Fixture("share.file", "{\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"filename\":\"file.bin\",\"mimeType\":\"application/octet-stream\"}", "share", "documents"),
        new Fixture("clipboard.write", "{\"text\":\"synthetic\"}", "clipboard"),
        new Fixture("haptics.perform", "{\"kind\":\"tap\"}", "haptics"),
        new Fixture("device.info", "{}", "device"),
        new Fixture("documents.open", "{\"mimeType\":\"application/octet-stream\"}", "documents"),
        new Fixture("documents.create", "{\"mimeType\":\"application/octet-stream\",\"filename\":\"synthetic.bin\"}", "documents"),
        new Fixture("documents.read", "{\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"offset\":0,\"length\":1}", "documents"),
        new Fixture("documents.write", "{\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"offset\":0,\"data\":\"AA==\"}", "documents"),
        new Fixture("documents.close", "{\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "documents"),
        new Fixture("documents.cancel", "{}", "documents"),
        new Fixture("photos.pick", "{}", "photos", "documents"),
        new Fixture("photos.capture", "{}", "photos", "documents"),
        new Fixture("audio.play", "{\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "audio", "documents"),
        new Fixture("browser.open", "{\"url\":\"https://example.invalid/review?fixture=1\"}", "browser"),
        new Fixture("maps.open", "{\"query\":\"Synthetic map fixture\"}", "maps"),
        new Fixture("phone.dial", "{\"number\":\"+15550100\"}", "phone"),
        new Fixture("email.compose", "{\"to\":\"fixture@example.invalid\",\"subject\":\"Fixture\",\"body\":\"Body\"}", "email"),
        new Fixture("sms.compose", "{\"number\":\"+15550100\",\"body\":\"Body\"}", "sms"),
        new Fixture("contacts.pick", "{\"kind\":\"phone\"}", "contacts"),
        new Fixture("calendar.insert", "{\"title\":\"Synthetic calendar fixture\",\"location\":\"\",\"description\":\"\",\"startTimeMillis\":0,\"endTimeMillis\":3600000,\"timeZone\":\"UTC\",\"allDay\":false}", "calendar"),
        new Fixture("database.info", "{}", "database"),
        new Fixture("database.migrate", "{\"fromVersion\":0,\"toVersion\":1,\"steps\":[{\"kind\":\"createTable\",\"table\":\"notes\",\"columns\":{\"text\":{\"type\":\"text\",\"nullable\":false}}}]}", "database"),
        new Fixture("database.transact", "{\"version\":1,\"operations\":[{\"kind\":\"insert\",\"table\":\"notes\",\"id\":\"one\",\"values\":{\"text\":\"synthetic\"}}]}", "database"),
        new Fixture("database.select", "{\"version\":1,\"table\":\"notes\"}", "database"),
        new Fixture("database.backupExport", "{\"version\":1,\"tables\":[\"notes\"],\"filename\":\"backup.json\"}", "database", "documents"),
        new Fixture("database.backupImport", "{\"version\":1,\"tables\":[\"notes\"],\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"mode\":\"insertOnly\"}", "database", "documents"),
        new Fixture("database.close", "{}", "database"),
        new Fixture("database.cancel", "{}", "database"),
        new Fixture("presentation.get", "{}", "presentation"),
        new Fixture("presentation.set", "{\"theme\":\"dark\",\"orientation\":\"portrait\"}", "presentation"),
        new Fixture("presentation.reset", "{}", "presentation"),
        new Fixture("sensors.list", "{}", "sensors"),
        new Fixture("sensors.capture", "{\"kind\":\"light\",\"durationMs\":100,\"intervalMs\":100,\"maxSamples\":1}", "sensors"),
        new Fixture("sensors.cancel", "{}", "sensors"),
        new Fixture("sensors.subscribe", "{\"kind\":\"light\",\"samplingPeriodUs\":100000}", "sensors"),
        new Fixture("sensors.poll", "{\"subscriptionId\":\"00000000-0000-0000-0000-000000000000\"}", "sensors"),
        new Fixture("sensors.unsubscribe", "{\"subscriptionId\":\"00000000-0000-0000-0000-000000000000\"}", "sensors"),
        new Fixture("biometrics.availability", "{}", "biometrics"),
        new Fixture("biometrics.authenticate", "{\"reason\":\"Confirm this local action\"}", "biometrics"),
        new Fixture("biometrics.cancel", "{}", "biometrics"),
        new Fixture("network.upload", "{\"url\":\"https://example.invalid/fixture\",\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"length\":1,\"maxBytes\":1}", "network", "documents"),
        new Fixture("network.download", "{\"url\":\"https://example.invalid/fixture\",\"handle\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"maxBytes\":1}", "network", "documents"),
        new Fixture("network.request", "{\"url\":\"https://example.invalid/fixture\",\"method\":\"GET\"}", "network"),
        new Fixture("network.probe", "{\"url\":\"https://example.invalid/fixture\",\"method\":\"HEAD\"}", "network"),
        new Fixture("network.ping", "{\"host\":\"example.invalid\",\"count\":1}", "network"),
        new Fixture("network.cancel", "{}", "network"),
        new Fixture("notifications.schedule", "{\"scheduleId\":\"synthetic\",\"atEpochMs\":1,\"expiresAtEpochMs\":2,\"notification\":{\"id\":1,\"channelId\":\"synthetic\",\"title\":\"Synthetic fixture\",\"body\":\"Fixture\"}}", "notifications"),
        new Fixture("notifications.resumeScheduled", "{\"scheduleId\":\"synthetic\"}", "notifications"),
        new Fixture("notifications.scheduleStatus", "{\"scheduleId\":\"synthetic\"}", "notifications"),
        new Fixture("notifications.cancelScheduled", "{\"scheduleId\":\"synthetic\"}", "notifications"),
        new Fixture("notifications.forgetScheduled", "{\"scheduleId\":\"synthetic\",\"acknowledgeDeliveryUnknown\":false}", "notifications"),
        new Fixture("notifications.pollActions", "{}", "notifications"),
        new Fixture("notifications.ackAction", "{\"eventId\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "notifications"),
        new Fixture("notifications.generationStatus", "{\"generationId\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "notifications"),
        new Fixture("notifications.forgetGeneration", "{\"generationId\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"acknowledgeDeliveryUnknown\":false}", "notifications"),
        new Fixture("notifications.requestExactAlarmAccess", "{}", "notifications"),
        new Fixture("notifications.status", "{\"channelId\":\"synthetic\"}", "notifications"),
        new Fixture("notifications.channel", "{\"id\":\"synthetic\",\"name\":\"Synthetic fixture\"}", "notifications"),
        new Fixture("notifications.show", "{\"id\":1,\"channelId\":\"synthetic\",\"title\":\"Synthetic fixture\",\"body\":\"Fixture\"}", "notifications"),
        new Fixture("notifications.cancel", "{\"id\":1}", "notifications"),
        new Fixture("notifications.requestPermission", "{}", "notifications"),
        new Fixture("speech.status", "{}", "speech"),
        new Fixture("speech.voices", "{\"engine\":\"\"}", "speech"),
        new Fixture("speech.speak", "{\"text\":\"Synthetic fixture\",\"language\":\"en-US\"}", "speech"),
        new Fixture("speech.stop", "{}", "speech"),
        new Fixture("voiceRecognition.status", "{}", "voiceRecognition"),
        new Fixture("voiceRecognition.recognize", "{\"language\":\"en-US\",\"maxResults\":1,\"preferOffline\":true,\"timeoutMs\":1000}", "voiceRecognition"),
        new Fixture("voiceRecognition.cancel", "{}", "voiceRecognition"),
        new Fixture("location.status", "{}", "location"),
        new Fixture("location.current", "{\"provider\":\"auto\",\"timeoutMs\":1000}", "location"),
        new Fixture("location.cancel", "{}", "location"),
        new Fixture("location.requestPermission", "{\"precision\":\"approximate\"}", "location"),
        new Fixture("recording.status", "{}", "recording"),
        new Fixture("recording.record", "{\"durationMs\":100,\"maxBytes\":48,\"format\":\"wav-pcm16\",\"sampleRate\":8000,\"channels\":1}", "recording", "documents"),
        new Fixture("recording.stop", "{}", "recording"),
        new Fixture("recording.cancel", "{}", "recording"),
        new Fixture("recording.requestPermission", "{}", "recording"),
        new Fixture("steps.status", "{}", "steps"),
        new Fixture("steps.current", "{\"timeoutMs\":1000}", "steps"),
        new Fixture("steps.cancel", "{}", "steps"),
        new Fixture("steps.requestPermission", "{}", "steps"),
        new Fixture("secrets.put", "{\"label\":\"Synthetic fixture\",\"scope\":{\"origin\":\"https://example.invalid\",\"methods\":[\"GET\"],\"header\":\"authorization\",\"prefix\":\"Bearer \"},\"value\":\"synthetic-not-a-credential\"}", "secrets"),
        new Fixture("secrets.capture", "{\"label\":\"Synthetic fixture\",\"scope\":{\"origin\":\"https://example.invalid\",\"methods\":[\"GET\"],\"header\":\"authorization\",\"prefix\":\"Bearer \"}}", "secrets"),
        new Fixture("secrets.get", "{\"id\":\"sec_00000000000000000000000000000000\"}", "secrets"),
        new Fixture("secrets.list", "{}", "secrets"),
        new Fixture("secrets.describe", "{\"id\":\"sec_00000000000000000000000000000000\"}", "secrets"),
        new Fixture("secrets.remove", "{\"id\":\"sec_00000000000000000000000000000000\"}", "secrets"),
        new Fixture("secrets.reset", "{}", "secrets"),
        new Fixture("secrets.request", "{\"id\":\"sec_00000000000000000000000000000000\",\"request\":{\"url\":\"https://example.invalid/fixture\",\"method\":\"GET\"}}", "secrets", "network"),
        new Fixture("incomingShare.list", "{}", "incomingShare"),
        new Fixture("incomingShare.take", "{\"id\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"attachmentIndices\":[0]}", "incomingShare"),
        new Fixture("incomingShare.discard", "{\"id\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "incomingShare"),
        new Fixture("nfc.status", "{}", "nfc"),
        new Fixture("nfc.read", "{\"timeoutMs\":1000}", "nfc"),
        new Fixture("nfc.write", "{\"timeoutMs\":1000,\"allowFormat\":false,\"records\":[{\"tnf\":0,\"typeHex\":\"\",\"idHex\":\"\",\"payloadHex\":\"\"}]}", "nfc"),
        new Fixture("nfc.cancel", "{}", "nfc"),
        new Fixture("bluetooth.status", "{}", "bluetooth"),
        new Fixture("bluetooth.requestPermissions", "{\"scan\":true,\"connect\":false}", "bluetooth"),
        new Fixture("bluetooth.scan", "{\"serviceUuids\":[],\"durationMs\":1}", "bluetooth"),
        new Fixture("bluetooth.poll", "{\"handle\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.stopScan", "{\"scanId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.connect", "{\"deviceId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.services", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\",\"offset\":0,\"limit\":1}", "bluetooth"),
        new Fixture("bluetooth.read", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\",\"attributeId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.write", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\",\"attributeId\":\"00000000-0000-0000-0000-000000000000\",\"valueBase64\":\"AA==\",\"writeType\":\"response\"}", "bluetooth"),
        new Fixture("bluetooth.requestMtu", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\",\"mtu\":23}", "bluetooth"),
        new Fixture("bluetooth.subscribe", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\",\"attributeId\":\"00000000-0000-0000-0000-000000000000\",\"mode\":\"notify\"}", "bluetooth"),
        new Fixture("bluetooth.unsubscribe", "{\"subscriptionId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.disconnect", "{\"connectionId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("bluetooth.cancel", "{\"operationId\":\"00000000-0000-0000-0000-000000000000\"}", "bluetooth"),
        new Fixture("widgets.availability", "{}", "widgets"),
        new Fixture("widgets.list", "{}", "widgets"),
        new Fixture("widgets.read", "{\"widgetId\":1}", "widgets"),
        new Fixture("widgets.requestPin", "{\"model\":{\"root\":{\"type\":\"text\",\"text\":\"Fixture\"}}}", "widgets"),
        new Fixture("widgets.update", "{\"widgetIds\":[1],\"model\":{\"root\":{\"type\":\"text\",\"text\":\"Fixture\"}}}", "widgets"),
        new Fixture("widgets.remove", "{\"widgetIds\":[1]}", "widgets"),
        new Fixture("widgets.pollActions", "{}", "widgets"),
        new Fixture("widgets.acknowledgeAction", "{\"eventId\":\"0000000000000000000000000000000000000000000000000000000000000000\"}", "widgets"),
        new Fixture("jobs.enqueueHttp", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"request\":{\"url\":\"https://example.invalid/fixture\",\"method\":\"GET\"}}", "jobs", "network"),
        new Fixture("jobs.status", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\"}", "jobs", "network"),
        new Fixture("jobs.cancel", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\"}", "jobs", "network"),
        new Fixture("jobs.readResult", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"acknowledge\":false}", "jobs", "network"),
        new Fixture("jobs.forget", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"acknowledgeUnknown\":false}", "jobs", "network"),
        new Fixture("jobs.resume", "{\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"execution\":\"now\",\"acknowledgeUnknown\":false}", "jobs", "network")
    };
}

package com.jarvys.factory.contract;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Closed production profiles plus host-only construction fixtures beyond the activated catalog. */
public class ManifestNodesTest {
    private static <T> List<T> list(T... values) { return Arrays.asList(values); }
    private static ManifestPlan plan(List<String> caps) {
        return new ManifestPlan("org.example.constructed", "name 🎉 日本語", 7, "versionName", 0x7f010000, 0x7f020000, caps);
    }
    private static ManifestNodes.Element filter(String action, String mime) {
        return ManifestNodes.intentFilter(list(ManifestNodes.action(action), ManifestNodes.category("android.intent.category.DEFAULT"), ManifestNodes.data(mime, null)));
    }
    static ManifestNodes.Element fixture() {
        ManifestPlan base = plan(Collections.emptyList());
        List<ManifestNodes.Element> appChildren = new ArrayList<>(base.root.children.get(1).children);
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.ACTIVITY, "org.example.PrivateActivity", list(
            ManifestNodes.intentFilter(list(ManifestNodes.action("android.intent.action.SEND"), ManifestNodes.action("android.intent.action.SEND_MULTIPLE"),
                ManifestNodes.category("android.intent.category.DEFAULT"), ManifestNodes.category("android.intent.category.BROWSABLE"), ManifestNodes.data("text/plain",null))), filter("android.intent.action.VIEW", "image/png"),
            ManifestNodes.metadataString("org.example.label", "name"), ManifestNodes.metadataBoolean("org.example.flag", false))));
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE, "org.example.PrivateService", list(
            ManifestNodes.metadataInt("org.example.count", 17), ManifestNodes.metadataResource("org.example.resource", 0x7f020000))));
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.RECEIVER, "org.example.PrivateReceiver", list(
            filter("android.intent.action.SEND", "application/json"))));
        appChildren.add(ManifestNodes.privateProvider("org.example.PrivateProvider", base.appId, "files", true, list(
            ManifestNodes.metadataResource("android.support.FILE_PROVIDER_PATHS", 0x7f020000))));
        ManifestNodes.Element app = ManifestNodes.base("application", appChildren, base.root.children.get(1).attributes.toArray(new ManifestPlan.Attribute[0]));
        return ManifestNodes.base("manifest", list(base.root.children.get(0),
            ManifestNodes.permission("android.permission.INTERNET", ManifestNodes.PermissionVariant.STANDARD, null),
            ManifestNodes.permission("android.permission.CAMERA", ManifestNodes.PermissionVariant.SDK_23, 35),
            ManifestNodes.permission("android.permission.RECORD_AUDIO", ManifestNodes.PermissionVariant.STANDARD, 36),
            ManifestNodes.feature("android.hardware.camera", false), ManifestNodes.feature("android.hardware.sensor.accelerometer", true),
            ManifestNodes.queries(list(ManifestNodes.queryPackage("org.example.handler"), ManifestNodes.queryPackage("org.example.second"),
                ManifestNodes.queryIntent("android.intent.action.SEND", "text/plain", null), ManifestNodes.queryIntent("android.intent.action.VIEW", null, "https"))),
            app), base.root.attributes.toArray(new ManifestPlan.Attribute[0]));
    }
    @Test public void currentBaseAndHistoricalPlanEncodeWithExactIndependentAudit() throws Exception {
        ManifestPlan current = plan(Collections.emptyList());
        ManifestAudit.read(ManifestXml.encode(current)).verify(current);
        ManifestPlan legacy = new ManifestPlan(current.appId,current.label,7,current.versionName,current.iconResourceId,current.backupResourceId,current.capabilities,ManifestPlan.Profile.V1_BEFORE_SAFE_AREA);
        ManifestAudit.read(ManifestXml.encode(legacy)).verify(legacy);
        assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(current)).verify(legacy));
        assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(legacy)).verify(current));
        assertEquals(7, current.nodes.size()); assertEquals(-1,current.nodes.get(0).parentIndex);
        assertEquals(4,current.nodes.get(6).parentIndex);
    }
    @Test public void all1048576OriginalCapabilitySelectionsRetainFourCurrentManifestShapes() throws Exception {
        assertEquals(23, CapabilityCatalog.NAMES.size());
        assertEquals(list("storage", "export", "share", "clipboard", "haptics", "device", "documents", "photos", "audio",
                "browser", "maps", "phone", "email", "sms", "contacts", "calendar", "database", "presentation", "sensors", "biometrics"),
                CapabilityCatalog.NAMES.subList(0, 20));
        byte[][] profiles = {
            ManifestXml.encode(plan(Collections.emptyList())), ManifestXml.encode(plan(list("biometrics"))),
            ManifestXml.encode(plan(list("documents"))), ManifestXml.encode(plan(list("biometrics", "documents")))
        };
        int checked = 0;
        // Exhaustive original capability domain; native surface combinations are covered below.
        for (int mask=0; mask<(1 << 20); mask++) {
            List<String> caps = new ArrayList<>();
            for (int bit=0; bit<20; bit++) if ((mask & (1<<bit)) != 0) caps.add(CapabilityCatalog.NAMES.get(bit));
            ManifestPlan p = plan(caps);
            boolean documents = caps.contains("documents") || caps.contains("browser") || caps.contains("maps") || caps.contains("phone") || caps.contains("email") || caps.contains("sms") || caps.contains("contacts") || caps.contains("calendar") || caps.contains("clipboard") || caps.contains("share") || caps.contains("export");
            boolean biometrics = caps.contains("biometrics");
            byte[] encoded = ManifestXml.encode(p);
            assertArrayEquals(profiles[(biometrics ? 1 : 0) + (documents ? 2 : 0)], encoded);
            ManifestAudit.Document decoded = ManifestAudit.read(encoded);
            decoded.verify(p);
            assertEquals((documents ? 1 : 0) + (biometrics ? 1 : 0), CapabilityCatalog.manifestContributions(caps).size());
            assertEquals(7 + (documents ? 3 : 0) + (biometrics ? 1 : 0), p.nodes.size());
            assertEquals(biometrics ? list(CapabilityCatalog.BIOMETRIC_PERMISSION) : Collections.emptyList(), p.permissions);
            assertTrue(p.features.isEmpty()); assertTrue(p.hosts.isEmpty());
            assertEquals(list(ManifestPlan.ACTIVITY), p.exportedComponents);
            assertEquals(24, decoded.attribute("manifest/uses-sdk", ManifestPlan.ANDROID, "minSdkVersion").value);
            assertEquals(documents ? 2 : 0, p.queries.size());
            if (biometrics) {
                ManifestPlan.Node permission = p.nodes.get(7);
                assertEquals("manifest/uses-permission", permission.path); assertEquals(0, permission.parentIndex);
                assertEquals(1, permission.attributes.size());
                ManifestPlan.Attribute name = permission.attributes.get(ManifestPlan.ANDROID + "|name");
                assertEquals(0x01010003, name.resourceId); assertEquals(3, name.type);
                assertEquals(CapabilityCatalog.BIOMETRIC_PERMISSION, name.value);
                assertThrows(IOException.class, () -> decoded.verify(plan(documents ? list("documents") : Collections.emptyList())));
            }
            if (documents) {
                assertEquals(new ArrayList<>(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES), p.queries);
                int queries = biometrics ? 8 : 7;
                assertEquals("manifest/queries", p.nodes.get(queries).path);
                assertEquals(queries, p.nodes.get(queries + 1).parentIndex); assertEquals(queries, p.nodes.get(queries + 2).parentIndex);
                assertThrows(IOException.class, () -> decoded.verify(plan(biometrics ? list("biometrics") : Collections.emptyList())));
            }
            checked++;
        }
        assertEquals(1048576, checked);
        for (CapabilityCatalog.Capability c : CapabilityCatalog.CAPABILITIES.values()) assertThrows(UnsupportedOperationException.class, () -> c.manifestNodes.add(ManifestNodes.feature("android.hardware.camera", false)));
        assertThrows(IllegalArgumentException.class, () -> plan(list("camera")));
    }
    @Test public void clipboardQueriesAreCurrentOnlyAndLegacyProfilesRemainExact() throws Exception {
        CapabilityCatalog.Capability clipboard = CapabilityCatalog.CAPABILITIES.get("clipboard");
        assertEquals(list(CapabilityCatalog.Method.CLIPBOARD_WRITE), clipboard.methods);
        assertEquals(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES, clipboard.queries);
        assertTrue(clipboard.permissions.isEmpty()); assertTrue(clipboard.components.isEmpty());
        assertEquals(1, clipboard.manifestNodes.size());
        for (List<String> caps : list(list("clipboard"), list("biometrics", "clipboard"), list("clipboard", "documents"))) {
            ManifestPlan current = plan(caps);
            ManifestPlan legacy = new ManifestPlan(current.appId, current.label, current.versionCode, current.versionName,
                    current.iconResourceId, current.backupResourceId, caps, ManifestPlan.Profile.V1_BEFORE_CLIPBOARD_BROKER);
            List<String> historicalContributions = new ArrayList<>(caps); historicalContributions.remove("clipboard");
            assertArrayEquals(ManifestXml.encode(plan(historicalContributions)), ManifestXml.encode(legacy));
            assertEquals(current.capabilities, legacy.capabilities);
            ManifestAudit.Document decoded = ManifestAudit.read(ManifestXml.encode(legacy));
            decoded.verify(legacy);
            assertEquals(16, decoded.attribute("manifest/application/activity", ManifestPlan.ANDROID, "windowSoftInputMode").value);
            if (!caps.contains("documents")) {
                assertThrows(IOException.class, () -> decoded.verify(current));
                assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(current)).verify(legacy));
            }
            List<ManifestNodes.Element> extraNodes = new ArrayList<>(legacy.root.children);
            extraNodes.add(ManifestNodes.feature("android.hardware.camera", false));
            ManifestNodes.Element extra = ManifestNodes.base("manifest", extraNodes,
                    legacy.root.attributes.toArray(new ManifestPlan.Attribute[0]));
            assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encodeTree(extra)).verify(legacy));
        }
        ManifestPlan current = plan(list("clipboard"));
        ManifestPlan ancient = new ManifestPlan(current.appId, current.label, current.versionCode, current.versionName,
                current.iconResourceId, current.backupResourceId, current.capabilities, ManifestPlan.Profile.V1_BEFORE_SAFE_AREA);
        assertTrue(ancient.queries.isEmpty()); assertEquals(7, ancient.nodes.size());
        ManifestAudit.read(ManifestXml.encode(ancient)).verify(ancient);
        assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(ancient)).verify(current));
    }
    @Test public void all32NativeSurfaceBasisSelectionsHaveOnlyExactPermissionsVisibilityAndCleartext() throws Exception {
        List<String> basis = list("biometrics", "documents", "network", "notifications", "speech");
        java.util.Set<String> shapes = new java.util.HashSet<>();
        for (int mask = 0; mask < 32; mask++) {
            List<String> caps = new ArrayList<>();
            for (int bit = 0; bit < basis.size(); bit++) if ((mask & (1 << bit)) != 0) caps.add(basis.get(bit));
            ManifestPlan selected = plan(caps);
            boolean network = caps.contains("network"), notifications = caps.contains("notifications"), speech = caps.contains("speech");
            boolean broker = caps.contains("documents") || network || notifications || speech;
            List<String> permissions = new ArrayList<>();
            if (network) permissions.add(CapabilityCatalog.INTERNET_PERMISSION);
            if (notifications) permissions.add(CapabilityCatalog.NOTIFICATION_PERMISSION);
            if (caps.contains("biometrics")) permissions.add(CapabilityCatalog.BIOMETRIC_PERMISSION);
            byte[] encoded = ManifestXml.encode(selected);
            ManifestAudit.Document decoded = ManifestAudit.read(encoded); decoded.verify(selected);
            ManifestAudit.verifyTree(encoded, selected.root);
            assertEquals(permissions, selected.permissions);
            assertEquals(broker ? new ArrayList<>(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES) : Collections.emptyList(), selected.queries);
            assertEquals(speech ? list(CapabilityCatalog.TTS_SERVICE_ACTION) : Collections.emptyList(), selected.queryActions);
            assertEquals(7 + permissions.size() + (broker ? 3 : 0) + (speech ? 2 : 0), selected.nodes.size());
            assertEquals(permissions.size() + (broker ? 1 : 0), CapabilityCatalog.manifestContributions(caps).size());
            assertEquals(broker ? 1L : 0L, decoded.nodes.stream().filter(n -> n.path.equals("manifest/queries")).count());
            ManifestAudit.Attribute cleartext = decoded.attribute("manifest/application", ManifestPlan.ANDROID, "usesCleartextTraffic");
            assertEquals(18, cleartext.type); assertEquals(network ? -1 : 0, cleartext.value);
            assertTrue(selected.features.isEmpty()); assertEquals(list(ManifestPlan.ACTIVITY), selected.exportedComponents);
            assertEquals(broker, CapabilityCatalog.requiresHostBroker(caps));
            shapes.add(java.util.Base64.getEncoder().encodeToString(encoded));
        }
        assertEquals(18, shapes.size());
    }
    @Test public void nativeCatalogAndBrokerRequirementAreClosedImmutableAndValidateTheWholeSelection() {
        assertEquals(list(CapabilityCatalog.Method.NETWORK_REQUEST, CapabilityCatalog.Method.NETWORK_PROBE,
                CapabilityCatalog.Method.NETWORK_CANCEL), CapabilityCatalog.CAPABILITIES.get("network").methods);
        assertEquals(list(CapabilityCatalog.Method.NOTIFICATIONS_STATUS, CapabilityCatalog.Method.NOTIFICATIONS_CHANNEL,
                CapabilityCatalog.Method.NOTIFICATIONS_SHOW, CapabilityCatalog.Method.NOTIFICATIONS_CANCEL,
                CapabilityCatalog.Method.NOTIFICATIONS_REQUEST_PERMISSION), CapabilityCatalog.CAPABILITIES.get("notifications").methods);
        assertEquals(list(CapabilityCatalog.Method.SPEECH_STATUS, CapabilityCatalog.Method.SPEECH_VOICES,
                CapabilityCatalog.Method.SPEECH_SPEAK, CapabilityCatalog.Method.SPEECH_STOP), CapabilityCatalog.CAPABILITIES.get("speech").methods);
        List<String> brokerCaps = list("documents", "browser", "maps", "phone", "email", "sms", "contacts", "calendar",
                "clipboard", "share", "export", "network", "notifications", "speech");
        for (CapabilityCatalog.Capability capability : CapabilityCatalog.CAPABILITIES.values()) {
            assertEquals(brokerCaps.contains(capability.name), CapabilityCatalog.requiresHostBroker(list(capability.name)));
            assertEquals(brokerCaps.contains(capability.name) ? CapabilityCatalog.DOCUMENT_BROKER_PACKAGES : Collections.emptySet(), capability.queries);
            assertEquals(capability.name.equals("speech") ? Collections.singleton(CapabilityCatalog.TTS_SERVICE_ACTION) : Collections.emptySet(), capability.queryActions);
            assertThrows(UnsupportedOperationException.class, () -> capability.queryActions.add("android.intent.action.VIEW"));
        }
        assertTrue(CapabilityCatalog.CAPABILITIES.get("speech").permissions.isEmpty());
        assertFalse(CapabilityCatalog.requiresHostBroker(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresHostBroker(list("network", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresHostBroker(list("speech", "speech")));
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresHostBroker(null));
        assertThrows(UnsupportedOperationException.class, () -> plan(list("speech")).queryActions.clear());
    }
    @Test public void historicalContractsDoNotAcquireNativeOrResidualBrokerDeclarations() throws Exception {
        for (List<String> caps : list(list("export"), list("share"), list("export", "share"),
                list("biometrics", "clipboard", "export", "share"), list("clipboard", "documents", "share"))) {
            ManifestPlan current = plan(caps);
            for (ManifestPlan.Profile profile : list(ManifestPlan.Profile.V2_BEFORE_NATIVE_OPERATIONS,
                    ManifestPlan.Profile.V1_BEFORE_CLIPBOARD_BROKER)) {
                List<String> historical = new ArrayList<>(caps); historical.remove("share"); historical.remove("export");
                if (profile == ManifestPlan.Profile.V1_BEFORE_CLIPBOARD_BROKER) historical.remove("clipboard");
                ManifestPlan legacy = new ManifestPlan(current.appId, current.label, current.versionCode, current.versionName,
                        current.iconResourceId, current.backupResourceId, caps, profile);
                assertArrayEquals(ManifestXml.encode(plan(historical)), ManifestXml.encode(legacy));
                ManifestAudit.read(ManifestXml.encode(legacy)).verify(legacy);
                assertTrue(legacy.queryActions.isEmpty());
                assertEquals(current.capabilities, legacy.capabilities);
            }
        }
        for (ManifestPlan.Profile profile : ManifestPlan.Profile.values()) if (profile != ManifestPlan.Profile.CURRENT)
            for (String cap : list("network", "notifications", "speech"))
                assertThrows(IllegalArgumentException.class, () -> new ManifestPlan("org.example.old", "old", 1, "1",
                        0x7f010000, 0x7f020000, list(cap), profile));
        ManifestPlan ancient = new ManifestPlan("org.example.old", "old", 1, "1", 0x7f010000, 0x7f020000,
                list("clipboard", "share", "export"), ManifestPlan.Profile.V1_BEFORE_SAFE_AREA);
        assertEquals(7, ancient.nodes.size()); assertTrue(ancient.permissions.isEmpty()); assertTrue(ancient.queries.isEmpty());
        ManifestAudit.read(ManifestXml.encode(ancient)).verify(ancient);
    }
    @Test public void speechVisibilityRejectsBroaderIntentsAndNetworkRequiresItsExactBoolean() throws Exception {
        ManifestPlan speech = plan(list("speech"));
        for (ManifestNodes.Element extra : list(ManifestNodes.queryIntent("android.intent.action.VIEW", null, null),
                ManifestNodes.queryIntent(CapabilityCatalog.TTS_SERVICE_ACTION, "text/plain", null))) {
            List<ManifestNodes.Element> queries = new ArrayList<>(CapabilityCatalog.DOCUMENT_QUERIES.children); queries.add(extra);
            List<ManifestNodes.Element> root = new ArrayList<>(speech.root.children); root.set(root.size() - 1, ManifestNodes.queries(queries));
            byte[] wrong = ManifestXml.encodeTree(ManifestNodes.base("manifest", root, speech.root.attributes.toArray(new ManifestPlan.Attribute[0])));
            assertThrows(IOException.class, () -> ManifestAudit.read(wrong).verify(speech));
        }
        ManifestPlan network = plan(list("network"));
        byte[] encoded = ManifestXml.encode(network); int nameIndex = -1;
        for (int p = 8; p < encoded.length; p += i32(encoded, p + 4)) if (u16(encoded, p) == 0x180)
            for (int a = p + 8; a < p + i32(encoded, p + 4); a += 4) if (i32(encoded, a) == 0x010104ec) nameIndex = (a - p - 8) / 4;
        assertTrue(nameIndex >= 0); boolean checked = false;
        for (int p = 8; p < encoded.length; p += i32(encoded, p + 4)) if (u16(encoded, p) == 0x102)
            for (int a = p + 36; a < p + i32(encoded, p + 4); a += 20) if (i32(encoded, a + 4) == nameIndex) {
                byte[] wrongValue = encoded.clone(); put(wrongValue, a + 16, 0);
                assertThrows(IOException.class, () -> ManifestAudit.read(wrongValue).verify(network));
                byte[] wrongType = encoded.clone(); wrongType[a + 15] = 16;
                assertThrows(IOException.class, () -> ManifestAudit.read(wrongType).verify(network)); checked = true;
            }
        assertTrue(checked);
    }
    @Test public void biometricsHasOnlyTheExactNormalPermissionAndFunctionalApi30Floor() throws Exception {
        CapabilityCatalog.Capability biometrics = CapabilityCatalog.CAPABILITIES.get("biometrics");
        assertNotNull(biometrics); assertEquals(30, biometrics.minimumApi);
        assertEquals(24, CapabilityCatalog.MIN_APP_API);
        assertEquals(1, CapabilityCatalog.SCHEMA_VERSION); assertEquals(2, CapabilityCatalog.SDK_VERSION);
        assertEquals(58, CapabilityCatalog.METHODS.size());
        assertEquals(list(CapabilityCatalog.Method.BIOMETRICS_AVAILABILITY,
                CapabilityCatalog.Method.BIOMETRICS_AUTHENTICATE, CapabilityCatalog.Method.BIOMETRICS_CANCEL), biometrics.methods);
        assertEquals(Collections.singleton(CapabilityCatalog.BIOMETRIC_PERMISSION), biometrics.permissions);
        assertTrue(biometrics.features.isEmpty()); assertTrue(biometrics.components.isEmpty());
        assertTrue(biometrics.intentFilters.isEmpty()); assertTrue(biometrics.queries.isEmpty());
        assertTrue(biometrics.metadata.isEmpty()); assertTrue(biometrics.resources.isEmpty());
        assertTrue(biometrics.dependencies.isEmpty()); assertTrue(biometrics.conflicts.isEmpty());
        assertEquals(1, biometrics.manifestNodes.size());
        assertEquals("uses-permission", biometrics.manifestNodes.get(0).name);
        for (CapabilityCatalog.Method method : biometrics.methods) {
            assertEquals("biometrics", method.capability); assertSame(method, CapabilityCatalog.METHODS.get(method.wireName));
        }
        assertThrows(UnsupportedOperationException.class, () -> biometrics.permissions.clear());
        assertThrows(UnsupportedOperationException.class, () -> biometrics.methods.clear());
        ManifestPlan current = plan(list("biometrics"));
        assertThrows(UnsupportedOperationException.class, () -> current.permissions.clear());
        assertThrows(IllegalArgumentException.class, () -> new ManifestPlan(current.appId, current.label,
                current.versionCode, current.versionName, current.iconResourceId, current.backupResourceId,
                current.capabilities, ManifestPlan.Profile.V1_BEFORE_SAFE_AREA));
        for (CapabilityCatalog.Capability capability : CapabilityCatalog.CAPABILITIES.values()) if (capability != biometrics) {
            assertEquals(24, capability.minimumApi);
            if (!capability.name.equals("network") && !capability.name.equals("notifications")) assertTrue(capability.permissions.isEmpty());
        }
    }
    @Test public void biometricPermissionConstructionRejectsAllBroaderOrDifferentProductionDeclarations() throws Exception {
        ManifestPlan selected = plan(list("biometrics"));
        for (ManifestNodes.Element permission : list(
                ManifestNodes.permission("android.permission.INTERNET", ManifestNodes.PermissionVariant.STANDARD, null),
                ManifestNodes.permission(CapabilityCatalog.BIOMETRIC_PERMISSION, ManifestNodes.PermissionVariant.SDK_23, null),
                ManifestNodes.permission(CapabilityCatalog.BIOMETRIC_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, 30))) {
            List<ManifestNodes.Element> children = new ArrayList<>(selected.root.children);
            children.set(children.size() - 1, permission);
            ManifestNodes.Element changed = ManifestNodes.base("manifest", children, selected.root.attributes.toArray(new ManifestPlan.Attribute[0]));
            ManifestAudit.Document decoded = ManifestAudit.read(ManifestXml.encodeTree(changed));
            assertThrows(IOException.class, () -> decoded.verify(selected));
        }
        ManifestAudit.Document absent = ManifestAudit.read(ManifestXml.encode(plan(Collections.emptyList())));
        assertThrows(IOException.class, () -> absent.verify(selected));
    }
    @Test public void databaseIsStandaloneAndAddsNoManifestSurface() throws Exception {
        CapabilityCatalog.Capability database = CapabilityCatalog.CAPABILITIES.get("database");
        assertNotNull(database);
        assertEquals(1, CapabilityCatalog.SCHEMA_VERSION); assertEquals(2, CapabilityCatalog.SDK_VERSION);
        assertEquals(list(CapabilityCatalog.Method.DATABASE_INFO, CapabilityCatalog.Method.DATABASE_MIGRATE,
                CapabilityCatalog.Method.DATABASE_TRANSACT, CapabilityCatalog.Method.DATABASE_SELECT,
                CapabilityCatalog.Method.DATABASE_BACKUP_EXPORT, CapabilityCatalog.Method.DATABASE_BACKUP_IMPORT,
                CapabilityCatalog.Method.DATABASE_CLOSE, CapabilityCatalog.Method.DATABASE_CANCEL), database.methods);
        assertTrue(database.permissions.isEmpty()); assertTrue(database.features.isEmpty());
        assertTrue(database.components.isEmpty()); assertTrue(database.intentFilters.isEmpty());
        assertTrue(database.queries.isEmpty()); assertTrue(database.metadata.isEmpty());
        assertTrue(database.resources.isEmpty()); assertTrue(database.dependencies.isEmpty());
        assertTrue(database.conflicts.isEmpty()); assertTrue(database.manifestNodes.isEmpty());
        ManifestPlan standalone = plan(list("database"));
        assertEquals(list("database"), standalone.capabilities);
        assertTrue(standalone.hosts.isEmpty()); assertTrue(standalone.queries.isEmpty());
        assertArrayEquals(ManifestXml.encode(plan(Collections.emptyList())), ManifestXml.encode(standalone));
        for (CapabilityCatalog.Method method : database.methods) {
            assertEquals("database", method.capability);
            assertSame(method, CapabilityCatalog.METHODS.get(method.wireName));
        }
        assertThrows(UnsupportedOperationException.class, () -> database.methods.clear());
    }
    @Test public void repeatedTypedNodesHaveIndependentParentsAndCanonicalTypes() throws Exception {
        ManifestNodes.Element root = fixture(); byte[] bytes = ManifestXml.encodeTree(root);
        ManifestAudit.verifyTree(bytes, root); ManifestAudit.Document d = ManifestAudit.read(bytes);
        int permissions=0, features=0, privateComponents=0, filters=0, metadata=0;
        for (ManifestAudit.Node n : d.nodes) {
            if (n.path.startsWith("manifest/uses-permission")) permissions++;
            if (n.path.equals("manifest/uses-feature")) features++;
            if (n.path.endsWith("intent-filter")) filters++;
            if (n.path.endsWith("meta-data")) metadata++;
            if (n.attributes.containsKey(ManifestPlan.ANDROID+"|exported") && n.attributes.get(ManifestPlan.ANDROID+"|exported").value.equals(0)) privateComponents++;
            if (n.parentIndex >= 0) assertTrue(n.path.startsWith(d.nodes.get(n.parentIndex).path + "/"));
        }
        assertEquals(3,permissions); assertEquals(2,features); assertEquals(4,privateComponents); assertEquals(4,filters); assertEquals(5,metadata);
        assertThrows(IOException.class, () -> d.attribute("manifest/application/activity",ManifestPlan.ANDROID,"name"));
        assertThrows(IOException.class, () -> d.verify(plan(Collections.emptyList())));
        assertArrayEquals(bytes,ManifestXml.encodeTree(fixture()));
        String dir = System.getProperty("jarvys.factory.evidenceDir");
        if (dir != null) {
            File out = new File(dir,"typed-manifest"); assertTrue(out.mkdirs() || out.isDirectory());
            Files.write(new File(out,"AndroidManifest.xml").toPath(),bytes);
            Files.write(new File(out,"base-manifest.xml").toPath(),ManifestXml.encode(plan(Collections.emptyList())));
        }
    }
    @Test public void sortingAndDefensiveCopiesDoNotDependOnInputOrder() throws Exception {
        List<ManifestNodes.Element> values = new ArrayList<>(list(ManifestNodes.queryPackage("org.example.z"),ManifestNodes.queryPackage("org.example.a")));
        ManifestNodes.Element a = ManifestNodes.queries(values); Collections.reverse(values); ManifestNodes.Element b = ManifestNodes.queries(values); values.clear();
        assertArrayEquals(ManifestXml.encodeTree(a),ManifestXml.encodeTree(b)); assertEquals(2,a.children.size());
        assertThrows(UnsupportedOperationException.class,()->a.children.clear());
        assertThrows(UnsupportedOperationException.class,()->a.children.get(0).attributes.clear());
    }
    @Test public void duplicateAndConflictingDeclarationsAreRejected() {
        ManifestNodes.Element p = ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.STANDARD,null);
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.ordered(list(p,p)));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.ordered(list(p,ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.SDK_23,35))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(list(ManifestNodes.queryPackage("org.example.a"),ManifestNodes.queryPackage("org.example.a"))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service",list(ManifestNodes.metadataInt("org.example.key",1),ManifestNodes.metadataString("org.example.key","x"))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.intentFilter(list(ManifestNodes.action("android.intent.action.SEND"),ManifestNodes.action("android.intent.action.SEND"))));
    }
    @Test public void applicationRejectsSharedAuthoritiesAndBaseComponentNameCollisions() {
        ManifestNodes.Element first=ManifestNodes.privateProvider("org.example.First","org.example.app","files",true,Collections.emptyList());
        ManifestNodes.Element second=ManifestNodes.privateProvider("org.example.Second","org.example.app","files",false,Collections.emptyList());
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(first,second)));
        ManifestNodes.Element launcher=plan(Collections.emptyList()).root.children.get(1).children.get(0);
        ManifestNodes.Element collision=ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,ManifestPlan.ACTIVITY,Collections.emptyList());
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(launcher,collision)));
    }
    @Test public void hostileNamesUnicodeBoundsAndChildKindsFailClosed() {
        for (String value : list(null,"", "android.permission.CAMERA;INTERNET", "android.permission.CAMERA/../X", "evil.permission.X", "android.permission.X\n"))
            assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission(value,ManifestNodes.PermissionVariant.STANDARD,null));
        for (String value : list(".Private", "org.example.A/B", "org.example.$Proxy", "org.example.X\uD800"))
            assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.ACTIVITY,value,Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.metadataString("org.example.label","bad\uDC00"));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.feature("android.permission.CAMERA",true));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission("android.permission.CAMERA",null,null));
        for (int api : new int[]{-1,0,23,37,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.STANDARD,api));
        for (int resource : new int[]{0,0x01010000,0x7f000000,0x80010000}) assertThrows(IllegalArgumentException.class,()->ManifestNodes.metadataResource("org.example.res",resource));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service",list(ManifestNodes.permission("android.permission.INTERNET",ManifestNodes.PermissionVariant.STANDARD,null))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(list(ManifestNodes.feature("android.hardware.camera",true))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.intentFilter(list(ManifestNodes.category("android.intent.category.DEFAULT"))));
    }
    @Test public void authoritiesAreDerivedAndIntentDataCannotInjectUrisOrPackages() throws Exception {
        ManifestNodes.Element first = ManifestNodes.privateProvider("org.example.Provider","org.example.one","files",false,Collections.emptyList());
        ManifestNodes.Element second = ManifestNodes.privateProvider("org.example.Provider","org.example.two","files",false,Collections.emptyList());
        assertEquals("org.example.one.files",ManifestAudit.read(ManifestXml.encodeTree(first)).attribute("provider",ManifestPlan.ANDROID,"authorities").value);
        assertEquals("org.example.two.files",ManifestAudit.read(ManifestXml.encodeTree(second)).attribute("provider",ManifestPlan.ANDROID,"authorities").value);
        for(String suffix:list("shared.authority","../files","x;y","",null)) assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateProvider("org.example.Provider","org.example.one",suffix,true,Collections.emptyList()));
        for(String scheme:list("intent","file","javascript","https://host","HTTPS")) assertThrows(IllegalArgumentException.class,()->ManifestNodes.data(null,scheme));
        for(String mime:list("text/plain; charset=utf-8","text/../plain","*/plain","text/plain\n")) assertThrows(IllegalArgumentException.class,()->ManifestNodes.data(mime,null));
    }
    @Test public void attributeTypeResourceRawNamespaceAndValueMutationCannotMatchConstruction() throws Exception {
        byte[] good = ManifestXml.encodeTree(fixture()); int tested=0;
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            if(u16(good,p)==0x102) for(int a=p+36;a<p+i32(good,p+4);a+=20) {
                final int position=a;
                for(int field:new int[]{0,4,8,16}) { byte[] bad=good.clone(); put(bad,position+field,0x7fffffff); assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); }
                byte[] bad=good.clone();bad[a+15]=(byte)127;assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); tested++;
            }
            if(u16(good,p)==0x180) for(int a=p+8;a<p+i32(good,p+4);a+=4) { byte[] bad=good.clone();put(bad,a,i32(good,a)^1);assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); }
        }
        assertTrue(tested>50);
    }
    @Test public void movingACompleteChildBetweenRepeatedPathsChangesItsParentAndFailsAudit() throws Exception {
        byte[] good=ManifestXml.encodeTree(fixture()); ManifestAudit.Document original=ManifestAudit.read(good);
        int source=-1,target=-1;
        for(int i=0;i<original.nodes.size();i++) {
            ManifestAudit.Node n=original.nodes.get(i);
            ManifestAudit.Attribute name=n.attributes.get(ManifestPlan.ANDROID+"|name");
            if(name!=null && name.value.equals("android.intent.category.LAUNCHER")) source=i;
            if(n.path.endsWith("intent-filter") && n.parentIndex>=0) {
                ManifestAudit.Attribute component=original.nodes.get(n.parentIndex).attributes.get(ManifestPlan.ANDROID+"|name");
                if(target<0 && component!=null && component.value.equals("org.example.PrivateActivity")) target=i;
            }
        }
        assertTrue(source>=0 && target>source);
        int start=-1,end=-1,destination=-1,index=0;List<Integer> stack=new ArrayList<>();
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            if(u16(good,p)==0x102) {if(index==source)start=p;stack.add(index++);}
            if(u16(good,p)==0x103) {
                int closing=stack.remove(stack.size()-1);
                if(closing==source)end=p+i32(good,p+4);
                if(closing==target)destination=p;
            }
        }
        assertTrue(start>=0 && end>start && destination>end);
        java.io.ByteArrayOutputStream moved=new java.io.ByteArrayOutputStream();
        moved.write(good,0,start);moved.write(good,end,destination-end);moved.write(good,start,end-start);moved.write(good,destination,good.length-destination);
        byte[] changed=moved.toByteArray();assertEquals(good.length,changed.length);
        ManifestAudit.Document actual=ManifestAudit.read(changed);boolean found=false;
        for(ManifestAudit.Node n:actual.nodes) {
            ManifestAudit.Attribute name=n.attributes.get(ManifestPlan.ANDROID+"|name");
            if(name!=null && name.value.equals("android.intent.category.LAUNCHER")) {
                assertEquals(original.nodes.get(source).path,n.path);
                assertNotEquals(original.nodes.get(source).parentIndex,n.parentIndex);found=true;
            }
        }
        assertTrue(found);assertThrows(IOException.class,()->ManifestAudit.verifyTree(changed,fixture()));
    }
    @Test public void truncationSpecialIndexesAndOversizedChunksAreRejected() throws Exception {
        byte[] good=ManifestXml.encodeTree(fixture());
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            byte[] shortBytes=Arrays.copyOf(good,p);put(shortBytes,4,shortBytes.length);assertThrows(IOException.class,()->ManifestAudit.read(shortBytes));
            byte[] oversized=good.clone();put(oversized,p+4,0x7ffffffc);assertThrows(IOException.class,()->ManifestAudit.read(oversized));
            if(u16(good,p)==0x102) { byte[] bad=good.clone();bad[p+30]=1;assertThrows(IOException.class,()->ManifestAudit.read(bad)); }
        }
    }
    @Test public void treeLimitsAreEnforcedBeforeWriting() throws Exception {
        List<ManifestNodes.Element> many=new ArrayList<>();for(int i=0;i<33;i++)many.add(ManifestNodes.queryPackage("org.example.a"+i));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(many));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(plan(Collections.emptyList()).root)));
        List<ManifestNodes.Element> components=new ArrayList<>();
        for(int i=0;i<30;i++) {
            List<ManifestNodes.Element> metadata=new ArrayList<>();
            for(int j=0;j<5;j++)metadata.add(ManifestNodes.metadataInt("org.example.k"+j,j));
            components.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service"+i,metadata));
        }
        ManifestNodes.Element large=ManifestNodes.base("application",components);
        assertThrows(IOException.class,()->ManifestXml.encodeTree(large));
        assertThrows(IOException.class,()->ManifestXml.encodeTree(null));
    }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static int i32(byte[] b,int p) { return u16(b,p)|(u16(b,p+2)<<16); }
    private static void put(byte[] b,int p,int value) { for(int i=0;i<4;i++) b[p+i]=(byte)(value>>>(8*i)); }

    @Test public void presentationIsStandaloneImmutableAndAddsNoManifestSurface() throws Exception {
        CapabilityCatalog.Capability presentation = CapabilityCatalog.CAPABILITIES.get("presentation");
        assertNotNull(presentation);
        assertEquals(1, CapabilityCatalog.SCHEMA_VERSION); assertEquals(2, CapabilityCatalog.SDK_VERSION);
        assertEquals(list(CapabilityCatalog.Method.PRESENTATION_GET, CapabilityCatalog.Method.PRESENTATION_SET,
                CapabilityCatalog.Method.PRESENTATION_RESET), presentation.methods);
        assertTrue(presentation.permissions.isEmpty()); assertTrue(presentation.features.isEmpty());
        assertTrue(presentation.components.isEmpty()); assertTrue(presentation.intentFilters.isEmpty());
        assertTrue(presentation.queries.isEmpty()); assertTrue(presentation.metadata.isEmpty());
        assertTrue(presentation.resources.isEmpty()); assertTrue(presentation.dependencies.isEmpty());
        assertTrue(presentation.conflicts.isEmpty()); assertTrue(presentation.manifestNodes.isEmpty());
        ManifestPlan standalone = plan(list("presentation"));
        assertEquals(list("presentation"), standalone.capabilities);
        assertTrue(standalone.hosts.isEmpty()); assertTrue(standalone.queries.isEmpty());
        assertArrayEquals(ManifestXml.encode(plan(Collections.emptyList())), ManifestXml.encode(standalone));
        for (CapabilityCatalog.Method method : presentation.methods) {
            assertEquals("presentation", method.capability);
            assertSame(method, CapabilityCatalog.METHODS.get(method.wireName));
        }
        assertThrows(UnsupportedOperationException.class, () -> presentation.methods.clear());
    }

    @Test public void sensorsAreStandaloneImmutableAndAddNoPermissionsFeaturesOrQueries() throws Exception {
        CapabilityCatalog.Capability sensors=CapabilityCatalog.CAPABILITIES.get("sensors");assertNotNull(sensors);
        assertEquals(list(CapabilityCatalog.Method.SENSORS_LIST,CapabilityCatalog.Method.SENSORS_CAPTURE,CapabilityCatalog.Method.SENSORS_CANCEL),sensors.methods);
        assertTrue(sensors.permissions.isEmpty());assertTrue(sensors.features.isEmpty());assertTrue(sensors.components.isEmpty());assertTrue(sensors.queries.isEmpty());
        assertTrue(sensors.intentFilters.isEmpty());assertTrue(sensors.metadata.isEmpty());assertTrue(sensors.resources.isEmpty());assertTrue(sensors.dependencies.isEmpty());assertTrue(sensors.conflicts.isEmpty());assertTrue(sensors.manifestNodes.isEmpty());
        ManifestPlan standalone=plan(list("sensors"));assertEquals(list("sensors"),standalone.capabilities);assertArrayEquals(ManifestXml.encode(plan(Collections.emptyList())),ManifestXml.encode(standalone));
        for(CapabilityCatalog.Method method:sensors.methods){assertEquals("sensors",method.capability);assertSame(method,CapabilityCatalog.METHODS.get(method.wireName));}
        assertThrows(UnsupportedOperationException.class,()->sensors.methods.clear());
    }

    @Test public void storageBackupExtendsExistingCapabilityWithoutManifestAuthority() {
        CapabilityCatalog.Capability storage = CapabilityCatalog.CAPABILITIES.get("storage");
        assertEquals(23, CapabilityCatalog.NAMES.size());
        assertEquals(58, CapabilityCatalog.METHODS.size());
        assertEquals(list(CapabilityCatalog.Method.STORAGE_GET, CapabilityCatalog.Method.STORAGE_SET,
                CapabilityCatalog.Method.STORAGE_REMOVE, CapabilityCatalog.Method.STORAGE_LIST,
                CapabilityCatalog.Method.STORAGE_BACKUP_EXPORT, CapabilityCatalog.Method.STORAGE_BACKUP_IMPORT), storage.methods);
        assertTrue(storage.permissions.isEmpty()); assertTrue(storage.manifestNodes.isEmpty());
        assertTrue(storage.dependencies.isEmpty()); assertTrue(storage.queries.isEmpty());
        assertFalse(CapabilityCatalog.METHODS.containsKey("storage.cancel"));
        for (CapabilityCatalog.Method method : storage.methods) {
            assertEquals("storage", method.capability); assertSame(method, CapabilityCatalog.METHODS.get(method.wireName));
        }
    }

}

package com.jarvys.factory.contract;

import org.junit.Test;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Host test source, like ManifestNodesTest: no dependencies are added to factory-contract. */
public final class ResourceBudgetsTest {
    private static final long MIB = 1024L * 1024L, GIB = MIB * 1024L;

    private static Map<String, Object> declaration(String section, String field, Object value) {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("version", 1);
        Map<String, Object> members = new LinkedHashMap<>(); members.put(field, value);
        root.put(section, members); return root;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String section) {
        return (Map<String, Object>) root.get(section);
    }
    private static void rejected(Map<?, ?> input) {
        assertThrows(IllegalArgumentException.class, () -> ResourceBudgets.parse(input));
    }

    @Test public void legacyStorageAndDatabaseValuesAreExact() {
        ResourceBudgets value = ResourceBudgets.legacy();
        assertEquals(MIB, value.storage.totalBytes); assertEquals(256, value.storage.entries);
        assertEquals(65536, value.storage.valueBytes);
        assertEquals(16 * MIB, value.database.mainFileBytes); assertEquals(10000, value.database.rows);
        assertEquals(8, value.database.tables); assertEquals(16, value.database.columnsPerTable);
        assertEquals(4096, value.database.cellBytes); assertEquals(16384, value.database.rowBytes);
        assertEquals(100, value.database.selectPageRows); assertEquals(64, value.database.batchOperations);
    }
    @Test public void legacyDocumentAudioSensorAndPackagingValuesAreExact() {
        ResourceBudgets value = ResourceBudgets.legacy();
        assertEquals(4, value.documents.openHandles); assertEquals(16 * MIB, value.documents.handleBytes);
        assertEquals(32 * MIB, value.documents.sessionBytes); assertEquals(300000, value.documents.leaseMs);
        assertEquals(6 * MIB, value.audio.sourceBytes); assertEquals(30000, value.audio.durationMs);
        assertEquals(5000, value.sensors.captureDurationMs); assertEquals(50, value.sensors.captureSamples);
        assertEquals(50, value.sensors.bufferedSamples); assertEquals(100000, value.sensors.minimumIntervalUs);
        assertEquals(128, value.packaging.sourceFiles); assertEquals(8 * MIB, value.packaging.totalSourceBytes);
        assertEquals(MIB, value.packaging.singleSourceBytes); assertEquals(32 * MIB, value.packaging.apkBytes);
    }
    @Test public void explicitVersionOnlyResolvesToLegacyAndSerializesCompletely() {
        ResourceBudgets value = ResourceBudgets.parse(Collections.singletonMap("version", 1));
        assertEquals(ResourceBudgets.legacy(), value);
        assertEquals(Arrays.asList("version", "storage", "database", "documents", "audio", "sensors", "packaging", "secrets", "notifications", "incomingShare"),
                new ArrayList<>(value.toMap().keySet()));
        int count = 0;
        for (Object section : value.toMap().values()) if (section instanceof Map) count += ((Map<?, ?>) section).size();
        assertEquals(36, count);
    }
    @Test public void partialOverridesDoNotChangeOtherDefaultsOrFollowInputMutations() {
        Map<String, Object> input = declaration("database", "mainFileBytes", 256 * MIB);
        ResourceBudgets value = ResourceBudgets.parse(input);
        section(input, "database").put("mainFileBytes", 4096);
        assertEquals(256 * MIB, value.database.mainFileBytes);
        assertEquals(ResourceBudgets.legacy().storage.totalBytes, value.storage.totalBytes);
        assertEquals(10000, value.database.rows);
    }
    @Test public void inputOrderCannotChangeCanonicalRepresentationOrEquality() {
        Map<String, Object> first = declaration("database", "mainFileBytes", 256 * MIB);
        section(first, "database").put("rows", 50000);
        Map<String, Object> second = declaration("database", "rows", 50000L);
        section(second, "database").put("mainFileBytes", 256 * MIB);
        ResourceBudgets left = ResourceBudgets.parse(first), right = ResourceBudgets.parse(second);
        assertEquals(left, right); assertEquals(left.hashCode(), right.hashCode());
        assertEquals(left.canonicalText(), right.canonicalText());
        assertTrue(left.canonicalText().startsWith("jarvys-resource-budgets-v1\nstorage.totalBytes="));
        assertEquals(left, ResourceBudgets.parse(left.toMap()));
    }
    @Test public void canonicalMapsAndImplementationLimitsAreDeeplyImmutable() {
        ResourceBudgets value = ResourceBudgets.legacy();
        assertThrows(UnsupportedOperationException.class, () -> value.toMap().put("version", 2));
        assertThrows(UnsupportedOperationException.class, () -> section(value.toMap(), "storage").put("totalBytes", 1));
        assertThrows(UnsupportedOperationException.class, () -> ResourceBudgets.implementationLimits().put("documentChunkBytes", 1L));
    }
    @Test public void valueEqualitySupportsDistinctInstancesAndHashCollections() {
        ResourceBudgets original = ResourceBudgets.legacy();
        ResourceBudgets roundTrip = ResourceBudgets.parse(original.toMap());
        ResourceBudgets changed = ResourceBudgets.parse(declaration("database", "rows", 10001));
        assertNotSame(original, roundTrip);
        assertEquals(original, original);
        assertEquals(original, roundTrip);
        assertEquals(roundTrip, original);
        assertEquals(original.hashCode(), roundTrip.hashCode());
        assertNotEquals(original, changed);
        assertNotEquals(changed, original);
        assertFalse(original.equals(null));
        assertFalse(original.equals(original.toMap()));
        HashSet<ResourceBudgets> values = new HashSet<>();
        values.add(original); values.add(roundTrip); values.add(changed);
        assertEquals(2, values.size());
        assertTrue(values.contains(ResourceBudgets.parse(changed.toMap())));
    }
    @Test public void versionAndSectionsMustUseExactKnownTypes() {
        rejected(null); rejected(Collections.emptyMap());
        for (Object version : Arrays.asList(1L, 1.0, "1", 2, true)) rejected(Collections.singletonMap("version", version));
        Map<String, Object> root = new LinkedHashMap<>(); root.put("version", 1); root.put("network", Collections.emptyMap()); rejected(root);
        root.remove("network"); root.put("storage", null); rejected(root);
        root.put("storage", 1024); rejected(root);
        rejected(declaration("storage", "unlimited", true));
    }
    @Test public void valuesAreExactIntegersNotCoercedNumbersOrStrings() {
        for (Object value : Arrays.asList(null, "256", 256.0, 256.0f, true, BigInteger.valueOf(256), (short) 256))
            rejected(declaration("storage", "entries", value));
        assertEquals(256, ResourceBudgets.parse(declaration("storage", "entries", 256L)).storage.entries);
    }
    @Test public void negativeZeroOverflowAndOverCeilingValuesAreRejected() {
        for (long value : new long[] {Long.MIN_VALUE, -1, 0, 257, Long.MAX_VALUE})
            rejected(declaration("storage", "entries", value));
        rejected(declaration("documents", "handleBytes", 4 * GIB + 1));
        rejected(declaration("database", "mainFileBytes", GIB + 4096));
        rejected(declaration("packaging", "apkBytes", 32 * MIB + 1));
    }
    @Test public void databasePagesAreWholeAndCellsFitRows() {
        rejected(declaration("database", "mainFileBytes", 65537));
        rejected(declaration("database", "rowBytes", 1024)); // The retained default cell is 4096.
        Map<String, Object> valid = declaration("database", "rowBytes", 1024);
        section(valid, "database").put("cellBytes", 512);
        assertEquals(512, ResourceBudgets.parse(valid).database.cellBytes);
    }
    @Test public void aggregateBudgetsCannotBeSmallerThanSingleItemBounds() {
        rejected(declaration("storage", "totalBytes", 65535));
        rejected(declaration("documents", "sessionBytes", MIB));
        rejected(declaration("packaging", "totalSourceBytes", 1024));
        rejected(declaration("packaging", "apkBytes", MIB));
        rejected(declaration("sensors", "bufferedSamples", 49));
    }
    @Test public void sensorSubscriptionBudgetAcceptsRawMicrosecondsAndFastest() {
        assertEquals(100001, ResourceBudgets.parse(declaration("sensors", "minimumIntervalUs", 100001)).sensors.minimumIntervalUs);
        assertEquals(0, ResourceBudgets.parse(declaration("sensors", "minimumIntervalUs", 0)).sensors.minimumIntervalUs);
        rejected(declaration("sensors", "minimumIntervalUs", -1));
        assertEquals(200000, ResourceBudgets.parse(declaration("sensors", "minimumIntervalUs", 200000)).sensors.minimumIntervalUs);
    }
    @Test public void streamingTotalsExceedSignedIntWithoutEnlargingChunks() {
        ResourceBudgets value = ResourceBudgets.maxima();
        assertEquals(4 * GIB, value.documents.handleBytes); assertEquals(16 * GIB, value.documents.sessionBytes);
        assertEquals(GIB, value.database.mainFileBytes); assertEquals(1000000, value.database.rows);
        assertEquals(32768, ResourceBudgets.DOCUMENT_CHUNK_BYTES);
        assertEquals(value, ResourceBudgets.parse(value.toMap()));
    }
    @Test public void materializedPayloadCapsStayDistinctFromStreamTotals() {
        ResourceBudgets value = ResourceBudgets.maxima();
        assertEquals(MIB, value.storage.totalBytes); assertEquals(6 * MIB, value.audio.sourceBytes);
        assertEquals(8 * MIB, value.packaging.totalSourceBytes); assertEquals(32 * MIB, value.packaging.apkBytes);
        assertEquals(300000, value.audio.durationMs); assertEquals(300000, value.sensors.captureDurationMs);
        assertEquals(50, value.sensors.bufferedSamples);
        assertEquals(Long.valueOf(6 * MIB), ResourceBudgets.implementationLimits().get("wholeAudioPayloadBytes"));
    }
    @Test public void maximumClosedSchemaHasRoomWithinItsIndependentByteCeiling() {
        ResourceBudgets.Database value = ResourceBudgets.maxima().database;
        // Conservative ASCII upper bound: 32-char identifiers, punctuation and the longest
        // {type,nullable} definition. Actual schema validation still checks serialized bytes.
        long maximumEncoded = 2L + value.tables * (36L + value.columnsPerTable * 80L);
        assertTrue(maximumEncoded < ResourceBudgets.DATABASE_SCHEMA_BYTES);
        assertTrue(value.rowBytes + 1024 < ResourceBudgets.DATABASE_RESPONSE_BYTES);
    }
    @Test public void defaultsNeverDependOnSuppliedSectionsOrMutableGlobalData() {
        List<String> before = new ArrayList<>(ResourceBudgets.legacy().toMap().keySet());
        ResourceBudgets.parse(declaration("audio", "durationMs", 300000));
        assertEquals(30000, ResourceBudgets.legacy().audio.durationMs);
        assertEquals(before, new ArrayList<>(ResourceBudgets.legacy().toMap().keySet()));
    }
    @Test public void nativeServiceBudgetsResolveDefaultsAndValidateAggregateCapacity() {
        ResourceBudgets value=ResourceBudgets.legacy();
        assertEquals(32,value.secrets.entries); assertEquals(8192,value.secrets.valueBytes); assertEquals(512*1024,value.secrets.vaultBytes);
        assertEquals(128,value.notifications.schedules); assertEquals(256,value.notifications.inboxEvents); assertEquals(1024,value.notifications.tombstones);
        assertEquals(16,value.incomingShare.queuedEnvelopes); assertEquals(16,value.incomingShare.attachmentsPerEnvelope); assertEquals(1024*1024,value.incomingShare.queuedBytes);
        rejected(declaration("secrets","vaultBytes",8193));
        rejected(declaration("secrets","entries",257));
        rejected(declaration("notifications","schedules",1025));
        rejected(declaration("incomingShare","queuedBytes",1024));
        assertEquals(32,ResourceBudgets.maxima().incomingShare.attachmentsPerEnvelope);
    }

}

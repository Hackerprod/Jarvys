package com.jarvys.agent.connectors;

import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;
import android.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Staged, unwired adapter. Its single owner must serialize bootstrap and coordinator operations. */
final class SharedPreferencesConnectionStorage implements Storage {
    static final String PREFIX = "connection_v1_";
    static final String FORMAT = PREFIX + "format", EPOCH = PREFIX + "epoch", HEADER_PROOF = PREFIX + "header_proof";
    static final String HIGH_WATER = PREFIX + "high_water", ACK = PREFIX + "ack_generation";
    static final String CURRENT = PREFIX + "current_", CANDIDATE = PREFIX + "candidate_";
    static final String PROOF = PREFIX + "proof_", PENDING = PREFIX + "pending_", BASELINE = PREFIX + "baseline_";
    static final String REVISION_FLOOR = PREFIX + "revision_floor_", INTENT_FLOOR = PREFIX + "intent_floor_";
    private final ConnectionPreferenceBackend backend;
    private final Object ownerIdentity = new Object();
    private long snapshotVersion;
    private DestinationBinding binding;
    private final Map<String, Long> reservedRevisions = new LinkedHashMap<>(), intentHighWater = new LinkedHashMap<>();
    private final Map<String, Mutation> current = new LinkedHashMap<>(), proofs = new LinkedHashMap<>();
    private final Map<String, Mutation> candidates = new LinkedHashMap<>(), initialConfirmed = new HashMap<>();
    private final Map<String, Event> pending = new LinkedHashMap<>();
    private Map<String, ?> sourceSnapshot;
    private String epoch, lastAck;
    private long highWater;
    private boolean loaded;
    // Survives load retries: a proof visible in SharedPreferences memory is not a new disk observation.
    private String metadataAttemptEpoch;
    private boolean metadataInitialConfirmed, metadataProofConfirmed;

    SharedPreferencesConnectionStorage(ConnectionPreferenceBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }
    @Override public synchronized void bindDestination(DestinationBinding binding) {
        this.binding = binding;
        snapshotVersion = Math.addExact(snapshotVersion, 1L);
    }
    @Override public synchronized RetentionSnapshot retentionSnapshot() {
        requireLoaded();
        return new RetentionSnapshot(image(true), ownerIdentity, snapshotVersion);
    }
    @Override public synchronized StoredImage load() throws StorageUnconfirmedException {
        if (loaded) throw new IllegalStateException("Connection storage already loaded");
        // Failed parsing never contaminates a later retry with records absent from its new snapshot.
        current.clear(); proofs.clear(); candidates.clear(); initialConfirmed.clear(); pending.clear();
        reservedRevisions.clear(); intentHighWater.clear();
        sourceSnapshot = null; highWater = 0; lastAck = null;
        Map<String, ?> raw = backend.snapshot();
        // A proof-only retry must still refer to the header this owner actually committed.
        // External reset/removal cannot inherit that in-memory capability.
        if (metadataInitialConfirmed && (!Long.valueOf(1L).equals(raw.get(FORMAT))
                || !Objects.equals(metadataAttemptEpoch, raw.get(EPOCH))
                || (metadataProofConfirmed && !Objects.equals(metadataAttemptEpoch, raw.get(HEADER_PROOF)))))
            throw invalid();
        if (!raw.containsKey(FORMAT)) {
            for (String key : raw.keySet()) if (key.startsWith(PREFIX)) throw invalid();
            epoch = metadataAttemptEpoch == null ? UUID.randomUUID().toString() : metadataAttemptEpoch;
        } else {
            if (!Long.valueOf(1L).equals(raw.get(FORMAT))) throw invalid();
            epoch = string(raw.get(EPOCH));
            if (raw.containsKey(HEADER_PROOF) && !epoch.equals(string(raw.get(HEADER_PROOF)))) throw invalid();
        }
        if (metadataAttemptEpoch == null) {
            metadataAttemptEpoch = epoch;
            // Only the first startup snapshot can contain a proof from a prior process.
            metadataInitialConfirmed = raw.containsKey(HEADER_PROOF);
            metadataProofConfirmed = metadataInitialConfirmed;
        } else if (!metadataAttemptEpoch.equals(epoch)) throw invalid();
        if (!metadataInitialConfirmed) {
            Map<String, Object> header = new HashMap<>(); header.put(FORMAT, 1L); header.put(EPOCH, epoch);
            if (commit(header, Collections.emptySet()) != Outcome.CONFIRMED) throw bootstrapFailure();
            metadataInitialConfirmed = true;
        }
        if (!metadataProofConfirmed) {
            if (commit(Collections.singletonMap(HEADER_PROOF, epoch), Collections.emptySet()) != Outcome.CONFIRMED) throw bootstrapFailure();
            metadataProofConfirmed = true;
        }
        Object water = raw.get(HIGH_WATER);
        if (water != null && (!(water instanceof Long) || (Long) water < 0)) throw invalid();
        highWater = water == null ? 0L : (Long) water;
        lastAck = raw.containsKey(ACK) ? string(raw.get(ACK)) : null;
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(CURRENT)) {
                Mutation m = decodeMutation(entry.getValue());
                if (!key.substring(CURRENT.length()).equals(m.event.connectorId)) throw invalid();
                current.put(m.event.connectorId, m);
            } else if (key.startsWith(CANDIDATE)) {
                Mutation m = decodeMutation(entry.getValue());
                if (!key.substring(CANDIDATE.length()).equals(m.version)) throw invalid();
                candidates.put(m.version, m);
            } else if (key.startsWith(PROOF)) {
                Mutation m = decodeMutation(entry.getValue());
                if (!key.substring(PROOF.length()).equals(m.version)) throw invalid();
                proofs.put(m.version, m);
            } else if (key.startsWith(PENDING)) {
                Event event = ConnectionStateCodec.event(decode(entry.getValue()));
                if (!key.substring(PENDING.length()).equals(event.eventId)) throw invalid();
                pending.put(event.eventId, event);
            } else if (key.startsWith(REVISION_FLOOR)) {
                reservedRevisions.put(nonemptySuffix(key, REVISION_FLOOR), floor(entry.getValue()));
            } else if (key.startsWith(INTENT_FLOOR)) {
                intentHighWater.put(nonemptySuffix(key, INTENT_FLOOR), floor(entry.getValue()));
            } else if (key.startsWith(BASELINE)) {
                string(entry.getValue());
            } else if (key.startsWith(PREFIX) && !key.equals(FORMAT) && !key.equals(EPOCH)
                    && !key.equals(HEADER_PROOF) && !key.equals(HIGH_WATER) && !key.equals(ACK)) throw invalid();
        }
        validateImage();
        sourceSnapshot = Collections.unmodifiableMap(new HashMap<>(raw));
        StoredImage result = image(false);
        loaded = true;
        return result;
    }
    /** The sole startup snapshot also supplies legacy inputs; never enumerate mutable prefs mid-flight. */
    synchronized Map<String, ?> startupSnapshot() { requireLoaded(); return sourceSnapshot; }

    private StoredImage image(boolean includeProvenCandidates) {
        Map<String, UncertainCandidate> unresolved = new LinkedHashMap<>();
        for (Mutation m : candidates.values()) {
            if (includeProvenCandidates || !m.equals(proofs.get(m.version))) unresolved.put(m.version, new UncertainCandidate(m, false));
        }
        java.util.List<LegacyRecord> legacy = new java.util.ArrayList<>();
        Set<String> legacyIds = new HashSet<>();
        for (Map.Entry<String, ?> entry : sourceSnapshot.entrySet()) {
            if (!entry.getKey().startsWith("connected_")) continue;
            String id = entry.getKey().substring("connected_".length());
            if (id.isEmpty()) throw invalid();
            legacyIds.add(id);
            LegacyState state;
            if (id.equals("email") || id.equals("sms_draft")) state = LegacyState.RETIRED;
            else if (entry.getValue() instanceof Boolean)
                state = (Boolean) entry.getValue() ? LegacyState.TRUE : LegacyState.FALSE;
            else state = LegacyState.INVALID;
            Object marker = sourceSnapshot.get(BASELINE + id);
            legacy.add(new LegacyRecord(epoch + ":legacy:" + id, id, "bootstrap:legacy-observed", state,
                    marker == null ? null : string(marker)));
        }
        // Retirement is a product invariant, not conditional on old keys surviving startup cleanup.
        for (String retired : java.util.Arrays.asList("email", "sms_draft")) if (!legacyIds.contains(retired)) {
            legacy.add(new LegacyRecord(epoch + ":legacy:" + retired, retired, LEGACY_DEFINITION,
                    LegacyState.RETIRED, null));
        }
        return new StoredImage(epoch, highWater, null, lastAck, current, proofs, pending, unresolved, legacy, reservedRevisions, intentHighWater);
    }
    private void validateImage() {
        Map<String, Mutation> versions = new HashMap<>(); Map<String, Event> events = new HashMap<>();
        Map<String, Command> commands = new HashMap<>();
        for (Map<String, Mutation> records : java.util.Arrays.asList(current, candidates, proofs)) {
            for (Mutation m : records.values()) {
                validateMutation(m);
                reserve(m);
                Mutation prior = versions.put(m.version, m);
                if (prior != null && !prior.equals(m)) throw invalid();
                Event priorEvent = events.put(m.event.eventId, m.event);
                if (priorEvent != null && !priorEvent.equals(m.event)) throw invalid();
                Command priorCommand = commands.put(m.operationId, m.command);
                if (priorCommand != null && !priorCommand.equals(m.command)) throw invalid();
            }
        }
        for (Event e : pending.values()) {
            if (!epoch.equals(e.storeEpoch) || e.sequence > highWater) throw invalid();
            Event known = events.get(e.eventId);
            if (known == null || !known.equals(e)) throw invalid();
        }
    }
    private void validateMutation(Mutation m) {
        if (!epoch.equals(m.event.storeEpoch) || m.highWater > highWater || m.event.sequence > highWater) throw invalid();
    }
    @Override public synchronized Outcome writeCandidate(Mutation mutation) {
        requireLoaded(); Objects.requireNonNull(mutation);
        if (!epoch.equals(mutation.event.storeEpoch)) throw invalid();
        checkVersion(mutation);
        Event existingEvent = pending.get(mutation.event.eventId);
        if (existingEvent != null && !existingEvent.equals(mutation.event)) throw invalid();
        Map<String, Object> patch = new HashMap<>();
        String bytes = encode(ConnectionStateCodec.mutation(mutation));
        patch.put(CURRENT + mutation.event.connectorId, bytes);
        patch.put(CANDIDATE + mutation.version, bytes);
        patch.put(PENDING + mutation.event.eventId, encode(ConnectionStateCodec.event(mutation.event)));
        patch.put("connected_" + mutation.event.connectorId, mutation.selected); // Explicit legacy compatibility key.
        reserve(mutation);
        if (mutation.bootstrapRecordId != null) patch.put(BASELINE + mutation.event.connectorId, mutation.version);
        // Raw candidate knowledge is not proof, even when commit changes memory and then returns false.
        current.put(mutation.event.connectorId, mutation); candidates.put(mutation.version, mutation);
        pending.put(mutation.event.eventId, mutation.event);
        carryUnresolvedAndFloors(patch);
        snapshotVersion = Math.addExact(snapshotVersion, 1L);
        Outcome result = commit(patch, Collections.emptySet());
        if (result == Outcome.CONFIRMED) initialConfirmed.put(mutation.version, mutation);
        return result;
    }
    @Override public synchronized Outcome writeProof(Mutation mutation) {
        requireLoaded(); Objects.requireNonNull(mutation); checkVersion(mutation);
        if (!mutation.equals(initialConfirmed.get(mutation.version)) && !mutation.equals(proofs.get(mutation.version)))
            throw new IllegalStateException("No confirmed initial mutation for this proof");
        Map<String, Object> patch = new HashMap<>();
        patch.put(PROOF + mutation.version, encode(ConnectionStateCodec.mutation(mutation)));
        carryUnresolvedAndFloors(patch);
        snapshotVersion = Math.addExact(snapshotVersion, 1L);
        Outcome result = commit(patch, Collections.emptySet());
        if (result == Outcome.CONFIRMED) proofs.put(mutation.version, mutation);
        return result;
    }
    @Override public synchronized Outcome cleanup(ProjectionBatch batch, RetentionPlan plan) {
        requireLoaded();
        if (binding == null || batch.binding != binding || !epoch.equals(batch.sourceEpoch)) return Outcome.NOT_ATTEMPTED;
        RetentionSnapshot expected = plan.expectedSnapshot;
        if (expected.ownerIdentity != ownerIdentity || expected.version != snapshotVersion
                || !epoch.equals(expected.image.storeEpoch)) return Outcome.NOT_ATTEMPTED;
        if (!current.equals(expected.image.current) || !proofs.equals(expected.image.proofs)
                || !pending.equals(expected.image.pending) || !candidatePayloadsEqual(expected.image.candidates))
            return Outcome.NOT_ATTEMPTED;
        Set<String> removals = new HashSet<>();
        for (Event event : batch.events) {
            boolean proven = false;
            for (Mutation m : proofs.values()) if (m.event.equals(event)) { proven = true; break; }
            if (!proven) throw new IllegalStateException("Projection acknowledgment lacks exact proof");
            Event existing = pending.get(event.eventId);
            if (existing != null && !existing.equals(event)) throw invalid();
            if (existing != null) removals.add(PENDING + event.eventId);
        }
        for (String version : proofs.keySet()) if (!plan.keepProofVersions.contains(version)) removals.add(PROOF + version);
        for (String version : candidates.keySet()) if (!plan.keepCandidateVersions.contains(version)) removals.add(CANDIDATE + version);
        Map<String, Object> patch = new HashMap<>();
        patch.put(ACK, batch.targetGeneration);
        for (Map.Entry<String, Mutation> entry : plan.carryCandidates.entrySet()) {
            Mutation carried = entry.getValue(), known = candidates.get(entry.getKey());
            if (!entry.getKey().equals(carried.version) || (known != null && !carried.equals(known))
                    || carried.equals(proofs.get(carried.version)) || !epoch.equals(carried.event.storeEpoch)
                    || carried.highWater > plan.highWater) throw invalid();
            checkVersion(carried);
            patch.put(CANDIDATE + carried.version, encode(ConnectionStateCodec.mutation(carried)));
        }
        for (Map.Entry<String, Event> entry : plan.carryPending.entrySet()) {
            Event carried = entry.getValue(), known = pending.get(entry.getKey());
            if (!entry.getKey().equals(carried.eventId) || (known != null && !known.equals(carried))) throw invalid();
            boolean backed = known != null;
            for (Mutation m : plan.carryCandidates.values()) if (m.event.equals(carried)) backed = true;
            if (!backed) throw invalid();
            patch.put(PENDING + entry.getKey(), encode(ConnectionStateCodec.event(carried)));
        }
        if (plan.highWater < highWater || !covers(plan.reservedRevisions, reservedRevisions)
                || !covers(plan.intentHighWater, intentHighWater)) throw invalid();
        writeFloors(patch, plan.highWater, plan.reservedRevisions, plan.intentHighWater);
        for (String removed : removals) if (patch.containsKey(removed)) throw invalid();
        // Carry can include a core reservation whose write failed before reaching this adapter.
        // Retain it as uncertain raw knowledge even if this cleanup itself returns UNKNOWN.
        candidates.putAll(plan.carryCandidates); pending.putAll(plan.carryPending);
        highWater = Math.max(highWater, plan.highWater);
        plan.reservedRevisions.forEach((id, value) -> reservedRevisions.merge(id, value, Math::max));
        plan.intentHighWater.forEach((id, value) -> intentHighWater.merge(id, value, Math::max));
        snapshotVersion = Math.addExact(snapshotVersion, 1L);
        Outcome result = commit(patch, removals);
        if (result == Outcome.CONFIRMED) {
            for (Event event : batch.events) pending.remove(event.eventId);
            proofs.keySet().retainAll(plan.keepProofVersions);
            candidates.keySet().retainAll(plan.keepCandidateVersions);
            initialConfirmed.keySet().retainAll(plan.keepCandidateVersions);
            highWater = plan.highWater;
            reservedRevisions.clear(); reservedRevisions.putAll(plan.reservedRevisions);
            intentHighWater.clear(); intentHighWater.putAll(plan.intentHighWater);
            lastAck = batch.targetGeneration;
        }
        return result;
    }
    private boolean candidatePayloadsEqual(Map<String, UncertainCandidate> expected) {
        if (!candidates.keySet().equals(expected.keySet())) return false;
        for (Mutation m : candidates.values()) if (!m.equals(expected.get(m.version).mutation)) return false;
        return true;
    }
    private static boolean covers(Map<String, Long> proposed, Map<String, Long> old) {
        for (Map.Entry<String, Long> entry : old.entrySet())
            if (proposed.getOrDefault(entry.getKey(), -1L) < entry.getValue()) return false;
        return true;
    }
    private void reserve(Mutation mutation) {
        highWater = Math.max(highWater, mutation.highWater);
        reservedRevisions.merge(mutation.event.connectorId, mutation.revision, Math::max);
        intentHighWater.merge(mutation.event.connectorId, Math.addExact(mutation.command.expectedIntentEpoch, 1L), Math::max);
    }
    /** Carry is provenance-only: never replay another candidate's current choice or mint its proof. */
    private void carryUnresolvedAndFloors(Map<String, Object> patch) {
        for (Mutation m : candidates.values()) if (!m.equals(proofs.get(m.version))) {
            patch.put(CANDIDATE + m.version, encode(ConnectionStateCodec.mutation(m)));
            boolean eventProven = false;
            for (Mutation proof : proofs.values()) if (proof.event.equals(m.event)) { eventProven = true; break; }
            if (pending.containsKey(m.event.eventId) || !eventProven) {
                patch.put(PENDING + m.event.eventId, encode(ConnectionStateCodec.event(m.event)));
                pending.put(m.event.eventId, m.event);
            }
        }
        writeFloors(patch, highWater, reservedRevisions, intentHighWater);
    }
    private static void writeFloors(Map<String, Object> patch, long highWater,
                                    Map<String, Long> revisions, Map<String, Long> intents) {
        patch.put(HIGH_WATER, highWater);
        for (Map.Entry<String, Long> e : revisions.entrySet()) patch.put(REVISION_FLOOR + e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : intents.entrySet()) patch.put(INTENT_FLOOR + e.getKey(), e.getValue());
    }
    private static long floor(Object value) {
        if (!(value instanceof Long) || (Long) value < 0) throw invalid();
        return (Long) value;
    }
    private static String nonemptySuffix(String key, String prefix) {
        String id = key.substring(prefix.length()); if (id.isEmpty()) throw invalid(); return id;
    }
    private void checkVersion(Mutation m) {
        Mutation old = candidates.get(m.version);
        if (old == null) old = proofs.get(m.version);
        if (old != null && !old.equals(m)) throw invalid();
        for (Map<String, Mutation> records : java.util.Arrays.asList(candidates, proofs)) {
            for (Mutation known : records.values()) {
                if (known.operationId.equals(m.operationId) && !known.command.equals(m.command)) throw invalid();
                if (known.event.eventId.equals(m.event.eventId) && !known.event.equals(m.event)) throw invalid();
            }
        }
    }
    private Outcome commit(Map<String, Object> patch, Set<String> removals) {
        try { return backend.commit(patch, removals) ? Outcome.CONFIRMED : Outcome.UNCERTAIN; }
        catch (RuntimeException failure) { return Outcome.UNCERTAIN; }
    }
    private void requireLoaded() { if (!loaded) throw new IllegalStateException("Connection storage has not initialized"); }
    private static Mutation decodeMutation(Object encoded) { return ConnectionStateCodec.mutation(decode(encoded)); }
    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    private static byte[] decode(Object value) {
        String encoded = string(value);
        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            if (!encode(decoded).equals(encoded)) throw invalid();
            return decoded;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static String string(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) throw invalid();
        return (String) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid connection persistence record"); }
    private static StorageUnconfirmedException bootstrapFailure() { return new StorageUnconfirmedException("Connection metadata persistence is unconfirmed"); }
}

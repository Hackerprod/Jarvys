package com.jarvys.agent;

import java.util.*;

/** Recorded input context only: neither authority, durable witness, current prompt nor command cause. */
public final class CrewInputContext {
    public enum Coverage { COMPLETE, PARTIAL }
    public enum Channel { CREW_BINDING, CONVERSATION_INPUT }

    /** Exact recorded scope. Empty optional fields remain unknown; never filled from an assignment root. */
    public static final class Scope {
        public final String sourceSessionId, executionSessionId, workspaceId, assignmentId, recordedProvenanceBotId, missionId;
        public Scope(String sourceSessionId, String executionSessionId, String workspaceId,
                     String assignmentId, String botId, String missionId) {
            this.sourceSessionId = optional(sourceSessionId); this.executionSessionId = required(executionSessionId);
            this.workspaceId = optional(workspaceId); this.assignmentId = optional(assignmentId);
            this.recordedProvenanceBotId = optional(botId); this.missionId = optional(missionId);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Scope)) return false;
            Scope that = (Scope) other;
            return sourceSessionId.equals(that.sourceSessionId) && executionSessionId.equals(that.executionSessionId)
                    && workspaceId.equals(that.workspaceId) && assignmentId.equals(that.assignmentId)
                    && recordedProvenanceBotId.equals(that.recordedProvenanceBotId) && missionId.equals(that.missionId);
        }
        @Override public int hashCode() { return Objects.hash(sourceSessionId, executionSessionId, workspaceId, assignmentId, recordedProvenanceBotId, missionId); }
    }

    public static final class Reference {
        public final Channel channel;
        public final String inputId, sourceKind, sourceId;
        public final Scope scope;
        public final String admissionCycleId, firstIncorporationCycleId, transportKind;
        public final String sourcePayloadSha256, deliverySha256, transportSha256;
        public final String principalRole, correlationMessageId, correlationReferenceId, correlationSha256;
        public final long assignmentRevision;
        public final String inputOrigin, recordedRequestId, recordedProvenanceCycleId;
        private Reference(Channel channel, String inputId, String sourceKind, String sourceId, Scope scope,
                String admissionCycleId, String firstCycle, String transportKind, String payloadDigest,
                String deliveryDigest, String transportDigest, String principalRole, String correlationMessageId,
                String correlationReferenceId, String correlationDigest, long assignmentRevision,
                String inputOrigin, String recordedRequestId, String recordedCycleId) {
            this.channel = Objects.requireNonNull(channel); this.inputId = required(inputId);
            this.sourceKind = required(sourceKind); this.sourceId = required(sourceId); this.scope = Objects.requireNonNull(scope);
            this.admissionCycleId = optional(admissionCycleId); firstIncorporationCycleId = optional(firstCycle);
            this.transportKind = optional(transportKind); sourcePayloadSha256 = digest(payloadDigest);
            deliverySha256 = digest(deliveryDigest); transportSha256 = digest(transportDigest);
            this.principalRole = optional(principalRole); this.correlationMessageId = optional(correlationMessageId);
            this.correlationReferenceId = optional(correlationReferenceId); correlationSha256 = digest(correlationDigest);
            if (assignmentRevision < 0) throw new IllegalArgumentException("Negative recorded assignment revision");
            this.assignmentRevision = assignmentRevision;
            this.inputOrigin = optional(inputOrigin); this.recordedRequestId = optional(recordedRequestId);
            recordedProvenanceCycleId = optional(recordedCycleId);
        }
        public static Reference binding(String inputId, String sourceKind, String sourceId, Scope scope,
                String admissionCycle, String firstCycle, String transportKind,
                String payloadDigest, String deliveryDigest, String transportDigest) {
            return new Reference(Channel.CREW_BINDING, inputId, sourceKind, sourceId, scope,
                    required(admissionCycle), required(firstCycle), required(transportKind), required(payloadDigest),
                    required(deliveryDigest), required(transportDigest), "", "", "", "", 0, "", "", "");
        }
        public static Reference conversation(String inputId, String kind, String sourceId, Scope scope,
                String principalRole, String correlationMessageId, String correlationReferenceId,
                String correlationDigest, long assignmentRevision, String inputOrigin,
                String recordedRequestId, String recordedCycleId) {
            return new Reference(Channel.CONVERSATION_INPUT, inputId, kind, sourceId, scope,
                    "", "", "", "", "", "", required(principalRole), correlationMessageId,
                    correlationReferenceId, correlationDigest, assignmentRevision,
                    required(inputOrigin), recordedRequestId, recordedCycleId);
        }
        private List<?> values() { return Arrays.asList(channel, inputId, sourceKind, sourceId, scope,
                admissionCycleId, firstIncorporationCycleId, transportKind, sourcePayloadSha256, deliverySha256,
                transportSha256, principalRole, correlationMessageId, correlationReferenceId, correlationSha256, assignmentRevision, inputOrigin, recordedRequestId, recordedProvenanceCycleId); }
        @Override public boolean equals(Object other) { return other instanceof Reference && values().equals(((Reference) other).values()); }
        @Override public int hashCode() { return values().hashCode(); }
    }

    public final List<Reference> references;
    public final Set<String> unresolvedInputIds;
    public final Coverage coverage;
    private CrewInputContext(List<Reference> references, Set<String> unresolved) {
        this.references = Collections.unmodifiableList(new ArrayList<>(references));
        unresolvedInputIds = Collections.unmodifiableSet(new LinkedHashSet<>(unresolved));
        coverage = unresolved.isEmpty() ? Coverage.COMPLETE : Coverage.PARTIAL;
    }
    /** Pure selection. COMPLETE means each applied ID has recorded metadata, not causal completeness. */
    public static CrewInputContext resolve(Collection<String> appliedIds, Collection<Reference> candidates) {
        SortedSet<String> applied = new TreeSet<>();
        for (String id : Objects.requireNonNull(appliedIds)) applied.add(required(id));
        Map<String, EnumMap<Channel, Reference>> selected = new TreeMap<>();
        for (Reference reference : Objects.requireNonNull(candidates)) {
            Objects.requireNonNull(reference);
            if (!applied.contains(reference.inputId)) continue;
            EnumMap<Channel, Reference> channels = selected.computeIfAbsent(reference.inputId, ignored -> new EnumMap<>(Channel.class));
            Reference prior = channels.putIfAbsent(reference.channel, reference);
            if (prior != null && !prior.equals(reference))
                throw new IllegalArgumentException("Conflicting recorded input identity");
        }
        List<Reference> references = new ArrayList<>();
        for (EnumMap<Channel, Reference> channels : selected.values()) references.addAll(channels.values());
        Set<String> unresolved = new LinkedHashSet<>(applied); unresolved.removeAll(selected.keySet());
        return new CrewInputContext(references, unresolved);
    }
    @Override public boolean equals(Object other) {
        return other instanceof CrewInputContext && references.equals(((CrewInputContext) other).references)
                && unresolvedInputIds.equals(((CrewInputContext) other).unresolvedInputIds);
    }
    @Override public int hashCode() { return Objects.hash(references, unresolvedInputIds); }
    private static String optional(String value) { return value == null ? "" : value; }
    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Recorded identity is required");
        return value;
    }
    private static String digest(String value) {
        String safe = optional(value);
        if (!safe.isEmpty() && !safe.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid recorded digest");
        return safe;
    }
}

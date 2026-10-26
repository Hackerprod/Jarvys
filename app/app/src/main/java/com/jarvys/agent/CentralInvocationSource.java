package com.jarvys.agent;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable operational provenance metadata, NOT a committed-checkpoint witness or authority.
 * Construction/decoding does not verify a source. A future producer must check committedCall,
 * owner, checkpoint and generation before minting a separate owner-bound witness.
 * No universal ID length/format bound is invented here: the future provider must apply each
 * existing source's constraints. This constructor retains a fixed number of already allocated
 * immutable strings; untrusted serialized input must use its caller-bounded decoder.
 */
public final class CentralInvocationSource {
    public final String storageGeneration, ownerCentralSessionId, sourceMessageId, sourceBatchId,
            sourceCallId, sourceRunId, sourceProcessId;

    public CentralInvocationSource(String storageGeneration, String ownerCentralSessionId,
            String sourceMessageId, String sourceBatchId, String sourceCallId,
            String sourceRunId, String sourceProcessId) {
        this.storageGeneration = identifier(storageGeneration);
        this.ownerCentralSessionId = identifier(ownerCentralSessionId);
        this.sourceMessageId = identifier(sourceMessageId);
        this.sourceBatchId = identifier(sourceBatchId);
        this.sourceCallId = identifier(sourceCallId);
        this.sourceRunId = identifier(sourceRunId);
        this.sourceProcessId = identifier(sourceProcessId);
    }

    private static String identifier(String value) {
        Objects.requireNonNull(value, "Source identifier");
        if (value.trim().isEmpty() || value.equals("null") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid source identifier");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Control character in identifier");
        }
        return value;
    }
    private java.util.List<String> values() {
        return Arrays.asList(storageGeneration, ownerCentralSessionId, sourceMessageId,
                sourceBatchId, sourceCallId, sourceRunId, sourceProcessId);
    }
    @Override public boolean equals(Object other) {
        return other instanceof CentralInvocationSource && values().equals(((CentralInvocationSource) other).values());
    }
    @Override public int hashCode() { return values().hashCode(); }
}

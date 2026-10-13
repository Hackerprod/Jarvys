package com.jarvys.agent.crew;

import org.json.JSONObject;

/** Historical exact append evidence; not a durability receipt, current context, or completion claim. */
public final class CrewInputIncorporation {
    public enum TransportKind { USER, INBOX, ASK_CHIEF }
    public final CrewInputBinding binding;
    public final String firstIncorporationCycleId;
    public final TransportKind transportKind;
    public final String transportSha256;

    public CrewInputIncorporation(CrewInputBinding binding, String firstIncorporationCycleId,
            TransportKind transportKind, String transportSha256) {
        if (binding == null || transportKind == null) throw new IllegalArgumentException("Complete input proof is required");
        if ((binding.sourceKind == CrewInputBinding.SourceKind.NATIVE_CORRECT) != (transportKind == TransportKind.USER))
            throw new IllegalArgumentException("Input provenance does not match its transport");
        this.binding = binding;
        this.firstIncorporationCycleId = CrewCoordinationEvidence.requireCycleId(firstIncorporationCycleId);
        this.transportKind = transportKind;
        this.transportSha256 = CrewInputBinding.requireDigest(transportSha256);
    }

    public static String transportSha256(String exactAppendedContent) {
        return CrewInputBinding.hashText("crew-input-transport-v1", exactAppendedContent);
    }
    public boolean samePayload(CrewInputIncorporation other) {
        return other != null && binding.samePayload(other.binding)
                && firstIncorporationCycleId.equals(other.firstIncorporationCycleId)
                && transportKind == other.transportKind && transportSha256.equals(other.transportSha256);
    }
    public JSONObject toJson() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("binding", binding.toJson())
                    .put("firstIncorporationCycleId", firstIncorporationCycleId)
                    .put("transportKind", transportKind.name()).put("transportSha256", transportSha256);
        } catch (Exception failure) { throw new IllegalStateException("Could not encode input proof", failure); }
    }
    public static CrewInputIncorporation fromJsonStrict(JSONObject row) {
        CrewInputBinding.fields(row, "schemaVersion", "binding", "firstIncorporationCycleId", "transportKind", "transportSha256");
        if (CrewInputBinding.integer(row, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported input proof schema");
        return new CrewInputIncorporation(CrewInputBinding.fromJsonStrict(row.optJSONObject("binding")),
                CrewInputBinding.string(row, "firstIncorporationCycleId"),
                TransportKind.valueOf(CrewInputBinding.string(row, "transportKind")),
                CrewInputBinding.string(row, "transportSha256"));
    }
}

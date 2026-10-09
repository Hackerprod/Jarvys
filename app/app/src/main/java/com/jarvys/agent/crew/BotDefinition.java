package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Persisted catalog identity. A metadata revision is distinct from executable profile version. */
public final class BotDefinition {
    public final CrewProfile profile;
    public final String id;
    public final int revision;
    public final boolean enabled;
    public final boolean builtIn;
    /** Private per-bot icon basename, never an arbitrary file path or external URI. */
    public final String iconRef;
    public final BotMascotDescriptor mascot;
    public final List<BotMascotDescriptor.Receipt> mascotReceipts;

    public BotDefinition(CrewProfile profile, int revision, boolean enabled, boolean builtIn, String iconRef) {
        this(profile, revision, enabled, builtIn, iconRef, null, Collections.emptyList());
    }

    public BotDefinition(CrewProfile profile, int revision, boolean enabled, boolean builtIn, String iconRef,
            BotMascotDescriptor mascot, List<BotMascotDescriptor.Receipt> mascotReceipts) {
        if (profile == null) throw CrewProfile.invalid("profile is required");
        if (revision < 1) throw CrewProfile.invalid("definition revision must be positive");
        if (iconRef == null || (!iconRef.isEmpty() && !iconRef.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"))) {
            throw CrewProfile.invalid("icon reference must be a private generated PNG basename");
        }
        if (mascotReceipts == null || mascotReceipts.size() > BotMascotDescriptor.MAX_RECEIPTS)
            throw CrewProfile.invalid("invalid mascot receipt count");
        if ((builtIn || CrewProfileRepository.isBuiltInId(profile.id)) && (mascot != null || !mascotReceipts.isEmpty()))
            throw CrewProfile.invalid("runtime templates cannot receive mascot metadata");
        HashSet<String> operations = new HashSet<>();
        HashSet<String> packages = new HashSet<>();
        int lastRevision = 1;
        for (BotMascotDescriptor.Receipt receipt : mascotReceipts) {
            if (receipt == null || receipt.assignedRevision <= lastRevision || receipt.assignedRevision > revision
                    || !operations.add(receipt.operationId) || !packages.add(receipt.mascot.packageRef))
                throw CrewProfile.invalid("invalid mascot receipt history");
            lastRevision = receipt.assignedRevision;
        }
        if ((mascot == null) != mascotReceipts.isEmpty()
                || mascot != null && !mascot.equals(mascotReceipts.get(mascotReceipts.size() - 1).mascot))
            throw CrewProfile.invalid("mascot must match its latest durable assignment receipt");
        this.mascot = mascot;
        this.mascotReceipts = Collections.unmodifiableList(new ArrayList<>(mascotReceipts));
        this.profile = profile;
        this.id = profile.id;
        this.revision = revision;
        this.enabled = enabled;
        this.builtIn = builtIn;
        this.iconRef = iconRef;
    }

    public BotDefinition withProfile(CrewProfile edited) {
        if (!id.equals(edited.id)) throw CrewProfile.invalid("definition identity cannot change");
        return new BotDefinition(edited, revision, enabled, builtIn, iconRef, mascot, mascotReceipts);
    }

    JSONObject toJson() {
        try {
            JSONArray receipts = new JSONArray();
            for (BotMascotDescriptor.Receipt receipt : mascotReceipts) receipts.put(receipt.toJson());
            return new JSONObject().put("profile", profile.toJson()).put("revision", revision)
                    .put("enabled", enabled).put("iconRef", iconRef)
                    .put("mascot", mascot == null ? JSONObject.NULL : mascot.toJson()).put("mascotReceipts", receipts);
        } catch (JSONException error) {
            throw CrewProfile.invalid("could not encode bot definition", error);
        }
    }

    static BotDefinition fromJson(JSONObject value, int schema) {
        if (schema == 3) CrewProfile.exactFields(value, "profile", "revision", "enabled", "iconRef");
        else if (schema == 4) CrewProfile.exactFields(value, "profile", "revision", "enabled", "iconRef", "mascot", "mascotReceipts");
        else throw CrewProfile.invalid("unsupported bot definition schema");
        if (!(value.opt("profile") instanceof JSONObject) || !(value.opt("enabled") instanceof Boolean)
                || !(value.opt("iconRef") instanceof String)) throw CrewProfile.invalid("invalid bot definition fields");
        BotMascotDescriptor mascot = null;
        List<BotMascotDescriptor.Receipt> receipts = new ArrayList<>();
        if (schema == 4) {
            Object raw = value.opt("mascot");
            if (raw instanceof JSONObject) mascot = BotMascotDescriptor.fromJson((JSONObject) raw);
            else if (raw != JSONObject.NULL) throw CrewProfile.invalid("invalid mascot descriptor");
            Object rawReceipts = value.opt("mascotReceipts");
            if (!(rawReceipts instanceof JSONArray)) throw CrewProfile.invalid("invalid mascot receipts");
            JSONArray rows = (JSONArray) rawReceipts;
            if (rows.length() > BotMascotDescriptor.MAX_RECEIPTS) throw CrewProfile.invalid("too many mascot receipts");
            for (int i = 0; i < rows.length(); i++) {
                if (!(rows.opt(i) instanceof JSONObject)) throw CrewProfile.invalid("invalid mascot receipt");
                receipts.add(BotMascotDescriptor.Receipt.fromJson((JSONObject) rows.opt(i)));
            }
        }
        return new BotDefinition(CrewProfile.fromJson((JSONObject) value.opt("profile")),
                CrewProfile.positiveInteger(value, "revision"), (Boolean) value.opt("enabled"), false,
                (String) value.opt("iconRef"), mascot, receipts);
    }
}

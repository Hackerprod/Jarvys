package com.jarvys.agent.crew;

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

    public BotDefinition(CrewProfile profile, int revision, boolean enabled, boolean builtIn, String iconRef) {
        if (profile == null) throw CrewProfile.invalid("profile is required");
        if (revision < 1) throw CrewProfile.invalid("definition revision must be positive");
        if (iconRef == null || (!iconRef.isEmpty() && !iconRef.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"))) {
            throw CrewProfile.invalid("icon reference must be a private generated PNG basename");
        }
        this.profile = profile;
        this.id = profile.id;
        this.revision = revision;
        this.enabled = enabled;
        this.builtIn = builtIn;
        this.iconRef = iconRef;
    }

    public BotDefinition withProfile(CrewProfile edited) {
        if (!id.equals(edited.id)) throw CrewProfile.invalid("definition identity cannot change");
        return new BotDefinition(edited, revision, enabled, builtIn, iconRef);
    }

    JSONObject toJson() {
        try {
            return new JSONObject().put("profile", profile.toJson()).put("revision", revision)
                    .put("enabled", enabled).put("iconRef", iconRef);
        } catch (JSONException error) {
            throw CrewProfile.invalid("could not encode bot definition", error);
        }
    }

    static BotDefinition fromJson(JSONObject value) {
        CrewProfile.exactFields(value, "profile", "revision", "enabled", "iconRef");
        if (!(value.opt("profile") instanceof JSONObject) || !(value.opt("enabled") instanceof Boolean)
                || !(value.opt("iconRef") instanceof String)) throw CrewProfile.invalid("invalid bot definition fields");
        return new BotDefinition(CrewProfile.fromJson((JSONObject) value.opt("profile")),
                CrewProfile.positiveInteger(value, "revision"), (Boolean) value.opt("enabled"), false,
                (String) value.opt("iconRef"));
    }
}

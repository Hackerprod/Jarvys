package com.jarvys.agent.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Runtime-owned instructions are selected by immutable profile identity, never display name. */
public final class SkillScopePolicy {
    public static final String APK_FACTORY_ID = "com.jarvys.apk-factory";
    public static final String APK_FACTORY_TOOL = "apk_factory";
    private SkillScopePolicy() {}

    public static boolean isCodingProfile(String profileId) { return "coding".equals(profileId); }

    public static boolean isReserved(String skillId) { return APK_FACTORY_ID.equals(skillId); }

    public static boolean availableTo(String skillId, String profileId) {
        return !isReserved(skillId) || isCodingProfile(profileId);
    }

    public static List<SkillEntry> forProfile(Collection<SkillEntry> skills, String profileId) {
        List<SkillEntry> selected = new ArrayList<>();
        for (SkillEntry skill : skills) {
            if (availableTo(skill.getMetadata().getId(), profileId)) selected.add(skill);
        }
        return Collections.unmodifiableList(selected);
    }

    public static List<String> skillIdsForProfile(Collection<String> ids, String profileId) {
        List<String> selected = new ArrayList<>();
        for (String id : ids) if (availableTo(id, profileId)) selected.add(id);
        return Collections.unmodifiableList(selected);
    }
}

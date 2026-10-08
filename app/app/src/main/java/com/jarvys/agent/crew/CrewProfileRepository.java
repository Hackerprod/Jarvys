package com.jarvys.agent.crew;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Single private catalog for immutable runtime templates and versioned custom bot definitions. */
public final class CrewProfileRepository {
    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Subscription> LISTENERS = new CopyOnWriteArrayList<>();
    public static final int SCHEMA_VERSION = 3;
    private final File root;
    private final File target;

    private static final class Subscription {
        final File root;
        final Consumer<BotDefinition> listener;
        Subscription(File root, Consumer<BotDefinition> listener) { this.root = root; this.listener = listener; }
    }

    public CrewProfileRepository(Context context) { this(context.getApplicationContext().getFilesDir()); }

    public CrewProfileRepository(File appFilesDirectory) {
        try {
            this.root = new File(appFilesDirectory.getCanonicalFile(), "crew_profiles");
            this.target = new File(this.root, "profiles.json");
            verifyPaths();
        } catch (IOException failure) {
            throw CrewProfile.invalid("could not verify private configuration location", failure);
        }
    }

    public static String newCustomId() { return "custom-" + UUID.randomUUID(); }

    public static boolean isBuiltInId(String id) {
        return CrewRoleTemplates.CODING.equals(id) || CrewRoleTemplates.ANDROID_USE.equals(id);
    }

    public CrewProfile codingProfile() { return profile(CrewRoleTemplates.CODING); }
    public CrewProfile profile(String id) { return definition(id).profile; }

    public BotDefinition definition(String id) {
        synchronized (LOCK) { return requireDefinition(load(), id); }
    }

    public List<BotDefinition> definitions() {
        synchronized (LOCK) { return Collections.unmodifiableList(new ArrayList<>(load().values())); }
    }

    /** Chief receives only enabled short metadata, never configuration instructions. */
    public List<CrewProfile.CatalogEntry> catalog() {
        synchronized (LOCK) {
            List<CrewProfile.CatalogEntry> entries = new ArrayList<>();
            for (BotDefinition definition : load().values()) if (definition.enabled) entries.add(definition.profile.catalogEntry());
            return Collections.unmodifiableList(entries);
        }
    }

    public CrewRole resolveRole(String id, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        BotDefinition definition = definition(id);
        if (!definition.enabled) throw CrewProfile.invalid("bot is disabled: " + id);
        return runtimeProfile(definition, availableSkillIds).resolveRole(approvedCapabilities, availableSkillIds);
    }

    public List<CrewRole> resolveRoles(Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        List<CrewRole> result = new ArrayList<>();
        for (BotDefinition definition : definitions()) if (definition.enabled) {
            result.add(runtimeProfile(definition, availableSkillIds).resolveRole(approvedCapabilities, availableSkillIds));
        }
        return Collections.unmodifiableList(result);
    }

    /** Optional factory availability narrows the immutable template only for this run. */
    private static CrewProfile runtimeProfile(BotDefinition definition, Collection<String> availableSkillIds) {
        CrewProfile profile = definition.profile;
        if (!definition.builtIn || !CrewRoleTemplates.CODING.equals(profile.id)
                || availableSkillIds.contains(com.jarvys.agent.skills.SkillScopePolicy.APK_FACTORY_ID)) return profile;
        List<String> skills = new ArrayList<>(profile.skillIds);
        skills.remove(com.jarvys.agent.skills.SkillScopePolicy.APK_FACTORY_ID);
        List<String> capabilities = new ArrayList<>(profile.capabilities);
        capabilities.remove(com.jarvys.agent.skills.SkillScopePolicy.APK_FACTORY_TOOL);
        if (skills.isEmpty()) capabilities.remove("read_skill");
        return new CrewProfile(profile.id, profile.version, profile.name, profile.description, profile.prompt,
                skills, capabilities, profile.workspaceMode);
    }

    public BotDefinition create(CrewProfile draft, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        if (draft == null) throw CrewProfile.invalid("profile is required");
        requireCustomId(draft.id);
        if (draft.version != 1) throw CrewProfile.invalid("new bot profiles must start at version 1");
        draft.validateAvailability(approvedCapabilities, availableSkillIds);
        BotDefinition created = new BotDefinition(draft, 1, true, false, "");
        synchronized (LOCK) {
            Map<String, BotDefinition> definitions = load();
            if (definitions.containsKey(draft.id)) throw CrewProfile.invalid("bot identity already exists");
            definitions.put(draft.id, created);
            persist(definitions.values());
        }
        notifyChanged(created);
        return created;
    }

    /** Compatibility API: CAS executable version, preserving the current icon and enabled metadata. */
    public CrewProfile save(CrewProfile edited, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        return saveProfile(edited, null, approvedCapabilities, availableSkillIds).profile;
    }

    /** Editor API: CAS the full definition revision as well as the executable version. */
    public BotDefinition save(BotDefinition edited, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        if (edited == null) throw CrewProfile.invalid("definition is required");
        return saveProfile(edited.profile, edited, approvedCapabilities, availableSkillIds);
    }

    private BotDefinition saveProfile(CrewProfile edited, BotDefinition expected, Collection<String> capabilities, Collection<String> skills) {
        if (edited == null) throw CrewProfile.invalid("profile is required");
        requireCustomId(edited.id);
        edited.validateAvailability(capabilities, skills);
        BotDefinition saved;
        synchronized (LOCK) {
            Map<String, BotDefinition> definitions = load();
            BotDefinition previous = requireEditable(definitions, edited.id);
            if (previous.profile.version != edited.version) throw conflict();
            if (expected != null && (previous.revision != expected.revision || expected.builtIn
                    || previous.enabled != expected.enabled || !previous.iconRef.equals(expected.iconRef))) throw conflict();
            saved = new BotDefinition(edited.withVersion(next(previous.profile.version)), next(previous.revision), previous.enabled, false, previous.iconRef);
            definitions.put(saved.id, saved);
            persist(definitions.values());
        }
        notifyChanged(saved);
        return saved;
    }

    public BotDefinition setEnabled(String id, int expectedRevision, boolean enabled) {
        BotDefinition saved;
        synchronized (LOCK) {
            Map<String, BotDefinition> definitions = load();
            BotDefinition previous = requireEditable(definitions, id);
            if (previous.revision != expectedRevision) throw conflict();
            if (previous.enabled == enabled) return previous;
            saved = new BotDefinition(previous.profile.withVersion(next(previous.profile.version)), next(previous.revision), enabled, false, previous.iconRef);
            definitions.put(id, saved);
            persist(definitions.values());
        }
        // Outside storage lock: stopping a worker may flush its checkpoint or check current policy.
        notifyChanged(saved);
        return saved;
    }

    public BotDefinition setIcon(String id, int expectedRevision, String iconRef) {
        BotDefinition saved;
        synchronized (LOCK) {
            Map<String, BotDefinition> definitions = load();
            BotDefinition previous = requireEditable(definitions, id);
            if (previous.revision != expectedRevision) throw conflict();
            if (!previous.enabled) throw CrewProfile.invalid("disabled bots cannot receive generated icons");
            if (previous.iconRef.equals(iconRef)) return previous;
            saved = new BotDefinition(previous.profile, next(previous.revision), previous.enabled, false, iconRef);
            definitions.put(id, saved);
            persist(definitions.values());
        }
        notifyChanged(saved);
        return saved;
    }

    public Runnable addChangeListener(Consumer<BotDefinition> listener) {
        Subscription subscription = new Subscription(root, java.util.Objects.requireNonNull(listener));
        LISTENERS.add(subscription);
        return () -> LISTENERS.remove(subscription);
    }

    private void notifyChanged(BotDefinition definition) {
        for (Subscription subscription : LISTENERS) if (root.equals(subscription.root)) {
            // A presentation/checkpoint callback cannot undo a committed definition mutation.
            try { subscription.listener.accept(definition); } catch (RuntimeException ignored) { }
        }
    }

    private static int next(int revision) {
        if (revision == Integer.MAX_VALUE) throw CrewProfile.invalid("version cannot be incremented");
        return revision + 1;
    }
    private static IllegalArgumentException conflict() { return CrewProfile.invalid("bot changed while editing. Reopen it before saving."); }
    private static void requireCustomId(String id) {
        if (isBuiltInId(id)) throw CrewProfile.invalid("runtime templates are immutable");
        if (!isCustomId(id)) throw CrewProfile.invalid("custom bots require a stable custom- identifier");
    }
    private static boolean isCustomId(String id) {
        return id != null && id.matches("custom-[a-z0-9][a-z0-9._-]*");
    }
    private static BotDefinition requireDefinition(Map<String, BotDefinition> definitions, String id) {
        BotDefinition definition = definitions.get(id);
        if (definition == null) throw CrewProfile.invalid("unknown profile: " + id);
        return definition;
    }
    private static BotDefinition requireEditable(Map<String, BotDefinition> definitions, String id) {
        if (isBuiltInId(id)) throw CrewProfile.invalid("runtime templates are immutable");
        BotDefinition definition = requireDefinition(definitions, id);
        if (definition.builtIn) throw CrewProfile.invalid("runtime templates are immutable");
        return definition;
    }

    private Map<String, BotDefinition> load() {
        try {
            verifyPaths();
            if (!target.exists() && !new File(target.getPath() + ".bak").exists()) return defaults();
            String json = new String(new AtomicFile(target).readFully(), StandardCharsets.UTF_8);
            Map<String, BotDefinition> definitions = decodeDefinitions(json);
            if (new JSONObject(json).getInt("schemaVersion") < SCHEMA_VERSION) persist(definitions.values());
            return definitions;
        } catch (IOException | JSONException failure) {
            throw CrewProfile.invalid("could not read profiles; stored configuration was preserved", failure);
        }
    }

    private static Map<String, BotDefinition> defaults() {
        Map<String, BotDefinition> definitions = new LinkedHashMap<>();
        for (CrewProfile profile : new CrewProfile[]{CrewProfile.codingDefault(), CrewProfile.androidDefault()}) {
            definitions.put(profile.id, new BotDefinition(profile, 1, true, true, ""));
        }
        return definitions;
    }

    public static Map<String, CrewProfile> decode(String source) {
        Map<String, CrewProfile> profiles = new LinkedHashMap<>();
        for (BotDefinition definition : decodeDefinitions(source).values()) profiles.put(definition.id, definition.profile);
        return profiles;
    }

    public static Map<String, BotDefinition> decodeDefinitions(String source) {
        try {
            JSONObject data = new JSONObject(source);
            int schema = CrewProfile.positiveInteger(data, "schemaVersion");
            if (schema < 1 || schema > SCHEMA_VERSION) throw CrewProfile.invalid("unsupported repository schema version: " + schema);
            String field = schema == SCHEMA_VERSION ? "bots" : "profiles";
            CrewProfile.exactFields(data, "schemaVersion", field);
            if (!(data.opt(field) instanceof JSONArray)) throw CrewProfile.invalid(field + " must be an array");
            JSONArray rows = (JSONArray) data.opt(field);
            Map<String, BotDefinition> definitions = defaults();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int index = 0; index < rows.length(); index++) {
                if (!(rows.opt(index) instanceof JSONObject)) throw CrewProfile.invalid("each bot must be an object");
                JSONObject row = (JSONObject) rows.opt(index);
                BotDefinition definition;
                if (schema == SCHEMA_VERSION) {
                    definition = BotDefinition.fromJson(row);
                    requireCustomId(definition.id);
                } else {
                    CrewProfile profile = schema == 1 ? CrewProfile.fromLegacyJson(row) : CrewProfile.fromJson(row);
                    if (!seen.add(profile.id)) throw CrewProfile.invalid("duplicate profile id: " + profile.id);
                    // A pristine old Coding default needs no clone. Any customization, old workspace,
                    // or revision is retained under a deterministic custom ID, never promoted to runtime.
                    if (profile.id.equals(CrewRoleTemplates.CODING) && sameProfile(profile, CrewProfile.codingDefault())) continue;
                    if (!isCustomId(profile.id)) {
                        profile = profile.withIdentity("custom-legacy-" + profile.id + "-" + digest(profile.toJson().toString()));
                    }
                    definition = new BotDefinition(profile, 1, true, false, "");
                }
                if (definitions.put(definition.id, definition) != null) throw CrewProfile.invalid("duplicate bot id: " + definition.id);
            }
            return definitions;
        } catch (JSONException failure) {
            throw CrewProfile.invalid("invalid repository JSON; stored configuration was preserved", failure);
        }
    }

    private static boolean sameProfile(CrewProfile one, CrewProfile two) {
        return one.version == two.version && one.id.equals(two.id) && one.name.equals(two.name)
                && one.description.equals(two.description) && one.prompt.equals(two.prompt)
                && one.skillIds.equals(two.skillIds) && one.capabilities.equals(two.capabilities)
                && one.workspaceMode == two.workspaceMode;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 12; index++) hex.append(String.format(java.util.Locale.ROOT, "%02x", bytes[index]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void persist(Collection<BotDefinition> definitions) {
        AtomicFile store = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            verifyPaths();
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create profile storage");
            verifyPaths();
            JSONArray rows = new JSONArray();
            // Runtime templates live in code and cannot be overridden by stored configuration.
            for (BotDefinition definition : definitions) if (!definition.builtIn) rows.put(definition.toJson());
            String json = new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("bots", rows).toString();
            stream = store.startWrite();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
            store.finishWrite(stream);
            stream = null;
            verifyPaths();
            if (!json.equals(new String(store.readFully(), StandardCharsets.UTF_8))) throw new IOException("Stored bot did not match the saved revision");
        } catch (IOException | JSONException failure) {
            if (stream != null) store.failWrite(stream);
            throw CrewProfile.invalid("could not verify the saved bot. Reopen it before retrying.", failure);
        }
    }

    private void verifyPaths() throws IOException {
        verify(this.root);
        verify(this.target);
        verify(new File(this.target.getPath() + ".bak"));
        verify(new File(this.target.getPath() + ".new"));
    }

    private static void verify(File file) throws IOException {
        try {
            String destination = Os.readlink(file.getAbsolutePath());
            if (destination != null) {
                throw CrewProfile.invalid("configuration symlinks are not allowed");
            }
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ENOENT && failure.errno != OsConstants.EINVAL) {
                throw new IOException("Could not inspect profile configuration path", failure);
            }
        }
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) {
            throw CrewProfile.invalid("configuration symlinks are not allowed");
        }
    }
}

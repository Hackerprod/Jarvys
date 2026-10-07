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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class CrewProfileRepository {
    private static final Object LOCK = new Object();
    public static final int SCHEMA_VERSION = 2;
    private final File root;
    private final File target;

    public CrewProfileRepository(Context context) {
        this(context.getApplicationContext().getFilesDir());
    }

    public CrewProfileRepository(File appFilesDirectory) {
        try {
            this.root = new File(appFilesDirectory.getCanonicalFile(), "crew_profiles");
            this.target = new File(this.root, "profiles.json");
            verifyPaths();
        } catch (IOException failure) {
            throw CrewProfile.invalid("could not verify private configuration location", failure);
        }
    }

    public CrewProfile codingProfile() {
        return profile(CrewRoleTemplates.CODING);
    }

    public CrewProfile profile(String id) {
        CrewProfile found;
        synchronized (LOCK) {
            found = load().get(id);
            if (found == null) {
                throw CrewProfile.invalid("unknown profile: " + id);
            }
        }
        return found;
    }

    public List<CrewProfile.CatalogEntry> catalog() {
        List<CrewProfile.CatalogEntry> listUnmodifiableList;
        synchronized (LOCK) {
            List<CrewProfile.CatalogEntry> entries = new ArrayList<>();
            for (CrewProfile profile : load().values()) {
                entries.add(profile.catalogEntry());
            }
            listUnmodifiableList = Collections.unmodifiableList(entries);
        }
        return listUnmodifiableList;
    }

    public CrewRole resolveRole(String id, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        return profile(id).resolveRole(approvedCapabilities, availableSkillIds);
    }

    public List<CrewRole> resolveRoles(Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        List<CrewRole> listUnmodifiableList;
        synchronized (LOCK) {
            List<CrewRole> result = new ArrayList<>();
            for (CrewProfile profile : load().values()) {
                result.add(profile.resolveRole(approvedCapabilities, availableSkillIds));
            }
            listUnmodifiableList = Collections.unmodifiableList(result);
        }
        return listUnmodifiableList;
    }

    public CrewProfile save(CrewProfile edited, Collection<String> approvedCapabilities, Collection<String> availableSkillIds) {
        CrewProfile saved;
        if (edited == null) {
            throw CrewProfile.invalid("profile is required");
        }
        edited.validateAvailability(approvedCapabilities, availableSkillIds);
        synchronized (LOCK) {
            Map<String, CrewProfile> profiles = load();
            CrewProfile previous = profiles.get(edited.id);
            if (previous == null) {
                throw CrewProfile.invalid("unknown profile: " + edited.id);
            }
            if (previous.version != edited.version) {
                throw CrewProfile.invalid("profile changed while editing. Reopen settings before saving.");
            }
            if (previous.version == Integer.MAX_VALUE) {
                throw CrewProfile.invalid("profile version cannot be incremented");
            }
            saved = edited.withVersion(previous.version + 1);
            profiles.put(saved.id, saved);
            persist(profiles.values());
        }
        return saved;
    }

    private Map<String, CrewProfile> load() {
        try {
            verifyPaths();
            if (!this.target.exists() && !new File(this.target.getPath() + ".bak").exists()) {
                Map<String, CrewProfile> defaults = new LinkedHashMap<>();
                CrewProfile coding = CrewProfile.codingDefault();
                defaults.put(coding.id, coding);
                return defaults;
            }
            String json = new String(new AtomicFile(this.target).readFully(), StandardCharsets.UTF_8);
            return decode(json);
        } catch (IOException failure) {
            throw CrewProfile.invalid("could not read profiles; stored configuration was preserved", failure);
        }
    }

    public static Map<String, CrewProfile> decode(String source) {
        try {
            JSONObject data = new JSONObject(source);
            CrewProfile.exactFields(data, "schemaVersion", "profiles");
            int schema = CrewProfile.positiveInteger(data, "schemaVersion");
            if (schema != 1 && schema != 2) {
                throw CrewProfile.invalid("unsupported repository schema version: " + schema);
            }
            Object raw = data.opt("profiles");
            if (!(raw instanceof JSONArray)) {
                throw CrewProfile.invalid("profiles must be an array");
            }
            JSONArray rows = (JSONArray)raw;
            Map<String, CrewProfile> profiles = new LinkedHashMap<>();
            for (int index = 0; index < rows.length(); index++) {
                Object row = rows.opt(index);
                if (!(row instanceof JSONObject)) {
                    throw CrewProfile.invalid("each profile must be an object");
                }
                CrewProfile profile = schema == 1 ? CrewProfile.fromLegacyJson((JSONObject)row) : CrewProfile.fromJson((JSONObject)row);
                if (profiles.put(profile.id, profile) != null) {
                    throw CrewProfile.invalid("duplicate profile id: " + profile.id);
                }
            }
            return profiles;
        } catch (JSONException failure) {
            throw CrewProfile.invalid("invalid repository JSON; stored configuration was preserved", failure);
        }
    }

    private void persist(Collection<CrewProfile> profiles) {
        AtomicFile store = new AtomicFile(this.target);
        FileOutputStream stream = null;
        try {
            verifyPaths();
            if (!this.root.isDirectory() && !this.root.mkdirs()) {
                throw new IOException("Could not create profile storage");
            }
            verifyPaths();
            JSONArray rows = new JSONArray();
            for (CrewProfile profile : profiles) rows.put(profile.toJson());
            String json = new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("profiles", rows).toString();
            stream = store.startWrite();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
            store.finishWrite(stream);
            stream = null;
            verifyPaths();
            if (!json.equals(new String(store.readFully(), StandardCharsets.UTF_8))) {
                throw new IOException("Stored profile did not match the saved revision");
            }
        } catch (IOException | JSONException failure) {
            if (stream != null) store.failWrite(stream);
            throw CrewProfile.invalid("could not verify the saved profile. Reopen settings before retrying.", failure);
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

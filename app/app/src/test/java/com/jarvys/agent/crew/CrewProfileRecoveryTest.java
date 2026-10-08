package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewProfileRecoveryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void codingDefaultRetainsExplicitCapabilitiesAndProjectScope() {
        CrewProfile profile = CrewProfile.codingDefault();
        assertEquals("coding", profile.id);
        assertEquals(3, profile.version);
        assertEquals(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT, profile.workspaceMode);
        assertFalse(profile.capabilities.contains("project_exec"));
        assertTrue(profile.capabilities.contains("coding_patch"));
        CrewRole role = profile.resolveRole(profile.capabilities, profile.skillIds);
        assertEquals(profile.prompt, role.missionPrompt);
        assertEquals(profile.version, role.profileVersion);
        assertEquals(profile.workspaceMode, role.withTools(Collections.singletonList("read")).workspaceMode);
        assertThrows(UnsupportedOperationException.class, ()->role.tools.add("project_exec"));
    }

    @Test
    public void schemaOneMigrationPreservesLegacyWorkspaceAndVersion() throws Exception {
        JSONObject profile = CrewProfile.codingDefault().withVersion(7).toJson();
        profile.remove("workspaceMode");
        String source = new JSONObject().put("schemaVersion", 1).put("profiles", new JSONArray().put(profile)).toString();
        Map<String, CrewProfile> decoded = CrewProfileRepository.decode(source);
        CrewProfile migrated = decoded.values().stream().filter(value -> value.id.startsWith("custom-legacy-coding-")).findFirst().get();
        assertEquals(CrewProfile.WorkspaceMode.LEGACY_CHAT, migrated.workspaceMode);
        assertEquals(7, migrated.version);
        assertEquals(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT, decoded.get("coding").workspaceMode);
        assertThrows(IllegalArgumentException.class, ()->migrated.resolveRole(migrated.capabilities, Collections.emptyList()));
        assertEquals(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT, CrewProfileRepository.decode(new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(CrewProfile.codingDefault().toJson())).toString()).get("coding").workspaceMode);
    }

    @Test
    public void rejectsUnknownFieldsInvalidVersionsAndDuplicateIdentities() throws Exception {
        JSONObject profile = CrewProfile.codingDefault().toJson();
        profile.put("unexpectedApproval", true);
        assertThrows(IllegalArgumentException.class, ()->CrewProfile.fromJson(profile));
        JSONObject fractional = CrewProfile.codingDefault().toJson().put("version", 1.5);
        assertThrows(IllegalArgumentException.class, ()->CrewProfile.fromJson(fractional));
        JSONObject duplicate = new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(CrewProfile.codingDefault().toJson()).put(CrewProfile.codingDefault().toJson()));
        assertThrows(IllegalArgumentException.class, ()->CrewProfileRepository.decode(duplicate.toString()));
        JSONObject future = new JSONObject().put("schemaVersion", 4).put("profiles", new JSONArray());
        assertThrows(IllegalArgumentException.class, ()->CrewProfileRepository.decode(future.toString()));
        assertThrows(IllegalArgumentException.class, ()->new CrewProfile("coding", 1, "Name", "Description", "Prompt", Collections.emptyList(), Arrays.asList("read", "read")));
    }

    @Test
    public void executionSkillsAndWorkspaceCannotBroadenAvailability() {
        CrewProfile exec = projectProfile(Collections.emptyList(), Collections.singletonList("project_exec"));
        assertThrows(IllegalArgumentException.class, ()->exec.validateAvailability(exec.capabilities, exec.skillIds));
        CrewProfile skills = projectProfile(Collections.singletonList("skill.one"), Collections.singletonList("read"));
        assertThrows(IllegalArgumentException.class, ()->skills.validateAvailability(skills.capabilities, skills.skillIds));
        CrewProfile valid = projectProfile(Collections.singletonList("skill.one"), Arrays.asList("project_exec", "project_jobs", "read_skill"));
        valid.validateAvailability(valid.capabilities, valid.skillIds);
        assertThrows(IllegalArgumentException.class, ()->valid.validateAvailability(Collections.singletonList("read_skill"), valid.skillIds));
        assertThrows(IllegalArgumentException.class, ()->valid.validateAvailability(valid.capabilities, Collections.emptyList()));
        assertFalse(CrewProfile.isWorkspaceCapabilityCompatible(CrewProfile.WorkspaceMode.LEGACY_CHAT, "project_jobs"));
        assertFalse(CrewProfile.isWorkspaceCapabilityCompatible(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT, "preview_workspace"));
    }

    @Test
    public void savingCustomChecksRevisionAndWritesSchemaThreeWithoutResettingCorruptEvidence() throws Exception {
        File root = temporary.newFolder();
        CrewProfileRepository repository = new CrewProfileRepository(root);
        CrewProfile initial = CrewProfile.codingDefault().withIdentity(CrewProfileRepository.newCustomId()).withVersion(1);
        repository.create(initial, initial.capabilities, initial.skillIds);
        CrewProfile saved = repository.save(initial, initial.capabilities, initial.skillIds);
        assertEquals(2, saved.version);
        assertEquals(2, repository.profile(initial.id).version);
        assertEquals(3, repository.codingProfile().version);
        assertThrows(IllegalArgumentException.class, ()->repository.save(initial, initial.capabilities, initial.skillIds));
        File file = new File(root, "crew_profiles/profiles.json");
        assertEquals(3, new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getInt("schemaVersion"));
        Files.write(file.toPath(), "broken evidence".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, repository::codingProfile);
        assertEquals("broken evidence", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void configurationSymlinkCannotRedirectWrites() throws Exception {
        File root = temporary.newFolder();
        File elsewhere = temporary.newFolder();
        Files.createSymbolicLink(new File(root, "crew_profiles").toPath(), elsewhere.toPath());
        assertThrows(IllegalArgumentException.class, ()->new CrewProfileRepository(root));
        assertEquals(0, elsewhere.list().length);
    }

    private static CrewProfile projectProfile(java.util.List<String> skills, java.util.List<String> capabilities) {
        return new CrewProfile("coding", 1, "Coding", "Description", "Prompt", skills, capabilities, CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
    }
}

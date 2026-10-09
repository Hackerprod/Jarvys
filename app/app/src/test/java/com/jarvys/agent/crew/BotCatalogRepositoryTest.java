package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
public class BotCatalogRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String ICON = "12345678-1234-1234-1234-123456789abc.png";

    @Test public void templatesAreImmutableAtEveryRepositoryMutationEntry() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        assertEquals(2, repository.definitions().size());
        for (BotDefinition template : repository.definitions()) {
            assertTrue(template.builtIn);
            assertTrue(template.enabled);
            assertThrows(IllegalArgumentException.class, () -> repository.create(template.profile, template.profile.capabilities, template.profile.skillIds));
            assertThrows(IllegalArgumentException.class, () -> repository.save(template.profile, template.profile.capabilities, template.profile.skillIds));
            BotDefinition forged = new BotDefinition(template.profile, template.revision, true, false, "");
            assertThrows(IllegalArgumentException.class, () -> repository.save(forged, template.profile.capabilities, template.profile.skillIds));
            assertThrows(IllegalArgumentException.class, () -> repository.setEnabled(template.id, template.revision, false));
            assertThrows(IllegalArgumentException.class, () -> repository.setIcon(template.id, template.revision, ICON));
        }
        assertFalse(repository.profile(CrewRoleTemplates.ANDROID_USE).capabilities.contains("adb"));
        assertFalse(repository.profile(CrewRoleTemplates.ANDROID_USE).capabilities.contains("python"));
    }

    @Test public void customIdentityEnabledAndMetadataSurviveFreshRepositoryInstances() throws Exception {
        File root = temporary.newFolder();
        CrewProfileRepository repository = new CrewProfileRepository(root);
        BotDefinition created = create(repository);
        BotDefinition icon = repository.setIcon(created.id, created.revision, ICON);
        assertEquals(created.profile.version, icon.profile.version);
        assertEquals(created.revision + 1, icon.revision);
        BotDefinition disabled = repository.setEnabled(created.id, icon.revision, false);
        CrewProfileRepository restarted = new CrewProfileRepository(root);
        BotDefinition stored = restarted.definition(created.id);
        assertEquals(disabled.revision, stored.revision);
        assertEquals(disabled.profile.version, stored.profile.version);
        assertEquals(ICON, stored.iconRef);
        assertFalse(stored.enabled);
        assertFalse(restarted.catalog().stream().anyMatch(entry -> entry.id.equals(created.id)));
        assertThrows(IllegalArgumentException.class, () -> restarted.resolveRole(created.id, Collections.emptyList(), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> restarted.setIcon(created.id, stored.revision, ""));
        BotDefinition enabled = restarted.setEnabled(created.id, stored.revision, true);
        assertEquals(created.id, restarted.resolveRole(enabled.id, Collections.emptyList(), Collections.emptyList()).id);
    }

    @Test public void revisionsPreventStaleWritesWithoutLosingUnrelatedIconMetadata() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition first = create(repository);
        BotDefinition icon = repository.setIcon(first.id, first.revision, ICON);
        assertThrows(IllegalArgumentException.class, () -> repository.save(first, Collections.emptyList(), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> repository.setEnabled(first.id, first.revision, false));
        CrewProfile saved = repository.save(first.profile, Collections.emptyList(), Collections.emptyList());
        assertEquals(2, saved.version);
        assertEquals(ICON, repository.definition(first.id).iconRef);
        assertEquals(icon.revision + 1, repository.definition(first.id).revision);
        assertThrows(IllegalArgumentException.class, () -> repository.save(first.profile, Collections.emptyList(), Collections.emptyList()));
        BotDefinition current = repository.definition(first.id);
        repository.setEnabled(first.id, current.revision, false);
        assertThrows(IllegalArgumentException.class, () -> repository.save(current.profile, Collections.emptyList(), Collections.emptyList()));
    }

    @Test public void migrationPreservesLegacyCodingAsDeterministicEditableCloneExactlyOnce() throws Exception {
        File root = temporary.newFolder();
        CrewProfile previous = new CrewProfile("coding", 7, "My Coding", "Custom review", "Keep my carefully edited instructions.",
                Collections.singletonList("skill.review"), Arrays.asList("read", "read_skill"), CrewProfile.WorkspaceMode.LEGACY_CHAT);
        String legacy = new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(previous.toJson())).toString();
        Map<String, BotDefinition> decoded = CrewProfileRepository.decodeDefinitions(legacy);
        BotDefinition clone = decoded.values().stream().filter(value -> !value.builtIn).findFirst().get();
        assertTrue(clone.id.startsWith("custom-legacy-coding-"));
        assertEquals(clone.id, CrewProfileRepository.decodeDefinitions(legacy).values().stream().filter(value -> !value.builtIn).findFirst().get().id);
        assertEquals(previous.prompt, clone.profile.prompt);
        assertEquals(previous.name, clone.profile.name);
        assertEquals(previous.description, clone.profile.description);
        assertEquals(previous.version, clone.profile.version);
        assertEquals(previous.capabilities, clone.profile.capabilities);
        assertEquals(previous.skillIds, clone.profile.skillIds);
        assertEquals(previous.workspaceMode, clone.profile.workspaceMode);
        File target = new File(root, "crew_profiles/profiles.json");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
        CrewProfileRepository repository = new CrewProfileRepository(root);
        assertEquals(3, repository.definitions().size());
        assertEquals(3, new CrewProfileRepository(root).definitions().size());
        assertEquals(CrewProfileRepository.SCHEMA_VERSION, new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)).getInt("schemaVersion"));
        assertEquals(CrewProfile.codingDefault().prompt, repository.codingProfile().prompt);
        assertFalse(repository.definition(clone.id).builtIn);
        assertEquals(8, repository.save(clone.profile, previous.capabilities, previous.skillIds).version);
    }

    @Test public void legacyCustomPrefixEdgeIdsMigrateToValidStableClonesAndReload() throws Exception {
        File root = temporary.newFolder();
        JSONArray rows = new JSONArray();
        for (String id : Arrays.asList("custom-", "custom--helper", "custom-_helper", "custom-.helper", "custom-valid")) {
            rows.put(new CrewProfile(id, 3, "Legacy " + id, "Legacy metadata", "Preserve " + id,
                    Collections.emptyList(), Collections.emptyList()).toJson());
        }
        String legacy = new JSONObject().put("schemaVersion", 2).put("profiles", rows).toString();
        File target = new File(root, "crew_profiles/profiles.json");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
        List<BotDefinition> first = new CrewProfileRepository(root).definitions();
        List<BotDefinition> reopened = new CrewProfileRepository(root).definitions();
        assertEquals(7, reopened.size());
        for (int index = 0; index < first.size(); index++) {
            BotDefinition before = first.get(index);
            BotDefinition after = reopened.get(index);
            assertEquals(before.id, after.id);
            assertEquals(before.profile.prompt, after.profile.prompt);
            if (!after.builtIn) {
                assertTrue(after.id, after.id.matches("custom-[a-z0-9][a-z0-9._-]*"));
                assertEquals(3, after.profile.version);
            }
        }
        assertTrue(reopened.stream().anyMatch(value -> value.id.equals("custom-valid")));
        assertEquals(4, reopened.stream().filter(value -> value.id.startsWith("custom-legacy-")).count());
    }

    @Test public void deterministicMigrationIdCollisionRejectsWithoutOverwritingLegacyEvidence() throws Exception {
        File root = temporary.newFolder();
        CrewProfile unusual = new CrewProfile("custom-", 1, "Old bot", "Old metadata", "Old prompt", Collections.emptyList(), Collections.emptyList());
        String single = new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(unusual.toJson())).toString();
        String cloneId = CrewProfileRepository.decodeDefinitions(single).values().stream().filter(value -> !value.builtIn).findFirst().get().id;
        CrewProfile colliding = new CrewProfile(cloneId, 1, "Other bot", "Other metadata", "Distinct data", Collections.emptyList(), Collections.emptyList());
        String collision = new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(unusual.toJson()).put(colliding.toJson())).toString();
        File target = new File(root, "crew_profiles/profiles.json");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), collision.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new CrewProfileRepository(root).definitions());
        assertEquals(collision, new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void forgedRuntimeRowsMalformedMetadataAndExternalIconsFailClosed() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition custom = create(repository);
        for (String unsafe : Arrays.asList("../other.png", "/tmp/icon.png", "content://icons/1", "https://host/icon.png", "wrong.svg")) {
            assertThrows(IllegalArgumentException.class, () -> repository.setIcon(custom.id, custom.revision, unsafe));
        }
        JSONObject forged = new BotDefinition(CrewProfile.codingDefault(), 1, false, false, "").toJson();
        assertThrows(IllegalArgumentException.class, () -> CrewProfileRepository.decodeDefinitions(schema(forged)));
        JSONObject wrongType = custom.toJson().put("enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> CrewProfileRepository.decodeDefinitions(schema(wrongType)));
        JSONObject extra = custom.toJson().put("approved", true);
        assertThrows(IllegalArgumentException.class, () -> CrewProfileRepository.decodeDefinitions(schema(extra)));
    }

    @Test public void callerSuppliedCatalogNeverGrantsCaptainToolsOrMissingSkills() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        for (String forbidden : Arrays.asList("delete", "crew_spawn", "crew_send", "crew_stop", "generate_bot_icon", "list_bots")) {
            CrewProfile draft = profile(Collections.singletonList(forbidden), Collections.emptyList());
            assertThrows(IllegalArgumentException.class, () -> repository.create(draft, draft.capabilities, draft.skillIds));
        }
        CrewProfile skill = profile(Collections.singletonList("read_skill"), Collections.singletonList("disabled.skill"));
        assertThrows(IllegalArgumentException.class, () -> repository.create(skill, skill.capabilities, Collections.emptyList()));
        CrewProfile outside = profile(Collections.singletonList("unavailable_tool"), Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> repository.create(outside, Collections.emptyList(), Collections.emptyList()));
        assertEquals(2, repository.definitions().size());
    }

    private static String schema(JSONObject row) throws Exception {
        return new JSONObject().put("schemaVersion", CrewProfileRepository.SCHEMA_VERSION).put("bots", new JSONArray().put(row)).toString();
    }
    private static CrewProfile profile(List<String> capabilities, List<String> skills) {
        return new CrewProfile(CrewProfileRepository.newCustomId(), 1, "Helper", "Short catalog description", "PRIVATE CHILD INSTRUCTIONS", skills, capabilities);
    }
    private static BotDefinition create(CrewProfileRepository repository) {
        return repository.create(profile(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), Collections.emptyList());
    }
}

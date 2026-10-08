package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.BotDefinition;
import com.jarvys.agent.crew.CrewBotSnapshot;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewProfileRepository;
import com.jarvys.agent.crew.CrewRole;
import com.jarvys.agent.crew.CrewRoleTemplates;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BotCatalogCoreRuntimeTest {
    @Test public void captainCatalogOnlyContainsEnabledShortMetadataNeverChildInstructions() {
        Context context = ApplicationProvider.getApplicationContext();
        CrewProfileRepository repository = new CrewProfileRepository(context);
        CrewProfile profile = new CrewProfile(CrewProfileRepository.newCustomId(), 1, "Review Bot", "Review public evidence", "PRIVATE CHILD INSTRUCTION SENTINEL", Collections.emptyList(), Collections.emptyList());
        BotDefinition definition = repository.create(profile, Collections.emptyList(), Collections.emptyList());
        String catalog = CoreAgentRuntime.profileCatalog(repository.catalog(), 8000);
        assertTrue(catalog.contains("crew_spawn role=" + definition.id));
        assertTrue(catalog.contains(profile.description));
        assertFalse(catalog.contains(profile.prompt));
        repository.setEnabled(definition.id, definition.revision, false);
        assertFalse(CoreAgentRuntime.profileCatalog(repository.catalog(), 8000).contains(definition.id));
    }

    @Test public void versionedLegacyToolInvocationRechecksDisabledStateAndCancelsToken() {
        Context context = ApplicationProvider.getApplicationContext();
        CrewProfileRepository repository = new CrewProfileRepository(context);
        CrewProfile profile = new CrewProfile(CrewProfileRepository.newCustomId(), 1, "Legacy Helper", "Read board", "Read the shared board only", Collections.emptyList(), Collections.singletonList("board_read"));
        BotDefinition definition = repository.create(profile, profile.capabilities, profile.skillIds);
        CoreAgentRuntime runtime = new CoreAgentRuntime(context, "catalog-legacy-guard", Collections.emptyList());
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        try (CrewManager manager = manager("catalog-legacy-guard", null, null)) {
            CrewManager.Bot bot = historical(manager, profile.resolveRole(profile.capabilities, profile.skillIds));
            CoreToolRegistry tools = runtime.createCrewBotTools(context, "catalog-legacy-guard", empty, bot, manager);
            assertTrue(tools.names().contains("board_read"));
            repository.setEnabled(definition.id, definition.revision, false);
            CoreToolResult result = tools.invoke("board_read", Collections.emptyMap(), bot.token);
            assertFalse(result.success);
            assertTrue(bot.token.isCancellationRequested());
        }
    }

    @Test public void oldCustomizedCodingCheckpointCannotResumeAsImmutableRuntime() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CrewProfile legacy = new CrewProfile("coding", 2, "My private coder", "Customized instructions", "DO NOT LOSE THIS OLD PROMPT", Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        File target = new File(context.getFilesDir(), "crew_profiles/profiles.json");
        target.getParentFile().mkdirs();
        Files.write(target.toPath(), new JSONObject().put("schemaVersion", 2).put("profiles", new JSONArray().put(legacy.toJson())).toString().getBytes(StandardCharsets.UTF_8));
        CrewProfileRepository repository = new CrewProfileRepository(context);
        String migratedId = repository.definitions().stream().filter(row -> !row.builtIn).findFirst().get().id;
        CoreAgentRuntime runtime = new CoreAgentRuntime(context, "catalog-old-coding", Collections.emptyList());
        try (CrewManager manager = manager("catalog-old-coding", null, null)) {
            CrewManager.Bot bot = historical(manager, legacy.resolveRole(Collections.emptyList(), Collections.emptyList()));
            IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> runtime.currentResumeRole(bot));
            assertTrue(rejected.getMessage(), rejected.getMessage().contains(migratedId));
            assertEquals("coding", bot.role.id);
            assertEquals(legacy.prompt, bot.role.missionPrompt);
            assertEquals(0, bot.completedCycles());
        }
    }

    @Test public void nativeAndroidTemplateUsesOnlySuppliedNativeConnectorNames() {
        Context context = ApplicationProvider.getApplicationContext();
        CoreAgentRuntime runtime = new CoreAgentRuntime(context, "catalog-android", Collections.emptyList());
        CrewProfile android = CrewProfile.androidDefault();
        CrewRole role = runtime.resolveCrewProfile(Arrays.asList("calendar_search_events", "mcp_untrusted_shell"), Collections.singletonList("calendar_search_events"), CrewRoleTemplates.ANDROID_USE);
        assertTrue(role.tools.contains("calendar_search_events"));
        assertTrue(role.tools.containsAll(android.capabilities));
        assertFalse(role.tools.contains("mcp_untrusted_shell"));
        assertFalse(role.tools.contains("adb"));
        assertFalse(role.tools.contains("python"));
        assertEquals(1, role.profileVersion);
    }

    @Test public void actualWorkingCountsUseStableRoleIdentityAndIgnoreCompletedRestoredState() throws Exception {
        Field field = CoreAgentRuntime.class.getDeclaredField("CREWS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, CrewManager> crews = (Map<String, CrewManager>) field.get(null);
        String session = "catalog-working-" + System.nanoTime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CrewManager manager = manager(session, started, release);
        crews.put(session, manager);
        try {
            CrewProfile profile = new CrewProfile(CrewProfileRepository.newCustomId(), 1, "Same display name", "Scope", "Work", Collections.emptyList(), Collections.emptyList());
            manager.configureProfiles(id -> profile.resolveRole(Collections.emptyList(), Collections.emptyList()));
            assertFalse(CoreAgentRuntime.workingBotCounts().containsKey(profile.id));
            CrewManager.Bot bot = manager.spawn(profile.id, "Actual mission", null, "Completely different display name");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(1), CoreAgentRuntime.workingBotCounts().get(profile.id));
            assertFalse(CoreAgentRuntime.workingBotCounts().containsKey(bot.name));
            release.countDown();
            bot.awaitTermination();
            assertFalse(CoreAgentRuntime.workingBotCounts().containsKey(profile.id));
        } finally { release.countDown(); crews.remove(session, manager); manager.close(); }
    }

    private static CrewManager manager(String session, CountDownLatch started, CountDownLatch release) {
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        return new CrewManager(session, empty, (bot, crew) -> empty,
                (bot, tools, incoming) -> new CoreAgentLoop((transcript, prompt, names, token) -> {
                    if (started != null) started.countDown();
                    if (release != null) try { release.await(); } catch (InterruptedException interrupted) { token.throwIfCancelled(); }
                    return new ModelReply("done", Collections.emptyList());
                }, tools, "", session, CorePromptBudget.standard(), null, CoreAgentLoop.Limits.UNBOUNDED, incoming, null), null);
    }
    private static CrewManager.Bot historical(CrewManager manager, CrewRole role) {
        CrewBotSnapshot bot = new CrewBotSnapshot("bot-historical", role.id, role.name, role.name, role.colorKey, "Saved mission", "INTERRUPTED", "", "", "", role.tools, 1, 0);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("mission", manager.conversationId(), "past", "Saved mission", "INTERRUPTED", "", 1, 0, Collections.singletonList(bot), Collections.emptyList());
        return manager.restoreBot(mission, bot, role, CoreAgentLoop.Checkpoint.empty(), new JSONObject(), "scope", Collections.emptyList(), 0, Collections.emptyList(), Collections.emptyList(), "");
    }
}

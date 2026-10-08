package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.connectors.ConnectorRegistry;
import com.jarvys.agent.crew.CrewRoleTemplates;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BotCatalogToolScopeTest {
    private Context context;
    private ProviderSettings settings;
    private SecretStore secrets;
    private Object previousSecrets;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        secrets = new SecretStore(context.getSharedPreferences("bot-tool-scope", Context.MODE_PRIVATE));
        secrets.saveCodexTokens("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3600000, "fixture-account");
        Field singleton = SecretStore.class.getDeclaredField("singleton");
        singleton.setAccessible(true);
        previousSecrets = singleton.get(null);
        singleton.set(null, secrets);
    }

    @After public void cleanup() throws Exception {
        secrets.clearCodexTokens();
        settings.setProvider(ProviderSettings.Provider.OPENROUTER);
        Field singleton = SecretStore.class.getDeclaredField("singleton");
        singleton.setAccessible(true);
        singleton.set(null, previousSecrets);
    }

    @Test public void narrowedMainRuntimeDoesNotRegainUnselectedBotToolsOrGuidance() throws Exception {
        assertTrue(BotIconGenerationTool.isAvailable(context, settings, 0, "scope-main"));
        CoreAgentRuntime narrowed = runtime("scope-main", 0, Collections.emptyList());
        assertFalse(narrowed.createTools().names().contains(BotCatalogTool.NAME));
        assertFalse(narrowed.createTools().names().contains(BotCreationTool.NAME));
        assertFalse(narrowed.instructions().contains("use create_bot"));
        assertFalse(narrowed.createTools().names().contains(BotIconGenerationTool.NAME));
        assertFalse(narrowed.instructions().contains("Bots catalog: use list_bots"));

        CoreAgentRuntime catalogOnly = runtime("scope-catalog", 0, Collections.singletonList(BotCatalogTool.NAME));
        assertTrue(catalogOnly.createTools().names().contains(BotCatalogTool.NAME));
        assertFalse(catalogOnly.createTools().names().contains(BotIconGenerationTool.NAME));
        CoreAgentRuntime both = runtime("scope-both", 0, Arrays.asList(BotCatalogTool.NAME, BotIconGenerationTool.NAME, BotCreationTool.NAME));
        assertTrue(both.createTools().names().containsAll(Arrays.asList(BotCatalogTool.NAME, BotIconGenerationTool.NAME, BotCreationTool.NAME)));
    }

    @Test public void mainOnlyBotToolsStayOutOfEveryChildInventoryEvenExplicitSelections() throws Exception {
        CoreAgentRuntime main = runtime("scope-main", 0, null);
        List<CoreTool> bots = Arrays.asList(new BotCatalogTool(context, "scope-main"),
                new BotIconGenerationTool(context, "scope-main", settings), new BotCreationTool(context, "scope-main"));
        for (boolean includeDelegate : new boolean[]{true, false}) {
            List<String> names = main.createToolsNames(bots, includeDelegate);
            assertFalse(names.contains(BotCatalogTool.NAME));
            assertFalse(names.contains(BotCreationTool.NAME));
            assertFalse(names.contains(BotIconGenerationTool.NAME));
        }
        CoreToolRegistry registry = new CoreToolRegistry(bots);
        assertTrue(registry.forDelegatedAgent().names().isEmpty());
        assertTrue(CoreAgentRuntime.crewBotCapabilityScope(registry).names().isEmpty());
        for (String name : Arrays.asList(BotCatalogTool.NAME, BotIconGenerationTool.NAME, BotCreationTool.NAME)) {
            assertThrows(IllegalArgumentException.class, () -> CrewRoleTemplates.custom("Forbidden", "Mission", Collections.singletonList(name), registry));
        }
        for (String session : Arrays.asList("scope-child", ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID)) {
            int depth = "scope-child".equals(session) ? 1 : 0;
            CoreAgentRuntime isolated = runtime(session, depth, Arrays.asList(BotCatalogTool.NAME, BotIconGenerationTool.NAME, BotCreationTool.NAME));
            assertFalse(isolated.createTools().names().contains(BotCatalogTool.NAME));
            assertFalse(isolated.createTools().names().contains(BotCreationTool.NAME));
            assertFalse(isolated.createTools().names().contains(BotIconGenerationTool.NAME));
            assertFalse(isolated.instructions().contains("Bots catalog: use list_bots"));
        }
    }

    private CoreAgentRuntime runtime(String session, int depth, List<String> allowed) throws Exception {
        Constructor<CoreAgentRuntime> constructor = CoreAgentRuntime.class.getDeclaredConstructor(Context.class, String.class,
                List.class, List.class, List.class, List.class, List.class, ConnectorRegistry.class,
                int.class, CorePromptBudget.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context, session, Collections.emptyList(), allowed, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null, depth, CorePromptBudget.standard(), true, false);
    }
}

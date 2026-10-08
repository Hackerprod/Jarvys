package com.jarvys.agent

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNavigationBackPolicyTest {
    @Test fun explicitNavigationGraphHasOnlyChatAsRootAndBackForEveryOtherRoute() {
        assertEquals(listOf(
            "chat", "settings", "settings/preferences", "settings/archived-chats", "scheduled-tasks",
            "providers", "providers/openai", "providers/openrouter", "providers/custom", "providers/service/{serviceId}",
            "mcp", "mcp/new", "mcp/server/{serverId}", "mcp/server/{serverId}/edit",
            "skills", "connectors", "connectors/device/{connectorId}",
            "connectors/remote/{serviceId}?title={title}", "connectors/google/{serviceId}?title={title}",
            "memory", "memory/history", "memory/file?path={path}&new={new}",
            "tasks", "tasks/detail/{taskId}", "bots", "crew", "crew/{missionId}",
            "crew/{missionId}/bot/{botId}", "workspace-preview/{projectId}", "artifact-preview/{sessionId}/{artifactId}",
        ), AppNavigationBackPolicy.registeredRoutePatterns)
        assertEquals(setOf("chat"), AppNavigationBackPolicy.rootRoutes)
        AppNavigationBackPolicy.registeredRoutePatterns.forEach { route ->
            assertEquals(route != "chat", AppNavigationBackPolicy.requiresBack(route))
        }
        assertFalse(AppNavigationBackPolicy.requiresBack("chat"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        singleton.set(null, SecretStore(context.getSharedPreferences("n1a-route-graph-secrets", 0)))
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val actualGraphRoutes = activity.get().navGraphRoutePatternsForTest()
                assertEquals(AppNavigationBackPolicy.registeredRoutePatterns.toSet(), actualGraphRoutes)
            } finally {
                activity.pause().stop().destroy()
                singleton.set(null, null)
            }
        } finally {
            WorkManagerTestCleanup.close(context)
        }
    }

    @Test fun assistantSettingsRouteAndCopyAreRemovedAndProvidersRoutesAreRealDestinations() {
        val root = sourceRoot()
        assertFalse(AppNavigationBackPolicy.registeredRoutePatterns.contains("settings/assistant"))
        val settings = File(root, "com/jarvys/agent/JarvysSettingsScreen.kt").readText()
        val navigation = File(root, "com/jarvys/agent/AppNavigationBack.kt").readText()
        val main = File(root, "com/jarvys/agent/MainActivity.kt").readText()
        val english = File(root.parentFile, "res/values/strings.xml").readText()
        val spanish = File(root.parentFile, "res/values-es/strings.xml").readText()
        assertFalse(settings.contains("AssistantPage"))
        assertFalse(settings.contains("JarvysSettingsPage.ASSISTANT"))
        assertFalse(navigation.contains("SETTINGS_ASSISTANT"))
        assertFalse(main.contains("Routes.SETTINGS_ASSISTANT"))
        assertFalse(english.contains("name=\"settings_assistant\""))
        assertFalse(spanish.contains("name=\"settings_assistant\""))
        assertFalse(settings.contains("ColorModePage"))
        assertFalse(settings.contains("JarvysSettingsPage.COLOR_MODE"))
        assertFalse(navigation.contains("SETTINGS_COLOR_MODE"))
        assertFalse(main.contains("Routes.SETTINGS_COLOR_MODE"))
        assertFalse(AppNavigationBackPolicy.registeredRoutePatterns.contains("settings/color-mode"))
        assertFalse(english.contains("name=\"settings_appearance\""))
        assertFalse(spanish.contains("name=\"settings_appearance\""))
        listOf("providers/openai", "providers/openrouter", "providers/service/{serviceId}").forEach {
            assertTrue(AppNavigationBackPolicy.registeredRoutePatterns.contains(it))
            assertTrue(AppNavigationBackPolicy.requiresBack(it))
        }
        assertTrue(main.contains("providersDestinations(navController, providersRepository)"))
    }

    @Test fun quickModelSelectorUsesCurrentProviderSearchAndEffortWithoutLegacyModelCards() {
        val root = sourceRoot()
        val main = File(root, "com/jarvys/agent/MainActivity.kt").readText()
        val quick = File(root, "com/jarvys/agent/ui/shell/ModelSelectorSheet.kt").readText()
        val providers = File(root, "com/jarvys/agent/providers/ProvidersScreens.kt").readText()
        val english = File(root.parentFile, "res/values/strings.xml").readText()
        val spanish = File(root.parentFile, "res/values-es/strings.xml").readText()
        assertTrue(quick.contains("ModelSelectorSheetContent"))
        assertTrue(quick.contains("quick-model-provider-label"))
        assertTrue(quick.contains("quick-model-filter"))
        assertTrue(quick.contains("ModelEffortSlider"))
        assertFalse(quick.contains("quick-provider-dropdown"))
        listOf("JarvysGroup", "JarvysChoiceGroup", "provider_save_selection")
            .forEach { assertFalse("old model selector control remains: $it", quick.contains(it)) }
        assertFalse(providers.contains("provider_choose_model_reasoning"))
        listOf("provider_choose_model", "provider_choose_model_reasoning", "provider_save_model", "provider_save_selection", "provider_codex_models")
            .forEach {
                assertFalse(english.contains("name=\"$it\""))
                assertFalse(spanish.contains("name=\"$it\""))
            }
    }

    @Test fun activityAndConnectorSubroutesUseTheSharedLocalizedFortyEightDpBackButton() {
        val root = File(sourceRoot(), "com/jarvys/agent")
        val main = File(root, "MainActivity.kt").readText()
        val shell = File(root, "ui/shell/JarvysShellFrame.kt").readText()
        val topBar = File(root, "ui/shell/JarvysRouteTopBar.kt").readText()
        val connectors = File(root, "connectors/ConnectorsScreen.kt").readText()
        val detail = File(root, "connectors/ConnectorDetailScaffold.kt").readText()
        val shared = File(root, "AppNavigationBack.kt").readText()
        assertTrue(main.contains("AppNavigationBackPolicy.metadata(context, route"))
        assertTrue(main.contains("JarvysShellFrame("))
        assertTrue(shell.contains("JarvysRouteTopBar("))
        assertTrue(topBar.contains("JarvysTopAppBar("))
        assertTrue(topBar.contains("AppRouteBackButton(onFallback"))
        assertTrue(shared.contains("dispatcher.onBackPressed()"))
        assertFalse(connectors.contains("JarvysTopAppBar("))
        assertFalse(connectors.contains("BackHandler("))
        assertFalse(connectors.contains("ConnectorPageHeader"))
        assertTrue(main.contains("connectorsDestinations(navController, connectorRegistry)"))
        assertTrue(detail.contains("JarvysBackNavigationButton"))
        val mcp = File(root, "ui/mcp/McpWorkspaceScreens.kt").readText()
        assertFalse(mcp.contains("BackHandler(enabled = selectedServer"))
        assertEquals(1, Regex("BackHandler\\(").findAll(mcp).count())
        assertEquals(2, mcp.split("EndpointRecordRow(").size - 1)
        assertTrue(mcp.contains("customServers.filter"))
        assertFalse(mcp.contains("McpServerListRow"))
        listOf("addRequest", "selectedServerId", "onSelectedServerIdChange", "onEditorStateChange")
            .forEach { assertFalse("legacy MCP navigation state remains: $it", mcp.contains(it)) }
        assertFalse(main.contains("mcpAddRequest"))
        assertFalse(main.contains("selectedMcpServerId"))
        assertFalse(main.contains("mcpEditorActive"))
        val memory = File(root, "MemorySettingsScreen.kt").readText()
        assertEquals(1, Regex("BackHandler\\(").findAll(memory).count())
        assertFalse(memory.contains("memoryInternalScreen"))
        assertFalse(memory.contains("historyMode"))
        assertTrue(main.contains("memoryDestinations("))
        val navigation = File(root, "memory/MemoryNavigation.kt").readText()
        assertTrue(navigation.contains("AppNavigationBackPolicy.MEMORY_HISTORY"))
        assertTrue(navigation.contains("AppNavigationBackPolicy.MEMORY_FILE"))
        assertTrue(shared.contains(".size(48.dp)"))
        assertTrue(shared.contains("R.string.drawer_back"))
    }

    private fun sourceRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not find app/src/main/java from ${working.path}")
    }
}

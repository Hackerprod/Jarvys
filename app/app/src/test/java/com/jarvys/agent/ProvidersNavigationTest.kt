package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.jarvys.agent.providers.ProvidersRepository
import com.jarvys.agent.providers.CodexDeviceCodeState
import com.jarvys.agent.providers.CodexDeviceCodeFlow
import com.jarvys.agent.providers.ProviderServiceRegistry
import com.jarvys.agent.providers.providersDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.unit.dp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvidersNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var repository: ProvidersRepository
    private val nav = AtomicReference<NavHostController>()

    @Before fun prepareRepository() {
        val context = RuntimeEnvironment.getApplication()
        ProvidersRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("r1-provider-secrets", 0).edit().clear().commit()
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
            .set(null, SecretStore(context.getSharedPreferences("r1-provider-secrets", 0)))
        context.getSharedPreferences("jarvys_provider_settings", 0).edit().clear().commit()
        repository = ProvidersRepository.get(context).also { it.refresh() }
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
    }

    private fun showGraph() {
        compose.setContent {
            val context = LocalContext.current
            MaterialTheme {
                val controller = rememberNavController()
                nav.set(controller)
                val entry by controller.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: AppNavigationBackPolicy.PROVIDERS
                val serviceId = entry?.arguments?.getString("serviceId")
                val title = when (route) {
                    AppNavigationBackPolicy.PROVIDERS -> context.getString(R.string.settings_providers)
                    AppNavigationBackPolicy.PROVIDERS_OPENAI -> context.getString(R.string.provider_openai_name)
                    AppNavigationBackPolicy.PROVIDERS_OPENROUTER -> context.getString(R.string.provider_openrouter_name)
                    AppNavigationBackPolicy.PROVIDERS_CUSTOM -> context.getString(R.string.provider_custom_endpoint_name)
                    AppNavigationBackPolicy.PROVIDERS_SERVICE -> context.getString(R.string.web_search_exa_name)
                    else -> context.getString(R.string.drawer_settings)
                }
                Scaffold(topBar = {
                    JarvysTopAppBar(
                        title = { Text(title, modifier = Modifier.testTag("r1-route-title")) },
                        navigationIcon = {
                            if (route == AppNavigationBackPolicy.PROVIDERS) Spacer(Modifier.size(48.dp))
                            else AppRouteBackButton(onFallback = { controller.popBackStack() })
                        },
                    )
                }) { padding ->
                    NavHost(controller, startDestination = AppNavigationBackPolicy.PROVIDERS,
                        modifier = Modifier.padding(padding)) {
                        providersDestinations(controller, repository)
                        composable("settings") { Text("Settings", modifier = Modifier.testTag("settings-route")) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun realProviderRoutesReturnWithArrowAndSystemBackAndReentryStartsAtList() {
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS_OPENAI, nav.get().currentDestination?.route)
        compose.onNodeWithTag("r1-route-title").assertTextEquals(compose.activity.getString(R.string.provider_openai_name))
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)

        compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_name)).performClick()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)

        compose.onNodeWithText(compose.activity.getString(R.string.provider_custom_endpoint_name)).performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS_CUSTOM, nav.get().currentDestination?.route)
        compose.onNodeWithTag("custom-endpoint-url").assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)

        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_name)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS_SERVICE, nav.get().currentDestination?.route)
        assertEquals(ProviderServiceRegistry.EXA_ID, nav.get().currentBackStackEntry?.arguments?.getString("serviceId"))
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)

        nav.get().navigate("settings")
        compose.waitForIdle()
        nav.get().navigate(AppNavigationBackPolicy.PROVIDERS)
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).assertIsDisplayed()
        assertTrue(AppNavigationBackPolicy.providerService("exa/other").contains("exa%2Fother"))
    }

    @Test fun providerServiceRegistryListsExaAndUnknownIdsReturnToProvidersWithoutCrashing() {
        assertEquals(listOf(ProviderServiceRegistry.EXA_ID), ProviderServiceRegistry.services.map { it.id })
        assertEquals(R.string.web_search_exa_name,
            ProviderServiceRegistry.find(ProviderServiceRegistry.EXA_ID)?.titleResource)
        assertNull(ProviderServiceRegistry.find("not-registered"))
        showGraph()
        nav.get().navigate(AppNavigationBackPolicy.providerService("not-registered"))
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.PROVIDERS, nav.get().currentDestination?.route)
        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_name)).performScrollTo().assertIsDisplayed()
    }

    @Test fun customEndpointListRowFollowsOpenRouterAndPrecedesRegistryServices() {
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_name)).performScrollTo()
        val openRouter = compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_name))
            .fetchSemanticsNode().boundsInRoot
        val custom = compose.onNodeWithText(compose.activity.getString(R.string.provider_custom_endpoint_name))
            .fetchSemanticsNode().boundsInRoot
        val services = compose.onNodeWithText(compose.activity.getString(R.string.providers_services_heading))
            .fetchSemanticsNode().boundsInRoot
        val exa = compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_name))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(openRouter.top < custom.top)
        assertTrue(custom.top < services.top)
        assertTrue(services.top < exa.top)
    }

    @Test fun providerListActiveMarkerFollowsTheProviderChosenFromItsDetail() {
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
        showGraph()
        assertEquals(1, compose.onAllNodesWithText(compose.activity.getString(R.string.provider_active_tag)).fetchSemanticsNodes().size)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_use_openrouter_active)).performClick()
        compose.waitForIdle()
        assertEquals(ProviderSettings.Provider.OPENROUTER, repository.state.value.activeProvider)
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText(compose.activity.getString(R.string.provider_active_tag)).fetchSemanticsNodes().size)
    }

    @Test fun openRouterKeyCanBeSavedReplacedAndRemovedOnlyAfterConfirmation() {
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_key_label))
            .performScrollTo().performTextInput("router-key-r1")
        compose.onNodeWithText(compose.activity.getString(R.string.provider_save_api_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.openRouterConnected)
        assertEquals("router-key-r1", SecretStore.get(compose.activity).openRouterKey)

        compose.onNodeWithText(compose.activity.getString(R.string.provider_replace_key)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openrouter_key_label))
            .performScrollTo().performTextInput("replacement-key-r1")
        compose.onNodeWithText(compose.activity.getString(R.string.provider_save_replacement_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("replacement-key-r1", SecretStore.get(compose.activity).openRouterKey)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_cancel)).performClick()

        val removeLabel = compose.activity.getString(R.string.provider_remove_key)
        compose.onAllNodesWithText(removeLabel).get(0).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_remove_openrouter_title)).assertIsDisplayed()
        val removeButtons = compose.onAllNodesWithText(removeLabel).fetchSemanticsNodes()
        compose.onAllNodesWithText(removeLabel).get(removeButtons.lastIndex).performClick()
        compose.waitForIdle()
        assertFalse(repository.state.value.openRouterConnected)
        assertEquals(null, SecretStore.get(compose.activity).openRouterKey)
    }

    @Test fun exaKeyCanBeSavedAndRemovedFromItsServiceDetailWithConfirmation() {
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_name)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_key_label))
            .performScrollTo().performTextInput("exa-key-r1")
        compose.onNodeWithText(compose.activity.getString(R.string.provider_save_api_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.exaConnected)
        assertEquals("exa-key-r1", SecretStore.get(compose.activity).getConnectorSecret("web_search", "exa_api_key"))

        val removeLabel = compose.activity.getString(R.string.provider_remove_key)
        compose.onAllNodesWithText(removeLabel).get(0).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.web_search_exa_remove_title)).assertIsDisplayed()
        val removeButtons = compose.onAllNodesWithText(removeLabel).fetchSemanticsNodes()
        compose.onAllNodesWithText(removeLabel).get(removeButtons.lastIndex).performClick()
        compose.waitForIdle()
        assertFalse(repository.state.value.exaConnected)
        assertEquals(null, SecretStore.get(compose.activity).getConnectorSecret("web_search", "exa_api_key"))
    }

    @Test fun settingsModelsAndServicesStartsWithProvidersAndItsRowOpensTheListRoute() {
        val context = RuntimeEnvironment.getApplication()
        val densityScale = mutableStateOf(1f)
        val destination = AtomicReference<String>()
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, densityScale.value),
            ) {
                MaterialTheme {
                    JarvysSettingsScreen(
                        page = JarvysSettingsPage.HOME,
                        themeMode = JarvysThemeMode.SYSTEM,
                        showAgentEvents = true,
                        proactiveEnabled = false,
                        proactiveStatus = com.jarvys.agent.proactive.ProactiveStatus(enabled = false),
                        agentTimeoutSeconds = 0,
                        memoryEnabled = false,
                        memoryUsedCharacters = 0,
                        languageChoice = AppLanguageChoice.ENGLISH,
                        onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
                        onProactiveEnabledChange = {}, onRefreshProactiveStatus = {}, onAgentTimeoutChange = {},
                        onNavigateRoute = { destination.set(it) }, onMcp = {}, onSkills = {}, onConnectors = {},
                        onMemory = {}, onAccessibilitySettings = {}, onNavigate = {},
                    )
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            densityScale.value = scale
            compose.waitForIdle()
            val providers = compose.onNodeWithText(context.getString(R.string.settings_providers)).performScrollTo()
            val mcp = compose.onNodeWithText(context.getString(R.string.settings_mcp)).performScrollTo()
            val skills = compose.onNodeWithText(context.getString(R.string.settings_skills)).performScrollTo()
            val connectors = compose.onNodeWithText(context.getString(R.string.settings_connectors)).performScrollTo()
            assertTrue(providers.fetchSemanticsNode().boundsInRoot.top < mcp.fetchSemanticsNode().boundsInRoot.top)
            assertTrue(mcp.fetchSemanticsNode().boundsInRoot.top < skills.fetchSemanticsNode().boundsInRoot.top)
            assertTrue(skills.fetchSemanticsNode().boundsInRoot.top < connectors.fetchSemanticsNode().boundsInRoot.top)
            providers.performClick()
            assertEquals(AppNavigationBackPolicy.PROVIDERS, destination.get())
        }
    }

    @Test fun oauthProgressRemainsInTheAppRepositoryWhileNavigatingBetweenProviderRoutes() {
        showGraph()
        nav.get().navigate(AppNavigationBackPolicy.PROVIDERS_OPENAI)
        compose.waitForIdle()
        compose.runOnIdle { repository.beginCodexSignIn(compose.activity) }
        assertTrue("OAuth should enter its pending state", repository.state.value.codexOAuthInProgress)
        nav.get().navigate(AppNavigationBackPolicy.PROVIDERS)
        compose.waitForIdle()
        assertTrue(repository.state.value.codexOAuthInProgress)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.codexOAuthInProgress)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_cancel_signin)).performClick()
        compose.waitForIdle()
        assertFalse(repository.state.value.codexOAuthInProgress)
    }

    @Test fun openAiDetailModelAndReasoningCombosPersistAfterRouteReentry() {
        repository.selectQuickModel(ProviderSettings.Provider.OPENAI_CODEX, "gpt-5.4", "none")
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("jarvys-dropdown-field").get(1).performScrollTo().performClick()
        compose.onNodeWithTag("jarvys-dropdown-filter").performTextInput("gpt-6-astra")
        compose.onNodeWithText("GPT-6 Astra").performClick()
        compose.waitForIdle()
        assertEquals("gpt-6-astra", repository.state.value.openAiModel)
        assertEquals("medium", repository.state.value.openAiReasoningVariant)

        compose.onAllNodesWithTag("jarvys-dropdown-field").get(2).performScrollTo().performClick()
        compose.onNodeWithTag("jarvys-dropdown-filter").performTextInput("none")
        compose.onNodeWithText(compose.activity.getString(R.string.provider_no_models_found)).assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-openai-model-dropdown").assertIsDisplayed()
        compose.onNodeWithText("GPT-6 Astra").assertIsDisplayed()
        assertEquals("gpt-6-astra", ProviderSettings(compose.activity).model)
        assertEquals("medium", ProviderSettings(compose.activity).reasoningVariant)
    }

    @Test fun deviceCodeStateSurvivesLeavingAndReenteringOpenAiDetailRoute() {
        repository.selectOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.DEVICE_CODE)
        val stateField = ProvidersRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(repository) as MutableStateFlow<com.jarvys.agent.providers.ProvidersUiState>
        stateFlow.value = stateFlow.value.copy(
            openAiAuthMethod = ProviderSettings.OpenAiAuthMethod.DEVICE_CODE,
            codexDeviceCode = CodexDeviceCodeState.WaitingForCode(
                "ABCD-EFGH", CodexDeviceCodeFlow.VERIFICATION_URL, System.currentTimeMillis() + 900_000L),
        )

        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-device-code-value").assertTextEquals("ABCD-EFGH")
        compose.onNodeWithTag("provider-device-code-url").performScrollTo().assertTextEquals(CodexDeviceCodeFlow.VERIFICATION_URL)
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.codexDeviceCode is CodexDeviceCodeState.WaitingForCode)

        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-device-code-value").assertTextEquals("ABCD-EFGH")
        compose.onNodeWithTag("provider-device-code-cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(CodexDeviceCodeState.Idle, repository.state.value.codexDeviceCode)
        assertTrue(compose.onAllNodesWithTag("provider-device-code-cancel").fetchSemanticsNodes().isEmpty())
    }

    @Test fun connectedDeviceCodeAccountStillRequiresConfirmationBeforeDisconnect() {
        SecretStore.get(compose.activity).saveCodexTokens("access-r3a", "refresh-r3a",
            System.currentTimeMillis() + 60_000, "account-r3a")
        repository.refresh()
        showGraph()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        val disconnect = compose.activity.getString(R.string.provider_disconnect_chatgpt)
        compose.onNodeWithText(disconnect).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_disconnect_chatgpt_title)).assertIsDisplayed()
        assertTrue(repository.state.value.codexConnected)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_keep_connected)).performClick()
        compose.waitForIdle()
        assertTrue(repository.state.value.codexConnected)
        compose.onNodeWithText(disconnect).performScrollTo().performClick()
        compose.waitForIdle()
        val disconnectButtons = compose.onAllNodesWithText(disconnect).fetchSemanticsNodes()
        compose.onAllNodesWithText(disconnect).get(disconnectButtons.lastIndex).performClick()
        compose.waitForIdle()
        assertFalse(repository.state.value.codexConnected)
        assertNull(SecretStore.get(compose.activity).codexCredentials)
    }

    @Test fun apiKeyProviderCanBecomeActiveAndRemovalRequiresConfirmation() {
        val secret = SecretStore.get(compose.activity)
        secret.saveOpenAiApiKey("sk-ui-r3b-secret")
        repository.selectOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.API_KEY)
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
        showGraph()
        compose.onAllNodesWithText(compose.activity.getString(R.string.provider_active_tag)).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_api_key_saved))
            .performScrollTo().assertIsDisplayed()
        val useOpenAi = compose.onNodeWithText(compose.activity.getString(R.string.provider_use_openai_active))
            .performScrollTo().assertIsDisplayed()
        useOpenAi.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ProviderSettings.Provider.OPENAI_API, repository.state.value.activeProvider)
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(compose.activity.getString(R.string.provider_active_tag)).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_name)).performClick()
        compose.waitForIdle()

        val remove = compose.activity.getString(R.string.provider_openai_api_key_remove)
        compose.onAllNodesWithText(remove).get(0).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_api_key_remove_title)).assertIsDisplayed()
        assertEquals("sk-ui-r3b-secret", secret.openAiApiKey)
        compose.onNodeWithText(compose.activity.getString(R.string.provider_keep_key)).performClick()
        compose.waitForIdle()
        assertEquals("sk-ui-r3b-secret", secret.openAiApiKey)

        compose.onAllNodesWithText(remove).get(0).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_openai_api_key_remove_title)).assertIsDisplayed()
        val removeNodes = compose.onAllNodesWithText(remove).fetchSemanticsNodes()
        compose.onAllNodesWithText(remove).get(removeNodes.lastIndex).performClick()
        compose.waitForIdle()
        assertNull(secret.openAiApiKey)
        assertFalse(repository.state.value.openAiApiKeyConnected)
    }
}

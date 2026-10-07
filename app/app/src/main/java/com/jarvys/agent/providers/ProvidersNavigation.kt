package com.jarvys.agent.providers

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy

/** Providers routes are shared by the activity NavHost and its route-parity tests. */
fun NavGraphBuilder.providersDestinations(
    navController: NavController,
    repository: ProvidersRepository,
) {
    composable(AppNavigationBackPolicy.PROVIDERS) {
        val state by repository.state.collectAsState()
        ProvidersScreen(
            state = state,
            onOpenOpenAi = { navController.navigate(AppNavigationBackPolicy.PROVIDERS_OPENAI) },
            onOpenOpenRouter = { navController.navigate(AppNavigationBackPolicy.PROVIDERS_OPENROUTER) },
            onOpenService = { serviceId -> navController.navigate(AppNavigationBackPolicy.providerService(serviceId)) },
            onOpenCustomEndpoint = { navController.navigate(AppNavigationBackPolicy.PROVIDERS_CUSTOM) },
        )
    }
    composable(AppNavigationBackPolicy.PROVIDERS_OPENAI) {
        ProvidersOpenAiDetailScreen(repository)
    }
    composable(AppNavigationBackPolicy.PROVIDERS_OPENROUTER) {
        ProvidersOpenRouterDetailScreen(repository)
    }
    composable(AppNavigationBackPolicy.PROVIDERS_CUSTOM) {
        ProvidersCustomEndpointDetailScreen(repository)
    }
    composable(AppNavigationBackPolicy.PROVIDERS_SERVICE,
        arguments = listOf(navArgument("serviceId") { type = NavType.StringType })) { entry ->
        val serviceId = entry.arguments?.getString("serviceId").orEmpty()
        val service = ProviderServiceRegistry.find(serviceId)
        if (service != null) {
            service.detail(repository)
        } else {
            LaunchedEffect(serviceId) { returnToProviders(navController) }
        }
    }
}

private fun returnToProviders(navController: NavController) {
    if (!navController.popBackStack(AppNavigationBackPolicy.PROVIDERS, false)) {
        navController.navigate(AppNavigationBackPolicy.PROVIDERS) { launchSingleTop = true }
    }
}

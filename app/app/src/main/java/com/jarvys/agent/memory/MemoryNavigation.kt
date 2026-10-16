package com.jarvys.agent.memory

import androidx.compose.runtime.key
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy

/** The Memory routes share the same graph in the activity and navigation tests. */
fun NavGraphBuilder.memoryDestinations(
    navController: NavController,
    conversationId: String,
) {
    composable(AppNavigationBackPolicy.MEMORY) {
        key(conversationId) {
            PersonalMemoryScreen(conversationId) {
                if (!navController.popBackStack()) navController.navigate(AppNavigationBackPolicy.SETTINGS_PREFERENCES)
            }
        }
    }
    composable(AppNavigationBackPolicy.MEMORY_HISTORY) {
        key(conversationId) { PersonalMemoryScreen(conversationId) }
    }
    composable(
        route = AppNavigationBackPolicy.MEMORY_FILE,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType },
            navArgument("new") { type = NavType.BoolType; defaultValue = false },
        ),
    ) { entry ->
        key(conversationId) { PersonalMemoryScreen(conversationId) }
    }
}

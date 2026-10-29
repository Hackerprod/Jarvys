package com.jarvys.agent.memory

import androidx.compose.runtime.key
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
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
}

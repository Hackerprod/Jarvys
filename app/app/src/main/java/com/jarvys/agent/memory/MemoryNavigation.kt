package com.jarvys.agent.memory

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.MemoryEditorDestination
import com.jarvys.agent.MemoryHistoryDestination
import com.jarvys.agent.MemorySettingsScreen
import com.jarvys.agent.MemoryStore

/** The Memory routes share the same graph in the activity and navigation tests. */
fun NavGraphBuilder.memoryDestinations(
    navController: NavController,
    store: MemoryStore,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    conversationId: String,
    onShowDisclosure: () -> Unit,
    onMemoryChanged: () -> Unit,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    lastReflectionMillis: Long,
    reflecting: Boolean,
    onReflectionEnabledChange: (Boolean) -> Unit,
    onReflectionDisclosure: () -> Unit,
) {
    composable(AppNavigationBackPolicy.MEMORY) {
        val conversationStore = remember(store, conversationId) { store.forConversation(conversationId) }
        key(conversationId) {
            MemorySettingsScreen(
                store = conversationStore,
                legacyStore = store.takeUnless { it.isConversationScoped },
                onCloseMemory = {
                    if (!navController.popBackStack()) navController.navigate(AppNavigationBackPolicy.SETTINGS_PREFERENCES)
                },
                enabled = enabled,
                onEnabledChange = onEnabledChange,
                conversationId = conversationId,
                onShowDisclosure = onShowDisclosure,
                onMemoryChanged = onMemoryChanged,
                reflectionEnabled = reflectionEnabled,
                reflectionStatus = reflectionStatus,
                lastReflectionMillis = lastReflectionMillis,
                reflecting = reflecting,
                onReflectionEnabledChange = onReflectionEnabledChange,
                onReflectionDisclosure = onReflectionDisclosure,
                onOpenHistory = { navController.navigate(AppNavigationBackPolicy.MEMORY_HISTORY) },
                onOpenFile = { path, isNew -> navController.navigate(AppNavigationBackPolicy.memoryFile(path, isNew)) },
            )
        }
    }
    composable(AppNavigationBackPolicy.MEMORY_HISTORY) {
        val conversationStore = remember(store, conversationId) { store.forConversation(conversationId) }
        key(conversationId) {
            MemoryHistoryDestination(conversationStore, conversationId, onMemoryChanged) {
                if (!navController.popBackStack(AppNavigationBackPolicy.MEMORY, false)) {
                    navController.navigate(AppNavigationBackPolicy.MEMORY) { launchSingleTop = true }
                }
            }
        }
    }
    composable(
        route = AppNavigationBackPolicy.MEMORY_FILE,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType },
            navArgument("new") { type = NavType.BoolType; defaultValue = false },
        ),
    ) { entry ->
        val path = entry.arguments?.getString("path").orEmpty()
        val isNew = entry.arguments?.getBoolean("new") ?: false
        val conversationStore = remember(store, conversationId) { store.forConversation(conversationId) }
        key(conversationId) {
            MemoryEditorDestination(
                store = conversationStore,
                conversationId = conversationId,
                path = path,
                isNew = isNew,
                onMemoryChanged = onMemoryChanged,
                onBack = { navController.popBackStack() },
                onMissingFile = {
                    if (!navController.popBackStack(AppNavigationBackPolicy.MEMORY, false)) {
                        navController.navigate(AppNavigationBackPolicy.MEMORY) { launchSingleTop = true }
                    }
                },
            )
        }
    }
}

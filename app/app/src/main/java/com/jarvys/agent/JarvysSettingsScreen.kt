package com.jarvys.agent

import androidx.compose.runtime.Composable
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.ui.settings.SettingsWorkspace
import com.jarvys.agent.ui.settings.SettingsWorkspacePage

typealias JarvysSettingsPage = SettingsWorkspacePage

@Composable
fun JarvysSettingsScreen(
    page: JarvysSettingsPage,
    themeMode: JarvysThemeMode,
    showAgentEvents: Boolean,
    proactiveEnabled: Boolean,
    proactiveStatus: ProactiveStatus,
    memoryEnabled: Boolean,
    memoryUsedCharacters: Int,
    languageChoice: AppLanguageChoice,
    onLanguageChange: (AppLanguageChoice) -> Unit,
    onThemeChange: (JarvysThemeMode) -> Unit,
    onShowAgentEventsChange: (Boolean) -> Unit,
    onProactiveEnabledChange: (Boolean) -> Unit,
    onRefreshProactiveStatus: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onConnectors: () -> Unit,
    onMemory: () -> Unit,
    scheduledTasksAvailable: Boolean = false,
    onScheduledTasks: () -> Unit = {},
    onAccessibilitySettings: () -> Unit,
    onNavigate: (JarvysSettingsPage) -> Unit,
    archivedChatsAvailable: Boolean = false,
    onArchivedChats: () -> Unit = {},
) = SettingsWorkspace(
    page = page,
    themeMode = themeMode,
    showAgentEvents = showAgentEvents,
    proactiveEnabled = proactiveEnabled,
    proactiveStatus = proactiveStatus,
    memoryEnabled = memoryEnabled,
    memoryUsedCharacters = memoryUsedCharacters,
    languageChoice = languageChoice,
    onLanguageChange = onLanguageChange,
    onThemeChange = onThemeChange,
    onShowAgentEventsChange = onShowAgentEventsChange,
    onProactiveEnabledChange = onProactiveEnabledChange,
    onRefreshProactiveStatus = onRefreshProactiveStatus,
    onNavigateRoute = onNavigateRoute,
    onMcp = onMcp,
    onSkills = onSkills,
    onConnectors = onConnectors,
    onMemory = onMemory,
    scheduledTasksAvailable = scheduledTasksAvailable,
    onScheduledTasks = onScheduledTasks,
    onAccessibilitySettings = onAccessibilitySettings,
    onNavigate = onNavigate,
    archivedChatsAvailable = archivedChatsAvailable,
    onArchivedChats = onArchivedChats,
)

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
    agentTimeoutSeconds: Int,
    memoryEnabled: Boolean,
    memoryUsedCharacters: Int,
    languageChoice: AppLanguageChoice,
    onLanguageChange: (AppLanguageChoice) -> Unit,
    onThemeChange: (JarvysThemeMode) -> Unit,
    onShowAgentEventsChange: (Boolean) -> Unit,
    onProactiveEnabledChange: (Boolean) -> Unit,
    onRefreshProactiveStatus: () -> Unit,
    onAgentTimeoutChange: (Int) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onConnectors: () -> Unit,
    onMemory: () -> Unit,
    scheduledTasksAvailable: Boolean = false,
    onScheduledTasks: () -> Unit = {},
    onAccessibilitySettings: () -> Unit,
    onNavigate: (JarvysSettingsPage) -> Unit,
) = SettingsWorkspace(
    page = page,
    themeMode = themeMode,
    showAgentEvents = showAgentEvents,
    proactiveEnabled = proactiveEnabled,
    proactiveStatus = proactiveStatus,
    agentTimeoutSeconds = agentTimeoutSeconds,
    memoryEnabled = memoryEnabled,
    memoryUsedCharacters = memoryUsedCharacters,
    languageChoice = languageChoice,
    onLanguageChange = onLanguageChange,
    onThemeChange = onThemeChange,
    onShowAgentEventsChange = onShowAgentEventsChange,
    onProactiveEnabledChange = onProactiveEnabledChange,
    onRefreshProactiveStatus = onRefreshProactiveStatus,
    onAgentTimeoutChange = onAgentTimeoutChange,
    onNavigateRoute = onNavigateRoute,
    onMcp = onMcp,
    onSkills = onSkills,
    onConnectors = onConnectors,
    onMemory = onMemory,
    scheduledTasksAvailable = scheduledTasksAvailable,
    onScheduledTasks = onScheduledTasks,
    onAccessibilitySettings = onAccessibilitySettings,
    onNavigate = onNavigate,
)

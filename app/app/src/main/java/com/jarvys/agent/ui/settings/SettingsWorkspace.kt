package com.jarvys.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.MemoryConstants
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysChoiceOption
import com.jarvys.agent.JarvysChoiceSheet
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.tasks.ui.TaskSettingsEntry
import java.util.Date

enum class SettingsWorkspacePage { HOME, PREFERENCES }

@Composable
fun SettingsWorkspace(
    page: SettingsWorkspacePage,
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
    scheduledTasksAvailable: Boolean,
    onScheduledTasks: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onNavigate: (SettingsWorkspacePage) -> Unit,
) {
    var languageChoiceOpen by remember { mutableStateOf(false) }
    var appearanceChoiceOpen by remember { mutableStateOf(false) }
    LaunchedEffect(page, proactiveEnabled) {
        if (page == SettingsWorkspacePage.PREFERENCES) onRefreshProactiveStatus()
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 8.dp)) {
        when (page) {
            SettingsWorkspacePage.HOME -> SettingsIndex(
                themeMode = themeMode,
                languageChoice = languageChoice,
                memoryEnabled = memoryEnabled,
                memoryUsedCharacters = memoryUsedCharacters,
                scheduledTasksAvailable = scheduledTasksAvailable,
                onScheduledTasks = onScheduledTasks,
                onMemory = onMemory,
                onAppearance = { appearanceChoiceOpen = true },
                onLanguage = { languageChoiceOpen = true },
                onPreferences = { onNavigate(SettingsWorkspacePage.PREFERENCES) },
                onProviders = { onNavigateRoute(AppNavigationBackPolicy.PROVIDERS) },
                onMcp = onMcp,
                onSkills = onSkills,
                onConnectors = onConnectors,
                onAccessibility = onAccessibilitySettings,
            )
            SettingsWorkspacePage.PREFERENCES -> PreferencesWorkspace(
                showAgentEvents = showAgentEvents,
                onShowAgentEventsChange = onShowAgentEventsChange,
                proactiveEnabled = proactiveEnabled,
                onProactiveEnabledChange = onProactiveEnabledChange,
                proactiveStatus = proactiveStatus,
                agentTimeoutSeconds = agentTimeoutSeconds,
                onAgentTimeoutChange = onAgentTimeoutChange,
            )
        }
    }
    if (languageChoiceOpen) JarvysChoiceSheet(
        title = stringResource(R.string.language_dialog_title),
        choices = listOf(
            JarvysChoiceOption(AppLanguageChoice.ENGLISH, stringResource(R.string.language_option_english)),
            JarvysChoiceOption(AppLanguageChoice.SPANISH, stringResource(R.string.language_option_spanish)),
            JarvysChoiceOption(AppLanguageChoice.SYSTEM, stringResource(R.string.language_option_system)),
        ),
        selected = languageChoice,
        onClose = { languageChoiceOpen = false },
        onSelect = onLanguageChange,
    )
    if (appearanceChoiceOpen) JarvysChoiceSheet(
        title = stringResource(R.string.settings_color_mode),
        choices = listOf(
            JarvysChoiceOption(JarvysThemeMode.SYSTEM, stringResource(R.string.settings_system)),
            JarvysChoiceOption(JarvysThemeMode.LIGHT, stringResource(R.string.settings_light)),
            JarvysChoiceOption(JarvysThemeMode.DARK, stringResource(R.string.settings_dark)),
        ),
        selected = themeMode,
        onClose = { appearanceChoiceOpen = false },
        onSelect = onThemeChange,
    )
}

@Composable
private fun SettingsIndex(
    themeMode: JarvysThemeMode,
    languageChoice: AppLanguageChoice,
    memoryEnabled: Boolean,
    memoryUsedCharacters: Int,
    scheduledTasksAvailable: Boolean,
    onScheduledTasks: () -> Unit,
    onMemory: () -> Unit,
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onPreferences: () -> Unit,
    onProviders: () -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onConnectors: () -> Unit,
    onAccessibility: () -> Unit,
) {
    SettingsSection(stringResource(R.string.settings_general)) {
        val memorySubtitle = if (memoryEnabled) stringResource(
            R.string.memory_settings_summary_on, memoryUsedCharacters.coerceAtLeast(0), MemoryConstants.MAX_CORE_MEMORY_CHARACTERS,
        ) else stringResource(R.string.memory_settings_summary_off)
        SettingsIndexRow(LucideIcons.Brain, stringResource(R.string.memory_title), memorySubtitle, onMemory)
        if (scheduledTasksAvailable) {
            SettingsRule()
            TaskSettingsEntry(onScheduledTasks)
        }
        SettingsRule()
        SettingsIndexRow(LucideIcons.SunMoon, stringResource(R.string.settings_color_mode),
            stringResource(themeResource(themeMode)), onAppearance, testTag = "settings-color-mode-row")
        SettingsRule()
        SettingsIndexRow(LucideIcons.Languages, stringResource(R.string.language_title),
            stringResource(languageResource(languageChoice)), onLanguage, testTag = "settings-language-row")
        SettingsRule()
        SettingsIndexRow(LucideIcons.Eye, stringResource(R.string.settings_preferences), null, onPreferences)
    }
    Spacer(Modifier.height(22.dp))
    SettingsSection(stringResource(R.string.settings_models_services)) {
        SettingsIndexRow(LucideIcons.Sparkles, stringResource(R.string.settings_providers), null, onProviders)
        SettingsRule()
        SettingsIndexRow(LucideIcons.Terminal, stringResource(R.string.settings_mcp), null, onMcp)
        SettingsRule()
        SettingsIndexRow(LucideIcons.WandSparkles, stringResource(R.string.settings_skills), null, onSkills)
        SettingsRule()
        SettingsIndexRow(LucideIcons.Boxes, stringResource(R.string.settings_connectors), null, onConnectors)
    }
    Spacer(Modifier.height(22.dp))
    SettingsSection(stringResource(R.string.settings_device)) {
        SettingsIndexRow(LucideIcons.Accessibility, stringResource(R.string.settings_accessibility), null, onAccessibility)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    JarvysSectionLabel(title, Modifier.padding(start = 3.dp, bottom = 5.dp))
    content()
}

@Composable
private fun SettingsIndexRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
                             detail: String?, onClick: () -> Unit, testTag: String? = null) {
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 8.dp)
    Row(if (testTag == null) rowModifier else rowModifier.testTag(testTag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        Icon(LucideIcons.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsRule() {
    HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun PreferencesWorkspace(
    showAgentEvents: Boolean,
    onShowAgentEventsChange: (Boolean) -> Unit,
    proactiveEnabled: Boolean,
    onProactiveEnabledChange: (Boolean) -> Unit,
    proactiveStatus: ProactiveStatus,
    agentTimeoutSeconds: Int,
    onAgentTimeoutChange: (Int) -> Unit,
) {
    var timeoutOpen by remember { mutableStateOf(false) }
    var timeoutMinutes by remember(agentTimeoutSeconds) {
        mutableStateOf(if (agentTimeoutSeconds == 0) "0" else (agentTimeoutSeconds / 60).toString())
    }
    SettingsSection(stringResource(R.string.settings_agent_activity)) {
        PreferenceToggle(stringResource(R.string.settings_show_agent_activity),
            stringResource(R.string.settings_agent_activity_description), showAgentEvents, onShowAgentEventsChange)
    }
    Spacer(Modifier.height(22.dp))
    SettingsSection(stringResource(R.string.settings_proactive_section)) {
        val context = LocalContext.current
        val status = if (proactiveStatus.lastCheckMillis <= 0L) stringResource(R.string.settings_proactive_status_never)
        else stringResource(R.string.settings_proactive_status_last,
            android.text.format.DateFormat.getTimeFormat(context).format(Date(proactiveStatus.lastCheckMillis)),
            proactiveStatus.pendingCount)
        val helper = listOfNotNull(status,
            if (proactiveEnabled && !proactiveStatus.notificationPermissionGranted)
                stringResource(R.string.settings_proactive_notifications_denied) else null).joinToString(" · ")
        PreferenceToggle(stringResource(R.string.settings_proactive_enabled),
            stringResource(R.string.settings_proactive_description), proactiveEnabled, onProactiveEnabledChange,
            icon = LucideIcons.Bell, supportingStatus = helper)
    }
    Spacer(Modifier.height(22.dp))
    SettingsSection(stringResource(R.string.settings_agent_behavior)) {
        SettingsIndexRow(LucideIcons.Clock, stringResource(R.string.settings_task_timeout),
            timeoutLabel(agentTimeoutSeconds), onClick = { timeoutMinutes = if (agentTimeoutSeconds == 0) "0"
                else (agentTimeoutSeconds / 60).toString(); timeoutOpen = true },
            testTag = "settings-timeout-row")
        Text(stringResource(R.string.settings_timeout_description),
            Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (timeoutOpen) {
        val minutes = timeoutMinutes.toLongOrNull()
        val valid = minutes != null && minutes >= 0 && minutes <= Int.MAX_VALUE / 60L
        AlertDialog(
            onDismissRequest = { timeoutOpen = false },
            title = { Text(stringResource(R.string.settings_task_timeout)) },
            text = { ScrollableDialogContent {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_timeout_description))
                    JarvysTextField(value = timeoutMinutes,
                        onValueChange = { if (it.all(Char::isDigit)) timeoutMinutes = it },
                        label = { Text(stringResource(R.string.settings_timeout_minutes_label)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                onAgentTimeoutChange((minutes ?: 0L).toInt() * 60)
                timeoutOpen = false
            }) { Text(stringResource(R.string.settings_save)) } },
            dismissButton = { TextButton(onClick = { timeoutOpen = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun PreferenceToggle(title: String, description: String, checked: Boolean,
                             onCheckedChange: (Boolean) -> Unit,
                             icon: androidx.compose.ui.graphics.vector.ImageVector = LucideIcons.Eye,
                             supportingStatus: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            supportingStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun timeoutLabel(seconds: Int): String = if (seconds == 0) stringResource(R.string.settings_timeout_unlimited)
    else androidx.compose.ui.res.pluralStringResource(R.plurals.settings_timeout_minutes, seconds / 60, seconds / 60)

private fun themeResource(mode: JarvysThemeMode) = when (mode) {
    JarvysThemeMode.SYSTEM -> R.string.settings_system
    JarvysThemeMode.LIGHT -> R.string.settings_light
    JarvysThemeMode.DARK -> R.string.settings_dark
}

private fun languageResource(choice: AppLanguageChoice) = when (choice) {
    AppLanguageChoice.ENGLISH -> R.string.language_option_english
    AppLanguageChoice.SPANISH -> R.string.language_option_spanish
    AppLanguageChoice.SYSTEM -> R.string.language_option_system
}

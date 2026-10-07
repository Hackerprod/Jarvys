package com.jarvys.agent.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.R
import com.jarvys.agent.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CrewProfileOption(val id: String, val label: String, val available: Boolean = true)
private data class CodingProfileSettingsState(val profile: CrewProfile,
    val capabilities: List<CrewProfileOption>, val skills: List<CrewProfileOption>)

@Composable
fun CrewCodingProfileSettings(onClose: () -> Unit, conversationId: String? = null) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { runCatching { CrewProfileRepository(context) } }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CodingProfileSettingsState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var loadEpoch by remember { mutableIntStateOf(0) }
    val latestConversationId by rememberUpdatedState(conversationId)
    BackHandler { if (!saving) onClose() }
    LaunchedEffect(context, conversationId, reload) {
        loadEpoch++
        state = null; error = null; saved = false
        try {
            state = withContext(Dispatchers.IO) {
                val profile = repository.getOrThrow().codingProfile()
                val capabilities = CoreAgentRuntime.profileCapabilities(context, conversationId).distinct()
                val skills = SkillRepository.get(context).also { it.refresh() }
                val available = skills.enabledForRun().map { it.metadata.id }.toSet()
                CodingProfileSettingsState(profile, capabilities.map { CrewProfileOption(it, CoreToolRegistry.humanizeToolName(it)) },
                    skills.skills.value.map { CrewProfileOption(it.metadata.id, it.metadata.name, it.metadata.id in available) })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
    }
    val loaded = state
    if (loaded == null) Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileHeader(onClose, !saving)
        if (error == null) CircularProgressIndicator() else {
            Text(stringResource(R.string.crew_profile_load_failed), color = MaterialTheme.colorScheme.error)
            Text(error.orEmpty(), Modifier.testTag("crew-profile-error"))
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.crew_profile_reload)) }
        }
    } else CrewCodingProfileEditor(loaded.profile, loaded.capabilities, loaded.skills, saving, error, saved,
        onClose, onReload = { if (!saving) reload++ }, onSave = { edited ->
            if (!saving) {
                saving = true; saved = false; error = null
                val epoch = loadEpoch
                val session = conversationId
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            val capabilities = CoreAgentRuntime.profileCapabilities(context, session)
                            val skills = SkillRepository.get(context).also { it.refresh() }
                            repository.getOrThrow().save(edited, capabilities, skills.enabledForRun().map { it.metadata.id })
                        }
                        if (epoch == loadEpoch && latestConversationId == session) {
                            state = loaded.copy(profile = updated); saved = true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (epoch == loadEpoch && latestConversationId == session) error = failure.message ?: failure.javaClass.simpleName
                    } finally { saving = false }
                }
            }
        })
}

@Composable
internal fun CrewCodingProfileEditor(profile: CrewProfile, capabilities: List<CrewProfileOption>,
    skills: List<CrewProfileOption>, saving: Boolean = false, error: String? = null, saved: Boolean = false,
    onClose: () -> Unit, onReload: () -> Unit = {}, onSave: (CrewProfile) -> Unit) {
    var name by remember(profile.id, profile.version) { mutableStateOf(profile.name) }
    var description by remember(profile.id, profile.version) { mutableStateOf(profile.description) }
    var prompt by remember(profile.id, profile.version) { mutableStateOf(profile.prompt) }
    var selectedSkills by remember(profile.id, profile.version) { mutableStateOf(profile.skillIds.toSet()) }
    var selectedCapabilities by remember(profile.id, profile.version) { mutableStateOf(profile.capabilities.toSet()) }
    var workspace by remember(profile.id, profile.version) { mutableStateOf(profile.workspaceMode) }
    var validation by remember(profile.id, profile.version) { mutableStateOf<String?>(null) }
    var editedSinceSave by remember(profile.id, profile.version) { mutableStateOf(false) }
    fun changed() { validation = null; editedSinceSave = true }
    Column(Modifier.fillMaxSize().testTag("crew-profile-editor")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileHeader(onClose, !saving)
            Text(stringResource(R.string.crew_profile_safety), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.crew_profile_version, profile.id, profile.version), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.crew_profile_workspace), style = MaterialTheme.typography.titleSmall)
            listOf(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT to R.string.crew_profile_workspace_project,
                CrewProfile.WorkspaceMode.LEGACY_CHAT to R.string.crew_profile_workspace_legacy).forEach { (mode, label) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { workspace = mode; changed() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = workspace == mode, onClick = { workspace = mode; changed() }, enabled = !saving)
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
            Text(stringResource(if (workspace == CrewProfile.WorkspaceMode.CONVERSATION_PROJECT) R.string.crew_profile_project_help else R.string.crew_profile_legacy_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.crew_profile_adoption_help), style = MaterialTheme.typography.bodySmall)
            JarvysTextField(name, { name = it; changed() }, label = { Text(stringResource(R.string.crew_profile_name)) }, enabled = !saving,
                modifier = Modifier.testTag("crew-profile-name"), singleLine = true)
            JarvysTextField(description, { description = it; changed() }, label = { Text(stringResource(R.string.crew_profile_description)) }, enabled = !saving,
                modifier = Modifier.testTag("crew-profile-description"), singleLine = true)
            JarvysTextField(prompt, { prompt = it; changed() }, label = { Text(stringResource(R.string.crew_profile_prompt)) }, enabled = !saving,
                modifier = Modifier.testTag("crew-profile-prompt"), singleLine = false, minLines = 4, maxLines = 12)
            Text(stringResource(R.string.crew_profile_capabilities), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.crew_profile_capabilities_help), style = MaterialTheme.typography.bodySmall)
            withMissingOptions(capabilities, selectedCapabilities).forEach { option ->
                val available = option.available && CrewProfile.isWorkspaceCapabilityCompatible(workspace, option.id)
                ProfileCheckbox(option.copy(available = available), option.id in selectedCapabilities, !saving, "capability") {
                    selectedCapabilities = toggle(selectedCapabilities, option.id); changed()
                }
            }
            Text(stringResource(R.string.crew_profile_skills), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.crew_profile_skills_help), style = MaterialTheme.typography.bodySmall)
            val options = withMissingOptions(skills, selectedSkills)
            if (options.isEmpty()) Text(stringResource(R.string.crew_profile_no_skills))
            options.forEach { option -> ProfileCheckbox(option, option.id in selectedSkills, !saving, "skill") {
                selectedSkills = toggle(selectedSkills, option.id); changed()
            } }
            (validation ?: error)?.let { Text(it, Modifier.testTag("crew-profile-error"), color = MaterialTheme.colorScheme.error) }
            if (saved && !editedSinceSave) Text(stringResource(R.string.crew_profile_saved), Modifier.testTag("crew-profile-saved"))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !saving, onClick = onReload, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.crew_profile_reload)) }
            Button(enabled = !saving, modifier = Modifier.weight(1f).testTag("crew-profile-save"), onClick = {
                try {
                    val edited = CrewProfile(profile.id, profile.version, name, description, prompt, selectedSkills.toList(), selectedCapabilities.toList(), workspace)
                    edited.validateAvailability(capabilities.filter { it.available }.map { it.id }, skills.filter { it.available }.map { it.id })
                    onSave(edited)
                } catch (failure: IllegalArgumentException) { validation = failure.message }
            }) { Text(stringResource(if (saving) R.string.crew_profile_saving else R.string.crew_profile_save)) }
        }
    }
}

@Composable
private fun ProfileHeader(onClose: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.crew_profile_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClose, enabled = enabled) { Text(stringResource(R.string.crew_profile_close)) }
    }
}

@Composable
private fun ProfileCheckbox(option: CrewProfileOption, checked: Boolean, enabled: Boolean, kind: String, onToggle: () -> Unit) {
    val canToggle = enabled && (option.available || checked)
    Row(Modifier.fillMaxWidth().clickable(enabled = canToggle, onClick = onToggle).testTag("crew-profile-$kind-${option.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = { onToggle() }, enabled = canToggle)
        Text(if (option.available) option.label else stringResource(R.string.crew_profile_unavailable, option.label),
            Modifier.weight(1f), color = if (option.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    }
}

private fun withMissingOptions(options: List<CrewProfileOption>, selected: Set<String>): List<CrewProfileOption> =
    options + (selected - options.map { it.id }.toSet()).map { CrewProfileOption(it, it, false) }
private fun toggle(selected: Set<String>, id: String) = if (id in selected) selected - id else selected + id

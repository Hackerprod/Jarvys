package com.jarvys.agent.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CrewProfileOption(val id: String, val label: String, val available: Boolean = true)

/** The old Coding entrypoint remains a runtime detail, never a second mutable profile editor. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CrewCodingProfileSettings(onClose: () -> Unit, conversationId: String? = null) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    val context = LocalContext.current.applicationContext
    var profile by remember { mutableStateOf<CrewProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onClose)
    LaunchedEffect(context, reload) {
        error = null
        try {
            profile = withContext(Dispatchers.IO) { CrewProfileRepository(context).codingProfile() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
    }
    val loaded = profile
    if (loaded != null) CrewCodingProfileEditor(loaded, emptyList(), emptyList(), onClose = onClose, onSave = {})
    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileHeader(onClose)
        if (error == null) CircularProgressIndicator() else {
            Text(stringResource(R.string.crew_profile_load_failed), color = MaterialTheme.colorScheme.error)
            Text(error.orEmpty(), Modifier.testTag("crew-profile-error"))
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.bots_retry)) }
        }
    }
    }
}

/** Kept source-compatible for legacy callers; even a direct call cannot edit built-in Coding. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun CrewCodingProfileEditor(profile: CrewProfile, capabilities: List<CrewProfileOption>,
    skills: List<CrewProfileOption>, saving: Boolean = false, error: String? = null, saved: Boolean = false,
    onClose: () -> Unit, onReload: () -> Unit = {}, onSave: (CrewProfile) -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().testTag("crew-profile-editor").verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileHeader(onClose)
        Text(stringResource(R.string.bots_read_only), Modifier.testTag("crew-profile-read-only"),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(profile.description)
                Text(stringResource(R.string.bots_instructions), style = MaterialTheme.typography.titleSmall)
                Text(profile.prompt, Modifier.testTag("crew-profile-instructions"))
                Text(stringResource(R.string.bots_workspace), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (profile.workspaceMode == CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
                    R.string.bots_workspace_project else R.string.bots_workspace_chat))
                Text(stringResource(R.string.bots_capabilities), style = MaterialTheme.typography.titleSmall)
                Text(profile.capabilities.joinToString("\n").ifEmpty { stringResource(R.string.bots_no_tools) })
                Text(stringResource(R.string.bots_skills), style = MaterialTheme.typography.titleSmall)
                Text(profile.skillIds.joinToString("\n").ifEmpty { stringResource(R.string.bots_no_skills) })
            }
        }
    }
    }
}

@Composable
private fun ProfileHeader(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bots_runtime_details), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClose, modifier = Modifier.testTag("crew-profile-close")) {
            Text(stringResource(R.string.crew_profile_close))
        }
    }
}

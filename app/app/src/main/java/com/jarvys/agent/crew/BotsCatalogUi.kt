package com.jarvys.agent.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import org.json.JSONObject
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.BotIconService
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysTopAppBar
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R

private const val MAX_SAVED_BOT_CONFIGURATION = 65_536

/** Bounded snapshots survive recreation without persisting an unreviewed bot to the catalog. */
internal val BotDraftStateSaver = Saver<BotDefinition?, String>(
    save = { it?.profile?.toJson()?.toString()?.takeIf { text -> text.length <= MAX_SAVED_BOT_CONFIGURATION } },
    restore = { text -> if (text.length > MAX_SAVED_BOT_CONFIGURATION) null else
        runCatching { BotDefinition(CrewProfile.fromJson(JSONObject(text)), 1, true, false, "") }.getOrNull() },
)
private val BotSelectionStateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** ID checks are intentional defense in depth for direct UI entrypoints with stale metadata. */
internal fun immutableCatalogBot(bot: BotDefinition): Boolean =
    bot.builtIn || bot.id == "coding" || bot.id == "android-use"

/** Fixed-size restore guard, independent of potentially large legacy instructions. */
internal fun botConfigurationFingerprint(profile: CrewProfile): String = botConfigurationFingerprint(
    profile.id, profile.name, profile.description, profile.prompt, profile.skillIds, profile.capabilities, profile.workspaceMode,
)

private fun botConfigurationFingerprint(id: String, name: String, description: String, prompt: String,
    skills: Collection<String>, capabilities: Collection<String>, workspace: CrewProfile.WorkspaceMode): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    field(id); field(name); field(description); field(prompt); field(workspace.value)
    field(skills.size.toString()); skills.forEach(::field)
    field(capabilities.size.toString()); capabilities.forEach(::field)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

@Composable
internal fun BotsCatalogGrid(
    bots: List<BotDefinition>,
    working: Map<String, Int>,
    onOpen: (BotDefinition) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("bots-catalog")) {
        BotsHeader(stringResource(R.string.bots_title), onClose, onCreate = onCreate, createEnabled = !busy)
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).testTag("bots-grid"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { BotsError(message) }
            }
            items(bots, key = { it.id }) { bot ->
                BotCatalogTile(bot, working[bot.id]?.coerceAtLeast(0) ?: 0) { onOpen(bot) }
            }
        }
    }
    }
}

@Composable
private fun BotCatalogTile(bot: BotDefinition, workingCount: Int, onOpen: () -> Unit) {
    val state = stringResource(if (bot.enabled) R.string.bots_enabled else R.string.bots_disabled)
    val open = stringResource(R.string.bots_open, bot.profile.name)
    val workingText = if (workingCount > 1) stringResource(R.string.bots_working_count, workingCount)
        else stringResource(R.string.bots_working)
    // The grid is intentionally just an icon and name. Management stays in the detail view.
    Column(Modifier.fillMaxWidth().testTag("bot-tile-${bot.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 16.dp)
            .testTag("bot-open-${bot.id}").semantics {
                contentDescription = open
                stateDescription = if (workingCount > 0) workingText else state
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BotWorkingVisual(workingCount > 0, bot.id, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotCatalogIcon(bot, Modifier.size(72.dp).alpha(if (bot.enabled) 1f else 0.5f).testTag("bot-icon-${bot.id}"))
                    val colors = MaterialTheme.colorScheme
                    Text(bot.profile.name, Modifier.fillMaxWidth().padding(vertical = 7.dp).testTag("bot-name-${bot.id}"),
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                        color = if (workingCount > 0 && colors.background.luminance() < 0.5f) colors.primary else colors.onSurface,
                        style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto))
                }
            }
        }
    }
}

@Composable
internal fun BotCatalogIcon(bot: BotDefinition, modifier: Modifier = Modifier) {
    BotIdentityIcon(BotIconIdentity(bot.id, bot.iconRef), modifier)
}

@Composable
internal fun BotCreationPrompt(generating: Boolean, error: String?, onGenerate: (String) -> Unit, onCancel: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    var prompt by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("bot-creation-prompt")) {
        BotsHeader(stringResource(R.string.bots_create), onCancel)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.bots_saved_for_later))
            JarvysTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.bots_creation_prompt)) },
                placeholder = { Text(stringResource(R.string.bots_creation_example)) }, singleLine = false, minLines = 5,
                maxLines = 12, enabled = !generating, modifier = Modifier.testTag("bot-creation-text"))
            if (generating) Text(stringResource(R.string.bots_generating_configuration),
                Modifier.testTag("bot-creation-progress").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary)
            error?.let { BotsError(it) }
            Button(onClick = { onGenerate(prompt.trim()) }, enabled = prompt.isNotBlank() && !generating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-prepare")) {
                Text(stringResource(R.string.bots_generate_configuration))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-creation-cancel")) {
                Text(stringResource(R.string.bots_cancel))
            }
        }
    }
    }
}

@Composable
internal fun BotDefinitionEditor(
    bot: BotDefinition,
    isNew: Boolean,
    capabilities: List<CrewProfileOption>,
    skills: List<CrewProfileOption>,
    onSave: (BotDefinition) -> Unit,
    onClose: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onGenerateIcon: (String) -> Unit,
    onCancelIcon: () -> Unit,
    saving: Boolean = false,
    changingEnabled: Boolean = false,
    iconAvailable: Boolean = false,
    iconGenerating: Boolean = false,
    iconCompletedRevision: Int? = null,
    error: String? = null,
    iconError: String? = null,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    val immutable = immutableCatalogBot(bot)
    val focusManager = LocalFocusManager.current
    // Only an identity change resets edits. Icon/toggle revisions must never discard the user's text.
    var name by rememberSaveable(bot.id) { mutableStateOf(bot.profile.name) }
    var description by rememberSaveable(bot.id) { mutableStateOf(bot.profile.description) }
    var instructions by rememberSaveable(bot.id) { mutableStateOf(bot.profile.prompt) }
    var selectedTools by rememberSaveable(bot.id, stateSaver = BotSelectionStateSaver) { mutableStateOf(bot.profile.capabilities.toSet()) }
    var selectedSkills by rememberSaveable(bot.id, stateSaver = BotSelectionStateSaver) { mutableStateOf(bot.profile.skillIds.toSet()) }
    var workspace by rememberSaveable(bot.id) { mutableStateOf(bot.profile.workspaceMode) }
    var baseFingerprint by rememberSaveable(bot.id) { mutableStateOf(botConfigurationFingerprint(bot.profile)) }
    var baseVersion by rememberSaveable(bot.id) { mutableIntStateOf(bot.profile.version) }
    var profileConflict by rememberSaveable(bot.id) { mutableStateOf(false) }
    val incomingFingerprint = remember(bot.profile) { botConfigurationFingerprint(bot.profile) }
    LaunchedEffect(bot.profile.version, incomingFingerprint) {
        if (baseFingerprint == incomingFingerprint) baseVersion = bot.profile.version
        else profileConflict = true
    }
    var validation by remember(bot.id) { mutableStateOf<String?>(null) }
    var discard by rememberSaveable(bot.id) { mutableStateOf(false) }
    var iconDialog by rememberSaveable(bot.id) { mutableStateOf(false) }
    LaunchedEffect(iconCompletedRevision) {
        if (iconCompletedRevision != null) iconDialog = false
    }
    val editedFingerprint = remember(bot.id, name, description, instructions, selectedSkills, selectedTools, workspace) {
        botConfigurationFingerprint(bot.id, name, description, instructions, selectedSkills, selectedTools, workspace)
    }
    val dirty = !immutable && (isNew || editedFingerprint != baseFingerprint)
    val blocked = saving || changingEnabled || iconGenerating
    val close = { if (!saving && !changingEnabled) { if (dirty) discard = true else onClose() }; Unit }
    BackHandler(onBack = close)
    if (iconDialog && !immutable) {
        BotIconPromptScreen(iconGenerating, iconError, onGenerateIcon, onCancel = {
            onCancelIcon(); iconDialog = false
        })
        return@CompositionLocalProvider
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("bot-editor-${bot.id}")) {
        BotsHeader(stringResource(if (immutable) R.string.bots_built_in else if (isNew) R.string.bots_review else R.string.bots_edit), close,
            enabled = !saving && !changingEnabled)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { BotCatalogIcon(bot, Modifier.size(88.dp)) }
            if (immutable) {
                Text(stringResource(R.string.bots_read_only), Modifier.testTag("bot-read-only"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BotReadOnlyConfiguration(bot.profile)
            } else {
                if (isNew) Text(stringResource(R.string.bots_review_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!isNew) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (bot.enabled) R.string.bots_enabled else R.string.bots_disabled), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        val enabledLabel = stringResource(R.string.bots_toggle_accessibility, bot.profile.name)
                        Switch(bot.enabled, onEnabledChange, enabled = !blocked,
                            modifier = Modifier.testTag("bot-editor-enabled").semantics { contentDescription = enabledLabel })
                    }
                    Text(stringResource(R.string.bots_enabled_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JarvysTextField(name, { name = it; validation = null }, label = { Text(stringResource(R.string.bots_name)) },
                    enabled = !saving, modifier = Modifier.testTag("bot-editor-name"))
                JarvysTextField(description, { description = it; validation = null }, label = { Text(stringResource(R.string.bots_description)) },
                    enabled = !saving, modifier = Modifier.testTag("bot-editor-description"), singleLine = true)
                JarvysTextField(instructions, { instructions = it; validation = null }, label = { Text(stringResource(R.string.bots_instructions)) },
                    enabled = !saving, modifier = Modifier.testTag("bot-editor-instructions"), singleLine = false, minLines = 5, maxLines = 12)
                Text(stringResource(R.string.bots_icon), style = MaterialTheme.typography.titleSmall)
                if (isNew || !iconAvailable || !bot.enabled) Text(stringResource(when {
                    isNew -> R.string.bots_icon_save_first
                    !bot.enabled -> R.string.bots_icon_disabled
                    else -> R.string.bots_icon_unavailable
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { focusManager.clearFocus(); iconDialog = true }, enabled = !isNew && iconAvailable && bot.enabled && !blocked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-editor-icon")) {
                    Icon(LucideIcons.WandSparkles, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bots_generate_icon))
                }
                Text(stringResource(R.string.bots_workspace), style = MaterialTheme.typography.titleSmall)
                listOf(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT to R.string.bots_workspace_project,
                    CrewProfile.WorkspaceMode.LEGACY_CHAT to R.string.bots_workspace_chat).forEach { (mode, label) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { workspace = mode; validation = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(workspace == mode, onClick = { workspace = mode; validation = null }, enabled = !saving)
                        Text(stringResource(label), Modifier.weight(1f))
                    }
                }
                Text(stringResource(R.string.bots_tool_scope_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.bots_capabilities), style = MaterialTheme.typography.titleSmall)
                val toolOptions = botOptionsWithMissing(capabilities, selectedTools)
                if (toolOptions.isEmpty()) Text(stringResource(R.string.bots_no_tools))
                toolOptions.forEach { option ->
                    BotChoice(option.copy(available = option.available && CrewProfile.isWorkspaceCapabilityCompatible(workspace, option.id)),
                        option.id in selectedTools, !saving, "bot-tool-${option.id}") {
                        selectedTools = toggledBotChoice(selectedTools, option.id); validation = null
                    }
                }
                Text(stringResource(R.string.bots_skills), style = MaterialTheme.typography.titleSmall)
                val skillOptions = botOptionsWithMissing(skills, selectedSkills)
                if (skillOptions.isEmpty()) Text(stringResource(R.string.bots_no_skills))
                skillOptions.forEach { option -> BotChoice(option, option.id in selectedSkills, !saving, "bot-skill-${option.id}") {
                    selectedSkills = toggledBotChoice(selectedSkills, option.id); validation = null
                } }
            }
            if (profileConflict) BotsError(stringResource(R.string.bots_changed_elsewhere))
            (validation ?: error)?.let { BotsError(it) }
            if (!immutable) {
                Button(onClick = {
                    try {
                        val profile = CrewProfile(bot.id, baseVersion, name, description, instructions,
                            selectedSkills.toList(), selectedTools.toList(), workspace)
                        profile.validateAvailability(capabilities.filter { it.available }.map { it.id }, skills.filter { it.available }.map { it.id })
                        onSave(bot.withProfile(profile))
                    } catch (failure: IllegalArgumentException) { validation = failure.message }
                }, enabled = !blocked && !profileConflict, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-editor-save")) {
                    Text(stringResource(if (saving) R.string.bots_saving else R.string.bots_save))
                }
                OutlinedButton(onClick = close, enabled = !saving && !changingEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-editor-cancel")) { Text(stringResource(R.string.bots_cancel)) }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.bots_discard_title)) },
        text = { com.jarvys.agent.ScrollableDialogContent { Text(stringResource(R.string.bots_discard_body)) } }, confirmButton = {
            TextButton(onClick = { discard = false; onCancelIcon(); onClose() }, modifier = Modifier.testTag("bot-discard-confirm")) {
                Text(stringResource(R.string.bots_discard))
            }
        }, dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.testTag("bot-keep-editing")) { Text(stringResource(R.string.bots_keep_editing)) } })
    }
}

@Composable
private fun BotReadOnlyConfiguration(profile: CrewProfile) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleLarge)
            Text(profile.description)
            Text(stringResource(R.string.bots_instructions), style = MaterialTheme.typography.titleSmall)
            Text(profile.prompt)
            Text(stringResource(R.string.bots_capabilities), style = MaterialTheme.typography.titleSmall)
            Text(profile.capabilities.joinToString("\n").ifEmpty { stringResource(R.string.bots_no_tools) })
            Text(stringResource(R.string.bots_skills), style = MaterialTheme.typography.titleSmall)
            Text(profile.skillIds.joinToString("\n").ifEmpty { stringResource(R.string.bots_no_skills) })
        }
    }
}

@Composable
internal fun BotIconPromptScreen(generating: Boolean, error: String?, onGenerate: (String) -> Unit, onCancel: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val tooLong = prompt.length > BotIconService.MAX_PROMPT_CHARS
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding().testTag("bot-icon-screen")) {
        BotsHeader(stringResource(R.string.bots_generate_icon), onCancel)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.bots_icon_help), style = MaterialTheme.typography.bodyMedium)
            JarvysTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.bots_icon_prompt)) },
                placeholder = { Text(stringResource(R.string.bots_icon_example)) }, enabled = !generating,
                singleLine = false, minLines = 5, maxLines = 12, modifier = Modifier.testTag("bot-icon-prompt"))
            Text(stringResource(R.string.bots_icon_original_preserved), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tooLong) BotsError(stringResource(R.string.bots_icon_prompt_limit, BotIconService.MAX_PROMPT_CHARS))
            if (generating) Text(stringResource(R.string.bots_icon_generating), color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("bot-icon-progress").semantics { liveRegion = LiveRegionMode.Polite })
            error?.let { BotsError(it) }
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { focusManager.clearFocus(); onGenerate(prompt.trim()) }, enabled = !generating && !tooLong && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-icon-generate")) { Text(stringResource(R.string.bots_generate_icon)) }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bot-icon-cancel")) {
                Text(stringResource(R.string.bots_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotsHeader(title: String, onClose: () -> Unit, enabled: Boolean = true,
    onCreate: (() -> Unit)? = null, createEnabled: Boolean = true,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    // Same component, typography and system insets as Settings. This route owns its header.
    JarvysTopAppBar(
        title = { Text(title, Modifier.testTag("bots-header-title"), fontSize = JarvysUiTokens.ToolbarTitleSize,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(48.dp).testTag("bots-back")) {
                Icon(LucideIcons.ArrowLeft, stringResource(R.string.bots_back), Modifier.size(20.dp))
            }
        },
        actions = {
            onCreate?.let { create ->
                IconButton(onClick = create, enabled = createEnabled, modifier = Modifier.size(48.dp).testTag("bots-create")) {
                    Icon(LucideIcons.Plus, stringResource(R.string.bots_create), Modifier.size(22.dp))
                }
            }
        },
        transparent = true,
        windowInsets = windowInsets,
    )
}

@Composable
internal fun BotsError(message: String) {
    Text(message, Modifier.testTag("bots-error").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium)
}

private fun botOptionsWithMissing(options: List<CrewProfileOption>, selected: Set<String>): List<CrewProfileOption> =
    options + (selected - options.map { it.id }.toSet()).map { CrewProfileOption(it, it, false) }
private fun toggledBotChoice(selected: Set<String>, id: String) = if (id in selected) selected - id else selected + id

@Composable
private fun BotChoice(option: CrewProfileOption, selected: Boolean, enabled: Boolean, tag: String, onToggle: () -> Unit) {
    val available = enabled && (option.available || selected)
    Row(Modifier.fillMaxWidth().clickable(enabled = available, onClick = onToggle).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = { onToggle() }, enabled = available)
        Text(if (option.available) option.label else stringResource(R.string.crew_profile_unavailable, option.label), Modifier.weight(1f),
            color = if (option.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    }
}

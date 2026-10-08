package com.jarvys.agent.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.BotIconService
import com.jarvys.agent.BotProfileDraftService
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.R
import com.jarvys.agent.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BotCatalogConfiguration(val definitions: List<BotDefinition>,
    val capabilities: List<CrewProfileOption>, val skills: List<CrewProfileOption>, val iconAvailable: Boolean)

/** Catalog configuration is independent of conversation/mission execution. Opening it starts no run. */
@Composable
fun BotsCatalogScreen(conversationId: String? = null, working: Map<String, Int> = emptyMap(), onClose: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(context) { runCatching { CrewProfileRepository(context) } }
    val icons = remember(context) { runCatching { BotIconService(context) } }
    var configuration by remember { mutableStateOf<BotCatalogConfiguration?>(null) }
    val recentDefinitions = remember { mutableStateMapOf<String, BotDefinition>() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(stateSaver = BotDraftStateSaver) { mutableStateOf<BotDefinition?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var iconCompletedRevision by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var epoch by remember { mutableIntStateOf(0) }
    var requestToken by remember { mutableStateOf<CancellationToken?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    val failedMessage = stringResource(R.string.bots_operation_failed)
    val loadFailedMessage = stringResource(R.string.bots_load_failed)
    val draftFailedMessage = stringResource(R.string.bots_draft_failed)
    val latestConversation by rememberUpdatedState(conversationId)

    fun cancelRequest() {
        epoch++
        requestToken?.cancel()
        requestJob?.cancel()
        requestToken = null
        requestJob = null
        if (operation == "draft" || operation == "icon") operation = null
        iconError = null
    }
    fun replaceDefinition(definition: BotDefinition) {
        val current = recentDefinitions[definition.id]
        if (current == null || definition.revision >= current.revision) recentDefinitions[definition.id] = definition
        configuration = configuration?.let { old -> old.copy(definitions = if (old.definitions.none { it.id == definition.id })
            old.definitions + definition else old.definitions.map { if (it.id == definition.id && definition.revision >= it.revision) definition else it }) }
    }
    fun leaveEditor() { cancelRequest(); selectedId = null; draft = null; creating = false; error = null }
    fun toggle(bot: BotDefinition, enabled: Boolean) {
        if (operation != null || immutableCatalogBot(bot)) return
        operation = "toggle"; error = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { repository.getOrThrow().setEnabled(bot.id, bot.revision, enabled) }
                replaceDefinition(updated)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "$failedMessage ${failure.message.orEmpty()}" }
            finally { operation = null }
        }
    }
    DisposableEffect(context) {
        onDispose { requestToken?.cancel(); requestJob?.cancel() }
    }
    DisposableEffect(repository) {
        val remove = repository.getOrNull()?.addChangeListener { definition ->
            scope.launch { replaceDefinition(definition) }
        }
        onDispose { remove?.run() }
    }
    LaunchedEffect(context, conversationId, reload) {
        cancelRequest(); error = null
        try {
            val fresh = withContext(Dispatchers.IO) {
                val definitions = repository.getOrThrow().definitions()
                val capabilities = CoreAgentRuntime.profileCapabilities(context, conversationId)
                    .filterNot { it == com.jarvys.agent.skills.SkillScopePolicy.APK_FACTORY_TOOL }.distinct()
                val skills = SkillRepository.get(context).also { it.refresh() }
                val available = skills.enabledForRun().map { it.metadata.id }.toSet()
                BotCatalogConfiguration(definitions, capabilities.map { CrewProfileOption(it, CoreToolRegistry.humanizeToolName(it)) },
                    skills.skills.value.filterNot { com.jarvys.agent.skills.SkillScopePolicy.isReserved(it.metadata.id) }
                        .map { CrewProfileOption(it.metadata.id, it.metadata.name, it.metadata.id in available) }, icons.getOrNull()?.let { runCatching { it.isAvailable }.getOrDefault(false) } ?: false)
            }
            // Listener deliveries that arrived during the load must win over an older disk snapshot.
            val merged = fresh.definitions.associateBy { it.id }.toMutableMap()
            recentDefinitions.values.forEach { changed ->
                if (changed.revision >= (merged[changed.id]?.revision ?: 0)) merged[changed.id] = changed
            }
            configuration = fresh.copy(definitions = merged.values.toList())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "$loadFailedMessage ${failure.message.orEmpty()}" }
    }
    val loaded = configuration
    if (loaded == null) {
        BackHandler(onBack = onClose)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("bots-loading")) {
            BotsHeader(stringResource(R.string.bots_title), onClose)
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (error == null) Text(stringResource(R.string.bots_loading)) else {
                    BotsError(error.orEmpty())
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.bots_retry)) }
                }
            }
        }
        return@CompositionLocalProvider
    }
    val editing = draft ?: selectedId?.let { id -> loaded.definitions.firstOrNull { it.id == id } }
    when {
        editing != null -> BotDefinitionEditor(editing, draft != null, loaded.capabilities, loaded.skills,
            saving = operation == "save", changingEnabled = operation == "toggle", iconAvailable = loaded.iconAvailable,
            iconGenerating = operation == "icon", iconCompletedRevision = iconCompletedRevision, error = error, iconError = iconError,
            onClose = ::leaveEditor, onEnabledChange = { toggle(editing, it) },
            onCancelIcon = {
                val cancelledId = editing.id
                cancelRequest()
                // A response can finish just as Cancel is tapped. Re-read only metadata, preserving editor text.
                scope.launch { runCatching { withContext(Dispatchers.IO) { repository.getOrThrow().definition(cancelledId) } }
                    .getOrNull()?.let(::replaceDefinition) }
            },
            onGenerateIcon = { prompt ->
                if (operation == null && draft == null && !immutableCatalogBot(editing)) {
                    error = null; iconError = null; operation = "icon"
                    val request = CancellationToken.cancellable(); requestToken = request
                    val requestEpoch = ++epoch
                    requestJob = scope.launch {
                        try {
                            val updated = withContext(Dispatchers.IO) { icons.getOrThrow().generateAndAssign(editing.id, editing.revision, prompt, request) }
                            if (requestEpoch == epoch && !request.isCancelled) { replaceDefinition(updated); iconCompletedRevision = updated.revision }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { if (requestEpoch == epoch) iconError = "$failedMessage ${failure.message.orEmpty()}" }
                        finally { if (requestEpoch == epoch) { operation = null; requestToken = null; requestJob = null } }
                    }
                }
            },
            onSave = { edited ->
                if (operation == null && !immutableCatalogBot(edited)) {
                    operation = "save"; error = null
                    val newBot = draft != null
                    val session = conversationId
                    scope.launch {
                        try {
                            val updated = withContext(Dispatchers.IO) {
                                val capabilities = CoreAgentRuntime.profileCapabilities(context, session)
                                val skills = SkillRepository.get(context).also { it.refresh() }.enabledForRun().map { it.metadata.id }
                                if (newBot) repository.getOrThrow().create(edited.profile, capabilities, skills)
                                else repository.getOrThrow().save(edited, capabilities, skills)
                            }
                            if (latestConversation == session) {
                                replaceDefinition(updated)
                                selectedId = null; draft = null; creating = false
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = "$failedMessage ${failure.message.orEmpty()}" }
                        finally { operation = null }
                    }
                }
            })
        creating -> BotCreationPrompt(operation == "draft", error, onCancel = { cancelRequest(); creating = false; error = null },
            onGenerate = { prompt ->
                if (operation == null) {
                    operation = "draft"; error = null
                    val request = CancellationToken.cancellable(); requestToken = request
                    val requestEpoch = ++epoch
                    requestJob = scope.launch {
                        try {
                            val profile = withContext(Dispatchers.IO) { BotProfileDraftService(context).generate(prompt,
                                loaded.capabilities.filter { it.available }.map { it.id }, loaded.skills.filter { it.available }.map { it.id }, request) }
                            if (requestEpoch == epoch && !request.isCancelled) draft = BotDefinition(profile, 1, true, false, "")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { if (requestEpoch == epoch) error = draftFailedMessage }
                        finally { if (requestEpoch == epoch) { operation = null; requestToken = null; requestJob = null } }
                    }
                }
            })
        else -> BotsCatalogGrid(loaded.definitions, working, onOpen = { selectedId = it.id; error = null; iconError = null; iconCompletedRevision = null },
            onCreate = { creating = true; error = null }, onClose = onClose,
            busy = operation != null, error = error)
    }
    }
}

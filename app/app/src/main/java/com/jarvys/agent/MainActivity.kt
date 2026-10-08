package com.jarvys.agent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import com.jarvys.agent.ui.chat.PendingChatAttachment
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfers
import com.jarvys.agent.ui.chat.ChatFileTransferHost
import com.jarvys.agent.ui.chat.LocalChatFileActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarvys.agent.proactive.ProactivePreferences
import com.jarvys.agent.proactive.ProactiveEventStore
import com.jarvys.agent.proactive.ProactiveScheduler
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.proactive.ProactiveStatusProvider
import com.jarvys.agent.proactive.ProactiveWorkNames
import com.jarvys.agent.proactive.ProactiveRunController
import com.jarvys.agent.proactive.ProactiveDecisionAuditStore
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.proactive.ProactiveNotificationPermission
import androidx.work.WorkManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.artemis.helper.ArtemisAccessibilityService
import com.jarvys.agent.mcp.McpConnectionManager
import com.jarvys.agent.mcp.McpOAuthManager
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.mcp.mcpDestinations
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.connectorsDestinations
import com.jarvys.agent.memory.memoryDestinations
import com.jarvys.agent.tasks.TaskStore
import com.jarvys.agent.tasks.TaskDataChanges
import com.jarvys.agent.tasks.ui.TaskDeepLink
import com.jarvys.agent.tasks.ui.TaskUiProjection
import com.jarvys.agent.tasks.ui.TasksNavigationRoutes
import com.jarvys.agent.tasks.ui.tasksDestinations
import com.jarvys.agent.providers.providersDestinations
import com.jarvys.agent.providers.ProviderServiceRegistry
import com.jarvys.agent.providers.OpenAiModelFields
import com.jarvys.agent.providers.OpenAiApiModelField
import com.jarvys.agent.providers.OpenRouterModelField
import com.jarvys.agent.providers.CustomEndpointModel
import com.jarvys.agent.providers.CustomEndpointModelField
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.approveAndAllowAlways
import com.jarvys.agent.connectors.ApprovalOutcomeTone
import com.jarvys.agent.connectors.approvalPrimaryActionResourceId
import com.jarvys.agent.connectors.approvalStatusPresentation
import com.jarvys.agent.connectors.compactApprovalSummary
import com.jarvys.agent.connectors.ApprovalIntentKind
import com.jarvys.agent.connectors.SafDocumentPickerHost
import com.jarvys.agent.connectors.ApprovalIntentSpec
import com.jarvys.agent.skills.SkillRepository
import com.jarvys.agent.skills.SkillGitHubImporter
import com.jarvys.agent.skills.SkillImportEntryDialog
import com.jarvys.agent.skills.SkillImportEntryKind
import com.jarvys.agent.skills.SkillImportOptionsSheet
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.skills.SkillsScreen
import com.jarvys.agent.skills.SkillEntry
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewMissionScreen
import com.jarvys.agent.crew.CrewBotDetailScreen
import com.jarvys.agent.crew.CrewModePicker
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewApprovalAttribution
import com.jarvys.agent.crew.CrewNavigationRoutes
import com.jarvys.agent.crew.crewDestinations
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.chat.ChatComposer
import com.jarvys.agent.ui.chat.ConversationDrawer
import com.jarvys.agent.ui.chat.ArchivedChatsScreen
import com.jarvys.agent.ui.chat.ScheduledTasksPlaceholderScreen
import com.jarvys.agent.ui.shell.JarvysRouteTopBar
import com.jarvys.agent.ui.shell.JarvysShellFrame
import com.jarvys.agent.ui.shell.ModelSelectorSheet
import com.jarvys.agent.ui.shell.quickModelOptions
import com.jarvys.agent.ui.shell.quickModelDisplayName
import com.jarvys.agent.ui.shell.modelEffortAppearance
import com.jarvys.agent.ui.shell.effortDisplayLabel
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.FileInputStream

private object Routes {
    const val CHAT = AppNavigationBackPolicy.CHAT_ROOT
    const val SETTINGS = AppNavigationBackPolicy.SETTINGS
    const val SETTINGS_PREFERENCES = AppNavigationBackPolicy.SETTINGS_PREFERENCES
    const val ARCHIVED_CHATS = AppNavigationBackPolicy.ARCHIVED_CHATS
    const val SCHEDULED_TASKS = AppNavigationBackPolicy.SCHEDULED_TASKS
    const val PROVIDERS = AppNavigationBackPolicy.PROVIDERS
    const val PROVIDERS_OPENAI = AppNavigationBackPolicy.PROVIDERS_OPENAI
    const val PROVIDERS_OPENROUTER = AppNavigationBackPolicy.PROVIDERS_OPENROUTER
    const val PROVIDERS_CUSTOM = AppNavigationBackPolicy.PROVIDERS_CUSTOM
    const val PROVIDERS_SERVICE = AppNavigationBackPolicy.PROVIDERS_SERVICE
    const val MCP = AppNavigationBackPolicy.MCP_LIST
    const val MCP_NEW = AppNavigationBackPolicy.MCP_NEW
    const val SKILLS = AppNavigationBackPolicy.SKILLS
    const val CONNECTORS = AppNavigationBackPolicy.CONNECTORS
    const val CONNECTOR_DEVICE = AppNavigationBackPolicy.CONNECTOR_DEVICE
    const val CONNECTOR_REMOTE = AppNavigationBackPolicy.CONNECTOR_REMOTE
    const val CONNECTOR_GOOGLE = AppNavigationBackPolicy.CONNECTOR_GOOGLE
    const val MEMORY = AppNavigationBackPolicy.MEMORY
    const val TASKS = AppNavigationBackPolicy.TASKS
    const val TASK_DETAIL = AppNavigationBackPolicy.TASK_DETAIL
    const val BOTS = AppNavigationBackPolicy.BOTS
    const val CREW_EMPTY = AppNavigationBackPolicy.CREW_EMPTY
    const val CREW = AppNavigationBackPolicy.CREW
    const val CREW_BOT = AppNavigationBackPolicy.CREW_BOT
    const val WORKSPACE_PREVIEW = AppNavigationBackPolicy.WORKSPACE_PREVIEW
    fun crew(missionId: String) = CrewNavigationRoutes.mission(missionId)
    fun crewBot(missionId: String, botId: String) = CrewNavigationRoutes.bot(missionId, botId)
    fun connectorDevice(id: String) = AppNavigationBackPolicy.connectorDevice(id)
    fun connectorRemote(id: String, title: String) = AppNavigationBackPolicy.connectorRemote(id, title)
    fun connectorGoogle(id: String, title: String) = AppNavigationBackPolicy.connectorGoogle(id, title)
    fun providerService(id: String) = AppNavigationBackPolicy.providerService(id)
    fun taskDetail(id: String) = TasksNavigationRoutes.detail(id)
}

/** Jarvys launcher: provider setup and the existing on-device run entry points. */
class MainActivity : ComponentActivity() {
    private var activeNavController: NavHostController? = null
    internal fun navGraphRoutePatternsForTest(): Set<String> = activeNavController?.graph?.let { graph ->
        buildSet {
            val destinations = graph.iterator()
            while (destinations.hasNext()) destinations.next().route?.let(::add)
        }
    }.orEmpty()
    private lateinit var providersRepository: com.jarvys.agent.providers.ProvidersRepository
    private lateinit var mcpServerRepository: McpServerRepository
    private lateinit var mcpConnectionManager: McpConnectionManager
    private lateinit var mcpOAuthManager: McpOAuthManager
    private lateinit var localRunStore: LocalRunStore
    private lateinit var uiPreferences: JarvysUiPreferences
    private lateinit var proactivePreferences: ProactivePreferences
    private lateinit var assistantSpeechController: AssistantSpeechController
    private lateinit var memoryStore: MemoryStore
    private lateinit var reflectionPreferences: MemoryReflectionPreferences
    private lateinit var skillRepository: SkillRepository
    private lateinit var connectorRegistry: ConnectorRegistry
    private lateinit var attachmentDrafts: AttachmentDraftViewModel
    private var attachmentPickerSession: String? = null
    private var conversationActionPending by mutableStateOf(false)
    private var goalInput by mutableStateOf("")
    private var lastRunReport by mutableStateOf("")
    private var themeMode by mutableStateOf(JarvysThemeMode.SYSTEM)
    private var languageChoice by mutableStateOf(AppLanguageChoice.ENGLISH)
    private var showAgentEvents by mutableStateOf(true)
    private var proactiveEnabled by mutableStateOf(false)
    private var proactiveStatus by mutableStateOf(ProactiveStatus(enabled = false))
    private var speakingMessageId by mutableStateOf<String?>(null)
    private var activeTranslationMessageId by mutableStateOf<String?>(null)
    private var translationErrorMessageId by mutableStateOf<String?>(null)
    private var translationError by mutableStateOf<String?>(null)
    private var translationFuture: Future<*>? = null
    private var agentTimeoutSeconds by mutableStateOf(JarvysUiPreferences.DEFAULT_AGENT_TIMEOUT_SECONDS)
    private var runHistory by mutableStateOf<List<RunHistoryItem>>(emptyList())
    private var selectedHistoryRunId by mutableStateOf<String?>(null)
    private var proactiveTargetMessageId by mutableStateOf<String?>(null)
    private var conversationSessionId by mutableStateOf(java.util.UUID.randomUUID().toString())
    private var conversationTitle by mutableStateOf<String?>(null)
    private var crewModeChoice by mutableStateOf(CrewMode.AUTO)
    private var historyEvents by mutableStateOf<List<AgentRunUiEvent>>(emptyList())
    private var showingNewChat by mutableStateOf(false)
    private var captureContextRequested by mutableStateOf(false)
    private var selectedSkillIds by mutableStateOf<Set<String>>(emptySet())
    private var memoryEnabled by mutableStateOf(true)
    private var memoryCoreCharacters by mutableIntStateOf(0)
    private var showMemoryDisclosure by mutableStateOf(false)
    private var reflectionEnabled by mutableStateOf(true)
    private var reflectionStatus by mutableStateOf("")
    private var reflectionLastSuccessMillis by mutableLongStateOf(0L)
    private var showReflectionDisclosure by mutableStateOf(false)
    private var manualReflectionAfterDisclosure by mutableStateOf(false)
    private var chatWithoutMemory by mutableStateOf(false)
    private var pendingTaskId: String? = null
    private var memoryChangeListener: MemoryStore.MemoryChangeListener? = null
    private val historyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysRunHistory").apply { isDaemon = true }
    }
    private val chatExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysConversationChat").apply { isDaemon = true }
    }
    private val sessionTitleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysSessionTitle").apply { isDaemon = true }
    }
    private val memoryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysMemoryUi").apply { isDaemon = true }
    }
    private val skillImportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysSkillImport").apply { isDaemon = true }
    }
    private lateinit var fileTransfers: ChatFileTransfers
    private val skillImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importSkillMarkdown)
    }
    private val attachmentPhotosLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val session = attachmentPickerSession
        attachmentPickerSession = null
        if (session != null && ::attachmentDrafts.isInitialized) attachmentDrafts.addUris(session, uris, ChatAttachment.Kind.IMAGE)
    }
    private val attachmentFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val session = attachmentPickerSession
        attachmentPickerSession = null
        if (session != null && ::attachmentDrafts.isInitialized) attachmentDrafts.addUris(session, uris)
    }
    private val attachmentCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (::attachmentDrafts.isInitialized) attachmentDrafts.finishCamera(success)
    }
    private val proactiveNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshProactiveStatus()
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageChoice = AppLanguageRuntime.current(this)
        CodexModelCatalog.loadCached(this)
        providersRepository = com.jarvys.agent.providers.ProvidersRepository.get(applicationContext)
        com.jarvys.agent.connectors.ApprovalNotificationCenter.attach(applicationContext)
        com.jarvys.agent.connectors.ApprovalNotificationCenter.attach(applicationContext)
        mcpServerRepository = McpServerRepository.get(this)
        mcpOAuthManager = McpOAuthManager.get(mcpServerRepository)
        mcpConnectionManager = McpConnectionManager.get(this)
        mcpConnectionManager.connectEnabledServers()
        localRunStore = LocalRunStore(this)
        attachmentDrafts = ViewModelProvider(this)[AttachmentDraftViewModel::class.java]
        fileTransfers = ViewModelProvider(this)[ChatFileTransfers::class.java]
        attachmentPickerSession = savedInstanceState?.getString("attachment_picker_session")
        assistantSpeechController = AssistantSpeechController(
            this,
            onSpeakingChanged = { messageId -> runOnUiThread { speakingMessageId = messageId } },
            onUnavailable = { runOnUiThread { toast(getString(R.string.chat_footer_tts_unavailable), Toast.LENGTH_LONG) } },
        )
        memoryStore = MemoryStore(this)
        migrateMemorySeedsAsync()
        reflectionPreferences = MemoryReflectionPreferences(this)
        memoryEnabled = memoryStore.isEnabled()
        showMemoryDisclosure = memoryEnabled && !memoryStore.hasShownDisclosure()
        refreshReflectionUiState(conversationSessionId)
        showReflectionDisclosure = memoryEnabled && reflectionEnabled && !reflectionPreferences.disclosureShown()
        memoryChangeListener = MemoryStore.MemoryChangeListener { revision ->
            runOnUiThread {
                AgentRunUiState.memoryChanged(revision)
                refreshMemoryStatus()
            }
        }.also(memoryStore::addChangeListener)
        uiPreferences = JarvysUiPreferences(this)
        proactivePreferences = ProactivePreferences(this)
        // The default-off upgrade path purges P0-era pending notification payloads before they can be consumed.
        val proactiveIsEnabled = proactivePreferences.enabled
        if (!proactiveIsEnabled) {
            ProactiveRunController.cancelAll()
            ProactiveEventStore(this).clearPending()
            ProactiveDecisionAuditStore(this).scrubNotificationText()
        }
        ProactiveScheduler.setEnabled(this, proactiveIsEnabled)
        com.jarvys.agent.tasks.TaskScheduler.rearm(applicationContext)
        observeProactiveWork()
        connectorRegistry = ConnectorRegistry.get(this)
        connectorRegistry.refreshStates()
        skillRepository = SkillRepository.get(this)
        themeMode = uiPreferences.themeMode()
        showAgentEvents = uiPreferences.showAgentEvents()
        proactiveEnabled = proactivePreferences.enabled
        refreshProactiveStatus()
        agentTimeoutSeconds = uiPreferences.agentTimeoutSeconds()
        goalInput = savedInstanceState?.getString(STATE_GOAL).orEmpty()
        selectedHistoryRunId = savedInstanceState?.getString(STATE_HISTORY_RUN)
        proactiveTargetMessageId = intent?.getStringExtra(EXTRA_OPEN_PROACTIVE_MESSAGE_ID)
            ?: savedInstanceState?.getString(STATE_PROACTIVE_TARGET_MESSAGE)
        pendingTaskId = TaskDeepLink.existingTaskId(this,
            intent?.getStringExtra(EXTRA_OPEN_TASK) ?: savedInstanceState?.getString(STATE_OPEN_TASK))
        conversationSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
            ?: intent?.getStringExtra(EXTRA_OPEN_CHAT_SESSION)
            ?: getSharedPreferences("jarvys_chat", MODE_PRIVATE).getString("active_session_id", null)
            ?: java.util.UUID.randomUUID().toString()
        val openingProactiveThread = conversationSessionId == ProactiveConversation.SESSION_ID
        if (openingProactiveThread) {
            selectedHistoryRunId = null
            goalInput = ""
            selectedSkillIds = emptySet()
        }
        crewModeChoice = CrewMode.read(this)
        chatWithoutMemory = getChatPreferences().getBoolean(MemoryUiLogic.sessionMemoryDisabledKey(conversationSessionId), false)
        conversationTitle = if (openingProactiveThread) ProactiveConversation.title(this)
            else savedInstanceState?.getString(STATE_SESSION_TITLE)
        persistConversationSession(conversationSessionId)
        showingNewChat = if (openingProactiveThread) false else savedInstanceState?.getBoolean(STATE_NEW_CHAT, false) ?: false
        selectedSkillIds = if (openingProactiveThread) emptySet()
            else savedInstanceState?.getStringArrayList(STATE_SELECTED_SKILLS)?.toSet().orEmpty()
        refreshUi()
        restoreConversationSession(conversationSessionId)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                attachmentDrafts.submissions.collect { submission ->
                    if (submission.persisted) {
                        requestConversationTitle(submission.sessionId, submission.text)
                        refreshRunHistory()
                    } else if (conversationSessionId == submission.sessionId && goalInput.isBlank()) {
                        goalInput = submission.text
                    }
                    submission.error?.let { toast(it, Toast.LENGTH_LONG) }
                }
            }
        }
        setContent {
            val pendingAttachments by attachmentDrafts.drafts.collectAsState()
            val transfers by fileTransfers.transfers.collectAsState()
            // Capture the rendered conversation, never the mutable selection after an asynchronous action.
            val fileActionSession = conversationSessionId
            val attachmentSending by attachmentDrafts.sending.collectAsState()
            val agentState by AgentRunUiState.state.collectAsState()
            val taskDataRevision by TaskDataChanges.revision.collectAsState()
            val scheduledTasksAvailable = remember(taskDataRevision) {
                runCatching { TaskUiProjection.showSettingsEntry(TaskStore(applicationContext).list().size) }.getOrDefault(false)
            }
            LaunchedEffect(conversationSessionId, agentState.reflecting, agentState.reflectionStatus,
                agentState.reflectionSessionId) {
                if (::reflectionPreferences.isInitialized) refreshReflectionUiState(conversationSessionId)
            }
            val skillEntries by skillRepository.skills.collectAsState()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            SideEffect {
                val dark = when (themeMode) {
                    JarvysThemeMode.SYSTEM -> systemDark
                    JarvysThemeMode.LIGHT -> false
                    JarvysThemeMode.DARK -> true
                }
                window.navigationBarColor = (if (dark) JarvysPalette.CanvasDark else JarvysPalette.CanvasLight).toArgb()
                window.decorView.systemUiVisibility = if (dark) 0 else (
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
            JarvysOwnTheme(themeMode) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(
                    transfers, fileTransfers::download, fileTransfers::share, fileTransfers::open, fileTransfers::cancel,
                )) {
                SafDocumentPickerHost(this@MainActivity)
                JarvysApp(
                    providersRepository = providersRepository,
                    languageChoice = languageChoice,
                    onLanguageChange = ::changeLanguage,
                    goal = goalInput,
                    onGoalChange = { goalInput = it },
                    lastRunReport = lastRunReport,
                    agentState = agentState,
                    runHistory = runHistory,
                    selectedHistoryRunId = selectedHistoryRunId,
                    proactiveTargetMessageId = proactiveTargetMessageId,
                    conversationSessionId = conversationSessionId,
                    scheduledTasksAvailable = scheduledTasksAvailable,
                    crewMode = crewModeChoice,
                    onCrewModeChange = ::persistCrewMode,
                    conversationTitle = conversationTitle,
                    memoryStore = memoryStore,
                    memoryEnabled = memoryEnabled,
                    memoryCoreCharacters = memoryCoreCharacters,
                    showMemoryDisclosure = showMemoryDisclosure,
                    reflectionEnabled = reflectionEnabled,
                    reflectionStatus = if (agentState.reflecting) agentState.reflectionStatus.orEmpty() else reflectionStatus,
                    reflectionLastSuccessMillis = reflectionLastSuccessMillis,
                    showReflectionDisclosure = showReflectionDisclosure,
                    chatWithoutMemory = chatWithoutMemory,
                    historyEvents = historyEvents,
                    showingNewChat = showingNewChat,
                    showAgentEvents = showAgentEvents,
                    onTranslateAssistant = ::translateAssistantMessage,
                    onCancelTranslation = ::cancelAssistantTranslation,
                    onHideTranslation = ::hideAssistantTranslation,
                    onDeleteAssistant = ::deleteAssistantMessage,
                    onProactiveSuggestedReply = { messageId, index ->
                        if (!com.jarvys.agent.proactive.ProactiveInteractionDispatcher
                                .submitSuggestedReply(this@MainActivity, messageId, index)) {
                            toast(getString(R.string.proactive_suggested_reply_unavailable))
                        }
                    },
                    onRegenerateAssistant = ::regenerateAssistantMessage,
                    onSpeakAssistant = { id, text ->
                        if (speakingMessageId == id) assistantSpeechController.stop(id)
                        else assistantSpeechController.speak(id, text)
                    },
                    speakingMessageId = speakingMessageId,
                    activeTranslationMessageId = activeTranslationMessageId,
                    translationErrorMessageId = translationErrorMessageId,
                    translationError = translationError,
                    agentTimeoutSeconds = agentTimeoutSeconds,
                    captureContextRequested = captureContextRequested,
                    skillEnabledCount = skillEntries.count { it.enabled && it.validationError == null },
                    skillTotalCount = skillEntries.size,
                    availableSkills = skillEntries.filter { it.enabled && it.validationError == null &&
                        com.jarvys.agent.skills.SkillScopePolicy.availableTo(it.metadata.id, null) },
                    selectedSkillIds = selectedSkillIds,
                    onSubmitMessage = ::submitMessage,
                    pendingAttachments = pendingAttachments,
                    attachmentSending = attachmentSending,
                    onRemoveAttachment = attachmentDrafts::remove,
                    onAttachmentCamera = ::launchAttachmentCamera,
                    onAttachmentPhotos = ::launchAttachmentPhotos,
                    onAttachmentFiles = ::launchAttachmentFiles,
                    conversationActionPending = conversationActionPending,
                    onConversationAction = ::applyConversationAction,
                    onStopRun = ::stopCurrentWork,
                    onNavControllerReady = { controller ->
                        activeNavController = controller
                        pendingTaskId?.let { taskId ->
                            openTaskDetailFromIntent(controller, taskId)
                            pendingTaskId = null
                        }
                    },
                    onStopTest = ::startStopTest,
                    onDeviceTest = ::startManualDeviceTest,
                    onAccessibilitySettings = ::openAccessibilitySettings,
                    onMemoryEnabledChange = ::updateMemoryEnabled,
                    onMemoryDisclosureDismiss = ::dismissMemoryDisclosure,
                    onMemoryDisclosureOpen = ::openMemoryFromDisclosure,
                    onReflectionEnabledChange = ::updateReflectionEnabled,
                    onReflectionDisclosureDismiss = ::acceptReflectionDisclosure,
                    onReflectionDisclosureDisable = ::disableReflectionFromDisclosure,
                    onReflectionDisclosureShow = ::showReflectionDisclosureAgain,
                    onReflectionManual = ::startManualReflection,
                    onMemoryDisclosureShow = ::showMemoryDisclosureAgain,
                    onMemoryToggleForChat = ::toggleMemoryForCurrentChat,
                    onManualCompact = ::startManualCompaction,
                    onMemoryChanged = ::refreshMemoryStatus,
                    onUndoMemoryRevision = ::undoMemoryRevisionFromChat,
                    onUndoReflectionGroup = ::undoReflectionGroupFromChat,
                    themeMode = themeMode,
                    onThemeChange = ::persistThemeMode,
                    onShowAgentEventsChange = ::persistShowAgentEvents,
                    proactiveEnabled = proactiveEnabled,
                    proactiveStatus = proactiveStatus,
                    onProactiveEnabledChange = ::persistProactiveEnabled,
                    onRefreshProactiveStatus = ::refreshProactiveStatus,
                    onAgentTimeoutChange = ::persistAgentTimeout,
                    onSelectHistory = ::selectHistoryRun,
                    onNewChat = ::newChat,
                    onCaptureContext = ::markCaptureContext,
                    onToggleRunSkill = ::toggleRunSkill,
                    onImportSkill = ::launchSkillImport,
                    onImportSkillMarkdown = ::importSkillText,
                    onImportSkillGitHub = ::importSkillFromGitHub,
                    onHistoryUpdated = ::refreshRunHistory,
                    onSaveGeneratedImage = { event -> saveGeneratedImage(fileActionSession, event) },
                    onShareGeneratedImage = { event -> shareGeneratedImage(fileActionSession, event) },
                    mcpServerRepository = mcpServerRepository,
                    mcpConnectionManager = mcpConnectionManager,
                    mcpOAuthManager = mcpOAuthManager,
                    skillRepository = skillRepository,
                    connectorRegistry = connectorRegistry,
                )
                ChatFileTransferHost(fileTransfers) { intent -> startActivity(intent) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        languageChoice = AppLanguageRuntime.synchronizeSystemChoice(this)
        migrateMemorySeedsAsync()
        if (::providersRepository.isInitialized) providersRepository.refresh()
        refreshUi()
        if (::connectorRegistry.isInitialized) connectorRegistry.refreshStates()
        if (::memoryStore.isInitialized) refreshMemoryStatus()
        if (::reflectionPreferences.isInitialized) refreshReflectionUiState(conversationSessionId)
        if (::proactivePreferences.isInitialized) refreshProactiveStatus()
    }

    override fun onStart() {
        super.onStart()
        appForeground = true
        updateUserDecisionUiAvailability()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTaskId = null
        val taskId = TaskDeepLink.existingTaskId(this, intent.getStringExtra(EXTRA_OPEN_TASK))
        if (taskId != null) {
            pendingTaskId = taskId
            activeNavController?.let { controller ->
                openTaskDetailFromIntent(controller, taskId)
                pendingTaskId = null
            }
            return
        }
        val session = intent.getStringExtra(EXTRA_OPEN_CHAT_SESSION)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }
            ?: return
        proactiveTargetMessageId = intent.getStringExtra(EXTRA_OPEN_PROACTIVE_MESSAGE_ID)
        if (session != conversationSessionId) {
            conversationSessionId = session
            persistConversationSession(session)
        }
        selectedHistoryRunId = null
        showingNewChat = false
        historyEvents = emptyList()
        goalInput = ""
        conversationTitle = if (session == ProactiveConversation.SESSION_ID) ProactiveConversation.title(this) else null
        restoreConversationSession(session)
        refreshUi()
        activeNavController?.let { controller ->
            if (controller.currentDestination?.route != Routes.CHAT) {
                controller.popBackStack(Routes.CHAT, false)
                if (controller.currentDestination?.route != Routes.CHAT) {
                    controller.navigate(Routes.CHAT) { launchSingleTop = true }
                }
            }
        }
    }

    override fun onDestroy() {
        memoryChangeListener?.let(memoryStore::removeChangeListener)
        memoryChangeListener = null
        if (::assistantSpeechController.isInitialized) assistantSpeechController.shutdown()
        translationFuture?.cancel(true)
        super.onDestroy()
    }

    override fun onStop() {
        appForeground = false
        UserDecisionUiAvailability.setChatVisible(false)
        if (::assistantSpeechController.isInitialized) assistantSpeechController.stop()
        super.onStop()
    }

    private fun updateUserDecisionUiAvailability() {
        UserDecisionUiAvailability.setChatVisible(appForeground
            && activeNavController?.currentDestination?.route == Routes.CHAT)
    }

    private fun openTaskDetailFromIntent(controller: NavHostController, taskId: String) {
        controller.navigate(Routes.TASKS) { launchSingleTop = true }
        controller.navigate(Routes.taskDetail(taskId)) { launchSingleTop = true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        attachmentPickerSession?.let { outState.putString("attachment_picker_session", it) }
        outState.putString(STATE_GOAL, goalInput)
        outState.putString(STATE_SESSION_ID, conversationSessionId)
        conversationTitle?.let { outState.putString(STATE_SESSION_TITLE, it) }
        selectedHistoryRunId?.let { outState.putString(STATE_HISTORY_RUN, it) }
        proactiveTargetMessageId?.let { outState.putString(STATE_PROACTIVE_TARGET_MESSAGE, it) }
        pendingTaskId?.let { outState.putString(STATE_OPEN_TASK, it) }
        outState.putBoolean(STATE_NEW_CHAT, showingNewChat)
        outState.putStringArrayList(STATE_SELECTED_SKILLS, ArrayList(selectedSkillIds))
        super.onSaveInstanceState(outState)
    }

    private fun changeLanguage(choice: AppLanguageChoice) {
        if (languageChoice == choice) return
        AppLanguageRuntime.select(this, choice)
        languageChoice = choice
        migrateMemorySeedsAsync()
        if (::reflectionPreferences.isInitialized) refreshReflectionUiState(conversationSessionId)
        if (Build.VERSION.SDK_INT < 33) recreate()
    }

    private fun migrateMemorySeedsAsync() {
        if (!::memoryStore.isInitialized) return
        val store = memoryStore
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.ensureInitialized() }
                .onFailure { android.util.Log.w("JarvysMemory", "Could not migrate starter memory seeds", it) }
        }
    }

    private fun submitMessage() {
        if (conversationActionPending) return
        val message = goalInput
        if (message.isBlank() && attachmentDrafts.drafts.value.isEmpty()) return
        val state = AgentRunUiState.state.value
        if (state.running || state.compacting || state.reflecting) return
        val sessionId = conversationSessionId
        val enabledSkillIds = skillRepository.enabledForRun().filter { it.metadata.id in selectedSkillIds }.map { it.metadata.id }
        if (conversationTitle == null) conversationTitle = ConversationTitle.fromFirstMessage(message)
        if (attachmentDrafts.submit(sessionId, message, ArrayList(enabledSkillIds), chatWithoutMemory)) {
            goalInput = ""
            selectedHistoryRunId = null
            historyEvents = emptyList()
            showingNewChat = false
            captureContextRequested = false
            selectedSkillIds = emptySet()
        }
    }

    private fun startStopTest() {
        AgentForegroundService.startStopTest(this)
        moveTaskToBack(true)
    }

    private fun startManualDeviceTest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(getString(R.string.chat_toast_capture_unsupported), Toast.LENGTH_LONG)
            return
        }
        if (ArtemisAccessibilityService.getInstance() == null) {
            toast(getString(R.string.chat_toast_enable_accessibility), Toast.LENGTH_LONG)
            return
        }
        AgentForegroundService.startManualDeviceTest(this)
        moveTaskToBack(true)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun refreshUi() {
        val accessibility = if (ArtemisAccessibilityService.getInstance() == null) {
            "Accesibilidad Jarvys: desactivada."
        } else {
            "Accesibilidad Jarvys: activa."
        }
        lastRunReport = AgentForegroundService.getLastRunReport()
        refreshRunHistory()
    }

    private fun persistThemeMode(mode: JarvysThemeMode) {
        uiPreferences.setThemeMode(mode)
        themeMode = mode
    }

    private fun persistShowAgentEvents(value: Boolean) {
        uiPreferences.setShowAgentEvents(value)
        showAgentEvents = value
    }

    private fun persistProactiveEnabled(value: Boolean) {
        proactivePreferences.enabled = value
        proactiveEnabled = value
        refreshProactiveStatus()
        if (ProactiveNotificationPermission.shouldRequestOnOptIn(this, value)) {
            proactiveNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshProactiveStatus() {
        if (!::proactivePreferences.isInitialized) return
        lifecycleScope.launch { proactiveStatus = ProactiveStatusProvider.read(this@MainActivity) }
    }

    private fun observeProactiveWork() {
        val workManager = WorkManager.getInstance(applicationContext)
        workManager.getWorkInfosForUniqueWorkLiveData(ProactiveWorkNames.PERIODIC)
            .observe(this) { refreshProactiveStatus() }
        workManager.getWorkInfosForUniqueWorkLiveData(ProactiveWorkNames.RUN_NOW)
            .observe(this) { refreshProactiveStatus() }
    }

    private fun persistAgentTimeout(seconds: Int) {
        uiPreferences.setAgentTimeoutSeconds(seconds)
        agentTimeoutSeconds = seconds
    }

    private fun getChatPreferences() = getSharedPreferences("jarvys_chat", MODE_PRIVATE)

    private fun refreshMemoryStatus() {
        if (!::memoryStore.isInitialized) return
        memoryEnabled = memoryStore.isEnabled()
        memoryExecutor.execute {
            val used = runCatching { memoryStore.coreCharactersUsed() }.getOrDefault(0)
            runOnUiThread { memoryCoreCharacters = used }
        }
    }

    private fun updateMemoryEnabled(enabled: Boolean) {
        memoryStore.setEnabled(enabled)
        memoryEnabled = enabled
        if (!enabled) {
            MemoryReflectionRuntime.cancelAll()
            advanceAllReflectionCheckpoints("memory disabled")
        }
        if (enabled && !memoryStore.hasShownDisclosure()) showMemoryDisclosure = true
        if (!enabled) showReflectionDisclosure = false
        else if (reflectionEnabled && !reflectionPreferences.disclosureShown()) showReflectionDisclosure = true
        refreshMemoryStatus()
    }

    private fun dismissMemoryDisclosure() {
        memoryStore.markDisclosureShown()
        showMemoryDisclosure = false
    }

    private fun openMemoryFromDisclosure() {
        dismissMemoryDisclosure()
    }

    private fun showMemoryDisclosureAgain() {
        showMemoryDisclosure = true
    }

    private fun refreshReflectionUiState(sessionId: String) {
        if (!::reflectionPreferences.isInitialized) return
        if (!MemoryReflectionRuntime.isAnyRunning()) reflectionPreferences.recoverGlobalInterrupted(
            System.currentTimeMillis(), getString(R.string.reflection_interrupted))
        reflectionEnabled = reflectionPreferences.enabled()
        reflectionStatus = reflectionPreferences.localizedStatus(
            reflectionPreferences.globalStatus().ifBlank { reflectionPreferences.status(sessionId) })
        reflectionLastSuccessMillis = maxOf(reflectionPreferences.globalLastSuccess(), reflectionPreferences.lastSuccess(sessionId))
    }

    private fun updateReflectionEnabled(value: Boolean) {
        reflectionPreferences.setEnabled(value)
        reflectionEnabled = value
        if (value && memoryEnabled && !reflectionPreferences.disclosureShown()) showReflectionDisclosure = true
        if (!value) {
            MemoryReflectionRuntime.cancelAll()
            advanceAllReflectionCheckpoints("automatic reflection disabled")
        }
        refreshReflectionUiState(conversationSessionId)
    }

    private fun showReflectionDisclosureAgain() { showReflectionDisclosure = true }

    private fun advanceAllReflectionCheckpoints(reason: String) {
        historyExecutor.execute {
            runCatching {
                val store = LocalRunStore(this)
                store.listConversationSessionIds().forEach { store.advanceReflectionCheckpoint(it, reason) }
            }
        }
    }

    private fun acceptReflectionDisclosure() {
        reflectionPreferences.markDisclosureShown()
        reflectionPreferences.setEnabled(true)
        reflectionEnabled = true
        showReflectionDisclosure = false
        if (manualReflectionAfterDisclosure) {
            manualReflectionAfterDisclosure = false
            startManualReflection()
        }
    }

    private fun disableReflectionFromDisclosure() {
        reflectionPreferences.setEnabled(false)
        reflectionPreferences.markDisclosureShown()
        reflectionEnabled = false
        showReflectionDisclosure = false
        manualReflectionAfterDisclosure = false
        advanceAllReflectionCheckpoints("reflection disabled from disclosure")
    }

    private fun toggleMemoryForCurrentChat() {
        val next = !chatWithoutMemory
        getChatPreferences().edit()
            .putBoolean(MemoryUiLogic.sessionMemoryDisabledKey(conversationSessionId), next)
            .apply()
        chatWithoutMemory = next
        if (next) {
            MemoryReflectionRuntime.cancel(conversationSessionId)
            localRunStore.advanceReflectionCheckpoint(conversationSessionId, "chat without memory")
        }
    }


    private fun startManualCompaction() {
        if (AgentRunUiState.state.value.running || AgentRunUiState.state.value.compacting) return
        if (localRunStore.conversationMessageCount(conversationSessionId) < 4) {
            toast(getString(R.string.compaction_manual_minimum))
            return
        }
        AgentForegroundService.startManualConversationCompaction(this, conversationSessionId)
    }

    private fun translateAssistantMessage(event: AgentRunUiEvent, targetLanguage: String) {
        val sessionId = conversationSessionId
        val messageId = event.messageId.ifBlank { "event-${event.id}" }
        val prompt = """You are a translation expert, skilled in translating various languages, and maintaining accuracy, faithfulness, and elegance in translation.
Next, I will send you text. Please translate it into $targetLanguage, and return the translation result directly, without adding any explanations or other content.

Please translate the <source_text> section:
<source_text>
${event.text}
</source_text>"""
        translationFuture?.cancel(true)
        activeTranslationMessageId = messageId
        translationErrorMessageId = null
        translationError = null
        translationFuture = chatExecutor.submit {
            try {
                val text = CoreAgentModel(applicationContext, "$sessionId-translation")
                    .completeSummary("", prompt, CancellationToken.uncancellable()).text.trim()
                if (Thread.currentThread().isInterrupted || activeTranslationMessageId != messageId) return@submit
                if (text.isBlank()) throw IllegalStateException(getString(R.string.chat_footer_translation_empty))
                localRunStore.appendAssistantTranslation(sessionId, messageId, targetLanguage, text)
                runOnUiThread {
                    if (activeTranslationMessageId == messageId) {
                        activeTranslationMessageId = null
                        translationFuture = null
                        refreshConversationTimeline(sessionId)
                    }
                }
            } catch (error: Throwable) {
                if (error is InterruptedException || Thread.currentThread().isInterrupted) return@submit
                runOnUiThread {
                    if (activeTranslationMessageId == messageId) {
                        activeTranslationMessageId = null
                        translationFuture = null
                        translationErrorMessageId = messageId
                        translationError = getString(R.string.chat_footer_translation_error)
                    }
                }
            }
        }
    }

    private fun cancelAssistantTranslation(messageId: String) {
        if (activeTranslationMessageId != messageId) return
        translationFuture?.cancel(true)
        translationFuture = null
        activeTranslationMessageId = null
    }

    private fun hideAssistantTranslation(messageId: String) {
        val sessionId = conversationSessionId
        runCatching { localRunStore.appendAssistantTranslationHidden(sessionId, messageId) }
            .onSuccess { refreshConversationTimeline(sessionId) }
            .onFailure { toast(getString(R.string.chat_footer_translation_error), Toast.LENGTH_LONG) }
    }

    private fun deleteAssistantMessage(event: AgentRunUiEvent) {
        if (AgentRunUiState.state.value.running) return
        val sessionId = conversationSessionId
        runCatching { localRunStore.appendAssistantDeleted(sessionId, event.messageId) }
            .onSuccess { refreshConversationTimeline(sessionId) }
            .onFailure { toast(getString(R.string.chat_footer_delete_error), Toast.LENGTH_LONG) }
    }

    private fun regenerateAssistantMessage(event: AgentRunUiEvent) {
        val sessionId = conversationSessionId
        if (AgentRunUiState.state.value.running || AgentRunUiState.state.value.compacting
            || !localRunStore.isLatestActiveAssistant(sessionId, event.messageId)) return
        val prompt = localRunStore.findUserMessageBeforeAssistant(sessionId, event.messageId) ?: run {
            toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG)
            return
        }
        runCatching { localRunStore.appendAssistantRegenerated(sessionId, event.messageId) }
            .onFailure { toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG); return }
        cancelAssistantTranslation(event.messageId)
        translationErrorMessageId = null
        translationError = null
        selectedHistoryRunId = null
        historyEvents = emptyList()
        showingNewChat = false
        AgentRunUiState.restoreSession(sessionId, localRunStore.readConversationTimeline(sessionId))
        val skillIds = skillRepository.enabledForRun().filter { it.metadata.id in selectedSkillIds }.map { it.metadata.id }
        runCatching {
            AgentForegroundService.startRegenerateAssistant(this, prompt.text, ArrayList(skillIds),
                sessionId, prompt.userMessageId, chatWithoutMemory)
        }.onFailure {
            toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG)
        }
    }

    private fun refreshConversationTimeline(sessionId: String) {
        if (conversationSessionId != sessionId) return
        val events = localRunStore.readConversationTimeline(sessionId)
        if (selectedHistoryRunId == null) AgentRunUiState.refreshPersistedSession(sessionId, events)
        else historyEvents = events
    }

    private fun shareGeneratedImage(sessionId: String, event: AgentRunUiEvent) {
        ChatFileRequest.generated(sessionId, event)?.let(fileTransfers::share)
    }

    private fun saveGeneratedImage(sessionId: String, event: AgentRunUiEvent) {
        ChatFileRequest.generated(sessionId, event)?.let(fileTransfers::download)
    }

    private fun undoMemoryRevisionFromChat(revisionId: Long) {
        val sessionId = conversationSessionId
        memoryExecutor.execute {
            val result = runCatching { memoryStore.undoRevision(revisionId, MemoryStore.Actor.USER, sessionId) }
            runOnUiThread {
                result.onSuccess {
                    toast(getString(R.string.memory_chat_undo_done))
                    refreshMemoryStatus()
                }.onFailure { error ->
                    toast(getString(R.string.memory_chat_undo_conflict), Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun undoReflectionGroupFromChat(reflectionId: String) {
        val sessionId = conversationSessionId
        memoryExecutor.execute {
            val result = runCatching {
                val revisions = memoryStore.undoReflectionGroup(reflectionId, sessionId)
                localRunStore.appendReflectionUndoEvent(sessionId, reflectionId)
                revisions
            }
            runOnUiThread {
                result.onSuccess {
                    AgentRunUiState.reflectionUndone(sessionId, reflectionId)
                    toast(getString(R.string.reflection_undo_done))
                    refreshMemoryStatus()
                }.onFailure {
                    toast(getString(R.string.reflection_undo_conflict), Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun startManualReflection() {
        if (!memoryEnabled || chatWithoutMemory) {
            toast(getString(R.string.reflection_unavailable))
            return
        }
        if (AgentRunUiState.state.value.running || AgentRunUiState.state.value.compacting
            || MemoryReflectionRuntime.isAnyRunning()) return
        if (!reflectionPreferences.disclosureShown()) {
            manualReflectionAfterDisclosure = true
            showReflectionDisclosure = true
            return
        }
        AgentForegroundService.startMemoryReflection(this, conversationSessionId,
            MemoryReflectionCoordinator.TRIGGER_MANUAL, true)
    }

    private fun stopCurrentWork() {
        attachmentDrafts.cancelSubmission()
        ProactiveRunController.cancelAll()
        val state = AgentRunUiState.state.value
        if (state.reflecting && state.reflectionSessionId != null) {
            MemoryReflectionRuntime.cancel(state.reflectionSessionId)
        }
        if (state.running || state.compacting) StopController.getInstance().stopRun()
    }

    private fun persistCrewMode(mode: CrewMode) {
        crewModeChoice = mode
        CrewMode.write(this, mode)
    }

    private fun newChat() {
        persistConversationSession(java.util.UUID.randomUUID().toString())
        conversationTitle = null
        AgentRunUiState.resetSession(conversationSessionId)
        selectedHistoryRunId = null
        historyEvents = emptyList()
        goalInput = ""
        showingNewChat = true
        captureContextRequested = false
        selectedSkillIds = emptySet()
    }

    private fun markCaptureContext() {
        captureContextRequested = true
    }

    private fun toggleRunSkill(skillId: String) {
        selectedSkillIds = if (skillId in selectedSkillIds) {
            selectedSkillIds - skillId
        } else if (selectedSkillIds.size >= 8) {
            toast(getString(R.string.chat_toast_skill_limit, 8), Toast.LENGTH_LONG)
            selectedSkillIds
        } else {
            selectedSkillIds + skillId
        }
    }

    private fun launchSkillImport() {
        skillImportLauncher.launch(arrayOf("*/*"))
    }

    private fun canAttachToCurrentChat(): Boolean {
        if (isManagedSystemConversation(conversationSessionId)) {
            toast(getString(R.string.chat_attachment_main_only))
            return false
        }
        val state = AgentRunUiState.state.value
        return !state.running && !state.compacting && !state.reflecting && !attachmentDrafts.sending.value
    }

    private fun launchAttachmentPhotos() {
        if (!canAttachToCurrentChat()) return
        attachmentPickerSession = conversationSessionId
        runCatching { attachmentPhotosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { attachmentPickerSession = null; toast(getString(R.string.chat_attachment_picker_failed), Toast.LENGTH_LONG) }
    }

    private fun launchAttachmentFiles() {
        if (!canAttachToCurrentChat()) return
        attachmentPickerSession = conversationSessionId
        runCatching { attachmentFilesLauncher.launch(arrayOf("*/*")) }
            .onFailure { attachmentPickerSession = null; toast(getString(R.string.chat_attachment_picker_failed), Toast.LENGTH_LONG) }
    }

    private fun launchAttachmentCamera() {
        if (!canAttachToCurrentChat()) return
        runCatching {
            val file = attachmentDrafts.createCameraFile(conversationSessionId)
            attachmentCameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.generated-images", file))
        }.onFailure {
            attachmentDrafts.finishCamera(false)
            toast(getString(R.string.chat_attachment_picker_failed), Toast.LENGTH_LONG)
        }
    }

    private fun importSkillMarkdown(uri: Uri) {
        importSkillAsync {
                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
                require(displayName.endsWith(".md", ignoreCase = true)) { "Selecciona un archivo .md" }
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= com.jarvys.agent.skills.SkillMarkdownParser.MAX_DOCUMENT_BYTES) {
                            "El archivo supera el límite de 256 KiB"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: error("No se pudo leer el archivo seleccionado")
                val markdown = String(bytes, StandardCharsets.UTF_8)
                skillRepository.importMarkdown(markdown)
        }
    }

    private fun importSkillText(markdown: String) {
        importSkillAsync { skillRepository.importMarkdown(markdown) }
    }

    private fun importSkillFromGitHub(url: String) {
        importSkillAsync { skillRepository.importMarkdown(SkillGitHubImporter.downloadMarkdown(url)) }
    }

    private fun importSkillAsync(operation: () -> com.jarvys.agent.skills.SkillEntry) {
        skillImportExecutor.execute {
            val result = runCatching(operation)
            runOnUiThread {
                result.onSuccess {
                    skillRepository.refresh()
                    toast(getString(R.string.chat_toast_skill_imported, it.metadata.name))
                }.onFailure { error -> toast(getString(R.string.chat_toast_skill_import_failed, error.message.orEmpty()), Toast.LENGTH_LONG) }
            }
        }
    }

    private fun selectHistoryRun(runId: String?) {
        selectedHistoryRunId = runId
        showingNewChat = false
        if (runId == null) {
            historyEvents = emptyList()
            val sessionId = conversationSessionId
            historyExecutor.execute {
                val title = runCatching { localRunStore.readConversationTitle(sessionId) }.getOrNull()
                runOnUiThread { if (conversationSessionId == sessionId) conversationTitle = title }
            }
            return
        }
        val historyItem = runHistory.firstOrNull { it.id == runId }
        conversationTitle = historyItem?.title
        val sessionId = historyItem?.sessionId ?: runId
        persistConversationSession(sessionId)
        historyExecutor.execute {
            runCatching { localRunStore.recoverCrewMissions(sessionId) }
            val conversationEvents = runCatching {
                localRunStore.readConversationTimeline(sessionId)
            }.getOrDefault(emptyList())
            val events = if (conversationEvents.isNotEmpty()) conversationEvents else runCatching {
                val stepEvents = localRunStore.readAllSteps().filter { it.optString("run_id") == runId }.mapIndexed { index, row ->
                    val step = row.optInt("step", index + 1)
                    val decisions = row.opt("decisions")?.toString().orEmpty()
                    val result = row.optString("result", "")
                    val summary = row.optString("summary", "")
                    val text = listOfNotNull(
                        decisions.takeIf(String::isNotBlank)?.let { getString(R.string.history_actions, it) },
                        result.takeIf(String::isNotBlank)?.let { getString(R.string.history_result, it) },
                        summary.takeIf(String::isNotBlank)?.let { getString(R.string.history_summary, it) },
                    ).joinToString("\n")
                    AgentRunUiEvent(id = index.toLong() + 1, kind = "progress",
                        stage = getString(R.string.history_step, step), text = text.ifBlank { getString(R.string.history_step_recorded) })
                }
                buildList {
                    historyItem?.let { add(AgentRunUiEvent(0L, "user", text = it.goal)) }
                    addAll(stepEvents)
                    historyItem?.let {
                         add(AgentRunUiEvent(Long.MAX_VALUE, "result", it.outcome,
                             getString(R.string.history_run_summary, it.outcome, it.steps, it.turns)))
                    }
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (selectedHistoryRunId == runId && conversationSessionId == sessionId) {
                    historyEvents = events
                    AgentRunUiState.restoreSession(sessionId, events)
                }
            }
        }
    }

    private fun restoreConversationSession(sessionId: String) {
        historyExecutor.execute {
            runCatching { localRunStore.recoverCrewMissions(sessionId) }
            val title = if (sessionId == ProactiveConversation.SESSION_ID) ProactiveConversation.title(this)
            else runCatching {
                localRunStore.readConversationTitle(sessionId)
                    ?: ConversationTitle.fromFirstMessage(localRunStore.readFirstUserMessage(sessionId))
            }.getOrNull()
            val events = runCatching {
                localRunStore.readConversationTimeline(sessionId)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (conversationSessionId == sessionId && selectedHistoryRunId == null && events.isNotEmpty()) {
                    AgentRunUiState.restoreSession(sessionId, events)
                }
                if (conversationSessionId == sessionId && selectedHistoryRunId == null && title != null) {
                    conversationTitle = title
                }
            }
        }
    }

    private fun requestConversationTitle(sessionId: String, submittedMessage: String) {
        sessionTitleExecutor.execute {
            if (!localRunStore.claimConversationTitleGeneration(sessionId)) return@execute
            try {
                val firstUserMessage = runCatching { localRunStore.readFirstUserMessage(sessionId) }
                    .getOrNull()?.takeIf(String::isNotBlank) ?: submittedMessage
                val generatedTitle = runCatching {
                    val model = CoreAgentModel(applicationContext, "$sessionId-title")
                    val response = model.complete(
                        "Generate a concise conversation title in the same language as the user's first message. "
                                + "Use 3-6 words, maximum 60 characters, sentence case, no emoji. Return only the title.",
                        emptyList(),
                        firstUserMessage,
                        emptyList(),
                        CancellationToken.uncancellable(),
                    )
                    ConversationTitle.normalizeModelTitle(response.text)
                }.getOrNull()
                val title = generatedTitle ?: ConversationTitle.fromFirstMessage(firstUserMessage) ?: "Jarvys"
                if (localRunStore.appendConversationTitleIfAbsent(sessionId, title)) {
                    runOnUiThread {
                        if (conversationSessionId == sessionId) conversationTitle = title
                        refreshRunHistory()
                    }
                }
            } catch (_: RuntimeException) {
                val fallback = ConversationTitle.fromFirstMessage(submittedMessage) ?: "Jarvys"
                if (runCatching { localRunStore.appendConversationTitleIfAbsent(sessionId, fallback) }.getOrDefault(false)) {
                    runOnUiThread {
                        if (conversationSessionId == sessionId) conversationTitle = fallback
                        refreshRunHistory()
                    }
                }
            } finally {
                localRunStore.releaseConversationTitleGeneration(sessionId)
            }
        }
    }

    private fun persistConversationSession(sessionId: String) {
        conversationSessionId = sessionId
        if (::attachmentDrafts.isInitialized) attachmentDrafts.switchSession(sessionId)
        getSharedPreferences("jarvys_chat", MODE_PRIVATE).edit()
            .putString("active_session_id", sessionId)
            .apply()
        chatWithoutMemory = getChatPreferences().getBoolean(MemoryUiLogic.sessionMemoryDisabledKey(sessionId), false)
        refreshReflectionUiState(sessionId)
    }

    private fun applyConversationAction(sessionId: String, action: ConversationAction, title: String?) {
        if (conversationActionPending) return
        if (action == ConversationAction.DELETE && isManagedSystemConversation(sessionId)) {
            toast(getString(R.string.drawer_system_chat_delete))
            return
        }
        fun blocked() = conversationRemovalBlocked(AgentRunUiState.state.value, attachmentDrafts.sending.value,
            activeTranslationMessageId != null, StopController.getInstance().isStopped(), CoreAgentRuntime.hasActiveCrewBots()) ||
            MemoryReflectionRuntime.isAnyRunning()
        val removal = action == ConversationAction.DELETE || action == ConversationAction.ARCHIVE
        if (removal && blocked()) { toast(getString(R.string.drawer_busy)); return }
        conversationActionPending = true
        historyExecutor.execute {
            val result = runCatching {
                check(!removal || !blocked()) { "Active work must finish first" }
                when (action) {
                    ConversationAction.PIN -> { localRunStore.setConversationPinned(sessionId, true); true }
                    ConversationAction.UNPIN -> { localRunStore.setConversationPinned(sessionId, false); true }
                    ConversationAction.ARCHIVE -> { localRunStore.setConversationArchived(sessionId, true); true }
                    ConversationAction.RESTORE -> { localRunStore.setConversationArchived(sessionId, false); true }
                    ConversationAction.RENAME -> { localRunStore.renameConversation(sessionId, title.orEmpty()); true }
                    ConversationAction.DELETE -> {
                        val cleaned = localRunStore.deleteConversation(sessionId)
                        check(cleaned || localRunStore.readConversationMetadata(sessionId).deleted) { "Chat was not deleted" }
                        cleaned
                    }
                }
            }.recoverCatching { failure ->
                if (action == ConversationAction.DELETE && runCatching { localRunStore.readConversationMetadata(sessionId).deleted }.getOrDefault(false)) false
                else throw failure
            }
            val updatedTitle = if (result.isSuccess && action == ConversationAction.RENAME)
                runCatching { localRunStore.readConversationTitle(sessionId) }.getOrNull() else null
            runOnUiThread {
                conversationActionPending = false
                result.onSuccess { cleaned ->
                    if (conversationActionLeavesCurrent(action, sessionId, conversationSessionId)) {
                        newChat()
                        activeNavController?.navigate(Routes.CHAT) {
                            popUpTo(Routes.CHAT) { inclusive = true }; launchSingleTop = true
                        }
                    } else if (action == ConversationAction.RENAME && sessionId == conversationSessionId) conversationTitle = updatedTitle
                    refreshRunHistory()
                    when (action) {
                        ConversationAction.ARCHIVE -> toast(getString(R.string.drawer_archived_notice))
                        ConversationAction.RESTORE -> toast(getString(R.string.drawer_restored_notice))
                        ConversationAction.DELETE -> toast(getString(if (cleaned) R.string.drawer_deleted_notice else R.string.drawer_deleted_partial))
                        else -> Unit
                    }
                }.onFailure { toast(getString(R.string.drawer_action_failed), Toast.LENGTH_LONG) }
            }
        }
    }

    private fun refreshRunHistory() {
        if (!::localRunStore.isInitialized) return
        historyExecutor.execute {
            val items = runCatching {
                localRunStore.listConversations().asSequence()
                    .filterNot { it.optString("session_id") == ProactiveConversation.SESSION_ID }
                    .mapNotNull { json ->
                    val id = json.optString("run_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    RunHistoryItem(
                        id = id,
                        goal = json.optString("goal", "Corrida de Jarvys"),
                        outcome = json.optString("outcome", "UNKNOWN"),
                        steps = json.optInt("steps", 0),
                        turns = json.optInt("turns", 0),
                        timestampSeconds = json.optDouble("timestamp", 0.0),
                        sessionId = json.optString("session_id").takeIf(String::isNotBlank) ?: id,
                        title = json.optString("title").takeIf(String::isNotBlank),
                        pinned = json.optBoolean("pinned", false),
                        archived = json.optBoolean("archived", false),
                    )
                }.toList()
                    .distinctBy { it.sessionId }
            }.getOrDefault(emptyList())
            runOnUiThread {
                runHistory = items
                selectedHistoryRunId?.let(::selectHistoryRun)
            }
        }
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    companion object {
        const val EXTRA_OPEN_CHAT_SESSION = "open_chat_session_id"
        const val EXTRA_OPEN_PROACTIVE_MESSAGE_ID = "open_proactive_message_id"
        const val EXTRA_OPEN_TASK = "open_scheduled_task_id"
        @Volatile private var appForeground = false
        @JvmStatic fun isAppForeground(): Boolean = appForeground
        const val STATE_GOAL = "agent_goal_input"
        const val STATE_HISTORY_RUN = "selected_history_run"
        const val STATE_PROACTIVE_TARGET_MESSAGE = "proactive_target_message"
        const val STATE_OPEN_TASK = "open_scheduled_task_id"
        const val STATE_SESSION_ID = "chat_session_id"
        const val STATE_SESSION_TITLE = "chat_session_title"
        const val STATE_NEW_CHAT = "showing_new_chat"
        const val STATE_SELECTED_SKILLS = "selected_run_skills"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvysApp(
    providersRepository: com.jarvys.agent.providers.ProvidersRepository,
    languageChoice: AppLanguageChoice,
    onLanguageChange: (AppLanguageChoice) -> Unit,
    goal: String,
    onGoalChange: (String) -> Unit,
    lastRunReport: String,
    agentState: AgentRunUiSnapshot,
    runHistory: List<RunHistoryItem>,
    selectedHistoryRunId: String?,
    proactiveTargetMessageId: String?,
    conversationSessionId: String,
    scheduledTasksAvailable: Boolean,
    crewMode: CrewMode,
    onCrewModeChange: (CrewMode) -> Unit,
    conversationTitle: String?,
    memoryStore: MemoryStore,
    memoryEnabled: Boolean,
    memoryCoreCharacters: Int,
    showMemoryDisclosure: Boolean,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    reflectionLastSuccessMillis: Long,
    showReflectionDisclosure: Boolean,
    chatWithoutMemory: Boolean,
    historyEvents: List<AgentRunUiEvent>,
    showingNewChat: Boolean,
    showAgentEvents: Boolean,
    proactiveEnabled: Boolean,
    proactiveStatus: ProactiveStatus,
    onTranslateAssistant: (AgentRunUiEvent, String) -> Unit,
    onCancelTranslation: (String) -> Unit,
    onHideTranslation: (String) -> Unit,
    onDeleteAssistant: (AgentRunUiEvent) -> Unit,
    onProactiveSuggestedReply: (String, Int) -> Unit,
    onRegenerateAssistant: (AgentRunUiEvent) -> Unit,
    onSpeakAssistant: (String, String) -> Unit,
    speakingMessageId: String?,
    activeTranslationMessageId: String?,
    translationErrorMessageId: String?,
    translationError: String?,
    agentTimeoutSeconds: Int,
    captureContextRequested: Boolean,
    skillEnabledCount: Int,
    skillTotalCount: Int,
    availableSkills: List<SkillEntry>,
    selectedSkillIds: Set<String>,
    onSubmitMessage: () -> Unit,
    pendingAttachments: List<PendingChatAttachment>,
    attachmentSending: Boolean,
    onRemoveAttachment: (String) -> Unit,
    onAttachmentCamera: () -> Unit,
    onAttachmentPhotos: () -> Unit,
    onAttachmentFiles: () -> Unit,
    conversationActionPending: Boolean,
    onConversationAction: (String, ConversationAction, String?) -> Unit,
    onStopRun: () -> Unit,
    onNavControllerReady: (NavHostController) -> Unit,
    onStopTest: () -> Unit,
    onDeviceTest: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onMemoryDisclosureDismiss: () -> Unit,
    onMemoryDisclosureOpen: () -> Unit,
    onMemoryDisclosureShow: () -> Unit,
    onReflectionEnabledChange: (Boolean) -> Unit,
    onReflectionDisclosureDismiss: () -> Unit,
    onReflectionDisclosureDisable: () -> Unit,
    onReflectionDisclosureShow: () -> Unit,
    onReflectionManual: () -> Unit,
    onMemoryToggleForChat: () -> Unit,
    onManualCompact: () -> Unit,
    onMemoryChanged: () -> Unit,
    onUndoMemoryRevision: (Long) -> Unit,
    onUndoReflectionGroup: (String) -> Unit,
    themeMode: JarvysThemeMode,
    onThemeChange: (JarvysThemeMode) -> Unit,
    onShowAgentEventsChange: (Boolean) -> Unit,
    onProactiveEnabledChange: (Boolean) -> Unit,
    onRefreshProactiveStatus: () -> Unit,
    onAgentTimeoutChange: (Int) -> Unit,
    onSelectHistory: (String?) -> Unit,
    onNewChat: () -> Unit,
    onCaptureContext: () -> Unit,
    onToggleRunSkill: (String) -> Unit,
    onImportSkill: () -> Unit,
    onImportSkillMarkdown: (String) -> Unit,
    onImportSkillGitHub: (String) -> Unit,
    onHistoryUpdated: () -> Unit,
    mcpServerRepository: McpServerRepository,
    mcpConnectionManager: McpConnectionManager,
    mcpOAuthManager: McpOAuthManager,
    skillRepository: com.jarvys.agent.skills.SkillRepository,
    connectorRegistry: com.jarvys.agent.connectors.ConnectorRegistry,
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
) {
    val context = LocalContext.current
    val providersState by providersRepository.state.collectAsState()
    val model = providersState.activeModel
    val reasoningVariant = providersState.activeReasoningVariant
    val codexModels by CodexModelCatalog.models.collectAsState()
    val apiModels by CodexModelCatalog.openAiApiModels.collectAsState()
    val routerModels by CodexModelCatalog.openRouterModels.collectAsState()
    val composerModels = quickModelOptions(providersState.activeProvider, codexModels, apiModels, routerModels, providersState.customModels)
    val composerModelName = quickModelDisplayName(composerModels, model)
    val composerVariants = if (providersState.activeProvider == ProviderSettings.Provider.OPENAI_CODEX)
        codexModels.firstOrNull { it.id == model }?.variants.orEmpty() else emptyList()
    val composerEffortAppearance = modelEffortAppearance(composerVariants, reasoningVariant)
    val composerEffortLabel = composerEffortAppearance?.let { effortDisplayLabel(composerVariants[it.selectedIndex].id) }.orEmpty()
    LaunchedEffect(providersRepository) {
        providersRepository.feedback.collect { feedback ->
            Toast.makeText(context, context.getString(feedback.resourceId, *feedback.arguments.toTypedArray()), Toast.LENGTH_LONG).show()
        }
    }
    val googleAuthorizationResolution = remember { com.jarvys.agent.connectors.ActivityIntentSenderBroker.get() }
    val googleAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        googleAuthorizationResolution.complete(it)
    }
    DisposableEffect(googleAuthorizationResolution, googleAuthorizationLauncher) {
        googleAuthorizationResolution.attach { request: IntentSenderRequest -> googleAuthorizationLauncher.launch(request) }
        onDispose { googleAuthorizationResolution.detach() }
    }
    val navController = rememberNavController()
    SideEffect { onNavControllerReady(navController) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navEntry by navController.currentBackStackEntryAsState()
    val route = navEntry?.destination?.route ?: Routes.CHAT
    val routeTaskId = navEntry?.arguments?.getString("taskId")
    val taskDataRevision by com.jarvys.agent.tasks.TaskDataChanges.revision.collectAsState()
    val routeTaskTitle = remember(routeTaskId, taskDataRevision) {
        routeTaskId?.let { id -> runCatching { TaskStore(context.applicationContext).get(id)?.name }.getOrNull() }
    }
    SideEffect {
        UserDecisionUiAvailability.setChatVisible(MainActivity.isAppForeground() && route == Routes.CHAT)
    }
    val routeMissionId = navEntry?.arguments?.getString("missionId")
    val routeBotId = navEntry?.arguments?.getString("botId")
    val routeMcpServerId = navEntry?.arguments?.getString("serverId")
    val routeConnectorId = navEntry?.arguments?.getString("connectorId")
    val routeProviderServiceId = navEntry?.arguments?.getString("serviceId")
    val routeServiceTitle = navEntry?.arguments?.getString("title")
    val mcpServers by mcpServerRepository.servers.collectAsState()
    val connectorDefinitions by connectorRegistry.definitions.collectAsState()
    val routeMcpServerAlias = mcpServers.firstOrNull { it.id == routeMcpServerId }?.alias
    val routeDetailTitle = when (route) {
        Routes.CONNECTOR_DEVICE -> connectorDefinitions.firstOrNull { it.id == routeConnectorId }?.let { definition ->
            if (definition.displayNameResourceId != 0) context.getString(definition.displayNameResourceId) else definition.name
        }
        Routes.CONNECTOR_REMOTE, Routes.CONNECTOR_GOOGLE -> routeServiceTitle
        Routes.PROVIDERS_SERVICE -> routeProviderServiceId?.let(ProviderServiceRegistry::find)
            ?.let { context.getString(it.titleResource) }
        Routes.PROVIDERS_CUSTOM -> providersState.customName.ifBlank { context.getString(R.string.provider_custom_endpoint_name) }
        Routes.TASK_DETAIL -> routeTaskTitle
        else -> routeMcpServerAlias
    }
    val routeCrewMission = routeMissionId?.let { missionId -> agentState.events.lastOrNull {
        it.kind == "crew_mission" && it.crewMissionSnapshot?.missionId == missionId
    }?.crewMissionSnapshot }
    val routeBotName = routeCrewMission?.bots?.firstOrNull { it.id == routeBotId }?.name
    LaunchedEffect(conversationSessionId) {
        withContext(Dispatchers.IO) {
            runCatching { CoreAgentRuntime.prepareCrewHistory(context.applicationContext, conversationSessionId) }
        }
    }
    val crewBoard = remember(conversationSessionId) {
        CrewBoard(WorkspaceStore.forCrewBoard(context.applicationContext, conversationSessionId))
    }
    var quickModelProvider by remember { mutableStateOf<ProviderSettings.Provider?>(null) }
    var showSkillImportOptions by remember { mutableStateOf(false) }
    var skillImportEntry by remember { mutableStateOf<SkillImportEntryKind?>(null) }
    var skillFileLink by remember { mutableStateOf<SkillFileLink?>(null) }
    var crewBoardReference by remember { mutableStateOf<String?>(null) }
    val showActiveConversation = (agentState.goal.isNotBlank() || agentState.events.any { it.kind == "user" && it.attachments.isNotEmpty() })
        && selectedHistoryRunId == null && !showingNewChat
    LaunchedEffect(agentState.runId, agentState.running) {
        if (!agentState.running && agentState.runId != null) onHistoryUpdated()
    }

    val chatTitle = when {
            route == Routes.CHAT && selectedHistoryRunId != null -> runHistory.firstOrNull { it.id == selectedHistoryRunId }
                ?.let { it.title ?: ConversationTitle.fromFirstMessage(it.goal) ?: it.goal }
                ?: stringResource(R.string.chat_previous_run)
            route == Routes.CHAT && showingNewChat -> conversationTitle ?: stringResource(R.string.drawer_new_chat)
            else -> conversationTitle ?: "Jarvys"
        }
    val routeMeta = AppNavigationBackPolicy.metadata(context, route, chatTitle, routeBotName,
        routeDetailTitle)

    JarvysShellFrame(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                history = runHistory,
                isDrawerOpen = drawerState.isOpen,
                onCloseDrawer = { scope.launch { drawerState.close() } },
                activeSessionId = conversationSessionId,
                activeTitle = conversationTitle,
                activeGoal = agentState.goal,
                activeSessionVisible = showActiveConversation,
                selectedHistoryId = selectedHistoryRunId,
                isChatRoute = route == Routes.CHAT,
                actionsEnabled = !conversationActionPending,
                onConversationAction = onConversationAction,
                onNewConversation = {
                    onNewChat()
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                    scope.launch { drawerState.close() }
                },
                onResumeActive = {
                    onSelectHistory(null)
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                },
                onOpenHistory = { runId ->
                    onSelectHistory(runId)
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                },
                onOpenScheduledTasks = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.SCHEDULED_TASKS) { launchSingleTop = true }
                },
                onOpenBots = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.BOTS) { launchSingleTop = true }
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        },
        route = routeMeta,
        agentSnapshot = agentState,
        onBack = { if (!navController.popBackStack()) navController.navigate(Routes.CHAT) { launchSingleTop = true } },
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
        onNewChat = {
            onNewChat()
            navController.navigate(Routes.CHAT) { launchSingleTop = true }
        },
        onAddServer = { navController.navigate(Routes.MCP_NEW) },
        onImportSkill = { showSkillImportOptions = true },
        chatWithoutMemory = chatWithoutMemory,
        canCompact = agentState.events.count { it.kind == "user" || it.kind == "assistant" } >= 4
            && !agentState.running && !agentState.compacting,
        compacting = agentState.compacting,
        canReflect = memoryEnabled && !chatWithoutMemory && !agentState.running && !agentState.compacting
            && !(agentState.reflecting && agentState.reflectionSessionId == conversationSessionId),
        onToggleMemory = onMemoryToggleForChat,
        onCompact = onManualCompact,
        onReflect = onReflectionManual,
        bottomBar = {
            if (route == Routes.CHAT) ChatComposer(
                    goal = goal,
                    onGoalChange = onGoalChange,
                    onSend = onSubmitMessage,
                    pendingAttachments = pendingAttachments,
                    attachmentSessionId = conversationSessionId,
                    attachmentSending = attachmentSending,
                    onRemoveAttachment = onRemoveAttachment,
                    onAttachmentCamera = onAttachmentCamera,
                    onAttachmentPhotos = onAttachmentPhotos,
                    onAttachmentFiles = onAttachmentFiles,
                    onStop = onStopRun,
                    onSelectModel = { quickModelProvider = providersState.activeProvider },
                    modelLabel = composerModelName,
                    effortLabel = composerEffortLabel,
                    effortAppearance = composerEffortAppearance,
                    effortMotionActive = quickModelProvider == null && drawerState.isClosed,
                    running = agentState.running || agentState.compacting
                            || agentState.reflecting && agentState.reflectionSessionId == conversationSessionId,
                    captureContextRequested = captureContextRequested,
                    onCaptureContext = onCaptureContext,
                    onImportSkill = onImportSkill,
                    availableSkills = availableSkills,
                    selectedSkillIds = selectedSkillIds,
                    onToggleRunSkill = onToggleRunSkill,
                    skillEnabledCount = skillEnabledCount,
                    skillTotalCount = skillTotalCount,
                    onManageSkills = { navController.navigate(Routes.SKILLS) { launchSingleTop = true } },
                    crewMode = crewMode,
                    onCrewModeChange = onCrewModeChange,
                    onOpenCrew = {
                        val latestMission = agentState.events.lastOrNull { it.kind == "crew_mission" }
                            ?.crewMissionSnapshot
                        navController.navigate(latestMission?.let { Routes.crew(it.missionId) } ?: Routes.CREW_EMPTY) {
                            launchSingleTop = true
                        }
                    },
            )
        },
    ) { padding ->
            val settingsPageContent: @Composable (JarvysSettingsPage) -> Unit = { settingsPage ->
                JarvysSettingsScreen(
                    page = settingsPage,
                    themeMode = themeMode,
                    showAgentEvents = showAgentEvents,
                    agentTimeoutSeconds = agentTimeoutSeconds,
                    memoryEnabled = memoryEnabled,
                    memoryUsedCharacters = memoryCoreCharacters,
                    languageChoice = languageChoice,
                    onLanguageChange = onLanguageChange,
                    onThemeChange = onThemeChange,
                    onShowAgentEventsChange = onShowAgentEventsChange,
                    proactiveEnabled = proactiveEnabled,
                    proactiveStatus = proactiveStatus,
                    onProactiveEnabledChange = onProactiveEnabledChange,
                    onRefreshProactiveStatus = onRefreshProactiveStatus,
                    onAgentTimeoutChange = onAgentTimeoutChange,
                    onNavigateRoute = { navController.navigate(it) { launchSingleTop = true } },
                    onMcp = { navController.navigate(Routes.MCP) { launchSingleTop = true } },
                    onSkills = { navController.navigate(Routes.SKILLS) { launchSingleTop = true } },
                    onConnectors = { navController.navigate(Routes.CONNECTORS) { launchSingleTop = true } },
                    onMemory = { navController.navigate(Routes.MEMORY) { launchSingleTop = true } },
                    archivedChatsAvailable = runHistory.any { it.archived },
                    onArchivedChats = { navController.navigate(Routes.ARCHIVED_CHATS) { launchSingleTop = true } },
                    scheduledTasksAvailable = scheduledTasksAvailable,
                    onScheduledTasks = { navController.navigate(Routes.TASKS) { launchSingleTop = true } },
                    onAccessibilitySettings = onAccessibilitySettings,
                    onNavigate = { destination ->
                        val path = when (destination) {
                            JarvysSettingsPage.HOME -> Routes.SETTINGS
                            JarvysSettingsPage.PREFERENCES -> Routes.SETTINGS_PREFERENCES
                        }
                        navController.navigate(path) { launchSingleTop = true }
                    },
                )
            }
            NavHost(navController = navController, startDestination = Routes.CHAT, modifier = Modifier.padding(padding)) {
                composable(Routes.CHAT) {
                    val conversationKey = selectedHistoryRunId ?: if (showingNewChat) "new" else agentState.sessionId ?: conversationSessionId
                    Crossfade(targetState = conversationKey,
                        animationSpec = if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
                            else JarvysMotion.placeChange(),
                        label = "chat-conversation-transition") { visibleConversationKey ->
                        val displayedEvents = when {
                            selectedHistoryRunId != null -> historyEvents
                            showingNewChat -> emptyList()
                            else -> agentState.events.filter {
                                showAgentEvents || (it.kind != "progress" && (it.kind != "tool" || it.previewId != null))
                            }
                        }
                        AgentRunScreen(
                            conversationKey = visibleConversationKey,
                            generatedImageSessionId = conversationSessionId,
                            events = displayedEvents,
                            targetMessageId = proactiveTargetMessageId,
                            onProactiveSuggestedReply = onProactiveSuggestedReply,
                            isRunning = agentState.running && selectedHistoryRunId == null && !showingNewChat,
                            emptyReport = null,
                            connectorRegistry = connectorRegistry,
                            onOpenPreview = { projectId ->
                                navController.navigate("workspace-preview/${Uri.encode(projectId)}")
                            },
                            onOpenSkillFile = { skillFileLink = it },
                            chatWithoutMemory = chatWithoutMemory,
                            onUndoMemoryRevision = onUndoMemoryRevision,
                            onUndoReflectionGroup = onUndoReflectionGroup,
                            onTranslateAssistant = onTranslateAssistant,
                            onCancelTranslation = onCancelTranslation,
                            onHideTranslation = onHideTranslation,
                            onDeleteAssistant = onDeleteAssistant,
                            onRegenerateAssistant = onRegenerateAssistant,
                            onSpeakAssistant = onSpeakAssistant,
                            speakingMessageId = speakingMessageId,
                            activeTranslationMessageId = activeTranslationMessageId,
                            translationErrorMessageId = translationErrorMessageId,
                            translationError = translationError,
                            streamActive = agentState.running && selectedHistoryRunId == null && !showingNewChat,
                            allowRegeneration = !agentState.running && !agentState.compacting
                                && selectedHistoryRunId == null && !showingNewChat,
                            isCompacting = agentState.compacting && agentState.sessionId == conversationSessionId,
                            compactionStatus = agentState.compactionStatus,
                            isReflecting = agentState.reflecting && agentState.reflectionSessionId == conversationSessionId,
                            reflectionStatus = agentState.reflectionStatus,
                            onOpenCrewMission = { missionId -> navController.navigate(Routes.crew(missionId)) },
                            runSnapshot = agentState,
                            allowMessageEntrance = selectedHistoryRunId == null && !showingNewChat,
                            onSaveGeneratedImage = onSaveGeneratedImage,
                            onShareGeneratedImage = onShareGeneratedImage,
                        )
                    }
                }
                composable(Routes.ARCHIVED_CHATS) {
                    ArchivedChatsScreen(
                        history = runHistory,
                        actionsEnabled = !conversationActionPending,
                        onOpenHistory = { runId ->
                            onSelectHistory(runId)
                            navController.navigate(Routes.CHAT) { launchSingleTop = true }
                        },
                        onConversationAction = onConversationAction,
                    )
                }
                composable(Routes.SCHEDULED_TASKS) { ScheduledTasksPlaceholderScreen() }
                composable(Routes.SETTINGS) { settingsPageContent(JarvysSettingsPage.HOME) }
                composable(Routes.SETTINGS_PREFERENCES) { settingsPageContent(JarvysSettingsPage.PREFERENCES) }
                providersDestinations(navController, providersRepository)
                mcpDestinations(navController, mcpServerRepository, mcpConnectionManager, mcpOAuthManager)
                composable(Routes.SKILLS) { SkillsScreen(skillRepository) }
                connectorsDestinations(navController, connectorRegistry)
                memoryDestinations(
                    navController = navController,
                    store = memoryStore,
                    enabled = memoryEnabled,
                    onEnabledChange = onMemoryEnabledChange,
                    conversationId = conversationSessionId,
                    onShowDisclosure = onMemoryDisclosureShow,
                    onMemoryChanged = onMemoryChanged,
                    reflectionEnabled = reflectionEnabled,
                    reflectionStatus = if (agentState.reflecting && agentState.reflectionStatus != null) {
                        agentState.reflectionStatus.orEmpty()
                    } else if (agentState.reflectionSessionId == conversationSessionId) agentState.reflectionStatus.orEmpty() else reflectionStatus,
                    lastReflectionMillis = if (agentState.reflectionSessionId == conversationSessionId) {
                        maxOf(reflectionLastSuccessMillis, agentState.reflectionLastSuccessMillis)
                    } else reflectionLastSuccessMillis,
                    reflecting = agentState.reflecting,
                    onReflectionEnabledChange = onReflectionEnabledChange,
                    onReflectionDisclosure = onReflectionDisclosureShow,
                )
                tasksDestinations(
                    navController = navController,
                    context = context,
                    onRequestChangeDraft = onGoalChange,
                    onConfigureProvider = { navController.navigate(Routes.PROVIDERS) { launchSingleTop = true } },
                    onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
                    onReturnToSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(Routes.CHAT) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
                composable(Routes.BOTS) {
                    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    var working by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
                    LaunchedEffect(owner) {
                        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            try {
                                while (true) {
                                    working = CoreAgentRuntime.workingBotCounts()
                                    kotlinx.coroutines.delay(500)
                                }
                            } finally { working = emptyMap() }
                        }
                    }
                    com.jarvys.agent.crew.BotsCatalogScreen(
                        conversationId = conversationSessionId,
                        working = working,
                        onClose = { if (!navController.popBackStack()) navController.navigate(Routes.CHAT) { launchSingleTop = true } },
                    )
                }
                crewDestinations(
                    navController = navController,
                    board = crewBoard,
                    readOnly = { snapshot -> selectedHistoryRunId != null
                        || CoreAgentRuntime.crewManagerForSession(conversationSessionId) == null
                        || snapshot == null },
                    missions = { agentState.events.mapNotNull { it.crewMissionSnapshot } },
                    manager = CoreAgentRuntime.crewManagerForSession(conversationSessionId),
                    onStopAll = { com.jarvys.agent.crew.CrewStopActions.stopAll(onStopRun,
                        CoreAgentRuntime.crewManagerForSession(conversationSessionId)) },
                    onResumeBot = { botId ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CoreAgentRuntime.resumeCrewBot(context.applicationContext, conversationSessionId, botId) }
                                AgentForegroundService.ensureCrewKeepalive(context)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { Toast.makeText(context, failure.message ?: context.getString(R.string.crew_resume_failed), Toast.LENGTH_LONG).show() }
                        }
                    },
                    crewMode = { crewMode },
                    onCrewModeChange = onCrewModeChange,
                    initialBoardReference = crewBoardReference,
                    onBoardReferenceConsumed = { crewBoardReference = null },
                    onBoardReference = { _, reference -> crewBoardReference = reference; navController.popBackStack() },
                )
                composable(
                    route = Routes.WORKSPACE_PREVIEW,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                ) { entry ->
                    WorkspacePreviewScreen(entry.arguments?.getString("projectId").orEmpty())
                }
            }
    }

    quickModelProvider?.let { modelProvider ->
        QuickModelDialog(
            providerName = modelProvider.name,
            openAiModel = providersState.openAiModel,
            openAiApiModel = providersState.openAiApiModel,
            openRouterModel = providersState.openRouterModel,
            reasoningVariant = providersState.openAiReasoningVariant,
            customName = providersState.customName,
            customModel = providersState.customModel,
            customModels = providersState.customModels,
            onDismiss = { quickModelProvider = null },
            onSelectionChange = { provider, chosenModel, variant ->
                providersRepository.selectQuickModel(provider, chosenModel, variant)
            },
        )
    }
    if (showSkillImportOptions) {
        SkillImportOptionsSheet(
            onDismiss = { showSkillImportOptions = false },
            onPasteMarkdown = { skillImportEntry = SkillImportEntryKind.MARKDOWN },
            onFromFile = onImportSkill,
            onFromGitHub = { skillImportEntry = SkillImportEntryKind.GITHUB },
        )
    }
    skillImportEntry?.let { kind ->
        SkillImportEntryDialog(
            kind = kind,
            onDismiss = { skillImportEntry = null },
            onImport = { value ->
                if (kind == SkillImportEntryKind.MARKDOWN) onImportSkillMarkdown(value)
                else onImportSkillGitHub(value)
            },
        )
    }
    skillFileLink?.let { link ->
        SkillFileDialog(
            link = link,
            repository = skillRepository,
            onOpenSkillFile = { skillFileLink = it },
            onDismiss = { skillFileLink = null },
        )
    }
    if (showMemoryDisclosure) {
        AlertDialog(
            onDismissRequest = onMemoryDisclosureDismiss,
            title = { Text(stringResource(R.string.memory_disclosure_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.memory_disclosure_body)) } },
            confirmButton = {
                TextButton(onClick = {
                    onMemoryDisclosureOpen()
                    navController.navigate(Routes.MEMORY) { launchSingleTop = true }
                }) { Text(stringResource(R.string.memory_disclosure_open)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onMemoryEnabledChange(false); onMemoryDisclosureDismiss() }) {
                        Text(stringResource(R.string.memory_disclosure_disable))
                    }
                    TextButton(onClick = onMemoryDisclosureDismiss) { Text(stringResource(R.string.memory_disclosure_understood)) }
                }
            },
        )
    }
    if (showReflectionDisclosure && !showMemoryDisclosure && memoryEnabled) {
        AlertDialog(
            onDismissRequest = onReflectionDisclosureDismiss,
            title = { Text(stringResource(R.string.reflection_privacy_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.reflection_privacy_body)) } },
            confirmButton = {
                TextButton(onClick = onReflectionDisclosureDismiss) {
                    Text(stringResource(R.string.reflection_privacy_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = onReflectionDisclosureDisable) {
                    Text(stringResource(R.string.reflection_privacy_disable))
                }
            },
        )
    }
}

@Composable
internal fun QuickModelDialog(
    providerName: String,
    openAiModel: String,
    openAiApiModel: String,
    openRouterModel: String,
    reasoningVariant: String,
    onDismiss: () -> Unit,
    onSelectionChange: (ProviderSettings.Provider, String, String) -> Unit,
    customName: String = "",
    customModel: String = "",
    customModels: List<CustomEndpointModel> = emptyList(),
 ) = ModelSelectorSheet(
    providerName = providerName,
    openAiModel = openAiModel,
    openAiApiModel = openAiApiModel,
    openRouterModel = openRouterModel,
    reasoningVariant = reasoningVariant,
    onDismiss = onDismiss,
    onSelectionChange = onSelectionChange,
    customName = customName,
    customModel = customModel,
    customModels = customModels,
)

@Composable
internal fun AgentRunScreen(
    conversationKey: String,
    generatedImageSessionId: String = conversationKey,
    events: List<AgentRunUiEvent>,
    targetMessageId: String? = null,
    onProactiveSuggestedReply: (String, Int) -> Unit = { _, _ -> },
    isRunning: Boolean,
    emptyReport: String?,
    connectorRegistry: ConnectorRegistry,
    onOpenPreview: (String) -> Unit,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    chatWithoutMemory: Boolean,
    onUndoMemoryRevision: (Long) -> Unit = {},
    onUndoReflectionGroup: (String) -> Unit = {},
    onTranslateAssistant: (AgentRunUiEvent, String) -> Unit = { _, _ -> },
    onCancelTranslation: (String) -> Unit = {},
    onHideTranslation: (String) -> Unit = {},
    onDeleteAssistant: (AgentRunUiEvent) -> Unit = {},
    onRegenerateAssistant: (AgentRunUiEvent) -> Unit = {},
    onSpeakAssistant: (String, String) -> Unit = { _, _ -> },
    speakingMessageId: String? = null,
    activeTranslationMessageId: String? = null,
    translationErrorMessageId: String? = null,
    translationError: String? = null,
    streamActive: Boolean = false,
    allowRegeneration: Boolean = true,
    isCompacting: Boolean = false,
    compactionStatus: String? = null,
    isReflecting: Boolean = false,
    reflectionStatus: String? = null,
    onOpenCrewMission: (String) -> Unit = {},
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    runSnapshot: AgentRunUiSnapshot? = null,
    allowMessageEntrance: Boolean = true,
) {
    ConversationTimeline(
        conversationKey = conversationKey,
        generatedImageSessionId = generatedImageSessionId,
        events = events,
        isRunning = isRunning,
        connectorRegistry = connectorRegistry,
        onOpenPreview = onOpenPreview,
        onOpenSkillFile = onOpenSkillFile,
        chatWithoutMemory = chatWithoutMemory,
        targetMessageId = targetMessageId,
        onProactiveSuggestedReply = onProactiveSuggestedReply,
        onUndoMemoryRevision = onUndoMemoryRevision,
        onUndoReflectionGroup = onUndoReflectionGroup,
        onTranslateAssistant = onTranslateAssistant,
        onCancelTranslation = onCancelTranslation,
        onHideTranslation = onHideTranslation,
        onDeleteAssistant = onDeleteAssistant,
        onRegenerateAssistant = onRegenerateAssistant,
        onSpeakAssistant = onSpeakAssistant,
        speakingMessageId = speakingMessageId,
        activeTranslationMessageId = activeTranslationMessageId,
        translationErrorMessageId = translationErrorMessageId,
        translationError = translationError,
        emptyReport = emptyReport,
        allowRegeneration = allowRegeneration,
        isCompacting = isCompacting,
        compactionStatus = compactionStatus,
        isReflecting = isReflecting,
        reflectionStatus = reflectionStatus,
        onOpenCrewMission = onOpenCrewMission,
        onSaveGeneratedImage = onSaveGeneratedImage,
        onShareGeneratedImage = onShareGeneratedImage,
        runSnapshot = runSnapshot,
        allowMessageEntrance = allowMessageEntrance,
    )
}

internal fun approvalIntent(spec: ApprovalIntentSpec): Intent = when (spec.kind) {
    ApprovalIntentKind.DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse(requireNotNull(spec.dataUri)))
    ApprovalIntentKind.SMS_COMPOSE -> Intent(Intent.ACTION_SENDTO, Uri.parse(requireNotNull(spec.dataUri))).apply {
        spec.extras["sms_body"]?.let { putExtra("sms_body", it) }
    }
    ApprovalIntentKind.EMAIL_COMPOSE -> Intent(Intent.ACTION_SENDTO, Uri.parse(requireNotNull(spec.dataUri))).apply {
        spec.extras["to"]?.takeIf(String::isNotBlank)?.let { putExtra(Intent.EXTRA_EMAIL, it.split(',').toTypedArray()) }
        spec.extras["cc"]?.takeIf(String::isNotBlank)?.let { putExtra(Intent.EXTRA_CC, it.split(',').toTypedArray()) }
        spec.extras["bcc"]?.takeIf(String::isNotBlank)?.let { putExtra(Intent.EXTRA_BCC, it.split(',').toTypedArray()) }
        spec.extras["subject"]?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        spec.extras["body"]?.let { putExtra(Intent.EXTRA_TEXT, it) }
    }
    ApprovalIntentKind.CONTACT_INSERT -> Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
        spec.extras["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        spec.extras["phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        spec.extras["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        spec.extras["company"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        spec.extras["job_title"]?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
    }
}

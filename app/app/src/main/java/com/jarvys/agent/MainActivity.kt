package com.jarvys.agent

import com.jarvys.agent.workspaces.*
import androidx.compose.runtime.saveable.rememberSaveable

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.jarvys.agent.ui.settings.SetupWizardPreferences
import com.jarvys.agent.ui.settings.SetupWizardScreen
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
import com.jarvys.agent.skills.userConfigurableSkillEntries
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

private val workspaceProjectionDispatcher = Dispatchers.IO.limitedParallelism(1)

private object Routes {
    const val CHAT = AppNavigationBackPolicy.CHAT_ROOT
    const val SETTINGS = AppNavigationBackPolicy.SETTINGS
    const val SETTINGS_PREFERENCES = AppNavigationBackPolicy.SETTINGS_PREFERENCES
    const val SETTINGS_DEVELOPER = AppNavigationBackPolicy.SETTINGS_DEVELOPER
    const val SETUP_WIZARD = AppNavigationBackPolicy.SETUP_WIZARD
    const val ARCHIVED_CHATS = AppNavigationBackPolicy.ARCHIVED_CHATS
    const val PROVIDERS = AppNavigationBackPolicy.PROVIDERS
    const val PROVIDERS_OPENAI = AppNavigationBackPolicy.PROVIDERS_OPENAI
    const val PROVIDERS_OPENROUTER = AppNavigationBackPolicy.PROVIDERS_OPENROUTER
    const val PROVIDERS_CUSTOM = AppNavigationBackPolicy.PROVIDERS_CUSTOM
    const val PROVIDERS_SERVICE = AppNavigationBackPolicy.PROVIDERS_SERVICE
    const val MCP = AppNavigationBackPolicy.MCP_LIST
    const val MCP_NEW = AppNavigationBackPolicy.MCP_NEW
    const val SKILLS = AppNavigationBackPolicy.SKILLS
    const val MODULES = AppNavigationBackPolicy.MODULES
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
    const val WORKSPACES = AppNavigationBackPolicy.WORKSPACES
    const val COORDINATED_WORKSPACE = AppNavigationBackPolicy.COORDINATED_WORKSPACE
    const val COORDINATED_FILES = AppNavigationBackPolicy.COORDINATED_FILES
    const val WORKSPACE_PREVIEW = AppNavigationBackPolicy.WORKSPACE_PREVIEW
    const val WORKSPACE_SESSION_PREVIEW = AppNavigationBackPolicy.WORKSPACE_SESSION_PREVIEW
    const val ARTIFACT_PREVIEW = AppNavigationBackPolicy.ARTIFACT_PREVIEW
    const val MODULE_ARTIFACT_VIEWER = AppNavigationBackPolicy.MODULE_ARTIFACT_VIEWER_PREFIX
    fun crew(missionId: String) = CrewNavigationRoutes.mission(missionId)
    fun crewBot(missionId: String, botId: String) = CrewNavigationRoutes.bot(missionId, botId)
    fun connectorDevice(id: String) = AppNavigationBackPolicy.connectorDevice(id)
    fun connectorRemote(id: String, title: String) = AppNavigationBackPolicy.connectorRemote(id, title)
    fun moduleArtifactViewer(sessionId: String, artifactId: String) =
        "$MODULE_ARTIFACT_VIEWER/${Uri.encode(sessionId)}/${Uri.encode(artifactId)}"
    fun connectorGoogle(id: String, title: String) = AppNavigationBackPolicy.connectorGoogle(id, title)
    fun providerService(id: String) = AppNavigationBackPolicy.providerService(id)
    fun taskDetail(id: String) = TasksNavigationRoutes.detail(id)
}

/** Jarvys launcher: provider setup and the existing on-device run entry points. */
class MainActivity : ComponentActivity() {
    /** Single production calculation used by the workspace timeline's thinking/stop state. */
    internal fun workspaceHasActiveWorkForUi(state: WorkspaceChatUiState?): Boolean =
        workspaceHasActiveWorkForUi(state, null)

    internal fun workspaceHasActiveWorkForUi(state: WorkspaceChatUiState?, runSnapshot: AgentRunUiSnapshot?): Boolean =
        workspaceHasActiveWork(state, runSnapshot)

    /** The workspace stop affordance follows the selected assignment's projected pause capability. */
    internal fun workspacePauseEnabledForUi(state: WorkspaceChatUiState?, assignmentId: String?): Boolean =
        workspacePauseEnabled(state, assignmentId)

    internal fun workspacePauseEnabledForUi(state: WorkspaceChatUiState?, assignmentId: String?, activeWork: Boolean): Boolean =
        workspacePauseEnabled(state, assignmentId, activeWork)

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
    private lateinit var reflectionPreferences: MemoryReflectionPreferences
    private lateinit var setupWizardPreferences: SetupWizardPreferences
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
    private var runHistory by mutableStateOf<List<RunHistoryItem>>(emptyList())
    private var selectedHistoryRunId by mutableStateOf<String?>(null)
    private var proactiveTargetMessageId by mutableStateOf<String?>(null)
    private var conversationSessionId by mutableStateOf(java.util.UUID.randomUUID().toString())
    private var conversationTitle by mutableStateOf<String?>(null)
    private var crewModeChoice by mutableStateOf(CrewMode.AUTO)
    private var timelinePageSessionId by mutableStateOf<String?>(null)
    private var timelineLoading by mutableStateOf(false)
    private var timelineOlderLoading by mutableStateOf(false)
    private var timelineOlderLoadError by mutableStateOf(false)
    private val chatDatabaseFailureNoticeShown = java.util.concurrent.atomic.AtomicBoolean(false)
    private var timelineHasOlder by mutableStateOf(false)
    private var timelineOlderCursor by mutableLongStateOf(LocalRunStore.NO_TIMELINE_CURSOR)
    private var timelineRequestSerial = 0L
    private var timelineOlderRequestSerial = 0L
    private var timelineHiddenAssistantMessageIds = emptySet<String>()
    private var timelineHiddenTranslationMessageIds = emptySet<String>()
    private var timelineNewerTranslationMessageIds = emptySet<String>()
    private var timelineInvalidatedToolTurnMessageIds = emptySet<String>()
    private var timelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers()
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
    private val historyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysRunHistory").apply { isDaemon = true }
    }
    private val crewRecoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysCrewRecovery").apply { isDaemon = true }
    }
    private val chatExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysConversationChat").apply { isDaemon = true }
    }
    private val sessionTitleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysSessionTitle").apply { isDaemon = true }
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
        setupWizardPreferences = SetupWizardPreferences(this)
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
        reflectionPreferences = MemoryReflectionPreferences(this)
        val memoryPreferences = getSharedPreferences(MemoryConstants.PREFERENCES_FILE, MODE_PRIVATE)
        memoryEnabled = memoryPreferences.getBoolean(MemoryConstants.ENABLED_KEY, true)
        PersonalMemoryConsolidationWorker.refreshSchedule(this)
        showMemoryDisclosure = memoryEnabled && !memoryPreferences.getBoolean(MemoryConstants.DISCLOSURE_SHOWN_KEY, false)
        refreshReflectionUiState(conversationSessionId)
        showReflectionDisclosure = false // Action policy owns any required reflection review.
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
        goalInput = savedInstanceState?.getString(STATE_GOAL).orEmpty()
        selectedHistoryRunId = savedInstanceState?.getString(STATE_HISTORY_RUN)
        proactiveTargetMessageId = intent?.getStringExtra(EXTRA_OPEN_PROACTIVE_MESSAGE_ID)
            ?: savedInstanceState?.getString(STATE_PROACTIVE_TARGET_MESSAGE)
        pendingTaskId = TaskDeepLink.existingTaskId(this,
            intent?.getStringExtra(EXTRA_OPEN_TASK) ?: savedInstanceState?.getString(STATE_OPEN_TASK))
        conversationSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
            ?: intent?.getStringExtra(EXTRA_OPEN_CHAT_SESSION)
            ?: getSharedPreferences("jarvys_chat", MODE_PRIVATE).getString("active_session_id", null)
            ?: WorkspaceIdentity.CENTRAL_SESSION
        if (WorkspaceIdentity.isExecutionSession(conversationSessionId)) conversationSessionId = WorkspaceIdentity.CENTRAL_SESSION
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
            val agentRenderState by AgentRunUiState.renderState.collectAsState()
            val agentState = agentRenderState.snapshot
            LaunchedEffect(conversationSessionId, agentState.reflecting, agentState.reflectionStatus,
                agentState.reflectionSessionId) {
                if (::reflectionPreferences.isInitialized) refreshReflectionUiState(conversationSessionId)
            }
            val skillEntries by skillRepository.skills.collectAsState()
            val configurableSkillEntries = userConfigurableSkillEntries(skillEntries)
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
                    agentHasUserAttachments = agentRenderState.hasUserAttachments,
                    agentConversationMessageCount = agentRenderState.conversationMessageCount,
                    agentCrewMissions = agentRenderState.crewMissions,
                    workspaceHasActiveWorkForUi = { state, snapshot -> workspaceHasActiveWorkForUi(state, snapshot) },
                    workspacePauseEnabledForUi = ::workspacePauseEnabledForUi,
                    runHistory = runHistory,
                    selectedHistoryRunId = selectedHistoryRunId,
                    proactiveTargetMessageId = proactiveTargetMessageId,
                    timelinePageSessionId = timelinePageSessionId,
                    timelineLoading = timelineLoading,
                    timelineHasOlder = timelineHasOlder,
                    timelineOlderLoading = timelineOlderLoading,
                    timelineOlderLoadError = timelineOlderLoadError,
                    onLoadOlderTimeline = ::loadOlderConversationTimeline,
                    conversationSessionId = conversationSessionId,
                    crewMode = crewModeChoice,
                    onCrewModeChange = ::persistCrewMode,
                    conversationTitle = conversationTitle,
                    memoryEnabled = memoryEnabled,
                    memoryCoreCharacters = memoryCoreCharacters,
                    showMemoryDisclosure = showMemoryDisclosure,
                    reflectionEnabled = reflectionEnabled,
                    reflectionStatus = if (agentState.reflecting) agentState.reflectionStatus.orEmpty() else reflectionStatus,
                    reflectionLastSuccessMillis = reflectionLastSuccessMillis,
                    showReflectionDisclosure = showReflectionDisclosure,
                    chatWithoutMemory = chatWithoutMemory,
                    showingNewChat = showingNewChat,
                    showAgentEvents = showAgentEvents,
                    onTranslateAssistant = ::translateAssistantMessage,
                    onCancelTranslation = ::cancelAssistantTranslation,
                    onHideTranslation = ::hideAssistantTranslation,
                    onDeleteAssistant = ::deleteAssistantMessage,
                    onProactiveSuggestedReply = { messageId, index ->
                        lifecycleScope.launch {
                            val submittedResult = withContext(Dispatchers.IO) {
                                chatDatabaseResult {
                                    com.jarvys.agent.proactive.ProactiveInteractionDispatcher
                                        .submitSuggestedReply(this@MainActivity, messageId, index)
                                }
                            }
                            val submitted = submittedResult.getOrDefault(false)
                            if (!submitted) toast(getString(R.string.proactive_suggested_reply_unavailable))
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
                    captureContextRequested = captureContextRequested,
                    skillEnabledCount = configurableSkillEntries.count { it.enabled && it.validationError == null },
                    skillTotalCount = configurableSkillEntries.size,
                    availableSkills = configurableSkillEntries.filter { it.enabled && it.validationError == null &&
                        com.jarvys.agent.skills.SkillScopePolicy.availableTo(it.metadata.id, null) },
                    selectedSkillIds = selectedSkillIds,
                    onSubmitMessage = ::submitMessage,
                    onInterruptAndSend = ::interruptAndSend,
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
                    onMemoryChanged = {},
                    onUndoMemoryRevision = {},
                    onUndoReflectionGroup = {},
                    themeMode = themeMode,
                    onThemeChange = ::persistThemeMode,
                    onShowAgentEventsChange = ::persistShowAgentEvents,
                    proactiveEnabled = proactiveEnabled,
                    proactiveStatus = proactiveStatus,
                    onProactiveEnabledChange = ::persistProactiveEnabled,
                    onRefreshProactiveStatus = ::refreshProactiveStatus,
                    onSelectHistory = ::selectHistoryRun,
                    onNewChat = ::newChat,
                    onOpenCentral = ::openCentralWorkspaceChat,
                    onWorkspaceControl = ::applyWorkspaceControl,
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
                    setupWizardPreferences = setupWizardPreferences,
                )
                ChatFileTransferHost(fileTransfers) { intent -> startActivity(intent) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        languageChoice = AppLanguageRuntime.synchronizeSystemChoice(this)
        if (::providersRepository.isInitialized) providersRepository.refresh()
        refreshUi()
        WorkspaceResultDelivery.scheduleCatalog(applicationContext)
        AgentForegroundService.recoverPendingConversationInputs(applicationContext)
        if (::connectorRegistry.isInitialized) connectorRegistry.refreshStates()
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
            ?.let { if (WorkspaceIdentity.isExecutionSession(it)) WorkspaceIdentity.CENTRAL_SESSION else it }
            ?: return
        proactiveTargetMessageId = intent.getStringExtra(EXTRA_OPEN_PROACTIVE_MESSAGE_ID)
        if (session != conversationSessionId) {
            conversationSessionId = session
            persistConversationSession(session)
        }
        selectedHistoryRunId = null
        showingNewChat = false
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
        if (::reflectionPreferences.isInitialized) refreshReflectionUiState(conversationSessionId)
        if (Build.VERSION.SDK_INT < 33) recreate()
    }

    private fun interruptAndSend() {
        if (conversationActionPending || attachmentDrafts.drafts.value.isNotEmpty()) return
        val text = goalInput
        if (text.isBlank()) return
        val state = AgentRunUiState.state.value
        if (!state.running || state.reflecting || state.sessionId != conversationSessionId) return
        val skills = ArrayList(skillRepository.enabledForRun().filter { it.metadata.id in selectedSkillIds }.map { it.metadata.id })
        val sessionId = conversationSessionId
        val memoryDisabled = chatWithoutMemory
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentForegroundService.interruptAndSend(this@MainActivity, sessionId, text, skills, memoryDisabled) }
            }
            result.onSuccess { accepted ->
                if (accepted && conversationSessionId == sessionId && goalInput == text) goalInput = ""
            }.onFailure { toast(getString(R.string.chat_interrupt_failed), Toast.LENGTH_LONG) }
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
        if (WorkspaceIdentity.isCentral(sessionId)) conversationTitle = getString(R.string.workspace_central_chat)
        else if (conversationTitle == null) conversationTitle = ConversationTitle.fromFirstMessage(message)
        if (attachmentDrafts.submit(sessionId, message, ArrayList(enabledSkillIds), chatWithoutMemory)) {
            goalInput = ""
            selectedHistoryRunId = null
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

    private fun getChatPreferences() = getSharedPreferences("jarvys_chat", MODE_PRIVATE)

    private fun updateMemoryEnabled(enabled: Boolean) {
        getSharedPreferences(MemoryConstants.PREFERENCES_FILE, MODE_PRIVATE).edit()
            .putBoolean(MemoryConstants.ENABLED_KEY, enabled).apply()
        memoryEnabled = enabled
        PersonalMemoryConsolidationWorker.refreshSchedule(this)
        if (!enabled) PersonalMemoryExtractionWorker.cancelAll(this)
        if (enabled && !getSharedPreferences(MemoryConstants.PREFERENCES_FILE, MODE_PRIVATE)
                .getBoolean(MemoryConstants.DISCLOSURE_SHOWN_KEY, false)) showMemoryDisclosure = true
        if (!enabled) showReflectionDisclosure = false
    }

    private fun dismissMemoryDisclosure() {
        getSharedPreferences(MemoryConstants.PREFERENCES_FILE, MODE_PRIVATE).edit()
            .putBoolean(MemoryConstants.DISCLOSURE_SHOWN_KEY, true).apply()
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
        reflectionEnabled = reflectionPreferences.enabled()
        reflectionStatus = reflectionPreferences.localizedStatus(
            reflectionPreferences.globalStatus().ifBlank { reflectionPreferences.status(sessionId) })
        reflectionLastSuccessMillis = maxOf(reflectionPreferences.globalLastSuccess(), reflectionPreferences.lastSuccess(sessionId))
    }

    private fun updateReflectionEnabled(value: Boolean) {
        reflectionPreferences.setEnabled(value)
        reflectionEnabled = value
        refreshReflectionUiState(conversationSessionId)
    }

    private fun showReflectionDisclosureAgain() { showReflectionDisclosure = true }

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
    }

    private fun toggleMemoryForCurrentChat() {
        val next = !chatWithoutMemory
        getChatPreferences().edit()
            .putBoolean(MemoryUiLogic.sessionMemoryDisabledKey(conversationSessionId), next)
            .apply()
        chatWithoutMemory = next
        if (next) {
            PersonalMemoryExtractionWorker.cancelConversation(this, conversationSessionId)
        }
    }


    private fun startManualCompaction() {
        if (AgentRunUiState.state.value.running || AgentRunUiState.state.value.compacting) return
        val sessionId = conversationSessionId
        lifecycleScope.launch {
            val countResult = withContext(Dispatchers.IO) {
                chatDatabaseResult { localRunStore.conversationMessageCount(sessionId) }
            }
            val messageCount = countResult.getOrNull() ?: return@launch
            if (conversationSessionId != sessionId) return@launch
            if (messageCount < 4) {
                toast(getString(R.string.compaction_manual_minimum))
                return@launch
            }
            AgentForegroundService.startManualConversationCompaction(this@MainActivity, sessionId)
        }
    }

    private fun translateAssistantMessage(event: AgentRunUiEvent, targetLanguage: String) {
        val sessionId = conversationSessionId
        if (!eventBelongsToSession(sessionId, event, "assistant")) return
        val messageId = event.messageId.takeIf(String::isNotBlank) ?: return
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
                chatDatabaseResult {
                    localRunStore.appendAssistantTranslation(sessionId, messageId, targetLanguage, text)
                }.getOrThrow()
                runOnUiThread {
                    if (activeTranslationMessageId == messageId) {
                        activeTranslationMessageId = null
                        translationFuture = null
                        AgentRunUiState.assistantTranslationAdded(sessionId,
                            AgentRunUiEvent.translationEvent(0L, targetLanguage, text, messageId, System.currentTimeMillis()))
                        if (conversationSessionId == sessionId) {
                            timelineNewerTranslationMessageIds += messageId
                        }
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
        if (AgentRunUiState.snapshotForSession(sessionId).events.none {
                it.kind == "assistant_translation" && it.detail == messageId
            }) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                chatDatabaseResult { localRunStore.appendAssistantTranslationHidden(sessionId, messageId) }
            }
            result.onSuccess {
                AgentRunUiState.assistantTranslationHidden(sessionId, messageId)
                if (conversationSessionId == sessionId) timelineHiddenTranslationMessageIds += messageId
            }.onFailure { toast(getString(R.string.chat_footer_translation_error), Toast.LENGTH_LONG) }
        }
    }

    private fun deleteAssistantMessage(event: AgentRunUiEvent) {
        val sessionId = conversationSessionId
        if (!eventBelongsToSession(sessionId, event, "assistant")
            || AgentRunUiState.snapshotForSession(sessionId).running) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                chatDatabaseResult {
                    localRunStore.hideAssistantWithOwner(sessionId, event.messageId, "assistant_deleted")
                        .ownerUserMessageId.orEmpty()
                }
            }
            result.onSuccess { userMessageId ->
                AgentRunUiState.assistantMessageDeleted(sessionId, event.messageId, userMessageId)
                if (conversationSessionId == sessionId) {
                    timelineHiddenAssistantMessageIds += event.messageId
                    timelineHiddenTranslationMessageIds += event.messageId
                    userMessageId?.takeIf(String::isNotBlank)?.let { timelineInvalidatedToolTurnMessageIds += it }
                }
            }.onFailure { toast(getString(R.string.chat_footer_delete_error), Toast.LENGTH_LONG) }
        }
    }

    private fun regenerateAssistantMessage(event: AgentRunUiEvent) {
        val sessionId = conversationSessionId
        if (!eventBelongsToSession(sessionId, event, "assistant")) return
        val sessionState = AgentRunUiState.snapshotForSession(sessionId)
        if (sessionState.running || sessionState.compacting) return
        lifecycleScope.launch {
            val prompt = withContext(Dispatchers.IO) {
                chatDatabaseResult {
                    localRunStore.regenerateAssistantIfLatest(sessionId, event.messageId)
                }
            }.getOrElse {
                toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG)
                return@launch
            } ?: run {
                toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG)
                return@launch
            }
            if (conversationSessionId != sessionId) return@launch
            cancelAssistantTranslation(event.messageId)
            translationErrorMessageId = null
            translationError = null
            selectedHistoryRunId = null
            showingNewChat = false
            refreshConversationTimeline(sessionId)
            val skillIds = skillRepository.enabledForRun().filter { it.metadata.id in selectedSkillIds }.map { it.metadata.id }
            runCatching {
                AgentForegroundService.startRegenerateAssistant(this@MainActivity, prompt.text, ArrayList(skillIds),
                    sessionId, prompt.userMessageId, chatWithoutMemory)
            }.onFailure {
                toast(getString(R.string.chat_footer_regenerate_error), Toast.LENGTH_LONG)
            }
        }
    }

    private fun refreshConversationTimeline(sessionId: String) {
        if (conversationSessionId != sessionId) return
        val requestSerial = ++timelineRequestSerial
        timelineOlderRequestSerial++
        timelinePageSessionId = sessionId
        timelineLoading = true
        timelineOlderLoading = false
        timelineOlderLoadError = false
        historyExecutor.execute {
            val pageResult = chatDatabaseResult {
                localRunStore.readConversationTimelinePage(
                    sessionId, LocalRunStore.NO_TIMELINE_CURSOR, LocalRunStore.INITIAL_TIMELINE_TURNS)
            }
            val page = pageResult.getOrNull()
            runOnUiThread {
                if (requestSerial != timelineRequestSerial || conversationSessionId != sessionId
                    || timelinePageSessionId != sessionId) return@runOnUiThread
                if (page != null) {
                    AgentRunUiState.replacePersistedTimelineWindow(sessionId, page.events)
                    timelineHiddenAssistantMessageIds = page.hiddenAssistantMessageIds
                    timelineHiddenTranslationMessageIds = page.hiddenTranslationMessageIds
                    timelineNewerTranslationMessageIds = page.translationMessageIds
                    timelineInvalidatedToolTurnMessageIds = page.invalidatedToolTurnMessageIds
                    timelineProjectionMarkers = page.projectionMarkers
                    timelineOlderCursor = page.olderCursor
                    timelineHasOlder = page.hasOlder
                } else {
                    AgentRunUiState.restoreSession(sessionId, emptyList())
                    timelineHiddenAssistantMessageIds = emptySet()
                    timelineHiddenTranslationMessageIds = emptySet()
                    timelineNewerTranslationMessageIds = emptySet()
                    timelineInvalidatedToolTurnMessageIds = emptySet()
                    timelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers()
                    timelineOlderCursor = LocalRunStore.NO_TIMELINE_CURSOR
                    timelineHasOlder = false
                }
                timelineLoading = false
            }
        }
    }

    private fun eventBelongsToSession(sessionId: String, event: AgentRunUiEvent, kind: String): Boolean =
        event.kind == kind && event.messageId.isNotBlank()
            && AgentRunUiState.containsEvent(sessionId, event)

    private fun <T> chatDatabaseResult(block: () -> T): Result<T> =
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RuntimeException) {
            reportChatDatabaseFailure(failure)
            Result.failure(failure)
        }

    private fun reportChatDatabaseFailure(error: Throwable) {
        AgentErrorReporter.reportChatDatabaseFailure(applicationContext, error)
        if (!chatDatabaseFailureNoticeShown.compareAndSet(false, true)) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed)
                toast(getString(R.string.chat_database_unavailable), Toast.LENGTH_LONG)
        }
    }

    private fun shareGeneratedImage(sessionId: String, event: AgentRunUiEvent) {
        ChatFileRequest.generated(sessionId, event)?.let(fileTransfers::share)
    }

    private fun saveGeneratedImage(sessionId: String, event: AgentRunUiEvent) {
        ChatFileRequest.generated(sessionId, event)?.let(fileTransfers::download)
    }

    private fun startManualReflection() {
        if (!memoryEnabled || chatWithoutMemory) {
            toast(getString(R.string.reflection_unavailable))
            return
        }
        if (AgentRunUiState.state.value.running || AgentRunUiState.state.value.compacting) return
        PersonalMemoryExtractionWorker.enqueue(this, conversationSessionId, snapshot = false, manual = true)
        toast(getString(R.string.reflection_status_running))
    }

    private fun stopCurrentWork() {
        attachmentDrafts.cancelSubmission(conversationSessionId)
        val state = AgentRunUiState.snapshotForSession(conversationSessionId)
        if (state.running || state.compacting) {
            AgentForegroundService.stopRun(conversationSessionId)
        }
    }

    private fun persistCrewMode(mode: CrewMode) {
        crewModeChoice = mode
        CrewMode.write(this, mode)
    }

    private fun openCentralWorkspaceChat() {
        if (!WorkspaceIdentity.isCentral(conversationSessionId)) goalInput = ""
        persistConversationSession(WorkspaceIdentity.CENTRAL_SESSION)
        conversationTitle = getString(R.string.workspace_central_chat)
        selectedHistoryRunId = null
        showingNewChat = false
        selectedSkillIds = emptySet()
        restoreConversationSession(conversationSessionId)
    }

    private fun applyWorkspaceControl(workspaceId: String, assignmentId: String, revision: Long,
                                      type: WorkspaceControlType, text: String) {
        val owner = java.lang.ref.WeakReference(this)
        WorkspaceCoordinator(applicationContext).enqueueNativeControl(workspaceId, assignmentId, revision, type, text,
            completion = { result ->
                if (result.isFailure) owner.get()?.let { activity -> activity.runOnUiThread {
                    if (!activity.isDestroyed)
                        activity.toast(activity.getString(R.string.workspace_control_failed), Toast.LENGTH_LONG)
                } }
            })
    }

    private fun newChat() {
        persistConversationSession(java.util.UUID.randomUUID().toString())
        conversationTitle = null
        AgentRunUiState.resetSession(conversationSessionId)
        selectedHistoryRunId = null
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
            timelineRequestSerial++
            timelineOlderRequestSerial++
            timelinePageSessionId = conversationSessionId
            timelineHiddenAssistantMessageIds = emptySet()
            timelineHiddenTranslationMessageIds = emptySet()
            timelineNewerTranslationMessageIds = emptySet()
            timelineInvalidatedToolTurnMessageIds = emptySet()
            timelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers()
            timelineLoading = false
            timelineOlderLoading = false
            timelineOlderLoadError = false
            timelineHasOlder = false
            timelineOlderCursor = LocalRunStore.NO_TIMELINE_CURSOR
            val sessionId = conversationSessionId
            historyExecutor.execute {
                val title = chatDatabaseResult { localRunStore.readConversationTitle(sessionId) }.getOrNull()
                runOnUiThread { if (conversationSessionId == sessionId) conversationTitle = title }
            }
            return
        }
        val historyItem = runHistory.firstOrNull { it.id == runId }
        conversationTitle = historyItem?.title
        val sessionId = historyItem?.sessionId ?: runId
        if (WorkspaceIdentity.isExecutionSession(sessionId)) { openCentralWorkspaceChat(); return }
        persistConversationSession(sessionId)
        val requestSerial = ++timelineRequestSerial
        timelineOlderRequestSerial++
        timelinePageSessionId = sessionId
        timelineHiddenAssistantMessageIds = emptySet()
        timelineHiddenTranslationMessageIds = emptySet()
        timelineNewerTranslationMessageIds = emptySet()
        timelineInvalidatedToolTurnMessageIds = emptySet()
        timelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers()
        timelineLoading = true
        timelineOlderLoading = false
        timelineOlderLoadError = false
        timelineHasOlder = false
        timelineOlderCursor = LocalRunStore.NO_TIMELINE_CURSOR
        historyExecutor.execute {
            val page = chatDatabaseResult {
                localRunStore.readConversationTimelinePage(
                    sessionId, LocalRunStore.NO_TIMELINE_CURSOR, LocalRunStore.INITIAL_TIMELINE_TURNS)
            }.getOrNull()
            val conversationEvents = page?.events.orEmpty()
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
                if (requestSerial == timelineRequestSerial && selectedHistoryRunId == runId && conversationSessionId == sessionId) {
                    val cachedEvents = AgentRunUiState.snapshotForSession(sessionId).events
                    if (conversationEvents.isNotEmpty() && cachedEvents.isNotEmpty()) {
                        AgentRunUiState.refreshPersistedSession(sessionId, conversationEvents)
                    } else {
                        AgentRunUiState.restoreSession(sessionId, events)
                    }
                    timelineHiddenAssistantMessageIds = page?.hiddenAssistantMessageIds.orEmpty()
                    timelineHiddenTranslationMessageIds = page?.hiddenTranslationMessageIds.orEmpty()
                    timelineNewerTranslationMessageIds = page?.translationMessageIds.orEmpty()
                    timelineInvalidatedToolTurnMessageIds = page?.invalidatedToolTurnMessageIds.orEmpty()
                    timelineProjectionMarkers = page?.projectionMarkers ?: LocalRunStore.TimelineProjectionMarkers()
                    timelineOlderCursor = page?.olderCursor ?: LocalRunStore.NO_TIMELINE_CURSOR
                    timelineHasOlder = page?.hasOlder == true
                    timelineLoading = false
                }
            }
            recoverCrewMissionsAsync(sessionId)
        }
    }

    private fun restoreConversationSession(sessionId: String) {
        val requestSerial = ++timelineRequestSerial
        timelineOlderRequestSerial++
        timelinePageSessionId = sessionId
        timelineHiddenAssistantMessageIds = emptySet()
        timelineHiddenTranslationMessageIds = emptySet()
        timelineNewerTranslationMessageIds = emptySet()
        timelineInvalidatedToolTurnMessageIds = emptySet()
        timelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers()
        timelineLoading = true
        timelineOlderLoading = false
        timelineOlderLoadError = false
        timelineHasOlder = false
        timelineOlderCursor = LocalRunStore.NO_TIMELINE_CURSOR
        historyExecutor.execute {
            val page = chatDatabaseResult {
                localRunStore.readConversationTimelinePage(
                    sessionId, LocalRunStore.NO_TIMELINE_CURSOR, LocalRunStore.INITIAL_TIMELINE_TURNS)
            }.getOrNull()
            runOnUiThread {
                if (requestSerial == timelineRequestSerial && conversationSessionId == sessionId && selectedHistoryRunId == null) {
                    page?.let {
                        val currentEvents = AgentRunUiState.snapshotForSession(sessionId).events
                        if (currentEvents.isEmpty()) AgentRunUiState.restoreSession(sessionId, it.events)
                        else {
                            AgentRunUiState.showSession(sessionId)
                            AgentRunUiState.refreshPersistedSession(sessionId, it.events)
                        }
                        timelineHiddenAssistantMessageIds = it.hiddenAssistantMessageIds
                        timelineHiddenTranslationMessageIds = it.hiddenTranslationMessageIds
                        timelineNewerTranslationMessageIds = it.translationMessageIds
                        timelineInvalidatedToolTurnMessageIds = it.invalidatedToolTurnMessageIds
                        timelineProjectionMarkers = it.projectionMarkers
                        timelineOlderCursor = it.olderCursor
                        timelineHasOlder = it.hasOlder
                    } ?: run {
                        AgentRunUiState.restoreSession(sessionId, emptyList())
                        conversationTitle = null
                        timelineHasOlder = false
                        timelineOlderCursor = LocalRunStore.NO_TIMELINE_CURSOR
                    }
                    timelineLoading = false
                }
            }
            recoverCrewMissionsAsync(sessionId)
            sessionTitleExecutor.execute {
                val title = if (WorkspaceIdentity.isCentral(sessionId)) getString(R.string.workspace_central_chat)
                else if (sessionId == ProactiveConversation.SESSION_ID) ProactiveConversation.title(this)
                else chatDatabaseResult {
                    localRunStore.readConversationTitle(sessionId)
                        ?: ConversationTitle.fromFirstMessage(localRunStore.readFirstUserMessage(sessionId))
                }.getOrNull()
                runOnUiThread {
                    if (conversationSessionId == sessionId && selectedHistoryRunId == null && title != null) {
                        conversationTitle = title
                    }
                }
            }
        }
    }

    private fun loadOlderConversationTimeline() {
        val sessionId = timelinePageSessionId ?: return
        if (timelineLoading || !timelineHasOlder || timelineOlderLoading
            || timelineOlderCursor == LocalRunStore.NO_TIMELINE_CURSOR) return
        val cursor = timelineOlderCursor
        val selectedRunId = selectedHistoryRunId
        val newerInvalidations = timelineInvalidatedToolTurnMessageIds
        val newerProjectionMarkers = timelineProjectionMarkers
        val requestSerial = ++timelineOlderRequestSerial
        timelineOlderLoading = true
        timelineOlderLoadError = false
        historyExecutor.execute {
            val page = chatDatabaseResult {
                localRunStore.readConversationTimelinePage(
                    sessionId, cursor, LocalRunStore.OLDER_TIMELINE_TURNS, newerInvalidations, newerProjectionMarkers)
            }.getOrNull()
            runOnUiThread {
                if (requestSerial != timelineOlderRequestSerial || conversationSessionId != sessionId
                    || timelinePageSessionId != sessionId || selectedHistoryRunId != selectedRunId) return@runOnUiThread
                if (page != null) {
                    AgentRunUiState.prependPersistedSession(
                        sessionId,
                        page.events,
                        timelineHiddenAssistantMessageIds,
                        timelineHiddenTranslationMessageIds,
                        timelineNewerTranslationMessageIds,
                    )
                    timelineHiddenAssistantMessageIds += page.hiddenAssistantMessageIds
                    timelineHiddenTranslationMessageIds += page.hiddenTranslationMessageIds
                    timelineNewerTranslationMessageIds += page.translationMessageIds
                    timelineInvalidatedToolTurnMessageIds += page.invalidatedToolTurnMessageIds
                    timelineProjectionMarkers = page.projectionMarkers
                    timelineOlderCursor = page.olderCursor
                    timelineHasOlder = page.hasOlder
                } else {
                    timelineOlderLoadError = true
                }
                timelineOlderLoading = false
            }
        }
    }

    private fun recoverCrewMissionsAsync(sessionId: String) {
        crewRecoveryExecutor.execute {
            val recovered = chatDatabaseResult { localRunStore.recoverCrewMissions(sessionId) }.getOrDefault(emptyList())
            if (recovered.isNotEmpty()) runOnUiThread {
                if (conversationSessionId == sessionId) recovered.forEach { AgentRunUiState.crewMissionChanged(sessionId, it) }
            }
        }
    }

    private fun requestConversationTitle(sessionId: String, submittedMessage: String) {
        if (WorkspaceIdentity.isCentral(sessionId) || WorkspaceIdentity.isExecutionSession(sessionId)) return
        sessionTitleExecutor.execute {
            if (!chatDatabaseResult { localRunStore.claimConversationTitleGeneration(sessionId) }.getOrDefault(false))
                return@execute
            try {
                val firstUserMessage = chatDatabaseResult { localRunStore.readFirstUserMessage(sessionId) }
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
                if (chatDatabaseResult { localRunStore.appendConversationTitleIfAbsent(sessionId, title) }.getOrDefault(false)) {
                    runOnUiThread {
                        if (conversationSessionId == sessionId) conversationTitle = title
                        refreshRunHistory()
                    }
                }
            } catch (_: RuntimeException) {
                val fallback = ConversationTitle.fromFirstMessage(submittedMessage) ?: "Jarvys"
                if (chatDatabaseResult { localRunStore.appendConversationTitleIfAbsent(sessionId, fallback) }.getOrDefault(false)) {
                    runOnUiThread {
                        if (conversationSessionId == sessionId) conversationTitle = fallback
                        refreshRunHistory()
                    }
                }
            } finally {
                chatDatabaseResult { localRunStore.releaseConversationTitleGeneration(sessionId) }
            }
        }
    }

    private fun persistConversationSession(sessionId: String) {
        conversationSessionId = sessionId
        AgentRunUiState.showSession(sessionId)
        if (::attachmentDrafts.isInitialized) attachmentDrafts.switchSession(sessionId)
        getSharedPreferences("jarvys_chat", MODE_PRIVATE).edit()
            .putString("active_session_id", sessionId)
            .apply()
        chatWithoutMemory = getChatPreferences().getBoolean(MemoryUiLogic.sessionMemoryDisabledKey(sessionId), false)
        refreshReflectionUiState(sessionId)
    }

    private fun applyConversationAction(sessionId: String, action: ConversationAction, title: String?) {
        if (conversationActionPending) return
        if (conversationActionForbiddenForWorkspace(action, sessionId)) {
            toast(getString(R.string.workspace_reserved_conversation)); return
        }
        if (action == ConversationAction.DELETE && isManagedSystemConversation(sessionId)) {
            toast(getString(R.string.drawer_system_chat_delete))
            return
        }
        fun blocked() = conversationRemovalBlocked(AgentRunUiState.snapshotForSession(sessionId),
            attachmentDrafts.isSending(sessionId), activeTranslationMessageId != null,
            !AgentRunUiState.isRunning(sessionId) && !AgentForegroundService.hasActiveRun(sessionId),
            CoreAgentRuntime.hasActiveCrewBots(sessionId))
        val removal = action == ConversationAction.DELETE || action == ConversationAction.ARCHIVE
        if (removal && blocked()) { toast(getString(R.string.drawer_busy)); return }
        conversationActionPending = true
        historyExecutor.execute {
            val result = runCatching {
                check(!removal || !blocked()) { "Active work must finish first" }
                when (action) {
                    ConversationAction.PIN -> chatDatabaseResult { localRunStore.setConversationPinned(sessionId, true); true }.getOrThrow()
                    ConversationAction.UNPIN -> chatDatabaseResult { localRunStore.setConversationPinned(sessionId, false); true }.getOrThrow()
                    ConversationAction.ARCHIVE -> {
                        PersonalMemoryExtractionWorker.enqueue(this@MainActivity, sessionId, snapshot = true, manual = true)
                        chatDatabaseResult { localRunStore.setConversationArchived(sessionId, true); true }.getOrThrow()
                    }
                    ConversationAction.RESTORE -> chatDatabaseResult { localRunStore.setConversationArchived(sessionId, false); true }.getOrThrow()
                    ConversationAction.RENAME -> chatDatabaseResult { localRunStore.renameConversation(sessionId, title.orEmpty()); true }.getOrThrow()
                    ConversationAction.DELETE -> {
                        val cleaned = chatDatabaseResult { localRunStore.deleteConversation(sessionId) }.getOrThrow()
                        check(cleaned || chatDatabaseResult { localRunStore.readConversationMetadata(sessionId).deleted }.getOrThrow()) {
                            "Chat was not deleted"
                        }
                        cleaned
                    }
                }
            }.recoverCatching { failure ->
                if (action == ConversationAction.DELETE
                    && chatDatabaseResult { localRunStore.readConversationMetadata(sessionId).deleted }.getOrDefault(false)) false
                else throw failure
            }
            val updatedTitle = if (result.isSuccess && action == ConversationAction.RENAME)
                chatDatabaseResult { localRunStore.readConversationTitle(sessionId) }.getOrNull() else null
            runOnUiThread {
                conversationActionPending = false
                result.onSuccess { cleaned ->
                    if (conversationActionLeavesCurrent(action, sessionId, conversationSessionId)) {
                        if (conversationActionNavigatesToAgent(action, sessionId, conversationSessionId)) {
                            openCentralWorkspaceChat()
                        } else {
                            newChat()
                        }
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
            val conversations = chatDatabaseResult { localRunStore.listConversations() }.getOrElse { return@execute }
            val items = conversations.asSequence()
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
    agentHasUserAttachments: Boolean,
    agentConversationMessageCount: Int,
    agentCrewMissions: List<com.jarvys.agent.crew.CrewMissionSnapshot>,
    workspaceHasActiveWorkForUi: (WorkspaceChatUiState?, AgentRunUiSnapshot?) -> Boolean,
    workspacePauseEnabledForUi: (WorkspaceChatUiState?, String?, Boolean) -> Boolean,
    runHistory: List<RunHistoryItem>,
    selectedHistoryRunId: String?,
    proactiveTargetMessageId: String?,
    timelinePageSessionId: String?,
    timelineLoading: Boolean,
    timelineHasOlder: Boolean,
    timelineOlderLoading: Boolean,
    timelineOlderLoadError: Boolean,
    onLoadOlderTimeline: () -> Unit,
    conversationSessionId: String,
    crewMode: CrewMode,
    onCrewModeChange: (CrewMode) -> Unit,
    conversationTitle: String?,
    memoryEnabled: Boolean,
    memoryCoreCharacters: Int,
    showMemoryDisclosure: Boolean,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    reflectionLastSuccessMillis: Long,
    showReflectionDisclosure: Boolean,
    chatWithoutMemory: Boolean,
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
    captureContextRequested: Boolean,
    skillEnabledCount: Int,
    skillTotalCount: Int,
    availableSkills: List<SkillEntry>,
    selectedSkillIds: Set<String>,
    onSubmitMessage: () -> Unit,
    onInterruptAndSend: () -> Unit = {},
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
    onSelectHistory: (String?) -> Unit,
    onNewChat: () -> Unit,
    onOpenCentral: () -> Unit,
    onWorkspaceControl: (String, String, Long, WorkspaceControlType, String) -> Unit,
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
    setupWizardPreferences: SetupWizardPreferences,
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
    DisposableEffect(googleAuthorizationResolution, context) {
        val owner = (context as? ComponentActivity)?.let { googleAuthorizationResolution.attachRegistry(it.activityResultRegistry) }
        onDispose { owner?.let(googleAuthorizationResolution::detach) }
    }
    val navController = rememberNavController()
    SideEffect { onNavControllerReady(navController) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var drawerNavigationPending by remember { mutableStateOf(false) }
    fun navigateFromDrawer(destination: () -> Unit) {
        if (drawerNavigationPending) return
        drawerNavigationPending = true
        scope.launch {
            try {
                // Finish the modal transition before changing the underlying route. A rapid
                // second pointer-down must not cancel closing and leave the new route covered.
                drawerState.close()
                destination()
            } finally {
                drawerNavigationPending = false
            }
        }
    }
    val workspaceRevision by WorkspaceChanges.revision.collectAsState()
    val workspaceCatalogRevision by WorkspaceChanges.catalogRevision.collectAsState()
    var workspaceCatalog by remember { mutableStateOf(WorkspaceCatalogUiState()) }
    var workspaceAfter by rememberSaveable { mutableStateOf<String?>(null) }
    var workspacePageHistory by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var workspaceReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(workspaceCatalogRevision, workspaceAfter, workspaceReload) {
        workspaceCatalog = workspaceCatalog.beginRefresh()
        try {
            val page = withContext(workspaceProjectionDispatcher) {
                WorkspaceProjection.sidebarPage(context.applicationContext, workspaceAfter)
            }
            workspaceCatalog = workspaceCatalog.loaded(page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AgentErrorReporter.reportChatDatabaseFailure(context.applicationContext, failure)
            workspaceCatalog = workspaceCatalog.failed(context.getString(R.string.workspace_catalog_load_error))
        }
    }
    var correctionWorkspace by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionAssignment by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionRevision by rememberSaveable { mutableStateOf(0L) }
    var correctionText by rememberSaveable { mutableStateOf("") }
    if (correctionWorkspace != null && correctionAssignment != null) AlertDialog(
        onDismissRequest = { correctionWorkspace = null; correctionAssignment = null; correctionText = "" },
        title = { Text(stringResource(R.string.workspace_correct)) },
        text = { ScrollableDialogContent {
            Text(stringResource(R.string.workspace_correction_target, correctionWorkspace!!, correctionAssignment!!))
            JarvysTextField(value = correctionText, onValueChange = { correctionText = it },
                singleLine = false,
                label = { Text(stringResource(R.string.workspace_correction_text)) })
        } },
        confirmButton = { TextButton(enabled = correctionText.isNotBlank() && !conversationActionPending, onClick = {
            onWorkspaceControl(correctionWorkspace!!, correctionAssignment!!, correctionRevision, WorkspaceControlType.CORRECT, correctionText)
            correctionWorkspace = null; correctionAssignment = null; correctionText = ""
        }) { Text(stringResource(R.string.workspace_send_correction)) } },
        dismissButton = { TextButton(onClick = { correctionWorkspace = null; correctionAssignment = null; correctionText = "" }) { Text(stringResource(android.R.string.cancel)) } },
    )
    val navEntry by navController.currentBackStackEntryAsState()
    val route = navEntry?.destination?.route ?: Routes.CHAT
    val routeWorkspaceId = navEntry?.arguments?.getString("workspaceId")
    val workspaceChatState = remember(routeWorkspaceId) {
        mutableStateOf(routeWorkspaceId?.let { id ->
            runCatching {
                WorkspaceChatUiState(id, WorkspaceIdentity.executionSessionId(id), "", WorkspaceUiStatus.UNKNOWN,
                    loading = true)
            }.getOrNull()
        })
    }
    var workspaceComposerText by rememberSaveable(routeWorkspaceId) { mutableStateOf("") }
    var workspaceSelectedAssignmentId by rememberSaveable(routeWorkspaceId) { mutableStateOf<String?>(null) }
    val workspaceAssignments = workspaceChatState.value?.assignments.orEmpty()
    val workspaceSelectedAssignment = workspaceChatSelectedAssignment(workspaceAssignments, workspaceSelectedAssignmentId)
    val workspaceLatestMission = workspaceChatState.value?.events?.lastOrNull { it.kind == "crew_mission" }
        ?.crewMissionSnapshot
    val workspaceHasActiveWork = workspaceHasActiveWorkForUi(workspaceChatState.value, agentState)
    var workspaceChatReload by remember { mutableIntStateOf(0) }
    var workspaceOlderHistoryLoading by remember(routeWorkspaceId) { mutableStateOf(false) }
    var workspaceOlderHistoryLoadError by remember(routeWorkspaceId) { mutableStateOf(false) }
    var workspaceOlderHistoryRequestSerial by remember(routeWorkspaceId) { mutableLongStateOf(0L) }
    val workspaceChatActive = route == Routes.COORDINATED_WORKSPACE
    val crewRoute = route == Routes.CREW_EMPTY || route.startsWith("crew/")
    var workspaceCrewSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route, workspaceChatActive, crewRoute) {
        if (workspaceChatActive || !crewRoute) workspaceCrewSessionId = null
    }
    val activeWorkspaceCrewSessionId = workspaceCrewSessionId.takeIf { crewRoute }
    LaunchedEffect(route, routeWorkspaceId, conversationSessionId, workspaceRevision, workspaceChatReload) {
        if (workspaceChatActive && routeWorkspaceId != null) {
            val executionSessionId = runCatching { WorkspaceIdentity.executionSessionId(routeWorkspaceId) }.getOrNull()
            if (executionSessionId == null) {
                workspaceChatState.value = null
                return@LaunchedEffect
            }
            val previous = workspaceChatState.value
            workspaceOlderHistoryRequestSerial++
            workspaceOlderHistoryLoading = false
            workspaceOlderHistoryLoadError = false
            workspaceChatState.value = WorkspaceChatUiState(routeWorkspaceId, executionSessionId,
                previous?.title.orEmpty(), previous?.status ?: WorkspaceUiStatus.UNKNOWN,
                previous?.events.orEmpty(), loading = true, assignments = previous?.assignments.orEmpty())
            AgentRunUiState.showSession(executionSessionId)
            try {
                val loaded = withContext(workspaceProjectionDispatcher) {
                    WorkspaceProjection.chat(context.applicationContext, routeWorkspaceId)
                }
                workspaceChatState.value = loaded
                AgentRunUiState.restoreSession(executionSessionId, loaded.events)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AgentErrorReporter.reportChatDatabaseFailure(context.applicationContext, failure)
                workspaceChatState.value = WorkspaceChatUiState(routeWorkspaceId, executionSessionId,
                    previous?.title.orEmpty(), previous?.status ?: WorkspaceUiStatus.UNKNOWN,
                    previous?.events.orEmpty(), error = context.getString(R.string.workspace_chat_load_error),
                    assignments = previous?.assignments.orEmpty())
                AgentRunUiState.restoreSession(executionSessionId, previous?.events.orEmpty())
            }
        } else {
            AgentRunUiState.showSession(activeWorkspaceCrewSessionId ?: conversationSessionId)
        }
    }
    val routeTaskId = navEntry?.arguments?.getString("taskId")
    val taskDataRevision by com.jarvys.agent.tasks.TaskDataChanges.revision.collectAsState()
    val routeTaskTitle = remember(routeTaskId, taskDataRevision) {
        routeTaskId?.let { id -> runCatching { TaskStore(context.applicationContext).get(id)?.name }.getOrNull() }
    }
    SideEffect {
        UserDecisionUiAvailability.setChatVisible(MainActivity.isAppForeground()
            && (route == Routes.CHAT || workspaceChatActive))
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
    val routeCrewMission = routeMissionId?.let { missionId ->
        agentCrewMissions.lastOrNull { it.missionId == missionId }
    }
    val routeBotName = routeCrewMission?.bots?.firstOrNull { it.id == routeBotId }?.name
    val crewSessionId = activeWorkspaceCrewSessionId ?: conversationSessionId
    LaunchedEffect(crewSessionId) {
        withContext(Dispatchers.IO) {
            runCatching { CoreAgentRuntime.prepareCrewHistory(context.applicationContext, crewSessionId) }
        }
    }
    val crewBoard = remember(crewSessionId) {
        CrewBoard(WorkspaceStore.forCrewBoard(context.applicationContext, crewSessionId))
    }
    var quickModelProvider by remember { mutableStateOf<ProviderSettings.Provider?>(null) }
    var showSkillImportOptions by remember { mutableStateOf(false) }
    var skillImportEntry by remember { mutableStateOf<SkillImportEntryKind?>(null) }
    var skillFileLink by remember { mutableStateOf<SkillFileLink?>(null) }
    var crewBoardReference by remember { mutableStateOf<String?>(null) }
    val showActiveConversation = (agentState.goal.isNotBlank() || agentHasUserAttachments)
        && selectedHistoryRunId == null && !showingNewChat
    LaunchedEffect(agentState.runId, agentState.running) {
        if (!agentState.running && agentState.runId != null) onHistoryUpdated()
    }

    val chatTitle = when {
            workspaceChatActive -> workspaceChatState.value?.title?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.workspace_chat_loading)
            route == Routes.CHAT && selectedHistoryRunId != null -> runHistory.firstOrNull { it.id == selectedHistoryRunId }
                ?.let { it.title ?: ConversationTitle.fromFirstMessage(it.goal) ?: it.goal }
                ?: stringResource(R.string.chat_previous_run)
            route == Routes.CHAT && showingNewChat -> conversationTitle ?: stringResource(R.string.drawer_new_chat)
            else -> conversationTitle ?: "Jarvys"
        }
    val routeMeta = AppNavigationBackPolicy.metadata(context, route, chatTitle, routeBotName,
        routeDetailTitle).let { meta ->
        if (workspaceChatActive) meta.copy(title = chatTitle, action = AppRouteAction.CHAT) else meta
    }

    val mascotLiveConversationVisible = workspaceChatActive || route == Routes.CHAT
        && selectedHistoryRunId == null && !showingNewChat
    val visibleConversationSessionId = if (workspaceChatActive) workspaceChatState.value?.executionSessionId
        ?: routeWorkspaceId?.let { runCatching { WorkspaceIdentity.executionSessionId(it) }.getOrNull() }
        else activeWorkspaceCrewSessionId ?: conversationSessionId
    val mainMascotMode = mainJarvysMascotMode(agentState, visibleConversationSessionId, mascotLiveConversationVisible)
    JarvysShellFrame(
        drawerState = drawerState,
        mainMascotMode = mainMascotMode,
        centralMascotCentered = route == Routes.CHAT && WorkspaceIdentity.isCentral(conversationSessionId),
        mascotVisibleSessionId = visibleConversationSessionId,
        mascotLiveConversationVisible = mascotLiveConversationVisible,
        conversationActionTitle = chatTitle,
        canRenameConversation = route == Routes.CHAT && !isReservedWorkspaceConversation(conversationSessionId),
        canDeleteConversation = route == Routes.CHAT && !isReservedWorkspaceConversation(conversationSessionId) &&
            !isManagedSystemConversation(conversationSessionId),
        conversationActionsEnabled = !conversationActionPending && !drawerNavigationPending,
        workspaceMenuActionTitle = if (workspaceChatActive) stringResource(R.string.workspace_open_files) else null,
        onWorkspaceMenuAction = {
            routeWorkspaceId?.let { id -> navController.navigate("workspace/${Uri.encode(id)}/files") { launchSingleTop = true } }
        },
        workspaceBotsActionTitle = if (workspaceChatActive && workspaceLatestMission != null)
            stringResource(R.string.workspace_view_bots) else null,
        onWorkspaceBotsAction = {
            workspaceLatestMission?.let { mission ->
                workspaceChatState.value?.let { state ->
                    workspaceCrewSessionId = state.executionSessionId
                    navController.navigate(Routes.crew(mission.missionId)) {
                        launchSingleTop = true
                    }
                }
            }
        },
        onConversationAction = onConversationAction,
        drawerGesturesEnabled = !drawerNavigationPending && route != Routes.WORKSPACE_PREVIEW
            && route != Routes.WORKSPACE_SESSION_PREVIEW && route != Routes.ARTIFACT_PREVIEW
            && route != Routes.MODULE_ARTIFACT_VIEWER,
        drawerContent = {
            ConversationDrawer(
                history = runHistory,
                centralSelected = route == Routes.CHAT && WorkspaceIdentity.isCentral(conversationSessionId),
                onOpenCentral = { navigateFromDrawer { onOpenCentral(); navController.navigate(Routes.CHAT) { launchSingleTop = true } } },
                onOpenWorkspaces = { navigateFromDrawer { navController.navigate(Routes.WORKSPACES) { launchSingleTop = true } } },
                workspacesSelected = route == Routes.WORKSPACES || route == Routes.COORDINATED_WORKSPACE || route == Routes.COORDINATED_FILES,
                isDrawerOpen = drawerState.isOpen,
                onCloseDrawer = { if (!drawerNavigationPending) scope.launch { drawerState.close() } },
                activeSessionId = conversationSessionId,
                activeTitle = conversationTitle,
                activeGoal = agentState.goal,
                activeSessionVisible = showActiveConversation,
                selectedHistoryId = selectedHistoryRunId,
                isChatRoute = route == Routes.CHAT,
                actionsEnabled = !conversationActionPending && !drawerNavigationPending,
                onConversationAction = onConversationAction,
                onNewConversation = { navigateFromDrawer {
                    onNewChat()
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                } },
                onResumeActive = { navigateFromDrawer {
                    onSelectHistory(null)
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                } },
                onOpenHistory = { runId -> navigateFromDrawer {
                    onSelectHistory(runId)
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                } },
                onOpenScheduledTasks = { navigateFromDrawer {
                    navController.navigate(Routes.TASKS) { launchSingleTop = true }
                } },
                onOpenBots = { navigateFromDrawer {
                    navController.navigate(Routes.BOTS) { launchSingleTop = true }
                } },
                onOpenSettings = { navigateFromDrawer {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                } },
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
        canCompact = agentConversationMessageCount >= 4
            && !agentState.running && !agentState.compacting,
        compacting = agentState.compacting,
        canReflect = memoryEnabled && !chatWithoutMemory && !agentState.running && !agentState.compacting
            && !(agentState.reflecting && agentState.reflectionSessionId == conversationSessionId),
        onToggleMemory = onMemoryToggleForChat,
        onCompact = onManualCompact,
        onReflect = onReflectionManual,
        bottomBar = {
            if (route == Routes.CHAT || workspaceChatActive) ChatComposer(
                    goal = if (workspaceChatActive) workspaceComposerText else goal,
                    onGoalChange = if (workspaceChatActive) ({ text: String -> workspaceComposerText = text }) else onGoalChange,
                    onSend = if (workspaceChatActive) ({
                        workspaceSelectedAssignment?.takeIf { it.canCorrect }?.let { assignment ->
                            if (workspaceComposerText.isNotBlank()) {
                                onWorkspaceControl(routeWorkspaceId.orEmpty(), assignment.id, assignment.revision,
                                    WorkspaceControlType.CORRECT, workspaceComposerText)
                                workspaceComposerText = ""
                            }
                        }
                        Unit
                    }) else onSubmitMessage,
                    onInterruptAndSend = if (workspaceChatActive) ({}) else onInterruptAndSend,
                    sendAllowed = if (workspaceChatActive) workspaceSelectedAssignment?.canCorrect == true
                        else agentState.interactiveOwnerSessionId == null,
                    canInterrupt = !workspaceChatActive && agentState.running && agentState.sessionId == conversationSessionId
                        && agentState.interactiveOwnerSessionId == conversationSessionId
                        && MessageReactionTool.isOrdinaryChat(conversationSessionId)
                        && !agentState.reflecting,
                    pendingAttachments = if (workspaceChatActive) emptyList() else pendingAttachments,
                    attachmentSessionId = visibleConversationSessionId.orEmpty(),
                    attachmentSending = !workspaceChatActive && attachmentSending,
                    onRemoveAttachment = onRemoveAttachment,
                    onAttachmentCamera = onAttachmentCamera,
                    onAttachmentPhotos = onAttachmentPhotos,
                    onAttachmentFiles = onAttachmentFiles,
                    onStop = if (workspaceChatActive) ({
                        workspaceSelectedAssignment?.takeIf { it.canPause }?.let { assignment ->
                            onWorkspaceControl(routeWorkspaceId.orEmpty(), assignment.id, assignment.revision,
                                WorkspaceControlType.PAUSE, "")
                        }
                        Unit
                    }) else onStopRun,
                    onSelectModel = { if (!workspaceChatActive) quickModelProvider = providersState.activeProvider },
                    modelLabel = composerModelName,
                    effortLabel = composerEffortLabel,
                    effortAppearance = composerEffortAppearance,
                    effortMotionActive = quickModelProvider == null && drawerState.isClosed,
                    running = if (workspaceChatActive) workspaceHasActiveWork else agentState.running || agentState.compacting
                            || agentState.reflecting && agentState.reflectionSessionId == visibleConversationSessionId,
                    captureContextRequested = !workspaceChatActive && captureContextRequested,
                    onCaptureContext = onCaptureContext,
                    onImportSkill = onImportSkill,
                    availableSkills = availableSkills,
                    selectedSkillIds = if (workspaceChatActive) emptySet() else selectedSkillIds,
                    onToggleRunSkill = onToggleRunSkill,
                    skillEnabledCount = skillEnabledCount,
                    skillTotalCount = skillTotalCount,
                    onManageSkills = { if (!workspaceChatActive) navController.navigate(Routes.SKILLS) { launchSingleTop = true } },
                    crewMode = crewMode,
                    onCrewModeChange = onCrewModeChange,
                    onOpenCrew = {
                        if (!workspaceChatActive) {
                            val latestMission = agentCrewMissions.lastOrNull()
                            navController.navigate(latestMission?.let { Routes.crew(it.missionId) } ?: Routes.CREW_EMPTY) {
                                launchSingleTop = true
                            }
                        }
                    },
                    inputEnabled = !workspaceChatActive || workspaceSelectedAssignment?.canCorrect == true,
                    disabledHint = if (workspaceChatActive) {
                        workspaceChatCorrectionDisabledHintResource(workspaceAssignments, workspaceSelectedAssignment)
                            ?.let { stringResource(it) }
                    } else null,
                    recipientContent = if (workspaceChatActive) ({
                        com.jarvys.agent.workspaces.WorkspaceAssignmentSelector(
                            assignments = workspaceAssignments,
                            selectedAssignmentId = workspaceSelectedAssignment?.id,
                            onSelect = { workspaceSelectedAssignmentId = it.id },
                        )
                    }) else null,
                    showModelSelector = !workspaceChatActive,
                    showContextActions = !workspaceChatActive,
                    showMicrophoneButton = !workspaceChatActive,
                    separateStopAction = workspaceChatActive,
                    stopActionEnabled = !workspaceChatActive ||
                        workspacePauseEnabledForUi(workspaceChatState.value, workspaceSelectedAssignmentId, workspaceHasActiveWork),
                    stopActionLabel = if (workspaceChatActive) stringResource(R.string.workspace_pause) else null,
            )
        },
    ) { padding ->
            val settingsPageContent: @Composable (JarvysSettingsPage) -> Unit = { settingsPage ->
                JarvysSettingsScreen(
                    page = settingsPage,
                    themeMode = themeMode,
                    showAgentEvents = showAgentEvents,
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
                    onNavigateRoute = { navController.navigate(it) { launchSingleTop = true } },
                    onMcp = { navController.navigate(Routes.MCP) { launchSingleTop = true } },
                    onSkills = { navController.navigate(Routes.SKILLS) { launchSingleTop = true } },
                    onModules = { navController.navigate(Routes.MODULES) { launchSingleTop = true } },
                    onSetupWizard = { navController.navigate(Routes.SETUP_WIZARD) { launchSingleTop = true } },
                    onConnectors = { navController.navigate(Routes.CONNECTORS) { launchSingleTop = true } },
                    onMemory = { navController.navigate(Routes.MEMORY) { launchSingleTop = true } },
                    archivedChatsAvailable = runHistory.any { it.archived },
                    onArchivedChats = { navController.navigate(Routes.ARCHIVED_CHATS) { launchSingleTop = true } },
                    onAccessibilitySettings = onAccessibilitySettings,
                    onNavigate = { destination ->
                        val path = when (destination) {
                            JarvysSettingsPage.HOME -> Routes.SETTINGS
                            JarvysSettingsPage.PREFERENCES -> Routes.SETTINGS_PREFERENCES
                            JarvysSettingsPage.DEVELOPER -> Routes.SETTINGS_DEVELOPER
                        }
                        navController.navigate(path) { launchSingleTop = true }
                    },
                )
            }
            NavHost(
                navController = navController,
                startDestination = if (setupWizardPreferences.shouldShowOnLaunch()) Routes.SETUP_WIZARD else Routes.CHAT,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.WORKSPACES) {
                    com.jarvys.agent.workspaces.WorkspaceListScreen(
                        workspaces = workspaceCatalog.rows,
                        workspacesLoading = workspaceCatalog.loading,
                        workspaceError = workspaceCatalog.error,
                        canPreviousWorkspacePage = workspacePageHistory.isNotEmpty(),
                        canFirstWorkspacePage = workspaceAfter != null,
                        canNextWorkspacePage = workspaceCatalog.nextCursor != null,
                        onPreviousWorkspacePage = { if (!workspaceCatalog.loading && workspacePageHistory.isNotEmpty()) {
                            workspaceCatalog = workspaceCatalog.beginRefresh()
                            workspaceAfter = workspacePageHistory.last().ifEmpty { null }; workspacePageHistory = workspacePageHistory.dropLast(1)
                        } },
                        onNextWorkspacePage = { if (!workspaceCatalog.loading) workspaceCatalog.nextCursor?.let { next ->
                            workspaceCatalog = workspaceCatalog.beginRefresh()
                            workspacePageHistory = workspacePushCursor(workspacePageHistory, workspaceAfter); workspaceAfter = next
                        } },
                        onFirstWorkspacePage = { if (!workspaceCatalog.loading) {
                            workspaceCatalog = workspaceCatalog.beginRefresh()
                            workspaceAfter = null; workspacePageHistory = emptyList()
                        } },
                        onRetryWorkspaces = { if (!workspaceCatalog.loading) {
                            workspaceCatalog = workspaceCatalog.beginRefresh(); workspaceReload++
                        } },
                        actionsEnabled = !conversationActionPending && !drawerNavigationPending,
                        onOpenWorkspace = { id -> navController.navigate("workspace/$id") { launchSingleTop = true } },
                    )
                }
                composable(Routes.COORDINATED_WORKSPACE,
                    arguments = listOf(navArgument("workspaceId") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("workspaceId").orEmpty()
                    val state = workspaceChatState.value
                    val workspaceTimelineState by AgentRunUiState.timelineEvents.collectAsState()
                    val workspaceTimelineEvents = remember(workspaceTimelineState, state?.executionSessionId) {
                        state?.executionSessionId?.let { sessionId ->
                            workspaceTimelineState.takeIf { it.sessionId == sessionId }
                        }
                    }
                    if (state == null) {
                        JarvysEmptyState(LucideIcons.Boxes,
                            stringResource(R.string.workspace_chat_load_error), "",
                            modifier = Modifier.fillMaxSize().testTag("workspace-chat-error"))
                    } else WorkspaceDetailScreen(
                        state = state,
                        runSnapshot = agentState,
                        timelineEvents = workspaceTimelineEvents,
                        workspaceRunning = workspaceHasActiveWork,
                        connectorRegistry = connectorRegistry,
                        onOpenPreview = { projectId ->
                            val sessionId = state.executionSessionId
                            val moduleArtifactId = com.jarvys.agent.modules.ModuleArtifactViewerLaunch
                                .artifactIdFromToken(projectId)
                            val destination = when {
                                moduleArtifactId != null -> Routes.moduleArtifactViewer(sessionId, moduleArtifactId)
                                HtmlPreviewDescriptor.isSnapshotToken(projectId) ->
                                    "artifact-preview/${Uri.encode(sessionId)}/${Uri.encode(projectId.removePrefix(HtmlPreviewDescriptor.TOKEN_PREFIX))}"
                                else -> "${Routes.WORKSPACE_SESSION_PREVIEW}/${Uri.encode(sessionId)}/${Uri.encode(projectId)}"
                            }
                            navController.navigate(destination) { launchSingleTop = true }
                        },
                        onOpenSkillFile = { skillFileLink = it },
                        onOpenCrewMission = { missionId ->
                            workspaceCrewSessionId = state.executionSessionId
                            navController.navigate(Routes.crew(missionId))
                        },
                        onRetry = { if (!state.loading) workspaceChatReload++ },
                        isLoadingOlderHistory = workspaceOlderHistoryLoading,
                        olderHistoryLoadError = workspaceOlderHistoryLoadError,
                        onLoadOlderHistory = {
                            val sessionId = state.executionSessionId
                            val cursor = state.olderHistoryCursor
                            if (!workspaceOlderHistoryLoading && state.hasOlderHistory
                                && cursor != LocalRunStore.NO_TIMELINE_CURSOR) {
                                val requestSerial = workspaceOlderHistoryRequestSerial + 1L
                                workspaceOlderHistoryRequestSerial = requestSerial
                                workspaceOlderHistoryLoading = true
                                workspaceOlderHistoryLoadError = false
                                scope.launch(Dispatchers.IO) {
                                    val page = try {
                                        LocalRunStore(context).readConversationTimelinePage(
                                            sessionId, cursor, LocalRunStore.OLDER_TIMELINE_TURNS,
                                            state.invalidatedToolTurnMessageIds, state.projectionMarkers)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: RuntimeException) {
                                        AgentErrorReporter.reportChatDatabaseFailure(context.applicationContext, failure)
                                        null
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (requestSerial != workspaceOlderHistoryRequestSerial
                                            || !workspaceChatActive || routeWorkspaceId != state.workspaceId
                                            || workspaceChatState.value?.executionSessionId != sessionId) return@withContext
                                        if (page != null) {
                                            AgentRunUiState.prependPersistedSession(
                                                sessionId, page.events,
                                                state.hiddenAssistantMessageIds,
                                                state.hiddenTranslationMessageIds,
                                                state.translationMessageIds)
                                            workspaceChatState.value = workspaceChatState.value?.withOlderTimelinePage(page)
                                        } else {
                                            workspaceOlderHistoryLoadError = true
                                        }
                                        workspaceOlderHistoryLoading = false
                                    }
                                }
                            }
                        },
                    )
                }
                composable(Routes.COORDINATED_FILES,
                    arguments = listOf(navArgument("workspaceId") { type = NavType.StringType })) { entry ->
                    WorkspaceFilesRoute(entry.arguments?.getString("workspaceId").orEmpty())
                }
                composable(Routes.CHAT) {
                    val timelineEventsState by AgentRunUiState.timelineEvents.collectAsState()
                    val selectedSessionEvents = remember(timelineEventsState, conversationSessionId) {
                        timelineEventsState.events.takeIf { timelineEventsState.sessionId == conversationSessionId }
                            .orEmpty()
                    }
                    val conversationKey = selectedHistoryRunId ?: if (showingNewChat) "new" else conversationSessionId
                    Crossfade(targetState = conversationKey,
                        animationSpec = if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
                            else JarvysMotion.placeChange(),
                        label = "chat-conversation-transition") { visibleConversationKey ->
                        val displayedEvents = remember(selectedSessionEvents, showingNewChat, showAgentEvents) {
                            when {
                                showingNewChat -> emptyList()
                                else -> selectedSessionEvents.filter {
                                    showAgentEvents || (it.kind != "progress" && (it.kind != "tool" || it.previewId != null))
                                }
                            }
                        }
                        AgentRunScreen(
                            conversationKey = visibleConversationKey,
                            generatedImageSessionId = conversationSessionId,
                            events = displayedEvents,
                            isLoadingHistory = timelineLoading && timelinePageSessionId == conversationSessionId
                                && displayedEvents.isEmpty(),
                            isRefreshingHistory = timelineLoading && timelinePageSessionId == conversationSessionId,
                            hasOlderHistory = timelineHasOlder && timelinePageSessionId == conversationSessionId,
                            isLoadingOlderHistory = timelineOlderLoading && timelinePageSessionId == conversationSessionId,
                            olderHistoryLoadError = timelineOlderLoadError
                                && timelinePageSessionId == conversationSessionId,
                            onLoadOlderHistory = onLoadOlderTimeline,
                            onRetryOlderHistory = onLoadOlderTimeline,
                            targetMessageId = proactiveTargetMessageId,
                            onProactiveSuggestedReply = onProactiveSuggestedReply,
                            isRunning = agentState.running && selectedHistoryRunId == null && !showingNewChat,
                            emptyReport = null,
                            connectorRegistry = connectorRegistry,
                            onOpenPreview = { projectId ->
                                val moduleArtifactId = com.jarvys.agent.modules.ModuleArtifactViewerLaunch
                                    .artifactIdFromToken(projectId)
                                val destination = when {
                                    moduleArtifactId != null -> Routes.moduleArtifactViewer(conversationSessionId, moduleArtifactId)
                                    HtmlPreviewDescriptor.isSnapshotToken(projectId) ->
                                        "artifact-preview/${Uri.encode(conversationSessionId)}/${Uri.encode(projectId.removePrefix(HtmlPreviewDescriptor.TOKEN_PREFIX))}"
                                    else -> "workspace-preview/${Uri.encode(projectId)}"
                                }
                                navController.navigate(destination) { launchSingleTop = true }
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
                            onOpenScheduledTask = { task ->
                                if (task.status == "cancelled") {
                                    navController.navigate(Routes.TASKS) { launchSingleTop = true }
                                } else {
                                    navController.navigate(Routes.TASKS) { launchSingleTop = true }
                                    navController.navigate(Routes.taskDetail(task.taskId)) { launchSingleTop = true }
                                }
                            },
                            runSnapshot = agentState,
                            allowMessageEntrance = selectedHistoryRunId == null && !showingNewChat,
                            showAssistantProgress = MessageReactionTool.isOrdinaryChat(conversationSessionId),
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
                composable(Routes.SETTINGS) { settingsPageContent(JarvysSettingsPage.HOME) }
                composable(Routes.SETTINGS_PREFERENCES) { settingsPageContent(JarvysSettingsPage.PREFERENCES) }
                composable(Routes.SETTINGS_DEVELOPER) { settingsPageContent(JarvysSettingsPage.DEVELOPER) }
                composable(Routes.SETUP_WIZARD) {
                    SetupWizardScreen(
                        preferences = setupWizardPreferences,
                        onFinish = {
                            connectorRegistry.notifyDefaultAutonomyPolicyChanged()
                            if (!navController.popBackStack()) {
                                navController.navigate(Routes.CHAT) {
                                    popUpTo(Routes.SETUP_WIZARD) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                }
                providersDestinations(navController, providersRepository)
                mcpDestinations(navController, mcpServerRepository, mcpConnectionManager, mcpOAuthManager)
                composable(Routes.SKILLS) { SkillsScreen(skillRepository) }
                composable(Routes.MODULES) {
                    com.jarvys.agent.modules.ModulesScreen(
                        moduleRegistry = com.jarvys.agent.modules.ModuleRegistry.get(context),
                        connectorRegistry = connectorRegistry,
                    )
                }
                connectorsDestinations(navController, connectorRegistry)
                memoryDestinations(
                    navController = navController,
                    conversationId = conversationSessionId,
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
                    readOnly = { snapshot -> selectedHistoryRunId != null && activeWorkspaceCrewSessionId == null
                        || CoreAgentRuntime.crewManagerForSession(crewSessionId) == null
                        || snapshot == null },
                    missions = { agentCrewMissions },
                    manager = CoreAgentRuntime.crewManagerForSession(crewSessionId),
                    onStopAll = { com.jarvys.agent.crew.CrewStopActions.stopAll(onStopRun,
                        CoreAgentRuntime.crewManagerForSession(crewSessionId)) },
                    onResumeBot = { botId ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CoreAgentRuntime.resumeCrewBot(context.applicationContext, crewSessionId, botId) }
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
                    onOpenPreview = { projectId ->
                        val moduleArtifactId = com.jarvys.agent.modules.ModuleArtifactViewerLaunch
                            .artifactIdFromToken(projectId)
                        val destination = when {
                            moduleArtifactId != null -> Routes.moduleArtifactViewer(crewSessionId, moduleArtifactId)
                            HtmlPreviewDescriptor.isSnapshotToken(projectId) ->
                                "artifact-preview/${Uri.encode(crewSessionId)}/${Uri.encode(projectId.removePrefix(HtmlPreviewDescriptor.TOKEN_PREFIX))}"
                            activeWorkspaceCrewSessionId != null ->
                                "${Routes.WORKSPACE_SESSION_PREVIEW}/${Uri.encode(crewSessionId)}/${Uri.encode(projectId)}"
                            else -> "workspace-preview/${Uri.encode(projectId)}"
                        }
                        navController.navigate(destination) { launchSingleTop = true }
                    },
                )
                composable(
                    route = Routes.WORKSPACE_PREVIEW,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                ) { entry ->
                    WorkspacePreviewScreen(entry.arguments?.getString("projectId").orEmpty(), sessionId = conversationSessionId)
                }
                composable(
                    route = Routes.WORKSPACE_SESSION_PREVIEW,
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType },
                        navArgument("projectId") { type = NavType.StringType }),
                ) { entry ->
                    WorkspacePreviewScreen(entry.arguments?.getString("projectId").orEmpty(),
                        sessionId = entry.arguments?.getString("sessionId"))
                }
                composable(
                    route = Routes.ARTIFACT_PREVIEW,
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType },
                        navArgument("artifactId") { type = NavType.StringType }),
                ) { entry ->
                    WorkspacePreviewScreen(HtmlPreviewDescriptor.TOKEN_PREFIX + entry.arguments?.getString("artifactId").orEmpty(),
                        sessionId = entry.arguments?.getString("sessionId"))
                }
                composable(
                    route = "${Routes.MODULE_ARTIFACT_VIEWER}/{sessionId}/{artifactId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType },
                        navArgument("artifactId") { type = NavType.StringType }),
                ) { entry ->
                    com.jarvys.agent.modules.ModuleArtifactViewerScreen(
                        conversationId = entry.arguments?.getString("sessionId").orEmpty(),
                        artifactId = entry.arguments?.getString("artifactId").orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                    )
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
    isLoadingHistory: Boolean = false,
    isRefreshingHistory: Boolean = isLoadingHistory,
    hasOlderHistory: Boolean = false,
    isLoadingOlderHistory: Boolean = false,
    olderHistoryLoadError: Boolean = false,
    onLoadOlderHistory: () -> Unit = {},
    onRetryOlderHistory: () -> Unit = onLoadOlderHistory,
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
    onOpenScheduledTask: (com.jarvys.agent.ScheduledTaskArtifact) -> Unit = {},
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    runSnapshot: AgentRunUiSnapshot? = null,
    allowMessageEntrance: Boolean = true,
    showAssistantProgress: Boolean = false,
) {
    ConversationTimeline(
        conversationKey = conversationKey,
        generatedImageSessionId = generatedImageSessionId,
        events = events,
        isLoadingHistory = isLoadingHistory,
        isRefreshingHistory = isRefreshingHistory,
        hasOlderHistory = hasOlderHistory,
        isLoadingOlderHistory = isLoadingOlderHistory,
        olderHistoryLoadError = olderHistoryLoadError,
        onLoadOlderHistory = onLoadOlderHistory,
        onRetryOlderHistory = onRetryOlderHistory,
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
        onOpenScheduledTask = onOpenScheduledTask,
        onSaveGeneratedImage = onSaveGeneratedImage,
        onShareGeneratedImage = onShareGeneratedImage,
        runSnapshot = runSnapshot,
        allowMessageEntrance = allowMessageEntrance,
        showAssistantProgress = showAssistantProgress,
    )
}

internal fun approvalIntent(spec: ApprovalIntentSpec): Intent = when (spec.kind) {
    ApprovalIntentKind.DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse(requireNotNull(spec.dataUri)))
    ApprovalIntentKind.CONTACT_INSERT -> Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
        spec.extras["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        spec.extras["phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        spec.extras["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        spec.extras["company"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        spec.extras["job_title"]?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
    }
}

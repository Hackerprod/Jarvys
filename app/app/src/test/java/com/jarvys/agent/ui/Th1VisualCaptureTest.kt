package com.jarvys.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.ActivityOptionsCompat
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.JarvysSettingsPage
import com.jarvys.agent.JarvysSettingsScreen
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.MemoryConstants
import com.jarvys.agent.MemorySettingsScreen
import com.jarvys.agent.MemoryStore
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.tasks.ui.TasksListScreen
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.tasks.CalendarCadence
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.ScheduleCalculator
import com.jarvys.agent.tasks.TaskDelivery
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger
import com.jarvys.agent.tasks.TaskSchedule
import com.jarvys.agent.tasks.TaskState
import com.jarvys.agent.tasks.TaskStore
import com.jarvys.agent.tasks.TaskToolScope
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewRoleTemplates
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.chat.AgentPresenceIndicator
import com.jarvys.agent.ui.chat.ChatComposer
import com.jarvys.agent.ui.chat.ConversationDrawer
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.JarvysRouteTopBar
import com.jarvys.agent.crew.CrewMode
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Th1VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.create("th1-shots")

    private enum class Scene { CHAT, PRESENCE, TOOL, DRAWER, SETTINGS, CREW, TASKS, MEMORY }

    @Test fun captureBeforeOrAfterScenesIndependentlyInBothThemes() {
        if (com.jarvys.agent.BuildConfig.FLAVOR != "play") return
        val stage = System.getenv("TH1_CAPTURE_STAGE") ?: "after"
        require(stage == "before" || stage == "after")
        output.listFiles()?.filter { it.name.startsWith("${stage}_") && it.extension == "png" }
            ?.forEach { TestCaptureDirectories.delete(output, it) }
        val hashes = mutableListOf<String>()
        for (mode in listOf(JarvysThemeMode.LIGHT, JarvysThemeMode.DARK)) {
            for (scene in Scene.entries) {
                installScene(mode, scene)
                compose.waitForIdle()
                if (scene == Scene.TASKS) compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag("tasks-list").fetchSemanticsNode(); true }.getOrDefault(false)
                }
                if (scene == Scene.MEMORY) compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag("memory-reflection-card").fetchSemanticsNode(); true }.getOrDefault(false)
                }
                repeat(3) { compose.mainClock.advanceTimeByFrame() }
                val bitmap = captureActivityBitmap()
                try {
                    val suffix = if (mode == JarvysThemeMode.DARK) "dark" else "light"
                    val file = File(output, "${stage}_${scene.name.lowercase(Locale.ROOT)}_$suffix.png")
                    file.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                    hashes += MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                } finally { bitmap.recycle() }
            }
        }
        assertEquals("Every TH1 scene/theme capture must be visually distinct", Scene.entries.size * 2, hashes.toSet().size)
    }

    private fun installScene(mode: JarvysThemeMode, scene: Scene) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val timestamp = 1_740_000_000_000L
        val events = listOf(
            AgentRunUiEvent.messageEvent(1L, "user", "Compara las opciones para el siguiente paso.", timestamp),
            AgentRunUiEvent.toolEvent(2L, "tool_error", "Calendar Search", "El proveedor rechazó la consulta.", "call-fail", null, timestamp + 1),
            AgentRunUiEvent.toolEvent(3L, "tool_result", "Workspace Write", "Se guardó el resumen en project/plan.md.", "call-ok", null, timestamp + 2),
            AgentRunUiEvent.messageEvent(4L, "assistant", "La opción azul mantiene visibles los estados y conserva el contraste.", timestamp + 3),
        )
        compose.activity.runOnUiThread {
            compose.activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            compose.activity.window.navigationBarColor = (if (mode == JarvysThemeMode.DARK)
                com.jarvys.agent.JarvysPalette.CanvasDark else com.jarvys.agent.JarvysPalette.CanvasLight).toArgb()
            compose.activity.window.decorView.systemUiVisibility = if (mode == JarvysThemeMode.DARK) 0 else
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
            JarvysOwnTheme(mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        when (scene) {
                            Scene.CHAT -> Column(Modifier.fillMaxSize()) {
                                JarvysRouteTopBar(
                                    route = AppRouteMeta("Conversación", isRoot = true, action = AppRouteAction.CHAT),
                                    agentSnapshot = AgentRunUiSnapshot(events = events),
                                    onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                                    onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                                    canCompact = false, compacting = false, canReflect = false,
                                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                                )
                                Box(Modifier.weight(1f)) {
                                    ConversationTimeline("th1-chat-$mode", events = events, isRunning = false,
                                        connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                                        chatWithoutMemory = false, runSnapshot = AgentRunUiSnapshot(events = events),
                                        allowMessageEntrance = false)
                                }
                                ChatComposer(goal = "¿Puedes resumir el plan de lanzamiento?", onGoalChange = {},
                                    onSend = {}, onStop = {}, onSelectModel = {}, modelLabel = "GPT-5.4",
                                    running = false, captureContextRequested = false, onCaptureContext = {},
                                    onImportSkill = {}, availableSkills = emptyList(), selectedSkillIds = emptySet(),
                                    onToggleRunSkill = {}, onManageSkills = {}, skillEnabledCount = 0,
                                    skillTotalCount = 0, crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {})
                            }
                            Scene.PRESENCE -> Column(Modifier.fillMaxSize()) {
                                JarvysRouteTopBar(
                                    route = AppRouteMeta("Trabajo en curso", isRoot = true, action = AppRouteAction.CHAT),
                                    agentSnapshot = AgentRunUiSnapshot(running = true, events = listOf(
                                        AgentRunUiEvent.toolEvent(5L, "tool_call", "Calendar Search", null, "presence", null, 5L))),
                                    onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                                    onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                                    canCompact = false, compacting = false, canReflect = false,
                                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                                )
                                AgentPresenceIndicator(AgentRunUiSnapshot(running = true), Modifier.padding(24.dp), showLabel = true)
                            }
                            Scene.TOOL -> Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Actividad de herramientas", style = MaterialTheme.typography.titleMedium)
                                ConversationTimeline("th1-tool-$mode", events = events.drop(1), isRunning = false,
                                    connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                                    chatWithoutMemory = false, runSnapshot = AgentRunUiSnapshot(events = events.drop(1)),
                                    allowMessageEntrance = false)
                            }
                            Scene.DRAWER -> ConversationDrawer(
                                history = listOf(
                                    RunHistoryItem("chat-a", "Plan de lanzamiento", "COMPLETED", 3, 2, 1_740_000_000.0, title = "Lanzamiento azul"),
                                    RunHistoryItem("chat-b", "Revisa el calendario", "FAILED", 2, 1, 1_739_000_000.0, title = "Calendario"),
                                    RunHistoryItem("chat-c", "Resume la reunión", "COMPLETED", 5, 3, 1_738_000_000.0, title = "Reunión semanal"),
                                ), activeSessionId = "chat-active", activeTitle = "Diseño del tema azul",
                                activeGoal = "Compara opciones", activeSessionVisible = true, selectedHistoryId = null,
                                isChatRoute = true, onNewConversation = {}, onResumeActive = {}, onOpenHistory = {}, onOpenSettings = {},
                            )
                            Scene.SETTINGS -> JarvysSettingsScreen(
                                page = JarvysSettingsPage.HOME, themeMode = mode, showAgentEvents = true,
                                proactiveEnabled = false, proactiveStatus = ProactiveStatus(enabled = false),
                                memoryEnabled = true, memoryUsedCharacters = 2_400,
                                languageChoice = AppLanguageChoice.SPANISH, onLanguageChange = {}, onThemeChange = {},
                                onShowAgentEventsChange = {}, onProactiveEnabledChange = {}, onRefreshProactiveStatus = {},
                                onNavigateRoute = {}, onMcp = {}, onSkills = {},
                                onConnectors = {}, onMemory = {}, onAccessibilitySettings = {}, onNavigate = {},
                            )
                            Scene.CREW -> CrewGallery()
                            Scene.TASKS -> TasksGallery(context)
                            Scene.MEMORY -> MemoryGallery(context)
                        }
                    }
                    }
                }
            }
            }
        }
    }

    @Composable private fun CrewGallery() {
        val roles = listOf(
            Triple(CrewRoleTemplates.EXPLORER, "Luna", "blue"),
            Triple(CrewRoleTemplates.ANALYST, "Nico", "violet"),
            Triple(CrewRoleTemplates.CRITIC, "Vera", "rose"),
            Triple(CrewRoleTemplates.WRITER, "Sol", "green"),
            Triple(CrewRoleTemplates.OPERATOR, "Teo", "operator"),
        )
        val bots = roles.mapIndexed { index, (role, name, color) ->
            CrewBotSnapshot("bot-$index", role, role, name, color, "Revisa fuentes y resume un hallazgo",
                if (index == 4) "DONE" else "RUNNING", "", if (index == 4) "Resumen listo" else "",
                "", emptyList(), 1L, if (index == 4) 2L else 0L)
        }
        val mission = CrewMissionSnapshot("th1-crew", "th1", "th1", "Preparar una propuesta para el equipo",
            "RUNNING", "", System.currentTimeMillis() - 90_000, 0, bots, emptyList())
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Equipo Crew", style = MaterialTheme.typography.titleMedium)
            CrewMissionCard(mission, onOpen = {}, reducedMotionOverride = true)
            roles.forEach { (role, name, color) ->
                Text("$name · $role · $color", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun TasksGallery(context: android.content.Context) {
        val files = remember { File(context.cacheDir, "th1-tasks-${UUID.randomUUID()}").apply { mkdirs() } }
        val clock = remember { Clock.fixed(Instant.parse("2025-04-01T09:00:00Z"), ZoneOffset.UTC) }
        val store = remember { TaskStore(File(files, "tasks.jsonl")) }
        val ledger = remember { TaskRunLedger(File(files, "runs.jsonl")) }
        val calculator = remember { ScheduleCalculator(clock,
            com.jarvys.agent.tasks.TaskZoneProvider { ZoneId.of("UTC") }) }
        val repository = remember { TaskRepository(context, store, calculator, clock, {}, ledger) }
        LaunchedEffect(Unit) {
            if (store.list().isEmpty()) {
                val now = clock.millis()
                store.create(ScheduledTask(name = "Preparar resumen diario", instruction = "Resume novedades",
                    schedule = TaskSchedule.Calendar("08:30", CalendarCadence.DAILY,
                        zone = com.jarvys.agent.tasks.TaskZone.Iana("UTC")), state = TaskState.Active,
                    nextRunAt = now + 60_000L, toolScope = TaskToolScope("LISTED", emptyList()),
                    delivery = TaskDelivery.ALWAYS, createdBy = "USER_UI", createdAt = now, updatedAt = now))
                store.create(ScheduledTask(name = "Revisar hitos del proyecto", instruction = "Revisa tareas abiertas",
                    schedule = TaskSchedule.Calendar("15:00", CalendarCadence.WEEKLY, daysOfWeek = setOf(1, 3),
                        zone = com.jarvys.agent.tasks.TaskZone.Iana("UTC")), state = TaskState.Paused,
                    nextRunAt = now + 120_000L, toolScope = TaskToolScope("LISTED", emptyList()),
                    delivery = TaskDelivery.ONLY_IF_NOTABLE, createdBy = "USER_UI", createdAt = now, updatedAt = now))
            }
        }
        TasksListScreen(context, repository, ledger, onOpenTask = {}, onRequestChange = {},
            onConfigureProvider = {}, onMessage = {}, onReturnToSettings = {}, healthIssuesOverride = { emptyList() },
            backgroundWorkBusyForAll = false)
    }

    @Composable private fun MemoryGallery(context: android.content.Context) {
        val memory = remember {
            MemoryStore(context).also { it.ensureInitialized() }
        }
        val registryOwner = rememberMemoryActivityResultOwner()
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            MemorySettingsScreen(store = memory, enabled = true, onEnabledChange = {},
                conversationId = "th1-memory", onShowDisclosure = {}, onMemoryChanged = {},
                reflectionEnabled = true, reflectionStatus = "Completada", lastReflectionMillis = System.currentTimeMillis() - 3_600_000L,
                reflecting = false, onReflectionEnabledChange = {}, onReflectionDisclosure = {},
                onOpenHistory = {}, onOpenFile = { _, _ -> })
        }
    }

    @Composable private fun rememberMemoryActivityResultOwner(): ActivityResultRegistryOwner {
        val registry = androidx.compose.runtime.remember {
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                                             input: I, options: ActivityOptionsCompat?) = Unit
            }
        }
        return androidx.compose.runtime.remember(registry) {
            object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        }
    }

    private fun captureActivityBitmap(): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val width = (411f * density).toInt()
            val height = (891f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            captured = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        return requireNotNull(captured)
    }
}

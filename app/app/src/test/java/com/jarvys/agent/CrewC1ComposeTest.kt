package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewApprovalAttribution
import com.jarvys.agent.crew.CrewBotDetailScreen
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewMissionScreen
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.crew.CrewModePicker
import com.jarvys.agent.ui.motion.JarvysMotionPolicy
import com.jarvys.agent.crew.CrewNavigationRoutes
import com.jarvys.agent.crew.CrewRole
import com.jarvys.agent.crew.CrewTools
import com.jarvys.agent.crew.crewDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrewC1ComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun snapshotBot(id: String, name: String, roleId: String, color: String, status: String) =
        CrewBotSnapshot(id, roleId, roleId, name, color, "Verify source evidence", status, "", "", "",
            listOf("board_read", "msg_send"), 100L, if (status == "DONE") 500L else 0L)

    private fun snapshot(botCount: Int, status: String = "RUNNING"): CrewMissionSnapshot {
        val roles = listOf(
            Triple("explorador", "teal", "Explorer"),
            Triple("analista", "amber", "Analyst"),
            Triple("critico", "rose", "Critic"),
            Triple("redactor", "green", "Writer"),
            Triple("operador", "custom-operator", "Operator"),
        )
        val bots = (0 until botCount).map { index ->
            val role = roles[index % roles.size]
            snapshotBot("bot-$index", "${role.third} ${index + 1}", role.first, role.second,
                if (index % 3 == 0) "RUNNING" else if (status == "SYNTHESIZED") "DONE" else "WAITING")
        }
        val messages = listOf(
            CrewMessage("message-1", "session-c1", "chief", "bot-0", CrewMessage.Type.TASK,
                "Find primary evidence", emptyList(), 200L),
            CrewMessage("message-2", "session-c1", "bot-0", "chief", CrewMessage.Type.FINDING,
                "The first source is dated 2024.", listOf("/board/source.md"), 300L),
        ).takeIf { botCount > 0 }.orEmpty()
        return CrewMissionSnapshot("mission-c1", "session-c1", "process", "Compare primary sources",
            status, if (status == "SYNTHESIZED") "Verified with uncertainty noted." else "",
            100L, if (status == "SYNTHESIZED") 500L else 0L, bots, messages)
    }

    @Test
    fun missionCardMeasuresZeroOneThreeAndTwelveBotsAtFontScaleTwoWithoutOrbOverlap() {
        var mission by mutableStateOf(snapshot(0))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).fillMaxSize().testTag("crew-test-viewport")) {
                        CrewMissionCard(mission, {}, reducedMotionOverride = true)
                    }
                }
            }
        }
        for (count in listOf(0, 1, 3, 12)) {
            mission = snapshot(count)
            compose.waitForIdle()
            compose.onNodeWithTag("crew-mission-card").assertIsDisplayed()
            val tags = listOf("crew-orb-captain") + (0 until count).map { "crew-orb-bot-$it" }
            val bounds = tags.map { tag -> compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
            bounds.forEachIndexed { index, current ->
                for (otherIndex in index + 1 until bounds.size) {
                    val other = bounds[otherIndex]
                    val separated = current.right <= other.left || other.right <= current.left
                        || current.bottom <= other.top || other.bottom <= current.top
                    assertTrue("orbs overlap at botCount=$count: $current / $other", separated)
                }
            }
            compose.onNodeWithTag("crew-progress").assertIsDisplayed()
        }
        mission = snapshot(3, "SYNTHESIZED")
        compose.waitForIdle()
        compose.onNodeWithTag("crew-mission-pill").assertIsDisplayed()
        compose.onAllNodesWithTag("crew-mission-card").assertCountEquals(0)
        mission = snapshot(3, "RUNNING")
        compose.waitForIdle()
        compose.onNodeWithTag("crew-mission-card").assertIsDisplayed()
        assertFalse(JarvysMotionPolicy.shouldAnimate(active = true, reduced = true))
        assertTrue(JarvysMotionPolicy.shouldAnimate(active = true, reduced = false))
    }

    @Test
    fun actualCrewNavDestinationsOpenTabsReferencesBotsAndBackAtFontScaleTwo() {
        val session = "session-c1"
        val workspace = WorkspaceStore(Files.createTempDirectory("crew-nav-workspace").toFile(),
            WorkspaceStore.projectIdForSession(session))
        val board = CrewBoard(workspace)
        board.post("/board/source.md", "Primary source text")
        val mission = snapshot(1)
        val navControllerHolder = AtomicReference<androidx.navigation.NavController>()
        var boardReference by mutableStateOf<String?>(null)
        compose.setContent {
            val nav = rememberNavController()
            navControllerHolder.set(nav)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    JarvysScreen(title = stringResource(R.string.crew_title), onBack = { nav.popBackStack() }) { padding ->
                        NavHost(nav, startDestination = "crew-home", modifier = Modifier.padding(padding).fillMaxSize()) {
                            composable("crew-home") {
                                CrewMissionCard(mission, { nav.navigate(CrewNavigationRoutes.mission(mission.missionId)) }, true)
                            }
                            crewDestinations(
                                navController = nav,
                                board = board,
                                readOnly = { false },
                                missions = { listOf(mission) },
                                manager = null,
                                onStopAll = {},
                                initialBoardReference = boardReference,
                                onBoardReferenceConsumed = { boardReference = null },
                                onBoardReference = { _, reference -> boardReference = reference; nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("crew-mission-card").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("crew-mission-screen").assertIsDisplayed()
        compose.onNodeWithTag("crew-ref-message-2").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Primary source text").assertIsDisplayed(); true }
                .getOrDefault(false)
        }
        compose.onNodeWithText("Primary source text").assertIsDisplayed()
        compose.onNodeWithTag("crew-tab-bots").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("crew-bot-row-bot-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("crew-bot-detail").assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("crew-mission-screen").assertIsDisplayed()
        assertTrue(navControllerHolder.get().currentDestination?.route == CrewNavigationRoutes.MISSION)
        navControllerHolder.get().navigate(CrewNavigationRoutes.EMPTY)
        compose.waitForIdle()
        compose.onNodeWithTag("crew-empty-state").assertIsDisplayed()
    }

    @Test
    fun userMessageFromBotDetailIsTrustedOnItsNextModelTurnAndPersistedAsUser() {
        val readyForMessage = CountDownLatch(1)
        val releaseLoop = CountDownLatch(1)
        val observedTranscript = AtomicReference<List<ConversationTurn>>()
        val observedPrompt = AtomicReference<String>()
        val baseTools = CoreToolRegistry(emptyList())
        lateinit var manager: CrewManager
        manager = CrewManager("trusted-input", baseTools, { _, _ -> baseTools },
            { _, tools, incoming ->
                readyForMessage.countDown()
                try { releaseLoop.await() } catch (_: InterruptedException) { throw java.util.concurrent.CancellationException() }
                CoreAgentLoop(object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                          declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                        observedTranscript.set(transcript.toList()); observedPrompt.set(prompt)
                        return ModelReply("acknowledged", emptyList())
                    }
                }, tools, "bot instructions", "trusted-input-bot", CorePromptBudget.standard(), null,
                    CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
            }, null)
        try {
            val bot = manager.spawn("custom", "Wait for user guidance", emptyList(), "Writer")
            assertTrue(readyForMessage.await(30, TimeUnit.SECONDS))
            val mission = CrewMissionSnapshot("mission", manager.conversationId(), "process", "Mission", "RUNNING", "",
                1L, 0L, listOf(snapshotBot(bot.id, bot.name, bot.role.id, bot.role.colorKey, "RUNNING")), emptyList())
            val workspace = CrewBoard(WorkspaceStore(Files.createTempDirectory("crew-user-message").toFile(),
                WorkspaceStore.projectIdForSession(manager.conversationId())))
            compose.setContent {
                MaterialTheme {
                    CrewBotDetailScreen(mission, bot.id, workspace, false,
                        onSendMessage = { id, text -> manager.sendUserMessage(id, text) },
                        onRedirect = { id, text -> manager.sendUserMessage(id, text) },
                        onStopBot = { manager.stop(it) }, onBoardReference = {})
                }
            }
            compose.onNodeWithTag("crew-user-message").performTextInput("Check the updated date")
            compose.onNodeWithTag("crew-user-send").performClick()
            releaseLoop.countDown()
            bot.awaitTermination()
            assertTrue(observedTranscript.get().any { it.role == "user" && it.content == "Check the updated date" })
            assertTrue(observedPrompt.get().contains("UNTRUSTED CREW DATA"))
            assertFalse("trusted user text must not enter the untrusted envelope",
                observedPrompt.get().contains("Check the updated date"))
            val userMessage = manager.messageBus().snapshot().single { it.from == "user" }
            assertEquals(CrewMessage.Type.USER, userMessage.type)
            assertEquals(bot.id, userMessage.to)
        } finally { releaseLoop.countDown(); manager.close() }
    }

    @Test
    fun completedBotCanReceiveFollowUpButInterruptedBotShowsWhyMessagingIsUnavailable() {
        val session = "reanimate-ui"
        val workspace = CrewBoard(WorkspaceStore(Files.createTempDirectory("crew-reanimate-ui").toFile(),
            WorkspaceStore.projectIdForSession(session)))
        var status by mutableStateOf("DONE")
        var sent = ""
        val bot = snapshotBot("bot-finished", "Reviewer", "analista", "amber", "DONE")
        fun missionFor(status: String) = CrewMissionSnapshot("mission-reanimate", session, "process", "Review",
            if (status == "INTERRUPTED") "INTERRUPTED" else "SYNTHESIZED", "", 100L, 500L,
            listOf(snapshotBot(bot.id, bot.name, bot.roleId, bot.colorKey, status)), emptyList())
        compose.setContent {
            MaterialTheme {
                CrewBotDetailScreen(missionFor(status), bot.id, workspace, false,
                    onSendMessage = { _, text -> sent = text },
                    onRedirect = { _, text -> sent = text }, onStopBot = {}, onBoardReference = {})
            }
        }
        compose.onNodeWithTag("crew-user-message").assertIsDisplayed().performTextInput("Please re-check the date")
        compose.onNodeWithTag("crew-user-send").assertIsDisplayed().performClick()
        assertEquals("Please re-check the date", sent)
        compose.onAllNodesWithTag("crew-stop-bot").assertCountEquals(0)

        status = "INTERRUPTED"
        compose.waitForIdle()
        compose.onAllNodesWithTag("crew-user-message").assertCountEquals(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.crew_bot_interrupted_description)).assertIsDisplayed()
    }

    @Test
    fun modeSelectorPersistsAllThreeModesAndRuntimeReadsTheSamePreference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selected = mutableStateOf(CrewMode.AUTO)
        compose.setContent {
            MaterialTheme { CrewModePicker(selected.value, { mode ->
                CrewMode.write(context, mode)
                selected.value = mode
            }, {}) }
        }
        listOf(CrewMode.OFF, CrewMode.AUTO, CrewMode.ALWAYS).forEach { mode ->
            compose.onNodeWithTag("crew-mode-${mode.name.lowercase()}").performClick()
            assertEquals(mode, CrewMode.read(context))
        }
    }

    @Test
    fun allCrewStringsAreLocalizedAndNewComposableLabelsUseResources() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val moduleRoot = sequenceOf(File(working, "app"), working, File(working.parentFile, "app"))
            .first { File(it, "src/main/java/com/jarvys/agent/crew/CrewUi.kt").isFile }
        val resources = File(moduleRoot, "src/main/res")
        fun keys(path: File): Set<String> {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(path)
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).mapNotNull { nodes.item(it).attributes?.getNamedItem("name")?.nodeValue }.toSet()
        }
        val english = keys(File(resources, "values/strings.xml"))
        val spanish = keys(File(resources, "values-es/strings.xml"))
        val newKeys = english.filter { it.startsWith("crew_") || it == "approval_requested_by_crew_bot" }
        assertTrue("Missing English strings: ${newKeys - english}", english.containsAll(newKeys))
        assertTrue("Missing Spanish strings: ${newKeys - spanish}", spanish.containsAll(newKeys))
        val source = File(moduleRoot, "src/main/java/com/jarvys/agent/crew/CrewUi.kt").readText()
        assertFalse(Regex("Text\\s*\\(\\s*\"[A-Za-z]").containsMatchIn(source))
        assertFalse(Regex("stringResource\\s*\\(\\s*\"").containsMatchIn(source))
    }

    @Test
    fun reducedMotionFlagDisablesPulseAndFinalStatusOrbsStayStatic() {
        assertTrue(JarvysMotionPolicy.shouldAnimate(true, false))
        assertFalse(JarvysMotionPolicy.shouldAnimate(true, true))
        assertFalse(JarvysMotionPolicy.shouldAnimate(false, false))
    }

    @Test
    fun concurrentApprovalAttributionUsesCrewRoleOrbAndLocalizedNameLabel() {
        compose.setContent {
            MaterialTheme { CrewApprovalAttribution("Ada", "rose", Modifier.testTag("approval-attribution-test")) }
        }
        compose.onNodeWithTag("crew-approval-requester-orb", useUnmergedTree = true).assertIsDisplayed()
        val requesterLabel = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.approval_requested_by_crew_bot, "Ada")
        compose.onNodeWithText(requesterLabel).assertIsDisplayed()
    }
}

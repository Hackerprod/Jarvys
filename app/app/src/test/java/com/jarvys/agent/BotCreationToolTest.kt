package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.crew.BotDefinition
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotCreationToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repository: CrewProfileRepository
    private lateinit var icons: BotIconStore
    private lateinit var settings: ProviderSettings
    private lateinit var secrets: SecretStore
    private var previousSecrets: Any? = null
    private var approvalCount = 0
    private var imageCount = 0
    private var available = true
    private var approved = ApprovalDecision.APPROVED
    private var caps = listOf("board_read", "report_done")
    private var availableSkills = emptyList<String>()
    private var approvalEffect: ((CancellationToken) -> Unit)? = null
    private var imageEffect: ((CancellationToken) -> Unit)? = null
    private val summaries = mutableListOf<ApprovalSummary>()
    private val session = "bot-create-main"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = CrewProfileRepository(temporary.root)
        icons = BotIconStore(temporary.root)
        settings = ProviderSettings(context).apply { setProvider(ProviderSettings.Provider.OPENAI_CODEX) }
        secrets = SecretStore(context.getSharedPreferences("bot-create-tests", Context.MODE_PRIVATE))
        secrets.saveCodexTokens("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3600000, "fixture-account")
        val singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previousSecrets = singleton.get(null); singleton.set(null, secrets)
    }
    @After fun cleanup() {
        secrets.clearCodexTokens()
        settings.setProvider(ProviderSettings.Provider.OPENROUTER)
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, previousSecrets)
    }
    private fun arguments(key: String = "create-reviewer-1") = linkedMapOf<String, Any>(
        "request_id" to key, "name" to "Reviewer", "description" to "Review software robustness",
        "instructions" to "Review software for bugs and robustness. Explain concrete findings and uncertainty in English.",
        "capabilities" to listOf("board_read"), "skill_ids" to emptyList<String>(), "workspace_mode" to "legacy_chat",
        "icon_prompt" to "An origami owl inspecting a cobalt circuit, no lettering",
    )
    private fun tool(repo: CrewProfileRepository = repository, sessionId: String = session): BotCreationTool {
        val client = CodexImageGenerationClient(settings, { request, _, token ->
            imageCount++
            assertEquals(1, request.getJSONArray("input").length())
            assertFalse(request.toString().contains("Review software for bugs"))
            assertFalse(request.toString().contains("image_url"))
            imageEffect?.invoke(token)
            BotIconServiceTest.success()
        }, { _, _, _ -> throw AssertionError("Creation must never invoke image editing") })
        val imageService = BotIconService(context, sessionId, settings, secrets, repo, icons, client)
        return BotCreationTool(BotCreationService(repo, sessionId, { caps }, { availableSkills }, { summary, token ->
            approvalCount++; summaries += summary; approvalEffect?.invoke(token); approved
        }, { available }, imageService::generateAndAssign))
    }

    @Test fun scriptedMainLoopCreatesFullBotPersistsAndFindsItNextTurnAfterRestart() {
        val production = CoreAgentRuntime(context, session, emptyList()).createTools()
        assertTrue(production.names().contains(BotCreationTool.NAME))
        assertTrue(production.names().contains(BotCatalogTool.NAME))
        val registry = production.replaceSelectedHandlers(listOf(tool(), BotCatalogTool(repository, session)))
        var turns = 0
        val loop = CoreAgentLoop(CoreAgentLoop.Model { transcript, _, declarations, _ ->
            assertTrue(declarations.any { it.name == BotCreationTool.NAME })
            if (turns++ == 0) ModelReply("", listOf(ModelReply.Call("create-1", BotCreationTool.NAME, arguments())))
            else {
                val result = JSONObject(transcript.last { it.kind == ConversationTurn.Kind.TOOL_RESULT }.content)
                assertTrue(result.getBoolean("saved")); assertTrue(result.getBoolean("icon_complete"))
                assertEquals("generated", result.getString("icon_status"))
                ModelReply("Reviewer has been saved with its icon.", emptyList())
            }
        }, registry, "Create the user's bot with English instructions.", session)
        assertEquals("COMPLETED", loop.run("Create a software review bot with an icon", emptyList(), CancellationToken.uncancellable(), null).outcome)
        assertEquals(1, approvalCount); assertEquals(1, imageCount)
        val restarted = CrewProfileRepository(temporary.root)
        val saved = restarted.definitions().single { !it.builtIn }
        assertEquals("Reviewer", saved.profile.name)
        assertEquals(arguments()["instructions"], saved.profile.prompt)
        assertTrue(icons.resolve(saved.id, saved.iconRef).isFile)
        assertFalse(summaries.single().allowAlwaysAvailable)
        assertTrue(summaries.single().lines.any { it.contains("Tools: board_read") })
        var nextTurns = 0
        val next = CoreAgentLoop(CoreAgentLoop.Model { transcript, _, _, _ ->
            if (nextTurns++ == 0) {
                assertTrue(transcript.any { it.kind == ConversationTurn.Kind.TOOL_RESULT && it.content.contains(saved.id) })
                ModelReply("", listOf(ModelReply.Call("list-next", BotCatalogTool.NAME, emptyMap())))
            } else {
                val result = transcript.last { it.kind == ConversationTurn.Kind.TOOL_RESULT }.content
                assertTrue(result.contains(saved.id)); assertFalse(result.contains(saved.profile.prompt))
                ModelReply("Reviewer is available in Bots.", emptyList())
            }
        }, CoreToolRegistry(listOf(BotCatalogTool(restarted, session))), "Find existing bots.", session)
        assertEquals("COMPLETED", next.run("Find my bot", loop.checkpointSnapshot().reconciled().transcript, CancellationToken.uncancellable(), null).outcome)
        assertEquals(1, restarted.definitions().count { !it.builtIn })
    }

    @Test fun denialOrAllowAlwaysNeverSavesOrGenerates() {
        for (decision in listOf(ApprovalDecision.DENIED, ApprovalDecision.APPROVED_ALLOW_ALWAYS)) {
            approved = decision
            assertFalse(tool().execute(arguments(), CancellationToken.uncancellable()).success)
        }
        assertEquals(0, imageCount); assertEquals(0, repository.definitions().count { !it.builtIn })
    }
    @Test fun staleCapabilityAfterReviewDoesNotSave() {
        approvalEffect = { caps = emptyList() }
        assertFalse(tool().execute(arguments(), CancellationToken.uncancellable()).success)
        assertEquals(0, imageCount); assertEquals(0, repository.definitions().count { !it.builtIn })
    }
    @Test fun strictSchemaUnknownCapabilityAndBuiltinInjectionNeverReachReview() {
        val base = arguments()
        val invalid = listOf(base + ("bot_id" to "coding"), base + ("capabilities" to listOf("create_bot")),
            base + ("capabilities" to listOf("unknown_tool")), base + ("capabilities" to listOf("board_read", "board_read")),
            base + ("name" to "A very long descriptive software reviewer name"), base + ("request_id" to "../escape"),
            base + ("icon_path" to "/private/image.png"), base + ("instructions" to ""))
        invalid.forEach { assertFalse(tool().execute(it, CancellationToken.uncancellable()).success) }
        assertEquals(0, approvalCount); assertEquals(0, imageCount)
        assertEquals(2, repository.definitions().size)
    }
    @Test fun sameRequestRetriesAfterRestartWithoutDuplicatingOrChargingAgain() {
        val first = JSONObject(tool().execute(arguments(), CancellationToken.uncancellable()).content)
        val again = JSONObject(tool(CrewProfileRepository(temporary.root)).execute(arguments(), CancellationToken.uncancellable()).content)
        assertEquals(first.getString("bot_id"), again.getString("bot_id"))
        assertTrue(again.getBoolean("already_existed")); assertTrue(again.getBoolean("icon_complete"))
        assertEquals(1, approvalCount); assertEquals(1, imageCount)
        assertEquals(1, repository.definitions().count { !it.builtIn })
    }
    @Test fun existingRequestCannotOverwriteChangedDefinition() {
        tool().execute(arguments(), CancellationToken.uncancellable())
        assertFalse(tool().execute(arguments() + ("instructions" to "Replace everything."), CancellationToken.uncancellable()).success)
        assertEquals(arguments()["instructions"], repository.definitions().single { !it.builtIn }.profile.prompt)
        assertEquals(1, approvalCount); assertEquals(1, imageCount)
    }
    @Test fun sameOperationWithChangedIconPromptFailsAfterRestart() {
        tool().execute(arguments(), CancellationToken.uncancellable())
        val changed = tool(CrewProfileRepository(temporary.root)).execute(arguments() + ("icon_prompt" to "A different visual concept"), CancellationToken.uncancellable())
        assertFalse(changed.success)
        assertEquals(1, approvalCount); assertEquals(1, imageCount)
        assertEquals(1, repository.definitions().count { !it.builtIn })
    }
    @Test fun skillSelectionCannotExceedActualRuntimeLimit() {
        caps = listOf("read_skill")
        availableSkills = (1..9).map { "skill.$it" }
        val result = tool().execute(arguments() + mapOf("capabilities" to caps, "skill_ids" to availableSkills), CancellationToken.uncancellable())
        assertFalse(result.success); assertEquals(0, approvalCount); assertEquals(0, imageCount)
        assertEquals(2, repository.definitions().size)
    }
    @Test fun unavailableImageIsHonestAndKeepsSavedConfigWithoutFallback() {
        available = false
        val result = JSONObject(tool().execute(arguments(), CancellationToken.uncancellable()).content)
        assertTrue(result.getBoolean("saved")); assertFalse(result.getBoolean("icon_complete"))
        assertEquals("unavailable", result.getString("icon_status"))
        assertEquals(0, imageCount); assertEquals(1, repository.definitions().count { !it.builtIn })
    }
    @Test fun iconFailureKeepsDefinitionAndDoesNotRetryOnDuplicateRequest() {
        imageEffect = { throw IllegalStateException("private provider details") }
        val first = tool().execute(arguments(), CancellationToken.uncancellable())
        assertTrue(first.success); assertFalse(first.content.contains("private provider details"))
        assertFalse(JSONObject(first.content).getBoolean("icon_complete"))
        tool().execute(arguments(), CancellationToken.uncancellable())
        assertEquals(1, imageCount); assertEquals(1, repository.definitions().count { !it.builtIn })
    }
    @Test fun cancellationBeforeApprovalCommitSavesNothing() {
        approvalEffect = { it.cancel() }
        assertThrows(CancellationException::class.java) { tool().execute(arguments(), CancellationToken.cancellable()) }
        assertEquals(0, repository.definitions().count { !it.builtIn }); assertEquals(0, imageCount)
    }
    @Test fun cancellationAfterPersistenceLeavesDiscoverableBotAndRetryDoesNotDuplicate() {
        imageEffect = { it.cancel() }
        assertThrows(CancellationException::class.java) { tool().execute(arguments(), CancellationToken.cancellable()) }
        assertEquals(1, repository.definitions().count { !it.builtIn })
        val retry = JSONObject(tool().execute(arguments(), CancellationToken.uncancellable()).content)
        assertTrue(retry.getBoolean("already_existed")); assertFalse(retry.getBoolean("icon_complete"))
        assertEquals(1, approvalCount); assertEquals(1, imageCount)
    }
    @Test fun childrenAndScheduledOrProactiveRunsCannotCreateBots() {
        assertFalse(tool().execute(arguments(), CancellationToken.crewChild()).success)
        for (sessionId in listOf("", ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID))
            assertFalse(tool(sessionId = sessionId).execute(arguments(), CancellationToken.uncancellable()).success)
        assertFalse(tool().canDelegate())
        assertTrue(CoreToolRegistry(listOf(tool())).forDelegatedAgent().names().isEmpty())
        assertTrue(CoreAgentRuntime.crewBotCapabilityScope(CoreToolRegistry(listOf(tool()))).names().isEmpty())
        assertTrue(CrewManager.isCaptainOnly(BotCreationTool.NAME))
        assertEquals(0, approvalCount)
    }
    @Test fun resultAndAuditDoNotExposeInstructionsIconPromptOrImageFiles() {
        val result = tool().execute(arguments(), CancellationToken.uncancellable())
        assertTrue(result.success)
        for (private in listOf(arguments()["instructions"] as String, arguments()["icon_prompt"] as String, ".png", "bot_icons", "base64")) {
            assertFalse(result.content.contains(private)); assertFalse(tool().auditDetail(arguments()).contains(private))
        }
        assertEquals(false, tool().declaration().jsonSchema()["additionalProperties"])
        assertTrue(tool().declaration().description.contains("English"))
    }
}

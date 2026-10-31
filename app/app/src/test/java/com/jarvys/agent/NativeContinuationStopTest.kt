package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.withChatReadTransaction
import com.jarvys.agent.chatdb.chatSql
import kotlinx.coroutines.runBlocking
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Mission Stop-all has no captain callback; the DONE child's native hook owns exact cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CentralFlowTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeContinuationStopTest {
    private val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    @Test fun missionStopAllCancelsAcknowledgedCentralOwnerWithoutTouchingReplacement() = verifyMissionStop(false)

    @Test fun missionStopBetweenDurableClaimAndOwnerRegistrationCannotBeLost() = verifyMissionStop(true)

    private fun verifyMissionStop(beforeOwnerRegistration: Boolean) {
        storage.localRunStore(context.filesDir)
        ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENROUTER); setOpenRouterModel("offline/continuation-stop")
        }
        check(context.getSharedPreferences("provider_context_metadata", 0).edit()
            .putInt("model_offline/continuation-stop", 32768)
            .putLong("fetched_offline/continuation-stop", System.currentTimeMillis()).commit())
        AgentRunUiState.resetSession(central)
        val store = LocalRunStore(context)
        val scope = storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(central, "chat")
            WorkspaceRegistry(context).use { it.createWorkspace(UUID.randomUUID().toString(), "Itinerary stop") }.also {
                ChatDatabaseBridge.getInstance(context).ensureConversationSync(it.executionSessionId, "workspace")
            }
        }
        val profile = storage.onDatabaseWorker {
            CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(), 1,
                "Planner", "General-purpose planning", "Prepare an itinerary", emptyList(), emptyList(),
                CrewProfile.WorkspaceMode.LEGACY_CHAT), emptyList(), emptyList())
        }
        val manager = storage.onDatabaseWorker {
            val childProvider = object : ModelProviderClient {
                override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                    tools: List<ToolSpec>, sessionId: String, token: CancellationToken) = ModelReply("Retained itinerary", emptyList())
                override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                    images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken) =
                    ModelReply("Retained itinerary", emptyList())
            }
            val runtime = CoreAgentRuntime(context, scope.executionSessionId, emptyList(), CorePromptBudget.standard())
            runtime.configureCrewManager(runtime.createTools(), CoreAgentModel(context, childProvider, scope.executionSessionId), null)
        }
        val releaseProvider = CountDownLatch(1)
        val providerEntered = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val reservationExecutor = Executors.newSingleThreadExecutor()
        val releaseReservation = CountDownLatch(1)
        val reservationEntered = CountDownLatch(1)
        var reservation: java.util.concurrent.Future<*>? = null
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        val ownerController = StopController.forRun()
        val exactToken = ownerController.beginRun()!!
        var replacement: CancellationToken? = null
        try {
            val delegated = storage.onDatabaseWorker {
                WorkspaceCoordinator(context, { manager }, {}).delegateContinuing(scope.workspaceId,
                    "stop-assignment", profile.id, "Prepare itinerary", emptyList(), "Planner", "standard",
                    "stop-request", "Plan a trip", CancellationToken.uncancellable(), Runnable {}, null)
            }
            val bot = manager.bot(delegated.getString("bot_id"))!!
            executor.submit { bot.awaitTermination() }.get(15, TimeUnit.SECONDS)
            assertEquals(bot.error(), CrewManager.Status.DONE, bot.status())
            val checkpoints = CrewCheckpointStore(context.filesDir)
            val inputId = storage.onDatabaseWorker {
                WorkspaceResultDelivery(context).reconcile(scope.workspaceId, CancellationToken.uncancellable())
                val record = checkpoints.load(scope.executionSessionId, bot.id)!!.metadata.getJSONObject(CentralContinuationRecord.FIELD)
                assertEquals("PREPARED", record.getString("dispatchState"))
                store.readConversationMessageByStableId(central, record.getString("inputStableId"))!!.getString("messageId")
            }
            @Suppress("UNCHECKED_CAST")
            val owners = WorkspaceContinuationDelivery::class.java.getDeclaredField("owners").apply { isAccessible = true }
                .get(null) as java.util.concurrent.ConcurrentHashMap<String, Any>
            val continuationKey = checkpoints.load(scope.executionSessionId, bot.id)!!.metadata
                .getJSONObject(CentralContinuationRecord.FIELD).getString("continuationKey")
            if (beforeOwnerRegistration) {
                // A real empty CHM reservation blocks putIfAbsent after the durable claim CAS.
                // It inserts no owner/authority and get remains nonblocking for the native stop hook.
                reservation = reservationExecutor.submit {
                    owners.compute(continuationKey) { _, old ->
                        assertNull(old)
                        reservationEntered.countDown()
                        assertTrue("Owner reservation was not released", releaseReservation.await(20, TimeUnit.SECONDS))
                        null
                    }
                }
                assertTrue(reservationEntered.await(10, TimeUnit.SECONDS))
            }
            val provider = object : ModelProviderClient {
                override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                    tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                    throw AssertionError("Expected conversation provider")
                override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                    images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                    assertSame(exactToken, token)
                    calls.incrementAndGet()
                    assertFalse("Stopped pre-owner claim must never reach a provider", beforeOwnerRegistration)
                    assertEquals("ACKNOWLEDGED", checkpoints.load(scope.executionSessionId, bot.id)!!.metadata
                        .getJSONObject(CentralContinuationRecord.FIELD).getString("dispatchState"))
                    providerEntered.countDown()
                    assertTrue(releaseProvider.await(15, TimeUnit.SECONDS))
                    token.throwIfCancelled()
                    return ModelReply("must not complete after mission stop", emptyList())
                }
            }
            service.runtimeFactory = AgentForegroundService.RuntimeFactory { ctx, id, skills ->
                CoreAgentRuntime(ctx, id, skills, CorePromptBudget.standard(), provider)
            }
            val run = executor.submit {
                val method = AgentForegroundService::class.java.getDeclaredMethod("runRealAgent", CancellationToken::class.java,
                    String::class.java, List::class.java, String::class.java, String::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
                method.invoke(service, exactToken, "", emptyList<String>(), central, "", true, false, inputId)
            }
            if (beforeOwnerRegistration) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline && checkpoints.load(scope.executionSessionId, bot.id)!!.metadata
                    .getJSONObject(CentralContinuationRecord.FIELD).getString("dispatchState") != "STARTED") Thread.sleep(5)
                assertEquals("Claim must reach the real durable CAS while registration is blocked", "STARTED",
                    checkpoints.load(scope.executionSessionId, bot.id)!!.metadata
                        .getJSONObject(CentralContinuationRecord.FIELD).getString("dispatchState"))
                assertNull("No transient owner exists in the tested crash window", owners[continuationKey])
                assertFalse("Service is blocked in owner registration", run.isDone)
                assertEquals(0, calls.get())
            } else assertTrue("Claimed provider did not start", providerEntered.await(15, TimeUnit.SECONDS))
            val before = checkpoints.confirmDurable(scope.executionSessionId, bot.id, bot.scopeIdentity())!!
            val selectedEvidenceId = before.metadataJson().getJSONObject(CentralContinuationRecord.FIELD)
                .getJSONObject("selectedResult").getString("evidenceId")
            val retainedEvidence = storage.onDatabaseWorker {
                store.readCrewResultEvidence(scope.executionSessionId, selectedEvidenceId)!!.toJson().toString()
            }
            val access = object : CrewSessionAccess {
                override fun resolve(session: String) = session == scope.executionSessionId
                override fun board(session: String) = CrewBoard(WorkspaceStore.forCrewBoard(context, session))
                override fun manager(session: String): CrewManager? = if (resolve(session)) manager else null
                override fun missions(session: String): List<CrewMissionSnapshot>? = manager(session)?.missionSnapshots()
                override fun stopAll(session: String) = CrewStopActions.stopAll(null, manager(session))
                override fun resumeBot(session: String, botId: String) = throw AssertionError("No Resume during stop")
            }
            storage.onDatabaseWorker { access.stopAll(scope.executionSessionId) }
            if (beforeOwnerRegistration) {
                // Stop returned despite absent owner. Releasing registration must observe its denial fence.
                assertNull(owners[continuationKey])
                releaseReservation.countDown()
                reservation!!.get(10, TimeUnit.SECONDS)
                run.get(15, TimeUnit.SECONDS)
                assertTrue("Post-CAS stop must latch the exact token immediately after owner registration", exactToken.isCancelled)
                assertEquals(0, calls.get())
            } else assertTrue("Mission stop must synchronously latch the exact acknowledged owner", exactToken.isCancelled)
            assertEquals(CrewManager.Status.DONE, bot.status())
            replacement = ownerController.beginRun()!!
            assertFalse(replacement!!.isCancelled)
            storage.onDatabaseWorker { access.stopAll(scope.executionSessionId) }
            assertFalse("Repeated stale owner cancellation must not stop replacement", replacement!!.isCancelled)
            releaseProvider.countDown()
            run.get(15, TimeUnit.SECONDS)
            assertEquals(if (beforeOwnerRegistration) 0 else 1, calls.get())
            assertFalse(replacement!!.isCancelled)
            val after = checkpoints.confirmDurable(scope.executionSessionId, bot.id, bot.scopeIdentity())!!
            assertEquals(before.payloadSha256, after.payloadSha256)
            assertEquals(if (beforeOwnerRegistration) "STARTED" else "ACKNOWLEDGED",
                after.metadataJson().getJSONObject(CentralContinuationRecord.FIELD).getString("dispatchState"))
            storage.onDatabaseWorker {
                assertEquals(if (beforeOwnerRegistration) null else "incorporated", store.readConversationInputState(central, inputId))
                if (beforeOwnerRegistration) {
                    val input = store.readConversationInput(central, inputId)!!
                    assertFalse("STARTED uncertainty cannot be replayed by a replacement generation",
                        WorkspaceContinuationDelivery.claim(context, input, replacement!!))
                    assertFalse(replacement!!.isCancelled)
                    assertEquals(0, calls.get())
                    val generationCount = runBlocking {
                        val db = JarvysChatDatabaseProvider.getInstance(context)
                        JarvysChatDatabaseProvider.onQueryExecutor(db) { db.withChatReadTransaction {
                            db.chatSql.query("SELECT COUNT(*) FROM generation_run", emptyArray()).use { cursor ->
                                check(cursor.moveToFirst()); cursor.getInt(0)
                            }
                        } }
                    }
                    assertEquals("STOP before owner registration must prevent provider journal preparation", 0, generationCount)
                    assertEquals(after.payloadSha256,
                        checkpoints.confirmDurable(scope.executionSessionId, bot.id, bot.scopeIdentity())!!.payloadSha256)
                }
                assertEquals(retainedEvidence,
                    store.readCrewResultEvidence(scope.executionSessionId, selectedEvidenceId)!!.toJson().toString())
                assertFalse(store.listCrewResultEvidence(scope.executionSessionId, 20, null).isEmpty())
            }
        } finally {
            // Always release the empty reservation before closing/stopping managers or services.
            releaseReservation.countDown()
            releaseProvider.countDown()
            exactToken.completeRun(); replacement?.completeRun()
            executor.shutdownNow(); reservationExecutor.shutdownNow()
            executor.awaitTermination(15, TimeUnit.SECONDS)
            reservationExecutor.awaitTermination(15, TimeUnit.SECONDS)
            manager.close(); controller.destroy()
            context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        }
    }
}

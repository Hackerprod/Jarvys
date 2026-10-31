package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native callback authority, not a fixture that manufactures a trusted live event from persisted text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class,
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeContinuationLivePermitTest {
    private val storage = ChatTestStore(registerApplicationFilesDirectory = true)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    private fun settleReceipts() {
        val executor = WorkspaceResultDelivery::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(null) as ExecutorService
        val lock = WorkspaceResultDelivery::class.java.getDeclaredField("pendingLock")
            .apply { isAccessible = true }.get(null)
        val pending = WorkspaceResultDelivery::class.java.getDeclaredField("pending")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        val catalog = WorkspaceResultDelivery::class.java.getDeclaredField("catalogPending")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        do {
            executor.submit {}.get(15, TimeUnit.SECONDS)
            if (synchronized(lock) { pending.isEmpty() } && !catalog.get()) return
            Thread.sleep(1)
        } while (System.nanoTime() < deadline)
        fail("Native result receipt queue did not become idle")
    }
    private fun startedInputs(): List<String> = buildList {
        val shadow = Shadows.shadowOf(context as Application)
        while (true) {
            val intent = shadow.nextStartedService ?: break
            if (intent.action == AgentForegroundService.ACTION_REAL_AGENT)
                add(intent.getStringExtra("chat_user_message_id").orEmpty())
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun liveRegistry() = WorkspaceContinuationDelivery::class.java.getDeclaredField("liveResults")
        .apply { isAccessible = true }.get(null) as MutableMap<String, WorkspaceContinuationDelivery.LiveResultPermit>

    private inner class Fixture(private val failProvider: Boolean = false) : AutoCloseable {
        val ledger = storage.localRunStore(context.filesDir)
        val checkpoints = CrewCheckpointStore(context.filesDir)
        val calls = AtomicInteger()
        val scope: WorkspaceRecord
        val manager: CrewManager
        val profile = CrewProfile(CrewProfileRepository.newCustomId(), 1, "Planner",
            "General-purpose planning", "Prepare itinerary", emptyList(), emptyList(),
            CrewProfile.WorkspaceMode.LEGACY_CHAT)
        init {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            check((context as TreeNativeTestApplication).connectionAccess().isReady())
            CrewProfileRepository(context).create(profile, emptyList(), emptyList())
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(central, "chat")
            ProviderSettings(context).apply {
                setProvider(ProviderSettings.Provider.OPENROUTER); setOpenRouterModel("offline/live-permit")
            }
            check(context.getSharedPreferences("provider_context_metadata",0).edit()
                .putInt("model_offline/live-permit",32768)
                .putLong("fetched_offline/live-permit",System.currentTimeMillis()).commit())
            scope = WorkspaceRegistry(context).use { it.createWorkspace(UUID.randomUUID().toString(), "Live permit") }
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId,"workspace")
            val provider = object : ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,
                    sessionId:String,token:CancellationToken):ModelReply = error("Conversation boundary required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,
                    images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    calls.incrementAndGet()
                    if (failProvider) throw IllegalStateException("Controlled native provider failure")
                    return ModelReply("Retained itinerary evidence",emptyList())
                }
            }
            val runtime = CoreAgentRuntime(context,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager = runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,provider,scope.executionSessionId),null)
        }
        inner class Episode(val bot:CrewManager.Bot) {
            fun record() = checkpoints.load(scope.executionSessionId,bot.id)!!.metadata.getJSONObject(CentralContinuationRecord.FIELD)
            fun input(): ConversationInput {
                val id = ledger.readConversationMessageByStableId(central,record().getString("inputStableId"))!!.getString("messageId")
                return ledger.readConversationInput(central,id)!!
            }
            fun permit() = requireNotNull(liveRegistry()[record().getString("continuationKey")])
        }
        fun complete(): Episode {
            val id = UUID.randomUUID().toString()
            // Deliberate retained-v98 native producer: no P2 admission or synthetic Central authority.
            val bot = WorkspaceRegistry(context).use { registry ->
                val prepared = registry.prepareAssignment(scope.workspaceId, "continuation-v1:" + UUID.nameUUIDFromBytes("assignment-$id".toByteArray(Charsets.UTF_8)), "request-$id", "Plan a trip",
                    profile.id, "Prepare itinerary", emptyList(), "Planner", CrewMissionAccess.STANDARD)
                val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, CrewProcessIdentity.ID)
                val worker = manager.spawnForMissionDeferred(started.missionId, started.originalUserRequest,
                    started.role, started.missionText, started.tools, started.name, started.missionAccess, scope.title, started)
                val bound = registry.bindBot(scope.workspaceId, started.assignmentId, started.revision, worker.id)
                WorkspaceContinuationDelivery.initialize(context, bound, manager, worker)
                assertNull("Retained P1 fixture must remain unenrolled", WorkspaceTreeStore(registry).findAssignment(scope.workspaceId, bound.assignmentId))
                manager.startPreparedForMission(worker)
                WorkspaceMissionCards.delegated(context, scope, bound)
                worker
            }
            bot.awaitTermination()
            assertEquals(bot.error(),if (failProvider) CrewManager.Status.FAILED else CrewManager.Status.DONE,bot.status())
            settleReceipts()
            WorkspaceResultDelivery(context).reconcile(scope.workspaceId,CancellationToken.uncancellable())
            return Episode(bot).also {
                assertEquals("PREPARED",it.record().getString("dispatchState"))
                assertNotNull(it.permit())
            }
        }
        override fun close() {
            manager.close();settleReceipts()
            context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        }
    }


    @Test fun nativePauseOfHistoricalResultDoesNotPoisonLaterSiblingSelectionAndDispatch() = Fixture().use { f ->
        val a = f.complete()
        val evidence = a.bot.resultEvidence()!!
        val before = a.record()
        val referenceId = before.getJSONObject("selectedResult").getString("resultReferenceId")
        val notice = f.ledger.readWorkspaceResultNotice(central, referenceId)!!.toJson().toString()
        val card = f.ledger.readConversationMessageByStableId(central, "mission_result:$referenceId")!!.toString()
        val input = a.input()
        val control = WorkspaceRegistry(context).use { registry ->
            val assignment = registry.getAssignment(f.scope.workspaceId, a.bot.assignmentId())!!
            registry.nativeControls().submit(UUID.randomUUID().toString(), f.scope.workspaceId,
                assignment.assignmentId, a.bot.id, assignment.revision, WorkspaceControlType.PAUSE, "")
        }
        val receipt = WorkspaceCoordinator(context, { f.manager }, {}).executeNativeControl(
            control.requestId, CancellationToken.uncancellable())
        assertTrue(receipt.contains("completed"))
        settleReceipts()
        assertEquals("CANCELLED", a.record().getString("dispatchState"))
        WorkspaceRegistry(context).use { registry ->
            assertTrue(registry.getAssignment(f.scope.workspaceId, a.bot.assignmentId())!!.revision >
                before.getJSONObject("selectedResult").getLong("assignmentRevision"))
        }
        assertEquals(before.getJSONArray("consumedResults").toString(), a.record().getJSONArray("consumedResults").toString())
        val cancelledRecord = a.record().toString()
        // Deterministic poisoned-prefix check: A is the only evidence in this first full scan.
        WorkspaceResultDelivery(context).reconcile(f.scope.workspaceId, CancellationToken.uncancellable())
        assertEquals(listOf(evidence.evidenceId), f.ledger.listCrewResultEvidence(f.scope.executionSessionId, 20, null))
        startedInputs()
        val b = f.complete() // Actual native callback and same-workspace scheduler, not a synthetic mint.
        val dispatched = startedInputs()
        assertFalse(dispatched.contains(input.messageId))
        assertTrue(dispatched.contains(b.input().messageId))
        val token = CancellationToken.cancellable()
        try {
            assertFalse(WorkspaceContinuationDelivery.claim(context, a.input(), token))
            assertTrue(WorkspaceContinuationDelivery.claim(context, b.input(), token))
            assertFalse(WorkspaceContinuationDelivery.claim(context, b.input(), token))
            assertEquals("STARTED", b.record().getString("dispatchState"))
        } finally { WorkspaceContinuationDelivery.releaseClaim(b.input(), token); token.completeRun() }
        assertEquals(cancelledRecord, a.record().toString())
        assertEquals(evidence.toJson().toString(), f.ledger.readCrewResultEvidence(f.scope.executionSessionId, evidence.evidenceId)!!.toJson().toString())
        assertEquals(notice, f.ledger.readWorkspaceResultNotice(central, referenceId)!!.toJson().toString())
        assertEquals(card, f.ledger.readConversationMessageByStableId(central, "mission_result:$referenceId")!!.toString())
        assertEquals(input.content, a.input().content)
        assertNull(f.ledger.readConversationInputState(central, input.messageId))
        assertEquals(2, f.calls.get())
    }

    @Test fun pendingNativeControlOnOneEpisodeDoesNotBlockSiblingLiveResult() = Fixture().use { f ->
        val a = f.complete()
        WorkspaceRegistry(context).use { registry ->
            val assignment = registry.getAssignment(f.scope.workspaceId, a.bot.assignmentId())!!
            registry.nativeControls().submit(UUID.randomUUID().toString(), f.scope.workspaceId,
                assignment.assignmentId, a.bot.id, assignment.revision, WorkspaceControlType.PAUSE, "")
        }
        val retained = a.record().toString()
        // Pending control is expected admission ineligibility, not damaged result evidence.
        WorkspaceResultDelivery(context).reconcile(f.scope.workspaceId, CancellationToken.uncancellable())
        startedInputs()
        val b = f.complete()
        val dispatched = startedInputs()
        assertNull("Expected stale/control admission cannot poison workspace receipt status", WorkspaceResultDelivery.issue(f.scope.workspaceId))
        assertFalse(dispatched.contains(a.input().messageId))
        assertTrue(dispatched.contains(b.input().messageId))
        assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context, a.input()))
        val token = CancellationToken.cancellable()
        try {
            assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.claim(context, a.input(), token) }
            assertTrue(WorkspaceContinuationDelivery.claim(context, b.input(), token))
            assertFalse(WorkspaceContinuationDelivery.claim(context, b.input(), token))
        } finally { WorkspaceContinuationDelivery.releaseClaim(b.input(), token); token.completeRun() }
        assertEquals(retained, a.record().toString())
        assertEquals(2, f.ledger.listCrewResultEvidence(f.scope.executionSessionId, 20, null).size)
        assertEquals(2, f.calls.get())
    }

    @Test fun staleProducerPermitDoesNotBlockSiblingLiveResult() = Fixture().use { f ->
        val a = f.complete()
        val retained = a.record().toString()
        val original = a.bot.token
        startedInputs()
        try {
            a.bot.token = CancellationToken.crewChild()
            val b = f.complete()
            val dispatched = startedInputs()
        assertNull("Expected stale/control admission cannot poison workspace receipt status", WorkspaceResultDelivery.issue(f.scope.workspaceId))
            assertFalse(dispatched.contains(a.input().messageId))
            assertTrue(dispatched.contains(b.input().messageId))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context, a.input()))
            val token = CancellationToken.cancellable()
            try {
                assertTrue(WorkspaceContinuationDelivery.claim(context, b.input(), token))
                assertFalse(WorkspaceContinuationDelivery.claim(context, b.input(), token))
            } finally { WorkspaceContinuationDelivery.releaseClaim(b.input(), token); token.completeRun() }
            assertEquals(retained, a.record().toString())
            assertEquals(2, f.calls.get())
        } finally { a.bot.token = original }
    }

    @Test fun expectedIneligibilityIsolationDoesNotSwallowCorruptEvidenceOrCancellation() = Fixture().use { f ->
        val a = f.complete()
        val evidence = a.bot.resultEvidence()!!
        WorkspaceContinuationDelivery.cancel(context, f.scope.workspaceId, a.bot.id)
        assertEquals("CANCELLED", a.record().getString("dispatchState"))
        val retained = a.record().toString()
        fun setHash(hash: String) = kotlinx.coroutines.runBlocking {
            val db = com.jarvys.agent.chatdb.JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction { db.chatSql.execSQL(
                "UPDATE crew_result_evidence SET content_sha256=? WHERE conversation_id=? AND evidence_id=?",
                arrayOf(hash, f.scope.executionSessionId, evidence.evidenceId)) }
        }
        startedInputs()
        setHash("0".repeat(64))
        try {
            assertThrows(RuntimeException::class.java) {
                WorkspaceResultDelivery(context).reconcile(f.scope.workspaceId, CancellationToken.uncancellable())
            }
            assertEquals(retained, a.record().toString())
            assertTrue(startedInputs().isEmpty())
        } finally { setHash(evidence.contentSha256) }
        val cancelled = CancellationToken.cancellable().also { it.cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            WorkspaceResultDelivery(context).reconcile(f.scope.workspaceId, cancelled)
        }
        assertEquals(evidence.toJson().toString(), f.ledger.readCrewResultEvidence(f.scope.executionSessionId, evidence.evidenceId)!!.toJson().toString())
        assertEquals(retained, a.record().toString())
        assertEquals(1, f.calls.get())
    }

    @Test fun nativeFailedResultRetainsExactPermitDespiteRequiringExplicitWorkerResume() = Fixture(failProvider = true).use { f ->
        val episode = f.complete()
        assertEquals("FAILED", episode.bot.resultEvidence()!!.reportedStatus)
        assertTrue(episode.bot.requiresExplicitResume())
        assertFalse(episode.bot.token.isCancellationRequested)
        assertTrue(WorkspaceContinuationDelivery.isPendingDispatchEligible(context, episode.input()))
        val token = CancellationToken.cancellable()
        try {
            assertTrue(WorkspaceContinuationDelivery.claim(context, episode.input(), token))
            assertEquals("STARTED", episode.record().getString("dispatchState"))
            assertFalse(liveRegistry().containsKey(episode.record().getString("continuationKey")))
            assertFalse(WorkspaceContinuationDelivery.claim(context, episode.input(), token))
            assertEquals(1, f.calls.get())
        } finally { WorkspaceContinuationDelivery.releaseClaim(episode.input(), token); token.completeRun() }
    }

    @Test fun nativeCallbackDuplicateKeepsOnePermitAndConsumedClaimCannotRemint() = Fixture().use { f ->
        val episode = f.complete()
        val permit = episode.permit()
        val evidence = episode.bot.resultEvidence()!!
        WorkspaceContinuationDelivery.recordLiveResult(context,f.manager,evidence)
        assertSame(permit,episode.permit())
        val token = CancellationToken.cancellable()
        try {
            assertTrue(WorkspaceContinuationDelivery.claim(context,episode.input(),token))
            assertFalse(liveRegistry().containsKey(episode.record().getString("continuationKey")))
            WorkspaceContinuationDelivery.recordLiveResult(context,f.manager,evidence)
            assertFalse(liveRegistry().containsKey(episode.record().getString("continuationKey")))
            assertFalse(WorkspaceContinuationDelivery.claim(context,episode.input(),token))
            assertEquals("STARTED",episode.record().getString("dispatchState"))
            assertEquals(1,f.calls.get())
        } finally { WorkspaceContinuationDelivery.releaseClaim(episode.input(),token);token.completeRun() }
    }

    @Test fun historicalEpisodeCannotBorrowNativeSiblingPermitAndCatalogCannotDispatchCachedPermit() = Fixture().use { f ->
        val historical = f.complete()
        val oldPermit = historical.permit()
        // Simulated loss of transient authority: persisted PREPARED/checkpoint/input remain unchanged.
        liveRegistry().remove(historical.record().getString("continuationKey"))
        startedInputs()
        val live = f.complete()
        val inputsFromNewResult = startedInputs()
        assertFalse(inputsFromNewResult.contains(historical.input().messageId))
        assertTrue(inputsFromNewResult.contains(live.input().messageId))
        assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,historical.input()))
        val token = CancellationToken.cancellable()
        try {
            assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.claim(context,historical.input(),token) }
            WorkspaceContinuationDelivery.dispatchLiveResult(context,oldPermit,token)
            WorkspaceContinuationDelivery.dispatchLiveResult(context,object : WorkspaceContinuationDelivery.LiveResultPermit {},token)
            assertTrue(startedInputs().isEmpty())
            WorkspaceResultDelivery.scheduleCatalog(context)
            settleReceipts();settleReceipts()
            assertTrue("Catalog cannot use even a cached valid live permit",startedInputs().isEmpty())
            assertEquals("PREPARED",historical.record().getString("dispatchState"))
            assertEquals("PREPARED",live.record().getString("dispatchState"))
            assertEquals(2,f.calls.get())
        } finally { token.completeRun() }
    }

    @Test fun staleProcessStorageTokenAndCycleDenyEnqueueAndClaimWithoutChangingDurableState() = Fixture().use { f ->
        val episode = f.complete()
        val permit = episode.permit()
        val before = episode.record().toString()
        startedInputs()
        fun denied() {
            val token = CancellationToken.cancellable()
            try {
                assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,episode.input()))
                assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.dispatchLiveResult(context,permit,token) }
                assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.claim(context,episode.input(),token) }
                assertEquals(before,episode.record().toString())
                assertTrue(startedInputs().isEmpty())
                assertEquals(1,f.calls.get())
            } finally { token.completeRun() }
        }
        // Test-only mutation of opaque fields simulates an old process/storage handle, not a mint API.
        for (fieldName in listOf("process","storage")) {
            val field = permit.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            val original = field.get(permit)
            try { field.set(permit,"obsolete-$fieldName");denied() } finally { field.set(permit,original) }
        }
        val originalToken = episode.bot.token
        try { episode.bot.token = CancellationToken.crewChild();denied() } finally { episode.bot.token = originalToken }
        val cycleField = CrewManager.Bot::class.java.getDeclaredField("cycleMetadata").apply { isAccessible = true }
        val originalCycle = cycleField.get(episode.bot)
        val constructor = originalCycle.javaClass.declaredConstructors.single().apply { isAccessible = true }
        try { cycleField.set(episode.bot,constructor.newInstance(UUID.randomUUID().toString(),null));denied() }
        finally { cycleField.set(episode.bot,originalCycle) }
        assertTrue(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,episode.input()))
    }

    @Test fun replacementBotCannotAdoptOriginalProducersLivePermit() = Fixture().use { f ->
        val original = f.complete();val replacement = f.complete()
        val permit = original.permit();val before = original.record().toString()
        startedInputs()
        @Suppress("UNCHECKED_CAST")
        val bots = CrewManager::class.java.getDeclaredField("bots").apply { isAccessible = true }
            .get(f.manager) as MutableMap<String,CrewManager.Bot>
        val token = CancellationToken.cancellable()
        try {
            bots[original.bot.id] = replacement.bot
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,original.input()))
            assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.dispatchLiveResult(context,permit,token) }
            assertThrows(RuntimeException::class.java) { WorkspaceContinuationDelivery.claim(context,original.input(),token) }
            assertEquals(before,original.record().toString())
            assertTrue(startedInputs().isEmpty())
            assertEquals(2,f.calls.get())
        } finally { bots[original.bot.id] = original.bot;token.completeRun() }
    }
}

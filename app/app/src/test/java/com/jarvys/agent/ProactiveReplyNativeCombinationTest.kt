package com.jarvys.agent

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.CentralSubmissionSnapshot
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.chatdb.testChatSql
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.providers.ChatCompletionsConfig
import com.jarvys.agent.workspaces.CentralResumeRecord
import com.jarvys.agent.workspaces.TreeNode
import com.jarvys.agent.workspaces.TreeNodeState
import com.jarvys.agent.workspaces.TreeRoot
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.workspaces.WorkspaceTreeStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** UNRUN: real native owners, service arbitration and sealed in-memory HTTP transport, never live network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveReplyNativeCombinationTest {
    private val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule()).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "native-p4-combined-thread"
    private val option = ProactiveSuggestedReply("Review combined notice", "Review exactly this independent threaded notice")
    private val sourceText = "Canonical combined native notice"
    private val uiExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val viewModels = androidx.lifecycle.ViewModelStore()
    private var serviceController: org.robolectric.android.controller.ServiceController<AgentForegroundService>? = null
    private val service get() = requireNotNull(serviceController).get()
    private val calls = AtomicInteger()
    private val runtimeFailure = AtomicReference<Throwable>()
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val releaseRace = CountDownLatch(1)
    private val firstEntered = CountDownLatch(1)
    private val secondEntered = CountDownLatch(1)
    private val thirdEntered = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private val releaseSecond = CountDownLatch(1)
    private val releaseThird = CountDownLatch(1)
    private val firstTransportReturned = CountDownLatch(1)
    private val yieldReached = CountDownLatch(1)
    private val releaseYield = CountDownLatch(1)
    private val yielded = AtomicReference<MainChatTranscriptStore.ConfirmedCentralYield>()
    private var yieldMode = false
    private var startId = 0
    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private val store get() = LocalRunStore(context)
    private val sql get() = JarvysChatDatabaseProvider.getInstance(context).testChatSql

    /** No connect call opens a socket. Interrupt-resistant waits model only remote inference transport. */
    private inner class Socket(private val index: Int) : HttpURLConnection(URL("http://127.0.0.1/p4-native-fixture")) {
        private val request = ByteArrayOutputStream()
        override fun getOutputStream() = request
        override fun getResponseCode(): Int {
            requests += request.toString("UTF-8")
            when (index) {
                1 -> { firstEntered.countDown(); awaitRemote(releaseFirst); firstTransportReturned.countDown() }
                2 -> { check(yieldMode); secondEntered.countDown(); awaitRemote(releaseSecond) }
                3 -> { check(yieldMode); thirdEntered.countDown(); awaitRemote(releaseThird) }
                else -> error("Unexpected synthetic provider request $index")
            }
            return 200
        }
        override fun getInputStream() = ByteArrayInputStream((when {
            !yieldMode -> """{"choices":[{"message":{"role":"assistant","content":"One native raced reply completed"}}]}"""
            index == 1 -> """{"choices":[{"message":{"role":"assistant","content":"STALE COMBINED PROPOSAL","tool_calls":[{"id":"stale-combined-effect","type":"function","function":{"name":"crew_spawn","arguments":"{}"}}]}}]}"""
            index == 2 -> """{"choices":[{"message":{"role":"assistant","content":"Independent threaded reply completed"}}]}"""
            else -> """{"choices":[{"message":{"role":"assistant","content":"Original exact safe checkpoint resumed"}}]}"""
        }).toByteArray())
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy() = false
    }

    private fun awaitRemote(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { /* Synthetic remote keeps working. */ }
    }
    private fun await(latch: CountDownLatch, description: String) {
        assertTrue(description, latch.await(30, TimeUnit.SECONDS))
        runtimeFailure.get()?.let { throw AssertionError("Actual native runtime failed", it) }
    }
    private fun receiverExecutor(): ExecutorService = Class.forName("com.jarvys.agent.proactive.ProactiveReplyReceiverExecutor")
        .getDeclaredField("executor").apply { isAccessible = true }.get(null) as ExecutorService
    private fun awaitReceiver() = receiverExecutor().submit {}.get(30, TimeUnit.SECONDS)
    private fun drainStarts(): List<Intent> = buildList {
        val app = Shadows.shadowOf(context as Application)
        while (true) add(app.nextStartedService ?: break)
    }
    private fun start(intent: Intent) = service.onStartCommand(Intent(intent), 0, ++startId)
    private fun assertCentralStarts(intents: List<Intent>, expected: Int) {
        assertEquals(expected, intents.size)
        assertTrue(intents.all { it.action == "com.jarvys.agent.CENTRAL_SUBMISSION" })
    }
    private fun submissions() = db { store.readCentralSubmissions(session) }
    private fun rows() = db { store.readConversationMessages(session) }
    private fun threadedUsers() = db { store.readProactiveThreadMessages(session, thread).filter { it.role == "user" } }
    private fun scalar(statement: String, vararg args: String): Long = db {
        sql.query(statement, args).use { check(it.moveToFirst()); it.getLong(0) }
    }
    private fun nextSeq() = scalar("SELECT next_seq FROM conversations WHERE id=?", session)
    private fun messageCount() = scalar("SELECT message_count FROM conversations WHERE id=?", session)
    private fun notice(): String = db {
        store.appendProactiveAssistantMessageIfAbsent(session, "combined-native-decision", sourceText, listOf(option), thread)
    }
    private fun notificationOption(source: String): Intent {
        db { ProactiveInteractionDispatcher.refreshNotification(context, thread, source) }
        val notification = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single {
            it.actions?.any { action -> !action.remoteInputs.isNullOrEmpty() } == true
        }
        return Intent(Shadows.shadowOf(notification.actions.single { it.remoteInputs.isNullOrEmpty() }.actionIntent).savedIntent)
    }
    private fun root(user: String): TreeRoot = db { WorkspaceRegistry(context).use { registry ->
        val id = registry.treeRead { database -> database.rawQuery(
            "SELECT root_id FROM bot_tree_root WHERE origin_user_message_id=?", arrayOf(user)).use {
            check(it.moveToFirst()); it.getString(0).also { _ -> check(!it.moveToNext()) }
        } }
        requireNotNull(WorkspaceTreeStore(registry).getRoot(id))
    } }
    private fun rootNode(root: TreeRoot): TreeNode = db { WorkspaceRegistry(context).use {
        requireNotNull(WorkspaceTreeStore(it).getNode(root.rootId, root.rootId))
    } }
    private fun suspension(root: TreeRoot): CentralResumeRecord = db { WorkspaceRegistry(context).use { registry ->
        val id = registry.treeRead { database -> database.rawQuery(
            "SELECT resume_id FROM bot_tree_central_resume WHERE root_id=? AND cause_kind='SUSPENSION'", arrayOf(root.rootId)).use {
            check(it.moveToFirst()); it.getString(0).also { _ -> check(!it.moveToNext()) }
        } }
        requireNotNull(WorkspaceTreeStore(registry).getCentralResume(id))
    } }
    private fun assertIdentity(before: CentralSubmissionSnapshot, after: CentralSubmissionSnapshot) {
        assertEquals(before.submissionId, after.submissionId)
        assertEquals(before.userMessageId, after.userMessageId)
        assertEquals(before.userSeq, after.userSeq)
        assertEquals(before.sourceSha256, after.sourceSha256)
        assertEquals(before.storageGeneration, after.storageGeneration)
        assertEquals(before.originalAdmissionProcess, after.originalAdmissionProcess)
    }

    @Before fun setUp() {
        check(ProactiveReplyHandoffPolicy.admissionEnabled() && CentralAvailabilityPolicy.admissionEnabled()) {
            "Requires actual D3 activation; no native gate override"
        }
        db {
            FreshStorageBootstrap.ensure(context)
            val connections = (context as TreeNativeTestApplication).connectionAccess()
            connections.initializeAsync().get(30, TimeUnit.SECONDS)
            check(connections.isReady())
        }
        AgentRunUiState.resetSession(session)
        AgentRunUiState.showSession(session)
        UserDecisionUiAvailability.setChatVisible(true)
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        drainStarts()
        val settings = ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENROUTER); setOpenRouterModel("offline/p4-native-combination")
        }
        check(context.getSharedPreferences("provider_context_metadata", 0).edit()
            .putInt("model_offline/p4-native-combination", 32768)
            .putLong("fetched_offline/p4-native-combination", System.currentTimeMillis()).commit())
        val secrets = SecretStore(context.getSharedPreferences("p4-combination-fixture", 0)).apply {
            saveOpenRouterKey("synthetic-test-only")
        }
        val provider = OpenRouterClient(secrets, settings, ChatCompletionsConfig.openRouterDefault(),
            ProviderHttp.ConnectionFactory { Socket(calls.incrementAndGet()) })
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { app, id, skills ->
            CoreAgentRuntime(app, id, skills, CorePromptBudget.standard(), provider)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            try {
                runtime.runMainChat(goal, history, attachments, token, progress).also { result ->
                    if (result.isYielded) {
                        check(yieldMode && yielded.compareAndSet(null, result.yieldReceipt()))
                        yieldReached.countDown()
                        // Hold the actual no-effect yield result before the service parks its existing owner.
                        check(releaseYield.await(30, TimeUnit.SECONDS))
                    }
                }
            } catch (failure: Throwable) { runtimeFailure.set(failure); throw failure }
        }
    }

    @After fun tearDown() {
        releaseRace.countDown(); releaseYield.countDown()
        releaseFirst.countDown(); releaseSecond.countDown(); releaseThird.countDown()
        try {
            awaitReceiver()
            uiExecutor.submit {}.get(30, TimeUnit.SECONDS)
            sendExecutor.submit {}.get(30, TimeUnit.SECONDS)
        } finally {
            try {
                serviceController?.let { controller ->
                    try {
                        AgentForegroundService.stopRun(session)
                        controller.get().awaitStopTasksForTesting().get(30, TimeUnit.SECONDS)
                        controller.get().awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
                    } finally { controller.destroy() }
                }
            } finally {
                viewModels.clear()
                uiExecutor.shutdownNow(); sendExecutor.shutdownNow()
                ProactiveReplyAdmission.cancelCapturedInteractions(session)
                UserDecisionUiAvailability.setChatVisible(false)
                AgentRunUiState.resetSession(session)
                drainStarts()
            }
        }
    }

    @Test fun simultaneousRenderedChipAndActualReceiverConserveOneAcceptanceAndPhysicalClaim() {
        val source = notice()
        val intent = notificationOption(source)
        val event = db { store.readRecentConversationTimeline(session) }.single { it.messageId == source }
        AgentRunUiState.restoreSession(session, listOf(event))
        val beforeSeq = nextSeq()
        val beforeCount = messageCount()
        val receiverBlocked = CountDownLatch(1)
        val uiCaptured = CountDownLatch(1)
        val nativeUi = AtomicReference<ProactiveReplyAdmission.NativeInteraction>()
        val uiAttempt = AtomicReference<Future<Result<Boolean>>>()
        val blocker = receiverExecutor().submit { receiverBlocked.countDown(); check(releaseRace.await(30, TimeUnit.SECONDS)) }
        await(receiverBlocked, "Receiver lane held before its real delivery")
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true }, ApprovalGate.INSTANCE)
        try {
            compose.setContent { MaterialTheme {
                AgentRunScreen(conversationKey = session, events = listOf(event), isRunning = false,
                    emptyReport = null, connectorRegistry = registry, onOpenPreview = {}, onOpenSkillFile = {},
                    onProactiveSuggestedReply = { selected, index ->
                        assertSame(event, selected)
                        val interaction = requireNotNull(ProactiveReplyAdmission.captureDisplayedOption(compose.activity, selected, index))
                        nativeUi.set(interaction)
                        uiAttempt.set(uiExecutor.submit<Result<Boolean>> {
                            interaction.use {
                                uiCaptured.countDown()
                                check(releaseRace.await(30, TimeUnit.SECONDS))
                                runCatching { ProactiveInteractionDispatcher.submitNativeReply(context, it) }
                            }
                        })
                    })
            } }
            compose.onNodeWithText(option.label).performClick()
            await(uiCaptured, "Actual rendered callback owns its live native UI selection")
            requireNotNull(nativeUi.get()).requireCurrent()
            ProactiveActionReceiver().onReceive(context, Intent(intent)) // Captures genuine receiver owner before queueing.
            assertTrue(submissions().isEmpty())
            releaseRace.countDown() // Both genuine owners are now eligible, with no prescribed winner.
            blocker.get(30, TimeUnit.SECONDS)
            val uiResult = requireNotNull(uiAttempt.get()).get(30, TimeUnit.SECONDS)
            awaitReceiver()
            uiResult.exceptionOrNull()?.let {
                assertTrue("Only a stale displayed selection may lose; no permit transfers to it", it is IllegalStateException)
            }
            val accepted = submissions().single()
            val user = threadedUsers().single()
            assertEquals("PREPARED", accepted.state)
            assertEquals(user.messageId, accepted.userMessageId)
            assertEquals(option.text, user.text)
            assertEquals(source, user.proactiveReplyToMessageId)
            assertEquals(thread, user.proactiveThreadKey)
            assertEquals(beforeSeq + 2, nextSeq()) // Exactly USER plus one option marker.
            assertEquals(beforeCount + 1, messageCount())
            assertEquals(1L, scalar("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='proactive_reply_used' AND item_key=?", session, source))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?", session, user.messageId, ProactiveReplyAcceptance.PART_KIND))
            val permit = requireNotNull(CentralSubmissionAdmission.find(session, accepted.submissionId))
            permit.requirePrepared()
            val starts = drainStarts()
            assertCentralStarts(starts, 1)
            ProactiveActionReceiver().onReceive(context, Intent(intent))
            awaitReceiver()
            assertEquals(listOf(accepted), submissions())
            assertSame(permit, CentralSubmissionAdmission.find(session, accepted.submissionId))
            assertCentralStarts(drainStarts(), 0)
            start(starts.single())
            await(firstEntered, "The single real native claim enters sealed synthetic inference")
            val claimed = submissions().single()
            assertEquals("STARTED", claimed.state)
            assertEquals(accepted.revision + 1, claimed.revision)
            assertNotNull(claimed.claimRunId)
            assertNotNull(claimed.claimTokenGeneration)
            start(starts.single())
            assertEquals(1, calls.get())
            releaseFirst.countDown()
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            runtimeFailure.get()?.let { throw AssertionError("Actual native runtime failed", it) }
            val completed = submissions().single()
            assertEquals("COMPLETED", completed.state)
            assertIdentity(accepted, completed)
            assertEquals(claimed.claimRunId, completed.claimRunId)
            assertEquals(1, calls.get())
            assertEquals(1, threadedUsers().size)
            val completedRoot = root(user.messageId)
            assertEquals(user.messageId, completedRoot.origin.originUserMessageId)
            assertEquals("COMPLETED", completedRoot.closeReason)
            assertEquals(1L, completedRoot.executions)
            assertEquals(0L, completedRoot.requests)
            assertEquals(0L, completedRoot.wakes)
            assertFalse(AgentForegroundService.hasActiveRun(session))
        } finally {
            releaseRace.countDown(); releaseFirst.countDown()
            nativeUi.get()?.close()
        }
    }

    @Test fun threadedReplyQueuesBehindRealPredecessorAndResumesOnlyItsProvenSafeCheckpoint() {
        yieldMode = true
        val drafts = AttachmentDraftViewModel(context as Application, submissionExecutor = sendExecutor)
            .apply { switchSession(session) }
        viewModels.put("real-predecessor-native-send", drafts)
        try {
            assertTrue(drafts.submit(session, "Original independent native request", arrayListOf()))
            sendExecutor.submit {}.get(30, TimeUnit.SECONDS)
            val originalPrepared = submissions().single()
            val originalStart = drainStarts()
            assertCentralStarts(originalStart, 1)
            start(originalStart.single())
            await(firstEntered, "Real predecessor owns a sealed provider attempt")
            val originalStarted = submissions().single()
            assertEquals("STARTED", originalStarted.state)
            assertTrue(AgentForegroundService.isCentralSourceActive(session, originalStarted.userMessageId))
            val originalRoot = root(originalStarted.userMessageId)
            val originalNode = rootNode(originalRoot)
            assertEquals(TreeNodeState.RUNNING, originalNode.state)
            assertEquals(1L, originalRoot.executions)

            val source = notice()
            val receiverIntent = notificationOption(source)
            ProactiveActionReceiver().onReceive(context, Intent(receiverIntent))
            awaitReceiver()
            await(yieldReached, "Actual provider boundary confirmed a no-effect yield for the independent reply")
            val accepted = submissions().single { it.userMessageId != originalStarted.userMessageId }
            assertEquals("PREPARED", accepted.state)
            assertEquals(originalStarted, submissions().single { it.userMessageId == originalStarted.userMessageId })
            assertEquals(1, calls.get())
            assertEquals(1L, releaseFirst.count) // Superseded remote attempt is still physically held.
            val receipt = requireNotNull(yielded.get())
            assertEquals(originalStarted.userMessageId, receipt.ownerMessageId())
            assertEquals(originalStarted.userMessageId, receipt.finalOwnerMessageId())
            assertEquals(ConversationInput.Origin.USER, receipt.finalOwnerOrigin())
            assertEquals(0, receipt.incomingOwners().length())
            assertEquals(originalRoot.rootId, receipt.rootId())
            assertEquals(originalNode.cycleId, receipt.oldCycleId())
            assertEquals(originalStarted.claimRunId, receipt.runId())
            assertEquals(accepted.submissionId, receipt.request().submissionId)
            assertEquals(accepted.userMessageId, receipt.request().userMessageId)
            assertFalse(receipt.retainedCheckpoint().appliedIncomingIds.contains(accepted.userMessageId))
            assertTrue(receipt.retainedCheckpoint().toolLifecycle.isEmpty())
            assertTrue(receipt.retainedCheckpoint().completedToolResults.isEmpty())
            assertFalse(receipt.retainedCheckpoint().transcript.any { it.durableMessageId == accepted.userMessageId || it.content.contains(option.text) })
            assertEquals(option.text, threadedUsers().single().text)
            assertEquals(source, threadedUsers().single().proactiveReplyToMessageId)
            val replyStarts = drainStarts()
            assertCentralStarts(replyStarts, 1)
            start(replyStarts.single())
            start(replyStarts.single())
            assertEquals("Queued while the exact predecessor physical owner is held", "PREPARED",
                submissions().single { it.userMessageId == accepted.userMessageId }.state)
            assertEquals(1, calls.get())

            releaseYield.countDown()
            await(secondEntered, "The independent typed reply obtains its own real claim after native park")
            val replyStarted = submissions().single { it.userMessageId == accepted.userMessageId }
            assertEquals("STARTED", replyStarted.state)
            assertEquals(accepted.revision + 1, replyStarted.revision)
            assertNotEquals(originalStarted.claimRunId, replyStarted.claimRunId)
            assertTrue(AgentForegroundService.isCentralSourceActive(session, accepted.userMessageId))
            assertFalse(AgentForegroundService.isCentralSourceActive(session, originalStarted.userMessageId))
            val replyRoot = root(accepted.userMessageId)
            assertNotEquals(originalRoot.rootId, replyRoot.rootId)
            assertEquals(accepted.userMessageId, replyRoot.origin.originUserMessageId)
            assertEquals(1L, replyRoot.executions)
            val parked = suspension(originalRoot)
            assertEquals("PREPARED", parked.state)
            assertEquals(receipt.certificate(), parked.certificate)
            assertEquals(originalNode.cycleId, parked.parentCycle)
            assertNull(parked.successorCycle)
            assertEquals(TreeNodeState.WAITING, rootNode(originalRoot).state)
            assertEquals(originalRoot.executions, root(originalStarted.userMessageId).executions)
            assertEquals(originalRoot.origin, root(originalStarted.userMessageId).origin)

            releaseSecond.countDown()
            await(thirdEntered, "Only the original proven safe checkpoint resumes, after the reply completes")
            val resumed = suspension(originalRoot)
            assertEquals("INCORPORATED", resumed.state)
            assertEquals(parked.resumeId, resumed.resumeId)
            assertEquals(parked.sourceDigest, resumed.sourceDigest)
            assertEquals(parked.certificate, resumed.certificate)
            assertNotNull(resumed.bindingReceipt)
            assertNotNull(resumed.incorporationReceipt)
            assertNotEquals(originalNode.cycleId, resumed.successorCycle)
            assertEquals(resumed.successorCycle, rootNode(originalRoot).cycleId)
            assertNull(rootNode(originalRoot).controlIntent)
            assertEquals(originalRoot.executions + 1, root(originalStarted.userMessageId).executions)
            assertEquals("COMPLETED", submissions().single { it.userMessageId == accepted.userMessageId }.state)
            assertTrue(AgentForegroundService.isCentralSourceActive(session, originalStarted.userMessageId))
            assertEquals(3, calls.get())
            assertEquals(1L, releaseFirst.count)
            assertFalse(requests[0].contains(option.text))
            assertTrue(requests[1].contains(option.text))
            assertFalse("Reply never became predecessor steering", requests[2].contains(option.text))
            releaseThird.countDown()
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            runtimeFailure.get()?.let { throw AssertionError("Actual native runtime failed", it) }
            val completed = submissions()
            assertEquals(2, completed.size)
            assertTrue(completed.all { it.state == "COMPLETED" })
            assertIdentity(originalPrepared, completed.single { it.userMessageId == originalPrepared.userMessageId })
            assertIdentity(accepted, completed.single { it.userMessageId == accepted.userMessageId })
            val originalAfter = root(originalStarted.userMessageId)
            val replyAfter = root(accepted.userMessageId)
            assertEquals(originalRoot.origin, originalAfter.origin)
            assertEquals(originalRoot.sourceReceipt, originalAfter.sourceReceipt)
            assertEquals(originalRoot.windowEpoch, originalAfter.windowEpoch)
            assertEquals(originalRoot.requests, originalAfter.requests)
            assertEquals(originalRoot.wakes, originalAfter.wakes)
            assertEquals(originalRoot.executions + 1, originalAfter.executions)
            assertEquals(1L, replyAfter.executions)
            assertEquals(0L, replyAfter.requests)
            assertEquals(0L, replyAfter.wakes)
            listOf(originalAfter, replyAfter).forEach {
                assertEquals("COMPLETED", it.closeReason); assertTrue(it.admissionsClosed); assertTrue(it.wakesClosed)
            }
            val retainedNotice = db { requireNotNull(store.readStoredMessage(session, source)) }
            assertEquals("assistant", retainedNotice.role)
            assertEquals("COMPLETED", retainedNotice.status)
            assertNull("Standalone notice never inherits either USER owner", retainedNotice.ownerUserMessageId)
            val answers = rows().filter { it.optString("role") == "assistant" && it.optString("status") == "COMPLETED" && it.optString("userMessageId").isNotBlank() }
            assertEquals(2, answers.size)
            assertEquals(setOf(originalStarted.userMessageId, accepted.userMessageId), answers.map { it.getString("userMessageId") }.toSet())
            assertTrue(answers.any { it.optString("content") == "Independent threaded reply completed" && it.optString("userMessageId") == accepted.userMessageId })
            assertTrue(answers.any { it.optString("content") == "Original exact safe checkpoint resumed" && it.optString("userMessageId") == originalStarted.userMessageId })
            assertEquals(1, threadedUsers().size)
            assertEquals(2, rows().count { it.optString("role") == "user" })
            assertFalse(AgentForegroundService.hasActiveRun(session))
            ProactiveActionReceiver().onReceive(context, Intent(receiverIntent))
            awaitReceiver()
            assertCentralStarts(drainStarts(), 0)
            start(originalStart.single()); start(replyStarts.single())
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            assertEquals(completed, submissions())
            assertEquals(originalAfter, root(originalStarted.userMessageId))
            assertEquals(replyAfter, root(accepted.userMessageId))
            assertEquals(3, calls.get())
            releaseFirst.countDown()
            await(firstTransportReturned, "Superseded synthetic transport returns only after both native turns completed")
            assertFalse(rows().any { it.optString("content").contains("STALE COMBINED PROPOSAL") })
            assertFalse(db { store.readModelTranscriptRows(session) }.any { it.toString().contains("stale-combined-effect") })
        } finally {
            releaseYield.countDown(); releaseFirst.countDown(); releaseSecond.countDown(); releaseThird.countDown()
        }
    }
}

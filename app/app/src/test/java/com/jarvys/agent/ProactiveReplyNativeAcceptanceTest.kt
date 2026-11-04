package com.jarvys.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.testChatSql
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.proactive.ProactiveReplyNotificationIdentity
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Genuine receiver ownership, Room and existing native STOP/retry boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [ArtifactOsShadow::class, ProactiveReplyNativeAcceptanceTest.FailingNotificationManagerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveReplyNativeAcceptanceTest {
    private val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "native-p4a-acceptance"
    private val choices = listOf(ProactiveSuggestedReply("Review", "Review the notice"), ProactiveSuggestedReply("Explain", "Explain the notice"))
    private var serviceController: org.robolectric.android.controller.ServiceController<AgentForegroundService>? = null
    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private val store get() = LocalRunStore(context)
    private val sql get() = JarvysChatDatabaseProvider.getInstance(context).testChatSql
    private val runtimeFailure = AtomicReference<Throwable>()
    private val runtimePhase = AtomicReference("before_runtime_constructor")

    @Before fun setUp() {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Native P4A acceptance requires completed D3 activation, not a test override" }
        db {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get(30, TimeUnit.SECONDS)
        }
        ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENROUTER)
            setOpenRouterModel("offline/p4-native-acceptance")
        }
        check(context.getSharedPreferences("provider_context_metadata", 0).edit()
            .putInt("model_offline/p4-native-acceptance", 32768)
            .putLong("fetched_offline/p4-native-acceptance", System.currentTimeMillis()).commit())
        FailingNotificationManagerShadow.resetPublication()
        AgentRunUiState.resetSession(session)
        AgentRunUiState.showSession(session)
        UserDecisionUiAvailability.setChatVisible(true)
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        drainServiceIntents()
    }

    @After fun tearDown() {
        FailingNotificationManagerShadow.resetPublication()
        awaitReceiverCompletion()
        serviceController?.let { controller ->
            AgentForegroundService.stopRun(session)
            controller.get().awaitStopTasksForTesting().get(30, TimeUnit.SECONDS)
            controller.destroy()
        }
        ProactiveReplyAdmission.cancelCapturedInteractions(session)
        UserDecisionUiAvailability.setChatVisible(false)
        AgentRunUiState.resetSession(session)
        drainServiceIntents()
    }

    private fun notice(suffix: String = "one"): String = db {
        store.appendProactiveAssistantMessageIfAbsent(session, "native-p4a-$suffix", "Synthetic native notice $suffix", choices, thread)
    }

    private fun posted(source: String): Notification {
        db { ProactiveInteractionDispatcher.refreshNotification(context, thread, source) }
        return Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single {
            it.actions?.any { action -> action.remoteInputs?.isNotEmpty() == true } == true
        }
    }

    private fun option(notification: Notification, index: Int = 0): Intent =
        Intent(Shadows.shadowOf(notification.actions.filter { it.remoteInputs.isNullOrEmpty() }[index].actionIntent).savedIntent)

    private fun inline(notification: Notification, text: String): Intent {
        val action = notification.actions.single { !it.remoteInputs.isNullOrEmpty() }
        val intent = Intent(Shadows.shadowOf(action.actionIntent).savedIntent)
        val input = RemoteInput.Builder(ProactiveActionReceiver.EXTRA_INLINE_REPLY).build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(ProactiveActionReceiver.EXTRA_INLINE_REPLY, text) })
        return intent
    }

    private fun deliver(vararg intents: Intent) {
        intents.forEach { ProactiveActionReceiver().onReceive(context, Intent(it)) }
        awaitReceiverCompletion()
    }

    /** Observation only: wait behind real receiver tasks; never instantiate or register a NativeDelivery. */
    private fun awaitReceiverCompletion() {
        val type = Class.forName("com.jarvys.agent.proactive.ProactiveReplyReceiverExecutor")
        val executor = type.getDeclaredField("executor").apply { isAccessible = true }.get(null) as ExecutorService
        executor.submit {}.get(30, TimeUnit.SECONDS)
    }

    private fun submissions() = db { store.readCentralSubmissions(session) }
    private fun users() = db { store.readProactiveThreadMessages(session, thread).filter { it.role == "user" } }
    private fun drainServiceIntents(): List<Intent> = buildList {
        val application = Shadows.shadowOf(context as Application)
        while (true) add(application.nextStartedService ?: break)
    }
    private fun assertOnlyCentralStarts(intents: List<Intent>, expected: Int) {
        assertEquals(expected, intents.size)
        assertTrue(intents.all { it.action == "com.jarvys.agent.CENTRAL_SUBMISSION" })
    }
    private fun reopen() = db {
        val old = JarvysChatDatabaseProvider.getInstance(context)
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        LocalRunStore(context).readCentralSubmissions(session)
        assertNotSame(old, JarvysChatDatabaseProvider.getInstance(context))
    }
    private fun display(userId: String): AgentRunUiEvent {
        val events = db { store.readRecentConversationTimeline(session) }
        AgentRunUiState.restoreSession(session, events)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // Equal MutableStateFlow values can retain the prior published object. Select what the UI actually exposes.
        return AgentRunUiState.eventsForSession(session).single { it.kind == "user" && it.messageId == userId }
    }

    @Test fun actualOptionDeliveriesConserveFirstReceiptAndConflictingOrdinalCannotDispatch() {
        val source = notice()
        val shown = posted(source)
        val first = option(shown, 0)
        val conflict = option(shown, 1)
        deliver(first, first)
        val accepted = submissions().single()
        assertEquals("PREPARED", accepted.state)
        assertEquals(choices[0].text, users().single().text)
        assertEquals(source, users().single().proactiveReplyToMessageId)
        assertOnlyCentralStarts(drainServiceIntents(), 1)
        deliver(conflict, first)
        assertEquals(listOf(accepted), submissions())
        assertEquals(1, users().size)
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }

    @Test fun inlineDuplicateConflictsAndFreshCanonicalSourcePermitsIntentionalSameText() {
        val source = notice()
        val original = posted(source)
        val initial = inline(original, "  Same intentional reply  ")
        val oldBinding = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, initial))
        deliver(initial)
        val first = submissions().single()
        assertOnlyCentralStarts(drainServiceIntents(), 1)
        deliver(inline(original, "Same intentional reply"), inline(original, "Different conflicting text"))
        assertEquals(listOf(first), submissions())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
        val refreshed = posted(first.userMessageId)
        val secondIntent = inline(refreshed, "Same intentional reply")
        val newBinding = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, secondIntent))
        assertNotEquals(oldBinding.actionId, newBinding.actionId)
        assertEquals(first.userMessageId, newBinding.sourceMessageId)
        assertEquals(source, oldBinding.sourceMessageId)
        assertNotEquals(initial.data, secondIntent.data)
        deliver(secondIntent)
        assertEquals(2, submissions().size)
        assertEquals(listOf("Same intentional reply", "Same intentional reply"), users().map { it.text })
        assertOnlyCentralStarts(drainServiceIntents(), 1)
    }

    @Test fun notificationPublicationFailureRetainsAcceptanceAndLaterCanonicalReplyRemainsIndependent() {
        val source = notice()
        val original = inline(posted(source), "Same text after publication failure")
        val originalBinding = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, original))
        val observed = AtomicReference<Result<Pair<List<com.jarvys.agent.chatdb.CentralSubmissionSnapshot>, Int>>>()
        FailingNotificationManagerShadow.beforeFailure = {
            observed.set(runCatching { submissions() to users().size })
        }
        FailingNotificationManagerShadow.failPublication = true
        try {
            deliver(original)
            val accepted = submissions().single()
            assertEquals("PREPARED", accepted.state)
            assertEquals(1, users().size)
            assertEquals(1, FailingNotificationManagerShadow.failures.get())
            val atAndroid = requireNotNull(observed.get()).getOrThrow()
            assertEquals(listOf(accepted), atAndroid.first)
            assertEquals(1, atAndroid.second) // The Android-only fault happened after the actual durable commit.
            val permit = requireNotNull(CentralSubmissionAdmission.find(session, accepted.submissionId))
            permit.requirePrepared()
            assertOnlyCentralStarts(drainServiceIntents(), 1) // Existing launch deliberately not delivered to a service.
            deliver(Intent(original))
            assertEquals(listOf(accepted), submissions())
            assertEquals(1, users().size)
            assertSame("Redelivery cannot mint or transfer a second permit", permit,
                CentralSubmissionAdmission.find(session, accepted.submissionId))
            assertOnlyCentralStarts(drainServiceIntents(), 0)
            assertEquals(2, FailingNotificationManagerShadow.failures.get())

            FailingNotificationManagerShadow.failPublication = false
            val refreshed = inline(posted(source), "Same text after publication failure") // Stale caller ID still refreshes canonically.
            val binding = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, refreshed))
            assertEquals(accepted.userMessageId, binding.sourceMessageId)
            assertNotEquals(originalBinding.actionId, binding.actionId)
            assertNotEquals(original.data, refreshed.data)
            deliver(refreshed)
            val second = submissions().single { it.submissionId != accepted.submissionId }
            assertEquals("PREPARED", second.state)
            assertEquals(accepted, submissions().single { it.submissionId == accepted.submissionId })
            assertNotEquals(accepted.userMessageId, second.userMessageId)
            assertEquals(listOf("Same text after publication failure", "Same text after publication failure"), users().map { it.text })
            assertOnlyCentralStarts(drainServiceIntents(), 1)
            assertSame(permit, CentralSubmissionAdmission.find(session, accepted.submissionId))
            assertNotSame(permit, CentralSubmissionAdmission.find(session, second.submissionId))
            assertEquals(2, FailingNotificationManagerShadow.failures.get())
        } finally { FailingNotificationManagerShadow.resetPublication() }
    }

    /** Android notification publication only. No production hook and no fabricated admission authority. */
    @org.robolectric.annotation.Implements(NotificationManager::class)
    class FailingNotificationManagerShadow : org.robolectric.shadows.ShadowNotificationManager() {
        @org.robolectric.annotation.Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            if (tag?.startsWith("jarvys_proactive_thread:") == true && failPublication) {
                beforeFailure?.invoke()
                failures.incrementAndGet()
                throw IllegalStateException("Synthetic Android proactive publication failure")
            }
            super.notify(tag, id, notification)
        }
        companion object {
            @Volatile var failPublication = false
            @Volatile var beforeFailure: (() -> Unit)? = null
            val failures = AtomicInteger()
            @JvmStatic @org.robolectric.annotation.Resetter fun resetPublication() {
                failPublication = false; beforeFailure = null; failures.set(0)
            }
        }
    }

    @Test fun realReceiverRejectsChangedUriOrExtrasBeforeWriting() {
        val source = notice()
        val original = inline(posted(source), "Rejected tampering")
        val mismatchedSource = Intent(original).putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, "different-source")
        val mismatchedAction = Intent(original).putExtra(ProactiveReplyNotificationIdentity.EXTRA_ACTION_ID, "0".repeat(64))
        val mismatchedUri = Intent(original).setData(android.net.Uri.parse("jarvys://proactive-native/wrong"))
        val mismatchedOrdinal = Intent(original).putExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, 0)
        deliver(mismatchedSource, mismatchedAction, mismatchedUri, mismatchedOrdinal)
        assertTrue(submissions().isEmpty())
        assertTrue(users().isEmpty())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }

    @Test fun sameFormatPhysicalGenerationChangeRejectsOldNativeAffordance() {
        val source = notice()
        val original = inline(posted(source), "Old physical generation")
        assertTrue(FreshStorageBootstrap.isCurrent(original))
        val prior = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, original))
        val replacement = if (prior.physicalGeneration == "00000000-0000-0000-0000-000000000001")
            "00000000-0000-0000-0000-000000000002" else "00000000-0000-0000-0000-000000000001"
        assertEquals(replacement, java.util.UUID.fromString(replacement).toString())
        assertNotEquals(prior.physicalGeneration, replacement)
        db { sql.execSQL("UPDATE storage_meta SET value=? WHERE key='connection_projection_generation_v1'", arrayOf(replacement)) }
        assertTrue("Format stamp alone remains current", FreshStorageBootstrap.isCurrent(original))
        deliver(original)
        assertTrue(submissions().isEmpty())
        assertTrue(users().isEmpty())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }

    @Test fun registrationThenReopenKeepsExactPreparedUserWithoutAutomaticDispatch() {
        deliver(inline(posted(notice()), "Accepted before process loss"))
        val before = submissions().single()
        assertOnlyCentralStarts(drainServiceIntents(), 1)
        val permit = requireNotNull(CentralSubmissionAdmission.find(session, before.submissionId))
        permit.requirePrepared()
        reopen()
        assertThrows(IllegalStateException::class.java) { permit.requirePrepared() }
        val selected = display(before.userMessageId)
        assertEquals(before, submissions().single())
        assertEquals(1, users().size)
        assertEquals("PREPARED", selected.centralAvailability!!.state)
        assertTrue(selected.centralAvailability!!.canRetry)
        assertTrue(selected.centralAvailability!!.recoveryFence().isNotBlank())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }

    @Test fun sourceHiddenBeforeAdmissionRejectsButHiddenAfterAcceptanceDoesNotRevokeRecovery() {
        val firstSource = notice("hidden-before")
        val firstIntent = option(posted(firstSource))
        db { store.appendAssistantDeleted(session, firstSource) }
        deliver(firstIntent)
        assertTrue(submissions().isEmpty())
        assertTrue(users().isEmpty())
        assertOnlyCentralStarts(drainServiceIntents(), 0)

        val source = notice("hidden-after")
        val acceptedIntent = option(posted(source))
        deliver(acceptedIntent)
        val accepted = submissions().single()
        assertOnlyCentralStarts(drainServiceIntents(), 1)
        db { store.appendAssistantDeleted(session, source) }
        deliver(acceptedIntent)
        assertEquals(accepted, submissions().single())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
        reopen()
        val retainedRoot = db { store.captureCentralUserRoot(session, accepted.userMessageId) }
        assertEquals(accepted.sourceSha256, retainedRoot.originDigest)
        assertTrue(display(accepted.userMessageId).centralAvailability!!.canRetry)
        assertEquals(accepted, submissions().single())
        fun sourceEvidence() = db {
            sql.query("SELECT suggested_replies_consumed_seq,hidden_kind FROM messages WHERE conversation_id=? AND message_id=?",
                arrayOf(session, source)).use { check(it.moveToFirst()); it.getLong(0) to it.getString(1) }
        }
        val hiddenEvidence = sourceEvidence()
        assertTrue(hiddenEvidence.first > accepted.userSeq)
        assertNotNull(hiddenEvidence.second)
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController!!.get()
        val calls = AtomicInteger()
        val actualModelInput = AtomicReference<String>()
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation route required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
                token: CancellationToken): ModelReply {
                token.throwIfCancelled()
                calls.incrementAndGet()
                actualModelInput.set(systemPrompt + "\n" + history.joinToString("\n") { it.content } + "\n" + userPrompt)
                return ModelReply("Explicit hidden-source reply recovered", emptyList())
            }
        }
        installRuntime(service, provider)
        db { service.runPendingInputRecoverySafely(null) }
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        assertEquals(0, calls.get())
        assertEquals(accepted, submissions().single())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
        val selected = display(accepted.userMessageId)
        assertFalse(AgentRunUiState.eventsForSession(session).any { it.messageId == source || it.messageId == firstSource })
        val executor = Executors.newSingleThreadExecutor()
        val drafts = recoveryModel(executor)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("hidden-source-explicit-recovery", drafts) }
        try {
            assertTrue(drafts.recoverPreparedSubmission(selected))
            executor.submit {}.get(30, TimeUnit.SECONDS)
            val recovered = submissions().single()
            assertEquals("PREPARED", recovered.state)
            assertEquals(accepted.revision + 1, recovered.revision)
            assertEquals(accepted.userMessageId, recovered.userMessageId)
            assertEquals(accepted.submissionId, recovered.submissionId)
            assertEquals(accepted.sourceSha256, recovered.sourceSha256)
            assertEquals(accepted.storageGeneration, recovered.storageGeneration)
            assertEquals(hiddenEvidence, sourceEvidence())
            val starts = drainServiceIntents()
            assertOnlyCentralStarts(starts, 1)
            service.onStartCommand(starts.single(), 0, 1)
            service.onStartCommand(Intent(starts.single()), 0, 2)
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            runtimeFailure.get()?.let { throw AssertionError("Hidden source failed actual protected-context runtime", it) }
            assertEquals(1, calls.get())
            val completed = submissions().single()
            assertEquals("COMPLETED", completed.state)
            assertEquals(accepted.userMessageId, completed.userMessageId)
            assertEquals(accepted.submissionId, completed.submissionId)
            assertEquals(accepted.sourceSha256, completed.sourceSha256)
            assertEquals(accepted.storageGeneration, completed.storageGeneration)
            assertEquals(1, users().size)
            assertEquals(hiddenEvidence, sourceEvidence())
            val modelInput = requireNotNull(actualModelInput.get())
            assertTrue(modelInput.contains(choices[0].text))
            assertFalse("Valid hidden source identity must not expose its removed text", modelInput.contains("Synthetic native notice hidden-after"))
            assertFalse(modelInput.contains("Synthetic native notice hidden-before"))
            deliver(acceptedIntent)
            assertEquals(listOf(completed), submissions())
            assertOnlyCentralStarts(drainServiceIntents(), 0)
            assertEquals(1, calls.get())
        } finally { owner.clear(); executor.shutdownNow() }
    }

    @Test fun nativeStopBeforeClaimCancelsHiddenSourceAndDuplicateCannotReviveIt() {
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val source = notice()
        val original = option(posted(source))
        deliver(original)
        val accepted = submissions().single()
        val queuedIntents = drainServiceIntents()
        assertOnlyCentralStarts(queuedIntents, 1)
        db { store.appendAssistantDeleted(session, source) }
        AgentForegroundService.stopRun(session)
        val service = serviceController!!.get()
        service.awaitStopTasksForTesting().get(30, TimeUnit.SECONDS)
        val cancelled = submissions().single()
        assertEquals("CANCELLED", cancelled.state)
        assertEquals(accepted.userMessageId, cancelled.userMessageId)
        deliver(original)
        queuedIntents.forEachIndexed { index, intent -> service.onStartCommand(intent, 0, index + 1) }
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        assertEquals(cancelled, submissions().single())
        assertEquals(1, users().size)
        assertFalse(display(accepted.userMessageId).centralAvailability!!.canRetry)
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }

    @Test fun duplicateActualServiceIntentClaimsAndStartsSyntheticProviderOnlyOnce() {
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController!!.get()
        val entered = CompletableFuture<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation route required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
                token: CancellationToken): ModelReply {
                calls.incrementAndGet()
                entered.complete(Unit)
                check(release.await(30, TimeUnit.SECONDS)) { "Native provider fixture was not released" }
                token.throwIfCancelled()
                return ModelReply("Synthetic accepted reply completed", emptyList())
            }
        }
        installRuntime(service, provider)
        try {
            deliver(option(posted(notice())))
            val accepted = submissions().single()
            val intent = drainServiceIntents().single()
            assertEquals("com.jarvys.agent.CENTRAL_SUBMISSION", intent.action)
            service.onStartCommand(intent, 0, 1)
            awaitNativeEntry(entered, service, "Actual provider must enter after native claim")
            assertEquals("STARTED", submissions().single().state)
            service.onStartCommand(Intent(intent), 0, 2)
            assertEquals(1, calls.get())
            release.countDown()
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            assertEquals("COMPLETED", submissions().single().state)
            assertEquals(accepted.userMessageId, submissions().single().userMessageId)
            assertEquals(1, users().size)
        } finally { release.countDown() }
    }

    private fun installRuntime(service: AgentForegroundService, provider: ModelProviderClient) {
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { app, id, skills ->
            runtimePhase.set("runtime_constructor")
            try {
                CoreAgentRuntime(app, id, skills, CorePromptBudget.standard(), provider)
                    .also { runtimePhase.set("runtime_ready") }
            } catch (failure: Throwable) { runtimeFailure.compareAndSet(null, failure); throw failure }
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            runtimePhase.set("native_runner")
            try { runtime.runMainChat(goal, history, attachments, token, progress) }
            catch (failure: Throwable) { runtimeFailure.compareAndSet(null, failure); throw failure }
        }
    }

    private fun awaitNativeEntry(entry: CompletableFuture<Unit>, service: AgentForegroundService, description: String) {
        // Fail promptly if the real physical lane exits before the requested entry; never lengthen its timeout.
        val idle = service.awaitSessionIdleForTesting(session) as CompletableFuture<*>
        val wait = runCatching { CompletableFuture.anyOf(entry, idle).get(30, TimeUnit.SECONDS) }
        val failure = runtimeFailure.get() ?: wait.exceptionOrNull()
        if (!entry.isDone || failure != null) throw AssertionError(
            "$description; phase=${runtimePhase.get()}; states=${submissions().map { it.state to it.revision }}; " +
                "diagnostic=${LocalDiagnostics.get(context).snapshot(null, 1, true)}", failure)
        assertTrue(description, entry.isDone)
    }

    private fun recoveryModel(executor: ExecutorService): AttachmentDraftViewModel {
        val noLegacyWrite = object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String = error("Recovery must not append another USER")
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>) = error("Recovery must not use generic stored-message dispatch")
        }
        return AttachmentDraftViewModel(context as Application, noLegacyWrite, executor).apply { switchSession(session) }
    }

    private fun installSyntheticProvider(service: AgentForegroundService, answer: () -> String) {
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation route required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
                token: CancellationToken): ModelReply {
                token.throwIfCancelled()
                return ModelReply(answer(), emptyList())
            }
        }
        installRuntime(service, provider)
    }

    @Test fun reopenedTypedReplyRunsOnceOnlyThroughFreshDisplayedViewModelRecovery() {
        deliver(inline(posted(notice()), "Recover this exact threaded USER"))
        val accepted = submissions().single()
        assertOnlyCentralStarts(drainServiceIntents(), 1) // Original launch request deliberately was not delivered.
        assertEquals(thread, users().single().proactiveThreadKey)
        reopen()
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController!!.get()
        val calls = AtomicInteger()
        installSyntheticProvider(service) { calls.incrementAndGet(); "Recovered native reply completed" }
        db { service.runPendingInputRecoverySafely(null) } // The real startup recovery routine, awaited synchronously.
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        assertEquals(0, calls.get())
        assertEquals(accepted, submissions().single())
        assertOnlyCentralStarts(drainServiceIntents(), 0)

        val stale = display(accepted.userMessageId)
        AgentRunUiState.restoreSession(session, emptyList()) // Actual display replacement, not an equal-value re-assignment.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val selected = display(accepted.userMessageId)
        assertNotSame(stale.centralAvailability, selected.centralAvailability)
        assertEquals("PREPARED", selected.centralAvailability!!.state)
        assertTrue(selected.centralAvailability!!.canRetry)
        assertEquals(thread, selected.proactiveThreadKey)
        val sameEvidenceCopy = selected.copy()
        assertNotSame(selected, sameEvidenceCopy)
        assertSame(selected.centralAvailability, sameEvidenceCopy.centralAvailability)
        assertTrue("An event copy retaining the actual availability is the same legitimate recovery selection",
            AgentRunUiState.containsExactRecoverySelection(session, sameEvidenceCopy))
        val executor = Executors.newSingleThreadExecutor()
        val drafts = recoveryModel(executor)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("typed-reply-recovery", drafts) }
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { occupied.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        assertTrue(occupied.await(30, TimeUnit.SECONDS))
        try {
            assertFalse("Stale displayed descriptor cannot authorize retry", drafts.recoverPreparedSubmission(stale))
            assertFalse("Reconstructed availability is not the actual displayed recovery evidence",
                drafts.recoverPreparedSubmission(selected.copy(centralAvailability = selected.centralAvailability!!.copy())))
            assertTrue(drafts.recoverPreparedSubmission(selected))
            assertFalse("A second tap while this exact retry is pending is denied", drafts.recoverPreparedSubmission(selected))
            release.countDown()
            executor.submit {}.get(30, TimeUnit.SECONDS)
            val recovered = submissions().single()
            assertEquals("PREPARED", recovered.state)
            assertEquals(accepted.revision + 1, recovered.revision)
            assertEquals(accepted.userMessageId, recovered.userMessageId)
            assertEquals(accepted.submissionId, recovered.submissionId)
            assertEquals(accepted.sourceSha256, recovered.sourceSha256)
            assertEquals(accepted.storageGeneration, recovered.storageGeneration)
            assertEquals(accepted.originalAdmissionProcess, recovered.originalAdmissionProcess)
            assertNotNull(recovered.recoveryReceipt)
            assertEquals(1, users().size)
            val starts = drainServiceIntents()
            assertOnlyCentralStarts(starts, 1)
            service.onStartCommand(starts.single(), 0, 1)
            service.onStartCommand(Intent(starts.single()), 0, 2)
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            val completed = submissions().single()
            assertEquals("COMPLETED", completed.state)
            assertEquals(1, calls.get())
            assertEquals(accepted.userMessageId, completed.userMessageId)
            assertEquals(accepted.submissionId, completed.submissionId)
            assertEquals(accepted.sourceSha256, completed.sourceSha256)
            assertEquals(accepted.storageGeneration, completed.storageGeneration)
            assertEquals(1, users().size)
            display(accepted.userMessageId)
            assertFalse("The consumed old display cannot retry again", drafts.recoverPreparedSubmission(selected))
            executor.submit {}.get(30, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            assertOnlyCentralStarts(drainServiceIntents(), 0)
        } finally {
            release.countDown()
            owner.clear()
            executor.shutdownNow()
        }
    }

    private class SyntheticPhysicalRunnerLoss : Error("Synthetic abrupt native runner exit after STARTED")

    @Test fun typedStartedReplyAfterAbruptPhysicalRunnerExitIsUncertainAndCannotReplay() {
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController!!.get()
        val runnerEntered = CompletableFuture<Unit>()
        val releaseRunner = CountDownLatch(1)
        val runnerStarts = AtomicInteger()
        val providerCalls = AtomicInteger()
        installSyntheticProvider(service) { providerCalls.incrementAndGet(); error("No provider request belongs to this runner-loss boundary") }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
            runnerStarts.incrementAndGet()
            runnerEntered.complete(Unit)
            check(releaseRunner.await(30, TimeUnit.SECONDS))
            // Error bypasses normal RuntimeException terminal handling; FutureTask still physically unwinds.
            // This is a host abrupt-runner-loss simulation, not an Android OS process kill.
            throw SyntheticPhysicalRunnerLoss()
        }
        val source = notice()
        val originalDelivery = option(posted(source))
        deliver(originalDelivery)
        val prepared = submissions().single()
        val originalStart = drainServiceIntents().single()
        try {
            service.onStartCommand(originalStart, 0, 1)
            awaitNativeEntry(runnerEntered, service, "Must cross the actual native claim before simulated loss")
            val started = submissions().single()
            assertEquals("STARTED", started.state)
            assertEquals(prepared.revision + 1, started.revision)
            assertNotNull(started.claimRunId)
            assertNotNull(started.claimTokenGeneration)
            assertNull(started.terminalEvidence)
            releaseRunner.countDown()
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            assertFalse("The original physical service task must have unwound", AgentForegroundService.hasActiveRun(session))
            assertEquals(started, submissions().single())
            assertEquals(1, runnerStarts.get())
            assertEquals(0, providerCalls.get())
            serviceController!!.destroy()
            serviceController = null
            reopen()

            serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
            val reopenedService = serviceController!!.get()
            installSyntheticProvider(reopenedService) { providerCalls.incrementAndGet(); "Forbidden automatic replay" }
            reopenedService.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
                runnerStarts.incrementAndGet(); throw AssertionError("STARTED reply was replayed")
            }
            db { reopenedService.runPendingInputRecoverySafely(null) }
            reopenedService.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            val selected = display(started.userMessageId)
            assertEquals(thread, selected.proactiveThreadKey)
            assertEquals("UNCERTAIN", selected.centralAvailability!!.state)
            assertFalse(selected.centralAvailability!!.canRetry)
            assertEquals(started, selected.centralAvailability!!.submission)
            val executor = Executors.newSingleThreadExecutor()
            val drafts = recoveryModel(executor)
            val owner = androidx.lifecycle.ViewModelStore().apply { put("uncertain-threaded-reply", drafts) }
            try {
                assertFalse("STARTED has no explicit PREPARED retry", drafts.recoverPreparedSubmission(selected))
                executor.submit {}.get(30, TimeUnit.SECONDS)
            } finally { owner.clear(); executor.shutdownNow() }
            deliver(originalDelivery)
            assertOnlyCentralStarts(drainServiceIntents(), 0)
            reopenedService.onStartCommand(Intent(originalStart), 0, 2)
            reopenedService.onStartCommand(Intent(originalStart), 0, 3)
            reopenedService.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            assertEquals(started, submissions().single())
            assertEquals(1, users().size)
            assertEquals(1, runnerStarts.get())
            assertEquals(0, providerCalls.get())
            assertOnlyCentralStarts(drainServiceIntents(), 0)
        } finally { releaseRunner.countDown() }
    }

    @Test fun acceptedThreadedUserIsExcludedFromPredecessorSteeringAndCorruptionCannotFallback() {
        val predecessor = db { store.appendConversationMessage(session, "user", "Existing foreground question") }
        val provider = db { MainChatTurnContextProvider(store, session, predecessor) }
        val source = notice()
        deliver(option(posted(source)))
        val accepted = submissions().single()
        assertOnlyCentralStarts(drainServiceIntents(), 1)
        assertTrue(db { provider.takeDurableMessages() }.isEmpty())
        assertEquals(thread, users().single().proactiveThreadKey)
        db { sql.execSQL("UPDATE messages SET proactive_thread_key=NULL WHERE conversation_id=? AND message_id=?", arrayOf(session, accepted.userMessageId)) }
        assertThrows(IllegalStateException::class.java) { db { store.captureCentralUserRoot(session, accepted.userMessageId) } }
        assertThrows(IllegalStateException::class.java) { db { store.readCompactedProactiveReferent(session, accepted.userMessageId) } }
        assertThrows(IllegalStateException::class.java) { db { MainChatTurnContextProvider(store, session, accepted.userMessageId).takeUntrustedContext() } }
        assertEquals(accepted, submissions().single())
        assertOnlyCentralStarts(drainServiceIntents(), 0)
    }
}

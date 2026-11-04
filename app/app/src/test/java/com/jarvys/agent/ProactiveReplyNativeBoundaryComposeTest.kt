package com.jarvys.agent

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt
import com.jarvys.agent.chatdb.testChatSql
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * Real rendered chip/Activity ownership reaches the real typed writer.
 * It intentionally stops before service orchestration; it does not claim whole-process crash coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveReplyNativeBoundaryComposeTest {
    private val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "actual-compose-native-reply"
    private val option = ProactiveSuggestedReply("Capture native review", "Review through the actual displayed option")
    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private val store get() = LocalRunStore(context)
    private val sql get() = JarvysChatDatabaseProvider.getInstance(context).testChatSql
    private val displayed = mutableStateOf<AgentRunUiEvent?>(null)
    private val captured = AtomicReference<ProactiveReplyAdmission.NativeInteraction>()
    private var contentInstalled = false

    @Before fun setUp() {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Requires actual D3 native activation, no test override" }
        db {
            FreshStorageBootstrap.ensure(context)
            val connections = (context as TreeNativeTestApplication).connectionAccess()
            connections.initializeAsync().get(30, TimeUnit.SECONDS)
            check(connections.isReady())
            // Direct-writer cases begin after the same native schema prerequisite as service admission.
            // This is fixture initialization, not coverage of service orchestration.
            WorkspaceRegistry(context).use { it.upgradeCentralResumeSchema() }
            CentralAvailabilityPolicy.requireRegistrySchemaReady(context)
        }
        AgentRunUiState.resetSession(session)
        AgentRunUiState.showSession(session)
        UserDecisionUiAvailability.setChatVisible(true)
    }

    @After fun tearDown() {
        UserDecisionUiAvailability.setChatVisible(false)
        ProactiveReplyAdmission.cancelCapturedInteractions(session)
    }

    private fun clickActualOption(suffix: String = "one"): ProactiveReplyAdmission.NativeInteraction {
        val event = db {
            val source = store.appendProactiveAssistantMessageIfAbsent(session, "actual-compose-decision-$suffix", "Displayed native notice $suffix", listOf(option), thread)
            store.readRecentConversationTimeline(session).single { it.messageId == source }
        }
        AgentRunUiState.restoreSession(session, listOf(event))
        compose.runOnIdle { displayed.value = event; captured.set(null) }
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true }, ApprovalGate.INSTANCE)
        if (!contentInstalled) compose.setContent {
            MaterialTheme {
                AgentRunScreen(
                    conversationKey = session,
                    events = listOfNotNull(displayed.value),
                    isRunning = false,
                    emptyReport = null,
                    connectorRegistry = registry,
                    onOpenPreview = {},
                    onOpenSkillFile = {},
                    onProactiveSuggestedReply = { selected, index ->
                        assertSame("The rendered callback must forward its actual event", displayed.value, selected)
                        captured.set(ProactiveReplyAdmission.captureDisplayedOption(compose.activity, selected, index))
                    },
                )
            }
        }
        contentInstalled = true
        compose.onNodeWithText(option.label).performClick()
        compose.waitForIdle()
        return requireNotNull(captured.get()).also { it.requireCurrent() }
    }

    private fun bind(interaction: ProactiveReplyAdmission.NativeInteraction) = db {
        interaction.bind(requireNotNull(store.readProactiveReplySource(interaction.sourceMessageId, null)))
    }
    private fun rows(): Map<String, List<List<String?>>> = db {
        linkedMapOf(
            "conversations" to "id", "messages" to "conversation_id,seq",
            "message_parts" to "conversation_id,message_id,ordinal",
            "proactive_suggested_replies" to "conversation_id,message_id,ordinal",
            "conversation_items" to "conversation_id,seq", "central_turn_submissions" to "conversation_id,submission_id",
        ).mapValues { (table, order) ->
            sql.query("SELECT * FROM $table ORDER BY $order").use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> if (cursor.isNull(index)) null else cursor.getString(index) })
            } }
        }
    }
    private fun reopen() = db {
        val previous = JarvysChatDatabaseProvider.getInstance(context)
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        LocalRunStore(context).readCentralSubmissions(session)
        assertNotSame(previous, JarvysChatDatabaseProvider.getInstance(context))
    }
    private fun assertNoDispatch(receipt: ProactiveReplyAppendReceipt) {
        assertNull(CentralSubmissionAdmission.find(session, receipt.receipt.snapshot.submissionId))
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }
    private fun assertPreparedRecovery(receipt: ProactiveReplyAppendReceipt) {
        val original = receipt.receipt.snapshot
        val events = db { store.readRecentConversationTimeline(session) }
        val selected = events.single { it.kind == "user" && it.messageId == original.userMessageId }
        assertEquals(listOf(original), db { store.readCentralSubmissions(session) })
        assertEquals("PREPARED", selected.centralAvailability!!.state)
        assertTrue(selected.centralAvailability!!.canRetry)
        assertEquals(original, selected.centralAvailability!!.submission)
        assertNoDispatch(receipt)
    }

    @Test fun actualDisplayedActionCommitBeforeRegistrationRetainsExactRecoveryAfterReopen() {
        val interaction = clickActualOption()
        val receipt = interaction.use { owner ->
            val action = bind(owner)
            requireNotNull(db { store.appendProactiveReplySubmission(action) })
        }
        assertTrue(receipt.created)
        assertNoDispatch(receipt)
        val before = rows()
        reopen()
        assertEquals(before, rows())
        assertPreparedRecovery(receipt)
    }

    @Test fun actualOwnerClosedAfterCommitCannotRegisterButCommittedUserSurvives() {
        val interaction = clickActualOption()
        val action = bind(interaction)
        val receipt = requireNotNull(db { store.appendProactiveReplySubmission(action) })
        action.recordCommitted(receipt)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // Actual ON_STOP revokes the captured owner.
        assertThrows(IllegalStateException::class.java) { CentralSubmissionAdmission.registerProactiveReply(action, receipt) }
        assertNoDispatch(receipt)
        reopen()
        assertPreparedRecovery(receipt)
    }

    @Test fun actualActivityStopBeforeCommitRevokesCapturedActionWithoutMutation() {
        val interaction = clickActualOption()
        val action = bind(interaction)
        val before = rows()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertThrows(IllegalStateException::class.java) { db { store.appendProactiveReplySubmission(action) } }
        assertEquals(before, rows())
        assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun actualActivityStopAfterRegistrationDoesNotCancelAcceptedUserOrPermit() {
        val interaction = clickActualOption()
        val permit = requireNotNull(db { ProactiveReplyAdmission.prepare(context, interaction) })
        val accepted = db { store.readCentralSubmissions(session) }.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertThrows(IllegalStateException::class.java) { interaction.requireCurrent() }
        permit.requirePrepared()
        assertEquals(accepted, db { store.readCentralSubmissions(session) }.single())
        assertEquals("PREPARED", accepted.state)
        assertEquals(permit.userMessageId, accepted.userMessageId)
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun retainedDisplayedSelectionCannotAdmitAfterLeavingVisibleCentral() {
        val interaction = clickActualOption()
        val action = bind(interaction)
        val before = rows()
        val original = requireNotNull(displayed.value)
        compose.runOnIdle {
            displayed.value = null
            UserDecisionUiAvailability.setChatVisible(false)
            assertNull(ProactiveReplyAdmission.captureDisplayedOption(compose.activity, original, 0))
        }
        assertThrows(IllegalStateException::class.java) { db { store.appendProactiveReplySubmission(action) } }
        compose.runOnIdle {
            UserDecisionUiAvailability.setChatVisible(true)
            AgentRunUiState.showSession("different-visible-session")
            assertNull(ProactiveReplyAdmission.captureDisplayedOption(compose.activity, original, 0))
        }
        assertThrows(IllegalStateException::class.java) { db { store.appendProactiveReplySubmission(action) } }
        assertEquals(before, rows())
        assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun stopIngressAfterCommitBeforeRegistrationRejectsOldOwnerButNotLaterNativeClick() {
        val interaction = clickActualOption("captured-before-stop")
        val action = bind(interaction)
        val receipt = requireNotNull(db { store.appendProactiveReplySubmission(action) })
        action.recordCommitted(receipt)
        val committedRows = rows()
        // Native denial-only ingress without a service: no durable cancellation is claimed here.
        AgentForegroundService.stopRun(session)
        assertThrows(IllegalStateException::class.java) { interaction.requireCurrent() }
        assertThrows(IllegalStateException::class.java) { CentralSubmissionAdmission.registerProactiveReply(action, receipt) }
        assertEquals(committedRows, rows())
        assertNoDispatch(receipt)
        val later = clickActualOption("captured-after-stop")
        val freshPermit = requireNotNull(db { ProactiveReplyAdmission.prepare(context, later) })
        freshPermit.requirePrepared()
        assertNotEquals(receipt.receipt.snapshot.submissionId, freshPermit.submissionId)
        assertNull(CentralSubmissionAdmission.find(session, receipt.receipt.snapshot.submissionId))
        assertEquals(2, db { store.readCentralSubmissions(session) }.size)
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun stopIngressRetiresRegisteredPermitAfterOwnerClosedWithoutCatchingLaterClick() {
        val interaction = clickActualOption("registered-before-stop")
        val oldPermit = requireNotNull(db { ProactiveReplyAdmission.prepare(context, interaction) })
        val committed = db { store.readCentralSubmissions(session) }.single()
        interaction.close() // Normal callback completion must not itself cancel accepted USER.
        oldPermit.requirePrepared()
        AgentForegroundService.stopRun(session)
        assertThrows(IllegalStateException::class.java) { oldPermit.requirePrepared() }
        assertNull(CentralSubmissionAdmission.find(session, oldPermit.submissionId))
        assertEquals(committed, db { store.readCentralSubmissions(session) }.single())
        val later = clickActualOption("registered-after-stop")
        val freshPermit = requireNotNull(db { ProactiveReplyAdmission.prepare(context, later) })
        freshPermit.requirePrepared()
        assertNotEquals(oldPermit.submissionId, freshPermit.submissionId)
        assertThrows(IllegalStateException::class.java) { oldPermit.requirePrepared() }
        assertEquals(2, db { store.readCentralSubmissions(session) }.size)
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun actualNativeUserInsertFailureRollsBackWholeAcceptance() = assertAtomicFailure(
        "BEFORE INSERT ON messages WHEN NEW.role='user'", "native_user_rejected",
    )

    @Test fun actualNativeConsumptionMarkerFailureRollsBackWholeAcceptance() = assertAtomicFailure(
        "BEFORE INSERT ON conversation_items WHEN NEW.item_kind='proactive_reply_used'", "native_marker_rejected",
    )

    @Test fun actualNativeSubmissionInsertFailureRollsBackWholeAcceptance() = assertAtomicFailure(
        "BEFORE INSERT ON central_turn_submissions", "native_submission_rejected",
    )

    private fun assertAtomicFailure(triggerTarget: String, reason: String) {
        val interaction = clickActualOption()
        val action = bind(interaction)
        val before = rows()
        db { sql.execSQL("CREATE TRIGGER p4a_native_rejection $triggerTarget BEGIN SELECT RAISE(ABORT,'$reason'); END") }
        val rejected = try {
            interaction.use { runCatching { db { store.appendProactiveReplySubmission(action) } } }
        } finally {
            db { sql.execSQL("DROP TRIGGER p4a_native_rejection") }
        }
        assertTrue("Must reject at the selected native SQLite boundary: $rejected",
            generateSequence(rejected.exceptionOrNull()) { it.cause }.any { it.message.orEmpty().contains(reason) })
        reopen()
        assertEquals("Native writer rollback must preserve every acceptance row and counter", before, rows())
        assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)

        val freshInteraction = clickActualOption() // Same conserved notice, new actual human-selection callback.
        val freshAction = bind(freshInteraction)
        db { sql.execSQL("CREATE TRIGGER p4a_native_rejection $triggerTarget BEGIN SELECT RAISE(ABORT,'$reason'); END") }
        val repeated = try {
            freshInteraction.use { runCatching { db { store.appendProactiveReplySubmission(freshAction) } } }
        } finally { db { sql.execSQL("DROP TRIGGER p4a_native_rejection") } }
        assertTrue("After reopen a fresh native re-selection must reach the same INSERT rather than consumed-null",
            generateSequence(repeated.exceptionOrNull()) { it.cause }.any { it.message.orEmpty().contains(reason) })
        assertEquals("The repeated failed selection also conserves all rows", before, rows())
        assertTrue(db { store.readCentralSubmissions(session) }.isEmpty())
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }
}

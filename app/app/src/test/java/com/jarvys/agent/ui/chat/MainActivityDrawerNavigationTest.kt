package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.tasks.TaskStore
import com.jarvys.agent.ui.chat.ChatFileTransfers
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native pointer-input coverage of the real Activity, stores, drawer, navigation and archive actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MainActivityDrawerNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val suffix = UUID.randomUUID().toString()
    private val currentSession = "navigation-current-$suffix"
    private val archivedA = "navigation-archive-a-$suffix"
    private val archivedB = "navigation-archive-b-$suffix"
    private val currentTitle = "Current project notes"
    private val titleA = "Archived garden notes"
    private val titleB = "Archived travel notes"
    private val draft = "Unsent: keep this draft while I check the drawer"
    private val attachmentBytes = "Persisted project attachment: keep this exact local content.".toByteArray()
    private lateinit var currentAttachment: ChatAttachment
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: LocalRunStore get() = LocalRunStore(context)
    private val secretSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var restartedScenario: ActivityScenario<MainActivity>? = null
    private var restartedActivity: MainActivity? = null
    private val activity: MainActivity get() = restartedActivity ?: compose.activity

    private val fixtures = object : ExternalResource() {
        override fun before() {
            // Robolectric supplies a new Application for each test; the Android factory is static.
            ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
                .apply { isAccessible = true }.set(null, null)
            secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux17-navigation-secrets", 0)))
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            // This fixture represents an existing user, past the two independent first-use modals.
            MemoryStore(context).markDisclosureShown()
            MemoryReflectionPreferences(context).markDisclosureShown()
            currentAttachment = AttachmentStore(context).copyFromStream(currentSession, "project-notes.txt", "text/plain",
                ChatAttachment.Kind.FILE, ByteArrayInputStream(attachmentBytes))
            seed(currentSession, currentTitle, turns = 18, firstAttachment = currentAttachment)
            seed(archivedA, titleA, archived = true)
            seed(archivedB, titleB, archived = true)
            AgentRunUiState.resetSession(currentSession)
            context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).edit()
                .putString("active_session_id", currentSession).commit()
        }

        override fun after() {
            restartedScenario?.close()
            restartedScenario = null
            restartedActivity = null
            secretSingleton.set(null, null)
            WorkManagerTestCleanup.close(context)
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun newChatDrawerTapSelectsANewSessionWithoutChangingThePreviousTranscriptOrAttachment() {
        awaitCurrentChat()
        val previousTranscript = transcript(currentSession)
        val transfers = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        openDrawer()
        tapTag("drawer-new-chat")
        compose.waitUntil(10_000) { selectedSession() != currentSession }
        val newSession = requireNotNull(selectedSession())
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        assertEquals("", compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertTrue(store.readConversationTimeline(newSession).isEmpty())
        assertEquals(previousTranscript, transcript(currentSession))
        val persistedAttachment = store.readConversationTimeline(currentSession).flatMap { it.attachments }.single()
        assertEquals(currentAttachment, persistedAttachment)
        assertArrayEquals(attachmentBytes, AttachmentStore(context).resolve(currentSession, persistedAttachment).readBytes())
        assertFalse(store.readConversationMetadata(currentSession).deleted)
        assertFalse(store.readConversationMetadata(currentSession).archived)
        assertSame(transfers, ViewModelProvider(activity)[ChatFileTransfers::class.java])

        openDrawer()
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(currentTitle))
        settleNativeFrame()
        compose.onNodeWithText(currentTitle).performTouchInput { click() }
        awaitCurrentChat()
        assertEquals(previousTranscript, transcript(currentSession))
        assertArrayEquals(attachmentBytes, AttachmentStore(context).resolve(currentSession, currentAttachment).readBytes())
        assertEquals(currentSession, selectedSession())
    }

    @Test fun systemBackDismissesSearchBeforeTheDrawerAndBeforeTheUnderlyingNestedRoute() {
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        openDrawer()
        tapTag("drawer-open-scheduled-tasks")
        awaitTag("scheduled-tasks-placeholder")
        // Nested pages have a Back arrow. Exercise the drawer's real horizontal edge gesture.
        settleNativeFrame()
        compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("scheduled-tasks-placeholder"))).performTouchInput {
            swipe(Offset(1f, centerY), Offset(width * 0.85f, centerY), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        tapTag("drawer-search-toggle")
        compose.onNodeWithTag("drawer-search").performTextInput("no-matching-chat-$suffix")
        compose.onNodeWithText(context.getString(R.string.drawer_search_no_results)).assertIsDisplayed()

        systemBack()
        compose.onNodeWithTag("drawer-search").assertDoesNotExist()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        compose.runOnIdle { assertEquals(AppNavigationBackPolicy.SCHEDULED_TASKS, navigation().currentDestination?.route) }
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(currentTitle))
        compose.onNodeWithText(currentTitle).assertIsDisplayed()
        tapTag("drawer-search-toggle")
        compose.onNodeWithTag("drawer-search").assertTextEquals("")
        systemBack()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        compose.onNodeWithTag("scheduled-tasks-placeholder").assertIsDisplayed()
        compose.runOnIdle { assertEquals(AppNavigationBackPolicy.SCHEDULED_TASKS, navigation().currentDestination?.route) }
        tapTag("jarvys-back")
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        assertEquals(currentSession, selectedSession())
    }

    @Test fun scheduledDestinationAndBackKeepDraftTranscriptScrollAndOneRouteAfterRapidTaps() {
        awaitCurrentChat()
        val persisted = transcript(currentSession)
        val transfers = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        // Scroll with real gestures, away from the newest-message anchor before leaving chat.
        settleNativeFrame()
        repeat(2) {
            compose.onNodeWithTag("chat-message-list").performTouchInput {
                // The list extends under both floating overlays; keep the drag inside visible history.
                swipe(Offset(centerX, height * 0.28f), Offset(centerX, height * 0.62f), durationMillis = 400)
            }
        }
        compose.waitForIdle()
        val scrollBefore = chatScrollPosition()
        assertTrue("The test must leave the newest-message anchor", scrollBefore > 0f)

        openDrawer()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        compose.onNodeWithTag("drawer-header-title").assertTextEquals(context.getString(R.string.app_name))
        compose.onNodeWithTag("drawer-new-chat").assertIsDisplayed()
        compose.onNodeWithTag("drawer-open-bots").assertIsDisplayed()
        compose.onNodeWithTag("drawer-open-settings").assertIsDisplayed()
        compose.onNodeWithTag("drawer-archive-toggle").assertDoesNotExist()
        capture("drawer-from-existing-chat")

        compose.onNodeWithTag("drawer-open-scheduled-tasks").assertIsDisplayed().performTouchInput {
            click()
            advanceEventTime(16)
            click()
        }
        awaitTag("scheduled-tasks-placeholder")
        awaitDrawerClosed()
        compose.onNodeWithText(context.getString(R.string.scheduled_tasks_placeholder_body)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(AppNavigationBackPolicy.SCHEDULED_TASKS, navigation().currentDestination?.route)
            assertEquals("Rapid taps must not append another destination", 1,
                navigation().currentBackStack.value.count { it.destination.route == AppNavigationBackPolicy.SCHEDULED_TASKS })
            assertTrue("The placeholder must not create scheduled work", TaskStore(context).list().isEmpty())
        }
        capture("scheduled-tasks-placeholder")

        tapTag("jarvys-back")
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        assertEquals(scrollBefore, chatScrollPosition(), 0.02f)
        assertEquals(persisted, transcript(currentSession))
        assertSame(transfers, ViewModelProvider(activity)[ChatFileTransfers::class.java])
        assertEquals(currentSession, selectedSession())
        compose.runOnIdle { assertEquals(AppNavigationBackPolicy.CHAT_ROOT, navigation().currentDestination?.route) }

        // A fresh open/close cycle also has a single Back step and does not consume the draft.
        openDrawer()
        tapTag("drawer-open-scheduled-tasks")
        awaitTag("scheduled-tasks-placeholder")
        tapTag("jarvys-back")
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        assertEquals(scrollBefore, chatScrollPosition(), 0.02f)
    }

    @Test fun archiveRenameRestoreAndConfirmedDeleteMutateOnlyTheirSessionsAndHideTheLastEntry() {
        awaitCurrentChat()
        val currentBefore = transcript(currentSession)
        val archivedABefore = transcript(archivedA)
        val archivedBBefore = transcript(archivedB)
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        openArchives()
        capture("archived-chats-before-actions")

        chooseConversationAction(titleA, R.string.drawer_rename_chat)
        val renameInput = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        renameInput.performTextReplacement(" ")
        pumpPendingNativeRoots()
        compose.waitForIdle()
        renameInput.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_name_required)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).assertIsDisplayed()
        captureDialog("archived-rename-empty-validation")
        renameInput.performTextReplacement("  Renamed garden notes  ")
        pumpPendingNativeRoots()
        tapText(R.string.drawer_save_name)
        awaitText("Renamed garden notes")
        assertEquals("Renamed garden notes", store.readConversationTitle(archivedA))
        assertEquals(titleB, store.readConversationTitle(archivedB))
        assertEquals(archivedABefore, transcript(archivedA))

        chooseConversationAction(titleB, R.string.drawer_unarchive)
        compose.waitUntil(10_000) { !store.readConversationMetadata(archivedB).archived }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(titleB).fetchSemanticsNodes().isEmpty() }
        assertTrue(store.readConversationMetadata(archivedA).archived)
        assertEquals(archivedBBefore, transcript(archivedB))

        chooseConversationAction("Renamed garden notes", R.string.drawer_delete_action)
        compose.onNodeWithText(context.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        assertFalse("Opening the confirmation must not delete", store.readConversationMetadata(archivedA).deleted)
        tapText(R.string.drawer_cancel_action)
        assertEquals(archivedABefore, transcript(archivedA))
        chooseConversationAction("Renamed garden notes", R.string.drawer_delete_action)
        tapText(R.string.drawer_delete_action)
        awaitTag("archived-chats-empty")
        assertTrue(store.readConversationMetadata(archivedA).deleted)
        assertTrue(store.readConversationTimeline(archivedA).isEmpty())
        assertFalse(store.readConversationMetadata(archivedB).deleted)
        assertEquals(archivedBBefore, transcript(archivedB))
        assertEquals(currentBefore, transcript(currentSession))
        assertEquals(currentSession, selectedSession())

        tapTag("jarvys-back")
        compose.runOnIdle { assertEquals(AppNavigationBackPolicy.SETTINGS, navigation().currentDestination?.route) }
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
        tapTag("jarvys-back")
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        openDrawer()
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(titleB))
        compose.onNodeWithText(titleB).assertIsDisplayed()
        compose.onNodeWithText("Renamed garden notes").assertDoesNotExist()
    }

    @Test fun archivingTheCurrentConversationPreservesItsLedgerAndCanReopenTheExactArchivedChat() {
        awaitCurrentChat()
        val original = transcript(currentSession)
        val untouched = transcript(archivedA)
        val transfers = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        openDrawer()
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(currentTitle))
        chooseConversationAction(currentTitle, R.string.drawer_archive)
        compose.waitUntil(10_000) {
            store.readConversationMetadata(currentSession).archived && selectedSession() != currentSession
        }
        assertEquals(original, transcript(currentSession))
        assertEquals(untouched, transcript(archivedA))
        assertFalse(store.readConversationMetadata(currentSession).deleted)
        assertEquals(currentAttachment, store.readConversationTimeline(currentSession).flatMap { it.attachments }.single())
        assertArrayEquals(attachmentBytes, AttachmentStore(context).resolve(currentSession, currentAttachment).readBytes())
        assertSame(transfers, ViewModelProvider(activity)[ChatFileTransfers::class.java])

        // Archiving leaves the drawer open in the existing workflow; its fixed Settings action is usable.
        tapTag("drawer-open-settings")
        awaitTag("settings-archived-chats-row")
        settleNativeFrame()
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().performTouchInput { click() }
        awaitTag("archived-chats-list")
        compose.onNodeWithTag("archived-chats-list").performScrollToNode(hasText(currentTitle))
        settleNativeFrame()
        compose.onNodeWithText(currentTitle).performTouchInput { click() }
        awaitCurrentChat()
        assertEquals(original, transcript(currentSession))
        assertEquals(original.map { it.first }, AgentRunUiState.state.value.events.map { it.messageId })
        assertTrue("Viewing an archive must not implicitly restore it", store.readConversationMetadata(currentSession).archived)
        assertEquals(currentSession, selectedSession())
    }

    @Test fun recreatingArchiveRouteReloadsPersistedTitlesAndRetainsSessionDraftAndTransfersBeforeStaleIntent() {
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        val original = transcript(currentSession)
        val transfers = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        openArchives()
        val previousActivity = activity
        compose.runOnIdle {
            activity.intent.putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, archivedB)
            store.renameConversation(archivedA, "Garden title changed on disk")
        }
        compose.activityRule.scenario.recreate()
        assertNotSame(previousActivity, activity)
        awaitTag("archived-chats-list")
        awaitText("Garden title changed on disk")
        compose.runOnIdle { assertEquals(AppNavigationBackPolicy.ARCHIVED_CHATS, navigation().currentDestination?.route) }
        assertEquals("Saved session wins over the original deep-link intent", currentSession, selectedSession())
        assertSame(transfers, ViewModelProvider(activity)[ChatFileTransfers::class.java])
        assertEquals(original, transcript(currentSession))

        tapTag("jarvys-back")
        awaitTag("settings-archived-chats-row")
        tapTag("jarvys-back")
        awaitCurrentChat()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
    }

    @Test fun coldActivityRelaunchReacquiresPersistedArchivesWithoutChangingTheSelectedConversation() {
        awaitCurrentChat()
        val currentBefore = transcript(currentSession)
        openArchives()
        chooseConversationAction(titleA, R.string.drawer_rename_chat)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Durable archive name")
        tapText(R.string.drawer_save_name)
        awaitText("Durable archive name")
        compose.activityRule.scenario.close()
        // Discard the process-local chat projection so the new Activity has to load its ledger.
        AgentRunUiState.resetSession("discarded-projection-$suffix")
        restartedScenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        restartedScenario!!.onActivity { restartedActivity = it }
        awaitCurrentChat()
        assertEquals(currentSession, selectedSession())
        assertEquals(currentBefore, transcript(currentSession))
        assertEquals(currentBefore.map { it.first }, AgentRunUiState.state.value.events.map { it.messageId })
        openArchives()
        awaitText("Durable archive name")
        compose.onNodeWithText(titleB).assertIsDisplayed()
        assertTrue(store.readConversationMetadata(archivedA).archived)
        assertEquals(currentSession, selectedSession())
    }

    private fun seed(session: String, title: String, turns: Int = 1, archived: Boolean = false,
        firstAttachment: ChatAttachment? = null) {
        repeat(turns) { turn ->
            val attachments = if (turn == 0 && firstAttachment != null) listOf(firstAttachment) else emptyList()
            val message = store.appendConversationMessage(session, "user", "$title question ${turn + 1}", attachments)
            store.appendConversationMessage(session, "assistant", "$title answer ${turn + 1}: a persisted local response.",
                0L, "", message, "COMPLETED")
        }
        store.renameConversation(session, title)
        store.setConversationArchived(session, archived)
    }

    /** Stable transcript identity/content comparison; archive and title metadata are deliberately separate. */
    private fun transcript(session: String): List<Pair<String, String>> = store.readConversationTimeline(session)
        .map { it.messageId to "${it.kind}:${it.text}" }

    private fun selectedSession(): String? = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
        .getString("active_session_id", null)

    private fun navigation(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(activity) as NavHostController

    private fun awaitCurrentChat() {
        try {
            compose.waitUntil(10_000) {
                // Fetch first to pump pending native UI callbacks even while async session reload is unfinished.
                val lists = compose.onAllNodesWithTag("chat-message-list").fetchSemanticsNodes().size
                selectedSession() == currentSession && AgentRunUiState.state.value.sessionId == currentSession &&
                    AgentRunUiState.state.value.events.isNotEmpty() &&
                    lists == 1
            }
        } catch (failure: Throwable) {
            throw AssertionError("Chat did not return: ${navigationDiagnostic()}; expected=$currentSession; " +
                "lists=${compose.onAllNodesWithTag("chat-message-list").fetchSemanticsNodes().size}", failure)
        }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun tapTag(tag: String) {
        settleNativeFrame()
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
        if (tag in setOf("drawer-open-settings", "drawer-open-scheduled-tasks", "drawer-new-chat", "drawer-open-bots")) {
            awaitDrawerClosed()
        }
    }

    private fun awaitDrawerClosed() {
        try {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                runCatching { compose.onNodeWithTag("drawer-header").assertIsNotDisplayed() }.isSuccess
            }
        } catch (failure: Throwable) {
            val bounds = compose.onAllNodesWithTag("drawer-header").fetchSemanticsNodes().map { it.boundsInRoot }
            throw AssertionError("Drawer still covers destination: ${navigationDiagnostic()}; headerBounds=$bounds", failure)
        }
        settleNativeFrame()
    }

    private fun systemBack() {
        compose.runOnIdle {
            println("UX17_SYSTEM_BACK before: ${navigationDiagnostic()}")
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.runOnIdle { println("UX17_SYSTEM_BACK after: ${navigationDiagnostic()}") }
    }

    private fun navigationDiagnostic(): String = "route=${navigation().currentDestination?.route}; " +
        "stack=${navigation().currentBackStack.value.map { it.destination.route }}; selected=${selectedSession()}; " +
        "projection=${AgentRunUiState.state.value.sessionId}; events=${AgentRunUiState.state.value.events.size}"

    private fun tapText(resource: Int) {
        settleNativeFrame()
        compose.onNodeWithText(context.getString(resource)).assertIsDisplayed().performTouchInput { click() }
        pumpPendingNativeRoots()
        try { compose.waitForIdle() }
        catch (failure: Throwable) {
            throw AssertionError("Native text tap did not settle: ${context.resources.getResourceEntryName(resource)}; " +
                "${navigationDiagnostic()}; ${composeIdleDiagnostic()}", failure)
        }
    }

    private fun testField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val matching = type.declaredFields.firstOrNull { it.name == name }
            if (matching != null) return matching.apply { isAccessible = true }.get(instance)
            type = type.superclass
        }
        error("Missing diagnostic field $name")
    }

    private fun registeredNativeRoots(): Collection<*> {
        val environment = requireNotNull(testField(compose, "environment"))
        val registry = requireNotNull(testField(environment, "composeRootRegistry"))
        return registry.javaClass.getMethod("getRegisteredComposeRoots").invoke(registry) as Collection<*>
    }

    /** A just-created dialog can have a native root still awaiting host measurement in Robolectric. */
    private fun pumpPendingNativeRoots() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            compose.runOnUiThread {
                val method = Class.forName("androidx.compose.ui.node.RootForTest").getMethod("measureAndLayoutForTest")
                val rootInterface = Class.forName("androidx.compose.ui.platform.ViewRootForTest")
                registeredNativeRoots().filterNotNull().forEach { root ->
                    method.invoke(root)
                    val view = rootInterface.getMethod("getView").invoke(root) as View
                    println("UX17_NATIVE_ROOT before parent=${view.parent?.javaClass?.simpleName}; " +
                        "size=${view.width}x${view.height}; pending=${rootInterface.getMethod("getHasPendingMeasureOrLayout").invoke(root)}")
                    if (view.parent?.javaClass?.simpleName == "DialogLayout") {
                        val decor = view.rootView
                        val width = decor.width.takeIf { it > 0 } ?: decor.measuredWidth
                        val availableHeight = (decor.resources.configuration.screenHeightDp * decor.resources.displayMetrics.density).toInt()
                        if (width > 0 && availableHeight > 0) {
                            decor.forceLayout()
                            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
                            decor.layout(decor.left, decor.top, decor.left + decor.measuredWidth, decor.top + decor.measuredHeight)
                            val bitmap = Bitmap.createBitmap(decor.measuredWidth, decor.measuredHeight, Bitmap.Config.ARGB_8888)
                            try { decor.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                        }
                    }
                    println("UX17_NATIVE_ROOT after parent=${view.parent?.javaClass?.simpleName}; " +
                        "size=${view.width}x${view.height}; pending=${rootInterface.getMethod("getHasPendingMeasureOrLayout").invoke(root)}")
                }
            }
        }
    }

    private fun composeIdleDiagnostic(): String = runCatching {
        val environment = requireNotNull(testField(compose, "environment"))
        val idling = requireNotNull(testField(environment, "composeIdlingResource"))
        val detail = idling.javaClass.getMethod("getDiagnosticMessageIfBusy").invoke(idling)
        val flags = listOf("hadAwaitersOnMainClock", "hadSnapshotChanges", "hadRecomposerChanges",
            "hadPendingSetContent", "hadPendingMeasureLayout").associateWith { testField(idling, it) }
        val rootInterface = Class.forName("androidx.compose.ui.platform.ViewRootForTest")
        val roots = registeredNativeRoots().filterNotNull().map { root ->
            val view = rootInterface.getMethod("getView").invoke(root) as View
            "${view.javaClass.simpleName} pending=${rootInterface.getMethod("getHasPendingMeasureOrLayout").invoke(root)} " +
                "size=${view.width}x${view.height} attached=${view.isAttachedToWindow} " +
                "parent=${view.parent?.javaClass?.simpleName} root=${view.rootView.javaClass.simpleName}"
        }
        "Compose idle diagnostic: $detail; $flags; roots=$roots"
    }.getOrElse { "Compose idle diagnostic unavailable: ${it.message}" }

    private fun openDrawer() {
        settleNativeFrame()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
    }

    private fun openArchives() {
        openDrawer()
        tapTag("drawer-open-settings")
        awaitTag("settings-archived-chats-row")
        settleNativeFrame()
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().performTouchInput { click() }
        awaitTag("archived-chats-list")
    }

    private fun chooseConversationAction(title: String, resource: Int) {
        settleNativeFrame()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, title))
            .assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
        tapText(resource)
    }

    private fun chatScrollPosition(): Float = compose.onNodeWithTag("chat-message-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun captureDialog(name: String) {
        compose.runOnIdle {
            val rootInterface = Class.forName("androidx.compose.ui.platform.ViewRootForTest")
            val view = registeredNativeRoots().filterNotNull().map { rootInterface.getMethod("getView").invoke(it) as View }
                .single { it.parent?.javaClass?.simpleName == "DialogLayout" }.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val output = TestCaptureDirectories.named("ux17-navigation-${BuildConfig.FLAVOR}")
                val file = File(output, "$name.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    /** Compose idleness alone can precede the Android window's layout/draw and pointer hit targets. */
    private fun settleNativeFrame() {
        compose.waitForIdle()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = activity.window.decorView
                val metrics = root.resources.displayMetrics
                val config = root.resources.configuration
                val width = (config.screenWidthDp * metrics.density).toInt()
                val height = (config.screenHeightDp * metrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Force a native host draw; no browser mockup or production rendering substitute.
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = activity.window.decorView
                val metrics = root.resources.displayMetrics
                val config = root.resources.configuration
                val width = (config.screenWidthDp * metrics.density).toInt()
                val height = (config.screenHeightDp * metrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val output = TestCaptureDirectories.named("ux17-navigation-${BuildConfig.FLAVOR}")
                    val file = File(output, "$name.png")
                    TestCaptureDirectories.assertOwned(output, file)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            compose.waitForIdle()
        }
    }
}

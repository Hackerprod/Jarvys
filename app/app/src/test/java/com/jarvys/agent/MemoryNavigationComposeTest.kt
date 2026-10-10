package com.jarvys.agent

import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.ActivityOptionsCompat
import com.jarvys.agent.memory.memoryDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryNavigationComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var hostView: View

    @Test fun dirtyEditorArrowRequiresConfirmAndCancelKeepsEditorOpen() {
        val store = newStore()
        val nav = showMemory(store)
        openNewNote(nav)

        compose.onNodeWithTag("jarvys-back").performClick()
        compose.onNodeWithText(text(R.string.memory_unsaved_title)).assertIsDisplayed()
        assertEquals(AppNavigationBackPolicy.MEMORY_FILE, nav.controller.currentDestination?.route)
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(AppNavigationBackPolicy.MEMORY_FILE, nav.controller.currentDestination?.route)

        compose.onNodeWithTag("jarvys-back").performClick()
        compose.onNodeWithText(text(R.string.memory_discard)).performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        assertFalse(runCatching { store.readUserFile("note.md") }.isSuccess)
    }

    @Test fun dirtyEditorSystemBackRequiresConfirmationBeforeReturningHome() {
        val nav = showMemory(newStore())
        openNewNote(nav)

        nav.dispatcher.onBackPressed()
        compose.onNodeWithText(text(R.string.memory_unsaved_title)).assertIsDisplayed()
        assertEquals(AppNavigationBackPolicy.MEMORY_FILE, nav.controller.currentDestination?.route)
        compose.onNodeWithText(text(R.string.memory_discard)).performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
    }

    @Test fun cleanEditorArrowReturnsToHomeWithoutDiscardDialog() {
        val nav = showMemory(newStore())
        openEditor(nav, "human.md")
        compose.onNodeWithTag("memory-editor-name").assertIsDisplayed()

        compose.onNodeWithTag("jarvys-back").performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onAllNodesWithText(text(R.string.memory_unsaved_title)).assertCountEquals(0)
    }

    @Test fun cleanEditorSystemBackReturnsToHomeWithoutDiscardDialog() {
        val nav = showMemory(newStore())
        openEditor(nav, "human.md")
        compose.onNodeWithTag("memory-editor-name").assertIsDisplayed()

        nav.dispatcher.onBackPressed()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onAllNodesWithText(text(R.string.memory_unsaved_title)).assertCountEquals(0)
    }

    @Test fun historyArrowReturnsToMemoryHome() {
        val nav = showMemory(newStore())
        openHistory(nav)

        compose.onNodeWithTag("jarvys-back").performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
    }

    @Test fun historySystemBackReturnsToMemoryHome() {
        val nav = showMemory(newStore())
        openHistory(nav)

        nav.dispatcher.onBackPressed()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
    }

    @Test fun restoringARevisionReturnsHomeAndRefreshesTheRestoredFileName() {
        val store = newStore()
        val path = "roundtrip.md"
        val first = document("First title", "First body")
        val second = document("Second title", "Second body")
        val firstRevision = store.writeUserFile(path, first, 0L, false, SESSION)
        store.writeUserFile(path, second, firstRevision.id, false, SESSION)
        val nav = showMemory(store)
        openHistory(nav)
        filterHistory(path)

        compose.onNodeWithText("#${firstRevision.id}").scrollIntoView().performClick()
        val restoreLabel = text(R.string.memory_restore_version)
        compose.onNodeWithText(restoreLabel).scrollIntoView().performClick()
        compose.onNodeWithText(text(R.string.memory_restore_title)).assertIsDisplayed()
        val restoreNodes = compose.onAllNodesWithText(restoreLabel).fetchSemanticsNodes().size
        val restoreClicked = confirmDialogAction(restoreLabel)
        awaitMemoryMutationReturn(nav, store, path, first, "restore", restoreNodes, restoreClicked)
        scrollHomeToFiles()
        compose.onNodeWithText("First title").assertIsDisplayed()
        compose.onAllNodesWithText("Second title").assertCountEquals(0)
    }

    @Test fun undoLastChangeReturnsHomeAndRefreshesTheRestoredFileName() {
        val store = newStore()
        val path = "undo-note.md"
        val first = document("Before undo", "Before")
        val second = document("After undo", "After")
        val firstRevision = store.writeUserFile(path, first, 0L, false, SESSION)
        store.writeUserFile(path, second, firstRevision.id, false, SESSION)
        val nav = showMemory(store)
        openHistory(nav)

        val undoLabel = text(R.string.memory_undo_last)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText(undoLabel).assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText(undoLabel).assertIsEnabled()
        compose.onNodeWithText(undoLabel).performClick()
        compose.onNodeWithText(text(R.string.memory_undo_title)).assertIsDisplayed()
        val undoNodes = compose.onAllNodesWithText(undoLabel).fetchSemanticsNodes().size
        val undoClicked = confirmDialogAction(undoLabel)
        awaitMemoryMutationReturn(nav, store, path, first, "undo", undoNodes, undoClicked)
        scrollHomeToFiles()
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithText("Before undo").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText("Before undo").assertIsDisplayed()
        compose.onAllNodesWithText("After undo").assertCountEquals(0)
    }

    @Test fun leavingEditorForChatAndReenteringMemoryOpensHome() {
        val nav = showMemory(newStore())
        openEditor(nav, "human.md")
        nav.controller.navigate(AppNavigationBackPolicy.CHAT_ROOT)
        awaitRoute(nav, AppNavigationBackPolicy.CHAT_ROOT)
        compose.onNodeWithTag("memory-test-enter").assertIsDisplayed()
        compose.onNodeWithTag("memory-test-enter").performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
    }

    @Test fun leavingHistoryForChatAndReenteringMemoryOpensHome() {
        val nav = showMemory(newStore())
        openHistory(nav)
        nav.controller.navigate(AppNavigationBackPolicy.CHAT_ROOT)
        awaitRoute(nav, AppNavigationBackPolicy.CHAT_ROOT)
        compose.onNodeWithTag("memory-test-enter").performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
    }

    @Test fun missingExistingFileRouteFallsBackToMemoryHome() {
        val nav = showMemory(newStore())
        nav.controller.navigate(AppNavigationBackPolicy.memoryFile("missing.md", false))

        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        assertEquals("Missing-file route failed to fall back; current=${nav.controller.currentDestination?.route}",
            AppNavigationBackPolicy.MEMORY, nav.controller.currentDestination?.route)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
    }

    @Test fun newFileRouteOpensBlankEditorAndKeepsNewFlag() {
        val nav = showMemory(newStore())
        nav.controller.navigate(AppNavigationBackPolicy.memoryFile("empty note.md", true))

        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_FILE)
        compose.onNodeWithText("empty note.md").assertIsDisplayed()
        compose.onNodeWithTag("memory-editor-name").assertTextContains("")
        compose.onNodeWithTag("memory-editor-description").assertTextContains("")
        compose.onNodeWithTag("memory-editor-body").assertTextContains("")
        compose.onNodeWithTag("memory-editor-save").scrollIntoView().assertIsDisplayed()
    }

    @Test fun encodedSubdirectoryPathOpensTheExistingMemoryFile() {
        val store = newStore()
        val path = "notes/sub/file.md"
        store.createDirectoryForUser("notes/sub", SESSION)
        store.writeUserFile(path, document("Nested title", "Nested body"), 0L, false, SESSION)
        val encoded = AppNavigationBackPolicy.memoryFile(path, false)
        val nav = showMemory(store)
        nav.controller.navigate(encoded)

        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_FILE)
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithTag("memory-editor-name").assertTextContains("Nested title") }
                .isSuccess
        }
        compose.onNodeWithText(path).assertIsDisplayed()
        compose.onNodeWithTag("memory-editor-name").assertTextContains("Nested title")
        compose.onNodeWithTag("memory-editor-body").assertTextContains("Nested body")
    }

    @Test fun arbitrarySpacesSlashesAndAccentsRoundTripThroughMemoryRouteEncoding() {
        val path = "notes with spaces/niño résumé.md"
        val route = AppNavigationBackPolicy.memoryFile(path, false)

        assertTrue(route.contains("%2F"))
        assertTrue(route.contains("%20"))
        assertTrue(route.contains("%C3"))
        assertEquals(path, Uri.parse(route).getQueryParameter("path"))
        assertEquals("false", Uri.parse(route).getQueryParameter("new"))
    }

    @Test fun invalidStorePathRouteReturnsHomeWithoutCrashing() {
        val nav = showMemory(newStore())
        val invalidPath = "notas del café/niño résumé.md"
        nav.controller.navigate(AppNavigationBackPolicy.memoryFile(invalidPath, false))

        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            nav.controller.currentDestination?.route == AppNavigationBackPolicy.MEMORY
                    || compose.onAllNodesWithText(text(R.string.memory_error_generic)).fetchSemanticsNodes().isNotEmpty()
        }
        val route = nav.controller.currentDestination?.route
        assertTrue("Invalid path should return home or render an editor error; route=$route",
            route == AppNavigationBackPolicy.MEMORY
                    || compose.onAllNodesWithText(text(R.string.memory_error_generic)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun deletingFileFromEditorReturnsHomeAndRemovesItFromTheList() {
        val store = newStore()
        store.writeUserFile("remove-me.md", document("Delete me", "Body"), 0L, false, SESSION)
        val nav = showMemory(store)
        openEditor(nav, "remove-me.md")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Delete me").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Delete me").assertIsDisplayed()

        val deleteLabel = text(R.string.memory_delete)
        compose.onNodeWithText(deleteLabel).scrollIntoView().performClick()
        val deleteTitle = text(R.string.memory_delete_title)
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithText(deleteTitle).assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText(deleteTitle).assertIsDisplayed()
        val matchingDeleteNodes = compose.onAllNodesWithText(deleteLabel).fetchSemanticsNodes().size
        val clickedDialogNode = confirmDialogAction(deleteLabel)
        awaitDeleteResult(nav, store, matchingDeleteNodes, clickedDialogNode)
        scrollHomeToFiles()
        compose.onAllNodesWithText("Delete me").assertCountEquals(0)
    }

    @Test fun externalRevisionWhileEditingShowsConflictDialog() {
        val store = newStore()
        store.writeUserFile("conflicted.md", document("Conflict title", "Before"), 0L, false, SESSION)
        val nav = showMemory(store)
        openEditor(nav, "conflicted.md")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("memory-editor-save").scrollIntoView().assertIsEnabled(); true }
                .getOrDefault(false)
        }
        compose.onNodeWithTag("memory-editor-name").assertTextContains("Conflict title")
        val originalRevision = store.latestRevisionId("conflicted.md")
        store.write("conflicted.md", document("Agent title", "Agent's update"), MemoryStore.Actor.AGENT, SESSION)
        assertTrue(store.latestRevisionId("conflicted.md") > originalRevision)
        compose.onNodeWithTag("memory-editor-body").scrollIntoView().performTextClearance()
        compose.onNodeWithTag("memory-editor-body").performTextInput("My competing edit")
        compose.onNodeWithTag("memory-editor-save").scrollIntoView().performClick()

        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText(text(R.string.memory_conflict_title)).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
        compose.onNodeWithText(text(R.string.memory_conflict_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.memory_conflict_text)).assertIsDisplayed()
        assertEquals(AppNavigationBackPolicy.MEMORY_FILE, nav.controller.currentDestination?.route)
    }

    @Test fun sharedToolbarUsesLocalizedTitlesForHomeHistoryAndEditorDestinations() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val nav = showMemory(newStore())
        compose.onNodeWithTag("memory-shared-title").assertTextContains(context.getString(R.string.memory_title))
        openHistory(nav)
        compose.onNodeWithTag("memory-shared-title").assertTextContains(context.getString(R.string.memory_history))
        nav.controller.navigate(AppNavigationBackPolicy.memoryFile("human.md", false))
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_FILE)
        compose.onNodeWithTag("memory-shared-title").assertTextContains(context.getString(R.string.memory_title))
    }

    @Test fun leavingNativeShareReviewForChatDiscardsConsentAndReentryStartsAtHome() {
        val store = newStore()
        val nav = showMemory(store)
        compose.onNodeWithTag("memory-home-list").scrollToIndexWithClock(4)
        compose.onNodeWithTag("memory-scope-open").performClick()
        scrollListToTag("memory-scope-list", "memory-scope-review-human.md")
        compose.onNodeWithTag("memory-scope-review-human.md").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("memory-scope-consent").fetchSemanticsNode(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("memory-scope-consent").scrollIntoView().performClick()
        compose.onNodeWithTag("memory-scope-approve").assertIsEnabled()
        compose.runOnIdle { nav.controller.navigate(AppNavigationBackPolicy.CHAT_ROOT) }
        awaitRoute(nav, AppNavigationBackPolicy.CHAT_ROOT)
        compose.onNodeWithTag("memory-test-enter").assertIsDisplayed().performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY)
        compose.onNodeWithTag("memory-home-list").assertIsDisplayed()
        compose.onNodeWithTag("memory-scope-dialog").assertDoesNotExist()
        assertTrue(store.listSharedPersonalForUser().isEmpty())
    }

    @Test fun memoryFileRouteEncodesPathAndRetainsNewFlag() {
        val path = "notes/hello world/niño résumé.md"
        val encoded = AppNavigationBackPolicy.memoryFile(path, true)
        assertTrue(encoded.contains("%2F"))
        assertTrue(encoded.contains("%20"))
        assertTrue(encoded.contains("%C3"))
        assertTrue(encoded.endsWith("&new=true"))
        assertEquals("memory/file?path=$encodedPath&new=false", AppNavigationBackPolicy.memoryFile("notes/hello world.md", false))
    }

    private class NavHarness {
        lateinit var controller: NavHostController
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        val memoryChanged = AtomicInteger()
    }

    private fun showMemory(store: MemoryStore): NavHarness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val harness = NavHarness()
        val owner = activityResultOwner()
        compose.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    val controller = rememberNavController()
                    harness.controller = controller
                    harness.dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher)
                    val entry by controller.currentBackStackEntryAsState()
                    val route = entry?.destination?.route ?: AppNavigationBackPolicy.MEMORY
                    val metadata = AppNavigationBackPolicy.metadata(context, route, "Chat", null, null)
                    Scaffold(topBar = {
                        if (metadata.showTopBar) JarvysTopAppBar(
                            title = { Text(metadata.title, modifier = Modifier.testTag("memory-shared-title")) },
                            navigationIcon = {
                                if (metadata.isRoot) Text("", modifier = Modifier.testTag("memory-no-back"))
                                else AppRouteBackButton(onFallback = { controller.popBackStack() })
                            },
                        )
                    }) { padding ->
                        NavHost(controller, startDestination = AppNavigationBackPolicy.MEMORY,
                            modifier = Modifier.padding(padding)) {
                            composable(AppNavigationBackPolicy.CHAT_ROOT) {
                                TextButton(onClick = { controller.navigate(AppNavigationBackPolicy.MEMORY) },
                                    modifier = Modifier.testTag("memory-test-enter")) { Text("Enter Memory") }
                            }
                            memoryDestinations(
                                navController = controller, store = store, enabled = true, onEnabledChange = {},
                                conversationId = SESSION, onShowDisclosure = {},
                                onMemoryChanged = { harness.memoryChanged.incrementAndGet() },
                                reflectionEnabled = false,
                                reflectionStatus = "", lastReflectionMillis = 0L, reflecting = false,
                                onReflectionEnabledChange = {},
                                onReflectionDisclosure = {},
                            )
                        }
                    }
                }
            }
        }
        awaitRoute(harness, AppNavigationBackPolicy.MEMORY)
        return harness
    }

    private fun openNewNote(nav: NavHarness) {
        compose.onNodeWithTag("memory-home-list").scrollToIndexWithClock(4)
        compose.onNodeWithTag("memory-add-note").performClick()
        compose.onNodeWithText(text(R.string.memory_continue)).performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_FILE)
        compose.onNodeWithTag("memory-editor-save").scrollIntoView().assertIsDisplayed()
    }

    private fun openHistory(nav: NavHarness) {
        compose.onNodeWithTag("memory-home-list").scrollToIndexWithClock(4)
        compose.onNodeWithTag("memory-history").performClick()
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_HISTORY)
    }

    private fun openEditor(nav: NavHarness, path: String) {
        nav.controller.navigate(AppNavigationBackPolicy.memoryFile(path, false))
        awaitRoute(nav, AppNavigationBackPolicy.MEMORY_FILE)
    }

    private fun filterHistory(path: String) {
        compose.onNodeWithText(text(R.string.memory_history_path_filter)).performTextInput(path)
        compose.waitForIdle()
    }

    private fun scrollHomeToFiles() {
        compose.onNodeWithTag("memory-home-list").scrollToIndexWithClock(10)
    }

    private fun confirmDialogAction(value: String): String {
        val dialogAction = compose.onNode(hasText(value) and hasAnyAncestor(isDialog()))
        dialogAction.assertIsDisplayed()
        dialogAction.performClick()
        return "dialog descendant (hasText + isDialog ancestor)"
    }

    private fun awaitDeleteResult(nav: NavHarness, store: MemoryStore, matchingNodes: Int, clickedNode: String) {
        val routeAndFile = runCatching {
            compose.waitUntil(10_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                nav.controller.currentDestination?.route == AppNavigationBackPolicy.MEMORY
                        && runCatching { store.readUserFile("remove-me.md") }.isFailure
            }
        }
        if (routeAndFile.isFailure) {
            val readResult = runCatching { store.readUserFile("remove-me.md") }
            val readStatus = readResult.fold({ "exists" }, { "${it::class.java.simpleName}: ${it.message}" })
            throw AssertionError("Delete did not complete: route=${nav.controller.currentDestination?.route}; file=$readStatus; " +
                "semantics=\n${compose.onRoot(useUnmergedTree = true).printToString()}", routeAndFile.exceptionOrNull())
        }
        compose.waitForIdle()
        val readResult = runCatching { store.readUserFile("remove-me.md") }
        val readStatus = readResult.fold({ "success(content=${it.take(80)})" }, { "error=${it::class.java.simpleName}: ${it.message}" })
        val tree = compose.onRoot(useUnmergedTree = true).printToString()
        assertEquals(
            "Delete diagnostic: matchingNodes=$matchingNodes; clicked=$clickedNode; fileRead=$readStatus; semantics=\n$tree",
            AppNavigationBackPolicy.MEMORY,
            nav.controller.currentDestination?.route,
        )
        assertFalse("Delete diagnostic: matchingNodes=$matchingNodes; clicked=$clickedNode; fileRead=$readStatus; semantics=\n$tree",
            readResult.isSuccess)
    }

    private fun awaitMemoryMutationReturn(
        nav: NavHarness,
        store: MemoryStore,
        path: String,
        expectedContent: String,
        operation: String,
        matchingNodes: Int,
        clickedNode: String,
    ) {
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            nav.controller.currentDestination?.route == AppNavigationBackPolicy.MEMORY
                    && runCatching { store.readUserFile(path) == expectedContent }.getOrDefault(false)
        }
        compose.waitForIdle()
        val readResult = runCatching { store.readUserFile(path) }
        val readStatus = readResult.fold({ "success(content=${it.take(80)})" }, { "error=${it::class.java.simpleName}: ${it.message}" })
        val tree = compose.onRoot(useUnmergedTree = true).printToString()
        val diagnostic = "$operation diagnostic: matchingNodes=$matchingNodes; clicked=$clickedNode; route=${nav.controller.currentDestination?.route}; fileRead=$readStatus; semantics=\n$tree"
        assertEquals(diagnostic, AppNavigationBackPolicy.MEMORY, nav.controller.currentDestination?.route)
        assertEquals(diagnostic, expectedContent, readResult.getOrThrow())
    }

    private fun awaitRoute(nav: NavHarness, route: String) {
        // A controller route can change before NavHost's queued effects publish its content.
        // waitUntil advances the test clock; waitForIdle alone can leave queued effects idle.
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            nav.controller.currentDestination?.route == route && when (route) {
                AppNavigationBackPolicy.MEMORY ->
                    displayedTag("memory-home-list") && compose.onAllNodes(
                        hasText(text(R.string.memory_loading)) and
                            hasAnyAncestor(hasTestTag("memory-home-list")),
                    ).fetchSemanticsNodes().isEmpty()
                AppNavigationBackPolicy.MEMORY_FILE ->
                    displayedTag("memory-editor-body") && compose.onAllNodesWithTag("memory-editor-save")
                        .fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
                AppNavigationBackPolicy.MEMORY_HISTORY ->
                    compose.onAllNodesWithText(text(R.string.memory_history_path_filter))
                        .fetchSemanticsNodes().size == 1 &&
                        compose.onNodeWithText(text(R.string.memory_history_path_filter)).isDisplayed()
                AppNavigationBackPolicy.CHAT_ROOT -> displayedTag("memory-test-enter")
                else -> error("No readiness selector for $route")
            }
        }
        compose.waitForIdle()
    }

    private fun displayedTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 &&
            compose.onNodeWithTag(tag).isDisplayed()

    private fun awaitNode(target: SemanticsNodeInteraction) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            try { target.fetchSemanticsNode(); true } catch (_: AssertionError) { false }
        }
        target.assertExists()
    }

    /** Keep the same real index action, then execute its queued coroutine and native layout. */
    private fun SemanticsNodeInteraction.scrollToIndexWithClock(index: Int): SemanticsNodeInteraction {
        awaitNode(this)
        performScrollToIndex(index)
        compose.mainClock.advanceTimeByFrame()
        settleNativeFrames()
        return this
    }

    private fun scrollParent(node: SemanticsNode): SemanticsNode =
        generateSequence(node.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Memory control has no vertical semantic scroll parent: ${node.id}")

    /**
     * Same finite measured ScrollBy pattern as BotMascotPilotComposeTest. Compose 1.9's
     * performScrollTo repeatedly queues ScrollBy without advancing StandardTestDispatcher.
     * One real action per correction, clock advancement, and progress/visibility assertions
     * prevent a stuck scroll from exhausting the worker instead of reporting its geometry.
     */
    private fun SemanticsNodeInteraction.scrollIntoView(): SemanticsNodeInteraction {
        awaitNode(this)
        repeat(3) { correction ->
            val node = fetchSemanticsNode()
            val scroller = scrollParent(node)
            val viewport = scroller.boundsInRoot
            val top = node.positionInRoot.y
            val bottom = top + node.size.height
            val left = node.positionInRoot.x
            val right = left + node.size.width
            if (top >= viewport.top - 1f && bottom <= viewport.bottom + 1f &&
                left >= viewport.left - 1f && right <= viewport.right + 1f) return assertIsDisplayed()
            assertTrue("Memory control is taller than its scroll viewport: ${node.size} / $viewport",
                node.size.height <= viewport.height + 1f)
            val axis = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            assertFalse("Memory fixture expects normal vertical scrolling", axis.reverseScrolling)
            val wanted = top + node.size.height / 2f - viewport.center.y
            // Lazy lists expose an item-based axis, not a pixel range. Only clamp pixel axes.
            val delta = if (scroller.config.contains(SemanticsActions.ScrollToIndex)) wanted
                else wanted.coerceIn(-before, axis.maxValue() - before)
            assertTrue("Memory scroll cannot advance: pass=$correction axis=$before/${axis.maxValue()} " +
                "target=($left,$top,$right,$bottom) viewport=$viewport", abs(delta) >= 0.5f)
            scrollByAndSettle(scroller, delta)
            val updated = fetchSemanticsNode()
            val after = scrollParent(updated).config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Memory scroll made no progress: pass=$correction before=$before after=$after " +
                "delta=$delta targetTop=${updated.positionInRoot.y}", after != before)
        }
        val node = fetchSemanticsNode()
        val viewport = scrollParent(node).boundsInRoot
        assertTrue("Memory control remained clipped after three measured corrections: " +
            "position=${node.positionInRoot} size=${node.size} viewport=$viewport",
            node.positionInRoot.y >= viewport.top - 1f &&
                node.positionInRoot.y + node.size.height <= viewport.bottom + 1f &&
                node.positionInRoot.x >= viewport.left - 1f &&
                node.positionInRoot.x + node.size.width <= viewport.right + 1f)
        return assertIsDisplayed()
    }

    /** Page the actual lazy list with a bounded, progress-checked action per page. */
    private fun scrollListToTag(listTag: String, targetTag: String) {
        val list = compose.onNodeWithTag(listTag)
        awaitNode(list)
        compose.waitUntil(10_000) { list.fetchSemanticsNode().children.isNotEmpty() }
        list.scrollToIndexWithClock(0)
        repeat(32) { page ->
            if (compose.onAllNodesWithTag(targetTag).fetchSemanticsNodes().size == 1) {
                compose.onNodeWithTag(targetTag).scrollIntoView()
                return
            }
            val node = list.fetchSemanticsNode()
            val axis = node.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            assertFalse("Memory fixture expects normal vertical scrolling", axis.reverseScrolling)
            assertTrue("$targetTag missing at end of $listTag: page=$page axis=$before/${axis.maxValue()}",
                before < axis.maxValue())
            val delta = node.boundsInRoot.height * 0.8f
            assertTrue("$listTag has no scroll viewport: ${node.boundsInRoot}", delta >= 1f)
            scrollByAndSettle(node, delta)
            val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("$listTag made no progress: page=$page before=$before after=$after delta=$delta", after > before)
        }
        throw AssertionError("$targetTag was not found after 32 real, progress-checked pages of $listTag")
    }

    private fun scrollByAndSettle(scroller: SemanticsNode, delta: Float) {
        val action = requireNotNull(scroller.config[SemanticsActions.ScrollBy].action)
        compose.runOnUiThread { assertTrue("Semantic scroll must accept the request", action(0f, delta)) }
        // Virtual time for the finite spring scroll, matching the existing pilot fixture.
        compose.mainClock.advanceTimeBy(2_000)
        settleNativeFrames()
    }

    /** Settle existing host/dialog roots at their actual measured size, without resizing UI. */
    private fun settleNativeFrames() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.runOnUiThread {
                val roots = listOfNotNull(hostView.rootView,
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView).distinct()
                roots.forEach { root ->
                    val width = root.width
                    val height = root.height
                    assertTrue("Memory fixture root must already be laid out: $width x $height", width > 0 && height > 0)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(root.left, root.top, root.left + width, root.top + height)
                }
            }
            compose.waitForIdle()
        }
    }

    private fun newStore(): MemoryStore = MemoryStore(
        Files.createTempDirectory("n1c-memory-route").toFile(), true,
        testMemorySeedProvider(AppLanguageChoice.ENGLISH),
    ).forConversation(SESSION).also { it.ensureInitialized() }

    private fun document(name: String, body: String) =
        "---\nname: $name\ndescription: navigation test file\n---\n$body"

    private fun text(resource: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)

    private fun activityResultOwner(): ActivityResultRegistryOwner {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                         options: ActivityOptionsCompat?) { }
        }
        return object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    }

    companion object {
        private const val SESSION = "n1c-short-round"
        private const val encodedPath = "notes%2Fhello%20world.md"
    }
}

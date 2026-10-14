package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic memory only. Cross-conversation sharing remains an explicit action in this screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
class MemoryScopeReviewComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingTheScopeScreenDoesNotShareAnythingUntilItsActionIsSelected() {
        val stores = stores(); show(stores)
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
    }

    @Test fun shareCopiesOnlyTheSelectedImmutableVersionAndKeepsItsSource() {
        val stores = stores(); show(stores)
        share("local.md")
        waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
        val grant = stores.root.listSharedPersonalForUser().single { it.active }
        assertEquals(LOCAL, stores.other.read(grant.path))
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
    }

    @Test fun repeatedShareOfTheSameSnapshotIsIdempotent() {
        val stores = stores(); show(stores)
        repeat(2) {
            share("local.md")
            waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
            waitNode("memory-scope-dialog")
        }
        assertEquals(1, stores.root.listSharedPersonalForUser().size)
        assertEquals(LOCAL, stores.other.read(stores.root.listSharedPersonalForUser().single().path))
    }

    @Test fun legacyPreviewIsReadOnlyAndExplicitSharePreservesOriginalBytes() {
        val stores = stores(); val before = stores.legacyFile.readBytes(); show(stores)
        tab("LEGACY")
        scrollTo("memory-scope-read-legacy-legacy.md")
        compose.onNodeWithTag("memory-scope-read-legacy-legacy.md").performClick()
        waitNode("memory-scope-approved-content")
        compose.onNodeWithTag("memory-scope-approved-content").assertTextEquals(LEGACY)
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertArrayEquals(before, stores.legacyFile.readBytes())

        share("legacy.md")
        waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
        val grant = stores.root.listSharedPersonalForUser().single()
        assertTrue(grant.legacy); assertNull(grant.sourceConversationId)
        assertEquals(LEGACY, stores.other.read(grant.path))
        assertArrayEquals(before, stores.legacyFile.readBytes())
    }

    @Test fun revokingProjectionRemovesOnlySharedCopyAndKeepsSourceIntact() {
        val stores = stores(); show(stores); share("local.md")
        waitFor { stores.root.listSharedPersonalForUser().any { it.active } }
        val grant = stores.root.listSharedPersonalForUser().single()
        tab("PERSONAL_SHARED")
        scrollTo("memory-scope-revoke-${grant.id}")
        compose.onNodeWithTag("memory-scope-revoke-${grant.id}").performClick()
        waitFor { !stores.root.listSharedPersonalForUser().single().active }
        assertFalse(stores.other.compileSystemPromptProjection().contains(LOCAL))
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
    }

    @Test fun closingScopeWhileShareWaitsForStoreLockCancelsThePendingEffect() {
        val stores = stores(); val showing = show(stores)
        val lock = MemoryStore::class.java.getDeclaredField("lock").apply { isAccessible = true }
            .get(stores.local) as java.util.concurrent.locks.ReentrantLock
        lock.lock()
        try {
            compose.runOnIdle {
                scrollList("memory-scope-list", "memory-scope-review-local.md")
                compose.onNodeWithTag("memory-scope-review-local.md").performClick()
            }
            compose.onNodeWithTag("memory-scope-close").performClick()
        } finally { lock.unlock() }
        waitNode("away")
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertEquals(LOCAL, stores.local.readForUser("local.md"))
    }

    @Test fun changingSourceDuringShareNeverPublishesUnreviewedBytes() {
        val stores = stores(); show(stores)
        val lock = MemoryStore::class.java.getDeclaredField("lock").apply { isAccessible = true }
            .get(stores.local) as java.util.concurrent.locks.ReentrantLock
        lock.lock()
        val clicked = CountDownLatch(1)
        try {
            val thread = Thread {
                compose.runOnIdle {
                    scrollList("memory-scope-list", "memory-scope-review-local.md")
                    compose.onNodeWithTag("memory-scope-review-local.md").performClick()
                }
                clicked.countDown()
            }.apply { start() }
            assertTrue(clicked.await(5, TimeUnit.SECONDS))
            stores.local.writeUserFile("local.md", document("Changed", "New unshared bytes"),
                stores.local.latestRevisionId("local.md"), false, SESSION)
        } finally { lock.unlock() }
        waitFor { compose.onAllNodesWithText(compose.activity.getString(R.string.memory_scope_approval_failed)).fetchSemanticsNodes().isNotEmpty() ||
            stores.root.listSharedPersonalForUser().isNotEmpty() }
        assertTrue(stores.root.listSharedPersonalForUser().isEmpty())
        assertTrue(stores.local.readForUser("local.md").contains("New unshared bytes"))
    }

    private fun show(stores: Stores): androidx.compose.runtime.MutableState<Boolean> {
        val showing = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (showing.value) MemoryScopeReviewDialog(stores.local, stores.root, { showing.value = false }, {})
            else Text("Away", Modifier.testTag("away"))
        } }
        waitNode("memory-scope-dialog"); return showing
    }
    private fun share(path: String) { scrollTo("memory-scope-review-$path"); compose.onNodeWithTag("memory-scope-review-$path").performClick() }
    private fun tab(value: String) { compose.onNodeWithTag("memory-scope-tab-$value").performClick(); waitNode("memory-scope-dialog") }
    private fun scrollTo(tag: String) = waitFor { runCatching { scrollList("memory-scope-list", tag); compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
    private fun scrollList(listTag: String, tag: String) = compose.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag))
    private fun waitNode(tag: String) = waitFor { runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false) }
    private fun waitFor(block: () -> Boolean) { compose.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); block() }; compose.waitForIdle() }
    private data class Stores(val root: MemoryStore, val local: MemoryStore, val other: MemoryStore, val legacyFile: File)
    private fun stores(): Stores {
        val dir = Files.createTempDirectory("memory-native-sharing").toFile()
        val legacy = File(dir, "legacy.md").apply { writeText(LEGACY) }
        val root = MemoryStore(dir, true, testMemorySeedProvider(AppLanguageChoice.ENGLISH))
        val local = root.forConversation(SESSION).also {
            it.ensureInitialized(); it.writeUserFile("local.md", LOCAL, 0L, false, SESSION)
        }
        return Stores(root, local, root.forConversation("other").also { it.ensureInitialized() }, legacy)
    }
    companion object {
        private const val SESSION = "review-current-chat"
        private fun document(name: String, body: String) = "---\nname: $name\ndescription: Native review test\n---\n$body\n"
        private val LOCAL = document("Local note", "Use tea with breakfast.\nThis exact second line must also be reviewed.")
        private val LEGACY = document("Legacy original", "An older fact with accents: café y té.\r\nKeep original bytes.  ")
    }
}

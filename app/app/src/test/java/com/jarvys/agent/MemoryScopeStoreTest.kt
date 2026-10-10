package com.jarvys.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryScopeStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun root(name: String = "memory") =
        MemoryStore(temporaryFolder.newFolder(name), true, testMemorySeedProvider())

    private fun note(body: String) = "---\nname: Synthetic note\ndescription: Synthetic fixture.\n---\n$body\n"

    private fun denied(action: () -> Unit) {
        assertTrue("Operation must fail closed", runCatching(action).exceptionOrNull() is RuntimeException)
    }

    @Test fun defaultLegacyRootNeverSeedsMigratesRecoversProjectsOrFeedsAnAgent() {
        val root = root()
        val legacy = File(root.rootDirectory(), "mixed.md")
        val bytes = "Mixed legacy: PROJECT_ALPHA and PERSONAL_TEA\r\n".toByteArray()
        legacy.writeBytes(bytes)
        val journal = File(root.rootDirectory(), MemoryConstants.JOURNAL_FILE)
        val malformedAudit = "{legacy incomplete audit".toByteArray()
        journal.writeBytes(malformedAudit)
        root.ensureInitialized()
        assertArrayEquals(bytes, legacy.readBytes())
        assertArrayEquals(malformedAudit, journal.readBytes())
        assertFalse(File(root.rootDirectory(), MemoryConstants.INITIALIZED_FILE).exists())
        assertEquals("", root.compileSystemPromptProjection())
        assertEquals("", root.treeForPrompt())
        assertTrue(root.searchDocuments().isEmpty())
        assertEquals(listOf("mixed.md"), root.listFilesForUser().map { it.path })
        assertNull(root.listFilesForUser().single().modifiedBy)
        assertEquals(String(bytes), root.readForUser("mixed.md"))
        denied { root.read("mixed.md") }
        denied { root.list("/memory/") }
        denied { root.getRevision(1) }
        denied { root.listRevisions(null, null) }
        denied { root.write("new.md", note("bad"), MemoryStore.Actor.AGENT, "alpha") }
        denied { root.beginReflectionGroup("bad", "alpha") }
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        assertFalse(alpha.compileSystemPromptProjection().contains("PROJECT_ALPHA"))
        assertFalse(beta.searchDocuments().any { it.content.contains("PERSONAL_TEA") })
        assertArrayEquals(bytes, legacy.readBytes())
        assertArrayEquals(malformedAudit, journal.readBytes())
    }

    @Test fun localReadWriteListSearchProjectionAndRestartAreConversationBound() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        alpha.write("project.md", note("PROJECT_ALPHA_ONLY"), MemoryStore.Actor.AGENT, "alpha")
        beta.write("project.md", note("PROJECT_BETA_ONLY"), MemoryStore.Actor.AGENT, "beta")
        assertTrue(alpha.read("project.md").contains("PROJECT_ALPHA_ONLY"))
        assertFalse(alpha.read("project.md").contains("PROJECT_BETA_ONLY"))
        assertTrue(beta.searchDocuments().any { it.content.contains("PROJECT_BETA_ONLY") })
        assertFalse(beta.searchDocuments().any { it.content.contains("PROJECT_ALPHA_ONLY") })
        assertFalse(beta.compileSystemPromptProjection().contains("PROJECT_ALPHA_ONLY"))
        val gamma = root.forConversation("gamma")
        assertFalse(gamma.list("/memory/").any { it.contains("project.md") })
        denied { gamma.read("project.md") }
        assertSame(alpha, alpha.forConversation("alpha"))
        denied { alpha.forConversation("beta") }
        denied { alpha.write("steal.md", note("bad"), MemoryStore.Actor.AGENT, "beta") }
        denied { alpha.write("steal.md", note("bad"), MemoryStore.Actor.REFLECTION, null) }
        denied { alpha.write("steal.md", note("bad"), MemoryStore.Actor.USER, "beta") }
        denied { alpha.beginReflectionGroup("cross_scope", "beta") }
        denied { alpha.read("/memory/.scopes/hidden.md") }
        denied { alpha.read("../project.md") }
        denied { alpha.write(".memory-initialized.md", note("bad"), MemoryStore.Actor.AGENT, "alpha") }
        val reopened = MemoryStore(root.rootDirectory(), true, testMemorySeedProvider())
        assertTrue(reopened.forConversation("alpha").read("project.md").contains("PROJECT_ALPHA_ONLY"))
        assertTrue(reopened.forConversation("beta").read("project.md").contains("PROJECT_BETA_ONLY"))
        assertTrue(alpha.listRevisions(null, null).all { it.conversationId == "alpha" })
        val digest = MessageDigest.getInstance("SHA-256").digest("alpha".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(File(root.rootDirectory(), ".scopes/$digest"), alpha.rootDirectory())
    }

    @Test fun exactExplicitPersonalApprovalCreatesOpaqueImmutableSnapshotAndSurvivesRestart() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        val content = note("I prefer SYNTHETIC_JASMINE_TEA")
        alpha.write("private-name.md", content, MemoryStore.Actor.AGENT, "alpha")
        assertFalse(beta.compileSystemPromptProjection().contains("SYNTHETIC_JASMINE_TEA"))
        val review = alpha.reviewForSharing("private-name.md")
        assertEquals(content, review.content)
        assertEquals("alpha", review.sourceConversationId)
        assertFalse(review.legacy)
        assertEquals(64, review.sha256.length)
        denied { beta.approveSharedPersonal(review) }
        val grant = alpha.approveSharedPersonal(review)
        assertEquals("ACTIVE", grant.status)
        assertFalse(grant.path.contains("alpha"))
        assertFalse(grant.path.contains("private-name"))
        assertEquals(content, beta.read(grant.path))
        assertTrue(beta.list("/memory/").any { it == "DIR  shared/" })
        assertTrue(beta.list("shared").any { it.contains(grant.id) })
        assertTrue(beta.read("shared/MEMORY.md").contains(grant.id))
        assertTrue(beta.compileSystemPromptProjection().contains("SYNTHETIC_JASMINE_TEA"))
        assertFalse(beta.compileSystemPromptProjection().contains("private-name.md"))
        assertTrue(beta.searchDocuments().any { it.path == grant.path && it.content == content })
        assertEquals(grant.id, alpha.approveSharedPersonal(review).id)
        val restarted = MemoryStore(root.rootDirectory(), true, testMemorySeedProvider()).forConversation("beta")
        assertEquals(content, restarted.read(grant.path))
        denied { beta.write(grant.path, note("replaced"), MemoryStore.Actor.AGENT, "beta") }
        denied { beta.edit(grant.path, "JASMINE", "GREEN", MemoryStore.Actor.REFLECTION, "beta") }
        denied { beta.delete(grant.path, MemoryStore.Actor.USER, "beta") }
        denied { beta.createDirectoryForUser("SHARED/forged", "beta") }
        denied { beta.write("Shared/forged.md", note("forged"), MemoryStore.Actor.AGENT, "beta") }
    }

    @Test fun changedSourceRejectsOldReviewAndNeverBroadcastsOrResurrectsNewBytes() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        val first = note("PREFERENCE_FIRST")
        alpha.write("choice.md", first, MemoryStore.Actor.USER, "alpha")
        val oldReview = alpha.reviewForSharing("choice.md")
        val grant = alpha.approveSharedPersonal(oldReview)
        alpha.write("choice.md", note("UNAPPROVED_NEW_PRIVATE_VALUE"), MemoryStore.Actor.AGENT, "alpha")
        denied { alpha.approveSharedPersonal(oldReview) }
        denied { beta.read(grant.path) }
        assertFalse(beta.compileSystemPromptProjection().contains("UNAPPROVED_NEW_PRIVATE_VALUE"))
        assertFalse(beta.compileSystemPromptProjection().contains("PREFERENCE_FIRST"))
        assertFalse(beta.searchDocuments().any { it.path == grant.path })
        assertEquals("STALE", beta.listSharedPersonalForUser().single().status)
        assertEquals(first, beta.readSharedPersonalForUser(grant.id))
        alpha.write("choice.md", first, MemoryStore.Actor.USER, "alpha")
        denied { beta.read(grant.path) }
        val replacement = alpha.approveSharedPersonal(alpha.reviewForSharing("choice.md"))
        assertNotEquals(grant.id, replacement.id)
        assertEquals(first, beta.read(replacement.path))
        root.revokeSharedPersonal(replacement.id)
        denied { beta.read(replacement.path) }
        assertEquals("REVOKED", beta.listSharedPersonalForUser().first { it.id == replacement.id }.status)
        assertEquals(first, root.readSharedPersonalForUser(replacement.id))
        val restarted = MemoryStore(root.rootDirectory(), true, testMemorySeedProvider()).forConversation("beta")
        denied { restarted.read(replacement.path) }
    }

    @Test fun legacyMissingOrCorruptAuditNeedsFullExactReviewWithoutInferringOrigin() {
        for ((index, audit) in listOf<String?>(null, "{not an audit", """{"version":1,"revisions":[{"id":7,"path":"mixed.md","conversationId":"guessed-last-conversation"}]}""").withIndex()) {
            val root = root("legacy-$index")
            val bytes = "LEGACY_MIXED_PROJECT_A_AND_PERSONAL_B\r\n".toByteArray()
            File(root.rootDirectory(), "mixed.md").writeBytes(bytes)
            if (audit != null) File(root.rootDirectory(), MemoryConstants.JOURNAL_FILE).writeText(audit)
            val alpha = root.forConversation("guessed-last-conversation")
            assertFalse(alpha.compileSystemPromptProjection().contains("LEGACY_MIXED"))
            val review = root.reviewForSharing("mixed.md")
            assertTrue(review.legacy)
            assertNull(review.sourceConversationId)
            assertEquals(String(bytes), review.content)
            assertTrue(root.listSharedPersonalForUser().isEmpty())
            val approved = root.approveSharedPersonal(review)
            assertEquals(String(bytes), alpha.read(approved.path))
            assertArrayEquals(bytes, File(root.rootDirectory(), "mixed.md").readBytes())
            if (audit != null) assertEquals(audit, File(root.rootDirectory(), MemoryConstants.JOURNAL_FILE).readText())
        }
    }

    @Test fun missingAndCorruptGrantMetadataFailClosedWithoutMutatingLegacyOrSource() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        alpha.write("choice.md", note("PERSONAL_FIXTURE"), MemoryStore.Actor.USER, "alpha")
        val grant = alpha.approveSharedPersonal(alpha.reviewForSharing("choice.md"))
        val metadata = File(root.rootDirectory(), ".shared-personal/${grant.id}.json")
        val original = metadata.readBytes()
        metadata.writeText("{bad}")
        denied { beta.read(grant.path) }
        assertFalse(beta.searchDocuments().any { it.path == grant.path })
        metadata.writeBytes(original)
        val forged = JSONObject(metadata.readText()).put("content", note("FORGED_BYTES"))
        metadata.writeText(forged.toString())
        denied { beta.read(grant.path) }
        assertFalse(beta.compileSystemPromptProjection().contains("FORGED_BYTES"))
        metadata.delete()
        denied { beta.read(grant.path) }
        assertTrue(alpha.read("choice.md").contains("PERSONAL_FIXTURE"))
    }

    @Test fun sameContentExternalEditAndClearCannotReuseTheReviewedVersion() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        alpha.write("choice.md", note("SOURCE_ORIGINAL"), MemoryStore.Actor.USER, "alpha")
        val review = alpha.reviewForSharing("choice.md")
        val grant = alpha.approveSharedPersonal(review)
        val source = File(alpha.rootDirectory(), "choice.md")
        assertTrue(source.setLastModified(source.lastModified() + 2000))
        denied { alpha.approveSharedPersonal(review) }
        denied { beta.read(grant.path) }
        val reapproved = alpha.approveSharedPersonal(alpha.reviewForSharing("choice.md"))
        assertEquals("ACTIVE", reapproved.status)
        alpha.clearAll(MemoryStore.Actor.USER)
        alpha.write("choice.md", note("SOURCE_ORIGINAL"), MemoryStore.Actor.USER, "alpha")
        denied { beta.read(reapproved.path) }
        denied { alpha.clearAll(MemoryStore.Actor.AGENT) }
    }

    @Test fun globalListenersOnlyInvalidateAndNeverReceiveScopedRevisionBytes() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        alpha.ensureInitialized(); beta.ensureInitialized()
        val global = mutableListOf<MemoryStore.Revision>()
        val local = mutableListOf<MemoryStore.Revision>()
        var invalidations = 0
        val globalListener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) { global.add(revision) }
            override fun onMemoryCleared() { invalidations++ }
        }
        val betaListener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) { local.add(revision) }
        }
        MemoryStore.addGlobalSearchListener(globalListener)
        beta.addChangeListener(betaListener)
        try {
            alpha.write("secret-project.md", note("ALPHA_PRIVATE"), MemoryStore.Actor.AGENT, "alpha")
            assertTrue(global.isEmpty())
            assertTrue(local.isEmpty())
            assertTrue(invalidations > 0)
            beta.write("own.md", note("BETA_LOCAL"), MemoryStore.Actor.AGENT, "beta")
            assertEquals(1, local.size)
            assertEquals("beta", local.single().conversationId)
        } finally {
            MemoryStore.removeGlobalSearchListener(globalListener)
            beta.removeChangeListener(betaListener)
        }
    }

    @Test fun concurrentIndependentScopesAndApprovalAgainstSourceMutationRemainSafe() {
        val root = root()
        val alpha = root.forConversation("alpha")
        val beta = root.forConversation("beta")
        val started = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0 until 2).map { index -> executor.submit(Callable {
                started.await()
                val scope = if (index == 0) alpha else beta
                val id = if (index == 0) "alpha" else "beta"
                repeat(15) { scope.write("note-$it.md", note("$id-VALUE-$it"), MemoryStore.Actor.AGENT, id) }
            }) }
            started.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            assertFalse(alpha.searchDocuments().any { it.content.contains("beta-VALUE") })
            assertFalse(beta.searchDocuments().any { it.content.contains("alpha-VALUE") })
            val review = alpha.reviewForSharing("note-0.md")
            val race = CountDownLatch(1)
            val approval = executor.submit(Callable {
                race.await(); runCatching { alpha.approveSharedPersonal(review) }.getOrNull()
            })
            val mutation = executor.submit(Callable {
                race.await(); alpha.write("note-0.md", note("NEVER_APPROVED_CHANGED"), MemoryStore.Actor.AGENT, "alpha")
            })
            race.countDown()
            mutation.get(20, TimeUnit.SECONDS)
            approval.get(20, TimeUnit.SECONDS)?.let { denied { beta.read(it.path) } }
            assertFalse(beta.compileSystemPromptProjection().contains("NEVER_APPROVED_CHANGED"))
            assertFalse(beta.searchDocuments().any { it.content.contains("NEVER_APPROVED_CHANGED") })
        } finally { executor.shutdownNow() }
    }
}

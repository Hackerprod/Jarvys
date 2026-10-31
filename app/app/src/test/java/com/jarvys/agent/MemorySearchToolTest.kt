package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemorySearchToolTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun searchFilesCoversAllowedZonesReturnsAllSortedSnippetsAndMarksDataUntrusted() {
        val fixture = fixture("search-zones")
        val (workspace, coordinator, registry, skillsRoot, _) = fixture
        try {
            workspace.write("docs/one.md", "heading\ncontext\norchid plans are ready\nclosing")
            workspace.write("docs/two.md", "orchid is also mentioned here")
            val skill = File(skillsRoot, "acme.docs/guide.md").apply {
                parentFile!!.mkdirs()
                writeText("orchid skill reference")
            }
            assertTrue(skill.isFile)


            val result = registry.invoke("search_files", mapOf("query" to "orchid"), CancellationToken.uncancellable())
            assertTrue(result.success)
            assertTrue(result.content.startsWith("UNTRUSTED FILE DATA"))
            assertTrue(result.content.contains("path: docs/one.md"))
            assertTrue(result.content.contains("path: docs/two.md"))
            assertTrue(result.content.contains("path: /skills/acme.docs/guide.md"))
            assertFalse(result.content.contains("/memory/"))
            assertTrue(result.content.contains(" | modified: "))
            assertTrue(result.content.contains("lines 2-4:"))
            assertTrue(result.content.contains("3: orchid plans are ready"))
            assertEquals(3, Regex("path:").findAll(result.content).count())

            val limited = registry.invoke("search_files", mapOf("query" to "orchid", "limit" to 1), CancellationToken.uncancellable())
            assertEquals(1, Regex("path:").findAll(limited.content).count())
            val workOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "workspace"),
                CancellationToken.uncancellable())
            assertFalse(workOnly.content.contains("/memory/"))
            assertFalse(workOnly.content.contains("/skills/"))
            val memoryOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "memory"),
                CancellationToken.uncancellable())
            assertFalse(memoryOnly.success)
            assertTrue(memoryOnly.content.contains("unsupported"))
            assertFalse(memoryOnly.content.contains("docs/one.md"))
            val skillsOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "skills"),
                CancellationToken.uncancellable())
            assertTrue(skillsOnly.content.contains("/skills/acme.docs/guide.md"))
            assertFalse(skillsOnly.content.contains("/memory/"))
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun invalidZonesAndLimitsFailAndSearchCannotTraverseOrReadOutsideFiles() {
        val (workspace, coordinator, registry, _, projectRoot) = fixture("search-boundaries")
        try {
            val outside = File(temporaryFolder.root, "outside.md").apply { writeText("orchid outside-secret") }
            workspace.write("visible.md", "orchid inside")
            val link = File(projectRoot, "escape.md")
            runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))

            assertFalse(registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "/memory/../../"),
                CancellationToken.uncancellable()).success)
            assertFalse(registry.invoke("search_files", mapOf("query" to "orchid", "limit" to 0),
                CancellationToken.uncancellable()).success)
            val result = registry.invoke("search_files", mapOf("query" to "orchid"), CancellationToken.uncancellable())
            assertTrue(result.content.contains("visible.md"))
            assertFalse(result.content.contains("outside-secret"))
            assertFalse(result.content.contains("escape.md"))
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun possibleSecretDocumentsAreReturnedWithoutAnyContentFragments() {
        val (workspace, coordinator, registry, _, _) = fixture("search-secrets")
        try {
            workspace.write("secret.md", "note\napi_key=ABCD1234EFGH\nfooter")
            val result = registry.invoke("search_files", mapOf("query" to "ABCD1234EFGH"),
                CancellationToken.uncancellable())
            assertTrue(result.success)
            assertTrue(result.content.contains("contains possible secret; snippets omitted"))
            assertFalse(result.content.contains("ABCD1234EFGH"))
            assertFalse(result.content.contains("api_key"))
        } finally { coordinator.disposeForTests() }
    }

    @Test fun removedMemoryZoneIsDeniedRegardlessOfFileContents() {
        val (workspace, coordinator, registry, _, projectRoot) = fixture("no-memory")
        try {
            File(projectRoot, "memory").mkdirs()
            File(projectRoot, "memory/private.md").writeText("isolatedtoken")
            val result = registry.invoke("search_files", mapOf("query" to "isolatedtoken", "zone" to "memory"), CancellationToken.uncancellable())
            assertFalse(result.success)
            assertTrue(result.content.contains("unsupported"))
            assertFalse(registry.invoke("read", mapOf("path" to "/memory/private.md"), CancellationToken.uncancellable()).success)
        } finally { coordinator.disposeForTests() }
    }

    @Test fun searchCatalogRemainsChatOnlyAndDoesNotRestoreReflectionFileTools() {
        val (workspace, coordinator, _, _, _) = fixture("search-scopes")
        try {
            val mainChat = WorkspaceTools.createWithSearchForTests(workspace, coordinator)
            assertTrue(mainChat.any { it.declaration().name == "search_files" })
            assertEquals(listOf("ls", "read", "write", "edit", "preview_workspace"), WorkspaceTools.names())
            assertFalse(WorkspaceTools.create(workspace).any { it.declaration().name == "search_files" })
            assertFalse(CoreAgentRuntime.crewBotCapabilityScope(CoreToolRegistry(mainChat)).names().contains("search_files"))
            assertFalse(mainChat.any { it.declaration().name == "delete" })
        } finally { coordinator.disposeForTests() }
    }

    @Test fun scheduledTasksSearchOnlyGrantedSkillsNeverWorkspaceOrFileMemory() {
        val (workspace, coordinator, _, skillsRoot, projectRoot) = fixture("task-file-scope")
        try {
            workspace.write("private-project.md", "project-only marker")
            File(skillsRoot, "acme.docs/guide.md").apply { parentFile!!.mkdirs(); writeText("scheduled task skill reference") }
            File(skillsRoot, "other.private/guide.md").apply { parentFile!!.mkdirs(); writeText("unapproved private marker") }
            val scope = WorkspaceStore(temporaryFolder.newFolder("task-only"),
                WorkspaceStore.projectIdForSession("task"), skillsRoot, null, "task", true)
            val registry = CoreToolRegistry(WorkspaceTools.createScheduledTask(scope, coordinator, listOf("acme.docs")))
            assertEquals(setOf("search_files"), registry.names().toSet())
            val search = registry.invoke("search_files", mapOf("query" to "scheduled task"), CancellationToken.uncancellable())
            assertTrue(search.success)
            assertTrue(search.content.contains("/skills/acme.docs/guide.md"))
            assertFalse(search.content.contains("private-project.md"))
            val denied = registry.invoke("search_files", mapOf("query" to "unapproved private marker", "zone" to "skills"), CancellationToken.uncancellable())
            assertEquals("No files matched the query.", denied.content)
            for (zone in listOf("workspace", "memory")) {
                assertFalse(registry.invoke("search_files", mapOf("query" to "project", "zone" to zone), CancellationToken.uncancellable()).success)
            }
            assertTrue(File(projectRoot, "private-project.md").isFile)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                WorkspaceTools.createScheduledTask(workspace, coordinator, listOf("acme.docs"))
            }
        } finally { coordinator.disposeForTests() }
    }

    private fun fixture(name: String): Fixture {
        val workspacesRoot = File(temporaryFolder.root, "$name-project")
        val projectId = WorkspaceStore.projectIdForSession(name)
        val skillsRoot = File(temporaryFolder.root, "$name-skills").apply { mkdirs() }
        val workspace = WorkspaceStore(workspacesRoot, projectId, skillsRoot,
            object : WorkspaceStore.SkillWorkspaceObserver {
                override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) { writeFile.run() }
            }, name, false)
        val coordinator = MemorySearchIndexCoordinator(FileMemorySearchIndex(File(temporaryFolder.root, "$name-index.bin")))
        return Fixture(workspace, coordinator, CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, coordinator)), skillsRoot, File(workspacesRoot, projectId))
    }
    private data class Fixture(val workspace: WorkspaceStore, val coordinator: MemorySearchIndexCoordinator,
        val registry: CoreToolRegistry, val skillsRoot: File, val projectRoot: File)
}

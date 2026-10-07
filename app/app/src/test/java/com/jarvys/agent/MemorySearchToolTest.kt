package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemorySearchToolTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun searchFilesCoversAllowedZonesReturnsAllSortedSnippetsAndMarksDataUntrusted() {
        val fixture = fixture("search-zones")
        val (workspace, memory, coordinator, registry, skillsRoot, _) = fixture
        try {
            workspace.write("docs/one.md", "heading\ncontext\norchid plans are ready\nclosing")
            workspace.write("docs/two.md", "orchid is also mentioned here")
            val skill = File(skillsRoot, "acme.docs/guide.md").apply {
                parentFile!!.mkdirs()
                writeText("orchid skill reference")
            }
            assertTrue(skill.isFile)
            memory.write("preferences.md", note("Preferences", "orchid is a saved preference."),
                MemoryStore.Actor.USER, "search-zones")

            val result = registry.invoke("search_files", mapOf("query" to "orchid"), CancellationToken.uncancellable())
            assertTrue(result.success)
            assertTrue(result.content.startsWith("UNTRUSTED FILE DATA"))
            assertTrue(result.content.contains("path: docs/one.md"))
            assertTrue(result.content.contains("path: docs/two.md"))
            assertTrue(result.content.contains("path: /skills/acme.docs/guide.md"))
            assertTrue(result.content.contains("path: /memory/preferences.md"))
            assertTrue(result.content.contains("latest revision:"))
            assertTrue(result.content.contains(" | modified: "))
            assertTrue(result.content.contains("lines 2-4:"))
            assertTrue(result.content.contains("3: orchid plans are ready"))
            assertEquals(4, Regex("path:").findAll(result.content).count())

            val limited = registry.invoke("search_files", mapOf("query" to "orchid", "limit" to 1), CancellationToken.uncancellable())
            assertEquals(1, Regex("path:").findAll(limited.content).count())
            val workOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "workspace"),
                CancellationToken.uncancellable())
            assertFalse(workOnly.content.contains("/memory/"))
            assertFalse(workOnly.content.contains("/skills/"))
            val memoryOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "memory"),
                CancellationToken.uncancellable())
            assertTrue(memoryOnly.content.contains("/memory/preferences.md"))
            assertFalse(memoryOnly.content.contains("docs/one.md"))
            val skillsOnly = registry.invoke("search_files", mapOf("query" to "orchid", "zone" to "skills"),
                CancellationToken.uncancellable())
            assertTrue(skillsOnly.content.contains("/skills/acme.docs/guide.md"))
            assertFalse(skillsOnly.content.contains("/memory/"))
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun invalidZonesAndLimitsFailAndSearchCannotTraverseOrReadOutsideFiles() {
        val (workspace, _, coordinator, registry, _, projectRoot) = fixture("search-boundaries")
        try {
            val outside = File(temporaryFolder.root, "outside.md").apply { writeText("orchid outside-secret") }
            workspace.write("visible.md", "orchid inside")
            val link = File(projectRoot, "escape.md")
            runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }

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
        val (workspace, _, coordinator, registry, _, _) = fixture("search-secrets")
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

    @Test
    fun aChatWithoutMemoryCannotSearchTheMemoryZone() {
        val files = temporaryFolder.newFolder("no-memory-files")
        val memory = MemoryStore(File(files, "memory"), true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val workspace = WorkspaceStore(File(files, "workspaces"), "f".repeat(24), File(files, "skills"), null,
            memory, "no-memory-session", false)
        val coordinator = MemorySearchIndexCoordinator(
            FileMemorySearchIndex(File(files, "index/no-memory.bin")), false)
        try {
            val registry = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, coordinator))
            val result = registry.invoke("search_files", mapOf("query" to "preference", "zone" to "memory"),
                CancellationToken.uncancellable())
            assertFalse(result.success)
            assertTrue(result.content.contains("zone is unavailable"))
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun searchToolIsAddedToChatFilesButNotCrewOrReflectionAndSt1NamesStayFixed() {
        val (workspace, memory, coordinator, _, _, _) = fixture("search-scopes")
        try {
            val mainChat = WorkspaceTools.createWithSearchForTests(workspace, coordinator)
            assertTrue(mainChat.any { it.declaration().name == "search_files" })
            assertEquals(listOf("ls", "read", "write", "edit", "preview_workspace"), WorkspaceTools.names())
            assertFalse(WorkspaceTools.create(workspace).any { it.declaration().name == "search_files" })

            val crewWorkspace = WorkspaceStore(temporaryFolder.newFolder("crew-workspace"),
                WorkspaceStore.projectIdForSession("crew-session"))
            assertFalse(WorkspaceTools.create(crewWorkspace).any { it.declaration().name == "search_files" })

            val reflectionWorkspace = WorkspaceStore(temporaryFolder.newFolder("reflection-workspace"),
                "e".repeat(24), null, null, memory, "reflection-session", true,
                MemoryStore.Actor.REFLECTION, "reflection-group", true)
            assertFalse(WorkspaceTools.createReflectionMemoryOnly(reflectionWorkspace)
                .any { it.declaration().name == "search_files" })
            assertFalse(CoreAgentRuntime.crewBotCapabilityScope(CoreToolRegistry(mainChat)).names()
                .contains("search_files"))
        } finally { coordinator.disposeForTests() }
    }

    private fun fixture(name: String): Fixture {
        val projectRoot = File(temporaryFolder.root, "$name-project")
        val skillsRoot = File(temporaryFolder.root, "$name-skills").apply { mkdirs() }
        val memoryRoot = File(temporaryFolder.root, "$name-memory")
        val memory = MemoryStore(memoryRoot, true, testMemorySeedProvider())
        val workspace = WorkspaceStore(projectRoot, WorkspaceStore.projectIdForSession(name), skillsRoot,
            object : WorkspaceStore.SkillWorkspaceObserver {
                override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) {
                    writeFile.run()
                }
            }, memory, name)
        val coordinator = MemorySearchIndexCoordinator(
            FileMemorySearchIndex(File(temporaryFolder.root, "$name-index.bin")), true)
        val registry = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, coordinator))
        return Fixture(workspace, memory, coordinator, registry, skillsRoot, projectRoot)
    }

    private data class Fixture(
        val workspace: WorkspaceStore,
        val memory: MemoryStore,
        val coordinator: MemorySearchIndexCoordinator,
        val registry: CoreToolRegistry,
        val skillsRoot: File,
        val projectRoot: File,
    )

    private fun note(name: String, body: String) = "---\nname: $name\ndescription: Saved preference\n---\n$body\n"
}

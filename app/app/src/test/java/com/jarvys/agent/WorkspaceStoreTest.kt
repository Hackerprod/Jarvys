package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.jarvys.agent.skills.SkillMarkdownParser
import java.io.File
import java.nio.file.Files
import com.jarvys.agent.mcp.McpCredentialVault
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.skills.SkillRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun contextDerivedWorkspaceAndSkillRootsAllowPlatformSymlinkedFilesDirAncestor() {
        val real = temporaryFolder.newFolder("workspace-real")
        val realFiles = File(real, "files").apply { mkdirs() }
        val alias = File(temporaryFolder.root, "workspace-platform-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val filesDir = File(alias, "files")
        val app = FilesDirContext(ApplicationProvider.getApplicationContext(), filesDir)
        val session = "production-symlink-workspace"
        val projectId = WorkspaceStore.projectIdForSession(session)

        val board = WorkspaceStore.forCrewBoard(app, session)
        board.write("notes/guide.md", "Production Context path follows the filesDir alias.")
        val physicalWorkspace = File(realFiles, "jarvys/workspaces/$projectId/notes/guide.md")
        assertTrue(physicalWorkspace.isFile)
        assertEquals("Production Context path follows the filesDir alias.", board.read("notes/guide.md"))

        val skillId = "com.jarvys.symlinked"
        val skillFile = File(realFiles, "skills/$skillId/guide.md").apply {
            parentFile!!.mkdirs()
            writeText("Skill root is canonicalized.")
        }
        val mcpInstance = McpServerRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val skillInstance = SkillRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousMcp = mcpInstance.get(null)
        val previousSkills = skillInstance.get(null)
        try {
            mcpInstance.set(null, McpServerRepository(app, object : McpCredentialVault {
                override fun get(serverId: String, key: String): String? = null
                override fun save(serverId: String, key: String, value: String) = Unit
                override fun clear(serverId: String) = Unit
            }))
            skillInstance.set(null, null)
            val workspace = WorkspaceStore(app, projectId, "symlink-session", false)
            assertEquals("Production Context path follows the filesDir alias.", board.read("notes/guide.md"))
            assertEquals("Skill root is canonicalized.", workspace.read("/skills/$skillId/guide.md"))
            assertTrue(workspace.list("/skills").any { it == "DIR  $skillId" })
            assertTrue(skillFile.isFile)
        } finally {
            mcpInstance.set(null, previousMcp)
            skillInstance.set(null, previousSkills)
        }
    }

    @Test
    fun filesPersistInsideProjectRootAndSupportReadWriteEditAndPreviewCheck() {
        val projectId = WorkspaceStore.projectIdForSession("session-alpha")
        assertEquals(24, projectId.length)
        val store = WorkspaceStore(temporaryFolder.root, projectId)
        assertFalse(store.hasIndexHtml())
        store.write("index.html", "<h1>Draft</h1>")
        store.write("assets/site.css", "body { color: blue; }")

        assertTrue(store.hasIndexHtml())
        assertEquals(listOf("DIR  assets", "FILE index.html (14 bytes)"), store.list("."))
        assertEquals("<h1>Draft</h1>", store.read("index.html"))
        assertEquals("Updated index.html", store.edit("index.html", "Draft", "Ready"))
        assertEquals("<h1>Ready</h1>", store.read("index.html"))
        assertEquals("assets", store.list(".").first().removePrefix("DIR  "))
        assertEquals("<h1>Ready</h1>", WorkspaceStore(temporaryFolder.root, projectId).read("index.html"))
    }

    @Test
    fun rejectsAbsoluteParentTraversalAndOversizedFiles() {
        val store = WorkspaceStore(temporaryFolder.root, WorkspaceStore.projectIdForSession("session-safe"))
        val outside = temporaryFolder.root.toPath().resolve("outside.txt").toFile()
        outside.writeText("safe")

        listOf("../outside.txt", outside.absolutePath, "folder/../../outside.txt").forEach { path ->
            runCatching { store.read(path) }.onSuccess { error("Accepted path outside project: $path") }
        }
        runCatching { store.write("large.txt", "x".repeat(WorkspaceStore.MAX_FILE_BYTES + 1)) }
            .onSuccess { error("Accepted oversized workspace file") }
        assertEquals("safe", outside.readText())
    }

    @Test
    fun skillsZoneWritesSupportFilesThenValidateAndRescanSkillMarkdown() {
        val projectId = WorkspaceStore.projectIdForSession("skills-zone")
        val projectRoot = temporaryFolder.newFolder("projects")
        val skillsRoot = temporaryFolder.newFolder("installed-skills")
        var committedWrites = 0
        val observer = object : WorkspaceStore.SkillWorkspaceObserver {
            override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) {
                if (skillMarkdown != null) {
                    val parsed = SkillMarkdownParser.parse(skillMarkdown)
                    require(parsed.metadata.id == skillId) { "SKILL.md id must match directory" }
                    val tools = WorkspaceTools.acceptedNames() + "read_skill"
                    val unknown = parsed.metadata.allowedTools.filterNot { it in tools }
                    require(unknown.isEmpty()) {
                        "Unknown or disabled tools: ${unknown.joinToString() }"
                    }
                }
                writeFile.run()
                committedWrites++
            }
        }
        val workspace = WorkspaceStore(projectRoot, projectId, skillsRoot, observer)
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))

        val support = tools.invoke("write", mapOf<String, Any>(
            "path" to "/skills/com.jarvys.example/references/guide.md",
            "content" to "Support instructions",
        ), CancellationToken.uncancellable())
        assertTrue(support.success)
        assertEquals("Support instructions", File(skillsRoot, "com.jarvys.example/references/guide.md").readText())
        assertEquals(1, committedWrites)
        assertEquals(listOf("DIR  com.jarvys.example"), workspace.list("/skills"))

        val invalid = tools.invoke("write", mapOf<String, Any>(
            "path" to "/skills/com.jarvys.invalid/SKILL.md",
            "content" to "not frontmatter",
        ), CancellationToken.uncancellable())
        assertFalse(invalid.success)
        assertTrue(invalid.content.contains("frontmatter"))
        assertFalse(File(skillsRoot, "com.jarvys.invalid/SKILL.md").exists())
        assertEquals(1, committedWrites)

        val unknownTool = tools.invoke("write", mapOf<String, Any>(
            "path" to "/skills/com.jarvys.invalid/SKILL.md",
            "content" to """---
id: com.jarvys.invalid
name: Invalid skill
description: It names a missing tool
version: 1
allowed-tools:
  - missing_tool
---
# Instructions
""".trimIndent(),
        ), CancellationToken.uncancellable())
        assertFalse(unknownTool.success)
        assertTrue(unknownTool.content.contains("Unknown or disabled tools: missing_tool"))
        assertFalse(File(skillsRoot, "com.jarvys.invalid/SKILL.md").exists())
        assertEquals(1, committedWrites)

        val markdown = """---
id: com.jarvys.example
name: Example skill
description: A workspace-created skill
version: 1
allowed-tools:
  - read
---
# Instructions
Read and verify the project files.
""".trimIndent()
        val saved = tools.invoke("write", mapOf<String, Any>(
            "path" to "/skills/com.jarvys.example/SKILL.md",
            "content" to markdown,
        ), CancellationToken.uncancellable())
        assertTrue(saved.success)
        assertTrue(saved.content.contains("jarvys://skills/com.jarvys.example/SKILL.md"))
        assertEquals(2, committedWrites)
        assertEquals(markdown, workspace.read("/skills/com.jarvys.example/SKILL.md"))
    }

    @Test
    fun skillsZoneRejectsTraversalAndSymlinkEscapes() {
        val projectId = WorkspaceStore.projectIdForSession("skills-zone-traversal")
        val projectRoot = temporaryFolder.newFolder("projects")
        val skillsRoot = temporaryFolder.newFolder("installed-skills")
        val observer = object : WorkspaceStore.SkillWorkspaceObserver {
            override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) = writeFile.run()
        }
        val workspace = WorkspaceStore(projectRoot, projectId, skillsRoot, observer)
        val outside = temporaryFolder.newFolder("outside").resolve("secret.txt").apply { writeText("safe") }
        listOf("/skills/../secret.txt", "/skills/com.jarvys.safe/../../secret.txt", "/outside.txt")
            .forEach { escaped ->
                runCatching { workspace.read(escaped) }.onSuccess { error("Allowed escaped skill path: $escaped") }
            }

        val skillDirectory = File(skillsRoot, "com.jarvys.safe").apply { mkdirs() }
        val symlink = File(skillDirectory, "escape")
        runCatching { java.nio.file.Files.createSymbolicLink(symlink.toPath(), outside.parentFile!!.toPath()) }
            .onSuccess {
                runCatching { workspace.read("/skills/com.jarvys.safe/escape/secret.txt") }
                    .onSuccess { error("Allowed symlink outside the skill directory") }
            }
        assertEquals("safe", outside.readText())
    }

    @Test
    fun memoryZoneUsesGlobalStoreAndDeleteCannotTouchWorkspaceOrSkills() {
        val projectRoot = temporaryFolder.newFolder("project-memory-zone")
        val skillsRoot = temporaryFolder.newFolder("skills-memory-zone")
        val memoryRoot = temporaryFolder.newFolder("global-memory-zone")
        val memory = MemoryStore(memoryRoot, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val projectId = WorkspaceStore.projectIdForSession("project-a")
        val observer = object : WorkspaceStore.SkillWorkspaceObserver {
            override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) = writeFile.run()
        }
        val workspace = WorkspaceStore(projectRoot, projectId, skillsRoot, observer, memory, "conversation-a")
        val tools = CoreToolRegistry(WorkspaceTools.create(workspace))
        val content = "---\nname: Person\ndescription: User details.\n---\nName shared by the user."

        val written = tools.invoke("write", mapOf("path" to "/memory/person.md", "content" to content), CancellationToken.uncancellable())
        assertTrue(written.success)
        assertTrue(written.content.contains("revision"))
        assertEquals(content, File(memoryRoot, "person.md").readText())
        assertFalse(File(projectRoot, "memory/person.md").exists())
        assertTrue(workspace.list("/memory").any { it.startsWith("FILE person.md") })

        workspace.write("project.txt", "project")
        workspace.write("/skills/com.jarvys.test/guide.md", "skill")
        assertEquals("project", workspace.read("project.txt"))
        assertEquals("skill", workspace.read("/skills/com.jarvys.test/guide.md"))
        assertTrue(workspace.read("/memory/person.md").contains("Name shared by the user."))

        val deleted = tools.invoke("delete", mapOf("path" to "/memory/person.md"), CancellationToken.uncancellable())
        assertTrue(deleted.success)
        assertFalse(File(memoryRoot, "person.md").exists())
        assertEquals("project", workspace.read("project.txt"))
        assertEquals("skill", workspace.read("/skills/com.jarvys.test/guide.md"))
        runCatching { workspace.deleteMemoryFile("/skills/com.jarvys.test/guide.md") }
            .onSuccess { error("Memory delete reached the skills zone") }
        runCatching { workspace.deleteMemoryFile("project.txt") }
            .onSuccess { error("Memory delete reached the project workspace") }
    }

    @Test
    fun disabledMemoryIsNotAnnouncedAndRejectsAgentWrites() {
        val projectRoot = temporaryFolder.newFolder("project-memory-disabled")
        val skillsRoot = temporaryFolder.newFolder("skills-memory-disabled")
        val memory = MemoryStore(temporaryFolder.newFolder("memory-disabled"), true, testMemorySeedProvider()).also { it.ensureInitialized() }
        memory.setEnabled(false)
        val workspace = WorkspaceStore(projectRoot, WorkspaceStore.projectIdForSession("disabled"),
            skillsRoot, null, memory, "conversation")
        val tools = WorkspaceTools.create(workspace)
        assertFalse(tools.any { it.declaration().name == "delete" })
        assertFalse(tools.any { it.declaration().description.contains("/memory/") })
        runCatching { workspace.write("/memory/note.md", "---\nname: Note\ndescription: Note.\n---\nbody") }
            .onSuccess { error("Agent wrote while memory was disabled") }
        assertTrue(File(memory.rootDirectory(), "human.md").exists())
    }

    private class FilesDirContext(base: Context, private val filesDirectory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = filesDirectory
    }

}

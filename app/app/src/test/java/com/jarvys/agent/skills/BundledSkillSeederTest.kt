package com.jarvys.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class BundledSkillSeederTest {
    @Test
    fun creatorAssetUsesJarvysMetadataAndOnlyAvailableTools() {
        val asset = File("src/main/assets/skills/$BUNDLED_SKILL_CREATOR_ID/SKILL.md")
        assertTrue("Bundled creator asset is missing", asset.isFile)

        val parsed = SkillMarkdownParser.parse(asset.readText(Charsets.UTF_8))
        assertEquals(BUNDLED_SKILL_CREATOR_ID, parsed.metadata.id)
        assertEquals("Skill Workshop", parsed.metadata.name)
        assertTrue(parsed.body.contains("ls /skills/"))
        assertTrue(parsed.body.contains("jarvys://skills/<id>/SKILL.md"))
        assertTrue(parsed.body.contains("read_skill"))
        assertTrue(parsed.metadata.allowedTools.all {
            it == "read_skill" || it in com.jarvys.agent.WorkspaceTools.acceptedNames()
        })
    }

    @Test
    fun seedsOnceAndPreservesUserEditsAcrossAssetUpdates() = withTempRoot { root ->
        val seeder = BundledSkillSeeder(root)
        val initial = creatorMarkdown("Initial instructions")
        seeder.seedSkillCreator { initial }

        val installed = File(root, "$BUNDLED_SKILL_CREATOR_ID/SKILL.md")
        assertEquals(initial, installed.readText(Charsets.UTF_8))
        assertEquals(sha256(initial), File(root, ".bundled-skill-creator").readText(Charsets.UTF_8).trim())

        val edited = creatorMarkdown("User-edited instructions")
        installed.writeText(edited, Charsets.UTF_8)
        val updatedAsset = creatorMarkdown("Updated shipped instructions")
        seeder.seedSkillCreator { updatedAsset }

        assertEquals(edited, installed.readText(Charsets.UTF_8))
        assertEquals(sha256(updatedAsset), File(root, ".bundled-skill-creator").readText(Charsets.UTF_8).trim())
    }

    @Test
    fun existingSkillDirectoryIsRepairedWithoutOverwritingOtherFiles() = withTempRoot { root ->
        val directory = File(root, BUNDLED_SKILL_CREATOR_ID).apply { mkdirs() }
        val note = File(directory, "user-notes.txt").apply { writeText("keep me") }
        val markdown = creatorMarkdown("Seeded instructions")

        BundledSkillSeeder(root).seedSkillCreator { markdown }

        assertEquals(markdown, File(directory, "SKILL.md").readText(Charsets.UTF_8))
        assertEquals("keep me", note.readText())
        assertTrue(File(root, ".bundled-skill-creator").isFile)
    }

    @Test
    fun preexistingMarkdownIsKeptAndAssetDeletionIsNotReseeded() = withTempRoot { root ->
        val directory = File(root, BUNDLED_SKILL_CREATOR_ID).apply { mkdirs() }
        val existing = creatorMarkdown("Existing custom instructions")
        File(directory, "SKILL.md").writeText(existing, Charsets.UTF_8)
        var loadedAsset = false
        val seeder = BundledSkillSeeder(root)
        seeder.seedSkillCreator { loadedAsset = true; creatorMarkdown("Current bundled instructions") }
        assertTrue(loadedAsset)
        assertEquals(existing, File(directory, "SKILL.md").readText(Charsets.UTF_8))

        assertTrue(directory.deleteRecursively())
        seeder.seedSkillCreator { creatorMarkdown("Current bundled instructions") }
        assertFalse(directory.exists())
    }

    @Test
    fun failedAssetDoesNotLeaveReceiptAndCanRetry() = withTempRoot { root ->
        val seeder = BundledSkillSeeder(root)
        runCatching { seeder.seedSkillCreator { "not a skill" } }
            .onSuccess { throw AssertionError("invalid asset must fail") }
        assertFalse(File(root, ".bundled-skill-creator").exists())

        val markdown = creatorMarkdown("Valid instructions")
        seeder.seedSkillCreator { markdown }
        assertEquals(markdown, File(root, "$BUNDLED_SKILL_CREATOR_ID/SKILL.md").readText(Charsets.UTF_8))
    }

    @Test
    fun bundledCreatorAndImportedSkillsAreEnabledByDefault() {
        assertTrue(skillEnabledByDefault(SkillSource.BUNDLED, BUNDLED_SKILL_CREATOR_ID))
        assertFalse(skillEnabledByDefault(SkillSource.BUNDLED, "com.jarvys.android-navigation"))
        assertTrue(skillEnabledByDefault(SkillSource.IMPORTED, "com.jarvys.new-skill"))
    }

    @Test
    fun legacyReceiptUpgradesOnlyAnUneditedKnownSeed() = withTempRoot { root ->
        assertEquals("608b4dd3e61a3912e793da2eaabdf2a3b5db4a6d2b9d6abffc0868a21e097af2",
            LEGACY_SEEDED_SKILL_CREATOR_HASH)
        val legacy = javaClass.getResourceAsStream("/legacy-skill-creator-v1.md")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val currentAsset = File("src/main/assets/skills/$BUNDLED_SKILL_CREATOR_ID/SKILL.md")
            .readText(Charsets.UTF_8)
        val directory = File(root, BUNDLED_SKILL_CREATOR_ID).apply { mkdirs() }
        val installed = File(directory, "SKILL.md").apply { writeText(legacy, Charsets.UTF_8) }
        File(root, ".bundled-skill-creator").writeText("1\n")

        BundledSkillSeeder(root, legacySeedHash = sha256(legacy)).seedSkillCreator { currentAsset }

        assertEquals(currentAsset, installed.readText(Charsets.UTF_8))
        assertEquals(sha256(currentAsset), File(root, ".bundled-skill-creator").readText(Charsets.UTF_8).trim())
    }

    @Test
    fun legacyReceiptPreservesEditedFileWhileAdvancingSeedHash() = withTempRoot { root ->
        val legacy = javaClass.getResourceAsStream("/legacy-skill-creator-v1.md")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val currentAsset = File("src/main/assets/skills/$BUNDLED_SKILL_CREATOR_ID/SKILL.md")
            .readText(Charsets.UTF_8)
        val directory = File(root, BUNDLED_SKILL_CREATOR_ID).apply { mkdirs() }
        val edited = legacy + "\n# My edits\n"
        val installed = File(directory, "SKILL.md").apply { writeText(edited, Charsets.UTF_8) }
        File(root, ".bundled-skill-creator").writeText("1\n")

        BundledSkillSeeder(root, legacySeedHash = sha256(legacy)).seedSkillCreator { currentAsset }

        assertEquals(edited, installed.readText(Charsets.UTF_8))
        assertEquals(sha256(currentAsset), File(root, ".bundled-skill-creator").readText(Charsets.UTF_8).trim())
    }

    private fun creatorMarkdown(instructions: String) = """---
id: $BUNDLED_SKILL_CREATOR_ID
name: skill-creator
description: Create or improve skills for Jarvys.
version: 1
---
# Skill Creator
$instructions
"""

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("jarvys_bundled_skill_").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

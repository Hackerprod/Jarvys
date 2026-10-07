package com.jarvys.agent.skills

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val BUNDLED_SKILL_CREATOR_ID = "com.jarvys.skill-creator"

internal fun skillEnabledByDefault(source: SkillSource, id: String): Boolean = when (source) {
    SkillSource.BUNDLED -> id == BUNDLED_SKILL_CREATOR_ID
    SkillSource.IMPORTED -> true
}

/** Installs bundled creator content and upgrades only files that still match their recorded seed. */
internal class BundledSkillSeeder(
    private val skillRoot: File,
    private val legacySeedHash: String = LEGACY_SEEDED_SKILL_CREATOR_HASH,
) {
    fun seedSkillCreator(loadMarkdown: () -> String) {
        val receipt = File(skillRoot, RECEIPT_FILE)
        val markdown = loadMarkdown()
        val parsed = SkillMarkdownParser.parse(markdown)
        require(parsed.metadata.id == BUNDLED_SKILL_CREATOR_ID) {
            "Bundled skill directory must match its frontmatter id"
        }
        val assetHash = sha256(markdown)
        val directory = File(skillRoot, BUNDLED_SKILL_CREATOR_ID)
        val markdownFile = File(directory, SKILL_FILE)
        if (receipt.isFile) {
            val recordedHash = receipt.readText(StandardCharsets.UTF_8).trim()
            if (recordedHash == assetHash) return
            // Older Jarvys builds wrote the literal "1" receipt. The pinned legacy hash
            // lets this upgrade replace only that exact shipped file; edited files are kept.
            val previousHash = when {
                HASH_PATTERN.matches(recordedHash) -> recordedHash
                recordedHash == LEGACY_RECEIPT -> legacySeedHash
                else -> null
            }
            // A matching hash is proof this file still has bundled bytes. Any edit, deletion,
            // or unrecognized old receipt is treated as user-owned and never overwritten.
            if (previousHash != null && markdownFile.isFile && sha256(markdownFile) == previousHash) {
                writeSeed(markdownFile, markdown)
            }
            writeReceipt(receipt, assetHash)
            return
        }

        if (!markdownFile.exists()) writeSeed(markdownFile, markdown)
        writeReceipt(receipt, assetHash)
    }

    private fun writeSeed(target: File, markdown: String) {
        val directory = target.parentFile ?: throw IllegalStateException("Skill storage has no parent directory")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create bundled skill storage" }
        val temporary = File(directory, "$SKILL_FILE.seed.tmp")
        try {
            temporary.writeText(markdown, StandardCharsets.UTF_8)
            check(temporary.renameTo(target)) { "Could not finish seeding bundled skill" }
        } finally {
            temporary.delete()
        }
    }

    private fun writeReceipt(receipt: File, hash: String) {
        check(skillRoot.isDirectory || skillRoot.mkdirs()) { "Could not create skills storage" }
        receipt.writeText("$hash\n", StandardCharsets.UTF_8)
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val RECEIPT_FILE = ".bundled-skill-creator"
        const val SKILL_FILE = "SKILL.md"
        const val LEGACY_RECEIPT = "1"
        val HASH_PATTERN = Regex("[a-f0-9]{64}")
    }
}

internal const val LEGACY_SEEDED_SKILL_CREATOR_HASH = "608b4dd3e61a3912e793da2eaabdf2a3b5db4a6d2b9d6abffc0868a21e097af2"

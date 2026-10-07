package com.jarvys.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillWorkspaceMutationTest {
    @Test
    fun validatesBeforeWritingAndRescansAfterEverySuccessfulSkillsWrite() {
        val calls = mutableListOf<String>()
        val validSkill = """---
id: com.jarvys.new-skill
name: New skill
description: A valid skill
version: 1
---
# Instructions
"""

        SkillWorkspaceMutation.commit(
            skillMarkdown = null,
            validateMarkdown = { calls += "validate" },
            writeFile = { calls += "write-support" },
            initializeEnabledState = { calls += "enable" },
            rescan = { calls += "rescan" },
        )
        assertEquals(listOf("write-support", "rescan"), calls)

        calls.clear()
        SkillWorkspaceMutation.commit(
            skillMarkdown = validSkill,
            validateMarkdown = {
                SkillMarkdownParser.parse(validSkill)
                calls += "validate"
            },
            writeFile = { calls += "write-skill" },
            initializeEnabledState = { calls += "enable" },
            rescan = { calls += "rescan" },
        )
        assertEquals(listOf("validate", "write-skill", "enable", "rescan"), calls)

        calls.clear()
        runCatching {
            SkillWorkspaceMutation.commit(
                skillMarkdown = "invalid markdown",
                validateMarkdown = {
                    SkillMarkdownParser.parse("invalid markdown")
                    calls += "validate"
                },
                writeFile = { calls += "write-skill" },
                initializeEnabledState = { calls += "enable" },
                rescan = { calls += "rescan" },
            )
        }.onSuccess { error("Invalid SKILL.md must be rejected before writing") }
        assertEquals(emptyList<String>(), calls)
    }
}

package com.jarvys.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMarkdownParserTest {
    @Test
    fun parsesRestrictedMetadataAndMarkdownBody() {
        val parsed = SkillMarkdownParser.parse(
            """---
id: com.example.settings
name: "Ajustes Android"
description: Guía para navegar ajustes visibles
version: 1
allowed-tools:
  - click
  - swipe
tags: [android, settings]
---

## Instrucciones
Usa únicamente la pantalla visible.
""".trimIndent(),
        )

        assertEquals("com.example.settings", parsed.metadata.id)
        assertEquals(listOf("click", "swipe"), parsed.metadata.allowedTools)
        assertEquals(listOf("android", "settings"), parsed.metadata.tags)
        assertTrue(parsed.body.contains("pantalla visible"))
    }

    @Test
    fun rejectsUnknownKeysDuplicateKeysAndUnsupportedVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: x\nname: X\ndescription: Y\nversion: 1\nshell: true\n---\nBody")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: x\nid: y\nname: X\ndescription: Y\nversion: 1\n---\nBody")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: x\nname: X\ndescription: Y\nversion: 2\n---\nBody")
        }
    }

    @Test
    fun rejectsYamlAliasesNestedDataInvalidIdsAndEmptyBodies() {
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: ../escape\nname: X\ndescription: Y\nversion: 1\n---\nBody")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: x\nname: X\ndescription: Y\nversion: 1\nallowed-tools: &tools\n---\nBody")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillMarkdownParser.parse("---\nid: x\nname: X\ndescription: Y\nversion: 1\n---\n   ")
        }
    }

    @Test
    fun acceptsWildcardAndMcpServerGroupCapabilityNames() {
        val parsed = SkillMarkdownParser.parse(
            """---
id: broad-tools
name: Broad tools
description: Allow all tools or a server group
version: 1
allowed-tools: [*, mcp_server:server-123]
---
Use the tools needed for the task.
""".trimIndent(),
        )

        assertEquals(listOf("*", "mcp_server:server-123"), parsed.metadata.allowedTools)
    }
}

package com.jarvys.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillFileLinkTest {
    @Test
    fun parsesSafeSkillFileLinksAndRejectsTraversal() {
        assertEquals(
            SkillFileLink("com.jarvys.demo", "SKILL.md"),
            SkillFileLinkParser.parse("jarvys://skills/com.jarvys.demo/SKILL.md"),
        )
        assertEquals(
            SkillFileLink("com.jarvys.demo", "references/design notes.md"),
            SkillFileLinkParser.parse("jarvys://skills/com.jarvys.demo/references/design%20notes.md"),
        )
        assertNull(SkillFileLinkParser.parse("jarvys://skills/com.jarvys.demo/%2e%2e/secret"))
        assertNull(SkillFileLinkParser.parse("jarvys://other/com.jarvys.demo/SKILL.md"))
        assertNull(SkillFileLinkParser.parse("jarvys://skills/com.jarvys.demo%2fother/SKILL.md"))
    }
}

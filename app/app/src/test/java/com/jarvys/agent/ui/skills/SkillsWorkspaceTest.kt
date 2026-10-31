package com.jarvys.agent.ui.skills

import com.jarvys.agent.skills.SkillEntry
import com.jarvys.agent.skills.SkillMetadata
import com.jarvys.agent.skills.SkillSource
import com.jarvys.agent.skills.userConfigurableSkillEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsWorkspaceTest {
    private fun skill(id: String, source: SkillSource) = SkillEntry(
        metadata = SkillMetadata(id, id, "Fixture", 1, emptyList(), emptyList()),
        body = "Instructions",
        source = source,
        enabled = true,
        usageCount = 0,
    )

    @Test fun settingsCatalogIncludesOnlyUserManagedSkills() {
        val system = skill("system.fixture", SkillSource.BUNDLED)
        val user = skill("user.fixture", SkillSource.IMPORTED)

        assertEquals(listOf(user), userConfigurableSkillEntries(listOf(system, user)))
        assertTrue(userConfigurableSkillEntries(listOf(system)).isEmpty())
    }
}

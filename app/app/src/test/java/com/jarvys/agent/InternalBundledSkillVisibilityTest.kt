package com.jarvys.agent

import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.skills.SkillEntry
import com.jarvys.agent.skills.SkillMetadata
import com.jarvys.agent.skills.SkillRepository
import com.jarvys.agent.skills.SkillSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternalBundledSkillVisibilityTest {
    private fun skill(
        id: String,
        name: String,
        description: String,
        source: SkillSource,
        tags: List<String> = emptyList(),
    ) = SkillEntry(
        SkillMetadata(id, name, description, 1, emptyList(), tags),
        "Follow the $name guidance.", source, enabled = true, usageCount = 0,
    )

    @Test fun botCreationCatalogFiltersInternalSkillsByTag() {
        val internal = skill("runtime.internal", "Private Name", "PRIVATE DESCRIPTION SENTINEL",
            SkillSource.BUNDLED, listOf(SkillRepository.INTERNAL_SKILL_VISIBILITY_TAG))
        val selectable = skill("user.skill", "User Skill", "User authored guidance.", SkillSource.IMPORTED)
        val ids = botCreationSkillIds(listOf(internal, selectable))
        assertEquals(listOf("user.skill"), ids)

        val service = BotCreationService(CrewProfileRepository(Files.createTempDirectory("bot-skills").toFile()),
            "main", { listOf("read_skill") }, { ids }, { false },
            { _, _, _, _ -> error("Catalog inspection does not generate icons") })
        val declaration = BotCreationTool(service).declaration()
        assertFalse(declaration.jsonSchema().toString().contains(internal.metadata.id))
        assertTrue(declaration.jsonSchema().toString().contains(selectable.metadata.id))
    }

    @Test fun delegateOmitsInternalIdsButChildReadSkillReceivesThemAutomatically() {
        val internal = skill("runtime.internal", "Private Name", "PRIVATE DESCRIPTION SENTINEL",
            SkillSource.BUNDLED, listOf(SkillRepository.INTERNAL_SKILL_VISIBILITY_TAG))
        val selectable = skill("user.skill", "User Skill", "User authored guidance.", SkillSource.IMPORTED)
        val parentSkillTool = LoadSkillTool(listOf(internal, selectable), 16_384)
        val delegate = DelegateSubtaskTool(
            DelegateSubtaskTool.Runner { _, _, _, _ -> "done" },
            listOf("read_skill"), listOf(internal, selectable))

        assertTrue(parentSkillTool.declaration().description.contains(internal.metadata.id))
        assertEquals("", parentSkillTool.activitySkillName(mapOf<String, Any>("skill_id" to internal.metadata.id)))
        assertTrue(delegate.declaration().description.contains(selectable.metadata.id))
        assertFalse(delegate.declaration().description.contains(internal.metadata.id))

        val child = CoreAgentRuntime.childCapabilityScope(
            CoreToolRegistry(listOf(parentSkillTool)), listOf("read_skill"), listOf(selectable.metadata.id),
            CorePromptBudget.standard())
        val childReadSkill = child.get("read_skill") as LoadSkillTool
        assertTrue(childReadSkill.declaration().description.contains(internal.metadata.id))
        assertTrue(childReadSkill.declaration().description.contains(selectable.metadata.id))
        val loaded = childReadSkill.execute(mapOf<String, Any>("skill_id" to internal.metadata.id), CancellationToken.uncancellable())
        assertTrue(loaded.content, loaded.success)
    }

    @Test fun mainPromptOmitsInternalDescriptionsWhileReadSkillListsInternalIds() {
        val internal = skill("runtime.internal", "Private Name", "PRIVATE DESCRIPTION SENTINEL",
            SkillSource.BUNDLED, listOf(SkillRepository.INTERNAL_SKILL_VISIBILITY_TAG))
        val selectable = skill("user.skill", "User Skill", "USER DESCRIPTION SENTINEL", SkillSource.IMPORTED)
        val runtime = CoreAgentRuntime(listOf(internal, selectable), emptyList(), emptyList(), emptyList())
        val readSkill = LoadSkillTool(listOf(internal, selectable), CorePromptBudget.standard().loadedSkillChars)
        val prompt = runtime.instructions(CoreToolRegistry(listOf(readSkill)), true)

        assertFalse(prompt.contains("PRIVATE DESCRIPTION SENTINEL"))
        assertFalse(prompt.contains("Private Name"))
        assertTrue(prompt.contains("USER DESCRIPTION SENTINEL"))
        assertTrue(readSkill.declaration().description.contains(internal.metadata.id))
    }
}

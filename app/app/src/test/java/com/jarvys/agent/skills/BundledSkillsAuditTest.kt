package com.jarvys.agent.skills

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.LoadSkillTool
import com.jarvys.agent.modules.ModuleRegistry
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledSkillsAuditTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instanceField: Field = SkillRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousRepository: Any? = null

    @Before fun isolateRepository() {
        previousRepository = instanceField.get(null)
        instanceField.set(null, null)
        context.getSharedPreferences("jarvys_skill_settings", MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreRepository() {
        instanceField.set(null, previousRepository)
    }

    @Test fun everyBundledSkillParsesMatchesItsAssetLocationAndRemainsRuntimeReadable() {
        val rootDirectories = bundledSkillDirectoryNames(context.assets.list("skills").orEmpty().toList())
        val rootParsed = rootDirectories.map { directory ->
            val markdown = context.assets.open("skills/$directory/SKILL.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = SkillMarkdownParser.parse(markdown)
            assertEquals("Asset directory must match SKILL.md id", directory, parsed.metadata.id)
            parsed
        }
        val moduleParsed = ModuleRegistry.fromAssets(context).modules().flatMap { module ->
            module.skills.map { relativePath ->
                val markdown = context.assets.open("${module.bundleAssetDirectory}/$relativePath")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                SkillMarkdownParser.parse(markdown)
            }
        }
        val allParsed = rootParsed + moduleParsed
        val allAssetIds = allParsed.map { it.metadata.id }
        assertTrue("Bundled skill ids must be unique across app and module assets", allAssetIds.size == allAssetIds.toSet().size)

        val repositorySkills = SkillRepository.get(context).skills.value.filter { it.source == SkillSource.BUNDLED }
        assertEquals(allAssetIds.toSet(), repositorySkills.map { it.metadata.id }.toSet())
        assertEquals(allAssetIds.size, repositorySkills.size)
        assertTrue(repositorySkills.all { !it.source.userConfigurable })
        assertTrue(repositorySkills.all { it.validationError == null })
        assertTrue("Bundled skills must not appear in the user-managed Skills UI", userConfigurableSkillEntries(repositorySkills).isEmpty())

        val hidden = repositorySkills.filter { SkillRepository.isInternalBundledSkill(it) }
        assertTrue("Expected the imported upstream skills to carry the internal visibility tag", hidden.isNotEmpty())
        assertTrue(botPickerSkillEntries(repositorySkills).none { SkillRepository.isInternalBundledSkill(it) })
        assertEquals(hidden.map { it.metadata.id }.toSet(), botPickerHiddenSkillIds(repositorySkills))
        val hiddenIds = hidden.map { it.metadata.id }.toSet()
        assertEquals(hiddenIds, SkillRepository.get(context).internalSkillsForProfile(null).map { it.metadata.id }.toSet())
        assertEquals(hiddenIds, SkillRepository.get(context).internalSkillsForProfile("custom-profile").map { it.metadata.id }.toSet())
        assertEquals(hiddenIds, SkillRepository.get(context)
            .availableForAgentProfile(emptyList(), "custom-profile").map { it.metadata.id }.toSet())

        val skill = hidden.first()
        val tool = LoadSkillTool(
            SkillRepository.get(context).availableForAgentProfile(emptyList(), "custom-profile"),
            CorePromptBudget.standard().loadedSkillChars,
        )
        val result = tool.execute(mapOf("skill_id" to skill.metadata.id), CancellationToken.uncancellable())
        assertTrue("Internal bundled skill must remain available through read_skill: ${result.content}", result.success)
        assertTrue(result.content.contains(skill.body.take(80)))
        hidden.forEach { entry ->
            assertTrue(tool.declaration().description.contains(entry.metadata.id))
            assertTrue(entry.metadata.id, tool.execute(mapOf("skill_id" to entry.metadata.id), CancellationToken.uncancellable()).success)
        }
    }

    @Test fun internalTagDoesNotHideUserImportedSkillsFromBotPicker() {
        val imported = SkillEntry(
            metadata = SkillMetadata("user.internal", "Imported", "User skill", 1, emptyList(), listOf(SkillRepository.INTERNAL_SKILL_VISIBILITY_TAG)),
            body = "User instructions",
            source = SkillSource.IMPORTED,
            enabled = true,
            usageCount = 0,
        )

        assertEquals(listOf(imported), botPickerSkillEntries(listOf(imported)))
        assertFalse(imported.metadata.id in botPickerHiddenSkillIds(listOf(imported)))
    }
}

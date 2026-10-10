package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.tasks.ScheduledTaskConversation
import com.jarvys.agent.proactive.ProactiveConversation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexImageGenerationToolScopeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun resetCodexSelection() {
        ProviderSettings(context).setProvider(ProviderSettings.Provider.OPENROUTER)
    }

    @Test
    fun imageToolRequiresActiveCodexAndStoredChatGptCredentials() {
        val settings = ProviderSettings(context)
        val secrets = testSecrets("availability")
        secrets.clearCodexTokens()
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX)
        assertFalse(CodexImageGenerationTool.isAvailable(context, settings, 0, "normal-chat", secrets))

        secrets.saveCodexTokens("access-token-test", "refresh-token-test", System.currentTimeMillis() + 3_600_000L,
            "account-test")
        assertTrue(CodexImageGenerationTool.isAvailable(context, settings, 0, "normal-chat", secrets))
        assertFalse(CodexImageGenerationTool.isAvailable(context, settings, 1, "normal-chat", secrets))
        assertFalse(CodexImageGenerationTool.isAvailable(context, settings, 0, ProactiveConversation.SESSION_ID, secrets))
        assertFalse(CodexImageGenerationTool.isAvailable(context, settings, 0, ScheduledTaskConversation.SESSION_ID, secrets))

        listOf(ProviderSettings.Provider.OPENAI_API, ProviderSettings.Provider.OPENROUTER,
            ProviderSettings.Provider.CUSTOM).forEach { provider ->
            settings.setProvider(provider)
            assertFalse(CodexImageGenerationTool.isAvailable(context, settings, 0, "normal-chat", secrets))
        }
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX)
    }

    @Test
    fun crewDelegatedAndReflectionScopesExcludeTheImageCapability() {
        val settings = ProviderSettings(context).apply { setProvider(ProviderSettings.Provider.OPENAI_CODEX) }
        val secrets = testSecrets("scope")
        secrets.saveCodexTokens("scope-access", "scope-refresh",
            System.currentTimeMillis() + 3_600_000L, "scope-account")
        val imageTool = CodexImageGenerationTool(context, "scope-chat", settings, secrets)
        val mainChat = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), listOf(imageTool)).createTools()
        assertTrue(mainChat.names().contains(CodexImageGenerationTool.NAME))
        val declarations = mainChat.declarations()
        assertTrue(declarations.first { it.name == CodexImageGenerationTool.NAME }.required.contains("prompt"))
        assertTrue(declarations.first { it.name == "delegate_subtask" }.description
            .contains("Available tools:"))
        assertFalse(declarations.first { it.name == "delegate_subtask" }.description
            .contains(CodexImageGenerationTool.NAME))
        val crew = CoreAgentRuntime.crewBotCapabilityScope(CoreToolRegistry(listOf(imageTool)))
        assertFalse(crew.names().contains(CodexImageGenerationTool.NAME))
        assertEquals(CodexImageGenerationTool.NAME, imageTool.declaration().name)
        assertTrue(imageTool.declaration().description.contains("quota"))

        val memory = MemoryStore(File(temporaryFolder.root, "reflection-memory"), true, testMemorySeedProvider())
            .forConversation("reflection-session")
        memory.ensureInitialized()
        val reflectionWorkspace = WorkspaceStore(File(temporaryFolder.root, "reflection-workspaces"),
            "a".repeat(24), null, null, memory, "reflection-session", true,
            MemoryStore.Actor.REFLECTION, "reflection-group", true)
        val reflectionTools = WorkspaceTools.createReflectionMemoryOnly(reflectionWorkspace)
        assertEquals(listOf("ls", "read", "write", "edit", "delete"), reflectionTools.map { it.declaration().name })
        assertFalse(reflectionTools.any { it.declaration().name == CodexImageGenerationTool.NAME })
        assertFalse(WorkspaceTools.names().contains(CodexImageGenerationTool.NAME))
    }

    @Test
    fun bilingualGuidanceRequiresAnExplicitUserRequestAndExplainsPlanQuotaAndPromptPrivacy() {
        val english = AgentPrompts.IMAGE_GENERATION_GUIDANCE_EN.lowercase()
        val spanish = AgentPrompts.IMAGE_GENERATION_GUIDANCE_ES.lowercase()
        listOf("only when the user explicitly asks", "chatgpt", "quota", "prompt", "proactively")
            .forEach { assertTrue(english.contains(it)) }
        listOf("solo cuando la persona pida explícitamente", "chatgpt", "cuota", "instrucción", "iniciativa propia")
            .forEach { assertTrue(spanish.contains(it)) }
    }

    private fun testSecrets(name: String) = SecretStore(
        context.getSharedPreferences("i1-image-test-$name", Context.MODE_PRIVATE))
}

package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewRoleTemplates
import com.jarvys.agent.tasks.ScheduledTaskConversation
import com.jarvys.agent.proactive.ProactiveDecisionSink
import com.jarvys.agent.proactive.ProactiveReadOnlyToolFactory
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDecisionScopeTest {
    private lateinit var testSecrets: SecretStore

    @Before fun installSecretStoreSeam() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testSecrets = SecretStore(context.getSharedPreferences("e1-scope-secrets", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, testSecrets)
    }

    @After fun clearSecretStoreSeam() {
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, null)
    }

    @Test fun mainChatGetsDecisionToolButProactiveReflectionCrewAndChildScopesDoNot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = CoreAgentRuntime(context, "e1-scope-${System.nanoTime()}", emptyList())
        val mainTools = runtime.createTools()
        assertTrue(mainTools.names().contains(UserDecisionTool.NAME))
        assertTrue(mainTools.names().containsAll(com.jarvys.agent.tasks.TaskManagementTools.TOOL_NAMES))
        val systemPrompt = runtime.instructions()
        assertTrue(systemPrompt.contains(AgentPrompts.USER_DECISION_GUIDANCE))
        assertTrue(systemPrompt.contains(AgentPrompts.TASK_MANAGEMENT_GUIDANCE))
        if (BuildConfig.FLAVOR == "full") assertTrue(systemPrompt.contains("linux_setup"))
        else assertFalse("Play prompt must not mention Linux", systemPrompt.contains("linux", ignoreCase = true))
        listOf("ollama", "lm studio").forEach { capability ->
            assertFalse("system prompt hardcodes '$capability'", systemPrompt.contains(capability, ignoreCase = true))
        }

        val delegated = mainTools.declarations().single { it.name == "delegate_subtask" }
        assertFalse(delegated.description.contains(UserDecisionTool.NAME))
        val crewScope = CoreAgentRuntime.crewBotCapabilityScope(mainTools)
        assertFalse(crewScope.names().contains(UserDecisionTool.NAME))
        com.jarvys.agent.tasks.TaskManagementTools.TOOL_NAMES.forEach { assertFalse(it in crewScope.names()) }
        CrewRoleTemplates.all(crewScope).forEach { role ->
            assertFalse("${role.id} received an interactive user decision", role.tools.contains(UserDecisionTool.NAME))
        }
        val customCrewRole = runCatching {
            CrewRoleTemplates.custom("Decision", "", listOf(UserDecisionTool.NAME), crewScope)
        }.exceptionOrNull()
        assertTrue(customCrewRole is IllegalArgumentException)

        val proactive = ProactiveReadOnlyToolFactory.create(context, ProactiveDecisionSink {
            CoreToolResult.success("accepted")
        })
        assertFalse(proactive.names().contains(UserDecisionTool.NAME))
        com.jarvys.agent.tasks.TaskManagementTools.TOOL_NAMES.forEach { assertFalse(it in proactive.names()) }

        val reflectionWorkspace = WorkspaceTools.createReflectionMemoryOnly(
            context, "e1-reflection-${System.nanoTime()}", MemoryStore(context), "group-e1")
        val reflection = CoreToolRegistry(reflectionWorkspace)
        assertEquals(listOf("ls", "read", "write", "edit", "delete"), reflection.names())
        assertFalse(reflection.names().contains(UserDecisionTool.NAME))
        com.jarvys.agent.tasks.TaskManagementTools.TOOL_NAMES.forEach { assertFalse(it in reflection.names()) }

        val taskConversation = CoreAgentRuntime(context, ScheduledTaskConversation.SESSION_ID, emptyList())
        assertTrue(taskConversation.instructions().contains(
            context.getString(R.string.scheduled_tasks_normal_thread_safety)))
    }
}

package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.tasks.TaskManagementTools
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskManagementScopeTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val secrets = SecretStore(context.getSharedPreferences("st2-management-secrets", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, secrets)
    }

    @After fun tearDown() {
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, null)
    }

    @Test fun managementToolsAndGuidanceOnlyAppearForMainForegroundChatAndAreRemovedFromCrewScope() {
        val runtime = CoreAgentRuntime(context, "st2-scope-${System.nanoTime()}", emptyList())
        val mainTools = runtime.createTools()
        assertTrue(mainTools.names().containsAll(TaskManagementTools.TOOL_NAMES))
        assertTrue(runtime.instructions().contains(AgentPrompts.TASK_MANAGEMENT_GUIDANCE))
        val crew = CoreAgentRuntime.crewBotCapabilityScope(mainTools)
        TaskManagementTools.TOOL_NAMES.forEach { assertFalse(it in crew.names()) }

        val noContext = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(noContext.createTools().names().any { it in TaskManagementTools.TOOL_NAMES })
        assertFalse(noContext.instructions().contains(AgentPrompts.TASK_MANAGEMENT_GUIDANCE))

        val childConstructor = CoreAgentRuntime::class.java.declaredConstructors.single { it.parameterTypes.size == 12 }
            .apply { isAccessible = true }
        val child = childConstructor.newInstance(context, "st2-child", emptyList<Any>(), null,
            emptyList<CoreTool>(), emptyList<CoreTool>(), emptyList<CoreTool>(), null, 1,
            CorePromptBudget.standard(), false, true) as CoreAgentRuntime
        assertFalse(child.createTools().names().any { it in TaskManagementTools.TOOL_NAMES })
        assertFalse(child.instructions().contains(AgentPrompts.TASK_MANAGEMENT_GUIDANCE))
        val guidance = AgentPrompts.TASK_MANAGEMENT_GUIDANCE
        assertTrue(guidance.contains("list_tasks"))
        assertTrue(guidance.contains("now_local"))
        assertTrue(guidance.contains("self-contained"))
        assertFalse(guidance.contains("Linux", ignoreCase = true))
        assertFalse(guidance.contains("email", ignoreCase = true))
    }
}

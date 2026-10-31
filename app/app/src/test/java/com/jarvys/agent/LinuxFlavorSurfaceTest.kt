package com.jarvys.agent

import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.flavor.FlavorLinuxTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxFlavorSurfaceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun linuxToolsAndGuidanceAreMainChatOnlyAndFullFlavorOnly() {
        val mainTools = FlavorLinuxTools.create(context, "main-session", 0)
        val mainPrompt = AgentPrompts.linuxEnvironmentGuidance(context, "main-session", 0)
        if (BuildConfig.FLAVOR == "full") {
            assertEquals(listOf("linux_status", "linux_setup", "linux_exec", "linux_uninstall"),
                mainTools.map { it.declaration().name })
            assertNotNull(mainPrompt)
            assertTrue(mainPrompt!!.contains("PRoot is not a security sandbox"))
            assertTrue(mainPrompt.contains("grants no real root privileges"))
            assertTrue(mainPrompt.contains("does not guarantee guest network access"))
            // UX44 keeps saved choices and real setup constraints while making every action configurable.
            assertTrue(mainPrompt.contains("Setup, command execution and removal have separate Allow/Ask/Deny settings"))
            assertTrue(mainPrompt.contains("defaulting to Allow only when no saved choice exists"))
            assertTrue(mainPrompt.contains("Ask reviews the exact action once and Deny blocks it."))
            assertTrue(mainPrompt.contains("Setup uses the pinned official release and checks PRoot."))
            assertTrue(mainPrompt.contains("supply delete_workspace explicitly or ask the user whether to keep their workspace"))
            assertTrue(mainPrompt.contains("inspect state rather than replaying"))
        } else {
            assertTrue(mainTools.isEmpty())
            assertNull(mainPrompt)
        }

        assertTrue(FlavorLinuxTools.create(context, "main-session", 1).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "main-session/crew/bot-1", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "jarvys-subagent-main-session-1", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "proactive-decision-123", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "task-42-1700000000000", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "task:42:1700000000000", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, null, 0).isEmpty())
        assertNull(AgentPrompts.linuxEnvironmentGuidance(context, "main-session", 1))
    }

    @Test fun activeMemoryReflectionNeverReceivesLinuxToolsOrGuidance() {
        val session = "memory-reflection-session"
        val token = MemoryReflectionRuntime.begin(session)
        assertNotNull(token)
        try {
            assertTrue(FlavorLinuxTools.create(context, session, 0).isEmpty())
            assertNull(AgentPrompts.linuxEnvironmentGuidance(context, session, 0))
        } finally {
            MemoryReflectionRuntime.finish(session, token)
        }
    }

    @Test fun crewCapabilityScopeStripsLinuxToolsEvenIfPresentInParentRegistry() {
        val registry = CoreToolRegistry(listOf(
            namedTool("linux_status"), namedTool("linux_setup"), namedTool("linux_exec"),
            namedTool("linux_uninstall"), namedTool("read_workspace"),
        ))

        val childNames = CoreAgentRuntime.crewBotCapabilityScope(registry).names()

        assertFalse(childNames.any { it.startsWith("linux_") })
        assertEquals(listOf("read_workspace"), childNames)
    }

    private fun namedTool(name: String) = object : CoreTool {
        override fun declaration() = ToolSpec(name, "test", "test", "test", ToolSpec.Status.IMPLEMENTED,
            emptyMap(), emptyList())

        override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.success("ok")
    }
}

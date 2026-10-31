package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreMcpToolNameTest {
    @Test
    fun displayNameUsesTitleCasedWireNameWithoutServerAlias() {
        assertEquals("System Metrics", CoreToolRegistry.humanizeToolName("system_metrics"))
        assertEquals("Process List", CoreToolRegistry.humanizeToolName("process_list"))
        assertEquals("Run Command", CoreToolRegistry.humanizeToolName("run_command"))
        assertEquals("List", CoreToolRegistry.humanizeToolName("ls"))
        assertEquals("Read", CoreToolRegistry.humanizeToolName("read"))
        assertEquals("Preview Workspace", CoreToolRegistry.humanizeToolName("preview_workspace"))
        assertEquals("Delegate Subtask", CoreToolRegistry.humanizeToolName("delegate_subtask"))
        assertEquals(listOf("ls", "read", "write", "edit", "preview_workspace"), WorkspaceTools.names())
        assertEquals("write", WorkspaceTools.canonicalToolName("workspace_write"))
    }
}

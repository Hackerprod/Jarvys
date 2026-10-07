package com.jarvys.agent.mcp

enum class McpEditorReturnTarget { SERVER_LIST, SERVER_DETAIL }

data class McpEditorBackDecision(val confirmDiscard: Boolean, val returnTarget: McpEditorReturnTarget)

object McpEditorNavigationPolicy {
    fun back(isDirty: Boolean, editingExistingServer: Boolean): McpEditorBackDecision = McpEditorBackDecision(
        confirmDiscard = isDirty,
        returnTarget = if (editingExistingServer) McpEditorReturnTarget.SERVER_DETAIL else McpEditorReturnTarget.SERVER_LIST,
    )
}

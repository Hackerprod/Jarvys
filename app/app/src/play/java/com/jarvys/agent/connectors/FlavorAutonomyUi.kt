package com.jarvys.agent.connectors

import androidx.compose.runtime.Composable
import com.jarvys.agent.AgentRunUiEvent

/** Play does not include Linux execution or its permissions. */
object FlavorAutonomyUi {
    fun handlesApproval(event: AgentRunUiEvent): Boolean = false
    @Composable fun ApprovalAction(event: AgentRunUiEvent, onStatusDetail: (ConnectorUiText) -> Unit) = Unit
    @Composable fun ConnectorControls(): Boolean = false
}

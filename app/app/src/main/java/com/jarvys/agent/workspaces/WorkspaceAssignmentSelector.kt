package com.jarvys.agent.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R

/** Destination selector for native workspace corrections and pause controls. */
@Composable
fun WorkspaceAssignmentSelector(
    assignments: List<WorkspaceAssignmentUi>,
    selectedAssignmentId: String?,
    onSelect: (WorkspaceAssignmentUi) -> Unit,
) {
    val available = assignments.filter { it.canCorrect || it.canPause }
    val selected = available.firstOrNull { it.id == selectedAssignmentId } ?: available.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.let { stringResource(R.string.workspace_assignment_target, it.name) }
        ?: stringResource(R.string.workspace_assignment_select)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = available.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .testTag("workspace-assignment-selector")
                .semantics { contentDescription = label },
        ) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { assignment ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("workspace-assignment-${assignment.id}"),
                    text = {
                        Column {
                            Text(assignment.name, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(workspaceStatusResource(assignment.status)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false; onSelect(assignment) },
                    enabled = assignment.canCorrect || assignment.canPause,
                )
            }
        }
    }
}

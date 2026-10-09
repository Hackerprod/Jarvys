package com.jarvys.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun toolActivityLabel(activity: ToolActivity): String {
    val name = if (activity.skill()) activity.skillName.ifBlank { activity.skillId } else activity.displayName
    val resource = when (activity.stage) {
        "tool_result" -> if (activity.skill()) R.string.activity_skill_loaded else R.string.connector_tool_used
        "tool_error" -> if (activity.skill()) R.string.activity_skill_failed else R.string.connector_tool_failed
        "tool_interrupted" -> R.string.connector_tool_unconfirmed
        "tool_not_started" -> R.string.connector_tool_not_started
        else -> if (activity.skill()) R.string.activity_skill_loading else R.string.connector_tool_using
    }
    return stringResource(resource,name)
}

@Composable
fun ToolActivityLine(activity: ToolActivity, modifier: Modifier = Modifier, onOpenPreview: ((String) -> Unit)? = null) {
    var expanded by rememberSaveable(activity.executionId) { mutableStateOf(false) }
    val label = toolActivityLabel(activity)
    val failed = activity.stage == "tool_error"
    Column(modifier.fillMaxWidth().testTag("activity-${activity.executionId}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f).semantics { stateDescription = label; liveRegion = LiveRegionMode.Polite },
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            if (activity.allDetail().isNotBlank() || activity.auditDetail.isNotBlank()) IconButton(onClick={expanded=!expanded},
                modifier=Modifier.size(48.dp).testTag("activity-details-${activity.executionId}")) {
                Icon(if(expanded) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                    stringResource(if(expanded) R.string.tool_output_collapse else R.string.tool_output_expand),Modifier.size(18.dp), tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (failed && !expanded && activity.detail.isNotBlank()) SelectionContainer {
            Text(activity.detail.lineSequence().firstOrNull().orEmpty().take(240), style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error)
        }
        if (activity.stage == "tool_interrupted") Text(stringResource(R.string.activity_unconfirmed_detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) SelectionContainer {
            Column {
                if(activity.auditDetail.isNotBlank()) Text(activity.auditDetail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(activity.allDetail().isNotBlank()) Text(activity.allDetail(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(activity.previewId.isNotBlank() && onOpenPreview!=null) TextButton(onClick={onOpenPreview(activity.previewId)},
            modifier=Modifier.heightIn(min=48.dp).testTag("activity-preview-${activity.executionId}")) { Text(stringResource(R.string.chat_open_preview)) }
    }
}

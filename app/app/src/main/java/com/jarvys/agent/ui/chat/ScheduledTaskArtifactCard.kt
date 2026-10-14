package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.ScheduledTaskArtifact

@Composable
internal fun ScheduledTaskArtifactCard(
    artifact: ScheduledTaskArtifact,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("scheduled-task-card-${artifact.taskId}")) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(artifact.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.scheduled_task_chat_schedule, artifact.scheduleHuman),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.scheduled_task_chat_next_run,
                artifact.nextRunLocal ?: stringResource(R.string.scheduled_task_chat_no_next_run)),
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpen, modifier = Modifier.testTag("scheduled-task-open-${artifact.taskId}")) {
                Text(stringResource(if (artifact.status == "cancelled") R.string.scheduled_task_chat_open_tasks
                    else R.string.scheduled_task_chat_open_task))
            }
        }
    }
}

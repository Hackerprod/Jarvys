package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R

/** Deliberately separate from the existing task runtime and its Settings management screen. */
@Composable
fun ScheduledTasksPlaceholderScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        .testTag("scheduled-tasks-placeholder"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(LucideIcons.Calendar, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.scheduled_tasks_placeholder_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.scheduled_tasks_placeholder_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

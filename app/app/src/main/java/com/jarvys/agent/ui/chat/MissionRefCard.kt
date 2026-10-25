package com.jarvys.agent.ui.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.workspaces.MissionRef
import com.jarvys.agent.workspaces.MissionRefResolver
import com.jarvys.agent.workspaces.WorkspaceChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface MissionCardState {
    data object Loading : MissionCardState
    data object Unavailable : MissionCardState
    data class Resolved(val snapshot: CrewMissionSnapshot) : MissionCardState
}

/**
 * The thread's only view of a bot mission. Its content is derived from the workspace registry and
 * the recorded Crew snapshots each time they change; a reference that no longer resolves says so.
 */
@Composable
internal fun MissionRefCard(
    ref: MissionRef,
    onOpen: (MissionRef) -> Unit,
    resolve: (Context, MissionRef) -> CrewMissionSnapshot? = MissionRefResolver::resolve,
) {
    val context = LocalContext.current
    val revision by WorkspaceChanges.revision.collectAsState()
    // Bots publish a snapshot per phase; only a mission that can still change is re-read for them.
    var settled by remember(ref) { mutableStateOf(false) }
    val state by produceState<MissionCardState>(MissionCardState.Loading, ref, if (settled) 0L else revision) {
        val resolved = withContext(Dispatchers.IO) {
            runCatching { resolve(context, ref) }.getOrNull()
        }
        settled = resolved != null && !resolved.active() && resolved.bots.none { it.active() }
        value = resolved?.let(MissionCardState::Resolved) ?: MissionCardState.Unavailable
    }
    Column(Modifier.fillMaxWidth().testTag("mission-ref-card"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ref.resultId != null) Text(stringResource(R.string.mission_card_result_ready),
            Modifier.padding(horizontal = 4.dp).testTag("mission-ref-result"),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        when (val current = state) {
            MissionCardState.Loading -> Unit
            is MissionCardState.Resolved -> CrewMissionCard(current.snapshot, onOpen = { onOpen(ref) })
            MissionCardState.Unavailable -> Surface(
                Modifier.fillMaxWidth().testTag("mission-ref-unavailable").clickable { onOpen(ref) },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(stringResource(R.string.crew_mission_unavailable), Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

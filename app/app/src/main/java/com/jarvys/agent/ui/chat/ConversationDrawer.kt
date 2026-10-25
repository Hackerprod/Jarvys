package com.jarvys.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R

/** The single conversation needs no list: the drawer is the agent profile entry plus Settings. */
@Composable
fun ConversationDrawer(
    agentName: String,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    actionsEnabled: Boolean = true,
    isDrawerOpen: Boolean = true,
    onCloseDrawer: () -> Unit = {},
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // Register while the modal is open, after the underlying NavHost's subcomposed callback.
    // Enabling an always-registered callback can leave Back owned by the underlying route.
    if (isDrawerOpen) BackHandler(onBack = onCloseDrawer)
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width((screenWidth * 0.86f).coerceAtMost(420.dp)),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("drawer-profile-header")
                .clickable(enabled = actionsEnabled, role = Role.Button, onClick = onOpenProfile)
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painter = painterResource(R.drawable.jarvys_mascot_static), contentDescription = null,
                    modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f)) {
                    Text(agentName, Modifier.testTag("drawer-profile-name"), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.drawer_profile_memory), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("drawer-open-settings")
                .clickable(enabled = actionsEnabled, role = Role.Button, onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(LucideIcons.Settings, contentDescription = null, modifier = Modifier.size(23.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.drawer_settings), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

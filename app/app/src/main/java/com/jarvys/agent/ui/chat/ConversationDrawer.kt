package com.jarvys.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R

/** Keep the established app navigation; only legacy chat creation/history/search are retired. */
@Composable
fun ConversationDrawer(
    agentName: String,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    actionsEnabled: Boolean = true,
    isDrawerOpen: Boolean = true,
    onCloseDrawer: () -> Unit = {},
    onOpenCentral: () -> Unit = {},
    onOpenWorkspaces: () -> Unit = {},
    onOpenBots: () -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    centralSelected: Boolean = false,
    workspacesSelected: Boolean = false,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // Register while the modal is open, after the underlying NavHost's subcomposed callback.
    if (isDrawerOpen) BackHandler(onBack = onCloseDrawer)
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width((screenWidth * 0.86f).coerceAtMost(420.dp)).imePadding(),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                .heightIn(min = 48.dp).testTag("drawer-header"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f).testTag("drawer-header-title"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("conversation-drawer-scroll"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                item(key = "central-navigation") {
                    Surface(shape = RoundedCornerShape(13.dp),
                        color = if (centralSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("drawer-open-central")
                            .semantics { selected = centralSelected }
                            .clickable(enabled = actionsEnabled, role = Role.Button, onClick = onOpenCentral)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Image(painterResource(R.drawable.jarvys_mascot_static), contentDescription = null,
                                modifier = Modifier.size(23.dp), contentScale = ContentScale.Fit)
                            Text(stringResource(R.string.drawer_agent), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                item(key = "workspaces-navigation") {
                    Surface(shape = RoundedCornerShape(13.dp),
                        color = if (workspacesSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        DrawerMenuRow(stringResource(R.string.workspace_sidebar_heading), LucideIcons.Layers2,
                            Modifier.testTag("drawer-open-workspaces").semantics { selected = workspacesSelected },
                            onOpenWorkspaces, actionsEnabled)
                    }
                }
                item(key = "primary-navigation") {
                    Column {
                        DrawerMenuRow(stringResource(R.string.drawer_bots), LucideIcons.Bot,
                            Modifier.testTag("drawer-open-bots"), onOpenBots, actionsEnabled)
                        DrawerMenuRow(stringResource(R.string.drawer_scheduled_tasks), LucideIcons.Calendar,
                            Modifier.testTag("drawer-open-scheduled-tasks"), onOpenScheduledTasks, actionsEnabled)
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 9.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("drawer-profile-header")
                        .clickable(enabled = actionsEnabled, role = Role.Button, onClick = onOpenProfile),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text("J", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(agentName, Modifier.testTag("drawer-profile-name"), style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.drawer_local_profile), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onOpenSettings, enabled = actionsEnabled,
                        modifier = Modifier.size(48.dp).testTag("drawer-open-settings")) {
                        Icon(LucideIcons.Settings, contentDescription = stringResource(R.string.drawer_settings), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerMenuRow(title: String, icon: ImageVector, modifier: Modifier,
                          onClick: () -> Unit, enabled: Boolean) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

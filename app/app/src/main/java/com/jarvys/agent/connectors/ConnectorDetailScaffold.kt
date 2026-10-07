package com.jarvys.agent.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.JarvysBackNavigationButton
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysUiTokens

/** Shared, presentation-only shell for device, MCP, and Full Google detail pages. */
@Composable
internal fun ConnectorDetailScaffold(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    connected: Boolean,
    summary: String,
    account: String? = null,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean = true,
    onPrimaryAction: () -> Unit,
    disconnectLabel: String,
    disconnectExplanation: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showIdentityHeader: Boolean = true,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
    advanced: (@Composable ColumnScope.() -> Unit)? = null,
    about: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var confirmDisconnect by remember(title) { mutableStateOf(false) }
    var showAbout by remember(title) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        onBack?.let { callback ->
            JarvysBackNavigationButton(callback)
        }
        if (showIdentityHeader) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) { icon() }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!connected) {
            Text(summary, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            Button(onClick = onPrimaryAction, enabled = primaryActionEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(primaryActionLabel)
            }
        } else {
            JarvysGroup(contentPadding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val initial = (account?.firstOrNull() ?: title.firstOrNull() ?: '?').uppercaseChar()
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center) {
                        Text(initial.toString(), color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(account?.takeIf(String::isNotBlank) ?: title, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.connector_status_connected), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(it, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            content()
            JarvysGroup(containerColor = MaterialTheme.colorScheme.errorContainer) {
                TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(disconnectLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        about?.let {
            TextButton(onClick = { showAbout = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.connector_about))
                Icon(LucideIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        advanced?.let { block ->
            var expanded by remember(title) { mutableStateOf(false) }
            JarvysGroup(contentPadding = PaddingValues(horizontal = JarvysUiTokens.ScreenPadding, vertical = 4.dp)) {
                Column {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (expanded) R.string.connector_collapse else R.string.connector_advanced))
                        Spacer(Modifier.weight(1f))
                        Icon(if (expanded) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    if (expanded) block()
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.connector_disconnect_confirm_title, title)) },
        text = { ScrollableDialogContent { Text(disconnectExplanation) } },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text(disconnectLabel) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.connector_cancel)) } },
    )
    if (showAbout && about != null) AlertDialog(
        onDismissRequest = { showAbout = false },
        title = { Text(stringResource(R.string.connector_about_title, title)) },
        text = { ScrollableDialogContent { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = about) } },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.connector_close)) } },
    )
}

internal data class ConnectorPolicyChoice(val labelResource: Int, val onSelect: () -> Unit)

@Composable
internal fun ConnectorPolicySelector(
    selectedLabelResource: Int,
    choices: List<ConnectorPolicyChoice>,
    enabled: Boolean = true,
) {
    var expanded by remember(selectedLabelResource, choices.size) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(selectedLabelResource), maxLines = 1)
            Icon(LucideIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(
                text = { Text(stringResource(choice.labelResource)) },
                onClick = { expanded = false; choice.onSelect() },
            ) }
        }
    }
}

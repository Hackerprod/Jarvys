package com.jarvys.agent.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
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

/** One layout contract for read-only values, native policies, OAuth scopes and MCP tools.
 * The trailing slot never depends on the selected value. Large text wraps inside it while
 * the left column retains nearly half of a narrow row; no touch target may overflow into its neighbour.
 */
@Composable
internal fun ConnectorPermissionRow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    permission: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val permissionWidth = minOf(112.dp * fontScale, maxWidth * 0.5f)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), content = content)
            Box(Modifier.width(permissionWidth), contentAlignment = Alignment.CenterEnd) { permission() }
        }
    }
}

internal data class ConnectorPolicyChoice(val labelResource: Int, val onSelect: () -> Unit)

/** Read access is informational. Reserving the same arrow slot keeps its value aligned. */
@Composable
internal fun ConnectorPolicyValue(selectedLabelResource: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterEnd) {
        ConnectorPolicyContent(selectedLabelResource, showChevron = false)
    }
}

/** Also used by the initial-policy editor, whose menu owns its existing option semantics. */
@Composable
internal fun ConnectorPolicyButton(
    selectedLabelResource: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        // A pill clips the corners of multiline labels at large font scales.
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)) {
        ConnectorPolicyContent(selectedLabelResource, showChevron = true)
    }
}

@Composable
private fun ConnectorPolicyContent(selectedLabelResource: Int, showChevron: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(selectedLabelResource), Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End,
            color = if (showChevron) androidx.compose.material3.LocalContentColor.current else MaterialTheme.colorScheme.primary)
        if (showChevron) Icon(LucideIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(17.dp))
        else Spacer(Modifier.size(17.dp))
    }
}

@Composable
internal fun ConnectorPolicySelector(
    selectedLabelResource: Int,
    choices: List<ConnectorPolicyChoice>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(selectedLabelResource, choices.size, enabled) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ConnectorPolicyButton(selectedLabelResource, onClick = { expanded = true },
            enabled = enabled, modifier = modifier)
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(
                text = { Text(stringResource(choice.labelResource)) },
                onClick = { expanded = false; choice.onSelect() },
            ) }
        }
    }
}

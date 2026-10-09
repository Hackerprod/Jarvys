package com.jarvys.agent.flavor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import android.widget.Toast
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorState
import com.jarvys.agent.connectors.FlavorRemoteServicesPanel
import com.jarvys.agent.connectors.GoogleOAuthManager
import com.jarvys.agent.connectors.GoogleOAuthProtocol
import com.jarvys.agent.connectors.GoogleOAuthError
import com.jarvys.agent.connectors.GoogleOAuthException
import com.jarvys.agent.connectors.GoogleIdentityAuthorizationException
import com.jarvys.agent.connectors.GoogleIdentityFailure
import com.jarvys.agent.connectors.GoogleRevocationState
import com.jarvys.agent.connectors.GoogleConfigurationDiagnostics
import com.jarvys.agent.connectors.GoogleAuthorizationDiagnostic
import com.jarvys.agent.connectors.GoogleAuthorizationPhase
import com.jarvys.agent.connectors.GoogleAuthorizationFailureCategory
import com.jarvys.agent.connectors.GmailConnector
import com.jarvys.agent.connectors.DriveConnector
import com.jarvys.agent.mcp.McpConnectionStatus
import com.jarvys.agent.connectors.ConnectorRowsGroup
import com.jarvys.agent.connectors.ConnectorRowsDivider
import com.jarvys.agent.connectors.ServiceBrandIconTile
import com.jarvys.agent.connectors.ConnectorDetailSection
import com.jarvys.agent.connectors.ConnectorDetailScaffold
import com.jarvys.agent.connectors.ConnectorPolicyChoice
import com.jarvys.agent.connectors.ConnectorPolicySelector
import com.jarvys.agent.connectors.ConnectorPermissionRow

/** Display/test identifier for an actual scope, including the trailing-slash full-mail scope. */
internal fun googleScopeName(scope: String): String = scope.removeSuffix("/").substringAfterLast('/')

/** Google-only large-text adaptation; other connectors retain their existing row layout. */
@Composable
private fun GooglePermissionRow(
    tagPrefix: String,
    content: @Composable ColumnScope.() -> Unit,
    permission: @Composable () -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.6f) {
        Column(Modifier.fillMaxWidth().testTag("$tagPrefix-row").padding(vertical = 4.dp)) {
            Column(Modifier.fillMaxWidth().testTag("$tagPrefix-label"), content = content)
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix-selector-row"),
                contentAlignment = Alignment.CenterEnd) {
                // Wider controls fit whole localized policy words at 200% without shrinking text.
                Box(Modifier.fillMaxWidth(0.8f), contentAlignment = Alignment.CenterEnd) { permission() }
            }
        }
    } else {
        ConnectorPermissionRow(modifier = Modifier.testTag("$tagPrefix-row"), content = {
            Column(Modifier.testTag("$tagPrefix-label"), content = content)
        }, permission = permission)
    }
}

/** Reflected only by the Full variant; no Google API identifiers or resources enter Play sources. */
class FullRemoteServicesPanel : FlavorRemoteServicesPanel {
    override fun supportsService(serviceId: String): Boolean =
        serviceId == GmailConnector.ID || serviceId == DriveConnector.ID

    @Composable
    override fun Content(context: Context, selectedId: String?, onSelect: (String, String) -> Unit,
                         onDetailExit: () -> Unit) {
        val app = LocalContext.current.applicationContext
        val manager = remember(app) { GoogleOAuthManager.get(app) }
        val registry = remember(app) { ConnectorRegistry.get(app) }
        val definitions by registry.definitions.collectAsState()
        val gmailTitle = stringResource(R.string.full_google_label_gmail)
        val driveTitle = stringResource(R.string.full_google_label_drive)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (selectedId == null) ConnectorRowsGroup {
            GoogleServiceCard(
                title = stringResource(R.string.full_google_label_gmail),
                description = stringResource(R.string.full_google_description_gmail),
                brandIconId = "gmail",
                connectorId = GmailConnector.ID,
                manager = manager,
                registry = registry,
                scopes = listOf(
                    GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read,
                    GoogleOAuthProtocol.GMAIL_LABELS to R.string.full_google_scope_gmail_labels,
                    GoogleOAuthProtocol.GMAIL_MODIFY to R.string.full_google_scope_gmail_modify,
                    GoogleOAuthProtocol.GMAIL_COMPOSE to R.string.full_google_scope_gmail_compose,
                    GoogleOAuthProtocol.GMAIL_SEND to R.string.full_google_scope_gmail_send,
                    GoogleOAuthProtocol.GMAIL_FULL to R.string.full_google_scope_gmail_full,
                ),
                selected = false,
                onSelect = { onSelect(GmailConnector.ID, gmailTitle) },
            )
            ConnectorRowsDivider()
            GoogleServiceCard(
                title = stringResource(R.string.full_google_label_drive),
                description = stringResource(R.string.full_google_description_drive),
                brandIconId = "googledrive",
                connectorId = DriveConnector.ID,
                manager = manager,
                registry = registry,
                scopes = listOf(
                    GoogleOAuthProtocol.DRIVE_READ to R.string.full_google_scope_drive_read,
                    GoogleOAuthProtocol.DRIVE_FILE to R.string.full_google_scope_drive_file,
                ),
                selected = false,
                onSelect = { onSelect(DriveConnector.ID, driveTitle) },
            )
          }
          if (selectedId == GmailConnector.ID || selectedId == DriveConnector.ID) {
            val gmail = selectedId == GmailConnector.ID
            GoogleServiceCard(
                title = stringResource(if (gmail) R.string.full_google_label_gmail else R.string.full_google_label_drive),
                description = stringResource(if (gmail) R.string.full_google_description_gmail else R.string.full_google_description_drive),
                brandIconId = if (gmail) "gmail" else "googledrive",
                connectorId = if (gmail) GmailConnector.ID else DriveConnector.ID,
                manager = manager,
                registry = registry,
                scopes = if (gmail) listOf(
                    GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read,
                    GoogleOAuthProtocol.GMAIL_LABELS to R.string.full_google_scope_gmail_labels,
                    GoogleOAuthProtocol.GMAIL_MODIFY to R.string.full_google_scope_gmail_modify,
                    GoogleOAuthProtocol.GMAIL_COMPOSE to R.string.full_google_scope_gmail_compose,
                    GoogleOAuthProtocol.GMAIL_SEND to R.string.full_google_scope_gmail_send,
                    GoogleOAuthProtocol.GMAIL_FULL to R.string.full_google_scope_gmail_full,
                ) else listOf(
                    GoogleOAuthProtocol.DRIVE_READ to R.string.full_google_scope_drive_read,
                    GoogleOAuthProtocol.DRIVE_FILE to R.string.full_google_scope_drive_file,
                ),
                selected = true,
                onSelect = {},
                onDetailExit = onDetailExit,
            )
          }
            // Keep the definitions observed to ensure the flavor-owned connectors are registered.
            if (definitions.none { it.presentationGroup.name == "SERVICES" }) {
                Text(stringResource(R.string.full_google_config_missing), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun GoogleServiceCard(
    title: String,
    description: String,
    brandIconId: String,
    connectorId: String,
    manager: GoogleOAuthManager,
    registry: ConnectorRegistry,
    scopes: List<Pair<String, Int>>,
    selected: Boolean,
    onSelect: () -> Unit,
    onDetailExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val status by registry.states.collectAsState()
    val definitions by registry.definitions.collectAsState()
    val autonomyRevision by registry.autonomyRevision.collectAsState()
    val enabled = status[connectorId] == ConnectorState.CONNECTED &&
        scopes.firstOrNull()?.first?.let(manager::isScopeGranted) == true
    var setupDialog by remember(connectorId) { mutableStateOf(false) }
    var revokeDialog by remember(connectorId) { mutableStateOf(false) }
    var forgetPendingDialog by remember(connectorId) { mutableStateOf(false) }
    val managerRevision by manager.stateRevision.collectAsState()
    val revocation = remember(manager, managerRevision) { manager.revocationState() }
    val pendingLegacy = remember(manager, managerRevision) { manager.hasPendingLegacyRevocation() }
    val clientIdentity = remember(context) { GoogleConfigurationDiagnostics.identity(context) }
    val inProgress = remember(manager, managerRevision) { manager.isAuthorizationInProgress() }
    var identityMode by remember(connectorId) { mutableStateOf(manager.isIdentityMode()) }
    val isConfigured = manager.isConfigured()
    var clientId by remember(identityMode) { mutableStateOf(manager.configuredClientId()) }
    var accountLabel by remember(identityMode) { mutableStateOf(manager.configuredAccountLabel()) }
    var clientSecret by remember(identityMode) { mutableStateOf("") }
    // Only localized app-authored text and the explicitly clicked scope are saved across recreation.
    // Never retain the Throwable, provider text, account details or Intent in UI state.
    var authorizationError by rememberSaveable(connectorId) { mutableStateOf<String?>(null) }
    var failedScope by rememberSaveable(connectorId) { mutableStateOf<String?>(null) }

    if (!selected) {
        val currentState = status[connectorId]
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ServiceBrandIconTile(brandIconId, title)
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (currentState == ConnectorState.CONNECTED && enabled) Text(stringResource(R.string.connector_status_connected),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            Icon(LucideIcons.ChevronRight, contentDescription = stringResource(R.string.connector_details_accessibility),
                modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val reauthorize = identityMode && manager.isIdentityReauthorizationRequired()
    val identityAccount = if (identityMode) manager.identityAccountEmail() else manager.configuredAccountLabel()
    val primaryActionLabel = when {
        inProgress -> R.string.full_google_authorizing
        authorizationError != null -> R.string.full_google_retry_authorization
        identityMode && reauthorize -> R.string.full_google_reauthorize
        identityMode -> R.string.full_google_connect_integrated
        !isConfigured -> R.string.full_google_configure
        enabled -> R.string.connector_reconnect
        else -> R.string.connector_connect
    }
    fun authorizeScope(scope: String) {
        val activity = context as? ComponentActivity
        if (activity == null) Toast.makeText(context, R.string.full_google_config_missing, Toast.LENGTH_LONG).show()
        else {
            manager.authorize(activity, scope) { result ->
                result.onSuccess {
                    authorizationError = null; failedScope = null
                    runCatching { registry.connect(connectorId) }
                    Toast.makeText(context, R.string.full_google_auth_success, Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    failedScope = scope
                    authorizationError = googleOAuthDiagnosticMessage(context, error)
                }
            }
        }
    }
    fun disconnectScopes(scope: String) {
        manager.disableScope(scope)
        registry.disconnect(GmailConnector.ID)
        registry.disconnect(DriveConnector.ID)
        Toast.makeText(context, R.string.full_google_feature_denied, Toast.LENGTH_LONG).show()
    }
    ConnectorDetailScaffold(
        title = title,
        subtitle = stringResource(when {
            enabled -> R.string.connector_status_connected
            reauthorize -> R.string.remote_service_reauth_required
            else -> R.string.connector_state_disconnected
        }),
        icon = { ServiceBrandIconTile(brandIconId, title, size = 42.dp) },
        connected = enabled,
        account = identityAccount,
        summary = description,
        primaryActionLabel = stringResource(primaryActionLabel),
        primaryActionEnabled = !inProgress && revocation != GoogleRevocationState.PENDING && !pendingLegacy,
        onPrimaryAction = {
            when {
                !identityMode && !isConfigured -> setupDialog = true
                authorizationError != null && scopes.any { it.first == failedScope } -> authorizeScope(requireNotNull(failedScope))
                else -> scopes.firstOrNull()?.first?.let(::authorizeScope)
            }
        },
        disconnectLabel = stringResource(R.string.full_google_disconnect),
        disconnectExplanation = stringResource(R.string.full_google_disconnect_body),
        onDisconnect = {
            runCatching {
                manager.disconnectLocal()
                registry.disconnect(GmailConnector.ID)
                registry.disconnect(DriveConnector.ID)
                clientId = ""; accountLabel = ""
            }.onSuccess {
                authorizationError = null; failedScope = null
                Toast.makeText(context, R.string.full_google_local_disconnected, Toast.LENGTH_LONG).show()
                onDetailExit()
            }
                .onFailure { error -> Toast.makeText(context, error.message.orEmpty(), Toast.LENGTH_LONG).show() }
        },
        showIdentityHeader = false,
        error = authorizationError,
        content = {
            if (authorizationError != null) {
                TextButton(onClick = { failedScope?.takeIf { scope -> scopes.any { it.first == scope } }?.let(::authorizeScope) },
                    enabled = !inProgress && revocation != GoogleRevocationState.PENDING && !pendingLegacy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.full_google_retry_authorization)) }
            }
            val readScopes = scopes.filter { it.first.endsWith("readonly") }
            val writeScopes = scopes.filterNot { it.first.endsWith("readonly") }
            val actualGrants = manager.grantedScopes().filter { granted ->
                if (connectorId == GmailConnector.ID) granted in GoogleOAuthProtocol.acceptedScopes(GoogleOAuthProtocol.GMAIL_SEND) ||
                    granted in setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_LABELS) else granted.startsWith("https://www.googleapis.com/auth/drive.")
            }
            Text(stringResource(R.string.full_google_capability_explanation), fontSize = 12.sp)
            if (actualGrants.isNotEmpty()) Text(stringResource(R.string.full_google_actual_grants,
                actualGrants.joinToString(", ", transform = ::googleScopeName)), fontSize = 12.sp,
                modifier = Modifier.testTag("google-actual-grants"))
            Text(stringResource(R.string.full_google_scope_deny_explanation), fontSize = 12.sp)
            if (connectorId == GmailConnector.ID && !identityMode && GoogleOAuthProtocol.GMAIL_SEND in manager.grantedScopes() &&
                !manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND)) {
                Text(stringResource(R.string.full_google_legacy_send_unverified), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error)
            }
            if (connectorId == GmailConnector.ID && !identityMode && GoogleOAuthProtocol.GMAIL_LABELS in manager.grantedScopes() &&
                !manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_LABELS)) {
                Text(stringResource(R.string.full_google_legacy_labels_unverified), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error)
            }
            @Composable fun ScopeRows(rows: List<Pair<String, Int>>) {
                rows.forEach { (scope, labelId) ->
                    val effective = manager.effectiveGrantedScope(scope)
                    val granted = effective != null
                    val fullLabel = stringResource(labelId)
                    GooglePermissionRow(tagPrefix = "google-scope-${googleScopeName(scope)}", content = {
                        Text(fullLabel, fontSize = 14.sp)
                        if (effective != null) Text(stringResource(R.string.full_google_granted_through, googleScopeName(effective)),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }, permission = {
                        val choices = if (granted) listOf(
                            ConnectorPolicyChoice(R.string.connector_policy_allow) {},
                            ConnectorPolicyChoice(R.string.connector_policy_deny) { disconnectScopes(scope) },
                        ) else listOf(ConnectorPolicyChoice(R.string.connector_add_permission) { authorizeScope(scope) })
                        ConnectorPolicySelector(
                            modifier = Modifier.testTag("google-scope-${googleScopeName(scope)}-control")
                                .semantics { contentDescription = fullLabel },
                            selectedLabelResource = if (granted) R.string.connector_policy_allow else R.string.connector_add_permission,
                            choices = choices, enabled = isConfigured && !inProgress && revocation != GoogleRevocationState.PENDING && !pendingLegacy,
                        )
                    })
                }
            }
            ConnectorDetailSection(stringResource(R.string.remote_service_read_tool)) { ScopeRows(readScopes) }
            if (connectorId == GmailConnector.ID) {
                ConnectorDetailSection(stringResource(R.string.full_google_section_labels)) {
                    ScopeRows(writeScopes.filter { it.first == GoogleOAuthProtocol.GMAIL_LABELS })
                    Text(stringResource(R.string.full_google_labels_explanation), fontSize = 12.sp)
                }
                ConnectorDetailSection(stringResource(R.string.full_google_section_manage)) {
                    ScopeRows(writeScopes.filter { it.first == GoogleOAuthProtocol.GMAIL_MODIFY })
                    Text(stringResource(R.string.full_google_modify_explanation), fontSize = 12.sp)
                }
                ConnectorDetailSection(stringResource(R.string.full_google_section_compose_send)) {
                    ScopeRows(writeScopes.filter { it.first in setOf(GoogleOAuthProtocol.GMAIL_COMPOSE, GoogleOAuthProtocol.GMAIL_SEND) })
                }
                ConnectorDetailSection(stringResource(R.string.full_google_section_irreversible)) {
                    ScopeRows(writeScopes.filter { it.first == GoogleOAuthProtocol.GMAIL_FULL })
                    Text(stringResource(R.string.full_google_full_explanation), fontSize = 12.sp)
                }
            } else if (writeScopes.isNotEmpty()) ConnectorDetailSection(stringResource(R.string.remote_service_write_tool)) { ScopeRows(writeScopes) }
            val definition = definitions.firstOrNull { it.id == connectorId }
            val writes = definition?.operations.orEmpty().filter { it.write }
            if (definition != null && writes.isNotEmpty()) ConnectorDetailSection(stringResource(R.string.connector_section_tools)) {
                writes.forEach { operation ->
                    val configured = remember(registry, definition, operation, autonomyRevision) {
                        registry.configuredAutonomyPolicy(definition, operation)
                    }
                    val requiredScope = if (connectorId == GmailConnector.ID) GmailConnector.requiredScope(operation.name)
                        else GoogleOAuthProtocol.DRIVE_FILE
                    val scopeAvailable = requiredScope != null && manager.isScopeGranted(requiredScope)
                    val canAllow = scopeAvailable && operation.autonomyAllowed &&
                        registry.autonomyPolicyUnavailableUiReason(definition, operation) == null
                    val choices = buildList {
                        add(ConnectorPolicyChoice(R.string.connector_policy_ask) { registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK) })
                        if (canAllow) add(ConnectorPolicyChoice(R.string.connector_policy_allow) {
                            registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
                        })
                        add(ConnectorPolicyChoice(R.string.connector_policy_deny) { registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.DENY) })
                    }
                    val fullLabel = if (operation.displayLabelResourceId != 0) context.getString(operation.displayLabelResourceId)
                        else operation.displayLabel
                    GooglePermissionRow(tagPrefix = "google-policy-${operation.name}", content = {
                        Text(fullLabel, fontWeight = FontWeight.Medium)
                    }, permission = {
                        ConnectorPolicySelector(when (configured) {
                            AutonomyPolicy.ASK -> R.string.connector_policy_ask
                            AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
                            AutonomyPolicy.DENY -> R.string.connector_policy_deny
                        }, choices, modifier = Modifier.testTag("google-policy-${operation.name}-control")
                            .semantics { contentDescription = fullLabel })
                    })
                    // Keep explanatory text full-width. In the narrow label column it can make a
                    // single 2x-text row taller than the viewport and clip the centered selector.
                    if (!scopeAvailable) Text(stringResource(R.string.full_google_operation_scope_unavailable),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().testTag("google-policy-${operation.name}-unavailable"))
                    if (connectorId == GmailConnector.ID && operation.name in setOf("delete_messages", "delete_label")) {
                        Text(stringResource(R.string.full_google_irreversible_operation), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (inProgress) {
                Text(stringResource(R.string.full_google_authorizing), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { manager.cancelAuthorization() }) {
                    Text(stringResource(R.string.full_google_cancel_authorization))
                }
            }
        },
        about = {
            Text(description)
            Text(stringResource(R.string.full_google_ai_disclosure))
            Text(stringResource(R.string.remote_service_credential_privacy))
            Text(stringResource(R.string.full_google_unverified_notice))
            if (identityMode) Text(stringResource(R.string.full_google_short_lived_token_note))
            else Text(stringResource(R.string.full_google_mobile_loopback_warning), color = MaterialTheme.colorScheme.error)
            Text(if (connectorId == GmailConnector.ID) "https://gmail.googleapis.com/gmail/v1/users/me" else "https://www.googleapis.com/drive/v3",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connectorId == GmailConnector.ID) Text(stringResource(R.string.full_google_enable_send_warning))
        },
        advanced = {
            GoogleConfigurationHelp(clientIdentity)
            OutlinedButton(onClick = { revokeDialog = true }, enabled = !inProgress && revocation != GoogleRevocationState.PENDING,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.full_google_revoke_action)) }
            GoogleRevocationStatus(revocation)
            if (pendingLegacy) {
                Text(stringResource(R.string.full_google_pending_legacy_help), fontSize = 12.sp)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections"))) }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.full_google_review_account_access)) }
                OutlinedButton(onClick = { forgetPendingDialog = true },
                    enabled = revocation != GoogleRevocationState.PENDING && !inProgress, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.full_google_forget_pending_action))
                }
            }
            OutlinedButton(onClick = { setupDialog = true }, enabled = !inProgress && revocation != GoogleRevocationState.PENDING && !pendingLegacy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.full_google_configure))
            }
            if (!identityMode) TextButton(onClick = {
                runCatching {
                    manager.useIdentityMode(); identityMode = true
                    authorizationError = null; failedScope = null
                    registry.disconnect(GmailConnector.ID); registry.disconnect(DriveConnector.ID)
                    clientId = ""; accountLabel = ""; clientSecret = ""
                }.onFailure { Toast.makeText(context, googleOAuthErrorMessage(context, it), Toast.LENGTH_LONG).show() }
            }, enabled = !inProgress && revocation != GoogleRevocationState.PENDING && !pendingLegacy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.full_google_use_integrated_authorization)) }
        },
    )

    if (forgetPendingDialog) AlertDialog(
        onDismissRequest = { forgetPendingDialog = false },
        title = { Text(stringResource(R.string.full_google_forget_pending_title)) },
        text = { Text(stringResource(R.string.full_google_forget_pending_body)) },
        confirmButton = { TextButton(onClick = {
            runCatching { manager.forgetPendingRevocationLocally() }
                .onSuccess { forgetPendingDialog = false; clientId = ""; accountLabel = "" }
                .onFailure { Toast.makeText(context, googleOAuthErrorMessage(context, it), Toast.LENGTH_LONG).show() }
        }) { Text(stringResource(R.string.full_google_forget_pending_action)) } },
        dismissButton = { TextButton(onClick = { forgetPendingDialog = false }) { Text(stringResource(R.string.connector_cancel)) } },
    )

    if (revokeDialog) AlertDialog(
        onDismissRequest = { revokeDialog = false },
        title = { Text(stringResource(R.string.full_google_revoke_title)) },
        text = { Text(stringResource(R.string.full_google_revoke_body)) },
        confirmButton = { TextButton(onClick = {
            revokeDialog = false
            manager.revokeAndClear { result ->
                registry.refreshStates()
                Toast.makeText(context, if (result.isSuccess) R.string.full_google_revoke_verified
                    else R.string.full_google_revoke_failed, Toast.LENGTH_LONG).show()
            }
            registry.disconnect(GmailConnector.ID)
            registry.disconnect(DriveConnector.ID)
        }) { Text(stringResource(R.string.full_google_revoke_action)) } },
        dismissButton = { TextButton(onClick = { revokeDialog = false }) { Text(stringResource(R.string.connector_cancel)) } },
    )

    if (setupDialog) AlertDialog(
        onDismissRequest = { setupDialog = false },
        title = { Text(stringResource(R.string.full_google_client_guide_title)) },
        text = { ScrollableDialogContent {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.full_google_client_guide), fontSize = 13.sp, lineHeight = 18.sp)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/auth/clients"))) }
                }) { Text(stringResource(R.string.full_google_open_console)) }
                OutlinedTextField(value = clientId, onValueChange = { clientId = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.full_google_client_id)) }, singleLine = true)
                OutlinedTextField(value = accountLabel, onValueChange = { accountLabel = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.full_google_account_label)) }, singleLine = true)
                OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.full_google_client_secret)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                manager.configure(clientId, clientSecret, accountLabel)
                authorizationError = null; failedScope = null
                identityMode = false
                registry.disconnect(connectorId)
                setupDialog = false
                clientSecret = ""
            }
                .onFailure { error -> Toast.makeText(context, context.getString(R.string.full_google_setup_error, error.message.orEmpty()), Toast.LENGTH_LONG).show() }
        }) { Text(stringResource(R.string.full_google_configure)) } },
        dismissButton = { TextButton(onClick = { setupDialog = false; clientSecret = "" }) {
            Text(stringResource(R.string.connector_cancel))
        } },
    )

}

internal fun googleOAuthErrorMessage(context: Context, error: Throwable): String {
    val identityFailure = (error as? GoogleIdentityAuthorizationException)?.reason
    if (identityFailure != null) {
        return when (identityFailure) {
            GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE -> context.getString(R.string.full_google_play_services_unavailable)
            GoogleIdentityFailure.USER_CANCELLED -> context.getString(R.string.full_google_user_cancelled)
            GoogleIdentityFailure.SCOPE_NOT_GRANTED -> context.getString(R.string.full_google_scope_not_granted)
            GoogleIdentityFailure.ACCESS_BLOCKED -> context.getString(R.string.full_google_error_access_blocked)
            GoogleIdentityFailure.NETWORK -> context.getString(R.string.full_google_network_error)
            GoogleIdentityFailure.OTHER -> context.getString(R.string.full_google_auth_error)
        }
    }
    return when (GoogleOAuthProtocol.mapError((error as? GoogleOAuthException)?.providerError
        ?: Regex("OAuth authorization failed: ([A-Za-z0-9_-]+)").find(error.message.orEmpty())?.groupValues?.get(1))) {
        GoogleOAuthError.REDIRECT_MISMATCH -> context.getString(R.string.full_google_error_redirect_mismatch)
        GoogleOAuthError.ACCESS_BLOCKED -> context.getString(R.string.full_google_error_access_blocked)
        GoogleOAuthError.CALLBACK_TIMEOUT -> context.getString(R.string.full_google_error_callback_timeout)
        GoogleOAuthError.OTHER -> context.getString(R.string.full_google_auth_error)
    }
}

/** Display only our localized guidance and closed diagnostic fields, never Throwable.message. */
internal fun googleOAuthDiagnosticMessage(context: Context, error: Throwable): String {
    val diagnostic = GoogleAuthorizationDiagnostic.from(error)
    val phase = when (diagnostic.phase) {
        GoogleAuthorizationPhase.REQUEST -> R.string.full_google_phase_request
        GoogleAuthorizationPhase.RESOLUTION -> R.string.full_google_phase_resolution
        GoogleAuthorizationPhase.RESULT -> R.string.full_google_phase_result
        GoogleAuthorizationPhase.PLAY_SERVICES -> R.string.full_google_phase_services
        GoogleAuthorizationPhase.LOCAL_STATE -> R.string.full_google_phase_local
        GoogleAuthorizationPhase.UNKNOWN -> R.string.full_google_phase_unknown
    }
    val category = when (diagnostic.category) {
        GoogleAuthorizationFailureCategory.PROVIDER -> R.string.full_google_failure_provider
        GoogleAuthorizationFailureCategory.CANCELLED -> R.string.full_google_failure_cancelled
        GoogleAuthorizationFailureCategory.TIMEOUT -> R.string.full_google_failure_timeout
        GoogleAuthorizationFailureCategory.PLAY_SERVICES -> R.string.full_google_failure_services
        GoogleAuthorizationFailureCategory.LOCAL_STATE -> R.string.full_google_failure_local
        GoogleAuthorizationFailureCategory.UNEXPECTED_RESPONSE -> R.string.full_google_failure_response
        GoogleAuthorizationFailureCategory.UNKNOWN -> R.string.full_google_failure_unknown
    }
    return buildList {
        add(googleOAuthErrorMessage(context, error))
        add(context.getString(R.string.full_google_diagnostic_phase, context.getString(phase)))
        add(context.getString(R.string.full_google_diagnostic_category, context.getString(category)))
        diagnostic.apiStatusCode?.let { add(context.getString(R.string.full_google_diagnostic_api_code, it)) }
    }.joinToString("\n\n")
}

@Composable
internal fun GoogleConfigurationHelp(identity: GoogleConfigurationDiagnostics.ClientIdentity) {
    ConnectorDetailSection(stringResource(R.string.full_google_diagnostics_title)) {
        Text(stringResource(R.string.full_google_diagnostics_body), fontSize = 13.sp)
        Text(stringResource(R.string.full_google_diagnostics_package, identity.packageName), fontSize = 12.sp)
        if (identity.signingSha1.isEmpty()) Text(stringResource(R.string.full_google_diagnostics_no_cert), fontSize = 12.sp)
        identity.signingSha1.forEach { fingerprint ->
            Text(stringResource(R.string.full_google_diagnostics_sha1, fingerprint), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun GoogleRevocationStatus(state: GoogleRevocationState) {
    val resource = when (state) {
        GoogleRevocationState.PENDING -> R.string.full_google_revoke_pending
        GoogleRevocationState.VERIFIED -> R.string.full_google_revoke_verified
        GoogleRevocationState.FAILED -> R.string.full_google_revoke_failed
        GoogleRevocationState.NOT_REQUESTED -> null
    }
    if (resource != null) Text(stringResource(resource), fontSize = 12.sp,
        color = if (state == GoogleRevocationState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

package com.jarvys.agent

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.SecretStore
import com.jarvys.agent.connectors.*
import com.jarvys.agent.flavor.GoogleServiceCard
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Real Full-only Google scope and operation rows, using fake grants and no network or accounts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class ConnectedGooglePermissionScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var priorSecrets: Any? = null
    private val policies = RecordingPermissionStore()
    private val authorizations = Collections.synchronizedList(mutableListOf<Set<String>>())
    private val requests = AtomicInteger()
    private val revocations = AtomicInteger()
    private lateinit var manager: GoogleOAuthManager
    private var currentScale = 1f
    private lateinit var registry: ConnectorRegistry
    private lateinit var gmail: ConnectorDefinition
    private lateinit var drive: ConnectorDefinition
    private val gmailScopes = listOf(
        GoogleOAuthProtocol.GMAIL_READ to R.string.full_google_scope_gmail_read,
        GoogleOAuthProtocol.GMAIL_LABELS to R.string.full_google_scope_gmail_labels,
        GoogleOAuthProtocol.GMAIL_MODIFY to R.string.full_google_scope_gmail_modify,
        GoogleOAuthProtocol.GMAIL_COMPOSE to R.string.full_google_scope_gmail_compose,
        GoogleOAuthProtocol.GMAIL_SEND to R.string.full_google_scope_gmail_send,
        GoogleOAuthProtocol.GMAIL_FULL to R.string.full_google_scope_gmail_full)
    private val driveScopes = listOf(
        GoogleOAuthProtocol.DRIVE_READ to R.string.full_google_scope_drive_read,
        GoogleOAuthProtocol.DRIVE_FILE to R.string.full_google_scope_drive_file)

    @Before fun setup() {
        priorSecrets = singleton.get(null)
        val secrets = compose.activity.getSharedPreferences("ux21_fake_google_secrets", Context.MODE_PRIVATE)
        secrets.edit().clear().commit()
        singleton.set(null, SecretStore(secrets))
        compose.activity.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun restoreSecrets() { singleton.set(null, priorSecrets) }

    private fun seed(scopes: Set<String>) {
        compose.activity.getSharedPreferences("jarvys_full_oauth_config", Context.MODE_PRIVATE).edit()
            .putString("google_identity_granted_scopes", scopes.joinToString(" "))
            .putString("google_identity_account_email", "permission-fixture@example.test").commit()
        manager = GoogleOAuthManager(compose.activity,
            GoogleHttpTransport { _, _, _, _ -> requests.incrementAndGet(); error("Policy rows must not call Google APIs") },
            object : GoogleIdentityAuthorization {
                override fun authorize(scopes: Set<String>, accountEmail: String?): GoogleIdentityGrant {
                    authorizations += scopes.toSet()
                    return GoogleIdentityGrant("fake-in-memory-only-token", scopes, "permission-fixture@example.test")
                }
                override fun clearToken(accessToken: String) = Unit
                override fun revoke(scopes: Set<String>, accountEmail: String?) { revocations.incrementAndGet() }
            })
        val gate = ApprovalGate(100, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) = error("Editing policy must not execute operations")
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        registry = ConnectorRegistry.createForTestsWithAutonomy(PermissionConnectionPreferences(), { true }, gate,
            policies, NoopAutonomyActionNotifier)
        val contacts = object : ContactsGateway {
            override fun search(query: String, limit: Int) = emptyList<ContactRecord>()
            override fun find(contactId: Long): ContactRecord? = null
        }
        gmail = GmailConnector.definition(GmailConnector(manager, contacts, { false }, { false }))
        drive = DriveConnector.definition(manager)
        registry.register(gmail); registry.register(drive)
        if (manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ)) registry.connect(gmail.id)
        if (GoogleOAuthProtocol.DRIVE_READ in scopes) registry.connect(drive.id)
    }
    private fun show(gmailSelected: Boolean, scale: Float = 1f, dark: Boolean = false) {
        currentScale = scale
        val definition = if (gmailSelected) gmail else drive
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        GoogleServiceCard(compose.activity.getString(definition.displayNameResourceId),
                            compose.activity.getString(definition.descriptionResourceId),
                            if (gmailSelected) "gmail" else "googledrive", definition.id, manager, registry,
                            if (gmailSelected) gmailScopes else driveScopes, selected = true, onSelect = {})
                    }
                }
            }
        }
    }
    private fun scopeTag(scope: String) = "google-scope-${scope.removeSuffix("/").substringAfterLast('/')}"
    private fun policyTag(operation: ConnectorOperation) = "google-policy-${operation.name}"
    private fun pick(prefix: String, label: Int) {
        compose.onNodeWithTag("$prefix-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(label)) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
    }
    private fun geometry(prefix: String) {
        // Scroll the entire row; scrolling only its centered button can leave tall labels clipped.
        compose.onNodeWithTag("$prefix-row", useUnmergedTree = true).performScrollTo()
        val row = compose.onNodeWithTag("$prefix-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("$prefix-control").fetchSemanticsNode().boundsInRoot
        assertEquals("$prefix right edge", row.right, control.right, 1f)
        if (currentScale >= 1.6f) {
            val label = compose.onNodeWithTag("$prefix-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val selectorRow = compose.onNodeWithTag("$prefix-selector-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("$prefix full-width label left", row.left, label.left, 1f)
            assertEquals("$prefix full-width label right", row.right, label.right, 1f)
            assertTrue("$prefix selector must be below its complete label", control.top >= label.bottom - 1f)
            assertEquals("$prefix selector row vertical center", selectorRow.center.y, control.center.y, 1f)
            assertEquals("$prefix selector row right edge", selectorRow.right, control.right, 1f)
            assertTrue("$prefix wider large-text selector", control.width >= row.width * 0.8f - 1f)
        } else {
            assertEquals("$prefix vertical center", row.center.y, control.center.y, 1f)
        }
        assertTrue("$prefix 48 dp touch target", control.height >= 48f)
    }
    private fun policy(prefix: String, resource: Int) =
        compose.onNodeWithTag("$prefix-control").assertTextContains(compose.activity.getString(resource))
    private fun capture(name: String) = compose.runOnIdle { captureConnectedPermissionWindows(compose.activity, name) }

    @Test fun connectedGmailUsesRealScopeAndOperationRowsWithoutGrantingOnOpen() = gmailRows(1f, false)

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun connectedGmailSpanishLargeTextKeepsScopeAndOperationPoliciesAligned() = gmailRows(2f, true)

    private fun gmailRows(scale: Float, dark: Boolean) {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ))
        show(true, scale, dark)
        assertEquals(ConnectorState.CONNECTED, registry.state(gmail))
        gmailScopes.forEach { (scope, _) ->
            geometry(scopeTag(scope))
            capture("gmail-scope-${scope.removeSuffix("/").substringAfterLast('/')}-${if (dark) "es-dark-large" else "en-light"}")
            policy(scopeTag(scope), if (scope == GoogleOAuthProtocol.GMAIL_READ) R.string.connector_policy_allow
                else R.string.connector_add_permission)
        }
        compose.onNodeWithTag("${scopeTag(GoogleOAuthProtocol.GMAIL_READ)}-row", useUnmergedTree = true).performScrollTo()
        capture("gmail-connected-scopes-${if (dark) "es-dark-large" else "en-light"}")
        // Selecting the already granted value must not launch consent or widen the grant.
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_READ), R.string.connector_policy_allow)
        gmail.operations.filter { it.write }.forEach { operation ->
            geometry(policyTag(operation)); policy(policyTag(operation), R.string.connector_policy_ask)
            if (operation.name in setOf("modify_messages", "delete_messages", "delete_label")) {
                capture("gmail-ux31-operation-${operation.name}-${if (dark) "es-dark-large" else "en-light"}")
            }
        }
        assertTrue(authorizations.isEmpty()); assertTrue(policies.changes.isEmpty())
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_READ), manager.grantedScopes())
        val guarded = gmail.operations.first { it.name == GmailConnector.SEND_DRAFT }
        compose.onNodeWithTag("${policyTag(guarded)}-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_allow)) and hasAnyAncestor(isPopup())).assertDoesNotExist()
        capture("gmail-guarded-operation-${if (dark) "es-dark-large" else "en-light"}")
        compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_deny)) and hasAnyAncestor(isPopup())).performClick()
        assertEquals(listOf(Triple(gmail.id, guarded.name, AutonomyPolicy.DENY)), policies.changes)
        // Persisted state AND visible label must update immediately after the callback.
        policy(policyTag(guarded), R.string.connector_policy_deny)
        gmail.operations.filter { it.write && it != guarded }.forEach { sibling ->
            assertEquals(AutonomyPolicy.ASK, registry.configuredAutonomyPolicy(gmail, sibling))
            policy(policyTag(sibling), R.string.connector_policy_ask)
        }
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_READ), manager.grantedScopes())
        pick(policyTag(guarded), R.string.connector_policy_ask)
        policy(policyTag(guarded), R.string.connector_policy_ask)
        assertEquals(listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK), policies.changes.map { it.third })
        assertTrue(authorizations.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun explicitGmailComposeChoiceRequestsOnlyReadAndComposeExactlyOnce() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ))
        show(true)
        val composeScope = scopeTag(GoogleOAuthProtocol.GMAIL_COMPOSE)
        compose.onNodeWithTag("$composeScope-control").performScrollTo().performClick()
        assertTrue(authorizations.isEmpty())
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_READ), manager.grantedScopes())
        capture("gmail-add-compose-popup")
        compose.onNode(hasText(compose.activity.getString(R.string.connector_add_permission)) and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() &&
                authorizations.size == 1 && !manager.isAuthorizationInProgress()
        }
        policy(composeScope, R.string.connector_policy_allow)
        policy(scopeTag(GoogleOAuthProtocol.GMAIL_SEND), R.string.connector_policy_allow)
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_COMPOSE)), authorizations.toList())
        assertTrue(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND))
        assertFalse(GoogleOAuthProtocol.GMAIL_SEND in manager.grantedScopes())
        assertTrue(policies.changes.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun connectedDriveLargeTextPreservesEveryReviewedWriteAndOnlyRequestsDriveFile() {
        seed(setOf(GoogleOAuthProtocol.DRIVE_READ))
        show(false, 2f, true)
        driveScopes.forEach {
            geometry(scopeTag(it.first))
            capture("drive-scope-${it.first.substringAfterLast('/')}-es-dark-large")
        }
        compose.onNodeWithTag("${scopeTag(GoogleOAuthProtocol.DRIVE_READ)}-row", useUnmergedTree = true).performScrollTo()
        capture("drive-connected-scopes-es-dark-large")
        drive.operations.filter { it.write }.forEach { operation ->
            geometry(policyTag(operation))
            compose.onNodeWithTag("${policyTag(operation)}-control").performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_allow)) and hasAnyAncestor(isPopup())).assertDoesNotExist()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_ask)) and hasAnyAncestor(isPopup())).performClick()
        }
        assertEquals(drive.operations.count { it.write }, policies.changes.size)
        assertTrue(policies.changes.all { it.third == AutonomyPolicy.ASK })
        assertTrue(authorizations.isEmpty())
        pick(scopeTag(GoogleOAuthProtocol.DRIVE_FILE), R.string.connector_add_permission)
        compose.waitUntil(5_000) {
            compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() &&
                authorizations.size == 1 && !manager.isAuthorizationInProgress()
        }
        assertEquals(listOf(setOf(GoogleOAuthProtocol.DRIVE_READ, GoogleOAuthProtocol.DRIVE_FILE)), authorizations.toList())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ))
        policy(scopeTag(GoogleOAuthProtocol.DRIVE_FILE), R.string.connector_policy_allow)
        assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun denyingGrantedScopeDisconnectsLocallyWithoutSilentReauthorizationOrRevocation() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.DRIVE_READ))
        show(true)
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_READ), R.string.connector_policy_deny)
        assertEquals(ConnectorState.DISCONNECTED, registry.state(gmail))
        assertEquals(ConnectorState.DISCONNECTED, registry.state(drive))
        assertTrue(manager.grantedScopes().isEmpty())
        assertTrue(authorizations.isEmpty()); assertTrue(policies.changes.isEmpty())
        assertEquals(0, requests.get()); assertEquals(0, revocations.get())
        compose.onNodeWithTag("${scopeTag(GoogleOAuthProtocol.GMAIL_READ)}-control").assertDoesNotExist()
    }

    @Test fun modifyGrantShowsEffectiveCapabilitiesAndSeparateActualGrantsWithoutOpeningConsent() = effectiveCapabilities(1f, false)

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun modifyGrantSpanishTwoTimesTextKeepsNewScopeRowsAccessible() = effectiveCapabilities(2f, true)

    private fun effectiveCapabilities(scale: Float, dark: Boolean) {
        seed(setOf(GoogleOAuthProtocol.GMAIL_MODIFY))
        show(true, scale, dark)
        val suffix = if (dark) "es-dark-large" else "en-light"
        compose.onNodeWithTag("google-actual-grants").performScrollTo()
            .assertTextContains("gmail.modify", substring = true)
        capture("gmail-ux31-actual-grants-$suffix")
        gmailScopes.forEach { (scope, _) ->
            geometry(scopeTag(scope))
            policy(scopeTag(scope), if (scope == GoogleOAuthProtocol.GMAIL_FULL) R.string.connector_add_permission
                else R.string.connector_policy_allow)
            capture("gmail-ux31-${scope.removeSuffix("/").substringAfterLast('/') }-$suffix")
        }
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_MODIFY), manager.grantedScopes())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
        assertTrue(authorizations.isEmpty()); assertTrue(policies.changes.isEmpty())
        assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun explicitModifyClickOnlyRunsOAuthAndDoesNotChangeToolAutonomy() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ))
        show(true)
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_MODIFY), R.string.connector_add_permission)
        compose.waitUntil(5_000) {
            compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() &&
                authorizations.size == 1 && !manager.isAuthorizationInProgress()
        }
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_MODIFY)), authorizations.toList())
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
        assertTrue(policies.changes.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun explicitFullClickOnlyRunsOAuthAndNeverDeletesMailOrChangesAutonomy() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_MODIFY))
        show(true)
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_FULL), R.string.connector_add_permission)
        compose.waitUntil(5_000) {
            compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() &&
                authorizations.size == 1 && !manager.isAuthorizationInProgress()
        }
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_MODIFY, GoogleOAuthProtocol.GMAIL_FULL)), authorizations.toList())
        policy(scopeTag(GoogleOAuthProtocol.GMAIL_FULL), R.string.connector_policy_allow)
        assertTrue(policies.changes.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun denyingReadCapabilityBackedByFullGrantDisconnectsEveryEffectiveCapability() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_FULL, GoogleOAuthProtocol.DRIVE_READ))
        show(true)
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_READ), R.string.connector_policy_deny)
        assertTrue(manager.grantedScopes().isEmpty())
        gmailScopes.forEach { assertFalse(manager.isScopeGranted(it.first)) }
        assertEquals(ConnectorState.DISCONNECTED, registry.state(gmail))
        assertEquals(ConnectorState.DISCONNECTED, registry.state(drive))
        assertTrue(authorizations.isEmpty()); assertTrue(policies.changes.isEmpty())
        assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun explicitLabelsClickGrantsOnlyMetadataScopeWithoutMailboxManagementOrSending() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ))
        show(true)
        pick(scopeTag(GoogleOAuthProtocol.GMAIL_LABELS), R.string.connector_add_permission)
        compose.waitUntil(5_000) {
            compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() &&
                authorizations.size == 1 && !manager.isAuthorizationInProgress()
        }
        assertEquals(listOf(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_LABELS)), authorizations.toList())
        policy(scopeTag(GoogleOAuthProtocol.GMAIL_LABELS), R.string.connector_policy_allow)
        policy(scopeTag(GoogleOAuthProtocol.GMAIL_MODIFY), R.string.connector_add_permission)
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_COMPOSE))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_SEND))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_MODIFY))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_FULL))
        assertTrue(policies.changes.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test fun labelsOnlyGrantCannotMakeMailboxAppearConnected() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_LABELS))
        show(true)
        assertEquals(ConnectorState.DISCONNECTED, registry.state(gmail))
        assertFalse(manager.isScopeGranted(GoogleOAuthProtocol.GMAIL_READ))
        assertEquals(setOf(GoogleOAuthProtocol.GMAIL_LABELS), manager.grantedScopes())
        assertTrue(authorizations.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun irreversibleAskDenyPopupsAndLabelScopeUseFullLabelsAtTwoTimesText() {
        seed(setOf(GoogleOAuthProtocol.GMAIL_READ, GoogleOAuthProtocol.GMAIL_LABELS, GoogleOAuthProtocol.GMAIL_FULL))
        show(true, 2f, true)
        val labelPrefix = scopeTag(GoogleOAuthProtocol.GMAIL_LABELS)
        geometry(labelPrefix)
        compose.onNodeWithTag("$labelPrefix-control").assertContentDescriptionEquals(
            compose.activity.getString(R.string.full_google_scope_gmail_labels))
        capture("gmail-ux31-label-metadata-scope-es-dark-large")
        for (name in listOf("delete_messages", "delete_label")) {
            val operation = gmail.operations.first { it.name == name }
            val prefix = policyTag(operation)
            geometry(prefix)
            compose.onNodeWithTag("$prefix-control").assertContentDescriptionEquals(
                compose.activity.getString(operation.displayLabelResourceId))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_ask)) and
                hasAnyAncestor(hasTestTag("$prefix-control")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Ask must remain a whole word at 2x", 1, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("$prefix-control").performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_ask)) and hasAnyAncestor(isPopup())).assertExists()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_deny)) and hasAnyAncestor(isPopup())).assertExists()
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_allow)) and hasAnyAncestor(isPopup())).assertDoesNotExist()
            capture("gmail-ux31-$name-ask-deny-popup-es-dark-large")
            compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_deny)) and hasAnyAncestor(isPopup())).performClick()
            policy(prefix, R.string.connector_policy_deny)
            geometry(prefix)
            capture("gmail-ux31-$name-denied-es-dark-large")
        }
        assertEquals(listOf("delete_messages", "delete_label"), policies.changes.map { it.second })
        assertTrue(policies.changes.all { it.third == AutonomyPolicy.DENY })
        assertTrue(authorizations.isEmpty()); assertEquals(0, requests.get()); assertEquals(0, revocations.get())
    }
}

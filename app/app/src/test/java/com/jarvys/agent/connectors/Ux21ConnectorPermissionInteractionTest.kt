package com.jarvys.agent.connectors

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.mcp.McpConnectionSnapshot
import com.jarvys.agent.mcp.McpConnectionStatus
import com.jarvys.agent.mcp.McpCredentialVault
import com.jarvys.agent.mcp.McpServerConfig
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.mcp.McpToolAnnotations
import com.jarvys.agent.mcp.McpToolConfig
import com.jarvys.agent.mcp.McpWriteApprovalCoordinator
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.mcp.McpServerDetailPage
import com.jarvys.agent.ui.mcp.McpServerEditorScreen
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w393dp-h900dp-port-mdpi")
class Ux21ConnectorPermissionInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun eachExactNativePolicyChoiceCallsOnceAndUpdatesWithoutMovingTheColumn() {
        val resources = listOf(R.string.connector_policy_ask, R.string.connector_policy_allow, R.string.connector_policy_deny)
        var selected by mutableStateOf(R.string.connector_policy_allow)
        val received = mutableListOf<Int>()
        showSingle { Ux21FixtureRow("choice", R.string.connector_operation_calendar_create, selected,
            choices = resources.map { resource -> ConnectorPolicyChoice(resource) { received += resource; selected = resource } }) }
        val original = compose.onNodeWithTag("policy-choice").fetchSemanticsNode().boundsInRoot
        resources.forEachIndexed { index, resource ->
            compose.policyButton("choice").performTouchInput { click() }
            assertExactChoices(resources)
            menuChoice(resource).performTouchInput { click() }
            compose.onAllNodes(isPopup()).assertCountEquals(0)
            assertEquals(resources.take(index + 1), received)
            compose.policyLabel("choice", resource).assertIsDisplayed()
            val current = compose.onNodeWithTag("policy-choice").fetchSemanticsNode().boundsInRoot
            assertEquals(original.right, current.right, 0.5f)
            assertEquals(original.left, current.left, 0.5f)
        }
    }

    @Test fun restrictedChoiceSetsDoNotGainAnAllowAction() {
        val resources = listOf(R.string.connector_policy_ask, R.string.connector_policy_deny)
        val received = mutableListOf<Int>()
        showSingle { Ux21FixtureRow("restricted", R.string.connector_operation_calendar_create, resources.first(),
            choices = resources.map { resource -> ConnectorPolicyChoice(resource) { received += resource } }) }
        compose.policyButton("restricted").performClick()
        assertExactChoices(resources)
        compose.onAllNodes(hasText(compose.activity.getString(R.string.connector_policy_allow)) and hasAnyAncestor(isPopup()))
            .assertCountEquals(0)
        menuChoice(R.string.connector_policy_deny).performClick()
        assertEquals(listOf(R.string.connector_policy_deny), received)
    }

    @Test fun readOnlyAndDisabledControlsCannotOpenMenusOrSendCallbacks() {
        val received = mutableListOf<Int>()
        showSingle {
            Ux21FixtureRow("read", R.string.connector_operation_calendar_search, R.string.connector_policy_allow, readOnly = true)
            Ux21FixtureRow("disabled", R.string.connector_operation_calendar_create, R.string.connector_policy_ask,
                enabled = false, choices = listOf(ConnectorPolicyChoice(R.string.connector_policy_deny) { received += 1 }))
        }
        compose.onNodeWithTag("policy-read").performTouchInput { click() }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.policyButton("disabled").assertIsNotEnabled().performTouchInput { click() }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertTrue(received.isEmpty())
    }

    @Test fun disablingAnOpenSelectorClosesItAndReenablingDoesNotReopenIt() {
        var enabled by mutableStateOf(true)
        var received = 0
        showSingle { Ux21FixtureRow("live", R.string.connector_operation_calendar_create, R.string.connector_policy_ask,
            enabled = enabled, choices = listOf(ConnectorPolicyChoice(R.string.connector_policy_allow) { received++ })) }
        compose.policyButton("live").performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        compose.runOnIdle { enabled = false }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.policyButton("live").assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        compose.policyButton("live").assertIsEnabled()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertEquals(0, received)
        compose.policyButton("live").performClick()
        menuChoice(R.string.connector_policy_allow).performClick()
        assertEquals(1, received)
    }

    @Test fun nativeBackAndOutsideTouchDismissWithoutSelectingAndReopenRemainsUsable() {
        var received = 0
        showSingle { Ux21FixtureRow("dismiss", R.string.connector_operation_calendar_create, R.string.connector_policy_allow,
            choices = listOf(ConnectorPolicyChoice(R.string.connector_policy_ask) { received++ })) }
        compose.policyButton("dismiss").performClick()
        compose.dismissPopupWithBack()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertEquals(0, received)
        compose.policyButton("dismiss").performClick()
        compose.dismissPopupOutside()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertEquals(0, received)
        compose.policyButton("dismiss").performClick()
        menuChoice(R.string.connector_policy_ask).performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertEquals(1, received)
    }

    @Test fun actualMcpDetailSearchRetainsTypedQueryAcrossPopupDismissAndPermissionChange() {
        val repository = McpServerRepository(compose.activity, Ux21EmptyVault())
        val changed = mutableListOf<Pair<String, Boolean>>()
        var server by mutableStateOf(McpServerConfig("ux21-search", "UX21 test catalog", "https://example.test/mcp",
            tools = (0..12).map { index -> McpToolConfig("create_event_$index", "event_$index", "Calendar operation $index",
                "{}", enabled = true, annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = false,
                    title = "Calendar operation $index")) }))
        val coordinator = McpWriteApprovalCoordinator(InMemoryConnectorAutonomyStore(),
            ApprovalGate(1_000, object : ApprovalPresenter {
                override fun show(id: String, summary: ApprovalSummary) = error("A settings selection must not execute a tool")
                override fun update(id: String, decision: ApprovalDecision) = Unit
            }), object : AutonomyActionNotifier {
                override fun canPost() = true
                override fun missingRuntimePermission(): String? = null
                override fun post(record: AutonomyAuditRecord) = error("A settings selection must not execute a tool")
            })
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                McpServerDetailPage(server, McpConnectionSnapshot(McpConnectionStatus.READY), coordinator, repository,
                    onConnect = {}, onDisconnect = {}, onAuthorize = {}, onEnable = {}, onEdit = {}, onDelete = {},
                    onToolEnabled = { name, enabled ->
                        changed += name to enabled
                        server = server.copy(tools = server.tools.map { if (it.wireName == name) it.copy(enabled = enabled) else it })
                    })
            }
        }
        val search = compose.onNode(hasSetTextAction()).performScrollTo().performClick()
        search.performTextInput("operation 12")
        search.assertIsFocused().assertTextContains("operation 12")
        compose.onAllNodesWithTag("mcp-policy-create_event_0-row").assertCountEquals(0)
        compose.onNodeWithTag("mcp-policy-create_event_12-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mcp-policy-create_event_12-control").performClick()
        val resources = listOf(R.string.remote_service_policy_ask, R.string.remote_service_policy_allow, R.string.remote_service_policy_deny)
        assertExactChoices(resources)
        Ux21NativeCapture(compose, "mcp-search-en-393dp-light").capture("filtered-menu", includePopup = true)
        compose.dismissPopupWithBack()
        search.assertTextContains("operation 12")
        assertTrue(changed.isEmpty())
        compose.onNodeWithTag("mcp-policy-create_event_12-control").performClick()
        menuChoice(R.string.remote_service_policy_deny).performClick()
        assertEquals(listOf("create_event_12" to false), changed)
        search.assertTextContains("operation 12")
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        search.performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("mcp-policy-create_event_0-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mcp-policy-create_event_12-row").performScrollTo().assertIsDisplayed()
        assertEquals("Search and scrolling never duplicate permission changes", 1, changed.size)
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun actualNewMcpEditorKeepsInitialPolicyAndFullWidthHelpClearAtDoubleSpanishText() {
        compose.configureFontScale(2f)
        val repository = McpServerRepository(compose.activity, Ux21EmptyVault())
        var saved = 0
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                McpServerEditorScreen(initial = null, repository = repository, onNavigateBack = {},
                    onSave = { _, _ -> saved++ }, onSaved = {})
            }
        }
        compose.onNodeWithTag("mcp-editor-tool-policy").performScrollTo().assertIsDisplayed()
        val title = compose.onNodeWithText(compose.activity.getString(R.string.mcp_initial_tool_permissions),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("mcp-editor-tool-policy-selector").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val help = compose.onNodeWithText(compose.activity.getString(R.string.mcp_initial_tool_permissions_help),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Editor title clears fixed trailing control", title.right + 7f * density <= control.left)
        assertEquals("Editor title and control vertically align", title.center.y, control.center.y, 1f)
        assertTrue("Full-width help remains below the complete permission row", help.top >= maxOf(title.bottom, control.bottom))
        assertEquals("Help starts at the left content edge", title.left, help.left, 1f)
        assertTrue("Help is not squeezed into the title column", help.right > control.left)
        assertTrue("Control stays within the narrow editor", control.right <= 320f * density)
        assertTrue("Editor target remains at least 48dp", control.height >= 48f * density)
        val label = compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and
            hasAnyAncestor(hasTestTag("mcp-editor-tool-policy-selector")), useUnmergedTree = true)
        label.assertPolicyTextFits()
        assertEquals("Editor label is centered within its target", control.center.y, label.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        val captures = Ux21NativeCapture(compose, "mcp-editor-es-320dp-light-font2")
        captures.capture("initial-policy")
        compose.onNodeWithTag("mcp-editor-tool-policy-selector").performClick()
        assertExactChoices(listOf(R.string.remote_service_policy_ask, R.string.remote_service_policy_allow, R.string.remote_service_policy_deny))
        captures.capture("initial-policy-menu", includePopup = true)
        compose.onNodeWithTag("mcp-editor-tool-policy-option-allow").performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and
            hasAnyAncestor(hasTestTag("mcp-editor-tool-policy-selector")), useUnmergedTree = true).assertPolicyTextFits()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        assertEquals("Changing the unsaved policy does not save the server", 0, saved)
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun wrappedEditablePolicyPreservesEveryNativeGlyphOfTheUnclippedStaticValue() {
        compose.configureFontScale(2f)
        var foreground = 0
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                foreground = MaterialTheme.colorScheme.primary.toArgb()
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    JarvysGroup(contentPadding = PaddingValues(18.dp)) {
                        Ux21FixtureRow("reference", R.string.connector_operation_calendar_search,
                            R.string.remote_service_policy_ask, readOnly = true)
                        Ux21FixtureRow("editable", R.string.connector_operation_calendar_create,
                            R.string.remote_service_policy_ask)
                    }
                }
            }
        }
        val reference = compose.policyLabel("reference", R.string.remote_service_policy_ask)
        val editable = compose.policyLabel("editable", R.string.remote_service_policy_ask)
        val referenceLayout = reference.assertPolicyTextFits()
        val editableLayout = editable.assertPolicyTextFits()
        assertTrue("Fixture must exercise a wrapped value", referenceLayout.lineCount > 1)
        assertEquals(referenceLayout.size, editableLayout.size)
        val referenceBounds = reference.fetchSemanticsNode().boundsInWindow
        val editableBounds = editable.fetchSemanticsNode().boundsInWindow
        assertEquals(referenceBounds.width, editableBounds.width, 0f)
        assertEquals(referenceBounds.height, editableBounds.height, 0f)
        // Capture before checking so a shape-clipping regression leaves the actual native evidence.
        Ux21NativeCapture(compose, "glyph-mask-es-320dp-light-font2").capture("static-vs-editable")
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                fun mask(bounds: androidx.compose.ui.geometry.Rect): BooleanArray {
                    val width = bounds.width.toInt()
                    val height = bounds.height.toInt()
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height)
                    // Both production labels use the same primary color and typography. Only
                    // the glyph area is sampled; the chevron and all container pixels are excluded.
                    return BooleanArray(pixels.size) { pixels[it] == foreground }
                }
                val expected = mask(referenceBounds)
                val actual = mask(editableBounds)
                assertTrue("Reference mask must contain substantial rendered text", expected.count { it } > 100)
                val missing = expected.indices.count { expected[it] && !actual[it] }
                assertEquals("Ancestor button shape clipped $missing native foreground glyph pixels", 0, missing)
                assertArrayEquals("Static and editable labels must preserve identical native foreground masks", expected, actual)
            } finally { bitmap.recycle() }
        }
    }

    private fun showSingle(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxWidth().padding(18.dp)) { content() } } }
    }

    private fun menuChoice(resource: Int) = compose.onNode(hasText(compose.activity.getString(resource)) and hasAnyAncestor(isPopup()))

    private fun assertExactChoices(resources: List<Int>) {
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(resources.size)
        resources.forEach { menuChoice(it).assertIsDisplayed() }
    }

    private class Ux21EmptyVault : McpCredentialVault {
        override fun get(serverId: String, key: String): String? = null
        override fun save(serverId: String, key: String, value: String) = error("No credentials are used by UX21 UI tests")
        override fun clear(serverId: String) = Unit
    }
}

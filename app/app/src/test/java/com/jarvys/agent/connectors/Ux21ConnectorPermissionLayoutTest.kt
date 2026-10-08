package com.jarvys.agent.connectors

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the Calendar read-only Allow/editable Allow offset, including shared device,
 * Google/MCP-style multi-line left content and long remote-policy labels. Measures production
 * composables on real native host windows. This is not physical-device/TalkBack validation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Ux21ConnectorPermissionLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
    fun en320LightFont1() = verifyLayout(320, false, 1f)

    @Test @Config(qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
    fun en320LightFont2() = verifyLayout(320, false, 2f)

    @Test @Config(qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
    fun en320DarkFont1() = verifyLayout(320, true, 1f)

    @Test @Config(qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
    fun en320DarkFont2() = verifyLayout(320, true, 2f)

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun es320LightFont1() = verifyLayout(320, false, 1f)

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun es320LightFont2() = verifyLayout(320, false, 2f)

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun es320DarkFont1() = verifyLayout(320, true, 1f)

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun es320DarkFont2() = verifyLayout(320, true, 2f)

    @Test @Config(qualifiers = "en-rUS-w393dp-h900dp-port-mdpi")
    fun en393LightFont1() = verifyLayout(393, false, 1f)

    @Test @Config(qualifiers = "en-rUS-w393dp-h900dp-port-mdpi")
    fun en393LightFont2() = verifyLayout(393, false, 2f)

    @Test @Config(qualifiers = "en-rUS-w393dp-h900dp-port-mdpi")
    fun en393DarkFont1() = verifyLayout(393, true, 1f)

    @Test @Config(qualifiers = "en-rUS-w393dp-h900dp-port-mdpi")
    fun en393DarkFont2() = verifyLayout(393, true, 2f)

    @Test @Config(qualifiers = "es-rES-w393dp-h900dp-port-mdpi")
    fun es393LightFont1() = verifyLayout(393, false, 1f)

    @Test @Config(qualifiers = "es-rES-w393dp-h900dp-port-mdpi")
    fun es393LightFont2() = verifyLayout(393, false, 2f)

    @Test @Config(qualifiers = "es-rES-w393dp-h900dp-port-mdpi")
    fun es393DarkFont1() = verifyLayout(393, true, 1f)

    @Test @Config(qualifiers = "es-rES-w393dp-h900dp-port-mdpi")
    fun es393DarkFont2() = verifyLayout(393, true, 2f)

    @Test @Config(qualifiers = "en-rUS-w800dp-h1280dp-port-mdpi")
    fun en800LightFont1() = verifyLayout(800, false, 1f)

    @Test @Config(qualifiers = "en-rUS-w800dp-h1280dp-port-mdpi")
    fun en800LightFont2() = verifyLayout(800, false, 2f)

    @Test @Config(qualifiers = "en-rUS-w800dp-h1280dp-port-mdpi")
    fun en800DarkFont1() = verifyLayout(800, true, 1f)

    @Test @Config(qualifiers = "en-rUS-w800dp-h1280dp-port-mdpi")
    fun en800DarkFont2() = verifyLayout(800, true, 2f)

    @Test @Config(qualifiers = "es-rES-w800dp-h1280dp-port-mdpi")
    fun es800LightFont1() = verifyLayout(800, false, 1f)

    @Test @Config(qualifiers = "es-rES-w800dp-h1280dp-port-mdpi")
    fun es800LightFont2() = verifyLayout(800, false, 2f)

    @Test @Config(qualifiers = "es-rES-w800dp-h1280dp-port-mdpi")
    fun es800DarkFont1() = verifyLayout(800, true, 1f)

    @Test @Config(qualifiers = "es-rES-w800dp-h1280dp-port-mdpi")
    fun es800DarkFont2() = verifyLayout(800, true, 2f)

    private fun verifyLayout(widthDp: Int, dark: Boolean, scale: Float) {
        compose.configureFontScale(scale)
        val language = compose.activity.resources.configuration.locales[0].language
        val case = "${language}_${widthDp}dp_${if (dark) "dark" else "light"}_font${scale.toInt()}"
        val captures = Ux21NativeCapture(compose, case)
        compose.setContent { Ux21PermissionFixture(dark, scale) }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(widthDp.toFloat(), compose.activity.window.decorView.width / density, 1f)
        assertEquals(scale, compose.activity.resources.configuration.fontScale, 0.01f)
        captures.capture("calendar-aligned")

        val labels = linkedMapOf(
            "read" to R.string.connector_policy_allow,
            "write" to R.string.connector_policy_allow,
            "ask" to R.string.connector_policy_ask,
            "deny" to R.string.connector_policy_deny,
            "disabled" to R.string.connector_policy_ask,
            "remote" to R.string.remote_service_policy_allow,
            "remote-ask" to R.string.remote_service_policy_ask,
            "initial" to R.string.connector_policy_allow_automatically,
        )
        var columnRight: Float? = null
        var columnLeft: Float? = null
        var labelRight: Float? = null
        var renderedRight: Float? = null
        labels.forEach { (id, label) ->
            compose.onNodeWithTag("row-$id").performScrollTo().assertIsDisplayed()
            val row = compose.onNodeWithTag("row-$id").fetchSemanticsNode().boundsInRoot
            val policy = compose.onNodeWithTag("policy-$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("title-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = compose.policyLabel(id, label).assertIsDisplayed()
            val textLayout = text.assertPolicyTextFits()
            val labelBounds = text.fetchSemanticsNode().boundsInRoot
            val glyphRight = labelBounds.left + (0 until textLayout.lineCount).maxOf(textLayout::getLineRight)
            if (columnRight == null) {
                columnRight = policy.right
                columnLeft = policy.left
                labelRight = labelBounds.right
                renderedRight = glyphRight
            }
            assertEquals("$case $id: common right edge", columnRight!!, policy.right, 1f)
            assertEquals("$case $id: common fixed column width", columnLeft!!, policy.left, 1f)
            assertEquals("$case $id: same label/chevron slots as static Allow", labelRight!!, labelBounds.right, 1f)
            assertEquals("$case $id: actual rendered text ends at the common edge", renderedRight!!, glyphRight, 1f)
            assertEquals("$case $id: label centered inside control", policy.center.y, labelBounds.center.y, 1f)
            assertEquals("$case $id: fixed to row right edge", row.right, policy.right, 1f)
            assertEquals("$case $id: vertically centered in its row", row.center.y, policy.center.y, 1f)
            assertTrue("$case $id: left text retains its weighted space", title.width > 0f)
            assertTrue("$case $id: left text clears the permission by 8dp", title.right + 7f * density <= policy.left)
            assertTrue("$case $id: policy text fits within its control", labelBounds.top >= policy.top - 1f && labelBounds.bottom <= policy.bottom + 1f)
            assertTrue("$case $id: control remains within viewport", policy.right <= widthDp * density)
            assertTrue("$case $id: minimum 48dp control width", policy.width >= 48f * density - 1f)
            assertTrue("$case $id: minimum 48dp control height", policy.height >= 48f * density - 1f)
            if (id == "ask") {
                val description = compose.onNodeWithTag("description-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("$case: supporting text also clears trailing controls", description.right + 7f * density <= policy.left)
            }
        }
        assertFalse("Informational read-only value is not an interactive permission", compose.onNodeWithTag("policy-read")
            .fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        compose.policyButton("disabled").assertIsNotEnabled()

        compose.onNodeWithTag("row-write").performScrollTo()
        compose.policyButton("write").performClick()
        assertChoices(listOf(R.string.connector_policy_ask, R.string.connector_policy_allow, R.string.connector_policy_deny))
        captures.capture("calendar-menu", includePopup = true)
        compose.dismissPopupWithBack()
        compose.onAllNodes(isPopup()).assertCountEquals(0)

        compose.onNodeWithTag("row-remote").performScrollTo()
        compose.policyButton("remote").performClick()
        assertChoices(listOf(R.string.remote_service_policy_ask, R.string.remote_service_policy_allow, R.string.remote_service_policy_deny))
        captures.capture("remote-menu", includePopup = true)
        compose.dismissPopupOutside()
        compose.onAllNodes(isPopup()).assertCountEquals(0)

        compose.onNodeWithTag("row-scroll-4").performScrollTo().assertIsDisplayed()
        assertEquals("Scroll does not shift the shared trailing column", columnRight!!,
            compose.onNodeWithTag("policy-scroll-4").fetchSemanticsNode().boundsInRoot.right, 1f)
        captures.capture("scrolled")
        compose.policyButton("scroll-4").performClick()
        assertChoices(listOf(R.string.connector_policy_ask, R.string.connector_policy_allow, R.string.connector_policy_deny))
        compose.dismissPopupWithBack()
    }

    private fun assertChoices(resources: List<Int>) {
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(resources.size)
        resources.forEach { resource ->
            val item = compose.onNode(hasText(compose.activity.getString(resource)) and hasAnyAncestor(isPopup()))
                .assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Every popup choice has at least a 48dp hit target", item.boundsInRoot.height >=
                48f * compose.activity.resources.displayMetrics.density - 1f)
        }
    }
}

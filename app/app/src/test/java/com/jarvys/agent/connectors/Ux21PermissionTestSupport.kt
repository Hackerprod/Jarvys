package com.jarvys.agent.connectors

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.JarvysOwnTheme
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

typealias Ux21ComposeRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Host fixture uses the actual shared production row, value, selector, typography and card. */
@Composable
internal fun Ux21PermissionFixture(dark: Boolean, expectedScale: Float) {
    assertEquals(expectedScale, LocalDensity.current.fontScale, 0.01f)
    JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(JarvysUiTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.connector_label_calendar), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground)
            JarvysGroup(contentPadding = PaddingValues(JarvysUiTokens.ScreenPadding)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Ux21FixtureRow("read", R.string.connector_operation_calendar_search, R.string.connector_policy_allow, readOnly = true)
                    Ux21FixtureRow("write", R.string.connector_operation_calendar_create, R.string.connector_policy_allow)
                    Ux21FixtureRow("ask", R.string.connector_operation_contacts_search, R.string.connector_policy_ask,
                        description = R.string.connector_description_contacts)
                    Ux21FixtureRow("deny", R.string.connector_operation_calendar_create, R.string.connector_policy_deny)
                    Ux21FixtureRow("disabled", R.string.connector_operation_contacts_detail, R.string.connector_policy_ask, enabled = false)
                    Ux21FixtureRow("remote", R.string.connector_operation_calendar_create, R.string.remote_service_policy_allow,
                        choices = ux21RemoteChoices())
                    Ux21FixtureRow("remote-ask", R.string.connector_operation_contacts_detail, R.string.remote_service_policy_ask,
                        choices = ux21RemoteChoices())
                    Ux21FixtureRow("initial", R.string.connector_operation_contacts_detail, R.string.connector_policy_allow_automatically)
                }
            }
            // Force a real scroll on phones and exercise the same trailing column below the fold.
            repeat(5) { index ->
                JarvysGroup(contentPadding = PaddingValues(JarvysUiTokens.ScreenPadding)) {
                    Ux21FixtureRow("scroll-$index", R.string.connector_operation_calendar_create,
                        R.string.connector_policy_allow)
                }
            }
        }
    }
}

@Composable
internal fun Ux21FixtureRow(
    id: String,
    operation: Int,
    selected: Int,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    description: Int? = null,
    choices: List<ConnectorPolicyChoice> = listOf(
        ConnectorPolicyChoice(R.string.connector_policy_ask) {},
        ConnectorPolicyChoice(R.string.connector_policy_allow) {},
        ConnectorPolicyChoice(R.string.connector_policy_deny) {},
    ),
) {
    ConnectorPermissionRow(modifier = Modifier.testTag("row-$id"), content = {
        Text(stringResource(operation), Modifier.testTag("title-$id"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        description?.let {
            Text(stringResource(it), Modifier.testTag("description-$id"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }, permission = {
        if (readOnly) ConnectorPolicyValue(selected, Modifier.testTag("policy-$id"))
        else ConnectorPolicySelector(selected, choices, modifier = Modifier.testTag("policy-$id"), enabled = enabled)
    })
}

internal fun Ux21ComposeRule.configureFontScale(scale: Float) {
    RuntimeEnvironment.setFontScale(scale)
    for (resources in listOf(RuntimeEnvironment.getApplication().resources, activity.resources).distinct()) {
        val configuration = Configuration(resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}

internal fun Ux21ComposeRule.policyButton(id: String): SemanticsNodeInteraction =
    onNode(hasClickAction() and (hasTestTag("policy-$id") or hasAnyAncestor(hasTestTag("policy-$id"))))

internal fun Ux21ComposeRule.policyLabel(id: String, resource: Int): SemanticsNodeInteraction =
    onNode(hasText(activity.getString(resource)) and hasAnyAncestor(hasTestTag("policy-$id")), useUnmergedTree = true)

internal fun SemanticsNodeInteraction.assertPolicyTextFits(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals("One native text layout per policy label", 1, layouts.size)
    assertFalse("Policy labels must not be clipped or ellipsized", layouts.single().hasVisualOverflow)
    return layouts.single()
}

internal fun ux21RemoteChoices() = listOf(
    ConnectorPolicyChoice(R.string.remote_service_policy_ask) {},
    ConnectorPolicyChoice(R.string.remote_service_policy_allow) {},
    ConnectorPolicyChoice(R.string.remote_service_policy_deny) {},
)

/** Actual Android window roots, including Compose PopupLayout, rather than a recreated menu. */
internal fun ux21WindowRoots(): List<View> {
    val type = Class.forName("android.view.WindowManagerGlobal")
    val global = ReflectionHelpers.callStaticMethod<Any>(type, "getInstance")
    return ReflectionHelpers.getField<ArrayList<View>>(global, "mViews").toList()
        .filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
}

internal fun Ux21ComposeRule.popupView(): View = ux21WindowRoots().single {
    it !== activity.window.decorView && it.javaClass.name.contains("Popup")
}

internal fun Ux21ComposeRule.dismissPopupWithBack() {
    runOnIdle {
        val popup = popupView()
        assertTrue(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
    }
    waitForIdle()
}

internal fun Ux21ComposeRule.dismissPopupOutside() {
    runOnIdle {
        val event = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try { assertTrue(popupView().dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    waitForIdle()
}

/** Evidence is native Robolectric View.draw, not an emulator, physical device or IME certification. */
internal class Ux21NativeCapture(private val compose: Ux21ComposeRule, private val caseId: String) {
    private val output: File by lazy {
        val explicit = System.getProperty("jarvys.ux21.captureDir") ?: System.getenv("JARVYS_UX21_CAPTURE_DIR")
        val root = if (explicit.isNullOrBlank()) TestCaptureDirectories.named("ux21-permissions") else File(explicit).also {
            require(it.isAbsolute) { "UX21 capture directory must be absolute" }
        }
        File(root, BuildConfig.FLAVOR).canonicalFile.apply { check(isDirectory || mkdirs()) }
    }

    fun capture(state: String, includePopup: Boolean = false) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = compose.activity.window.decorView
            val windows = if (includePopup) listOf("activity" to activity, "popup" to compose.popupView())
                else listOf("activity" to activity)
            val manifest = JSONArray()
            for ((kind, view) in windows) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val pixels = IntArray(view.width * view.height)
                    bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
                    assertTrue("$kind capture contains native rendered pixels", pixels.toSet().size > 16)
                    val file = File(output, "${caseId}_${state}_$kind.png")
                    check(file.canonicalFile.parentFile == output)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                    manifest.put(JSONObject().put("file", file.name).put("sha256", hash)
                        .put("window", kind).put("nativeViewClass", view.javaClass.name)
                        .put("widthPixels", view.width).put("heightPixels", view.height)
                        .put("windowX", location[0]).put("windowY", location[1])
                        .put("fontScale", activity.resources.configuration.fontScale)
                        .put("density", activity.resources.displayMetrics.density)
                        .put("locale", activity.resources.configuration.locales[0].toLanguageTag()))
                } finally { bitmap.recycle() }
            }
            File(output, "${caseId}_$state.json").writeText(JSONObject()
                .put("evidence", "Native Robolectric Android Activity/Popup View.draw; not physical-device validation")
                .put("case", caseId).put("state", state).put("windows", manifest).toString(2) + "\n")
            println("UX21_CAPTURE_DIR=${output.absolutePath} case=$caseId state=$state windows=${windows.size}")
        }
    }
}

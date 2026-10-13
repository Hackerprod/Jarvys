package com.jarvys.agent.ui.mascot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real original-header caller and gestures. Reduced motion avoids JNI; no Rive playback claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class JarvysOriginalHeaderInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Before fun resetConsent() { JarvysMascotProcessConsent.revoke() }
    @After fun clearConsent() { JarvysMascotProcessConsent.revoke() }

    private fun mount(size: Dp = 84.dp, onAccountAction: () -> Unit = {}) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.size(96.dp)) {
                    JarvysMascotAccountMenu(
                        mode = null,
                        visibleSessionId = "original-header-test",
                        liveConversationVisible = true,
                        surfaceVisible = true,
                        mascotSize = size,
                        originalHeaderAnimation = true,
                        levitate = true,
                    ) { closeMenu ->
                        DropdownMenuItem(text = { Text("Existing account action") },
                            onClick = { closeMenu(); onAccountAction() })
                    }
                }
            }
        } }
        awaitReactionDrawIdle(compose)
        compose.onNodeWithTag("jarvys-header-rive").assertDoesNotExist()
    }

    @Test fun physicalTapDoesNotOpenMenuButLongPressDoesAndAccountActionStillWorks() {
        var accountCalls = 0
        mount(onAccountAction = { accountCalls++ })
        val target = compose.onNodeWithTag("jarvys-account-menu")
        target.assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
        val initialBounds = target.fetchSemanticsNode().boundsInRoot
        repeat(3) {
            target.performTouchInput { click() }
            awaitReactionDrawIdle(compose)
            compose.onNodeWithText("Existing account action").assertDoesNotExist()
            assertEquals("tap must not move or resize its hitbox", initialBounds,
                target.fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(0, accountCalls)
        target.performTouchInput { longClick() }
        awaitReactionDrawIdle(compose)
        compose.onNodeWithText("Existing account action").assertIsDisplayed().performClick()
        awaitReactionDrawIdle(compose)
        assertEquals(1, accountCalls)
        compose.onNodeWithText("Existing account action").assertDoesNotExist()
        assertEquals("menu round trip keeps the centered84dp hitbox", initialBounds,
            target.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("jarvys-header-rive").assertDoesNotExist()
    }

    @Test fun accessibilityExposesDistinctTauntClickAndAccountLongClickActions() {
        var accountCalls = 0
        mount(size = 30.dp, onAccountAction = { accountCalls++ })
        val target = compose.onNodeWithTag("jarvys-account-menu")
        target.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val initialBounds = target.fetchSemanticsNode().boundsInRoot
        val config = target.fetchSemanticsNode().config
        val tauntLabel = compose.activity.getString(R.string.jarvys_mascot_play_taunt)
        val accountLabel = compose.activity.getString(R.string.jarvys_account_menu)
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertTrue(config[SemanticsProperties.ContentDescription].contains(tauntLabel))
        assertEquals(tauntLabel, config[SemanticsActions.OnClick].label)
        assertEquals(accountLabel, config[SemanticsActions.OnLongClick].label)
        target.performClick()
        awaitReactionDrawIdle(compose)
        compose.onNodeWithText("Existing account action").assertDoesNotExist()
        target.performSemanticsAction(SemanticsActions.OnLongClick) { action ->
            assertTrue("account accessibility action must be handled", action())
        }
        awaitReactionDrawIdle(compose)
        compose.onNodeWithText("Existing account action").assertIsDisplayed().performClick()
        awaitReactionDrawIdle(compose)
        assertEquals(1, accountCalls)
        assertEquals("accessible actions retain the48dp minimum hitbox", initialBounds,
            target.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("jarvys-header-rive").assertDoesNotExist()
    }

    @Test fun originalHeaderSourceDisablesOnlyItsPressedIndicationAndRetainsTapTokenRouting() {
        // Explicit structural regression guard, not a claim that reduced-motion tests play Taunt.
        val source = File(sourceRoot(), "com/jarvys/agent/ui/mascot/JarvysMascotAccountMenu.kt").readText()
        val signature = "private fun JarvysMascotHeaderButton("
        assertTrue("inspect the actual production button", source.contains(signature))
        val button = source.substringAfter(signature)
        val original = button.substringAfter("if (originalHeaderAnimation) {")
            .substringBefore("} else IconButton")
        assertTrue(Regex("val\\s+interactionSource\\s*=\\s*remember\\s*\\{\\s*MutableInteractionSource\\(\\)\\s*}")
            .containsMatchIn(original))
        val clickArguments = original.substringAfter("Box(modifier.combinedClickable(")
            .substringBefore("), contentAlignment")
        assertTrue("explicit null indication suppresses the default pressed flash",
            Regex("\\bindication\\s*=\\s*null\\s*,").containsMatchIn(clickArguments))
        assertTrue(Regex("\\binteractionSource\\s*=\\s*interactionSource\\s*,").containsMatchIn(clickArguments))
        assertTrue(Regex("\\bonClick\\s*=\\s*onTap\\s*,").containsMatchIn(clickArguments))
        assertTrue(Regex("\\bonLongClick\\s*=\\s*onMenu\\s*,").containsMatchIn(clickArguments))
        assertTrue(source.contains("onTap = { tauntEventId = UUID.randomUUID().toString() }"))
        assertTrue("legacy button remains separate", button.contains("else IconButton(onClick = onMenu"))
        assertFalse("do not disable indications globally", source.contains("LocalIndication provides"))
    }

    private fun sourceRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not find app/src/main/java from ${working.path}")
    }
}

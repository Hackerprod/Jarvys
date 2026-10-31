package com.jarvys.agent.crew

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.LocalReducedMotion
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrewBotAvatarComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachBotModeHasAStateDescriptionAndReducedMotionDrawsStaticAvatar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = mutableStateOf("RUNNING" to "")
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    CrewBotAvatar("Mara", CrewRoleTemplates.EXPLORER, "blue", state.value.first,
                        waitingReason = state.value.second,
                        testTag = "crew-bot-avatar")
                }
            }
        }
        compose.waitForIdle()
        val avatar = compose.onNodeWithTag("crew-bot-avatar")
        avatar.assertIsDisplayed()
        assertEquals(context.getString(R.string.crew_status_working),
            avatar.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        compose.runOnIdle { state.value = "WAITING" to "límite del proveedor" }
        compose.waitForIdle()
        assertEquals(context.getString(R.string.crew_waiting_provider),
            compose.onNodeWithTag("crew-bot-avatar").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}

package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysUiTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorDetailSectionInsetTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun edgeToEdgeSectionKeepsTheSameInsetOnTopBottomAndLeft() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag("host")) {
                    ConnectorDetailSection("Section", contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Box(Modifier.padding(vertical = ConnectorPolicyRowVerticalInset)
                            .padding(horizontal = JarvysUiTokens.ScreenPadding)
                            .fillMaxWidth().height(40.dp).testTag("row"))
                    }
                }
            }
        }
        val host = compose.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("Section").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val inset = JarvysUiTokens.ScreenPadding.value
        assertEquals(inset, (label.left - host.left).value, 0.5f)
        assertEquals(inset, (label.top - host.top).value, 3f)
        assertEquals(inset, (host.bottom - row.bottom).value, 0.5f + ConnectorPolicyRowVerticalInset.value)
    }
}

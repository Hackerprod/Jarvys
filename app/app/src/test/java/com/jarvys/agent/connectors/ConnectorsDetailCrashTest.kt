package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorsDetailCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun connectorDetailScaffoldRendersInItsRouteViewportWithoutAnOuterVerticalScroll() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConnectorDetailScaffold(
                        title = "Contacts",
                        subtitle = "On device",
                        icon = {},
                        connected = true,
                        summary = "Test detail",
                        primaryActionLabel = "Connect",
                        onPrimaryAction = {},
                        disconnectLabel = "Disconnect",
                        disconnectExplanation = "Confirm",
                        onDisconnect = {},
                        showIdentityHeader = false,
                        content = { Text("Details") },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Details").assertIsDisplayed()
    }
}

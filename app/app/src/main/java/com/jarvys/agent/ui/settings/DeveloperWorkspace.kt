package com.jarvys.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jarvys.agent.connectors.FlavorAutonomyUi

@Composable
internal fun DeveloperWorkspace() {
    Column(modifier = Modifier.fillMaxWidth().testTag("developer-settings")) {
        FlavorAutonomyUi.DeveloperControls()
    }
}

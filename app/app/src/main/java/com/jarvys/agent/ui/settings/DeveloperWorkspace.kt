package com.jarvys.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvys.agent.canvas.ChatCanvasBenchmarkScreen
import com.jarvys.agent.canvas.ChatCanvasPreferences
import com.jarvys.agent.connectors.FlavorAutonomyUi

@Composable
internal fun DeveloperWorkspace() {
    val context = LocalContext.current
    var canvasEnabled by remember { mutableStateOf(ChatCanvasPreferences.enabled(context)) }
    var showCanvasBenchmark by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().testTag("developer-settings")) {
        FlavorAutonomyUi.DeveloperControls()
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lienzo de chat (experimental)", style = MaterialTheme.typography.titleMedium)
                        Text("Usa WebView solo para la lista de mensajes. Desactivado por defecto.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = canvasEnabled,
                        onCheckedChange = { enabled ->
                            canvasEnabled = enabled
                            ChatCanvasPreferences.setEnabled(context, enabled)
                        },
                        modifier = Modifier.testTag("chat-canvas-experimental-toggle"),
                    )
                }
                OutlinedButton(onClick = { showCanvasBenchmark = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ejecutar benchmark")
                }
            }
        }
    }
    if (showCanvasBenchmark) Dialog(
        onDismissRequest = { showCanvasBenchmark = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatCanvasBenchmarkScreen(onClose = { showCanvasBenchmark = false })
        }
    }
}

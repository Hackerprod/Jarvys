package com.jarvys.agent.modules

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.ui.JarvysOwnTheme

/** Emulator-only route for exercising the production artifact viewer with a persisted artifact. */
class ArtifactViewerDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val artifactId = intent.getStringExtra(EXTRA_ARTIFACT_ID)
        if (conversationId.isNullOrBlank() || artifactId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                ModuleArtifactViewerScreen(conversationId, artifactId)
            }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_ARTIFACT_ID = "artifactId"
    }
}

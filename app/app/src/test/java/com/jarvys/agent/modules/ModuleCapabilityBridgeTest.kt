package com.jarvys.agent.modules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleCapabilityBridgeTest {
    @Test fun grantsOnlyDeclaredCapabilitiesAndOnlyDeclaredArtifactTypes() {
        val access = RecordingAccess("conversation-a")
        val bridge = ModuleCapabilityBridge(
            conversationId = "conversation-a",
            declaredCapabilities = setOf(
                ModuleCapabilities.WORKSPACE_READ,
                ModuleCapabilities.WORKSPACE_WRITE,
                ModuleCapabilities.ARTIFACT_DELIVER,
                ModuleCapabilities.PROGRESS,
                ModuleCapabilities.LOG,
            ),
            declaredArtifactTypes = listOf("image/*"),
            access = access,
            complete = {},
            fail = {},
        )

        assertEquals("read:charts/source.json", bridge.workspaceRead("charts/source.json"))
        bridge.workspaceWrite("charts/result.json", "{}")
        assertEquals("{\"id\":\"delivered\"}", bridge.artifactDeliver(
            "charts/result.png", "chart.png", "image/png", "Chart",
        ))
        bridge.progress("Rendering")
        bridge.log("module diagnostic")

        assertEquals(listOf("charts/result.json"), access.writtenPaths)
        assertEquals("image/png", access.deliveredType)
        assertEquals(listOf("Rendering"), access.progressMessages)
    }

    @Test fun deniesUndeclaredNetworkOutOfScopePathsAndUndeclaredArtifactTypes() {
        val bridge = ModuleCapabilityBridge(
            conversationId = "conversation-a",
            declaredCapabilities = setOf(ModuleCapabilities.WORKSPACE_READ, ModuleCapabilities.ARTIFACT_DELIVER),
            declaredArtifactTypes = listOf("image/png"),
            access = RecordingAccess("conversation-a"),
            complete = {},
            fail = {},
        )

        assertThrows(SecurityException::class.java) { bridge.networkFetch("https://example.invalid") }
        assertThrows(IllegalArgumentException::class.java) { bridge.workspaceRead("../conversation-b/secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { bridge.workspaceRead("memory/notes.md") }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.artifactDeliver("charts/result.png", "chart.png", "image/jpeg", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.artifactDeliver("charts/result.png", "chart.png", "image/../../private", null)
        }
    }

    @Test fun refusesAHostAccessObjectBoundToAnotherConversation() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCapabilityBridge(
                conversationId = "conversation-a",
                declaredCapabilities = setOf(ModuleCapabilities.WORKSPACE_READ),
                declaredArtifactTypes = emptyList(),
                access = RecordingAccess("conversation-b"),
                complete = {},
                fail = {},
            )
        }
    }

    @Test fun rejectsUnknownDeclaredHostCapabilities() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleCapabilityBridge(
                conversationId = "conversation-a",
                declaredCapabilities = setOf("credentials.read"),
                declaredArtifactTypes = emptyList(),
                access = RecordingAccess("conversation-a"),
                complete = {},
                fail = {},
            )
        }
    }

    private class RecordingAccess(override val conversationId: String) : ModuleCapabilityAccess {
        val writtenPaths = mutableListOf<String>()
        val progressMessages = mutableListOf<String>()
        var deliveredType: String? = null

        override fun readWorkspace(path: String) = "read:$path"
        override fun writeWorkspace(path: String, content: String) { writtenPaths += path }
        override fun deliverArtifact(path: String, name: String, artifactType: String, title: String?): JSONObject {
            deliveredType = artifactType
            return JSONObject().put("id", "delivered")
        }
        override fun reportProgress(message: String) { progressMessages += message }
        override fun log(message: String) = Unit
        override fun fetchNetwork(url: String): String = error("network must not be called")
    }
}

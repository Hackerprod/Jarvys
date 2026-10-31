package com.jarvys.agent.modules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test fun deliversGeneratedBytesAndOnlyTheDeclaredThumbnailType() {
        val access = RecordingAccess("conversation-a")
        val bridge = ModuleCapabilityBridge(
            conversationId = "conversation-a",
            declaredCapabilities = setOf(ModuleCapabilities.ARTIFACT_DELIVER),
            declaredArtifactTypes = listOf("application/vnd.example.chart+json"),
            access = access,
            complete = {},
            fail = {},
            thumbnailArtifactTypes = listOf("application/vnd.example.chart+json"),
        )

        assertEquals("{\"id\":\"delivered-bytes\"}", bridge.artifactDeliverBytes(
            android.util.Base64.encodeToString(byteArrayOf(1, 2), android.util.Base64.NO_WRAP),
            "chart.json", "application/vnd.example.chart+json", "Chart",
            android.util.Base64.encodeToString(byteArrayOf(3, 4), android.util.Base64.NO_WRAP), "image/png",
            "123e4567-e89b-42d3-a456-426614174000",
        ))
        assertArrayEquals(byteArrayOf(1, 2), access.deliveredBytes)
        assertArrayEquals(byteArrayOf(3, 4), access.thumbnailBytes)
        assertEquals("123e4567-e89b-42d3-a456-426614174000", access.deliveryId)
        assertThrows(IllegalArgumentException::class.java) {
            bridge.artifactDeliverBytes("AQI=", "chart.json", "application/vnd.example.chart+json", null, "AwQ=", "application/json", "123e4567-e89b-42d3-a456-426614174000")
        }
    }

    @Test fun generatedDeliveryIdsAreDistinctAndThumbnailTransportIsBoundedByCardPixels() {
        val bridge = ModuleCapabilityBridge(
            conversationId = "conversation-a",
            declaredCapabilities = setOf(ModuleCapabilities.ARTIFACT_DELIVER),
            declaredArtifactTypes = listOf("application/vnd.example.chart+json"),
            access = RecordingAccess("conversation-a"),
            complete = {},
            fail = {},
            thumbnailArtifactTypes = listOf("application/vnd.example.chart+json"),
        )
        val first = bridge.newArtifactDeliveryId()
        val second = bridge.newArtifactDeliveryId()
        assertNotEquals(first, second)

        val tooLarge = "A".repeat(ModuleThumbnailBudget.MAX_BASE64_CHARS.toInt() + 1)
        assertThrows(IllegalArgumentException::class.java) {
            bridge.artifactDeliverBytes(
                "AQI=", "chart.json", "application/vnd.example.chart+json", "Chart",
                tooLarge, "image/png", first,
            )
        }
        assertEquals(ModuleThumbnailBudget.MAX_CARD_BITMAP_WIDTH_PX * ModuleThumbnailBudget.MAX_CARD_BITMAP_HEIGHT_PX * 4,
            ModuleThumbnailBudget.MAX_ENCODED_BYTES)
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
        assertThrows(IllegalArgumentException::class.java) {
            bridge.artifactDeliverBytes("AQI=", "image.png", "image/png", null, "AwQ=", "image/png",
                "123e4567-e89b-42d3-a456-426614174000")
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
        var deliveredBytes = byteArrayOf()
        var thumbnailBytes = byteArrayOf()
        var deliveryId: String? = null

        override fun readWorkspace(path: String) = "read:$path"
        override fun writeWorkspace(path: String, content: String) { writtenPaths += path }
        override fun deliverArtifact(path: String, name: String, artifactType: String, title: String?): JSONObject {
            deliveredType = artifactType
            return JSONObject().put("id", "delivered")
        }
        override fun deliverArtifact(bytes: ByteArray, name: String, artifactType: String, title: String?,
            thumbnailBytes: ByteArray, thumbnailMimeType: String, deliveryId: String): JSONObject {
            deliveredType = artifactType
            deliveredBytes = bytes
            this.thumbnailBytes = thumbnailBytes
            this.deliveryId = deliveryId
            return JSONObject().put("id", "delivered-bytes")
        }
        override fun reportProgress(message: String) { progressMessages += message }
        override fun log(message: String) = Unit
        override fun fetchNetwork(url: String): String = error("network must not be called")
    }
}

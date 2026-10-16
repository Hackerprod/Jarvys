package com.jarvys.agent.modules

import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.UUID

/** Native capabilities available to bundled module handlers. Web code cannot add capabilities. */
object ModuleCapabilities {
    const val WORKSPACE_READ = "workspace.read"
    const val WORKSPACE_WRITE = "workspace.write"
    const val ARTIFACT_DELIVER = "artifact.deliver"
    const val PROGRESS = "progress"
    const val LOG = "log"
    const val NETWORK_FETCH = "network.fetch"

    val supported = setOf(WORKSPACE_READ, WORKSPACE_WRITE, ARTIFACT_DELIVER, PROGRESS, LOG, NETWORK_FETCH)
}

/** Scope-bound native operations. Implementations must already be bound to one conversation. */
interface ModuleCapabilityAccess {
    val conversationId: String
    fun readWorkspace(path: String): String
    fun writeWorkspace(path: String, content: String)
    fun deliverArtifact(path: String, name: String, artifactType: String, title: String?): JSONObject
    fun deliverArtifact(
        bytes: ByteArray,
        name: String,
        artifactType: String,
        title: String?,
        thumbnailBytes: ByteArray,
        thumbnailMimeType: String,
        deliveryId: String,
    ): JSONObject
    fun reportProgress(message: String)
    fun log(message: String)
    fun fetchNetwork(url: String): String
}

/** The only JavaScript-visible surface installed in a module WebView. */
class ModuleCapabilityBridge(
    conversationId: String,
    declaredCapabilities: Set<String>,
    declaredArtifactTypes: List<String>,
    private val access: ModuleCapabilityAccess,
    private val complete: (String) -> Unit,
    private val fail: (String) -> Unit,
    thumbnailArtifactTypes: List<String> = emptyList(),
) {
    private val capabilities = declaredCapabilities.toSet()
    private val artifactTypes = declaredArtifactTypes.map(::canonicalType)
    private val thumbnailTypes = thumbnailArtifactTypes.map(::canonicalType)

    init {
        require(conversationId.isNotBlank() && access.conversationId == conversationId) {
            "Module host access must belong to the executing conversation"
        }
        require(capabilities.all { it in ModuleCapabilities.supported }) {
            "Module declares an unsupported host capability"
        }
        require(thumbnailTypes.all { it in artifactTypes }) {
            "Generated thumbnail declarations must refer to declared artifact types"
        }
    }

    @JavascriptInterface
    fun workspaceRead(path: String): String {
        requireCapability(ModuleCapabilities.WORKSPACE_READ)
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Workspace path is outside the module scope." }
        return access.readWorkspace(path)
    }

    @JavascriptInterface
    fun workspaceWrite(path: String, content: String) {
        requireCapability(ModuleCapabilities.WORKSPACE_WRITE)
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Workspace path is outside the module scope." }
        access.writeWorkspace(path, content)
    }

    @JavascriptInterface
    fun artifactDeliver(path: String, name: String, artifactType: String, title: String?): String {
        requireCapability(ModuleCapabilities.ARTIFACT_DELIVER)
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Artifact path is outside the module workspace." }
        val concreteType = canonicalType(artifactType)
        require(CONCRETE_MIME_TYPE.matches(concreteType) && artifactTypes.any { it.matchesArtifactType(concreteType) }) {
            "Artifact type is not declared by this module"
        }
        return access.deliverArtifact(path, name, concreteType, title).toString()
    }

    /** Delivers bytes produced in the module WebView, with the declared raster thumbnail. */
    @JavascriptInterface
    fun artifactDeliverBytes(
        base64: String,
        name: String,
        artifactType: String,
        title: String?,
        thumbnailBase64: String?,
        thumbnailMimeType: String?,
        deliveryId: String,
    ): String {
        requireCapability(ModuleCapabilities.ARTIFACT_DELIVER)
        val concreteType = canonicalType(artifactType)
        require(CONCRETE_MIME_TYPE.matches(concreteType) && artifactTypes.any { it.matchesArtifactType(concreteType) }) {
            "Artifact type is not declared by this module"
        }
        val declaredThumbnail = thumbnailTypes.firstOrNull { declaredType ->
            declaredType.matchesArtifactType(concreteType)
        }
        require(declaredThumbnail != null) { "This artifact declaration does not permit a generated thumbnail" }
        require(DELIVERY_ID.matches(deliveryId)) { "Artifact delivery identity is invalid" }
        val concreteThumbnailType = canonicalType(thumbnailMimeType.orEmpty())
        require(CONCRETE_MIME_TYPE.matches(concreteThumbnailType) &&
            concreteThumbnailType.startsWith("image/") && thumbnailBase64 != null) {
            "Thumbnail must be a declared image artifact"
        }
        return access.deliverArtifact(
            decodeBase64(base64), name, concreteType, title,
            decodeThumbnailBase64(thumbnailBase64), concreteThumbnailType, deliveryId,
        ).toString()
    }

    /** Generates one idempotency key for a single module artifact delivery. */
    @JavascriptInterface
    fun newArtifactDeliveryId(): String = UUID.randomUUID().toString()

    @JavascriptInterface
    fun progress(message: String) {
        requireCapability(ModuleCapabilities.PROGRESS)
        access.reportProgress(message)
    }

    @JavascriptInterface
    fun log(message: String) {
        requireCapability(ModuleCapabilities.LOG)
        access.log(message)
    }

    /** Network access is always mediated by the host; WebView network loading stays disabled. */
    @JavascriptInterface
    fun networkFetch(url: String): String {
        requireCapability(ModuleCapabilities.NETWORK_FETCH)
        return access.fetchNetwork(url)
    }

    @JavascriptInterface
    fun complete(resultJson: String) = complete.invoke(resultJson)

    @JavascriptInterface
    fun fail(message: String) = fail.invoke(message)

    private fun requireCapability(capability: String) {
        if (capability !in capabilities) throw SecurityException("Module capability is not declared: $capability")
    }

    private fun canonicalType(type: String): String = type.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)

    private fun decodeBase64(value: String): ByteArray {
        val encodedLimit = ((com.jarvys.agent.ConnectorArtifactAccess.MAX_BYTES + 2L) / 3L) * 4L
        require(value.length.toLong() <= encodedLimit) { "Encoded artifact exceeds the existing connector delivery limit" }
        return try {
            android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        } catch (invalid: IllegalArgumentException) {
            throw IllegalArgumentException("Artifact bytes are not valid base64", invalid)
        }
    }

    private fun decodeThumbnailBase64(value: String?): ByteArray {
        require(value != null) { "Thumbnail bytes are missing" }
        require(value.length.toLong() <= ModuleThumbnailBudget.MAX_BASE64_CHARS) {
            "Artifact thumbnail exceeds the displayed-card transport budget"
        }
        val bytes = decodeBase64(value)
        require(bytes.size <= ModuleThumbnailBudget.MAX_ENCODED_BYTES) {
            "Artifact thumbnail exceeds the displayed-card transport budget"
        }
        return bytes
    }

    private fun String.matchesArtifactType(value: String): Boolean {
        val actual = canonicalType(value)
        if (this == "*/*") return '/' in actual
        if (endsWith("/*")) return actual.startsWith(substringBefore('/') + "/")
        return this == actual
    }

    private companion object {
        val CONCRETE_MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
        val DELIVERY_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}

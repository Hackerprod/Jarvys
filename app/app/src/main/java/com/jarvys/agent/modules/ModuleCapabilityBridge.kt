package com.jarvys.agent.modules

import android.webkit.JavascriptInterface
import org.json.JSONObject

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
) {
    private val capabilities = declaredCapabilities.toSet()
    private val artifactTypes = declaredArtifactTypes.map(::canonicalType)

    init {
        require(conversationId.isNotBlank() && access.conversationId == conversationId) {
            "Module host access must belong to the executing conversation"
        }
        require(capabilities.all { it in ModuleCapabilities.supported }) {
            "Module declares an unsupported host capability"
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

    private fun String.matchesArtifactType(value: String): Boolean {
        val actual = canonicalType(value)
        if (this == "*/*") return '/' in actual
        if (endsWith("/*")) return actual.startsWith(substringBefore('/') + "/")
        return this == actual
    }

    private companion object {
        val CONCRETE_MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
    }
}

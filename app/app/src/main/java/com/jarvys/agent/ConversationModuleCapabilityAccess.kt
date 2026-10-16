package com.jarvys.agent

import android.content.Context
import android.os.Looper
import com.jarvys.agent.modules.ModuleCapabilityAccess
import com.jarvys.agent.modules.ModuleExecutionRequest
import com.jarvys.agent.modules.ModuleWorkspacePath
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/** Host implementation for one module invocation, pinned to the executing conversation. */
internal class ConversationModuleCapabilityAccess(
    context: Context,
    request: ModuleExecutionRequest,
    private val token: CancellationToken,
    private val progress: CoreTool.ProgressListener?,
) : ModuleCapabilityAccess {
    private val appContext = context.applicationContext
    override val conversationId = requireNotNull(request.conversationId)
    private val module = request.module
    private val workspace = WorkspaceStore(
        File(appContext.filesDir, "jarvys/workspaces"),
        WorkspaceStore.projectIdForSession(conversationId),
        null,
        null,
        null,
        conversationId,
        false,
    ).forDelegatedAgent().withoutAttachments()

    override fun readWorkspace(path: String): String {
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Workspace path is outside the module scope." }
        token.throwIfCancelled()
        return workspace.read(path) ?: ""
    }

    override fun writeWorkspace(path: String, content: String) {
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Workspace path is outside the module scope." }
        token.throwIfCancelled()
        workspace.write(path, content)
    }

    override fun deliverArtifact(path: String, name: String, artifactType: String, title: String?): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Artifact persistence cannot run on the main thread." }
        require(ModuleWorkspacePath.isSafeWorkspacePath(path)) { "Artifact path is outside the module workspace." }
        token.throwIfCancelled()
        return try {
            val store = DeliveredArtifactStore(appContext)
            val artifact = store.snapshot(conversationId, workspace, path, name, token)
            token.throwIfCancelled()
            LocalRunStore(appContext).appendDeliveredFile(
                conversationId,
                artifact,
                artifactType,
                module.id,
                module.manifestJson,
                title,
            )
            AgentRunUiState.moduleArtifactDelivered(
                conversationId,
                artifact,
                artifactType,
                module.id,
                module.manifestJson,
                title,
            )
            JSONObject().put("id", artifact.id).put("artifactType", artifactType)
                .put("moduleId", module.id).put("name", artifact.name)
        } catch (failure: Exception) {
            if (failure !is CancellationException) AgentErrorReporter.reportBackgroundFailure(appContext, failure)
            throw failure
        }
    }

    override fun reportProgress(message: String) {
        try { progress?.onProgress(message) } catch (_: RuntimeException) { }
    }

    override fun log(message: String) {
        // Module logs stay local to the host boundary and are never sent as telemetry.
    }

    override fun fetchNetwork(url: String): String =
        throw SecurityException("No mediated network provider is installed for this module.")
}

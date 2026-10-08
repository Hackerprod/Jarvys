package com.jarvys.agent.ui.chat

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.staticCompositionLocalOf
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.ChatFileProvider
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.DownloadIntents
import com.jarvys.agent.DownloadStore
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.LocalRunStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A click owns its source conversation, even if the user navigates while the copy runs. */
internal data class ChatFileRequest(
    val sessionId: String,
    val kind: String,
    val artifactId: String,
    val displayName: String,
    val mimeType: String,
    val attachment: ChatAttachment? = null,
) {
    val key: String get() = "$sessionId/$kind/$artifactId"

    fun resolve(context: Context): File = when (kind) {
        "delivered" -> DeliveredArtifactStore(context).resolve(sessionId, requireNotNull(attachment))
        "attachment" -> AttachmentStore(context).resolve(sessionId, requireNotNull(attachment))
        "generated" -> GeneratedImageStore(context).resolve(sessionId, artifactId)
        else -> throw IllegalArgumentException("Unknown chat file kind")
    }

    /** Rechecked at action time and stream-open time; stale cards cannot export a deleted chat. */
    fun requireOwnership(context: Context) {
        val store = LocalRunStore(context)
        val owned = if (kind == "generated") store.ownsGeneratedFile(sessionId, artifactId)
            else attachment != null && attachment == store.findChatFile(sessionId, kind, artifactId)
        check(owned) { "This file is no longer attached to the conversation" }
    }

    companion object {
        fun attachment(sessionId: String, attachment: ChatAttachment, delivered: Boolean = false) =
            ChatFileRequest(sessionId, if (delivered) "delivered" else "attachment", attachment.id,
                attachment.name, attachment.mimeType, attachment)

        fun generated(sessionId: String, event: AgentRunUiEvent): ChatFileRequest? =
            event.generatedImagePath?.let { path -> ChatFileRequest(sessionId, "generated", path,
                path, event.generatedImageMimeType ?: "image/png") }
    }
}

internal data class ChatFileTransfer(
    val request: ChatFileRequest,
    val busy: Boolean = false,
    val waitingForPermission: Boolean = false,
    val result: DownloadStore.Result? = null,
    val failure: Failure? = null,
) {
    enum class Failure { DOWNLOAD, PERMISSION, UNAVAILABLE, OPEN }
    val saved: Boolean get() = result?.status == DownloadStore.Status.SAVED || result?.status == DownloadStore.Status.ALREADY_SAVED
}

/** Activity-owned VM retains actual copies, permission targets and completion across rotation. */
internal class ChatFileTransfers(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableTransfers = MutableStateFlow<Map<String, ChatFileTransfer>>(emptyMap())
    val transfers: StateFlow<Map<String, ChatFileTransfer>> = mutableTransfers
    private val tokens = mutableMapOf<String, CancellationToken>()
    private val permissionQueue = linkedMapOf<String, ChatFileRequest>()
    private val mutablePermissionRequest = MutableStateFlow<String?>(null)
    val permissionRequest: StateFlow<String?> = mutablePermissionRequest
    private var permissionLaunched = false
    private val mutableNotice = MutableStateFlow<ChatFileTransfer?>(null)
    val notice: StateFlow<ChatFileTransfer?> = mutableNotice
    private val mutableLaunch = MutableStateFlow<Intent?>(null)
    val launch: StateFlow<Intent?> = mutableLaunch

    fun download(request: ChatFileRequest) {
        if (mutableTransfers.value[request.key]?.busy == true || permissionQueue.containsKey(request.key)) return
        val token = CancellationToken.cancellable()
        tokens[request.key] = token
        update(ChatFileTransfer(request, busy = true))
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    request.requireOwnership(app)
                    val file = request.resolve(app)
                    val bytes = request.attachment?.sizeBytes ?: file.length()
                    val source = DownloadStore.Source(request.sessionId, "${request.kind}:${request.artifactId}",
                        request.displayName, request.mimeType, bytes) {
                        // Revalidate both ownership and immutable bytes immediately before opening.
                        request.requireOwnership(app)
                        if (request.kind == "delivered") DeliveredArtifactStore(app).open(request.sessionId, request.attachment)
                        else request.resolve(app).inputStream()
                    }
                    DownloadStore(app).export(source, token)
                }
            }
            tokens.remove(request.key)
            result.fold(onSuccess = { exported ->
                when (exported.status) {
                    DownloadStore.Status.PERMISSION_REQUIRED -> {
                        permissionQueue[request.key] = request
                        update(ChatFileTransfer(request, waitingForPermission = true))
                        if (mutablePermissionRequest.value == null) mutablePermissionRequest.value = request.key
                    }
                    DownloadStore.Status.SAVED, DownloadStore.Status.ALREADY_SAVED -> {
                        val completed = ChatFileTransfer(request, result = exported)
                        update(completed)
                        mutableNotice.value = completed
                    }
                    DownloadStore.Status.CANCELLED -> update(ChatFileTransfer(request))
                    else -> fail(request, ChatFileTransfer.Failure.DOWNLOAD)
                }
            }, onFailure = { fail(request, ChatFileTransfer.Failure.UNAVAILABLE) })
        }
    }

    /** Consumed before launching so recomposition/rotation never creates a second permission prompt. */
    fun claimPermissionRequest(): Boolean {
        if (permissionLaunched || mutablePermissionRequest.value == null) return false
        permissionLaunched = true
        return true
    }

    fun permissionResult(granted: Boolean) {
        val requests = permissionQueue.values.toList()
        permissionQueue.clear()
        permissionLaunched = false
        mutablePermissionRequest.value = null
        requests.forEach { request ->
            if (granted) download(request) else fail(request, ChatFileTransfer.Failure.PERMISSION)
        }
    }

    fun cancel(request: ChatFileRequest) {
        tokens[request.key]?.cancel()
        if (permissionQueue.remove(request.key) != null) {
            update(ChatFileTransfer(request))
            if (!permissionLaunched) mutablePermissionRequest.value = permissionQueue.keys.firstOrNull()
        }
    }

    fun share(request: ChatFileRequest) = sourceIntent(request, share = true)
    fun open(request: ChatFileRequest) {
        val result = mutableTransfers.value[request.key]?.takeIf { it.saved }?.result
        if (result != null) mutableLaunch.value = DownloadIntents.open(result)
        else sourceIntent(request, share = false)
    }

    private fun sourceIntent(request: ChatFileRequest, share: Boolean) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    request.requireOwnership(app)
                    request.resolve(app)
                    val uri = ChatFileProvider.uri(app, request.sessionId, request.kind, request.artifactId)
                    Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                        if (share) { type = request.mimeType; putExtra(Intent.EXTRA_STREAM, uri) }
                        else setDataAndType(uri, request.mimeType)
                        clipData = ClipData.newRawUri(request.displayName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }.onSuccess { mutableLaunch.value = if (share) Intent.createChooser(it, null) else it }
                .onFailure { fail(request, ChatFileTransfer.Failure.UNAVAILABLE) }
        }
    }

    fun consumeLaunch() { mutableLaunch.value = null }
    fun dismissNotice() { mutableNotice.value = null }
    fun openFailed(request: ChatFileRequest) { fail(request, ChatFileTransfer.Failure.OPEN) }

    private fun fail(request: ChatFileRequest, failure: ChatFileTransfer.Failure) {
        val failed = ChatFileTransfer(request, failure = failure)
        update(failed)
        mutableNotice.value = failed
    }

    private fun update(value: ChatFileTransfer) {
        mutableTransfers.value = mutableTransfers.value + (value.request.key to value)
    }

    override fun onCleared() {
        tokens.values.forEach { it.cancel() }
        scope.cancel()
    }
}

internal data class ChatFileActions(
    val transfers: Map<String, ChatFileTransfer> = emptyMap(),
    val download: (ChatFileRequest) -> Unit = {},
    val share: (ChatFileRequest) -> Unit = {},
    val open: (ChatFileRequest) -> Unit = {},
    val cancel: (ChatFileRequest) -> Unit = {},
)

internal val LocalChatFileActions = staticCompositionLocalOf { ChatFileActions() }

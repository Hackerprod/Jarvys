package com.jarvys.agent

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import com.jarvys.agent.ui.chat.PendingChatAttachment
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Owns temporary attachments across activity recreation; persisted messages own their files. */
class AttachmentDraftViewModel(application: Application) : AndroidViewModel(application) {
    data class Submission(val sessionId: String, val text: String, val persisted: Boolean, val error: String?)
    private val app = application.applicationContext
    private val store by lazy { AttachmentStore(app) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "JarvysAttachmentDrafts").apply { isDaemon = true } }
    private val lock = Any()
    private val mutableDrafts = MutableStateFlow<List<PendingChatAttachment>>(emptyList())
    private val mutableSending = MutableStateFlow(false)
    private val events = Channel<Submission>(Channel.UNLIMITED)
    val drafts = mutableDrafts.asStateFlow()
    val sending = mutableSending.asStateFlow()
    val submissions = events.receiveAsFlow()
    private var sessionId = ""
    private var protectedIds = emptySet<String>()
    private val cancelSend = AtomicBoolean(false)
    var cameraFile: File? = null
        private set
    var cameraSession: String? = null
        private set

    init {
        if (startupCleanup.compareAndSet(false, true)) io.execute {
            runCatching { LocalRunStore(app).cleanupOrphanAttachments() }
        }
    }

    fun switchSession(next: String) = synchronized(lock) {
        if (sessionId == next) return@synchronized
        val oldSession = sessionId
        val abandoned = mutableDrafts.value.filterNot { it.id in protectedIds }
        sessionId = next
        mutableDrafts.value = emptyList()
        abandoned.forEach { pending -> pending.attachment?.let { attachment ->
            io.execute { runCatching { store.deleteAttachment(oldSession, attachment) } }
        } }
    }

    fun addUris(session: String, uris: List<Uri>, kind: ChatAttachment.Kind? = null) = synchronized(lock) {
        if (session != sessionId || mutableSending.value) return@synchronized
        uris.forEach { uri ->
            val id = UUID.randomUUID().toString()
            mutableDrafts.value += PendingChatAttachment(id,
                message(if (kind == ChatAttachment.Kind.IMAGE) R.string.chat_attachment_image else R.string.chat_attachment_file),
                kind = kind ?: ChatAttachment.Kind.FILE, copying = true)
            io.execute {
                val result = runCatching {
                    if (kind == null) store.copyFromUri(session, uri) else store.copyFromUri(session, uri, kind)
                }
                synchronized(lock) {
                    if (session != sessionId || mutableDrafts.value.none { it.id == id }) {
                        result.getOrNull()?.let { runCatching { store.deleteAttachment(session, it) } }
                    } else mutableDrafts.value = mutableDrafts.value.map { pending ->
                        if (pending.id != id) pending else result.fold(
                            onSuccess = { PendingChatAttachment(id, it.name, it.mimeType, it.sizeBytes, it.kind, it) },
                            onFailure = { pending.copy(copying = false, error = message(R.string.chat_attachment_copy_failed)) })
                    }
                }
            }
        }
    }

    fun remove(id: String) = synchronized(lock) {
        if (id in protectedIds) return@synchronized
        val pending = mutableDrafts.value.firstOrNull { it.id == id } ?: return@synchronized
        val session = sessionId
        mutableDrafts.value = mutableDrafts.value.filterNot { it.id == id }
        pending.attachment?.let { io.execute { runCatching { store.deleteAttachment(session, it) } } }
    }

    fun submit(session: String, text: String, skills: ArrayList<String>, memoryDisabled: Boolean): Boolean {
        val pending = synchronized(lock) {
            if (session != sessionId || mutableSending.value) return false
            val pending = mutableDrafts.value
            if (pending.any { it.copying || it.error != null || it.attachment == null }
                || (text.isBlank() && pending.isEmpty())) return false
            protectedIds = pending.map { it.id }.toSet()
            cancelSend.set(false)
            mutableSending.value = true
            pending
        }
        io.execute {
            var persisted = false
            var error: String? = null
            try {
                if (cancelSend.get()) throw InterruptedException()
                val attachments = pending.mapNotNull { it.attachment }
                val messageId = LocalRunStore(app).appendConversationMessage(session, "user", text, attachments)
                persisted = true
                AgentRunUiState.beginRun(session, text, attachments)
                AgentRunUiState.bindCurrentUserMessage(session, messageId)
                if (cancelSend.get()) throw InterruptedException()
                AgentForegroundService.startRealAgentFromStoredChatMessage(app, session, messageId, skills, memoryDisabled)
            } catch (failure: Exception) {
                error = message(if (failure is InterruptedException) R.string.chat_attachment_send_cancelled
                    else R.string.chat_attachment_send_failed)
                if (persisted) AgentRunUiState.failChatTurn(session, error)
            } finally {
                synchronized(lock) {
                    if (persisted) mutableDrafts.value = mutableDrafts.value.filterNot { it.id in protectedIds }
                    else if (session != sessionId) pending.forEach { item -> item.attachment?.let {
                        runCatching { store.deleteAttachment(session, it) }
                    } }
                    protectedIds = emptySet()
                    mutableSending.value = false
                }
                events.trySend(Submission(session, text, persisted, error))
            }
        }
        return true
    }

    fun cancelSubmission() { cancelSend.set(true) }
    private fun message(resource: Int) = AppLanguageRuntime.localizedContext(app).getString(resource)

    fun createCameraFile(session: String): File = synchronized(lock) {
        clearCameraFile()
        val root = File(app.cacheDir, "jarvys/camera")
        check(root.canonicalPath == File(app.cacheDir.canonicalFile, "jarvys/camera").path) { "Unsafe camera cache" }
        check(root.isDirectory || root.mkdirs()) { "Could not create camera cache" }
        File.createTempFile("capture-", ".jpg", root).also { cameraFile = it; cameraSession = session }
    }

    fun finishCamera(success: Boolean) {
        val (file, session) = synchronized(lock) { cameraFile to cameraSession }
        try {
            if (success && file != null && session != null && file.isFile) {
                addUris(session, listOf(FileProvider.getUriForFile(app, "${app.packageName}.generated-images", file)), ChatAttachment.Kind.IMAGE)
            }
        } finally {
            synchronized(lock) { cameraFile = null; cameraSession = null }
            if (file != null) io.execute { safeDeleteCamera(file) }
        }
    }

    private fun clearCameraFile() {
        cameraFile?.let(::safeDeleteCamera)
        cameraFile = null
        cameraSession = null
    }
    private fun safeDeleteCamera(file: File) {
        runCatching {
            val root = File(app.cacheDir.canonicalFile, "jarvys/camera")
            if (file.parentFile?.canonicalFile == root && file.canonicalFile.parentFile == root) file.delete()
        }
    }
    override fun onCleared() {
        cancelSubmission()
        synchronized(lock) {
            val session = sessionId
            mutableDrafts.value.filterNot { it.id in protectedIds }.forEach { item -> item.attachment?.let {
                io.execute { runCatching { store.deleteAttachment(session, it) } }
            } }
            mutableDrafts.value = emptyList()
            sessionId = ""
            clearCameraFile()
        }
        io.shutdown()
    }
    private companion object { val startupCleanup = AtomicBoolean(false) }
}

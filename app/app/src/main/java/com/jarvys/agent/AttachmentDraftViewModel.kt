package com.jarvys.agent

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import com.jarvys.agent.ui.chat.PendingChatAttachment
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Owns temporary attachments across activity recreation; persisted messages own their files. */
class AttachmentDraftViewModel internal constructor(
    application: Application,
    private val messageSubmitter: AttachmentMessageSubmitter = LocalAttachmentMessageSubmitter,
    private val submissionExecutor: ExecutorService = newSubmissionExecutor(),
) : AndroidViewModel(application) {
    data class Submission(val sessionId: String, val text: String, val persisted: Boolean, val error: String?)
    private val app = application.applicationContext
    private val store by lazy { AttachmentStore(app) }
    private val attachmentIo = Executors.newSingleThreadExecutor { Thread(it, "JarvysAttachmentDrafts").apply { isDaemon = true } }
    private val lock = Any()
    private val mutableDrafts = MutableStateFlow<List<PendingChatAttachment>>(emptyList())
    private val mutableSending = MutableStateFlow(false)
    private val events = Channel<Submission>(Channel.UNLIMITED)
    val drafts = mutableDrafts.asStateFlow()
    /** Sending reflects only the currently selected conversation. */
    val sending = mutableSending.asStateFlow()
    val submissions = events.receiveAsFlow()
    private var sessionId = ""
    private val protectedIdsBySession = mutableMapOf<String, Set<String>>()
    private val activeSubmissions = mutableMapOf<String, ActiveSubmission>()
    var cameraFile: File? = null
        private set
    var cameraSession: String? = null
        private set

    init {
        if (startupCleanup.compareAndSet(false, true)) attachmentIo.execute {
            runCatching { LocalRunStore(app).cleanupOrphanAttachments() }
        }
    }

    fun switchSession(next: String) = synchronized(lock) {
        if (sessionId == next) return@synchronized
        val oldSession = sessionId
        val protectedIds = protectedIdsBySession[oldSession].orEmpty()
        val abandoned = mutableDrafts.value.filterNot { it.id in protectedIds }
        sessionId = next
        mutableDrafts.value = emptyList()
        updateSendingProjectionLocked()
        abandoned.forEach { pending -> pending.attachment?.let { attachment ->
            attachmentIo.execute { runCatching { store.deleteAttachment(oldSession, attachment) } }
        } }
    }

    fun addUris(session: String, uris: List<Uri>, kind: ChatAttachment.Kind? = null) = synchronized(lock) {
        if (session != sessionId || mutableSending.value) return@synchronized
        uris.forEach { uri ->
            val id = UUID.randomUUID().toString()
            mutableDrafts.value += PendingChatAttachment(id,
                message(if (kind == ChatAttachment.Kind.IMAGE) R.string.chat_attachment_image else R.string.chat_attachment_file),
                kind = kind ?: ChatAttachment.Kind.FILE, copying = true)
            attachmentIo.execute {
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
        if (id in protectedIdsBySession[sessionId].orEmpty()) return@synchronized
        val pending = mutableDrafts.value.firstOrNull { it.id == id } ?: return@synchronized
        val session = sessionId
        mutableDrafts.value = mutableDrafts.value.filterNot { it.id == id }
        pending.attachment?.let { attachmentIo.execute { runCatching { store.deleteAttachment(session, it) } } }
    }

    fun submit(session: String, text: String, skills: ArrayList<String>, memoryDisabled: Boolean): Boolean {
        val pending = synchronized(lock) {
            if (session != sessionId || session in activeSubmissions) return false
            val pending = mutableDrafts.value
            if (pending.any { it.copying || it.error != null || it.attachment == null }
                || (text.isBlank() && pending.isEmpty())) return false
            protectedIdsBySession[session] = pending.map { it.id }.toSet()
            val attempt = ActiveSubmission(session, SubmissionCancellation())
            activeSubmissions[session] = attempt
            updateSendingProjectionLocked()
            pending to attempt
        }
        val (attachmentsToSend, attempt) = pending
        // Publish the user turn before any disk work so the chat reflects the tap immediately.
        val optimisticEventId = AgentRunUiState.beginChatSubmission(
            session, text, attachmentsToSend.mapNotNull { it.attachment })
        try {
            submissionExecutor.execute {
                var persisted = false
                var error: String? = null
                try {
                    attempt.cancellation.throwIfCancelledBeforePersistence()
                    val attachments = attachmentsToSend.mapNotNull { it.attachment }
                    attempt.cancellation.throwIfCancelledBeforePersistence()
                    val messageId = messageSubmitter.persistMessage(getApplication(), session, text, attachments)
                    persisted = true
                    attempt.cancellation.markPersisted()
                    AgentRunUiState.bindCurrentUserMessage(session, messageId, optimisticEventId)
                    messageSubmitter.startAgent(getApplication(), session, messageId, skills, memoryDisabled)
                } catch (failure: Exception) {
                    error = message(if (failure is CancellationException && !persisted) R.string.chat_attachment_send_cancelled
                        else R.string.chat_attachment_send_failed)
                    if (persisted) AgentRunUiState.failChatTurn(session, error)
                    else AgentRunUiState.failOptimisticUserMessage(session, optimisticEventId, error)
                } finally {
                    synchronized(lock) {
                        if (persisted && session == sessionId) {
                            val protectedIds = protectedIdsBySession[session].orEmpty()
                            mutableDrafts.value = mutableDrafts.value.filterNot { it.id in protectedIds }
                        } else if (!persisted && (session != sessionId
                            || mutableDrafts.value.none { draft -> draft.id in protectedIdsBySession[session].orEmpty() })) {
                            attachmentsToSend.forEach { item -> item.attachment?.let {
                                runCatching { store.deleteAttachment(session, it) }
                            } }
                        }
                        protectedIdsBySession.remove(session)
                        if (activeSubmissions[session] === attempt) activeSubmissions.remove(session)
                        updateSendingProjectionLocked()
                    }
                    events.trySend(Submission(session, text, persisted, error))
                }
            }
        } catch (failure: RuntimeException) {
            val error = message(R.string.chat_attachment_send_failed)
            AgentRunUiState.failOptimisticUserMessage(session, optimisticEventId, error)
            synchronized(lock) {
                if (session != sessionId) attachmentsToSend.forEach { item -> item.attachment?.let {
                    runCatching { store.deleteAttachment(session, it) }
                } }
                protectedIdsBySession.remove(session)
                if (activeSubmissions[session] === attempt) activeSubmissions.remove(session)
                updateSendingProjectionLocked()
            }
            events.trySend(Submission(session, text, persisted = false, error = error))
        }
        return true
    }

    /** Whether this conversation has a submission being persisted or handed to the service. */
    fun isSending(sessionId: String): Boolean = synchronized(lock) { sessionId in activeSubmissions }

    /** Stop only the in-flight, unpersisted submission for the requested session. */
    fun cancelSubmission(session: String? = null) = synchronized(lock) {
        val targetSession = session ?: sessionId
        val attempt = activeSubmissions[targetSession] ?: return@synchronized
        if (!attempt.cancellation.isPersisted()) {
            attempt.cancellation.cancelBeforePersistence()
        }
    }

    private fun updateSendingProjectionLocked() {
        mutableSending.value = sessionId in activeSubmissions
    }

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
            if (file != null) attachmentIo.execute { safeDeleteCamera(file) }
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
        synchronized(lock) {
            activeSubmissions.values.forEach { it.cancellation.cancelBeforePersistence() }
            val session = sessionId
            val protectedIds = protectedIdsBySession[session].orEmpty()
            mutableDrafts.value.filterNot { it.id in protectedIds }.forEach { item -> item.attachment?.let {
                attachmentIo.execute { runCatching { store.deleteAttachment(session, it) } }
            } }
            mutableDrafts.value = emptyList()
            sessionId = ""
            updateSendingProjectionLocked()
            clearCameraFile()
        }
        submissionExecutor.shutdown()
        attachmentIo.shutdown()
    }
    private class ActiveSubmission(val sessionId: String, val cancellation: SubmissionCancellation)

    private class SubmissionCancellation {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var persisted = false

        fun cancelBeforePersistence() {
            if (!persisted) cancelled.set(true)
        }

        fun throwIfCancelledBeforePersistence() {
            if (!persisted && cancelled.get()) throw CancellationException("Submission stopped before persistence")
        }

        fun markPersisted() { persisted = true }
        fun isPersisted() = persisted
    }

    private companion object {
        val startupCleanup = AtomicBoolean(false)
        fun newSubmissionExecutor() = Executors.newCachedThreadPool {
            Thread(it, "JarvysChatSubmissions").apply { isDaemon = true }
        }
    }
}

internal interface AttachmentMessageSubmitter {
    fun persistMessage(application: Application, session: String, text: String, attachments: List<ChatAttachment>): String
    fun startAgent(application: Application, session: String, messageId: String, skills: ArrayList<String>, memoryDisabled: Boolean)
}

private object LocalAttachmentMessageSubmitter : AttachmentMessageSubmitter {
    override fun persistMessage(application: Application, session: String, text: String, attachments: List<ChatAttachment>) =
        LocalRunStore(application).appendConversationMessage(session, "user", text, attachments)

    override fun startAgent(application: Application, session: String, messageId: String, skills: ArrayList<String>, memoryDisabled: Boolean) =
        AgentForegroundService.startRealAgentFromStoredChatMessage(application, session, messageId, skills, memoryDisabled)
}

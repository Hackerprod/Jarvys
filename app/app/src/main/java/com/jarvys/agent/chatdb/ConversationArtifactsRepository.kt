package com.jarvys.agent.chatdb

import androidx.room.withTransaction
import com.jarvys.agent.ChatAttachment
import java.util.Locale
import java.util.UUID

data class DeliveredArtifactInput(
    val conversationId: String,
    val artifactId: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val kind: String,
    val relativePath: String,
    val sha256: String,
    val artifactType: String? = null,
    val moduleId: String? = null,
    val manifestJson: String? = null,
    val title: String? = null,
    val thumbnailRef: String? = null,
    val createdAtSeconds: Double? = null,
)

data class GeneratedImageInput(
    val conversationId: String,
    val prompt: String,
    val revisedPrompt: String = "",
    val size: String = "",
    val mimeType: String = "image/png",
    val status: String,
    val imagePath: String? = null,
    val sha256: String? = null,
    val bytes: Long? = null,
    val error: String? = null,
    val createdAtSeconds: Double? = null,
)

sealed interface ChatFileOwnership {
    data class Attachment(val value: ChatAttachment) : ChatFileOwnership
    data class Delivered(val value: ConversationArtifact) : ChatFileOwnership
}

/**
 * Normalized ownership for `delivered_file` and `generated_image` ledger rows. It replaces
 * `LocalRunStore.appendDeliveredFile`/`findChatFile` (`LocalRunStore.java:1465-1532`) and
 * `appendGeneratedImageEvent`/`appendGeneratedImageFailure`/`ownsGeneratedFile`
 * (`LocalRunStore.java:1528-1554`). Module manifests remain opaque JSON because only their
 * presentation module defines their fields; the shared artifact identity, type, module, file,
 * thumbnail and privacy lookups remain indexed.
 */
internal class ConversationArtifactsRepository internal constructor(database: JarvysChatDatabase) : M1bWriteSupport(database) {
    /** Stores one `delivered_file`; duplicate `(conversation_id,artifact_id)` content is idempotent, differing content conflicts. */
    suspend fun appendDeliveredArtifact(input: DeliveredArtifactInput): ChatDbResult<ConversationArtifact> = transaction {
        if (input.conversationId.isBlank() || input.artifactId.isBlank() || input.name.isBlank() || input.relativePath.isBlank() ||
            input.sha256.isBlank() || input.sizeBytes < 0L || input.kind.isBlank())
            return@transaction ChatDbResult.Invalid("delivered artifact metadata is required")
        ensureConversationExists(input.conversationId)
        val mime = normalizeMimeType(input.mimeType)
        val type = input.artifactType?.takeIf(String::isNotBlank) ?: mime ?: "application/octet-stream"
        val existing = readArtifact(input.conversationId, input.artifactId)
        if (existing != null) {
            val same = existing.name == input.name && existing.mime_type == mime && existing.size_bytes == input.sizeBytes &&
                existing.kind == input.kind && existing.relative_path == input.relativePath && existing.sha256 == input.sha256 &&
                existing.artifact_type == type && existing.module_id == input.moduleId && existing.manifest_json == input.manifestJson &&
                existing.title == input.title && existing.thumbnail_ref == input.thumbnailRef
            return@transaction if (same) ChatDbResult.Success(existing) else ChatDbResult.Conflict("delivered artifact identity has different content")
        }
        if (input.thumbnailRef != null && !fileRefExists(input.thumbnailRef))
            return@transaction ChatDbResult.Conflict("artifact thumbnail file reference is missing")
        val timestamp = input.createdAtSeconds ?: nowSeconds()
        val fileRef = ensureFileRef(input.relativePath, input.sha256, input.sizeBytes, mime ?: "application/octet-stream", (timestamp * 1000.0).toLong())
        val seq = insertLedgerItem(input.conversationId, "delivered_file", input.artifactId, timestamp)
        sql.execSQL(
            "INSERT INTO artifacts(conversation_id,artifact_id,seq,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id,artifact_type,module_id,manifest_json,title,thumbnail_ref,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, input.artifactId, seq, input.name, mime, input.sizeBytes, input.kind,
                input.relativePath, input.sha256, fileRef, type, input.moduleId, input.manifestJson, input.title, input.thumbnailRef, timestamp),
        )
        ChatDbResult.Success(readArtifact(input.conversationId, input.artifactId)!!)
    }

    /**
     * Stores a `generated_image` event, replacing `LocalRunStore.appendGeneratedImageEvent` and
     * `appendGeneratedImageFailure` (`LocalRunStore.java:appendGeneratedImageEvent`,
     * `LocalRunStore.java:appendGeneratedImageFailure`); image bytes stay on disk and `file_refs` records metadata.
     */
    suspend fun appendGeneratedImage(input: GeneratedImageInput): ChatDbResult<GeneratedImage> = transaction {
        if (input.conversationId.isBlank() || input.status.isBlank() || input.bytes?.let { it < 0L } == true)
            return@transaction ChatDbResult.Invalid("generated image fields are invalid")
        if ((input.imagePath == null) != (input.sha256 == null) || (input.imagePath == null) != (input.bytes == null))
            return@transaction ChatDbResult.Invalid("generated image path, digest, and byte count must be supplied together")
        ensureConversationExists(input.conversationId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val fileRef = if (input.imagePath == null) null else ensureFileRef(
            input.imagePath, input.sha256!!, input.bytes!!, input.mimeType, (time * 1000.0).toLong(),
        )
        val seq = insertLedgerItem(input.conversationId, "generated_image", input.imagePath ?: "", time)
        sql.execSQL(
            "INSERT INTO generated_images(conversation_id,seq,image_path,prompt,revised_prompt,size,mime_type,status,error,file_ref_id,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, input.imagePath, input.prompt, input.revisedPrompt, input.size, input.mimeType,
                input.status, input.error, fileRef, time),
        )
        ChatDbResult.Success(readGeneratedImage(input.conversationId, seq)!!)
    }

    /** Indexed equivalent of `LocalRunStore.findChatFile` (`LocalRunStore.java:findChatFile`) for delivered and message-attachment ownership. */
    suspend fun findChatFile(conversationId: String, kind: String, id: String): ChatFileOwnership? = database.withTransaction {
        when (kind) {
            "delivered" -> readArtifact(conversationId, id)?.let { ChatFileOwnership.Delivered(it) }
            "attachment" -> sql.query(
                "SELECT a.* FROM message_attachments a JOIN messages m ON m.conversation_id=a.conversation_id AND m.message_id=a.message_id " +
                    "WHERE a.conversation_id=? AND a.attachment_id=? ORDER BY m.seq DESC,a.ordinal DESC LIMIT 1",
                arrayOf(conversationId, id),
            ).use { c ->
                if (!c.moveToFirst()) null else runCatching {
                    ChatFileOwnership.Attachment(ChatAttachment(
                        c.getString(c.getColumnIndexOrThrow("attachment_id")),
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getStringOrNullM1b("mime_type"),
                        c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                        ChatAttachment.Kind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
                        c.getString(c.getColumnIndexOrThrow("relative_path")),
                    ))
                }.getOrNull()
            }
            else -> null
        }
    }

    /** Lists `delivered_file` artifacts in ledger order, backed by `LocalRunStore.findChatFile` ownership data and `idx_artifacts_seq`. */
    suspend fun artifactsForConversation(conversationId: String): List<ConversationArtifact> = database.withTransaction {
        sql.query("SELECT artifact_id FROM artifacts WHERE conversation_id=? ORDER BY seq", arrayOf(conversationId)).use { c ->
            buildList { while (c.moveToNext()) readArtifact(conversationId, c.getString(0))?.let(::add) }
        }
    }

    /** Indexed equivalent of `LocalRunStore.ownsGeneratedFile` (`LocalRunStore.java:ownsGeneratedFile`); failed or pathless generations are never owned images. */
    suspend fun ownsGeneratedFile(conversationId: String, relativePath: String): Boolean = database.withTransaction {
        sql.query(
            "SELECT EXISTS(SELECT 1 FROM generated_images WHERE conversation_id=? AND image_path=? AND status='COMPLETED')",
            arrayOf(conversationId, relativePath),
        ).use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /** Equivalent to `LocalRunStore.conversationHasAttachments` (`LocalRunStore.java:1645-1654`). */
    suspend fun conversationHasAttachments(conversationId: String): Boolean = database.withTransaction {
        sql.query("SELECT EXISTS(SELECT 1 FROM message_attachments WHERE conversation_id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /** Equivalent to `LocalRunStore.conversationHasPrivateImagesOrAttachments`; delivered files count with generated images. */
    suspend fun conversationHasPrivateImagesOrAttachments(conversationId: String): Boolean = database.withTransaction {
        sql.query(
            "SELECT EXISTS(SELECT 1 FROM message_attachments WHERE conversation_id=? UNION ALL " +
                "SELECT 1 FROM artifacts WHERE conversation_id=? UNION ALL " +
                "SELECT 1 FROM generated_images WHERE conversation_id=?)",
            arrayOf(conversationId, conversationId, conversationId),
        ).use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    private fun normalizeMimeType(raw: String?): String {
        val mime = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (mime.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) mime else "application/octet-stream"
    }

    private fun readArtifact(conversationId: String, artifactId: String): ConversationArtifact? = sql.query(
        "SELECT * FROM artifacts WHERE conversation_id=? AND artifact_id=?", arrayOf(conversationId, artifactId),
    ).use { if (it.moveToFirst()) ConversationArtifact(
        it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getStringOrNullM1b("mime_type"), it.getLong(5),
        it.getString(6), it.getString(7), it.getStringOrNullM1b("sha256"), it.getStringOrNullM1b("file_ref_id"),
        it.getString(10), it.getStringOrNullM1b("module_id"), it.getStringOrNullM1b("manifest_json"),
        it.getStringOrNullM1b("title"), it.getStringOrNullM1b("thumbnail_ref"), it.getDouble(15),
    ) else null }

    private fun readGeneratedImage(conversationId: String, seq: Long): GeneratedImage? = sql.query(
        "SELECT * FROM generated_images WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) GeneratedImage(
        it.getString(0), it.getLong(1), it.getStringOrNullM1b("image_path"), it.getString(3), it.getStringOrNullM1b("revised_prompt"),
        it.getStringOrNullM1b("size"), it.getStringOrNullM1b("mime_type"), it.getString(7), it.getStringOrNullM1b("error"),
        it.getStringOrNullM1b("file_ref_id"), it.getDouble(10),
    ) else null }

    private fun fileRefExists(id: String): Boolean = sql.query("SELECT EXISTS(SELECT 1 FROM file_refs WHERE id=?)", arrayOf(id))
        .use { it.moveToFirst(); it.getInt(0) != 0 }

    private fun ensureFileRef(path: String, sha256: String, bytes: Long, mimeType: String, nowMillis: Long): String {
        val existing = sql.query("SELECT id,sha256,bytes,mime_type FROM file_refs WHERE relative_path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getStringOrNullM1b("mime_type")) else null
        }
        if (existing != null) {
            if (existing[1] != sha256 || existing[2] != bytes.toString()) throw M1bAbort("file_reference_content_conflict")
            val id = existing[0]!!
            sql.execSQL("UPDATE file_refs SET mime_type=?,last_referenced_at=?,not_before=NULL,attempts=0,generation=generation+1 WHERE id=?",
                arrayOf(mimeType, nowMillis, id))
            return id
        }
        val id = UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO file_refs(id,sha256,relative_path,bytes,mime_type,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,0,0)",
            arrayOf(id, sha256, path, bytes, mimeType, nowMillis))
        return id
    }
}

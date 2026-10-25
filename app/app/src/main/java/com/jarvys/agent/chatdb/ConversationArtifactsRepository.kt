package com.jarvys.agent.chatdb

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
    val thumbnailPath: String? = null,
    val thumbnailSha256: String? = null,
    val thumbnailSizeBytes: Long? = null,
    val thumbnailMimeType: String? = null,
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
    val artifactType: String? = null,
    val moduleId: String? = null,
    val title: String? = null,
    val manifestJson: String? = null,
)

/** Ordered legacy image-reference candidates; invalid generated paths are filtered by the domain factory. */
data class ConversationImageReferenceValue(
    val messageId: String,
    val attachment: ChatAttachment? = null,
    val generatedPath: String? = null,
    val prompt: String = "",
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
    private data class FileRefContent(val id: String, val sha256: String, val bytes: Long, val mimeType: String?)

    /** Stores one generic artifact event; duplicate identity/content is idempotent, changed content conflicts. */
    suspend fun appendDeliveredArtifact(input: DeliveredArtifactInput): ChatDbResult<ConversationArtifact> = transaction {
        if (input.conversationId.isBlank() || input.artifactId.isBlank() || input.name.isBlank() || input.relativePath.isBlank() ||
            input.sha256.isBlank() || input.sizeBytes < 0L || input.kind.isBlank())
            return@transaction ChatDbResult.Invalid("delivered artifact metadata is required")
        val hasThumbnailMetadata = input.thumbnailPath != null || input.thumbnailSha256 != null ||
            input.thumbnailSizeBytes != null || input.thumbnailMimeType != null
        val hasCompleteThumbnailMetadata = input.thumbnailPath != null && input.thumbnailSha256 != null &&
            input.thumbnailSizeBytes != null && input.thumbnailMimeType != null
        if (hasThumbnailMetadata && (!hasCompleteThumbnailMetadata || input.thumbnailRef != null))
            return@transaction ChatDbResult.Invalid("thumbnail path, digest, byte count, and MIME type must be supplied together")
        if (hasCompleteThumbnailMetadata && (input.thumbnailPath.isNullOrBlank() ||
                !input.thumbnailSha256.orEmpty().matches(Regex("[a-f0-9]{64}")) ||
                input.thumbnailSizeBytes!! < 0L ||
                normalizeMimeType(input.thumbnailMimeType)?.startsWith("image/") != true))
            return@transaction ChatDbResult.Invalid("thumbnail metadata is invalid")
        ensureConversationExists(input.conversationId)
        val mime = normalizeMimeType(input.mimeType)
        val type = input.artifactType?.takeIf(String::isNotBlank) ?: mime ?: "application/octet-stream"
        val existing = readArtifact(input.conversationId, input.artifactId)
        if (existing != null) {
            val expectedThumbnailRef = when {
                hasCompleteThumbnailMetadata -> findFileRef(input.thumbnailPath!!)?.takeIf { fileRef ->
                    fileRef.sha256 == input.thumbnailSha256 && fileRef.bytes == input.thumbnailSizeBytes &&
                        fileRef.mimeType == normalizeMimeType(input.thumbnailMimeType)
                }?.id
                else -> input.thumbnailRef
            }
            val same = existing.name == input.name && existing.mime_type == mime && existing.size_bytes == input.sizeBytes &&
                existing.kind == input.kind && existing.relative_path == input.relativePath && existing.sha256 == input.sha256 &&
                existing.artifact_type == type && existing.module_id == input.moduleId && existing.manifest_json == input.manifestJson &&
                existing.title == input.title && existing.thumbnail_ref == expectedThumbnailRef
            return@transaction if (same) ChatDbResult.Success(existing) else ChatDbResult.Conflict("delivered artifact identity has different content")
        }
        if (input.thumbnailRef != null && !fileRefExists(input.thumbnailRef))
            return@transaction ChatDbResult.Conflict("artifact thumbnail file reference is missing")
        val timestamp = input.createdAtSeconds ?: nowSeconds()
        val fileRef = ensureFileRef(input.relativePath, input.sha256, input.sizeBytes, mime ?: "application/octet-stream", (timestamp * 1000.0).toLong())
        val thumbnailRef = if (hasCompleteThumbnailMetadata) ensureFileRef(
            input.thumbnailPath!!, input.thumbnailSha256!!, input.thumbnailSizeBytes!!,
            normalizeMimeType(input.thumbnailMimeType) ?: return@transaction ChatDbResult.Invalid("thumbnail MIME type is invalid"),
            (timestamp * 1000.0).toLong(),
        ) else input.thumbnailRef
        val seq = insertLedgerItem(input.conversationId, "artifact", input.artifactId, timestamp)
        sql.execSQL(
            "INSERT INTO artifacts(conversation_id,artifact_id,seq,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id,artifact_type,module_id,manifest_json,title,thumbnail_ref,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, input.artifactId, seq, input.name, mime, input.sizeBytes, input.kind,
                input.relativePath, input.sha256, fileRef, type, input.moduleId, input.manifestJson, input.title, thumbnailRef, timestamp),
        )
        ChatDbResult.Success(readArtifact(input.conversationId, input.artifactId)!!)
    }

    /**
     * Stores generated-image metadata and a generic artifact event when bytes exist, replacing `LocalRunStore.appendGeneratedImageEvent` and
     * `appendGeneratedImageFailure` (`LocalRunStore.java:appendGeneratedImageEvent`,
     * `LocalRunStore.java:appendGeneratedImageFailure`); image bytes stay on disk and `file_refs` records metadata.
     */
    suspend fun appendGeneratedImage(input: GeneratedImageInput): ChatDbResult<GeneratedImage> = transaction {
        if (input.conversationId.isBlank() || input.status.isBlank() || input.bytes?.let { it < 0L } == true)
            return@transaction ChatDbResult.Invalid("generated image fields are invalid")
        val hasAnyFileMetadata = input.imagePath != null || input.sha256 != null || input.bytes != null
        val hasCompleteFileMetadata = input.imagePath != null && input.sha256 != null && input.bytes != null
        if (hasAnyFileMetadata && !hasCompleteFileMetadata)
            return@transaction ChatDbResult.Invalid("generated image path, digest, and byte count must be supplied together")
        ensureConversationExists(input.conversationId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val type = input.artifactType?.takeIf(String::isNotBlank) ?: normalizeMimeType(input.mimeType) ?: "application/octet-stream"
        val name = input.imagePath?.substringAfterLast('/')?.ifBlank { "artifact" } ?: "artifact"
        val artifactId = input.imagePath?.let(::generatedArtifactId) ?: "generated:$time"
        if (hasCompleteFileMetadata && input.status == "COMPLETED") {
            val existing = readArtifact(input.conversationId, artifactId)
            if (existing != null) {
                val existingImage = readGeneratedImage(input.conversationId, existing.seq)
                val same = existingImage != null && existing.name == name && existing.mime_type == normalizeMimeType(input.mimeType) &&
                    existing.size_bytes == input.bytes && existing.kind == "IMAGE" && existing.relative_path == input.imagePath &&
                    existing.sha256 == input.sha256 && existing.artifact_type == type && existing.module_id == input.moduleId &&
                    existing.manifest_json == input.manifestJson && existing.title == (input.title ?: name) &&
                    existingImage.image_path == input.imagePath &&
                    existingImage.prompt == input.prompt && existingImage.revised_prompt == input.revisedPrompt &&
                    existingImage.size == input.size && existingImage.mime_type == input.mimeType &&
                    existingImage.status == input.status && existingImage.error == input.error
                return@transaction if (same) ChatDbResult.Success(existingImage!!)
                    else ChatDbResult.Conflict("generated artifact identity has different content")
            }
        }
        val fileRef = if (!hasCompleteFileMetadata) null else ensureFileRef(
            input.imagePath!!, input.sha256!!, input.bytes!!, input.mimeType, (time * 1000.0).toLong(),
        )
        val seq = insertLedgerItem(input.conversationId, "artifact", "generated:$time:${input.imagePath.orEmpty()}", time)
        sql.execSQL(
            "INSERT INTO generated_images(conversation_id,seq,image_path,prompt,revised_prompt,size,mime_type,status,error,file_ref_id,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, input.imagePath, input.prompt, input.revisedPrompt, input.size, input.mimeType,
                input.status, input.error, fileRef, time),
        )
        if (hasCompleteFileMetadata && input.status == "COMPLETED") {
            val mime = normalizeMimeType(input.mimeType)
            sql.execSQL(
                "INSERT INTO artifacts(conversation_id,artifact_id,seq,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id,artifact_type,module_id,manifest_json,title,thumbnail_ref,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(input.conversationId, artifactId, seq, name, mime, input.bytes!!, "IMAGE", input.imagePath,
                    input.sha256, fileRef, type, input.moduleId, input.manifestJson, input.title ?: name, null, time),
            )
        }
        ChatDbResult.Success(readGeneratedImage(input.conversationId, seq)!!)
    }

    private fun generatedArtifactId(path: String): String = "generated:$path"

    /** Indexed equivalent of `LocalRunStore.findChatFile` (`LocalRunStore.java:findChatFile`) for delivered and message-attachment ownership. */
    suspend fun findChatFile(conversationId: String, kind: String, id: String): ChatFileOwnership? = readSnapshot {
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
    suspend fun artifactsForConversation(conversationId: String): List<ConversationArtifact> = readSnapshot {
        sql.query("SELECT * FROM artifacts WHERE conversation_id=? ORDER BY seq", arrayOf(conversationId)).use { c ->
            buildList { while (c.moveToNext()) add(c.toConversationArtifact()) }
        }
    }

    /** Resolves the persisted thumbnail file reference for one exact transcript artifact. */
    suspend fun artifactThumbnailRef(conversationId: String, artifactId: String): FileRef? = readSnapshot {
        sql.query(
            "SELECT f.* FROM artifacts a JOIN file_refs f ON f.id=a.thumbnail_ref WHERE a.conversation_id=? AND a.artifact_id=?",
            arrayOf(conversationId, artifactId),
        ).use { c -> if (!c.moveToFirst()) null else FileRef(
            c.getString(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("sha256")),
            c.getString(c.getColumnIndexOrThrow("relative_path")),
            c.getLong(c.getColumnIndexOrThrow("bytes")),
            c.getStringOrNullM1b("mime_type"),
            c.getLong(c.getColumnIndexOrThrow("last_referenced_at")),
            c.getLongOrNullM1b("not_before"),
            c.getInt(c.getColumnIndexOrThrow("attempts")),
            c.getLong(c.getColumnIndexOrThrow("generation")),
        ) }
    }

    /**
     * Image refs in physical ledger order, replacing `LocalRunStore.readConversationImageReferences`.
     * The user-message owner is derived at each generated-image row's sequence, not from the
     * steering-aware UI order.
     */
    suspend fun readImageReferences(conversationId: String): List<ConversationImageReferenceValue> = readSnapshot {
        val kind = sql.query("SELECT kind FROM conversations WHERE id=?", arrayOf(conversationId))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (kind == "proactive" || kind == "tasks") return@readSnapshot emptyList()
        val query = "SELECT source_seq,ordinal,message_id,source_kind,attachment_id,name,mime_type,size_bytes,attachment_kind,relative_path,generated_path,prompt " +
            "FROM (" +
            "SELECT m.seq AS source_seq,a.ordinal AS ordinal,m.message_id AS message_id,'attachment' AS source_kind," +
            "a.attachment_id AS attachment_id,a.name AS name,a.mime_type AS mime_type,a.size_bytes AS size_bytes," +
            "a.kind AS attachment_kind,a.relative_path AS relative_path,NULL AS generated_path,NULL AS prompt " +
            "FROM messages m JOIN message_attachments a ON a.conversation_id=m.conversation_id AND a.message_id=m.message_id " +
            "WHERE m.conversation_id=? AND m.role='user' AND COALESCE(m.proactive_thread_key,'')='' AND a.kind='IMAGE' " +
            "UNION ALL " +
            "SELECT g.seq AS source_seq,0 AS ordinal," +
            "COALESCE((SELECT m.message_id FROM messages m WHERE m.conversation_id=g.conversation_id AND m.role='user' AND m.seq<g.seq ORDER BY m.seq DESC LIMIT 1),'') AS message_id," +
            "'generated' AS source_kind,NULL AS attachment_id,NULL AS name,NULL AS mime_type,NULL AS size_bytes,NULL AS attachment_kind,NULL AS relative_path," +
            "g.image_path AS generated_path,g.prompt AS prompt FROM generated_images g " +
            "WHERE g.conversation_id=? AND g.status='COMPLETED'" +
            ") ORDER BY source_seq,ordinal"
        sql.query(query, arrayOf(conversationId, conversationId)).use { c -> buildList {
            while (c.moveToNext()) {
                if (c.getString(3) == "attachment") {
                    val attachment = runCatching {
                        ChatAttachment(
                            c.getString(4), c.getString(5), c.getStringOrNullM1b("mime_type"), c.getLong(7),
                            ChatAttachment.Kind.valueOf(c.getString(8)), c.getString(9),
                        )
                    }.getOrNull() ?: continue
                    add(ConversationImageReferenceValue(c.getString(2), attachment = attachment))
                } else {
                    add(ConversationImageReferenceValue(
                        c.getString(2), generatedPath = c.getStringOrNullM1b("generated_path"),
                        prompt = c.getStringOrNullM1b("prompt").orEmpty(),
                    ))
                }
            }
        } }
    }

    /** File metadata with no surviving history reference, for a caller-owned post-transaction GC pass. */
    suspend fun unreferencedFileRefs(): List<FileRef> = readSnapshot {
        sql.query(
            "SELECT f.* FROM file_refs f " +
                "WHERE NOT EXISTS(SELECT 1 FROM message_attachments a WHERE a.file_ref_id=f.id) " +
                "AND NOT EXISTS(SELECT 1 FROM artifacts a WHERE a.file_ref_id=f.id) " +
                "AND NOT EXISTS(SELECT 1 FROM artifacts a WHERE a.thumbnail_ref=f.id) " +
                "AND NOT EXISTS(SELECT 1 FROM generated_images g WHERE g.file_ref_id=f.id) " +
                "AND NOT EXISTS(SELECT 1 FROM model_artifacts m WHERE m.file_ref_id=f.id) " +
                "ORDER BY f.last_referenced_at,f.relative_path",
        ).use { c -> buildList { while (c.moveToNext()) add(
            FileRef(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                sha256 = c.getString(c.getColumnIndexOrThrow("sha256")),
                relative_path = c.getString(c.getColumnIndexOrThrow("relative_path")),
                bytes = c.getLong(c.getColumnIndexOrThrow("bytes")),
                mime_type = c.getStringOrNullM1b("mime_type"),
                last_referenced_at = c.getLong(c.getColumnIndexOrThrow("last_referenced_at")),
                not_before = c.getColumnIndexOrThrow("not_before").let { if (c.isNull(it)) null else c.getLong(it) },
                attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                generation = c.getLong(c.getColumnIndexOrThrow("generation")),
            ),
        ) } }
    }

    /** Indexed equivalent of `LocalRunStore.ownsGeneratedFile` (`LocalRunStore.java:ownsGeneratedFile`); failed or pathless generations are never owned images. */
    suspend fun ownsGeneratedFile(conversationId: String, relativePath: String): Boolean = readSnapshot {
        sql.query(
            "SELECT EXISTS(SELECT 1 FROM generated_images WHERE conversation_id=? AND image_path=? AND status='COMPLETED')",
            arrayOf(conversationId, relativePath),
        ).use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /** Equivalent to `LocalRunStore.conversationHasAttachments` (`LocalRunStore.java:1645-1654`). */
    suspend fun conversationHasAttachments(conversationId: String): Boolean = readSnapshot {
        sql.query("SELECT EXISTS(SELECT 1 FROM message_attachments WHERE conversation_id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    private fun normalizeMimeType(raw: String?): String {
        val mime = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (mime.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) mime else "application/octet-stream"
    }

    private fun readArtifact(conversationId: String, artifactId: String): ConversationArtifact? = sql.query(
        "SELECT * FROM artifacts WHERE conversation_id=? AND artifact_id=?", arrayOf(conversationId, artifactId),
    ).use { if (it.moveToFirst()) it.toConversationArtifact() else null }

    private fun android.database.Cursor.toConversationArtifact() = ConversationArtifact(
        getString(requiredColumnIndex("conversation_id")),
        getString(requiredColumnIndex("artifact_id")),
        getLong(requiredColumnIndex("seq")),
        getString(requiredColumnIndex("name")),
        getStringOrNullM1b("mime_type"),
        getLong(requiredColumnIndex("size_bytes")),
        getString(requiredColumnIndex("kind")),
        getString(requiredColumnIndex("relative_path")),
        getStringOrNullM1b("sha256"),
        getStringOrNullM1b("file_ref_id"),
        getString(requiredColumnIndex("artifact_type")),
        getStringOrNullM1b("module_id"),
        getStringOrNullM1b("manifest_json"),
        getStringOrNullM1b("title"),
        getStringOrNullM1b("thumbnail_ref"),
        getDouble(requiredColumnIndex("created_at")),
    )

    private fun android.database.Cursor.requiredColumnIndex(name: String): Int =
        getColumnIndex(name).also { check(it >= 0) { "Missing artifact query column: $name" } }

    private fun readGeneratedImage(conversationId: String, seq: Long): GeneratedImage? = sql.query(
        "SELECT * FROM generated_images WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) GeneratedImage(
        it.getString(0), it.getLong(1), it.getStringOrNullM1b("image_path"), it.getString(3), it.getStringOrNullM1b("revised_prompt"),
        it.getStringOrNullM1b("size"), it.getStringOrNullM1b("mime_type"), it.getString(7), it.getStringOrNullM1b("error"),
        it.getStringOrNullM1b("file_ref_id"), it.getDouble(10),
    ) else null }

    private fun fileRefExists(id: String): Boolean = sql.query("SELECT EXISTS(SELECT 1 FROM file_refs WHERE id=?)", arrayOf(id))
        .use { it.moveToFirst(); it.getInt(0) != 0 }

    private fun findFileRef(path: String): FileRefContent? = sql.query(
        "SELECT id,sha256,bytes,mime_type FROM file_refs WHERE relative_path=?", arrayOf(path),
    ).use { c -> if (!c.moveToFirst()) null else FileRefContent(
        c.getString(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("sha256")),
        c.getLong(c.getColumnIndexOrThrow("bytes")),
        c.getStringOrNullM1b("mime_type"),
    ) }

    private fun ensureFileRef(path: String, sha256: String, bytes: Long, mimeType: String, nowMillis: Long): String {
        val existing = sql.query("SELECT id,sha256,bytes,mime_type FROM file_refs WHERE relative_path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getStringOrNullM1b("mime_type")) else null
        }
        if (existing != null) {
            val storedSha256 = existing[1].orEmpty()
            if (storedSha256.isNotEmpty() && sha256.isNotEmpty() && storedSha256 != sha256 || existing[2] != bytes.toString())
                throw M1bAbort("file_reference_content_conflict")
            val id = existing[0]!!
            // An empty digest means the source did not know it. It is compatible with a known
            // digest and can be completed later without changing the file identity.
            val resolvedSha256 = if (storedSha256.isEmpty()) sha256 else storedSha256
            sql.execSQL("UPDATE file_refs SET sha256=?,mime_type=?,last_referenced_at=?,not_before=NULL,attempts=0,generation=generation+1 WHERE id=?",
                arrayOf(resolvedSha256, mimeType, nowMillis, id))
            return id
        }
        val id = UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO file_refs(id,sha256,relative_path,bytes,mime_type,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,0,0)",
            arrayOf(id, sha256, path, bytes, mimeType, nowMillis))
        return id
    }

    private suspend fun <T> readSnapshot(block: suspend () -> T): T =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { block() }
        }
}

private fun android.database.Cursor.getLongOrNullM1b(name: String): Long? =
    getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }

package com.jarvys.agent

/** Format-neutral timeline data resolved by a presentation module. */
data class PresentationArtifact(
    val id: String,
    val artifactType: String,
    val moduleId: String?,
    val name: String,
    val title: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val relativePath: String?,
    val sha256: String?,
    val manifestJson: String?,
    val thumbnailRef: String?,
    /** Storage classification retained for transfer compatibility; presenters use module/type. */
    val storageKind: String,
) {
    /** Generated images use a reserved artifact id prefix and IMAGE storage classification. */
    val isGeneratedImage: Boolean
        get() = storageKind.equals("IMAGE", ignoreCase = true) && id.startsWith("generated:")
}

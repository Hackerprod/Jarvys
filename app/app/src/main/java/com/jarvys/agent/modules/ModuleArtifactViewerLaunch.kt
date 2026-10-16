package com.jarvys.agent.modules

/** Opaque preview callback value used to route a delivered module artifact into the visible host. */
object ModuleArtifactViewerLaunch {
    private const val PREFIX = "module-artifact-viewer:"
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    @JvmStatic
    fun token(artifactId: String): String? = artifactId.takeIf(uuid::matches)?.let { PREFIX + it }

    @JvmStatic
    fun artifactIdFromToken(value: String): String? = value.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.takeIf(uuid::matches)
}

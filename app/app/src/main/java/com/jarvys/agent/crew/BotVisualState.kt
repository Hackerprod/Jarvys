package com.jarvys.agent.crew

/** Visual-only projection; manager states and wait reasons remain the source of truth. */
data class BotVisualState(val mode: Mode, val waitingReason: String = "") {
    enum class Mode { IDLE, QUEUED, RUNNING, WAITING_PROVIDER, WAITING_USER, DONE, ERROR, INTERRUPTED }

    val active: Boolean get() = mode in setOf(Mode.QUEUED, Mode.RUNNING, Mode.WAITING_PROVIDER, Mode.WAITING_USER)
    val terminal: Boolean get() = !active

    companion object {
        @JvmStatic
        fun from(status: String?, waitingReason: String?): BotVisualState {
            val reason = waitingReason.orEmpty()
            val normalizedReason = reason.lowercase().trim()
            val mode = when (status?.uppercase()) {
                "QUEUED" -> Mode.QUEUED
                "RUNNING" -> Mode.RUNNING
                "WAITING" -> if (normalizedReason.contains("provider") || normalizedReason.contains("proveedor")
                    || normalizedReason.contains("limit") || normalizedReason.contains("límite")
                    || normalizedReason.contains("limite")) Mode.WAITING_PROVIDER else Mode.WAITING_USER
                "DONE", "COMPLETED", "SYNTHESIZED" -> Mode.DONE
                "FAILED", "ERROR" -> Mode.ERROR
                "STOPPED", "INTERRUPTED" -> Mode.INTERRUPTED
                "IDLE" -> Mode.IDLE
                else -> Mode.IDLE
            }
            return BotVisualState(mode, reason)
        }
    }
}

/** Stable role signature for the drawn head silhouette and facial layout. */
data class BotAvatarDesign(
    val sides: Int,
    val rotationDegrees: Float,
    val antennaStyle: Int,
    val eyeSpacing: Float,
) {
    companion object {
        @JvmStatic
        fun forRole(roleId: String?): BotAvatarDesign {
            val role = roleId.orEmpty().lowercase().trim()
            return when (role) {
                CrewRoleTemplates.EXPLORER, "explorer" -> BotAvatarDesign(5, -90f, 0, 0.28f)
                CrewRoleTemplates.ANALYST, "analyst" -> BotAvatarDesign(6, -90f, 1, 0.34f)
                CrewRoleTemplates.CRITIC, "critic" -> BotAvatarDesign(3, -90f, 2, 0.30f)
                CrewRoleTemplates.WRITER, "writer" -> BotAvatarDesign(4, 45f, 0, 0.38f)
                CrewRoleTemplates.OPERATOR, "operator" -> BotAvatarDesign(8, -90f, 2, 0.32f)
                "captain", "chief" -> BotAvatarDesign(7, -90f, 1, 0.36f)
                else -> {
                    val hash = role.fold(0x811c9dc5.toInt()) { value, character ->
                        (value xor character.code) * 0x01000193
                    }.toLong() and 0xffffffffL
                    BotAvatarDesign(
                        sides = 5 + (hash % 4).toInt(),
                        rotationDegrees = ((hash ushr 3) % 360).toFloat(),
                        antennaStyle = ((hash ushr 11) % 3).toInt(),
                        eyeSpacing = 0.28f + ((hash ushr 16) % 6).toInt() * 0.02f,
                    )
                }
            }
        }
    }
}

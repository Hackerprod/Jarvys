package com.jarvys.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable digest for comparing exact action inputs; it carries no authority or saved state. */
internal object ExactActionBinding {
    @JvmStatic fun digest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

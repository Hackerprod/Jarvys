package com.jarvys.agent.chatdb

import java.text.Normalizer

/** Local descriptive metadata, never a namespace ticket or a filesystem permission. */
data class MemoryProjectAlias(val kind: String, val key: String, val project: String)
data class MemoryProjectAliasResolution(
    val project: String,
    val normalizedKey: String?,
    val normalizedCwd: String?,
    val keyAlias: MemoryProjectAlias?,
    val cwdAlias: MemoryProjectAlias?,
    val conflict: Boolean,
) {
    val matched: Boolean get() = keyAlias != null || cwdAlias != null
}
class MemoryProjectAliasCorruptException : IllegalStateException("memory_alias_corrupt")

object MemoryProjectAliases {
    private fun unicode(raw: String) {
        var index = 0
        while (index < raw.length) {
            val c = raw[index++]
            if (Character.isHighSurrogate(c)) {
                require(index < raw.length && Character.isLowSurrogate(raw[index])) { "memory_alias_invalid_unicode" }
                index++
            } else require(!Character.isLowSurrogate(c)) { "memory_alias_invalid_unicode" }
        }
    }
    @JvmStatic fun key(raw: String): String {
        unicode(raw)
        val trimmed = MemoryObservationContent.trimSpace(raw)
        require(trimmed.isNotEmpty() && trimmed.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) { "memory_alias_invalid_key" }
        val nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        val lower = buildString {
            var offset = 0
            while (offset < nfc.length) {
                val cp = nfc.codePointAt(offset)
                appendCodePoint(Character.toLowerCase(cp))
                offset += Character.charCount(cp)
            }
        }
        var result = Normalizer.normalize(MemoryObservationContent.trimSpace(lower), Normalizer.Form.NFC)
        while (result.contains("--")) result = result.replace("--", "-")
        while (result.contains("__")) result = result.replace("__", "_")
        return result
    }
    @JvmStatic fun cwd(raw: String): String {
        unicode(raw)
        require(MemoryObservationContent.trimSpace(raw).isNotEmpty() && '\u0000' !in raw && raw.startsWith('/')) { "memory_alias_invalid_cwd" }
        val parts = ArrayList<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }
    @JvmStatic fun normalize(kind: String, raw: String): String = when (kind) {
        "key" -> key(raw)
        "cwd" -> cwd(raw)
        else -> throw IllegalArgumentException("memory_alias_invalid_kind")
    }
}

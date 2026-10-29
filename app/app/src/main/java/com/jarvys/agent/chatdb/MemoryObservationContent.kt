package com.jarvys.agent.chatdb

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** Canonical observation strings only; never rewrites transcript or technical evidence. */
object MemoryObservationContent {
    const val LIMIT_BYTES = 50_000
    const val TRUNCATION_MARKER = "... [truncated]"
    data class Prepared(val content: String, val truncated: Boolean, val originalBytes: Int, val limitBytes: Int = LIMIT_BYTES)
    private val privateTags = Pattern.compile("<private>.*?</private>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private fun space(cp: Int) = cp in 9..13 || cp == 32 || cp == 0x85 || cp == 0xa0 || cp == 0x1680 ||
        cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000
    @JvmStatic fun trimSpace(value: String): String {
        var start=0; var end=value.length
        while(start<end && space(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start))
        while(end>start && space(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end))
        return value.substring(start,end)
    }
    @JvmStatic fun prepareTitle(raw: String): String = trimSpace(privateTags.matcher(raw).replaceAll("[REDACTED]"))
    @JvmStatic fun prepareContent(raw: String): Prepared {
        val value=prepareTitle(raw)
        val bytes=value.toByteArray(StandardCharsets.UTF_8)
        if(bytes.size<=LIMIT_BYTES) return Prepared(value,false,bytes.size)
        var end=LIMIT_BYTES
        while(end>0 && (bytes[end].toInt() and 0xc0)==0x80) end--
        return Prepared(String(bytes,0,end,StandardCharsets.UTF_8)+TRUNCATION_MARKER,true,bytes.size)
    }
    fun replace(content: String, find: String, replacement: String): String {
        require(find.toByteArray(Charsets.UTF_8).size<=LIMIT_BYTES && replacement.toByteArray(Charsets.UTF_8).size<=LIMIT_BYTES) { "memory_replace_input_too_large" }
        if(find.isEmpty()) return content
        val marker=if(content.endsWith(TRUNCATION_MARKER)) TRUNCATION_MARKER else ""
        val prefix=if(marker.isEmpty()) content else content.dropLast(marker.length)
        if(!prefix.contains(find)) return content
        val oldBytes=prefix.toByteArray(Charsets.UTF_8).size
        require(oldBytes<=LIMIT_BYTES) { "memory_replace_existing_content_too_large" }
        var occurrences=0L;var offset=0
        while(true) { val index=prefix.indexOf(find,offset);if(index<0) break;occurrences++;offset=index+find.length }
        val growth=replacement.toByteArray(Charsets.UTF_8).size.toLong()-find.toByteArray(Charsets.UTF_8).size
        require(growth<=0 || occurrences*growth<=LIMIT_BYTES-oldBytes) { "memory_replace_result_too_large" }
        val result=prepareTitle(prefix.replace(find,replacement))
        require(result.isNotEmpty()) { "memory_content_required" }
        require(result.toByteArray(Charsets.UTF_8).size<=LIMIT_BYTES) { "memory_replace_result_too_large" }
        return result+marker
    }
    /** Hash final stored body, not source text. Deliberately no NFC or locale case folding. */
    @JvmStatic fun normalizedHash(content: String): String {
        val normalized = buildString {
            var offset = 0
            var pendingSpace = false
            while (offset < content.length) {
                val cp = content.codePointAt(offset)
                if (space(cp)) pendingSpace = isNotEmpty()
                else {
                    if (pendingSpace) append(' ')
                    pendingSpace = false
                    appendCodePoint(Character.toLowerCase(cp))
                }
                offset += Character.charCount(cp)
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @JvmStatic fun dedupeWindowExpression(durationMillis: Long): String {
        val effective = if (durationMillis <= 0) 900_000L else durationMillis
        return "-${(effective / 60_000L).coerceAtLeast(1L)} minutes"
    }

    /** Raw identity: upstream deliberately truncates bytes, including a partial final UTF-8 rune. */
    @JvmStatic fun normalizeTopicBytes(raw: String?): ByteArray? {
        if (raw == null) return null
        val value = fields(simpleLower(raw)).joinToString("-")
        return value.takeIf(String::isNotEmpty)?.toByteArray(Charsets.UTF_8)?.let { it.copyOf(minOf(120, it.size)) }
    }

    /** Display only. Callers must never round-trip this value into an identity comparison. */
    fun topicDisplay(bytes: ByteArray?): String? = bytes?.let { String(it, Charsets.UTF_8) }
    fun topicIsUtf8(bytes: ByteArray?): Boolean? = bytes?.let {
        try { strictTopic(it); true } catch (_: java.nio.charset.CharacterCodingException) { false }
    }
    fun strictTopic(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    fun topicBase64(bytes: ByteArray?): String? = bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    fun sameTopic(left: ByteArray?, right: ByteArray?): Boolean =
        if (left == null || right == null) left == null && right == null else left.contentEquals(right)

    private fun simpleLower(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) { val cp = raw.codePointAt(i); appendCodePoint(Character.toLowerCase(cp)); i += Character.charCount(cp) }
    }
    internal fun fields(raw: String): List<String> {
        val parts = ArrayList<String>(); var start = -1; var index = 0
        while (index < raw.length) {
            val cp = raw.codePointAt(index)
            if (space(cp)) { if (start >= 0) { parts.add(raw.substring(start, index)); start = -1 } }
            else if (start < 0) start = index
            index += Character.charCount(cp)
        }
        if (start >= 0) parts.add(raw.substring(start))
        return parts
    }
    private fun topicSegment(raw: String): String = fields(simpleLower(trimSpace(raw)).replace(Regex("[^a-z0-9]+"), " "))
        .joinToString("-").take(100)

    /** Pure pinned-upstream suggestion, not a normalized stored key or an admission to save. */
    @JvmStatic fun suggestTopicKey(type: String, title: String, content: String): String {
        val t = trimSpace(simpleLower(type))
        val aliases = listOf(
            "architecture" to listOf("architecture", "design", "adr", "refactor"),
            "bug" to listOf("bug", "bugfix", "fix", "incident", "hotfix"),
            "decision" to listOf("decision"), "pattern" to listOf("pattern", "convention", "guideline"),
            "config" to listOf("config", "setup", "infra", "infrastructure", "ci"),
            "discovery" to listOf("discovery", "investigation", "root_cause", "root-cause"),
            "learning" to listOf("learning", "learn"), "session" to listOf("session_summary"))
        val text = simpleLower("$title $content")
        val keywords = listOf(
            "bug" to listOf("bug", "fix", "panic", "error", "crash", "regression", "incident", "hotfix"),
            "architecture" to listOf("architecture", "design", "adr", "boundary", "hexagonal", "refactor"),
            "decision" to listOf("decision", "tradeoff", "chose", "choose", "decide"),
            "pattern" to listOf("pattern", "convention", "naming", "guideline"),
            "config" to listOf("config", "setup", "environment", "env", "docker", "pipeline"),
            "discovery" to listOf("discovery", "investigate", "investigation", "found", "root cause"),
            "learning" to listOf("learned", "learning"))
        val family = aliases.firstOrNull { t in it.second }?.first
            ?: keywords.firstOrNull { (_, words) -> words.any(text::contains) }?.first
            ?: if (t.isNotEmpty() && t != "manual") topicSegment(t) else "topic"
        var source = prepareTitle(title)
        var segment = topicSegment(source)
        if (segment.isEmpty()) { source = fields(prepareTitle(content)).take(8).joinToString(" "); segment = topicSegment(source) }
        if (segment.isEmpty()) segment = "general"
        segment = segment.removePrefix("$family-")
        if (segment.isEmpty() || segment == family) segment = "general"
        if (source.any { it.code > 127 }) {
            val suffix = "-u-" + java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            segment = segment.take(minOf(100, 120 - family.length - 1) - suffix.length) + suffix
        }
        return "$family/$segment"
    }
}

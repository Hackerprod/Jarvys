package com.jarvys.agent.skills

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class SkillFileLink(val skillId: String, val relativePath: String)

object SkillFileLinkParser {
    private val skillIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun parse(raw: String): SkillFileLink? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("jarvys", ignoreCase = true))
        require(uri.rawAuthority == "skills" && uri.rawUserInfo == null && uri.port == -1)
        require(uri.rawQuery == null && uri.rawFragment == null)
        val rawPath = uri.rawPath ?: return null
        require(rawPath.startsWith("/"))
        val rawSegments = rawPath.removePrefix("/").split('/')
        require(rawSegments.size >= 2 && rawSegments.none(String::isEmpty))
        val segments = rawSegments.map { segment ->
            val decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            require(decoded.isNotEmpty() && decoded != "." && decoded != "..")
            require('/' !in decoded && '\\' !in decoded && '\u0000' !in decoded)
            decoded
        }
        val skillId = segments.first()
        require(skillIdPattern.matches(skillId))
        SkillFileLink(skillId, segments.drop(1).joinToString("/"))
    }.getOrNull()
}

package com.jarvys.agent

/** Export-only conservative streaming detector. State is bounded, independent of field length. */
internal class MemoryExportSecretGuard {
    private data class Token(val kind: Int, val stage: Int = 0, val count: Int = 0)
    private var suffix = ""
    private var tokens = emptySet<Token>()
    private var assignment: String? = null
    private var assignmentExempt = false
    private var assignmentLength = 0
    private val assignments = Regex("(?:password|passwd|api[_ -]?key|secret) ?[:=] ?$")
    private fun blocked(): Nothing = throw IllegalArgumentException("memory_export_secret_blocked")
    fun accept(value: Char) {
        val ch = value.lowercaseChar()
        val short = if (ch.isWhitespace()) ' ' else ch
        val next = HashSet<Token>()
        for (token in tokens) {
            when (token.kind) {
                0 -> if (ch in 'a'..'z' || ch in '0'..'9') {
                    if (token.count + 1 >= 16) blocked()
                    next += token.copy(count = token.count + 1)
                }
                1, 2 -> if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-') {
                    if (token.count + 1 >= 20) blocked()
                    next += token.copy(count = token.count + 1)
                }
                3 -> if (token.stage == 0 && ch.isWhitespace()) next += token
                    else if (ch in 'a'..'z' || ch in '0'..'9' || ch in "._~+/=-") {
                        if (token.count + 1 >= 16) blocked()
                        next += token.copy(stage = 1, count = token.count + 1)
                    }
                4 -> if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-') {
                    val count = (token.count + 1).coerceAtMost(8)
                    if (token.stage == 2 && count >= 8) blocked()
                    next += token.copy(count = count)
                } else if (ch == '.' && token.count >= 8 && token.stage < 2) next += Token(4, token.stage + 1)
            }
        }
        assignment?.let { prefix ->
            if (ch.isWhitespace() && assignmentLength == 0) Unit
            else if (ch.isWhitespace() || ch == ',' || ch == ';') {
                finishAssignment(); assignment = null
            } else {
                assignmentLength = (assignmentLength + 1).coerceAtMost(4)
                val updated = if (prefix.length < 13) prefix + ch else prefix
                assignment = updated
                val exemptions = listOf("<redacted>", "redacted", "example", "placeholder", "your")
                assignmentExempt = exempt(updated, false)
                val mayBeExempt = exemptions.any { it.startsWith(updated) }
                if (assignmentLength >= 4 && !assignmentExempt && !mayBeExempt) blocked()
            }
        }
        if (!(short == ' ' && suffix.endsWith(' '))) suffix = (suffix + short).takeLast(96)
        fun starts(prefix: String): Boolean {
            if (!suffix.endsWith(prefix)) return false
            val before = suffix.length - prefix.length - 1
            return before < 0 || !(suffix[before].isLetterOrDigit() || suffix[before] == '_')
        }
        if (suffix.endsWith("-----begin private key-----") || suffix.endsWith("-----begin rsa private key-----") ||
            suffix.endsWith("-----begin ec private key-----") || suffix.endsWith("-----begin openssh private key-----")) blocked()
        if (starts("akia")) next += Token(0)
        if (listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_").any(::starts)) next += Token(1)
        if (starts("sk-")) next += Token(2)
        if (starts("bearer ")) next += Token(3)
        if (starts("eyj")) next += Token(4)
        if (assignments.containsMatchIn(suffix)) {
            // Repeated whitespace must not reset an already accumulating value.
            if (short == ':' || short == '=' || assignment == null) {
                assignment = ""; assignmentLength = 0; assignmentExempt = false
            }
        }
        tokens = next
    }
    private fun exempt(prefix: String, ended: Boolean): Boolean =
        listOf("<redacted>", "your").any(prefix::startsWith) ||
            listOf("redacted", "example", "placeholder").any { word ->
                prefix.startsWith(word) && (prefix.length == word.length && ended ||
                    prefix.length > word.length && !(prefix[word.length].isLetterOrDigit() || prefix[word.length] == '_'))
            }
    private fun finishAssignment() {
        if (assignmentLength >= 4 && !assignmentExempt && !exempt(assignment.orEmpty(), true)) blocked()
    }
    fun finish() { if (assignment != null) finishAssignment() }
}

package com.jarvys.agent

/** Creates a speakable reading of a message while leaving the stored Markdown untouched. */
internal fun assistantSpeechText(markdown: String): String {
    val prose = StringBuilder(markdown.length)
    var inFence = false
    var fenceMark = '\u0000'
    var fenceWidth = 0

    for (line in markdown.lineSequence()) {
        val indentation = line.takeWhile { it == ' ' || it == '\t' }.length
        val rest = line.drop(indentation)
        val mark = rest.firstOrNull()
        val run = if (mark == '`' || mark == '~') rest.takeWhile { it == mark }.length else 0
        if (run >= 3 && indentation <= 3) {
            if (!inFence) {
                inFence = true
                fenceMark = mark!!
                fenceWidth = run
            } else if (mark == fenceMark && run >= fenceWidth && rest.drop(run).isBlank()) {
                inFence = false
            }
            continue
        }
        if (inFence) continue
        prose.append(stripLineLead(line)).append('\n')
    }

    return collapseSpeakableWhitespace(stripInlineMarkup(prose.toString()))
}

/** Removes only line-leading Markdown structure; an asterisk in ordinary prose stays literal. */
private fun stripLineLead(line: String): String {
    var index = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
    var cursor = index
    while (cursor < line.length && line[cursor] == '#') cursor++
    if (cursor > index && cursor < line.length && line[cursor].isWhitespace()) {
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        return line.substring(cursor)
    }

    // Quote bars can be repeated for nested quoted passages.
    while (cursor < line.length && line[cursor] == '>') {
        cursor++
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    }
    if (cursor > index) index = cursor

    cursor = index
    if (cursor < line.length && (line[cursor] == '-' || line[cursor] == '+' || line[cursor] == '*')
        && cursor + 1 < line.length && line[cursor + 1].isWhitespace()) {
        cursor += 2
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        return line.substring(cursor)
    }

    var digitsEnd = cursor
    while (digitsEnd < line.length && line[digitsEnd].isDigit()) digitsEnd++
    if (digitsEnd > cursor && digitsEnd + 1 < line.length
        && (line[digitsEnd] == '.' || line[digitsEnd] == ')') && line[digitsEnd + 1].isWhitespace()) {
        cursor = digitsEnd + 2
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        return line.substring(cursor)
    }
    return line.substring(index)
}

/** A small scanner handles inline constructs without changing text outside matched delimiters. */
private fun stripInlineMarkup(source: String): String {
    val output = StringBuilder(source.length)
    var cursor = 0
    while (cursor < source.length) {
        if (source[cursor] == '`') {
            val markerWidth = runLength(source, cursor, '`')
            val close = findRun(source, cursor + markerWidth, '`', markerWidth)
            if (close >= 0) {
                cursor = close + markerWidth
                continue
            }
            output.append(source, cursor, cursor + markerWidth)
            cursor += markerWidth
            continue
        }

        val image = source[cursor] == '!' && cursor + 1 < source.length && source[cursor + 1] == '['
        val openingBracket = if (image) cursor + 1 else cursor
        if (source[openingBracket] == '[') {
            val closeBracket = matchingDelimiter(source, openingBracket, '[', ']')
            if (closeBracket >= 0 && closeBracket + 1 < source.length && source[closeBracket + 1] == '(') {
                val closeParen = matchingDelimiter(source, closeBracket + 1, '(', ')')
                if (closeParen >= 0) {
                    val label = stripInlineMarkup(source.substring(openingBracket + 1, closeBracket))
                    // For images, speak the alt text as an accessibility description; never speak the URL.
                    output.append(label)
                    cursor = closeParen + 1
                    continue
                }
            }
        }

        val emphasisWidth = emphasisRun(source, cursor)
        if (emphasisWidth > 0) {
            val marker = source[cursor]
            val close = findEmphasisClose(source, cursor + emphasisWidth, marker, emphasisWidth)
            if (close >= 0) {
                output.append(stripInlineMarkup(source.substring(cursor + emphasisWidth, close)))
                cursor = close + emphasisWidth
                continue
            }
        }
        if (source[cursor] == '|') output.append(' ') else output.append(source[cursor])
        cursor++
    }
    return output.toString()
}

private fun matchingDelimiter(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var cursor = start
    while (cursor < text.length) {
        when (text[cursor]) {
            '\\' -> cursor++
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return cursor
            }
        }
        cursor++
    }
    return -1
}

private fun emphasisRun(text: String, start: Int): Int {
    val marker = text[start]
    if (marker != '*' && marker != '_' && marker != '~') return 0
    val width = runLength(text, start, marker)
    if (width > 2) return 0
    // A delimiter surrounded by spaces is treated as literal punctuation, not formatting.
    if (start + width >= text.length || text[start + width].isWhitespace()) return 0
    if (start > 0 && text[start - 1].isWhitespace()) return width
    return width
}

private fun findEmphasisClose(text: String, start: Int, marker: Char, width: Int): Int {
    var cursor = start
    while (cursor < text.length) {
        if (text[cursor] == '\\') {
            cursor += 2
            continue
        }
        if (text[cursor] == marker && runLength(text, cursor, marker) == width
            && cursor + width < text.length && !text[cursor - 1].isWhitespace()) return cursor
        cursor++
    }
    return -1
}

private fun runLength(text: String, start: Int, character: Char): Int {
    var cursor = start
    while (cursor < text.length && text[cursor] == character) cursor++
    return cursor - start
}

private fun findRun(text: String, start: Int, character: Char, width: Int): Int {
    var cursor = start
    while (cursor < text.length) {
        if (text[cursor] == character && runLength(text, cursor, character) == width) return cursor
        cursor++
    }
    return -1
}

private fun collapseSpeakableWhitespace(text: String): String {
    val result = StringBuilder(text.length)
    var pendingSpace = false
    for (character in text) {
        if (character.isWhitespace() || character == '\u00a0') {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            result.append(character)
            pendingSpace = false
        }
    }
    return result.toString().trim()
}

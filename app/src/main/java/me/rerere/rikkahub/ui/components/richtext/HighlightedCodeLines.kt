package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString

/** Split only after lexing the complete block, preserving spans and empty lines. */
internal fun splitHighlightedCodeLines(code: AnnotatedString): List<AnnotatedString> {
    val result = mutableListOf<AnnotatedString>()
    var start = 0
    var index = 0
    while (index < code.length) {
        val character = code[index]
        if (character == '\n' || character == '\r') {
            result.add(code.subSequence(start, index))
            if (character == '\r' && index + 1 < code.length && code[index + 1] == '\n') index++
            start = index + 1
        }
        index++
    }
    result.add(code.subSequence(start, code.length))
    return result
}

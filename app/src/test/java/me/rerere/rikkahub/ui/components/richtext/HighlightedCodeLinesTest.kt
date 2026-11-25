package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import me.rerere.bettertext.highlight.CodeHighlighter
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightedCodeLinesTest {
    private val highlighter = CodeHighlighter()

    @Test
    fun `python triple quoted string retains cross-line string style`() {
        assertMultilineStyle("python", "value = \"\"\"first\ninside string\nlast\"\"\"", AtomOneDarkPalette.string)
    }

    @Test
    fun `javascript template string retains cross-line string style`() {
        assertMultilineStyle("javascript", "const value = `first\ninside string\nlast`;", AtomOneDarkPalette.string)
    }

    @Test
    fun `multiline comment retains comment style on continuation`() {
        assertMultilineStyle("javascript", "/* first\ninside comment\nlast */", AtomOneDarkPalette.comment)
    }

    @Test
    fun `preserves empty trailing lines and all newline formats`() {
        assertEquals(listOf("one", "", "two", "three", ""),
            splitHighlightedCodeLines(AnnotatedString("one\r\n\r\ntwo\rthree\n")).map { it.text })
        assertEquals(listOf(""), splitHighlightedCodeLines(AnnotatedString("")).map { it.text })
    }

    @Test
    fun `long blocks retain all lines without nested lazy containers`() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val lines = splitHighlightedCodeLines(AnnotatedString(code))
        assertEquals(1000, lines.size)
        assertEquals(code, lines.joinToString("\n") { it.text })
    }

    private fun assertMultilineStyle(language: String, code: String, expectedColor: androidx.compose.ui.graphics.Color) {
        val highlighted = highlighter.highlightToAnnotatedString(code, language, AtomOneDarkPalette)
        val lines = splitHighlightedCodeLines(highlighted)
        assertEquals(code.lines(), lines.map { it.text })
        assertTrue("Continuation must retain its whole-block lexical style", lines[1].spanStyles.any {
            it.start == 0 && it.end >= lines[1].length && it.item.color == expectedColor
        })
    }
}

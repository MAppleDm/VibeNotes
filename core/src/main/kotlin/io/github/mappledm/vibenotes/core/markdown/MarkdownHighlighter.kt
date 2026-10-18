package io.github.mappledm.vibenotes.core.markdown

enum class HighlightKind {
    HEADING_1, HEADING_2, HEADING_3, BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK,
    QUOTE, LINK, TAG, LIST_MARKER, TASK_DONE, SYNTAX
}

data class Highlight(val start: Int, val end: Int, val kind: HighlightKind)

/**
 * Computes style ranges for the editing mode. The source text is not changed —
 * markers stay visible but are dimmed ([HighlightKind.SYNTAX]), which keeps
 * cursor positions identical to the file content.
 */
object MarkdownHighlighter {
    private val heading = Regex("""^(#{1,6})\s.*$""")
    private val listMarker = Regex("""^(\s*)([-*+]|\d+[.)])(\s+\[[ xX]])?\s""")
    private val inline = listOf(
        Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""") to HighlightKind.BOLD,
        Regex("""(?<![*\w])([*_])(?=[^\s*_])(.+?)(?<=[^\s*_])\1(?![*\w])""") to HighlightKind.ITALIC,
        Regex("""(~~)(.+?)\1""") to HighlightKind.STRIKE,
        Regex("""(`)([^`\n]+)\1""") to HighlightKind.CODE,
    )
    private val wikiLink = Regex("""\[\[[^\]\n]+]]""")
    private val mdLink = Regex("""!?\[[^\]\n]*]\([^)\n]*\)""")
    private val tag = Regex("""(?<![\p{L}\p{N}/#&])#[\p{L}_][\p{L}\p{N}_/\-]*""")

    fun highlight(text: String): List<Highlight> {
        val result = mutableListOf<Highlight>()
        var offset = 0
        var inFence = false
        for (line in text.split('\n')) {
            val lineStart = offset
            val lineEnd = offset + line.length
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    inFence = !inFence
                    result += Highlight(lineStart, lineEnd, HighlightKind.CODE_BLOCK)
                }
                inFence -> result += Highlight(lineStart, lineEnd, HighlightKind.CODE_BLOCK)
                else -> highlightLine(line, lineStart, result)
            }
            offset = lineEnd + 1
        }
        return result
    }

    private fun highlightLine(line: String, base: Int, out: MutableList<Highlight>) {
        heading.matchEntire(line)?.let { m ->
            val level = m.groupValues[1].length
            val kind = when (level) {
                1 -> HighlightKind.HEADING_1
                2 -> HighlightKind.HEADING_2
                else -> HighlightKind.HEADING_3
            }
            out += Highlight(base, base + line.length, kind)
            out += Highlight(base, base + level, HighlightKind.SYNTAX)
        }
        if (line.trimStart().startsWith(">")) {
            out += Highlight(base, base + line.length, HighlightKind.QUOTE)
        }
        listMarker.find(line)?.let { m ->
            out += Highlight(base + m.range.first, base + m.range.last + 1, HighlightKind.LIST_MARKER)
            val task = m.groupValues[3]
            if (task.contains('x', ignoreCase = true)) {
                out += Highlight(base + m.range.last + 1, base + line.length, HighlightKind.TASK_DONE)
            }
        }
        for ((regex, kind) in inline) {
            regex.findAll(line).forEach { m ->
                val markerLen = m.groupValues[1].length
                out += Highlight(base + m.range.first, base + m.range.last + 1, kind)
                out += Highlight(base + m.range.first, base + m.range.first + markerLen, HighlightKind.SYNTAX)
                out += Highlight(base + m.range.last + 1 - markerLen, base + m.range.last + 1, HighlightKind.SYNTAX)
            }
        }
        wikiLink.findAll(line).forEach { m -> out += Highlight(base + m.range.first, base + m.range.last + 1, HighlightKind.LINK) }
        mdLink.findAll(line).forEach { m -> out += Highlight(base + m.range.first, base + m.range.last + 1, HighlightKind.LINK) }
        tag.findAll(line).forEach { m -> out += Highlight(base + m.range.first, base + m.range.last + 1, HighlightKind.TAG) }
    }
}

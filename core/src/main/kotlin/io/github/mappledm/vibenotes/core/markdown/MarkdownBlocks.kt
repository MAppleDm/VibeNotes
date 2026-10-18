package io.github.mappledm.vibenotes.core.markdown

/** Block-level structure used by the read mode. [line] is the 0-based source line. */
sealed interface MdBlock {
    val line: Int

    data class Heading(val level: Int, val text: String, override val line: Int) : MdBlock
    data class Paragraph(val text: String, override val line: Int) : MdBlock
    data class ListItem(
        val text: String,
        val depth: Int,
        val ordered: Boolean,
        val number: Int,
        /** null for a plain item, true/false for a task item. */
        val checked: Boolean?,
        override val line: Int,
    ) : MdBlock
    data class Quote(val text: String, override val line: Int) : MdBlock
    data class Code(val language: String, val code: String, override val line: Int) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, override val line: Int) : MdBlock
    data class Rule(override val line: Int) : MdBlock
}

/**
 * Small CommonMark-ish block parser. It intentionally covers what notes use daily
 * (headings, lists, tasks, quotes, fenced code, tables) instead of the full spec.
 */
object MarkdownBlocks {
    private val headingRegex = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val listRegex = Regex("""^(\s*)([-*+]|(\d+)[.)])\s+(?:\[([ xX])]\s+)?(.*)$""")
    private val ruleRegex = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(source: String): List<MdBlock> {
        val (frontmatter, body) = Frontmatter.split(source.replace("\r\n", "\n"))
        val lineOffset = if (frontmatter.raw.isEmpty()) 0 else frontmatter.raw.count { it == '\n' }
        val lines = body.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        val paragraph = StringBuilder()
        var paragraphLine = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.toString(), paragraphLine)
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val sourceLine = i + lineOffset
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flushParagraph()
                    val fence = trimmed.takeWhile { it == '`' || it == '~' }
                    val language = trimmed.removePrefix(fence).trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        code += lines[i]
                        i++
                    }
                    blocks += MdBlock.Code(language, code.joinToString("\n"), sourceLine)
                }
                line.isBlank() -> flushParagraph()
                headingRegex.matches(line) -> {
                    flushParagraph()
                    val m = headingRegex.matchEntire(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2], sourceLine)
                }
                ruleRegex.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule(sourceLine)
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    blocks += MdBlock.Quote(quote.joinToString("\n"), sourceLine)
                    continue
                }
                listRegex.matches(line) -> {
                    flushParagraph()
                    val m = listRegex.matchEntire(line)!!
                    val indent = m.groupValues[1].replace("\t", "    ").length
                    val number = m.groupValues[3]
                    blocks += MdBlock.ListItem(
                        text = m.groupValues[5],
                        depth = indent / 2,
                        ordered = number.isNotEmpty(),
                        number = number.toIntOrNull() ?: 0,
                        checked = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { it != " " },
                        line = sourceLine,
                    )
                }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flushParagraph()
                    val header = splitRow(line)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows += splitRow(lines[i])
                        i++
                    }
                    blocks += MdBlock.Table(header, rows, sourceLine)
                    continue
                }
                else -> {
                    if (paragraph.isEmpty()) paragraphLine = sourceLine else paragraph.append('\n')
                    paragraph.append(line.trimEnd())
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

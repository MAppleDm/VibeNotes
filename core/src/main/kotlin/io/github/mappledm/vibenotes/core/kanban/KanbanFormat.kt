package io.github.mappledm.vibenotes.core.kanban

import io.github.mappledm.vibenotes.core.markdown.Frontmatter
import io.github.mappledm.vibenotes.core.markdown.MarkdownText

/**
 * Reads and writes boards in the Markdown format of the Obsidian Kanban plugin,
 * so the same file can be opened on a PC:
 *
 * ```
 * ---
 *
 * kanban-plugin: board
 *
 * ---
 *
 * ## To do
 *
 * - [ ] Card @{2026-10-16}
 *
 *
 * ## Done
 *
 * **Complete**
 * - [x] Finished card
 *
 *
 * %% kanban:settings
 * ...
 * %%
 * ```
 */
object KanbanFormat {
    const val FRONTMATTER_KEY = "kanban-plugin"
    const val DEFAULT_FRONTMATTER = "---\n\nkanban-plugin: board\n\n---\n"
    const val DEFAULT_SETTINGS = "%% kanban:settings\n```\n{\"kanban-plugin\":\"board\"}\n```\n%%"
    private const val COMPLETE_MARKER = "**Complete**"

    private val cardRegex = Regex("""^[-*+]\s+(?:\[([ xX])]\s?)?(.*)$""")

    fun isBoard(text: String): Boolean = Frontmatter.split(text).first.values.containsKey(FRONTMATTER_KEY)

    /** Preview for the note list: like a note's, without the plugin's @{date} and @@{time} tokens. */
    fun preview(text: String): String = MarkdownText.preview(text.lines().joinToString("\n") { KanbanCard.stripDue(it) })

    fun parse(text: String): KanbanBoard {
        val normalized = text.replace("\r\n", "\n")
        val (frontmatter, body) = Frontmatter.split(normalized)
        val lines = body.split('\n')

        val tailStart = lines.indexOfFirst { line ->
            val t = line.trim()
            t == "***" || t.startsWith("%% kanban:settings")
        }
        val contentLines = if (tailStart >= 0) lines.subList(0, tailStart) else lines
        val tail = if (tailStart >= 0) lines.subList(tailStart, lines.size).joinToString("\n") else ""

        val firstLane = contentLines.indexOfFirst { it.startsWith("## ") }
        val preamble = if (firstLane < 0) contentLines.joinToString("\n") else contentLines.subList(0, firstLane).joinToString("\n") + "\n"

        val lanes = mutableListOf<KanbanLane>()
        if (firstLane >= 0) {
            var title = ""
            var complete = false
            var cards = mutableListOf<KanbanCard>()
            var extra = mutableListOf<String>()
            var current: KanbanCard? = null

            fun flushCard() {
                current?.let { cards += it }
                current = null
            }

            fun flushLane() {
                flushCard()
                lanes += KanbanLane(title, cards, complete, extra)
            }

            for (i in firstLane until contentLines.size) {
                val line = contentLines[i]
                when {
                    line.startsWith("## ") -> {
                        if (i != firstLane) flushLane()
                        title = line.removePrefix("## ").trim()
                        complete = false
                        cards = mutableListOf()
                        extra = mutableListOf()
                    }
                    line.trim() == COMPLETE_MARKER -> {
                        flushCard()
                        complete = true
                    }
                    line.isBlank() -> flushCard()
                    current != null && (line.startsWith("\t") || line.startsWith("  ")) -> {
                        current = current!!.copy(continuation = current!!.continuation + line)
                    }
                    else -> {
                        val match = cardRegex.matchEntire(line)
                        if (match != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                            flushCard()
                            val mark = match.groupValues[1]
                            current = KanbanCard(
                                text = match.groupValues[2].trim(),
                                checked = mark.equals("x", ignoreCase = true),
                            )
                        } else {
                            flushCard()
                            extra += line
                        }
                    }
                }
            }
            flushLane()
        }

        return KanbanBoard(
            lanes = lanes,
            frontmatter = frontmatter.raw.ifEmpty { DEFAULT_FRONTMATTER },
            preamble = preamble,
            tail = tail,
        )
    }

    fun serialize(board: KanbanBoard): String {
        val sb = StringBuilder()
        sb.append(board.frontmatter)
        sb.append(board.preamble)
        for (lane in board.lanes) {
            sb.append("## ").append(lane.title).append("\n\n")
            if (lane.isComplete) sb.append(COMPLETE_MARKER).append('\n')
            for (card in lane.cards) {
                sb.append("- [").append(if (card.checked) 'x' else ' ').append("] ").append(card.text).append('\n')
                for (cont in card.continuation) sb.append(cont).append('\n')
            }
            for (line in lane.extraLines) sb.append(line).append('\n')
            sb.append("\n\n")
        }
        sb.append(board.tail)
        if (board.tail.isNotEmpty() && !board.tail.endsWith("\n")) sb.append('\n')
        return sb.toString()
    }
}

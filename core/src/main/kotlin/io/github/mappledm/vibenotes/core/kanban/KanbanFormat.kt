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
 * ***
 *
 * ## Archive
 *
 * - [x] Archived card
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
    /** The plugin's own (English) title; an existing archive keeps whatever heading it has. */
    const val DEFAULT_ARCHIVE_TITLE = "Archive"
    private const val COMPLETE_MARKER = "**Complete**"
    private const val ARCHIVE_BREAK = "***"
    private const val SETTINGS_START = "%% kanban:settings"

    private val cardRegex = Regex("""^[-*+]\s+(?:\[([ xX])]\s?)?(.*)$""")

    fun isBoard(text: String): Boolean = Frontmatter.split(text).first.values.containsKey(FRONTMATTER_KEY)

    /**
     * Preview for the note list: how many cards each column holds, then the open cards
     * ("Сделать 2 · В работе 1 · Готово 3" / "Позвонить в банк · Отчёт"), without due tokens or plugin markers.
     */
    fun preview(text: String, maxLength: Int = 160): String {
        val board = parse(text)
        if (board.lanes.isEmpty()) return MarkdownText.preview(text.lines().joinToString("\n") { KanbanCard.stripDue(it) }, maxLength)
        val counts = board.lanes.joinToString(" · ") { "${it.title} ${it.cards.size}" }
        val open = board.lanes.filterNot { it.isComplete }.flatMap { it.cards }.filterNot { it.checked }.map { it.title }
        val full = if (open.isEmpty()) counts else counts + "\n" + open.joinToString(" · ")
        return if (full.length > maxLength) full.take(maxLength).trimEnd() + "…" else full
    }

    fun parse(text: String): KanbanBoard {
        val normalized = text.replace("\r\n", "\n")
        val (frontmatter, body) = Frontmatter.split(normalized)
        val lines = body.split('\n')

        val tailStart = lines.indexOfFirst { line ->
            val t = line.trim()
            t == ARCHIVE_BREAK || t.startsWith(SETTINGS_START)
        }
        val contentLines = if (tailStart >= 0) lines.subList(0, tailStart) else lines
        val tailLines = if (tailStart >= 0) lines.subList(tailStart, lines.size) else emptyList()

        val firstLane = contentLines.indexOfFirst { it.startsWith("## ") }
        val preamble = if (firstLane < 0) contentLines.joinToString("\n") else contentLines.subList(0, firstLane).joinToString("\n") + "\n"

        val lanes = mutableListOf<KanbanLane>()
        if (firstLane >= 0) {
            var start = firstLane
            for (i in firstLane + 1..contentLines.size) {
                if (i == contentLines.size || contentLines[i].startsWith("## ")) {
                    lanes += parseLane(contentLines[start].removePrefix("## ").trim(), contentLines.subList(start + 1, i))
                    start = i
                }
            }
        }

        val (archive, tail) = parseArchive(tailLines)
        return KanbanBoard(
            lanes = lanes,
            frontmatter = frontmatter.raw.ifEmpty { DEFAULT_FRONTMATTER },
            preamble = preamble,
            tail = tail,
            archive = archive,
        )
    }

    private fun parseLane(title: String, lines: List<String>): KanbanLane {
        var complete = false
        val cards = mutableListOf<KanbanCard>()
        val extra = mutableListOf<String>()
        var current: KanbanCard? = null

        fun flushCard() {
            current?.let { cards += it }
            current = null
        }

        for (line in lines) {
            val card = current
            when {
                line.trim() == COMPLETE_MARKER -> {
                    flushCard()
                    complete = true
                }
                line.isBlank() -> flushCard()
                card != null && (line.startsWith("\t") || line.startsWith("  ")) -> {
                    current = card.copy(continuation = card.continuation + line)
                }
                else -> {
                    flushCard()
                    val match = cardRegex.matchEntire(line)
                    if (match != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                        current = KanbanCard(
                            text = match.groupValues[2].trim(),
                            checked = match.groupValues[1].equals("x", ignoreCase = true),
                        )
                    } else {
                        extra += line
                    }
                }
            }
        }
        flushCard()
        return KanbanLane(title, cards, complete, extra)
    }

    /**
     * The plugin keeps archived cards after the lanes, under "***" and an "## Archive" heading
     * (the heading follows Obsidian's language, so any heading is accepted and kept).
     * Returns the archive and the rest of the tail (the settings block).
     */
    private fun parseArchive(tailLines: List<String>): Pair<KanbanLane?, String> {
        val raw = tailLines.joinToString("\n")
        if (tailLines.firstOrNull()?.trim() != ARCHIVE_BREAK) return null to raw
        val settings = tailLines.indexOfFirst { it.trim().startsWith(SETTINGS_START) }.let { if (it < 0) tailLines.size else it }
        val body = tailLines.subList(1, settings)
        val heading = body.indexOfFirst { it.isNotBlank() }
        if (heading < 0 || !body[heading].startsWith("## ")) return null to raw
        val archive = parseLane(body[heading].removePrefix("## ").trim(), body.subList(heading + 1, body.size))
        return archive.copy(isComplete = false) to tailLines.subList(settings, tailLines.size).joinToString("\n")
    }

    fun serialize(board: KanbanBoard): String {
        val sb = StringBuilder()
        sb.append(board.frontmatter)
        sb.append(board.preamble)
        for (lane in board.lanes) {
            sb.append("## ").append(lane.title).append("\n\n")
            if (lane.isComplete) sb.append(COMPLETE_MARKER).append('\n')
            appendCards(sb, lane)
            sb.append("\n\n")
        }
        // An emptied archive disappears from the file, as in the plugin.
        board.archive?.takeIf { it.cards.isNotEmpty() || it.extraLines.isNotEmpty() }?.let { archive ->
            sb.append(ARCHIVE_BREAK).append("\n\n## ").append(archive.title).append("\n\n")
            appendCards(sb, archive)
            sb.append('\n')
        }
        sb.append(board.tail)
        if (board.tail.isNotEmpty() && !board.tail.endsWith("\n")) sb.append('\n')
        return sb.toString()
    }

    private fun appendCards(sb: StringBuilder, lane: KanbanLane) {
        for (card in lane.cards) {
            sb.append("- [").append(if (card.checked) 'x' else ' ').append("] ").append(card.text).append('\n')
            for (cont in card.continuation) sb.append(cont).append('\n')
        }
        for (line in lane.extraLines) sb.append(line).append('\n')
    }
}

package io.github.mappledm.vibenotes.core.markdown

/** Text plus selection, independent of any UI toolkit. Selection is [selStart, selEnd). */
data class TextEdit(val text: String, val selStart: Int, val selEnd: Int = selStart)

/**
 * Pure functions behind the editor toolbar and keyboard shortcuts.
 * Every operation returns the new text and where the cursor should be.
 */
object MarkdownEditing {

    private val listPrefix = Regex("""^(\s*)([-*+]\s+\[[ xX]]\s+|[-*+]\s+|(\d+)([.)])\s+)""")

    /** Wraps the selection with [marker] (e.g. "**"), or unwraps if it is already wrapped. */
    fun toggleWrap(edit: TextEdit, marker: String, placeholder: String = ""): TextEdit {
        val start = minOf(edit.selStart, edit.selEnd)
        val end = maxOf(edit.selStart, edit.selEnd)
        val text = edit.text
        val before = text.substring(0, start)
        val selected = text.substring(start, end)
        val after = text.substring(end)

        if (before.endsWith(marker) && after.startsWith(marker)) {
            val newText = before.dropLast(marker.length) + selected + after.drop(marker.length)
            return TextEdit(newText, start - marker.length, end - marker.length)
        }
        if (selected.length >= marker.length * 2 && selected.startsWith(marker) && selected.endsWith(marker)) {
            val inner = selected.substring(marker.length, selected.length - marker.length)
            return TextEdit(before + inner + after, start, start + inner.length)
        }
        val content = selected.ifEmpty { placeholder }
        val newText = before + marker + content + marker + after
        val innerStart = start + marker.length
        return TextEdit(newText, innerStart, innerStart + content.length)
    }

    /** Start (inclusive) and end (exclusive, before '\n') of the lines touched by [start, end]. */
    private fun lineBounds(text: String, start: Int, end: Int): Pair<Int, Int> {
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return lineStart to lineEnd
    }

    /**
     * Toggles a line prefix ("# ", "- ", "- [ ] ", "> ") on every selected line.
     * Existing list/heading prefixes are replaced rather than stacked.
     */
    fun toggleLinePrefix(edit: TextEdit, prefix: String): TextEdit {
        val start = minOf(edit.selStart, edit.selEnd)
        val end = maxOf(edit.selStart, edit.selEnd)
        val (blockStart, blockEnd) = lineBounds(edit.text, start, end)
        val block = edit.text.substring(blockStart, blockEnd)
        val lines = block.split('\n')
        val allHave = lines.filter { it.isNotBlank() }.let { nonBlank -> nonBlank.isNotEmpty() && nonBlank.all { it.startsWith(prefix) } }

        var delta = 0
        var firstDelta = 0
        val newLines = lines.mapIndexed { i, line ->
            val replaced = when {
                allHave -> line.removePrefix(prefix)
                line.isBlank() && lines.size > 1 -> line
                else -> prefix + stripBlockPrefix(line)
            }
            val d = replaced.length - line.length
            if (i == 0) firstDelta = d
            delta += d
            replaced
        }
        val newBlock = newLines.joinToString("\n")
        val newText = edit.text.substring(0, blockStart) + newBlock + edit.text.substring(blockEnd)
        return if (start == end) {
            val pos = (start + firstDelta).coerceIn(blockStart, blockStart + newLines.first().length)
            TextEdit(newText, pos)
        } else {
            TextEdit(newText, blockStart, blockStart + newBlock.length)
        }
    }

    private fun stripBlockPrefix(line: String): String =
        line.replaceFirst(Regex("""^(#{1,6}\s+|>\s?|[-*+]\s+\[[ xX]]\s+|[-*+]\s+|\d+[.)]\s+)"""), "")

    /** Inserts text at the selection (replacing it) and places the cursor after it. */
    fun insert(edit: TextEdit, insertion: String, cursorOffset: Int = insertion.length): TextEdit {
        val start = minOf(edit.selStart, edit.selEnd)
        val end = maxOf(edit.selStart, edit.selEnd)
        val newText = edit.text.substring(0, start) + insertion + edit.text.substring(end)
        return TextEdit(newText, start + cursorOffset)
    }

    fun insertLink(edit: TextEdit): TextEdit {
        val start = minOf(edit.selStart, edit.selEnd)
        val end = maxOf(edit.selStart, edit.selEnd)
        val selected = edit.text.substring(start, end)
        return if (selected.isEmpty()) insert(edit, "[[]]", 2) else insert(edit, "[[$selected]]")
    }

    fun insertCodeBlock(edit: TextEdit): TextEdit {
        val start = minOf(edit.selStart, edit.selEnd)
        val end = maxOf(edit.selStart, edit.selEnd)
        val selected = edit.text.substring(start, end)
        val needsLeadingNewline = start > 0 && edit.text[start - 1] != '\n'
        val lead = if (needsLeadingNewline) "\n" else ""
        val block = "$lead```\n$selected\n```\n"
        return insert(edit, block, lead.length + 4 + selected.length)
    }

    /**
     * Called after the user typed a newline at [cursor] (the '\n' is already in [text]).
     * Continues bullet/numbered/task lists, or ends the list when the item was empty.
     * Returns null when nothing should change.
     */
    fun continueList(text: String, cursor: Int): TextEdit? {
        if (cursor <= 0 || cursor > text.length || text[cursor - 1] != '\n') return null
        val prevLineEnd = cursor - 1
        val prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1) + 1
        val prevLine = text.substring(prevLineStart, prevLineEnd)
        val match = listPrefix.find(prevLine) ?: return null
        val fullPrefix = match.value
        val indent = match.groupValues[1]
        val itemContent = prevLine.substring(fullPrefix.length)

        if (itemContent.isBlank()) {
            // Empty item + Enter: remove the dangling bullet and stop the list.
            val newText = text.substring(0, prevLineStart) + text.substring(cursor)
            return TextEdit(newText, prevLineStart)
        }

        val marker = match.groupValues[2]
        val nextPrefix = when {
            match.groupValues[3].isNotEmpty() -> {
                val n = match.groupValues[3].toInt() + 1
                "$indent$n${match.groupValues[4]} "
            }
            marker.contains('[') -> indent + marker.substring(0, 1) + " [ ] "
            else -> indent + marker
        }
        val newText = text.substring(0, cursor) + nextPrefix + text.substring(cursor)
        return TextEdit(newText, cursor + nextPrefix.length)
    }

    /** Toggles "- [ ]" <-> "- [x]" on the given 0-based line. */
    fun toggleTaskAtLine(text: String, lineIndex: Int): String {
        val lines = text.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return text
        val line = lines[lineIndex]
        val m = Regex("""^(\s*[-*+]\s+\[)([ xX])(].*)$""").matchEntire(line) ?: return text
        val next = if (m.groupValues[2] == " ") "x" else " "
        lines[lineIndex] = m.groupValues[1] + next + m.groupValues[3]
        return lines.joinToString("\n")
    }
}

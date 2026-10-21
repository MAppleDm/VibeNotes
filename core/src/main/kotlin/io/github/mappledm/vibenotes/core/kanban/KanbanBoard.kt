package io.github.mappledm.vibenotes.core.kanban

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * A card is one list item of a lane: "- [ ] Buy milk @{2026-10-16}".
 * [continuation] keeps extra indented lines verbatim so foreign content round-trips.
 */
data class KanbanCard(
    val text: String,
    val checked: Boolean = false,
    val continuation: List<String> = emptyList(),
) {
    val title: String get() = stripDue(text).replace(Regex("\\s+"), " ").trim()

    val dueDate: LocalDate? get() = dateTokenRegex.find(text)?.groupValues?.get(1)?.let { parseDate(it) }

    val dueTime: LocalTime? get() = timeTokenRegex.find(text)?.groupValues?.get(1)?.let { parseTime(it) }

    /** Returns a copy with the due date/time tokens replaced (null removes them). */
    fun withDue(date: LocalDate?, time: LocalTime? = null): KanbanCard {
        val base = stripDue(text).replace(Regex("\\s+"), " ").trim()
        val sb = StringBuilder(base)
        if (date != null) {
            sb.append(" @{").append(date).append('}')
            if (time != null) sb.append(" @@{").append(formatTime(time)).append('}')
        }
        return copy(text = sb.toString())
    }

    fun withTitle(newTitle: String): KanbanCard {
        val clean = newTitle.replace('\n', ' ').trim()
        return copy(text = clean).withDue(dueDate, dueTime)
    }

    companion object {
        // Obsidian Kanban plugin default triggers: @{date} and @@{time}.
        // Braces are escaped everywhere: Android's ICU regex engine rejects a bare "}".
        private val dateTokenRegex = Regex("""(?<!@)@\{([^\}]+)\}""")
        private val timeTokenRegex = Regex("""@@\{([^\}]+)\}""")

        /** [text] without the @{date} and @@{time} tokens. */
        fun stripDue(text: String): String = text.replace(timeTokenRegex, "").replace(dateTokenRegex, "")

        fun create(title: String, due: LocalDate? = null, time: LocalTime? = null): KanbanCard =
            KanbanCard(title.replace('\n', ' ').trim()).withDue(due, time)

        private fun parseDate(raw: String): LocalDate? = try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        private fun parseTime(raw: String): LocalTime? = try {
            LocalTime.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        private fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
    }
}

data class KanbanLane(
    val title: String,
    val cards: List<KanbanCard> = emptyList(),
    /** Lanes marked "**Complete**" check every card moved into them (Obsidian Kanban behaviour). */
    val isComplete: Boolean = false,
    /** Non-card lines found inside the lane, preserved verbatim. */
    val extraLines: List<String> = emptyList(),
)

/** Identifies a card for an edit; [text] guards against editing a card that changed on disk. */
data class CardRef(val lane: Int, val index: Int, val text: String)

data class KanbanBoard(
    val lanes: List<KanbanLane>,
    val frontmatter: String = KanbanFormat.DEFAULT_FRONTMATTER,
    val preamble: String = "\n",
    val tail: String = KanbanFormat.DEFAULT_SETTINGS,
) {
    fun laneIndex(title: String): Int = lanes.indexOfFirst { it.title.equals(title.trim(), ignoreCase = true) }

    fun card(ref: CardRef): KanbanCard? = lanes.getOrNull(ref.lane)?.cards?.getOrNull(ref.index)

    /** Locates [ref] even if positions shifted because of an external edit. */
    fun locate(ref: CardRef): CardRef? {
        val direct = card(ref)
        if (direct != null && direct.text == ref.text) return ref
        lanes.forEachIndexed { laneIdx, lane ->
            val idx = lane.cards.indexOfFirst { it.text == ref.text }
            if (idx >= 0) return CardRef(laneIdx, idx, ref.text)
        }
        return null
    }

    fun addCard(laneIndex: Int, card: KanbanCard): KanbanBoard {
        require(laneIndex in lanes.indices) { "Колонка не найдена" }
        val lane = lanes[laneIndex]
        val adjusted = if (lane.isComplete) card.copy(checked = true) else card
        return replaceLane(laneIndex, lane.copy(cards = lane.cards + adjusted))
    }

    fun updateCard(ref: CardRef, transform: (KanbanCard) -> KanbanCard): KanbanBoard {
        val at = locate(ref) ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        val lane = lanes[at.lane]
        val cards = lane.cards.toMutableList()
        cards[at.index] = transform(cards[at.index])
        return replaceLane(at.lane, lane.copy(cards = cards))
    }

    fun removeCard(ref: CardRef): KanbanBoard {
        val at = locate(ref) ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        val lane = lanes[at.lane]
        return replaceLane(at.lane, lane.copy(cards = lane.cards.filterIndexed { i, _ -> i != at.index }))
    }

    /** Moves a card to the end of [toLane] (or to [toIndex]); completion follows the target lane. */
    fun moveCard(ref: CardRef, toLane: Int, toIndex: Int? = null): KanbanBoard {
        require(toLane in lanes.indices) { "Колонка не найдена" }
        val at = locate(ref) ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        val card = lanes[at.lane].cards[at.index]
        val fromComplete = lanes[at.lane].isComplete
        val target = lanes[toLane]
        val moved = when {
            target.isComplete -> card.copy(checked = true)
            fromComplete -> card.copy(checked = false)
            else -> card
        }
        val removed = removeCard(at)
        val targetCards = removed.lanes[toLane].cards.toMutableList()
        val insertAt = (toIndex ?: targetCards.size).coerceIn(0, targetCards.size)
        targetCards.add(insertAt, moved)
        return removed.replaceLane(toLane, removed.lanes[toLane].copy(cards = targetCards))
    }

    /** Checking a card moves it to the first complete lane if the board has one. */
    fun setChecked(ref: CardRef, checked: Boolean): KanbanBoard {
        val at = locate(ref) ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        val completeLane = lanes.indexOfFirst { it.isComplete }
        return when {
            checked && completeLane >= 0 && completeLane != at.lane -> moveCard(at, completeLane)
            !checked && lanes[at.lane].isComplete -> {
                val firstOpen = lanes.indexOfFirst { !it.isComplete }
                if (firstOpen >= 0) moveCard(at, firstOpen, 0) else updateCard(at) { it.copy(checked = false) }
            }
            else -> updateCard(at) { it.copy(checked = checked) }
        }
    }

    fun addLane(title: String): KanbanBoard {
        val clean = title.trim()
        if (clean.isEmpty()) throw KanbanException("Название колонки не может быть пустым")
        // Keep complete lanes last, like "Done" columns usually are.
        val insertAt = lanes.indexOfFirst { it.isComplete }.let { if (it < 0) lanes.size else it }
        return copy(lanes = lanes.toMutableList().apply { add(insertAt, KanbanLane(clean)) })
    }

    fun renameLane(index: Int, title: String): KanbanBoard {
        val clean = title.trim()
        if (clean.isEmpty()) throw KanbanException("Название колонки не может быть пустым")
        return replaceLane(index, lanes[index].copy(title = clean))
    }

    fun removeLane(index: Int): KanbanBoard {
        if (lanes[index].cards.isNotEmpty()) throw KanbanException("Сначала перенесите или удалите карточки колонки")
        return copy(lanes = lanes.filterIndexed { i, _ -> i != index })
    }

    private fun replaceLane(index: Int, lane: KanbanLane): KanbanBoard =
        copy(lanes = lanes.toMutableList().also { it[index] = lane })

    companion object {
        fun default(): KanbanBoard = KanbanBoard(
            lanes = listOf(
                KanbanLane("Сделать"),
                KanbanLane("В работе"),
                KanbanLane("Готово", isComplete = true),
            ),
        )
    }
}

class KanbanException(message: String) : Exception(message)

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
    /** Cards taken off the board but kept (the plugin's "Archive"); not tasks anymore. */
    val archive: KanbanLane? = null,
) {
    val archived: List<KanbanCard> get() = archive?.cards.orEmpty()

    /** Cards that are done: checked, or sitting in a complete lane. */
    val finishedCount: Int get() = lanes.sumOf { lane -> lane.cards.count { it.checked || lane.isComplete } }

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

    /**
     * Puts [card] back at [index] of the lane titled [laneTitle] (the first open lane if that
     * lane is gone). Used to undo a delete or an archive.
     */
    fun insertCard(laneTitle: String, index: Int, card: KanbanCard): KanbanBoard {
        val laneIdx = laneIndex(laneTitle).takeIf { it >= 0 }
            ?: lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 }
            ?: throw KanbanException("На доске нет колонок")
        val lane = lanes[laneIdx]
        val cards = lane.cards.toMutableList()
        cards.add(index.coerceIn(0, cards.size), card)
        return replaceLane(laneIdx, lane.copy(cards = cards))
    }

    /** Takes the card off the board into the archive, as it is. */
    fun archiveCard(ref: CardRef): KanbanBoard {
        val at = locate(ref) ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        val card = lanes[at.lane].cards[at.index]
        return removeCard(at).withArchived(archived + card)
    }

    /** Archives every finished card (checked or in a complete lane); open cards stay. */
    fun archiveFinished(): KanbanBoard {
        val finished = mutableListOf<KanbanCard>()
        val kept = lanes.map { lane ->
            val (done, open) = lane.cards.partition { it.checked || lane.isComplete }
            finished += done
            lane.copy(cards = open)
        }
        if (finished.isEmpty()) return this
        return copy(lanes = kept).withArchived(archived + finished)
    }

    /** Moves an archived card back onto the board; completion follows the target lane. */
    fun restoreArchived(index: Int, text: String, toLane: Int): KanbanBoard {
        require(toLane in lanes.indices) { "Колонка не найдена" }
        val at = locateArchived(index, text)
        val card = archived[at]
        val lane = lanes[toLane]
        val restored = replaceLane(toLane, lane.copy(cards = lane.cards + card.copy(checked = lane.isComplete)))
        return restored.withArchived(archived.filterIndexed { i, _ -> i != at })
    }

    fun deleteArchived(index: Int, text: String): KanbanBoard {
        val at = locateArchived(index, text)
        return withArchived(archived.filterIndexed { i, _ -> i != at })
    }

    /** Puts a deleted archived card back at [index]; undoes [deleteArchived]. */
    fun insertArchived(index: Int, card: KanbanCard): KanbanBoard =
        withArchived(archived.toMutableList().apply { add(index.coerceIn(0, size), card) })

    /** Removes the newest archived card with [text]; undoes an archive. No-op if it is gone. */
    fun unarchiveLast(text: String): KanbanBoard {
        val at = archived.indexOfLast { it.text == text }
        if (at < 0) return this
        return withArchived(archived.filterIndexed { i, _ -> i != at })
    }

    private fun locateArchived(index: Int, text: String): Int =
        index.takeIf { archived.getOrNull(it)?.text == text }
            ?: archived.indexOfFirst { it.text == text }.takeIf { it >= 0 }
            ?: throw KanbanException("Карточка архива изменилась в другом месте. Обновите доску.")

    private fun withArchived(cards: List<KanbanCard>): KanbanBoard =
        copy(archive = (archive ?: KanbanLane(KanbanFormat.DEFAULT_ARCHIVE_TITLE)).copy(cards = cards))

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

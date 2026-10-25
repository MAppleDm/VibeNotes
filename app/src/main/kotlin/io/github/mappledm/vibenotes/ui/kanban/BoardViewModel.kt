package io.github.mappledm.vibenotes.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.kanban.CardRef
import io.github.mappledm.vibenotes.core.kanban.KanbanBoard
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A snackbar line; [undo] reverses the change it reports (re-applied to the board as it is then). */
class BoardMessage(val text: String, val undo: ((KanbanBoard) -> KanbanBoard)? = null)

class BoardViewModel(container: AppContainer, val path: String, private val createIfMissing: Boolean = false) : ViewModel() {
    private val kanban = container.requireVault().kanban
    val title: String = VaultPaths.baseName(path)

    private val _board = MutableStateFlow<KanbanBoard?>(null)
    val board: StateFlow<KanbanBoard?> = _board.asStateFlow()

    private val _message = MutableStateFlow<BoardMessage?>(null)
    val message: StateFlow<BoardMessage?> = _message.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                if (createIfMissing) kanban.ensureBoard(path)
                kanban.load(path)
            }
                .onSuccess { _board.value = it }
                .onFailure { _message.value = BoardMessage(it.message ?: "Не удалось открыть доску") }
        }
    }

    private fun change(done: BoardMessage? = null, operation: (KanbanBoard) -> KanbanBoard) {
        viewModelScope.launch {
            runCatching { kanban.update(path, operation) }
                .onSuccess {
                    _board.value = it
                    if (done != null) _message.value = done
                }
                .onFailure {
                    _message.value = BoardMessage(it.message ?: "Не удалось сохранить доску")
                    load()
                }
        }
    }

    fun messageShown(message: BoardMessage) {
        if (_message.value === message) _message.value = null
    }

    fun undo(message: BoardMessage) {
        message.undo?.let { change(operation = it) }
    }

    fun addCard(lane: Int, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        change { it.addCard(lane, KanbanCard.create(clean)) }
    }

    fun updateCard(ref: CardRef, title: String, due: LocalDate?) {
        if (title.isBlank()) return
        change { board ->
            board.updateCard(ref) { card ->
                val keepTime = if (due != null && due == card.dueDate) card.dueTime else null
                card.withTitle(title).withDue(due, keepTime)
            }
        }
    }

    /** Creates a board with the default columns next to the others and reports its path. */
    fun createBoard(title: String, onCreated: (String) -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching { kanban.createBoard("", clean) }
                .onSuccess { onCreated(it.path) }
                .onFailure { _message.value = BoardMessage(it.message ?: "Не удалось создать доску") }
        }
    }

    /** Where a card is now, to put it back on undo. */
    private class Place(val laneTitle: String, val index: Int, val card: KanbanCard)

    private fun placeOf(ref: CardRef): Place? {
        val board = _board.value ?: return null
        val at = board.locate(ref) ?: return null
        return Place(board.lanes[at.lane].title, at.index, board.lanes[at.lane].cards[at.index])
    }

    fun move(ref: CardRef, toLane: Int) {
        val lane = _board.value?.lanes?.getOrNull(toLane)?.title
        change(lane?.let { BoardMessage("Перенесено в «$it»") }) { it.moveCard(ref, toLane) }
    }

    fun setChecked(ref: CardRef, checked: Boolean) = change { it.setChecked(ref, checked) }

    /** Removes the card from the board for good (the snackbar can bring it back). */
    fun delete(ref: CardRef) {
        val place = placeOf(ref) ?: return
        change(BoardMessage("Задача удалена") { it.insertCard(place.laneTitle, place.index, place.card) }) { it.removeCard(ref) }
    }

    fun archive(ref: CardRef) {
        val place = placeOf(ref) ?: return
        change(BoardMessage("Задача в архиве") { it.unarchiveLast(place.card.text).insertCard(place.laneTitle, place.index, place.card) }) {
            it.archiveCard(ref)
        }
    }

    /** Archives every finished card at once: clears "Готово" without losing anything. */
    fun archiveFinished() {
        val board = _board.value ?: return
        val places = board.lanes.flatMap { lane ->
            lane.cards.mapIndexedNotNull { index, card -> Place(lane.title, index, card).takeIf { card.checked || lane.isComplete } }
        }
        if (places.isEmpty()) return
        val undo: (KanbanBoard) -> KanbanBoard = { current ->
            places.fold(current) { b, place -> b.unarchiveLast(place.card.text).insertCard(place.laneTitle, place.index, place.card) }
        }
        change(BoardMessage("В архиве: ${places.size}", undo)) { it.archiveFinished() }
    }

    /** Returns an archived card to the first open column. */
    fun restoreArchived(index: Int, card: KanbanCard) {
        val board = _board.value ?: return
        val lane = board.lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 } ?: 0
        if (board.lanes.isEmpty()) return
        change(BoardMessage("Возвращено в «${board.lanes[lane].title}»")) { it.restoreArchived(index, card.text, lane) }
    }

    fun deleteArchived(index: Int, card: KanbanCard) {
        change(BoardMessage("Удалено из архива") { it.insertArchived(index, card) }) { it.deleteArchived(index, card.text) }
    }

    fun addLane(title: String) = change { it.addLane(title) }
    fun renameLane(index: Int, title: String) = change { it.renameLane(index, title) }
    fun deleteLane(index: Int) = change { it.removeLane(index) }
}

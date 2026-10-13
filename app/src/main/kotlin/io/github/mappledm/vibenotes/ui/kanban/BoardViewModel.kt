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

class BoardViewModel(container: AppContainer, val path: String, private val createIfMissing: Boolean = false) : ViewModel() {
    private val kanban = container.requireVault().kanban
    val title: String = VaultPaths.baseName(path)

    private val _board = MutableStateFlow<KanbanBoard?>(null)
    val board: StateFlow<KanbanBoard?> = _board.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
                .onFailure { _error.value = it.message ?: "Не удалось открыть доску" }
        }
    }

    private fun change(operation: (KanbanBoard) -> KanbanBoard) {
        viewModelScope.launch {
            runCatching { kanban.update(path, operation) }
                .onSuccess { _board.value = it }
                .onFailure {
                    _error.value = it.message ?: "Не удалось сохранить доску"
                    load()
                }
        }
    }

    fun errorShown() {
        _error.value = null
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

    fun move(ref: CardRef, toLane: Int) = change { it.moveCard(ref, toLane) }
    fun setChecked(ref: CardRef, checked: Boolean) = change { it.setChecked(ref, checked) }
    fun delete(ref: CardRef) = change { it.removeCard(ref) }
    fun addLane(title: String) = change { it.addLane(title) }
    fun renameLane(index: Int, title: String) = change { it.renameLane(index, title) }
    fun deleteLane(index: Int) = change { it.removeLane(index) }
}

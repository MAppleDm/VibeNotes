package io.github.mappledm.vibenotes.core.kanban

import io.github.mappledm.vibenotes.core.notes.NoteKind
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A card together with where it lives — used by the week view and AI tools. */
data class TaskItem(
    val boardPath: String,
    val laneTitle: String,
    val laneIsComplete: Boolean,
    val ref: CardRef,
    val card: KanbanCard,
) {
    val boardTitle: String get() = VaultPaths.baseName(boardPath)
}

/**
 * Boards are ordinary Markdown files (see [KanbanFormat]); every change re-reads the
 * file first, so edits made on another device in between are not overwritten.
 */
class KanbanRepository(private val notes: NotesRepository) {
    private val mutex = Mutex()

    suspend fun load(path: String): KanbanBoard {
        val content = notes.read(path) ?: throw KanbanException("Доска «${VaultPaths.baseName(path)}» не найдена")
        return KanbanFormat.parse(content)
    }

    suspend fun update(path: String, change: (KanbanBoard) -> KanbanBoard): KanbanBoard = mutex.withLock {
        val board = load(path)
        val updated = change(board)
        notes.save(path, KanbanFormat.serialize(updated))
        updated
    }

    suspend fun createBoard(folder: String, title: String, board: KanbanBoard = KanbanBoard.default()): NoteMeta =
        notes.create(folder, title, KanbanFormat.serialize(board))

    /** Returns [path] if it is a board, creating a default board there when the file is missing. */
    suspend fun ensureBoard(path: String): String = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        val existing = notes.read(normalized)
        when {
            existing == null -> {
                notes.save(normalized, KanbanFormat.serialize(KanbanBoard.default()))
                normalized
            }
            KanbanFormat.isBoard(existing) -> normalized
            else -> throw KanbanException("«${VaultPaths.baseName(normalized)}» — обычная заметка, а не доска")
        }
    }

    /** Every card of every board, read from the notes index cache. */
    fun allTasks(): List<TaskItem> {
        val result = mutableListOf<TaskItem>()
        for (meta in notes.index.value.notes) {
            if (meta.kind != NoteKind.BOARD) continue
            val content = notes.cachedContent(meta.path) ?: continue
            val board = KanbanFormat.parse(content)
            board.lanes.forEachIndexed { laneIdx, lane ->
                lane.cards.forEachIndexed { cardIdx, card ->
                    result += TaskItem(meta.path, lane.title, lane.isComplete, CardRef(laneIdx, cardIdx, card.text), card)
                }
            }
        }
        return result
    }
}

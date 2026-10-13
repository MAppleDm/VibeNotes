package io.github.mappledm.vibenotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.core.notes.SearchHit
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotesUiState(
    val folder: String = "",
    val subfolders: List<String> = emptyList(),
    val notes: List<NoteMeta> = emptyList(),
    val pinned: List<NoteMeta> = emptyList(),
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val loaded: Boolean = false,
    val allFolders: List<String> = emptyList(),
    val pinnedPaths: Set<String> = emptySet(),
)

class NotesViewModel(private val container: AppContainer, private val folder: String) : ViewModel() {
    private val vault = container.requireVault()
    private val notes = vault.notes
    private val query = MutableStateFlow("")

    private val _message = MutableStateFlow<UndoMessage?>(null)
    val message: StateFlow<UndoMessage?> = _message.asStateFlow()

    data class UndoMessage(val text: String, val trashPath: String? = null, val originalPath: String? = null)

    val state: StateFlow<NotesUiState> = combine(notes.index, query, container.settings.state) { index, q, settings ->
        val inFolder = index.notes.filter { it.folder == folder }
        val subfolders = index.folders.filter { VaultPaths.parent(it) == folder }
        val pinned = if (folder.isEmpty()) index.notes.filter { it.path in settings.pinned } else emptyList()
        NotesUiState(
            folder = folder,
            subfolders = subfolders.sortedBy { it.lowercase() },
            notes = inFolder.sortedWith(compareByDescending<NoteMeta> { it.path in settings.pinned }.thenByDescending { it.lastModified }),
            pinned = pinned,
            query = q,
            results = if (q.isBlank()) emptyList() else notes.search(q),
            loaded = index.loaded,
            allFolders = listOf("") + index.folders,
            pinnedPaths = settings.pinned,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState(folder = folder))

    fun refresh() {
        viewModelScope.launch { runCatching { notes.refresh() }.onFailure { show(it.message ?: "Не удалось обновить список") } }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun createFolder(name: String) {
        val clean = VaultPaths.sanitizeFileName(name)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching { notes.createFolder(VaultPaths.join(folder, clean)) }.onFailure { show(it.message ?: "Ошибка") }
        }
    }

    fun togglePin(path: String) {
        container.settings.setPinned(path, path !in container.settings.current.pinned)
    }

    fun rename(path: String, title: String) {
        viewModelScope.launch {
            runCatching { notes.rename(path, title) }
                .onSuccess { container.settings.renamePinned(path, it) }
                .onFailure { show(it.message ?: "Не удалось переименовать") }
        }
    }

    fun move(path: String, targetFolder: String) {
        viewModelScope.launch {
            runCatching { notes.move(path, targetFolder) }
                .onSuccess { container.settings.renamePinned(path, it) }
                .onFailure { show(it.message ?: "Не удалось переместить") }
        }
    }

    fun delete(path: String) {
        viewModelScope.launch {
            runCatching { notes.delete(path) }
                .onSuccess { trash ->
                    container.settings.setPinned(path, false)
                    _message.value = UndoMessage("«${VaultPaths.baseName(path)}» перемещена в корзину", trash, path)
                }
                .onFailure { show(it.message ?: "Не удалось удалить") }
        }
    }

    fun undoDelete(message: UndoMessage) {
        val trash = message.trashPath ?: return
        val original = message.originalPath ?: return
        viewModelScope.launch { runCatching { notes.restore(trash, original) }.onFailure { show(it.message ?: "Не удалось восстановить") } }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun show(text: String) {
        _message.value = UndoMessage(text)
    }
}

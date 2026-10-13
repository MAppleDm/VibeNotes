package io.github.mappledm.vibenotes.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.markdown.MarkdownEditing
import io.github.mappledm.vibenotes.core.markdown.MarkdownText
import io.github.mappledm.vibenotes.core.markdown.TextEdit
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SaveStatus {
    data object Saved : SaveStatus
    data object Pending : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

/**
 * Editor state. Saves automatically ~0.7 s after the last keystroke, when the screen
 * pauses and when it closes. New notes become files only once they have content.
 */
class EditorViewModel(
    private val container: AppContainer,
    initialPath: String?,
    private val folder: String,
) : ViewModel() {
    private val notes: NotesRepository = container.requireVault().notes

    var path by mutableStateOf(initialPath)
        private set
    var title by mutableStateOf(initialPath?.let { VaultPaths.baseName(it) } ?: "")
        private set
    var content by mutableStateOf(TextFieldValue(""))
        private set
    var preview by mutableStateOf(false)
        private set
    var status by mutableStateOf<SaveStatus>(SaveStatus.Saved)
        private set
    var loaded by mutableStateOf(initialPath == null)
        private set
    var missing by mutableStateOf(false)
        private set
    var closed by mutableStateOf(false)
        private set

    val isNew: Boolean get() = path == null
    val isPinned: Boolean get() = path?.let { it in container.settings.current.pinned } ?: false

    private var lastSavedText = ""
    /** Set after deletion so a pending autosave cannot resurrect the note. */
    private var discarded = false
    private var saveJob: Job? = null
    private val mutex = Mutex()

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastUndoPush = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun updateUndoState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    init {
        if (initialPath != null) load(initialPath)
    }

    private fun load(p: String) {
        viewModelScope.launch {
            val text = runCatching { notes.read(p) }.getOrElse {
                status = SaveStatus.Failed(it.message ?: "Не удалось открыть")
                null
            }
            if (text == null) {
                missing = true
            } else {
                content = TextFieldValue(text)
                lastSavedText = text
                // Notes with content open in read mode, like a document; empty ones go straight to editing.
                preview = text.isNotBlank()
            }
            loaded = true
        }
    }

    /** Picks up edits made elsewhere (e.g. synced from a PC) when nothing local is pending. */
    fun reloadIfChangedExternally() {
        val p = path ?: return
        if (content.text != lastSavedText || saveJob?.isActive == true) return
        viewModelScope.launch {
            val text = runCatching { notes.read(p) }.getOrNull() ?: return@launch
            if (text != lastSavedText && content.text == lastSavedText) {
                val selection = TextRange(content.selection.start.coerceAtMost(text.length), content.selection.end.coerceAtMost(text.length))
                content = TextFieldValue(text, selection)
                lastSavedText = text
            }
        }
    }

    fun onContentChange(value: TextFieldValue) {
        val old = content
        var next = value
        // Enter at the end of a list item continues the list.
        if (value.text.length == old.text.length + 1 && value.selection.collapsed) {
            val cursor = value.selection.start
            if (cursor > 0 && value.text[cursor - 1] == '\n' && value.text.removeRange(cursor - 1, cursor) == old.text) {
                MarkdownEditing.continueList(value.text, cursor)?.let { edit ->
                    next = TextFieldValue(edit.text, TextRange(edit.selStart, edit.selEnd))
                }
            }
        }
        if (next.text != old.text) {
            pushUndo(old, force = false)
            redoStack.clear()
            updateUndoState()
        }
        content = next
        if (next.text != old.text) scheduleSave()
    }

    fun onTitleChange(value: String) {
        title = value.replace('\n', ' ')
        if (isNew) scheduleSave()
    }

    fun togglePreview() {
        preview = !preview
    }

    fun showPreview(value: Boolean) {
        preview = value
    }

    // --- formatting -----------------------------------------------------------------------

    private fun applyEdit(transform: (TextEdit) -> TextEdit) {
        val sel = content.selection
        val result = transform(TextEdit(content.text, sel.min, sel.max))
        pushUndo(content, force = true)
        redoStack.clear()
        updateUndoState()
        content = TextFieldValue(result.text, TextRange(result.selStart, result.selEnd))
        scheduleSave()
    }

    fun bold() = applyEdit { MarkdownEditing.toggleWrap(it, "**", "текст") }
    fun italic() = applyEdit { MarkdownEditing.toggleWrap(it, "*", "текст") }
    fun strike() = applyEdit { MarkdownEditing.toggleWrap(it, "~~", "текст") }
    fun inlineCode() = applyEdit { MarkdownEditing.toggleWrap(it, "`", "код") }
    fun heading() = applyEdit { MarkdownEditing.toggleLinePrefix(it, "## ") }
    fun bullet() = applyEdit { MarkdownEditing.toggleLinePrefix(it, "- ") }
    fun numbered() = applyEdit { MarkdownEditing.toggleLinePrefix(it, "1. ") }
    fun task() = applyEdit { MarkdownEditing.toggleLinePrefix(it, "- [ ] ") }
    fun quote() = applyEdit { MarkdownEditing.toggleLinePrefix(it, "> ") }
    fun link() = applyEdit { MarkdownEditing.insertLink(it) }
    fun codeBlock() = applyEdit { MarkdownEditing.insertCodeBlock(it) }

    fun toggleTask(line: Int) {
        val updated = MarkdownEditing.toggleTaskAtLine(content.text, line)
        if (updated == content.text) return
        pushUndo(content, force = true)
        updateUndoState()
        content = content.copy(text = updated)
        scheduleSave()
    }

    // --- undo -----------------------------------------------------------------------------

    private fun pushUndo(snapshot: TextFieldValue, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUndoPush < 1000 && undoStack.isNotEmpty()) return
        undoStack.addLast(snapshot)
        if (undoStack.size > 200) undoStack.removeFirst()
        lastUndoPush = now
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(content)
        content = previous
        lastUndoPush = 0
        updateUndoState()
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(content)
        content = next
        updateUndoState()
        scheduleSave()
    }

    // --- saving ---------------------------------------------------------------------------

    private fun scheduleSave() {
        status = SaveStatus.Pending
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            // The write itself must not be cancelled half-way (it could create a duplicate file).
            withContext(NonCancellable) { mutex.withLock { saveLocked() } }
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        viewModelScope.launch { withContext(NonCancellable) { mutex.withLock { saveLocked() } } }
    }

    /** Applies a changed title as a file rename. */
    fun commitTitle() {
        viewModelScope.launch { withContext(NonCancellable) { mutex.withLock { saveLocked(); renameLocked() } } }
    }

    /** Saves everything; used when the screen pauses or closes. */
    suspend fun flush() = withContext(NonCancellable) {
        saveJob?.cancel()
        mutex.withLock {
            saveLocked()
            renameLocked()
        }
    }

    private suspend fun saveLocked() {
        if (discarded) return
        val text = content.text
        val current = path
        try {
            if (current == null) {
                if (text.isBlank() && title.isBlank()) {
                    status = SaveStatus.Saved
                    return
                }
                val name = title.ifBlank { titleFromContent(text) }
                val meta = notes.create(folder, name, text)
                path = meta.path
                title = meta.title
                lastSavedText = text
            } else if (text != lastSavedText) {
                notes.save(current, text)
                lastSavedText = text
            }
            status = SaveStatus.Saved
        } catch (e: Exception) {
            status = SaveStatus.Failed(e.message ?: "Не удалось сохранить")
        }
    }

    private suspend fun renameLocked() {
        if (discarded) return
        val current = path ?: return
        val desired = VaultPaths.sanitizeFileName(title)
        if (desired.isEmpty()) {
            title = VaultPaths.baseName(current)
            return
        }
        if (desired == VaultPaths.baseName(current)) return
        try {
            val newPath = notes.rename(current, desired)
            container.settings.renamePinned(current, newPath)
            path = newPath
            title = VaultPaths.baseName(newPath)
        } catch (e: Exception) {
            status = SaveStatus.Failed(e.message ?: "Не удалось переименовать")
            title = VaultPaths.baseName(current)
        }
    }

    fun togglePin() {
        val p = path ?: return
        container.settings.setPinned(p, !isPinned)
    }

    fun delete() {
        val p = path
        saveJob?.cancel()
        if (p == null) {
            discarded = true
            closed = true
            return
        }
        viewModelScope.launch {
            runCatching {
                mutex.withLock {
                    notes.delete(p)
                    discarded = true
                }
            }
                .onSuccess {
                    container.settings.setPinned(p, false)
                    closed = true
                }
                .onFailure { status = SaveStatus.Failed(it.message ?: "Не удалось удалить") }
        }
    }

    /** Opens [[target]]: an existing note, or a new empty one in the same folder. */
    suspend fun resolveOrCreateLink(target: String): String? {
        notes.resolveLink(target)?.let { return it.path }
        return runCatching { notes.create(path?.let { VaultPaths.parent(it) } ?: folder, target, "").path }.getOrNull()
    }

    override fun onCleared() {
        // Leaving the screen must never lose text: finish saving outside the ViewModel scope.
        container.appScope.launch { flush() }
        super.onCleared()
    }

    private fun titleFromContent(text: String): String {
        val firstLine = text.lineSequence().map { MarkdownText.stripLine(it) }.firstOrNull { it.isNotBlank() }
        return firstLine?.take(60)?.trim() ?: NotesRepository.DEFAULT_TITLE
    }
}

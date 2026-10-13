package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.BoardInfo
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.inbox.ChatEntry
import io.github.mappledm.vibenotes.core.kanban.KanbanFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(private val container: AppContainer) : ViewModel() {
    private val vault = container.requireVault()
    private val inbox = vault.inbox

    val entries: StateFlow<List<ChatEntry>> = inbox.entries
    val aiConfigured: StateFlow<Boolean> = container.secrets.hasApiKey

    var composer by mutableStateOf(TextFieldValue(""))
        private set

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    val boards: StateFlow<List<BoardInfo>> = vault.notes.index
        .map { index ->
            index.boards.mapNotNull { meta ->
                val content = vault.notes.cachedContent(meta.path) ?: return@mapNotNull null
                BoardInfo(meta.path, meta.title, KanbanFormat.parse(content).lanes.map { it.title })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    init {
        loadCalendars()
        viewModelScope.launch {
            container.pendingShare.collect { shared ->
                if (shared != null) {
                    val prefix = if (composer.text.isBlank()) "" else composer.text.trimEnd() + "\n"
                    val text = prefix + shared
                    composer = TextFieldValue(text, TextRange(text.length))
                    container.pendingShare.value = null
                }
            }
        }
    }

    fun onComposerChange(value: TextFieldValue) {
        composer = value
    }

    fun appendToComposer(text: String) {
        val current = composer.text
        val joined = if (current.isBlank()) text else current.trimEnd() + " " + text
        composer = TextFieldValue(joined, TextRange(joined.length))
    }

    fun useSuggestion(text: String) {
        composer = TextFieldValue(text, TextRange(text.length))
    }

    fun send() {
        val text = composer.text.trim()
        if (text.isEmpty() || _generating.value) return
        composer = TextFieldValue("")
        job = viewModelScope.launch(Dispatchers.Default) {
            _generating.value = true
            try {
                inbox.send(text)
            } finally {
                _generating.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun retry(entryId: String) {
        if (_generating.value) return
        job = viewModelScope.launch(Dispatchers.Default) {
            _generating.value = true
            try {
                inbox.retry(entryId)
            } finally {
                _generating.value = false
            }
        }
    }

    fun hasCalendarPermission(): Boolean = container.calendar.hasPermission()

    fun confirm(entryId: String, proposalId: String) {
        viewModelScope.launch(Dispatchers.Default) { inbox.confirm(entryId, proposalId) }
    }

    fun dismiss(entryId: String, proposalId: String) {
        viewModelScope.launch { inbox.dismiss(entryId, proposalId) }
    }

    fun edit(entryId: String, proposalId: String, proposal: ActionProposal, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(inbox.edit(entryId, proposalId, proposal))
        }
    }

    fun clearHistory() {
        job?.cancel()
        viewModelScope.launch { inbox.clear() }
    }

    fun loadCalendars() {
        if (!container.calendar.hasPermission()) return
        viewModelScope.launch {
            _calendars.value = runCatching { container.calendar.writableCalendars() }.getOrDefault(emptyList())
        }
    }

    fun resolveNote(target: String): String? = vault.notes.resolveLink(target)?.path
}

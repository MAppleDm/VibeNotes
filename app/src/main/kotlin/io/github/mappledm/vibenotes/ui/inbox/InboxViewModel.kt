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
import io.github.mappledm.vibenotes.core.inbox.ChatFocus
import io.github.mappledm.vibenotes.core.inbox.ChatRole
import io.github.mappledm.vibenotes.core.inbox.isOpen
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

const val LATEST_EXCHANGE = "latest"

/**
 * The messages [panel] points at: one user message and the answers to it. For an answer id,
 * the exchange around that answer; for [LATEST_EXCHANGE], the last one.
 */
fun exchange(entries: List<ChatEntry>, panel: String?): List<ChatEntry> {
    if (panel == null || entries.isEmpty()) return emptyList()
    val at = if (panel == LATEST_EXCHANGE) entries.lastIndex else entries.indexOfFirst { it.id == panel }.takeIf { it >= 0 } ?: return emptyList()
    val start = (at downTo 0).firstOrNull { entries[it].role == ChatRole.USER } ?: at
    val end = (at + 1..entries.lastIndex).firstOrNull { entries[it].role == ChatRole.USER } ?: entries.size
    return entries.subList(start, end)
}

/** A card still waiting for the user, shown on the calendar day or the board where it will land. */
data class Draft(val entryId: String, val proposal: ActionProposal)

fun drafts(entries: List<ChatEntry>): List<Draft> =
    entries.flatMap { entry -> entry.proposals.filter { it.status.isOpen }.map { Draft(entry.id, it.proposal) } }

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
                KanbanFormat.parse(content).lanes.let { lanes ->
                    BoardInfo(meta.path, meta.title, lanes.map { it.title }, lanes.filter { it.isComplete }.map { it.title })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    init {
        viewModelScope.launch { container.clearChatIfNewDay() }
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

    /**
     * Which exchange the answer panel shows over the other tabs: [LATEST_EXCHANGE], the id of an
     * assistant answer (a draft was tapped), or null when the panel is closed.
     */
    var panel by mutableStateOf<String?>(null)
        private set

    /** [focus]: the tab's context; [inPanel]: sent from another tab, so the answer opens over it. */
    fun send(focus: ChatFocus? = null, inPanel: Boolean = false) {
        val text = composer.text.trim()
        if (text.isEmpty() || _generating.value) return
        composer = TextFieldValue("")
        panel = if (inPanel) LATEST_EXCHANGE else null
        job = viewModelScope.launch(Dispatchers.Default) {
            _generating.value = true
            try {
                runCatching { container.clearChatIfNewDay() }
                inbox.send(text, focus)
            } finally {
                _generating.value = false
            }
        }
    }

    fun showInPanel(entryId: String) {
        panel = entryId
    }

    fun closePanel() {
        panel = null
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
        viewModelScope.launch(Dispatchers.Default) {
            applySavedCalendar(entryId, proposalId)
            inbox.confirm(entryId, proposalId)
        }
    }

    private fun savedCalendar(calendars: List<CalendarInfo>): Long? =
        container.settings.current.defaultCalendarId?.takeIf { id -> calendars.any { it.id == id } }

    /** An event without a valid calendar goes to the calendar the user chose earlier. */
    private suspend fun applySavedCalendar(entryId: String, proposalId: String) {
        val item = inbox.entries.value.firstOrNull { it.id == entryId }?.proposals?.firstOrNull { it.id == proposalId } ?: return
        val event = item.proposal as? ActionProposal.CreateEvent ?: return
        val calendars = _calendars.value
        if (calendars.any { it.id == event.calendarId }) return
        val saved = savedCalendar(calendars) ?: return
        inbox.edit(entryId, proposalId, event.copy(calendarId = saved))
    }

    fun dismiss(entryId: String, proposalId: String) {
        viewModelScope.launch { inbox.dismiss(entryId, proposalId) }
    }

    fun undo(entryId: String, proposalId: String) {
        viewModelScope.launch(Dispatchers.Default) { inbox.undo(entryId, proposalId) }
    }

    fun edit(entryId: String, proposalId: String, proposal: ActionProposal, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(inbox.edit(entryId, proposalId, proposal))
        }
    }

    /** Called when the screen comes back: a new calendar day starts a fresh chat. */
    fun onResume() {
        if (!_generating.value) viewModelScope.launch { container.clearChatIfNewDay() }
        loadCalendars()
    }

    fun loadCalendars() {
        if (!container.calendar.hasPermission()) return
        viewModelScope.launch { fetchCalendars() }
    }

    private suspend fun fetchCalendars(): List<CalendarInfo> {
        val list = runCatching { container.calendar.writableCalendars() }.getOrDefault(emptyList())
        _calendars.value = list
        return list
    }

    /**
     * The user picked a calendar on a card: use it for this event and remember it,
     * so the next events go to the same account without asking.
     */
    fun chooseCalendar(entryId: String, proposalId: String, calendarId: Long) {
        container.settings.setDefaultCalendar(calendarId)
        viewModelScope.launch {
            val item = inbox.entries.value.firstOrNull { it.id == entryId }?.proposals?.firstOrNull { it.id == proposalId } ?: return@launch
            val event = item.proposal as? ActionProposal.CreateEvent ?: return@launch
            inbox.edit(entryId, proposalId, event.copy(calendarId = calendarId))
        }
    }

    /** True when the event cannot be added before the user says which calendar to use. */
    fun needsCalendarChoice(proposal: ActionProposal, calendars: List<CalendarInfo>): Boolean =
        proposal is ActionProposal.CreateEvent &&
            calendars.size > 1 &&
            calendars.none { it.id == proposal.calendarId } &&
            savedCalendar(calendars) == null

    /** Calendar the card should display for [proposal]. */
    fun effectiveCalendar(proposal: ActionProposal.CreateEvent, calendars: List<CalendarInfo>): CalendarInfo? =
        calendars.firstOrNull { it.id == proposal.calendarId }
            ?: savedCalendar(calendars)?.let { id -> calendars.firstOrNull { it.id == id } }
            ?: calendars.singleOrNull()

    /** After the permission dialog: confirm right away unless a calendar still has to be chosen. */
    fun afterPermission(entryId: String, proposalId: String, granted: Boolean) {
        viewModelScope.launch {
            if (!granted) {
                inbox.confirm(entryId, proposalId)
                return@launch
            }
            val calendars = fetchCalendars()
            val item = inbox.entries.value.firstOrNull { it.id == entryId }?.proposals?.firstOrNull { it.id == proposalId } ?: return@launch
            if (item.proposal is ActionProposal.UpdateEvent || item.proposal is ActionProposal.DeleteEvent) {
                inbox.confirm(entryId, proposalId)
                return@launch
            }
            val event = item.proposal as? ActionProposal.CreateEvent ?: return@launch
            val saved = savedCalendar(calendars)
            when {
                calendars.any { it.id == event.calendarId } -> inbox.confirm(entryId, proposalId)
                saved != null -> {
                    inbox.edit(entryId, proposalId, event.copy(calendarId = saved))
                    inbox.confirm(entryId, proposalId)
                }
                calendars.size == 1 -> {
                    inbox.edit(entryId, proposalId, event.copy(calendarId = calendars.single().id))
                    inbox.confirm(entryId, proposalId)
                }
                // Several accounts: leave the card pending — it now shows the calendar picker.
                else -> Unit
            }
        }
    }

    fun resolveNote(target: String): String? = vault.notes.resolveLink(target)?.path
}

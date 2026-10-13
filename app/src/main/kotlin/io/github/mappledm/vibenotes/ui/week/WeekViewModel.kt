package io.github.mappledm.vibenotes.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

data class DayAgenda(val date: LocalDate, val events: List<CalendarEvent>, val tasks: List<TaskItem>)

data class WeekUiState(
    val weekStart: LocalDate,
    val days: List<DayAgenda> = emptyList(),
    val overdue: List<TaskItem> = emptyList(),
    val calendarAllowed: Boolean = false,
    val calendarError: String? = null,
    val loading: Boolean = true,
)

/** One week of calendar events and Kanban cards with due dates. */
class WeekViewModel(private val container: AppContainer) : ViewModel() {
    private val vault = container.requireVault()
    private val _state = MutableStateFlow(WeekUiState(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))))
    val state: StateFlow<WeekUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun shiftWeek(weeks: Long) {
        _state.update { it.copy(weekStart = it.weekStart.plusWeeks(weeks)) }
        refresh()
    }

    fun today() {
        _state.update { it.copy(weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val start = _state.value.weekStart
            val end = start.plusDays(7)
            val zone = ZoneId.systemDefault()
            runCatching { vault.notes.refresh() }
            val allowed = container.calendar.hasPermission()
            var calendarError: String? = null
            val events = if (allowed) {
                runCatching {
                    container.calendar.events(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant())
                }.getOrElse {
                    calendarError = it.message
                    emptyList()
                }
            } else emptyList()
            val tasks = vault.kanban.allTasks().filter { !it.card.checked && !it.laneIsComplete }
            val today = LocalDate.now()

            val days = (0 until 7).map { offset ->
                val date = start.plusDays(offset.toLong())
                DayAgenda(
                    date = date,
                    events = events.filter { e -> eventDays(e, zone).contains(date) }.sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.start }),
                    tasks = tasks.filter { it.card.dueDate == date },
                )
            }
            // Overdue cards from earlier weeks; this week's are already listed under their day.
            val overdue = if (!today.isBefore(start) && today.isBefore(end)) {
                tasks.filter { it.card.dueDate?.isBefore(start) == true }.sortedBy { it.card.dueDate }
            } else emptyList()
            _state.update {
                it.copy(days = days, overdue = overdue, calendarAllowed = allowed, calendarError = calendarError, loading = false)
            }
        }
    }

    /** Days an event covers; all-day events are stored in UTC by the provider. */
    private fun eventDays(event: CalendarEvent, zone: ZoneId): Set<LocalDate> {
        val tz = if (event.allDay) ZoneOffset.UTC else zone
        val first = event.start.atZone(tz).toLocalDate()
        val endInstant = if (event.end.isAfter(event.start)) event.end.minusMillis(1) else event.start
        val last = endInstant.atZone(tz).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(62).toSet()
    }

    fun complete(task: TaskItem) {
        viewModelScope.launch {
            runCatching { vault.kanban.update(task.boardPath) { it.setChecked(task.ref, true) } }
            refresh()
        }
    }
}

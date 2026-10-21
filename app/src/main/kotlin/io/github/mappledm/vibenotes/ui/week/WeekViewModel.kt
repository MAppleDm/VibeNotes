package io.github.mappledm.vibenotes.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import io.github.mappledm.vibenotes.ui.components.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** Something at a time of day: a timed event or a task with a due time. */
sealed interface TimedEntry {
    val time: LocalTime

    /** [startsBefore]/[endsAfter]: the event crosses midnight at the start or end of this day. */
    data class Event(val event: CalendarEvent, override val time: LocalTime, val startsBefore: Boolean, val endsAfter: Boolean) : TimedEntry
    data class Task(val task: TaskItem, override val time: LocalTime) : TimedEntry
}

data class DayAgenda(
    val date: LocalDate,
    /** All-day events and timed events that cover the whole day. */
    val allDay: List<CalendarEvent>,
    /** Timed events and tasks with a time, in order. */
    val timed: List<TimedEntry>,
    /** Tasks without a time. */
    val tasks: List<TaskItem>,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty() && tasks.isEmpty()
}

data class WeekUiState(
    val weekStart: LocalDate,
    val days: List<DayAgenda> = emptyList(),
    /** The week [days] belong to; null until the first load. */
    val loadedWeek: LocalDate? = null,
    /** Open tasks due before today; shown only in the current week. */
    val overdue: List<TaskItem> = emptyList(),
    val calendarAllowed: Boolean = false,
    val calendarError: String? = null,
    val loading: Boolean = true,
)

/** One week of calendar events and Kanban cards with due dates. */
class WeekViewModel(private val container: AppContainer) : ViewModel() {
    private val vault = container.requireVault()
    private val _state = MutableStateFlow(WeekUiState(thisWeek()))
    val state: StateFlow<WeekUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun shiftWeek(weeks: Long) {
        _state.update { it.copy(weekStart = it.weekStart.plusWeeks(weeks)) }
        refresh()
    }

    fun today() {
        _state.update { it.copy(weekStart = thisWeek()) }
        refresh()
    }

    /** Puts the day into the Inbox composer: the chat is where things get planned. */
    fun planFor(date: LocalDate) {
        container.pendingShare.value = "${Dates.long(date)}: "
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
            val isCurrentWeek = !today.isBefore(start) && today.isBefore(end)

            val days = (0 until 7).map { offset ->
                val date = start.plusDays(offset.toLong())
                val dayStart = date.atStartOfDay(zone).toInstant()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
                val allDay = mutableListOf<CalendarEvent>()
                val timed = mutableListOf<TimedEntry>()
                for (event in events.filter { eventDays(it, zone).contains(date) }) {
                    val startsBefore = event.start.isBefore(dayStart)
                    val endsAfter = event.end.isAfter(dayEnd)
                    if (event.allDay || (startsBefore && endsAfter)) {
                        allDay += event
                    } else {
                        val time = if (startsBefore) LocalTime.MIDNIGHT else event.start.atZone(zone).toLocalTime()
                        timed += TimedEntry.Event(event, time, startsBefore, endsAfter)
                    }
                }
                // In the current week, open tasks of past days are listed once, under "Просрочено".
                val dayTasks = if (isCurrentWeek && date.isBefore(today)) emptyList() else tasks.filter { it.card.dueDate == date }
                dayTasks.forEach { task -> task.card.dueTime?.let { timed += TimedEntry.Task(task, it) } }
                DayAgenda(
                    date = date,
                    allDay = allDay.sortedBy { it.start },
                    timed = timed.sortedBy { it.time },
                    tasks = dayTasks.filter { it.card.dueTime == null },
                )
            }
            val overdue = if (isCurrentWeek) {
                tasks.filter { it.card.dueDate?.isBefore(today) == true }
                    .sortedWith(compareBy<TaskItem> { it.card.dueDate }.thenBy { it.card.dueTime })
            } else emptyList()
            _state.update {
                // A newer week was chosen while this one loaded; its own refresh will publish.
                if (it.weekStart != start) it
                else it.copy(
                    days = days, loadedWeek = start, overdue = overdue,
                    calendarAllowed = allowed, calendarError = calendarError, loading = false,
                )
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

    private fun thisWeek(): LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

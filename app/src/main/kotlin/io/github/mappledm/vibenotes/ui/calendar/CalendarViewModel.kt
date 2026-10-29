package io.github.mappledm.vibenotes.ui.calendar

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
import java.time.LocalTime
import java.time.YearMonth
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

enum class CalendarMode { WEEK, MONTH }

/** The days a view shows: one week, or the whole weeks around a month (like a wall calendar). */
data class CalendarRange(val mode: CalendarMode, val start: LocalDate, val days: Int) {
    val end: LocalDate get() = start.plusDays(days.toLong() - 1)
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    companion object {
        fun week(anyDay: LocalDate): CalendarRange = CalendarRange(CalendarMode.WEEK, mondayOf(anyDay), 7)

        fun month(month: YearMonth): CalendarRange {
            val start = mondayOf(month.atDay(1))
            val end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            return CalendarRange(CalendarMode.MONTH, start, (end.toEpochDay() - start.toEpochDay() + 1).toInt())
        }

        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

data class CalendarUiState(
    val mode: CalendarMode,
    /** Monday of the week shown in the week view. */
    val weekStart: LocalDate,
    /** The month shown in the month view. */
    val month: YearMonth,
    /** The day whose plans are listed under the month grid. */
    val selected: LocalDate,
    val days: List<DayAgenda> = emptyList(),
    /** The range [days] belong to; null until the first load. */
    val loaded: CalendarRange? = null,
    /** Open tasks due before today; the week view lists them in the current week, the month view under today. */
    val overdue: List<TaskItem> = emptyList(),
    val calendarAllowed: Boolean = false,
    val calendarError: String? = null,
    val loading: Boolean = true,
) {
    val range: CalendarRange get() = if (mode == CalendarMode.WEEK) CalendarRange.week(weekStart) else CalendarRange.month(month)
}

/** Calendar events and Kanban cards with due dates, by week or by month. */
class CalendarViewModel(private val container: AppContainer) : ViewModel() {
    private val vault = container.requireVault()
    private val _state = MutableStateFlow(initialState(LocalDate.now()))
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun initialState(today: LocalDate) = CalendarUiState(
        mode = if (container.settings.current.calendarMonth) CalendarMode.MONTH else CalendarMode.WEEK,
        weekStart = CalendarRange.mondayOf(today),
        month = YearMonth.from(today),
        selected = today,
    )

    /** Switching views keeps the place: the week of the selected day, or the month of the week shown. */
    fun setMode(mode: CalendarMode) {
        if (mode == _state.value.mode) return
        container.settings.setCalendarMonth(mode == CalendarMode.MONTH)
        val today = LocalDate.now()
        _state.update { s ->
            if (mode == CalendarMode.WEEK) {
                s.copy(mode = mode, weekStart = CalendarRange.mondayOf(s.selected))
            } else {
                val inWeek = if (today in CalendarRange.week(s.weekStart)) today else s.weekStart
                s.copy(mode = mode, month = YearMonth.from(inWeek), selected = inWeek)
            }
        }
        refresh()
    }

    /** One week or one month back or forward. */
    fun shift(steps: Long) {
        val today = LocalDate.now()
        _state.update { s ->
            if (s.mode == CalendarMode.WEEK) {
                s.copy(weekStart = s.weekStart.plusWeeks(steps))
            } else {
                val month = s.month.plusMonths(steps)
                s.copy(month = month, selected = if (YearMonth.from(today) == month) today else month.atDay(1))
            }
        }
        refresh()
    }

    fun today() {
        val today = LocalDate.now()
        _state.update { it.copy(weekStart = CalendarRange.mondayOf(today), month = YearMonth.from(today), selected = today) }
        refresh()
    }

    /** Selects a day of the month grid; a day of the next or previous month opens that month. */
    fun select(date: LocalDate) {
        val month = YearMonth.from(date)
        val reload = month != _state.value.month
        _state.update { it.copy(selected = date, month = month) }
        if (reload) refresh()
    }

    /** Opens the week (or the month) of [date] with that day selected. */
    fun show(date: LocalDate) {
        _state.update { it.copy(weekStart = CalendarRange.mondayOf(date), month = YearMonth.from(date), selected = date) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val range = _state.value.range
            val zone = ZoneId.systemDefault()
            runCatching { vault.notes.refresh() }
            val allowed = container.calendar.hasPermission()
            var calendarError: String? = null
            val events = if (allowed) {
                runCatching {
                    container.calendar.events(range.start.atStartOfDay(zone).toInstant(), range.end.plusDays(1).atStartOfDay(zone).toInstant())
                }.getOrElse {
                    calendarError = it.message
                    emptyList()
                }
            } else emptyList()
            val tasks = vault.kanban.allTasks().filter { !it.card.checked && !it.laneIsComplete }
            val today = LocalDate.now()
            // The week view lists open tasks of past days once, under "Просрочено"; a month grid keeps them on their days.
            val hidePastTasks = range.mode == CalendarMode.WEEK && today in range

            val days = (0 until range.days).map { offset ->
                val date = range.start.plusDays(offset.toLong())
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
                val dayTasks = if (hidePastTasks && date.isBefore(today)) emptyList() else tasks.filter { it.card.dueDate == date }
                dayTasks.forEach { task -> task.card.dueTime?.let { timed += TimedEntry.Task(task, it) } }
                DayAgenda(
                    date = date,
                    allDay = allDay.sortedBy { it.start },
                    timed = timed.sortedBy { it.time },
                    tasks = dayTasks.filter { it.card.dueTime == null },
                )
            }
            val overdue = tasks.filter { it.card.dueDate?.isBefore(today) == true }
                .sortedWith(compareBy<TaskItem> { it.card.dueDate }.thenBy { it.card.dueTime })
            _state.update {
                // Another week or month was chosen while this one loaded; its own refresh will publish.
                if (it.range != range) it
                else it.copy(
                    days = days, loaded = range, overdue = overdue,
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
}

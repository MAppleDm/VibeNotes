package io.github.mappledm.vibenotes.ui.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What is planned, by week or by month — an overview, not a second calendar app.
 * The month view is a wall calendar like Google Calendar's, with the chosen day's plans under it.
 * Planning goes through the Inbox ("+" types the day there), details and editing open in the system Calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onOpenSettings: () -> Unit, onOpenBoard: (String) -> Unit, onOpenInbox: () -> Unit) {
    val vm: CalendarViewModel = viewModel(factory = containerFactory { CalendarViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val today = LocalDate.now()

    fun openCalendar(date: LocalDate) {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching { context.startActivity(AndroidCalendarGateway.viewTimeIntent(millis)) }
    }

    val actions = DayActions(
        onPlan = { date -> vm.planFor(date); onOpenInbox() },
        onOpenEvent = { event -> runCatching { context.startActivity(AndroidCalendarGateway.viewTimeIntent(event.start.toEpochMilli())) } },
        onCheck = vm::complete,
        onOpenBoard = onOpenBoard,
        onAllowCalendar = { permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS) },
        onOpenCalendar = ::openCalendar,
    )

    OnResume { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title(state, today), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle(state, today),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    val week = state.mode == CalendarMode.WEEK
                    TodayButton(today, onClick = vm::today)
                    IconButton(onClick = { vm.shift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (week) "Предыдущая неделя" else "Предыдущий месяц")
                    }
                    IconButton(onClick = { vm.shift(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (week) "Следующая неделя" else "Следующий месяц")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Настройки") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            ModeSwitch(state.mode, vm::setMode)
            when (state.mode) {
                CalendarMode.WEEK -> WeekView(state, today, actions)
                CalendarMode.MONTH -> MonthView(state, today, actions, onSelect = vm::select)
            }
        }
    }
}

/** Everything a day can do, shared by the week and the month view. */
private class DayActions(
    val onPlan: (LocalDate) -> Unit,
    val onOpenEvent: (CalendarEvent) -> Unit,
    val onCheck: (TaskItem) -> Unit,
    val onOpenBoard: (String) -> Unit,
    val onAllowCalendar: () -> Unit,
    val onOpenCalendar: (LocalDate) -> Unit,
)

/** Google Calendar's "today" button: a small page with today's date on it. */
@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier
                .size(22.dp)
                .border(1.5.dp, color, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                today.dayOfMonth.toString(),
                style = TextStyle(fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold),
                color = color,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: CalendarMode, onMode: (CalendarMode) -> Unit) {
    val options = listOf(
        Triple(CalendarMode.WEEK, "Неделя", Icons.Outlined.CalendarViewWeek),
        Triple(CalendarMode.MONTH, "Месяц", Icons.Outlined.CalendarViewMonth),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label, icon) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { SegmentedButtonDefaults.Icon(mode == value) { Icon(icon, null, Modifier.size(18.dp)) } },
                label = { Text(label) },
            )
        }
    }
}

// --- week -----------------------------------------------------------------------------------

@Composable
private fun WeekView(state: CalendarUiState, today: LocalDate, actions: DayActions) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(state, today) { weekRows(state, today) }

    // The current week opens at today (or at what is overdue, right above it); other weeks at the top.
    LaunchedEffect(state.loaded) {
        val week = state.loaded?.takeIf { it.mode == CalendarMode.WEEK } ?: return@LaunchedEffect
        val target = if (today in week) {
            rows.indexOfFirst { it is WeekRow.Overdue || (it is WeekRow.Day && it.day.date == today) }
        } else 0
        listState.scrollToItem(target.coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        WeekStrip(state, today) { date ->
            val index = rows.indexOfFirst { it is WeekRow.Day && it.day.date == date }
            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            // Top padding equals the item spacing, so a day scrolled to the top hides the one above it.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    WeekRow.Permission -> PermissionCard(actions.onAllowCalendar)
                    is WeekRow.Error -> ErrorText(row.text)
                    is WeekRow.Overdue -> OverdueCard(row.tasks, actions)
                    is WeekRow.Day -> DayBlock(row.day, today, actions)
                    is WeekRow.Footer -> Footer(
                        emptyHint = if (row.weekIsEmpty) "Неделя свободна. Напишите во «Входящие», например «в четверг в 15:00 стоматолог», — и событие появится здесь." else null,
                    ) {
                        actions.onOpenCalendar(if (today in state.range) today else state.weekStart)
                    }
                }
            }
        }
    }
}

private sealed interface WeekRow {
    val key: String

    data object Permission : WeekRow {
        override val key = "permission"
    }

    data class Error(val text: String) : WeekRow {
        override val key = "error"
    }

    data class Overdue(val tasks: List<TaskItem>) : WeekRow {
        override val key = "overdue"
    }

    data class Day(val day: DayAgenda) : WeekRow {
        override val key = "day:${day.date}"
    }

    data class Footer(val weekIsEmpty: Boolean) : WeekRow {
        override val key = "footer"
    }
}

/** Past days, then what is still overdue, then today and the rest: the list reads in time order. */
private fun weekRows(state: CalendarUiState, today: LocalDate): List<WeekRow> = buildList {
    if (state.loading || state.loaded != state.range) return@buildList
    if (!state.calendarAllowed) add(WeekRow.Permission)
    state.calendarError?.let { add(WeekRow.Error(it)) }
    val overdue = if (today in state.range) state.overdue else emptyList()
    val (past, rest) = state.days.partition { it.date.isBefore(today) }
    past.forEach { add(WeekRow.Day(it)) }
    if (overdue.isNotEmpty()) add(WeekRow.Overdue(overdue))
    rest.forEach { add(WeekRow.Day(it)) }
    add(WeekRow.Footer(weekIsEmpty = overdue.isEmpty() && state.days.all { it.isEmpty }))
}

/** Seven days at a glance; a dot per calendar colour and a ring for tasks. Tap to jump to the day. */
@Composable
private fun WeekStrip(state: CalendarUiState, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val byDate = if (state.loaded == state.range) state.days.associateBy { it.date } else emptyMap()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (offset in 0L until 7L) {
            val date = state.weekStart.plusDays(offset)
            val isToday = date == today
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onDay(date) }
                    .alpha(if (date.isBefore(today)) PAST_ALPHA else 1f)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Dates.weekdayShort(date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                DayNumber(date, isToday, size = 34)
                Spacer(Modifier.height(4.dp))
                DayDots(byDate[date])
            }
        }
    }
}

@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, size: Int, selected: Boolean = false) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (selected && !isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = if (size >= 30) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DayDots(day: DayAgenda?) {
    val primary = MaterialTheme.colorScheme.primary
    val colors = day?.let { d ->
        (d.allDay + d.timed.filterIsInstance<TimedEntry.Event>().map { it.event })
            .map { it.color?.let { c -> Color(c) } ?: primary }
            .distinct()
            .take(3)
    }.orEmpty()
    val hasTasks = day != null && (day.tasks.isNotEmpty() || day.timed.any { it is TimedEntry.Task })
    Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(it)) }
        if (hasTasks) Box(Modifier.size(5.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
    }
}

// --- month ----------------------------------------------------------------------------------

/** A wall calendar: whole weeks around the month, chips for what is planned, the chosen day's plans below. */
@Composable
private fun MonthView(state: CalendarUiState, today: LocalDate, actions: DayActions, onSelect: (LocalDate) -> Unit) {
    val loaded = state.loaded == state.range
    val selectedDay = state.days.firstOrNull { it.date == state.selected }.takeIf { loaded }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "grid") { MonthGrid(state, today, onSelect) }
        if (loaded && !state.calendarAllowed) {
            item(key = "permission") { Box(Modifier.padding(horizontal = 16.dp)) { PermissionCard(actions.onAllowCalendar) } }
        }
        state.calendarError?.let { item(key = "error") { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(it) } } }
        if (loaded && state.selected == today && state.overdue.isNotEmpty()) {
            item(key = "overdue") { Box(Modifier.padding(horizontal = 16.dp)) { OverdueCard(state.overdue, actions) } }
        }
        selectedDay?.let { day ->
            item(key = "day:${day.date}") { Box(Modifier.padding(horizontal = 16.dp)) { DayBlock(day, today, actions, compactWhenFree = false) } }
        }
        item(key = "footer") { Footer(emptyHint = null) { actions.onOpenCalendar(state.selected) } }
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val range = state.range
    val byDate = if (state.loaded == range) state.days.associateBy { it.date } else emptyMap()
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (offset in 0L until 7L) {
                Text(
                    Dates.weekdayShort(range.start.plusDays(offset)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (week in 0 until range.days / 7) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(MONTH_CELL_HEIGHT)
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
            ) {
                for (weekday in 0 until 7) {
                    val date = range.start.plusDays(week * 7L + weekday)
                    MonthCell(
                        date = date,
                        day = byDate[date],
                        inMonth = YearMonth.from(date) == state.month,
                        isToday = date == today,
                        selected = date == state.selected,
                        past = date.isBefore(today),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** One thing in a month cell: an event in its calendar's colour or an outlined task. */
private data class CellEntry(val title: String, val color: Color?, val isTask: Boolean)

@Composable
private fun MonthCell(
    date: LocalDate,
    day: DayAgenda?,
    inMonth: Boolean,
    isToday: Boolean,
    selected: Boolean,
    past: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val entries = day?.let { d ->
        d.allDay.map { CellEntry(it.title, it.color?.let { c -> Color(c) } ?: primary, isTask = false) } +
            d.timed.map { entry ->
                when (entry) {
                    is TimedEntry.Event -> CellEntry(entry.event.title, entry.event.color?.let { c -> Color(c) } ?: primary, isTask = false)
                    is TimedEntry.Task -> CellEntry(entry.task.card.title, null, isTask = true)
                }
            } +
            d.tasks.map { CellEntry(it.card.title, null, isTask = true) }
    }.orEmpty()
    // Three lines fit: all three entries, or two and "+N".
    val shown = if (entries.size > MONTH_CELL_LINES) entries.take(MONTH_CELL_LINES - 1) else entries
    Column(
        modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .alpha(if (!inMonth) OTHER_MONTH_ALPHA else if (past) PAST_ALPHA else 1f)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DayNumber(date, isToday, size = 24, selected = selected)
        Spacer(Modifier.height(2.dp))
        shown.forEach { CellChip(it) }
        if (entries.size > shown.size) {
            Text(
                "+${entries.size - shown.size}",
                style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp),
            )
        }
    }
}

@Composable
private fun CellChip(entry: CellEntry) {
    val shape = RoundedCornerShape(3.dp)
    val chip = Modifier.fillMaxWidth().padding(top = 1.dp).height(13.dp).clip(shape)
    val background = entry.color
    Text(
        entry.title,
        style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
        color = when {
            background == null -> MaterialTheme.colorScheme.onSurface
            background.luminance() > 0.45f -> Color.Black
            else -> Color.White
        },
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = if (background != null) {
            chip.background(background).padding(horizontal = 3.dp)
        } else {
            chip.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, shape).padding(horizontal = 3.dp)
        },
    )
}

// --- days -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayBlock(day: DayAgenda, today: LocalDate, actions: DayActions, compactWhenFree: Boolean = true) {
    val isToday = day.date == today
    val past = day.date.isBefore(today)
    val onPlan = { actions.onPlan(day.date) }.takeUnless { past }
    // A free day is a single line, so a quiet week stays short and still shows every day.
    if (day.isEmpty && !isToday && compactWhenFree) {
        DayHeader(day.date, isToday = false, note = "свободно", onPlan = onPlan, modifier = Modifier.alpha(if (past) PAST_ALPHA else 1f))
        return
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (past) PAST_ALPHA else 1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primaryContainer) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape,
            )
            .padding(bottom = 8.dp),
    ) {
        DayHeader(day.date, isToday, note = null, onPlan = onPlan)
        if (day.isEmpty) {
            Text(
                if (past) "Ничего не было запланировано." else "Свободный день. Нажмите +, чтобы записать план во «Входящие».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        if (day.allDay.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                day.allDay.forEach { AllDayChip(it) { actions.onOpenEvent(it) } }
            }
        }
        val now = Instant.now()
        day.timed.forEach { entry ->
            when (entry) {
                is TimedEntry.Event -> EventEntry(entry, now, dimFinished = isToday) { actions.onOpenEvent(entry.event) }
                is TimedEntry.Task -> TaskEntry(entry.task, Dates.timeOf(entry.time), onCheck = { actions.onCheck(entry.task) }, onOpen = { actions.onOpenBoard(entry.task.boardPath) })
            }
        }
        day.tasks.forEach { task ->
            TaskEntry(task, null, onCheck = { actions.onCheck(task) }, onOpen = { actions.onOpenBoard(task.boardPath) })
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, isToday: Boolean, note: String?, onPlan: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Dates.weekday(date), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Text(Dates.dayMonthLong(date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isToday) {
            Spacer(Modifier.width(8.dp))
            Pill("сегодня")
        }
        Spacer(Modifier.weight(1f))
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onPlan != null) {
            IconButton(onClick = onPlan, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Add, "Запланировать на ${Dates.dayMonthLong(date)}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AllDayChip(event: CalendarEvent, onClick: () -> Unit) {
    val color = eventColor(event)
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(event.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EventEntry(entry: TimedEntry.Event, now: Instant, dimFinished: Boolean, onClick: () -> Unit) {
    val event = entry.event
    val ongoing = !now.isBefore(event.start) && now.isBefore(event.end)
    val finished = dimFinished && !event.end.isAfter(now)
    val (top, bottom) = when {
        entry.startsBefore -> "до" to Dates.time(event.end)
        entry.endsAfter -> Dates.time(event.start) to "→ " + Dates.weekdayShort(event.end.atZone(ZoneId.systemDefault()).toLocalDate())
        else -> Dates.time(event.start) to Dates.time(event.end)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .alpha(if (finished) FINISHED_ALPHA else 1f)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        TimeLabel(top, bottom)
        Box(Modifier.width(MARKER_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(eventColor(event)))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ongoing) {
                    Spacer(Modifier.width(8.dp))
                    Pill("сейчас")
                }
            }
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TaskEntry(
    task: TaskItem,
    time: String?,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
    timeColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeLabel(time.orEmpty(), null, color = timeColor)
        // The whole row opens the board; a smaller check keeps it aligned with the event bars.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MARKER_WIDTH) {
            Box(Modifier.width(MARKER_WIDTH), contentAlignment = Alignment.Center) {
                Checkbox(checked = false, onCheckedChange = { onCheck() })
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(task.card.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${task.boardTitle} · ${task.laneTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TimeLabel(top: String, bottom: String?, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.width(TIME_WIDTH)) {
        Text(top, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        bottom?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

// --- the rest -------------------------------------------------------------------------------

@Composable
private fun OverdueCard(tasks: List<TaskItem>, actions: DayActions) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), shape)
            .padding(bottom = 8.dp),
    ) {
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Просрочено", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text(tasks.size.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        tasks.forEach { task ->
            TaskEntry(
                task,
                time = task.card.dueDate?.let { Dates.dayMonth(it) },
                onCheck = { actions.onCheck(task) },
                onOpen = { actions.onOpenBoard(task.boardPath) },
                timeColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PermissionCard(onAllow: () -> Unit) {
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Покажу события календаря рядом с задачами", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAllow) { Text("Разрешить") }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Footer(emptyHint: String?, onOpenCalendar: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        emptyHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        TextButton(onClick = onOpenCalendar) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Открыть в Календаре")
        }
    }
}

@Composable
private fun eventColor(event: CalendarEvent): Color = event.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

private fun title(state: CalendarUiState, today: LocalDate): String =
    if (state.mode == CalendarMode.WEEK) weekTitle(state.weekStart) else Dates.monthYear(state.month.atDay(1), today)

private fun subtitle(state: CalendarUiState, today: LocalDate): String =
    if (state.mode == CalendarMode.WEEK) weekSubtitle(state.weekStart, today) else monthSubtitle(state.month, today)

/** "19–25 октября" or "29 сент – 5 окт". */
private fun weekTitle(start: LocalDate): String {
    val end = start.plusDays(6)
    return if (start.month == end.month) "${start.dayOfMonth}–${Dates.dayMonthLong(end)}"
    else "${Dates.dayMonth(start)} – ${Dates.dayMonth(end)}"
}

/** "эта неделя", "через 3 недели", "прошлая неделя · 2025". */
private fun weekSubtitle(start: LocalDate, today: LocalDate): String {
    val weeks = ChronoUnit.WEEKS.between(CalendarRange.mondayOf(today), start)
    val relative = when {
        weeks == 0L -> "эта неделя"
        weeks == 1L -> "следующая неделя"
        weeks == -1L -> "прошлая неделя"
        weeks > 0 -> "через $weeks ${plural(weeks, "неделю", "недели", "недель")}"
        else -> "${-weeks} ${plural(-weeks, "неделю", "недели", "недель")} назад"
    }
    val end = start.plusDays(6)
    return if (end.year != today.year || start.year != today.year) "$relative · ${end.year}" else relative
}

/** "этот месяц", "через 2 месяца", "5 месяцев назад". */
private fun monthSubtitle(month: YearMonth, today: LocalDate): String {
    val months = ChronoUnit.MONTHS.between(YearMonth.from(today), month)
    return when {
        months == 0L -> "этот месяц"
        months == 1L -> "следующий месяц"
        months == -1L -> "прошлый месяц"
        months > 0 -> "через $months ${plural(months, "месяц", "месяца", "месяцев")}"
        else -> "${-months} ${plural(-months, "месяц", "месяца", "месяцев")} назад"
    }
}

private fun plural(n: Long, one: String, few: String, many: String): String = when {
    n % 10 == 1L && n % 100 != 11L -> one
    n % 10 in 2L..4L && n % 100 !in 12L..14L -> few
    else -> many
}

private const val PAST_ALPHA = 0.6f
private const val FINISHED_ALPHA = 0.55f
private const val OTHER_MONTH_ALPHA = 0.4f
private const val MONTH_CELL_LINES = 3
private val MONTH_CELL_HEIGHT = 76.dp
private val TIME_WIDTH = 46.dp
private val MARKER_WIDTH = 32.dp

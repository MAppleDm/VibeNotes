package io.github.mappledm.vibenotes.ui.week

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(onOpenDrawer: () -> Unit, onOpenBoard: (String) -> Unit) {
    val vm: WeekViewModel = viewModel(factory = containerFactory { WeekViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val today = LocalDate.now()

    OnResume { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val end = state.weekStart.plusDays(6)
                    Text("${Dates.dayMonth(state.weekStart)} – ${Dates.dayMonth(end)}")
                },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Меню") } },
                actions = {
                    IconButton(onClick = { vm.shiftWeek(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущая неделя") }
                    IconButton(onClick = vm::today) { Icon(Icons.Outlined.Today, "Эта неделя") }
                    IconButton(onClick = { vm.shiftWeek(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая неделя") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!state.calendarAllowed && !state.loading) {
                item {
                    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Покажу события календаря рядом с задачами", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS) }) { Text("Разрешить") }
                        }
                    }
                }
            }
            state.calendarError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.overdue.isNotEmpty()) {
                item { DayHeader("Просрочено", highlight = false, error = true) }
                items(state.overdue, key = { "overdue:${it.boardPath}:${it.ref.lane}:${it.ref.index}" }) { task ->
                    TaskRow(task, showDate = true, onCheck = { vm.complete(task) }, onOpen = { onOpenBoard(task.boardPath) })
                }
            }
            state.days.forEach { day ->
                item(key = "day:${day.date}") {
                    DayHeader(Dates.long(day.date) + if (day.date == today) " · сегодня" else "", highlight = day.date == today)
                }
                if (day.events.isEmpty() && day.tasks.isEmpty()) {
                    item(key = "empty:${day.date}") {
                        Text("—", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    }
                }
                items(day.events, key = { "event:${day.date}:${it.eventId}:${it.start}" }) { event ->
                    EventRow(event) {
                        runCatching { context.startActivity(AndroidCalendarGateway.viewTimeIntent(event.start.toEpochMilli())) }
                    }
                }
                items(day.tasks, key = { "task:${day.date}:${it.boardPath}:${it.ref.lane}:${it.ref.index}" }) { task ->
                    TaskRow(task, showDate = false, onCheck = { vm.complete(task) }, onOpen = { onOpenBoard(task.boardPath) })
                }
            }
        }
    }
}

@Composable
private fun DayHeader(text: String, highlight: Boolean, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = when {
            error -> MaterialTheme.colorScheme.error
            highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(event.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (event.allDay) "весь день" else "${Dates.time(event.start)}–${Dates.time(event.end)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            event.location?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, showDate: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = false, onCheckedChange = { onCheck() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(task.card.title, style = MaterialTheme.typography.bodyLarge)
            val meta = buildString {
                append(task.boardTitle).append(" · ").append(task.laneTitle)
                if (showDate) task.card.dueDate?.let { append(" · ").append(Dates.short(it)) }
                task.card.dueTime?.let { append(" · ").append(it) }
            }
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

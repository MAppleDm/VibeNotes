package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.BoardInfo
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.components.DatePickerModal
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.ReminderOptions
import io.github.mappledm.vibenotes.ui.components.Selector
import io.github.mappledm.vibenotes.ui.components.TimePickerModal
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Lets the user correct what the assistant proposed before confirming it. */
@Composable
fun ProposalEditorDialog(
    proposal: ActionProposal,
    calendars: List<CalendarInfo>,
    boards: List<BoardInfo>,
    onDismiss: () -> Unit,
    onSave: (ActionProposal, (String?) -> Unit) -> Unit,
) {
    var draft by remember(proposal) { mutableStateOf(proposal) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val p = draft) {
                    is ActionProposal.CreateEvent -> EventFields(p, calendars) { draft = it }
                    is ActionProposal.AddTask -> TaskFields(p, boards) { draft = it }
                    is ActionProposal.CreateNote -> {
                        OutlinedTextField(p.title, { draft = p.copy(title = it) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(p.folder, { draft = p.copy(folder = it) }, label = { Text("Папка") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(p.content, { draft = p.copy(content = it) }, label = { Text("Текст") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    }
                    is ActionProposal.AppendToNote -> {
                        Text("В «${VaultPaths.baseName(p.path)}»", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(p.text, { draft = p.copy(text = it) }, label = { Text("Текст") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(draft) { problem ->
                    if (problem == null) onDismiss() else error = problem
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun EventFields(p: ActionProposal.CreateEvent, calendars: List<CalendarInfo>, onChange: (ActionProposal.CreateEvent) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(p.date) }.getOrDefault(LocalDate.now())
    val start = ActionProposal.parseTime(p.startTime) ?: LocalTime.of(9, 0)
    val end = ActionProposal.parseTime(p.endTime) ?: start.plusHours(1)

    OutlinedTextField(p.title, { onChange(p.copy(title = it)) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) { Text(Dates.long(date)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Весь день", modifier = Modifier.weight(1f))
        Switch(
            checked = p.allDay,
            onCheckedChange = { allDay ->
                onChange(
                    if (allDay) p.copy(allDay = true, startTime = null, endTime = null, endDate = null)
                    else p.copy(allDay = false, startTime = "09:00", endTime = "10:00", endDate = null),
                )
            },
        )
    }
    if (!p.allDay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickStart = true }) { Text(hhmm(start)) }
            Text("  —  ")
            OutlinedButton(onClick = { pickEnd = true }) { Text(hhmm(end)) }
        }
    }
    OutlinedTextField(p.location.orEmpty(), { onChange(p.copy(location = it.ifBlank { null })) }, label = { Text("Место") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (calendars.isNotEmpty()) {
        Text("Календарь", style = MaterialTheme.typography.labelLarge)
        val selected = p.calendarId ?: calendars.firstOrNull { it.isPrimary }?.id ?: calendars.first().id
        Selector(calendars.map { it.id to "${it.name} (${it.accountName})" }, selected, { onChange(p.copy(calendarId = it)) })
    }
    Text("Напоминание", style = MaterialTheme.typography.labelLarge)
    Selector(ReminderOptions.map { it to reminderLabel(it) }, p.reminderMinutes, { onChange(p.copy(reminderMinutes = it)) })

    if (pickDate) DatePickerModal(date, { onChange(p.copy(date = it.toString(), endDate = null)) }, { pickDate = false })
    if (pickStart) TimePickerModal(start, { newStart ->
        // Keep the duration when the start moves.
        val minutes = ChronoUnit.MINUTES.between(start, end).let { if (it <= 0) 60 else it }
        val newEnd = newStart.plusMinutes(minutes)
        onChange(p.copy(startTime = hhmm(newStart), endTime = hhmm(newEnd), endDate = if (newEnd < newStart) date.plusDays(1).toString() else null))
    }, { pickStart = false })
    if (pickEnd) TimePickerModal(end, { newEnd ->
        onChange(p.copy(endTime = hhmm(newEnd), endDate = if (!newEnd.isAfter(start)) date.plusDays(1).toString() else null))
    }, { pickEnd = false })
}

@Composable
private fun TaskFields(p: ActionProposal.AddTask, boards: List<BoardInfo>, onChange: (ActionProposal.AddTask) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    OutlinedTextField(p.title, { onChange(p.copy(title = it)) }, label = { Text("Задача") }, modifier = Modifier.fillMaxWidth())
    if (boards.isNotEmpty()) {
        Text("Доска", style = MaterialTheme.typography.labelLarge)
        Selector(boards.map { it.path to it.title }, p.boardPath, { path ->
            val lane = boards.firstOrNull { it.path == path }?.lanes?.firstOrNull() ?: p.lane
            onChange(p.copy(boardPath = path, lane = lane))
        }, placeholder = VaultPaths.baseName(p.boardPath))
        val lanes = boards.firstOrNull { it.path == p.boardPath }?.lanes.orEmpty()
        if (lanes.isNotEmpty()) {
            Text("Колонка", style = MaterialTheme.typography.labelLarge)
            Selector(lanes.map { it to it }, p.lane, { onChange(p.copy(lane = it)) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val due = p.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        OutlinedButton(onClick = { pickDate = true }) { Text(due?.let { "Срок: ${Dates.short(it)}" } ?: "Добавить срок") }
        if (due != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onChange(p.copy(dueDate = null, dueTime = null)) }) { Text("Убрать") }
        }
    }
    if (pickDate) DatePickerModal(
        p.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        { onChange(p.copy(dueDate = it.toString())) },
        { pickDate = false },
    )
}

private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

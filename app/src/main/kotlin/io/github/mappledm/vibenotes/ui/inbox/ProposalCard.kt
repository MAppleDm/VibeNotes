package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.EventSnapshot
import io.github.mappledm.vibenotes.core.agent.ResultTarget
import io.github.mappledm.vibenotes.core.agent.TaskSnapshot
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.inbox.ProposalItem
import io.github.mappledm.vibenotes.core.inbox.ProposalStatus
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.Selector
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import java.time.LocalDate

/**
 * Confirmation card for an action the assistant proposed. Nothing happens
 * until the user taps the primary button; the result shown is what the system reported.
 */
@Composable
fun ProposalCard(
    item: ProposalItem,
    calendars: List<CalendarInfo>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenInCalendarApp: () -> Unit,
    selectedCalendar: CalendarInfo?,
    needsCalendarChoice: Boolean,
    onChooseCalendar: (Long) -> Unit,
    onLoadCalendars: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = item.proposal
    val dimmed = item.status == ProposalStatus.DISMISSED
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(p), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(kindLabel(p), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            val contentColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            when (p) {
                is ActionProposal.CreateEvent -> EventDetails(
                    p = p,
                    calendars = calendars,
                    selected = selectedCalendar,
                    editable = item.status == ProposalStatus.PENDING || item.status == ProposalStatus.FAILED,
                    color = contentColor,
                    onChoose = onChooseCalendar,
                    onLoad = onLoadCalendars,
                )
                is ActionProposal.AddTask -> {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                    Detail("Доска «${VaultPaths.baseName(p.boardPath)}» → ${p.lane}")
                    p.dueDate?.let { d ->
                        val date = runCatching { LocalDate.parse(d) }.getOrNull()
                        Detail("Срок: " + (date?.let { Dates.relativeDay(it) } ?: d) + (p.dueTime?.let { ", $it" } ?: ""))
                    }
                }
                is ActionProposal.CreateNote -> {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                    if (p.folder.isNotEmpty()) Detail("Папка: ${p.folder}")
                    if (p.content.isNotBlank()) {
                        Text(p.content.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ActionProposal.AppendToNote -> {
                    Text("В «${VaultPaths.baseName(p.path)}»", style = MaterialTheme.typography.titleMedium, color = contentColor)
                    Text(p.text.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ActionProposal.UpdateEvent -> {
                    Text(p.after.title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                    if (p.before.title != p.after.title) Detail("Название: ${p.before.title} → ${p.after.title}")
                    Detail("Было: ${eventWhen(p.before)}")
                    Detail("Станет: ${eventWhen(p.after)}")
                    if (p.before.location != p.after.location) {
                        Detail("Место: ${p.before.location ?: "—"} → ${p.after.location ?: "—"}")
                    } else {
                        p.after.location?.let { DetailWithIcon(Icons.Outlined.Place, it) }
                    }
                    if (p.before.reminderMinutes != p.after.reminderMinutes) {
                        Detail("Напоминание: ${reminderLabel(p.before.reminderMinutes)} → ${reminderLabel(p.after.reminderMinutes)}")
                    }
                    if (p.before.description != p.after.description) Detail("Описание изменено")
                }
                is ActionProposal.DeleteEvent -> {
                    Text(p.event.title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                    Detail(eventWhen(p.event))
                    Detail("Событие будет удалено из календаря")
                }
                is ActionProposal.UpdateTask -> {
                    Text(p.after.title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                    Detail("Доска «${VaultPaths.baseName(p.after.boardPath)}» → ${p.after.lane}")
                    if (p.before.title != p.after.title) Detail("Название: ${p.before.title} → ${p.after.title}")
                    if (p.before.dueDate != p.after.dueDate || p.before.dueTime != p.after.dueTime) {
                        Detail("Срок: ${taskDue(p.before)} → ${taskDue(p.after)}")
                    }
                    if (p.before.done != p.after.done) Detail(if (p.after.done) "Отметить готовой" else "Вернуть в работу")
                }
            }
            Spacer(Modifier.size(2.dp))
            Footer(item, needsCalendarChoice, onConfirm, onDismiss, onEdit, onOpen, onUndo, onRequestPermission, onOpenInCalendarApp)
        }
    }
}

@Composable
private fun EventDetails(
    p: ActionProposal.CreateEvent,
    calendars: List<CalendarInfo>,
    selected: CalendarInfo?,
    editable: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onChoose: (Long) -> Unit,
    onLoad: () -> Unit,
) {
    Text(p.title, style = MaterialTheme.typography.titleMedium, color = color)
    val date = runCatching { LocalDate.parse(p.date) }.getOrNull()
    val day = date?.let { Dates.short(it) + if (it == LocalDate.now()) " (сегодня)" else if (it == LocalDate.now().plusDays(1)) " (завтра)" else "" } ?: p.date
    val time = if (p.allDay) "весь день" else {
        val endDay = p.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        "${p.startTime}–${p.endTime ?: ""}" + (endDay?.let { " (${Dates.short(it)})" } ?: "")
    }
    Text("$day · $time", style = MaterialTheme.typography.bodyLarge, color = color)
    p.location?.let { DetailWithIcon(Icons.Outlined.Place, it) }
    if (p.reminderMinutes != null) DetailWithIcon(Icons.Outlined.Notifications, "Напоминание ${reminderLabel(p.reminderMinutes)}")
    when {
        !editable -> Detail("Календарь: " + (selected?.label ?: "—"))
        calendars.isEmpty() -> TextButton(onClick = onLoad, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Outlined.Event, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Выбрать календарь и аккаунт")
        }
        else -> Selector(
            options = calendars.map { it.id to it.label },
            selected = selected?.id ?: -1L,
            onSelect = onChoose,
            placeholder = "Выберите календарь",
        )
    }
}

@Composable
private fun Footer(
    item: ProposalItem,
    needsCalendarChoice: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenInCalendarApp: () -> Unit,
) {
    val canEdit = item.proposal !is ActionProposal.DeleteEvent
    when (item.status) {
        ProposalStatus.PENDING -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
            if (needsCalendarChoice) {
                Text("На телефоне несколько аккаунтов — выберите календарь выше", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                if (canEdit) TextButton(onClick = onEdit) { Text("Изменить") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onConfirm, enabled = !needsCalendarChoice) { Text(confirmLabel(item.proposal)) }
            }
        }
        ProposalStatus.APPLYING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Выполняю…", style = MaterialTheme.typography.bodyMedium)
        }
        ProposalStatus.APPLIED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = LocalExtraColors.current.success)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.resultMessage ?: "Готово", style = MaterialTheme.typography.bodyMedium)
                    if (item.autoApplied) {
                        Text(
                            "Без подтверждения — так настроено в «Настройки → Ассистент»",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.undoError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (item.target != null) {
                    TextButton(onClick = onOpen) { Text(if (item.target is ResultTarget.Event) "В календаре" else "Открыть") }
                }
            }
            if (item.undo != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onUndo) { Text("Отменить") }
                }
            }
        }
        ProposalStatus.UNDONE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(item.resultMessage ?: "Отменено", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProposalStatus.DISMISSED -> Text("Отменено", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProposalStatus.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(item.resultMessage ?: "Ошибка", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val calendarProposal = item.proposal is ActionProposal.CreateEvent ||
                    item.proposal is ActionProposal.UpdateEvent ||
                    item.proposal is ActionProposal.DeleteEvent
                if (item.needsPermission && calendarProposal) {
                    if (item.proposal is ActionProposal.CreateEvent) {
                        TextButton(onClick = onOpenInCalendarApp) { Text("Через Календарь") }
                    }
                    Button(onClick = onRequestPermission) { Text("Разрешить доступ") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    if (canEdit) TextButton(onClick = onEdit) { Text("Изменить") }
                    Button(onClick = onConfirm, enabled = !needsCalendarChoice) { Text("Повторить") }
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailWithIcon(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Detail(text)
    }
}

private fun iconFor(p: ActionProposal): ImageVector = when (p) {
    is ActionProposal.CreateEvent -> Icons.Outlined.Event
    is ActionProposal.UpdateEvent -> Icons.Outlined.Event
    is ActionProposal.DeleteEvent -> Icons.Outlined.Event
    is ActionProposal.AddTask -> Icons.Outlined.TaskAlt
    is ActionProposal.UpdateTask -> Icons.Outlined.TaskAlt
    is ActionProposal.CreateNote -> Icons.AutoMirrored.Outlined.NoteAdd
    is ActionProposal.AppendToNote -> Icons.AutoMirrored.Outlined.Article
}

private fun kindLabel(p: ActionProposal): String = when (p) {
    is ActionProposal.CreateEvent -> "Событие в календаре"
    is ActionProposal.UpdateEvent -> "Перенести событие"
    is ActionProposal.DeleteEvent -> "Удалить событие"
    is ActionProposal.AddTask -> "Задача"
    is ActionProposal.UpdateTask -> "Изменить задачу"
    is ActionProposal.CreateNote -> "Новая заметка"
    is ActionProposal.AppendToNote -> "Дописать в заметку"
}

private fun confirmLabel(p: ActionProposal): String = when (p) {
    is ActionProposal.CreateEvent -> "В календарь"
    is ActionProposal.UpdateEvent -> "Перенести"
    is ActionProposal.DeleteEvent -> "Удалить"
    is ActionProposal.AddTask -> "Добавить"
    is ActionProposal.UpdateTask -> "Сохранить"
    is ActionProposal.CreateNote -> "Создать"
    is ActionProposal.AppendToNote -> "Дописать"
}

private fun eventWhen(s: EventSnapshot): String {
    val date = runCatching { LocalDate.parse(s.date) }.getOrNull()?.let { Dates.short(it) } ?: s.date
    return if (s.allDay) "$date · весь день" else "$date · ${s.startTime ?: ""}–${s.endTime ?: ""}"
}

private fun taskDue(s: TaskSnapshot): String {
    val date = s.dueDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull()?.let { Dates.short(it) } ?: raw }
        ?: return "без срока"
    return date + (s.dueTime?.let { ", $it" } ?: "")
}

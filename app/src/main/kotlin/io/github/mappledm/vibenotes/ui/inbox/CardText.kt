package io.github.mappledm.vibenotes.ui.inbox

import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.EventSnapshot
import io.github.mappledm.vibenotes.core.agent.TaskSnapshot
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import java.time.Duration
import java.time.LocalDate

/** Short words about a card: the one line it folds into, and what a correction changed. */
internal object CardText {

    fun title(p: ActionProposal): String = when (p) {
        is ActionProposal.CreateEvent -> p.title
        is ActionProposal.UpdateEvent -> p.after.title
        is ActionProposal.DeleteEvent -> p.event.title
        is ActionProposal.AddTask -> p.title
        is ActionProposal.UpdateTask -> p.after.title
        is ActionProposal.CreateNote -> p.title
        is ActionProposal.AppendToNote -> "В «${VaultPaths.baseName(p.path)}»"
    }

    /** "Встреча с Андреем · чт, 29 окт, 16:00" */
    fun subject(p: ActionProposal): String = listOfNotNull(title(p), whenOf(p)).joinToString(" · ")

    /** The day the card puts something on, if any: where the calendar shows it as a draft. */
    fun day(p: ActionProposal): LocalDate? = when (p) {
        is ActionProposal.CreateEvent -> parse(p.date)
        is ActionProposal.UpdateEvent -> parse(p.after.date)
        is ActionProposal.AddTask -> p.dueDate?.let(::parse)
        else -> null
    }

    private fun whenOf(p: ActionProposal): String? = when (p) {
        is ActionProposal.CreateEvent -> event(fields(p))
        is ActionProposal.UpdateEvent -> event(fields(p.after))
        is ActionProposal.DeleteEvent -> event(fields(p.event))
        is ActionProposal.AddTask -> p.dueDate?.let { due(it, p.dueTime) }
        is ActionProposal.UpdateTask -> p.after.dueDate?.let { due(it, p.after.dueTime) }
        is ActionProposal.CreateNote, is ActionProposal.AppendToNote -> null
    }

    /** What changed between the first version of a card and its corrected one: "15:00 → 16:00", "1 ч → 30 мин". */
    fun changes(original: ActionProposal, current: ActionProposal): List<String> = when {
        original is ActionProposal.CreateEvent && current is ActionProposal.CreateEvent -> eventChanges(fields(original), fields(current))
        original is ActionProposal.UpdateEvent && current is ActionProposal.UpdateEvent -> eventChanges(fields(original.after), fields(current.after))
        original is ActionProposal.AddTask && current is ActionProposal.AddTask -> buildList {
            if (original.title != current.title) add("«${original.title}» → «${current.title}»")
            if (original.boardPath != current.boardPath) add("доска «${VaultPaths.baseName(current.boardPath)}»")
            if (original.lane != current.lane) add("${original.lane} → ${current.lane}")
            if (original.dueDate != current.dueDate || original.dueTime != current.dueTime) {
                add("${original.dueDate?.let { due(it, original.dueTime) } ?: "без срока"} → ${current.dueDate?.let { due(it, current.dueTime) } ?: "без срока"}")
            }
        }
        original is ActionProposal.UpdateTask && current is ActionProposal.UpdateTask -> taskChanges(original.after, current.after)
        original is ActionProposal.CreateNote && current is ActionProposal.CreateNote -> buildList {
            if (original.title != current.title) add("«${original.title}» → «${current.title}»")
            if (original.folder != current.folder) add("папка «${current.folder.ifEmpty { "корень" }}»")
            if (original.content != current.content) add("текст изменён")
        }
        original is ActionProposal.AppendToNote && current is ActionProposal.AppendToNote -> buildList {
            if (original.path != current.path) add("в «${VaultPaths.baseName(current.path)}»")
            if (original.text != current.text) add("текст изменён")
        }
        original is ActionProposal.DeleteEvent && current is ActionProposal.DeleteEvent -> emptyList()
        else -> listOf("было: ${kindWord(original)}")
    }.ifEmpty { listOf("исправлено") }

    private data class EventFields(
        val title: String,
        val date: String,
        val allDay: Boolean,
        val start: String?,
        val minutes: Long?,
        val location: String?,
        val reminder: Int?,
    )

    private fun fields(p: ActionProposal.CreateEvent) = EventFields(
        p.title, p.date, p.allDay, p.startTime,
        runCatching { Duration.between(p.startDateTime, p.endDateTime).toMinutes() }.getOrNull()?.takeUnless { p.allDay },
        p.location, p.reminderMinutes,
    )

    private fun fields(s: EventSnapshot) = EventFields(
        s.title, s.date, s.allDay, s.startTime,
        runCatching { Duration.between(s.startDateTime, s.endDateTime).toMinutes() }.getOrNull()?.takeUnless { s.allDay },
        s.location, s.reminderMinutes,
    )

    private fun event(f: EventFields): String {
        val day = parse(f.date)?.let { Dates.relativeDay(it) } ?: f.date
        return if (f.allDay || f.start == null) "$day, весь день" else "$day, ${f.start}"
    }

    private fun eventChanges(a: EventFields, b: EventFields): List<String> = buildList {
        if (a.title != b.title) add("«${a.title}» → «${b.title}»")
        if (a.date != b.date) add("${shortDay(a.date)} → ${shortDay(b.date)}")
        when {
            a.allDay != b.allDay -> add(if (b.allDay) "весь день" else "в ${b.start}")
            a.start != b.start -> add("${a.start} → ${b.start}")
        }
        if (!b.allDay && a.minutes != null && b.minutes != null && a.minutes != b.minutes) add("${duration(a.minutes)} → ${duration(b.minutes)}")
        if (a.location != b.location) add(b.location?.let { "место: $it" } ?: "без места")
        if (a.reminder != b.reminder) add("напоминание ${reminderLabel(b.reminder)}")
    }

    private fun taskChanges(a: TaskSnapshot, b: TaskSnapshot): List<String> = buildList {
        if (a.title != b.title) add("«${a.title}» → «${b.title}»")
        if (a.lane != b.lane) add("${a.lane} → ${b.lane}")
        if (a.dueDate != b.dueDate || a.dueTime != b.dueTime) {
            add("${a.dueDate?.let { due(it, a.dueTime) } ?: "без срока"} → ${b.dueDate?.let { due(it, b.dueTime) } ?: "без срока"}")
        }
        if (a.done != b.done) add(if (b.done) "готово" else "в работу")
    }

    private fun kindWord(p: ActionProposal): String = when (p) {
        is ActionProposal.CreateEvent, is ActionProposal.UpdateEvent, is ActionProposal.DeleteEvent -> "событие"
        is ActionProposal.AddTask, is ActionProposal.UpdateTask -> "задача"
        is ActionProposal.CreateNote, is ActionProposal.AppendToNote -> "заметка"
    }

    private fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    private fun due(date: String, time: String?): String =
        (parse(date)?.let { Dates.relativeDay(it) } ?: date) + (time?.let { ", $it" } ?: "")

    private fun shortDay(date: String): String = parse(date)?.let { Dates.short(it) } ?: date

    private fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
}

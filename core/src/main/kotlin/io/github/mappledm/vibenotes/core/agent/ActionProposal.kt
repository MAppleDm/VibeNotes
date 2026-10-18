package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.calendar.NewCalendarEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * A change the assistant suggests. Nothing is written until the user confirms it;
 * then [ActionExecutor] performs it and reports the real outcome.
 * Dates and times are ISO strings so proposals persist as plain JSON.
 */
@Serializable
sealed interface ActionProposal {

    @Serializable
    @SerialName("calendar_event")
    data class CreateEvent(
        val title: String,
        /** yyyy-MM-dd */
        val date: String,
        /** HH:mm, null for all-day events. */
        val startTime: String? = null,
        val endDate: String? = null,
        val endTime: String? = null,
        val allDay: Boolean = false,
        val location: String? = null,
        val description: String? = null,
        val calendarId: Long? = null,
        val reminderMinutes: Int? = null,
    ) : ActionProposal {
        val startDateTime: LocalDateTime
            get() = LocalDate.parse(date).atTime(if (allDay) LocalTime.MIDNIGHT else parseTime(startTime) ?: LocalTime.of(9, 0))

        val endDateTime: LocalDateTime
            get() {
                val endDay = endDate?.let { LocalDate.parse(it) } ?: LocalDate.parse(date)
                return if (allDay) endDay.plusDays(1).atStartOfDay()
                else endDay.atTime(parseTime(endTime) ?: startDateTime.toLocalTime().plusHours(1))
            }
    }

    @Serializable
    @SerialName("note")
    data class CreateNote(
        val title: String,
        val content: String,
        val folder: String = "",
    ) : ActionProposal

    @Serializable
    @SerialName("append")
    data class AppendToNote(
        val path: String,
        val text: String,
    ) : ActionProposal

    @Serializable
    @SerialName("task")
    data class AddTask(
        val boardPath: String,
        val lane: String,
        val title: String,
        /** yyyy-MM-dd */
        val dueDate: String? = null,
        /** HH:mm */
        val dueTime: String? = null,
    ) : ActionProposal

    @Serializable
    @SerialName("event_update")
    data class UpdateEvent(
        val eventId: Long,
        val before: EventSnapshot,
        val after: EventSnapshot,
    ) : ActionProposal

    @Serializable
    @SerialName("event_delete")
    data class DeleteEvent(
        val eventId: Long,
        val event: EventSnapshot,
    ) : ActionProposal

    @Serializable
    @SerialName("task_update")
    data class UpdateTask(
        val before: TaskSnapshot,
        val after: TaskSnapshot,
    ) : ActionProposal

    companion object {
        fun parseTime(value: String?): LocalTime? {
            if (value.isNullOrBlank()) return null
            return try {
                LocalTime.parse(value.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it }.take(5))
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** Event data shown on a card and used to restore the event after "Отменить". */
@Serializable
data class EventSnapshot(
    val title: String,
    /** yyyy-MM-dd */
    val date: String,
    /** HH:mm, null for all-day events. */
    val startTime: String? = null,
    val endDate: String? = null,
    val endTime: String? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
    val calendarId: Long? = null,
    val reminderMinutes: Int? = null,
) {
    val startDateTime: LocalDateTime
        get() = LocalDate.parse(date).atTime(if (allDay) LocalTime.MIDNIGHT else ActionProposal.parseTime(startTime) ?: LocalTime.of(9, 0))

    val endDateTime: LocalDateTime
        get() {
            val endDay = endDate?.let { LocalDate.parse(it) } ?: LocalDate.parse(date)
            return if (allDay) endDay.plusDays(1).atStartOfDay()
            else endDay.atTime(ActionProposal.parseTime(endTime) ?: startDateTime.toLocalTime().plusHours(1))
        }

    /** Calendar Provider event fields for an insert or an update. */
    fun toNewEvent(calendarId: Long = this.calendarId ?: error("Календарь не выбран"), zone: ZoneId): NewCalendarEvent {
        val startDay = LocalDate.parse(date)
        val start = startDay.atTime(if (allDay) LocalTime.MIDNIGHT else ActionProposal.parseTime(startTime) ?: LocalTime.of(9, 0))
        val end = endDateTime
        return NewCalendarEvent(
            calendarId = calendarId,
            title = title,
            allDay = allDay,
            start = start,
            end = if (end.isAfter(start)) end else start.plusHours(1),
            zone = zone,
            location = location?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            reminderMinutes = reminderMinutes,
        )
    }

    companion object {
        fun from(event: CalendarEvent, zone: ZoneId): EventSnapshot {
            if (event.allDay) {
                val first = event.start.atZone(ZoneOffset.UTC).toLocalDate()
                val last = if (event.end.isAfter(event.start)) event.end.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1) else first
                return EventSnapshot(
                    title = event.title,
                    date = first.toString(),
                    allDay = true,
                    endDate = last.takeIf { it.isAfter(first) }?.toString(),
                    location = event.location,
                    description = event.description,
                    calendarId = event.calendarId,
                    reminderMinutes = event.reminderMinutes,
                )
            }
            val start = event.start.atZone(zone)
            val end = event.end.atZone(zone)
            return EventSnapshot(
                title = event.title,
                date = start.toLocalDate().toString(),
                startTime = hhmm(start.toLocalTime()),
                endDate = end.toLocalDate().takeIf { it != start.toLocalDate() }?.toString(),
                endTime = hhmm(end.toLocalTime()),
                location = event.location,
                description = event.description,
                calendarId = event.calendarId,
                reminderMinutes = event.reminderMinutes,
            )
        }

        private fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
    }
}

/** A Kanban card before and after an edit, so the card can show "было → стало". */
@Serializable
data class TaskSnapshot(
    val boardPath: String,
    val lane: String,
    val title: String,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val done: Boolean = false,
    /** Raw card line without the checkbox, used to find the exact card in the Markdown file. */
    val text: String,
)

/** What "Отменить" does after a confirmed action. Stored with the proposal, survives restarts. */
@Serializable
sealed interface UndoAction {
    @Serializable
    @SerialName("trash_note")
    data class TrashNote(val path: String) : UndoAction

    @Serializable
    @SerialName("remove_appended")
    data class RemoveAppended(val path: String, val suffix: String, val wasNewFile: Boolean) : UndoAction

    @Serializable
    @SerialName("delete_event")
    data class DeleteEvent(val eventId: Long) : UndoAction

    @Serializable
    @SerialName("remove_task")
    data class RemoveTask(val boardPath: String, val lane: String, val cardText: String) : UndoAction

    @Serializable
    @SerialName("restore_event")
    data class RestoreEvent(val eventId: Long, val event: EventSnapshot) : UndoAction

    @Serializable
    @SerialName("recreate_event")
    data class RecreateEvent(val event: EventSnapshot) : UndoAction

    @Serializable
    @SerialName("restore_task")
    data class RestoreTask(val before: TaskSnapshot, val afterText: String) : UndoAction
}

@Serializable
sealed interface ResultTarget {
    @Serializable
    @SerialName("note")
    data class Note(val path: String) : ResultTarget

    @Serializable
    @SerialName("board")
    data class Board(val path: String) : ResultTarget

    @Serializable
    @SerialName("event")
    data class Event(val eventId: Long) : ResultTarget
}

sealed interface ActionResult {
    data class Success(
        val message: String,
        val target: ResultTarget?,
        /** Hidden inverse operation; shown as "Отменить" on the applied card. */
        val undo: UndoAction? = null,
    ) : ActionResult
    data class Failure(val message: String, val permissionRequired: Boolean = false) : ActionResult
}

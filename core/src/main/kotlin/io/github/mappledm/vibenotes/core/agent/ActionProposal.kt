package io.github.mappledm.vibenotes.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    data class Success(val message: String, val target: ResultTarget?) : ActionResult
    data class Failure(val message: String, val permissionRequired: Boolean = false) : ActionResult
}

package io.github.mappledm.vibenotes.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val color: Int?,
    val isPrimary: Boolean,
)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String?,
    val color: Int?,
)

/** Event the user confirmed. All-day events use [startDate]/[endDateExclusive]. */
data class NewCalendarEvent(
    val calendarId: Long,
    val title: String,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: ZoneId,
    val location: String? = null,
    val description: String? = null,
    val reminderMinutes: Int? = null,
) {
    val startDate: LocalDate get() = start.toLocalDate()
    val endDateExclusive: LocalDate get() = if (end.toLocalDate() > start.toLocalDate()) end.toLocalDate() else start.toLocalDate().plusDays(1)
}

/** Device calendar access (Android Calendar Provider in the app). */
interface CalendarGateway {
    fun hasPermission(): Boolean

    /** Calendars the user can add events to. */
    suspend fun writableCalendars(): List<CalendarInfo>

    suspend fun events(from: Instant, to: Instant): List<CalendarEvent>

    /** Inserts the event and verifies it can be read back. Returns the event id. */
    suspend fun insert(event: NewCalendarEvent): Long
}

class CalendarException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { PERMISSION, NO_CALENDAR, FAILED }
}

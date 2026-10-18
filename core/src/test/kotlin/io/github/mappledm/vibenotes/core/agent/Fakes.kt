package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.LlmClient
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.ToolCallRequest
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.calendar.CalendarException
import io.github.mappledm.vibenotes.core.calendar.CalendarGateway
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.calendar.NewCalendarEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Replays scripted model turns and records every request. */
class ScriptedLlm(vararg turns: LlmEvent.Completed) : LlmClient {
    private val queue = ArrayDeque(turns.toList())
    val requests = mutableListOf<LlmRequest>()

    override fun stream(config: ProviderConfig, request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        val turn = queue.removeFirstOrNull() ?: error("No scripted turn left")
        if (turn.text.isNotEmpty()) emit(LlmEvent.TextDelta(turn.text))
        emit(turn)
    }

    companion object {
        fun text(text: String) = LlmEvent.Completed(text, emptyList(), "stop")
        fun calls(vararg calls: Pair<String, String>) = LlmEvent.Completed(
            "",
            calls.mapIndexed { i, (name, args) -> ToolCallRequest("call_$i", name, args) },
            "tool_calls",
        )
    }
}

class FakeCalendar(
    var permission: Boolean = true,
    var failInsert: Boolean = false,
    val calendars: List<CalendarInfo> = listOf(
        CalendarInfo(1, "Личный", "me@gmail.com", null, isPrimary = true),
        CalendarInfo(2, "Работа", "me@gmail.com", null, isPrimary = false),
    ),
) : CalendarGateway {
    val inserted = mutableListOf<NewCalendarEvent>()
    val events = mutableListOf<CalendarEvent>()
    val updated = mutableListOf<Pair<Long, NewCalendarEvent>>()
    val deleted = mutableListOf<Long>()

    override fun hasPermission() = permission

    override suspend fun writableCalendars(): List<CalendarInfo> {
        if (!permission) throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю")
        return calendars
    }

    override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> =
        events.filter { it.start < to && it.end > from }

    override suspend fun event(eventId: Long): CalendarEvent? = events.firstOrNull { it.eventId == eventId }

    override suspend fun insert(event: NewCalendarEvent): Long {
        if (!permission) throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю")
        if (failInsert) throw CalendarException(CalendarException.Kind.FAILED, "Календарь отклонил событие")
        inserted += event
        val id = 100L + inserted.size
        events += event.toCalendarEvent(id)
        return id
    }

    override suspend fun update(eventId: Long, event: NewCalendarEvent) {
        if (!permission) throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю")
        val index = events.indexOfFirst { it.eventId == eventId }
        if (index < 0) throw CalendarException(CalendarException.Kind.FAILED, "Событие не найдено")
        updated += eventId to event
        events[index] = event.toCalendarEvent(eventId)
    }

    override suspend fun delete(eventId: Long) {
        if (!permission) throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю")
        if (!events.removeAll { it.eventId == eventId }) throw CalendarException(CalendarException.Kind.FAILED, "Событие не найдено")
        deleted += eventId
    }
}

private fun NewCalendarEvent.toCalendarEvent(eventId: Long): CalendarEvent = CalendarEvent(
    eventId = eventId,
    calendarId = calendarId,
    title = title,
    start = if (allDay) startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant() else start.atZone(zone).toInstant(),
    end = if (allDay) endDateExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant() else end.atZone(zone).toInstant(),
    allDay = allDay,
    location = location,
    color = null,
    description = description,
    reminderMinutes = reminderMinutes,
)

val TEST_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

/** Tuesday, 13 October 2026, 10:30. */
val TEST_NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 13, 10, 30, 0, 0, TEST_ZONE)

fun testContext(
    calendars: List<CalendarInfo>? = FakeCalendar().calendars,
    boards: List<BoardInfo> = listOf(BoardInfo("Задачи.md", "Задачи", listOf("Сделать", "В работе", "Готово"))),
) = AssistantContext(
    now = TEST_NOW,
    boards = boards,
    defaultBoardPath = "Задачи.md",
    inboxNotePath = "Входящие.md",
    folders = listOf("Работа"),
    calendars = calendars,
    defaultCalendarId = 1,
    defaultEventMinutes = 60,
    defaultReminderMinutes = 30,
)

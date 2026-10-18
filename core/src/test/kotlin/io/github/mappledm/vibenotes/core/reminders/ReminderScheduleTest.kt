package io.github.mappledm.vibenotes.core.reminders

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderScheduleTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun picksTheUpcomingWeekday() {
        // Tuesday 13 October 2026, 10:30.
        val now = ZonedDateTime.of(2026, 10, 13, 10, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone),
            ReminderSchedule.next(now, DayOfWeek.MONDAY, 10, 0),
        )
    }

    @Test
    fun sameWeekdayMovesToNextWeekWhenTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 10, 12, 10, 1, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone),
            ReminderSchedule.next(now, DayOfWeek.MONDAY, 10, 0),
        )
    }

    @Test
    fun sameWeekdayLaterTodayStaysToday() {
        val now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, zone),
            ReminderSchedule.next(now, DayOfWeek.MONDAY, 10, 0),
        )
    }
}

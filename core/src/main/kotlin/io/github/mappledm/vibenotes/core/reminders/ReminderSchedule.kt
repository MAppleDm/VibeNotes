package io.github.mappledm.vibenotes.core.reminders

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Pure calculation of the next weekly reminder, kept out of Android so it can be tested. */
object ReminderSchedule {
    /**
     * The next [day] at [hour]:[minute] strictly after [now].
     * If today is that weekday but the time has passed, the reminder moves to next week.
     */
    fun next(now: ZonedDateTime, day: DayOfWeek, hour: Int, minute: Int): ZonedDateTime {
        var candidate = now
            .with(TemporalAdjusters.nextOrSame(day))
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
        return candidate
    }
}

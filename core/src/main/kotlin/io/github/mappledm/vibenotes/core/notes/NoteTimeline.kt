package io.github.mappledm.vibenotes.core.notes

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How finely the note list is grouped by time, from the finest to the coarsest (like zooming out in a photo gallery). */
enum class TimeScale {
    DAY, WEEK, MONTH, YEAR;

    /** One step finer (zooming in) or coarser (zooming out); stays put at the ends. */
    fun finer(): TimeScale = entries[(ordinal - 1).coerceAtLeast(0)]
    fun coarser(): TimeScale = entries[(ordinal + 1).coerceAtMost(entries.size - 1)]
}

/** Items of one period: a day, a week (from Monday), a month or a year starting at [start]. */
data class TimeGroup<T>(val start: LocalDate, val items: List<T>)

object NoteTimeline {
    /** First day of the period [date] falls into; weeks start on Monday. */
    fun periodStart(date: LocalDate, scale: TimeScale): LocalDate = when (scale) {
        TimeScale.DAY -> date
        TimeScale.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        TimeScale.MONTH -> date.withDayOfMonth(1)
        TimeScale.YEAR -> date.withDayOfYear(1)
    }

    /** Notes grouped by the period of their last change; groups and notes inside them go in time order. */
    fun group(
        notes: List<NoteMeta>,
        scale: TimeScale,
        zone: ZoneId = ZoneId.systemDefault(),
        newestFirst: Boolean = true,
    ): List<TimeGroup<NoteMeta>> {
        val byTime = if (newestFirst) notes.sortedByDescending { it.lastModified } else notes.sortedBy { it.lastModified }
        // Sorted input keeps both the groups and their items in order.
        return byTime
            .groupBy { periodStart(Instant.ofEpochMilli(it.lastModified).atZone(zone).toLocalDate(), scale) }
            .map { (start, items) -> TimeGroup(start, items) }
    }
}

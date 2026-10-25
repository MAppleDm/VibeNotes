package io.github.mappledm.vibenotes.core.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NoteTimelineTest {
    private fun note(path: String, date: String, hour: Int = 12) =
        NoteMeta(path, LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), 1)

    private val notes = listOf(
        note("a.md", "2026-10-25", 9),
        note("b.md", "2026-10-25", 18),
        note("c.md", "2026-10-19"), // Monday of the same week
        note("d.md", "2026-10-18"), // Sunday of the week before
        note("e.md", "2026-09-30"),
        note("f.md", "2025-12-31"),
    )

    @Test
    fun groupsByEachScaleNewestFirst() {
        fun paths(scale: TimeScale) = NoteTimeline.group(notes, scale, ZoneOffset.UTC).map { g -> g.items.map { it.path } }

        assertEquals(listOf(listOf("b.md", "a.md"), listOf("c.md"), listOf("d.md"), listOf("e.md"), listOf("f.md")), paths(TimeScale.DAY))
        assertEquals(listOf(listOf("b.md", "a.md", "c.md"), listOf("d.md"), listOf("e.md"), listOf("f.md")), paths(TimeScale.WEEK))
        assertEquals(listOf(listOf("b.md", "a.md", "c.md", "d.md"), listOf("e.md"), listOf("f.md")), paths(TimeScale.MONTH))
        assertEquals(listOf(listOf("b.md", "a.md", "c.md", "d.md", "e.md"), listOf("f.md")), paths(TimeScale.YEAR))
    }

    @Test
    fun oldestFirstAndPeriodStarts() {
        val groups = NoteTimeline.group(notes, TimeScale.WEEK, ZoneOffset.UTC, newestFirst = false)
        assertEquals(LocalDate.parse("2025-12-29"), groups.first().start)
        assertEquals(listOf("c.md", "a.md", "b.md"), groups.last().items.map { it.path })
        assertEquals(LocalDate.parse("2026-10-19"), groups.last().start)
        assertEquals(LocalDate.parse("2026-01-01"), NoteTimeline.periodStart(LocalDate.parse("2026-10-25"), TimeScale.YEAR))
    }

    @Test
    fun zoomStepsStopAtTheEnds() {
        assertEquals(TimeScale.DAY, TimeScale.DAY.finer())
        assertEquals(TimeScale.MONTH, TimeScale.WEEK.coarser())
        assertEquals(TimeScale.YEAR, TimeScale.YEAR.coarser())
    }
}

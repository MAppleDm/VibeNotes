package io.github.mappledm.vibenotes.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Russian date formatting used across screens ("чт, 15 окт"). */
object Dates {
    private val ru = Locale.forLanguageTag("ru")
    private val dayShort = DateTimeFormatter.ofPattern("EEE, d MMM", ru)
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", ru)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", ru)
    private val weekdayShort = DateTimeFormatter.ofPattern("EEE", ru)
    private val dayMonthLong = DateTimeFormatter.ofPattern("d MMMM", ru)

    fun short(date: LocalDate): String = dayShort.format(date).replace(".", "")
    fun long(date: LocalDate): String = dayLong.format(date).replaceFirstChar { it.uppercase() }
    fun dayMonth(date: LocalDate): String = dayMonth.format(date).replace(".", "")
    /** "Среда" */
    fun weekday(date: LocalDate): String = weekday.format(date).replaceFirstChar { it.uppercase() }
    /** "ср" */
    fun weekdayShort(date: LocalDate): String = weekdayShort.format(date).replace(".", "")
    /** "22 октября" */
    fun dayMonthLong(date: LocalDate): String = dayMonthLong.format(date)
    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = time.format(instant.atZone(zone))
    fun timeOf(value: LocalTime): String = time.format(value)

    /** "сегодня", "завтра" or a short date. */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        today.minusDays(1) -> "вчера"
        else -> short(date)
    }

    /** For note lists: time today, otherwise date. */
    fun modified(millis: Long): String {
        if (millis <= 0) return ""
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.now(zone)
        return when {
            dateTime.toLocalDate() == today -> time.format(dateTime)
            dateTime.year == today.year -> dayMonth.format(dateTime).replace(".", "")
            else -> full.format(dateTime).replace(".", "")
        }
    }
}

fun reminderLabel(minutes: Int?): String = when {
    minutes == null -> "без напоминания"
    minutes == 0 -> "в момент начала"
    minutes < 60 -> "за $minutes мин"
    minutes % (24 * 60) == 0 -> "за ${minutes / (24 * 60)} дн"
    minutes % 60 == 0 -> "за ${minutes / 60} ч"
    else -> "за $minutes мин"
}

val ReminderOptions: List<Int?> = listOf(null, 0, 10, 30, 60, 120, 24 * 60)

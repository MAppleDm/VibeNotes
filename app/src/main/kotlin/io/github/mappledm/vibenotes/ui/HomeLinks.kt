package io.github.mappledm.vibenotes.ui

import io.github.mappledm.vibenotes.core.inbox.ChatFocus
import io.github.mappledm.vibenotes.ui.components.Dates
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Where "Открыть" leads inside the app: a day of the calendar or a card on a board, shown highlighted. */
sealed interface Spot {
    data class Day(val date: LocalDate, val eventId: Long? = null) : Spot
    data class Card(val boardPath: String, val title: String) : Spot
}

/** What a tab adds to a message written on it: a short label for the user, a hint for the model. */
object Focus {
    fun day(date: LocalDate): ChatFocus = ChatFocus(
        label = "На ${Dates.relativeDay(date)}",
        hint = "the user is planning $date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}) in the calendar; " +
            "the message is about that day unless it names another date",
    )

    fun week(start: LocalDate): ChatFocus {
        val end = start.plusDays(6)
        return ChatFocus(
            label = "Неделя ${Dates.dayMonth(start)} – ${Dates.dayMonth(end)}",
            hint = "the user is looking at the week $start..$end in the calendar; a weekday without a date means that day of this week",
        )
    }

    fun board(title: String): ChatFocus = ChatFocus(
        label = "Доска «$title»",
        hint = "the user is on the Kanban board \"$title\"; new tasks go to this board unless the message names another, " +
            "and a to-do without an appointment time is a task",
    )

    val notes = ChatFocus(
        label = "Заметки",
        hint = "the user is in the notes list; a thought, idea or list without a date is most likely a note " +
            "(create_note, or append_to_note for a short thought)",
    )
}

package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The tool call that would have produced [this] proposal — used to replay history faithfully. */
fun ActionProposal.toolCall(): Pair<String, String> = when (this) {
    is ActionProposal.CreateEvent -> "create_calendar_event" to buildJsonObject {
        put("title", title)
        put("date", date)
        startTime?.let { put("start_time", it) }
        endTime?.let { put("end_time", it) }
        put("all_day", allDay)
        location?.let { put("location", it) }
        calendarId?.let { put("calendar_id", it) }
    }.toString()
    is ActionProposal.UpdateEvent -> "update_calendar_event" to buildJsonObject {
        put("event_id", eventId)
        put("title", after.title)
        put("date", after.date)
        after.startTime?.let { put("start_time", it) }
        after.endTime?.let { put("end_time", it) }
        after.endDate?.let { put("end_date", it) }
        put("all_day", after.allDay)
    }.toString()
    is ActionProposal.DeleteEvent -> "delete_calendar_event" to buildJsonObject {
        put("event_id", eventId)
    }.toString()
    is ActionProposal.AddTask -> "add_task" to buildJsonObject {
        put("title", title)
        put("board", VaultPaths.noteTitle(boardPath))
        put("column", lane)
        dueDate?.let { put("due_date", it) }
        dueTime?.let { put("due_time", it) }
    }.toString()
    is ActionProposal.UpdateTask -> "update_task" to buildJsonObject {
        put("board", VaultPaths.noteTitle(before.boardPath))
        put("column", before.lane)
        put("title", before.title)
        if (after.title != before.title) put("new_title", after.title)
        if (after.dueDate != before.dueDate) {
            if (after.dueDate == null) put("clear_due", true) else put("due_date", after.dueDate)
        }
        if (after.dueTime != before.dueTime) after.dueTime?.let { put("due_time", it) }
        if (after.done != before.done) put("done", after.done)
    }.toString()
    is ActionProposal.CreateNote -> "create_note" to buildJsonObject {
        put("title", title)
        put("content", content.take(400))
        put("folder", folder)
    }.toString()
    is ActionProposal.AppendToNote -> "append_to_note" to buildJsonObject {
        put("path", path)
        put("text", text.take(400))
    }.toString()
}

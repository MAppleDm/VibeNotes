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
    is ActionProposal.AddTask -> "add_task" to buildJsonObject {
        put("title", title)
        put("board", VaultPaths.noteTitle(boardPath))
        put("column", lane)
        dueDate?.let { put("due_date", it) }
        dueTime?.let { put("due_time", it) }
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

package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.ToolSpec
import io.github.mappledm.vibenotes.core.calendar.CalendarException
import io.github.mappledm.vibenotes.core.calendar.CalendarGateway
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Builds the tool set available to the assistant. */
class ToolRegistry(
    notes: NotesRepository,
    kanban: KanbanRepository,
    calendar: CalendarGateway,
) {
    val tools: List<AgentTool> = listOf(
        SearchNotesTool(notes),
        ReadNoteTool(notes),
        ListTasksTool(kanban),
        ListCalendarsTool(calendar),
        ListEventsTool(calendar),
        CreateEventTool(),
        CreateNoteTool(),
        AppendToNoteTool(notes),
        AddTaskTool(),
    )

    fun find(name: String): AgentTool? = tools.firstOrNull { it.spec.name == name }
}

private const val MAX_NOTE_CHARS = 6000

class SearchNotesTool(private val notes: NotesRepository) : AgentTool {
    override val kind = ToolKind.READ
    override val spec = ToolSpec(
        name = "search_notes",
        description = "Full-text search in the user's Markdown notes. Returns matching note paths with snippets. " +
            "Use it before answering questions about the user's own information.",
        parameters = schema {
            prop("query", "string", "Search words (all must match)", isRequired = true)
            prop("limit", "integer", "Max results, 1-10 (default 6)")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val query = args.string("query") ?: return ToolOutcome.Error("Error: 'query' is required")
        val limit = (args.int("limit") ?: 6).coerceIn(1, 10)
        val hits = notes.search(query, limit)
        val label = "Поиск «$query»: " + if (hits.isEmpty()) "ничего" else "${hits.size}"
        if (hits.isEmpty()) return ToolOutcome.Data("No notes match \"$query\".", ToolActivity(ActivityKind.SEARCH, label))
        val body = hits.mapIndexed { i, hit ->
            val tags = if (hit.note.tags.isEmpty()) "" else " | tags: ${hit.note.tags.joinToString(", ")}"
            "${i + 1}. path: ${hit.note.path}$tags\n   snippet: ${hit.snippet}"
        }.joinToString("\n")
        return ToolOutcome.Data(untrusted("search:$query", body), ToolActivity(ActivityKind.SEARCH, label))
    }
}

class ReadNoteTool(private val notes: NotesRepository) : AgentTool {
    override val kind = ToolKind.READ
    override val spec = ToolSpec(
        name = "read_note",
        description = "Reads the full Markdown content of one note by its path (as returned by search_notes) or title.",
        parameters = schema { prop("path", "string", "Note path like 'Folder/Note.md' or note title", isRequired = true) },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val raw = args.string("path") ?: return ToolOutcome.Error("Error: 'path' is required")
        val meta = notes.resolveLink(raw) ?: return ToolOutcome.Error(
            "Note '$raw' not found. Use search_notes to find the exact path.",
            ToolActivity(ActivityKind.ERROR, "Заметка «$raw» не найдена"),
        )
        val content = notes.read(meta.path) ?: return ToolOutcome.Error("Note '$raw' could not be read.")
        val truncated = if (content.length > MAX_NOTE_CHARS) content.take(MAX_NOTE_CHARS) + "\n…[truncated]" else content
        return ToolOutcome.Data(untrusted("note:${meta.path}", truncated), ToolActivity(ActivityKind.READ, "Прочитана «${meta.title}»"))
    }
}

class ListTasksTool(private val kanban: KanbanRepository) : AgentTool {
    override val kind = ToolKind.READ
    override val spec = ToolSpec(
        name = "list_tasks",
        description = "Lists Kanban tasks (cards) from the user's boards with their column and due date.",
        parameters = schema {
            prop("board", "string", "Board title or path; omit for all boards")
            prop("include_done", "boolean", "Include cards from completed columns (default false)")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val board = args.string("board")
        val includeDone = args.bool("include_done") ?: false
        val tasks = kanban.allTasks()
            .filter { board == null || it.boardTitle.equals(board, true) || it.boardPath.equals(board, true) }
            .filter { includeDone || (!it.laneIsComplete && !it.card.checked) }
            .take(80)
        val label = "Задачи" + (board?.let { " «$it»" } ?: "") + ": ${tasks.size}"
        if (tasks.isEmpty()) return ToolOutcome.Data("No tasks found.", ToolActivity(ActivityKind.TASKS, label))
        val body = tasks.joinToString("\n") { t ->
            val due = t.card.dueDate?.let { d -> " (due $d" + (t.card.dueTime?.let { " $it" } ?: "") + ")" } ?: ""
            "- [${t.boardTitle} / ${t.laneTitle}] ${t.card.title}$due"
        }
        return ToolOutcome.Data(untrusted("tasks", body), ToolActivity(ActivityKind.TASKS, label))
    }
}

class ListCalendarsTool(private val calendar: CalendarGateway) : AgentTool {
    override val kind = ToolKind.READ
    override val spec = ToolSpec(
        name = "list_calendars",
        description = "Lists device calendars the user can add events to (id, name, account).",
        parameters = schema { },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        if (!calendar.hasPermission()) {
            return ToolOutcome.Error("Calendar permission is not granted. Propose the event anyway; the app will ask for permission.")
        }
        val list = calendar.writableCalendars()
        val body = if (list.isEmpty()) "No writable calendars." else list.joinToString("\n") {
            "- id ${it.id}: ${it.name} (${it.accountName})" + if (it.isPrimary) " [primary]" else ""
        }
        return ToolOutcome.Data(untrusted("calendars", body), ToolActivity(ActivityKind.CALENDAR, "Список календарей"))
    }
}

class ListEventsTool(private val calendar: CalendarGateway) : AgentTool {
    override val kind = ToolKind.READ
    override val spec = ToolSpec(
        name = "list_calendar_events",
        description = "Lists the user's calendar events between two dates (inclusive). Use for questions like 'what do I have this week'.",
        parameters = schema {
            prop("from", "string", "Start date yyyy-MM-dd (default today)")
            prop("to", "string", "End date yyyy-MM-dd inclusive (default from + 6 days)")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        if (!calendar.hasPermission()) {
            return ToolOutcome.Error(
                "Calendar permission is not granted, events cannot be read. Tell the user to allow calendar access.",
                ToolActivity(ActivityKind.ERROR, "Нет доступа к календарю"),
            )
        }
        val zone = context.now.zone
        val from = parseDate(args.string("from")) ?: context.now.toLocalDate()
        val to = (parseDate(args.string("to")) ?: from.plusDays(6)).let { if (it.isBefore(from)) from else it }
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > 62) {
            return ToolOutcome.Error("Range too large; request at most 62 days.")
        }
        val events = try {
            calendar.events(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
        } catch (e: CalendarException) {
            return ToolOutcome.Error("Calendar error: ${e.message}", ToolActivity(ActivityKind.ERROR, e.message ?: "Ошибка календаря"))
        }
        val dayFmt = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("ru"))
        val label = "Календарь: ${from.format(dayFmt)} – ${to.format(dayFmt)}, событий: ${events.size}"
        if (events.isEmpty()) return ToolOutcome.Data("No events in this range.", ToolActivity(ActivityKind.CALENDAR, label))
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val body = events.sortedBy { it.start }.joinToString("\n") { e ->
            if (e.allDay) {
                // All-day events are stored in UTC by the Calendar Provider.
                val day = e.start.atZone(ZoneId.of("UTC")).toLocalDate()
                "- $day all-day: ${e.title}"
            } else {
                val s = e.start.atZone(zone)
                val en = e.end.atZone(zone)
                "- ${s.toLocalDate()} ${s.format(timeFmt)}–${en.format(timeFmt)}: ${e.title}" + (e.location?.let { " @ $it" } ?: "")
            }
        }
        return ToolOutcome.Data(untrusted("calendar", body), ToolActivity(ActivityKind.CALENDAR, label))
    }
}

class CreateEventTool : AgentTool {
    override val kind = ToolKind.PROPOSE
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Proposes a calendar event. The app shows a preview card and creates the event only after the user confirms. " +
            "Use for anything happening at a specific date (meetings, appointments, visits).",
        parameters = schema {
            prop("title", "string", "Short event title in the user's language, e.g. 'Врач'", isRequired = true)
            prop("date", "string", "Start date yyyy-MM-dd (resolve relative dates using the calendar table)", isRequired = true)
            prop("start_time", "string", "Start time HH:mm (24h). Omit for all-day events")
            prop("end_time", "string", "End time HH:mm. Omit to use duration_minutes")
            prop("duration_minutes", "integer", "Duration if end_time is not given")
            prop("all_day", "boolean", "True for all-day events")
            prop("location", "string", "Place, if mentioned")
            prop("description", "string", "Extra details, if any")
            prop("calendar_id", "integer", "Calendar id from the context; omit to use the default calendar")
            prop("reminder_minutes", "integer", "Reminder before start, in minutes; omit for the user's default")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val title = args.string("title") ?: return ToolOutcome.Error("Error: 'title' is required")
        val date = parseDate(args.string("date")) ?: return ToolOutcome.Error("Error: 'date' must be yyyy-MM-dd")
        val startTime = ActionProposal.parseTime(args.string("start_time"))
        val allDay = args.bool("all_day") ?: (startTime == null)
        if (!allDay && startTime == null) return ToolOutcome.Error("Error: give start_time or set all_day=true")

        var endDate: LocalDate? = null
        var endTime: LocalTime? = null
        if (!allDay && startTime != null) {
            val explicitEnd = ActionProposal.parseTime(args.string("end_time"))
            val start = date.atTime(startTime)
            val end = if (explicitEnd != null) {
                date.atTime(explicitEnd).let { if (!it.isAfter(start)) it.plusDays(1) else it }
            } else {
                val minutes = (args.int("duration_minutes") ?: context.defaultEventMinutes).coerceIn(5, 24 * 60)
                start.plusMinutes(minutes.toLong())
            }
            endTime = end.toLocalTime()
            if (end.toLocalDate() != date) endDate = end.toLocalDate()
        }

        val calendarId = args.long("calendar_id")
            ?.takeIf { id -> context.calendars?.any { it.id == id } ?: true }
            ?: context.defaultCalendarId
        val proposal = ActionProposal.CreateEvent(
            title = title,
            date = date.toString(),
            startTime = startTime?.let { hhmm(it) },
            endDate = endDate?.toString(),
            endTime = endTime?.let { hhmm(it) },
            allDay = allDay,
            location = args.string("location"),
            description = args.string("description"),
            calendarId = calendarId,
            reminderMinutes = args.int("reminder_minutes")?.coerceIn(0, 7 * 24 * 60) ?: context.defaultReminderMinutes,
        )
        ProposalValidator.validate(proposal)?.let { return ToolOutcome.Error("Invalid event: $it") }
        return ToolOutcome.Propose(proposal)
    }
}

class CreateNoteTool : AgentTool {
    override val kind = ToolKind.PROPOSE
    override val spec = ToolSpec(
        name = "create_note",
        description = "Proposes a new Markdown note (ideas, information, lists, drafts). Created only after user confirmation.",
        parameters = schema {
            prop("title", "string", "Note title (becomes the file name)", isRequired = true)
            prop("content", "string", "Markdown body. Do not repeat the title as a heading.", isRequired = true)
            prop("folder", "string", "Existing folder from the context, or empty for the root")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val title = args.string("title") ?: return ToolOutcome.Error("Error: 'title' is required")
        val content = args.string("content") ?: ""
        val folder = VaultPaths.normalize(args.string("folder") ?: "")
            .split('/').filterNot { it.startsWith(".") }.joinToString("/")
        val proposal = ActionProposal.CreateNote(title.trim(), content, folder)
        ProposalValidator.validate(proposal)?.let { return ToolOutcome.Error("Invalid note: $it") }
        return ToolOutcome.Propose(proposal)
    }
}

class AppendToNoteTool(private val notes: NotesRepository) : AgentTool {
    override val kind = ToolKind.PROPOSE
    override val spec = ToolSpec(
        name = "append_to_note",
        description = "Proposes appending text to the end of an existing note (or the Inbox note when no path is given). " +
            "Use for quick thoughts that do not deserve a separate note.",
        parameters = schema {
            prop("text", "string", "Markdown text to append", isRequired = true)
            prop("path", "string", "Path or title of an existing note; omit for the Inbox note")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val text = args.string("text") ?: return ToolOutcome.Error("Error: 'text' is required")
        val requested = args.string("path")
        val path = if (requested == null) {
            context.inboxNotePath
        } else {
            notes.resolveLink(requested)?.path
                ?: VaultPaths.normalize(requested).takeIf { it.equals(context.inboxNotePath, ignoreCase = true) }
                ?: return ToolOutcome.Error("Note '$requested' not found. Use search_notes, or create_note for a new note.")
        }
        val proposal = ActionProposal.AppendToNote(path, text)
        ProposalValidator.validate(proposal)?.let { return ToolOutcome.Error("Invalid append: $it") }
        return ToolOutcome.Propose(proposal)
    }
}

class AddTaskTool : AgentTool {
    override val kind = ToolKind.PROPOSE
    override val spec = ToolSpec(
        name = "add_task",
        description = "Proposes a Kanban task card (to-dos without a fixed meeting time). Added only after user confirmation.",
        parameters = schema {
            prop("title", "string", "Task text, short and actionable", isRequired = true)
            prop("board", "string", "Board title from the context; omit for the default board")
            prop("column", "string", "Column title on that board; omit for the first open column")
            prop("due_date", "string", "Deadline yyyy-MM-dd, if any")
            prop("due_time", "string", "Deadline time HH:mm, if any")
        },
    )

    override suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome {
        val title = args.string("title") ?: return ToolOutcome.Error("Error: 'title' is required")
        val boardArg = args.string("board")
        val board = boardArg?.let { name ->
            context.boards.firstOrNull { it.title.equals(name, true) || it.path.equals(name, true) }
        } ?: context.boards.firstOrNull { it.path.equals(context.defaultBoardPath, true) }
        val boardPath = board?.path ?: context.defaultBoardPath
        val lanes = board?.lanes ?: listOf("Сделать")
        val column = args.string("column")?.let { c -> lanes.firstOrNull { it.equals(c, true) } } ?: lanes.firstOrNull() ?: "Сделать"
        val due = args.string("due_date")?.let { parseDate(it) ?: return ToolOutcome.Error("Error: due_date must be yyyy-MM-dd") }
        val dueTime = ActionProposal.parseTime(args.string("due_time"))
        val proposal = ActionProposal.AddTask(
            boardPath = boardPath,
            lane = column,
            title = title,
            dueDate = due?.toString(),
            dueTime = if (due != null) dueTime?.let { hhmm(it) } else null,
        )
        ProposalValidator.validate(proposal)?.let { return ToolOutcome.Error("Invalid task: $it") }
        return ToolOutcome.Propose(proposal)
    }
}

private fun parseDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value.trim().take(10))
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

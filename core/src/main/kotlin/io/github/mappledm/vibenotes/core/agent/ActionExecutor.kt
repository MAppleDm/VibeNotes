package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.calendar.CalendarException
import io.github.mappledm.vibenotes.core.calendar.CalendarGateway
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.calendar.NewCalendarEvent
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.kanban.KanbanException
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.StorageException
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Performs confirmed proposals. Reports failures honestly — never claims success on error. */
interface ActionExecutor {
    suspend fun execute(proposal: ActionProposal): ActionResult
}

object ProposalValidator {
    /** Returns a user-facing problem description, or null if the proposal can be executed. */
    fun validate(proposal: ActionProposal): String? = when (proposal) {
        is ActionProposal.CreateEvent -> validateEvent(proposal)
        is ActionProposal.CreateNote -> when {
            VaultPaths.sanitizeFileName(proposal.title).isEmpty() -> "Укажите название заметки"
            else -> null
        }
        is ActionProposal.AppendToNote -> when {
            proposal.text.isBlank() -> "Пустой текст"
            !VaultPaths.isMarkdown(proposal.path) -> "Можно дописывать только в .md заметки"
            else -> null
        }
        is ActionProposal.AddTask -> when {
            proposal.title.isBlank() -> "Укажите текст задачи"
            proposal.dueDate != null && runCatching { LocalDate.parse(proposal.dueDate) }.isFailure -> "Неверная дата"
            else -> null
        }
    }

    private fun validateEvent(event: ActionProposal.CreateEvent): String? {
        if (event.title.isBlank()) return "Укажите название события"
        return try {
            LocalDate.parse(event.date)
            if (!event.allDay && event.startTime != null && ActionProposal.parseTime(event.startTime) == null) return "Неверное время начала"
            if (!event.endDateTime.isAfter(event.startDateTime)) "Окончание должно быть позже начала" else null
        } catch (_: DateTimeParseException) {
            "Неверная дата"
        }
    }
}

class DefaultActionExecutor(
    private val notes: NotesRepository,
    private val kanban: KanbanRepository,
    private val calendar: CalendarGateway,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ActionExecutor {

    override suspend fun execute(proposal: ActionProposal): ActionResult {
        ProposalValidator.validate(proposal)?.let { return ActionResult.Failure(it) }
        return try {
            when (proposal) {
                is ActionProposal.CreateEvent -> createEvent(proposal)
                is ActionProposal.CreateNote -> {
                    val meta = notes.create(VaultPaths.normalize(proposal.folder), proposal.title, proposal.content.trimEnd() + "\n")
                    ActionResult.Success("Заметка «${meta.title}» создана", ResultTarget.Note(meta.path))
                }
                is ActionProposal.AppendToNote -> {
                    val meta = notes.append(proposal.path, proposal.text)
                    ActionResult.Success("Добавлено в «${meta.title}»", ResultTarget.Note(meta.path))
                }
                is ActionProposal.AddTask -> addTask(proposal)
            }
        } catch (e: CalendarException) {
            ActionResult.Failure(e.message ?: "Ошибка календаря", permissionRequired = e.kind == CalendarException.Kind.PERMISSION)
        } catch (e: StorageException) {
            ActionResult.Failure(e.message ?: "Ошибка записи файла")
        } catch (e: KanbanException) {
            ActionResult.Failure(e.message ?: "Ошибка доски")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure("Не удалось выполнить: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun createEvent(p: ActionProposal.CreateEvent): ActionResult {
        if (!calendar.hasPermission()) {
            return ActionResult.Failure("Нет доступа к календарю", permissionRequired = true)
        }
        val calendars = calendar.writableCalendars()
        if (calendars.isEmpty()) return ActionResult.Failure("На устройстве нет календаря, доступного для записи")
        val target = calendars.firstOrNull { it.id == p.calendarId }
            ?: CalendarInfo.preferred(calendars)
            ?: return ActionResult.Failure("Не выбран календарь")
        val id = calendar.insert(
            NewCalendarEvent(
                calendarId = target.id,
                title = p.title.trim(),
                allDay = p.allDay,
                start = p.startDateTime,
                end = p.endDateTime,
                zone = zone(),
                location = p.location?.takeIf { it.isNotBlank() },
                description = p.description?.takeIf { it.isNotBlank() },
                reminderMinutes = p.reminderMinutes,
            ),
        )
        return ActionResult.Success("Событие сохранено в «${target.label}»", ResultTarget.Event(id))
    }

    private suspend fun addTask(p: ActionProposal.AddTask): ActionResult {
        val path = kanban.ensureBoard(p.boardPath)
        val due = p.dueDate?.let { LocalDate.parse(it) }
        val time = ActionProposal.parseTime(p.dueTime)
        var laneTitle = ""
        kanban.update(path) { board ->
            val laneIdx = board.laneIndex(p.lane).takeIf { it >= 0 }
                ?: board.lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 }
                ?: throw KanbanException("На доске нет подходящей колонки")
            laneTitle = board.lanes[laneIdx].title
            board.addCard(laneIdx, KanbanCard.create(p.title, due, if (due != null) time else null))
        }
        return ActionResult.Success("Задача добавлена в «$laneTitle» на доске «${VaultPaths.baseName(path)}»", ResultTarget.Board(path))
    }
}

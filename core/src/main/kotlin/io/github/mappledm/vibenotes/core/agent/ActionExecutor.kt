package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.calendar.CalendarException
import io.github.mappledm.vibenotes.core.calendar.CalendarGateway
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.calendar.NewCalendarEvent
import io.github.mappledm.vibenotes.core.kanban.CardRef
import io.github.mappledm.vibenotes.core.kanban.KanbanBoard
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

    /** Takes back an applied action. Fails honestly if the target changed or is gone. */
    suspend fun undo(action: UndoAction): ActionResult
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
        is ActionProposal.UpdateEvent -> when {
            proposal.eventId <= 0 -> "Не выбрано событие"
            else -> validateEvent(proposal.after)
        }
        is ActionProposal.DeleteEvent -> when {
            proposal.eventId <= 0 -> "Не выбрано событие"
            proposal.event.title.isBlank() -> "Не выбрано событие"
            else -> null
        }
        is ActionProposal.UpdateTask -> validateTask(proposal)
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

    private fun validateEvent(event: EventSnapshot): String? {
        if (event.title.isBlank()) return "Укажите название события"
        return try {
            LocalDate.parse(event.date)
            if (!event.allDay && event.startTime != null && ActionProposal.parseTime(event.startTime) == null) return "Неверное время начала"
            if (event.endTime != null && ActionProposal.parseTime(event.endTime) == null) return "Неверное время окончания"
            if (!event.endDateTime.isAfter(event.startDateTime)) "Окончание должно быть позже начала" else null
        } catch (_: DateTimeParseException) {
            "Неверная дата"
        }
    }

    private fun validateTask(task: ActionProposal.UpdateTask): String? {
        val before = task.before
        val after = task.after
        return when {
            before.boardPath.isBlank() || after.boardPath.isBlank() -> "Не выбрана доска"
            before.lane.isBlank() || after.lane.isBlank() -> "Не выбрана колонка"
            before.title.isBlank() || after.title.isBlank() -> "Укажите текст задачи"
            before.boardPath != after.boardPath -> "Задачу можно переносить только в пределах доски"
            after.dueDate != null && runCatching { LocalDate.parse(after.dueDate) }.isFailure -> "Неверная дата"
            after.dueTime != null && ActionProposal.parseTime(after.dueTime) == null -> "Неверное время"
            else -> null
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
                    ActionResult.Success(
                        "Заметка «${meta.title}» создана",
                        ResultTarget.Note(meta.path),
                        UndoAction.TrashNote(meta.path),
                    )
                }
                is ActionProposal.AppendToNote -> appendToNote(proposal)
                is ActionProposal.AddTask -> addTask(proposal)
                is ActionProposal.UpdateEvent -> updateEvent(proposal)
                is ActionProposal.DeleteEvent -> deleteEvent(proposal)
                is ActionProposal.UpdateTask -> updateTask(proposal)
            }
        } catch (e: CalendarException) {
            ActionResult.Failure(e.message ?: "Ошибка календаря", permissionRequired = e.kind == CalendarException.Kind.PERMISSION)
        } catch (e: StorageException) {
            ActionResult.Failure(e.message ?: "Ошибка записи файла")
        } catch (e: KanbanException) {
            ActionResult.Failure(e.message ?: "Ошибка доски")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Last line of defence: an unexpected error must show up on the card, not close the app.
            ActionResult.Failure("Не удалось выполнить: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun undo(action: UndoAction): ActionResult = try {
        when (action) {
            is UndoAction.TrashNote -> {
                notes.delete(action.path)
                ActionResult.Success("Заметка убрана в корзину", ResultTarget.Note(action.path))
            }
            is UndoAction.RemoveAppended -> removeAppended(action)
            is UndoAction.DeleteEvent -> {
                calendar.delete(action.eventId)
                ActionResult.Success("Событие удалено", null)
            }
            is UndoAction.RemoveTask -> {
                kanban.update(action.boardPath) { board ->
                    val laneIndex = board.laneIndex(action.lane)
                        .takeIf { it >= 0 } ?: throw KanbanException("Колонка не найдена")
                    val index = board.lanes[laneIndex].cards.indexOfFirst { it.text == action.cardText }
                        .takeIf { it >= 0 } ?: throw KanbanException("Карточка уже изменилась")
                    board.removeCard(CardRef(laneIndex, index, action.cardText))
                }
                ActionResult.Success("Задача убрана с доски", ResultTarget.Board(action.boardPath))
            }
            is UndoAction.RestoreEvent -> {
                val calendarId = action.event.calendarId ?: throw CalendarException(CalendarException.Kind.FAILED, "Не выбран календарь")
                calendar.update(action.eventId, action.event.toNewEvent(calendarId, zone()))
                ActionResult.Success("Событие восстановлено", ResultTarget.Event(action.eventId))
            }
            is UndoAction.RecreateEvent -> {
                val calendarId = action.event.calendarId ?: throw CalendarException(CalendarException.Kind.FAILED, "Не выбран календарь")
                val id = calendar.insert(action.event.toNewEvent(calendarId, zone()))
                ActionResult.Success("Событие восстановлено", ResultTarget.Event(id))
            }
            is UndoAction.RestoreTask -> {
                kanban.update(action.before.boardPath) { board ->
                    val ref = findCard(board, action.afterText) ?: throw KanbanException("Карточка уже изменилась")
                    val due = action.before.dueDate?.let(LocalDate::parse)
                    val time = ActionProposal.parseTime(action.before.dueTime)
                    var result = board
                    val current = result.card(ref)
                    if (current != null && (current.title != action.before.title || current.dueDate != due || current.dueTime != time)) {
                        result = result.updateCard(ref) { card ->
                            card.withTitle(action.before.title).withDue(due, if (due != null) time else null)
                        }
                    }
                    val restoredText = result.lanes[ref.lane].cards[ref.index].text
                    var restoredRef = CardRef(ref.lane, ref.index, restoredText)
                    val targetLane = result.laneIndex(action.before.lane).takeIf { it >= 0 }
                        ?: throw KanbanException("Колонка «${action.before.lane}» больше не существует")
                    if (targetLane != restoredRef.lane) {
                        result = result.moveCard(restoredRef, targetLane)
                        restoredRef = findCard(result, restoredText)
                            ?: throw KanbanException("Карточка потерялась при возврате в колонку")
                    }
                    // Put the card back exactly: same lane and same checked state.
                    result.updateCard(restoredRef) { it.copy(checked = action.before.done) }
                }
                ActionResult.Success("Задача восстановлена", ResultTarget.Board(action.before.boardPath))
            }
        }
    } catch (e: CalendarException) {
        ActionResult.Failure(e.message ?: "Ошибка календаря", permissionRequired = e.kind == CalendarException.Kind.PERMISSION)
    } catch (e: StorageException) {
        ActionResult.Failure(e.message ?: "Ошибка записи файла")
    } catch (e: KanbanException) {
        ActionResult.Failure(e.message ?: "Ошибка доски")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ActionResult.Failure("Не удалось отменить: ${e.message ?: e.javaClass.simpleName}")
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
        return ActionResult.Success(
            "Событие сохранено в «${target.label}»",
            ResultTarget.Event(id),
            UndoAction.DeleteEvent(id),
        )
    }

    private suspend fun appendToNote(p: ActionProposal.AppendToNote): ActionResult {
        val existing = notes.read(p.path)
        val suffix = appendSuffix(existing, p.text)
        val meta = notes.append(p.path, p.text)
        return ActionResult.Success(
            "Добавлено в «${meta.title}»",
            ResultTarget.Note(meta.path),
            UndoAction.RemoveAppended(meta.path, suffix, wasNewFile = existing == null),
        )
    }

    private suspend fun removeAppended(p: UndoAction.RemoveAppended): ActionResult {
        val current = notes.read(p.path) ?: return ActionResult.Failure("Заметка не найдена")
        if (p.wasNewFile) {
            if (current != p.suffix) return ActionResult.Failure("Заметка изменилась после добавления")
            notes.delete(p.path)
            return ActionResult.Success("Заметка убрана в корзину", ResultTarget.Note(p.path))
        }
        if (!current.endsWith(p.suffix)) return ActionResult.Failure("Заметка изменилась после добавления")
        val restored = current.removeSuffix(p.suffix)
        notes.save(p.path, restored)
        return ActionResult.Success("Добавленный текст убран", ResultTarget.Note(p.path))
    }

    private suspend fun addTask(p: ActionProposal.AddTask): ActionResult {
        val path = kanban.ensureBoard(p.boardPath)
        val due = p.dueDate?.let { LocalDate.parse(it) }
        val time = ActionProposal.parseTime(p.dueTime)
        val card = KanbanCard.create(p.title, due, if (due != null) time else null)
        var laneTitle = ""
        kanban.update(path) { board ->
            val laneIdx = board.laneIndex(p.lane).takeIf { it >= 0 }
                ?: board.lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 }
                ?: throw KanbanException("На доске нет подходящей колонки")
            laneTitle = board.lanes[laneIdx].title
            board.addCard(laneIdx, card)
        }
        return ActionResult.Success(
            "Задача добавлена в «$laneTitle» на доске «${VaultPaths.baseName(path)}»",
            ResultTarget.Board(path),
            UndoAction.RemoveTask(path, laneTitle, card.text),
        )
    }

    private suspend fun updateEvent(p: ActionProposal.UpdateEvent): ActionResult {
        val current = calendar.event(p.eventId)
            ?: return ActionResult.Failure("Событие не найдено — возможно, его уже удалили")
        if (eventChanged(EventSnapshot.from(current, zone()), p.before)) {
            return ActionResult.Failure("Событие изменилось после карточки. Откройте список событий и попробуйте снова")
        }
        val calendarId = p.after.calendarId ?: current.calendarId
        calendar.update(p.eventId, p.after.toNewEvent(calendarId, zone()))
        return ActionResult.Success(
            "Событие обновлено",
            ResultTarget.Event(p.eventId),
            UndoAction.RestoreEvent(p.eventId, p.before),
        )
    }

    private suspend fun deleteEvent(p: ActionProposal.DeleteEvent): ActionResult {
        val current = calendar.event(p.eventId)
            ?: return ActionResult.Failure("Событие уже удалено")
        if (eventChanged(EventSnapshot.from(current, zone()), p.event)) {
            return ActionResult.Failure("Событие изменилось после карточки. Откройте список событий и попробуйте снова")
        }
        calendar.delete(p.eventId)
        val snapshot = p.event.copy(calendarId = p.event.calendarId ?: current.calendarId)
        return ActionResult.Success(
            "Событие удалено",
            null,
            UndoAction.RecreateEvent(snapshot),
        )
    }

    private fun eventChanged(current: EventSnapshot, expected: EventSnapshot): Boolean =
        current.title != expected.title ||
            current.allDay != expected.allDay ||
            current.startDateTime != expected.startDateTime ||
            current.endDateTime != expected.endDateTime ||
            current.location != expected.location ||
            current.description != expected.description ||
            current.reminderMinutes != expected.reminderMinutes

    private suspend fun updateTask(p: ActionProposal.UpdateTask): ActionResult {
        var updatedText = p.before.text
        kanban.update(p.before.boardPath) { board ->
            val (changed, text) = applyTaskChange(board, p.before, p.after)
            updatedText = text
            changed
        }
        return ActionResult.Success(
            "Задача обновлена",
            ResultTarget.Board(p.before.boardPath),
            UndoAction.RestoreTask(p.before, updatedText),
        )
    }

    /** Applies the edit and returns the card's actual text, preserving any extra Markdown. */
    private fun applyTaskChange(board: KanbanBoard, from: TaskSnapshot, to: TaskSnapshot): Pair<KanbanBoard, String> {
        val fromRef = findCard(board, from.text)
            ?: throw KanbanException("Карточка изменилась в другом месте. Обновите доску.")
        var result = board
        var ref = fromRef
        var text = from.text
        val due = to.dueDate?.let(LocalDate::parse)
        val time = ActionProposal.parseTime(to.dueTime)
        if (to.title != from.title || to.dueDate != from.dueDate || to.dueTime != from.dueTime) {
            result = result.updateCard(ref) { card -> card.withTitle(to.title).withDue(due, if (due != null) time else null) }
            text = result.lanes[ref.lane].cards[ref.index].text
            ref = CardRef(ref.lane, ref.index, text)
        }
        if (!to.lane.equals(from.lane, ignoreCase = true)) {
            val lane = result.laneIndex(to.lane).takeIf { it >= 0 }
                ?: throw KanbanException("Колонки «${to.lane}» на доске больше нет")
            result = result.moveCard(ref, lane)
            ref = findCard(result, text) ?: throw KanbanException("Карточка потерялась при переносе")
        }
        val done = result.card(ref)?.let { it.checked || result.lanes[ref.lane].isComplete } ?: from.done
        if (to.done != done) {
            result = result.setChecked(ref, to.done)
        }
        return result to text
    }

    private fun findCard(board: KanbanBoard, text: String): CardRef? {
        board.lanes.forEachIndexed { laneIndex, lane ->
            val index = lane.cards.indexOfFirst { it.text == text }
            if (index >= 0) return CardRef(laneIndex, index, text)
        }
        return null
    }

    private fun appendSuffix(existing: String?, text: String): String {
        val block = text.trimEnd() + "\n"
        return if (existing.isNullOrEmpty() || existing.endsWith("\n")) block else "\n" + block
    }
}

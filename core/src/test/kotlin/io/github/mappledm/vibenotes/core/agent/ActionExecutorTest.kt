package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ActionExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var notes: NotesRepository
    private lateinit var calendar: FakeCalendar
    private lateinit var executor: DefaultActionExecutor

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
        notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        calendar = FakeCalendar()
        executor = DefaultActionExecutor(notes, KanbanRepository(notes), calendar) { TEST_ZONE }
    }

    private val event = ActionProposal.CreateEvent(
        title = "Встреча с Андреем", date = "2026-10-14", startTime = "15:00", endTime = "16:00",
        calendarId = 2, reminderMinutes = 30,
    )

    @Test
    fun createsEventInChosenCalendar() = runTest {
        val result = executor.execute(event) as ActionResult.Success
        assertTrue(result.message.startsWith("Событие сохранено в «Работа · me@gmail.com»"))
        assertEquals(ResultTarget.Event(101), result.target)
        val inserted = calendar.inserted.single()
        assertEquals(LocalDateTime.of(2026, 10, 14, 15, 0), inserted.start)
        assertEquals(LocalDateTime.of(2026, 10, 14, 16, 0), inserted.end)
        assertEquals(TEST_ZONE, inserted.zone)
        assertEquals(30, inserted.reminderMinutes)
    }

    @Test
    fun calendarErrorsAreNeverReportedAsSuccess() = runTest {
        calendar.permission = false
        val denied = executor.execute(event) as ActionResult.Failure
        assertTrue(denied.permissionRequired)

        calendar.permission = true
        calendar.failInsert = true
        val failed = executor.execute(event) as ActionResult.Failure
        assertEquals("Календарь отклонил событие", failed.message)
        assertTrue(calendar.inserted.isEmpty())
    }

    @Test
    fun invalidProposalIsRejectedBeforeExecution() = runTest {
        val bad = event.copy(startTime = "16:00", endTime = "15:00")
        assertTrue(executor.execute(bad) is ActionResult.Failure)
        assertTrue(calendar.inserted.isEmpty())
    }

    @Test
    fun addTaskCreatesDefaultBoardWhenMissing() = runTest {
        val result = executor.execute(ActionProposal.AddTask("Задачи.md", "Сделать", "Купить молоко", "2026-10-16")) as ActionResult.Success
        assertEquals(ResultTarget.Board("Задачи.md"), result.target)
        val text = File(root, "Задачи.md").readText()
        assertTrue(text.contains("kanban-plugin: board"))
        assertTrue(text.contains("- [ ] Купить молоко @{2026-10-16}"))
    }

    @Test
    fun createAndAppendNotes() = runTest {
        val created = executor.execute(ActionProposal.CreateNote("Идеи", "- приложение", "Проекты")) as ActionResult.Success
        assertEquals(ResultTarget.Note("Проекты/Идеи.md"), created.target)
        assertEquals("- приложение\n", File(root, "Проекты/Идеи.md").readText())

        executor.execute(ActionProposal.AppendToNote("Входящие.md", "- мысль"))
        assertEquals("- мысль\n", File(root, "Входящие.md").readText())
    }

    @Test
    fun appliedActionsCarryAnUndoThatRestoresTheVault() = runTest {
        val note = executor.execute(ActionProposal.CreateNote("Идеи", "- приложение")) as ActionResult.Success
        assertEquals(UndoAction.TrashNote("Идеи.md"), note.undo)
        executor.undo(note.undo!!)
        assertFalse(File(root, "Идеи.md").exists())

        executor.execute(ActionProposal.AppendToNote("Входящие.md", "- мысль"))
        val appended = executor.execute(ActionProposal.AppendToNote("Входящие.md", "- ещё")) as ActionResult.Success
        executor.undo(appended.undo!!)
        assertEquals("- мысль\n", File(root, "Входящие.md").readText())

        val task = executor.execute(ActionProposal.AddTask("Задачи.md", "Сделать", "Купить молоко")) as ActionResult.Success
        assertTrue(File(root, "Задачи.md").readText().contains("Купить молоко"))
        executor.undo(task.undo!!)
        assertFalse(File(root, "Задачи.md").readText().contains("Купить молоко"))
    }

    @Test
    fun updateAndDeleteEventCanBeUndone() = runTest {
        calendar.events += CalendarEvent(
            1, 1, "Врач",
            Instant.parse("2026-10-14T07:00:00Z"), Instant.parse("2026-10-14T08:00:00Z"),
            allDay = false, location = null, color = null,
        )
        val before = EventSnapshot.from(calendar.events.single(), TEST_ZONE)
        val update = ActionProposal.UpdateEvent(
            1,
            before,
            before.copy(startTime = "12:00", endTime = "13:00"),
        )
        val updated = executor.execute(update) as ActionResult.Success
        assertEquals(12, calendar.updated.single().second.start.hour)
        executor.undo(updated.undo!!)
        assertEquals(10, calendar.updated.last().second.start.hour)
        assertEquals("10:00", EventSnapshot.from(calendar.events.single(), TEST_ZONE).startTime)

        val delete = ActionProposal.DeleteEvent(1, before)
        val deleted = executor.execute(delete) as ActionResult.Success
        assertTrue(calendar.events.isEmpty())
        executor.undo(deleted.undo as UndoAction.RecreateEvent)
        assertEquals(1, calendar.events.size)
        assertEquals("Врач", calendar.events.single().title)
    }

    @Test
    fun updateTaskMovesAndRestoresTheCard() = runTest {
        val kanban = KanbanRepository(notes)
        kanban.createBoard("", "Задачи")
        kanban.update("Задачи.md") { it.addCard(0, KanbanCard.create("Купить молоко", LocalDate.parse("2026-10-16"))) }
        notes.refresh()
        val before = TaskSnapshot(
            boardPath = "Задачи.md",
            lane = "Сделать",
            title = "Купить молоко",
            dueDate = "2026-10-16",
            text = "Купить молоко @{2026-10-16}",
        )
        val after = before.copy(
            dueDate = "2026-10-20",
            done = true,
            text = "Купить молоко @{2026-10-20}",
        )
        val result = executor.execute(ActionProposal.UpdateTask(before, after)) as ActionResult.Success
        val text = File(root, "Задачи.md").readText()
        assertTrue(text.contains("Купить молоко @{2026-10-20}"))
        assertTrue(text.contains("- [x] Купить молоко"))

        executor.undo(result.undo!!)
        val restored = File(root, "Задачи.md").readText()
        assertTrue(restored.contains("Купить молоко @{2026-10-16}"))
        assertTrue(restored.contains("- [ ] Купить молоко"))
    }

    @Test
    fun updateTaskStartsWorkAndUndoReturnsTheCard() = runTest {
        val kanban = KanbanRepository(notes)
        kanban.createBoard("", "Задачи")
        kanban.update("Задачи.md") { it.addCard(0, KanbanCard.create("Отчёт")) }
        notes.refresh()
        val before = TaskSnapshot(boardPath = "Задачи.md", lane = "Сделать", title = "Отчёт", text = "Отчёт")
        val result = executor.execute(ActionProposal.UpdateTask(before, before.copy(lane = "В работе"))) as ActionResult.Success
        val board = kanban.load("Задачи.md")
        assertEquals(listOf("Отчёт"), board.lanes[1].cards.map { it.text })
        assertTrue(board.lanes[0].cards.isEmpty())

        executor.undo(result.undo!!)
        assertEquals(listOf("Отчёт"), kanban.load("Задачи.md").lanes[0].cards.map { it.text })
    }
}

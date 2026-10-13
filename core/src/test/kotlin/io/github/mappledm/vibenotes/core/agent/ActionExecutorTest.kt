package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class ActionExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var calendar: FakeCalendar
    private lateinit var executor: DefaultActionExecutor

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
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
}

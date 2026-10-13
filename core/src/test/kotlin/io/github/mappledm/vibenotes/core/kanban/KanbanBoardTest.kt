package io.github.mappledm.vibenotes.core.kanban

import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KanbanBoardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun board() = KanbanBoard.default()
        .addCard(0, KanbanCard.create("A"))
        .addCard(0, KanbanCard.create("B"))

    @Test
    fun moveToCompleteLaneChecksCardAndBack() {
        val b = board()
        val done = b.moveCard(CardRef(0, 0, "A"), toLane = 2)
        assertEquals(listOf("B"), done.lanes[0].cards.map { it.text })
        assertTrue(done.lanes[2].cards.single().checked)

        val reopened = done.moveCard(CardRef(2, 0, "A"), toLane = 1)
        assertFalse(reopened.lanes[1].cards.single().checked)
    }

    @Test
    fun setCheckedMovesBetweenLanes() {
        val checked = board().setChecked(CardRef(0, 1, "B"), true)
        assertEquals("B", checked.lanes[2].cards.single().text)
        val unchecked = checked.setChecked(CardRef(2, 0, "B"), false)
        assertEquals(listOf("B", "A"), unchecked.lanes[0].cards.map { it.text })
    }

    @Test
    fun locateFindsCardAfterExternalReorder() {
        val b = board()
        // Positions are stale (card moved), but the text still identifies it.
        val updated = b.updateCard(CardRef(0, 0, "B")) { it.withTitle("B2") }
        assertEquals(listOf("A", "B2"), updated.lanes[0].cards.map { it.text })
        try {
            b.removeCard(CardRef(0, 0, "missing"))
            fail("expected KanbanException")
        } catch (_: KanbanException) {
        }
    }

    @Test
    fun laneOperations() {
        val b = board().addLane("Когда-нибудь")
        assertEquals(listOf("Сделать", "В работе", "Когда-нибудь", "Готово"), b.lanes.map { it.title })
        assertEquals(2, b.laneIndex("когда-НИБУДЬ"))
        val renamed = b.renameLane(2, "Позже")
        assertEquals("Позже", renamed.lanes[2].title)
        assertEquals(3, renamed.removeLane(2).lanes.size)
        try {
            renamed.removeLane(0)
            fail("non-empty lane must not be removed")
        } catch (_: KanbanException) {
        }
    }

    @Test
    fun repositoryRereadsFileBeforeEachChange() = runTest {
        val root = tmp.newFolder("vault")
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        val kanban = KanbanRepository(notes)
        val path = kanban.ensureBoard("Задачи.md")
        kanban.update(path) { it.addCard(0, KanbanCard.create("с телефона")) }

        // Simulate a sync that added a card on the PC.
        val file = File(root, path)
        file.writeText(file.readText().replace("- [ ] с телефона\n", "- [ ] с телефона\n- [ ] с компьютера\n"))

        kanban.update(path) { it.addCard(1, KanbanCard.create("в работе")) }
        val result = kanban.load(path)
        assertEquals(listOf("с телефона", "с компьютера"), result.lanes[0].cards.map { it.text })
        assertEquals(listOf("в работе"), result.lanes[1].cards.map { it.text })

        notes.refresh()
        val tasks = kanban.allTasks()
        assertEquals(3, tasks.size)
        assertEquals("Задачи", tasks.first().boardTitle)
    }

    @Test
    fun ensureBoardRefusesToOverwriteRegularNote() = runTest {
        val root = tmp.newFolder("vault2")
        File(root, "Список.md").writeText("обычная заметка")
        val kanban = KanbanRepository(NotesRepository(LocalFileStore(root, Dispatchers.Unconfined)))
        try {
            kanban.ensureBoard("Список.md")
            fail("expected KanbanException")
        } catch (_: KanbanException) {
        }
        assertEquals("обычная заметка", File(root, "Список.md").readText())
    }
}

package io.github.mappledm.vibenotes.core.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class KanbanFormatTest {
    private val obsidianBoard = """
        ---

        kanban-plugin: board

        ---

        ## Todo

        - [ ] Task one @{2026-10-16}
        - [ ] Task two
        	with a second line

        ## Doing



        ## Done

        **Complete**
        - [x] Finished


        %% kanban:settings
        ```
        {"kanban-plugin":"board","list-collapse":[false,false,false]}
        ```
        %%
    """.trimIndent() + "\n"

    @Test
    fun parsesObsidianKanbanBoard() {
        val board = KanbanFormat.parse(obsidianBoard)
        assertEquals(listOf("Todo", "Doing", "Done"), board.lanes.map { it.title })
        assertEquals(2, board.lanes[0].cards.size)
        assertEquals("Task one", board.lanes[0].cards[0].title)
        assertEquals(LocalDate.of(2026, 10, 16), board.lanes[0].cards[0].dueDate)
        assertEquals(listOf("\twith a second line"), board.lanes[0].cards[1].continuation)
        assertTrue(board.lanes[2].isComplete)
        assertTrue(board.lanes[2].cards[0].checked)
        assertTrue(board.tail.startsWith("%% kanban:settings"))
    }

    @Test
    fun roundTripKeepsFileByteForByte() {
        val canonical = """
            ---

            kanban-plugin: board

            ---

            ## Todo

            - [ ] Task one @{2026-10-16}
            - [ ] Task two


            ## Doing



            ## Done

            **Complete**
            - [x] Finished


            %% kanban:settings
            ```
            {"kanban-plugin":"board"}
            ```
            %%
        """.trimIndent() + "\n"
        assertEquals(canonical, KanbanFormat.serialize(KanbanFormat.parse(canonical)))
    }

    @Test
    fun defaultBoardIsRecognisedAndReparses() {
        val text = KanbanFormat.serialize(KanbanBoard.default())
        assertTrue(KanbanFormat.isBoard(text))
        val parsed = KanbanFormat.parse(text)
        assertEquals(listOf("Сделать", "В работе", "Готово"), parsed.lanes.map { it.title })
        assertTrue(parsed.lanes.last().isComplete)
        assertEquals(text, KanbanFormat.serialize(parsed))
    }

    @Test
    fun plainNoteIsNotABoard() {
        assertFalse(KanbanFormat.isBoard("# Заметка\n\n- [ ] пункт"))
    }

    @Test
    fun cardDueTokens() {
        val card = KanbanCard.create("Сходить к врачу", LocalDate.of(2026, 10, 15), LocalTime.of(9, 0))
        assertEquals("Сходить к врачу @{2026-10-15} @@{09:00}", card.text)
        assertEquals("Сходить к врачу", card.title)
        assertEquals(LocalTime.of(9, 0), card.dueTime)

        val cleared = card.withDue(null)
        assertEquals("Сходить к врачу", cleared.text)
        assertNull(cleared.dueDate)

        val renamed = card.withTitle("Врач")
        assertEquals("Врач @{2026-10-15} @@{09:00}", renamed.text)
    }
}

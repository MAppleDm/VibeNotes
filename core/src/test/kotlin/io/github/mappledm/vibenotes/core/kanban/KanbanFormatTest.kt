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
    fun previewSummarisesColumnsAndOpenCards() {
        val board = "---\nkanban-plugin: board\n---\n\n## Todo\n\n- [ ] Call mom @{2026-10-21} @@{13:00}\n- [ ] Pay bills @{2026-10-16}\n\n" +
            "## Done\n\n**Complete**\n- [x] Old\n\n***\n\n## Archive\n\n- [x] Older\n"
        assertEquals("Todo 2 · Done 1\nCall mom · Pay bills", KanbanFormat.preview(board))
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

    @Test
    fun archiveRoundTripsAndStaysOutOfLanes() {
        val withArchive = """
            ---

            kanban-plugin: board

            ---

            ## Todo

            - [ ] Task one


            ## Done

            **Complete**
            - [x] Finished


            ***

            ## Архив

            - [x] Old card
            	with notes
            - [ ] Dropped idea

            %% kanban:settings
            ```
            {"kanban-plugin":"board"}
            ```
            %%
        """.trimIndent() + "\n"
        val board = KanbanFormat.parse(withArchive)
        assertEquals(listOf("Todo", "Done"), board.lanes.map { it.title })
        assertEquals(listOf("Old card", "Dropped idea"), board.archived.map { it.title })
        assertEquals(listOf("\twith notes"), board.archived[0].continuation)
        assertEquals("Архив", board.archive?.title)
        assertTrue(board.tail.startsWith("%% kanban:settings"))
        assertEquals(withArchive, KanbanFormat.serialize(board))

        // Archiving into a board without an archive adds the plugin's block; emptying it removes the block.
        val plain = KanbanFormat.parse(KanbanFormat.serialize(KanbanBoard.default().addCard(0, KanbanCard.create("A"))))
        val archived = KanbanFormat.serialize(plain.archiveCard(CardRef(0, 0, "A")))
        assertTrue(archived.contains("\n\n***\n\n## Archive\n\n- [ ] A\n\n%% kanban:settings"))
        val reparsed = KanbanFormat.parse(archived)
        assertEquals(listOf("A"), reparsed.archived.map { it.text })
        assertFalse(KanbanFormat.serialize(reparsed.deleteArchived(0, "A")).contains("***"))
    }
}

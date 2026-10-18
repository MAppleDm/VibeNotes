package io.github.mappledm.vibenotes.core.privacy

import io.github.mappledm.vibenotes.core.agent.ActivityKind
import io.github.mappledm.vibenotes.core.agent.ToolOutcome
import io.github.mappledm.vibenotes.core.agent.ToolRegistry
import io.github.mappledm.vibenotes.core.agent.FakeCalendar
import io.github.mappledm.vibenotes.core.agent.testContext
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiPrivacyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ruleSyntax() {
        val rules = AiIgnore.parse(
            """
            # comment
            Пароли.md
            /Работа/План.md
            Личное/
            **/черновик*.md
            """.trimIndent(),
        )
        assertTrue(rules.isHidden("Пароли.md"))
        assertTrue(rules.isHidden("Любая/папка/пароли.md"))
        assertTrue(rules.isHidden("Работа/План.md"))
        assertFalse(rules.isHidden("Другое/Работа/План.md"))
        assertTrue(rules.isHidden("Личное/Дневник.md"))
        assertTrue(rules.isHidden("Личное", isDirectory = true))
        assertFalse(rules.isHidden("Личное.md"))
        assertTrue(rules.isHidden("a/b/черновик 2.md"))
        assertTrue(rules.isHidden("server.env"), "*.env is built in")
        assertTrue(rules.isHidden(".aiignore"))
        assertFalse(rules.isHidden("Покупки.md"))
    }

    private fun assertTrue(value: Boolean, message: String) = org.junit.Assert.assertTrue(message, value)

    @Test
    fun ruleEditing() {
        val added = AiIgnore.addRule(null, AiIgnore.exactRule("Ключи.md", false))
        assertTrue(added.startsWith("#"))
        assertTrue(added.lines().contains("/Ключи.md"))
        assertEquals(added, AiIgnore.addRule(added, "/Ключи.md"))
        assertFalse(AiIgnore.removeRule(added, "/Ключи.md").lines().contains("/Ключи.md"))
        assertEquals(true, AiIgnore.renameRule(added, "Ключи.md", "Архив/Ключи.md")?.lines()?.contains("/Архив/Ключи.md"))
        assertNull(AiIgnore.renameRule(added, "Другое.md", "x.md"))
    }

    @Test
    fun textNotesKeepTheirFormat() = runTest {
        val root = tmp.newFolder("v1")
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        val env = notes.create("", "server.env", "API_KEY=secret\n")
        assertEquals("server.env", env.path)
        assertEquals("server.env", env.title)
        assertTrue(env.hiddenFromAi)
        val txt = notes.create("", "список.txt", "a")
        assertEquals("список.txt", txt.path)
        assertEquals("prod.env", notes.rename("server.env", "prod"))
        File(root, "readme.pdf").writeText("x")
        notes.refresh()
        assertEquals(setOf("prod.env", "список.txt"), notes.index.value.notes.map { it.path }.toSet())
    }

    @Test
    fun hiddenNotesNeverReachTheAssistant() = runTest {
        val root = tmp.newFolder("v2")
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        val kanban = KanbanRepository(notes)
        val registry = ToolRegistry(notes, kanban, FakeCalendar())
        notes.create("", "Банк", "Пароль от банка: 1234, отпуск в ноябре")
        notes.create("Личное", "Дневник", "отпуск в ноябре, пароль не скажу")
        notes.create("", "Отпуск", "отпуск в ноябре")
        notes.create("", "keys.env", "отпуск=TOKEN")
        notes.refresh()

        assertNull(notes.setHiddenFromAi("Банк.md", isDirectory = false, hidden = true))
        assertNull(notes.setHiddenFromAi("Личное", isDirectory = true, hidden = true))
        assertTrue(File(root, ".aiignore").readText().contains("/Банк.md"))
        assertTrue(notes.index.value.notes.first { it.path == "Банк.md" }.hiddenFromAi)
        assertEquals(setOf("Личное"), notes.index.value.hiddenFolders)
        // .aiignore itself is not a note.
        assertFalse(notes.index.value.notes.any { it.path == ".aiignore" })

        fun args(json: String) = Json.parseToJsonElement(json).jsonObject
        val search = registry.find("search_notes")!!.run(args("""{"query":"отпуск"}"""), testContext()) as ToolOutcome.Data
        assertTrue(search.forModel.contains("Отпуск.md"))
        assertFalse(search.forModel.contains("Банк"))
        assertFalse(search.forModel.contains("Дневник"))
        assertFalse(search.forModel.contains("keys.env"))

        val read = registry.find("read_note")!!.run(args("""{"path":"Банк"}"""), testContext()) as ToolOutcome.Error
        assertFalse(read.forModel.contains("1234"))
        assertEquals(ActivityKind.PRIVATE, read.activity!!.kind)

        assertTrue(registry.find("append_to_note")!!.run(args("""{"path":"Банк","text":"x"}"""), testContext()) is ToolOutcome.Error)

        // Un-hiding works for exact rules, and explains broader ones.
        assertNull(notes.setHiddenFromAi("Банк.md", isDirectory = false, hidden = false))
        assertFalse(notes.isHiddenFromAi("Банк.md"))
        assertNotNull(notes.setHiddenFromAi("Личное/Дневник.md", isDirectory = false, hidden = false))
        assertNotNull(notes.setHiddenFromAi("keys.env", isDirectory = false, hidden = false))
    }

    @Test
    fun renamedHiddenNoteStaysHidden() = runTest {
        val root = tmp.newFolder("v3")
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        notes.create("", "Ключи", "secret")
        notes.setHiddenFromAi("Ключи.md", isDirectory = false, hidden = true)
        val renamed = notes.rename("Ключи.md", "Доступы")
        assertTrue(notes.isHiddenFromAi(renamed))
        val moved = notes.move(renamed, "Архив")
        assertTrue(notes.isHiddenFromAi(moved))
        assertTrue(File(root, ".aiignore").readText().contains("/Архив/Доступы.md"))
    }
}

package io.github.mappledm.vibenotes.core.notes

import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import io.github.mappledm.vibenotes.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotesRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
        repo = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
    }

    @Test
    fun createSaveAndReopenRoundTrip() = runTest {
        val meta = repo.create("", "Покупки", "- [ ] молоко\n")
        assertEquals("Покупки.md", meta.path)
        assertEquals("- [ ] молоко\n", File(root, "Покупки.md").readText())

        repo.save(meta.path, "- [x] молоко\n- [ ] хлеб\n")
        // A fresh repository (like an app restart) sees the saved content.
        val reopened = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        reopened.refresh()
        assertEquals("- [x] молоко\n- [ ] хлеб\n", reopened.read("Покупки.md"))
        assertEquals(listOf("Покупки.md"), reopened.index.value.notes.map { it.path })
    }

    @Test
    fun createUsesUniqueNamesAndSanitizes() = runTest {
        repo.create("Работа", "План: Q4", "a")
        val second = repo.create("Работа", "План: Q4", "b")
        assertEquals("Работа/План Q4 2.md", second.path)
        assertTrue(repo.index.value.folders.contains("Работа"))
    }

    @Test
    fun refreshReadsOnlyChangedFilesAndDetectsExternalEdits() = runTest {
        File(root, "a.md").writeText("first #tag")
        File(root, "sub").mkdirs()
        File(root, "sub/b.md").writeText("second")
        File(root, "image.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, ".obsidian").mkdirs()
        File(root, ".obsidian/workspace.md").writeText("hidden")

        val index = repo.refresh()
        assertEquals(setOf("a.md", "sub/b.md"), index.notes.map { it.path }.toSet())
        assertEquals(setOf("tag"), repo.meta("a.md")!!.tags)

        val file = File(root, "a.md")
        file.writeText("changed on PC")
        file.setLastModified(file.lastModified() + 5_000)
        repo.refresh()
        assertEquals("changed on PC", repo.cachedContent("a.md"))

        File(root, "sub/b.md").delete()
        repo.refresh()
        assertNull(repo.meta("sub/b.md"))
    }

    @Test
    fun renameMoveAndConflicts() = runTest {
        val a = repo.create("", "Старое", "x")
        repo.create("", "Занято", "y")
        val renamed = repo.rename(a.path, "Новое")
        assertEquals("Новое.md", renamed)
        assertTrue(File(root, "Новое.md").exists())
        assertFalse(File(root, "Старое.md").exists())

        try {
            repo.rename(renamed, "Занято")
            fail("expected conflict")
        } catch (e: StorageException) {
            assertTrue(e.message!!.contains("уже существует"))
        }

        val moved = repo.move(renamed, "Архив")
        assertEquals("Архив/Новое.md", moved)
        assertEquals("x", File(root, "Архив/Новое.md").readText())
    }

    @Test
    fun deleteMovesToTrashAndCanBeRestored() = runTest {
        val meta = repo.create("", "Удалить меня", "важное")
        val trash = repo.delete(meta.path)
        assertEquals(".trash/Удалить меня.md", trash)
        assertFalse(File(root, "Удалить меня.md").exists())
        assertTrue(File(root, trash).exists())
        repo.refresh()
        assertTrue(repo.index.value.notes.isEmpty())

        val restored = repo.restore(trash, meta.path)
        assertEquals("Удалить меня.md", restored)
        assertEquals("важное", File(root, restored).readText())
    }

    @Test
    fun appendCreatesAndExtendsNotes() = runTest {
        repo.append("Входящие.md", "- первая")
        repo.append("Входящие.md", "- вторая")
        assertEquals("- первая\n- вторая\n", File(root, "Входящие.md").readText())
    }

    @Test
    fun searchRanksTitleMatchesAndRequiresAllTerms() = runTest {
        repo.create("", "Врач", "Записаться к терапевту в поликлинику")
        repo.create("", "Дневник", "Сегодня был у врача, всё хорошо")
        repo.create("", "Покупки", "молоко, хлеб")
        repo.refresh()

        val hits = repo.search("врач")
        assertEquals(listOf("Врач.md", "Дневник.md"), hits.map { it.note.path })
        assertTrue(hits[1].snippet.contains("врача"))

        assertEquals(listOf("Дневник.md"), repo.search("врача хорошо").map { it.note.path })
        assertTrue(repo.search("несуществующее").isEmpty())
        assertTrue(repo.search("   ").isEmpty())
    }

    @Test
    fun resolveWikiLinksByTitleOrPath() = runTest {
        repo.create("Проекты", "VibeNotes", "x")
        repo.refresh()
        assertNotNull(repo.resolveLink("vibenotes"))
        assertEquals("Проекты/VibeNotes.md", repo.resolveLink("Проекты/VibeNotes")!!.path)
        assertEquals("Проекты/VibeNotes.md", repo.resolveLink("VibeNotes#Раздел")!!.path)
        assertNull(repo.resolveLink("Нет такой"))
    }

    @Test
    fun boardsAreDetectedByFrontmatter() = runTest {
        File(root, "Задачи.md").writeText("---\n\nkanban-plugin: board\n\n---\n\n## Сделать\n\n")
        File(root, "Заметка.md").writeText("текст")
        repo.refresh()
        assertEquals(listOf("Задачи.md"), repo.index.value.boards.map { it.path })
        assertEquals(NoteKind.NOTE, repo.meta("Заметка.md")!!.kind)
    }
}

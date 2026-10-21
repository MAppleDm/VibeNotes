package io.github.mappledm.vibenotes.core.preview

import io.github.mappledm.vibenotes.core.agent.ScriptedLlm
import io.github.mappledm.vibenotes.core.ai.LlmClient
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmException
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiPreviewsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var notes: NotesRepository
    private val config = ProviderConfig("https://example.test", "model", "key")
    private val long = "Обсудили переезд офиса на Ленина, 5. Решили: переезжаем 14 ноября, " +
        "Аня собирает коробки, Олег договаривается с грузчиками и заказывает машину на утро. " +
        "Ключи от старого офиса сдаём 15-го."

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
        cache = File(tmp.newFolder("app"), "previews.json")
        notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
    }

    private fun TestScope.previews(llm: LlmClient, enabled: Boolean = true, clock: () -> Long = { 0L }) =
        AiPreviews(notes, llm, cache, { config }, { enabled }, backgroundScope, clock)

    @Test
    fun describesLongVisibleNotesOnly() = runTest {
        File(root, "Переезд.md").writeText(long)
        File(root, "Коротко.md").writeText("Купить молоко")
        File(root, "Ключи.env").writeText("TOKEN=secret " + long)
        File(root, "Дневник.md").writeText(long + " Личное.")
        File(root, ".aiignore").writeText("/Дневник.md\n")
        File(root, "Доска.md").writeText("---\nkanban-plugin: board\n---\n\n## Сделать\n\n- [ ] $long\n")
        notes.refresh()
        val llm = ScriptedLlm(ScriptedLlm.text("«Переезд 14 ноября: Аня — коробки, Олег — грузчики»"))
        val previews = previews(llm)

        previews.update(notes.index.value.notes.map { it.path })

        assertEquals(1, llm.requests.size)
        val sent = llm.requests.single().messages.last().content
        assertTrue(sent.contains("<untrusted_data source=\"note:Переезд.md\">"))
        val meta = notes.index.value.notes.associateBy { it.path }
        assertEquals("Переезд 14 ноября: Аня — коробки, Олег — грузчики", previews.textFor(meta.getValue("Переезд.md")))
        assertNull(previews.textFor(meta.getValue("Коротко.md")))
        assertNull(previews.textFor(meta.getValue("Ключи.env")))
        assertNull(previews.textFor(meta.getValue("Дневник.md")))
        assertNull(previews.textFor(meta.getValue("Доска.md")))
    }

    @Test
    fun onlyChangedNotesAreSentAgainAndRenamesReuseThePreview() = runTest {
        notes.create("", "Переезд", long)
        val llm = ScriptedLlm(ScriptedLlm.text("Первое"), ScriptedLlm.text("Второе"))
        val previews = previews(llm)

        previews.update(listOf("Переезд.md"))
        previews.update(listOf("Переезд.md"))
        assertEquals(1, llm.requests.size)

        val renamed = notes.rename("Переезд.md", "Офис")
        previews.update(listOf(renamed))
        assertEquals(1, llm.requests.size)
        assertEquals("Первое", previews.entries.value[renamed]?.text)

        notes.save(renamed, "$long Добавили: ключи сдаём 15-го.")
        previews.update(listOf(renamed))
        assertEquals(2, llm.requests.size)
        assertEquals("Второе", previews.entries.value[renamed]?.text)
    }

    @Test
    fun providerErrorPausesTheQueue() = runTest {
        notes.create("", "Один", long)
        notes.create("", "Два", "$long Ещё.")
        var calls = 0
        val failing = object : LlmClient {
            override fun stream(config: ProviderConfig, request: LlmRequest): Flow<LlmEvent> = flow {
                calls++
                throw LlmException(LlmException.Kind.BALANCE, "Недостаточно средств")
            }
        }
        val previews = previews(failing)

        previews.update(listOf("Один.md", "Два.md"))
        previews.update(listOf("Один.md", "Два.md"))

        assertEquals(1, calls)
        assertTrue(previews.entries.value.isEmpty())
    }

    @Test
    fun hidingANoteDropsItsPreview() = runTest {
        notes.create("", "Переезд", long)
        val previews = previews(ScriptedLlm(ScriptedLlm.text("Переезд 14 ноября")))
        previews.update(listOf("Переезд.md"))
        assertEquals(setOf("Переезд.md"), previews.entries.value.keys)

        notes.setHiddenFromAi("Переезд.md", isDirectory = false, hidden = true)
        previews.update(listOf("Переезд.md"))

        assertTrue(previews.entries.value.isEmpty())
        assertTrue(!cache.readText().contains("Переезд 14 ноября"))
    }

    @Test
    fun disabledSendsNothingAndShowsPlainText() = runTest {
        notes.create("", "Переезд", long)
        val llm = ScriptedLlm()
        val previews = previews(llm, enabled = false)

        previews.update(listOf("Переезд.md"))

        assertTrue(llm.requests.isEmpty())
        assertNull(previews.textFor(notes.index.value.notes.single()))
    }

    @Test
    fun cacheSurvivesRestart() = runTest {
        notes.create("", "Переезд", long)
        previews(ScriptedLlm(ScriptedLlm.text("Переезд 14 ноября"))).update(listOf("Переезд.md"))

        val llm = ScriptedLlm()
        val reopened = previews(llm)
        reopened.update(listOf("Переезд.md"))

        assertTrue(llm.requests.isEmpty())
        assertEquals("Переезд 14 ноября", reopened.entries.value["Переезд.md"]?.text)
    }

    @Test
    fun cleansModelAnswers() {
        assertEquals("План на ноябрь", AiPreviews.cleanPreview("Превью: «План на ноябрь»"))
        assertEquals("Итоги встречи", AiPreviews.cleanPreview("\"Итоги встречи\""))
        // Theses stay on their own lines; list markers, bold and the model's thinking go.
        assertEquals(
            "Переезд 14 ноя → Ленина 5\nУжин: кафе «Пушкин», 19:00",
            AiPreviews.cleanPreview("<think>подумаю\nещё</think>\n- **Переезд 14 ноя → Ленина 5**\n\n2. Ужин: кафе «Пушкин», 19:00\n"),
        )
        assertEquals(listOf("1", "2", "3", "4"), AiPreviews.cleanPreview("1\n2\n3\n4\n5")!!.lines())
        assertNull(AiPreviews.cleanPreview("  \n "))
        val long = AiPreviews.cleanPreview("а".repeat(400))!!
        assertEquals(AiPreviews.MAX_LENGTH, long.length)
        assertTrue(long.endsWith("…"))
    }
}

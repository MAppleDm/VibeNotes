package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.AgentOptions
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.DefaultActionExecutor
import io.github.mappledm.vibenotes.core.agent.FakeCalendar
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.ScriptedLlm
import io.github.mappledm.vibenotes.core.agent.TEST_NOW
import io.github.mappledm.vibenotes.core.agent.TEST_ZONE
import io.github.mappledm.vibenotes.core.agent.ToolRegistry
import io.github.mappledm.vibenotes.core.agent.testContext
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PowerFeaturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val config = ProviderConfig("https://example", "deepseek-chat", "k")
    private var ids = 0

    private class Env(val inbox: InboxService, val root: File, val llm: ScriptedLlm, val calendar: FakeCalendar)

    private fun env(
        llm: ScriptedLlm = ScriptedLlm(),
        options: AgentOptions = AgentOptions(),
        autoApply: Set<ProposalKind> = emptySet(),
    ): Env {
        val root = tmp.newFolder()
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        val kanban = KanbanRepository(notes)
        val calendar = FakeCalendar()
        val inbox = InboxService(
            store = ChatStore(File(root.parentFile, "chat-${ids++}.json")),
            engine = AssistantEngine(llm, ToolRegistry(notes, kanban, calendar)),
            executor = DefaultActionExecutor(notes, kanban, calendar) { TEST_ZONE },
            notes = notes,
            providerConfig = { config },
            contextProvider = { testContext().copy(options = options) },
            inboxNotePath = { "Входящие.md" },
            now = { TEST_NOW },
            newId = { "id${ids++}" },
            traces = TraceStore(File(root.parentFile, "traces-${ids++}.json")),
            autoApply = { autoApply },
            defaultBoard = { "Задачи.md" },
        )
        return Env(inbox, root, llm, calendar)
    }

    @Test
    fun slashCommandsParseAndSuggest() {
        assertEquals(CommandAction.TASK, SlashCommands.parse("/задача купить хлеб")!!.first.action)
        assertEquals("купить хлеб", SlashCommands.parse("/t купить хлеб")!!.second)
        assertNull(SlashCommands.parse("/неизвестно x"))
        assertNull(SlashCommands.parse("просто текст"))
        assertEquals(listOf("задача", "заметка", "заметки"), SlashCommands.suggestions("/за").map { it.name })
        assertTrue(SlashCommands.suggestions("/задача хлеб").isEmpty())
    }

    @Test
    fun dataCommandsRunWithoutAiAndStayOutOfHistory() = runTest {
        val e = env()
        assertEquals(CommandAction.TASK, e.inbox.runCommand("/задача Купить хлеб"))
        assertTrue(File(e.root, "Задачи.md").readText().contains("- [ ] Купить хлеб"))

        e.inbox.runCommand("/заметка Идея: приложение для заметок")
        assertEquals("приложение для заметок\n", File(e.root, "Идея.md").readText())

        e.inbox.runCommand("/входящие позвонить")
        assertEquals("- 2026-10-13 10:30 позвонить\n", File(e.root, "Входящие.md").readText())

        e.inbox.runCommand("/найти приложение")
        assertTrue(e.inbox.entries.value.last().text.contains("[[Идея]]"))

        e.inbox.runCommand("/задача")
        assertTrue(e.inbox.entries.value.last().text.startsWith("Напишите после команды"))

        assertEquals(CommandAction.OPEN_WEEK, e.inbox.runCommand("/неделя"))
        assertTrue(e.llm.requests.isEmpty())
        assertTrue(e.inbox.buildHistory(e.inbox.entries.value).isEmpty())
    }

    @Test
    fun traceRecordsWhatWasSent() = runTest {
        val e = env(
            ScriptedLlm(
                ScriptedLlm.calls("list_tasks" to "{}"),
                ScriptedLlm.text("Задач нет."),
            ),
        )
        e.inbox.send("какие задачи?")
        val answer = e.inbox.entries.value.last()
        assertTrue(answer.hasTrace)
        val trace = e.inbox.trace(answer.id)!!
        assertEquals("deepseek-chat", trace.model)
        assertEquals(2, trace.steps.size)
        assertEquals("list_tasks", trace.steps[0].toolCalls.single().name)
        assertTrue(trace.systemPrompt.contains("VibeNotes"))
        assertEquals("какие задачи?", trace.messages.last().content)
        assertEquals(answer.id, e.inbox.latestTraceId())
    }

    @Test
    fun disabledToolsAreNotOfferedOrExecuted() = runTest {
        val e = env(
            ScriptedLlm(
                ScriptedLlm.calls("list_calendar_events" to "{}"),
                ScriptedLlm.text("Не могу посмотреть календарь."),
            ),
            options = AgentOptions(disabledTools = setOf("list_calendar_events")),
        )
        e.inbox.send("что в календаре?")
        val first = e.llm.requests.first()
        assertFalse(first.tools.any { it.name == "list_calendar_events" })
        assertTrue(e.llm.requests[1].messages.last().content.contains("disabled"))
    }

    @Test
    fun customInstructionsReachTheSystemPrompt() = runTest {
        val e = env(ScriptedLlm(ScriptedLlm.text("ок")), options = AgentOptions(customInstructions = "Мой рабочий день 9–18."))
        e.inbox.send("привет")
        assertTrue(e.llm.requests.single().messages.first().content.contains("Мой рабочий день 9–18."))
    }

    @Test
    fun autoApplyRunsTrustedKindsOnly() = runTest {
        val e = env(
            ScriptedLlm(
                ScriptedLlm.calls(
                    "add_task" to """{"title":"Купить хлеб"}""",
                    "create_calendar_event" to """{"title":"Врач","date":"2026-10-15","start_time":"09:00"}""",
                ),
            ),
            autoApply = setOf(ProposalKind.TASK),
        )
        e.inbox.send("купить хлеб и к врачу в четверг")
        val proposals = e.inbox.entries.value.last().proposals
        val task = proposals.first { it.proposal is io.github.mappledm.vibenotes.core.agent.ActionProposal.AddTask }
        val event = proposals.first { it.proposal is io.github.mappledm.vibenotes.core.agent.ActionProposal.CreateEvent }
        assertEquals(ProposalStatus.APPLIED, task.status)
        assertTrue(task.autoApplied)
        assertEquals(ProposalStatus.PENDING, event.status)
        assertTrue(e.calendar.inserted.isEmpty())
        assertNotNull(File(e.root, "Задачи.md").readText().lines().firstOrNull { it.contains("Купить хлеб") })
    }
}

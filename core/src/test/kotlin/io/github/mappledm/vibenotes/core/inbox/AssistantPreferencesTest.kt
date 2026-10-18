package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.AgentOptions
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.DataAccess
import io.github.mappledm.vibenotes.core.agent.DefaultActionExecutor
import io.github.mappledm.vibenotes.core.agent.FakeCalendar
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.ScriptedLlm
import io.github.mappledm.vibenotes.core.agent.TEST_NOW
import io.github.mappledm.vibenotes.core.agent.TEST_ZONE
import io.github.mappledm.vibenotes.core.agent.ToolKind
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the user can tune about the assistant: tools, own instructions, auto-apply. */
class AssistantPreferencesTest {
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
            autoApply = { autoApply },
        )
        return Env(inbox, root, llm, calendar)
    }

    @Test
    fun dataAccessSwitchesCoverRealReadTools() {
        val notes = NotesRepository(LocalFileStore(tmp.newFolder(), Dispatchers.Unconfined))
        val tools = ToolRegistry(notes, KanbanRepository(notes), FakeCalendar()).tools.associateBy { it.spec.name }
        DataAccess.entries.forEach { access ->
            access.tools.forEach { name -> assertEquals("$access/$name", ToolKind.READ, tools[name]?.kind) }
        }
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

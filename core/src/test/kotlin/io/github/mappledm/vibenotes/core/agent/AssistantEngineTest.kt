package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val config = ProviderConfig("https://example", "m", "k")
    private lateinit var notes: NotesRepository
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        notes = NotesRepository(LocalFileStore(tmp.newFolder("vault"), Dispatchers.Unconfined))
        registry = ToolRegistry(notes, KanbanRepository(notes), FakeCalendar())
    }

    @Test
    fun proposalOnlyTurnStopsWithoutExtraRoundTrip() = runTest {
        val llm = ScriptedLlm(
            ScriptedLlm.calls("create_calendar_event" to """{"title":"Врач","date":"2026-10-15","start_time":"09:00"}"""),
        )
        val events = AssistantEngine(llm, registry).run(config, testContext(), emptyList(), "в четверг с утра к врачу").toList()

        val proposal = events.filterIsInstance<AssistantEvent.Proposal>().single().proposal as ActionProposal.CreateEvent
        assertEquals("Врач", proposal.title)
        assertEquals(1, llm.requests.size)
        assertTrue(events.last() is AssistantEvent.Finished)

        val request = llm.requests.single()
        assertEquals(Role.SYSTEM, request.messages.first().role)
        assertTrue(request.messages.first().content.contains("2026-10-15 Thursday / четверг"))
        assertTrue(request.tools.any { it.name == "create_calendar_event" })
    }

    @Test
    fun readToolResultsAreFedBackToTheModel() = runTest {
        notes.create("", "Отпуск", "Летим 3 ноября")
        notes.refresh()
        val llm = ScriptedLlm(
            ScriptedLlm.calls("search_notes" to """{"query":"отпуск"}"""),
            ScriptedLlm.text("Вы летите 3 ноября, см. [[Отпуск]]."),
        )
        val events = AssistantEngine(llm, registry).run(config, testContext(), emptyList(), "когда отпуск?").toList()

        assertEquals("Вы летите 3 ноября, см. [[Отпуск]].", (events.last() as AssistantEvent.Finished).text)
        assertEquals(ActivityKind.SEARCH, events.filterIsInstance<AssistantEvent.Activity>().single().activity.kind)
        val second = llm.requests[1].messages
        assertEquals(Role.ASSISTANT, second[second.size - 2].role)
        val toolMessage = second.last()
        assertEquals(Role.TOOL, toolMessage.role)
        assertEquals("call_0", toolMessage.toolCallId)
        assertTrue(toolMessage.content.contains("Отпуск.md"))
    }

    @Test
    fun invalidArgumentsLetModelRetry() = runTest {
        val llm = ScriptedLlm(
            ScriptedLlm.calls("add_task" to "{not json"),
            ScriptedLlm.calls("add_task" to """{"title":"Позвонить"}"""),
        )
        val events = AssistantEngine(llm, registry).run(config, testContext(), emptyList(), "позвонить").toList()
        assertEquals(2, llm.requests.size)
        assertTrue(llm.requests[1].messages.last().content.contains("not valid JSON"))
        assertEquals("Позвонить", (events.filterIsInstance<AssistantEvent.Proposal>().single().proposal as ActionProposal.AddTask).title)
    }

    @Test
    fun pastActionsAreReplayedAsToolCalls() = runTest {
        val llm = ScriptedLlm(ScriptedLlm.text("ok"))
        val history = listOf(
            ConversationTurn(Role.USER, "в четверг к врачу"),
            ConversationTurn(
                Role.ASSISTANT, "",
                listOf(PastAction("create_calendar_event", """{"title":"Врач"}""", "The user confirmed and the app completed it.")),
            ),
        )
        AssistantEngine(llm, registry).run(config, testContext(), history, "перенеси на 10").toList()
        val messages = llm.requests.single().messages
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.USER), messages.map { it.role })
        val call = messages[2].toolCalls.single()
        assertEquals("create_calendar_event", call.name)
        assertEquals(call.id, messages[3].toolCallId)
        assertTrue(messages[3].content.contains("confirmed"))
    }

    @Test
    fun stepLimitIsEnforced() = runTest {
        val loop = Array(5) { ScriptedLlm.calls("list_tasks" to "{}") }
        val llm = ScriptedLlm(*loop)
        val context = testContext().copy(options = AgentOptions(maxSteps = 5))
        val events = AssistantEngine(llm, registry).run(config, context, emptyList(), "x").toList()
        assertEquals(5, llm.requests.size)
        assertTrue(events.filterIsInstance<AssistantEvent.Activity>().any { it.activity.kind == ActivityKind.ERROR })
    }
}

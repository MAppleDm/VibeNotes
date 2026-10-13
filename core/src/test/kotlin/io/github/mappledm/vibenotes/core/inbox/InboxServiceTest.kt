package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.DefaultActionExecutor
import io.github.mappledm.vibenotes.core.agent.FakeCalendar
import io.github.mappledm.vibenotes.core.agent.ScriptedLlm
import io.github.mappledm.vibenotes.core.agent.TEST_NOW
import io.github.mappledm.vibenotes.core.agent.TEST_ZONE
import io.github.mappledm.vibenotes.core.agent.ToolRegistry
import io.github.mappledm.vibenotes.core.agent.testContext
import io.github.mappledm.vibenotes.core.ai.LlmClient
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmException
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var counter = 0

    private fun service(llm: LlmClient, config: ProviderConfig?, calendar: FakeCalendar = FakeCalendar()): Pair<InboxService, File> {
        val root = tmp.newFolder()
        val notes = NotesRepository(LocalFileStore(root, Dispatchers.Unconfined))
        val kanban = KanbanRepository(notes)
        val store = ChatStore(File(tmp.root, "chat-${counter}.json"))
        val inbox = InboxService(
            store = store,
            engine = AssistantEngine(llm, ToolRegistry(notes, kanban, calendar)),
            executor = DefaultActionExecutor(notes, kanban, calendar) { TEST_ZONE },
            notes = notes,
            providerConfig = { config },
            contextProvider = { testContext() },
            inboxNotePath = { "Входящие.md" },
            now = { TEST_NOW },
            newId = { "id${counter++}" },
        )
        return inbox to root
    }

    private val config = ProviderConfig("https://example", "m", "k")

    @Test
    fun withoutAiMessagesAreCapturedIntoInboxNote() = runTest {
        val (inbox, root) = service(ScriptedLlm(), config = null)
        inbox.send("купить батарейки")
        assertEquals("- 2026-10-13 10:30 купить батарейки\n", File(root, "Входящие.md").readText())
        val roles = inbox.entries.value.map { it.role }
        assertEquals(listOf(ChatRole.USER, ChatRole.NOTICE), roles)
    }

    @Test
    fun proposalIsCreatedOnlyAfterConfirmation() = runTest {
        val calendar = FakeCalendar()
        val llm = ScriptedLlm(ScriptedLlm.calls("create_calendar_event" to """{"title":"Врач","date":"2026-10-15","start_time":"09:00"}"""))
        val (inbox, _) = service(llm, config, calendar)
        inbox.send("в четверг с утра сходить к врачу")

        val assistant = inbox.entries.value.last()
        assertEquals(ChatRole.ASSISTANT, assistant.role)
        val item = assistant.proposals.single()
        assertEquals(ProposalStatus.PENDING, item.status)
        assertTrue(calendar.inserted.isEmpty())

        inbox.confirm(assistant.id, item.id)
        val applied = inbox.entries.value.last().proposals.single()
        assertEquals(ProposalStatus.APPLIED, applied.status)
        assertEquals(1, calendar.inserted.size)

        // Confirming twice does not create a duplicate.
        inbox.confirm(assistant.id, item.id)
        assertEquals(1, calendar.inserted.size)
    }

    @Test
    fun failedActionIsMarkedFailedAndCanBeEdited() = runTest {
        val calendar = FakeCalendar(permission = false)
        val llm = ScriptedLlm(ScriptedLlm.calls("create_calendar_event" to """{"title":"Врач","date":"2026-10-15","start_time":"09:00"}"""))
        val (inbox, _) = service(llm, config, calendar)
        inbox.send("врач в четверг")
        val entry = inbox.entries.value.last()
        val item = entry.proposals.single()

        inbox.confirm(entry.id, item.id)
        val failed = inbox.entries.value.last().proposals.single()
        assertEquals(ProposalStatus.FAILED, failed.status)
        assertTrue(failed.needsPermission)

        val badEdit = inbox.edit(entry.id, item.id, (item.proposal as ActionProposal.CreateEvent).copy(title = " "))
        assertNotNull(badEdit)
        assertNull(inbox.edit(entry.id, item.id, (item.proposal as ActionProposal.CreateEvent).copy(startTime = "10:00", endTime = "11:00")))
        val edited = inbox.entries.value.last().proposals.single()
        assertEquals(ProposalStatus.PENDING, edited.status)
        assertEquals("10:00", (edited.proposal as ActionProposal.CreateEvent).startTime)

        calendar.permission = true
        inbox.confirm(entry.id, item.id)
        assertEquals(ProposalStatus.APPLIED, inbox.entries.value.last().proposals.single().status)
    }

    @Test
    fun apiErrorsAreShownOnTheMessage() = runTest {
        val failing = object : LlmClient {
            override fun stream(config: ProviderConfig, request: LlmRequest): Flow<LlmEvent> = flow {
                throw LlmException(LlmException.Kind.NETWORK, "Нет соединения с сервером AI")
            }
        }
        val (inbox, _) = service(failing, config)
        inbox.send("привет")
        val entry = inbox.entries.value.last()
        assertEquals("Нет соединения с сервером AI", entry.error)
        assertTrue(!entry.streaming)
    }

    @Test
    fun historySummarisesProposals() = runTest {
        val llm = ScriptedLlm(ScriptedLlm.calls("add_task" to """{"title":"Позвонить маме"}"""))
        val (inbox, _) = service(llm, config)
        inbox.send("позвонить маме")
        val entry = inbox.entries.value.last()
        inbox.dismiss(entry.id, entry.proposals.single().id)

        val history = inbox.buildHistory(inbox.entries.value)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), history.map { it.role })
        assertTrue(history[1].text.contains("задача «Позвонить маме»"))
        assertTrue(history[1].text.contains("отклонено"))
    }

    @Test
    fun chatPersistsAcrossRestarts() = runTest {
        val file = File(tmp.root, "persist.json")
        val store = ChatStore(file)
        store.load()
        store.add(ChatEntry("a", ChatRole.USER, "привет", 1))
        val reloaded = ChatStore(file)
        reloaded.load()
        assertEquals("привет", reloaded.entries.value.single().text)
    }
}

package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.ActionExecutor
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ActionResult
import io.github.mappledm.vibenotes.core.agent.AssistantContext
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.AssistantEvent
import io.github.mappledm.vibenotes.core.agent.ConversationTurn
import io.github.mappledm.vibenotes.core.agent.ProposalValidator
import io.github.mappledm.vibenotes.core.ai.LlmException
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Inbox use cases: send a message, stream the assistant's answer, confirm or dismiss
 * proposals. Without an AI provider, messages are still captured into the Inbox note.
 */
class InboxService(
    private val store: ChatStore,
    private val engine: AssistantEngine,
    private val executor: ActionExecutor,
    private val notes: NotesRepository,
    private val providerConfig: suspend () -> ProviderConfig?,
    private val contextProvider: suspend () -> AssistantContext,
    private val inboxNotePath: () -> String,
    private val historySize: () -> Int = { 10 },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val entries = store.entries

    suspend fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        store.load()
        val history = buildHistory(store.entries.value)
        store.add(ChatEntry(newId(), ChatRole.USER, message, now().toInstant().toEpochMilli()))

        val config = providerConfig()
        if (config == null) {
            captureWithoutAi(message)
            return
        }
        respond(config, history, message)
    }

    /** Re-runs the assistant for the user message right before the failed [entryId]. */
    suspend fun retry(entryId: String) {
        val all = store.entries.value
        val index = all.indexOfFirst { it.id == entryId }
        if (index <= 0) return
        val userEntry = all.subList(0, index).lastOrNull { it.role == ChatRole.USER } ?: return
        val config = providerConfig() ?: return
        val history = buildHistory(all.subList(0, all.indexOf(userEntry)))
        store.update(entryId) { it.copy(text = "", error = null, activities = emptyList(), proposals = emptyList()) }
        respond(config, history, userEntry.text, existingEntryId = entryId)
    }

    private suspend fun respond(config: ProviderConfig, history: List<ConversationTurn>, message: String, existingEntryId: String? = null) {
        val entryId = existingEntryId ?: newId().also { id ->
            store.add(ChatEntry(id, ChatRole.ASSISTANT, "", now().toInstant().toEpochMilli(), streaming = true), persist = false)
        }
        store.update(entryId, persist = false) { it.copy(streaming = true) }
        try {
            val context = contextProvider()
            engine.run(config, context, history, message).collect { event ->
                when (event) {
                    is AssistantEvent.TextDelta -> store.update(entryId, persist = false) { it.copy(text = it.text + event.text) }
                    is AssistantEvent.Activity -> store.update(entryId, persist = false) { it.copy(activities = it.activities + event.activity) }
                    is AssistantEvent.Proposal -> store.update(entryId, persist = false) {
                        it.copy(proposals = it.proposals + ProposalItem(newId(), event.proposal))
                    }
                    is AssistantEvent.Finished -> store.update(entryId) { entry ->
                        val finalText = event.text.ifBlank {
                            if (entry.proposals.isEmpty()) "Модель вернула пустой ответ." else ""
                        }
                        entry.copy(text = finalText, streaming = false)
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.update(entryId) { it.copy(streaming = false, error = if (it.text.isEmpty() && it.proposals.isEmpty()) "Генерация остановлена" else null) }
            }
            throw e
        } catch (e: LlmException) {
            store.update(entryId) { it.copy(streaming = false, error = e.message) }
        } catch (e: Exception) {
            store.update(entryId) { it.copy(streaming = false, error = "Ошибка: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private suspend fun captureWithoutAi(message: String) {
        val path = inboxNotePath()
        val stamp = now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val line = "- $stamp " + message.replace("\n", "\n  ")
        val notice = try {
            notes.append(path, line)
            "Сохранено в «${VaultPaths.baseName(path)}». AI не подключён — подключите его в настройках, чтобы разбирать сообщения на события и задачи."
        } catch (e: Exception) {
            "Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}"
        }
        store.add(ChatEntry(newId(), ChatRole.NOTICE, notice, now().toInstant().toEpochMilli()))
    }

    suspend fun confirm(entryId: String, proposalId: String): ActionResult? {
        val item = store.find(entryId)?.proposals?.firstOrNull { it.id == proposalId } ?: return null
        if (item.status == ProposalStatus.APPLYING || item.status == ProposalStatus.APPLIED) return null
        store.updateProposal(entryId, proposalId) { it.copy(status = ProposalStatus.APPLYING, resultMessage = null) }
        val result = withContext(NonCancellable) { executor.execute(item.proposal) }
        store.updateProposal(entryId, proposalId) {
            when (result) {
                is ActionResult.Success -> it.copy(status = ProposalStatus.APPLIED, resultMessage = result.message, target = result.target, needsPermission = false)
                is ActionResult.Failure -> it.copy(status = ProposalStatus.FAILED, resultMessage = result.message, needsPermission = result.permissionRequired)
            }
        }
        return result
    }

    suspend fun dismiss(entryId: String, proposalId: String) {
        store.updateProposal(entryId, proposalId) {
            if (it.status == ProposalStatus.APPLIED || it.status == ProposalStatus.APPLYING) it else it.copy(status = ProposalStatus.DISMISSED)
        }
    }

    /** Replaces a not-yet-applied proposal with the user's edited version. */
    suspend fun edit(entryId: String, proposalId: String, proposal: ActionProposal): String? {
        ProposalValidator.validate(proposal)?.let { return it }
        store.updateProposal(entryId, proposalId) {
            if (it.status == ProposalStatus.APPLIED) it else it.copy(proposal = proposal, status = ProposalStatus.PENDING, resultMessage = null)
        }
        return null
    }

    suspend fun clear() = store.clear()

    /** Recent turns for the model; proposals are summarised so follow-ups ("move it to 10") work. */
    internal fun buildHistory(entries: List<ChatEntry>): List<ConversationTurn> =
        entries.filter { it.role != ChatRole.NOTICE && it.error == null && !it.streaming }
            .takeLast(historySize())
            .mapNotNull { entry ->
                when (entry.role) {
                    ChatRole.USER -> ConversationTurn(Role.USER, entry.text)
                    ChatRole.ASSISTANT -> {
                        val cards = entry.proposals.joinToString("\n") { "[${ProposalText.summary(it.proposal)} — ${statusText(it)}]" }
                        val text = listOf(entry.text, cards).filter { it.isNotBlank() }.joinToString("\n")
                        if (text.isBlank()) null else ConversationTurn(Role.ASSISTANT, text)
                    }
                    ChatRole.NOTICE -> null
                }
            }

    private fun statusText(item: ProposalItem): String = when (item.status) {
        ProposalStatus.PENDING -> "ждёт подтверждения"
        ProposalStatus.APPLYING -> "выполняется"
        ProposalStatus.APPLIED -> "выполнено"
        ProposalStatus.DISMISSED -> "отклонено пользователем"
        ProposalStatus.FAILED -> "ошибка: ${item.resultMessage}"
    }
}

object ProposalText {
    fun summary(p: ActionProposal): String = when (p) {
        is ActionProposal.CreateEvent -> {
            val time = if (p.allDay) "весь день" else "${p.startTime}–${p.endTime ?: ""}"
            "событие «${p.title}» ${p.date} $time"
        }
        is ActionProposal.CreateNote -> "заметка «${p.title}»"
        is ActionProposal.AppendToNote -> "дописать в «${VaultPaths.baseName(p.path)}»"
        is ActionProposal.AddTask -> "задача «${p.title}» на доске «${VaultPaths.baseName(p.boardPath)}»" +
            (p.dueDate?.let { " до $it" } ?: "")
    }
}

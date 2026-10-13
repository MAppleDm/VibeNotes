package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.ActionExecutor
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.AgentTrace
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.kind
import io.github.mappledm.vibenotes.core.agent.ActionResult
import io.github.mappledm.vibenotes.core.agent.AssistantContext
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.AssistantEvent
import io.github.mappledm.vibenotes.core.agent.ConversationTurn
import io.github.mappledm.vibenotes.core.agent.PastAction
import io.github.mappledm.vibenotes.core.agent.toolCall
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
    private val traces: TraceStore? = null,
    private val autoApply: () -> Set<ProposalKind> = { emptySet() },
    private val defaultBoard: () -> String = { "Задачи.md" },
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
                    is AssistantEvent.Trace -> traces?.let { store ->
                        runCatching { store.put(entryId, event.trace) }
                        this.store.update(entryId, persist = false) { it.copy(hasTrace = true) }
                    }
                    is AssistantEvent.Finished -> {
                        var claimed = false
                        store.update(entryId) { entry ->
                            val finalText = event.text.ifBlank {
                                if (entry.proposals.isEmpty()) "Модель вернула пустой ответ." else ""
                            }
                            // Safety net: the text says something was done, but no action was proposed.
                            claimed = entry.proposals.isEmpty() && entry.activities.isEmpty() && claimsCompletion(finalText)
                            entry.copy(text = finalText, streaming = false, unverifiedClaim = claimed)
                        }
                        applyAutomatically(entryId)
                        if (claimed) {
                            store.add(
                                ChatEntry(
                                    newId(), ChatRole.NOTICE,
                                    "Ничего не добавлено: ассистент написал об этом текстом, но не предложил действие. " +
                                        "Отправьте запрос ещё раз — должна появиться карточка для подтверждения.",
                                    now().toInstant().toEpochMilli(),
                                ),
                            )
                        }
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

    /** Proposals of kinds the user trusts are applied right away (events only with a known calendar). */
    private suspend fun applyAutomatically(entryId: String) {
        val kinds = autoApply()
        if (kinds.isEmpty()) return
        val entry = store.find(entryId) ?: return
        for (item in entry.proposals) {
            val proposal = item.proposal
            if (item.status != ProposalStatus.PENDING || proposal.kind !in kinds) continue
            if (proposal is ActionProposal.CreateEvent && proposal.calendarId == null) continue
            store.updateProposal(entryId, item.id) { it.copy(autoApplied = true) }
            confirm(entryId, item.id)
        }
    }

    suspend fun trace(entryId: String): AgentTrace? = traces?.get(entryId)

    suspend fun latestTraceId(): String? = traces?.latestId()

    /**
     * Runs a "/command". Data commands (task, note, inbox, search, help) execute here without AI
     * and leave a note in the chat; navigation commands are returned for the UI to handle.
     */
    suspend fun runCommand(input: String): CommandAction? {
        val (command, argument) = SlashCommands.parse(input) ?: return null
        val local = command.action in setOf(CommandAction.TASK, CommandAction.NOTE, CommandAction.INBOX, CommandAction.SEARCH, CommandAction.HELP)
        if (!local) return command.action
        store.load()
        store.add(ChatEntry(newId(), ChatRole.USER, input.trim(), now().toInstant().toEpochMilli(), isCommand = true))
        val reply = if (command.takesArgument && argument.isBlank()) {
            "Напишите после команды: `/${command.name} ${command.hint}`"
        } else {
            when (command.action) {
                CommandAction.TASK -> resultText(executor.execute(ActionProposal.AddTask(defaultBoard(), "", argument)))
                CommandAction.NOTE -> {
                    val (title, body) = splitNote(argument)
                    resultText(executor.execute(ActionProposal.CreateNote(title, body, "")), link = title)
                }
                CommandAction.INBOX -> {
                    val stamp = now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    resultText(executor.execute(ActionProposal.AppendToNote(inboxNotePath(), "- $stamp " + argument.replace("\n", "\n  "))))
                }
                CommandAction.SEARCH -> searchText(argument)
                else -> SlashCommands.help()
            }
        }
        store.add(ChatEntry(newId(), ChatRole.NOTICE, reply, now().toInstant().toEpochMilli()))
        return command.action
    }

    private fun resultText(result: ActionResult, link: String? = null): String = when (result) {
        is ActionResult.Success -> result.message + (link?.let { " — [[${it}]]" } ?: "")
        is ActionResult.Failure -> "Не удалось: ${result.message}"
    }

    private fun splitNote(argument: String): Pair<String, String> {
        val colon = argument.indexOf(':')
        if (colon in 1..80) return argument.substring(0, colon).trim() to argument.substring(colon + 1).trim()
        val firstLine = argument.lineSequence().first().trim()
        val title = if (firstLine.length <= 60) firstLine else firstLine.take(60).substringBeforeLast(' ').ifBlank { firstLine.take(60) }
        return title to argument.trim()
    }

    private fun searchText(query: String): String {
        val hits = notes.search(query, 8)
        if (hits.isEmpty()) return "По запросу «$query» ничего не найдено."
        return "Найдено: ${hits.size}\n" + hits.joinToString("\n") { hit ->
            "- [[${hit.note.title}]]" + (hit.snippet.takeIf { it.isNotBlank() }?.let { " — ${it.take(120)}" } ?: "")
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

    suspend fun clear() {
        store.clear()
        traces?.clear()
    }

    /**
     * Recent turns for the model. Proposals are replayed as real tool calls with their outcome
     * (confirmed / dismissed / failed), so follow-ups like "move it to 10" work and the model
     * never sees actions written as text it could imitate.
     */
    internal fun buildHistory(entries: List<ChatEntry>): List<ConversationTurn> =
        entries.filter { it.role != ChatRole.NOTICE && it.error == null && !it.streaming && !it.unverifiedClaim && !it.isCommand }
            // Older answers stored before the flag existed: same check, applied retroactively.
            .filterNot { it.role == ChatRole.ASSISTANT && it.proposals.isEmpty() && it.activities.isEmpty() && claimsCompletion(it.text) }
            .takeLast(historySize())
            .mapNotNull { entry ->
                when (entry.role) {
                    ChatRole.USER -> ConversationTurn(Role.USER, entry.text)
                    ChatRole.ASSISTANT -> {
                        val actions = entry.proposals.map { item ->
                            val (tool, args) = item.proposal.toolCall()
                            PastAction(tool, args, outcomeText(item))
                        }
                        if (entry.text.isBlank() && actions.isEmpty()) null else ConversationTurn(Role.ASSISTANT, entry.text, actions)
                    }
                    ChatRole.NOTICE -> null
                }
            }

    private fun outcomeText(item: ProposalItem): String = when (item.status) {
        ProposalStatus.PENDING, ProposalStatus.APPLYING ->
            "Shown to the user as a card; NOT confirmed yet, nothing was created."
        ProposalStatus.APPLIED -> "The user confirmed and the app completed it: ${item.resultMessage ?: "done"}."
        ProposalStatus.DISMISSED -> "The user dismissed the card. Nothing was created."
        ProposalStatus.FAILED -> "Failed: ${item.resultMessage ?: "error"}. Nothing was created."
    }

}

private val completionClaim =
    Regex("""(?i)(выполнено|добавил|добавлено|добавлена|создал|создано|создана|запланировал|запланировано|поставил|сохранил|сохранено|внёс|занёс|done|created|added|scheduled)""")

internal fun claimsCompletion(text: String): Boolean = completionClaim.containsMatchIn(text)

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

package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.ActionExecutor
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.kind
import io.github.mappledm.vibenotes.core.agent.ActionResult
import io.github.mappledm.vibenotes.core.agent.AssistantContext
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.AssistantEvent
import io.github.mappledm.vibenotes.core.agent.ConversationTurn
import io.github.mappledm.vibenotes.core.agent.PastAction
import io.github.mappledm.vibenotes.core.agent.cardRef
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
    private val autoApply: () -> Set<ProposalKind> = { emptySet() },
) {
    val entries = store.entries

    /** [focus]: the tab the message was written on; it tells the model which day, board or note kind is meant. */
    suspend fun send(text: String, focus: ChatFocus? = null) {
        val message = text.trim()
        if (message.isEmpty()) return
        store.load()
        val history = buildHistory(store.entries.value)
        store.add(ChatEntry(newId(), ChatRole.USER, message, now().toInstant().toEpochMilli(), focus = focus))

        val config = providerConfig()
        if (config == null) {
            captureWithoutAi(message)
            return
        }
        respond(config, history, withFocus(message, focus))
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
        respond(config, history, withFocus(userEntry.text, userEntry.focus), existingEntryId = entryId)
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
                    is AssistantEvent.Proposal -> addProposal(entryId, event.proposal, event.replacesCard)
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

    /**
     * A correction ("нет, в 16") takes the place of the card it corrects: the old card is marked
     * [ProposalStatus.REPLACED] and can no longer be confirmed, so the chat never holds two live
     * versions of one action. The model names the card; without that, an open card about the same
     * thing (same title and day, same event or task) counts as corrected.
     */
    private suspend fun addProposal(entryId: String, proposal: ActionProposal, replacesCard: String?) {
        val open = store.entries.value
            .filter { it.id != entryId }
            .flatMap { entry -> entry.proposals.filter { it.status.isOpen }.map { entry.id to it } }
        val ref = replacesCard?.trim()?.removePrefix("card ")
        val key = proposal.subjectKey()
        val replaced = open.lastOrNull { (_, item) -> cardRef(item.id) == ref }
            ?: key?.let { k -> open.lastOrNull { (_, item) -> item.proposal.subjectKey() == k } }
        val item = ProposalItem(newId(), proposal, original = replaced?.second?.let { it.original ?: it.proposal })
        store.update(entryId, persist = false) { it.copy(proposals = it.proposals + item) }
        if (replaced != null) {
            store.updateProposal(replaced.first, replaced.second.id) {
                if (it.status.isOpen) it.copy(status = ProposalStatus.REPLACED, resultMessage = null) else it
            }
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
        // A replaced card was corrected later: only its newer version can be confirmed.
        if (item.status == ProposalStatus.APPLYING || item.status == ProposalStatus.APPLIED || item.status == ProposalStatus.REPLACED) return null
        store.updateProposal(entryId, proposalId) { it.copy(status = ProposalStatus.APPLYING, resultMessage = null) }
        val result = withContext(NonCancellable) { executor.execute(item.proposal) }
        store.updateProposal(entryId, proposalId) {
            when (result) {
                is ActionResult.Success -> it.copy(
                    status = ProposalStatus.APPLIED,
                    resultMessage = result.message,
                    target = result.target,
                    needsPermission = false,
                    undo = result.undo,
                    undoError = null,
                )
                is ActionResult.Failure -> it.copy(
                    status = ProposalStatus.FAILED,
                    resultMessage = result.message,
                    needsPermission = result.permissionRequired,
                    undo = null,
                    undoError = null,
                )
            }
        }
        return result
    }

    /** Takes back an applied action. The card stays in the chat as "Отменено". */
    suspend fun undo(entryId: String, proposalId: String): ActionResult? {
        val item = store.find(entryId)?.proposals?.firstOrNull { it.id == proposalId } ?: return null
        if (item.status != ProposalStatus.APPLIED) return null
        val action = item.undo ?: return null
        store.updateProposal(entryId, proposalId) { it.copy(status = ProposalStatus.APPLYING, undoError = null) }
        val result = withContext(NonCancellable) { executor.undo(action) }
        store.updateProposal(entryId, proposalId) {
            when (result) {
                is ActionResult.Success -> it.copy(
                    status = ProposalStatus.UNDONE,
                    resultMessage = "Отменено: ${result.message}",
                    target = null,
                    undo = null,
                    undoError = null,
                )
                is ActionResult.Failure -> it.copy(status = ProposalStatus.APPLIED, undoError = result.message)
            }
        }
        return result
    }

    suspend fun dismiss(entryId: String, proposalId: String) {
        store.updateProposal(entryId, proposalId) {
            if (it.status == ProposalStatus.APPLIED || it.status == ProposalStatus.APPLYING || it.status == ProposalStatus.REPLACED) it
            else it.copy(status = ProposalStatus.DISMISSED)
        }
    }

    /** Replaces a not-yet-applied proposal with the user's edited version. */
    suspend fun edit(entryId: String, proposalId: String, proposal: ActionProposal): String? {
        ProposalValidator.validate(proposal)?.let { return it }
        store.updateProposal(entryId, proposalId) {
            if (it.status == ProposalStatus.APPLIED || it.status == ProposalStatus.REPLACED) it
            else it.copy(proposal = proposal, status = ProposalStatus.PENDING, resultMessage = null)
        }
        return null
    }

    suspend fun clear() = store.clear()

    /**
     * Recent turns for the model. Proposals are replayed as real tool calls with their outcome
     * (confirmed / dismissed / failed), so follow-ups like "move it to 10" work and the model
     * never sees actions written as text it could imitate.
     */
    internal fun buildHistory(entries: List<ChatEntry>): List<ConversationTurn> =
        entries.filter { it.role != ChatRole.NOTICE && it.error == null && !it.streaming && !it.unverifiedClaim }
            // Older answers stored before the flag existed: same check, applied retroactively.
            .filterNot { it.role == ChatRole.ASSISTANT && it.proposals.isEmpty() && it.activities.isEmpty() && claimsCompletion(it.text) }
            // A message without an answer (saved offline, failed, or answered only by a false claim)
            // is not a request the model should pick up again.
            .let { kept -> kept.filterIndexed { i, e -> e.role != ChatRole.USER || kept.getOrNull(i + 1)?.role == ChatRole.ASSISTANT } }
            .takeLast(historySize())
            .mapNotNull { entry ->
                when (entry.role) {
                    ChatRole.USER -> ConversationTurn(Role.USER, withFocus(entry.text, entry.focus))
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
        ProposalStatus.PENDING ->
            "Shown to the user as card ${cardRef(item.id)}; NOT confirmed yet, nothing was created or changed. " +
                "To correct it, propose again with replaces_card=\"${cardRef(item.id)}\"."
        ProposalStatus.APPLYING -> "Shown to the user as a card; NOT confirmed yet, nothing was created or changed."
        ProposalStatus.APPLIED -> "The user confirmed and the app completed it: ${item.resultMessage ?: "done"}."
        ProposalStatus.UNDONE -> "The user confirmed it, then undid it. Nothing created or changed by it remains."
        ProposalStatus.DISMISSED -> "The user dismissed the card. Nothing was created or changed."
        ProposalStatus.FAILED -> "Card ${cardRef(item.id)} failed: ${item.resultMessage ?: "error"}. Nothing was created or changed. " +
            "To correct it, propose again with replaces_card=\"${cardRef(item.id)}\"."
        ProposalStatus.REPLACED -> "Replaced by a corrected card in a later turn. Nothing was created or changed by this one."
    }

}

/** The tab's context goes to the model in front of the message, never into the text the user sees. */
internal fun withFocus(message: String, focus: ChatFocus?): String =
    if (focus == null) message else "[Context: ${focus.hint}]\n$message"

/**
 * What a proposal is about, to recognise a correction the model did not mark: the same event title on the
 * same day, the same task, note or existing event. Appending text has no subject: two thoughts are two cards.
 */
internal fun ActionProposal.subjectKey(): String? = when (this) {
    is ActionProposal.CreateEvent -> "event:${title.trim().lowercase()}@$date"
    is ActionProposal.AddTask -> "task:${boardPath.lowercase()}:${title.trim().lowercase()}"
    is ActionProposal.CreateNote -> "note:${folder.lowercase()}:${title.trim().lowercase()}"
    is ActionProposal.AppendToNote -> null
    is ActionProposal.UpdateEvent -> "event#$eventId"
    is ActionProposal.DeleteEvent -> "event#$eventId"
    is ActionProposal.UpdateTask -> "task:${before.boardPath.lowercase()}:${before.text}"
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
        is ActionProposal.UpdateEvent -> "перенос события «${p.after.title}» на ${p.after.date}" +
            (p.after.startTime?.let { " $it" } ?: "")
        is ActionProposal.DeleteEvent -> "удаление события «${p.event.title}»"
        is ActionProposal.UpdateTask -> "изменение задачи «${p.before.title}»"
    }
}

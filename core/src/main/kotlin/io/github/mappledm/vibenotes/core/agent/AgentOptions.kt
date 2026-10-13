package io.github.mappledm.vibenotes.core.agent

import kotlinx.serialization.Serializable

/** How the assistant behaves, as tuned by the user in settings. */
data class AgentOptions(
    val temperature: Double = 0.3,
    /** Model round-trips allowed per message (each read tool result needs one). */
    val maxSteps: Int = 5,
    /** Tool names the user switched off; they are neither offered to nor executable by the model. */
    val disabledTools: Set<String> = emptySet(),
    /** The user's own instructions (AGENTS.md in the vault), appended to the system prompt. */
    val customInstructions: String? = null,
)

/** What the assistant may look at, in the user's terms; each switch covers some READ tools. */
enum class DataAccess(val label: String, val description: String, val tools: Set<String>) {
    NOTES("Заметки", "Искать и читать заметки, когда это нужно для ответа", setOf("search_notes", "read_note")),
    TASKS("Задачи", "Смотреть карточки и сроки на досках", setOf("list_tasks")),
    CALENDAR("Календарь", "Смотреть события, чтобы отвечать «что у меня на неделе» и не ставить встречи внахлёст", setOf("list_calendar_events")),
}

/** Kinds of changes the assistant can propose; the user may let some apply without a card. */
@Serializable
enum class ProposalKind(val label: String) {
    EVENT("события в календарь"),
    TASK("задачи на доску"),
    NOTE("новые заметки"),
    APPEND("записи в существующие заметки"),
}

val ActionProposal.kind: ProposalKind
    get() = when (this) {
        is ActionProposal.CreateEvent -> ProposalKind.EVENT
        is ActionProposal.AddTask -> ProposalKind.TASK
        is ActionProposal.CreateNote -> ProposalKind.NOTE
        is ActionProposal.AppendToNote -> ProposalKind.APPEND
    }

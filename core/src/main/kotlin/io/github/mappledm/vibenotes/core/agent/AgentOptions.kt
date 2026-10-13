package io.github.mappledm.vibenotes.core.agent

import kotlinx.serialization.Serializable

/** Advanced, user-tunable behaviour of the assistant ("under the hood" settings). */
data class AgentOptions(
    val temperature: Double = 0.3,
    /** Model round-trips allowed per message (each read tool result needs one). */
    val maxSteps: Int = 5,
    /** Tool names the user switched off; they are neither offered to nor executable by the model. */
    val disabledTools: Set<String> = emptySet(),
    /** The user's own instructions (AGENTS.md in the vault), appended to the system prompt. */
    val customInstructions: String? = null,
)

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

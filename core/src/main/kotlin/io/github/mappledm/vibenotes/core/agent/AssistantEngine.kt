package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.LlmClient
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmMessage
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class ConversationTurn(val role: Role, val text: String)

sealed interface AssistantEvent {
    data class TextDelta(val text: String) : AssistantEvent
    data class Activity(val activity: ToolActivity) : AssistantEvent
    data class Proposal(val proposal: ActionProposal) : AssistantEvent
    data class Finished(val text: String) : AssistantEvent
}

/**
 * The reasoning loop: the model may call READ tools (executed immediately, results fed back)
 * and PROPOSE tools (turned into cards; never executed here). The model reasons, the tools act.
 */
class AssistantEngine(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    private val maxSteps: Int = 5,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(
        config: ProviderConfig,
        context: AssistantContext,
        history: List<ConversationTurn>,
        userText: String,
    ): Flow<AssistantEvent> = flow {
        val messages = mutableListOf(LlmMessage(Role.SYSTEM, SystemPrompt.build(context)))
        history.filter { it.text.isNotBlank() && (it.role == Role.USER || it.role == Role.ASSISTANT) }
            .forEach { messages += LlmMessage(it.role, it.text) }
        messages += LlmMessage(Role.USER, userText)

        val specs = registry.tools.map { it.spec }
        val finalText = StringBuilder()

        for (step in 0 until maxSteps) {
            var completed: LlmEvent.Completed? = null
            var stepHasText = false
            llm.stream(config, LlmRequest(messages.toList(), specs, temperature = 0.3)).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        if (!stepHasText && finalText.isNotEmpty()) emit(AssistantEvent.TextDelta("\n\n"))
                        stepHasText = true
                        emit(AssistantEvent.TextDelta(event.text))
                    }
                    is LlmEvent.ReasoningDelta -> Unit
                    is LlmEvent.Completed -> completed = event
                }
            }
            val result = completed ?: break
            if (result.text.isNotBlank()) {
                if (finalText.isNotEmpty()) finalText.append("\n\n")
                finalText.append(result.text.trim())
            }
            if (result.toolCalls.isEmpty()) break

            messages += LlmMessage(
                Role.ASSISTANT,
                result.text,
                toolCalls = result.toolCalls,
                reasoning = result.reasoning.ifEmpty { null },
            )
            var needsAnotherStep = false
            for (call in result.toolCalls) {
                val tool = registry.find(call.name)
                val args: JsonObject? = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
                val reply: String = when {
                    tool == null -> {
                        needsAnotherStep = true
                        "Error: unknown tool '${call.name}'."
                    }
                    args == null -> {
                        needsAnotherStep = true
                        "Error: arguments are not valid JSON."
                    }
                    else -> when (val outcome = tool.run(args, context)) {
                        is ToolOutcome.Data -> {
                            needsAnotherStep = true
                            emit(AssistantEvent.Activity(outcome.activity))
                            outcome.forModel
                        }
                        is ToolOutcome.Propose -> {
                            emit(AssistantEvent.Proposal(outcome.proposal))
                            "Shown to the user as a confirmation card. It is NOT executed yet; do not claim it is done."
                        }
                        is ToolOutcome.Error -> {
                            needsAnotherStep = true
                            outcome.activity?.let { emit(AssistantEvent.Activity(it)) }
                            outcome.forModel
                        }
                    }
                }
                messages += LlmMessage(Role.TOOL, reply, toolCallId = call.id)
            }
            // Only proposals: the cards speak for themselves, no extra round-trip needed.
            if (!needsAnotherStep) break
            if (step == maxSteps - 1) {
                emit(AssistantEvent.Activity(ToolActivity(ActivityKind.ERROR, "Достигнут лимит шагов ассистента")))
            }
        }
        emit(AssistantEvent.Finished(finalText.toString()))
    }
}

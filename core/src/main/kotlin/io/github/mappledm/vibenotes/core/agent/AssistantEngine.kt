package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.LlmClient
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmMessage
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.ai.ToolCallRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * An action proposed in an earlier turn, replayed to the model as a real tool call plus its
 * outcome. Writing actions as plain text in history taught the model to "perform" them in text.
 */
data class PastAction(val toolName: String, val argumentsJson: String, val outcome: String)

data class ConversationTurn(val role: Role, val text: String, val actions: List<PastAction> = emptyList())

sealed interface AssistantEvent {
    data class TextDelta(val text: String) : AssistantEvent
    data class Activity(val activity: ToolActivity) : AssistantEvent
    data class Proposal(val proposal: ActionProposal) : AssistantEvent
    data class Trace(val trace: AgentTrace) : AssistantEvent
    data class Finished(val text: String) : AssistantEvent
}

/**
 * The reasoning loop: the model may call READ tools (executed immediately, results fed back)
 * and PROPOSE tools (turned into cards; never executed here). The model reasons, the tools act.
 * Behaviour is tuned by [AgentOptions] in the context (temperature, steps, disabled tools).
 */
class AssistantEngine(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(
        config: ProviderConfig,
        context: AssistantContext,
        history: List<ConversationTurn>,
        userText: String,
    ): Flow<AssistantEvent> = flow {
        val options = context.options
        val systemPrompt = SystemPrompt.build(context)
        val messages = mutableListOf(LlmMessage(Role.SYSTEM, systemPrompt))
        history.forEachIndexed { turnIndex, turn ->
            when (turn.role) {
                Role.USER -> if (turn.text.isNotBlank()) messages += LlmMessage(Role.USER, turn.text)
                Role.ASSISTANT -> {
                    if (turn.actions.isNotEmpty()) {
                        val calls = turn.actions.mapIndexed { i, action ->
                            ToolCallRequest("history_${turnIndex}_$i", action.toolName, action.argumentsJson)
                        }
                        messages += LlmMessage(Role.ASSISTANT, "", toolCalls = calls)
                        turn.actions.forEachIndexed { i, action -> messages += LlmMessage(Role.TOOL, action.outcome, toolCallId = calls[i].id) }
                    }
                    val text = turn.text.ifBlank { if (turn.actions.isNotEmpty()) "Показал карточку для подтверждения." else "" }
                    if (text.isNotBlank()) messages += LlmMessage(Role.ASSISTANT, text)
                }
                else -> Unit
            }
        }
        messages += LlmMessage(Role.USER, userText)
        val sentMessages = messages.drop(1).map { TraceMessage(it.role.name.lowercase(), traceText(it)) }

        val enabledTools = registry.tools.filter { it.spec.name !in options.disabledTools }
        val specs = enabledTools.map { it.spec }
        val finalText = StringBuilder()
        val traceSteps = mutableListOf<TraceStep>()
        val maxSteps = options.maxSteps.coerceIn(1, 10)

        for (step in 0 until maxSteps) {
            var completed: LlmEvent.Completed? = null
            var stepHasText = false
            val started = System.currentTimeMillis()
            llm.stream(config, LlmRequest(messages.toList(), specs, temperature = options.temperature)).collect { event ->
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
            val duration = System.currentTimeMillis() - started
            if (result.text.isNotBlank()) {
                if (finalText.isNotEmpty()) finalText.append("\n\n")
                finalText.append(result.text.trim())
            }
            val tracedCalls = mutableListOf<TraceToolCall>()
            fun recordStep() {
                traceSteps += TraceStep(
                    durationMs = duration,
                    finishReason = result.finishReason,
                    promptTokens = result.usage?.promptTokens,
                    completionTokens = result.usage?.completionTokens,
                    cachedTokens = result.usage?.cachedTokens,
                    text = result.text,
                    toolCalls = tracedCalls.toList(),
                )
            }
            if (result.toolCalls.isEmpty()) {
                recordStep()
                break
            }

            messages += LlmMessage(
                Role.ASSISTANT,
                result.text,
                toolCalls = result.toolCalls,
                reasoning = result.reasoning.ifEmpty { null },
            )
            var needsAnotherStep = false
            for (call in result.toolCalls) {
                val tool = enabledTools.firstOrNull { it.spec.name == call.name }
                val args: JsonObject? = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
                val reply: String = when {
                    tool == null -> {
                        needsAnotherStep = true
                        if (registry.find(call.name) != null) "Error: the user disabled the tool '${call.name}'." else "Error: unknown tool '${call.name}'."
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
                tracedCalls += TraceToolCall(call.name, call.argumentsJson, reply.take(MAX_TRACE_CHARS))
                messages += LlmMessage(Role.TOOL, reply, toolCallId = call.id)
            }
            recordStep()
            // Only proposals: the cards speak for themselves, no extra round-trip needed.
            if (!needsAnotherStep) break
            if (step == maxSteps - 1) {
                emit(AssistantEvent.Activity(ToolActivity(ActivityKind.ERROR, "Достигнут лимит шагов ассистента ($maxSteps)")))
            }
        }
        emit(
            AssistantEvent.Trace(
                AgentTrace(
                    model = config.model,
                    baseUrl = config.baseUrl,
                    systemPrompt = systemPrompt,
                    messages = sentMessages,
                    tools = specs.map { it.name },
                    steps = traceSteps,
                ),
            ),
        )
        emit(AssistantEvent.Finished(finalText.toString()))
    }

    private fun traceText(message: LlmMessage): String {
        val calls = message.toolCalls.joinToString("\n") { "→ ${it.name}(${it.argumentsJson})" }
        return listOf(message.content.take(MAX_TRACE_CHARS), calls).filter { it.isNotBlank() }.joinToString("\n")
    }

    private companion object {
        const val MAX_TRACE_CHARS = 3000
    }
}

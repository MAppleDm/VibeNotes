package io.github.mappledm.vibenotes.core.agent

import kotlinx.serialization.Serializable

/**
 * What exactly happened for one answer: the prompt and messages sent, each model call
 * (time, tokens, finish reason) and every tool call with its result. Shown on request
 * ("Под капотом") — it is the ground truth of what left the phone.
 */
@Serializable
data class AgentTrace(
    val model: String,
    val baseUrl: String,
    val systemPrompt: String,
    val messages: List<TraceMessage>,
    val tools: List<String>,
    val steps: List<TraceStep>,
) {
    val promptTokens: Int get() = steps.sumOf { it.promptTokens ?: 0 }
    val completionTokens: Int get() = steps.sumOf { it.completionTokens ?: 0 }
    val durationMs: Long get() = steps.sumOf { it.durationMs }
}

@Serializable
data class TraceMessage(val role: String, val content: String)

@Serializable
data class TraceStep(
    val durationMs: Long,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val text: String = "",
    val toolCalls: List<TraceToolCall> = emptyList(),
)

@Serializable
data class TraceToolCall(val name: String, val arguments: String, val result: String)

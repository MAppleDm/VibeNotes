package io.github.mappledm.vibenotes.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Connection settings of an OpenAI-compatible provider (DeepSeek by default). */
data class ProviderConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    /** Never print the key. */
    override fun toString(): String = "ProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"

    companion object {
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEEPSEEK_MODEL = "deepseek-chat"
    }
}

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

data class LlmMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    /** DeepSeek thinking models expect their reasoning back within the same tool-calling turn. */
    val reasoning: String? = null,
)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ReasoningDelta(val text: String) : LlmEvent
    data class Completed(
        val text: String,
        val toolCalls: List<ToolCallRequest>,
        val finishReason: String?,
        val reasoning: String = "",
        val usage: TokenUsage? = null,
    ) : LlmEvent
}

data class TokenUsage(val promptTokens: Int?, val completionTokens: Int?, val cachedTokens: Int?)

/** Streaming chat completion. Cancelling the collecting coroutine aborts the HTTP call. */
interface LlmClient {
    fun stream(config: ProviderConfig, request: LlmRequest): Flow<LlmEvent>
}

class LlmException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NOT_CONFIGURED, AUTH, BALANCE, RATE_LIMIT, BAD_REQUEST, SERVER, NETWORK, PROTOCOL }
}

internal val LlmJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

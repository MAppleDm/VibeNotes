package io.github.mappledm.vibenotes.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Chat Completions client for any OpenAI-compatible API (DeepSeek, OpenAI, OpenRouter,
 * local servers...). Uses server-sent events for streaming and assembles tool-call deltas.
 *
 * The API key is only placed in the Authorization header; it is never logged.
 */
class OpenAiCompatibleClient(
    private val http: OkHttpClient = defaultHttpClient(),
) : LlmClient {

    override fun stream(config: ProviderConfig, request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        if (config.apiKey.isBlank()) throw LlmException(LlmException.Kind.NOT_CONFIGURED, "API-ключ не задан")
        val body = encodeRequest(config, request, stream = true)
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val call = http.newCall(httpRequest)

        // Blocking network reads run in a child; cancelling the collector cancels the call,
        // which unblocks the read immediately.
        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw httpError(resp)
                    val source = resp.body?.source() ?: throw LlmException(LlmException.Kind.PROTOCOL, "Пустой ответ сервера")
                    val assembler = StreamAssembler()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        for (event in assembler.accept(data)) send(event)
                    }
                    send(assembler.complete())
                }
                close()
            } catch (e: LlmException) {
                close(e)
            } catch (e: IOException) {
                if (call.isCanceled()) close() else close(
                    LlmException(LlmException.Kind.NETWORK, "Нет соединения с сервером AI: ${e.message ?: e.javaClass.simpleName}", e),
                )
            }
        }
        awaitClose { call.cancel() }
    }

    private fun httpError(resp: Response): LlmException {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val serverMessage = runCatching {
            LlmJson.parseToJsonElement(raw).jsonObject["error"]?.let { err ->
                (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (err as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull() ?: raw.take(200)
        val detail = if (serverMessage.isBlank()) "" else ": $serverMessage"
        return when (resp.code) {
            401, 403 -> LlmException(LlmException.Kind.AUTH, "Ключ API не принят (${resp.code})$detail")
            402 -> LlmException(LlmException.Kind.BALANCE, "Недостаточно средств на счёте провайдера$detail")
            404 -> LlmException(LlmException.Kind.BAD_REQUEST, "Адрес API или модель не найдены (404)$detail")
            400, 422 -> LlmException(LlmException.Kind.BAD_REQUEST, "Запрос отклонён (${resp.code})$detail")
            429 -> LlmException(LlmException.Kind.RATE_LIMIT, "Слишком много запросов, попробуйте позже$detail")
            in 500..599 -> LlmException(LlmException.Kind.SERVER, "Сервер AI недоступен (${resp.code})$detail")
            else -> LlmException(LlmException.Kind.PROTOCOL, "Неожиданный ответ ${resp.code}$detail")
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun chatCompletionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        fun encodeRequest(config: ProviderConfig, request: LlmRequest, stream: Boolean): String {
            val json = buildJsonObject {
                put("model", config.model)
                put("stream", stream)
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("max_tokens", it) }
                put("messages", buildJsonArray { request.messages.forEach { add(encodeMessage(it)) } })
                if (request.tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        request.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                })
                            })
                        }
                    })
                }
            }
            return json.toString()
        }

        private fun encodeMessage(message: LlmMessage): JsonObject = buildJsonObject {
            put("role", message.role.name.lowercase())
            put("content", message.content)
            if (message.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            })
                        })
                    }
                })
            }
            message.toolCallId?.let { put("tool_call_id", it) }
            message.reasoning?.takeIf { it.isNotEmpty() }?.let { put("reasoning_content", it) }
        }
    }
}

/** Accumulates streamed deltas (text and tool calls split across many chunks). */
internal class StreamAssembler {
    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = sortedMapOf<Int, PartialCall>()
    private var finishReason: String? = null

    fun accept(data: String): List<LlmEvent> {
        val root = try {
            LlmJson.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            throw LlmException(LlmException.Kind.PROTOCOL, "Не удалось разобрать ответ сервера", e)
        }
        root["error"]?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw LlmException(LlmException.Kind.SERVER, "Ошибка провайдера: $msg")
        }
        val events = mutableListOf<LlmEvent>()
        val choices = root["choices"].asArray() ?: return events
        for (choice in choices) {
            val obj = choice as? JsonObject ?: continue
            obj["finish_reason"].asString()?.let { finishReason = it }
            val delta = obj["delta"] as? JsonObject ?: obj["message"] as? JsonObject ?: continue
            delta["reasoning_content"].asString()?.takeIf { it.isNotEmpty() }?.let {
                reasoning.append(it)
                events += LlmEvent.ReasoningDelta(it)
            }
            delta["content"].asString()?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                events += LlmEvent.TextDelta(it)
            }
            delta["tool_calls"].asArray()?.forEachIndexed { position, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: position
                val partial = calls.getOrPut(index) { PartialCall() }
                call["id"].asString()?.takeIf { it.isNotEmpty() }?.let { partial.id = it }
                val function = call["function"] as? JsonObject
                function?.get("name").asString()?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                function?.get("arguments").asString()?.let { partial.args.append(it) }
            }
        }
        return events
    }

    fun complete(): LlmEvent.Completed = LlmEvent.Completed(
        text = text.toString(),
        toolCalls = calls.entries.filter { it.value.name.isNotEmpty() }.map { (index, call) ->
            ToolCallRequest(
                id = call.id.ifEmpty { "call_$index" },
                name = call.name,
                argumentsJson = call.args.toString().ifBlank { "{}" },
            )
        },
        finishReason = finishReason,
        reasoning = reasoning.toString(),
    )

    private fun JsonElement?.asString(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private fun JsonElement?.asArray(): JsonArray? = when (this) {
        is JsonArray -> this
        else -> null
    }
}

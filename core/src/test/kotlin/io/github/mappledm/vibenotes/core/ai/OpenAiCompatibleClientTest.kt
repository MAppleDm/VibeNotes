package io.github.mappledm.vibenotes.core.ai

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer
    private val client = OpenAiCompatibleClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config() = ProviderConfig(server.url("/v1").toString(), "deepseek-chat", "sk-secret")

    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    @Test
    fun streamsTextAndSendsAuthHeader() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":"При"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"вет"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"prompt_cache_hit_tokens":0}}""",
            ),
        )
        val events = client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "hi")))).toList()
        assertEquals(listOf("При", "вет"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        val done = events.last() as LlmEvent.Completed
        assertEquals("Привет", done.text)
        assertEquals(12, done.usage?.promptTokens)
        assertEquals(3, done.usage?.completionTokens)
        assertEquals("stop", done.finishReason)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-secret", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content)
    }

    @Test
    fun assemblesToolCallsFromDeltas() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_calendar_event","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"title\":\"Врач\","}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"date\":\"2026-10-15\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val tools = listOf(ToolSpec("create_calendar_event", "d", buildJsonObject { }))
        val done = client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")), tools)).toList().last() as LlmEvent.Completed
        val call = done.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("create_calendar_event", call.name)
        assertEquals("""{"title":"Врач","date":"2026-10-15"}""", call.argumentsJson)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("create_calendar_event", body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsHttpErrorsToReadableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
        try {
            client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")))).first()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(LlmException.Kind.AUTH, e.kind)
            assertTrue(e.message!!.contains("Authentication Fails"))
            assertTrue(!e.message!!.contains("sk-secret"))
        }

        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient Balance"}}"""))
        try {
            client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")))).first()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(LlmException.Kind.BALANCE, e.kind)
        }
    }

    @Test
    fun networkFailureIsReported() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")))).toList()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(LlmException.Kind.NETWORK, e.kind)
        }
    }

    @Test
    fun missingKeyFailsWithoutRequest() = runBlocking {
        try {
            client.stream(config().copy(apiKey = " "), LlmRequest(emptyList())).toList()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(LlmException.Kind.NOT_CONFIGURED, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reasoningIsCollectedAndSentBackWithToolCalls() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"reasoning_content":"думаю"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"list_tasks","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val done = client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")))).toList().last() as LlmEvent.Completed
        assertEquals("думаю", done.reasoning)

        val body = OpenAiCompatibleClient.encodeRequest(
            config(),
            LlmRequest(listOf(LlmMessage(Role.ASSISTANT, "", done.toolCalls, reasoning = done.reasoning))),
            stream = true,
        )
        val message = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0].jsonObject
        assertEquals("думаю", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("list_tasks", message["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancellingTheCollectorAbortsAStalledStream() = runBlocking {
        // First chunk arrives, then the server stalls for a long time.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}\n\n" + "x".repeat(4096))
                .throttleBody(64, 5, java.util.concurrent.TimeUnit.SECONDS),
        )
        val started = System.currentTimeMillis()
        val first = kotlinx.coroutines.withTimeout(10_000) {
            client.stream(config(), LlmRequest(listOf(LlmMessage(Role.USER, "x")))).first()
        }
        assertEquals(LlmEvent.TextDelta("Hi"), first)
        // first() cancels the flow; the blocked read must be released promptly.
        assertTrue(System.currentTimeMillis() - started < 9_000)
    }

    @Test
    fun urlNormalisation() {
        assertEquals("https://api.deepseek.com/chat/completions", OpenAiCompatibleClient.chatCompletionsUrl("https://api.deepseek.com/"))
        assertEquals("https://x/v1/chat/completions", OpenAiCompatibleClient.chatCompletionsUrl("https://x/v1/chat/completions"))
    }

    @Test
    fun configToStringHidesKey() {
        assertTrue(!config().toString().contains("sk-secret"))
    }
}

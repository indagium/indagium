package com.openlog.ai

import com.openlog.model.defaultAiProviderProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    @Test
    fun streamsTextAndExplicitCompletion() = runBlocking {
        provider(
            sse(
                """
                {"choices":[{"index":0,"delta":{"content":"Hello"}}]}
                {"choices":[{"index":0,"delta":{"content":" world"}}]}
                [DONE]
                """,
            ),
        ).use { provider ->
            assertEquals(
                listOf(
                    LlmStreamEvent.TextDelta("Hello"),
                    LlmStreamEvent.TextDelta(" world"),
                    LlmStreamEvent.Completed,
                ),
                provider.streamChat(request()).toList(),
            )
        }
    }

    @Test
    fun assemblesInterleavedFragmentedToolCallsByIndex() = runBlocking {
        provider(
            sse(
                """
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_filter","function":{"name":"set_filter","arguments":"{\"tag\":"}},{"index":1,"id":"call_note","function":{"name":"create_note","arguments":"{\"text\":"}}]}}]}
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Crash\"}"}},{"index":1,"function":{"arguments":"\"Investigate\"}"}}]}}]}
                [DONE]
                """,
            ),
        ).use { provider ->
            val events = provider.streamChat(request()).toList()
            assertEquals(4, events.filterIsInstance<LlmStreamEvent.ToolCallDelta>().size)
            assertEquals(
                listOf(
                    LlmToolCall("call_filter", "set_filter", "{\"tag\":\"Crash\"}"),
                    LlmToolCall("call_note", "create_note", "{\"text\":\"Investigate\"}"),
                ),
                events.filterIsInstance<LlmStreamEvent.ToolCall>().map { it.call },
            )
            assertEquals(LlmStreamEvent.Completed, events.last())
        }
    }

    @Test
    fun skipsMalformedSseEventAndContinues() = runBlocking {
        provider(
            sse(
                """
                this-is-not-json
                {"choices":[{"index":0,"delta":{"content":"usable"}}]}
                [DONE]
                """,
            ),
        ).use { provider ->
            val events = provider.streamChat(request()).toList()
            assertIs<LlmStreamEvent.Warning>(events.first())
            assertEquals(LlmStreamEvent.TextDelta("usable"), events[1])
            assertEquals(LlmStreamEvent.Completed, events.last())
        }
    }

    @Test
    fun reportsUnavailableEndpointWithoutThrowing() = runBlocking {
        provider("not available", HttpStatusCode.ServiceUnavailable).use { provider ->
            val events = provider.streamChat(request()).toList()
            assertEquals(listOf(LlmStreamEvent.Error("Provider request failed (HTTP 503).")), events)
        }
    }

    @Test
    fun sendsOpenAiCompatibleChatCompletionRequest() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(sse("[DONE]"), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }
        OpenAiCompatibleProvider(
            profile = defaultAiProviderProfile().copy(baseUrl = "http://provider.test/v1/"),
            apiKey = "session-only-key",
            httpClient = client,
        ).use { provider ->
            provider.streamChat(request()).toList()
        }

        val request = requireNotNull(capturedRequest)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer session-only-key", request.headers[HttpHeaders.Authorization])
        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertTrue(payload["stream"]!!.jsonPrimitive.boolean)
        assertTrue(payload["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("local-model", payload["model"]!!.jsonPrimitive.content)
        val function = payload["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("function", payload["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("set_filter", function["name"]!!.jsonPrimitive.content)
        assertEquals("Changes a log filter", function["description"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { }, function["parameters"]!!.jsonObject)
    }

    @Test
    fun pathlessBaseUrlIsTreatedAsItsV1Base() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(sse("[DONE]"), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }
        // LM Studio's own UI shows its "Reachable at" address as a bare http://host:port with no
        // path; a request against that address verbatim would hit /chat/completions at the root.
        OpenAiCompatibleProvider(
            profile = defaultAiProviderProfile().copy(baseUrl = "http://192.168.0.189:1234"),
            httpClient = client,
        ).use { provider ->
            provider.streamChat(request()).toList()
        }
        assertEquals("/v1/chat/completions", requireNotNull(capturedRequest).url.encodedPath)
    }

    @Test
    fun finalUsageOnlyChunkIsParsedDespiteItsEmptyChoicesArray() = runBlocking {
        provider(
            sse(
                """
                {"choices":[{"index":0,"delta":{"content":"Answer"}}]}
                {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":34,"total_tokens":154}}
                [DONE]
                """,
            ),
        ).use { provider ->
            assertEquals(
                listOf(
                    LlmStreamEvent.TextDelta("Answer"),
                    LlmStreamEvent.Usage(promptTokens = 120, completionTokens = 34, totalTokens = 154),
                    LlmStreamEvent.Completed,
                ),
                provider.streamChat(request()).toList(),
            )
        }
    }

    @Test
    fun cancellationAfterFirstDeltaDoesNotEmitCompletion() = runBlocking {
        provider(
            sse(
                """
                {"choices":[{"index":0,"delta":{"content":"first"}}]}
                [DONE]
                """,
            ),
        ).use { provider ->
            assertEquals(
                listOf(LlmStreamEvent.TextDelta("first")),
                provider.streamChat(request()).take(1).toList(),
            )
        }
    }

    @Test
    fun discoversModelsAndGracefullyHandlesUnsupportedEndpoint() {
        runBlocking {
            provider("{\"data\":[{\"id\":\"local-a\"},{\"id\":\"local-b\",\"name\":\"Local B\"}]}").use { provider ->
                assertEquals(
                    ModelDiscoveryResult.Available(listOf(LlmModel("local-a"), LlmModel("local-b", "Local B"))),
                    provider.listModels(),
                )
            }
            provider("missing", HttpStatusCode.NotFound).use { provider ->
                assertIs<ModelDiscoveryResult.Unavailable>(provider.listModels())
            }
        }
    }

    private fun provider(body: String, status: HttpStatusCode = HttpStatusCode.OK): OpenAiCompatibleProvider {
        val client = HttpClient(MockEngine { respond(body, status, headersOf("Content-Type", "text/event-stream")) }) {
            expectSuccess = false
        }
        return OpenAiCompatibleProvider(
            profile = defaultAiProviderProfile().copy(baseUrl = "http://provider.test/v1"),
            apiKey = "session-only-key",
            httpClient = client,
        )
    }

    private fun sse(frames: String): String = frames.trimIndent().lineSequence()
        .filter { it.isNotBlank() }
        .joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    private fun request() = LlmRequest(
        model = "local-model",
        messages = listOf(LlmMessage(LlmRole.USER, "Analyze this log line")),
        tools = listOf(
            LlmToolDefinition(
                name = "set_filter",
                description = "Changes a log filter",
                parameters = buildJsonObject { },
            ),
        ),
    )
}

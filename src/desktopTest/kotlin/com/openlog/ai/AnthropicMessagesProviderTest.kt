package com.openlog.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicMessagesProviderTest {
    @Test
    fun sendsNativeMessagesRequestWithSystemToolsAndToolResult() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(sse("message_stop {}"), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }

        AnthropicMessagesProvider(
            baseUrl = "http://provider.test/v1/",
            apiKey = "anthropic-session-key",
            httpClient = client,
        ).use { provider ->
            provider.streamChat(request()).toList()
        }

        val request = requireNotNull(capturedRequest)
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("anthropic-session-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals(ContentType.Text.EventStream.toString(), request.headers[HttpHeaders.Accept])

        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals("claude-3-7-sonnet", payload["model"]!!.jsonPrimitive.content)
        assertEquals(2048, payload["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(payload["stream"]!!.jsonPrimitive.boolean)
        assertEquals("Use the log context.", payload["system"]!!.jsonPrimitive.content)

        val messages = payload["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Inspect this crash.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val assistantBlock = messages[1].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_use", assistantBlock["type"]!!.jsonPrimitive.content)
        assertEquals("call-1", assistantBlock["id"]!!.jsonPrimitive.content)
        assertEquals("lookup_source", assistantBlock["name"]!!.jsonPrimitive.content)
        assertEquals("Crash", assistantBlock["input"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        val result = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("call-1", result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("source result", result["content"]!!.jsonPrimitive.content)

        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("lookup_source", tool["name"]!!.jsonPrimitive.content)
        assertEquals("Find source", tool["description"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { }, tool["input_schema"]!!.jsonObject)
    }

    @Test
    fun streamsTextUsageAndCompletion() = runBlocking {
        val events = provider(
            sse(
                """
                message_start {"message":{"usage":{"input_tokens":12}}}
                content_block_start {"index":0,"content_block":{"type":"text","text":""}}
                content_block_delta {"index":0,"delta":{"type":"text_delta","text":"Hello"}}
                content_block_delta {"index":0,"delta":{"type":"text_delta","text":" world"}}
                message_delta {"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}
                message_stop {}
                """.trimIndent(),
            ),
        ).use { it.streamChat(request()).toList() }

        assertEquals(
            listOf(
                LlmStreamEvent.TextDelta("Hello"),
                LlmStreamEvent.TextDelta(" world"),
                LlmStreamEvent.Usage(promptTokens = 12, completionTokens = 7, totalTokens = 19),
                LlmStreamEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun assemblesToolUseInputAcrossAnthropicBlocks() = runBlocking {
        val events = provider(
            sse(
                """
                content_block_start {"index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"lookup_source","input":{}}}
                content_block_delta {"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"tag\":"}}
                content_block_delta {"index":0,"delta":{"type":"input_json_delta","partial_json":"\"Crash\"}"}}
                content_block_stop {"index":0}
                message_stop {}
                """.trimIndent(),
            ),
        ).use { it.streamChat(request()).toList() }

        assertEquals(
            listOf(
                LlmStreamEvent.ToolCallDelta(index = 0, id = "tool-1", name = "lookup_source"),
                LlmStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "{\"tag\":"),
                LlmStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "\"Crash\"}"),
                LlmStreamEvent.ToolCall(LlmToolCall("tool-1", "lookup_source", "{\"tag\":\"Crash\"}")),
                LlmStreamEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun malformedFrameWarnsAndProviderErrorStopsStream() = runBlocking {
        val events = provider(
            sse(
                """
                not-json
                content_block_delta {"index":0,"delta":{"type":"text_delta","text":"usable"}}
                error {"error":{"type":"invalid_request_error","message":"bad request"}}
                """.trimIndent(),
            ),
        ).use { it.streamChat(request()).toList() }

        assertIs<LlmStreamEvent.Warning>(events[0])
        assertEquals(LlmStreamEvent.TextDelta("usable"), events[1])
        assertEquals(LlmStreamEvent.Error("bad request"), events[2])
        assertEquals(3, events.size)
    }

    @Test
    fun reportsHttpFailureAndListsModelsWhenReachable() = runBlocking {
        provider("unavailable", HttpStatusCode.Unauthorized).use { provider ->
            assertEquals(
                listOf(LlmStreamEvent.Error("Provider request failed (HTTP 401).")),
                provider.streamChat(request()).toList(),
            )
            // A failing /v1/models response degrades to Unavailable rather than throwing.
            assertIs<ModelDiscoveryResult.Unavailable>(provider.listModels())
            assertEquals(true, provider.capabilities.modelDiscovery)
        }
    }

    @Test
    fun discoversModelsFromModelsEndpointWithThinkingEfforts() = runBlocking {
        var capturedPath: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                """{"data":[
                    {"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
                    {"type":"model","id":"claude-3-5-haiku-latest","display_name":"Claude Haiku 3.5"}
                ]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }) { expectSuccess = false }

        AnthropicMessagesProvider(baseUrl = "https://api.anthropic.com", apiKey = "k", httpClient = client).use { provider ->
            val result = provider.listModels()
            assertEquals("/v1/models", capturedPath)
            val available = assertIs<ModelDiscoveryResult.Available>(result)
            assertEquals(listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest"), available.models.map { it.id })
            // Thinking-capable Claude 4 model advertises efforts; the 3.5 model does not.
            assertEquals(listOf("low", "medium", "high"), available.models[0].reasoningEfforts)
            assertEquals(emptyList(), available.models[1].reasoningEfforts)
        }
    }

    @Test
    fun enablesThinkingBlockWhenReasoningEffortSet() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(sse("message_stop {}"), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }

        AnthropicMessagesProvider(baseUrl = "https://api.anthropic.com", apiKey = "k", httpClient = client).use { provider ->
            provider.streamChat(request().copy(model = "claude-sonnet-4-5", reasoningEffort = "high", temperature = 0.2)).toList()
        }

        val payload = Json.parseToJsonElement((requireNotNull(capturedRequest).body as TextContent).text).jsonObject
        val thinking = payload["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(24576, thinking["budget_tokens"]!!.jsonPrimitive.int)
        // max_tokens must exceed the thinking budget, and temperature is dropped for thinking.
        assertTrue(payload["max_tokens"]!!.jsonPrimitive.int > 24576)
        assertEquals(null, payload["temperature"])
    }

    @Test
    fun replaysThinkingBlocksVerbatimOnToolUseTurn() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(sse("message_stop {}"), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }

        val conversation = LlmRequest(
            model = "claude-sonnet-4-5",
            reasoningEffort = "medium",
            messages = listOf(
                LlmMessage(LlmRole.USER, "Inspect this crash."),
                LlmMessage(
                    role = LlmRole.ASSISTANT,
                    reasoning = listOf(LlmReasoning(thinking = "Let me check the tags.", signature = "sig-123")),
                    toolCalls = listOf(LlmToolCall("call-1", "lookup_source", "{}")),
                ),
                LlmMessage(LlmRole.TOOL, "result", toolCallId = "call-1"),
            ),
        )
        AnthropicMessagesProvider(baseUrl = "https://api.anthropic.com", apiKey = "k", httpClient = client).use {
            it.streamChat(conversation).toList()
        }

        val payload = Json.parseToJsonElement((requireNotNull(capturedRequest).body as TextContent).text).jsonObject
        val assistantContent = payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val thinkingBlock = assistantContent[0].jsonObject
        assertEquals("thinking", thinkingBlock["type"]!!.jsonPrimitive.content)
        assertEquals("Let me check the tags.", thinkingBlock["thinking"]!!.jsonPrimitive.content)
        assertEquals("sig-123", thinkingBlock["signature"]!!.jsonPrimitive.content)
        assertEquals("tool_use", assistantContent[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun emitsReasoningCompleteFromThinkingStream() = runBlocking {
        val events = provider(
            sse(
                """
                content_block_start {"index":0,"content_block":{"type":"thinking","thinking":""}}
                content_block_delta {"index":0,"delta":{"type":"thinking_delta","thinking":"Step one."}}
                content_block_delta {"index":0,"delta":{"type":"signature_delta","signature":"sig-xyz"}}
                content_block_stop {"index":0}
                message_stop {}
                """.trimIndent(),
            ),
        ).use { it.streamChat(request()).toList() }

        assertEquals(
            listOf(
                LlmStreamEvent.ReasoningComplete(LlmReasoning(thinking = "Step one.", signature = "sig-xyz")),
                LlmStreamEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun cancellationAfterFirstDeltaDoesNotEmitCompletion() = runBlocking {
        provider(
            sse(
                """
                content_block_delta {"index":0,"delta":{"type":"text_delta","text":"first"}}
                message_stop {}
                """.trimIndent(),
            ),
        ).use { provider ->
            assertEquals(
                listOf(LlmStreamEvent.TextDelta("first")),
                provider.streamChat(request()).take(1).toList(),
            )
        }
    }

    private fun provider(body: String, status: HttpStatusCode = HttpStatusCode.OK): AnthropicMessagesProvider {
        val client = HttpClient(MockEngine {
            respond(body, status, headersOf("Content-Type", "text/event-stream"))
        }) { expectSuccess = false }
        return AnthropicMessagesProvider(
            baseUrl = "http://provider.test/v1",
            apiKey = "anthropic-session-key",
            httpClient = client,
        )
    }

    private fun sse(events: String): String = events.lineSequence()
        .filter { it.isNotBlank() }
        .joinToString("\n\n", postfix = "\n\n") { line ->
            val firstSpace = line.indexOf(' ')
            if (firstSpace < 0) {
                "data: $line"
            } else {
                val eventType = line.substring(0, firstSpace)
                val payload = Json.parseToJsonElement(line.substring(firstSpace + 1)).jsonObject
                val withType = buildJsonObject {
                    put("type", JsonPrimitive(eventType))
                    payload.forEach { (key, value) -> put(key, value) }
                }
                "data: $withType"
            }
        }

    private fun request() = LlmRequest(
        model = "claude-3-7-sonnet",
        messages = listOf(
            LlmMessage(LlmRole.SYSTEM, "Use the log context."),
            LlmMessage(LlmRole.USER, "Inspect this crash."),
            LlmMessage(
                role = LlmRole.ASSISTANT,
                toolCalls = listOf(LlmToolCall("call-1", "lookup_source", "{\"tag\":\"Crash\"}")),
            ),
            LlmMessage(LlmRole.TOOL, "source result", toolCallId = "call-1"),
        ),
        tools = listOf(
            LlmToolDefinition(
                name = "lookup_source",
                description = "Find source",
                parameters = buildJsonObject { },
            ),
        ),
        maxTokens = 2048,
    )
}

package com.openlog.ai

import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexAppServerClientTest {
    @Test
    fun hostRequestsWithAnIdAreNotMistakenForResponses() = runBlocking {
        lateinit var process: FakeProcess
        var elicitationReply: JsonObject? = null
        process = FakeProcess { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            if (message["method"] == null && message["id"]?.jsonPrimitive?.content == "0") {
                elicitationReply = message
            }
        }

        CodexAppServerClient(process).use { client ->
            process.notify(
                """{"id":0,"method":"mcpServer/elicitation/request","params":{"message":"Authorization required"}}""",
            )
            withTimeout(2_000) {
                while (client.events.replayCache.none { it is CodexAppServerEvent.ServerRequest }) {
                    kotlinx.coroutines.yield()
                }
            }
            val request = assertIs<CodexAppServerEvent.ServerRequest>(
                client.events.replayCache.filterIsInstance<CodexAppServerEvent.ServerRequest>().single(),
            )
            assertEquals(0L, request.id)
            assertEquals("mcpServer/elicitation/request", request.method)
            assertTrue(client.events.replayCache.none { it is CodexAppServerEvent.ProtocolError })

            client.resolveServerRequest(request.id, buildJsonObject {
                put("action", "cancel")
                put("content", JsonNull)
                put("_meta", JsonNull)
            })
            withTimeout(2_000) {
                while (elicitationReply == null) kotlinx.coroutines.yield()
            }
            assertEquals("cancel", elicitationReply!!["result"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun initializesStartsAndStreamsTurnLifecycle() = runBlocking {
        lateinit var process: FakeProcess
        process = FakeProcess { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            when (message["method"]?.jsonPrimitive?.content) {
                "initialize" -> {
                    process.respond("""{"id":${message["id"]},"result":{"serverInfo":{"name":"codex","version":"1"}}}""")
                }
                "initialized" -> Unit
                "thread/start" -> {
                    process.respond("""{"id":${message["id"]},"result":{"thread":{"id":"thread-1","status":{"type":"idle"},"cwd":"/tmp/project"}}}""")
                    process.notify("""{"method":"thread/started","params":{"thread":{"id":"thread-1","status":{"type":"idle"},"cwd":"/tmp/project"}}}""")
                }
                "thread/resume" -> process.respond("""{"id":${message["id"]},"result":{"thread":{"id":"thread-1","status":{"type":"idle"}}}}""")
                "turn/start" -> {
                    val input = message["params"]!!.jsonObject["input"]!!.jsonArray
                    assertEquals("text", input[0].jsonObject["type"]!!.jsonPrimitive.content)
                    assertEquals("Explain this crash", input[0].jsonObject["text"]!!.jsonPrimitive.content)
                    assertEquals("high", message["params"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                    process.respond("""{"id":${message["id"]},"result":{"turn":{"id":"turn-1","threadId":"thread-1","status":{"type":"inProgress"}}}}""")
                    process.notify("""{"method":"turn/started","params":{"turn":{"id":"turn-1","threadId":"thread-1","status":{"type":"inProgress"}}}}""")
                    process.notify("""{"method":"mcpServer/startupStatus/updated","params":{"name":"openlog","status":"ready","error":null}}""")
                    process.notify("""{"method":"item/mcpToolCall/progress","params":{"threadId":"thread-1","message":"Calling get_issue_description"}}""")
                    process.notify("""{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"It starts with "}}""")
                    process.notify("""{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"the parser."}}""")
                    process.notify("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":{"type":"completed"}}}}""")
                }
                "turn/interrupt" -> process.respond("""{"id":${message["id"]},"result":{}}""")
            }
        }

        CodexAppServerClient(process).use { client ->
            val initialize = client.initialize(CodexClientInfo("test-client", "Test", "1"))
            val thread = client.startThread(CodexThreadOptions(cwd = "/tmp/project"))
            val resumed = client.resumeThread(thread.id)
            val turn = client.startTurn(thread.id, "Explain this crash", effort = "high")
            client.interruptTurn(thread.id, turn.id)

            assertEquals("codex", initialize.serverInfo!!.string("name"))
            assertEquals("thread-1", thread.id)
            assertEquals(thread.id, resumed.id)
            assertEquals("turn-1", turn.id)

            withTimeout(2_000) {
                while (client.events.replayCache.none { it is CodexAppServerEvent.TurnCompleted }) {
                    kotlinx.coroutines.yield()
                }
            }
            val events = client.events.replayCache
            assertTrue(events.any { it is CodexAppServerEvent.Initialized })
            assertTrue(events.any { it is CodexAppServerEvent.ThreadStarted && it.thread.id == "thread-1" })
            assertTrue(events.any { it is CodexAppServerEvent.TurnStarted && it.turn.id == "turn-1" })
            assertTrue(events.any { it is CodexAppServerEvent.McpServerStartupStatus && it.serverName == "openlog" && it.status == "ready" })
            assertTrue(events.any { it is CodexAppServerEvent.McpToolProgress && it.message == "Calling get_issue_description" })
            assertEquals(
                "It starts with the parser.",
                events.filterIsInstance<CodexAppServerEvent.AgentTextDelta>().joinToString("") { it.text },
            )
            assertTrue(events.any { it is CodexAppServerEvent.TurnCompleted && it.turnId == "turn-1" })
        }

        assertEquals(
            listOf("initialize", "initialized", "thread/start", "thread/resume", "turn/start", "turn/interrupt"),
            process.sentMethods,
        )
    }

    @Test
    fun failedTurnIsExposedAsTypedFailureEvent() = runBlocking {
        lateinit var process: FakeProcess
        process = FakeProcess { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            when (message["method"]?.jsonPrimitive?.content) {
                "initialize" -> process.respond("""{"id":${message["id"]},"result":{}}""")
                "thread/start" -> process.respond("""{"id":${message["id"]},"result":{"thread":{"id":"thread-1"}}}""")
                "turn/start" -> {
                    process.respond("""{"id":${message["id"]},"result":{"turn":{"id":"turn-1","threadId":"thread-1"}}}""")
                    process.notify(
                        """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":{"type":"failed","error":{"message":"upstream unavailable"}}}}}""",
                    )
                }
            }
        }

        CodexAppServerClient(process).use { client ->
            client.initialize()
            val thread = client.startThread()
            client.startTurn(thread.id, "hello")
            withTimeout(2_000) {
                while (client.events.replayCache.none { it is CodexAppServerEvent.TurnFailed }) {
                    kotlinx.coroutines.yield()
                }
            }
            val failure = assertIs<CodexAppServerEvent.TurnFailed>(
                client.events.replayCache.filterIsInstance<CodexAppServerEvent.TurnFailed>().single(),
            )
            assertEquals("upstream unavailable", failure.message)
        }
    }

    @Test
    fun tokenUsageUpdatedNotificationParsesTheCumulativeTotalBreakdown() = runBlocking {
        lateinit var process: FakeProcess
        process = FakeProcess { }

        CodexAppServerClient(process).use { client ->
            process.notify(
                """
                {"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1",
                  "tokenUsage":{
                    "last":{"inputTokens":10,"cachedInputTokens":0,"outputTokens":5,"reasoningOutputTokens":0,"totalTokens":15},
                    "total":{"inputTokens":120,"cachedInputTokens":40,"outputTokens":30,"reasoningOutputTokens":8,"totalTokens":150},
                    "modelContextWindow":200000
                  }}}
                """.trimIndent(),
            )
            withTimeout(2_000) {
                while (client.events.replayCache.none { it is CodexAppServerEvent.TokenUsageUpdated }) {
                    kotlinx.coroutines.yield()
                }
            }
            val event = assertIs<CodexAppServerEvent.TokenUsageUpdated>(
                client.events.replayCache.filterIsInstance<CodexAppServerEvent.TokenUsageUpdated>().single(),
            )
            assertEquals("thread-1", event.threadId)
            assertEquals("turn-1", event.turnId)
            // The cumulative "total" breakdown is what callers use, not the per-notification "last".
            assertEquals(120L, event.total.inputTokens)
            assertEquals(40L, event.total.cachedInputTokens)
            assertEquals(30L, event.total.outputTokens)
            assertEquals(8L, event.total.reasoningOutputTokens)
            assertEquals(150L, event.total.totalTokens)
        }
    }

    @Test
    fun requestErrorsAreRaisedWithJsonRpcDetails() = runBlocking {
        lateinit var process: FakeProcess
        process = FakeProcess { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            if (message["method"]?.jsonPrimitive?.content == "initialize") {
                process.respond(
                    """{"id":${message["id"]},"error":{"code":-32600,"message":"bad initialize","data":{"field":"clientInfo"}}}""",
                )
            }
        }

        CodexAppServerClient(process).use { client ->
            val error = kotlin.test.assertFailsWith<CodexAppServerException> { client.initialize() }
            assertEquals(-32600, error.errorCode)
            assertEquals("bad initialize", error.message)
            assertEquals("clientInfo", error.errorData!!.jsonObject["field"]!!.jsonPrimitive.content)
        }
    }

    private class FakeProcess(
        private val onWrite: (String) -> Unit,
    ) : CodexAppServerProcess {
        private val output = LinkedBlockingQueue<String>()
        private val writes = mutableListOf<String>()
        @Volatile private var alive = true

        val sentMethods: List<String>
            get() = synchronized(writes) {
                writes.mapNotNull {
                    Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content
                }
            }

        override fun writeLine(line: String) {
            synchronized(writes) { writes += line }
            onWrite(line)
        }

        override fun readLine(): String? {
            while (alive) {
                val line = output.take()
                if (line == EOF) return null
                return line
            }
            return null
        }

        override fun isAlive(): Boolean = alive

        override fun exitCode(): Int? = if (alive) null else 0

        override fun destroy() {
            if (!alive) return
            alive = false
            output.offer(EOF)
        }

        fun respond(json: String) = output.put(json)

        fun notify(json: String) = output.put(json)

        companion object {
            private const val EOF = "__EOF__"
        }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
}

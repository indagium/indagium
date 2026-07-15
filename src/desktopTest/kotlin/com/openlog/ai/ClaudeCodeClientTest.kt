package com.openlog.ai

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCodeClientTest {
    @Test
    fun buildsPrintModeCommandWithGeneratedMcpConfig() {
        val request = ClaudeCodeRequest(
            prompt = "Explain the selected crash",
            mcpServers = linkedMapOf(
                "openlog" to ClaudeCodeMcpServer(
                    url = "http://127.0.0.1:43123/mcp",
                    headers = mapOf("Authorization" to "Bearer test-token"),
                ),
            ),
            sessionId = "session-1",
            model = "sonnet",
            maxTurns = 3,
        )

        val command = ClaudeCodeClient.buildCommand(request, executable = "/opt/claude")
        assertEquals("/opt/claude", command.first())
        assertTrue(command.containsAll(listOf("--print", "--output-format", "stream-json")))
        assertTrue(command.containsAll(listOf("--verbose", "--include-partial-messages", "--mcp-config")))
        assertTrue(command.containsAll(listOf("--model", "sonnet", "--max-turns", "3", "--resume", "session-1")))
        assertEquals(request.prompt, command.last())

        val config = Json.parseToJsonElement(command[command.indexOf("--mcp-config") + 1]).jsonObject
        val openLog = config["mcpServers"]!!.jsonObject["openlog"]!!.jsonObject
        assertEquals("http", openLog["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:43123/mcp", openLog["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer test-token", openLog["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildCommandAlwaysRestrictsToTheManagedOpenlogMcpToolsWithoutPrompting() {
        // Without these, Claude Code defaults to interactive per-tool approval, which it cannot
        // satisfy in --print mode - it then answers in prose asking the user to grant permissions
        // instead of calling any tool at all.
        val command = ClaudeCodeClient.buildCommand(ClaudeCodeRequest(prompt = "hi"))

        assertTrue("--strict-mcp-config" in command)
        val toolsIndex = command.indexOf("--tools")
        assertTrue(toolsIndex >= 0)
        assertEquals("", command[toolsIndex + 1])
        val permissionModeIndex = command.indexOf("--permission-mode")
        assertTrue(permissionModeIndex >= 0)
        assertEquals("bypassPermissions", command[permissionModeIndex + 1])
    }

    @Test
    fun buildCommandAddsEffortFlagOnlyWhenPresent() {
        val withEffort = ClaudeCodeClient.buildCommand(ClaudeCodeRequest(prompt = "hi", effort = "high"))
        val effortIndex = withEffort.indexOf("--effort")
        assertTrue(effortIndex >= 0)
        assertEquals("high", withEffort[effortIndex + 1])

        val withoutEffort = ClaudeCodeClient.buildCommand(ClaudeCodeRequest(prompt = "hi", effort = null))
        assertTrue("--effort" !in withoutEffort)

        val blankEffort = ClaudeCodeClient.buildCommand(ClaudeCodeRequest(prompt = "hi", effort = "  "))
        assertTrue("--effort" !in blankEffort)
    }

    @Test
    fun streamsTextAndFinalSessionFromFakeProcess() = runBlocking {
        val process = FakeProcess(
            stdoutText = """
                {"type":"system","subtype":"init","session_id":"session-42"}
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}}
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":" world"}}}
                {"type":"result","subtype":"success","result":"Hello world","session_id":"session-42"}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        assertEquals(
            listOf(
                ClaudeCodeEvent.SessionId("session-42"),
                ClaudeCodeEvent.TextDelta("Hello"),
                ClaudeCodeEvent.TextDelta(" world"),
                ClaudeCodeEvent.Final("Hello world", "session-42"),
            ),
            events,
        )
    }

    @Test
    fun parsesCompleteAssistantFallbackAndStructuredClaudeError() = runBlocking {
        val process = FakeProcess(
            stdoutText = """
                {"type":"system","subtype":"init","session_id":"session-7"}
                {"type":"assistant","message":{"content":[{"type":"text","text":"The answer"}]}}
                {"type":"result","subtype":"error_during_execution","is_error":true,"result":"tool failed","session_id":"session-7"}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()
        assertEquals(ClaudeCodeEvent.SessionId("session-7"), events[0])
        assertEquals(ClaudeCodeEvent.TextDelta("The answer"), events[1])
        // A top-level "assistant" line always closes out a turn, whether its text came from here
        // (the non-partial-streaming fallback) or was already streamed via prior deltas.
        assertEquals(ClaudeCodeEvent.TurnBoundary, events[2])
        val error = assertIs<ClaudeCodeEvent.Error>(events[3])
        assertEquals(ClaudeCodeErrorKind.ClaudeCode, error.kind)
        assertEquals("tool failed", error.message)
        assertEquals("session-7", error.sessionId)
    }

    @Test
    fun errorsArrayIsUsedWhenNoErrorOrMessageFieldIsPresent() = runBlocking {
        // Real shape from a failed --resume: no top-level "error"/"message" field, just "errors".
        // Without checking it, the real reason ("No conversation found...") was silently replaced
        // by a generic fallback, which is what made this bug hard to diagnose in the first place.
        val process = FakeProcess(
            stdoutText = """
                {"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"session-9","errors":["No conversation found with session ID: session-9"]}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        val error = assertIs<ClaudeCodeEvent.Error>(events.last())
        assertEquals("No conversation found with session ID: session-9", error.message)
    }

    @Test
    fun turnBoundaryFiresOncePerCompleteAssistantMessageAcrossToolCalls() = runBlocking {
        // Shape of a real multi-turn run: interim narration, a tool call/result round-trip (the
        // "user" tool-result line in between is not parsed into any event), then a final turn.
        // This is the exact scenario that used to glue every turn's text into one Assistant answer
        // with no separator between them - see the fix in AccountAgentRunner.runClaude.
        val process = FakeProcess(
            stdoutText = """
                {"type":"system","subtype":"init","session_id":"session-9"}
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Let me check the logs."}}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"Let me check the logs."},{"type":"tool_use","id":"t1","name":"get_tags","input":{}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"The root cause is X."}}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"The root cause is X."}]}}
                {"type":"result","subtype":"success","result":"The root cause is X.","session_id":"session-9"}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        assertEquals(
            listOf(
                ClaudeCodeEvent.SessionId("session-9"),
                ClaudeCodeEvent.TextDelta("Let me check the logs."),
                ClaudeCodeEvent.TurnBoundary,
                ClaudeCodeEvent.TextDelta("The root cause is X."),
                ClaudeCodeEvent.TurnBoundary,
                ClaudeCodeEvent.Final("The root cause is X.", "session-9"),
            ),
            events,
        )
    }

    @Test
    fun finalResultCarriesUsageWhenTheCliReportsIt() = runBlocking {
        val process = FakeProcess(
            stdoutText = """
                {"type":"system","subtype":"init","session_id":"session-11"}
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Done."}}}
                {"type":"result","subtype":"success","result":"Done.","session_id":"session-11","usage":{"input_tokens":120,"output_tokens":30,"cache_creation_input_tokens":40,"cache_read_input_tokens":5}}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        val final = assertIs<ClaudeCodeEvent.Final>(events.last())
        val usage = requireNotNull(final.usage)
        assertEquals(120, usage.inputTokens)
        assertEquals(30, usage.outputTokens)
        assertEquals(40, usage.cacheCreationInputTokens)
        assertEquals(5, usage.cacheReadInputTokens)
    }

    @Test
    fun finalResultUsageIsNullWhenTheCliOmitsIt() = runBlocking {
        val process = FakeProcess(
            stdoutText = """
                {"type":"system","subtype":"init","session_id":"session-12"}
                {"type":"result","subtype":"success","result":"Done.","session_id":"session-12"}
            """.trimIndent(),
        )

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        val final = assertIs<ClaudeCodeEvent.Final>(events.last())
        assertEquals(null, final.usage)
    }

    @Test
    fun reportsBinaryNotFoundWithoutLaunchingARealCli() = runBlocking {
        val factory = ClaudeCodeProcessFactory { _, _ -> throw ClaudeCodeBinaryNotFoundException("claude") }

        val events = ClaudeCodeClient(factory).stream(ClaudeCodeRequest("prompt")).toList()

        assertEquals(1, events.size)
        val error = assertIs<ClaudeCodeEvent.Error>(events.single())
        assertEquals(ClaudeCodeErrorKind.BinaryNotFound, error.kind)
        assertTrue(error.message.contains("claude"))
    }

    @Test
    fun reportsNonZeroProcessWithStderrWhenNoTerminalEventArrives() = runBlocking {
        val process = FakeProcess(stdoutText = "", stderrText = "authentication failed", exitCode = 2)

        val events = ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).toList()

        val error = assertIs<ClaudeCodeEvent.Error>(events.single())
        assertEquals(ClaudeCodeErrorKind.Process, error.kind)
        assertEquals("authentication failed", error.message)
        assertEquals(2, error.exitCode)
    }

    @Test
    fun cancellationForciblyDestroysBlockingFakeProcess() = runBlocking {
        val process = BlockingFakeProcess(
            firstLine = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"first\"}}}",
        )

        val events = withTimeout(2_000) {
            ClaudeCodeClient(FakeFactory(process)).stream(ClaudeCodeRequest("prompt")).take(1).toList()
        }

        assertEquals(listOf(ClaudeCodeEvent.TextDelta("first")), events)
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
        assertTrue(process.forceDestroyed)
    }

    private class FakeFactory(private val process: ClaudeCodeProcess) : ClaudeCodeProcessFactory {
        var command: List<String>? = null

        override fun start(command: List<String>, workingDirectory: Path?): ClaudeCodeProcess {
            this.command = command
            return process
        }
    }

    private open class FakeProcess(
        stdoutText: String,
        stderrText: String = "",
        private val exitCode: Int = 0,
    ) : ClaudeCodeProcess {
        override val stdout: InputStream = ByteArrayInputStream((stdoutText + "\n").toByteArray())
        override val stderr: InputStream = ByteArrayInputStream(stderrText.toByteArray())
        override var isAlive: Boolean = true
        var forceDestroyed = false

        override fun waitFor(): Int {
            isAlive = false
            return exitCode
        }

        override fun destroy() {
            isAlive = false
        }

        override fun destroyForcibly() {
            forceDestroyed = true
            isAlive = false
        }
    }

    private class BlockingFakeProcess(firstLine: String) : ClaudeCodeProcess {
        private val output = ReleasingInputStream((firstLine + "\n").toByteArray())
        override val stdout: InputStream = output
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        override var isAlive: Boolean = true
        var forceDestroyed = false
        val destroyed = CountDownLatch(1)

        override fun waitFor(): Int {
            isAlive = false
            return 0
        }

        override fun destroy() {
            isAlive = false
            output.close()
        }

        override fun destroyForcibly() {
            forceDestroyed = true
            isAlive = false
            output.close()
            destroyed.countDown()
        }
    }

    private class ReleasingInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        @Volatile private var released = false

        override fun read(): Int {
            while (index >= bytes.size && !released) Thread.sleep(5)
            return if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (index >= bytes.size && !released) Thread.sleep(5)
            if (index >= bytes.size) return -1
            val count = minOf(length, bytes.size - index)
            bytes.copyInto(target, offset, index, index + count)
            index += count
            return count
        }

        override fun close() {
            released = true
        }
    }
}
